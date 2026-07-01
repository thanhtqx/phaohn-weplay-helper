package com.phaohn.spyhelper

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phaohn.spyhelper.databinding.ActivityMainBinding
import com.phaohn.spyhelper.databinding.ItemBottomNavBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navTabs: List<NavTab>

    private var homeFragment: HomeFragment? = null
    private var wordsHubFragment: WordsHubFragment? = null
    private var autoFragment: AutoFragment? = null
    private var historyFragment: HistoryFragment? = null
    private var profileFragment: ProfileFragment? = null
    private lateinit var auth: AuthManager
    private var selectedNavId = R.id.nav_home
    private var fragmentsReady = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SpyAccessibilityService.ACTION_PAIRS_UPDATED -> {
                    if (selectedNavId == R.id.nav_words) {
                        wordsHubFragment?.refreshWordList()
                    }
                    if (selectedNavId == R.id.nav_home) {
                        refreshHomeUi()
                    }
                }
                SpyAccessibilityService.ACTION_AUTO_PREFS_CHANGED -> {
                    autoFragment?.refreshAutoUi()
                    OverlayService.refreshAutoUi()
                }
                SpyAccessibilityService.ACTION_LOOKUP -> updateLookup(intent)
            }
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshHomeUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = AuthManager(this)
        if (!auth.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        selectedNavId = savedInstanceState?.getInt(KEY_SELECTED_NAV, R.id.nav_home) ?: R.id.nav_home
        initMainUi(savedInstanceState)
        lifecycleScope.launch {
            try {
                auth.verifySession()
            } catch (e: AccountLockedException) {
                auth.clearSession()
                startActivity(
                    Intent(this@MainActivity, LoginActivity::class.java).apply {
                        putExtra(AccountLockHandler.EXTRA_LOCK_MESSAGE, e.lockMessage)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    },
                )
                finish()
            } catch (_: AuthException) {
                auth.clearSession()
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                finish()
            }
        }
    }

    private fun initMainUi(savedInstanceState: Bundle?) {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth.getUser()?.username?.let { SpyPrefs.setLastPushUsername(this, it) }
        val baseUrl = SpyPrefs.syncBaseUrl(this)
        val token = auth.getToken()
        lifecycleScope.launch {
            val repo = PhaoHNApp.repo(application)
            repo.mergeFromServerQuiet(baseUrl, token, this@MainActivity)
            repo.flushPendingPushToServer(this@MainActivity, baseUrl, token)
            AdminNotificationHelper.pullAfterServerTouch(
                this@MainActivity,
                baseUrl,
                token,
                this@MainActivity,
            )
        }

        setSupportActionBar(binding.toolbar)
        setupBottomNavInsets()
        setupBottomNav()
        ensureFragments()
        showTab(selectedNavId)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission()
        }

        if (BuildConfig.A11Y_DIRECT && savedInstanceState == null && !SpyAccessibilityService.isEnabled(this)) {
            binding.root.post {
                AccessibilitySetupHelper.openAccessibilityServiceDetails(this)
            }
        }

        binding.root.post { maybeShowPermissionsSetup() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_SELECTED_NAV, selectedNavId)
        super.onSaveInstanceState(outState)
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupBottomNavInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavBar) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, 0, 0, navBar.bottom)
            insets
        }
    }

    private fun setupBottomNav() {
        navTabs = listOf(
            NavTab(R.id.nav_home, binding.navHome, R.drawable.ic_nav_home_on, R.drawable.ic_nav_home_off, R.string.nav_home),
            NavTab(R.id.nav_words, binding.navWords, R.drawable.ic_nav_words_on, R.drawable.ic_nav_words_off, R.string.nav_words),
            NavTab(R.id.nav_auto, binding.navAuto, R.drawable.ic_nav_auto_on, R.drawable.ic_nav_auto_off, R.string.nav_auto),
            NavTab(R.id.nav_history, binding.navHistory, R.drawable.ic_nav_history_on, R.drawable.ic_nav_history_off, R.string.nav_history),
            NavTab(R.id.nav_about, binding.navAbout, R.drawable.ic_nav_profile_on, R.drawable.ic_nav_profile_off, R.string.nav_profile),
        )
        navTabs.forEach { tab ->
            tab.binding.navLabel.setText(tab.labelRes)
            val select = View.OnClickListener { onNavSelected(tab.itemId) }
            tab.binding.root.setOnClickListener(select)
            tab.binding.navContent.setOnClickListener(select)
        }
        updateNavVisuals(selectedNavId)
    }

    private fun ensureFragments() {
        if (fragmentsReady) return
        val fm = supportFragmentManager
        homeFragment = fm.findFragmentByTag(TAG_HOME) as? HomeFragment ?: HomeFragment.newInstance()
        wordsHubFragment = fm.findFragmentByTag(TAG_WORDS) as? WordsHubFragment ?: WordsHubFragment.newInstance()
        autoFragment = fm.findFragmentByTag(TAG_AUTO) as? AutoFragment ?: AutoFragment.newInstance()
        historyFragment = fm.findFragmentByTag(TAG_HISTORY) as? HistoryFragment ?: HistoryFragment.newInstance()
        profileFragment = fm.findFragmentByTag(TAG_PROFILE) as? ProfileFragment ?: ProfileFragment.newInstance()

        val tx = fm.beginTransaction().setReorderingAllowed(true)
        if (!homeFragment!!.isAdded) tx.add(R.id.fragmentContainer, homeFragment!!, TAG_HOME)
        if (!wordsHubFragment!!.isAdded) tx.add(R.id.fragmentContainer, wordsHubFragment!!, TAG_WORDS).hide(wordsHubFragment!!)
        if (!autoFragment!!.isAdded) tx.add(R.id.fragmentContainer, autoFragment!!, TAG_AUTO).hide(autoFragment!!)
        if (!historyFragment!!.isAdded) tx.add(R.id.fragmentContainer, historyFragment!!, TAG_HISTORY).hide(historyFragment!!)
        if (!profileFragment!!.isAdded) tx.add(R.id.fragmentContainer, profileFragment!!, TAG_PROFILE).hide(profileFragment!!)
        tx.commit()
        fragmentsReady = true
    }

    private fun onNavSelected(itemId: Int) {
        if (itemId == selectedNavId) return
        selectedNavId = itemId
        updateNavVisuals(itemId)
        updateToolbarTitle(itemId)
        showTab(itemId)
        refreshVisibleTab()
    }

    private fun showTab(itemId: Int) {
        ensureFragments()
        val tx = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        tx.hide(homeFragment!!)
        tx.hide(wordsHubFragment!!)
        tx.hide(autoFragment!!)
        tx.hide(historyFragment!!)
        tx.hide(profileFragment!!)
        when (itemId) {
            R.id.nav_home -> tx.show(homeFragment!!)
            R.id.nav_words -> tx.show(wordsHubFragment!!)
            R.id.nav_auto -> tx.show(autoFragment!!)
            R.id.nav_history -> tx.show(historyFragment!!)
            R.id.nav_about -> tx.show(profileFragment!!)
        }
        tx.commit()
    }

    private fun updateNavVisuals(itemId: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.nav_active)
        val inactiveColor = ContextCompat.getColor(this, R.color.nav_inactive)
        navTabs.forEach { tab ->
            val selected = tab.itemId == itemId
            tab.binding.navActiveBg.visibility = if (selected) View.VISIBLE else View.GONE
            tab.binding.navIcon.setImageResource(if (selected) tab.iconOn else tab.iconOff)
            tab.binding.navLabel.setTextColor(if (selected) activeColor else inactiveColor)
            tab.binding.navLabel.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun updateToolbarTitle(@Suppress("UNUSED_PARAMETER") itemId: Int) {
        binding.toolbar.title = ""
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(SpyAccessibilityService.ACTION_PAIRS_UPDATED)
            addAction(SpyAccessibilityService.ACTION_AUTO_PREFS_CHANGED)
            addAction(SpyAccessibilityService.ACTION_LOOKUP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        refreshVisibleTab()
        maybeShowPermissionsSetup()
    }

    override fun onPause() {
        unregisterReceiver(receiver)
        super.onPause()
    }

    private fun refreshVisibleTab() {
        when (selectedNavId) {
            R.id.nav_home -> refreshHomeUi()
            R.id.nav_words -> wordsHubFragment?.refreshWordList()
            R.id.nav_auto -> autoFragment?.refreshAutoUi()
            R.id.nav_history -> historyFragment?.loadHistory()
        }
    }

    fun refreshHomeUi() {
        homeFragment?.refreshDashboard()
    }

    fun showPermissionsSetup() {
        selectedNavId = R.id.nav_home
        updateNavVisuals(R.id.nav_home)
        showTab(R.id.nav_home)
        PermissionsSetupSheet.show(supportFragmentManager)
    }

    private fun maybeShowPermissionsSetup() {
        if (PermissionHelper.check(this).allGranted) return
        if (supportFragmentManager.findFragmentByTag(PermissionsSetupSheet.TAG) != null) return
        showPermissionsSetup()
    }

    private fun updateLookup(intent: Intent) {
        val word = intent.getStringExtra(SpyAccessibilityService.EXTRA_MY_WORD).orEmpty()
        if (word.isEmpty()) return
        if (selectedNavId == R.id.nav_history) {
            historyFragment?.loadHistory()
        }
    }

    private data class NavTab(
        val itemId: Int,
        val binding: ItemBottomNavBinding,
        val iconOn: Int,
        val iconOff: Int,
        val labelRes: Int,
    )

    companion object {
        private const val KEY_SELECTED_NAV = "selected_nav"
        private const val TAG_HOME = "tab_home"
        private const val TAG_WORDS = "tab_words"
        private const val TAG_AUTO = "tab_auto"
        private const val TAG_HISTORY = "tab_history"
        private const val TAG_PROFILE = "tab_profile"
    }
}