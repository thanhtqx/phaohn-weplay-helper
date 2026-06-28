package com.phaohn.spyhelper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class WordRepository(
    private val dao: WordDao,
    private val historyDao: LookupHistoryDao,
) {

    private data class CachedLookup(
        val myRole: WordRole,
        val others: List<LabeledWord>,
    )

    private val lookupCache = ConcurrentHashMap<String, CachedLookup>()

    suspend fun warmCache() = withContext(Dispatchers.IO) {
        lookupCache.clear()
        dao.getAll().forEach { cachePair(it) }
    }

    suspend fun pairCount(): Int = withContext(Dispatchers.IO) { dao.countAll() }

    suspend fun historyCount(): Int = withContext(Dispatchers.IO) { historyDao.count() }

    suspend fun savePair(civilian: String, spy: String): Boolean = withContext(Dispatchers.IO) {
        tryInsertPair(civilian, spy) == InsertStatus.SUCCESS
    }

    suspend fun addManual(civilian: String, spy: String): PairSaveResult = withContext(Dispatchers.IO) {
        PairSaveResult().plus(tryInsertPair(civilian, spy))
    }

    suspend fun updatePair(id: Long, civilian: String, spy: String): Boolean = withContext(Dispatchers.IO) {
        if (civilian.isEmpty() || spy.isEmpty() || civilian == spy) return@withContext false
        if (hasExactDuplicatePair(civilian, spy, excludeId = id)) return@withContext false
        dao.update(id, civilian, spy)
        warmCache()
        true
    }

    /** Ghi DB: trùng chỉ khi cả hai từ khớp tuyệt đối — không khác một ký tự nào. */
    private suspend fun tryInsertPair(civilian: String, spy: String): InsertStatus {
        if (civilian.isEmpty() || spy.isEmpty()) return InsertStatus.EMPTY
        if (civilian == spy) return InsertStatus.SAME_WORD
        if (hasExactDuplicatePair(civilian, spy)) return InsertStatus.DUPLICATE
        val pair = WordPair(civilianWord = civilian, spyWord = spy)
        if (dao.insert(pair) == -1L) return InsertStatus.DUPLICATE
        cachePair(pair)
        return InsertStatus.SUCCESS
    }

    suspend fun deletePair(id: Long): Boolean = withContext(Dispatchers.IO) {
        dao.deleteById(id)
        warmCache()
        true
    }

    suspend fun lookupOthers(myWord: String): LookupResult = withContext(Dispatchers.IO) {
        if (myWord.isEmpty()) return@withContext LookupResult.NotInGame
        lookupCache[myWord]?.let { cached ->
            return@withContext LookupResult.Found(myWord, cached.myRole, cached.others)
        }
        val pairs = findPairsByExactWord(myWord)
        val others = collectLabeledOthers(myWord, pairs)
        if (others.isEmpty()) return@withContext LookupResult.NotFound(myWord)
        val myRole = resolveMyRole(myWord, pairs)
        lookupCache[myWord] = CachedLookup(myRole, others)
        return@withContext LookupResult.Found(myWord, myRole, others)
    }

    suspend fun allPairs(): List<WordPair> = withContext(Dispatchers.IO) { dao.getAll() }

    suspend fun searchPairs(query: String): List<WordPair> = withContext(Dispatchers.IO) {
        val all = dao.getAll()
        if (query.isEmpty()) return@withContext all
        all.filter { pair ->
            pair.civilianWord.contains(query) || pair.spyWord.contains(query)
        }
    }

    suspend fun recordLookup(myWord: String, otherWords: List<String>) = withContext(Dispatchers.IO) {
        historyDao.insert(
            LookupHistory(
                myWord = myWord,
                otherWords = otherWords.joinToString("|"),
            )
        )
    }

    suspend fun recentHistory(limit: Int = 100): List<LookupHistory> =
        withContext(Dispatchers.IO) { historyDao.recent(limit) }

    suspend fun topLookupWords(limit: Int = 16): List<LookupWordCount> =
        withContext(Dispatchers.IO) { historyDao.topWords(limit) }

    suspend fun deleteHistoryItem(id: Long) = withContext(Dispatchers.IO) {
        historyDao.deleteById(id)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) { historyDao.clearAll() }

    suspend fun importPairs(rows: List<Pair<String, String>>): PairSaveResult = withContext(Dispatchers.IO) {
        var result = PairSaveResult()
        for ((civilian, spy) in rows) {
            result = result.plus(tryInsertPair(civilian, spy))
        }
        result
    }

    private suspend fun hasExactDuplicatePair(
        civilian: String,
        spy: String,
        excludeId: Long? = null,
    ): Boolean {
        return dao.getAll().any { pair ->
            (excludeId == null || pair.id != excludeId) &&
                WordMatcher.isDuplicatePair(pair.civilianWord, pair.spyWord, civilian, spy)
        }
    }

    /** So khớp tuyệt đối trong Kotlin — không phụ thuộc SQLite (tránh lỗi hoa/thường). */
    private suspend fun findPairsByExactWord(word: String): List<WordPair> {
        return dao.getAll().filter { pair ->
            WordMatcher.matches(pair.civilianWord, word) || WordMatcher.matches(pair.spyWord, word)
        }
    }

    private fun cachePair(pair: WordPair) {
        lookupCache.remove(pair.civilianWord)
        lookupCache.remove(pair.spyWord)
    }

    private fun resolveMyRole(word: String, pairs: List<WordPair>): WordRole {
        val asCivilian = pairs.any { WordMatcher.matches(it.civilianWord, word) }
        val asSpy = pairs.any { WordMatcher.matches(it.spyWord, word) }
        return when {
            asCivilian && !asSpy -> WordRole.CIVILIAN
            asSpy -> WordRole.SPY
            else -> WordRole.CIVILIAN
        }
    }

    private fun collectLabeledOthers(word: String, pairs: List<WordPair>): List<LabeledWord> {
        val others = linkedSetOf<LabeledWord>()
        for (pair in pairs) {
            when {
                WordMatcher.matches(pair.civilianWord, word) ->
                    others.add(LabeledWord(pair.spyWord, WordRole.SPY))
                WordMatcher.matches(pair.spyWord, word) ->
                    others.add(LabeledWord(pair.civilianWord, WordRole.CIVILIAN))
            }
        }
        return others.toList()
    }
}

sealed class LookupResult {
    data object NotInGame : LookupResult()
    data class NotFound(val myWord: String) : LookupResult()
    data class Found(
        val myWord: String,
        val myRole: WordRole,
        val otherWords: List<LabeledWord>,
    ) : LookupResult() {
        val otherWordStrings: List<String> get() = otherWords.map { it.word }
    }
}