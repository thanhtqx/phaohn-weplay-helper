package com.phaohn.spyhelper

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Nút Bật/Tắt thay MaterialSwitch — bấm mượt, không lag. */
object AutoToggleUi {
    const val LOCKED_PICKER_ALPHA = 0.72f
    const val DISABLED_CONTROL_ALPHA = 0.58f

    fun pulse(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.93f)
            .scaleY(0.93f)
            .setDuration(65L)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(110L)
                    .start()
            }
            .start()
    }

    fun wire(
        view: TextView,
        onChanged: (Boolean) -> Unit,
        canToggle: () -> Boolean = { view.isEnabled },
    ) {
        view.isClickable = true
        view.isFocusable = false
        view.setOnClickListener {
            if (!canToggle()) return@setOnClickListener
            pulse(view)
            val next = !view.isSelected
            applyAppPill(view, next, enabled = true)
            onChanged(next)
        }
    }

    fun applyAppPill(view: TextView, on: Boolean, enabled: Boolean = view.isEnabled) {
        view.isEnabled = enabled
        view.isSelected = on && enabled
        view.alpha = if (enabled) 1f else DISABLED_CONTROL_ALPHA
        view.text = view.context.getString(
            if (on && enabled) R.string.toggle_on else R.string.toggle_off,
        )
        view.refreshDrawableState()
    }

    fun wireLabelToggle(view: TextView, onChanged: (Boolean) -> Unit) {
        view.isClickable = true
        view.isFocusable = false
        view.setOnClickListener {
            if (!view.isEnabled) return@setOnClickListener
            pulse(view)
            val next = !view.isSelected
            applyAppLabel(view, next)
            onChanged(next)
        }
    }

    fun applyAppLabel(view: TextView, on: Boolean) {
        view.isEnabled = true
        view.isSelected = on
        view.alpha = 1f
        view.refreshDrawableState()
    }

    fun applyVoteLockLabel(view: TextView, locked: Boolean) {
        view.isEnabled = true
        view.isClickable = true
        view.isSelected = locked
        view.alpha = 1f
        view.text = view.context.getString(
            if (locked) R.string.auto_vote_lock_action_unlock else R.string.auto_vote_lock_action_lock,
        )
        view.refreshDrawableState()
    }

    fun applyOverlayPill(view: TextView, on: Boolean, enabled: Boolean = view.isEnabled) {
        view.isEnabled = enabled
        view.isClickable = enabled
        view.isSelected = on && enabled
        view.alpha = if (enabled) 1f else DISABLED_CONTROL_ALPHA
        view.text = view.context.getString(
            if (on && enabled) R.string.toggle_on else R.string.toggle_off,
        )
        view.setTextColor(
            ContextCompat.getColor(
                view.context,
                if (on && enabled) R.color.overlay_text_primary else R.color.overlay_text_muted,
            ),
        )
        view.refreshDrawableState()
    }
}