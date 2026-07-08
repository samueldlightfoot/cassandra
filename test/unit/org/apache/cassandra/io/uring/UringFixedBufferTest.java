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

import com.sun.nio.file.ExtendedOpenOption;

import org.agrona.BufferUtil;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.io.util.File;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.NativeLibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Sub-phase 1.5 acceptance: O_DIRECT + registered (FIXED) buffers, verified on the default
 * temp filesystem and — when the Phase 0 bench mounts exist — on ext4 AND xfs explicitly.
 */
public class UringFixedBufferTest
{
    private static final int PAGES = 16;

    @BeforeClass
    public static void checkAvailability()
    {
        Assume.assumeTrue("io_uring unavailable: " + UringAvailability.reason(), UringAvailability.isAvailable());
    }

    private static byte pattern(long p)
    {
        return (byte) (p * 31 + (p >>> 11) + 7);
    }

    private static File patternFileIn(File directory, int blockSize) throws IOException
    {
        File file = FileUtils.createTempFile("uringfixed", ".bin", directory);
        file.deleteOnExit();
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE))
        {
            ByteBuffer buf = ByteBuffer.allocate(PAGES * blockSize);
            for (int i = 0; i < buf.capacity(); i++)
                buf.put(i, pattern(i));
            while (buf.hasRemaining())
                channel.write(buf);
            channel.force(true);
        }
        return file;
    }

    private static void runFixedVsNonFixed(File directory) throws IOException
    {
        Assume.assumeTrue("direct I/O unsupported in " + directory, FileUtils.isDirectIOSupported(directory));
        int blockSize = FileUtils.getBlockSize(directory);
        File file = patternFileIn(directory, blockSize);

        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, ExtendedOpenOption.DIRECT);
             UringRing ring = UringRing.create(8, 32))
        {
            int fd = NativeLibrary.getfd(channel);
            assertTrue(fd >= 0);

            ByteBuffer[] registered = new ByteBuffer[]{ BufferUtil.allocateDirectAligned(blockSize, blockSize),
                                                        BufferUtil.allocateDirectAligned(blockSize, blockSize) };
            ring.registerBuffers(registered);
            try
            {
                // FIXED read through registered buffer 1
                long fixedOp = ring.prepareReadFixed(fd, blockSize, 1, 0, blockSize);
                // plain (non-fixed) O_DIRECT read of the same range for comparison
                ByteBuffer plain = BufferUtil.allocateDirectAligned(blockSize, blockSize);
                long plainOp = ring.prepareRead(fd, blockSize, plain);

                ring.submit();
                int[] results = { Integer.MIN_VALUE, Integer.MIN_VALUE };
                ring.awaitCompletions(2, (opId, res) -> results[opId == fixedOp ? 0 : 1] = res);
                assertEquals(blockSize, results[0]);
                assertEquals(blockSize, results[1]);
                for (int i = 0; i < blockSize; i++)
                {
                    if (registered[1].get(i) != pattern(blockSize + i))
                        fail("FIXED read byte mismatch at " + i);
                    if (plain.get(i) != pattern(blockSize + i))
                        fail("plain read byte mismatch at " + i);
                }
            }
            finally
            {
                ring.unregisterBuffers();
            }
        }
    }

    @Test
    public void testFixedVsNonFixedOnTempFs() throws IOException
    {
        runFixedVsNonFixed(FileUtils.getTempDir());
    }

    @Test
    public void testFixedVsNonFixedOnBenchExt4() throws IOException
    {
        File mount = new File("/bench-ext4");
        Assume.assumeTrue("/bench-ext4 not present (rig-only arm)", mount.isDirectory() && mount.isWritable());
        runFixedVsNonFixed(mount);
    }

    @Test
    public void testFixedVsNonFixedOnBenchXfs() throws IOException
    {
        File mount = new File("/bench-xfs");
        Assume.assumeTrue("/bench-xfs not present (rig-only arm)", mount.isDirectory() && mount.isWritable());
        runFixedVsNonFixed(mount);
    }

    @Test
    public void testMisalignedODirectReadSurfacesEinval() throws IOException
    {
        File tempDir = FileUtils.getTempDir();
        Assume.assumeTrue(FileUtils.isDirectIOSupported(tempDir));
        int blockSize = FileUtils.getBlockSize(tempDir);
        File file = patternFileIn(tempDir, blockSize);

        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, ExtendedOpenOption.DIRECT);
             UringRing ring = UringRing.create(8, 32))
        {
            int fd = NativeLibrary.getfd(channel);
            ByteBuffer buf = BufferUtil.allocateDirectAligned(blockSize, blockSize);
            ring.prepareRead(fd, 1, buf); // misaligned file offset
            ring.submitAndWait(1);
            int[] result = { 0 };
            ring.drainCompletions((opId, res) -> result[0] = res);
            assertEquals("misaligned O_DIRECT read must surface -EINVAL raw", -UringConstants.EINVAL, result[0]);
        }
    }

    @Test
    public void testRegistrationRules() throws IOException
    {
        try (UringRing ring = UringRing.create(8, 32))
        {
            // non-uniform capacities rejected
            try
            {
                ring.registerBuffers(new ByteBuffer[]{ ByteBuffer.allocateDirect(4096), ByteBuffer.allocateDirect(8192) });
                fail("expected IllegalArgumentException for non-uniform capacities");
            }
            catch (IllegalArgumentException expected)
            {
            }

            ring.registerBuffers(new ByteBuffer[]{ ByteBuffer.allocateDirect(4096) });

            // double registration rejected in Java, not left to shifting kernel EBUSY semantics
            try
            {
                ring.registerBuffers(new ByteBuffer[]{ ByteBuffer.allocateDirect(4096) });
                fail("expected IllegalStateException for double registration");
            }
            catch (IllegalStateException expected)
            {
            }

            // range validation against registered capacity
            try
            {
                ring.prepareReadFixed(0, 0, 0, 4000, 200);
                fail("expected IllegalArgumentException for range exceeding capacity");
            }
            catch (IllegalArgumentException expected)
            {
            }

            ring.unregisterBuffers();
            try
            {
                ring.unregisterBuffers();
                fail("expected IllegalStateException for unregister without registration");
            }
            catch (IllegalStateException expected)
            {
            }
        }
    }

    @Test
    public void testUnregisterRefusedWithFixedOpInFlight() throws IOException
    {
        File tempDir = FileUtils.getTempDir();
        int blockSize = FileUtils.getBlockSize(tempDir);
        File file = patternFileIn(tempDir, blockSize);
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
             UringRing ring = UringRing.create(8, 32))
        {
            int fd = NativeLibrary.getfd(channel);
            ring.registerBuffers(new ByteBuffer[]{ BufferUtil.allocateDirectAligned(blockSize, blockSize) });
            ring.prepareReadFixed(fd, 0, 0, 0, blockSize); // prepared, not yet completed
            try
            {
                ring.unregisterBuffers();
                fail("expected IllegalStateException with FIXED op in flight");
            }
            catch (IllegalStateException expected)
            {
            }
            ring.submitAndWait(1);
            ring.drainCompletions((opId, res) -> assertEquals(blockSize, res));
            ring.unregisterBuffers();
        }
    }
}
