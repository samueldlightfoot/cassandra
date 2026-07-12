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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.InheritingClass;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.db.memtable.AbstractShardedMemtable;
import org.apache.cassandra.db.memtable.SkipListMemtable;
import org.apache.cassandra.db.memtable.TrieMemtable;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.schema.TableMetadata;

import static org.apache.cassandra.db.memtable.AbstractShardedMemtable.SHARDS_OPTION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MutationShardRoutingTest extends CQLTester
{
    @BeforeClass
    public static void setUpClass()
    {
        prePrepareServer();

        // Named memtable configurations so tables can opt into a sharded (trie) or unsharded (skiplist)
        // memtable via `WITH memtable = '...'`.
        LinkedHashMap<String, InheritingClass> memtableConfig = new LinkedHashMap<>();
        memtableConfig.put("trie", new InheritingClass(null, TrieMemtable.class.getName(), Map.of(SHARDS_OPTION, "4")));
        memtableConfig.put("skiplist", new InheritingClass(null, SkipListMemtable.class.getName(), Map.of()));
        DatabaseDescriptor.getRawConfig().memtable = new Config.MemtableOptions();
        DatabaseDescriptor.getRawConfig().memtable.configurations = memtableConfig;

        DatabaseDescriptor.setPartitionerUnsafe(Murmur3Partitioner.instance);
        prepareServer();
    }

    @Test
    public void routingDisabledByDefault()
    {
        // Flag off (the default) => the routing call sites take the unchanged path; see
        // ShardRoutedMutationApplyTest for the flag-on behaviour.
        assertFalse(MutationShardRouting.ROUTING_ENABLED);
    }

    @Test
    public void routesPlainTrieTable()
    {
        createTable("CREATE TABLE %s (k int PRIMARY KEY, v int) WITH memtable = 'trie'");
        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        DecoratedKey key = key(cfs, 42);

        int expected = ((AbstractShardedMemtable) cfs.getCurrentMemtable()).getShardBoundaries().getShardForKey(key);
        assertEquals(OptionalInt.of(expected), route(cfs, key));
    }

    @Test
    public void skipsNonShardedSkiplistTable()
    {
        createTable("CREATE TABLE %s (k int PRIMARY KEY, v int) WITH memtable = 'skiplist'");
        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        assertFalse(route(cfs, key(cfs, 42)).isPresent());
    }

    @Test
    public void skipsCounterTable()
    {
        createTable("CREATE TABLE %s (k int PRIMARY KEY, c counter) WITH memtable = 'trie'");
        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        assertFalse(route(cfs, key(cfs, 42)).isPresent());
    }

    @Test
    public void skipsLegacySecondaryIndexTable()
    {
        createTable("CREATE TABLE %s (k int PRIMARY KEY, v int) WITH memtable = 'trie'");
        createIndex("CREATE INDEX ON %s(v)");
        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        assertFalse(route(cfs, key(cfs, 42)).isPresent());
    }

    @Test
    public void routesSaiIndexedTable()
    {
        createTable("CREATE TABLE %s (k int PRIMARY KEY, v int) WITH memtable = 'trie'");
        createIndex("CREATE CUSTOM INDEX ON %s(v) USING 'org.apache.cassandra.index.sai.StorageAttachedIndex'");
        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        assertTrue(route(cfs, key(cfs, 42)).isPresent());
    }

    @Test
    public void skipsUnknownKeyspaceWithoutThrowing()
    {
        // A keyspace dropped while a write is in flight must not throw here: at ingress route() runs on
        // the netty loop, ahead of the handler's schema checks that would otherwise reject it.
        TableMetadata ghost = TableMetadata.builder("no_such_keyspace", "tbl")
                                           .addPartitionKeyColumn("k", Int32Type.instance)
                                           .build();
        DecoratedKey key = Murmur3Partitioner.instance.decorateKey(Int32Type.instance.decompose(1));
        assertFalse(MutationShardRouting.route(new Mutation(PartitionUpdate.emptyUpdate(ghost, key))).isPresent());
    }

    // Not yet covered: materialized-view base tables (needs a view + an affecting update), CDC tables
    // (needs global cdc_enabled), multi-table boundary disagreement, and local-system keyspaces.

    private static DecoratedKey key(ColumnFamilyStore cfs, int k)
    {
        return cfs.getPartitioner().decorateKey(Int32Type.instance.decompose(k));
    }

    private static OptionalInt route(ColumnFamilyStore cfs, DecoratedKey key)
    {
        return MutationShardRouting.route(new Mutation(PartitionUpdate.emptyUpdate(cfs.metadata(), key)));
    }
}
