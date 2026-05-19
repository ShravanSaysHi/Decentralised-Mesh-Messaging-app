package com.hop.mesh.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hop.mesh.databinding.FragmentChatBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Chat tab: inbox with chat bubbles, destination selector, message compose, broadcast.
 */
class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var inboxAdapter: InboxAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        inboxAdapter = InboxAdapter()
        binding.inboxList.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.inboxList.adapter = inboxAdapter

        // The Chat UI is now "isolated". Users navigate here via the Network or Devices tab.
        // There is no local dropdown anymore.

        binding.btnBack.setOnClickListener {
            // "Back" just clears the active chat and shows the prompt to select one
            viewModel.selectDestination(null)
            Toast.makeText(requireContext(), "Chat closed", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearDest.setOnClickListener {
            viewModel.selectDestination(null)
            Toast.makeText(requireContext(), "Switched to Global Chat", Toast.LENGTH_SHORT).show()
        }

        binding.btnSend.setOnClickListener {
            val destId = viewModel.uiState.value.selectedDestinationId
            val text = binding.editMessage.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                val success = if (destId == "BROADCAST") {
                    viewModel.broadcastMessage(text)
                } else {
                    viewModel.sendMessageToNode(destId, text)
                }
                
                if (success) {
                    binding.editMessage.text?.clear()
                } else {
                    Toast.makeText(requireContext(), "Failed to send (no peers?)", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "Message is empty", Toast.LENGTH_SHORT).show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                inboxAdapter.updateIdentity(viewModel.localNodeId, state.nodeNames)
                inboxAdapter.submitList(state.inboxMessages.toList())

                val hasMessages = state.inboxMessages.isNotEmpty()
                binding.inboxList.visibility = if (hasMessages) View.VISIBLE else View.GONE
                binding.emptyInboxHint.visibility = if (hasMessages) View.GONE else View.VISIBLE

                if (hasMessages) {
                    binding.inboxList.scrollToPosition(state.inboxMessages.size - 1)
                }

                // Update destination picker
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, state.knownDestinations)
                binding.destSelector.setAdapter(adapter)

                val selectedLabel = state.knownDestinations.find { it.nodeId == state.selectedDestinationId }?.displayName ?: state.selectedDestinationId
                binding.destSelector.setText(selectedLabel, false)

                binding.destSelector.onItemClickListener = AdapterView.OnItemClickListener { parent, _, position, _ ->
                    val item = parent.getItemAtPosition(position) as MainViewModel.DestinationItem
                    viewModel.selectDestination(item.nodeId)
                }

                // Dynamic hint based on destination
                binding.destSelectorLayout.hint = if (state.selectedDestinationId == "BROADCAST") "Global Chat" else "Private Chat"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
