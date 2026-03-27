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

import org.apache.cassandra.io.compress.CompressionMetadata;
import org.apache.cassandra.io.compress.CorruptBlockException;
import org.apache.cassandra.io.util.ChannelProxy;
import org.apache.cassandra.io.util.CompressedChunkReader;
import org.apache.cassandra.io.util.DirectThreadLocalByteBufferHolder;
import org.apache.cassandra.utils.ChecksumType;

/**
 * A {@link CompressedChunkReader.CompressedReader} that reads compressed chunks via io_uring
 * instead of FileChannel. Mirrors {@code DirectRandomAccessReader} exactly — block-aligned reads
 * with direct ByteBuffers — but uses the thread-local io_uring ring for the actual I/O.
 *
 * Only the raw read is replaced; CRC checking and decompression remain unchanged in the
 * calling {@link CompressedChunkReader.Direct#readChunk}.
 */
public class IoUringCompressedReader implements CompressedChunkReader.CompressedReader
{
    private final ChannelProxy channel;
    private final int blockSize;
    private final DirectThreadLocalByteBufferHolder bufferHolder;

    public IoUringCompressedReader(ChannelProxy channel, int blockSize)
    {
        this.channel = channel;
        this.blockSize = blockSize;
        this.bufferHolder = new DirectThreadLocalByteBufferHolder(blockSize);
    }

    @Override
    public ByteBuffer read(CompressionMetadata.Chunk chunk, boolean shouldCheckCrc) throws CorruptBlockException
    {
        int length = shouldCheckCrc ? chunk.length + Integer.BYTES
                                    : chunk.length;

        long alignedPos = chunk.offset & -blockSize;
        int delta = (int) (chunk.offset - alignedPos);

        ByteBuffer buffer = bufferHolder.getBuffer(length + delta);
        try
        {
            int fd = channel.getFileDescriptor();
            int bytesRead = IoUringContext.ring().readSync(fd, alignedPos, buffer, buffer.remaining());
            if (bytesRead < length + delta)
                throw new CorruptBlockException(channel.filePath(), chunk);
        }
        catch (IOException e)
        {
            throw new CorruptBlockException(channel.filePath(), chunk, e);
        }

        buffer.position(delta);
        buffer.limit(delta + length);

        ByteBuffer slice = buffer.slice();
        slice.limit(chunk.length);

        if (shouldCheckCrc)
        {
            int checksum = (int) ChecksumType.CRC32.of(slice);
            slice.limit(length);
            if (slice.getInt() != checksum)
                throw new CorruptBlockException(channel.filePath(), chunk);

            slice.position(0).limit(chunk.length);
        }
        return slice;
    }
}
