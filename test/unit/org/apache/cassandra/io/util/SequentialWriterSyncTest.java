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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ClusteringComparator;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.io.compress.CompressedSequentialWriter;
import org.apache.cassandra.io.compress.DirectCompressedSequentialWriter;
import org.apache.cassandra.io.sstable.metadata.MetadataCollector;
import org.apache.cassandra.schema.CompressionParams;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;

/**
 * Records fsyncs and fdatasyncs at {@code force(boolean)}, which all of them go through: a read-back
 * cannot tell one from the other, and the unit suite runs under {@code cassandra.skip_sync}.
 */
@RunWith(Parameterized.class)
public class SequentialWriterSyncTest
{
    @Parameterized.Parameter(0)
    public String flavour;

    @Parameterized.Parameter(1)
    public WriterFactory factory;

    private Recording recording;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> writers()
    {
        return asList(new Object[][]{
            { "SequentialWriter", (WriterFactory) RecordingSW::new },
            { "ChecksummedSequentialWriter", (WriterFactory) RecordingChecksummed::new },
            { "CompressedSequentialWriter", (WriterFactory) RecordingCompressed::new },
            { "DirectCompressedSequentialWriter", (WriterFactory) RecordingDirect::new },
        });
    }

    @BeforeClass
    public static void setupDD()
    {
        DatabaseDescriptor.daemonInitialization();
    }

    @After
    public void cleanup()
    {
        if (recording != null)
            recording.cleanup();
    }

    @Test
    public void midStreamSyncsCommitNoMetadataAndCloseCommitsItOnce() throws IOException
    {
        recording = factory.create();
        SequentialWriter writer = recording.writer();

        writer.write(new byte[8192]);
        writer.sync();
        assertEquals(flavour + ": a mid-stream sync must not commit metadata",
                     asList(false), recording.forces());

        writer.finish();
        assertEquals(flavour + ": close must add exactly one metadata-committing sync",
                     asList(false, true), recording.forces());
    }

    @FunctionalInterface
    private interface WriterFactory
    {
        Recording create() throws IOException;
    }

    private interface Recording
    {
        SequentialWriter writer();
        List<Boolean> forces();
        void cleanup();
    }

    private static File tempFile(String suffix)
    {
        File file = FileUtils.createTempFile("syncContract", suffix);
        file.tryDelete();
        return file;
    }

    private static CompressionParams params()
    {
        return CompressionParams.lz4(4096);
    }

    private static MetadataCollector collector()
    {
        return new MetadataCollector(new ClusteringComparator(UTF8Type.instance));
    }

    private static class RecordingSW extends SequentialWriter implements Recording
    {
        private final List<Boolean> forces = new ArrayList<>();
        private final File data;

        RecordingSW()
        {
            this(tempFile(".db"));
        }

        private RecordingSW(File data)
        {
            super(data, SequentialWriterOption.DEFAULT);
            this.data = data;
        }

        @Override
        protected void force(boolean metaData)
        {
            forces.add(metaData);
            super.force(metaData);
        }

        public SequentialWriter writer() { return this; }
        public List<Boolean> forces() { return forces; }
        public void cleanup() { data.tryDelete(); }
    }

    private static class RecordingChecksummed extends ChecksummedSequentialWriter implements Recording
    {
        private final List<Boolean> forces = new ArrayList<>();
        private final List<File> files;

        RecordingChecksummed()
        {
            this(tempFile(".db"), tempFile(".crc"));
        }

        private RecordingChecksummed(File data, File crc)
        {
            super(data, crc, null, SequentialWriterOption.DEFAULT);
            this.files = Arrays.asList(data, crc);
        }

        @Override
        protected void force(boolean metaData)
        {
            forces.add(metaData);
            super.force(metaData);
        }

        public SequentialWriter writer() { return this; }
        public List<Boolean> forces() { return forces; }
        public void cleanup() { files.forEach(File::tryDelete); }
    }

    private static class RecordingCompressed extends CompressedSequentialWriter implements Recording
    {
        private final List<Boolean> forces = new ArrayList<>();
        private final List<File> files;

        RecordingCompressed()
        {
            this(tempFile(".db"), tempFile(".metadata"));
        }

        private RecordingCompressed(File data, File offsets)
        {
            super(data, offsets, null, SequentialWriterOption.DEFAULT, params(), collector());
            this.files = Arrays.asList(data, offsets);
        }

        @Override
        protected void force(boolean metaData)
        {
            forces.add(metaData);
            super.force(metaData);
        }

        public SequentialWriter writer() { return this; }
        public List<Boolean> forces() { return forces; }
        public void cleanup() { files.forEach(File::tryDelete); }
    }

    private static class RecordingDirect extends DirectCompressedSequentialWriter implements Recording
    {
        private final List<Boolean> forces = new ArrayList<>();
        private final List<File> files;

        RecordingDirect()
        {
            this(tempFile(".db"), tempFile(".metadata"));
        }

        private RecordingDirect(File data, File offsets)
        {
            super(data, offsets, null, SequentialWriterOption.DEFAULT, params(), collector(), null);
            this.files = Arrays.asList(data, offsets);
        }

        @Override
        protected void force(boolean metaData)
        {
            forces.add(metaData);
            super.force(metaData);
        }

        public SequentialWriter writer() { return this; }
        public List<Boolean> forces() { return forces; }
        public void cleanup() { files.forEach(File::tryDelete); }
    }
}
