# AsterionCore: bounded automation for 300,000+ images

v2.0.6 changes the automation driver from a single snapshot of all image IDs
and one unbounded worker into **database-keyset paginated jobs**.

- SQL uses the existing `images(active, imported_order)` index and stable
  `(imported_order, image_id)` ordering, with `LIMIT 64`. No OFFSET query
  and no 300k-ID array in memory.
- `SELECT COUNT(*)` supplies initial progress without allocating image IDs.
- A unique durable run UUID, cursor, counters and successful probe flag are
  stored in app-private preferences.
- After **each completed image**, the cursor is synchronously committed.
  A WorkManager retry, safe Pause/Resume and a later dependent batch can
  start from that checkpoint. No fake processing progress or all-library reset.
- A worker runs a maximum 64 images and yields after 20 minutes when possible;
  subsequent WorkManager requests are appended to the same unique-work chain.
- The expensive real-model execution probe only runs once per session.
- A failed probe does not automatically scan the full cloud folder tree, which
  could incur hundreds of thousands of network metadata requests.
- Reprocess Entire Library preserves force-all intent across continuation jobs.

Limitations: Actual performance depends on model inference latency and the
Android foreground-service/background execution rules and TeraBox provider.
A 300k image test in a pure JVM test validates keyset ordering and bounded
batch size, **not** 300k native device inferences. Real-world repeated execution
and device thermal/power behavior require further validation. Changing the
Android signing key requires one-time safe model-data migration, separately.
