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

package org.apache.cassandra.concurrent;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.annotations.VisibleForTesting;

import org.apache.cassandra.utils.ExecutorUtils;
import org.apache.cassandra.utils.FBUtilities;

import io.netty.util.concurrent.FastThreadLocal;

import static org.apache.cassandra.concurrent.ExecutorFactory.Global.executorFactory;
import static org.apache.cassandra.config.CassandraRelevantProperties.MUTATION_SHARD_ROUTING;

/**
 * A pool of per-shard single-writer executors that routed mutation applies land on. One
 * {@code sequential()} executor per shard gives that shard a single writer thread, making the
 * memtable's per-shard {@code writeLock} redundant for writes it owns.
 *
 * <p>Correctness never depends on routing: {@link #currentThreadIsOwnerOf} is a hint that lets a write
 * skip the lock; a wrong or absent shard id just fails the check and takes the lock as before.
 */
public final class ShardExecutors
{
    private static final int UNSET = -1;

    /** One executor per available processor, added on top of the existing request/mutation pools —
     *  enabling routing intentionally oversubscribes the CPUs. */
    private static final int SHARD_COUNT = FBUtilities.getAvailableProcessors();

    /** The executor index the calling thread owns while it runs a routed task; {@link #UNSET} otherwise.
     *  Set per task (not once per thread) so it survives worker-thread replacement. Netty's
     *  {@link FastThreadLocal}, not {@code java.lang.ThreadLocal}: shard threads are FastThreadLocalThreads,
     *  where get/set is an array index rather than a hashed-map probe on every routed task. */
    private static final FastThreadLocal<Integer> CURRENT_SHARD = new FastThreadLocal<Integer>()
    {
        @Override
        protected Integer initialValue()
        {
            return UNSET;
        }
    };

    /** Created only when routing is enabled, so no shard threads exist when the flag is off (the default). */
    private static volatile ShardExecutors instance =
        MUTATION_SHARD_ROUTING.getBoolean() ? new ShardExecutors() : null;

    private final SequentialExecutorPlus[] executors;

    /** Count of routed applies submitted, across all shards. */
    private final AtomicLong submitted = new AtomicLong();

    @VisibleForTesting
    ShardExecutors()
    {
        SequentialExecutorPlus[] created = new SequentialExecutorPlus[SHARD_COUNT];
        for (int i = 0; i < SHARD_COUNT; i++)
            created[i] = executorFactory().localAware().withJmx("request").sequential("Shard-" + i);
        this.executors = created;
    }

    /** The pool, or {@code null} when routing is disabled (no shard threads exist). */
    public static ShardExecutors instance()
    {
        return instance;
    }

    public static int shardCount()
    {
        return SHARD_COUNT;
    }

    /** Total routed applies submitted to the shard executors since startup. */
    public long submittedTaskCount()
    {
        return submitted.get();
    }

    /** The executor index owned by the calling thread, or {@link #UNSET} if it is not a shard thread
     *  currently running a routed task. */
    public static int currentShardId()
    {
        return CURRENT_SHARD.get();
    }

    /** True iff the calling thread is the single writer for memtable shard {@code memtableShardId}.
     *  Shard {@code m} maps to executor {@code m % SHARD_COUNT}, so more shards than executors is safe:
     *  every write to shard {@code m} still lands on one thread. */
    public static boolean currentThreadIsOwnerOf(int memtableShardId)
    {
        return CURRENT_SHARD.get() == Math.floorMod(memtableShardId, SHARD_COUNT);
    }

    /** Submit {@code task} to the executor owning {@code memtableShardId}, tagging that thread with the
     *  shard id for the task's duration so {@link #currentThreadIsOwnerOf} answers correctly inside it. */
    public void execute(int memtableShardId, Runnable task)
    {
        int shard = Math.floorMod(memtableShardId, SHARD_COUNT);
        submitted.incrementAndGet();
        executors[shard].execute(shardTagged(shard, task));
    }

    /** As {@link #execute(int, Runnable)}, but carries {@code locals} (trace/client-warn state) into the
     *  routed task. Used when routing a whole verb from the inbound path, where the loop-side locals must
     *  survive the hop; the shard executors are {@code localAware()}, so the state propagates. */
    public void execute(ExecutorLocals locals, int memtableShardId, Runnable task)
    {
        int shard = Math.floorMod(memtableShardId, SHARD_COUNT);
        submitted.incrementAndGet();
        executors[shard].execute(locals, shardTagged(shard, task));
    }

    /** Wrap {@code task} so it runs with {@link #CURRENT_SHARD} set to {@code shard}, restored after. */
    private static Runnable shardTagged(int shard, Runnable task)
    {
        return () -> {
            CURRENT_SHARD.set(shard);
            try
            {
                task.run();
            }
            finally
            {
                CURRENT_SHARD.set(UNSET);
            }
        };
    }

    @VisibleForTesting
    SequentialExecutorPlus executorFor(int memtableShardId)
    {
        return executors[Math.floorMod(memtableShardId, SHARD_COUNT)];
    }

    /** Drain the shard executors gracefully (run queued applies, no interrupt) and await termination.
     *  Called after the feeding stages and before flush + commitlog stop, so every routed apply reaches
     *  the memtable and commitlog first. No-op when routing is disabled. */
    public static void drainAndAwait(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException
    {
        ShardExecutors current = instance;
        if (current != null)
            ExecutorUtils.shutdownAndWait(timeout, unit, Arrays.asList(current.executors));
    }

    @VisibleForTesting
    static void unsafeSetInstance(ShardExecutors replacement)
    {
        instance = replacement;
    }
}
