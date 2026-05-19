package com.hop.mesh.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 unit tests for Bluetooth layer.
 * Tests data classes and constants without requiring Android runtime.
 */
class BluetoothControllerTest {

    @Test
    fun meshServiceUuid_isValid() {
        val uuid = BluetoothController.MESH_SERVICE_UUID
        assertEquals("00001101-0000-1000-8000-00805f9b34fb", uuid.toString().lowercase())
    }

    @Test
    fun discoveredDevice_equality() {
        val a = BluetoothController.DiscoveredDevice("AA:BB:CC:DD:EE:FF", "Device1", true)
        val b = BluetoothController.DiscoveredDevice("AA:BB:CC:DD:EE:FF", "Device1", true)
        val c = BluetoothController.DiscoveredDevice("AA:BB:CC:DD:EE:FF", "Other", false)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }

    @Test
    fun discoveredDevice_defaultIsBondedFalse() {
        val d = BluetoothController.DiscoveredDevice("00:11:22:33:44:55", "Name")
        assertFalse(d.isBonded)
    }

    @Test
    fun connectionState_disconnected() {
        val state = BluetoothController.ConnectionState.Disconnected
        assertTrue(state is BluetoothController.ConnectionState.Disconnected)
    }

    @Test
    fun connectionState_connected_holdsAddress() {
        val state = BluetoothController.ConnectionState.Connected("AA:BB:CC:DD:EE:FF")
        assertTrue(state is BluetoothController.ConnectionState.Connected)
        assertEquals("AA:BB:CC:DD:EE:FF", (state as BluetoothController.ConnectionState.Connected).remoteAddress)
    }

    @Test
    fun connectionState_error_holdsMessage() {
        val state = BluetoothController.ConnectionState.Error("Something failed")
        assertTrue(state is BluetoothController.ConnectionState.Error)
        assertEquals("Something failed", (state as BluetoothController.ConnectionState.Error).message)
    }
}
