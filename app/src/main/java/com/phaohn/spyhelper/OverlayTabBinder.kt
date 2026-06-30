package com.phaohn.spyhelper

import android.content.Context
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible

class OverlayTabBinder(
    private val menuRoot: View,
    private val context: Context,
    private val onLayoutChanged: () -> Unit,
) {
    private data class Tab(
        val button: TextView,
        val panel: ScrollView,
    )

    private val tabs = listOf(
        Tab(menuRoot.findViewById(R.id.overlayTabWords), menuRoot.findViewById(R.id.overlayWordsPanel)),
        Tab(menuRoot.findViewById(R.id.overlayTabAuto), menuRoot.findViewById(R.id.overlayAutoPanel)),
    )

    fun bind() {
        tabs[0].button.setOnClickListener { show(0) }
        tabs[1].button.setOnClickListener { show(1) }
        show(0)
    }

    fun showWords() = show(0)

    private fun show(index: Int) {
        tabs.forEachIndexed { i, tab ->
            val active = i == index
            tab.button.isSelected = active
            tab.button.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (active) R.color.overlay_text_primary else R.color.overlay_text_muted,
                ),
            )
            tab.panel.isVisible = active
        }
        onLayoutChanged()
    }
}