package dev.herdr.android

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : FragmentActivity() {
    lateinit var biometricUnlock: BiometricUnlock
        private set

    override fun onStop() {
        biometricUnlock.cancel()
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        biometricUnlock = BiometricUnlock(this)
        enableEdgeToEdge()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HerdrViewModel(ServerStore(applicationContext), PasswordStore(applicationContext)) as T
        }
        setContent {
            val model: HerdrViewModel = viewModel(factory = factory)
            val settings by model.settings.collectAsStateWithLifecycle()
            val settingsReady by model.settingsReady.collectAsStateWithLifecycle()
            if (!settingsReady) return@setContent
            val dark = settings.theme == "dark" || settings.theme == "system" && isSystemInDarkTheme()
            HerdrTheme(dark) {
                val background = MaterialTheme.colorScheme.surfaceContainer.toArgb()
                SideEffect {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(background, background) { dark },
                    )
                }
                HerdrApp(model)
            }
        }
    }
}
