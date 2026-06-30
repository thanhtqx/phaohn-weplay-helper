package com.phaohn.spyhelper

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible

class OverlayAutoBinder(
    private val menuRoot: View,
    private val context: Context,
    private val onLayoutChanged: () -> Unit,
) {
    private val readyToggle: TextView = menuRoot.findViewById(R.id.overlayAutoReady)
    private val sitToggle: TextView = menuRoot.findViewById(R.id.overlayAutoSit)
    private val voteToggle: TextView = menuRoot.findViewById(R.id.overlayAutoVote)
    private val votePanel: View = menuRoot.findViewById(R.id.overlayVotePanel)
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
        wireToggle(readyToggle) { checked ->
            SpyPrefs.setAutoReadyEnabled(context, checked)
            AutoPrefsNotifier.notifyChanged(context)
        }
        wireToggle(sitToggle) { checked ->
            SpyPrefs.setAutoSitEnabled(context, checked)
            AutoPrefsNotifier.notifyChanged(context)
        }
        wireToggle(voteToggle) { checked ->
            if (checked && !SpyPrefs.isVoteSeatChosen(context)) {
                val seat = SpyPrefs.voteTargetSeat(context)
                SpyPrefs.setVoteTargetSeat(context, seat)
                SpyPrefs.setVoteSeatChosen(context, true)
                styleSeatChips(seat)
            }
            SpyPrefs.setAutoVoteEnabled(context, checked)
            refreshVotePanel(forceLayout = true)
            AutoPrefsNotifier.notifyChanged(context)
        }
        refreshFromPrefs()
    }

    fun refreshFromPrefs() {
        styleToggle(readyToggle, SpyPrefs.isAutoReadyEnabled(context))
        styleToggle(sitToggle, SpyPrefs.isAutoSitEnabled(context))
        val chosen = SpyPrefs.isVoteSeatChosen(context)
        var voteOn = SpyPrefs.isAutoVoteEnabled(context)
        if (voteOn && !chosen) {
            SpyPrefs.setAutoVoteEnabled(context, false)
            voteOn = false
        }
        styleToggle(voteToggle, voteOn)
        if (chosen) {
            styleSeatChips(SpyPrefs.voteTargetSeat(context))
        } else {
            seatChips.forEach { it.isSelected = false }
        }
        styleSecChips(SpyPrefs.voteTapAtSeconds(context))
        refreshVotePanel()
    }

    private fun wireToggle(view: TextView, onChanged: (Boolean) -> Unit) {
        view.setOnClickListener {
            val next = !view.isSelected
            styleToggle(view, next)
            onChanged(next)
            onLayoutChanged()
        }
    }

    private fun styleToggle(view: TextView, on: Boolean) {
        view.isSelected = on
        view.setTextColor(
            ContextCompat.getColor(
                context,
                if (on) R.color.overlay_text_primary else R.color.overlay_text_muted,
            ),
        )
        view.refreshDrawableState()
    }

    private fun refreshVotePanel(forceLayout: Boolean = false) {
        val show = SpyPrefs.isAutoVoteEnabled(context)
        if (votePanel.isVisible != show || forceLayout) {
            votePanel.isVisible = show
            onLayoutChanged()
        }
        (seatChips + secChips).forEach { chip ->
            chip.isEnabled = true
            chip.isClickable = true
            chip.alpha = 1f
        }
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
                if (suppressSeat) return@setOnClickListener
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
                if (suppressSec) return@setOnClickListener
                SpyPrefs.setVoteTapAtSeconds(context, sec)
                styleSecChips(sec)
                AutoPrefsNotifier.notifyChanged(context)
                onLayoutChanged()
            }
        }
    }

    private fun createChip(label: String): TextView {
        val density = context.resources.displayMetrics.density
        val padH = (density * 4f).toInt()
        val padV = (density * 7f).toInt()
        val minH = (density * 32f).toInt()
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = (density * 3f).toInt()
        }
        return TextView(context).apply {
            text = label
            textSize = 11f
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