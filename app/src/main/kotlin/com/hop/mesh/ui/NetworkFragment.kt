package com.hop.mesh.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hop.mesh.databinding.FragmentNetworkBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Network tab: mesh topology visualization + routing table.
 */
class NetworkFragment : Fragment() {

    private var _binding: FragmentNetworkBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var routingAdapter: RoutingAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNetworkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        routingAdapter = RoutingAdapter { nodeId ->
            (requireActivity() as? MainActivity)?.navigateToChat(nodeId)
        }
        binding.routingList.layoutManager = LinearLayoutManager(requireContext())
        binding.routingList.isNestedScrollingEnabled = false
        binding.routingList.adapter = routingAdapter

        binding.btnRefreshTopology.setOnClickListener {
            viewModel.refreshBondedDevices()
        }

        binding.topologyView.onNodeClick = { nodeId ->
            (requireActivity() as? MainActivity)?.navigateToChat(nodeId)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                // Update topology view
                val meshNodes = state.routingEntries
                    .filter { it.nodeId != viewModel.localNodeId && it.cost > 0 }
                    .map { entry ->
                        val isStale = (System.currentTimeMillis() - entry.lastSeen) > 3 * 60 * 1000L
                        MeshTopologyView.MeshNode(
                            nodeId = entry.nodeId,
                            deviceName = entry.deviceName,
                            hopCount = entry.hopCount,
                            nextHop = entry.nextHop,
                            isStale = isStale
                        )
                    }
                binding.topologyView.setTopology(viewModel.localNodeId, meshNodes)

                // Update routing list
                routingAdapter.submitList(state.routingEntries)

                binding.emptyRoutingHint.visibility =
                    if (state.routingEntries.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
