#!/usr/bin/env python3
"""Crash-regression source gate for the real standalone Android opening path.

This checks the bridge between Application, Compose, and the persistent Fusion
runtime; it cannot replace a real Samsung launch acceptance test.
"""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
src=root/"android_app/app/src/main"
bootstrap=(src/"java/com/ailm/android/startup/AppBootstrap.kt").read_text()
app=(src/"java/com/ailm/android/startup/AilmApplication.kt").read_text()
main=(src/"java/com/ailm/android/MainActivity.kt").read_text()
screens=(src/"java/com/ailm/android/ui/screens/Screens.kt").read_text()
vm=(src/"java/com/ailm/android/ui/viewmodel/AppViewModel.kt").read_text()
gradle=(root/"android_app/app/build.gradle.kts").read_text()

assert "AppBootstrap.initializeApplication(applicationContext)" in app
assert "Executors.newSingleThreadExecutor()" in bootstrap
assert "initializeRuntime(appContext)" in bootstrap
assert 'Log.e("AsterionStartup", "Runtime initialization failed; data preserved.", failure)' in bootstrap
assert "catch (failure: Throwable)" in bootstrap
assert "initializationCompleted = true" in bootstrap
assert "notifyInitializationListeners()" in bootstrap
assert "suspend fun awaitInitialization()" in bootstrap
assert "suspendCancellableCoroutine<Unit>" in bootstrap
assert "continuation.invokeOnCancellation" in bootstrap
assert "if (initializationCompleted)" in bootstrap
assert "if (runtimeInitialized)" in bootstrap

initial_screen = screens.split("fun ScreenScaffold(",1)[1].split("val chooseFolder:",1)[0]
assert "AppBootstrap.awaitInitialization()" in initial_screen
assert "AppBootstrap.awaitInitialization()\n        if (AppBootstrap.isRuntimeInitialized()) {\n            appViewModel.refreshDashboard()" in initial_screen
assert "AppBootstrap.isRuntimeInitialized()" in initial_screen
assert "appViewModel.reportStartupFailure(" in initial_screen
assert "Your existing artwork and " in initial_screen
assert "fun reportStartupFailure(message: String)" in vm
assert "errorMessage = message" in vm
assert "Gallery could not finish opening" in screens
# Native splash -> Compose continuity: icon must not vanish or jump.
assert 'var splashVisible by rememberSaveable' in main
assert 'val logoScale = 1f + (0.015f * introProgress)' in main
assert 'alpha = 1f' in main
assert 'offset(y = 145.dp)' in main
assert '.background(AsterionColors.MatteBlack)' in main
assert 'withTimeoutOrNull(450L) { AppBootstrap.awaitInitialization() }' in main
assert 'logoRotation' not in main
assert 'alpha = introProgress.coerceIn(0f, 1f)' not in main
assert 'reportStartupPreparing()' in initial_screen
assert 'if (state.loading)' in screens
assert 'Opening your gallery' in screens
assert 'fun reportStartupPreparing()' in vm

# Preserve the approved splash/launch handoff, package ID and installed data.
assert "installSplashScreen()" in main
assert "painterResource(id = R.drawable.asterioncore_logo_splash)" in main
assert 'applicationId = "com.ailm.android"' in gradle
# Release number may increase without changing the frozen application identity.
assert 'versionCode = 20043' in gradle
assert 'versionName = "2.0.43"' in gradle
assert not any(x in initial_screen for x in ("deleteDatabase(", "clearApplicationUserData(", "recreateDatabase(", "deleteRecursively("))
assert "postSplashScreenTheme" in (src/"res/values/themes.xml").read_text()
print("PASS: fatal startup isolation, cancellable gallery init gate, preserved original splash and data; device launch still required")
