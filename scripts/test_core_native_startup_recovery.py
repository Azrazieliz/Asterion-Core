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
workers=(src/"java/com/ailm/android/workers/SetupWorkers.kt").read_text()

assert "AppBootstrap.initializeApplication(applicationContext)" not in app, (
    "Heavy Fusion must not begin before the Android native launch frame"
)
assert "AppBootstrap.initializeApplication(applicationContext)" in main
assert "delay(450L)" in main
assert "initializationStarted.compareAndSet(false, true)" in bootstrap
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
assert "delay(15_000L)" in workers, "AI workers must not compete with startup presentation"
assert "Deferred AI setup failed; queue retained." in workers

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
assert 'LaunchedEffect(bootstrapComplete, splashVisible)' in main
assert 'bootstrapComplete && !splashVisible && AppBootstrap.isRuntimeInitialized()' in main
assert 'delay(8_000L)' in main
assert 'splashVisible -> Box(modifier = Modifier.fillMaxSize())' in main
assert main.index('splashVisible -> Box(modifier = Modifier.fillMaxSize())') < main.index('bootstrapComplete && AppBootstrap.isRuntimeInitialized() -> AppNavHost()')
assert 'ActivityCompat.requestPermissions(' not in main, "No permission prompt may interrupt native splash"
assert 'ActivityResultContracts.RequestPermission()' in screens
assert 'startAutomationWithPermission(false)' in screens
assert 'startAutomationWithPermission(true)' in screens
assert 'logoRotation' not in main
assert 'alpha = introProgress.coerceIn(0f, 1f)' not in main
assert 'reportStartupPreparing()' in initial_screen
assert 'if (state.loading)' in screens
assert 'Opening your gallery' in screens
assert 'fun reportStartupPreparing()' in vm

# Preserve the approved splash/launch handoff, package ID and installed data.
assert "installSplashScreen()" in main
assert "painterResource(id = R.drawable.asterioncore_logo)" in main
assert "painterResource(id = R.drawable.asterioncore_logo_splash)" not in main
assert 'applicationId = "com.ailm.android"' in gradle
# Release number may increase without changing the frozen application identity.
import re
version_code_match = re.search(r"versionCode\\s*=\\s*(\\d+)", gradle)
version_name_match = re.search(r'versionName\\s*=\\s*"(\\d+)\\.(\\d+)\\.(\\d+)"', gradle)
assert version_code_match and version_name_match, "Android version must remain explicit"
version_code = int(version_code_match.group(1))
version_major, version_minor, version_patch = map(int, version_name_match.groups())
assert version_code >= 20048, "Release must not downgrade from last installed Core"
assert (version_major, version_minor, version_patch) >= (2, 0, 48)
assert version_code == version_major * 10000 + version_minor * 100 + version_patch, (
    "Android versionCode and versionName must agree before an in-place update"
)
assert not any(x in initial_screen for x in ("deleteDatabase(", "clearApplicationUserData(", "recreateDatabase(", "deleteRecursively("))
assert "postSplashScreenTheme" in (src/"res/values/themes.xml").read_text()
print("PASS: startup animation isolation, delayed AI workers, permission timing, gallery gate, and retained Fusion; phone launch still required")
