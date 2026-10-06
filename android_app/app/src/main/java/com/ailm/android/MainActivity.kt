package com.ailm.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ailm.android.ui.navigation.AppNavHost
import com.ailm.android.ui.theme.AsterionTheme
import androidx.compose.material3.Surface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        captureIncomingTeraBoxShare(intent)

        setContent {
            AsterionTheme {
                Surface {
                    AppNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureIncomingTeraBoxShare(intent)
    }

    private fun captureIncomingTeraBoxShare(intent: Intent?) {
        if (intent == null) return
        val candidate = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim().orEmpty()

        val link = TERABOX_LINK_REGEX.find(candidate)?.value.orEmpty()
        if (link.isBlank()) return

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_PENDING_TERABOX_SHARE_LINK, link)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ailm_android"
        const val PREF_PENDING_TERABOX_SHARE_LINK = "pending_terabox_share_link"
        private val TERABOX_LINK_REGEX = Regex(
            """https://(?:www\.)?(?:terabox\.com|terabox\.app|1024tera\.com)/[^\s]+""",
            RegexOption.IGNORE_CASE,
        )
    }
}
