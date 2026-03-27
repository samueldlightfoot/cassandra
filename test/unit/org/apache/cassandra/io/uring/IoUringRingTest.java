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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.Assume;
import org.junit.Test;

import org.apache.cassandra.utils.NativeLibrary;

import static org.junit.Assert.*;

public class IoUringRingTest
{
    @Test
    public void testRingSetupAndClose() throws IOException
    {
        Assume.assumeTrue("io_uring not available", IoUringAvailability.isAvailable());

        try (IoUringRing ring = new IoUringRing(4))
        {
            assertTrue(ring.ringFd() >= 0);
            assertTrue(ring.sqEntries() >= 4);
            assertTrue(ring.cqEntries() >= 4);
        }
    }

    @Test
    public void testReadSync() throws IOException
    {
        Assume.assumeTrue("io_uring not available", IoUringAvailability.isAvailable());

        // Write known data to a temp file
        byte[] expected = new byte[4096];
        ThreadLocalRandom.current().nextBytes(expected);

        Path tempFile = Files.createTempFile("iouring-test-", ".dat");
        try
        {
            Files.write(tempFile, expected);

            // Open the file and get its fd
            try (FileChannel fc = FileChannel.open(tempFile, StandardOpenOption.READ);
                 IoUringRing ring = new IoUringRing(4))
            {
                int fd = NativeLibrary.getfd(fc);
                assertTrue("Could not get fd from FileChannel", fd >= 0);

                // Read the entire file via io_uring
                ByteBuffer buf = ByteBuffer.allocateDirect(4096);
                int bytesRead = ring.readSync(fd, 0, buf, 4096);

                assertEquals(4096, bytesRead);
                buf.flip();

                byte[] actual = new byte[4096];
                buf.get(actual);
                assertArrayEquals(expected, actual);
            }
        }
        finally
        {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testReadSyncPartial() throws IOException
    {
        Assume.assumeTrue("io_uring not available", IoUringAvailability.isAvailable());

        byte[] data = new byte[8192];
        ThreadLocalRandom.current().nextBytes(data);

        Path tempFile = Files.createTempFile("iouring-test-partial-", ".dat");
        try
        {
            Files.write(tempFile, data);

            try (FileChannel fc = FileChannel.open(tempFile, StandardOpenOption.READ);
                 IoUringRing ring = new IoUringRing(4))
            {
                int fd = NativeLibrary.getfd(fc);

                // Read second half of the file
                ByteBuffer buf = ByteBuffer.allocateDirect(4096);
                int bytesRead = ring.readSync(fd, 4096, buf, 4096);

                assertEquals(4096, bytesRead);
                buf.flip();

                byte[] actual = new byte[4096];
                buf.get(actual);

                byte[] expectedSecondHalf = new byte[4096];
                System.arraycopy(data, 4096, expectedSecondHalf, 0, 4096);
                assertArrayEquals(expectedSecondHalf, actual);
            }
        }
        finally
        {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testMultipleSequentialReads() throws IOException
    {
        Assume.assumeTrue("io_uring not available", IoUringAvailability.isAvailable());

        byte[] data = new byte[16384];
        ThreadLocalRandom.current().nextBytes(data);

        Path tempFile = Files.createTempFile("iouring-test-multi-", ".dat");
        try
        {
            Files.write(tempFile, data);

            try (FileChannel fc = FileChannel.open(tempFile, StandardOpenOption.READ);
                 IoUringRing ring = new IoUringRing(4))
            {
                int fd = NativeLibrary.getfd(fc);

                // Read in 4KB chunks
                for (int offset = 0; offset < data.length; offset += 4096)
                {
                    ByteBuffer buf = ByteBuffer.allocateDirect(4096);
                    int bytesRead = ring.readSync(fd, offset, buf, 4096);
                    assertEquals(4096, bytesRead);

                    buf.flip();
                    byte[] actual = new byte[4096];
                    buf.get(actual);

                    byte[] expectedChunk = new byte[4096];
                    System.arraycopy(data, offset, expectedChunk, 0, 4096);
                    assertArrayEquals("Mismatch at offset " + offset, expectedChunk, actual);
                }
            }
        }
        finally
        {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testRegisterFiles() throws IOException
    {
        Assume.assumeTrue("io_uring not available", IoUringAvailability.isAvailable());

        byte[] data = new byte[4096];
        ThreadLocalRandom.current().nextBytes(data);

        Path tempFile = Files.createTempFile("iouring-test-regfd-", ".dat");
        try
        {
            Files.write(tempFile, data);

            try (FileChannel fc = FileChannel.open(tempFile, StandardOpenOption.READ);
                 IoUringRing ring = new IoUringRing(4))
            {
                int fd = NativeLibrary.getfd(fc);

                // Register the fd
                ring.registerFiles(new int[]{ fd });

                // Unregister
                ring.unregisterFiles();
            }
        }
        finally
        {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectHeapBuffer() throws IOException
    {
        Assume.assumeTrue("io_uring not available", IoUringAvailability.isAvailable());

        try (IoUringRing ring = new IoUringRing(4))
        {
            ByteBuffer heapBuf = ByteBuffer.allocate(4096);
            ring.readSync(0, 0, heapBuf, 4096);
        }
    }
}
