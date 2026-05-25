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

import org.apache.cassandra.db.rows.Cell;
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

    /**
     * TTL-aware classifier that buckets SSTables by their earliest expiration time.
     *
     * <p>For SSTables containing rows written with TTL, Cassandra's
     * {@link SSTableReader#getMinLocalDeletionTime()} returns the earliest
     * expiration time across all rows in the SSTable (in seconds since epoch).
     * This is a direct measure of "how soon will this SSTable's data start
     * dying" — strictly stronger information than {@link MaxTimestamp} can
     * provide, because the same {@code maxTimestamp} can correspond to vastly
     * different deathtimes if the rows have different TTLs.
     *
     * <p>The bucket is computed as {@code floor(minLocalDeletionTime / windowSeconds)}.
     * SSTables without TTL information (sentinel {@link Cell#NO_DELETION_TIME})
     * fall back to {@link MaxTimestamp} classification — that preserves
     * baseline behaviour for non-TTL data while letting TTL data drive
     * grouping where it's meaningful.
     *
     * <p>This classifier provides information UCS T4 baseline cannot use
     * implicitly: UCS sorts by {@code maxTimestamp} (write-time), not by
     * {@code localDeletionTime} (expiration-time). On workloads with
     * heterogeneous TTLs, the two diverge — and bucketing by expiration is
     * the operationally useful axis.
     */
    final class MinLocalDeletionTime implements DeathtimeClassifier
    {
        public static final long DEFAULT_BASE_WINDOW_MICROS = 300_000_000L; // 5 min

        private final long baseWindowMicros;
        private final MaxTimestamp fallback;

        public MinLocalDeletionTime(long baseWindowMicros)
        {
            if (baseWindowMicros <= 0)
                throw new IllegalArgumentException("baseWindowMicros must be > 0, got " + baseWindowMicros);
            this.baseWindowMicros = baseWindowMicros;
            this.fallback = new MaxTimestamp(baseWindowMicros);
        }

        public MinLocalDeletionTime()
        {
            this(DEFAULT_BASE_WINDOW_MICROS);
        }

        @Override
        public int classify(SSTableReader sstable, int level, Controller controller)
        {
            long minDelSeconds = sstable.getMinLocalDeletionTime();
            if (minDelSeconds == Cell.NO_DELETION_TIME)
            {
                // No TTL'd rows in this SSTable — defer to maxTimestamp-based
                // bucketing so non-TTL data still gets time-coherent grouping.
                return fallback.classify(sstable, level, controller);
            }
            // minLocalDeletionTime is seconds since epoch; convert to microseconds
            // for unit consistency with windowForLevel (which is in microseconds).
            long minDelMicros = Math.multiplyExact(minDelSeconds, 1_000_000L);
            long window = windowForLevel(level, controller);
            return Math.toIntExact(Math.floorDiv(minDelMicros, window));
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
