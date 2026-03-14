package com.minicode.ui.connection

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.minicode.R
import com.minicode.databinding.FragmentConnectionFormBinding
import com.minicode.model.AuthType
import com.minicode.viewmodel.ConnectionListViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ConnectionFormFragment : Fragment() {

    private var _binding: FragmentConnectionFormBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ConnectionListViewModel by activityViewModels()
    private var editProfileId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentConnectionFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editProfileId = arguments?.getString("profile_id")

        // Auth type dropdown
        val authTypes = arrayOf("Password", "Private Key")
        val authAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, authTypes)
        binding.dropdownAuthType.setAdapter(authAdapter)
        binding.dropdownAuthType.setText("Password", false)

        binding.dropdownAuthType.setOnItemClickListener { _, _, position, _ ->
            updateAuthFieldsVisibility(position == 1)
        }

        // Load existing profile if editing
        if (editProfileId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val profile = viewModel.getProfile(editProfileId!!) ?: return@launch
                binding.inputLabel.setText(profile.label)
                binding.inputHost.setText(profile.host)
                binding.inputPort.setText(profile.port.toString())
                binding.inputUsername.setText(profile.username)
                binding.inputInitialDirectory.setText(profile.initialDirectory ?: "")
                binding.inputStartupCommand.setText(profile.startupCommand ?: "")

                val isPrivateKey = profile.authType == AuthType.PRIVATE_KEY
                binding.dropdownAuthType.setText(if (isPrivateKey) "Private Key" else "Password", false)
                updateAuthFieldsVisibility(isPrivateKey)

                if (isPrivateKey) {
                    binding.inputPrivateKey.setText(viewModel.getPrivateKey(profile.id) ?: "")
                    binding.inputPassphrase.setText(viewModel.getPassphrase(profile.id) ?: "")
                } else {
                    binding.inputPassword.setText(viewModel.getPassword(profile.id) ?: "")
                }
            }
            binding.toolbar.title = "Edit Connection"
        } else {
            binding.toolbar.title = "New Connection"
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.buttonSave.setOnClickListener { saveProfile() }
        binding.buttonTest.setOnClickListener { testConnection() }

        // Observe test connection state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.testConnectionState.collect { state ->
                    binding.progressTest.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    binding.buttonTest.isEnabled = !state.isLoading
                    if (state.result != null) {
                        val color = if (state.isError) {
                            com.google.android.material.R.color.design_default_color_error
                        } else {
                            com.google.android.material.R.color.design_default_color_primary
                        }
                        Snackbar.make(binding.root, state.result, Snackbar.LENGTH_LONG).show()
                        viewModel.clearTestState()
                    }
                }
            }
        }
    }

    private fun updateAuthFieldsVisibility(isPrivateKey: Boolean) {
        binding.layoutPassword.visibility = if (isPrivateKey) View.GONE else View.VISIBLE
        binding.layoutPrivateKey.visibility = if (isPrivateKey) View.VISIBLE else View.GONE
        binding.layoutPassphrase.visibility = if (isPrivateKey) View.VISIBLE else View.GONE
    }

    private fun getAuthType(): AuthType =
        if (binding.dropdownAuthType.text.toString() == "Private Key") AuthType.PRIVATE_KEY else AuthType.PASSWORD

    private fun validate(): Boolean {
        var valid = true
        if (binding.inputLabel.text.isNullOrBlank()) {
            binding.layoutLabel.error = "Label required"
            valid = false
        } else {
            binding.layoutLabel.error = null
        }
        if (binding.inputHost.text.isNullOrBlank()) {
            binding.layoutHost.error = "Host required"
            valid = false
        } else {
            binding.layoutHost.error = null
        }
        if (binding.inputUsername.text.isNullOrBlank()) {
            binding.layoutUsername.error = "Username required"
            valid = false
        } else {
            binding.layoutUsername.error = null
        }
        return valid
    }

    private fun saveProfile() {
        if (!validate()) return
        viewModel.saveProfile(
            id = editProfileId,
            label = binding.inputLabel.text.toString().trim(),
            host = binding.inputHost.text.toString().trim(),
            port = binding.inputPort.text.toString().toIntOrNull() ?: 22,
            username = binding.inputUsername.text.toString().trim(),
            authType = getAuthType(),
            password = binding.inputPassword.text?.toString(),
            privateKey = binding.inputPrivateKey.text?.toString(),
            passphrase = binding.inputPassphrase.text?.toString(),
            initialDirectory = binding.inputInitialDirectory.text?.toString(),
            startupCommand = binding.inputStartupCommand.text?.toString(),
        )
        findNavController().popBackStack()
    }

    private fun testConnection() {
        if (binding.inputHost.text.isNullOrBlank() || binding.inputUsername.text.isNullOrBlank()) {
            Snackbar.make(binding.root, "Host and username are required", Snackbar.LENGTH_SHORT).show()
            return
        }
        viewModel.testConnection(
            host = binding.inputHost.text.toString().trim(),
            port = binding.inputPort.text.toString().toIntOrNull() ?: 22,
            username = binding.inputUsername.text.toString().trim(),
            authType = getAuthType(),
            password = binding.inputPassword.text?.toString(),
            privateKey = binding.inputPrivateKey.text?.toString(),
            passphrase = binding.inputPassphrase.text?.toString(),
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
