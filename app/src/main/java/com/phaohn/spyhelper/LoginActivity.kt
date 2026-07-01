package com.phaohn.spyhelper

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.phaohn.spyhelper.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: AuthManager
    private lateinit var repository: WordRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = AuthManager(this)
        repository = PhaoHNApp.repo(application)

        val lockMessage = intent.getStringExtra(AccountLockHandler.EXTRA_LOCK_MESSAGE)
        if (auth.isLoggedIn()) {
            setContentView(R.layout.activity_boot)
            lifecycleScope.launch {
                try {
                    auth.verifySession()
                    goMain()
                } catch (e: AccountLockedException) {
                    auth.clearSession()
                    openLoginForm(e.lockMessage)
                } catch (_: AuthException) {
                    auth.clearSession()
                    openLoginForm(lockMessage)
                } catch (_: Exception) {
                    openLoginForm(lockMessage)
                }
            }
            return
        }
        openLoginForm(lockMessage)
    }

    private fun openLoginForm(lockMessage: String?) {
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnLogin.setOnClickListener { attemptLogin() }
        if (!lockMessage.isNullOrBlank()) {
            showError(lockMessage)
        }
    }

    private fun attemptLogin() {
        val username = binding.inputUsername.text?.toString().orEmpty()
        val password = binding.inputPassword.text?.toString().orEmpty()
        if (username.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.login_empty))
            return
        }
        binding.btnLogin.isEnabled = false
        binding.loginProgress.visibility = View.VISIBLE
        binding.loginError.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val user = auth.login(username, password)
                SpyPrefs.setLastPushUsername(this@LoginActivity, user.username)
                val baseUrl = SpyPrefs.syncBaseUrl(this@LoginActivity)
                val token = auth.getToken()
                repository.mergeFromServerQuiet(baseUrl, token, this@LoginActivity)
                repository.flushPendingPushToServer(this@LoginActivity, baseUrl, token)
                AdminNotificationHelper.pullAfterServerTouch(
                    this@LoginActivity,
                    baseUrl,
                    token,
                    this@LoginActivity,
                )
                goMain()
            } catch (e: AccountLockedException) {
                auth.clearSession()
                showError(e.lockMessage)
            } catch (e: Exception) {
                auth.clearSession()
                val msg = when (e.message) {
                    "invalid_credentials" -> getString(R.string.login_invalid)
                    else -> getString(R.string.login_fail, e.message ?: "")
                }
                showError(msg)
            } finally {
                binding.btnLogin.isEnabled = true
                binding.loginProgress.visibility = View.GONE
            }
        }
    }

    private fun showError(msg: String) {
        binding.loginError.text = msg
        binding.loginError.visibility = View.VISIBLE
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}