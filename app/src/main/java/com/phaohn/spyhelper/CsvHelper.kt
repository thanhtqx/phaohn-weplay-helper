package com.phaohn.spyhelper

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvHelper {

    private const val BOM = "\uFEFF"

    fun exportPairs(pairs: List<WordPair>): String {
        val sb = StringBuilder(BOM)
        sb.appendLine("Dân thường,Gián điệp")
        pairs.forEach { pair ->
            sb.appendLine("${escape(pair.civilianWord)},${escape(pair.spyWord)}")
        }
        return sb.toString()
    }

    fun exportHistory(history: List<LookupHistory>): String {
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val sb = StringBuilder(BOM)
        sb.appendLine("Thời gian,Từ của bạn,Từ còn lại")
        history.forEach { entry ->
            val others = entry.otherWords
                .split("|")
                .filter { it.isNotBlank() }
                .joinToString("; ")
            sb.appendLine(
                "${escape(fmt.format(Date(entry.playedAt)))},${escape(entry.myWord)},${escape(others)}"
            )
        }
        return sb.toString()
    }

    fun parsePairs(text: String): List<Pair<String, String>> {
        val lines = text
            .removePrefix(BOM)
            .lines()
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()

        val startIndex = if (looksLikeHeader(lines.first())) 1 else 0
        val rows = mutableListOf<Pair<String, String>>()
        for (i in startIndex until lines.size) {
            val cols = parseLine(lines[i])
            when {
                cols.size >= 3 -> rows += cols[1] to cols[2]
                cols.size == 2 -> rows += cols[0] to cols[1]
            }
        }
        return rows.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
    }

    private fun looksLikeHeader(line: String): Boolean {
        val lower = line.lowercase(Locale.getDefault())
        return lower.contains("dân") || lower.contains("gián") ||
            lower.contains("civilian") || lower.contains("spy") ||
            lower.startsWith("stt")
    }

    private fun parseLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result += sb.toString()
                    sb.clear()
                }
                else -> sb.append(ch)
            }
            i++
        }
        result += sb.toString()
        return result
    }

    private fun escape(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}