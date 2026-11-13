package com.ailm.android

import android.Manifest
import android.content.Intent
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.ailm.android.runtime.StandaloneRuntime
import com.ailm.android.startup.AppBootstrap
import com.ailm.android.ui.navigation.AppNavHost
import com.ailm.android.ui.theme.AsterionColors
import com.ailm.android.ui.theme.AsterionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val contentReady = mutableStateOf(false)
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !contentReady.value }

        super.onCreate(savedInstanceState)
        // Android 13+ hides foreground notifications from the drawer when
        // POST_NOTIFICATIONS is denied, making Pause/Resume/Stop inaccessible.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4208,
            )
        }
        handleTeraBoxCallback(intent)

        setContent {
            AsterionTheme {
                AsterionCoreApp(onComposeVisible = { contentReady.value = true })
            }
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
    LaunchedEffect(bootstrapComplete) {
        if (bootstrapComplete && AppBootstrap.isRuntimeInitialized()) {
            // Do not unpack the 300+ MB embedded ONNX model during splash
            // or compete with the gallery's first Compose/SQLite frame.
            delay(2_000L)
            StandaloneRuntime.beginDeferredBundledModelSetup()
        }
    }
    val intro = remember { Animatable(0f) }
    val sweep = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }

    LaunchedEffect(splashVisible) {
        // The native splash logo and first Compose logo are both fully visible
        // and centered. Never hand off to a fully transparent opening frame.
        onComposeVisible()
        if (!splashVisible) return@LaunchedEffect

        coroutineScope {
            launch {
                intro.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 640,
                        easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f),
                    ),
                )
            }
            launch {
                delay(140)
                sweep.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 940,
                        easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f),
                    ),
                )
            }
        }

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
            PremiumAsterionSplash(
                introProgress = intro.value,
                sweepProgress = sweep.value,
                exitProgress = exit.value,
            )
        }
    }
}

@Composable
private fun PremiumAsterionSplash(
    introProgress: Float,
    sweepProgress: Float,
    exitProgress: Float,
) {
    val overallAlpha = (1f - exitProgress).coerceIn(0f, 1f)
    val wordmarkProgress = ((introProgress - 0.48f) / 0.52f).coerceIn(0f, 1f)
    // Match the Android native mark on the first frame. Its former zero-alpha,
    // rotated, scaled Compose replacement caused a visible black flash/jump.
    val logoScale = 1f + (0.015f * introProgress)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overallAlpha }
            .background(AsterionColors.MatteBlack),
        contentAlignment = Alignment.Center,
    ) {
        // A restrained metallic halo gives depth without turning the mark into
        // a generic neon/glow treatment.
        Box(
            modifier = Modifier
                .size(248.dp)
                .graphicsLayer {
                    alpha = (0.35f * introProgress) * (1f - 0.55f * exitProgress)
                    scaleX = 0.84f + 0.16f * introProgress
                    scaleY = scaleX
                }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AsterionColors.GoldLight.copy(alpha = 0.18f),
                            AsterionColors.Gold.copy(alpha = 0.07f),
                            Color.Transparent,
                        ),
                    ),
                    CircleShape,
                ),
        )

        // Keep the approved gold mark at the same physical center as the
        // Android native splash icon throughout the handoff and animation.
        Box(
            modifier = Modifier.size(196.dp),
            contentAlignment = Alignment.Center,
        ) {
                Canvas(
                    modifier = Modifier
                        .size(190.dp)
                        .graphicsLayer {
                            alpha = (0.80f * introProgress) * (1f - exitProgress)
                        },
                ) {
                    drawCircle(
                        color = AsterionColors.Gold.copy(alpha = 0.13f),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.035f),
                        radius = size.minDimension * 0.45f,
                        style = Stroke(width = 0.75.dp.toPx()),
                    )
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(
                                Color.Transparent,
                                AsterionColors.GoldDark.copy(alpha = 0.10f),
                                AsterionColors.GoldLight.copy(alpha = 0.88f),
                                AsterionColors.Gold.copy(alpha = 0.24f),
                                Color.Transparent,
                            ),
                        ),
                        startAngle = -128f + (sweepProgress * 265f),
                        sweepAngle = 78f,
                        useCenter = false,
                        style = Stroke(
                            width = 1.45.dp.toPx(),
                            cap = StrokeCap.Round,
                        ),
                    )
                }

                Image(
                    painter = painterResource(id = R.drawable.asterioncore_logo_splash),
                    contentDescription = "Asterion Core",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(146.dp)
                        .graphicsLayer {
                            scaleX = logoScale
                            scaleY = logoScale
                            alpha = 1f
                        }
                        .clip(CircleShape),
                )
        }

        // The wordmark enters underneath, without moving the logo out of its
        // native-splash position during the transition.
        Column(
            modifier = Modifier.align(Alignment.Center).offset(y = 145.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "ASTERION CORE",
                color = AsterionColors.Text.copy(alpha = 0.94f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 4.2.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = wordmarkProgress
                    translationY = 8f * (1f - wordmarkProgress)
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .width((34f * wordmarkProgress).dp)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                AsterionColors.GoldLight.copy(alpha = 0.72f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
    }
}
