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
package org.apache.cassandra.utils.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import org.apache.cassandra.concurrent.ExecutorPlus;
import org.apache.cassandra.utils.WithResources;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class AsyncPromiseTest extends AbstractTestAsyncPromise
{
    @After
    public void shutdown()
    {
        exec.shutdownNow();
    }

    private static <V> List<Supplier<Promise<V>>> suppliers(AtomicInteger listeners, boolean includeUncancellable)
    {
        List<Supplier<Promise<V>>> cancellable = ImmutableList.of(
            () -> new AsyncPromise<>(),
            () -> new AsyncPromise<>(f -> listeners.incrementAndGet()),
            () -> AsyncPromise.withExecutor(TestInExecutor.INSTANCE));
        List<Supplier<Promise<V>>> uncancellable = ImmutableList.of(
            () -> AsyncPromise.uncancellable(),
            () -> AsyncPromise.uncancellable((GenericFutureListener<? extends Future<? super V>>) f -> listeners.incrementAndGet()),
            () -> AsyncPromise.uncancellable(MoreExecutors.directExecutor()),
            () -> AsyncPromise.uncancellable(TestInExecutor.INSTANCE)
        );

        if (!includeUncancellable)
            return cancellable;

        ImmutableList.Builder<Supplier<Promise<V>>> builder = ImmutableList.builder();
        builder.addAll(cancellable)
               .addAll(cancellable.stream().map(s -> (Supplier<Promise<V>>) () -> cancelSuccess(s.get())).collect(Collectors.toList()))
               .addAll(cancellable.stream().map(s -> (Supplier<Promise<V>>) () -> cancelExclusiveSuccess(s.get())).collect(Collectors.toList()))
               .addAll(uncancellable)
               .addAll(uncancellable.stream().map(s -> (Supplier<Promise<V>>) () -> cancelFailure(s.get())).collect(Collectors.toList()));
        return builder.build();
    }

    @Test
    public void testSuccess()
    {
        final AtomicInteger initialListeners = new AtomicInteger();
        List<Supplier<Promise<Integer>>> suppliers = suppliers(initialListeners, true);
        for (boolean tryOrSet : new boolean[]{ false, true })
            for (Integer v : new Integer[]{ null, 1 })
                for (Supplier<Promise<Integer>> supplier : suppliers)
                    testOneSuccess(supplier.get(), tryOrSet, v, 2);
        Assert.assertEquals(5 * 2 * 2, initialListeners.get());
    }

    @Test
    public void testFailure()
    {
        final AtomicInteger initialListeners = new AtomicInteger();
        List<Supplier<Promise<Integer>>> suppliers = suppliers(initialListeners, true);
        for (boolean tryOrSet : new boolean[] { false, true })
            for (Throwable v : new Throwable[] { null, new NullPointerException() })
                for (Supplier<Promise<Integer>> supplier : suppliers)
                    testOneFailure(supplier.get(), tryOrSet, v, 2);
        Assert.assertEquals(5 * 2 * 2, initialListeners.get());
    }


    @Test
    public void testCancellation()
    {
        final AtomicInteger initialListeners = new AtomicInteger();
        List<Supplier<Promise<Integer>>> suppliers = suppliers(initialListeners, false);
        for (boolean interruptIfRunning : new boolean[] { true, false })
            for (Supplier<Promise<Integer>> supplier : suppliers)
                testOneCancellation(supplier.get(), interruptIfRunning, 2);
        Assert.assertEquals(2, initialListeners.get());
    }


    @Test
    public void testTimeout()
    {
        final AtomicInteger initialListeners = new AtomicInteger();
        List<Supplier<Promise<Integer>>> suppliers = suppliers(initialListeners, true);
        for (Supplier<Promise<Integer>> supplier : suppliers)
            testOneTimeout(supplier.get());
        Assert.assertEquals(0, initialListeners.get());
    }

    @Test
    public void testAddCallbackBiConsumerAlreadyDone()
    {
        // Attach-on-done fast path: on an already-terminal promise the callback must run inline and
        // deliver exactly (value, null) on success / (null, cause) on failure, once.
        for (boolean tryOrSet : new boolean[]{ false, true })
        {
            AsyncPromise<Integer> s = new AsyncPromise<>();
            if (tryOrSet) s.trySuccess(7); else s.setSuccess(7);
            List<Object[]> got = new ArrayList<>();
            s.addCallback((v, t) -> got.add(new Object[]{ v, t }));
            Assert.assertEquals("callback must fire inline exactly once", 1, got.size());
            Assert.assertEquals(Integer.valueOf(7), got.get(0)[0]);
            Assert.assertNull(got.get(0)[1]);

            AsyncPromise<Integer> f = new AsyncPromise<>();
            RuntimeException ex = new RuntimeException("boom");
            if (tryOrSet) f.tryFailure(ex); else f.setFailure(ex);
            List<Object[]> gotf = new ArrayList<>();
            f.addCallback((v, t) -> gotf.add(new Object[]{ v, t }));
            Assert.assertEquals(1, gotf.size());
            Assert.assertNull(gotf.get(0)[0]);
            Assert.assertSame(ex, gotf.get(0)[1]);
        }
    }

    @Test
    public void testAddCallbackBiConsumerNotYetDone()
    {
        // Not terminal at attach time: falls through to the listener-node path and fires on completion.
        AsyncPromise<Integer> p = new AsyncPromise<>();
        List<Object> got = new ArrayList<>();
        p.addCallback((v, t) -> got.add(v));
        Assert.assertTrue("must not fire before completion", got.isEmpty());
        p.setSuccess(9);
        Assert.assertEquals(ImmutableList.of(9), got);
    }

    @Test
    public void testAddCallbackBiConsumerInlineReentrant()
    {
        // The invariant the reverted naive inline form broke: a callback that re-enters and adds a
        // callback while running on the fast path must fire that addition once, deferred in order —
        // never nested or twice. cb0 runs inline; its re-entrant cb2 defers behind NOTIFYING and drains
        // after cb0 returns; cb1 is a fresh fast-path attach on the now-empty slot. Order: 0, 2, 1.
        for (boolean tryOrSet : new boolean[]{ false, true })
        {
            AsyncPromise<Integer> p = new AsyncPromise<>();
            if (tryOrSet) p.trySuccess(1); else p.setSuccess(1);
            List<Integer> order = new ArrayList<>();
            p.addCallback((v, t) -> { order.add(0); p.addCallback((v2, t2) -> order.add(2)); });
            p.addCallback((v, t) -> order.add(1));
            Assert.assertEquals(ImmutableList.of(0, 2, 1), order);
        }
    }

    @Test
    public void testMapInlineOnAlreadyDone()
    {
        // map on an already-terminal future applies the mapper inline (fast path) and yields the mapped value.
        AsyncPromise<Integer> p = new AsyncPromise<>();
        p.setSuccess(3);
        org.apache.cassandra.utils.concurrent.Future<Integer> mapped = p.map(v -> v + 100);
        Assert.assertTrue(mapped.isDone());
        Assert.assertEquals(Integer.valueOf(103), mapped.getNow());
    }

    @Test
    public void testMapInlineReentrant()
    {
        // A mapper that re-enters and adds a callback while running on the map fast path must fire that
        // callback once, deferred in order, and still produce the mapped value. Order: 0 (mapper), 2
        // (re-entrant callback, drained after the mapper), 1 (a later fast-path attach). Value: 1 + 100.
        for (boolean tryOrSet : new boolean[]{ false, true })
        {
            AsyncPromise<Integer> p = new AsyncPromise<>();
            if (tryOrSet) p.trySuccess(1); else p.setSuccess(1);
            List<Integer> order = new ArrayList<>();
            org.apache.cassandra.utils.concurrent.Future<Integer> mapped =
                p.map(v -> { order.add(0); p.addCallback((v2, t2) -> order.add(2)); return v + 100; });
            p.addCallback((v, t) -> order.add(1));
            Assert.assertEquals(ImmutableList.of(0, 2, 1), order);
            Assert.assertEquals(Integer.valueOf(101), mapped.getNow());
        }
    }

    private static final class TestInExecutor implements ExecutorPlus
    {
        static final TestInExecutor INSTANCE = new TestInExecutor();
        @Override
        public void shutdown()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Runnable> shutdownNow()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isShutdown()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isTerminated()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> org.apache.cassandra.utils.concurrent.Future<T> submit(Callable<T> task)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> org.apache.cassandra.utils.concurrent.Future<T> submit(Runnable task, T result)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.apache.cassandra.utils.concurrent.Future<?> submit(Runnable task)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(WithResources withResources, Runnable task)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> org.apache.cassandra.utils.concurrent.Future<T> submit(WithResources withResources, Callable<T> task)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.apache.cassandra.utils.concurrent.Future<?> submit(WithResources withResources, Runnable task)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> org.apache.cassandra.utils.concurrent.Future<T> submit(WithResources withResources, Runnable task, T result)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean inExecutor()
        {
            return true;
        }

        @Override
        public void execute(Runnable command)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getCorePoolSize()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setCorePoolSize(int newCorePoolSize)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getMaximumPoolSize()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setMaximumPoolSize(int newMaximumPoolSize)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getActiveTaskCount()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getCompletedTaskCount()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getPendingTaskCount()
        {
            throw new UnsupportedOperationException();
        }
    }

}

