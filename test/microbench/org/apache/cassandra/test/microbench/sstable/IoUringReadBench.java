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

package org.apache.cassandra.test.microbench.sstable;

import java.io.IOException;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import org.apache.cassandra.config.CassandraRelevantProperties;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.io.sstable.SSTableCursorReader;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.uring.IoUringAvailability;

/**
 * A/B benchmark comparing SSTable cursor reads with and without io_uring,
 * for both compressed and uncompressed SSTables.
 *
 * Produces a matrix: (useIoUring=true/false) x (compression=none/lz4)
 *
 * Run with:
 *   ant testsome -Dtest.name=org.apache.cassandra.test.microbench.sstable.IoUringReadBench
 * Or:
 *   java -jar build/test/jmh/microbench.jar IoUringReadBench -f 1 -wi 5 -i 10
 */
@State(Scope.Benchmark)
public class IoUringReadBench extends SSTableAbstractBench
{
    @Param({"true", "false"})
    boolean useIoUring;

    @Param({"none", "lz4"})
    String compression;

    private SSTableReader ssTableReader;
    private SSTableCursorReader cursor;
    long[] counters = new long[4];

    @Override
    public void setup() throws Throwable
    {
        // Set io_uring and partitioner BEFORE prepareServer() reads config
        System.setProperty(CassandraRelevantProperties.USE_IO_URING.getKey(), String.valueOf(useIoUring));
        // Cursor API requires Murmur3 (createReusableKey not supported by ByteOrderedPartitioner)
        System.setProperty(CassandraRelevantProperties.PARTITIONER.getKey(),
                           "org.apache.cassandra.dht.Murmur3Partitioner");

        String status = useIoUring
                        ? (IoUringAvailability.isAvailable() ? "io_uring ENABLED" : "io_uring REQUESTED but unavailable")
                        : "io_uring DISABLED (FileChannel baseline)";
        System.err.println(status + " | compression=" + compression);

        super.setup();
    }

    @Override
    protected void setupTable()
    {
        keyspace = createKeyspace("CREATE KEYSPACE %s with replication = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 } and durable_writes = false");

        String compressionClause = "none".equals(compression)
                                   ? "WITH compression = {'enabled': 'false'}"
                                   : "WITH compression = {'class': 'LZ4Compressor'}";

        String tableDef = "CREATE TABLE %s ( userid bigint, picid1 bigint, picid2 bigint, commentid bigint, "
                          + "PRIMARY KEY(userid, picid1, picid2)) " + compressionClause;

        table = createTable(keyspace, tableDef);
        execute("use " + keyspace + ";");

        Keyspace.system().forEach(k -> k.getColumnFamilyStores().forEach(c -> c.disableAutoCompaction()));

        cfs = Keyspace.open(keyspace).getColumnFamilyStore(table);
        cfs.disableAutoCompaction();
    }

    @Setup(Level.Invocation)
    public void prepareReader() throws IOException
    {
        ssTableReader = super.getReader();
        cursor = new SSTableCursorReader(ssTableReader);
    }

    @TearDown(Level.Invocation)
    public void closeReader() throws Exception
    {
        cursor.close();
        ssTableReader.ref().close();
    }

    @Benchmark
    public void readPartitionAndUnfiltered() throws IOException
    {
        SSTableReadingFileCursorBench.readPartitionAndUnfiltered(counters, cursor);
    }

    @Benchmark
    public void readPartitionSkipUnfiltered() throws IOException
    {
        SSTableReadingFileCursorBench.readPartitionSkipUnfiltered(counters, cursor);
    }

    @Benchmark
    public void skipPartition() throws IOException
    {
        SSTableReadingFileCursorBench.skipPartition(counters, cursor);
    }
}
