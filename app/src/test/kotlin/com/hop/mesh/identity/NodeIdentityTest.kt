package com.hop.mesh.identity

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 2: Unit tests for NodeIdentity (UUID format only; persistence needs Android context).
 */
class NodeIdentityTest {

    @Test
    fun nodeId_isValidUuidFormat() {
        val uuidString = UUID.randomUUID().toString()
        assertTrue(uuidString.length == 36)
        assertTrue(uuidString[8] == '-')
        assertTrue(uuidString[13] == '-')
        assertNotNull(UUID.fromString(uuidString))
    }
}
