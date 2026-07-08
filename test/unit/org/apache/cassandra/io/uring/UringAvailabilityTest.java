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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.sun.jna.Native;
import com.sun.jna.Pointer;

import org.junit.Assume;
import org.junit.Test;

import org.apache.cassandra.utils.FBUtilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Outcome-asserting availability test: on Linux io_uring must be AVAILABLE (a kernel that
 * denies it is a broken test environment and should fail loudly, with the probe's reason in
 * the message); on other platforms it must be unavailable with the exact "not Linux" reason.
 * No blanket Assume — a skip can never mask a probe regression on the platform it targets.
 */
public class UringAvailabilityTest
{
    private static final int REQUIRED_FEATURES = UringConstants.IORING_FEAT_SINGLE_MMAP
                                                 | UringConstants.IORING_FEAT_NODROP
                                                 | UringConstants.IORING_FEAT_SUBMIT_STABLE;

    @Test
    public void testAvailabilityMatchesPlatform()
    {
        if (FBUtilities.isLinux)
        {
            assertTrue("io_uring must be available on Linux; probe said: " + UringAvailability.reason(),
                       UringAvailability.isAvailable());
            assertEquals(REQUIRED_FEATURES, UringAvailability.features() & REQUIRED_FEATURES);
            assertNotEquals(null, UringAvailability.setupTier());
            assertTrue(UringAvailability.reason().contains("features="));
            assertTrue(UringAvailability.reason().contains("tier="));
        }
        else
        {
            assertFalse(UringAvailability.isAvailable());
            assertEquals("not Linux", UringAvailability.reason());
            assertEquals(0, UringAvailability.features());
        }
    }

    /**
     * Empirical struct-layout validation (spec sub-phase 1.1): sets up a real 4-entry ring with
     * raw syscalls, asserts the io_uring_params fields land where {@link UringConstants} says,
     * then mmaps the ring region and proves the io_sqring_offsets/io_cqring_offsets layouts by
     * reading ring_entries/ring_mask through them — values only the kernel could have written.
     */
    @Test
    public void testStructLayoutEmpirically() throws Exception
    {
        Assume.assumeTrue(FBUtilities.isLinux);

        ByteBuffer params = ByteBuffer.allocateDirect(UringConstants.PARAMS_SIZE)
                                      .order(ByteOrder.nativeOrder());
        int ringFd = (int) UringNative.ioUringSetup(4, Native.getDirectBufferPointer(params));
        Pointer ring = null;
        long ringSize = 0;
        try
        {
            assertEquals(4, params.getInt(UringConstants.PARAMS_SQ_ENTRIES));
            int cqEntries = params.getInt(UringConstants.PARAMS_CQ_ENTRIES);
            assertTrue("cq_entries expected >= 8, got " + cqEntries, cqEntries >= 8);

            int features = params.getInt(UringConstants.PARAMS_FEATURES);
            assertEquals("features word must contain SINGLE_MMAP|NODROP|SUBMIT_STABLE",
                         REQUIRED_FEATURES, features & REQUIRED_FEATURES);

            int sqHeadOff = params.getInt(UringConstants.PARAMS_SQ_OFF + UringConstants.SQ_OFF_HEAD);
            int sqTailOff = params.getInt(UringConstants.PARAMS_SQ_OFF + UringConstants.SQ_OFF_TAIL);
            int sqMaskOff = params.getInt(UringConstants.PARAMS_SQ_OFF + UringConstants.SQ_OFF_RING_MASK);
            int sqEntriesOff = params.getInt(UringConstants.PARAMS_SQ_OFF + UringConstants.SQ_OFF_RING_ENTRIES);
            int sqDroppedOff = params.getInt(UringConstants.PARAMS_SQ_OFF + UringConstants.SQ_OFF_DROPPED);
            int sqArrayOff = params.getInt(UringConstants.PARAMS_SQ_OFF + UringConstants.SQ_OFF_ARRAY);
            int cqHeadOff = params.getInt(UringConstants.PARAMS_CQ_OFF + UringConstants.CQ_OFF_HEAD);
            int cqTailOff = params.getInt(UringConstants.PARAMS_CQ_OFF + UringConstants.CQ_OFF_TAIL);
            int cqMaskOff = params.getInt(UringConstants.PARAMS_CQ_OFF + UringConstants.CQ_OFF_RING_MASK);
            int cqEntriesOff = params.getInt(UringConstants.PARAMS_CQ_OFF + UringConstants.CQ_OFF_RING_ENTRIES);
            int cqCqesOff = params.getInt(UringConstants.PARAMS_CQ_OFF + UringConstants.CQ_OFF_CQES);

            // offsets must be distinct and inside a plausible region; array/cqes sit past the counters
            assertNotEquals(sqHeadOff, sqTailOff);
            assertNotEquals(cqHeadOff, cqTailOff);
            assertTrue(sqArrayOff > sqMaskOff);
            assertTrue(cqCqesOff > cqMaskOff);

            ringSize = Math.max(sqArrayOff + 4L * 4, cqCqesOff + (long) cqEntries * UringConstants.CQE_SIZE);
            ring = UringNative.map(ringSize, ringFd, UringConstants.IORING_OFF_SQ_RING);
            assertFalse("mmap of ring region failed", UringNative.isMapFailed(ring));

            // kernel-written values read back through our offset constants
            assertEquals(4, ring.getInt(sqEntriesOff));
            assertEquals(3, ring.getInt(sqMaskOff));
            assertEquals(cqEntries, ring.getInt(cqEntriesOff));
            assertEquals(cqEntries - 1, ring.getInt(cqMaskOff));
            assertEquals(0, ring.getInt(sqDroppedOff));
            assertEquals(ring.getInt(sqHeadOff), ring.getInt(sqTailOff)); // empty ring
        }
        finally
        {
            if (ring != null && !UringNative.isMapFailed(ring))
                UringNative.unmap(ring, ringSize);
            UringNative.closeFd(ringFd);
        }
    }
}
