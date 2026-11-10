package com.ailm.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.weight
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

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = AppDestination.Dashboard.route,
            modifier = Modifier.fillMaxSize().padding(
                bottom = if (showDock && !inGallery) 78.dp else 0.dp,
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
                    .padding(horizontal = 20.dp, vertical = 7.dp)
                    .widthIn(max = 456.dp)
                    .shadow(10.dp, RoundedCornerShape(38.dp))
                    .clip(RoundedCornerShape(38.dp))
                    .background(Color(0xEF27262B))
                    .height(66.dp)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val labels = listOf("Gallery", "Collections", "Organize", "Review")
                val glyphs = listOf("▦", "▤", "◷", "✓")
                primaryDestinations.forEachIndexed { index, destination ->
                    val selected = if (destination == AppDestination.Dashboard) inGallery
                        else activeRoute == destination.route
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(32.dp))
                            .background(if (selected) Color(0xFF4A484E) else Color.Transparent)
                            .clickable { navigate(destination) }
                            .padding(vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            glyphs[index],
                            color = if (selected) Color.White else Color(0xFFE3E0DB),
                            fontSize = 20.sp,
                        )
                        Text(
                            labels[index],
                            fontSize = 11.sp,
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
