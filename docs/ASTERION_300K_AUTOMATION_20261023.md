# AsterionCore v2.0.6 — 300,000+ images

## Memory-safe image scheduling

Previous automation allocated an entire list of image IDs and tried to
process every one in a single WorkManager request. At 300K files, that causes
unbounded work lifetimes, excessive memory pressure and lost run progress.

The worker now uses SQLite keyset batches (16 rows, bounded to 64 per DB
query), indexed by (active, imported_order, image_id), without OFFSET.
An exact SQL COUNT(*) replaces materializing all pending IDs in the UI.

Each foreground work request completes at most 32 images or approximately
six minutes of work (whichever is reached after an image finishes), then
queues a durable APPEND_OR_REPLACE continuation. The UI state totals and the
last completed image cursor are persisted after every image by synchronous
SharedPreferences commit; a process restart or pause resumes without
walking the previous 300K images again.

The initial real-inference probe is run once per automation run and retained
in a checkpoint. A giant cloud library is *not* automatically rescanned when
a small sample has invalid document URIs (above 2K): instead the user receives
an explicit manual-rescan error.

## Important limitations

A model may require minutes per image. Processing 300K images locally can
take days to months depending on throughput, battery, thermal state and
remote storage latency. Background execution is still subject to Android
scheduling constraints. This patch removes the unbounded in-memory queue,
not model execution time. Individual image I/O and the library scanner
still need real-device profiling at full scale.

## Signing / updates

Only APKs signed with the same certificate as the installed app can update
without removal. Existing CI debug keys are ephemeral. The repository has
a stable-release signing workflow, but it will not produce a stable signed
artifact until the four ASTERION_SIGNING_* secrets are set. Do not uninstall
a debug-signed installation with irreplaceable model data merely to switch keys.
See docs/ANDROID_STABLE_SIGNING_AND_UPDATES.md.
