#!/usr/bin/env python3
"""Static safeguard for P0 phone failure regressions; real Android testing remains required.

The v2.0.20 run attempted 152 images but none reached any model. This test
protects the code wiring for source staging and failure/retry handling.
It deliberately does not claim to prove SAF availability or AI accuracy.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
runtime = (ROOT / "android_app/app/src/main/java/com/ailm/android/runtime/StandaloneRuntime.kt").read_text()
worker = (ROOT / "android_app/app/src/main/java/com/ailm/android/workers/SetupWorkers.kt").read_text()
manifest = (ROOT / "android_app/app/src/main/AndroidManifest.xml").read_text()


def require(code: str, snippet: str, label: str):
    if snippet not in code:
        raise AssertionError(f"{label}: expected invariant missing: {snippet}")


def main():
    # A provider's content:// handle is not a native local filesystem path.
    require(runtime, 'parsed.scheme.equals("content", ignoreCase = true)', "All SAF sources must stage")
    require(runtime, 'source?.takeIf { requiresAiStaging(it.uri) }', "Automation source path")
    require(runtime, "stageAiImageSource(imageId, record.uri, record.filename)", "Probe staging")
    require(runtime, 'mapOf("_asterion_staged_remote_path" to probeStagedPath)', "Probe/native same image")
    require(runtime, 'if (requiresAiStaging(originalUri))', "All model pipeline entrypoints")
    for field in ("image_uri", "uri", "path", "source_path", "image_path", "file_path"):
        require(runtime, f'prepared["{field}"] = staged.absolutePath', f"Native input {field}")
    require(runtime, 'val stagedRemote = sourceStage?.file', "Single source stage")
    require(runtime, 'ImageFingerprinting.inspectLocal(stagedRemote,', "Fingerprint uses staged image")
    require(runtime, 'source_failure_code" to errorCode', "Typed source errors")
    require(runtime, 'target.delete()', "Partial stage cleanup")
    require(runtime, 'if (file.exists() && file.parentFile?.name == "remote_ai")', "Only app-owned staging deleted")
    require(runtime, '"source_exceeds_256mib_staging_cap"', "Bounded per-image copy")
    # A failing DocumentsProvider must not waste 152 attempts or claim success.
    require(worker, "consecutiveSourceDeferrals >= 5", "Provider circuit breaker")
    require(worker, 'lastSourceFailure = sourceFailure', "Per-provider failure identity")
    require(worker, 'skipped++; consecutiveFailures = 0;', "Deferrals counted")
    require(worker, 'if (StandaloneRuntime.automationStatus()["automation_status"] == "stopped")', "Respect user Stop")
    require(worker, 'status("queued", "Android interrupted worker', "OS stop recoverable")
    require(worker, 'scheduleDeferredRecovery()', "Durable retry watchdog")
    require(manifest, 'android.permission.FOREGROUND_SERVICE', "Foreground attempt configured")
    print("PASS: P0 source-staging, explicit failure, recovery and preservation wiring")


if __name__ == "__main__":
    main()
