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
import java.nio.ByteBuffer;

import org.apache.cassandra.io.FSReadError;
import org.apache.cassandra.io.compress.BufferType;
import org.apache.cassandra.io.util.AbstractReaderFileProxy;
import org.apache.cassandra.io.util.BufferManagingRebufferer;
import org.apache.cassandra.io.util.ChannelProxy;
import org.apache.cassandra.io.util.ChunkReader;
import org.apache.cassandra.io.util.Rebufferer;

/**
 * A {@link ChunkReader} that reads SSTable chunks via io_uring instead of FileChannel.
 * Drop-in replacement for SimpleChunkReader — same constructor signature, same semantics.
 *
 * Uses the thread-local ring from {@link IoUringContext} to submit a single synchronous
 * IORING_OP_READ per chunk. This is the PoC path — batching comes later.
 */
public class IoUringChunkReader extends AbstractReaderFileProxy implements ChunkReader
{
    private final int bufferSize;
    private final BufferType bufferType;

    public IoUringChunkReader(ChannelProxy channel, long fileLength, BufferType bufferType, int bufferSize)
    {
        super(channel, fileLength);
        this.bufferSize = bufferSize;
        // io_uring requires direct (off-heap) buffers for DMA
        this.bufferType = BufferType.OFF_HEAP;
    }

    @Override
    public void readChunk(long position, ByteBuffer buffer)
    {
        buffer.clear();
        try
        {
            int fd = channel.getFileDescriptor();
            IoUringContext.ring().readSync(fd, position, buffer, buffer.remaining());
        }
        catch (IOException e)
        {
            throw new FSReadError(e, channel.filePath());
        }
        buffer.flip();
    }

    @Override
    public int chunkSize()
    {
        return bufferSize;
    }

    @Override
    public BufferType preferredBufferType()
    {
        return bufferType;
    }

    @Override
    public Rebufferer instantiateRebufferer(boolean forScan)
    {
        if (Integer.bitCount(bufferSize) == 1)
            return new BufferManagingRebufferer.Aligned(this);
        else
            return new BufferManagingRebufferer.Unaligned(this);
    }

    @Override
    public String toString()
    {
        return String.format("%s(%s - chunk length %d, data length %d)",
                             getClass().getSimpleName(),
                             channel.filePath(),
                             bufferSize,
                             fileLength());
    }
}
