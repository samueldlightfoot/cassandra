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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

    // The invariant part of the predicate (everything derived from the statement's frozen metadata snapshot
    // and fixed CQL shape) is the per-write CPU cost — two isLocalSystemKeyspace scans and a keyspace lookup.
    // Memoize it per statement. Weak keys: the entry is collected once the statement is GC'd after the
    // prepared-statement cache evicts it, and any table schema change re-prepares to a NEW statement object,
    // so a stale metadata snapshot can never be served. The value never references the statement key.
    private static final Cache<ModificationStatement, Plan> PLANS = Caffeine.newBuilder().weakKeys().build();

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

            // The metadata-snapshot-invariant part of the predicate is memoized per statement; only the two
            // runtime-mutable gates and the per-key work below are re-evaluated each request.
            Plan plan = PLANS.get(stmt, CqlShardRouter::computePlan);
            if (!plan.routable)
                return fallback();

            // Runtime-mutable, so NOT memoized. Denylist gate is JMX-settable; a miss does a synchronous
            // distributed read, which must not run on a shard thread.
            if (DatabaseDescriptor.getPartitionDenylistEnabled() && DatabaseDescriptor.getDenylistWritesEnabled())
                return fallback();
            // Transient replication is set by ALTER KEYSPACE, which has NO prepared-statement invalidation, so
            // re-read the strategy every request. getReplicationStrategy() reads it off the live keyspace
            // metadata (the Keyspace object is stable), so the cached reference still sees the change.
            // maybeTryAdditionalReplicas can park the shard.
            if (plan.keyspace.getReplicationStrategy().hasTransientReplicas())
                return fallback();

            List<ByteBuffer> values = exec.options.getValues();
            int idx = plan.pkIndex;
            if (idx < 0 || idx >= values.size())
                return fallback();
            ByteBuffer keyBytes = values.get(idx);
            if (keyBytes == null || keyBytes == ByteBufferUtil.UNSET_BYTE_BUFFER)
                return fallback();

            DecoratedKey key = plan.metadata.partitioner.decorateKey(keyBytes);
            OptionalInt shard = MutationShardRouting.shardForKey(plan.metadata, key);
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

    /**
     * The invariant portion of the predicate — the checks that depend only on the statement's frozen
     * {@link TableMetadata} snapshot and fixed CQL shape. Computed once per statement and memoized in
     * {@link #PLANS}. Coordinate-time hazards are excluded here because coordinate runs on the shard thread
     * before {@code performLocally}'s authoritative apply check: LWT (paxos path), a non-single-column or
     * partially-bound partition key, counters (locked read-modify-write), and triggers (arbitrary user code
     * inline). The resolved {@link Keyspace} is captured so the per-request transient-replica check skips the
     * keyspace lookup. Runs inside {@code routeShard}'s try/catch; Caffeine does not cache a thrown exception.
     */
    private static Plan computePlan(ModificationStatement stmt)
    {
        if (stmt.hasConditions())                           // LWT -> paxos path, not the routed write path
            return Plan.NOT_ROUTABLE;

        // getPartitionKeyBindVariableIndexes returns one index per PK component only when every component is a
        // bind marker, so length == 1 means a single-column PK given by exactly one bound value (the whole key).
        short[] pkIndexes = stmt.getPartitionKeyBindVariableIndexes();
        if (pkIndexes == null || pkIndexes.length != 1)
            return Plan.NOT_ROUTABLE;

        TableMetadata metadata = stmt.metadata();
        if (metadata.isCounter())                           // read-modify-write under a Striped lock
            return Plan.NOT_ROUTABLE;
        if (!metadata.triggers.isEmpty())                   // arbitrary user code inline in coordinate
            return Plan.NOT_ROUTABLE;
        if (SchemaConstants.isLocalSystemKeyspace(metadata.keyspace))
            return Plan.NOT_ROUTABLE;

        Keyspace keyspace = Schema.instance.getKeyspaceInstance(metadata.keyspace);
        if (keyspace == null)
            return Plan.NOT_ROUTABLE;

        return new Plan(pkIndexes[0], metadata, keyspace);
    }

    private static OptionalInt fallback()
    {
        fallbacks.inc();
        return OptionalInt.empty();
    }

    /**
     * Memoized invariant verdict for a prepared statement (see {@link #computePlan}). Holds no reference to
     * the statement (the {@link #PLANS} key), so weak-key collection is not defeated. The runtime-mutable
     * predicates (denylist config, transient replication) are deliberately absent — {@code routeShard}
     * re-reads them live.
     */
    private static final class Plan
    {
        static final Plan NOT_ROUTABLE = new Plan();

        final boolean routable;
        final int pkIndex;                // bind index of the single-column partition key (valid iff routable)
        final TableMetadata metadata;     // the statement's frozen snapshot
        final Keyspace keyspace;          // resolved once; its live strategy is re-read per request

        private Plan()
        {
            this.routable = false;
            this.pkIndex = -1;
            this.metadata = null;
            this.keyspace = null;
        }

        Plan(int pkIndex, TableMetadata metadata, Keyspace keyspace)
        {
            this.routable = true;
            this.pkIndex = pkIndex;
            this.metadata = metadata;
            this.keyspace = keyspace;
        }
    }
}
