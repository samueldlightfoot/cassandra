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

package org.apache.cassandra.distributed.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.QueryTrace;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SimpleStatement;
import com.datastax.driver.core.Statement;

import org.apache.cassandra.db.MutationShardRouting;
import org.apache.cassandra.distributed.Cluster;

import static org.apache.cassandra.config.CassandraRelevantProperties.MUTATION_SHARD_ROUTING;
import static org.apache.cassandra.distributed.api.Feature.GOSSIP;
import static org.apache.cassandra.distributed.api.Feature.NATIVE_PROTOCOL;
import static org.apache.cassandra.distributed.api.Feature.NETWORK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the non-blocking native write path with shard routing on. With routing enabled the
 * coordinator hands its own local apply to a shard thread, so the Native-Transport worker dispatches
 * the CQL write and returns instead of parking on the acknowledgement; the post-execute finalize
 * (warnings/tracing teardown, response encode, flush) then runs on the completing thread. Unlike the
 * in-JVM {@code coordinator().execute()} path, driving writes through the real client protocol is the
 * only way to exercise the Dispatcher flip and the {@link com.datastax.driver.core.Cluster} carriers.
 *
 * <p>Covers the three write carriers (QUERY / EXECUTE / BATCH), concurrency (no hang, no lost writes),
 * a traced write (the self-carrying tracing teardown must complete the session on the finishing thread),
 * and — at RF=3 / QUORUM — a write whose terminal acknowledgement can arrive on a messaging thread that
 * never carried the coordinator's thread-locals, so the captured-context restore is load-bearing.
 */
public class NonBlockingWriteDispatchTest extends TestBaseImpl
{
    @BeforeClass
    public static void enableRouting()
    {
        // ROUTING_ENABLED / ShardExecutors are read once at each instance's class-init, so the flag must
        // be a visible process-global system property before any instance starts.
        MUTATION_SHARD_ROUTING.setString("true");
    }

    @AfterClass
    public static void disableRouting()
    {
        MUTATION_SHARD_ROUTING.reset();
    }

    @Test
    public void singleNodeCarriersConcurrencyAndTracing() throws Throwable
    {
        try (Cluster cluster = init(builder().withNodes(1)
                                             .withConfig(c -> c.with(GOSSIP, NETWORK, NATIVE_PROTOCOL))
                                             .start(), 1))
        {
            assertTrue("shard routing must be enabled for this test to exercise the thread hop",
                       cluster.get(1).callOnInstance(() -> MutationShardRouting.ROUTING_ENABLED));

            cluster.schemaChange(withKeyspace("CREATE TABLE %s.tbl (k int PRIMARY KEY, v int)"));

            try (com.datastax.driver.core.Cluster driver = com.datastax.driver.core.Cluster.builder()
                                                                                           .addContactPoint("127.0.0.1")
                                                                                           .build();
                 Session session = driver.connect())
            {
                String table = KEYSPACE + ".tbl";

                // QUERY carrier: an unprepared INSERT.
                session.execute("INSERT INTO " + table + " (k, v) VALUES (1, 10)");

                // EXECUTE carrier: a prepared INSERT.
                PreparedStatement insert = session.prepare("INSERT INTO " + table + " (k, v) VALUES (?, ?)");
                session.execute(insert.bind(2, 20));

                // BATCH carrier.
                BatchStatement batch = new BatchStatement();
                batch.add(insert.bind(3, 30));
                batch.add(insert.bind(4, 40));
                session.execute(batch);

                for (int k = 1; k <= 4; k++)
                    assertEquals("carrier write k=" + k, k * 10, readValue(session, table, k));

                // Concurrency: many in-flight writes must all complete (no hang now that the worker no
                // longer parks) and none may be lost.
                int concurrent = 500;
                int base = 1000;
                List<ResultSetFuture> futures = new ArrayList<>(concurrent);
                for (int i = 0; i < concurrent; i++)
                    futures.add(session.executeAsync(insert.bind(base + i, i)));
                for (ResultSetFuture f : futures)
                    f.getUninterruptibly(30, TimeUnit.SECONDS); // throws on hang; asserts the worker freed

                for (int i = 0; i < concurrent; i++)
                    assertEquals("concurrent write i=" + i, i, readValue(session, table, base + i));

                // Traced write: the tracing session is created on the worker but torn down on the
                // completing thread; a broken teardown would leave the session unrecorded (or trip the
                // -ea assertion in Tracing.newSession on the next traced request). Run several.
                for (int k = 90; k < 95; k++)
                {
                    Statement traced = new SimpleStatement("INSERT INTO " + table + " (k, v) VALUES (" + k + ", " + (k * 10) + ")");
                    traced.enableTracing();
                    QueryTrace trace = session.execute(traced).getExecutionInfo().getQueryTrace();
                    assertNotNull("traced write must record a session (teardown ran on the finishing thread)", trace);
                    assertNotNull("trace must have a session id", trace.getTraceId());
                    assertTrue("trace must record events", !trace.getEvents().isEmpty());
                    assertEquals("traced write k=" + k, k * 10, readValue(session, table, k));
                }
            }
        }
    }

    @Test
    public void quorumWritesCompleteOnRemoteAck() throws Throwable
    {
        try (Cluster cluster = init(builder().withNodes(3)
                                             .withConfig(c -> c.with(GOSSIP, NETWORK, NATIVE_PROTOCOL))
                                             .start(), 3))
        {
            cluster.schemaChange(withKeyspace("CREATE TABLE %s.tbl (k int PRIMARY KEY, v int)"));

            try (com.datastax.driver.core.Cluster driver = com.datastax.driver.core.Cluster.builder()
                                                                                           .addContactPoint("127.0.0.1")
                                                                                           .build();
                 Session session = driver.connect())
            {
                String table = KEYSPACE + ".tbl";
                PreparedStatement insert = session.prepare("INSERT INTO " + table + " (k, v) VALUES (?, ?)");
                insert.setConsistencyLevel(ConsistencyLevel.QUORUM);

                int rows = 500;
                List<ResultSetFuture> futures = new ArrayList<>(rows);
                for (int i = 0; i < rows; i++)
                    futures.add(session.executeAsync(insert.bind(i, i * 10)));
                for (ResultSetFuture f : futures)
                    f.getUninterruptibly(30, TimeUnit.SECONDS);

                // Read at ALL: every replica must hold every write — the async completion did not drop or
                // corrupt any acknowledgement even when it landed on a non-coordinator thread.
                for (int i = 0; i < rows; i++)
                {
                    Statement select = new SimpleStatement("SELECT v FROM " + table + " WHERE k = " + i)
                                       .setConsistencyLevel(ConsistencyLevel.ALL);
                    Row row = session.execute(select).one();
                    assertNotNull("row k=" + i + " must be present on all replicas", row);
                    assertEquals("quorum write k=" + i, i * 10, row.getInt("v"));
                }
            }
        }
    }

    private static int readValue(Session session, String table, int k)
    {
        Statement select = new SimpleStatement("SELECT v FROM " + table + " WHERE k = " + k)
                           .setConsistencyLevel(ConsistencyLevel.ONE);
        Row row = session.execute(select).one();
        assertNotNull("expected a row for k=" + k, row);
        return row.getInt("v");
    }
}
