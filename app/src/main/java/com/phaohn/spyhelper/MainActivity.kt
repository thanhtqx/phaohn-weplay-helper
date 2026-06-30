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
import com.phaohn.spyhelper.databinding.ActivityMainBinding
import com.phaohn.spyhelper.databinding.ItemBottomNavBinding

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

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SpyAccessibilityService.ACTION_PAIRS_UPDATED -> {
                    wordsHubFragment?.refreshWordList()
                    refreshHomeUi()
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        setupBottomNavInsets()

        selectedNavId = savedInstanceState?.getInt(KEY_SELECTED_NAV, R.id.nav_home) ?: R.id.nav_home
        setupBottomNav()

        if (savedInstanceState == null) {
            homeFragment = HomeFragment.newInstance()
            onNavSelected(R.id.nav_home)
        } else {
            updateNavVisuals(selectedNavId)
            updateToolbarTitle(selectedNavId)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission()
        }
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

    private fun onNavSelected(itemId: Int) {
        selectedNavId = itemId
        updateNavVisuals(itemId)
        updateToolbarTitle(itemId)
        when (itemId) {
            R.id.nav_home -> showFragment(
                homeFragment ?: HomeFragment.newInstance().also { homeFragment = it },
                R.string.nav_home,
            )
            R.id.nav_words -> showFragment(
                wordsHubFragment ?: WordsHubFragment.newInstance().also { wordsHubFragment = it },
                R.string.nav_words,
            )
            R.id.nav_auto -> showFragment(
                autoFragment ?: AutoFragment.newInstance().also { autoFragment = it },
                R.string.nav_auto,
            )
            R.id.nav_history -> showFragment(
                historyFragment ?: HistoryFragment.newInstance().also { historyFragment = it },
                R.string.nav_history,
            )
            R.id.nav_about -> showFragment(
                profileFragment ?: ProfileFragment.newInstance().also { profileFragment = it },
                R.string.nav_profile,
            )
        }
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

    private fun updateToolbarTitle(itemId: Int) {
        val titleRes = when (itemId) {
            R.id.nav_home -> R.string.nav_home
            R.id.nav_words -> R.string.nav_words
            R.id.nav_auto -> R.string.nav_auto
            R.id.nav_history -> R.string.nav_history
            R.id.nav_about -> R.string.nav_profile
            else -> R.string.nav_home
        }
        binding.toolbar.title = getString(titleRes)
    }

    private fun showFragment(fragment: Fragment, @Suppress("UNUSED_PARAMETER") titleRes: Int) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
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
        refreshHomeUi()
        wordsHubFragment?.refreshWordList()
        autoFragment?.refreshAutoUi()
        historyFragment?.loadHistory()
    }

    override fun onPause() {
        unregisterReceiver(receiver)
        super.onPause()
    }

    fun refreshHomeUi() {
        homeFragment?.refreshDashboard()
    }

    private fun updateLookup(intent: Intent) {
        val word = intent.getStringExtra(SpyAccessibilityService.EXTRA_MY_WORD).orEmpty()
        if (word.isEmpty()) return
        historyFragment?.loadHistory()
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
    }
}