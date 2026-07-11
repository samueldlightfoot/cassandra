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

package org.apache.cassandra.db;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.concurrent.ShardExecutors;
import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.InheritingClass;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.db.memtable.TrieMemtable;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.transport.Dispatcher;

import static org.apache.cassandra.config.CassandraRelevantProperties.MUTATION_SHARD_ROUTING;
import static org.apache.cassandra.db.memtable.AbstractShardedMemtable.SHARDS_OPTION;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end coordinator-path test with shard routing enabled: writes driven through
 * {@link StorageProxy#mutate} must be applied on the shard executors and be readable afterwards.
 */
public class ShardRoutedMutationApplyTest extends CQLTester
{
    @BeforeClass
    public static void setUpClass()
    {
        // Enable routing before MutationShardRouting / ShardExecutors initialize (first write), so both
        // read it as on. Run this class in its own forked JVM (it flips a read-once static-final flag).
        MUTATION_SHARD_ROUTING.setString("true");
        prePrepareServer();

        LinkedHashMap<String, InheritingClass> memtableConfig = new LinkedHashMap<>();
        memtableConfig.put("trie", new InheritingClass(null, TrieMemtable.class.getName(), Map.of(SHARDS_OPTION, "4")));
        DatabaseDescriptor.getRawConfig().memtable = new Config.MemtableOptions();
        DatabaseDescriptor.getRawConfig().memtable.configurations = memtableConfig;

        DatabaseDescriptor.setPartitionerUnsafe(Murmur3Partitioner.instance);
        prepareServer();
    }

    @AfterClass
    public static void tearDownClass()
    {
        MUTATION_SHARD_ROUTING.reset();
    }

    @Test
    public void routedWritesLandAndReadBack() throws Throwable
    {
        assertTrue("shard routing must be enabled for this test", MutationShardRouting.ROUTING_ENABLED);
        ShardExecutors shards = ShardExecutors.instance();
        assertNotNull("shard executors must exist when routing is enabled", shards);

        createTable("CREATE TABLE %s (k int PRIMARY KEY, v int) WITH memtable = 'trie'");
        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();

        int rows = 200;
        long before = shards.submittedTaskCount();
        for (int i = 0; i < rows; i++)
        {
            PartitionUpdate.SimpleBuilder builder = PartitionUpdate.simpleBuilder(cfs.metadata(), i);
            builder.row().add("v", i * 10);
            StorageProxy.mutate(Collections.singletonList(new Mutation(builder.build())),
                                ConsistencyLevel.ONE, Dispatcher.RequestTime.forImmediateExecution());
        }

        // The writes were actually routed to the shard executors, not silently sent down the
        // fall-through path: each routable local apply increments the submitted count.
        assertTrue("expected >= " + rows + " routed applies (before=" + before + ", now="
                   + shards.submittedTaskCount() + ")",
                   shards.submittedTaskCount() >= before + rows);

        // Reads (unrouted in this increment) must observe every routed write.
        for (int i = 0; i < rows; i++)
            assertRows(execute("SELECT v FROM %s WHERE k = ?", i), row(i * 10));
    }
}
