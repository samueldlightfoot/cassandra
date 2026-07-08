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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.NamedThreadFactory;

/**
 * Per-thread {@link UringRing} registry: lazy creation, dead-thread reclamation, JVM-shutdown
 * closure. This registry IS the leak backstop — there is deliberately no Cleaner/finalizer on
 * UringRing (a Cleaner would need in-flight tracking to be safe to run; production TODO for
 * Phase 3). A ring belonging to a dead thread is reclaimed by the sweep that runs on each new
 * registration, or by {@link #closeAll()} at JVM shutdown, whichever comes first.
 * <p>
 * A dead thread's ring that still has ops in flight is removed from the registry but NOT closed
 * (deliberate leak): unmapping ring memory under in-flight kernel writes is undefined behaviour,
 * and with the owner dead nobody can ever reap the completions.
 * <p>
 * Production TODO (moot for the spike, owned by Phase 3's design): JVM shutdown hooks run
 * unordered with respect to StorageService's drain hook, so closeAll could in principle unmap
 * rings while drain-triggered writes are in flight.
 */
public final class UringRings
{
    private static final Logger logger = LoggerFactory.getLogger(UringRings.class);

    private static final ConcurrentHashMap<Thread, UringRing> rings = new ConcurrentHashMap<>();

    private static final int DEFAULT_SQ_ENTRIES = 64;
    private static final int DEFAULT_CQ_ENTRIES = 256;

    static
    {
        Runtime.getRuntime().addShutdownHook(NamedThreadFactory.createThread(UringRings::closeAll, "uring-rings-shutdown"));
    }

    private UringRings() {}

    /** Lazy per-thread ring (64 SQ / 256 CQ). Sweeps dead-thread rings on each new registration. */
    public static UringRing threadLocal() throws IOException
    {
        Thread self = Thread.currentThread();
        UringRing ring = rings.get(self);
        if (ring == null || ring.isClosed())
        {
            sweep();
            ring = UringRing.create(DEFAULT_SQ_ENTRIES, DEFAULT_CQ_ENTRIES);
            rings.put(self, ring);
        }
        return ring;
    }

    /** Closes and removes rings whose owner thread has died. */
    static void sweep()
    {
        for (Map.Entry<Thread, UringRing> entry : rings.entrySet())
        {
            if (!entry.getKey().isAlive())
            {
                rings.remove(entry.getKey(), entry.getValue());
                closeReclaimed(entry.getValue(), entry.getKey());
            }
        }
    }

    /** Closes every registered ring (live or dead owners); idempotent. */
    public static void closeAll()
    {
        for (Map.Entry<Thread, UringRing> entry : rings.entrySet())
        {
            rings.remove(entry.getKey(), entry.getValue());
            closeReclaimed(entry.getValue(), entry.getKey());
        }
    }

    private static void closeReclaimed(UringRing ring, Thread owner)
    {
        try
        {
            ring.close();
        }
        catch (IllegalStateException e)
        {
            // in-flight ops: leak rather than unmap under kernel writes (see class Javadoc)
            logger.warn("Leaking io_uring ring of thread {} ({}); it can never be safely closed", owner.getName(), e.getMessage());
        }
    }

    /** Test hook: number of currently registered rings. */
    static int registeredCount()
    {
        return rings.size();
    }

    /** Test hook: the ring registered for the given thread, if any. */
    static UringRing registered(Thread thread)
    {
        return rings.get(thread);
    }
}
