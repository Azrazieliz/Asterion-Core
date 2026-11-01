#!/usr/bin/env python3
"""Automation preflight regression: Android FGS restrictions and queued OCR."""
from pathlib import Path

worker = Path("android_app/app/src/main/java/com/ailm/android/workers/SetupWorkers.kt").read_text()
manager = Path("android_app/app/src/main/java/com/ailm/android/runtime/ai/LocalAiManager.kt").read_text()
runtime = Path("android_app/app/src/main/java/com/ailm/android/runtime/StandaloneRuntime.kt").read_text()
gradle = Path("android_app/app/build.gradle.kts").read_text()

automation = worker.split("class LibraryAutomationWorker(", 1)[1]
foreground = automation.split("private suspend fun setForegroundIfAllowed(", 1)[1].split(
    "private fun foregroundInfo(", 1
)[0]
assert "foregroundServiceDenied = !setForegroundIfAllowed(" in automation
assert "setForeground(foregroundInfo(processed, total" not in automation
assert "visibility.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE" in foreground
assert "ForegroundServiceStartNotAllowedException" in foreground
assert "catch (cancelled: CancellationException)" in foreground
assert "if (!forbidden) throw error" in foreground
assert 'Log.w("AsterionAutomation"' in foreground

await_code = manager.split("private fun awaitTaskTerminalState(", 1)[1].split(
    "private fun extractSemanticMatches(", 1
)[0]
assert "executionScheduler.resume()" in await_code
assert "latest.status !in TERMINAL_TASK_STATUSES" in await_code
assert "executionScheduler.cancelTask(taskId)" in await_code
assert "return null" in await_code
assert "return latest" in await_code
assert "if (completedTask == null)" in manager
assert '"status" to "timeout"' in manager

for method in ("fun runAutonomousImageWorkflow(", "fun probeAutomationExecution("):
    block = runtime.split(method, 1)[1].split("\n    fun ", 1)[0]
    assert '"priority" to 1_000' in block, method

assert "versionCode = 20014" in gradle
assert 'versionName = "2.0.14"' in gradle
print("PASS: Android foreground denial does not fail automation, and queued OCR is never interpreted as terminal model failure.")
