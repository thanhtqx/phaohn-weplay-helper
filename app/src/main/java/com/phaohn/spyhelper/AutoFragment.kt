package com.phaohn.spyhelper

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.phaohn.spyhelper.databinding.FragmentAutoBinding

class AutoFragment : Fragment() {

    private var _binding: FragmentAutoBinding? = null
    private val binding get() = _binding!!
    private val voteSeatChips = mutableListOf<Chip>()
    private val voteTapAtChips = mutableListOf<Chip>()
    private var suppressVoteSeatFeedback = false
    private var suppressVoteTapAtFeedback = false
    private var suppressVoteLockFeedback = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAutoBinding.inflate(inflater, container, false)

        AutoToggleUi.wireLabelToggle(binding.toggleAutoReady) { checked ->
            SpyPrefs.setAutoReadyEnabled(requireContext(), checked)
            AutoPrefsNotifier.notifyChanged(requireContext())
        }
        AutoToggleUi.wireLabelToggle(binding.toggleAutoSit) { checked ->
            SpyPrefs.setAutoSitEnabled(requireContext(), checked)
            AutoPrefsNotifier.notifyChanged(requireContext())
        }
        binding.toggleVoteLock.setOnClickListener {
            if (suppressVoteLockFeedback) return@setOnClickListener
            AutoToggleUi.pulse(binding.toggleVoteLock)
            onVoteLockChanged(!SpyPrefs.isVoteSettingsLocked(requireContext()))
        }

        binding.btnAutoA11y.setOnClickListener {
            AccessibilitySetupHelper.openAccessibilityServiceDetails(requireContext())
        }
        setupVoteSeatChips()
        setupVoteTapAtChips()
        refreshVoteUi()
        refreshA11yBanner()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        refreshAutoUi()
        refreshA11yBanner()
    }

    private fun refreshA11yBanner() {
        if (_binding == null) return
        binding.autoA11yBanner.isVisible = !SpyAccessibilityService.isEnabled(requireContext())
    }

    fun refreshAutoUi() {
        if (_binding == null) return
        val ctx = requireContext()
        AutoToggleUi.applyAppLabel(binding.toggleAutoReady, SpyPrefs.isAutoReadyEnabled(ctx))
        AutoToggleUi.applyAppLabel(binding.toggleAutoSit, SpyPrefs.isAutoSitEnabled(ctx))
        refreshVoteUi()
    }

    private fun onVoteLockChanged(locked: Boolean) {
        val ctx = requireContext()
        if (locked) {
            SpyPrefs.ensureDefaultVoteSeat(ctx)
            SpyPrefs.setVoteSeatChosen(ctx, true)
        }
        SpyPrefs.setVoteLockWithAutoVote(ctx, locked)
        applyVoteSettingsLock(locked)
        setLockToggleState(locked)
        updateVoteSettingsVisual(locked)
        AutoPrefsNotifier.notifyChanged(ctx)
    }

    private fun setLockToggleState(locked: Boolean) {
        suppressVoteLockFeedback = true
        try {
            AutoToggleUi.applyVoteLockLabel(binding.toggleVoteLock, locked)
        } finally {
            suppressVoteLockFeedback = false
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
            if (suppressVoteSeatFeedback || SpyPrefs.isVoteSettingsLocked(ctx)) return@setOnCheckedStateChangeListener
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(checkedId) ?: return@setOnCheckedStateChangeListener
            val num = chip.text.toString().toIntOrNull() ?: return@setOnCheckedStateChangeListener
            SpyPrefs.setVoteTargetSeat(ctx, num)
            SpyPrefs.setVoteSeatChosen(ctx, true)
            styleVoteSeatChips(num)
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
        val locked = SpyPrefs.isVoteSettingsLocked(ctx)
        if (locked != SpyPrefs.isAutoVoteEnabled(ctx)) {
            SpyPrefs.setAutoVoteEnabled(ctx, locked)
        }
        setLockToggleState(locked)
        syncVoteSeatChips(SpyPrefs.voteTargetSeat(ctx))
        syncVoteTapAtChips(SpyPrefs.voteTapAtSeconds(ctx))
        applyVoteSettingsLock(locked)
        updateVoteSettingsVisual(locked)
    }

    private fun applyVoteSettingsLock(locked: Boolean) {
        val pickerAlpha = if (locked) AutoToggleUi.LOCKED_PICKER_ALPHA else 1f
        binding.votePickerPanel.alpha = pickerAlpha
        voteSeatChips.forEach { chip ->
            chip.isEnabled = !locked
            chip.isClickable = !locked
            chip.alpha = pickerAlpha
        }
        voteTapAtChips.forEach { chip ->
            chip.isEnabled = !locked
            chip.isClickable = !locked
            chip.alpha = pickerAlpha
        }
    }

    private fun updateVoteSettingsVisual(voteLocked: Boolean) {
        binding.voteSettingsCard.alpha = if (voteLocked) 1f else 0.96f
        binding.toggleVoteLock.isSelected = voteLocked
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
            if (suppressVoteTapAtFeedback || SpyPrefs.isVoteSettingsLocked(ctx)) return@setOnCheckedStateChangeListener
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
            chipCornerRadius = 8f * density
            chipStrokeWidth = density
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

    private fun clearVoteSeatSelection() {
        suppressVoteSeatFeedback = true
        try {
            binding.voteSeatGroup.clearCheck()
        } finally {
            suppressVoteSeatFeedback = false
        }
        styleVoteSeatChips(-1)
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
            chip.chipStrokeWidth = if (active) density * 2.5f else density
            chip.typeface = if (active) {
                android.graphics.Typeface.DEFAULT_BOLD
            } else {
                android.graphics.Typeface.DEFAULT
            }
            chip.elevation = if (active) density * 2f else 0f
            chip.refreshDrawableState()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val VOTE_SEAT_COLUMNS = 4
        private const val VOTE_TAP_AT_COLUMNS = 6

        fun newInstance() = AutoFragment()
    }
}