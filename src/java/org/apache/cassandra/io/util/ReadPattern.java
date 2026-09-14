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

package org.apache.cassandra.io.util;

/**
 * How a reader will walk the file it is opening.
 * <p>
 * Two independent decisions hang off this, and they do not have the same answer for every caller: whether to read
 * ahead, and whether the chunks are worth caching. A partition range read walks the file in order, so it reads ahead,
 * but it may run again over the same rows, so its chunks are still worth caching. Compaction reads in order too and
 * will never come back.
 */
public enum ReadPattern
{
    /** Point reads. Cache the chunks; no read-ahead. */
    RANDOM,

    /** Partition range reads. Read ahead, and still cache the chunks: the same rows may be read again. */
    SEQUENTIAL,

    /** Compaction, repair, streaming, index build. Read ahead, and bypass the cache: each chunk is read once. */
    BULK;

    /** Whether a reader on this pattern should read ahead of the position it is asked for. */
    public boolean readsAhead()
    {
        return this != RANDOM;
    }

    /** Whether chunks read on this pattern are one-shot, and so must not evict cached data. */
    public boolean bypassesChunkCache()
    {
        return this == BULK;
    }
}
