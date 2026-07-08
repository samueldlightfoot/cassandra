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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.utils.FBUtilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class UringLifecycleTest
{
    @BeforeClass
    public static void checkAvailability()
    {
        Assume.assumeTrue("io_uring unavailable: " + UringAvailability.reason(), UringAvailability.isAvailable());
    }

    @After
    public void cleanup()
    {
        UringRings.closeAll();
    }

    @Test
    public void testThreadLocalIdentity() throws IOException
    {
        UringRing first = UringRings.threadLocal();
        assertSame(first, UringRings.threadLocal());
    }

    @Test
    public void testDistinctRingsOnDistinctThreads() throws Exception
    {
        UringRing mine = UringRings.threadLocal();
        AtomicReference<UringRing> theirs = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try
            {
                theirs.set(UringRings.threadLocal());
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }, "uring-lifecycle-distinct");
        thread.start();
        thread.join(10_000);
        assertNotNull(theirs.get());
        assertNotSame(mine, theirs.get());
    }

    @Test
    public void testDeadThreadRingReclaimedBySweep() throws Exception
    {
        AtomicReference<UringRing> ref = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try
            {
                ref.set(UringRings.threadLocal());
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }, "uring-lifecycle-dying");
        thread.start();
        thread.join(10_000);
        assertNotNull(ref.get());
        assertSame(ref.get(), UringRings.registered(thread));

        UringRings.sweep();
        assertTrue("dead thread's ring must be closed by the sweep", ref.get().isClosed());
        assertNull(UringRings.registered(thread));
    }

    @Test
    public void testCloseAllIdempotentAndRecoverable() throws IOException
    {
        UringRing ring = UringRings.threadLocal();
        UringRings.closeAll();
        UringRings.closeAll();
        assertEquals(0, UringRings.registeredCount());
        assertTrue(ring.isClosed());

        UringRing fresh = UringRings.threadLocal();
        assertNotSame(ring, fresh);
        assertTrue(!fresh.isClosed());
    }

    @Test
    public void testNoFdLeakAcrossCreateCloseCycles() throws IOException
    {
        UringRing.create(8, 32).close(); // warm any lazily-opened JVM/JNA fds out of the measurement
        long before = openFdCount();
        for (int i = 0; i < 200; i++)
            UringRing.create(8, 32).close();
        long after = openFdCount();
        assertTrue("fd count grew from " + before + " to " + after + " over 200 create/close cycles",
                   after - before < 10); // a real leak would show as +200
    }

    private static long openFdCount() throws IOException
    {
        Assume.assumeTrue(FBUtilities.isLinux);
        try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd")))
        {
            return fds.count();
        }
    }
}
