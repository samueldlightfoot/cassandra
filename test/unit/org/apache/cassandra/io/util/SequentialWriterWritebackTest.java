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
import java.util.ArrayList;
import java.util.List;

import com.google.common.io.Files;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.io.compress.BufferType;
import org.apache.cassandra.utils.NativeLibrary;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The writeback range bookkeeping behind {@code trickle_fsync}, exercised through a seam rather than the
 * syscall, so it runs everywhere rather than on Linux alone.
 */
public class SequentialWriterWritebackTest
{
    private static final int BUFFER_SIZE = 4096;

    private final List<RecordingWriter> writers = new ArrayList<>();

    @BeforeClass
    public static void setupDD()
    {
        DatabaseDescriptor.daemonInitialization();
    }

    @After
    public void cleanup()
    {
        for (RecordingWriter writer : writers)
        {
            if (writer.isOpen())
                writer.close();
            writer.file.tryDelete();
        }
        writers.clear();
    }

    @Test
    public void submitsTheNewRangeAndWaitsOnTheOneBeforeIt() throws IOException
    {
        RecordingWriter writer = writer(BUFFER_SIZE);
        writeIntervals(writer, 3);

        assertEquals("the first interval has no range before it to wait on", 5, writer.calls.size());
        assertWrite(writer.calls.get(0), 0, BUFFER_SIZE);

        assertWrite(writer.calls.get(1), BUFFER_SIZE, BUFFER_SIZE);
        assertWaitBefore(writer.calls.get(2), 0, BUFFER_SIZE);

        assertWrite(writer.calls.get(3), 2 * BUFFER_SIZE, BUFFER_SIZE);
        assertWaitBefore(writer.calls.get(4), BUFFER_SIZE, BUFFER_SIZE);

    }

    @Test
    public void aRangeThatWasNotSubmittedIsRetriedWholeAndNothingSyncsInstead() throws IOException
    {
        RecordingWriter writer = writer(BUFFER_SIZE);
        writeIntervals(writer, 1);
        assertEquals(1, writer.calls.size());

        writer.submit = false;
        writeIntervals(writer, 1);
        assertWrite(writer.calls.get(1), BUFFER_SIZE, BUFFER_SIZE);

        writer.submit = true;
        writeIntervals(writer, 1);

        assertWrite(writer.calls.get(2), BUFFER_SIZE, 2 * BUFFER_SIZE);
        assertWaitBefore(writer.calls.get(3), 0, BUFFER_SIZE);
        assertEquals("a range that was not submitted must not be synced some other way", 0, writer.dataSyncs);
    }

    @Test
    public void rangesResumeFromTheTruncationPoint() throws IOException
    {
        RecordingWriter writer = writer(BUFFER_SIZE);
        writeIntervals(writer, 2);
        DataPosition mark = writer.mark();
        long truncateTarget = writer.position();

        writeIntervals(writer, 2);
        writer.resetAndTruncate(mark);
        int before = writer.calls.size();

        writeIntervals(writer, 2);

        assertTrue("intervals must keep firing after a truncate", writer.calls.size() > before);
        assertWrite(writer.calls.get(before), truncateTarget, BUFFER_SIZE);
    }

    @Test
    public void pacingWritebackDoesNotChangeTheFileItWrites() throws IOException
    {
        byte[] contents = new byte[8 * BUFFER_SIZE];
        for (int i = 0; i < contents.length; i++)
            contents[i] = (byte) i;

        File paced = write(contents, true);
        File unpaced = write(contents, false);

        assertArrayEquals("writeback pacing must not alter a byte of the file",
                          Files.toByteArray(unpaced.toJavaIOFile()), Files.toByteArray(paced.toJavaIOFile()));
    }

    private File write(byte[] contents, boolean trickle) throws IOException
    {
        File file = SequentialWriterTest.TestableSW.tempFile("identity");
        try (SequentialWriter writer = new SequentialWriter(file, SequentialWriterOption.newBuilder()
                                                                                        .bufferSize(BUFFER_SIZE)
                                                                                        .bufferType(BufferType.OFF_HEAP)
                                                                                        .trickleFsync(trickle)
                                                                                        .trickleFsyncByteInterval(BUFFER_SIZE)
                                                                                        .finishOnClose(true)
                                                                                        .build()))
        {
            writer.write(contents);
        }
        return file;
    }

    private RecordingWriter writer(int interval)
    {
        RecordingWriter writer = new RecordingWriter(SequentialWriterTest.TestableSW.tempFile("writeback"),
                                                     SequentialWriterOption.newBuilder()
                                                                           .bufferSize(BUFFER_SIZE)
                                                                           .bufferType(BufferType.OFF_HEAP)
                                                                           .trickleFsync(true)
                                                                           .trickleFsyncByteInterval(interval)
                                                                           .finishOnClose(false)
                                                                           .build());
        writers.add(writer);
        return writer;
    }

    private static void writeIntervals(SequentialWriter writer, int count) throws IOException
    {
        byte[] bytes = new byte[BUFFER_SIZE];
        for (int i = 0; i < count; i++)
        {
            writer.write(bytes);
            writer.flush();
        }
    }

    private static void assertWrite(Range call, long offset, long nbytes)
    {
        assertEquals("offset", offset, call.offset);
        assertEquals("length", nbytes, call.nbytes);
        assertEquals("flags", NativeLibrary.SYNC_FILE_RANGE_WRITE, call.flags);
    }

    private static void assertWaitBefore(Range call, long offset, long nbytes)
    {
        assertEquals("offset", offset, call.offset);
        assertEquals("length", nbytes, call.nbytes);
        assertEquals("flags", NativeLibrary.SYNC_FILE_RANGE_WAIT_BEFORE, call.flags);
    }

    private static class Range
    {
        final long offset, nbytes;
        final int flags;

        Range(long offset, long nbytes, int flags)
        {
            this.offset = offset;
            this.nbytes = nbytes;
            this.flags = flags;
        }
    }

    private static class RecordingWriter extends SequentialWriter
    {
        final File file;
        final List<Range> calls = new ArrayList<>();
        int dataSyncs = 0;
        boolean submit = true;

        RecordingWriter(File file, SequentialWriterOption option)
        {
            super(file, option);
            this.file = file;
        }

        @Override
        protected boolean syncFileRange(long offset, long nbytes, int flags)
        {
            calls.add(new Range(offset, nbytes, flags));
            return submit;
        }

        @Override
        protected void syncDataOnlyInternal()
        {
            dataSyncs++;
            super.syncDataOnlyInternal();
        }
    }
}
