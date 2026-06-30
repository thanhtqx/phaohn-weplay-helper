package com.phaohn.spyhelper

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
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
        if (auth.isLoggedIn()) {
            goMain()
            return
        }
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = PhaoHNApp.repo(application)

        binding.btnLogin.setOnClickListener { attemptLogin() }
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
                auth.login(username, password)
                try {
                    repository.pullServerWords(
                        SpyPrefs.syncBaseUrl(this@LoginActivity),
                        auth.getToken(),
                    )
                } catch (e: Exception) {
                    auth.clearSession()
                    showError(getString(R.string.login_sync_fail, e.message ?: ""))
                    return@launch
                }
                goMain()
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