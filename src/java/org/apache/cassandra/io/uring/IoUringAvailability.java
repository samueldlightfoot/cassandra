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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.utils.NativeLibrary;

/**
 * Probes whether io_uring is available on the current platform by attempting to create
 * a minimal 1-entry ring. The result is cached — this check runs at most once.
 *
 * io_uring requires Linux 5.6+ on x86_64. On all other platforms (macOS, Windows, older
 * Linux kernels), this returns false and Cassandra falls back to FileChannel.
 */
public final class IoUringAvailability
{
    private static final Logger logger = LoggerFactory.getLogger(IoUringAvailability.class);

    private static final boolean available;
    private static final String unavailabilityReason;

    static
    {
        boolean avail = false;
        String reason = null;

        if (NativeLibrary.osType != NativeLibrary.OSType.LINUX)
        {
            reason = "io_uring is only supported on Linux (current OS: " + NativeLibrary.osType + ')';
        }
        else if (!IoUringNative.isAvailable())
        {
            reason = "JNA native library bindings are not available";
        }
        else
        {
            // Probe by creating a minimal ring and immediately closing it
            try (IoUringRing probe = new IoUringRing(1))
            {
                avail = true;
                logger.info("io_uring is available (ring fd={}, sq_entries={}, cq_entries={})",
                            probe.ringFd(), probe.sqEntries(), probe.cqEntries());
            }
            catch (Exception e)
            {
                reason = "io_uring_setup probe failed: " + e.getMessage();
                logger.debug("io_uring probe failed", e);
            }
        }

        available = avail;
        unavailabilityReason = reason;
    }

    private IoUringAvailability() {}

    /**
     * @return true if io_uring is available and functional on this platform
     */
    public static boolean isAvailable()
    {
        return available;
    }

    /**
     * @return human-readable reason why io_uring is unavailable, or null if available
     */
    public static String unavailabilityReason()
    {
        return unavailabilityReason;
    }
}
