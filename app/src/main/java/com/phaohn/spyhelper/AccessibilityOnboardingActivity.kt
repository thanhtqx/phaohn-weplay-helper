package com.phaohn.spyhelper

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phaohn.spyhelper.databinding.ActivityAccessibilityOnboardingBinding

class AccessibilityOnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccessibilityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccessibilityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.a11y_onboard_title)

        binding.btnTrigger.setOnClickListener {
            OemSettingsNavigator.openAccessibilityServiceDetails(this)
        }
        binding.btnRestricted.setOnClickListener {
            if (!OemSettingsNavigator.openRestrictedSettings(this)) {
                OemSettingsNavigator.openAppDetails(this)
            }
        }
        binding.btnAccessibility.setOnClickListener {
            OemSettingsNavigator.openAccessibilityServiceDetails(this)
        }
        binding.btnAppDetails.setOnClickListener {
            OemSettingsNavigator.openAppDetails(this)
        }
        binding.btnDone.setOnClickListener { finish() }

        val needsRestrictedFlow = needsRestrictedFlow()
        binding.cardTrigger.visibility = if (needsRestrictedFlow) View.VISIBLE else View.GONE
        binding.cardRestricted.visibility = if (needsRestrictedFlow) View.VISIBLE else View.GONE

        if (!needsRestrictedFlow) {
            binding.stepA11yIcon.text = "1"
        }

        refreshSteps()
    }

    override fun onResume() {
        super.onResume()
        refreshSteps()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun needsRestrictedFlow(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !OemSettingsNavigator.isPlayStoreInstall(this)
    }

    private fun refreshSteps() {
        val restrictedFlow = needsRestrictedFlow()
        val restrictedOk = !restrictedFlow || AccessibilitySetupHelper.isRestrictedSettingsAllowed(this)
        val a11yOk = SpyAccessibilityService.isEnabled(this)

        if (restrictedFlow) {
            markStep(binding.stepRestrictedStatus, binding.stepRestrictedIcon, "2", restrictedOk)
            binding.stepTriggerStatus.text = getString(R.string.a11y_step_trigger_hint)
        }
        val a11yStep = if (restrictedFlow) "3" else "1"
        markStep(binding.stepA11yStatus, binding.stepA11yIcon, a11yStep, a11yOk)

        binding.btnRestricted.isEnabled = !restrictedOk
        binding.btnAccessibility.isEnabled = !a11yOk

        binding.btnDone.visibility = if (a11yOk) View.VISIBLE else View.GONE

        if (a11yOk) {
            setResult(RESULT_OK)
        }
    }

    private fun markStep(
        statusView: android.widget.TextView,
        iconView: android.widget.TextView,
        stepNumber: String,
        ok: Boolean,
    ) {
        statusView.text = getString(if (ok) R.string.a11y_step_done else R.string.a11y_step_pending)
        statusView.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.success else R.color.accent),
        )
        iconView.text = if (ok) "✓" else stepNumber
    }
}