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

package org.apache.cassandra.transport;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

import com.codahale.metrics.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.QueryHandler;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.statements.ModificationStatement;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.MutationShardRouting;
import org.apache.cassandra.metrics.ClientMetrics;
import org.apache.cassandra.metrics.DefaultNameFactory;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.schema.SchemaConstants;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.transport.messages.ExecuteMessage;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.NoSpamLogger;

import static org.apache.cassandra.config.CassandraRelevantProperties.CQL_INGRESS_ROUTING;
import static org.apache.cassandra.metrics.CassandraMetricsRegistry.Metrics;

/**
 * CQL ingress routing: on the native-transport event loop, decide whether a prepared single-partition
 * write may run its coordinate on the owning shard thread instead of the shared Native-Transport-Requests
 * (NTR) pool. Routing here deletes the loop&rarr;NTR handoff for the request, and — with the owner-inline
 * bypass in {@code StorageProxy.performLocally} — lets the coordinator's local apply run inline on the
 * owner, so coordinate and apply share one thread.
 *
 * <p>This runs on the netty loop, where an escaping throw would close the connection. Every path is
 * therefore wrapped: any failure (including the {@code OptionsWithNames} assertion on named-value
 * requests) returns empty, and the caller submits to the NTR pool exactly as before. Correctness never
 * depends on this decision — the apply's shard is re-decided authoritatively by
 * {@link MutationShardRouting#route} on the shard thread; this only places coordinate on the likely owner.
 *
 * <p>The predicate excludes everything whose <em>coordinate</em> would block or misbehave on a shard
 * thread, because coordinate runs there before the authoritative apply check: non-prepared /
 * non-{@link ModificationStatement} / LWT / batch (wrong path or paxos), counters (locked
 * read-modify-write), triggers (arbitrary user code inline), the partition denylist when its write gate is
 * on (a synchronous distributed read on a miss), and transient-replication keyspaces (the additional-write
 * await can park). Apply-time hazards (views, CDC, legacy 2i) are left to {@code performLocally}'s check,
 * which routes those applies off-shard.
 *
 * <p>Skeleton scope: single-column partition keys only. A composite key needs {@code CompositeType}
 * assembly from the bound components; until that is built such writes fall back (correct, unoptimized).
 */
public final class CqlShardRouter
{
    private static final Logger logger = LoggerFactory.getLogger(CqlShardRouter.class);
    private static final NoSpamLogger noSpamLogger = NoSpamLogger.getLogger(logger, 1, TimeUnit.MINUTES);
    // Counters live under the native-transport "Client" metric group (a statically-registered group),
    // mirroring the internode sibling which registers under "Messaging"; a bespoke group would need
    // registering in CassandraMetricsRegistry.metricGroups or the counter registration throws.
    private static final DefaultNameFactory FACTORY = new DefaultNameFactory(ClientMetrics.TYPE_NAME);

    /** Master gate: this flag AND mutation shard routing (its flag + a periodic commitlog). Read once at
     *  startup — a mid-run flip would create mixed routed/unrouted windows. */
    public static final boolean ENABLED =
        CQL_INGRESS_ROUTING.getBoolean() && MutationShardRouting.ROUTING_ENABLED;

    /** Requests whose coordinate was routed to a shard executor at native ingress. */
    private static final Counter routed = Metrics.counter(FACTORY.createMetricName("CqlIngressRouted"));
    /** Requests left on the NTR pool: not an allowlisted single-partition write, unprepared, or an error. */
    private static final Counter fallbacks = Metrics.counter(FACTORY.createMetricName("CqlIngressFallbacks"));

    private CqlShardRouter() {}

    public static long routedCount() { return routed.getCount(); }
    public static long fallbackCount() { return fallbacks.getCount(); }

    /**
     * @return the shard to run {@code request}'s coordinate on (its coordinator-local apply then runs
     *         inline there), or empty to take the normal NTR-pool path. Never throws.
     */
    public static OptionalInt routeShard(Message.Request request)
    {
        if (!ENABLED)
            return OptionalInt.empty();

        try
        {
            if (!(request instanceof ExecuteMessage))
                return fallback();

            // Custom query handlers run arbitrary code in getPrepared/execute; only route the built-in one.
            if (!(ClientState.getCQLQueryHandler() instanceof QueryProcessor))
                return fallback();

            ExecuteMessage exec = (ExecuteMessage) request;

            // Policy-neutral lookup: a normal getPrepared could trigger a system-table write (eviction) on
            // the loop. A miss means unprepared/evicted -> normal path re-prepares.
            QueryHandler.Prepared prepared = QueryProcessor.getPreparedNoTouch(exec.statementId);
            if (prepared == null || !(prepared.statement instanceof ModificationStatement))
                return fallback();

            ModificationStatement stmt = (ModificationStatement) prepared.statement;
            if (stmt.hasConditions())                       // LWT -> paxos path, not the routed write path
                return fallback();

            // Single-column partition key, fully bound as one marker: getPartitionKeyBindVariableIndexes
            // returns one index per PK component only when every component is a bind marker, so length == 1
            // means a single-column PK given by exactly one bound value (the whole key).
            short[] pkIndexes = stmt.getPartitionKeyBindVariableIndexes();
            if (pkIndexes == null || pkIndexes.length != 1)
                return fallback();

            TableMetadata metadata = stmt.metadata();

            // Coordinate-time hazards — all run before performLocally, so they must be excluded here.
            if (metadata.isCounter())                       // read-modify-write under a Striped lock
                return fallback();
            if (!metadata.triggers.isEmpty())               // arbitrary user code inline in coordinate
                return fallback();
            if (SchemaConstants.isLocalSystemKeyspace(metadata.keyspace))
                return fallback();
            if (DatabaseDescriptor.getPartitionDenylistEnabled() && DatabaseDescriptor.getDenylistWritesEnabled())
                return fallback();                          // a denylist miss does a synchronous distributed read

            Keyspace keyspace = Schema.instance.getKeyspaceInstance(metadata.keyspace);
            if (keyspace == null)
                return fallback();
            if (keyspace.getReplicationStrategy().hasTransientReplicas())
                return fallback();                          // maybeTryAdditionalReplicas can park the shard

            List<ByteBuffer> values = exec.options.getValues();
            int idx = pkIndexes[0];
            if (idx < 0 || idx >= values.size())
                return fallback();
            ByteBuffer keyBytes = values.get(idx);
            if (keyBytes == null || keyBytes == ByteBufferUtil.UNSET_BYTE_BUFFER)
                return fallback();

            DecoratedKey key = metadata.partitioner.decorateKey(keyBytes);
            OptionalInt shard = MutationShardRouting.shardForKey(metadata, key);
            if (shard.isEmpty())
                return fallback();

            routed.inc();
            return shard;
        }
        catch (Throwable t)
        {
            // On the netty loop a throw would close the connection; routing is only an optimization, so any
            // failure (incl. the OptionsWithNames named-value assertion) falls back to the NTR pool.
            noSpamLogger.warn("CQL ingress routing failed; falling back to the NTR pool", t);
            return fallback();
        }
    }

    private static OptionalInt fallback()
    {
        fallbacks.inc();
        return OptionalInt.empty();
    }
}
