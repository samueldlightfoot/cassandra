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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.config.DatabaseDescriptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShardExecutorsTest
{
    @BeforeClass
    public static void setUpClass()
    {
        DatabaseDescriptor.daemonInitialization();
    }

    @Test
    public void currentShardIdUnsetOnNonShardThread()
    {
        assertEquals(-1, ShardExecutors.currentShardId());
        assertFalse(ShardExecutors.currentThreadIsOwnerOf(0));
    }

    @Test
    public void executeRunsOnOwningShardThread() throws Exception
    {
        int n = ShardExecutors.shardCount();
        Assume.assumeTrue("needs >= 2 shard executors", n >= 2);

        ShardExecutors se = new ShardExecutors();
        try
        {
            AtomicInteger observed = new AtomicInteger(-99);
            AtomicInteger ownsSelf = new AtomicInteger();
            AtomicInteger ownsOther = new AtomicInteger();
            AtomicInteger ownsWrapAround = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(1);
            se.execute(1, () -> {
                observed.set(ShardExecutors.currentShardId());
                ownsSelf.set(ShardExecutors.currentThreadIsOwnerOf(1) ? 1 : 0);
                ownsOther.set(ShardExecutors.currentThreadIsOwnerOf(0) ? 1 : 0);
                ownsWrapAround.set(ShardExecutors.currentThreadIsOwnerOf(1 + n) ? 1 : 0);
                done.countDown();
            });
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertEquals("runs on executor 1", 1, observed.get());
            assertEquals("owns its own shard", 1, ownsSelf.get());
            assertEquals("does not own shard 0", 0, ownsOther.get());
            assertEquals("owns the wrap-around shard 1+n (== 1 mod n)", 1, ownsWrapAround.get());
        }
        finally
        {
            drain(se);
        }
    }

    @Test
    public void moreMemtableShardsThanExecutorsMapModulo() throws Exception
    {
        int n = ShardExecutors.shardCount();
        Assume.assumeTrue(n >= 2);

        ShardExecutors se = new ShardExecutors();
        try
        {
            AtomicInteger observed = new AtomicInteger(-99);
            CountDownLatch done = new CountDownLatch(1);
            // memtable shard (n + 1) is owned by executor (n + 1) % n == 1
            se.execute(n + 1, () -> {
                observed.set(ShardExecutors.currentShardId());
                done.countDown();
            });
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertEquals(1, observed.get());
        }
        finally
        {
            drain(se);
        }
    }

    @Test
    public void threadLocalClearedAfterTask() throws Exception
    {
        ShardExecutors se = new ShardExecutors();
        try
        {
            // A routed task sets the thread-local; a subsequent raw task on the SAME executor thread
            // must observe it cleared (execute restores it in a finally).
            CountDownLatch first = new CountDownLatch(1);
            se.execute(0, first::countDown);
            assertTrue(first.await(30, TimeUnit.SECONDS));

            AtomicInteger afterId = new AtomicInteger(-99);
            CountDownLatch second = new CountDownLatch(1);
            se.executorFor(0).execute(() -> {
                afterId.set(ShardExecutors.currentShardId());
                second.countDown();
            });
            assertTrue(second.await(30, TimeUnit.SECONDS));
            assertEquals("thread-local reset to UNSET after the routed task", -1, afterId.get());
        }
        finally
        {
            drain(se);
        }
    }

    @Test
    public void drainTerminatesExecutors() throws Exception
    {
        ShardExecutors se = new ShardExecutors();
        ShardExecutors.unsafeSetInstance(se);
        try
        {
            ShardExecutors.drainAndAwait(30, TimeUnit.SECONDS);
            assertTrue(se.executorFor(0).isTerminated());
        }
        finally
        {
            ShardExecutors.unsafeSetInstance(null);
        }
    }

    private static void drain(ShardExecutors se) throws Exception
    {
        ShardExecutors.unsafeSetInstance(se);
        ShardExecutors.drainAndAwait(30, TimeUnit.SECONDS);
        ShardExecutors.unsafeSetInstance(null);
    }
}
