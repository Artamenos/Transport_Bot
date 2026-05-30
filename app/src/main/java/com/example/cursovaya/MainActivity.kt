package com.example.cursovaya

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.cursovaya.BuildConfig
import com.example.cursovaya.data.local.AppPrefs
import com.example.cursovaya.data.network.ApiClient
import com.example.cursovaya.data.repository.AuthRepository
import com.example.cursovaya.databinding.ActivityMainBinding
import com.example.cursovaya.ui.auth.LoginFragment
import com.example.cursovaya.ui.auth.RegisterFragment
import com.example.cursovaya.ui.chat.ChatFragment
import com.example.cursovaya.ui.search.SearchFragment

class MainActivity : AppCompatActivity(), AppNavigator {
    private lateinit var binding: ActivityMainBinding
    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = AppPrefs(applicationContext)
        val savedUrl = prefs.serverUrl()
        try {
            ApiClient.initWithBaseUrl(savedUrl)
        } catch (error: IllegalArgumentException) {
            // Fallback на базовый URL, если сохранен некорректный адрес
            prefs.saveServerUrl(BuildConfig.BASE_URL)
            ApiClient.initWithBaseUrl(BuildConfig.BASE_URL)
        }

        authRepository = AuthRepository(applicationContext)
        if (savedInstanceState == null) {
            if (authRepository.isLoggedIn()) openSearch() else openLogin()
        }
    }

    override fun openLogin() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, LoginFragment())
            .commit()
    }

    override fun openRegister() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, RegisterFragment())
            .commit()
    }

    override fun openSearch() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, SearchFragment())
            .commit()
    }

    override fun openChat() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, ChatFragment())
            .commit()
    }
}