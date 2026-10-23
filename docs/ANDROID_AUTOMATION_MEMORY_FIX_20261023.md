# Android automation crash at 0/353: memory-budget correction

## Evidence from phone recording (2026-10-23)

12 GB physical RAM handset (RAM Plus is storage-backed, not additional physical RAM).
Automation stopped at 0/353 in the real-execution probe:
`aesthetic_scoring failed before full-library automation: Insufficient memory reservation budget for execution`.

The installed aesthetic_predictor_v2.5 model was displayed as 1,639.19 MB.
The old fallback reserved 4x model size (~6,556 MB) regardless of task,
while LocalAiMemoryManager's default budget was 60% of available physical RAM.

## Fix in 2.0.5

- Task-specific admission estimates: aesthetic ONNX uses 1.25x artifact size
  plus 256 MiB scratch, instead of 4x.
- Explicit per-model memory_requirement_bytes and reserve_ram_bytes continue
  to override the fallback. Active task reservations and hardware limits
  remain enforced; this is not a forced memory allocation.
- Resource-exhaustion failures now show requested, available and budget MiB
  for actionable, on-device diagnostics.
- Actual automation early stages now allow up to 120 seconds per stage, and
  Qwen-VL allows up to 180 seconds, matching the preliminary execution probe.
  The previous 12-second default aborted otherwise functional phone models.
- JVM regression tests cover model size from recording, peak-memory override,
  multimodal scratch, and arithmetic safety.

No installed model, images, library, or user settings are wiped or changed.
The native runtime still may fail loading large models on some handsets; only
an actual on-phone automation run can verify full execution. This revision
does not authorize destructive cleanup of remote originals.

The model importer previously inserted size*2 into memory_requirement_bytes for every model, without declaring that this was estimated. v2.0.5 marks future estimates and treats the legacy exact 2x unlabelled value as an estimate rather than a measured limit; explicit model-level peaks remain authoritative.
