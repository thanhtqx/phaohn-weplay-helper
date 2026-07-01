package com.phaohn.spyhelper

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class OverlayAutoBinder(
    private val menuRoot: View,
    private val context: Context,
    private val onLayoutChanged: () -> Unit,
) {
    private val readyToggle: TextView = menuRoot.findViewById(R.id.overlayAutoReady)
    private val sitToggle: TextView = menuRoot.findViewById(R.id.overlayAutoSit)
    private val lockToggle: TextView = menuRoot.findViewById(R.id.overlayVoteLock)
    private val seatRow1: LinearLayout = menuRoot.findViewById(R.id.overlaySeatRow1)
    private val seatRow2: LinearLayout = menuRoot.findViewById(R.id.overlaySeatRow2)
    private val secRow1: LinearLayout = menuRoot.findViewById(R.id.overlaySecRow1)
    private val secRow2: LinearLayout = menuRoot.findViewById(R.id.overlaySecRow2)
    private val seatChips = mutableListOf<TextView>()
    private val secChips = mutableListOf<TextView>()
    private var suppressSeat = false
    private var suppressSec = false

    fun bind() {
        if (seatChips.isEmpty()) {
            buildSeatChips()
            buildSecChips()
        }
        wireOverlayToggle(readyToggle) { checked ->
            SpyPrefs.setAutoReadyEnabled(context, checked)
            AutoPrefsNotifier.notifyChanged(context)
        }
        wireOverlayToggle(sitToggle) { checked ->
            SpyPrefs.setAutoSitEnabled(context, checked)
            AutoPrefsNotifier.notifyChanged(context)
        }
        lockToggle.setOnClickListener {
            AutoToggleUi.pulse(lockToggle)
            onVoteLockChanged(!SpyPrefs.isVoteSettingsLocked(context))
        }
        refreshFromPrefs()
    }

    fun refreshFromPrefs() {
        styleOverlayToggle(readyToggle, SpyPrefs.isAutoReadyEnabled(context))
        styleOverlayToggle(sitToggle, SpyPrefs.isAutoSitEnabled(context))
        val locked = SpyPrefs.isVoteSettingsLocked(context)
        if (locked != SpyPrefs.isAutoVoteEnabled(context)) {
            SpyPrefs.setAutoVoteEnabled(context, locked)
        }
        styleLockButton(locked)
        styleSeatChips(SpyPrefs.voteTargetSeat(context))
        styleSecChips(SpyPrefs.voteTapAtSeconds(context))
        applyVoteSettingsLock(locked)
    }

    private fun onVoteLockChanged(locked: Boolean) {
        if (locked) {
            SpyPrefs.ensureDefaultVoteSeat(context)
            SpyPrefs.setVoteSeatChosen(context, true)
        }
        SpyPrefs.setVoteLockWithAutoVote(context, locked)
        styleLockButton(locked)
        applyVoteSettingsLock(locked)
        AutoPrefsNotifier.notifyChanged(context)
        onLayoutChanged()
    }

    private fun applyVoteSettingsLock(locked: Boolean) {
        val pickerAlpha = if (locked) AutoToggleUi.LOCKED_PICKER_ALPHA else 1f
        seatRow1.alpha = pickerAlpha
        seatRow2.alpha = pickerAlpha
        secRow1.alpha = pickerAlpha
        secRow2.alpha = pickerAlpha
        seatChips.forEach { chip ->
            chip.isEnabled = !locked
            chip.isClickable = !locked
            chip.alpha = pickerAlpha
        }
        secChips.forEach { chip ->
            chip.isEnabled = !locked
            chip.isClickable = !locked
            chip.alpha = pickerAlpha
        }
    }

    private fun styleLockButton(locked: Boolean) {
        lockToggle.isSelected = locked
        lockToggle.text = context.getString(
            if (locked) R.string.auto_vote_lock_action_unlock else R.string.auto_vote_lock_action_lock,
        )
        lockToggle.setTextColor(
            ContextCompat.getColor(
                context,
                if (locked) R.color.overlay_text_primary else R.color.overlay_text_muted,
            ),
        )
        lockToggle.refreshDrawableState()
    }

    private fun wireOverlayToggle(view: TextView, onChanged: (Boolean) -> Unit) {
        view.setOnClickListener {
            if (!view.isEnabled) return@setOnClickListener
            AutoToggleUi.pulse(view)
            val next = !view.isSelected
            styleOverlayToggle(view, next)
            onChanged(next)
            onLayoutChanged()
        }
    }

    private fun styleOverlayToggle(view: TextView, on: Boolean) {
        view.isSelected = on
        view.setTextColor(
            ContextCompat.getColor(
                context,
                if (on) R.color.overlay_text_primary else R.color.overlay_text_muted,
            ),
        )
        view.refreshDrawableState()
    }

    private fun buildSeatChips() {
        seatChips.clear()
        seatRow1.removeAllViews()
        seatRow2.removeAllViews()
        for (seat in 1..WePlayIds.SEAT_COUNT) {
            val chip = createChip(seat.toString())
            seatChips += chip
            if (seat <= 4) seatRow1.addView(chip) else seatRow2.addView(chip)
            chip.setOnClickListener {
                if (suppressSeat || SpyPrefs.isVoteSettingsLocked(context)) return@setOnClickListener
                AutoToggleUi.pulse(chip)
                SpyPrefs.setVoteTargetSeat(context, seat)
                SpyPrefs.setVoteSeatChosen(context, true)
                styleSeatChips(seat)
                AutoPrefsNotifier.notifyChanged(context)
                onLayoutChanged()
            }
        }
    }

    private fun buildSecChips() {
        secChips.clear()
        secRow1.removeAllViews()
        secRow2.removeAllViews()
        for (sec in SpyPrefs.VOTE_TAP_AT_MIN..SpyPrefs.VOTE_TAP_AT_MAX) {
            val chip = createChip(sec.toString())
            secChips += chip
            if (sec <= 5) secRow1.addView(chip) else secRow2.addView(chip)
            chip.setOnClickListener {
                if (suppressSec || SpyPrefs.isVoteSettingsLocked(context)) return@setOnClickListener
                AutoToggleUi.pulse(chip)
                SpyPrefs.setVoteTapAtSeconds(context, sec)
                styleSecChips(sec)
                AutoPrefsNotifier.notifyChanged(context)
                onLayoutChanged()
            }
        }
    }

    private fun createChip(label: String): TextView {
        val density = context.resources.displayMetrics.density
        val padH = (density * 2f).toInt().coerceAtLeast(2)
        val padV = (density * 4f).toInt().coerceAtLeast(4)
        val minH = context.resources.getDimensionPixelSize(R.dimen.overlay_chip_min_h)
        val margin = (density * 2.5f).toInt()
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = margin
        }
        return TextView(context).apply {
            text = label
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            minHeight = minH
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = false
            background = ContextCompat.getDrawable(context, R.drawable.bg_overlay_chip_selector)
            setTextColor(ContextCompat.getColor(context, R.color.overlay_text_primary))
            layoutParams = lp
        }
    }

    private fun styleSeatChips(selected: Int) {
        suppressSeat = true
        try {
            seatChips.forEach { chip ->
                val num = chip.text.toString().toIntOrNull() ?: return@forEach
                chip.isSelected = num == selected
            }
        } finally {
            suppressSeat = false
        }
    }

    private fun styleSecChips(selected: Int) {
        suppressSec = true
        try {
            secChips.forEach { chip ->
                val sec = chip.text.toString().toIntOrNull() ?: return@forEach
                chip.isSelected = sec == selected
            }
        } finally {
            suppressSec = false
        }
    }
}