package com.ailm.android

import android.Manifest
import android.content.Intent
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
import com.ailm.android.ui.navigation.AppNavHost
import com.ailm.android.ui.theme.AsterionColors
import com.ailm.android.ui.theme.AsterionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var splashVisible by remember { mutableStateOf(true) }
    val intro = remember { Animatable(0f) }
    val sweep = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // The custom premium surface is present on the first Compose frame, so
        // Android's native splash can hand off without exposing another color.
        onComposeVisible()

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

        delay(360)
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
            AppNavHost()
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
    val logoScale = 0.90f + (0.10f * introProgress) + (0.012f * exitProgress)
    val logoRotation = -3.2f * (1f - introProgress)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overallAlpha }
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF15110B),
                        Color(0xFF0D0C0A),
                        AsterionColors.MatteBlack,
                        AsterionColors.MatteBlack,
                    ),
                ),
            ),
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

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                            rotationZ = logoRotation
                            alpha = introProgress.coerceIn(0f, 1f)
                        }
                        .clip(CircleShape),
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

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
