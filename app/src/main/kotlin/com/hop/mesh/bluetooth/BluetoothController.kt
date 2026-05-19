package com.hop.mesh.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Phase 1: Bluetooth Communication Layer.
 */
class BluetoothController(private val context: Context) {

    companion object {
        val MESH_SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _discoveredDevices = MutableStateFlow<Set<DiscoveredDevice>>(emptySet())
    val discoveredDevices: StateFlow<Set<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _receivedData = MutableStateFlow<String>("")
    val receivedData: StateFlow<String> = _receivedData.asStateFlow()

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var acceptJob: Job? = null
    private var receiveJob: Job? = null

    val isBluetoothAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun clearReceivedData() { _receivedData.value = "" }

    @SuppressLint("MissingPermission")
    fun getLocalName(): String {
        return adapter?.name ?: "Unknown Device"
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun canConnect(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else true
    }

    @SuppressLint("MissingPermission")
    fun getBondedDevices(): Set<DiscoveredDevice> {
        if (!canConnect()) return emptySet()
        return try {
            adapter?.bondedDevices?.map {
                DiscoveredDevice(it.address, it.name ?: "Unknown", true)
            }?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        try {
            adapter?.cancelDiscovery()
            _discoveredDevices.value = emptySet()
            adapter?.startDiscovery()
        } catch (e: Exception) { }
    }

    fun addDiscoveredDevice(device: DiscoveredDevice) {
        _discoveredDevices.value = _discoveredDevices.value + device
    }

    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptySet()
    }

    @SuppressLint("MissingPermission")
    suspend fun startServer() {
        if (!canConnect()) {
            _connectionState.value = ConnectionState.Error("Missing Bluetooth Connect permission")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                serverSocket?.close()
                serverSocket = adapter?.listenUsingRfcommWithServiceRecord("HopMesh", MESH_SERVICE_UUID)
                _connectionState.value = ConnectionState.Listening
                acceptJob = CoroutineScope(Dispatchers.IO).launch { acceptLoop() }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error(e.message ?: "Server failed")
            }
        }
    }

    private suspend fun acceptLoop() {
        while (true) {
            try {
                val socket = serverSocket?.accept() ?: break
                withContext(Dispatchers.IO) {
                    serverSocket?.close()
                    serverSocket = null
                    setConnectedSocket(socket)
                    _connectionState.value = ConnectionState.Connected(socket.remoteDevice.address)
                }
                break
            } catch (e: Exception) { break }
        }
    }

    /**
     * Pair if needed, then connect. No need to open system settings first.
     */
    @SuppressLint("MissingPermission")
    suspend fun pairAndConnect(address: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!canConnect()) return@withContext Result.failure(SecurityException("Missing permission"))
        val device = adapter?.getRemoteDevice(address) ?: return@withContext Result.failure(IOException("Device not found"))
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> connectTo(address)
            else -> {
                _connectionState.value = ConnectionState.Pairing(address)
                when (val bondResult = waitForBond(device)) {
                    is BondResult.Success -> connectTo(address)
                    is BondResult.Failed -> {
                        _connectionState.value = ConnectionState.Error(bondResult.message)
                        Result.failure(IOException(bondResult.message))
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun waitForBond(device: BluetoothDevice): BondResult = suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (d?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                    BluetoothDevice.BOND_BONDED -> {
                        try { context?.unregisterReceiver(this) } catch (_: Exception) { }
                        if (cont.isActive) cont.resume(BondResult.Success)
                    }
                    BluetoothDevice.BOND_NONE -> {
                        val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                        if (prev == BluetoothDevice.BOND_BONDING) {
                            try { context?.unregisterReceiver(this) } catch (_: Exception) { }
                            if (cont.isActive) cont.resume(BondResult.Failed("Pairing declined or failed"))
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        cont.invokeOnCancellation {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
        }
        try {
            if (!device.createBond()) {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
                if (cont.isActive) cont.resume(BondResult.Failed("Could not start pairing"))
            }
        } catch (e: Exception) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
            if (cont.isActive) cont.resume(BondResult.Failed(e.message ?: "Pairing failed"))
        }
    }

    private sealed class BondResult {
        object Success : BondResult()
        data class Failed(val message: String) : BondResult()
    }

    @SuppressLint("MissingPermission")
    suspend fun connectTo(address: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!canConnect()) return@withContext Result.failure(SecurityException("Missing permission"))
        try {
            disconnect()
            val device = adapter?.getRemoteDevice(address) ?: return@withContext Result.failure(IOException("Device not found"))
            val socket = device.createRfcommSocketToServiceRecord(MESH_SERVICE_UUID)
            socket.connect()
            setConnectedSocket(socket)
            _connectionState.value = ConnectionState.Connected(address)
            Result.success(Unit)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Connect failed")
            Result.failure(e)
        }
    }

    private fun setConnectedSocket(socket: BluetoothSocket) {
        clientSocket = socket
        inputStream = socket.inputStream
        outputStream = socket.outputStream
    }

    fun disconnect() {
        acceptJob?.cancel()
        receiveJob?.cancel()
        try {
            inputStream?.close()
            outputStream?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (_: Exception) { }
        inputStream = null
        outputStream = null
        clientSocket = null
        serverSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun sendText(text: String): Boolean {
        return try {
            outputStream?.write(text.toByteArray())
            outputStream?.flush()
            true
        } catch (e: Exception) { false }
    }

    /** Phase 4+: raw bytes for protocol layer (length-prefixed frames). */
    var onRawBytes: ((ByteArray, Int, Int) -> Unit)? = null

    fun sendData(data: ByteArray): Boolean {
        return try {
            outputStream?.write(data)
            outputStream?.flush()
            true
        } catch (e: Exception) { false }
    }

    fun startReceiveLoop(scope: CoroutineScope) {
        receiveJob?.cancel()
        receiveJob = scope.launch(Dispatchers.IO) {
            val stream = inputStream ?: return@launch
            val buffer = ByteArray(4096)
            while (true) {
                try {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    onRawBytes?.invoke(buffer, 0, read)
                    val text = String(buffer, 0, read, Charsets.UTF_8)
                    _receivedData.value = _receivedData.value + text
                } catch (e: Exception) { break }
            }
        }
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Listening : ConnectionState()
        data class Pairing(val address: String) : ConnectionState()
        data class Connected(val remoteAddress: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    data class DiscoveredDevice(val address: String, val name: String, val isBonded: Boolean = false)
}
