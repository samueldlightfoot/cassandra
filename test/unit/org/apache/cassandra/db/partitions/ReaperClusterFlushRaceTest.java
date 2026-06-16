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

package org.apache.cassandra.db.partitions;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.db.Clustering;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.DeletionTime;
import org.apache.cassandra.db.marshal.SetType;
import org.apache.cassandra.db.marshal.TimestampType;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.db.memtable.AbstractAllocatorMemtable;
import org.apache.cassandra.db.rows.BTreeRow;
import org.apache.cassandra.db.rows.BufferCell;
import org.apache.cassandra.db.rows.Cell;
import org.apache.cassandra.db.rows.CellPath;
import org.apache.cassandra.db.rows.ColumnData;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.index.transactions.UpdateTransaction;
import org.apache.cassandra.schema.ColumnMetadata;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.schema.TableMetadataRef;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.concurrent.ImmediateFuture;
import org.apache.cassandra.utils.concurrent.OpOrder;
import org.apache.cassandra.utils.memory.Cloner;
import org.apache.cassandra.utils.memory.MemtableAllocator;
import org.apache.cassandra.utils.memory.MemtableCleaner;
import org.apache.cassandra.utils.memory.MemtablePool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASSANDRA-21390 — exercises memory accounting in {@link MemtableAllocator.SubAllocator}
 * and {@link BTreePartitionUpdater} for the {@code caas_repair.cluster} write shape:
 * a full-row INSERT with a {@code SET<TEXT>} column whose complex deletion at
 * {@code ts-1} shadows existing set elements at {@code ts}.
 *
 * Under this shape, a partition merge drives {@code BTreePartitionUpdater.heapSize}
 * transiently negative — shadow-deletes run before their matching inserts.
 * {@code reportAllocatedMemory()} runs unconditionally from the {@code addAll} and
 * {@code mergePartitions} {@code finally} blocks. An exception while {@code heapSize}
 * is negative passes that partial value to {@code SubAllocator.adjust}, routing through
 * {@code released} and decrementing {@code owns} for bytes still referenced by the
 * unchanged partition tree.
 *
 * All tests pin to {@code offheap_objects}.
 *
 * Knobs (system properties):
 * <ul>
 *   <li>{@code repro.f.iterations}            — rounds per writer thread, F1/F2/F3 (default 50000)</li>
 *   <li>{@code repro.f.concurrency}           — writer threads, F2/F3 (default 36)</li>
 *   <li>{@code repro.f.adversaryDelayNanos}   — busy-spin delay in the F2 adversary (default 0)</li>
 *   <li>{@code repro.f4.iterations}           — ops per thread, F4/F5 (default 1000)</li>
 *   <li>{@code repro.f4.concurrency}          — writer threads, F4/F5 (default 8)</li>
 * </ul>
 */
public class ReaperClusterFlushRaceTest
{
    private static final int ITERATIONS = Integer.getInteger("repro.f.iterations", 50_000);
    private static final int CONCURRENCY = Integer.getInteger("repro.f.concurrency", 36);
    private static final int ADVERSARY_DELAY_NANOS = Integer.getInteger("repro.f.adversaryDelayNanos", 0);

    /**
     * Tests that drive real merges through {@link AtomicBTreePartition#addAll} allocate
     * offheap through the cloner. {@link #DUMMY_CLEANER} returns a failed future, so
     * running close to the allocator limit deadlocks the test. These tests use a
     * smaller iteration count than the synthetic-{@code adjust} tests above.
     */
    private static final int F4_ITERATIONS = Integer.getInteger("repro.f4.iterations", 1_000);
    private static final int F4_CONCURRENCY = Integer.getInteger("repro.f4.concurrency", 8);

    private static final long INITIAL_TS_MICROS = 1_700_000_000_000_000L;

    private static final long HEAP_LIMIT = 1L << 30;       // 1 GB
    private static final long OFF_HEAP_LIMIT = 1L << 30;   // 1 GB
    private static final float MEMTABLE_CLEANUP_THRESHOLD = 0.25f;
    private static final MemtableCleaner DUMMY_CLEANER =
        () -> ImmediateFuture.failure(new IllegalStateException());

    private static TableMetadata metadata;
    private static DecoratedKey partitionKey;
    private static ColumnMetadata partitionerCol;
    private static ColumnMetadata propertiesCol;
    private static ColumnMetadata stateCol;
    private static ColumnMetadata lastContactCol;
    private static ColumnMetadata seedHostsCol;

    @BeforeClass
    public static void setUp()
    {
        DatabaseDescriptor.daemonInitialization();
        metadata = TableMetadata.builder("caas_repair", "cluster")
                                 .addPartitionKeyColumn("name", UTF8Type.instance)
                                 .addRegularColumn("partitioner", UTF8Type.instance)
                                 .addRegularColumn("properties", UTF8Type.instance)
                                 .addRegularColumn("state", UTF8Type.instance)
                                 .addRegularColumn("last_contact", TimestampType.instance)
                                 .addRegularColumn("seed_hosts", SetType.getInstance(UTF8Type.instance, true))
                                 .build();
        partitionKey = DatabaseDescriptor.getPartitioner().decorateKey(ByteBufferUtil.bytes("test-cluster"));
        partitionerCol = metadata.getColumn(new ColumnIdentifier("partitioner", false));
        propertiesCol = metadata.getColumn(new ColumnIdentifier("properties", false));
        stateCol = metadata.getColumn(new ColumnIdentifier("state", false));
        lastContactCol = metadata.getColumn(new ColumnIdentifier("last_contact", false));
        seedHostsCol = metadata.getColumn(new ColumnIdentifier("seed_hosts", false));
    }

    // ------------------------------------------------------------------------
    // F1 — Late released() during DISCARDING
    // ------------------------------------------------------------------------

