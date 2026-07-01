package com.phaohn.spyhelper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phaohn.spyhelper.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: WordRepository

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshDashboard() }

    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshDashboard() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        repository = PhaoHNApp.repo(requireActivity().application)

        binding.rowAccessibility.setOnClickListener {
            val ctx = requireContext()
            if (SpyAccessibilityService.isEnabled(ctx)) {
                OemSettingsNavigator.openAccessibilitySettings(ctx)
            } else if (BuildConfig.A11Y_DIRECT) {
                AccessibilitySetupHelper.openAccessibilityServiceDetails(ctx)
            } else {
                accessibilityLauncher.launch(
                    Intent(ctx, AccessibilityOnboardingActivity::class.java),
                )
            }
        }
        binding.rowOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(requireContext())) {
                overlayLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                )
            }
        }
        binding.rowNotification.setOnClickListener {
            (activity as? MainActivity)?.requestNotificationPermission()
        }
        binding.readyChip.setOnClickListener {
            if (!PermissionHelper.check(requireContext()).allGranted) {
                (activity as? MainActivity)?.showPermissionsSetup()
            }
        }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    fun refreshDashboard() {
        if (_binding == null) return
        val ctx = requireContext()
        val perm = PermissionHelper.check(ctx)
        val on = getString(R.string.status_on)
        val off = getString(R.string.status_off)

        setPermRow(binding.dotAccessibility, binding.statusAccessibility, perm.accessibility, on, off)
        if (!perm.accessibility && AccessibilitySetupHelper.needsRestrictedSettingsUnlock(ctx)) {
            binding.statusAccessibility.text = getString(R.string.accessibility_restricted_hint)
        }
        setPermRow(binding.dotOverlay, binding.statusOverlay, perm.overlay, on, off)
        setPermRow(binding.dotNotification, binding.statusNotification, perm.notification, on, off)

        if (perm.allGranted) {
            binding.readyChip.setBackgroundResource(R.drawable.bg_ready_hero_ok)
            binding.readyChip.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
            binding.readyChip.text = "✓ ${getString(R.string.ready_ok)}"
        } else {
            binding.readyChip.setBackgroundResource(R.drawable.bg_ready_hero_warn)
            binding.readyChip.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
            binding.readyChip.text = "! ${getString(R.string.ready_missing)}"
        }

        lifecycleScope.launch {
            binding.statPairs.text = repository.pairCount().toString()
            binding.statLookups.text = repository.historyCount().toString()
        }
    }

    private fun setPermRow(dot: View, statusView: android.widget.TextView, ok: Boolean, on: String, off: String) {
        dot.setBackgroundResource(if (ok) R.drawable.dot_ok else R.drawable.dot_off)
        statusView.text = if (ok) on else off
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = HomeFragment()
    }
}