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

package org.apache.cassandra.db.compaction.unified;

import org.apache.cassandra.io.sstable.format.SSTableReader;

/**
 * Maps an SSTable to a deathtime bucket id within a UCS level. Used by
 * {@link org.apache.cassandra.db.compaction.UnifiedCompactionStrategy} to
 * partition compaction candidates by expected invalidation time (deathtime)
 * before forming overlap sets, following the GDT principle described in
 * Lee, Ziegler, Leis, <i>How to Write to SSDs</i>, PVLDB Vol. 19 No. 7, 2026,
 * §4 (Deathtime-Based GC).
 *
 * <p>Two SSTables that map to the same bucket id are treated as having similar
 * expected lifetimes and may be compacted together. Two SSTables that map to
 * different bucket ids are kept in separate compaction picks even when their
 * token ranges overlap, so a compaction does not mix data of very different
 * lifetimes — the central GDT insight that reduces DB-side write amplification.
 *
 * <p>Implementations must be safe to call concurrently from the compaction
 * scheduler thread.
 */
public interface DeathtimeClassifier
{
    /**
     * @return the deathtime bucket id for {@code sstable} at the given level.
     *         SSTables with the same id are eligible to be compacted together;
     *         SSTables with different ids are not.
     */
    int classify(SSTableReader sstable, int level, Controller controller);

    /**
     * Classifier that buckets SSTables by {@code floor(maxTimestamp / windowSize)}
     * where {@code windowSize} grows with level as {@code baseWindow * fanout^level}.
     *
     * <p>The window grows with level because higher levels in UCS compact less
     * frequently — coarser deathtime resolution is appropriate there. SSTables
     * without a usable {@code maxTimestamp} (e.g., empty or unwritten metadata)
     * map to bucket 0, which preserves baseline behaviour for those.
     */

    final class MaxTimestamp implements DeathtimeClassifier
    {
        public static final long DEFAULT_BASE_WINDOW_MICROS = 3_600_000_000L; // 1 hour

        private final long baseWindowMicros;

        public MaxTimestamp(long baseWindowMicros)
        {
            if (baseWindowMicros <= 0)
                throw new IllegalArgumentException("baseWindowMicros must be > 0, got " + baseWindowMicros);
            this.baseWindowMicros = baseWindowMicros;
        }

        public MaxTimestamp()
        {
            this(DEFAULT_BASE_WINDOW_MICROS);
        }

        @Override
        public int classify(SSTableReader sstable, int level, Controller controller)
        {
            long ts = sstable.getMaxTimestamp();
            if (ts <= 0)
                return 0;
            long window = windowForLevel(level, controller);
            long bucket = Math.floorDiv(ts, window);
            // SSTable maxTimestamps are microseconds-since-epoch (~1.7e15 today),
            // and the smallest plausible window is ~1 minute (~6e7 micros), so
            // bucket fits comfortably in int (~3e7 at the extreme). Use exact to
            // surface programmer error if a pathological window is chosen.
            return Math.toIntExact(bucket);
        }

        long windowForLevel(int level, Controller controller)
        {
            int fanout = Math.max(controller.getFanout(level), 2);
            long window = baseWindowMicros;
            for (int i = 0; i < level; i++)
                window = Math.multiplyExact(window, (long) fanout);
            return window;
        }

        public long baseWindowMicros()
        {
            return baseWindowMicros;
        }
    }
}
