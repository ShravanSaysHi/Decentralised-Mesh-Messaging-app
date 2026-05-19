package com.hop.mesh.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hop.mesh.bluetooth.BluetoothDiscoveryReceiver
import com.hop.mesh.databinding.ActivityMainBinding
import com.hop.mesh.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var discoveryReceiver: BluetoothDiscoveryReceiver? = null

    private val requestBluetoothEnable = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.refreshBondedDevices()
        } else {
            Toast.makeText(this, "Bluetooth is required", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            viewModel.refreshBondedDevices()
        } else {
            Toast.makeText(this, "Permissions needed for mesh networking", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkBluetoothAndPermissions()
        setupViewPager()
        collectState()
    }

    override fun onResume() {
        super.onResume()
        val controller = viewModel.bluetoothController
        if (controller != null && discoveryReceiver == null) {
            discoveryReceiver = BluetoothDiscoveryReceiver(controller)
        }
        discoveryReceiver?.register(this)
        if (hasAllPermissions()) {
            viewModel.refreshBondedDevices()
        }
    }

    override fun onPause() {
        super.onPause()
        discoveryReceiver?.unregister(this)
    }

    private fun hasAllPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun checkBluetoothAndPermissions() {
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.isUserInputEnabled = true

        // Sync BottomNav with ViewPager
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_devices -> binding.viewPager.currentItem = 0
                R.id.nav_chat -> binding.viewPager.currentItem = 1
                R.id.nav_network -> binding.viewPager.currentItem = 2
            }
            true
        }

        binding.viewPager.registerOnPageChangeCallback(
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    binding.bottomNav.menu.getItem(position).isChecked = true
                    // Update toolbar subtitle based on tab
                    binding.toolbar.subtitle = when (position) {
                        0 -> "Devices & Connections"
                        1 -> "Encrypted Messaging"
                        2 -> "Network Topology"
                        else -> ""
                    }
                }
            }
        )

        // Default subtitle
        binding.toolbar.subtitle = "Devices & Connections"
    }

    private fun collectState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                // Update discovery receiver when service becomes available
                if (state.serviceConnected && discoveryReceiver == null) {
                    val controller = viewModel.bluetoothController
                    if (controller != null) {
                        discoveryReceiver = BluetoothDiscoveryReceiver(controller)
                        discoveryReceiver?.register(this@MainActivity)
                    }
                }

                // Check Bluetooth enable on first service connect
                if (state.serviceConnected && !viewModel.isBluetoothEnabled && viewModel.isBluetoothAvailable) {
                    requestBluetoothEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            }
        }
    }

    fun navigateToChat(nodeId: String) {
        // Switch to the Chat tab (index 1)
        binding.viewPager.currentItem = 1
        
        // Let the ViewModel know we want to chat with this specific node
        viewModel.selectDestination(nodeId)
    }
}
