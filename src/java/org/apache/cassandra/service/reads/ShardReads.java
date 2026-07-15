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

package org.apache.cassandra.service.reads;

import java.util.OptionalInt;
import java.util.concurrent.RejectedExecutionException;

import com.codahale.metrics.Counter;

import org.apache.cassandra.concurrent.ShardExecutors;
import org.apache.cassandra.db.MutationShardRouting;
import org.apache.cassandra.db.ReadCommand;
import org.apache.cassandra.db.SinglePartitionReadCommand;
import org.apache.cassandra.metrics.ClientMetrics;
import org.apache.cassandra.metrics.DefaultNameFactory;
import org.apache.cassandra.schema.SchemaConstants;
import org.apache.cassandra.schema.TableMetadata;

import static org.apache.cassandra.config.CassandraRelevantProperties.CQL_READ_ROUTING;
import static org.apache.cassandra.metrics.CassandraMetricsRegistry.Metrics;

/**
 * Read-path sharding: run a routable single-partition read's local portion on the executor that owns its
 * shard — the core that owns that partition's memtable/sstable data — instead of the shared {@code Stage.READ}
 * pool, so the read's CPU work (deserialize/merge/decompress/iterate) stays on one core's cache.
 *
 * <p>Only the <em>local</em> read moves. The coordinator still contacts every replica in
 * {@code AbstractReadExecutor.makeRequests} and still does digest comparison and read-repair on its own
 * thread, so routing the local read is correct at any consistency level and RF — a declined route simply runs
 * on {@code Stage.READ} exactly as before. This is why, unlike the write path, no CL gate is needed.
 *
 * <p>The gate excludes anything whose local read must not run on a shard thread: a read issued while already
 * on a shard thread (a routed write coordinate can run a synchronous auth read here — submitting it back to
 * its own executor would self-stall), local-system tables (their {@code ShardBoundaries.NONE} maps every key
 * to shard 0), and secondary-index reads (the index searcher is arbitrary/long work). The shard executors are
 * shut down before {@code Stage.READ}, so a submit racing the drain falls back rather than throwing.
 */
public final class ShardReads
{
    private static final DefaultNameFactory FACTORY = new DefaultNameFactory(ClientMetrics.TYPE_NAME);

    /** This flag AND mutation shard routing (the shard executors only exist when that is on). Read once at
     *  startup — a mid-run flip would create mixed routed/unrouted windows. */
    public static final boolean ENABLED = CQL_READ_ROUTING.getBoolean() && MutationShardRouting.ROUTING_ENABLED;

    /** Local reads run on their owning shard executor. */
    private static final Counter routed = Metrics.counter(FACTORY.createMetricName("ShardLocalReadRouted"));
    /** Local reads left on {@code Stage.READ}: not a routable single-partition read, or a drain-window race. */
    private static final Counter fallbacks = Metrics.counter(FACTORY.createMetricName("ShardLocalReadFallbacks"));

    private ShardReads() {}

    public static long routedCount() { return routed.getCount(); }
    public static long fallbackCount() { return fallbacks.getCount(); }

    /**
     * Try to run {@code localRead} on the executor owning {@code command}'s shard.
     *
     * @return true if it was submitted to a shard executor (the caller does nothing further); false if the
     *         caller must run it on {@code Stage.READ} as before. Never throws.
     */
    public static boolean submitLocalRead(ReadCommand command, Runnable localRead)
    {
        if (!ENABLED)
            return fallback();

        ShardExecutors shards = ShardExecutors.instance();
        if (shards == null)
            return fallback();

        // A read issued from a shard thread (e.g. an expired-auth checkAccess on a routed write coordinate)
        // must not route: submitting its local portion back to the same executor while that thread blocks
        // awaiting it would stall the shard until the read times out and is dropped with no callback. Reads
        // issued from NTR threads are cycle-free (NTR waits on the shard; the shard waits only on disk).
        if (ShardExecutors.isShardThread())
            return fallback();

        if (!(command instanceof SinglePartitionReadCommand))
            return fallback();
        SinglePartitionReadCommand read = (SinglePartitionReadCommand) command;

        TableMetadata metadata = read.metadata();
        // Local-system tables use ShardBoundaries.NONE, so shardForKey returns 0 for every key — routing them
        // would serialize all driver control-plane reads onto shard 0.
        if (SchemaConstants.isLocalSystemKeyspace(metadata.keyspace))
            return fallback();
        // A secondary-index read runs the index searcher (arbitrary, potentially long) on the shard thread.
        if (read.indexQueryPlan() != null)
            return fallback();

        OptionalInt shard = MutationShardRouting.shardForKey(metadata, read.partitionKey());
        if (shard.isEmpty())
            return fallback();

        try
        {
            shards.execute(shard.getAsInt(), localRead);
        }
        catch (RejectedExecutionException e)
        {
            // Drain window: the shard executors are terminating while Stage.READ is still up.
            return fallback();
        }
        routed.inc();
        return true;
    }

    private static boolean fallback()
    {
        fallbacks.inc();
        return false;
    }
}
