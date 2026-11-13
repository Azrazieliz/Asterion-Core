#!/usr/bin/env python3
"""Source-level guard for navigation continuity and native-only Core automation."""
from pathlib import Path

base = Path("android_app/app/src/main/java/com/ailm/android")
screens = (base / "ui/screens/Screens.kt").read_text()
viewmodel = (base / "ui/viewmodel/AppViewModel.kt").read_text()
nav = (base / "ui/navigation/AppNavHost.kt").read_text()
manager = (base / "runtime/ai/LocalAiManager.kt").read_text()
runtime = (base / "runtime/StandaloneRuntime.kt").read_text()
gradle = Path("android_app/app/build.gradle.kts").read_text()

# NavHost maintains a single ViewModel, while ScreenScaffold is created again
# for every route. Returning to a tab must not re-bootstrap or clear the catalog.
assert "fun AppNavHost(appViewModel: AppViewModel = viewModel())" in nav
assert "if (!appViewModel.claimBootstrapOnce()) return@LaunchedEffect" in screens
assert "fun claimBootstrapOnce(): Boolean" in viewmodel
assert "if (bootstrapClaimed) return false" in viewmodel
assert "_uiState.value = current.copy(" in viewmodel.split("fun refreshDashboard()", 1)[1].split("fun loadMoreGallery()", 1)[0]
assert "if (state.aiOverview.isEmpty()) onRefreshAi() else onPollAutomation()" in screens
assert "delay(3000)" in screens
assert "rememberLazyStaggeredGridState()" in screens

# Qwen must be inactive, unregistered, and absent from new APK binaries.
assert "LlamaCppBackend(resolver, appContext)" not in manager
assert "retireInstalledQwenModels()" in manager
assert "filterNot(::isRetiredQwen)" in manager
assert "externalNativeBuild" not in gradle
workflow = runtime.split("fun runAutonomousImageWorkflow(", 1)[1].split("private fun automationImagePreflight(", 1)[0]
assert "resolvedQwenSemanticModel()" not in runtime
assert "qwenFallback" not in workflow
assert '"recognition_mode" to "native_classifier"' in runtime
assert 'val nativeTagger = resolvedNativeTaggerModel() ?: return mapOf(' in workflow
assert "tag_prediction" in workflow and "classifyDetectedSubjects(" in workflow
assert "AutomationStageFailurePolicy.blocking(" in workflow
assert 'state = "retry_required"' in workflow
assert "waiting_for_knowledge" in workflow

print("PASS: tab continuity, no route re-bootstrap, retired Qwen runtime and native-only Fusion evidence")
