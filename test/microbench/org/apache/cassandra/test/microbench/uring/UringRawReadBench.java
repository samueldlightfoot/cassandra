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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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
import org.apache.cassandra.utils.NativeLibrary;

/**
 * Phase 2 Level B read cells (phase-2-benchmark/spec.md §1.1-B): single-thread 4 KiB random
 * reads against the SAME pre-made 32 GiB pseudo-random file the fio Level A cells use, so
 * every JMH number has a native twin and the B/A delta is the JVM/JNA tax.
 *
 * Modes: pread (FileChannel positional read — the psync twin), sync (ring sync facade at
 * QD1), batched (prepare x window -> submit -> await, pipelined). Cold arms drop the page
 * cache before every iteration exactly like the fio driver (sync + drop_caches; rig runs
 * as root); hot arms prime deterministically with one sequential full-file pass at trial
 * setup and never invalidate.
 *
 * Run: ant microbench -Dbenchmark.name=UringRawReadBench
 *   -Djmh.args="-bm thrpt,sample -p file=/bench-xfs/uring-bench-read.dat -p mode=batched -p qd=64 -p direct=true -p cold=true"
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Threads(1) // single thread is the point: TPC needs one thread to saturate the device
@State(Scope.Benchmark)
public class UringRawReadBench
{
    static final int PAGE = 4096;
    static final int OPS_PER_INVOCATION = 64;

    @Param({ "/bench-ext4/uring-bench-read.dat" })
    String file;

    @Param({ "1", "32", "64" })
    int qd;

    @Param({ "pread", "sync", "batched" })
    String mode;

    @Param({ "false", "true" })
    boolean direct;

    @Param({ "true", "false" })
    boolean cold;

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

        java.io.File f = new java.io.File(file);
        if (!f.isFile() || f.length() < (1L << 30))
            throw new IllegalStateException("bench file missing or too small (make-bench-file.sh): " + file);

        channel = direct
                  ? FileChannel.open(f.toPath(), StandardOpenOption.READ, ExtendedOpenOption.DIRECT)
                  : FileChannel.open(f.toPath(), StandardOpenOption.READ);
        fd = NativeLibrary.getfd(channel);
        if (fd < 0)
            throw new IllegalStateException("could not obtain fd");
        pages = channel.size() / PAGE;

        ring = UringRing.create(128, 512);
        buffers = new ByteBuffer[qd];
        free = new ArrayDeque<>(qd);
        for (int i = 0; i < qd; i++)
        {
            buffers[i] = BufferUtil.allocateDirectAligned(PAGE, PAGE);
            free.add(buffers[i]);
        }
        random = new Random(20260708);

        if (!cold)
            prime(f);
    }

    /** Deterministic hot priming: one sequential full-file pass through a buffered channel. */
    static void prime(java.io.File f) throws IOException
    {
        dropCaches();
        try (FileChannel prime = FileChannel.open(f.toPath(), StandardOpenOption.READ))
        {
            ByteBuffer chunk = ByteBuffer.allocateDirect(8 << 20);
            long pos = 0, size = prime.size();
            while (pos < size)
            {
                chunk.clear();
                int n = prime.read(chunk, pos);
                if (n <= 0)
                    break;
                pos += n;
            }
        }
    }

    /** Same cold mechanism as the fio driver, so cold cells are comparable across levels. */
    static void dropCaches() throws IOException
    {
        try
        {
            new ProcessBuilder("/bin/sync").start().waitFor();
        }
        catch (InterruptedException e)
        {
            throw new AssertionError(e);
        }
        Files.write(Paths.get("/proc/sys/vm/drop_caches"), "3".getBytes(StandardCharsets.US_ASCII),
                    StandardOpenOption.WRITE);
    }

    @Setup(Level.Iteration)
    public void perIteration() throws IOException
    {
        if (cold)
            dropCaches();
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
        if ("pread".equals(mode))
        {
            ByteBuffer buf = buffers[0];
            for (int i = 0; i < OPS_PER_INVOCATION; i++)
            {
                buf.clear();
                channel.read(buf, nextOffset());
                checksum += buf.get(0);
            }
        }
        else if ("sync".equals(mode))
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
    }
}
