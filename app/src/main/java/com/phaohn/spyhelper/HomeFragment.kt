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

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshDashboard() }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshDashboard() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        repository = PhaoHNApp.repo(requireActivity().application)

        binding.rowAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        binding.switchAutoReady.isChecked = SpyPrefs.isAutoReadyEnabled(requireContext())
        binding.switchAutoReady.setOnCheckedChangeListener { _, checked ->
            SpyPrefs.setAutoReadyEnabled(requireContext(), checked)
        }
        binding.switchAutoSit.isChecked = SpyPrefs.isAutoSitEnabled(requireContext())
        binding.switchAutoSit.setOnCheckedChangeListener { _, checked ->
            SpyPrefs.setAutoSitEnabled(requireContext(), checked)
        }
        binding.switchAutoVote.isChecked = SpyPrefs.isAutoVoteEnabled(requireContext())
        binding.switchAutoVote.setOnCheckedChangeListener { _, checked ->
            SpyPrefs.setAutoVoteEnabled(requireContext(), checked)
            updateVoteSeatVisibility(checked)
        }
        setupVoteSeatChips()
        updateVoteSeatVisibility(binding.switchAutoVote.isChecked)
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
        val autoVote = SpyPrefs.isAutoVoteEnabled(ctx)
        if (binding.switchAutoVote.isChecked != autoVote) {
            binding.switchAutoVote.isChecked = autoVote
        }
        updateVoteSeatVisibility(autoVote)
        syncVoteSeatChips(SpyPrefs.voteTargetSeat(ctx))
        val perm = PermissionHelper.check(ctx)
        val on = getString(R.string.status_on)
        val off = getString(R.string.status_off)

        setPermRow(binding.dotAccessibility, binding.statusAccessibility, perm.accessibility, on, off)
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

    fun setLookup(myWord: String, myRole: WordRole?, others: List<LabeledWord>) {
        if (_binding == null) return
        if (myWord.isEmpty()) {
            binding.lookupMyWord.text = getString(R.string.lookup_empty)
            binding.lookupOthers.text = ""
            return
        }
        val ctx = requireContext()
        if (myRole != null) {
            binding.lookupMyWord.text = android.text.SpannableStringBuilder().apply {
                append(getString(R.string.my_word))
                append(": ")
                val start = length
                append(myWord)
                setSpan(
                    android.text.style.ForegroundColorSpan(RoleTextFormatter.colorForRole(ctx, myRole)),
                    start,
                    length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            binding.lookupOthers.text = RoleTextFormatter.formatOthersApp(ctx, others)
        } else {
            binding.lookupMyWord.text = getString(R.string.lookup_my_word, myWord)
            binding.lookupOthers.text = getString(R.string.not_in_db)
        }
    }

    private fun setupVoteSeatChips() {
        val ctx = requireContext()
        val group = binding.voteSeatGroup
        voteSeatChips.clear()
        group.removeAllViews()
        val chipBg = ContextCompat.getColorStateList(ctx, R.color.chip_vote_bg)
        val chipStroke = ContextCompat.getColorStateList(ctx, R.color.chip_vote_stroke)
        val chipText = ContextCompat.getColorStateList(ctx, R.color.chip_vote_text)
        val selected = SpyPrefs.voteTargetSeat(ctx)
        for (seat in 1..WePlayIds.SEAT_COUNT) {
            val chip = Chip(ctx).apply {
                id = View.generateViewId()
                text = seat.toString()
                isCheckable = true
                isCheckedIconVisible = false
                chipBackgroundColor = chipBg
                chipStrokeColor = chipStroke
                setTextColor(chipText)
                textSize = 13f
                minHeight = 0
                chipMinHeight = resources.getDimension(R.dimen.vote_seat_chip_height)
                isClickable = true
                setEnsureMinTouchTargetSize(false)
            }
            group.addView(chip)
            voteSeatChips += chip
        }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(checkedId) ?: return@setOnCheckedStateChangeListener
            val num = chip.text.toString().toIntOrNull() ?: return@setOnCheckedStateChangeListener
            SpyPrefs.setVoteTargetSeat(ctx, num)
            styleVoteSeatChips(num)
        }
        group.post { layoutVoteSeatChips() }
        syncVoteSeatChips(selected)
    }

    private fun layoutVoteSeatChips() {
        val group = binding.voteSeatGroup
        val density = resources.displayMetrics.density
        val spacing = (4 * density).toInt()
        val groupWidth = group.width
        if (groupWidth <= 0) return
        val chipWidth = (groupWidth - spacing * 3) / 4
        voteSeatChips.forEach { chip ->
            chip.layoutParams = ChipGroup.LayoutParams(chipWidth, ChipGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun syncVoteSeatChips(selected: Int) {
        val group = binding.voteSeatGroup
        val chip = voteSeatChips.firstOrNull { it.text.toString().toIntOrNull() == selected }
        if (chip != null) {
            group.check(chip.id)
        }
        styleVoteSeatChips(selected)
    }

    private fun updateVoteSeatVisibility(visible: Boolean) {
        binding.voteSeatGroup.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun styleVoteSeatChips(selected: Int) {
        val density = resources.displayMetrics.density
        voteSeatChips.forEach { chip ->
            val num = chip.text.toString().toIntOrNull()
            val active = num == selected
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