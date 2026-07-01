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
    @Volatile
    private var pairsSnapshot: List<WordPair>? = null

    suspend fun warmCache() = withContext(Dispatchers.IO) {
        rebuildLookupCache()
    }

    suspend fun pairCount(): Int = withContext(Dispatchers.IO) {
        pairsSnapshot().size
    }

    suspend fun historyCount(): Int = withContext(Dispatchers.IO) { historyDao.count() }

    /** Từ tự bắt sau ván — duyệt ngay, dùng tra cứu. */
    suspend fun savePair(civilian: String, spy: String): Boolean = withContext(Dispatchers.IO) {
        tryInsertPair(
            civilian,
            spy,
            WordPair.STATUS_APPROVED,
            WordPair.SOURCE_CAPTURE,
        ) == InsertStatus.SUCCESS
    }

    /** Thêm thủ công — user thường chờ duyệt; admin duyệt ngay. */
    suspend fun addManual(civilian: String, spy: String, isAdmin: Boolean = false): PairSaveResult =
        withContext(Dispatchers.IO) {
            val approved = isAdmin
            val status = tryInsertPair(
                civilian,
                spy,
                if (approved) WordPair.STATUS_APPROVED else WordPair.STATUS_PENDING,
                WordPair.SOURCE_MANUAL,
            )
            PairSaveResult().plus(
                status,
                pendingApproval = !approved && status == InsertStatus.SUCCESS,
            )
        }

    suspend fun updatePair(id: Long, civilian: String, spy: String): Boolean = withContext(Dispatchers.IO) {
        if (civilian.isEmpty() || spy.isEmpty() || civilian == spy) return@withContext false
        if (hasExactDuplicatePair(civilian, spy, excludeId = id)) return@withContext false
        dao.update(id, civilian, spy)
        dao.markServerUnsynced(id)
        invalidatePairsSnapshot()
        rebuildLookupCache()
        true
    }

    private suspend fun tryInsertPair(
        civilian: String,
        spy: String,
        approvalStatus: String,
        pairSource: String,
    ): InsertStatus {
        if (civilian.isEmpty() || spy.isEmpty()) return InsertStatus.EMPTY
        if (civilian == spy) return InsertStatus.SAME_WORD
        val existing = findDuplicatePair(civilian, spy)
        if (existing != null) {
            if (approvalStatus == WordPair.STATUS_APPROVED && existing.isPendingApproval) {
                dao.updateApproval(existing.id, WordPair.STATUS_APPROVED, pairSource)
                invalidatePairsSnapshot()
                rebuildLookupCache()
                return InsertStatus.SUCCESS
            }
            return InsertStatus.DUPLICATE
        }
        val pair = WordPair(
            civilianWord = civilian,
            spyWord = spy,
            approvalStatus = approvalStatus,
            pairSource = pairSource,
            serverSynced = pairSource == WordPair.SOURCE_SYNC,
        )
        if (dao.insert(pair) == -1L) return InsertStatus.DUPLICATE
        invalidatePairsSnapshot()
        if (pair.isApproved) {
            cachePair(pair)
        }
        return InsertStatus.SUCCESS
    }

    suspend fun deletePair(id: Long): Boolean = withContext(Dispatchers.IO) {
        dao.deleteById(id)
        invalidatePairsSnapshot()
        rebuildLookupCache()
        true
    }

    suspend fun lookupOthers(myWord: String): LookupResult = withContext(Dispatchers.IO) {
        if (myWord.isEmpty()) return@withContext LookupResult.NotInGame
        lookupCache[myWord]?.let { cached ->
            return@withContext LookupResult.Found(myWord, cached.myRole, cached.others)
        }
        val pairs = findApprovedPairsByExactWord(myWord)
        val others = collectLabeledOthers(myWord, pairs)
        if (others.isEmpty()) return@withContext LookupResult.NotFound(myWord)
        val myRole = resolveMyRole(myWord, pairs)
        lookupCache[myWord] = CachedLookup(myRole, others)
        return@withContext LookupResult.Found(myWord, myRole, others)
    }

    suspend fun allPairs(): List<WordPair> = withContext(Dispatchers.IO) { pairsSnapshot() }

    suspend fun searchPairs(query: String): List<WordPair> = withContext(Dispatchers.IO) {
        val all = pairsSnapshot()
        if (query.isEmpty()) return@withContext all
        all.filter { pair ->
            WordMatcher.matches(pair.civilianWord, query) ||
                WordMatcher.matches(pair.spyWord, query)
        }
    }

    suspend fun recordLookup(myWord: String, otherWords: List<String>) = withContext(Dispatchers.IO) {
        val others = otherWords.joinToString("|")
        val latest = historyDao.recent(1).firstOrNull()
        if (latest?.myWord == myWord && latest.otherWords == others) return@withContext
        historyDao.insert(
            LookupHistory(
                myWord = myWord,
                otherWords = others,
            )
        )
        val maxHistory = 80
        val extra = historyDao.count() - maxHistory
        if (extra > 0) historyDao.deleteOldest(extra)
    }

    suspend fun recentHistory(limit: Int = 80): List<LookupHistory> =
        withContext(Dispatchers.IO) { historyDao.recent(limit) }

    suspend fun topLookupWords(limit: Int = 16): List<LookupWordCount> =
        withContext(Dispatchers.IO) { historyDao.topWords(limit) }

    suspend fun deleteHistoryItem(id: Long) = withContext(Dispatchers.IO) {
        historyDao.deleteById(id)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) { historyDao.clearAll() }

    /** Nhập file / nhiều dòng — user thường chờ duyệt; admin duyệt ngay. */
    suspend fun importPairs(
        rows: List<Pair<String, String>>,
        isAdmin: Boolean = false,
    ): PairSaveResult = withContext(Dispatchers.IO) {
        var result = PairSaveResult()
        val approval = if (isAdmin) WordPair.STATUS_APPROVED else WordPair.STATUS_PENDING
        for ((civilian, spy) in rows) {
            val status = tryInsertPair(civilian, spy, approval, WordPair.SOURCE_IMPORT)
            result = result.plus(
                status,
                pendingApproval = !isAdmin && status == InsertStatus.SUCCESS,
            )
        }
        rebuildLookupCache()
        result
    }

    private suspend fun approveAllLocalPending() {
        pairsSnapshot().filter { it.isPendingApproval }.forEach { pair ->
            dao.updateApproval(pair.id, WordPair.STATUS_APPROVED, pair.pairSource)
        }
        invalidatePairsSnapshot()
    }

    /** Gộp từ server vào local — không xóa từ đang có (giữ DB máy để chơi). */
    private suspend fun mergeRemoteIntoLocal(remote: List<RemoteWordPair>): PairSaveResult {
        var result = PairSaveResult()
        for ((civilian, spy) in remote.map { it.civilianWord to it.spyWord }) {
            val existing = findDuplicatePair(civilian, spy)
            if (existing != null) {
                markPairSyncedIfNeeded(existing)
                result = result.plus(InsertStatus.DUPLICATE)
                continue
            }
            val status = tryInsertPair(
                civilian,
                spy,
                WordPair.STATUS_APPROVED,
                WordPair.SOURCE_SYNC,
            )
            result = result.plus(status)
        }
        pruneLocalRemovedOnServer(remote)
        dedupeLocalPairs()
        rebuildLookupCache()
        return result
    }

    /** Admin xóa trên web — gỡ bản sync/đã đẩy khỏi máy, không đẩy ngược lên. */
    private suspend fun pruneLocalRemovedOnServer(remote: List<RemoteWordPair>) {
        val toRemove = pairsSnapshot().filter { pair ->
            (pair.serverSynced || pair.pairSource == WordPair.SOURCE_SYNC) &&
                !remote.any { r ->
                    WordMatcher.isDuplicatePair(
                        pair.civilianWord,
                        pair.spyWord,
                        r.civilianWord,
                        r.spyWord,
                    )
                }
        }
        if (toRemove.isEmpty()) return
        toRemove.forEach { dao.deleteById(it.id) }
        invalidatePairsSnapshot()
    }

    /** Kéo từ server nền — tự bổ sung từ khóa, không đụng từ local. */
    suspend fun mergeFromServerQuiet(
        baseUrl: String,
        token: String,
        lockContext: android.content.Context? = null,
    ) = withContext(Dispatchers.IO) {
        if (token.isEmpty()) return@withContext
        try {
            mergeRemoteIntoLocal(WordSyncClient(baseUrl, token, lockContext).pullPairs())
        } catch (_: Exception) {
        }
    }

    /** Chỉ đẩy cặp từ chưa có trên server. */
    private suspend fun pairsToPushToServer(): List<WordPair> =
        pairsSnapshot().filter { !it.serverSynced }

    suspend fun clearAllLocalData() = withContext(Dispatchers.IO) {
        dao.clearAll()
        historyDao.clearAll()
        invalidatePairsSnapshot()
        lookupCache.clear()
    }

    suspend fun pullServerWords(
        baseUrl: String,
        token: String,
        lockContext: android.content.Context? = null,
    ) = withContext(Dispatchers.IO) {
        val client = WordSyncClient(baseUrl, token, lockContext)
        mergeRemoteIntoLocal(client.pullPairs())
        warmCache()
    }

    suspend fun syncWithServer(
        baseUrl: String,
        token: String,
        lockContext: android.content.Context? = null,
        isAdmin: Boolean = false,
    ): ServerSyncResult =
        withContext(Dispatchers.IO) {
            val client = WordSyncClient(baseUrl, token, lockContext)
            val pullResult = mergeRemoteIntoLocal(client.pullPairs())
            val toPush = pairsToPushToServer()
            val pushResult = if (toPush.isEmpty()) {
                RemoteSyncPushResult(added = 0, skipped = 0, total = pairsSnapshot().size)
            } else {
                pushAndMarkSynced(client, toPush)
            }
            if (isAdmin) {
                approveAllLocalPending()
            }
            warmCache()
            ServerSyncResult(
                pulledAdded = pullResult.added,
                pulledSkipped = pullResult.duplicate + pullResult.empty + pullResult.sameWord,
                pushedAdded = pushResult.added,
                pushedSkipped = pushResult.skipped,
                serverTotal = pushResult.total,
                localTotal = pairsSnapshot().size,
            )
        }

    /**
     * Tự bắt + đồng bộ nền sau ván.
     * Trùng từ (kể cả đảo vai) → bỏ qua im lặng, không báo lỗi (không làm phiền lúc chơi).
     */
    suspend fun autoCaptureAndSync(
        context: android.content.Context,
        civilian: String,
        spy: String,
        baseUrl: String,
        token: String,
    ): AutoCaptureResult = withContext(Dispatchers.IO) {
        when (
            tryInsertPair(
                civilian,
                spy,
                WordPair.STATUS_APPROVED,
                WordPair.SOURCE_CAPTURE,
            )
        ) {
            InsertStatus.SUCCESS -> pushCaptureIfNeeded(context, civilian, spy, baseUrl, token)
            InsertStatus.DUPLICATE -> {
                val existing = findDuplicatePair(civilian, spy)
                when {
                    existing == null -> AutoCaptureResult.DUPLICATE_SILENT
                    existing.serverSynced -> AutoCaptureResult.DUPLICATE_SILENT
                    else -> pushCaptureIfNeeded(context, civilian, spy, baseUrl, token)
                }
            }
            else -> AutoCaptureResult.FAILED_SILENT
        }
    }

    private suspend fun pushCaptureIfNeeded(
        context: android.content.Context,
        civilian: String,
        spy: String,
        baseUrl: String,
        token: String,
    ): AutoCaptureResult {
        if (token.isEmpty()) {
            PendingSyncStore.enqueue(context, civilian, spy)
            return AutoCaptureResult.QUEUED_OFFLINE
        }
        val pair = findDuplicatePair(civilian, spy)
            ?: WordPair(
                civilianWord = civilian,
                spyWord = spy,
                approvalStatus = WordPair.STATUS_APPROVED,
                pairSource = WordPair.SOURCE_CAPTURE,
            )
        if (pair.serverSynced) return AutoCaptureResult.DUPLICATE_SILENT
        return if (pushPairsToServer(context, baseUrl, token, listOf(pair))) {
            flushPendingPushToServer(context, baseUrl, token)
            AutoCaptureResult.SAVED
        } else {
            PendingSyncStore.enqueue(context, civilian, spy)
            AutoCaptureResult.QUEUED_OFFLINE
        }
    }

    suspend fun flushPendingPushToServer(
        context: android.content.Context,
        baseUrl: String,
        token: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (token.isEmpty()) return@withContext true
        val pending = PendingSyncStore.load(context).filter { item ->
            val existing = findDuplicatePair(item.civilian, item.spy)
            existing == null || !existing.serverSynced
        }
        if (pending.isEmpty()) {
            PendingSyncStore.clear(context)
            return@withContext true
        }
        val pairs = pending.map {
            findDuplicatePair(it.civilian, it.spy)
                ?: WordPair(
                    civilianWord = it.civilian,
                    spyWord = it.spy,
                    approvalStatus = WordPair.STATUS_APPROVED,
                    pairSource = WordPair.SOURCE_CAPTURE,
                )
        }
        val ok = pushPairsToServer(context, baseUrl, token, pairs)
        if (ok) PendingSyncStore.clear(context)
        ok
    }

    private suspend fun pushAndMarkSynced(
        client: WordSyncClient,
        pairs: List<WordPair>,
    ): RemoteSyncPushResult {
        val result = client.pushPairs(pairs)
        if (result.added > 0 || result.skipped > 0) {
            markPairsSynced(pairs)
        }
        return result
    }

    private suspend fun pushPairsToServer(
        context: android.content.Context,
        baseUrl: String,
        token: String,
        pairs: List<WordPair>,
    ): Boolean {
        if (token.isEmpty() || pairs.isEmpty()) return true
        return try {
            val client = WordSyncClient(baseUrl, token, context)
            pushAndMarkSynced(client, pairs)
            pullAdminInboxQuiet(context, baseUrl, token)
            true
        } catch (_: AccountLockedException) {
            false
        } catch (_: SyncException) {
            false
        }
    }

    private suspend fun pullAdminInboxQuiet(
        context: android.content.Context,
        baseUrl: String,
        token: String,
    ) {
        try {
            val inbox = AdminNotificationClient(baseUrl, token, context).fetchInbox()
            PendingAdminNotificationStore.save(context, inbox)
        } catch (_: SyncException) {
        }
    }

    private suspend fun markPairSyncedIfNeeded(pair: WordPair) {
        if (pair.serverSynced) return
        dao.markServerSynced(pair.id)
        invalidatePairsSnapshot()
    }

    private suspend fun markPairsSynced(pairs: List<WordPair>) {
        var changed = false
        for (pair in pairs) {
            val target = if (pair.id > 0) pair else findDuplicatePair(pair.civilianWord, pair.spyWord)
            if (target != null && !target.serverSynced) {
                dao.markServerSynced(target.id)
                changed = true
            }
        }
        if (changed) invalidatePairsSnapshot()
    }

    private suspend fun pairsSnapshot(): List<WordPair> {
        var snap = pairsSnapshot
        if (snap == null) {
            snap = dao.getAll()
            pairsSnapshot = snap
        }
        return snap
    }

    private fun invalidatePairsSnapshot() {
        pairsSnapshot = null
    }

    private suspend fun rebuildLookupCache() {
        lookupCache.clear()
        pairsSnapshot().filter { it.isApproved }.forEach { cachePair(it) }
    }

    private suspend fun findDuplicatePair(
        civilian: String,
        spy: String,
        excludeId: Long? = null,
    ): WordPair? {
        return pairsSnapshot().firstOrNull { pair ->
            (excludeId == null || pair.id != excludeId) &&
                WordMatcher.isDuplicatePair(pair.civilianWord, pair.spyWord, civilian, spy)
        }
    }

    private suspend fun hasExactDuplicatePair(
        civilian: String,
        spy: String,
        excludeId: Long? = null,
    ): Boolean = findDuplicatePair(civilian, spy, excludeId) != null

    /** Gộp cặp trùng (kể cả đảo vai) — giữ bản đã duyệt. */
    private suspend fun dedupeLocalPairs() {
        val all = pairsSnapshot().sortedBy { it.id }
        val kept = ArrayList<WordPair>()
        for (pair in all) {
            val dupIndex = kept.indexOfFirst { existing ->
                WordMatcher.isDuplicatePair(
                    existing.civilianWord,
                    existing.spyWord,
                    pair.civilianWord,
                    pair.spyWord,
                )
            }
            if (dupIndex < 0) {
                kept.add(pair)
                continue
            }
            val existing = kept[dupIndex]
            val dropId = when {
                pair.isApproved && existing.isPendingApproval -> {
                    kept[dupIndex] = pair
                    existing.id
                }
                existing.isApproved && pair.isPendingApproval -> pair.id
                else -> pair.id
            }
            dao.deleteById(dropId)
        }
        invalidatePairsSnapshot()
    }

    private suspend fun findApprovedPairsByExactWord(word: String): List<WordPair> {
        return pairsSnapshot().filter { pair ->
            pair.isApproved &&
                (WordMatcher.matches(pair.civilianWord, word) || WordMatcher.matches(pair.spyWord, word))
        }
    }

    private fun cachePair(pair: WordPair) {
        if (!pair.isApproved) return
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

data class ServerSyncResult(
    val pulledAdded: Int,
    val pulledSkipped: Int,
    val pushedAdded: Int,
    val pushedSkipped: Int,
    val serverTotal: Int,
    val localTotal: Int,
)

enum class AutoCaptureResult {
    /** Lưu mới + đẩy server OK */
    SAVED,
    /** Đã có cặp từ — bỏ qua, không báo lỗi */
    DUPLICATE_SILENT,
    /** Mất mạng — xếp hàng đợi, không báo lỗi */
    QUEUED_OFFLINE,
    /** Dữ liệu không hợp lệ — bỏ qua im lặng */
    FAILED_SILENT,
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