    /**
     * Allocates bytes on a LIVE allocator, transitions it to DISCARDING, then releases
     * the bytes. {@code released()} is a no-op outside LIVE, so {@code owns} stays
     * elevated until {@code setDiscarded()} resets it to zero. Asserts that the
     * LIVE→DISCARDING transition never drives {@code owns} below zero.
     */
    @Test
    public void f1_lateReleasedDuringDiscardingStaysNonNegative() throws InterruptedException
    {
        MemtablePool pool = AbstractAllocatorMemtable.createMemtableAllocatorPoolInternal(
            Config.MemtableAllocationType.offheap_objects, HEAP_LIMIT, OFF_HEAP_LIMIT,
            MEMTABLE_CLEANUP_THRESHOLD, DUMMY_CLEANER);
        OpOrder opOrder = new OpOrder();
        opOrder.start();

        AtomicLong minOnHeap = new AtomicLong(0);
        AtomicLong maxOnHeap = new AtomicLong(0);
        int rounds = Math.min(ITERATIONS, 1_000);   // F1 is sequential; smaller round count is sufficient

        try
        {
            for (int round = 0; round < rounds; round++)
            {
                MemtableAllocator allocator = pool.newAllocator("f1-round-" + round);
                OpOrder.Group group = opOrder.getCurrent();

                long size = 256L + (round % 1024);
                allocator.onHeap().adjust(size, group);                      // owns += size
                track(allocator.onHeap().owns(), minOnHeap, maxOnHeap);

                allocator.setDiscarding();                                   // LIVE → DISCARDING
                track(allocator.onHeap().owns(), minOnHeap, maxOnHeap);

                allocator.onHeap().adjust(-size, group);                     // released(): NO-OP under DISCARDING
                track(allocator.onHeap().owns(), minOnHeap, maxOnHeap);

                allocator.setDiscarded();                                    // DISCARDING → DISCARDED, releaseAll() → owns = 0
                track(allocator.onHeap().owns(), minOnHeap, maxOnHeap);

                assertThat(allocator.onHeap().owns())
                    .as("round %d: after setDiscarded owns must be 0", round)
                    .isZero();
            }

            System.out.println(String.format(
                "[F1] rounds=%d minOnHeap=%d maxOnHeap=%d (expected: minOnHeap >= 0, maxOnHeap > 0 demonstrating +drift)",
                rounds, minOnHeap.get(), maxOnHeap.get()));

            assertThat(minOnHeap.get()).as("F1: owns must never go negative").isGreaterThanOrEqualTo(0L);
        }
        finally
        {
            shutdownPool(pool);
        }
    }

    // ------------------------------------------------------------------------
    // F2 — Concurrent mixed-sign adjust() interleaving (mechanism demonstration)
    // ------------------------------------------------------------------------

