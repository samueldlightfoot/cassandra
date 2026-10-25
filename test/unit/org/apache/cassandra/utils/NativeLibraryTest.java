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
package org.apache.cassandra.utils;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import org.apache.cassandra.io.util.File;
import org.apache.cassandra.io.util.FileUtils;

public class NativeLibraryTest
{
    @Test
    public void testSkipCache()
    {
        File file = FileUtils.createDeletableTempFile("testSkipCache", "1");

        NativeLibrary.trySkipCache(file.path(), 0, 0);
    }

    @Test
    public void getPid()
    {
        long pid = NativeLibrary.getProcessID();
        Assert.assertTrue(pid > 0);
    }

    @Test
    public void syncFileRangeSubmitsARegularFile() throws IOException
    {
        Assume.assumeTrue(FBUtilities.isLinux);
        File file = FileUtils.createDeletableTempFile("syncFileRange", "1");
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE))
        {
            channel.write(ByteBuffer.allocate(4096));
            int fd = NativeLibrary.getfd(channel);
            Assert.assertTrue(NativeLibrary.trySyncFileRange(fd, 0, 4096, NativeLibrary.SYNC_FILE_RANGE_WRITE));
            Assert.assertTrue(NativeLibrary.trySyncFileRange(fd, 0, 4096, NativeLibrary.SYNC_FILE_RANGE_WAIT_BEFORE));
        }
    }

    @Test
    public void syncFileRangeRefusedWithEinvalReturnsFalse() throws IOException
    {
        Assume.assumeTrue(FBUtilities.isLinux);
        File file = FileUtils.createDeletableTempFile("syncFileRange", "1");
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE))
        {
            int invalidFlags = 8;
            Assert.assertFalse("EINVAL means unsupported, which only disables pacing",
                               NativeLibrary.trySyncFileRange(NativeLibrary.getfd(channel), 0, 4096, invalidFlags));
        }
    }

    @Test
    public void syncFileRangeFailingWithAnyOtherErrnoThrows() throws IOException
    {
        Assume.assumeTrue(FBUtilities.isLinux);
        // sync_file_range rejects a character device with ESPIPE, a real errno outside the unsupported set
        try (FileChannel channel = FileChannel.open(Paths.get("/dev/null"), StandardOpenOption.WRITE))
        {
            NativeLibrary.trySyncFileRange(NativeLibrary.getfd(channel), 0, 4096, NativeLibrary.SYNC_FILE_RANGE_WAIT_BEFORE);
            Assert.fail("a writeback error swallowed here is lost: the next fsync on the descriptor reports success");
        }
        catch (IOException e)
        {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("errno (29)"));
        }
    }
}
