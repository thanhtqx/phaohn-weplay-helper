package com.phaohn.spyhelper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    private var seatedInLobby = false
    private var voteRoundActive = false
    private var voteTappedThisRound = false
    private var lastVoteTapAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = PhaoHNApp.repo(application)
        ensureOverlayService()
        scope.launch { repository.warmCache() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName != WePlayIds.PACKAGE) {
            seatedInLobby = false
            resetVoteState()
            if (bubbleVisible) requestHide()
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = SystemClock.uptimeMillis()
            val fastVoteScan = voteRoundActive ||
                (SpyPrefs.isAutoVoteEnabled(applicationContext) && lastSelfWord != null)
            val fastLobbyScan = !seatedInLobby && SpyPrefs.isAutoSitEnabled(applicationContext)
            val interval = when {
                fastVoteScan -> VOTE_SCAN_INTERVAL_MS
                fastLobbyScan -> LOBBY_SCAN_INTERVAL_MS
                else -> SCAN_INTERVAL_MS
            }
            if (now - lastScanAt < interval) return
            lastScanAt = now
        }
        val root = rootInActiveWindow ?: return
        try {
            scanTree(root)
        } finally {
            root.recycle()
        }
    }

    private fun scanTree(root: AccessibilityNodeInfo) {
        if (root.packageName?.toString() != WePlayIds.PACKAGE) {
            seatedInLobby = false
            resetVoteState()
            if (bubbleVisible) requestHide()
            return
        }

        val civilian = textById(root, WePlayIds.CIVILIAN)
        val spy = textById(root, WePlayIds.SPY)
        if (!civilian.isNullOrEmpty() && !spy.isNullOrEmpty()) {
            resetVoteState()
            maybeSavePair(civilian, spy)
            performHide()
            return
        }

        val selfWord = WordParser.parseSelfWord(textById(root, WePlayIds.SELF_WORD))
        if (selfWord != null) {
            seatedInLobby = true
            cancelPendingHide()
            maybeAutoVote(root)
            maybeLookup(selfWord)
        } else {
            resetVoteState()
            seatedInLobby = WePlaySeatHelper.isUserSeated(root)
            maybeAutoSit(root)
            maybeAutoReady(root)
            if (bubbleVisible) requestHide()
        }
    }

    private fun maybeAutoSit(root: AccessibilityNodeInfo) {
        if (!SpyPrefs.isAutoSitEnabled(applicationContext)) return
        if (WePlaySeatHelper.isUserSeated(root)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSitClickAt < SIT_CLICK_COOLDOWN_MS) return
        if (WePlaySeatHelper.tapFirstEmptySeat(this, root)) {
            lastSitClickAt = now
        }
    }

    private fun resetVoteState() {
        voteRoundActive = false
        voteTappedThisRound = false
    }

    private fun maybeAutoVote(root: AccessibilityNodeInfo) {
        if (!SpyPrefs.isAutoVoteEnabled(applicationContext)) return
        val judge = WePlayVoteHelper.latestJudgeLine(root)
        val voting = WePlayVoteHelper.isVotingPhase(root, judge)
        if (!voting) {
            if (voteRoundActive && WePlayVoteHelper.voteEnded(judge)) {
                voteTappedThisRound = false
            }
            voteRoundActive = false
            return
        }
        voteRoundActive = true
        if (voteTappedThisRound) return
        val timerSec = WePlayVoteHelper.parseTimerSeconds(textById(root, WePlayIds.TIMER_TV)) ?: return
        if (timerSec != 0) return
        val now = SystemClock.uptimeMillis()
        if (now - lastVoteTapAt < VOTE_TAP_COOLDOWN_MS) return
        val seat = SpyPrefs.voteTargetSeat(applicationContext)
        if (WePlaySeatHelper.tapSeatNumber(this, root, seat)) {
            lastVoteTapAt = now
            voteTappedThisRound = true
        }
    }

    private fun maybeAutoReady(root: AccessibilityNodeInfo) {
        if (!SpyPrefs.isAutoReadyEnabled(applicationContext)) return
        val nodes = root.findAccessibilityNodeInfosByViewId(WePlayIds.READY_BTN)
        try {
            val node = nodes.firstOrNull() ?: return
            val label = node.text?.toString().orEmpty()
            if (!label.contains(WePlayIds.READY_LABEL, ignoreCase = true)) return
            if (!node.isClickable || !node.isEnabled || !node.isVisibleToUser) return
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

    private fun maybeSavePair(civilian: String, spy: String) {
        if (civilian == lastCivilian && spy == lastSpy) return
        lastCivilian = civilian
        lastSpy = spy
        scope.launch {
            val saved = repository.savePair(civilian, spy)
            if (saved) {
                sendBroadcast(Intent(ACTION_PAIRS_UPDATED).setPackage(packageName))
            }
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
        cancelPendingHide()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SCAN_INTERVAL_MS = 50L
        private const val HIDE_DEBOUNCE_MS = 800L
        private const val READY_CLICK_COOLDOWN_MS = 400L
        private const val SIT_CLICK_COOLDOWN_MS = 200L
        private const val VOTE_TAP_COOLDOWN_MS = 120L
        private const val LOBBY_SCAN_INTERVAL_MS = 25L
        private const val VOTE_SCAN_INTERVAL_MS = 25L

        const val ACTION_PAIRS_UPDATED = "com.phaohn.spyhelper.PAIRS_UPDATED"
        const val ACTION_LOOKUP = "com.phaohn.spyhelper.LOOKUP"
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