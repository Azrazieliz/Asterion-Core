package com.ailm.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ailm.android.ui.screens.ScreenScaffold
import com.ailm.android.ui.viewmodel.AppViewModel

@Composable
fun AppNavHost(appViewModel: AppViewModel = viewModel()) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = AppDestination.Dashboard.route) {
        AppDestination.entries.forEach { destination ->
            composable(destination.route) {
                ScreenScaffold(
                    destination = destination,
                    onNavigate = { next ->
                        val primary = next in setOf(
                            AppDestination.Dashboard, AppDestination.LibraryBrowser,
                            AppDestination.Collections, AppDestination.Automation,
                            AppDestination.ReviewQueue,
                        )
                        navController.navigate(next.route) {
                            launchSingleTop = true
                            if (primary) {
                                popUpTo(AppDestination.Dashboard.route) { saveState = true }
                                restoreState = true
                            }
                        }
                    },
                    appViewModel = appViewModel,
                )
            }
        }
    }
}
