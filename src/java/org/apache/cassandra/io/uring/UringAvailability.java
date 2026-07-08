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
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.io.util.File;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.NativeLibrary;

import static org.apache.cassandra.config.CassandraRelevantProperties.OS_ARCH;
import static org.apache.cassandra.config.CassandraRelevantProperties.URING_ENABLED;

/**
 * One-time probe deciding whether the io_uring binding can be used on this JVM/kernel.
 * Result, human-readable reason, the kernel's feature word and the setup-flag tier the
 * kernel accepts are all determined once at class initialisation and cached.
 */
public final class UringAvailability
{
    private static final Logger logger = LoggerFactory.getLogger(UringAvailability.class);

    /**
     * Setup-flag tiers, best first. ≥6.1 kernels take SINGLE_ISSUER|DEFER_TASKRUN (matches the
     * one-ring-per-thread model); 5.19–6.0 take COOP_TASKRUN; older take none. Backports vary,
     * so tier selection is by trying setup and stepping down on EINVAL, not by version parsing.
     */
    public enum SetupTier
    {
        DEFER_TASKRUN(UringConstants.IORING_SETUP_SINGLE_ISSUER | UringConstants.IORING_SETUP_DEFER_TASKRUN),
        COOP_TASKRUN(UringConstants.IORING_SETUP_COOP_TASKRUN),
        NONE(0);

        public final int setupFlags;

        SetupTier(int setupFlags)
        {
            this.setupFlags = setupFlags;
        }
    }

    private static final boolean AVAILABLE;
    private static final String REASON;
    private static final int FEATURES;
    private static final SetupTier TIER;

    static
    {
        boolean available = false;
        String reason;
        int features = 0;
        SetupTier tier = SetupTier.NONE;

        String arch = OS_ARCH.getString();
        if (!URING_ENABLED.getBoolean())
        {
            reason = "disabled by " + URING_ENABLED.getKey();
        }
        else if (!("amd64".equals(arch) || "x86_64".equals(arch) || "aarch64".equals(arch)))
        {
            reason = "unverified arch: " + arch;
        }
        else if (!FBUtilities.isLinux)
        {
            reason = "not Linux";
        }
        else if (!UringNative.isAvailable())
        {
            reason = "JNA link failure";
        }
        else
        {
            ProbeResult result = liveProbe();
            String failure = result.reason != null ? result.reason : readProbe(result.tier);
            available = failure == null;
            features = result.features;
            tier = result.tier;
            reason = available
                     ? String.format("available (features=0x%x, tier=%s)", features, tier)
                     : failure;
        }

        AVAILABLE = available;
        REASON = reason;
        FEATURES = features;
        TIER = tier;
        logger.info("io_uring binding: {}", reason);
    }

    private UringAvailability() {}

    private static final class ProbeResult
    {
        final String reason; // null = success
        final int features;
        final SetupTier tier;

        ProbeResult(String reason, int features, SetupTier tier)
        {
            this.reason = reason;
            this.features = features;
            this.tier = tier;
        }
    }

    /**
     * Sets up a small live ring (stepping down the flag tiers on EINVAL), validates the
     * features word this binding relies on, and tears the ring down.
     */
    private static ProbeResult liveProbe()
    {
        ByteBuffer params = ByteBuffer.allocateDirect(UringConstants.PARAMS_SIZE) // pre-zeroed, GC-owned
                                      .order(ByteOrder.nativeOrder());
        Pointer paramsPtr = Native.getDirectBufferPointer(params);

        int ringFd = -1;
        SetupTier tier = null;
        int lastErrno = 0;
        for (SetupTier candidate : SetupTier.values())
        {
            zero(params);
            params.putInt(UringConstants.PARAMS_FLAGS, candidate.setupFlags);
            try
            {
                ringFd = (int) UringNative.ioUringSetup(4, paramsPtr);
                tier = candidate;
                break;
            }
            catch (LastErrorException e)
            {
                lastErrno = e.getErrorCode();
                if (lastErrno != UringConstants.EINVAL)
                    break; // only EINVAL means "flags unsupported, try the next tier"
            }
        }

        if (tier == null)
        {
            String detail = UringNative.errnoDescription(lastErrno);
            if (lastErrno == UringConstants.EPERM)
                detail += " — likely kernel.io_uring_disabled sysctl (>=6.6; default-disabled on some distros) or container seccomp policy";
            return new ProbeResult("io_uring_setup failed: " + detail, 0, SetupTier.NONE);
        }

        try
        {
            int features = params.getInt(UringConstants.PARAMS_FEATURES);
            int required = UringConstants.IORING_FEAT_SINGLE_MMAP
                           | UringConstants.IORING_FEAT_NODROP
                           | UringConstants.IORING_FEAT_SUBMIT_STABLE;
            if ((features & required) != required)
                return new ProbeResult(String.format("kernel lacks required io_uring features: have 0x%x, need 0x%x (kernel too old, <5.5)",
                                                     features, required),
                                       features, tier);
            return new ProbeResult(null, features, tier);
        }
        finally
        {
            try
            {
                UringNative.closeFd(ringFd);
            }
            catch (LastErrorException e)
            {
                logger.warn("Failed to close io_uring probe ring fd {}: {}", ringFd, UringNative.errnoDescription(e.getErrorCode()));
            }
        }
    }

    /**
     * Submits one real IORING_OP_READ through {@link UringRing} and byte-verifies the result —
     * proving the non-vectored opcode floor (5.6+) at probe time, not first use.
     * @return null on success, failure reason otherwise
     */
    private static String readProbe(SetupTier tier)
    {
        try
        {
            File file = FileUtils.createDeletableTempFile("uring-probe", ".bin");
            byte[] payload = "io_uring availability read probe".getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE))
            {
                channel.write(ByteBuffer.wrap(payload));
                channel.force(true);
                int fd = NativeLibrary.getfd(channel);
                if (fd < 0)
                    return "live READ probe failed: could not obtain fd for probe file";
                try (UringRing ring = UringRing.create(4, 8, tier))
                {
                    ByteBuffer read = ByteBuffer.allocateDirect(payload.length);
                    int n = ring.readSync(fd, 0, read);
                    if (n != payload.length)
                        return "live READ probe failed: read " + n + " of " + payload.length + " bytes";
                    read.flip();
                    for (int i = 0; i < payload.length; i++)
                        if (read.get(i) != payload[i])
                            return "live READ probe failed: byte mismatch at " + i;
                }
            }
            return null;
        }
        catch (Throwable t)
        {
            return "live READ probe failed: " + t;
        }
    }

    private static void zero(ByteBuffer buf)
    {
        for (int i = 0; i < buf.capacity(); i += 8)
            buf.putLong(i, 0L);
    }

    public static boolean isAvailable()
    {
        return AVAILABLE;
    }

    /** Why the binding is or is not available; for the available case includes the features word and setup tier. */
    public static String reason()
    {
        return REASON;
    }

    /** The io_uring_params.features word reported at probe time; 0 if unavailable. */
    public static int features()
    {
        return FEATURES;
    }

    /** The best setup-flag tier the kernel accepted at probe time. */
    public static SetupTier setupTier()
    {
        return TIER;
    }
}
