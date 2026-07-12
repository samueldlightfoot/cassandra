/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.distributed.test;

import com.google.common.collect.ImmutableMap;

import com.codahale.metrics.Counter;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.concurrent.ShardExecutors;
import org.apache.cassandra.db.MutationShardRouting;
import org.apache.cassandra.distributed.Cluster;
import org.apache.cassandra.metrics.CassandraMetricsRegistry;
import org.apache.cassandra.net.ShardInboundRouter;

import static org.apache.cassandra.config.CassandraRelevantProperties.INBOUND_SHARD_DISPATCH;
import static org.apache.cassandra.config.CassandraRelevantProperties.MUTATION_SHARD_ROUTING;
import static org.apache.cassandra.distributed.api.ConsistencyLevel.ALL;
import static org.apache.cassandra.distributed.api.ConsistencyLevel.QUORUM;
import static org.apache.cassandra.distributed.shared.AssertUtils.assertRows;
import static org.apache.cassandra.distributed.shared.AssertUtils.row;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Exercises inbound shard dispatch (I5): with the flag on, an inbound MUTATION_REQ is routed to the
 * owning shard executor at ingress, so its whole handler runs on the shard thread instead of the
 * MUTATION stage. A single-node test cannot reach this — the coordinator applies its own writes via
 * {@code performLocally}, never through inbound messaging. At three nodes / RF=3 every node replicates
 * every key, so a coordinator write on node 1 forwards a MUTATION_REQ to nodes 2 and 3, whose inbound
 * path the router intercepts.
 *
 * <p>The counters give a clean 1x-vs-2x discriminator for the owner-inline bypass:
 * <ul>
 *   <li>node 1 (coordinator+replica) applies locally via {@code performLocally} — the router never
 *       sees those writes (routed count stays low), but the apply is still shard-executed (I1), so
 *       its {@code submittedTaskCount} advances;</li>
 *   <li>nodes 2, 3 route every write through the router (routed count &ge; rows). Because the routed
 *       handler runs on the owning shard thread, {@code applyMutation} applies inline rather than
 *       re-submitting — so their {@code submittedTaskCount} advances by ~routed count, not ~twice it.
 *       A broken bypass would double the submitted count on the replicas.</li>
 * </ul>
 */
public class ShardInboundDispatchTest extends TestBaseImpl
{
    @BeforeClass
    public static void setUpClass()
    {
        // ENABLED / ShardExecutors are read once at each instance's class-init, so both flags must be
        // visible (process-global system properties) before any instance starts.
        MUTATION_SHARD_ROUTING.setString("true");
        INBOUND_SHARD_DISPATCH.setString("true");
    }

    @AfterClass
    public static void tearDownClass()
    {
        INBOUND_SHARD_DISPATCH.reset();
        MUTATION_SHARD_ROUTING.reset();
    }

    @Test
    public void inboundMutationsRouteToOwningShardAndReadBack() throws Throwable
    {
        try (Cluster cluster = init(Cluster.build(3)
                                           .withConfig(c -> c.set("memtable", ImmutableMap.of(
                                                   "configurations", ImmutableMap.of(
                                                           "trie", ImmutableMap.of("class_name", "TrieMemtable")))))
                                           .start(), 3))
        {
            for (int n = 1; n <= 3; n++)
                assertTrue("inbound shard dispatch must be enabled on node " + n,
                           cluster.get(n).callOnInstance(() -> ShardInboundRouter.ENABLED));

            cluster.schemaChange(withKeyspace(
                "CREATE TABLE %s.tbl (k int PRIMARY KEY, v int) WITH memtable = 'trie'"));

            int rows = 200;
            long[] routedBefore = { routed(cluster, 1), routed(cluster, 2), routed(cluster, 3) };
            long[] submitBefore = { submitted(cluster, 1), submitted(cluster, 2), submitted(cluster, 3) };

            for (int i = 0; i < rows; i++)
                cluster.coordinator(1).execute(
                    withKeyspace("INSERT INTO %s.tbl (k, v) VALUES (?, ?)"), QUORUM, i, i * 10);

            // Remote replicas routed every inbound write through the router — the I5 path.
            for (int n = 2; n <= 3; n++)
            {
                long routedDelta = routed(cluster, n) - routedBefore[n - 1];
                assertTrue("node " + n + " must have routed >= " + rows + " inbound mutations (delta="
                           + routedDelta + ")", routedDelta >= rows);
            }

            // Owner-inline bypass: on the replicas the routed handler runs on the owning shard thread, so
            // applyMutation applies inline. Submitted (shards.execute) advances ~once per routed mutation,
            // not twice; a broken bypass would re-enqueue and roughly double it.
            for (int n = 2; n <= 3; n++)
            {
                long routedDelta = routed(cluster, n) - routedBefore[n - 1];
                long submitDelta = submitted(cluster, n) - submitBefore[n - 1];
                assertTrue("node " + n + " submitted (" + submitDelta + ") must not double routed ("
                           + routedDelta + ") — owner-inline bypass failed",
                           submitDelta <= routedDelta + 5);
            }

            // Node 1 applies its own coordinator writes via performLocally, so the inbound router barely
            // sees them: its routed count stays well below the write load.
            assertTrue("node 1 coordinator-local applies must not go through the inbound router",
                       routed(cluster, 1) - routedBefore[0] < rows);

            // Steady boundaries: no shard thread wrote a shard it does not own.
            for (int n = 1; n <= 3; n++)
                assertEquals("misrouted puts must be zero on node " + n, 0L, misroutedPuts(cluster, n));

            // Every replica holds every write.
            for (int i = 0; i < rows; i++)
                assertRows(cluster.coordinator(1).execute(
                    withKeyspace("SELECT v FROM %s.tbl WHERE k = ?"), ALL, i), row(i * 10));
        }
    }

