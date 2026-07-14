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

import java.util.OptionalInt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.memtable.AbstractShardedMemtable;
import org.apache.cassandra.db.memtable.Memtable;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.schema.IndexMetadata;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.schema.TableMetadata;

import static org.apache.cassandra.config.CassandraRelevantProperties.MUTATION_SHARD_ROUTING;

/**
 * Decides whether a local mutation apply may be routed to a per-shard single-writer executor
 * ({@link org.apache.cassandra.concurrent.ShardExecutors}), and to which shard.
 *
 * <p>{@link #route} is a pure per-mutation predicate. It excludes materialized views, counters, CDC
 * tables, tables with a legacy or custom (non-SAI) secondary index, local-system keyspaces, non-sharded
 * memtables, and multi-table mutations whose tables disagree on the shard — all of these fall back to
 * the existing path. Correctness never depends on routing: a non-routed or mis-routed write simply takes
 * the memtable lock and is still correct.
 *
 * <p>{@link #ROUTING_ENABLED} is the global gate: the master flag AND a periodic commitlog. Routing an
 * apply under {@code batch}/{@code group} commitlog would block a shard thread on the commit fsync, so
 * those modes disable routing wholesale. The gate is read once at startup — a mid-run flip would create
 * mixed-writer windows.
 */
public final class MutationShardRouting
{
    private static final Logger logger = LoggerFactory.getLogger(MutationShardRouting.class);

    /** Global gate the routing call sites consult before {@link #route}. */
    public static final boolean ROUTING_ENABLED = computeRoutingEnabled();

    private MutationShardRouting() {}

    private static boolean computeRoutingEnabled()
    {
        if (!MUTATION_SHARD_ROUTING.getBoolean())
            return false;

        Config.CommitLogSync sync = DatabaseDescriptor.getCommitLogSync();
        if (sync != Config.CommitLogSync.periodic)
        {
            logger.warn("cassandra.mutation.shard_routing is set but commitlog_sync={} (not periodic); "
                        + "shard routing disabled to avoid stalling shard threads on the commit fsync.", sync);
            return false;
        }
        return true;
    }

    /**
     * @return the shard id to route this mutation's apply to, or empty if it must take the existing path.
     *         The shard id is computed from each updated table's current memtable boundaries, so it
     *         matches the shard that memtable's {@code put} will select.
     */
    public static OptionalInt route(Mutation mutation)
    {
        String keyspaceName = mutation.getKeyspaceName();

        // Unknown keyspace (e.g. dropped while a write was in flight): don't route. Ingress routing runs
        // on the netty loop, ahead of the handler's schema checks, so this lookup must not throw here.
        Keyspace keyspace = Schema.instance.getKeyspaceInstance(keyspaceName);
        if (keyspace == null)
            return OptionalInt.empty();

        // Views: a routed apply would run the synchronous view update and the ViewManager striped-lock
        // retry loop on a shard thread.
        if (keyspace.viewManager.updatesAffectView(mutation, false))
            return OptionalInt.empty();

        // CDC: commitlog CDC allocation can block or throw on space exhaustion.
        if (mutation.trackedByCDC())
            return OptionalInt.empty();

        // Local-system keyspaces carry non-routable traffic and use ShardBoundaries.NONE anyway.
        if (keyspace.isLocalSystemKeyspace())
            return OptionalInt.empty();

        DecoratedKey key = mutation.key();
        int shardId = -1;
        for (PartitionUpdate update : mutation.getPartitionUpdates())
        {
            TableMetadata metadata = update.metadata();

            // Counters: read-modify-write under a Striped lock, not routable.
            if (metadata.isCounter())
                return OptionalInt.empty();

            // Legacy 2i (and any custom non-SAI index running user code): index puts are keyed by the
            // indexed value (a different token than the base key) so a shard thread would write them
            // off-owner, and index apply carries the full write-path blocking budget.
            if (!indexesAllowRouting(metadata))
                return OptionalInt.empty();

            ColumnFamilyStore cfs = ColumnFamilyStore.getIfExists(metadata.id);
            if (cfs == null)
                return OptionalInt.empty();

            Memtable memtable = cfs.getTracker().getView().getCurrentMemtable();
            if (!(memtable instanceof AbstractShardedMemtable))
                return OptionalInt.empty();

            int tableShard = ((AbstractShardedMemtable) memtable).getShardBoundaries().getShardForKey(key);
            if (shardId < 0)
                shardId = tableShard;
            else if (shardId != tableShard)      // multi-table mutation whose tables disagree on the shard
                return OptionalInt.empty();
        }

        return shardId < 0 ? OptionalInt.empty() : OptionalInt.of(shardId);
    }

    /**
     * The shard that owns {@code key} in {@code metadata}'s current memtable, or empty if that memtable
     * is not sharded. Used by CQL ingress routing to place a request's coordinate on the apply's likely
     * owner; the apply's shard is re-decided authoritatively by {@link #route} on the shard thread, so a
     * stale answer here only costs the optimization, never correctness.
     */
    public static OptionalInt shardForKey(TableMetadata metadata, DecoratedKey key)
    {
        ColumnFamilyStore cfs = ColumnFamilyStore.getIfExists(metadata.id);
        if (cfs == null)
            return OptionalInt.empty();

        Memtable memtable = cfs.getTracker().getView().getCurrentMemtable();
        if (!(memtable instanceof AbstractShardedMemtable))
            return OptionalInt.empty();

        return OptionalInt.of(((AbstractShardedMemtable) memtable).getShardBoundaries().getShardForKey(key));
    }

    /**
     * Routable index-wise iff the table has no indexes, or every index is SAI (parallel in-memory
     * structures, no memtable write). Legacy 2i and custom non-SAI indexes are excluded.
     */
    private static boolean indexesAllowRouting(TableMetadata metadata)
    {
        if (metadata.indexes.isEmpty())
            return true;
        for (IndexMetadata index : metadata.indexes)
            if (!index.isCustom() || !IndexMetadata.isSAIIndex(index.getIndexClassName()))
                return false;
        return true;
    }
}
