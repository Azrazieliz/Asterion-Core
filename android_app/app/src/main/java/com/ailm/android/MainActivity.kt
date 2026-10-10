package com.ailm.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Surface
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ailm.android.ui.navigation.AppNavHost
import com.ailm.android.ui.theme.AsterionTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val composeFirstFrameRendered = mutableStateOf(false)
        val platformSplash = installSplashScreen()
        // Do not dismiss the Android 12+ platform splash before Compose
        // has rendered the first full-screen branded frame.
        platformSplash.setKeepOnScreenCondition { !composeFirstFrameRendered.value }

        super.onCreate(savedInstanceState)
        setContent {
            AsterionTheme {
                AsterionCoreApp(
                    onFirstFrame = { composeFirstFrameRendered.value = true },
                )
            }
        }
    }
}

/** Branded introduction, independent of the initial library/setup state. */
@Composable
private fun AsterionCoreApp(onFirstFrame: () -> Unit) {
    // Must start VISIBLE: starting transparent and enabling only after a frame
    // caused the introduction to disappear on some Android startup paths.
    var showIntro by remember { mutableStateOf(true) }
    val imagePainter = painterResource(R.drawable.asterioncore_splash)
    val introTransition = rememberInfiniteTransition(label = "asterionOpening")
    val imageScale by introTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.045f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "openingImageScale",
    )
    val introAlpha by animateFloatAsState(
        targetValue = if (showIntro) 1f else 0f,
        animationSpec = tween(durationMillis = 650, easing = LinearEasing),
        label = "openingFade",
    )

    LaunchedEffect(Unit) {
        // Wait for a composed frame instead of releasing the native splash
        // before the first visible branded frame can be drawn.
        withFrameNanos { onFirstFrame() }
        delay(2400)
        showIntro = false
    }

    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize()) {
            AppNavHost()
        }
        if (showIntro || introAlpha > 0f) {
            Box(
                Modifier.fillMaxSize().background(Color(0xFF080808)).alpha(introAlpha),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = imagePainter,
                    contentDescription = "Asterion Core opening artwork",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().scale(imageScale),
                )
            }
        }
    }
}
