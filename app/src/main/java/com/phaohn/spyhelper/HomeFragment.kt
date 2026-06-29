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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.phaohn.spyhelper.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: WordRepository
    private val voteSeatChips = mutableListOf<Chip>()
    private val voteTapAtChips = mutableListOf<Chip>()
    private var suppressVoteSeatFeedback = false
    private var suppressVoteTapAtFeedback = false
    private var suppressVoteSwitchFeedback = false

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshDashboard() }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshDashboard() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        repository = PhaoHNApp.repo(requireActivity().application)

        binding.permAccessibility.root.setOnClickListener {
            AccessibilitySetupHelper.openAccessibilityFlow(this)
        }
        binding.permOverlay.root.setOnClickListener {
            if (!Settings.canDrawOverlays(requireContext())) {
                overlayLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                )
            }
        }
        binding.permNotification.root.setOnClickListener {
            (activity as? MainActivity)?.requestNotificationPermission()
        }
        binding.switchAutoReady.isChecked = SpyPrefs.isAutoReadyEnabled(requireContext())
        binding.switchAutoReady.setOnCheckedChangeListener { _, checked ->
            SpyPrefs.setAutoReadyEnabled(requireContext(), checked)
        }
        binding.switchAutoSit.isChecked = SpyPrefs.isAutoSitEnabled(requireContext())
        binding.switchAutoSit.setOnCheckedChangeListener { _, checked ->
            SpyPrefs.setAutoSitEnabled(requireContext(), checked)
        }
        setupVoteSeatChips()
        setupVoteTapAtChips()
        binding.switchAutoVote.setOnCheckedChangeListener { _, checked ->
            val ctx = requireContext()
            if (checked && !SpyPrefs.isVoteSeatChosen(ctx)) {
                suppressVoteSwitchFeedback = true
                binding.switchAutoVote.isChecked = false
                suppressVoteSwitchFeedback = false
                return@setOnCheckedChangeListener
            }
            if (suppressVoteSwitchFeedback) return@setOnCheckedChangeListener
            SpyPrefs.setAutoVoteEnabled(ctx, checked)
            updateVoteControlsEnabled(!checked)
        }
        refreshVoteUi()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    fun refreshDashboard() {
        if (_binding == null) return
        val ctx = requireContext()
        val autoReady = SpyPrefs.isAutoReadyEnabled(ctx)
        if (binding.switchAutoReady.isChecked != autoReady) {
            binding.switchAutoReady.isChecked = autoReady
        }
        val autoSit = SpyPrefs.isAutoSitEnabled(ctx)
        if (binding.switchAutoSit.isChecked != autoSit) {
            binding.switchAutoSit.isChecked = autoSit
        }
        refreshVoteUi()
        val perm = PermissionHelper.check(ctx)
        val on = getString(R.string.status_on)
        val off = getString(R.string.status_off)

        setPermRow(
            binding.permAccessibility.dotAccessibility,
            binding.permAccessibility.statusAccessibility,
            perm.accessibility,
            on,
            off,
        )
        if (!perm.accessibility && AccessibilitySetupHelper.needsRestrictedSettingsUnlock(ctx)) {
            binding.permAccessibility.statusAccessibility.text =
                getString(R.string.accessibility_restricted_hint)
        }
        setPermRow(
            binding.permOverlay.dotOverlay,
            binding.permOverlay.statusOverlay,
            perm.overlay,
            on,
            off,
        )
        setPermRow(
            binding.permNotification.dotNotification,
            binding.permNotification.statusNotification,
            perm.notification,
            on,
            off,
        )

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

    private fun setupVoteSeatChips() {
        val ctx = requireContext()
        val group = binding.voteSeatGroup
        voteSeatChips.clear()
        group.removeAllViews()
        val chosen = SpyPrefs.isVoteSeatChosen(ctx)
        val selected = if (chosen) SpyPrefs.voteTargetSeat(ctx) else null
        for (seat in 1..WePlayIds.SEAT_COUNT) {
            val chip = createVoteChip(seat.toString(), textSizeSp = 14f)
            group.addView(chip)
            voteSeatChips += chip
        }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressVoteSeatFeedback) return@setOnCheckedStateChangeListener
            if (SpyPrefs.isAutoVoteEnabled(ctx)) {
                suppressVoteSeatFeedback = true
                try {
                    syncVoteSeatChips(SpyPrefs.voteTargetSeat(ctx))
                } finally {
                    suppressVoteSeatFeedback = false
                }
                return@setOnCheckedStateChangeListener
            }
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(checkedId) ?: return@setOnCheckedStateChangeListener
            val num = chip.text.toString().toIntOrNull() ?: return@setOnCheckedStateChangeListener
            SpyPrefs.setVoteTargetSeat(ctx, num)
            SpyPrefs.setVoteSeatChosen(ctx, true)
            styleVoteSeatChips(num)
            updateVoteSwitchEnabled(true)
        }
        group.post { layoutVoteChips(voteSeatChips, group, VOTE_SEAT_COLUMNS) }
        if (chosen && selected != null) {
            syncVoteSeatChips(selected)
        } else {
            clearVoteSeatSelection()
        }
    }

    private fun refreshVoteUi() {
        if (_binding == null) return
        val ctx = requireContext()
        val chosen = SpyPrefs.isVoteSeatChosen(ctx)
        var autoVote = SpyPrefs.isAutoVoteEnabled(ctx)
        if (autoVote && !chosen) {
            SpyPrefs.setAutoVoteEnabled(ctx, false)
            autoVote = false
        }
        updateVoteSwitchEnabled(chosen)
        suppressVoteSwitchFeedback = true
        try {
            binding.switchAutoVote.isChecked = autoVote
        } finally {
            suppressVoteSwitchFeedback = false
        }
        if (chosen) {
            syncVoteSeatChips(SpyPrefs.voteTargetSeat(ctx))
        } else {
            clearVoteSeatSelection()
        }
        syncVoteTapAtChips(SpyPrefs.voteTapAtSeconds(ctx))
        updateVoteControlsEnabled(!autoVote)
    }

    private fun setupVoteTapAtChips() {
        val ctx = requireContext()
        val group = binding.voteTapAtGroup
        voteTapAtChips.clear()
        group.removeAllViews()
        val selected = SpyPrefs.voteTapAtSeconds(ctx)
        for (sec in SpyPrefs.VOTE_TAP_AT_MIN..SpyPrefs.VOTE_TAP_AT_MAX) {
            val chip = createVoteChip(sec.toString(), textSizeSp = 13f)
            group.addView(chip)
            voteTapAtChips += chip
        }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressVoteTapAtFeedback) return@setOnCheckedStateChangeListener
            if (SpyPrefs.isAutoVoteEnabled(ctx)) {
                suppressVoteTapAtFeedback = true
                try {
                    syncVoteTapAtChips(SpyPrefs.voteTapAtSeconds(ctx))
                } finally {
                    suppressVoteTapAtFeedback = false
                }
                return@setOnCheckedStateChangeListener
            }
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(checkedId) ?: return@setOnCheckedStateChangeListener
            val sec = chip.text.toString().toIntOrNull() ?: return@setOnCheckedStateChangeListener
            SpyPrefs.setVoteTapAtSeconds(ctx, sec)
            styleVoteTapAtChips(sec)
        }
        group.post { layoutVoteChips(voteTapAtChips, group, VOTE_TAP_AT_COLUMNS) }
        syncVoteTapAtChips(selected)
    }

    private fun createVoteChip(label: String, textSizeSp: Float): Chip {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        return Chip(ctx).apply {
            id = View.generateViewId()
            text = label
            isCheckable = true
            isCheckedIconVisible = false
            chipBackgroundColor = ContextCompat.getColorStateList(ctx, R.color.chip_vote_bg)
            chipStrokeColor = ContextCompat.getColorStateList(ctx, R.color.chip_vote_stroke)
            setTextColor(ContextCompat.getColorStateList(ctx, R.color.chip_vote_text))
            textSize = textSizeSp
            minHeight = 0
            chipMinHeight = resources.getDimension(R.dimen.vote_seat_chip_height)
            chipCornerRadius = 12f * density
            chipStrokeWidth = 1.5f * density
            isClickable = true
            setEnsureMinTouchTargetSize(false)
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
    }

    private fun layoutVoteChips(chips: List<Chip>, group: ChipGroup, columns: Int) {
        if (columns <= 0 || chips.isEmpty()) return
        val spacing = resources.getDimensionPixelSize(R.dimen.vote_chip_spacing)
        val groupWidth = group.width
        if (groupWidth <= 0) return
        val chipWidth = (groupWidth - spacing * (columns - 1)) / columns
        chips.forEach { chip ->
            chip.layoutParams = ChipGroup.LayoutParams(chipWidth, ChipGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun updateVoteControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.5f
        (voteSeatChips + voteTapAtChips).forEach { chip ->
            chip.isClickable = enabled
            chip.isEnabled = enabled
            chip.alpha = alpha
        }
    }

    private fun clearVoteSeatSelection() {
        suppressVoteSeatFeedback = true
        try {
            binding.voteSeatGroup.clearCheck()
        } finally {
            suppressVoteSeatFeedback = false
        }
        styleVoteSeatChips(-1)
    }

    private fun updateVoteSwitchEnabled(enabled: Boolean) {
        binding.switchAutoVote.isEnabled = enabled
    }

    private fun syncVoteSeatChips(selected: Int) {
        val group = binding.voteSeatGroup
        val chip = voteSeatChips.firstOrNull { it.text.toString().toIntOrNull() == selected }
        suppressVoteSeatFeedback = true
        try {
            if (chip != null) {
                group.check(chip.id)
            }
        } finally {
            suppressVoteSeatFeedback = false
        }
        styleVoteSeatChips(selected)
    }

    private fun syncVoteTapAtChips(selected: Int) {
        val group = binding.voteTapAtGroup
        val chip = voteTapAtChips.firstOrNull { it.text.toString().toIntOrNull() == selected }
        suppressVoteTapAtFeedback = true
        try {
            if (chip != null) {
                group.check(chip.id)
            }
        } finally {
            suppressVoteTapAtFeedback = false
        }
        styleVoteTapAtChips(selected)
    }

    private fun styleVoteSeatChips(selected: Int) {
        styleVoteChips(voteSeatChips, selected, requirePositive = true)
    }

    private fun styleVoteTapAtChips(selected: Int) {
        styleVoteChips(voteTapAtChips, selected, requirePositive = false)
    }

    private fun styleVoteChips(chips: List<Chip>, selected: Int, requirePositive: Boolean) {
        val density = resources.displayMetrics.density
        chips.forEach { chip ->
            val num = chip.text.toString().toIntOrNull()
            val active = num == selected && (!requirePositive || selected > 0)
            chip.chipStrokeWidth = if (active) 0f else density * 1.5f
            chip.typeface = if (active) {
                android.graphics.Typeface.DEFAULT_BOLD
            } else {
                android.graphics.Typeface.DEFAULT
            }
            chip.elevation = if (active) density * 3f else density * 0.5f
            chip.refreshDrawableState()
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
        private const val VOTE_SEAT_COLUMNS = 4
        private const val VOTE_TAP_AT_COLUMNS = 6

        fun newInstance() = HomeFragment()
    }
}