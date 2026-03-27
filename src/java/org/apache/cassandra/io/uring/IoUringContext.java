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

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-local io_uring ring management. Each thread that performs io_uring I/O gets
 * its own ring instance, lazily initialized on first use. Rings are not thread-safe,
 * so this pattern avoids synchronization entirely.
 */
public final class IoUringContext
{
    private static final Logger logger = LoggerFactory.getLogger(IoUringContext.class);

    private static final int DEFAULT_RING_SIZE = 32;

    private static final ThreadLocal<IoUringRing> RING = new ThreadLocal<>();

    private IoUringContext() {}

    /**
     * Get the io_uring ring for the current thread. Creates one if it doesn't exist.
     *
     * @throws IOException if ring creation fails
     */
    public static IoUringRing ring() throws IOException
    {
        IoUringRing ring = RING.get();
        if (ring == null)
        {
            ring = new IoUringRing(DEFAULT_RING_SIZE);
            RING.set(ring);
            logger.debug("Created io_uring ring for thread {} (fd={}, sq_entries={})",
                         Thread.currentThread().getName(), ring.ringFd(), ring.sqEntries());
        }
        return ring;
    }

    /**
     * Close and remove the ring for the current thread, if one exists.
     */
    public static void closeCurrentThread()
    {
        IoUringRing ring = RING.get();
        if (ring != null)
        {
            ring.close();
            RING.remove();
        }
    }
}
