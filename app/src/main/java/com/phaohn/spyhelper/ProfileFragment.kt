package com.phaohn.spyhelper

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.phaohn.spyhelper.databinding.FragmentProfileBinding
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: AuthManager
    private lateinit var repository: WordRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        auth = AuthManager(requireContext())
        repository = PhaoHNApp.repo(requireActivity().application)
        refreshProfile()
        binding.profileVersion.text = getString(R.string.settings_version_fmt, BuildConfig.VERSION_NAME)
        binding.btnChangePassword.setOnClickListener { showChangePasswordDialog() }
        binding.btnLogout.setOnClickListener { logout() }
        return binding.root
    }

    private fun refreshProfile() {
        val user = auth.getUser() ?: return
        binding.profileUsername.text = user.username
        binding.profileRole.text = when (user.role) {
            AuthManager.ROLE_SUPERADMIN -> getString(R.string.profile_role_superadmin)
            AuthManager.ROLE_ADMIN -> getString(R.string.profile_role_admin)
            else -> getString(R.string.profile_role_user)
        }
    }

    private fun showChangePasswordDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val oldInput = EditText(requireContext()).apply {
            hint = getString(R.string.profile_old_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val newInput = EditText(requireContext()).apply {
            hint = getString(R.string.profile_new_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(oldInput)
        layout.addView(newInput)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_change_password)
            .setView(layout)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                lifecycleScope.launch {
                    try {
                        auth.changePassword(
                            oldInput.text.toString(),
                            newInput.text.toString(),
                        )
                        Toast.makeText(
                            requireContext(),
                            R.string.profile_password_changed,
                            Toast.LENGTH_SHORT,
                        ).show()
                        dialog.dismiss()
                    } catch (e: Exception) {
                        val msg = when (e.message) {
                            "wrong_password" -> getString(R.string.profile_wrong_password)
                            "password_short" -> getString(R.string.profile_password_short)
                            else -> getString(R.string.profile_password_fail)
                        }
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun logout() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.profile_logout_confirm)
            .setPositiveButton(R.string.profile_logout) { d, _ ->
                lifecycleScope.launch {
                    auth.logout()
                    repository.clearAllLocalData()
                    startActivity(Intent(requireContext(), LoginActivity::class.java))
                    requireActivity().finish()
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = ProfileFragment()
    }
}