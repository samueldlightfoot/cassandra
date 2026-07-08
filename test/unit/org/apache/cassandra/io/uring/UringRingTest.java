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
import java.util.Random;

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
 * Sub-phase 1.2 acceptance: sync facade on real temp files on a real filesystem
 * (no jimfs — it rejects the native fd path entirely).
 */
public class UringRingTest
{
    @BeforeClass
    public static void checkAvailability()
    {
        Assume.assumeTrue("io_uring unavailable: " + UringAvailability.reason(), UringAvailability.isAvailable());
    }

    private static File tempFileWithContent(byte[] content) throws IOException
    {
        File file = FileUtils.createDeletableTempFile("uringring", ".bin");
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE))
        {
            ByteBuffer buf = ByteBuffer.wrap(content);
            while (buf.hasRemaining())
                channel.write(buf);
            channel.force(true);
        }
        return file;
    }

    private static byte[] randomBytes(int n, long seed)
    {
        byte[] bytes = new byte[n];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    @Test
    public void testReadAtOffsets() throws IOException
    {
        byte[] content = randomBytes(8192, 1);
        File file = tempFileWithContent(content);
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
             UringRing ring = UringRing.create(8, 32))
        {
            int fd = NativeLibrary.getfd(channel);

            ByteBuffer atZero = ByteBuffer.allocateDirect(4096);
            assertEquals(4096, ring.readSync(fd, 0, atZero));
            assertBufferEquals(content, 0, atZero, 0, 4096);

            ByteBuffer atOffset = ByteBuffer.allocateDirect(1000);
            assertEquals(1000, ring.readSync(fd, 3210, atOffset));
            assertBufferEquals(content, 3210, atOffset, 0, 1000);
        }
    }

    @Test
    public void testReadIntoNonZeroPosition() throws IOException
    {
        byte[] content = randomBytes(4096, 2);
        File file = tempFileWithContent(content);
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
             UringRing ring = UringRing.create(8, 32))
        {
            int fd = NativeLibrary.getfd(channel);
            ByteBuffer buf = ByteBuffer.allocateDirect(1024);
            buf.position(100);
            assertEquals(924, ring.readSync(fd, 0, buf));
            assertEquals(1024, buf.position());
            assertBufferEquals(content, 0, buf, 100, 924);
        }
    }

    @Test
    public void testWriteFsyncReadBack() throws IOException
    {
        byte[] content = randomBytes(70000, 3); // > 64 KiB: not a single trivial transfer
        File file = FileUtils.createDeletableTempFile("uringring", ".bin");
        try (FileChannel writeChannel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE);
             UringRing ring = UringRing.create(8, 32))
        {
            int fd = NativeLibrary.getfd(writeChannel);
            ByteBuffer buf = ByteBuffer.allocateDirect(content.length);
            buf.put(content).flip();
            assertEquals(content.length, ring.writeSync(fd, 0, buf));
            ring.fsyncSync(fd, false);
            ring.fsyncSync(fd, true); // DATASYNC arm
        }

        // read back through a completely separate fd, plain NIO — real-FS round trip
        try (FileChannel readChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ))
        {
            assertEquals(content.length, readChannel.size());
            ByteBuffer readBack = ByteBuffer.allocate(content.length);
            while (readBack.hasRemaining() && readChannel.read(readBack) != -1)
            {
            }
            for (int i = 0; i < content.length; i++)
                if (content[i] != readBack.get(i))
                    fail("byte mismatch at " + i);
        }
    }

    @Test
    public void testEofReturnsShort() throws IOException
    {
        byte[] content = randomBytes(1000, 4);
        File file = tempFileWithContent(content);
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
             UringRing ring = UringRing.create(8, 32))
        {
            int fd = NativeLibrary.getfd(channel);
            ByteBuffer buf = ByteBuffer.allocateDirect(4096);
            assertEquals(1000, ring.readSync(fd, 0, buf));
            assertEquals(1000, buf.position());

            ByteBuffer pastEof = ByteBuffer.allocateDirect(64);
            assertEquals(0, ring.readSync(fd, 100000, pastEof));
        }
    }

    @Test
    public void testErrnoSurfacesInException() throws IOException
    {
        try (UringRing ring = UringRing.create(8, 32))
        {
            ByteBuffer buf = ByteBuffer.allocateDirect(64);
            try
            {
                ring.readSync(-1, 0, buf); // bad fd
                fail("expected IOException");
            }
            catch (IOException e)
            {
                assertTrue("message should carry errno text: " + e.getMessage(),
                           e.getMessage().contains("errno"));
            }
        }
    }

    @Test
    public void testHeapBufferRejected() throws IOException
    {
        try (UringRing ring = UringRing.create(8, 32))
        {
            try
            {
                ring.readSync(0, 0, ByteBuffer.allocate(64));
                fail("expected IllegalArgumentException");
            }
            catch (IllegalArgumentException expected)
            {
            }
        }
    }

    @Test
    public void testClosedRingThrows() throws IOException
    {
        UringRing ring = UringRing.create(8, 32);
        ring.close();
        ring.close(); // idempotent
        try
        {
            ring.readSync(0, 0, ByteBuffer.allocateDirect(64));
            fail("expected IllegalStateException");
        }
        catch (IllegalStateException expected)
        {
        }
    }

    @Test
    public void testSqIndexWrap() throws IOException
    {
        byte[] content = randomBytes(4096, 5);
        File file = tempFileWithContent(content);
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
             UringRing ring = UringRing.create(4, 16)) // small ring: 40 ops = 10 full wraps
        {
            int fd = NativeLibrary.getfd(channel);
            ByteBuffer buf = ByteBuffer.allocateDirect(256);
            for (int i = 0; i < 40; i++)
            {
                buf.clear();
                int offset = (i * 97) % 3840;
                assertEquals(256, ring.readSync(fd, offset, buf));
                assertBufferEquals(content, offset, buf, 0, 256);
            }
        }
    }

    private static void assertBufferEquals(byte[] expected, int expectedOffset, ByteBuffer actual, int actualOffset, int length)
    {
        for (int i = 0; i < length; i++)
            if (expected[expectedOffset + i] != actual.get(actualOffset + i))
                fail("byte mismatch at " + i + " (file offset " + (expectedOffset + i) + ')');
    }
}
