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

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.distributed.Cluster;
import org.apache.cassandra.service.reads.ShardReads;

import static org.apache.cassandra.config.CassandraRelevantProperties.CQL_READ_ROUTING;
import static org.apache.cassandra.config.CassandraRelevantProperties.MUTATION_SHARD_ROUTING;
import static org.apache.cassandra.distributed.api.ConsistencyLevel.ALL;
import static org.apache.cassandra.distributed.api.ConsistencyLevel.ONE;
import static org.apache.cassandra.distributed.api.ConsistencyLevel.QUORUM;
import static org.apache.cassandra.distributed.shared.AssertUtils.assertRows;
import static org.apache.cassandra.distributed.shared.AssertUtils.row;
import static org.junit.Assert.assertTrue;

/**
 * Read-path sharding correctness: a routable single-partition read's local portion runs on the shard
 * executor owning its partition instead of {@code Stage.READ}, while the coordinator still contacts every
 * replica and resolves digests. Verifies that moving the local read off the read stage changes nothing a
 * client sees — correct results at RF=1 and RF=3, at CL ONE and CL QUORUM — and that the routed path is
 * actually exercised (not silently falling back).
 *
 * <p>The routed read here is the <em>coordinator-local</em> read (when the coordinator is a replica). Remote
 * replicas answer a coordinator's read through the internode read verb handler, a path this increment leaves
 * on {@code Stage.READ}; at RF=3 the coordinator-local half still routes and the multi-replica read stays
 * correct.
 */
public class ShardRoutedLocalReadTest extends TestBaseImpl
{
    @BeforeClass
    public static void setUpClass()
    {
        // ROUTING_ENABLED / ShardExecutors and ShardReads.ENABLED are read once at each instance's class-init,
        // so both flags must be visible (process-global) before any instance starts.
        MUTATION_SHARD_ROUTING.setString("true");
        CQL_READ_ROUTING.setString("true");
    }

    @AfterClass
    public static void tearDownClass()
    {
        CQL_READ_ROUTING.reset();
        MUTATION_SHARD_ROUTING.reset();
    }

    @Test
    public void routedLocalReadsAreCorrect() throws Throwable
    {
        try (Cluster cluster = init(Cluster.build(3)
                                           .withConfig(c -> c.set("memtable", ImmutableMap.of(
                                                   "configurations", ImmutableMap.of(
                                                           "trie", ImmutableMap.of("class_name", "TrieMemtable")))))
                                           .start(), 3))
        {
            for (int n = 1; n <= 3; n++)
                assertTrue("read routing must be enabled on node " + n,
                           cluster.get(n).callOnInstance(() -> ShardReads.ENABLED));

            // RF=3 (the init keyspace): every node replicates every key, so the coordinator always reads
            // locally. RF=1: a distinct keyspace with a single replica per key.
            cluster.schemaChange(withKeyspace(
                "CREATE TABLE %s.rf3 (k int PRIMARY KEY, v int) WITH memtable = 'trie'"));
            cluster.schemaChange(
                "CREATE KEYSPACE rf1 WITH replication = {'class':'SimpleStrategy','replication_factor':1}");
            cluster.schemaChange("CREATE TABLE rf1.tbl (k int PRIMARY KEY, v int) WITH memtable = 'trie'");

            int rows = 200;
            for (int i = 0; i < rows; i++)
            {
                cluster.coordinator(1).execute(withKeyspace("INSERT INTO %s.rf3 (k, v) VALUES (?, ?)"),
                                               QUORUM, i, i * 10);
                cluster.coordinator(1).execute("INSERT INTO rf1.tbl (k, v) VALUES (?, ?)", ONE, i, i * 100);
            }

            long before = routedReads(cluster, 1);

            // RF=3 at CL ONE and QUORUM: local read routes, digest resolution runs on the coordinator.
            for (int i = 0; i < rows; i++)
            {
                assertRows(cluster.coordinator(1).execute(
                    withKeyspace("SELECT v FROM %s.rf3 WHERE k = ?"), ONE, i), row(i * 10));
                assertRows(cluster.coordinator(1).execute(
                    withKeyspace("SELECT v FROM %s.rf3 WHERE k = ?"), QUORUM, i), row(i * 10));
            }

            // RF=1 at CL ONE: keys the coordinator owns route locally; the rest forward, still correct.
            for (int i = 0; i < rows; i++)
                assertRows(cluster.coordinator(1).execute(
                    "SELECT v FROM rf1.tbl WHERE k = ?", ONE, i), row(i * 100));

            // Every QUORUM read above contacted node 1 (the local, closest replica) and routed its local
            // read, so the routed counter grew by at least the QUORUM-read count. Proves we exercised the
            // routed path rather than silently falling back to Stage.READ.
            assertTrue("coordinator-local reads must have routed (before=" + before
                       + ", after=" + routedReads(cluster, 1) + ")",
                       routedReads(cluster, 1) >= before + rows);

            // Stronger correctness sweep: re-read RF=3 at ALL so all three replicas answer.
            for (int i = 0; i < rows; i++)
                assertRows(cluster.coordinator(1).execute(
                    withKeyspace("SELECT v FROM %s.rf3 WHERE k = ?"), ALL, i), row(i * 10));
        }
    }

    private static long routedReads(Cluster cluster, int node)
    {
        return cluster.get(node).callOnInstance(() -> ShardReads.routedCount());
    }
}