    /**
     * Runs N writer threads against one LIVE {@link MemtableAllocator.SubAllocator},
     * each performing paired {@code adjust(+X)}/{@code adjust(-X)} over many rounds.
     * Per thread, acquires balance releases. An adversary thread polls {@code owns()}
     * and records the minimum observed value. Asserts that concurrent paired
     * acquire/release atomics cannot themselves drive {@code owns} below zero.
     */
    @Test
    public void f2_concurrentMixedSignAdjustOnLiveAllocator() throws InterruptedException
    {
        MemtablePool pool = AbstractAllocatorMemtable.createMemtableAllocatorPoolInternal(
            Config.MemtableAllocationType.offheap_objects, HEAP_LIMIT, OFF_HEAP_LIMIT,
            MEMTABLE_CLEANUP_THRESHOLD, DUMMY_CLEANER);
        OpOrder opOrder = new OpOrder();
        opOrder.start();
        MemtableAllocator allocator = pool.newAllocator("f2-shared");

        AtomicLong minOnHeap = new AtomicLong(0);
        AtomicLong maxOnHeap = new AtomicLong(0);
        AtomicBoolean stopAdversary = new AtomicBoolean(false);
        AtomicInteger firstNegativeOpIdx = new AtomicInteger(-1);

        Thread adversary = new Thread(() -> {
            while (!stopAdversary.get())
            {
                long o = allocator.onHeap().owns();
                track(o, minOnHeap, maxOnHeap);
                if (o < 0)
                    firstNegativeOpIdx.compareAndSet(-1, -2);
                if (ADVERSARY_DELAY_NANOS > 0)
                    java.util.concurrent.locks.LockSupport.parkNanos(ADVERSARY_DELAY_NANOS);
            }
        }, "f2-adversary");
        adversary.setDaemon(true);
        adversary.start();

        AtomicInteger opCounter = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        Thread[] writers = new Thread[CONCURRENCY];
        for (int t = 0; t < CONCURRENCY; t++)
        {
            final int tid = t;
            writers[t] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException ie) { return; }
                OpOrder.Group group = opOrder.getCurrent();
                long seed = 0x9E3779B97F4A7C15L ^ (tid * 0x100000001B3L);
                for (int i = 0; i < ITERATIONS; i++)
                {
                    long size = 16L + (Math.abs(seed) % 4096L);
                    allocator.onHeap().adjust(size, group);          // +X
                    long mid = allocator.onHeap().owns();
                    track(mid, minOnHeap, maxOnHeap);
                    if (mid < 0)
                        firstNegativeOpIdx.compareAndSet(-1, opCounter.get());
                    allocator.onHeap().adjust(-size, group);         // -X
                    long post = allocator.onHeap().owns();
                    track(post, minOnHeap, maxOnHeap);
                    if (post < 0)
                        firstNegativeOpIdx.compareAndSet(-1, opCounter.get());
                    opCounter.incrementAndGet();
                    seed = seed * 6364136223846793005L + 1442695040888963407L;
                }
            }, "f2-writer-" + t);
            writers[t].start();
        }

        try
        {
            start.countDown();
            for (Thread w : writers) w.join();
        }
        finally
        {
            stopAdversary.set(true);
            adversary.join(5000);
        }

        long finalOwns = allocator.onHeap().owns();
        System.out.println(String.format(
            "[F2] threads=%d iterPerThread=%d totalOps=%d minOnHeap=%d maxOnHeap=%d finalOnHeap=%d firstNegativeOpIdx=%d",
            CONCURRENCY, ITERATIONS, opCounter.get(), minOnHeap.get(), maxOnHeap.get(), finalOwns, firstNegativeOpIdx.get()));

        try
        {
            assertThat(minOnHeap.get())
                .as("F2: owns went negative under concurrent mixed-sign adjust on LIVE allocator (firstNegativeOpIdx=%d, finalOwns=%d)",
                    firstNegativeOpIdx.get(), finalOwns)
                .isGreaterThanOrEqualTo(0L);
        }
        finally
        {
            shutdownAllocator(allocator);
            shutdownPool(pool);
        }
    }

    // ------------------------------------------------------------------------
    // F3 — Unmatched adjust(-Y) drives owns below zero
    // ------------------------------------------------------------------------

    /**
     * Injects {@code adjust(-Y)} calls with no matching positive acquire to confirm
     * that {@link MemtableAllocator.SubAllocator#released} has no floor: a
     * cumulative release larger than the current {@code owns} drives the counter
     * below zero. This test is expected to fail; it documents the invariant the
     * suite exercises through real merge paths in the tests that follow.
     */
    @Test
    public void f3_btreePartitionUpdaterAsymmetricFinally() throws InterruptedException
    {
        MemtablePool pool = AbstractAllocatorMemtable.createMemtableAllocatorPoolInternal(
            Config.MemtableAllocationType.offheap_objects, HEAP_LIMIT, OFF_HEAP_LIMIT,
            MEMTABLE_CLEANUP_THRESHOLD, DUMMY_CLEANER);
        OpOrder opOrder = new OpOrder();
        opOrder.start();
        MemtableAllocator allocator = pool.newAllocator("f3-shared");

        // Seed some legitimate baseline owns so the test isn't trivially negative on the first injection.
        long baseline = 1L << 20;   // 1 MB
        allocator.onHeap().adjust(baseline, opOrder.getCurrent());

        AtomicLong minOnHeap = new AtomicLong(allocator.onHeap().owns());
        AtomicLong maxOnHeap = new AtomicLong(allocator.onHeap().owns());
        AtomicInteger firstNegativeOpIdx = new AtomicInteger(-1);

        final int INJECT_EVERY = 1000;
        final long NORMAL_SIZE = 64L;
        final long INJECTED_LEAK = 4096L;   // simulates partial-merge heapSize: delete(big existing) without matching insert

        AtomicInteger opCounter = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);

        Thread[] writers = new Thread[CONCURRENCY];
        for (int t = 0; t < CONCURRENCY; t++)
        {
            final int tid = t;
            writers[t] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException ie) { return; }
                OpOrder.Group group = opOrder.getCurrent();
                for (int i = 0; i < ITERATIONS; i++)
                {
                    // Balanced acquire/release pair.
                    allocator.onHeap().adjust(NORMAL_SIZE, group);
                    allocator.onHeap().adjust(-NORMAL_SIZE, group);

                    // Periodic release with no matching acquire.
                    if (((tid * ITERATIONS + i) % INJECT_EVERY) == 0)
                        allocator.onHeap().adjust(-INJECTED_LEAK, group);

                    long obs = allocator.onHeap().owns();
                    track(obs, minOnHeap, maxOnHeap);
                    if (obs < 0)
                        firstNegativeOpIdx.compareAndSet(-1, opCounter.get());
                    opCounter.incrementAndGet();
                }
            }, "f3-writer-" + t);
            writers[t].start();
        }

        start.countDown();
        for (Thread w : writers) w.join();

        long totalInjected = (long) CONCURRENCY * (ITERATIONS / INJECT_EVERY + 1) * INJECTED_LEAK;
        long finalOwns = allocator.onHeap().owns();
        System.out.println(String.format(
            "[F3] threads=%d iterPerThread=%d totalOps=%d baseline=%d totalInjectedLeak=%d minOnHeap=%d maxOnHeap=%d finalOnHeap=%d firstNegativeOpIdx=%d",
            CONCURRENCY, ITERATIONS, opCounter.get(), baseline, totalInjected,
            minOnHeap.get(), maxOnHeap.get(), finalOwns, firstNegativeOpIdx.get()));

        try
        {
            assertThat(minOnHeap.get())
                .as("F3: owns went negative under asymmetric-finally simulation (firstNegativeOpIdx=%d, finalOwns=%d, totalInjectedLeak=%d, baseline=%d)",
                    firstNegativeOpIdx.get(), finalOwns, totalInjected, baseline)
                .isGreaterThanOrEqualTo(0L);
        }
        finally
        {
            shutdownAllocator(allocator);
            shutdownPool(pool);
        }
    }

    // ------------------------------------------------------------------------
    // F4 — Real AtomicBTreePartition.addAll with exception-interrupted merge
    // ------------------------------------------------------------------------

    /**
     * Drives {@link AtomicBTreePartition#addAll} with an {@link UpdateTransaction}
     * that throws on every Nth call. The indexer fires after
     * {@link BTreePartitionUpdater} has already updated {@code heapSize} for the
     * current cell, so the partial accumulator is symmetric — it under-accounts
     * pending inserts rather than going net-negative. Asserts that an indexer fault
     * alone does not drive {@code owns} below zero.
     */
    @Test
    public void f4_realAddAllWithExceptionInterruptedMerge() throws InterruptedException
    {
        MemtablePool pool = AbstractAllocatorMemtable.createMemtableAllocatorPoolInternal(
            Config.MemtableAllocationType.offheap_objects, HEAP_LIMIT, OFF_HEAP_LIMIT,
            MEMTABLE_CLEANUP_THRESHOLD, DUMMY_CLEANER);
        MemtableAllocator allocator = pool.newAllocator("f4-shared");
        OpOrder opOrder = new OpOrder();
        opOrder.start();

        TableMetadataRef metadataRef = TableMetadataRef.forOfflineTools(metadata);
        AtomicBTreePartition partition = new AtomicBTreePartition(metadataRef, partitionKey, allocator);

        AtomicLong minOnHeap = new AtomicLong(allocator.onHeap().owns());
        AtomicLong maxOnHeap = new AtomicLong(allocator.onHeap().owns());
        AtomicInteger firstNegativeIdx = new AtomicInteger(-1);
        AtomicInteger exceptionCount = new AtomicInteger(0);
        AtomicLong tsCounter = new AtomicLong(INITIAL_TS_MICROS);
        AtomicInteger opCounter = new AtomicInteger(0);

        // Throw every Nth indexer call. Tuned so most writes succeed and a handful interrupt mid-merge.
        final int THROW_EVERY = 97;

        CountDownLatch start = new CountDownLatch(1);
        Thread[] writers = new Thread[F4_CONCURRENCY];
        for (int t = 0; t < F4_CONCURRENCY; t++)
        {
            writers[t] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException ie) { return; }
                OpOrder.Group group = opOrder.getCurrent();
                Cloner cloner = allocator.cloner(group);
                AtomicInteger localCounter = new AtomicInteger(0);
                ThrowingIndexer indexer = new ThrowingIndexer(localCounter, THROW_EVERY);

                for (int i = 0; i < F4_ITERATIONS; i++)
                {
                    long ts = tsCounter.getAndIncrement();
                    PartitionUpdate update = makeReaperInsert(ts);
                    try
                    {
                        partition.addAll(update, cloner, group, indexer);
                    }
                    catch (RuntimeException expected)
                    {
                        exceptionCount.incrementAndGet();
                    }
                    long obs = allocator.onHeap().owns();
                    track(obs, minOnHeap, maxOnHeap);
                    if (obs < 0)
                        firstNegativeIdx.compareAndSet(-1, opCounter.get());
                    opCounter.incrementAndGet();
                }
            }, "f4-writer-" + t);
            writers[t].start();
        }

        start.countDown();
        for (Thread w : writers) w.join();

        long finalOwns = allocator.onHeap().owns();
        System.out.println(String.format(
            "[F4] threads=%d iterPerThread=%d totalOps=%d throwEvery=%d totalExceptions=%d minOnHeap=%d maxOnHeap=%d finalOnHeap=%d firstNegativeIdx=%d",
            F4_CONCURRENCY, F4_ITERATIONS, opCounter.get(), THROW_EVERY, exceptionCount.get(),
            minOnHeap.get(), maxOnHeap.get(), finalOwns, firstNegativeIdx.get()));

        try
        {
            assertThat(minOnHeap.get())
                .as("F4: owns went negative under real AtomicBTreePartition.addAll with exception-interrupted merge (firstNegativeIdx=%d, finalOwns=%d, totalExceptions=%d)",
                    firstNegativeIdx.get(), finalOwns, exceptionCount.get())
                .isGreaterThanOrEqualTo(0L);
        }
        finally
        {
            shutdownAllocator(allocator);
            shutdownPool(pool);
        }
    }

    // ------------------------------------------------------------------------
    // F5 — Concurrent addAll with a throwing cloner
    // ------------------------------------------------------------------------

    /**
     * Drives concurrent {@link AtomicBTreePartition#addAll} with a {@link Cloner} that
     * throws on every Nth {@code clone(Cell)}. Writers use monotonically advancing
     * timestamps so each iteration's complex deletion at {@code ts-1} shadows the
     * previous iteration's SET cells, forcing the merge through the shadow→insert
     * window. {@code delete(existing)} decrements {@code heapSize} before the
     * matching {@code cloner.clone(insert)} runs, so a clone throw leaves the
     * accumulator net-negative for that column. Asserts that {@code owns} stays
     * non-negative on both onHeap and offHeap.
     */
    @Test
    public void f5_realAddAllWithThrowingCloner() throws InterruptedException
    {
        MemtablePool pool = AbstractAllocatorMemtable.createMemtableAllocatorPoolInternal(
            Config.MemtableAllocationType.offheap_objects, HEAP_LIMIT, OFF_HEAP_LIMIT,
            MEMTABLE_CLEANUP_THRESHOLD, DUMMY_CLEANER);
        MemtableAllocator allocator = pool.newAllocator("f5-shared");
        OpOrder opOrder = new OpOrder();
        opOrder.start();

        TableMetadataRef metadataRef = TableMetadataRef.forOfflineTools(metadata);
        AtomicBTreePartition partition = new AtomicBTreePartition(metadataRef, partitionKey, allocator);

        // Step timestamps by 1 second to ensure each iteration's complex-deletion at (ts-1)
        // shadows the previous iteration's SET cells, forcing the merge to call delete() on
        // shadowed cells BEFORE the per-cell clone runs.
        final long TS_STEP = 1_000_000L;

        // Seed the partition with an initial insert so subsequent writes hit the merge path.
        AtomicLong tsCounter = new AtomicLong(INITIAL_TS_MICROS);
        {
            OpOrder.Group g0 = opOrder.getCurrent();
            Cloner c0 = allocator.cloner(g0);
            partition.addAll(makeReaperInsert(tsCounter.getAndAdd(TS_STEP)), c0, g0, UpdateTransaction.NO_OP);
        }

        AtomicLong minOnHeap = new AtomicLong(allocator.onHeap().owns());
        AtomicLong maxOnHeap = new AtomicLong(allocator.onHeap().owns());
        AtomicLong minOffHeap = new AtomicLong(allocator.offHeap().owns());
        AtomicLong maxOffHeap = new AtomicLong(allocator.offHeap().owns());
        AtomicInteger firstNegativeIdx = new AtomicInteger(-1);
        AtomicInteger exceptionCount = new AtomicInteger(0);
        AtomicInteger opCounter = new AtomicInteger(0);

        final int THROW_EVERY_CLONE = 31;

        CountDownLatch start = new CountDownLatch(1);
        Thread[] writers = new Thread[F4_CONCURRENCY];
        for (int t = 0; t < F4_CONCURRENCY; t++)
        {
            writers[t] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException ie) { return; }
                OpOrder.Group group = opOrder.getCurrent();
                Cloner realCloner = allocator.cloner(group);
                AtomicInteger localCloneCounter = new AtomicInteger(0);
                Cloner throwing = new ThrowingCloner(realCloner, localCloneCounter, THROW_EVERY_CLONE);

                for (int i = 0; i < F4_ITERATIONS; i++)
                {
                    long ts = tsCounter.getAndAdd(TS_STEP);
                    PartitionUpdate update = makeReaperInsert(ts);
                    try
                    {
                        partition.addAll(update, throwing, group, UpdateTransaction.NO_OP);
                    }
                    catch (RuntimeException expected)
                    {
                        exceptionCount.incrementAndGet();
                    }
                    long onH = allocator.onHeap().owns();
                    long offH = allocator.offHeap().owns();
                    track(onH, minOnHeap, maxOnHeap);
                    track(offH, minOffHeap, maxOffHeap);
                    if (onH < 0 || offH < 0)
                        firstNegativeIdx.compareAndSet(-1, opCounter.get());
                    opCounter.incrementAndGet();
                }
            }, "f5-writer-" + t);
            writers[t].start();
        }

        start.countDown();
        for (Thread w : writers) w.join();

        long finalOn = allocator.onHeap().owns();
        long finalOff = allocator.offHeap().owns();
        System.out.println(String.format(
            "[F5] threads=%d iterPerThread=%d totalOps=%d throwEveryClone=%d totalExceptions=%d minOnHeap=%d maxOnHeap=%d finalOnHeap=%d minOffHeap=%d maxOffHeap=%d finalOffHeap=%d firstNegativeIdx=%d",
            F4_CONCURRENCY, F4_ITERATIONS, opCounter.get(), THROW_EVERY_CLONE, exceptionCount.get(),
            minOnHeap.get(), maxOnHeap.get(), finalOn,
            minOffHeap.get(), maxOffHeap.get(), finalOff,
            firstNegativeIdx.get()));

        try
        {
            assertThat(minOnHeap.get())
                .as("F5: onHeap owns went negative under ThrowingCloner mid-merge (firstNegativeIdx=%d, finalOnHeap=%d, exceptions=%d)",
                    firstNegativeIdx.get(), finalOn, exceptionCount.get())
                .isGreaterThanOrEqualTo(0L);
            assertThat(minOffHeap.get())
                .as("F5: offHeap owns went negative under ThrowingCloner mid-merge (firstNegativeIdx=%d, finalOffHeap=%d, exceptions=%d)",
                    firstNegativeIdx.get(), finalOff, exceptionCount.get())
                .isGreaterThanOrEqualTo(0L);
        }
        finally
        {
            shutdownAllocator(allocator);
            shutdownPool(pool);
        }
    }

    /**
     * Delegates to a real {@link Cloner} and throws {@link RuntimeException} on every
     * Nth {@code clone(Cell)} call. {@link DecoratedKey} and {@link Clustering} clones
     * pass through untouched so partition setup is unaffected.
     */
    private static final class ThrowingCloner implements Cloner
    {
        private final Cloner delegate;
        private final AtomicInteger counter;
        private final int throwEvery;

        ThrowingCloner(Cloner delegate, AtomicInteger counter, int throwEvery)
        {
            this.delegate = delegate;
            this.counter = counter;
            this.throwEvery = throwEvery;
        }

        @Override public DecoratedKey clone(DecoratedKey key) { return delegate.clone(key); }
        @Override public Clustering<?> clone(Clustering<?> clustering) { return delegate.clone(clustering); }
        @Override public int estimateCloneSize(Clustering<?> clustering) { return delegate.estimateCloneSize(clustering); }
        @Override public Cell<?> clone(Cell<?> cell)
        {
            int c = counter.incrementAndGet();
            if (c % throwEvery == 0)
                throw new RuntimeException("simulated mid-merge cloner fault on call " + c);
            return delegate.clone(cell);
        }
        @Override public int estimateCloneSize(Cell<?> cell) { return delegate.estimateCloneSize(cell); }
        @Override public boolean isContextAwareCloningSupported() { return delegate.isContextAwareCloningSupported(); }
        @Override public Cloner createContextAwareCloner(int estimatedCloneSize) { return this; }
        @Override public void adjustUnused() { delegate.adjustUnused(); }
    }

    /**
     * Throws {@link RuntimeException} on every Nth {@code onInserted} or {@code onUpdated}
     * call. All other {@link UpdateTransaction} methods are no-ops.
     */
    private static final class ThrowingIndexer implements UpdateTransaction
    {
        private final AtomicInteger counter;
        private final int throwEvery;

        ThrowingIndexer(AtomicInteger counter, int throwEvery)
        {
            this.counter = counter;
            this.throwEvery = throwEvery;
        }

        private void maybeThrow()
        {
            int c = counter.incrementAndGet();
            if (c % throwEvery == 0)
                throw new RuntimeException("simulated mid-merge indexer fault");
        }

        @Override public void start() {}
        @Override public void onPartitionDeletion(DeletionTime deletionTime) {}
        @Override public void onRangeTombstone(org.apache.cassandra.db.RangeTombstone rangeTombstone) {}
        @Override public void onInserted(Row row) { maybeThrow(); }
        @Override public void onUpdated(Row existing, Row updated) { maybeThrow(); }
        @Override public void commit() {}
    }

    // ------------------------------------------------------------------------
    // F8 — Deterministic reproducer through AtomicBTreePartition.addAll
    // ------------------------------------------------------------------------

    /**
     * Drives {@link AtomicBTreePartition#addAll} with a wrapper {@link Cloner} that
     * throws on its fifth {@code clone(Cell)} call per iteration. On the schema used
     * here, the first four clones are the four simple-cell merges and leave
     * {@code heapSize} at zero; the fifth lands on the first complex-column insert,
     * after the three shadow-deletes plus the negative {@code onAllocatedOnHeap}
     * have pushed {@code heapSize} to its minimum. The throw causes {@code addAll}'s
     * {@code finally} block to call {@code reportAllocatedMemory()} with that partial
     * value, which routes through {@code SubAllocator.released}. Asserts that
     * {@code owns} never goes negative.
     *
     * Real call sites that can throw at this point include OOM from
     * {@link org.apache.cassandra.utils.memory.NativeAllocator} and
     * interrupt-induced exceptions in blocking allocation.
     */
    @Test
    public void f8_realClonerThrowPath()
    {
        MemtablePool pool = AbstractAllocatorMemtable.createMemtableAllocatorPoolInternal(
            Config.MemtableAllocationType.offheap_objects, HEAP_LIMIT, OFF_HEAP_LIMIT,
            MEMTABLE_CLEANUP_THRESHOLD, DUMMY_CLEANER);
        MemtableAllocator allocator = pool.newAllocator("f8-shared");
        OpOrder opOrder = new OpOrder();
        opOrder.start();
        OpOrder.Group group = opOrder.getCurrent();
        Cloner realCloner = allocator.cloner(group);

        TableMetadataRef metadataRef = TableMetadataRef.forOfflineTools(metadata);
        AtomicBTreePartition partition = new AtomicBTreePartition(metadataRef, partitionKey, allocator);

        long ts = INITIAL_TS_MICROS;
        final long TS_STEP = 1_000_000L;
        final int ITER = 100;

        // Seed
        partition.addAll(makeReaperInsert(ts), realCloner, group, UpdateTransaction.NO_OP);
        ts += TS_STEP;

        long initialOwns = allocator.onHeap().owns();
        System.out.println(String.format("[F8] seeded onHeap=%d", initialOwns));

        long minObserved = initialOwns;
        int throws_ = 0;
        for (int i = 0; i < ITER; i++)
        {
            // Per-iteration counter resets so we deterministically throw on the 5th clone of each merge.
            AtomicInteger callCounter = new AtomicInteger(0);
            Cloner throwOnFifth = new Cloner()
            {
                @Override public DecoratedKey clone(DecoratedKey key) { return realCloner.clone(key); }
                @Override public Clustering<?> clone(Clustering<?> c)  { return realCloner.clone(c); }
                @Override public int estimateCloneSize(Clustering<?> clustering) { return realCloner.estimateCloneSize(clustering); }
                @Override public Cell<?> clone(Cell<?> cell)
                {
                    int n = callCounter.incrementAndGet();
                    if (n == 5)
                        throw new RuntimeException("F8: simulated cloner throw on 5th cell clone (heapSize should be at -104)");
                    return realCloner.clone(cell);
                }
                @Override public int estimateCloneSize(Cell<?> cell) { return realCloner.estimateCloneSize(cell); }
                @Override public boolean isContextAwareCloningSupported() { return realCloner.isContextAwareCloningSupported(); }
                @Override public Cloner createContextAwareCloner(int estimatedCloneSize) { return this; }
                @Override public void adjustUnused() { realCloner.adjustUnused(); }
            };
            PartitionUpdate update = makeReaperInsert(ts);
            ts += TS_STEP;
            try
            {
                partition.addAll(update, throwOnFifth, group, UpdateTransaction.NO_OP);
            }
            catch (RuntimeException expected)
            {
                throws_++;
            }
            long onH = allocator.onHeap().owns();
            if (onH < minObserved) minObserved = onH;
            if (i < 5 || i % 25 == 0)
                System.out.println(String.format("[F8] iter=%d throws=%d onHeap=%d (initial=%d, delta=%d)",
                    i, throws_, onH, initialOwns, onH - initialOwns));
        }

        long finalOwns = allocator.onHeap().owns();
        System.out.println(String.format("[F8] DONE iters=%d throws=%d initialOnHeap=%d finalOnHeap=%d minOnHeap=%d delta=%d",
            ITER, throws_, initialOwns, finalOwns, minObserved, finalOwns - initialOwns));

        try
        {
            assertThat(minObserved)
                .as("F8: onHeap owns went negative under realistic cloner.clone() throw at call 5 (throws=%d, initialOwns=%d, finalOwns=%d)",
                    throws_, initialOwns, finalOwns)
                .isGreaterThanOrEqualTo(0L);
        }
        finally
        {
            shutdownAllocator(allocator);
            shutdownPool(pool);
        }
    }

    // ------------------------------------------------------------------------
    // F7 — Deterministic reproducer through BTreePartitionUpdater.mergePartitions
    // ------------------------------------------------------------------------

    /**
     * Drives {@link BTreePartitionUpdater#mergePartitions} directly with an updater
     * subclass that throws inside {@code insert(ColumnData)} after the first
     * complex-column insert. By that point the three shadow-deletes and the negative
     * {@code onAllocatedOnHeap} for the complex-tree size delta have already pushed
     * {@code heapSize} negative. The throw causes {@code reportAllocatedMemory()} to
     * run from the {@code finally} block with that partial value, calling
     * {@code allocator.onHeap().adjust(negative)}. Asserts that {@code owns} never
     * goes negative.
     */
    @Test
    public void f7_deterministicShadowWindowThrow() throws InterruptedException
    {
        MemtablePool pool = AbstractAllocatorMemtable.createMemtableAllocatorPoolInternal(
            Config.MemtableAllocationType.offheap_objects, HEAP_LIMIT, OFF_HEAP_LIMIT,
            MEMTABLE_CLEANUP_THRESHOLD, DUMMY_CLEANER);
        MemtableAllocator allocator = pool.newAllocator("f7-shared");
        OpOrder opOrder = new OpOrder();
        opOrder.start();
        OpOrder.Group group = opOrder.getCurrent();
        Cloner cloner = allocator.cloner(group);

        long ts = INITIAL_TS_MICROS;
        final long TS_STEP = 1_000_000L;
        final int ITER = 100;

        // Seed
        BTreePartitionData state;
        {
            BTreePartitionUpdater seed = new BTreePartitionUpdater(allocator, cloner, group, UpdateTransaction.NO_OP);
            state = seed.mergePartitions(null, makeReaperInsert(ts));
            ts += TS_STEP;
        }
        long initialOwns = allocator.onHeap().owns();
        System.out.println(String.format("[F7] seeded onHeap=%d", initialOwns));

        AtomicLong minOwns = new AtomicLong(initialOwns);
        int throws_ = 0;
        for (int i = 0; i < ITER; i++)
        {
            PartitionUpdate update = makeReaperInsert(ts);
            ts += TS_STEP;
            ThrowAfterFirstInsertUpdater throwingU = new ThrowAfterFirstInsertUpdater(allocator, cloner, group, UpdateTransaction.NO_OP);
            try
            {
                throwingU.mergePartitions(state, update);
            }
            catch (RuntimeException expected)
            {
                throws_++;
            }
            long onH = allocator.onHeap().owns();
            track(onH, minOwns, new AtomicLong(0));
            if (i < 5 || i % 25 == 0)
                System.out.println(String.format("[F7] iter=%d throws=%d onHeap=%d (initial=%d, delta=%d)",
                    i, throws_, onH, initialOwns, onH - initialOwns));
        }

        long finalOwns = allocator.onHeap().owns();
        System.out.println(String.format("[F7] DONE iters=%d throws=%d initialOnHeap=%d finalOnHeap=%d minOnHeap=%d delta=%d",
            ITER, throws_, initialOwns, finalOwns, minOwns.get(), finalOwns - initialOwns));

        try
        {
            assertThat(minOwns.get())
                .as("F7: onHeap owns went negative under deterministic shadow→insert window throws (throws=%d, initialOwns=%d, finalOwns=%d)",
                    throws_, initialOwns, finalOwns)
                .isGreaterThanOrEqualTo(0L);
        }
        finally
        {
            shutdownAllocator(allocator);
            shutdownPool(pool);
        }
    }

    /**
     * Lets a merge proceed through all {@code delete} calls and the first
     * {@code insert(ColumnData)}, then throws {@link RuntimeException}.
     */
    private static final class ThrowAfterFirstInsertUpdater extends BTreePartitionUpdater
    {
        private int insertCalls;

        ThrowAfterFirstInsertUpdater(MemtableAllocator allocator, Cloner cloner, OpOrder.Group writeOp, UpdateTransaction indexer)
        {
            super(allocator, cloner, writeOp, indexer);
        }

        @Override
        public ColumnData insert(ColumnData insert)
        {
            ColumnData r = super.insert(insert);
            if (++insertCalls == 1)
                throw new RuntimeException("simulated mid-merge throw after first complex insert (heapSize=" + this.heapSize + ")");
            return r;
        }
    }

    // ------------------------------------------------------------------------
    // F6 — Single-threaded merge trace
    // ------------------------------------------------------------------------

    /**
     * Builds two {@link PartitionUpdate}s one second apart on the {@code caas_repair.cluster}
     * schema and runs them through an {@link InstrumentedUpdater} that prints
     * {@code heapSize} after every {@code delete}, {@code insert}, {@code merge}, and
     * {@code onAllocatedOnHeap}. The second merge's complex deletion at {@code ts-1}
     * shadows the first merge's SET cells via {@code postReconcile.delete()}, driving
     * {@code heapSize} negative before the matching inserts restore it. Diagnostic
     * only; makes no assertions.
     */
    @Test
    public void f6_instrumentedMergeTrace()
    {
        MemtablePool pool = AbstractAllocatorMemtable.createMemtableAllocatorPoolInternal(
            Config.MemtableAllocationType.offheap_objects, HEAP_LIMIT, OFF_HEAP_LIMIT,
            MEMTABLE_CLEANUP_THRESHOLD, DUMMY_CLEANER);
        MemtableAllocator allocator = pool.newAllocator("f6-shared");
        OpOrder opOrder = new OpOrder();
        opOrder.start();
        OpOrder.Group group = opOrder.getCurrent();
        Cloner cloner = allocator.cloner(group);

        long ts1 = INITIAL_TS_MICROS;
        long ts2 = INITIAL_TS_MICROS + 1_000_000L;

        PartitionUpdate u1 = makeReaperInsert(ts1);
        PartitionUpdate u2 = makeReaperInsert(ts2);

        // First merge: empty existing + u1
        InstrumentedUpdater updater1 = new InstrumentedUpdater(allocator, cloner, group, UpdateTransaction.NO_OP, "merge1[empty+u1]");
        BTreePartitionData merged1 = updater1.mergePartitions(null, u1);
        System.out.println(String.format("[F6] %s deletes=%d inserts=%d merges=%d onAlloc=%d finalHeapSize=%d allocator.onHeap=%d",
            updater1.label, updater1.deletes, updater1.inserts, updater1.merges, updater1.onAllocs, updater1.heapSizeFinal, allocator.onHeap().owns()));

        // Second merge: merged1 + u2 — this is where the complex deletion at ts2-1 should shadow u1's SET cells
        InstrumentedUpdater updater2 = new InstrumentedUpdater(allocator, cloner, group, UpdateTransaction.NO_OP, "merge2[m1+u2]");
        updater2.mergePartitions(merged1, u2);
        System.out.println(String.format("[F6] %s deletes=%d inserts=%d merges=%d onAlloc=%d finalHeapSize=%d allocator.onHeap=%d",
            updater2.label, updater2.deletes, updater2.inserts, updater2.merges, updater2.onAllocs, updater2.heapSizeFinal, allocator.onHeap().owns()));

        // Third merge: like second — same shape, different ts. Verifies steady state.
        PartitionUpdate u3 = makeReaperInsert(INITIAL_TS_MICROS + 2_000_000L);
        InstrumentedUpdater updater3 = new InstrumentedUpdater(allocator, cloner, group, UpdateTransaction.NO_OP, "merge3[m1+u3]");
        updater3.mergePartitions(merged1, u3);
        System.out.println(String.format("[F6] %s deletes=%d inserts=%d merges=%d onAlloc=%d finalHeapSize=%d allocator.onHeap=%d",
            updater3.label, updater3.deletes, updater3.inserts, updater3.merges, updater3.onAllocs, updater3.heapSizeFinal, allocator.onHeap().owns()));

        shutdownAllocator(allocator);
        shutdownPool(pool);
    }

    /** Subclass of {@link BTreePartitionUpdater} that counts calls and records heapSize transitions. */
    private static final class InstrumentedUpdater extends BTreePartitionUpdater
    {
        final String label;
        int deletes;
        int inserts;
        int merges;
        long onAllocs;
        long heapSizeFinal;

        InstrumentedUpdater(MemtableAllocator allocator, Cloner cloner, OpOrder.Group writeOp, UpdateTransaction indexer, String label)
        {
            super(allocator, cloner, writeOp, indexer);
            this.label = label;
        }

        @Override
        public void delete(ColumnData existing)
        {
            long before = this.heapSize;
            super.delete(existing);
            long after = this.heapSize;
            deletes++;
            System.out.println(String.format("  [%s] DELETE %s heapSize %d -> %d (delta=%d)", label,
                existing.getClass().getSimpleName(), before, after, after - before));
        }

        @Override
        public Row insert(Row insert)
        {
            long before = this.heapSize;
            Row r = super.insert(insert);
            long after = this.heapSize;
            inserts++;
            System.out.println(String.format("  [%s] INSERT Row heapSize %d -> %d (delta=%d)", label, before, after, after - before));
            return r;
        }

        @Override
        public Row merge(Row existing, Row update)
        {
            long before = this.heapSize;
            Row r = super.merge(existing, update);
            long after = this.heapSize;
            merges++;
            System.out.println(String.format("  [%s] MERGE Row heapSize %d -> %d (delta=%d)", label, before, after, after - before));
            return r;
        }

        @Override
        public Cell<?> merge(Cell<?> previous, Cell<?> insert)
        {
            long before = this.heapSize;
            Cell<?> r = super.merge(previous, insert);
            long after = this.heapSize;
            merges++;
            System.out.println(String.format("  [%s] MERGE Cell %s+%s heapSize %d -> %d (delta=%d)", label,
                previous.getClass().getSimpleName(), insert.getClass().getSimpleName(), before, after, after - before));
            return r;
        }

        @Override
        public ColumnData insert(ColumnData insert)
        {
            long before = this.heapSize;
            ColumnData r = super.insert(insert);
            long after = this.heapSize;
            inserts++;
            System.out.println(String.format("  [%s] INSERT ColumnData %s heapSize %d -> %d (delta=%d)", label,
                insert.getClass().getSimpleName(), before, after, after - before));
            return r;
        }

        @Override
        public void onAllocatedOnHeap(long heapSize)
        {
            long before = this.heapSize;
            super.onAllocatedOnHeap(heapSize);
            long after = this.heapSize;
            onAllocs += heapSize;
            System.out.println(String.format("  [%s] onAllocatedOnHeap(%d) heapSize %d -> %d", label, heapSize, before, after));
        }

        @Override
        public void reportAllocatedMemory()
        {
            heapSizeFinal = this.heapSize;
            System.out.println(String.format("  [%s] reportAllocatedMemory final heapSize=%d", label, heapSizeFinal));
            super.reportAllocatedMemory();
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static void track(long sample, AtomicLong min, AtomicLong max)
    {
        min.accumulateAndGet(sample, Math::min);
        max.accumulateAndGet(sample, Math::max);
    }

    private static void shutdownAllocator(MemtableAllocator allocator)
    {
        try { allocator.setDiscarding(); } catch (Throwable ignore) {}
        try { allocator.setDiscarded(); } catch (Throwable ignore) {}
    }

    private static void shutdownPool(MemtablePool pool)
    {
        try { pool.shutdownAndWait(1, TimeUnit.SECONDS); } catch (Throwable ignore) {}
    }

    // Unused but kept for reference if we later want to drive real addAll under F2/F3 instead of synthetic adjust().
    @SuppressWarnings("unused")
    private PartitionUpdate makeReaperInsert(long ts)
    {
        Row.Builder rowBuilder = BTreeRow.unsortedBuilder();
        rowBuilder.newRow(Clustering.EMPTY);
        rowBuilder.addCell(simpleCell(partitionerCol, ts, ByteBufferUtil.bytes("Murmur3Partitioner")));
        rowBuilder.addCell(simpleCell(propertiesCol, ts, ByteBufferUtil.bytes("{}")));
        rowBuilder.addCell(simpleCell(stateCol, ts, ByteBufferUtil.bytes("ACTIVE")));
        rowBuilder.addCell(simpleCell(lastContactCol, ts, TimestampType.instance.fromString("2026-06-16T00:00:00+0000")));
        rowBuilder.addComplexDeletion(seedHostsCol, DeletionTime.build(ts - 1, (int) (ts / 1_000_000)));
        rowBuilder.addCell(setElement(seedHostsCol, ts, "10.0.0.1"));
        rowBuilder.addCell(setElement(seedHostsCol, ts, "10.0.0.2"));
        rowBuilder.addCell(setElement(seedHostsCol, ts, "10.0.0.3"));
        Row row = rowBuilder.build();
        return PartitionUpdate.singleRowUpdate(metadata, partitionKey, row, null);
    }

    @SuppressWarnings("unused")
    private static Cell<?> simpleCell(ColumnMetadata column, long timestamp, ByteBuffer value)
    {
        return new BufferCell(column, timestamp, Cell.NO_TTL, Cell.NO_DELETION_TIME, value, null);
    }

    @SuppressWarnings("unused")
    private static Cell<?> setElement(ColumnMetadata column, long timestamp, String element)
    {
        return new BufferCell(column, timestamp, Cell.NO_TTL, Cell.NO_DELETION_TIME,
                              ByteBufferUtil.EMPTY_BYTE_BUFFER,
                              CellPath.create(ByteBufferUtil.bytes(element)));
    }

    @SuppressWarnings("unused")
    private static TableMetadataRef metadataRef()
    {
        return TableMetadataRef.forOfflineTools(metadata);
    }

    @SuppressWarnings("unused")
    private static UpdateTransaction noOpIndexer()
    {
        return UpdateTransaction.NO_OP;
    }

    @SuppressWarnings("unused")
    private static Cloner clonerFor(MemtableAllocator a, OpOrder.Group g)
    {
        return a.cloner(g);
    }
}
