package com.phaohn.spyhelper

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

object WordLookupUi {

    fun bindMyWord(view: TextView, text: CharSequence, context: Context) {
        view.text = text
        val placeholder = context.getString(R.string.word_placeholder)
        if (text == placeholder) {
            view.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            view.setTypeface(null, Typeface.NORMAL)
        } else {
            view.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            if (text !is Spanned) {
                view.setTypeface(null, Typeface.BOLD)
            }
        }
    }

    fun bindSearchWords(scroll: ScrollView?, view: TextView, text: CharSequence, context: Context) {
        view.text = text
        scroll?.scrollTo(0, 0)
        val placeholder = context.getString(R.string.word_placeholder)
        val searching = context.getString(R.string.word_searching)
        val notInList = context.getString(R.string.word_not_in_list)
        view.setTextColor(
            ContextCompat.getColor(
                context,
                when (text) {
                    placeholder, searching, notInList -> R.color.text_secondary
                    else -> R.color.text_primary
                },
            ),
        )
    }
}