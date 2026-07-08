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

package org.apache.cassandra.test.microbench.uring;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.sun.nio.file.ExtendedOpenOption;

import org.agrona.BufferUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import org.apache.cassandra.io.uring.UringAvailability;
import org.apache.cassandra.io.uring.UringRing;
import org.apache.cassandra.io.util.File;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.NativeLibrary;

/**
 * Phase 1 sub-phase 1.7 skeleton: single-thread raw 4 KiB read IOPS, sync-vs-batched at
 * queue depths 1–64, buffered vs O_DIRECT. Matrix execution, environment capture, and
 * fio cross-checks are Phase 2's job (phase-2-benchmark/spec.md); this class provides the
 * measured body only.
 *
 * Run: ant microbench -Dbenchmark.name=UringRawReadBench
 * Cell override example: -Djmh.args="-p qd=64 -p mode=batched -p direct=false -p fileGiB=8"
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Threads(1) // single thread is the point: TPC needs one thread to saturate the device
@State(Scope.Benchmark)
public class UringRawReadBench
{
    private static final int PAGE = 4096;
    private static final int OPS_PER_INVOCATION = 64;

    @Param({ "1", "8", "32", "64" })
    int qd;

    @Param({ "sync", "batched" })
    String mode;

    @Param({ "false", "true" })
    boolean direct;

    @Param({ "8" })
    int fileGiB;

    /** Target directory; empty = JVM temp dir. Point at /bench-ext4 or /bench-xfs on the rig. */
    @Param({ "" })
    String dir;

    private File file;
    private FileChannel channel;
    private int fd;
    private UringRing ring;
    private ByteBuffer[] buffers;
    private ArrayDeque<ByteBuffer> free;
    private final HashMap<Long, ByteBuffer> inflightBufs = new HashMap<>();
    private Random random;
    private long pages;

    @Setup(Level.Trial)
    public void setup() throws IOException
    {
        if (!UringAvailability.isAvailable())
            throw new IllegalStateException("io_uring unavailable: " + UringAvailability.reason());

        File directory = dir.isEmpty() ? FileUtils.getTempDir() : new File(dir);
        file = FileUtils.createTempFile("uring-bench", ".bin", directory);
        long size = (long) fileGiB << 30;
        try (FileChannel writeChannel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE))
        {
            ByteBuffer chunk = ByteBuffer.allocateDirect(8 << 20);
            long written = 0;
            while (written < size)
            {
                chunk.clear();
                chunk.limit((int) Math.min(chunk.capacity(), size - written));
                written += writeChannel.write(chunk);
            }
            writeChannel.force(true);
        }
        pages = size / PAGE;

        channel = direct
                  ? FileChannel.open(file.toPath(), StandardOpenOption.READ, ExtendedOpenOption.DIRECT)
                  : FileChannel.open(file.toPath(), StandardOpenOption.READ);
        fd = NativeLibrary.getfd(channel);
        if (fd < 0)
            throw new IllegalStateException("could not obtain fd");

        ring = UringRing.create(128, 512);
        buffers = new ByteBuffer[qd];
        free = new ArrayDeque<>(qd);
        for (int i = 0; i < qd; i++)
        {
            buffers[i] = BufferUtil.allocateDirectAligned(PAGE, PAGE);
            free.add(buffers[i]);
        }
        random = new Random(20260708);
    }

    @Setup(Level.Iteration)
    public void dropCache()
    {
        if (!direct)
            NativeLibrary.trySkipCache(file.path(), 0, 0);
    }

    private long nextOffset()
    {
        return (random.nextLong() >>> 1) % pages * PAGE;
    }

    @Benchmark
    @OperationsPerInvocation(OPS_PER_INVOCATION)
    @Fork(value = 1, jvmArgsAppend = { "-Xmx1G" })
    public long read() throws IOException
    {
        long checksum = 0;
        if ("sync".equals(mode))
        {
            ByteBuffer buf = buffers[0];
            for (int i = 0; i < OPS_PER_INVOCATION; i++)
            {
                buf.clear();
                ring.readSync(fd, nextOffset(), buf);
                checksum += buf.get(0);
            }
        }
        else
        {
            int issued = 0;
            long[] state = { 0, 0 }; // completions, checksum
            UringRing.CompletionHandler handler = (opId, res) -> {
                state[0]++;
                state[1] += res;
                free.add(inflightBufs.remove(opId));
            };
            while (state[0] < OPS_PER_INVOCATION)
            {
                while (issued < OPS_PER_INVOCATION && !free.isEmpty() && ring.inFlight() < qd)
                {
                    ByteBuffer buf = free.poll();
                    buf.clear();
                    inflightBufs.put(ring.prepareRead(fd, nextOffset(), buf), buf);
                    issued++;
                }
                ring.submit();
                ring.awaitCompletions(1, handler);
            }
            checksum = state[1];
        }
        return checksum;
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException
    {
        if (ring != null)
            ring.close();
        if (channel != null)
            channel.close();
        if (file != null)
            file.delete();
    }
}
