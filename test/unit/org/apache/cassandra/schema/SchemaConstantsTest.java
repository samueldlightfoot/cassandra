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

package org.apache.cassandra.schema;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the system-keyspace predicates, whose per-write hot path stopped lowercasing a fresh copy
 * of the keyspace name by making the backing name sets case-insensitive. These cases pin the
 * case-insensitivity the sets provide in place of that lowercasing.
 */
public class SchemaConstantsTest
{
    @Test
    public void localSystemKeyspaceRecognisedCaseInsensitively()
    {
        for (String name : new String[]{ "system", "System", "SYSTEM", "system_schema", "SYSTEM_SCHEMA", "system_accord" })
            assertTrue(name, SchemaConstants.isLocalSystemKeyspace(name));

        assertFalse("a user keyspace is not a local system keyspace", SchemaConstants.isLocalSystemKeyspace("my_app"));
    }

    @Test
    public void virtualSystemKeyspaceRecognisedCaseInsensitively()
    {
        assertTrue(SchemaConstants.isVirtualSystemKeyspace("system_views"));
        assertTrue(SchemaConstants.isVirtualSystemKeyspace("System_Views"));
        // isLocalSystemKeyspace folds in the virtual ones
        assertTrue(SchemaConstants.isLocalSystemKeyspace("SYSTEM_VIRTUAL_SCHEMA"));
        assertFalse(SchemaConstants.isVirtualSystemKeyspace("system"));
    }

    @Test
    public void replicatedSystemKeyspaceRecognisedCaseInsensitively()
    {
        assertTrue(SchemaConstants.isReplicatedSystemKeyspace("system_auth"));
        assertTrue(SchemaConstants.isReplicatedSystemKeyspace("System_Traces"));
        assertFalse(SchemaConstants.isReplicatedSystemKeyspace("system"));
        assertFalse(SchemaConstants.isReplicatedSystemKeyspace("my_app"));
    }

    @Test
    public void nonVirtualAndUnionPredicates()
    {
        assertTrue(SchemaConstants.isNonVirtualSystemKeyspace("SYSTEM"));
        assertTrue(SchemaConstants.isNonVirtualSystemKeyspace("system_auth"));
        assertFalse("virtual keyspaces are excluded", SchemaConstants.isNonVirtualSystemKeyspace("system_views"));

        assertTrue(SchemaConstants.isSystemKeyspace("System_Auth"));
        assertTrue(SchemaConstants.isSystemKeyspace("system_views"));
        assertFalse(SchemaConstants.isSystemKeyspace("my_app"));
    }

    @Test
    public void publicNameSetsHoldCanonicalLowercaseNames()
    {
        assertTrue(SchemaConstants.LOCAL_SYSTEM_KEYSPACE_NAMES.contains("system"));
        assertTrue(SchemaConstants.VIRTUAL_SYSTEM_KEYSPACE_NAMES.contains("system_views"));
        assertTrue(SchemaConstants.REPLICATED_SYSTEM_KEYSPACE_NAMES.contains("system_auth"));
        assertFalse(SchemaConstants.LOCAL_SYSTEM_KEYSPACE_NAMES.contains("my_app"));
    }
}
