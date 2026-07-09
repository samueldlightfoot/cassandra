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
import org.apache.cassandra.utils.NativeLibrary;

/**
 * Phase 2 Level B write cells (phase-2-benchmark/spec.md §1.1-B), mirroring fio A4
 * (256 KiB sequential write, buffered — the io-wq punt probe) and A5 (4 KiB random write,
 * O_DIRECT, preallocated file). Writes go to the pre-made overwrite targets from
 * make-bench-file.sh, never extending the file.
 *
 * Durability stance: buffered arms issue a DATASYNC fsync through the same API under test
 * every {@code FSYNC_INTERVAL_BYTES} (batched arms drain in-flight writes first — ordering
 * is the caller's job per the binding contract). fio's A4 twin instead uses one end_fsync
 * per 32 GiB pass, so A4 B/A ratios are approximate by design; the primary A4 signal is
 * iou-wrk punt behavior, sampled system-side. O_DIRECT arms do not fsync (nor does fio A5).
 *
 * Run: ant microbench -Dbenchmark.name=UringRawWriteBench
 *   -Djmh.args="-bm thrpt,sample -p file=/bench-xfs/uring-bench-seqwrite.dat -p mode=batched -p qd=32 -p bs=262144 -p pattern=seq -p direct=false"
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Threads(1)
@State(Scope.Benchmark)
public class UringRawWriteBench
{
    static final int OPS_PER_INVOCATION = 64;
    static final long FSYNC_INTERVAL_BYTES = 64L << 20;

    @Param({ "/bench-ext4/uring-bench-seqwrite.dat" })
    String file;

    @Param({ "1", "32" })
    int qd;

    @Param({ "pwrite", "sync", "batched" })
    String mode;

    @Param({ "262144" })
    int bs;

    @Param({ "seq", "rand" })
    String pattern;

    @Param({ "false", "true" })
    boolean direct;

    private FileChannel channel;
    private int fd;
    private UringRing ring;
    private ByteBuffer[] buffers;
    private ArrayDeque<ByteBuffer> free;
    private final HashMap<Long, ByteBuffer> inflightBufs = new HashMap<>();
    private Random random;
    private long blocks;
    private long seqBlock;
    private long bytesSinceFsync;

    @Setup(Level.Trial)
    public void setup() throws IOException
    {
        if (!UringAvailability.isAvailable())
            throw new IllegalStateException("io_uring unavailable: " + UringAvailability.reason());

        java.io.File f = new java.io.File(file);
        if (!f.isFile() || f.length() < (1L << 30))
            throw new IllegalStateException("bench file missing or too small (make-bench-file.sh): " + file);

        channel = direct
                  ? FileChannel.open(f.toPath(), StandardOpenOption.WRITE, ExtendedOpenOption.DIRECT)
                  : FileChannel.open(f.toPath(), StandardOpenOption.WRITE);
        fd = NativeLibrary.getfd(channel);
        if (fd < 0)
            throw new IllegalStateException("could not obtain fd");
        blocks = channel.size() / bs;

        ring = UringRing.create(128, 512);
        buffers = new ByteBuffer[qd];
        free = new ArrayDeque<>(qd);
        Random content = new Random(42);
        byte[] junk = new byte[bs];
        for (int i = 0; i < qd; i++)
        {
            content.nextBytes(junk);
            buffers[i] = BufferUtil.allocateDirectAligned(bs, UringRawReadBench.PAGE);
            buffers[i].put(junk).flip();
            free.add(buffers[i]);
        }
        random = new Random(20260708);
    }

    /** Writeback settle between iterations (spec §4): flush, drop, let the device quiesce. */
    @Setup(Level.Iteration)
    public void settle() throws IOException
    {
        UringRawReadBench.dropCaches();
        if (!direct)
        {
            try
            {
                Thread.sleep(10_000);
            }
            catch (InterruptedException e)
            {
                throw new AssertionError(e);
            }
        }
    }

    private long nextOffset()
    {
        if ("seq".equals(pattern))
        {
            long off = seqBlock * bs;
            seqBlock = (seqBlock + 1) % blocks;
            return off;
        }
        return (random.nextLong() >>> 1) % blocks * bs;
    }

    private boolean fsyncDue(int written)
    {
        if (direct)
            return false;
        bytesSinceFsync += written;
        if (bytesSinceFsync < FSYNC_INTERVAL_BYTES)
            return false;
        bytesSinceFsync = 0;
        return true;
    }

    @Benchmark
    @OperationsPerInvocation(OPS_PER_INVOCATION)
    @Fork(value = 1, jvmArgsAppend = { "-Xmx1G" })
    public long write() throws IOException
    {
        long written = 0;
        if ("pwrite".equals(mode))
        {
            ByteBuffer buf = buffers[0];
            for (int i = 0; i < OPS_PER_INVOCATION; i++)
            {
                buf.rewind();
                written += channel.write(buf, nextOffset());
                if (fsyncDue(bs))
                    channel.force(false);
            }
        }
        else if ("sync".equals(mode))
        {
            ByteBuffer buf = buffers[0];
            for (int i = 0; i < OPS_PER_INVOCATION; i++)
            {
                buf.rewind();
                written += ring.writeSync(fd, nextOffset(), buf);
                if (fsyncDue(bs))
                    ring.fsyncSync(fd, true);
            }
        }
        else
        {
            int issued = 0;
            long[] state = { 0, 0 }; // completions, bytes
            UringRing.CompletionHandler handler = (opId, res) -> {
                state[0]++;
                state[1] += res;
                free.add(inflightBufs.remove(opId));
            };
            boolean[] fsyncPending = { false };
            UringRing.CompletionHandler fsyncHandler = (opId, res) -> fsyncPending[0] = false;
            while (state[0] < OPS_PER_INVOCATION)
            {
                while (issued < OPS_PER_INVOCATION && !free.isEmpty() && ring.inFlight() < qd)
                {
                    ByteBuffer buf = free.poll();
                    buf.rewind();
                    inflightBufs.put(ring.prepareWrite(fd, nextOffset(), buf), buf);
                    issued++;
                    if (fsyncDue(bs))
                    {
                        // ordering is the caller's job: drain writes, then a solo DATASYNC
                        ring.submit();
                        while (!inflightBufs.isEmpty())
                            ring.awaitCompletions(1, handler);
                        fsyncPending[0] = true;
                        ring.prepareFsync(fd, true);
                        ring.submit();
                        while (fsyncPending[0])
                            ring.awaitCompletions(1, fsyncHandler);
                    }
                }
                ring.submit();
                if (state[0] < OPS_PER_INVOCATION && !inflightBufs.isEmpty())
                    ring.awaitCompletions(1, handler);
            }
            written = state[1];
        }
        return written;
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
