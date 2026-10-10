package com.ailm.android.ui.navigation

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ailm.android.ui.screens.ScreenScaffold
import com.ailm.android.ui.viewmodel.AppViewModel

@Composable
fun AppNavHost(appViewModel: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val initialDestination = remember(context) {
        val prefs = context.getSharedPreferences("ailm_android", Context.MODE_PRIVATE)
        val selectedTree = prefs.getString("library_tree_uri", null).orEmpty()
        val authorized = selectedTree.isNotBlank() && context.contentResolver.persistedUriPermissions.any {
            it.uri == Uri.parse(selectedTree) && it.isReadPermission && it.isWritePermission
        }
        if (authorized) AppDestination.Dashboard else AppDestination.FirstLaunchWizard
    }
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = initialDestination.route) {
        AppDestination.entries.forEach { destination ->
            composable(destination.route) {
                ScreenScaffold(
                    destination = destination,
                    onNavigate = { next ->
                        if (navController.currentDestination?.route != next.route) {
                            navController.navigate(next.route) { launchSingleTop = true }
                        }
                    },
                    appViewModel = appViewModel,
                )
            }
        }
    }
}
