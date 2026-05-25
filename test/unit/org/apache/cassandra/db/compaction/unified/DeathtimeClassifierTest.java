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

import org.junit.Test;

import org.apache.cassandra.db.rows.Cell;
import org.apache.cassandra.io.sstable.format.SSTableReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DeathtimeClassifierTest
{
    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long MICROS_PER_HOUR = 3600L * MICROS_PER_SECOND;

    private static SSTableReader sstableWithMaxTs(long maxTs)
    {
        SSTableReader rdr = mock(SSTableReader.class);
        when(rdr.getMaxTimestamp()).thenReturn(maxTs);
        return rdr;
    }

    /** Mock a Controller that returns a fixed fanout for any level. */
    private static Controller controllerWithFanout(int fanout)
    {
        Controller c = mock(Controller.class);
        when(c.getFanout(org.mockito.ArgumentMatchers.anyInt())).thenReturn(fanout);
        return c;
    }

    @Test
    public void sameTimestampMapsToSameBucket()
    {
        DeathtimeClassifier classifier = new DeathtimeClassifier.MaxTimestamp(MICROS_PER_HOUR);
        Controller c = controllerWithFanout(4);
        int b1 = classifier.classify(sstableWithMaxTs(1_700_000_000_000_000L), 0, c);
        int b2 = classifier.classify(sstableWithMaxTs(1_700_000_000_000_000L), 0, c);
        assertEquals(b1, b2);
    }

    @Test
    public void timestampsWithinSameWindowShareABucket()
    {
        DeathtimeClassifier classifier = new DeathtimeClassifier.MaxTimestamp(MICROS_PER_HOUR);
        Controller c = controllerWithFanout(4);
        long base = 1_700_000_000_000_000L;
        int b1 = classifier.classify(sstableWithMaxTs(base), 0, c);
        int b2 = classifier.classify(sstableWithMaxTs(base + MICROS_PER_HOUR / 2), 0, c);
        assertEquals(b1, b2);
    }

    @Test
    public void timestampsAcrossWindowsLandInDifferentBuckets()
    {
        DeathtimeClassifier classifier = new DeathtimeClassifier.MaxTimestamp(MICROS_PER_HOUR);
        Controller c = controllerWithFanout(4);
        long base = 1_700_000_000_000_000L;
        int b1 = classifier.classify(sstableWithMaxTs(base), 0, c);
        int b2 = classifier.classify(sstableWithMaxTs(base + 2 * MICROS_PER_HOUR), 0, c);
        assertNotEquals(b1, b2);
    }

    @Test
    public void higherLevelsUseWiderWindows()
    {
        DeathtimeClassifier.MaxTimestamp classifier = new DeathtimeClassifier.MaxTimestamp(MICROS_PER_HOUR);
        Controller c = controllerWithFanout(4);
        // base = 1 hour, fanout = 4
        assertEquals(MICROS_PER_HOUR, classifier.windowForLevel(0, c));
        assertEquals(4L * MICROS_PER_HOUR, classifier.windowForLevel(1, c));
        assertEquals(16L * MICROS_PER_HOUR, classifier.windowForLevel(2, c));
        assertEquals(64L * MICROS_PER_HOUR, classifier.windowForLevel(3, c));
    }

    @Test
    public void timestampsAcrossSmallWindowsButWithinLevel2WindowShareABucket()
    {
        DeathtimeClassifier classifier = new DeathtimeClassifier.MaxTimestamp(MICROS_PER_HOUR);
        Controller c = controllerWithFanout(4);
        // Align base to the level-2 window so the two observations are guaranteed to
        // be inside the same level-2 bucket (avoiding straddle of a window boundary).
        long level2Window = 16L * MICROS_PER_HOUR; // baseWindow * fanout^2 = 1h * 16
        long base = (1_700_000_000_000_000L / level2Window) * level2Window;
        long tsA = base + 1 * MICROS_PER_HOUR;
        long tsB = base + 6 * MICROS_PER_HOUR;

        // Level 0 window = 1h → 5 hours apart → different buckets.
        assertNotEquals(classifier.classify(sstableWithMaxTs(tsA), 0, c),
                        classifier.classify(sstableWithMaxTs(tsB), 0, c));

        // Level 2 window = 16h, both values inside the same aligned 16h window → same bucket.
        assertEquals(classifier.classify(sstableWithMaxTs(tsA), 2, c),
                     classifier.classify(sstableWithMaxTs(tsB), 2, c));
    }

    @Test
    public void zeroOrNegativeTimestampMapsToBucketZero()
    {
        DeathtimeClassifier classifier = new DeathtimeClassifier.MaxTimestamp(MICROS_PER_HOUR);
        Controller c = controllerWithFanout(4);
        assertEquals(0, classifier.classify(sstableWithMaxTs(0L), 0, c));
        assertEquals(0, classifier.classify(sstableWithMaxTs(-1L), 0, c));
        assertEquals(0, classifier.classify(sstableWithMaxTs(Long.MIN_VALUE), 0, c));
    }

    @Test
    public void zeroOrNegativeBaseWindowRejectedAtConstruction()
    {
        for (long bad : new long[] { 0L, -1L })
        {
            try
            {
                new DeathtimeClassifier.MaxTimestamp(bad);
                fail("expected IllegalArgumentException for baseWindowMicros=" + bad);
            }
            catch (IllegalArgumentException expected)
            {
                // ok
            }
        }
    }

    @Test
    public void defaultConstructorUsesOneHourWindow()
    {
        DeathtimeClassifier.MaxTimestamp classifier = new DeathtimeClassifier.MaxTimestamp();
        assertEquals(MICROS_PER_HOUR, classifier.baseWindowMicros());
        assertEquals(DeathtimeClassifier.MaxTimestamp.DEFAULT_BASE_WINDOW_MICROS, classifier.baseWindowMicros());
    }

    @Test
    public void fanoutBelowTwoIsTreatedAsTwo()
    {
        DeathtimeClassifier.MaxTimestamp classifier = new DeathtimeClassifier.MaxTimestamp(MICROS_PER_HOUR);
        Controller c1 = controllerWithFanout(1); // pathological — guard against /1 collapsing windows
        assertEquals(2L * MICROS_PER_HOUR, classifier.windowForLevel(1, c1));
        assertEquals(4L * MICROS_PER_HOUR, classifier.windowForLevel(2, c1));
    }

    // ---- MinLocalDeletionTime classifier ----------------------------------

    private static SSTableReader sstableWithDeletionAndMaxTs(long minLocalDeletionSec, long maxTsMicros)
    {
        SSTableReader rdr = mock(SSTableReader.class);
        when(rdr.getMinLocalDeletionTime()).thenReturn(minLocalDeletionSec);
        when(rdr.getMaxTimestamp()).thenReturn(maxTsMicros);
        return rdr;
    }

    @Test
    public void minDeletionTimeSameExpiryMapsToSameBucket()
    {
        DeathtimeClassifier classifier = new DeathtimeClassifier.MinLocalDeletionTime(MICROS_PER_HOUR);
        Controller c = controllerWithFanout(4);
        // Two SSTables whose data expires at the same second
        long expirySec = 1_700_000_000L;
        int b1 = classifier.classify(sstableWithDeletionAndMaxTs(expirySec, 1L), 0, c);
        int b2 = classifier.classify(sstableWithDeletionAndMaxTs(expirySec, 9_999L), 0, c);
        assertEquals(b1, b2);
        // maxTimestamp doesn't influence the result when deletion time is set
    }

    @Test
    public void minDeletionTimeDifferentTTLsSplitsBuckets()
    {
        DeathtimeClassifier classifier = new DeathtimeClassifier.MinLocalDeletionTime(MICROS_PER_HOUR);
        Controller c = controllerWithFanout(4);
        // SSTable A's earliest expiry: now + 300s (TTL=5min on the oldest row)
        // SSTable B's earliest expiry: now + 3600s (TTL=1hr on the oldest row)
        // Same maxTimestamp — UCS baseline (MaxTimestamp classifier) would put them in same bucket.
        // MinLocalDeletionTime separates them.
        long nowSec = 1_700_000_000L;
        int bShort = classifier.classify(sstableWithDeletionAndMaxTs(nowSec + 300, 1L), 0, c);
        int bLong  = classifier.classify(sstableWithDeletionAndMaxTs(nowSec + 3600, 1L), 0, c);
        assertNotEquals(bShort, bLong);
    }

    @Test
    public void minDeletionTimeFallsBackToMaxTimestampWhenNoTTL()
    {
        DeathtimeClassifier classifier = new DeathtimeClassifier.MinLocalDeletionTime(MICROS_PER_HOUR);
        Controller c = controllerWithFanout(4);

        // Two SSTables with no TTL (sentinel value) — should bucket by maxTimestamp instead.
        long ts1 = 1_700_000_000_000_000L;          // bucket A
        long ts2 = ts1 + 2L * MICROS_PER_HOUR;      // bucket A + 2 → different
        long ts3 = ts1 + MICROS_PER_HOUR / 2;       // within bucket A → same as ts1

        int b1 = classifier.classify(sstableWithDeletionAndMaxTs(Cell.NO_DELETION_TIME, ts1), 0, c);
        int b2 = classifier.classify(sstableWithDeletionAndMaxTs(Cell.NO_DELETION_TIME, ts2), 0, c);
        int b3 = classifier.classify(sstableWithDeletionAndMaxTs(Cell.NO_DELETION_TIME, ts3), 0, c);

        assertNotEquals(b1, b2);  // separated by 2h
        assertEquals(b1, b3);     // both within same 1h window
    }

    @Test
    public void minDeletionTimeIgnoresMaxTimestampWhenTTLPresent()
    {
        DeathtimeClassifier classifier = new DeathtimeClassifier.MinLocalDeletionTime(MICROS_PER_HOUR);
        Controller c = controllerWithFanout(4);
        // Identical expiry, wildly different maxTimestamp → must stay in same bucket
        long expirySec = 1_700_000_000L;
        int bA = classifier.classify(sstableWithDeletionAndMaxTs(expirySec, 0L), 0, c);
        int bB = classifier.classify(sstableWithDeletionAndMaxTs(expirySec, Long.MAX_VALUE / 2), 0, c);
        assertEquals(bA, bB);
    }

    @Test
    public void minDeletionTimeRejectsBadWindowAtConstruction()
    {
        for (long bad : new long[] { 0L, -1L })
        {
            try
            {
                new DeathtimeClassifier.MinLocalDeletionTime(bad);
                fail("expected IllegalArgumentException for baseWindowMicros=" + bad);
            }
            catch (IllegalArgumentException expected)
            {
                // ok
            }
        }
    }
}
