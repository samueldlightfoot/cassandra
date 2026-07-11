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

import static org.apache.cassandra.config.CassandraRelevantProperties.MUTATION_SHARD_ROUTING;
import static org.apache.cassandra.distributed.api.ConsistencyLevel.ALL;
import static org.apache.cassandra.distributed.api.ConsistencyLevel.QUORUM;
import static org.apache.cassandra.distributed.shared.AssertUtils.assertRows;
import static org.apache.cassandra.distributed.shared.AssertUtils.row;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the replica-side of shard routing, which a single-node test cannot reach: when the
 * coordinator is also a replica it applies locally via {@code performLocally}, never through the
 * inbound MUTATION_REQ handler. With three nodes at RF=3 every node replicates every key, so each
 * coordinator write forwards a MUTATION_REQ to the two remote replicas whose
 * {@code MutationVerbHandler.applyMutation} must route the apply onto that node's per-shard
 * executor. Proves the routed replica path lands data, and that healthy boundaries produce no
 * misrouted puts.
 */
public class ShardRoutedReplicaApplyTest extends TestBaseImpl
{
    @BeforeClass
    public static void setUpClass()
    {
        // ROUTING_ENABLED / ShardExecutors are read once at each instance's class-init, so the flag
        // must be visible (process-global system property) before any instance starts.
        MUTATION_SHARD_ROUTING.setString("true");
    }

    @AfterClass
    public static void tearDownClass()
    {
        MUTATION_SHARD_ROUTING.reset();
    }

    @Test
    public void routedReplicaWritesLandAndReadBack() throws Throwable
    {
        try (Cluster cluster = init(Cluster.build(3)
                                           .withConfig(c -> c.set("memtable", ImmutableMap.of(
                                                   "configurations", ImmutableMap.of(
                                                           "trie", ImmutableMap.of("class_name", "TrieMemtable")))))
                                           .start(), 3))
        {
            for (int n = 1; n <= 3; n++)
                assertTrue("shard routing must be enabled on node " + n,
                           cluster.get(n).callOnInstance(() -> MutationShardRouting.ROUTING_ENABLED));

            cluster.schemaChange(withKeyspace(
                "CREATE TABLE %s.tbl (k int PRIMARY KEY, v int) WITH memtable = 'trie'"));

            int rows = 200;
            long[] before = { routedApplies(cluster, 1), routedApplies(cluster, 2), routedApplies(cluster, 3) };
            for (int i = 0; i < rows; i++)
                cluster.coordinator(1).execute(
                    withKeyspace("INSERT INTO %s.tbl (k, v) VALUES (?, ?)"), QUORUM, i, i * 10);

            // The remote replicas (2, 3) each routed every write through their shard executors — the
            // replica path a single-node test never reaches. The coordinator-local apply on node 1
            // routes too, but node 1 alone would be indistinguishable from the single-node case.
            for (int n = 1; n <= 3; n++)
                assertTrue("node " + n + " must have routed >= " + rows + " applies (before="
                           + before[n - 1] + ", after=" + routedApplies(cluster, n) + ")",
                           routedApplies(cluster, n) >= before[n - 1] + rows);

            // Steady boundaries: no shard thread wrote a shard it does not own.
            for (int n = 1; n <= 3; n++)
                assertEquals("misrouted puts must be zero on node " + n, 0L, misroutedPuts(cluster, n));

            // Every replica holds every write.
            for (int i = 0; i < rows; i++)
                assertRows(cluster.coordinator(1).execute(
                    withKeyspace("SELECT v FROM %s.tbl WHERE k = ?"), ALL, i), row(i * 10));
        }
    }

    private static long routedApplies(Cluster cluster, int node)
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
