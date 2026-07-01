package com.phaohn.spyhelper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.phaohn.spyhelper.databinding.BottomSheetPermissionsBinding

class PermissionsSetupSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPermissionsBinding? = null
    private val binding get() = _binding!!

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshStatus() }

    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshStatus() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetPermissionsBinding.inflate(inflater, container, false)
        val ctx = requireContext()

        binding.sheetRowAccessibility.setOnClickListener {
            if (SpyAccessibilityService.isEnabled(ctx)) {
                OemSettingsNavigator.openAccessibilitySettings(ctx)
            } else if (BuildConfig.A11Y_DIRECT) {
                AccessibilitySetupHelper.openAccessibilityServiceDetails(ctx)
            } else {
                accessibilityLauncher.launch(Intent(ctx, AccessibilityOnboardingActivity::class.java))
            }
        }
        binding.sheetRowOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(ctx)) {
                overlayLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}"),
                    ),
                )
            }
        }
        binding.sheetRowNotification.setOnClickListener {
            (activity as? MainActivity)?.requestNotificationPermission()
        }
        binding.btnPermissionsLater.setOnClickListener { dismiss() }

        refreshStatus()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (PermissionHelper.check(requireContext()).allGranted) {
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun refreshStatus() {
        if (_binding == null) return
        val ctx = requireContext()
        val perm = PermissionHelper.check(ctx)
        val on = getString(R.string.status_on)
        val off = getString(R.string.status_off)

        setPermRow(binding.sheetDotAccessibility, binding.sheetStatusAccessibility, perm.accessibility, on, off)
        if (!perm.accessibility && AccessibilitySetupHelper.needsRestrictedSettingsUnlock(ctx)) {
            binding.sheetStatusAccessibility.text = getString(R.string.accessibility_restricted_hint)
        }
        setPermRow(binding.sheetDotOverlay, binding.sheetStatusOverlay, perm.overlay, on, off)
        setPermRow(binding.sheetDotNotification, binding.sheetStatusNotification, perm.notification, on, off)

        (activity as? MainActivity)?.refreshHomeUi()
    }

    private fun setPermRow(dot: View, statusView: TextView, ok: Boolean, on: String, off: String) {
        dot.setBackgroundResource(if (ok) R.drawable.dot_ok else R.drawable.dot_off)
        statusView.text = if (ok) on else off
    }

    companion object {
        const val TAG = "permissions_setup"

        fun show(fragmentManager: FragmentManager) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            PermissionsSetupSheet().show(fragmentManager, TAG)
        }
    }
}