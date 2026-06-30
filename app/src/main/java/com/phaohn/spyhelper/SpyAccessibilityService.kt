package com.phaohn.spyhelper

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SpyAccessibilityService : AccessibilityService() {

    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: WordRepository

    private var lastSelfWord: String? = null
    private var cachedGameBubble: CachedGameBubble? = null
    private var lastRecordedKey: String? = null
    private var lastCivilian: String? = null
    private var lastSpy: String? = null
    private var bubbleVisible = false
    private var overlayStandby = false
    private var hideJob: Job? = null
    private var lookupJob: Job? = null
    private var lastReadyClickAt = 0L
    private var lastSitClickAt = 0L
    private var lastSitSkipLogAt = 0L
    private var seatedInLobby = false
    private var voteRoundActive = false
    private var voteTappedThisRound = false
    private var lastVoteTapAt = 0L
    private var lastTimerSec: Int? = null
    private var voteTimerZeroSeen = false
    private var continuousScanJob: Job? = null
    private var lastVoteSkipLogAt = 0L
    private var voteTapInProgress = false
    private var voteUiGoneStreak = 0

    private val pairsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PAIRS_UPDATED) {
                refreshLookupAfterSync()
            }
        }
    }

    private val autoPrefsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_AUTO_PREFS_CHANGED) return
            onAutoPrefsChanged()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = PhaoHNApp.repo(application)
        ensureOverlayService()
        registerPairsUpdatedReceiver()
        registerAutoPrefsReceiver()
        scanScope.launch { repository.warmCache() }
        startContinuousScan()
        mainScope.launch {
            delay(OVERLAY_BOOT_DELAY_MS)
            if (isWePlayForeground()) ensureBubbleVisible()
        }
    }

    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun registerPairsUpdatedReceiver() {
        registerReceiverCompat(pairsUpdatedReceiver, IntentFilter(ACTION_PAIRS_UPDATED))
    }

    private fun registerAutoPrefsReceiver() {
        registerReceiverCompat(autoPrefsReceiver, IntentFilter(ACTION_AUTO_PREFS_CHANGED))
    }

    private fun onAutoPrefsChanged() {
        val ctx = applicationContext
        if (SpyPrefs.isAutoSitEnabled(ctx)) lastSitClickAt = 0L
        if (SpyPrefs.isAutoReadyEnabled(ctx)) lastReadyClickAt = 0L
        if (!SpyPrefs.isAutoVoteEnabled(ctx)) resetVoteState()
        scanScope.launch { scanOnce() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName != WePlayIds.PACKAGE) {
            seatedInLobby = false
            resetVoteState()
            return
        }
        scanOnce()
    }

    private fun startContinuousScan() {
        if (continuousScanJob?.isActive == true) return
        continuousScanJob = scanScope.launch {
            while (true) {
                val nextDelay = scanOnce()
                delay(nextDelay)
            }
        }
    }

    private fun stopContinuousScan() {
        continuousScanJob?.cancel()
        continuousScanJob = null
    }

    private fun scanOnce(): Long {
        val root = findWePlayRoot()
        if (root == null) {
            seatedInLobby = false
            resetVoteState()
            if (isWePlayForeground()) {
                cancelPendingHide()
                maybeShowStandby()
                return POLL_NO_WEPLAY_MS
            }
            if (bubbleVisible) requestHide()
            return POLL_NO_WEPLAY_MS
        }
        try {
            return scanTree(root)
        } finally {
            root.recycle()
        }
    }

    private fun isWePlayForeground(): Boolean {
        val active = rootInActiveWindow
        if (active != null) {
            val inWePlay = active.packageName?.toString() == WePlayIds.PACKAGE
            active.recycle()
            if (inWePlay) return true
        }
        val windows = windows ?: return false
        for (window in windows) {
            val node = window.root ?: continue
            try {
                if (node.packageName?.toString() == WePlayIds.PACKAGE) return true
            } finally {
                node.recycle()
            }
        }
        return false
    }

    private fun findWePlayRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() == WePlayIds.PACKAGE) {
            return active
        }
        active?.recycle()
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1
        val windows = windows ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            try {
                if (root.packageName?.toString() != WePlayIds.PACKAGE) continue
                val score = scoreWePlayWindow(window, root)
                if (score > bestScore) {
                    best?.recycle()
                    best = AccessibilityNodeInfo.obtain(root)
                    bestScore = score
                }
            } finally {
                root.recycle()
            }
        }
        return best
    }

    /** Ưu tiên cửa sổ phòng (có ghế), tránh MainActivity nằm dưới FixRoomActivity. */
    private fun scoreWePlayWindow(window: AccessibilityWindowInfo, root: AccessibilityNodeInfo): Int {
        var score = 0
        if (window.isFocused) score += 100
        if (window.isActive) score += 50
        if (WePlaySeatHelper.hasWePlayGameUi(root)) score += 200
        if (WePlaySeatHelper.hasVisibleSelfWord(root)) score += 80
        if (WePlaySeatHelper.hasVoteUiReady(root)) score += 60
        return score
    }

    private fun scanTree(root: AccessibilityNodeInfo): Long {
        if (root.packageName?.toString() != WePlayIds.PACKAGE) {
            seatedInLobby = false
            resetVoteState()
            clearRoomSeatCountIfNeeded()
            if (bubbleVisible) requestHide()
            return POLL_IDLE_MS
        }

        maybeDetectRoomSeatCount(root)

        val civilian = textById(root, WePlayIds.CIVILIAN)
        val spy = textById(root, WePlayIds.SPY)
        if (!civilian.isNullOrEmpty() && !spy.isNullOrEmpty()) {
            resetVoteState()
            maybeSavePairAndClose(root, civilian, spy)
            clearGameBubbleState()
            cancelPendingHide()
            maybeShowStandby()
            return POLL_LOBBY_MS
        }

        val selfWord = WordParser.parseSelfWord(textById(root, WePlayIds.SELF_WORD))
        val voteUi = WePlaySeatHelper.hasVoteUiReady(root)
        val inGame = selfWord != null || lastSelfWord != null ||
            cachedGameBubble != null ||
            WePlaySeatHelper.hasVisibleSelfWord(root)

        if (selfWord != null) {
            seatedInLobby = true
            cancelPendingHide()
            maybeLookup(selfWord)
        } else if (!WePlaySeatHelper.isWaitingInRoom(root) &&
            (lastSelfWord != null || cachedGameBubble != null)
        ) {
            seatedInLobby = true
            cancelPendingHide()
            restoreCachedBubble()
        } else {
            maybeShowStandby(root, voteUi)
        }

        if (inGame || voteUi) {
            ensureBubbleVisible()
            maybeAutoVote(root)
            return when {
                voteRoundActive || voteTapInProgress || voteUi -> POLL_VOTE_MS
                selfWord != null || lastSelfWord != null -> POLL_GAME_MS
                else -> POLL_LOBBY_MS
            }
        }

        if (WePlaySeatHelper.canAutoSit(root)) {
            if (lastSelfWord != null || cachedGameBubble != null) clearGameBubbleState()
            cancelPendingHide()
            maybeShowStandby(root)
            if (!voteRoundActive) resetVoteState()
            val seated = WePlaySeatHelper.isUserSeated(root)
            seatedInLobby = seated
            if (!seated) maybeAutoSit(root)
            maybeAutoReady(root)
            return POLL_LOBBY_MS
        }
        if (!voteRoundActive) resetVoteState()
        lastTimerSec = null
        seatedInLobby = false
        maybeAutoSit(root)
        maybeAutoReady(root)
        cancelPendingHide()
        if (cachedGameBubble != null) {
            restoreCachedBubble()
        } else {
            maybeShowStandby(root)
        }
        return POLL_IDLE_MS
    }

    private fun maybeDetectRoomSeatCount(root: AccessibilityNodeInfo) {
        if (!WePlaySeatHelper.hasRoomSeatUi(root)) {
            clearRoomSeatCountIfNeeded()
            return
        }
        val layout = WePlayIds.detectSeatLayout(root) ?: return
        val count = layout.count
        if (count !in 1..WePlayIds.SEAT_COUNT) return
        val ctx = applicationContext
        if (SpyPrefs.roomSeatCount(ctx) == count) return
        SpyPrefs.setRoomSeatCount(ctx, count)
        sendBroadcast(
            Intent(ACTION_ROOM_SEATS).apply {
                setPackage(packageName)
                putExtra(EXTRA_ROOM_SEAT_COUNT, count)
            },
        )
    }

    private fun clearRoomSeatCountIfNeeded() {
        val ctx = applicationContext
        if (SpyPrefs.roomSeatCount(ctx) == 0) return
        SpyPrefs.setRoomSeatCount(ctx, 0)
        sendBroadcast(
            Intent(ACTION_ROOM_SEATS).apply {
                setPackage(packageName)
                putExtra(EXTRA_ROOM_SEAT_COUNT, 0)
            },
        )
    }

    private fun maybeAutoSit(root: AccessibilityNodeInfo) {
        if (!SpyPrefs.isAutoSitEnabled(applicationContext)) return
        if (!WePlaySeatHelper.canAutoSit(root)) {
            val now = SystemClock.uptimeMillis()
            if (now - lastSitSkipLogAt > SIT_SKIP_LOG_INTERVAL_MS) {
                lastSitSkipLogAt = now
                Log.d(
                    SIT_TAG,
                    "skip sit: inGame=${WePlaySeatHelper.hasVisibleSelfWord(root)} " +
                        "seats=${WePlaySeatHelper.hasRoomSeatUi(root)} " +
                        "empty=${WePlaySeatHelper.hasEmptySeatInLobby(root)}",
                )
            }
            return
        }
        if (WePlaySeatHelper.isUserSeated(root)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSitClickAt < SIT_CLICK_COOLDOWN_MS) return
        val tapped = WePlaySeatHelper.tapFirstEmptySeat(this, root)
        if (tapped) {
            lastSitClickAt = now
            Log.d(SIT_TAG, "tap empty seat OK")
        } else {
            val now = SystemClock.uptimeMillis()
            if (now - lastSitSkipLogAt > SIT_SKIP_LOG_INTERVAL_MS) {
                lastSitSkipLogAt = now
                Log.d(
                    SIT_TAG,
                    "tap empty seat FAILED empty=${WePlaySeatHelper.hasEmptySeatInLobby(root)}",
                )
            }
        }
    }

    private fun resetVoteState() {
        voteRoundActive = false
        voteTappedThisRound = false
        lastTimerSec = null
        voteTimerZeroSeen = false
        voteUiGoneStreak = 0
    }

    private fun markVoteUiGone(root: AccessibilityNodeInfo): Boolean {
        if (WePlaySeatHelper.hasVoteUiReady(root)) {
            voteUiGoneStreak = 0
            return false
        }
        voteUiGoneStreak++
        if (voteUiGoneStreak < VOTE_FINISH_MISS_STREAK) return false
        Log.d(TAG, "vote finished (UI gone x$voteUiGoneStreak)")
        voteRoundActive = false
        voteTappedThisRound = false
        voteTimerZeroSeen = false
        voteUiGoneStreak = 0
        return true
    }

    private fun maybeAutoVote(root: AccessibilityNodeInfo) {
        if (!SpyPrefs.isAutoVoteEnabled(applicationContext)) return
        if (!SpyPrefs.isVoteSeatChosen(applicationContext)) return

        if (voteRoundActive && markVoteUiGone(root)) return

        val voteUi = WePlaySeatHelper.hasVoteUiReady(root)
        val timerSec = WePlayVoteHelper.readTimerSeconds(root)
        val countdown = timerSec != null && timerSec in 0..WePlayVoteHelper.VOTE_TIMER_MAX_SEC

        if (voteUi && (countdown || voteRoundActive)) {
            voteUiGoneStreak = 0
            if (!voteRoundActive) {
                if (!countdown) {
                    logVoteWaiting(timerSec)
                    return
                }
                voteTappedThisRound = false
                voteTimerZeroSeen = false
                Log.d(TAG, "vote phase active timer=${timerSec}s")
                voteRoundActive = true
            }
            attemptVoteTap(root)
            return
        }

        if (voteRoundActive) {
            if (markVoteUiGone(root)) return
        } else {
            voteUiGoneStreak = 0
            if (voteUi) logVoteWaiting(timerSec)
        }
    }

    private fun logVoteWaiting(timerSec: Int?) {
        val now = SystemClock.uptimeMillis()
        if (now - lastVoteSkipLogAt <= VOTE_SKIP_LOG_INTERVAL_MS) return
        lastVoteSkipLogAt = now
        Log.d(TAG, "vote UI seen, waiting timer (now=${timerSec ?: "?"}s)")
    }

    private fun attemptVoteTap(root: AccessibilityNodeInfo) {
        if (voteTappedThisRound || !voteRoundActive || voteTapInProgress) return
        if (!WePlaySeatHelper.hasVoteUiReady(root)) return

        val seat = SpyPrefs.voteTargetSeat(applicationContext)
        val tapAtSec = SpyPrefs.voteTapAtSeconds(applicationContext)
        val timerRaw = textById(root, WePlayIds.TIMER_TV)
        val timerSec = WePlayVoteHelper.parseTimerSeconds(timerRaw)

        if (!shouldFireVoteTap(timerSec, tapAtSec, timerRaw)) return

        if (!voteTimerZeroSeen) {
            voteTimerZeroSeen = true
            val shown = timerSec?.toString() ?: lastTimerSec?.toString() ?: "?"
            Log.d(TAG, "timer=${shown}s ($timerRaw), tap @ ${tapAtSec}s")
        }
        performVoteTap(root, seat)
    }

    /**
     * tapAt=0: chỉ tap đúng 0s (hoặc fallback khi timer mất sau 1s).
     * tapAt>0: tap ngay khi timer <= tapAt (ví dụ chọn 3 → tap lúc 3,2,1,0 lần đầu gặp).
     */
    private fun shouldFireVoteTap(timerSec: Int?, tapAtSec: Int, timerRaw: String?): Boolean {
        when {
            timerSec != null -> {
                if (timerSec > tapAtSec) {
                    voteTimerZeroSeen = false
                    lastTimerSec = timerSec
                    return false
                }
                lastTimerSec = timerSec
                return tapAtSec > 0 || timerSec == 0
            }
            tapAtSec == 0 -> {
                val prev = lastTimerSec
                if (prev != null && prev <= 1) {
                    Log.d(TAG, "0s fallback: timer unreadable ($timerRaw) sau ${prev}s")
                    return true
                }
                return false
            }
            else -> return false
        }
    }

    private fun performVoteTap(root: AccessibilityNodeInfo, seat: Int) {
        if (voteTappedThisRound || !voteRoundActive || voteTapInProgress) return
        val now = SystemClock.uptimeMillis()
        if (now - lastVoteTapAt < VOTE_TAP_COOLDOWN_MS) return
        voteTapInProgress = true
        val seatRes = WePlayIds.seatResourceId(root, seat)
        Log.d(TAG, "đột tử tap seat $seat ($seatRes) x3 @ ${lastTimerSec}s")
        mainScope.launch {
            val tapped = WePlaySeatHelper.tapSeatNumberBurst(
                service = this@SpyAccessibilityService,
                seatNum = seat,
                obtainRoot = { findWePlayRoot() },
            )
            voteTapInProgress = false
            if (tapped) {
                lastVoteTapAt = now
                voteTappedThisRound = true
                Log.d(TAG, "tap seat $seat OK x3")
            } else {
                Log.d(TAG, "tap seat $seat FAILED — retry")
            }
        }
    }

    private fun maybeAutoReady(root: AccessibilityNodeInfo) {
        if (!SpyPrefs.isAutoReadyEnabled(applicationContext)) return
        val nodes = root.findAccessibilityNodeInfosByViewId(WePlayIds.READY_BTN)
        try {
            val node = nodes.firstOrNull() ?: return
            val label = node.text?.toString().orEmpty()
            if (!label.contains(WePlayIds.READY_LABEL, ignoreCase = true)) return
            if (!node.isEnabled) return
            val now = SystemClock.uptimeMillis()
            if (now - lastReadyClickAt < READY_CLICK_COOLDOWN_MS) return
            if (AccessibilityTapHelper.tapNode(this, node)) {
                lastReadyClickAt = now
            }
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    private fun textById(root: AccessibilityNodeInfo, viewId: String): String? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        try {
            return nodes.firstOrNull()?.text?.toString()
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    private fun maybeSavePairAndClose(
        root: AccessibilityNodeInfo,
        civilian: String,
        spy: String,
    ) {
        if (civilian == lastCivilian && spy == lastSpy) {
            dismissEndGameDialog(root)
            return
        }
        lastCivilian = civilian
        lastSpy = spy
        scanScope.launch {
            val saved = repository.savePair(civilian, spy)
            if (saved) {
                sendBroadcast(Intent(ACTION_PAIRS_UPDATED).setPackage(packageName))
            }
            val fresh = findWePlayRoot()
            if (fresh != null) {
                try {
                    dismissEndGameDialog(fresh)
                } finally {
                    fresh.recycle()
                }
            }
        }
    }

    private fun dismissEndGameDialog(root: AccessibilityNodeInfo) {
        val nodes = root.findAccessibilityNodeInfosByViewId(WePlayIds.END_DIALOG_CLOSE_BTN)
        try {
            val close = nodes.firstOrNull() ?: return
            if (AccessibilityTapHelper.tapNode(this, close)) {
                Log.d(DIALOG_TAG, "close end dialog OK")
            }
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    private fun ensureOverlayService() {
        if (!Settings.canDrawOverlays(this)) return
        try {
            OverlayService.start(this)
        } catch (_: Exception) {
        }
    }

    private fun clearActiveWordState() {
        lastSelfWord = null
        lastRecordedKey = null
    }

    private fun clearGameBubbleState() {
        clearActiveWordState()
        cachedGameBubble = null
    }

    private fun maybeShowStandby(
        root: AccessibilityNodeInfo? = null,
        @Suppress("UNUSED_PARAMETER") voteUi: Boolean = root?.let { WePlaySeatHelper.hasVoteUiReady(it) } == true,
    ) {
        if (cachedGameBubble != null) {
            restoreCachedBubble()
            return
        }
        if (lastSelfWord != null) return
        if (root != null && WePlaySeatHelper.hasVisibleSelfWord(root)) return
        showStandbyFab()
    }

    private fun ensureBubbleVisible() {
        if (!Settings.canDrawOverlays(this)) return
        if (bubbleVisible && OverlayService.isBubbleOnScreen()) return
        if (cachedGameBubble != null) {
            restoreCachedBubble()
        } else {
            showStandbyFab()
        }
    }

    private fun showStandbyFab() {
        if (!Settings.canDrawOverlays(this)) return
        if (bubbleVisible && !OverlayService.isBubbleOnScreen()) {
            bubbleVisible = false
            overlayStandby = false
        }
        if (overlayStandby && bubbleVisible && OverlayService.isBubbleOnScreen()) return
        cancelPendingHide()
        mainScope.launch {
            if (!Settings.canDrawOverlays(this@SpyAccessibilityService)) return@launch
            ensureOverlayService()
            OverlayService.update(
                this@SpyAccessibilityService,
                getString(R.string.overlay_window_title),
                null,
                emptyList(),
                getString(R.string.overlay_standby_hint),
                animate = false,
            )
            overlayStandby = true
            bubbleVisible = true
        }
    }

    private fun restoreCachedBubble() {
        val cached = cachedGameBubble ?: return
        showGameBubble(cached.myWord, cached.myRole, cached.others, cached.plainMessage)
    }

    private fun showGameBubble(
        myWord: String,
        myRole: WordRole?,
        others: List<LabeledWord>,
        plainMessage: String? = null,
    ) {
        val snapshot = CachedGameBubble(myWord, myRole, others, plainMessage)
        if (bubbleVisible && !OverlayService.isBubbleOnScreen()) {
            bubbleVisible = false
        }
        if (cachedGameBubble == snapshot && bubbleVisible && !overlayStandby &&
            OverlayService.isBubbleOnScreen()
        ) {
            return
        }
        cachedGameBubble = snapshot
        if (!Settings.canDrawOverlays(this)) return
        cancelPendingHide()
        mainScope.launch {
            if (!Settings.canDrawOverlays(this@SpyAccessibilityService)) return@launch
            overlayStandby = false
            ensureOverlayService()
            OverlayService.update(
                this@SpyAccessibilityService,
                myWord,
                myRole,
                others,
                plainMessage,
                animate = false,
            )
            bubbleVisible = true
        }
    }

    private fun sendLookupBroadcast(myWord: String, myRole: WordRole?, others: List<LabeledWord>) {
        sendBroadcast(
            Intent(ACTION_LOOKUP).apply {
                setPackage(packageName)
                putExtra(EXTRA_MY_WORD, myWord)
                myRole?.let { putExtra(EXTRA_MY_ROLE, it.name) }
                putExtra(EXTRA_OTHERS_ROLES, RoleTextFormatter.encodeOthers(others))
                putStringArrayListExtra(EXTRA_OTHER_WORDS, ArrayList(others.map { it.word }))
            }
        )
    }

    private fun cancelPendingHide() {
        hideJob?.cancel()
        hideJob = null
    }

    private fun requestHide() {
        if (!bubbleVisible) return
        if (isWePlayForeground()) return
        if (hideJob?.isActive == true) return
        hideJob = mainScope.launch {
            delay(HIDE_DEBOUNCE_MS)
            performHide()
        }
    }

    private fun performHide() {
        cancelPendingHide()
        if (isWePlayForeground()) {
            ensureBubbleVisible()
            return
        }
        if (!bubbleVisible && lastSelfWord == null && cachedGameBubble == null) return
        clearGameBubbleState()
        overlayStandby = false
        bubbleVisible = false
        if (Settings.canDrawOverlays(this)) {
            OverlayService.hide(this)
        }
    }

    private fun refreshLookupAfterSync() {
        val word = lastSelfWord ?: cachedGameBubble?.myWord ?: return
        cachedGameBubble = null
        lastRecordedKey = null
        lastSelfWord = null
        maybeLookup(word)
    }

    private fun maybeLookup(selfWord: String) {
        if (selfWord == lastSelfWord && cachedGameBubble != null) return
        lastSelfWord = selfWord
        lookupJob?.cancel()
        lookupJob = scanScope.launch {
            when (val result = repository.lookupOthers(selfWord)) {
                is LookupResult.Found -> {
                    if (selfWord != lastSelfWord) return@launch
                    maybeRecordLookup(result.myWord, result.otherWordStrings)
                    showGameBubble(result.myWord, result.myRole, result.otherWords)
                    sendLookupBroadcast(result.myWord, result.myRole, result.otherWords)
                }
                is LookupResult.NotFound -> {
                    if (selfWord != lastSelfWord) return@launch
                    maybeRecordLookup(selfWord, emptyList())
                    showGameBubble(selfWord, null, emptyList(), getString(R.string.not_in_db))
                    sendLookupBroadcast(selfWord, null, emptyList())
                }
                LookupResult.NotInGame -> mainScope.launch { maybeShowStandby() }
            }
        }
    }

    private suspend fun maybeRecordLookup(myWord: String, otherWords: List<String>) {
        val key = "$myWord|${otherWords.joinToString("|")}"
        if (key == lastRecordedKey) return
        lastRecordedKey = key
        repository.recordLookup(myWord, otherWords)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopContinuousScan()
        cancelPendingHide()
        lookupJob?.cancel()
        try {
            unregisterReceiver(pairsUpdatedReceiver)
            unregisterReceiver(autoPrefsReceiver)
        } catch (_: Exception) {
        }
        scanScope.cancel()
        mainScope.cancel()
        super.onDestroy()
    }

    private data class CachedGameBubble(
        val myWord: String,
        val myRole: WordRole?,
        val others: List<LabeledWord>,
        val plainMessage: String?,
    )

    companion object {
        private const val POLL_VOTE_MS = 16L
        private const val POLL_GAME_MS = 40L
        private const val POLL_LOBBY_MS = 100L
        private const val POLL_IDLE_MS = 250L
        private const val POLL_NO_WEPLAY_MS = 600L
        private const val HIDE_DEBOUNCE_MS = 800L
        private const val OVERLAY_BOOT_DELAY_MS = 400L
        private const val READY_CLICK_COOLDOWN_MS = 300L
        private const val SIT_CLICK_COOLDOWN_MS = 1000L
        private const val SIT_SKIP_LOG_INTERVAL_MS = 3000L
        private const val VOTE_TAP_COOLDOWN_MS = 350L
        private const val VOTE_SKIP_LOG_INTERVAL_MS = 3000L
        private const val VOTE_FINISH_MISS_STREAK = 3
        private const val TAG = "PhaoHN-Vote"
        private const val SIT_TAG = "PhaoHN-Sit"
        private const val DIALOG_TAG = "PhaoHN-Dialog"

        const val ACTION_PAIRS_UPDATED = "com.phaohn.spyhelper.PAIRS_UPDATED"
        const val ACTION_AUTO_PREFS_CHANGED = "com.phaohn.spyhelper.AUTO_PREFS_CHANGED"
        const val ACTION_LOOKUP = "com.phaohn.spyhelper.LOOKUP"
        const val ACTION_ROOM_SEATS = "com.phaohn.spyhelper.ROOM_SEATS"
        const val EXTRA_ROOM_SEAT_COUNT = "room_seat_count"
        const val EXTRA_MY_WORD = "my_word"
        const val EXTRA_MY_ROLE = "my_role"
        const val EXTRA_OTHERS_ROLES = "others_roles"
        const val EXTRA_OTHER_WORDS = "other_words"

        fun isEnabled(context: android.content.Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains(context.packageName)
        }
    }
}