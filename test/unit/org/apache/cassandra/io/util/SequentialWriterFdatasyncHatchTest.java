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
package org.apache.cassandra.io.util;

import java.io.IOException;

import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.io.compress.BufferType;

import static org.apache.cassandra.config.CassandraRelevantProperties.TRICKLE_FSYNC_USE_FDATASYNC;
import static org.junit.Assert.assertEquals;

/**
 * The operator escape hatch back to a per-interval fdatasync. Its own class because SequentialWriter reads
 * the property once, when it loads, and the build gives each test class its own JVM.
 */
public class SequentialWriterFdatasyncHatchTest
{
    private static final int BUFFER_SIZE = 4096;

    static
    {
        assertEquals("renaming the key silently breaks every operator's -D",
                     "cassandra.trickle_fsync.use_fdatasync", TRICKLE_FSYNC_USE_FDATASYNC.getKey());
        TRICKLE_FSYNC_USE_FDATASYNC.setBoolean(true);
    }

    @BeforeClass
    public static void setupDD()
    {
        DatabaseDescriptor.daemonInitialization();
    }

    @Test
    public void intervalsSyncDataAndSubmitNoRanges() throws IOException
    {
        File file = SequentialWriterTest.TestableSW.tempFile("hatch");
        CountingWriter writer = new CountingWriter(file, SequentialWriterOption.newBuilder()
                                                                              .bufferSize(BUFFER_SIZE)
                                                                              .bufferType(BufferType.OFF_HEAP)
                                                                              .trickleFsync(true)
                                                                              .trickleFsyncByteInterval(BUFFER_SIZE)
                                                                              .finishOnClose(false)
                                                                              .build());
        try
        {
            byte[] bytes = new byte[BUFFER_SIZE];
            for (int i = 0; i < 3; i++)
            {
                writer.write(bytes);
                writer.flush();
            }

            assertEquals("each interval must fdatasync under the hatch", 3, writer.dataSyncs);
            assertEquals("the hatch must not also pace writeback", 0, writer.rangeCalls);
        }
        finally
        {
            writer.close();
            file.tryDelete();
        }
    }

    private static class CountingWriter extends SequentialWriter
    {
        int dataSyncs = 0;
        int rangeCalls = 0;

        CountingWriter(File file, SequentialWriterOption option)
        {
            super(file, option);
        }

        @Override
        protected void force(boolean metaData)
        {
            if (!metaData)
                dataSyncs++;
            super.force(metaData);
        }

        @Override
        protected boolean syncFileRange(long offset, long nbytes, int flags) throws IOException
        {
            rangeCalls++;
            return super.syncFileRange(offset, nbytes, flags);
        }
    }
}
