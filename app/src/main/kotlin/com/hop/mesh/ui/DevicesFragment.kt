package com.hop.mesh.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hop.mesh.databinding.FragmentDevicesBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Devices tab: status card, host/discover actions, device list with connect/remove.
 */
class DevicesFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var connectedAdapter: DevicesAdapter
    private lateinit var availableAdapter: DevicesAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        connectedAdapter = DevicesAdapter(
            onChat = { device ->
                // Navigate to chat
                (requireActivity() as? MainActivity)?.navigateToChat(device.address)
            },
            onDisconnect = { device ->
                viewModel.disconnectPeer(device.address)
                Toast.makeText(requireContext(), "Disconnecting from ${device.name}", Toast.LENGTH_SHORT).show()
            }
        )
        binding.connectedList.layoutManager = LinearLayoutManager(requireContext())
        binding.connectedList.adapter = connectedAdapter

        availableAdapter = DevicesAdapter(
            onConnect = { device ->
                viewModel.connectTo(device.address)
                Toast.makeText(requireContext(), "Connecting to ${device.name}…", Toast.LENGTH_SHORT).show()
            },
            onRemove = { device ->
                viewModel.removeDevice(device.address)
                Toast.makeText(requireContext(), "Removed ${device.name}", Toast.LENGTH_SHORT).show()
            }
        )
        binding.devicesList.layoutManager = LinearLayoutManager(requireContext())
        binding.devicesList.adapter = availableAdapter

        binding.btnStartServer.setOnClickListener {
            viewModel.startServer()
            Toast.makeText(requireContext(), "Mesh hosting started", Toast.LENGTH_SHORT).show()
        }
        binding.btnDiscover.setOnClickListener {
            viewModel.startDiscovery()
            Toast.makeText(requireContext(), "Scanning for devices…", Toast.LENGTH_SHORT).show()
        }
        binding.btnDisconnect.setOnClickListener {
            viewModel.disconnectAll()
            Toast.makeText(requireContext(), "Disconnected all peers", Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.myNodeId.text = viewModel.localNodeId
                binding.status.text = state.connectionStatus

                val connected = state.reachablePeers
                val available = state.discoveredDevices.filter { it.address !in state.connectedPeers }

                connectedAdapter.submitList(connected)
                availableAdapter.submitList(available)

                val hasConnected = connected.isNotEmpty()
                binding.headerActiveConnections.visibility = if (hasConnected) View.VISIBLE else View.GONE
                binding.connectedList.visibility = if (hasConnected) View.VISIBLE else View.GONE

                binding.emptyDevicesHint.visibility =
                    if (state.discoveredDevices.isEmpty()) View.VISIBLE else View.GONE

                // Update status dot color
                val dotColor = when {
                    state.connectedPeers.isNotEmpty() -> requireContext().getColor(com.hop.mesh.R.color.status_connected)
                    state.isServerRunning -> requireContext().getColor(com.hop.mesh.R.color.status_reachable)
                    else -> requireContext().getColor(com.hop.mesh.R.color.status_offline)
                }
                binding.statusDot.setBackgroundColor(dotColor)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
