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

package org.apache.cassandra.net;

import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

import com.codahale.metrics.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.ExecutorLocals;
import org.apache.cassandra.concurrent.ShardExecutors;
import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.db.MutationShardRouting;
import org.apache.cassandra.metrics.DefaultNameFactory;
import org.apache.cassandra.metrics.MessagingMetrics;
import org.apache.cassandra.tcm.ClusterMetadata;
import org.apache.cassandra.utils.NoSpamLogger;

import static org.apache.cassandra.config.CassandraRelevantProperties.INBOUND_SHARD_DISPATCH;
import static org.apache.cassandra.metrics.CassandraMetricsRegistry.Metrics;

/**
 * Inbound shard dispatch: route an allowlisted verb to the owning shard executor at ingress, so its
 * whole handler (deserialized, key knowable) runs on the shard thread instead of the verb's Stage.
 * This collapses the extra thread handoff that routing the apply alone adds at RF&ge;3.
 *
 * <p>The single decision point for both the production netty path ({@link InboundMessageHandler}) and
 * the in-JVM test delivery path, so the two cannot drift. It runs on the caller's thread (a netty
 * event loop, or the test delivery thread) and must not block: anything on the handler that would
 * block a shard thread is diverted back to the Stage by the guards below.
 *
 * <p>Correctness never depends on routing here — a message that is not routed simply takes the Stage
 * path exactly as before, which is what {@link #tryRoute} returning {@code false} instructs the caller
 * to do.
 */
public final class ShardInboundRouter
{
    private static final Logger logger = LoggerFactory.getLogger(ShardInboundRouter.class);
    private static final NoSpamLogger noSpamLogger = NoSpamLogger.getLogger(logger, 1, TimeUnit.MINUTES);
    // Registered under the existing "Messaging" metric group (these count inbound-messaging routing
    // decisions); a novel group would need the registry's static allowlist + its virtual table updated.
    private static final DefaultNameFactory FACTORY = new DefaultNameFactory(MessagingMetrics.TYPE_NAME);

    /** Master gate: this flag AND mutation shard routing (its flag + a periodic commitlog). Read once
     *  at startup — a mid-run flip would create mixed routed/unrouted windows. */
    public static final boolean ENABLED =
        INBOUND_SHARD_DISPATCH.getBoolean() && MutationShardRouting.ROUTING_ENABLED;

    /** Messages routed to a shard executor at ingress. */
    private static final Counter routed = Metrics.counter(FACTORY.createMetricName("ShardRoutedMessages"));
    /** Allowlisted MUTATION_REQ candidates sent to the verb's Stage instead: a guard tripped
     *  (epoch-ahead / FORWARD_TO), the mutation was unroutable, or routing hit an error. */
    private static final Counter stageFallbacks = Metrics.counter(FACTORY.createMetricName("ShardRoutingStageFallbacks"));

    private ShardInboundRouter() {}

    public static long routedCount() { return routed.getCount(); }
    public static long stageFallbackCount() { return stageFallbacks.getCount(); }

    /**
     * If {@code message} is routable, submit {@code deliveryTask} to the owning shard executor carrying
     * {@code locals} and return {@code true}; otherwise return {@code false} and let the caller dispatch
     * on the verb's Stage as before.
     *
     * @param message      the fully deserialized inbound message (large messages, deserialized on-stage,
     *                     are never routable and must not reach here)
     * @param locals       the trace/client-warn state built on the loop; the shard executors are
     *                     {@code localAware()} so it propagates into the routed task
     * @param deliveryTask the work the caller would otherwise hand to the Stage
     */
    public static boolean tryRoute(Message<?> message, ExecutorLocals locals, Runnable deliveryTask)
    {
        if (!ENABLED)
            return false;

        // Runs on the netty event loop, where an escaping throw would close the connection. Routing is
        // only ever an optimization, so on any failure fall back to the Stage instead of propagating.
        try
        {
            // Allowlist: MUTATION_REQ only. READ_REQ is deferred (a read miss would block the shard
            // thread); other verbs carry work not yet proven shard-safe.
            if (message.verb() != Verb.MUTATION_REQ)
                return false;

            // Guard 1 - epoch ahead. The handler's TCM catch-up does a blocking peer/CMS fetch when the
            // message epoch leads ours; that must never run on a shard thread. Safe because epochs only
            // advance: a message not ahead of the loop's read is not ahead of the later shard-thread read.
            if (message.epoch().isAfter(ClusterMetadata.current().epoch))
                return fallback();

            // Guard 2 - FORWARD_TO. The handler forwards to peer replicas; that send must not queue behind
            // a hot shard (cross-node head-of-line blocking).
            if (message.forwardTo() != null)
                return fallback();

            ShardExecutors shards = ShardExecutors.instance();
            if (shards == null)
                return fallback();

            OptionalInt shardId = MutationShardRouting.route((Mutation) message.payload);
            if (shardId.isEmpty())
                return fallback();

            shards.execute(locals, shardId.getAsInt(), deliveryTask);
            routed.inc();
            return true;
        }
        catch (Throwable t)
        {
            noSpamLogger.warn("Inbound shard routing failed; falling back to the stage", t);
            return fallback();
        }
    }

    private static boolean fallback()
    {
        stageFallbacks.inc();
        return false;
    }
}