    /**
     * Guard 2: a message bearing FORWARD_TO must divert to the Stage, because the handler would forward
     * it to peer replicas and that send must not queue behind a hot shard. In a two-DC cluster every
     * CL.ALL write from the DC0 coordinator forwards into DC1 through one node carrying FORWARD_TO — that
     * node's router must fall back, while the two forwarded (FORWARD_TO-stripped) legs still route.
     */
    @Test
    public void forwardToMessagesDivertToStage() throws Throwable
    {
        try (Cluster cluster = (Cluster) init(builder()
                                              .withDC("dc0", 1)
                                              .withDC("dc1", 3)
                                              .withConfig(c -> c.set("memtable", ImmutableMap.of(
                                                      "configurations", ImmutableMap.of(
                                                              "trie", ImmutableMap.of("class_name", "TrieMemtable")))))
                                              .start()))
        {
            // Every DC1 node replicates, so the coordinator forwards to DC1 through exactly one FORWARD_TO
            // message per write.
            cluster.schemaChange("ALTER KEYSPACE " + KEYSPACE +
                " WITH replication = {'class':'NetworkTopologyStrategy','dc0':1,'dc1':3}");
            cluster.schemaChange(withKeyspace(
                "CREATE TABLE %s.tbl (k int PRIMARY KEY, v int) WITH memtable = 'trie'"));

            int[] dc1 = { 2, 3, 4 };
            long fallbackBefore = 0;
            for (int n : dc1) fallbackBefore += fallbacks(cluster, n);

            int rows = 100;
            for (int i = 0; i < rows; i++)
                cluster.coordinator(1).execute(
                    withKeyspace("INSERT INTO %s.tbl (k, v) VALUES (?, ?)"), ALL, i, i * 10);

            long fallbackDelta = 0;
            for (int n : dc1) fallbackDelta += fallbacks(cluster, n);
            fallbackDelta -= fallbackBefore;

            // One FORWARD_TO message reaches DC1 per write; each is diverted to the Stage.
            assertTrue("FORWARD_TO messages must divert to the stage (dc1 fallback delta=" + fallbackDelta + ")",
                       fallbackDelta >= rows);

            for (int i = 0; i < rows; i++)
                assertRows(cluster.coordinator(1).execute(
                    withKeyspace("SELECT v FROM %s.tbl WHERE k = ?"), ALL, i), row(i * 10));
        }
    }

    private static long routed(Cluster cluster, int node)
    {
        return cluster.get(node).callOnInstance(() -> ShardInboundRouter.routedCount());
    }

    private static long fallbacks(Cluster cluster, int node)
    {
        return cluster.get(node).callOnInstance(() -> ShardInboundRouter.stageFallbackCount());
    }

    private static long submitted(Cluster cluster, int node)
    {
        return cluster.get(node).callOnInstance(() -> ShardExecutors.instance().submittedTaskCount());
    }

    private static long misroutedPuts(Cluster cluster, int node)
    {
        return cluster.get(node).callOnInstance(() ->
            CassandraMetricsRegistry.Metrics
                .getCounters((name, metric) -> name.contains("Misrouted memtable puts"))
                .values().stream().mapToLong(Counter::getCount).sum());
    }
}
