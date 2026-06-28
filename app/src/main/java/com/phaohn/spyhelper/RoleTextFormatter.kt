package com.phaohn.spyhelper

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat

object RoleTextFormatter {

    fun colorForRole(context: Context, role: WordRole): Int = when (role) {
        WordRole.CIVILIAN -> ContextCompat.getColor(context, R.color.civilian)
        WordRole.SPY -> ContextCompat.getColor(context, R.color.spy)
    }

    fun bubbleColorForRole(role: WordRole): Int = when (role) {
        WordRole.CIVILIAN -> 0xFF90CAF9.toInt()
        WordRole.SPY -> 0xFFFFAB40.toInt()
    }

    fun coloredWord(context: Context, word: String, role: WordRole): CharSequence {
        return SpannableStringBuilder(word).apply {
            setSpan(
                ForegroundColorSpan(colorForRole(context, role)),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    fun coloredWordBubble(word: String, role: WordRole): CharSequence {
        return SpannableStringBuilder(word).apply {
            setSpan(
                ForegroundColorSpan(bubbleColorForRole(role)),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    fun formatOthersBubble(others: List<LabeledWord>): CharSequence {
        if (others.isEmpty()) return ""
        val sb = SpannableStringBuilder()
        others.forEachIndexed { index, entry ->
            if (index > 0) sb.append("\n")
            sb.append("• ")
            val wordStart = sb.length
            sb.append(entry.word)
            sb.setSpan(
                ForegroundColorSpan(bubbleColorForRole(entry.role)),
                wordStart,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return sb
    }

    fun formatOthersApp(context: Context, others: List<LabeledWord>): CharSequence {
        if (others.isEmpty()) return ""
        val sb = SpannableStringBuilder()
        others.forEachIndexed { index, entry ->
            if (index > 0) sb.append("\n")
            val bulletStart = sb.length
            sb.append("• ")
            sb.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, R.color.text_secondary)),
                bulletStart,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val wordStart = sb.length
            sb.append(entry.word)
            sb.setSpan(
                ForegroundColorSpan(colorForRole(context, entry.role)),
                wordStart,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return sb
    }

    fun encodeOthers(others: List<LabeledWord>): String =
        others.joinToString("\n") { "${it.word}|${it.role.name}" }

    fun decodeOthers(raw: String?): List<LabeledWord> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val sep = line.indexOf('|')
            if (sep <= 0) return@mapNotNull null
            val word = line.substring(0, sep)
            val role = WordRole.fromName(line.substring(sep + 1)) ?: return@mapNotNull null
            if (word.isEmpty()) null else LabeledWord(word, role)
        }
    }
}