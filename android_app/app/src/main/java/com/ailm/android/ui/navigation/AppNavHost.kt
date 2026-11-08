package com.ailm.android.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

@Composable
fun AppNavHost(appViewModel: AppViewModel = viewModel()) {
    val navController = rememberNavController()
    val entry = navController.currentBackStackEntryAsState().value
    val activeRoute = entry?.destination?.route.orEmpty()
    val inGallery = activeRoute == AppDestination.LibraryBrowser.route ||
        activeRoute == AppDestination.Dashboard.route

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

    Scaffold(
        bottomBar = {
            if (inGallery || primaryDestinations.any { it.route == activeRoute }) {
                NavigationBar {
                    val labels = listOf("Gallery", "Collections", "Organize", "Review")
                    val glyphs = listOf("▦", "▤", "◷", "✓")
                    primaryDestinations.forEachIndexed { index, destination ->
                        NavigationBarItem(
                            selected = if (destination == AppDestination.Dashboard) inGallery
                                else activeRoute == destination.route,
                            onClick = { navigate(destination) },
                            icon = {
                                Text(
                                    glyphs[index],
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            },
                            label = { Text(labels[index]) },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Dashboard.route,
            modifier = Modifier.padding(innerPadding),
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
    }
}
