package com.ailm.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ailm.android.ui.screens.ScreenScaffold
import com.ailm.android.ui.components.GalleryGlyph
import com.ailm.android.ui.components.GalleryLineIcon
import com.ailm.android.ui.viewmodel.AppViewModel

private val primaryDestinations = listOf(
    AppDestination.Dashboard,
    AppDestination.Collections,
    AppDestination.Automation,
    AppDestination.ReviewQueue,
)

/**
 * Samsung Gallery-like image-first navigation. The gallery renders behind
 * this translucent dock, while utility pages leave enough safe interaction
 * space at the bottom. No reserved tall NavigationBar or duplicated insets.
 */
@Composable
fun AppNavHost(appViewModel: AppViewModel = viewModel()) {
    val navController = rememberNavController()
    val entry = navController.currentBackStackEntryAsState().value
    val activeRoute = entry?.destination?.route.orEmpty()
    val inGallery = activeRoute == AppDestination.LibraryBrowser.route ||
        activeRoute == AppDestination.Dashboard.route
    val showDock = inGallery || primaryDestinations.any { it.route == activeRoute }

    fun navigate(next: AppDestination) {
        if (activeRoute == next.route) return
        navController.navigate(next.route) {
            launchSingleTop = true
            if (next in primaryDestinations) {
                popUpTo(AppDestination.Dashboard.route) { saveState = true }
                restoreState = true
            }
        }
    }

    // The photo grid may remain immersive, but utility pages, Review and the
    // artwork editor must not render text underneath Samsung's status icons.
    Box(
        Modifier.fillMaxSize().then(
            if (inGallery) Modifier else Modifier.statusBarsPadding()
        )
    ) {
        NavHost(
            navController = navController,
            startDestination = AppDestination.Dashboard.route,
            modifier = Modifier.fillMaxSize().padding(
                // Dock height + Android gesture/navigation inset + margin.
                // The old 58.dp reservation let Review controls render behind
                // the translucent 49.dp dock on Samsung gesture navigation.
                bottom = if (showDock && !inGallery) 100.dp else 0.dp,
            ),
        ) {
            AppDestination.entries.forEach { destination ->
                composable(destination.route) {
                    ScreenScaffold(
                        destination = destination,
                        onNavigate = ::navigate,
                        appViewModel = appViewModel,
                    )
                }
            }
        }

        if (showDock) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .fillMaxWidth(0.70f)
                    .widthIn(max = 360.dp)
                    .shadow(3.dp, RoundedCornerShape(30.dp))
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color(0xB127262C))
                    .height(49.dp)
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val labels = listOf("Gallery", "Collections", "Organize", "Review")
                val glyphs = listOf(
                    GalleryGlyph.Pictures, GalleryGlyph.Collections,
                    GalleryGlyph.Organize, GalleryGlyph.Review,
                )
                primaryDestinations.forEachIndexed { index, destination ->
                    val selected = if (destination == AppDestination.Dashboard) inGallery
                        else activeRoute == destination.route
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(27.dp))
                            .background(if (selected) Color(0x845C5961) else Color.Transparent)
                            .clickable { navigate(destination) }
                            .padding(vertical = 1.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        GalleryLineIcon(
                            glyph = glyphs[index],
                            tint = if (selected) Color.White else Color(0xFFE3E0DB),
                            modifier = Modifier.height(18.dp).widthIn(min = 18.dp, max = 18.dp),
                        )
                        Text(
                            labels[index],
                            fontSize = 9.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) Color.White else Color(0xFFE3E0DB),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
