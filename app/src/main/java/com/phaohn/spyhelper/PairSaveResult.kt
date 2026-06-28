package com.phaohn.spyhelper

data class PairSaveResult(
    val added: Int = 0,
    val duplicate: Int = 0,
    val empty: Int = 0,
    val sameWord: Int = 0,
    val invalidFormat: Int = 0,
) {
    val hasSuccess: Boolean get() = added > 0

    fun plus(status: InsertStatus): PairSaveResult = when (status) {
        InsertStatus.SUCCESS -> copy(added = added + 1)
        InsertStatus.DUPLICATE -> copy(duplicate = duplicate + 1)
        InsertStatus.EMPTY -> copy(empty = empty + 1)
        InsertStatus.SAME_WORD -> copy(sameWord = sameWord + 1)
    }

    fun withInvalid(count: Int): PairSaveResult =
        if (count == 0) this else copy(invalidFormat = invalidFormat + count)
}

enum class InsertStatus {
    SUCCESS,
    DUPLICATE,
    EMPTY,
    SAME_WORD,
}

data class PairParseResult(
    val pairs: List<Pair<String, String>>,
    val invalidLines: Int,
)

fun PairSaveResult.formatMessage(context: android.content.Context): String {
    val lines = buildList {
        if (added > 0) add(context.getString(R.string.save_stat_added, added))
        if (duplicate > 0) add(context.getString(R.string.save_stat_duplicate, duplicate))
        if (empty > 0) add(context.getString(R.string.save_stat_empty, empty))
        if (sameWord > 0) add(context.getString(R.string.save_stat_same, sameWord))
        if (invalidFormat > 0) add(context.getString(R.string.save_stat_invalid, invalidFormat))
    }
    return lines.ifEmpty { listOf(context.getString(R.string.save_stat_none)) }.joinToString("\n")
}