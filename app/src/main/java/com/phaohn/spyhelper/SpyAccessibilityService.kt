package com.phaohn.spyhelper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: WordRepository

    private var lastSelfWord: String? = null
    private var lastCivilian: String? = null
    private var lastSpy: String? = null
    private var bubbleVisible = false
    private var hideJob: Job? = null
    private var lastScanAt = 0L
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = PhaoHNApp.repo(application)
        ensureOverlayService()
        scope.launch { repository.warmCache() }
        startContinuousScan()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName != WePlayIds.PACKAGE) {
            seatedInLobby = false
            resetVoteState()
            if (bubbleVisible) requestHide()
            return
        }
        scanOnce()
    }

    private fun startContinuousScan() {
        if (continuousScanJob?.isActive == true) return
        continuousScanJob = scope.launch {
            while (true) {
                scanOnce()
                delay(CONTINUOUS_POLL_MS)
            }
        }
    }

    private fun stopContinuousScan() {
        continuousScanJob?.cancel()
        continuousScanJob = null
    }

    private fun scanOnce() {
        val now = SystemClock.uptimeMillis()
        if (now - lastScanAt < CONTINUOUS_POLL_MS) return
        lastScanAt = now
        val root = findWePlayRoot() ?: return
        try {
            scanTree(root)
        } finally {
            root.recycle()
        }
    }

    private fun findWePlayRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null) {
            if (active.packageName?.toString() == WePlayIds.PACKAGE &&
                WePlaySeatHelper.hasWePlayGameUi(active)
            ) {
                return active
            }
            active.recycle()
        }
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

    private fun scanTree(root: AccessibilityNodeInfo) {
        if (root.packageName?.toString() != WePlayIds.PACKAGE) {
            seatedInLobby = false
            resetVoteState()
            clearRoomSeatCountIfNeeded()
            if (bubbleVisible) requestHide()
            return
        }

        maybeDetectRoomSeatCount(root)

        val civilian = textById(root, WePlayIds.CIVILIAN)
        val spy = textById(root, WePlayIds.SPY)
        if (!civilian.isNullOrEmpty() && !spy.isNullOrEmpty()) {
            resetVoteState()
            maybeSavePairAndClose(root, civilian, spy)
            performHide()
            return
        }

        val selfWord = WordParser.parseSelfWord(textById(root, WePlayIds.SELF_WORD))
        val voteUi = WePlaySeatHelper.hasVoteUiReady(root)
        val inGame = selfWord != null || lastSelfWord != null ||
            WePlaySeatHelper.hasVisibleSelfWord(root)

        if (selfWord != null) {
            seatedInLobby = true
            cancelPendingHide()
            maybeLookup(selfWord)
        } else if (lastSelfWord != null && !WePlaySeatHelper.isWaitingInRoom(root)) {
            seatedInLobby = true
            cancelPendingHide()
        }

        if (inGame || voteUi) {
            maybeAutoVote(root)
            return
        }

        if (WePlaySeatHelper.canAutoSit(root)) {
            if (lastSelfWord != null) {
                performHide()
            }
            if (!voteRoundActive) resetVoteState()
            val seated = WePlaySeatHelper.isUserSeated(root)
            seatedInLobby = seated
            if (!seated) maybeAutoSit(root)
            maybeAutoReady(root)
            return
        }
        if (!voteRoundActive) resetVoteState()
        lastSelfWord = null
        lastTimerSec = null
        seatedInLobby = false
        maybeAutoSit(root)
        maybeAutoReady(root)
        if (bubbleVisible) requestHide()
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

        if (!WePlayVoteHelper.isVotePhaseActive(root)) {
            if (voteRoundActive) {
                if (markVoteUiGone(root)) return
            } else {
                voteUiGoneStreak = 0
                val timer = WePlayVoteHelper.readTimerSeconds(root)
                val now = SystemClock.uptimeMillis()
                if (now - lastVoteSkipLogAt > VOTE_SKIP_LOG_INTERVAL_MS &&
                    WePlaySeatHelper.hasVoteUiReady(root)
                ) {
                    lastVoteSkipLogAt = now
                    Log.d(TAG, "vote UI seen, waiting timer (now=${timer}s)")
                }
            }
            return
        }

        voteUiGoneStreak = 0

        val timerSec = WePlayVoteHelper.readTimerSeconds(root)
        if (!voteRoundActive) {
            voteTappedThisRound = false
            voteTimerZeroSeen = false
            Log.d(TAG, "vote phase active timer=${timerSec}s")
        }
        voteRoundActive = true
        attemptVoteTap(root)
    }

    private fun attemptVoteTap(root: AccessibilityNodeInfo) {
        if (voteTappedThisRound || !voteRoundActive || voteTapInProgress) return
        if (markVoteUiGone(root)) return
        if (!WePlayVoteHelper.isVotePhaseActive(root)) return

        val seat = SpyPrefs.voteTargetSeat(applicationContext)
        val tapAtSec = SpyPrefs.voteTapAtSeconds(applicationContext)
        val timerRaw = textById(root, WePlayIds.TIMER_TV)
        val timerSec = WePlayVoteHelper.parseTimerSeconds(timerRaw) ?: return
        lastTimerSec = timerSec

        if (timerSec > tapAtSec) {
            voteTimerZeroSeen = false
            return
        }
        if (!voteTimerZeroSeen) {
            voteTimerZeroSeen = true
            Log.d(TAG, "timer=${timerSec}s ($timerRaw), tap ngay @ ${tapAtSec}s")
        }
        performVoteTap(root, seat)
    }

    private fun performVoteTap(root: AccessibilityNodeInfo, seat: Int) {
        if (voteTappedThisRound || !voteRoundActive || voteTapInProgress) return
        val now = SystemClock.uptimeMillis()
        if (now - lastVoteTapAt < VOTE_TAP_COOLDOWN_MS) return
        voteTapInProgress = true
        val seatRes = WePlayIds.seatResourceId(root, seat)
        Log.d(TAG, "đột tử tap seat $seat ($seatRes) @ ${lastTimerSec}s")
        val tapped = WePlaySeatHelper.tapSeatNumber(this, root, seat)
        voteTapInProgress = false
        if (tapped) {
            lastVoteTapAt = now
            voteTappedThisRound = true
            Log.d(TAG, "tap seat $seat OK")
        } else {
            Log.d(TAG, "tap seat $seat FAILED — retry")
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
        scope.launch {
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

    private fun showGameBubble(
        myWord: String,
        myRole: WordRole?,
        others: List<LabeledWord>,
        plainMessage: String? = null,
    ) {
        if (!Settings.canDrawOverlays(this)) return
        ensureOverlayService()
        OverlayService.update(this, myWord, myRole, others, plainMessage)
        bubbleVisible = true
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
        if (hideJob?.isActive == true) return
        hideJob = scope.launch {
            delay(HIDE_DEBOUNCE_MS)
            performHide()
        }
    }

    private fun performHide() {
        cancelPendingHide()
        if (!bubbleVisible && lastSelfWord == null) return
        lastSelfWord = null
        bubbleVisible = false
        if (Settings.canDrawOverlays(this)) {
            OverlayService.hide(this)
        }
        sendBroadcast(
            Intent(ACTION_LOOKUP).apply {
                setPackage(packageName)
                putExtra(EXTRA_MY_WORD, "")
                putStringArrayListExtra(EXTRA_OTHER_WORDS, arrayListOf())
            }
        )
    }

    private fun maybeLookup(selfWord: String) {
        if (selfWord == lastSelfWord && bubbleVisible) return
        lastSelfWord = selfWord
        showGameBubble(selfWord, null, emptyList())
        scope.launch {
            when (val result = repository.lookupOthers(selfWord)) {
                is LookupResult.Found -> {
                    if (selfWord != lastSelfWord) return@launch
                    repository.recordLookup(result.myWord, result.otherWordStrings)
                    showGameBubble(result.myWord, result.myRole, result.otherWords)
                    sendLookupBroadcast(result.myWord, result.myRole, result.otherWords)
                }
                is LookupResult.NotFound -> {
                    if (selfWord != lastSelfWord) return@launch
                    repository.recordLookup(selfWord, emptyList())
                    showGameBubble(selfWord, null, emptyList(), getString(R.string.not_in_db))
                    sendLookupBroadcast(selfWord, null, emptyList())
                }
                LookupResult.NotInGame -> performHide()
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopContinuousScan()
        cancelPendingHide()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CONTINUOUS_POLL_MS = 20L
        private const val HIDE_DEBOUNCE_MS = 800L
        private const val READY_CLICK_COOLDOWN_MS = 300L
        private const val SIT_CLICK_COOLDOWN_MS = 1000L
        private const val SIT_SKIP_LOG_INTERVAL_MS = 3000L
        private const val VOTE_TAP_COOLDOWN_MS = 120L
        private const val VOTE_SKIP_LOG_INTERVAL_MS = 3000L
        private const val VOTE_FINISH_MISS_STREAK = 3
        private const val TAG = "PhaoHN-Vote"
        private const val SIT_TAG = "PhaoHN-Sit"
        private const val DIALOG_TAG = "PhaoHN-Dialog"

        const val ACTION_PAIRS_UPDATED = "com.phaohn.spyhelper.PAIRS_UPDATED"
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