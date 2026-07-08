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

package org.apache.cassandra.io.uring;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.io.util.File;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.NativeLibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Sub-phase 1.4 acceptance: batched submission + deferred reap — the spike's core deliverable —
 * plus the 1.6 batched fsync semantics. The QD proof at the bottom is the number Phase 1 exists
 * to produce (Phase 2 formalizes it).
 */
public class UringBatchTest
{
    private static final Logger logger = LoggerFactory.getLogger(UringBatchTest.class);

    private static final int PAGE = 4096;

    @BeforeClass
    public static void checkAvailability()
    {
        Assume.assumeTrue("io_uring unavailable: " + UringAvailability.reason(), UringAvailability.isAvailable());
    }

    /** Deterministic content: byte at file position p is pattern(p) — verifiable without held state */
    private static byte pattern(long p)
    {
        return (byte) (p ^ (p >>> 8) ^ (p >>> 13) ^ 0x5b);
    }

    private static File patternFile(long size) throws IOException
    {
        File file = FileUtils.createDeletableTempFile("uringbatch", ".bin");
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE))
        {
            ByteBuffer chunk = ByteBuffer.allocate(1 << 20);
            long written = 0;
            while (written < size)
            {
                chunk.clear();
                int n = (int) Math.min(chunk.capacity(), size - written);
                for (int i = 0; i < n; i++)
                    chunk.put((byte) pattern(written + i));
                chunk.flip();
                while (chunk.hasRemaining())
                    channel.write(chunk);
                written += n;
            }
            channel.force(true);
        }
        return file;
    }

    private static void verifyPattern(ByteBuffer buf, long fileOffset, int length)
    {
        for (int i = 0; i < length; i++)
            if (buf.get(i) != pattern(fileOffset + i))
                fail("byte mismatch at buffer index " + i + " (file offset " + (fileOffset + i) + ')');
    }

    /** Content-free fast writer for the QD proof (the proof asserts res only, never bytes) */
    private static File bulkFile(long size) throws IOException
    {
        File file = FileUtils.createDeletableTempFile("uringbulk", ".bin");
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE))
        {
            ByteBuffer chunk = ByteBuffer.allocateDirect(8 << 20);
            long written = 0;
            while (written < size)
            {
                chunk.clear();
                chunk.limit((int) Math.min(chunk.capacity(), size - written));
                written += channel.write(chunk);
            }
            channel.force(true);
        }
        return file;
    }

    @Test
    public void testBatchOf64OneEnter() throws IOException
    {
        File file = patternFile(64 * PAGE);
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
             UringRing ring = UringRing.create(64, 256))
        {
            int fd = NativeLibrary.getfd(channel);
            ByteBuffer[] buffers = new ByteBuffer[64];
            Map<Long, Integer> opToIndex = new HashMap<>();
            for (int i = 0; i < 64; i++)
            {
                buffers[i] = ByteBuffer.allocateDirect(PAGE);
                long opId = ring.prepareRead(fd, (long) i * PAGE, buffers[i]);
                opToIndex.put(opId, i);
            }
            assertEquals(64, ring.inFlight());
            assertEquals("all 64 SQEs must be consumed by ONE enter", 64, ring.submit());

            int drained = ring.awaitCompletions(64, (opId, res) -> {
                assertEquals(PAGE, res);
                int index = opToIndex.get(opId);
                verifyPattern(buffers[index], (long) index * PAGE, PAGE);
            });
            assertEquals(64, drained);
            assertEquals(0, ring.inFlight());
        }
    }

    @Test
    public void testSustainedInterleavedOps() throws IOException
    {
        final int totalOps = 100_000;
        final int targetDepth = 48;
        File file = patternFile(256 * PAGE);
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
             UringRing ring = UringRing.create(64, 256))
        {
            int fd = NativeLibrary.getfd(channel);
            ArrayDeque<ByteBuffer> free = new ArrayDeque<>();
            for (int i = 0; i < targetDepth + 8; i++)
                free.add(ByteBuffer.allocateDirect(PAGE));
            Map<Long, long[]> inflight = new HashMap<>(); // opId -> {fileOffset, buffer identity via map below}
            Map<Long, ByteBuffer> bufOf = new HashMap<>();

            Random random = new Random(42);
            long issued = 0;
            long[] completed = { 0 };
            UringRing.CompletionHandler handler = (opId, res) -> {
                assertEquals(PAGE, res);
                long offset = inflight.remove(opId)[0];
                ByteBuffer buf = bufOf.remove(opId);
                verifyPattern(buf, offset, PAGE);
                free.add(buf);
                completed[0]++;
            };

            while (completed[0] < totalOps)
            {
                while (issued < totalOps && ring.inFlight() < targetDepth && !free.isEmpty())
                {
                    long offset = (long) random.nextInt(256) * PAGE;
                    ByteBuffer buf = free.poll();
                    buf.clear();
                    long opId = ring.prepareRead(fd, offset, buf);
                    inflight.put(opId, new long[]{ offset });
                    bufOf.put(opId, buf);
                    issued++;
                }
                ring.submit();
                ring.awaitCompletions(1, handler);
            }
            assertEquals(totalOps, completed[0]);
            assertEquals(0, ring.inFlight());
        }
    }

    @Test
    public void testBackpressureAtSlotCapacity() throws IOException
    {
        File file = patternFile(16 * PAGE);
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
             UringRing ring = UringRing.create(8, 8))
        {
            int fd = NativeLibrary.getfd(channel);
            ByteBuffer[] buffers = new ByteBuffer[8];
            for (int i = 0; i < 8; i++)
            {
                buffers[i] = ByteBuffer.allocateDirect(PAGE);
                ring.prepareRead(fd, (long) i * PAGE, buffers[i]);
            }
            try
            {
                ring.prepareRead(fd, 0, ByteBuffer.allocateDirect(PAGE));
                fail("expected IllegalStateException at capacity");
            }
            catch (IllegalStateException expected)
            {
            }
            ring.submit();
            ring.awaitCompletions(8, (opId, res) -> assertEquals(PAGE, res));
        }
    }

    @Test
    public void testBackpressureAtSqCapacity() throws IOException
    {
        File file = patternFile(16 * PAGE);
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
             UringRing ring = UringRing.create(8, 32))
        {
            int fd = NativeLibrary.getfd(channel);
            for (int i = 0; i < 8; i++)
                ring.prepareRead(fd, (long) i * PAGE, ByteBuffer.allocateDirect(PAGE));
            try
            {
                // slots have room (8 < 32) but the SQ has 8 unsubmitted entries
                ring.prepareRead(fd, 0, ByteBuffer.allocateDirect(PAGE));
                fail("expected IllegalStateException when SQ is full of unsubmitted entries");
            }
            catch (IllegalStateException expected)
            {
            }
            ring.submit();
            ring.awaitCompletions(8, (opId, res) -> assertEquals(PAGE, res));
        }
    }

    @Test
    public void testShortReadPassedThroughNotRetried() throws IOException
    {
        File file = patternFile(6 * 1024); // 6 KiB: a 4 KiB read at 4096 gets 2048
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
             UringRing ring = UringRing.create(8, 32))
        {
            int fd = NativeLibrary.getfd(channel);
            ByteBuffer buf = ByteBuffer.allocateDirect(PAGE);
            ring.prepareRead(fd, PAGE, buf);
            int[] result = { Integer.MIN_VALUE };
            ring.submitAndWait(1);
            ring.drainCompletions((opId, res) -> result[0] = res);
            assertEquals("short read must be delivered raw, not retried", 2048, result[0]);
            verifyPattern(buf, PAGE, 2048);
            assertEquals("batched mode must not touch position", 0, buf.position());
        }
    }

    @Test
    public void testBatchedWriteThenFsync() throws IOException
    {
        File file = FileUtils.createDeletableTempFile("uringbatch-wf", ".bin");
        int pages = 16;
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE);
             UringRing ring = UringRing.create(64, 256))
        {
            int fd = NativeLibrary.getfd(channel);
            for (int i = 0; i < pages; i++)
            {
                ByteBuffer buf = ByteBuffer.allocateDirect(PAGE);
                for (int b = 0; b < PAGE; b++)
                    buf.put(b, pattern((long) i * PAGE + b));
                ring.prepareWrite(fd, (long) i * PAGE, buf);
            }
            ring.submit();
            // caller-ordered write->fsync: await ALL write completions BEFORE preparing the fsync
            ring.awaitCompletions(pages, (opId, res) -> assertEquals(PAGE, res));
            ring.prepareFsync(fd, false);
            ring.submitAndWait(1);
            ring.drainCompletions((opId, res) -> assertEquals(0, res));
        }

        try (FileChannel readChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ))
        {
            assertEquals((long) pages * PAGE, readChannel.size());
            ByteBuffer readBack = ByteBuffer.allocate(pages * PAGE);
            while (readBack.hasRemaining() && readChannel.read(readBack) != -1)
            {
            }
            for (int p = 0; p < pages * PAGE; p++)
                if (readBack.get(p) != pattern(p))
                    fail("byte mismatch at " + p);
        }
    }

    /**
     * ★ The headline (spec 1.4): one thread keeping 64 reads in flight must beat the same thread
     * doing one read at a time by >4× on cache-cold 4 KiB reads. Numbers are logged for
     * progress.md; Phase 2 formalizes the methodology.
     */
    @Test
    public void testSingleThreadQueueDepthProof() throws IOException
    {
        final long fileSize = 8L << 30; // 8 GiB, per spec — large enough that cold means cold
        final int syncOps = 20_000;
        final int batchedOps = 100_000;
        final int depth = 64;

        File file = bulkFile(fileSize);
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
             UringRing ring = UringRing.create(64, 256))
        {
            int fd = NativeLibrary.getfd(channel);
            long pagesInFile = fileSize / PAGE;

            // --- QD1: sequential pread-style readSync ---
            NativeLibrary.trySkipCache(file.path(), 0, 0);
            Random random = new Random(7);
            ByteBuffer buf = ByteBuffer.allocateDirect(PAGE);
            long syncStart = System.nanoTime();
            for (int i = 0; i < syncOps; i++)
            {
                buf.clear();
                long offset = (random.nextLong() >>> 1) % pagesInFile * PAGE;
                assertEquals(PAGE, ring.readSync(fd, offset, buf));
            }
            long syncNanos = System.nanoTime() - syncStart;
            double syncIops = syncOps / (syncNanos / 1e9);

            // --- QD64: batched ---
            NativeLibrary.trySkipCache(file.path(), 0, 0);
            random = new Random(11);
            ArrayDeque<ByteBuffer> free = new ArrayDeque<>();
            for (int i = 0; i < depth; i++)
                free.add(ByteBuffer.allocateDirect(PAGE));
            Map<Long, ByteBuffer> bufOf = new HashMap<>();
            long[] completed = { 0 };
            UringRing.CompletionHandler handler = (opId, res) -> {
                assertEquals(PAGE, res);
                free.add(bufOf.remove(opId));
                completed[0]++;
            };
            long issued = 0;
            long batchStart = System.nanoTime();
            while (completed[0] < batchedOps)
            {
                while (issued < batchedOps && !free.isEmpty())
                {
                    long offset = (random.nextLong() >>> 1) % pagesInFile * PAGE;
                    ByteBuffer b = free.poll();
                    b.clear();
                    bufOf.put(ring.prepareRead(fd, offset, b), b);
                    issued++;
                }
                ring.submit();
                ring.awaitCompletions(1, handler);
            }
            long batchNanos = System.nanoTime() - batchStart;
            double batchedIops = batchedOps / (batchNanos / 1e9);

            double ratio = batchedIops / syncIops;
            logger.info("QD PROOF (single thread, cold 4 KiB reads, 8 GiB file): " +
                        "sync QD1 = {} IOPS ({} ops in {} ms); batched QD{} = {} IOPS ({} ops in {} ms); ratio = {}x",
                        String.format("%.0f", syncIops), syncOps, syncNanos / 1_000_000,
                        depth,
                        String.format("%.0f", batchedIops), batchedOps, batchNanos / 1_000_000,
                        String.format("%.2f", ratio));
            assertTrue(String.format("batched/sync IOPS ratio %.2f must exceed 4x (sync=%.0f, batched=%.0f)",
                                     ratio, syncIops, batchedIops),
                       ratio > 4.0);
        }
        finally
        {
            file.delete(); // 8 GiB — do not wait for JVM exit
        }
    }
}
