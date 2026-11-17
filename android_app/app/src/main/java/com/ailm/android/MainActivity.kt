package com.ailm.android

import android.Manifest
import android.content.Intent
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.ailm.android.runtime.StandaloneRuntime
import com.ailm.android.startup.AppBootstrap
import com.ailm.android.ui.navigation.AppNavHost
import com.ailm.android.ui.theme.AsterionColors
import com.ailm.android.ui.theme.AsterionTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val contentReady = mutableStateOf(false)
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !contentReady.value }

        super.onCreate(savedInstanceState)
        // Android permission dialogs must never interrupt the first branded
        // frame. Notification permission is requested only when the owner
        // starts automation from the gallery.
        handleTeraBoxCallback(intent)

        setContent {
            AsterionTheme {
                AsterionCoreApp(onComposeVisible = { contentReady.value = true })
            }
        }

        // The window must render before expensive Fusion/SQLite initialization
        // is allowed to compete for the first Android launch frame.
        lifecycleScope.launch {
            delay(450L)
            AppBootstrap.initializeApplication(applicationContext)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            // Native ONNX sessions are large. Evict only on actual foreground
            // memory pressure and never block a Compose frame on the session
            // lock while an inference is still executing.
            lifecycleScope.launch(Dispatchers.IO) {
                com.ailm.android.runtime.ai.OnnxRuntimeClient.releaseTaggerSession()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTeraBoxCallback(intent)
    }

    private fun handleTeraBoxCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!uri.scheme.equals("asterioncore", ignoreCase = true) ||
            !uri.host.equals("teraboxOauth", ignoreCase = true)
        ) {
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            StandaloneRuntime.initialize(applicationContext)
            StandaloneRuntime.completeTeraBoxAuthorization(uri)
        }
    }
}

@Composable
private fun AsterionCoreApp(onComposeVisible: () -> Unit) {
    // An Activity recreation must not replay the opening animation on top of
    // an already visible gallery; rememberSaveable keeps the visual handoff.
    var splashVisible by rememberSaveable { mutableStateOf(true) }
    // A previous release composed the entire navigation tree while
    // StandaloneRuntime's SQLite/Fusion collaborators were still uninitialized.
    // The visual splash may animate, but no screen is allowed to query the
    // runtime until its initialization has actually completed.
    var bootstrapComplete by remember {
        mutableStateOf(AppBootstrap.isInitializationCompleted())
    }
    LaunchedEffect(Unit) {
        AppBootstrap.awaitInitialization()
        bootstrapComplete = true
    }
    LaunchedEffect(bootstrapComplete, splashVisible) {
        if (bootstrapComplete && !splashVisible && AppBootstrap.isRuntimeInitialized()) {
            // The bundled model stays available, but decompression/install
            // must not race the opening animation or gallery's first frames.
            delay(8_000L)
            try {
                withContext(Dispatchers.IO) {
                    StandaloneRuntime.beginDeferredBundledModelSetup()
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.e("AsterionStartup", "Deferred model setup failed; gallery retained.", failure)
            }
        }
    }
    val exit = remember { Animatable(0f) }

    LaunchedEffect(splashVisible) {
        // The native splash logo and first Compose logo are both fully visible
        // and centered. Never hand off to a fully transparent opening frame.
        onComposeVisible()
        if (!splashVisible) return@LaunchedEffect

        // Hold the exact native-system splash logo at the same size and center.
        // The only motion is a restrained fade; no added rings or wordmark.
        delay(1_080L)

        // Prevent the branded opening from vanishing into a blank gallery
        // during a slow but safe pre-existing Fusion runtime initialization.
        // A slow initialization remains visible as an explicit loading state
        // after this bounded splash window rather than freezing the animation.
        withTimeoutOrNull(450L) { AppBootstrap.awaitInitialization() }
        delay(180)
        exit.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 300,
                easing = CubicBezierEasing(0.4f, 0f, 1f, 1f),
            ),
        )
        splashVisible = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AsterionColors.MatteBlack,
        ) {
            when {
                // Never compose the heavy, database-backed gallery underneath
                // the opening animation. Rendering both at once caused a
                // visible handoff stutter and spiked first-frame memory.
                splashVisible -> Box(modifier = Modifier.fillMaxSize())
                bootstrapComplete && AppBootstrap.isRuntimeInitialized() -> AppNavHost()
                bootstrapComplete -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Gallery initialization could not finish. Your " +
                            "existing Fusion database was not reset. " +
                            (AppBootstrap.initializationFailure() ?: "See startup diagnostics."),
                        color = AsterionColors.Text,
                        fontSize = 14.sp,
                    )
                }
                else -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Opening your gallery",
                        color = AsterionColors.Text,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        if (splashVisible) {
            PremiumAsterionSplash(exitProgress = exit.value)
        }
    }
}

@Composable
private fun PremiumAsterionSplash(exitProgress: Float) {
    // Android's system splash renders this padded 768px raster in a 288dp icon
    // viewport. Use that exact same raster and viewport in Compose to avoid
    // a size/position jump at the native-to-Compose handoff. The original
    // AsterionCore-Logo.png artwork remains unmodified.
    val overallAlpha = (1f - exitProgress).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AsterionColors.MatteBlack),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.asterioncore_logo_splash),
            contentDescription = "Asterion Core",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(288.dp)
                .graphicsLayer { alpha = overallAlpha },
        )
    }
}
