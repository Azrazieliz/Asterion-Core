# Asterion Core — P0 production recovery checkpoint (2 November 2026)

> **CRITICAL / MUST REMAIN VISIBLE UNTIL VERIFIED.** GitHub [issue #30](https://github.com/Azrazieliz/Asterion-Core/issues/30), [PR #31](https://github.com/Azrazieliz/Asterion-Core/pull/31), and the [34-finding canonical audit](ASTERION_CORE_PRODUCTION_READINESS_AUDIT_2026-11-02.md). Do not replace this checkpoint with a generic success claim after a passing compile.

## Status and observation

- **Previous installed app:** v2.0.20, Samsung SM-S948B, Android 17. 14,528 catalog records, 152 attempted, **152 deferred**, **0 successfully analyzed**.
- Actual full AI validation passed on one sample, then each sampled source failed in image-opening/fingerprinting before OCR/ONNX/Qwen. The underlying failing provider exception was NOT captured; do not claim root-cause proof for stale permission vs Rounder/TeraBox outage.
- Android denied foreground-service promotion and a scheduler/thermal interruption was observed. The final app process was absent. Termination cause unknown.
- The earlier low-memory admission fix improved model scheduling. Because no image entered inference after source validation, **the current 152/152 failure cannot be attributed to failed OCR/Qwen weights**. End-to-end performance and model correctness are still unverified.

## Code defects established and repaired in the v2.0.21 candidate branch

| Defect | Change | Proof state |
|---|---|---|
| Normal local SAF content URIs were not uniformly staged into native files | Every content:// image is streamed once to bounded app-owned temporary file, passed to all native stage input fields, fingerprinted locally and discarded after completion | Source patch pushed; phone untested |
| AI probe opened its own provider stream and native models could see different source fields | Probe now stages exactly like actual inference and passes that same file to OCR/ONNX/Qwen | Source patch pushed; phone untested |
| Source-open/read errors obscured by generic deferred result | Staging reports sanitized provider authority plus typed permission / missing document / I/O / empty-stream / staging-cap failures | Source patch pushed; phone untested |
| Worker treated every deferred input as nonfatal, allowing 152 wasteful retries | Stop after five consecutive identical source-provider errors; keep all unresolved rows retryable and do not organize | Worker patch pushed; phone untested |
| OS stop could be indistinguishable from user stop | System interruption sets queued/checkpointed recovery, but explicit Stop remains respected | Worker patch pushed; phone untested |
| Some SAF providers expose a typed virtual image document | Try native SAF stream first, fallback to advertised typed image stream; support imported local file paths | SAF patch pushed; phone untested |
| General single/batch/multi-stage AI entrypoints leaked app-owned staged files | finally-block cleanup on completion, failure and cancellation | Runtime patch pushed; phone untested |
| Release CI ran an explicit subset of available Kotlin tests | Entire Android unit suite and Python P0 staging contract required; candidate versionCode 20021 | GitHub CI pending |

## Unresolved before production acceptance

1. **The exact provider failure remains unknown.** Inspect image URI *authority* and safe exception class from a single source attempt on the actual phone after a signed candidate exists; if Rounder or TeraBox auth is offline, recover that provider rather than altering models.
2. **Real native model end-to-end is not proven on catalog images.** Verify OCR, NSFW, embedding, aesthetic scoring, Qwen structured output, Fusion persistence, review and organization on 20 representative images, then 100/1,000.
3. **Long-term Android background durability not proven.** WorkManager/foreground service on Android 17 remains governed by OS quotas and thermal limits; no promise of unrestricted always-running local ML.
4. **Additional P1/P2 canonical audit items remain:** stage-output atomicity, provider-auth reconnection UI, per-provider delayed retry queue, Qwen memory accounting/eviction, read-only cloud organization, gallery stress, full 14,528 catalog run, 300K scale.
5. 1–7★ original organization mapping unrecovered; preserve everything, do not invent new ratings. No PC in Asterion Core runtime. No uninstall, data clear, signing changes, model removal, or source deletion.

## Release gates (NOT waived by CI)

G0: source access verified on physical device. G1: 20 full AI image successes persisted. G2: 100 and then 1,000 mixed-source images; independently audited counters. G3: background/screen-off/suspension and user Stop/resume verified. G4: all 14,528 catalog records accounted for. 300K paging validated synthetically only until device throughput can be measured.

**Do not run v2.0.20 automation again. Do not claim v2.0.21 has fixed the phone until signed in-place installation and actual measured output.**
