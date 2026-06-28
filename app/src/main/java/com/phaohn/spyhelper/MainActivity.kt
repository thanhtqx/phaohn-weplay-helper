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
import androidx.fragment.app.Fragment
import com.phaohn.spyhelper.databinding.ActivityMainBinding
import com.phaohn.spyhelper.databinding.ItemBottomNavBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navTabs: List<NavTab>

    private var homeFragment: HomeFragment? = null
    private var lookupFragment: LookupFragment? = null
    private var wordListFragment: WordListFragment? = null
    private var historyFragment: HistoryFragment? = null
    private var aboutFragment: AboutFragment? = null
    private var selectedNavId = R.id.nav_home

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SpyAccessibilityService.ACTION_PAIRS_UPDATED -> {
                    wordListFragment?.loadPairs()
                    refreshHomeUi()
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

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

    private fun setupBottomNav() {
        navTabs = listOf(
            NavTab(R.id.nav_home, binding.navHome, R.drawable.ic_nav_home_on, R.drawable.ic_nav_home_off, R.string.nav_home),
            NavTab(R.id.nav_words, binding.navWords, R.drawable.ic_nav_words_on, R.drawable.ic_nav_words_off, R.string.nav_words),
            NavTab(R.id.nav_lookup, binding.navLookup, R.drawable.ic_nav_lookup_on, R.drawable.ic_nav_lookup_off, R.string.nav_lookup),
            NavTab(R.id.nav_history, binding.navHistory, R.drawable.ic_nav_history_on, R.drawable.ic_nav_history_off, R.string.nav_history),
            NavTab(R.id.nav_about, binding.navAbout, R.drawable.ic_nav_about_on, R.drawable.ic_nav_about_off, R.string.nav_about),
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
            R.id.nav_lookup -> showFragment(
                lookupFragment ?: LookupFragment.newInstance().also { lookupFragment = it },
                R.string.nav_lookup,
            )
            R.id.nav_words -> showFragment(
                wordListFragment ?: WordListFragment.newInstance().also { wordListFragment = it },
                R.string.nav_words,
            )
            R.id.nav_history -> showFragment(
                historyFragment ?: HistoryFragment.newInstance().also { historyFragment = it },
                R.string.nav_history,
            )
            R.id.nav_about -> showFragment(
                aboutFragment ?: AboutFragment.newInstance().also { aboutFragment = it },
                R.string.nav_about,
            )
        }
    }

    private fun updateNavVisuals(itemId: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.nav_active)
        val inactiveColor = ContextCompat.getColor(this, R.color.nav_inactive)
        val liftPx = resources.getDimension(R.dimen.bottom_nav_active_lift)
        navTabs.forEach { tab ->
            val selected = tab.itemId == itemId
            tab.binding.navActiveBg.visibility = if (selected) View.VISIBLE else View.GONE
            tab.binding.navContent.translationY = if (selected) liftPx else 0f
            tab.binding.navIcon.setImageResource(if (selected) tab.iconOn else tab.iconOff)
            tab.binding.navLabel.setTextColor(if (selected) activeColor else inactiveColor)
            tab.binding.navLabel.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun updateToolbarTitle(itemId: Int) {
        val titleRes = when (itemId) {
            R.id.nav_home -> R.string.nav_home
            R.id.nav_words -> R.string.nav_words
            R.id.nav_lookup -> R.string.nav_lookup
            R.id.nav_history -> R.string.nav_history
            R.id.nav_about -> R.string.nav_about
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
            addAction(SpyAccessibilityService.ACTION_LOOKUP)

        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        refreshHomeUi()
        wordListFragment?.loadPairs()
        historyFragment?.loadHistory()
    }

    override fun onPause() {
        unregisterReceiver(receiver)
        super.onPause()
    }

    fun refreshHomeUi() {
        homeFragment?.refreshDashboard()
    }

    private fun updateLookup(@Suppress("UNUSED_PARAMETER") intent: Intent) {
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