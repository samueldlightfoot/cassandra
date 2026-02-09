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

package org.apache.cassandra.db.compaction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.cassandra.db.AbstractCompactionController;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Directories;
import org.apache.cassandra.db.compaction.writers.CompactionAwareWriter;
import org.apache.cassandra.db.lifecycle.ILifecycleTransaction;
import org.apache.cassandra.io.sstable.ISSTableScanner;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.utils.TimeUUID;

class CursorCompactionPipeline extends AbstractCompactionPipeline {
    final CursorCompactor cursorCompactor;
    final CompactionTask task;
    long totalKeysWritten;
    CompactionAwareWriter writer;

    CursorCompactionPipeline(CompactionTask task, OperationType type, AbstractCompactionStrategy.ScannerList scanners, AbstractCompactionController controller, long nowInSec, TimeUUID compactionId) {
        this.task = task;
        // Extract SSTableReaders from the scanners, then close the scanners immediately.
        // CursorCompactor opens its own data readers via SSTableCursorReader so the scanner-opened
        // readers are not needed. Keeping them open wastes file descriptors and, when using direct
        // disk access mode, the scanner's ThreadLocalReadAheadBuffer pollutes the same static
        // thread-local block map that the cursor reader will use, causing read corruption.
        List<SSTableReader> sstables = new ArrayList<>();
        for (ISSTableScanner scanner : scanners.scanners)
        {
            sstables.addAll(scanner.getBackingSSTables());
            scanner.close();
        }
        cursorCompactor = new CursorCompactor(type, sstables, controller, nowInSec, compactionId);
    }

    public AutoCloseable openWriterResource(ColumnFamilyStore cfs,
                                            Directories directories,
                                            ILifecycleTransaction transaction,
                                            Set<SSTableReader> nonExpiredSSTables) {
        this.writer = task.getCompactionAwareWriter(cfs, directories, transaction, nonExpiredSSTables);
        return writer;
    }


    @Override
    public Collection<SSTableReader> finishWriting() {
        return writer.finish();
    }

    @Override
    public long estimatedKeys() {
        return writer.estimatedKeys();
    }

    @Override
    public CompactionInfo getCompactionInfo() {
        return cursorCompactor.getCompactionInfo();
    }

    @Override
    public boolean isGlobal() {
        return cursorCompactor.isGlobal();
    }

    @Override
    boolean processNextPartitionKey() throws IOException {
        if (cursorCompactor.writeNextPartition(writer)) {
            totalKeysWritten++;
            cursorCompactor.setTargetDirectory(writer.getSStableDirectoryPath());
            return true;
        }
        return false;
    }

    @Override
    public long[] getMergedRowCounts() {
        return cursorCompactor.getMergedRowsCounts();
    }

    @Override
    public long getTotalSourceCQLRows() {
        return cursorCompactor.getTotalSourceCQLRows();
    }

    @Override
    public long getTotalKeysWritten() {
        return totalKeysWritten;
    }

    @Override
    public long getTotalBytesScanned() {
        return cursorCompactor.getTotalBytesScanned();
    }

    @Override
    public void close() throws IOException {
        cursorCompactor.close();
    }

    @Override
    public void stop() {
        cursorCompactor.stop();
    }
}
