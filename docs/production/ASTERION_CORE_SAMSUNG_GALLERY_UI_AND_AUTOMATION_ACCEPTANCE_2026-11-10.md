# Asterion Core — Samsung Gallery visual reference and automation acceptance

**Captured:** 2026-11-10 from user-supplied on-device Asterion Core and Samsung Gallery screenshots.
**Status:** USER-APPROVED TARGET / PARTIALLY IMPLEMENTED. A passing Gradle build or matching static UI source is **not** on-device acceptance.
**Project:** existing Android app `com.ailm.android`; continue in place, retain all data, models, signatures and SAF permissions.

## Frozen upstream requirements (do not replace or reinterpret)

The complete, binding functionality remains in:
1. [Frozen original architecture](../ARCHITECTURE.md) — manually controlled canonical knowledge, species inheritance, flat colors, permanent vs illustration traits, Unknown vs Not Visible, adaptive learning limits, reference sheet constraints.
2. [Character resolver and organization checkpoint](../ASTERION_CORE_CHARACTER_RESOLVER_CHECKPOINT.md) — original identity/transformations, multiple canonical colors, separate per-subject visual evidence, alias ambiguity, OC handling, 1-image transactional work, safe Pause, accepted filenames and folder paths.
3. [Android-only execution and preservation contract](../ASTERION_CORE_ANDROID_ONLY_EXECUTION_CONTRACT.md) — no PC runtime dependency, preserve installed models and original source files, no invented 1–7★ mapping.
4. [Canonical Fusion-native 95-item requirements](ASTERION_CORE_FUSION_NATIVE_GALLERY_REBUILD_REQUIREMENTS_2026-11-08.md) — F01–F13, **R01–R22, A01–A18**, G01–G24, Q01–Q09, X01–X09. All remain binding; this amendment does not silently waive any item.

The user expects the **existing specified system**, not a new gallery/AI product. Never treat one model's open-ended description, an untrained fixed-head tagger, or a compiled UI preview as a substitute for those contracts.

## Samsung Gallery visual target (actual reference: three user-supplied screenshots on 2026-11-10)

The reference is the **image-first interaction and composition**, not Samsung-owned artwork or application resources.

**Must have:**
- Artwork fills essentially the entire usable screen. Native preview aspect ratio governs each item's height; no uniform-height crop, image-squashing, grey side gutters, Material Card background, labels over each image, or permanent margins around artwork. Source format and order must remain unchanged.
- Close-packed 2–5-column mosaic/masonry with approximately 0–2dp spacing; varied portrait, square and landscape images render in their own proportions. Never load original cloud files merely for off-screen tiles. Decode actual cached preview dimensions to correct stale DB geometry.
- Pure vertical continuous scrolling, preserving scroll state; **no Previous / page-count / Next bar**. Bounded 200-image or appropriate keyset chunks load automatically near the viewport end; retry individual failed pages rather than restarting whole folder scans.
- Small floating translucent top-right control island: **cloud/sources, search, contextual filters, overflow/settings**; no permanent giant text search field, Find Artwork button, gallery title area, large filter ribbon, or redundant toolbar.
- Compact, rounded, translucent bottom navigation dock **over the images**, with accessible Gallery / Collections / Organize / Review actions. The dock must not reserve a tall black bar or block the last images from being scrolled into view. Maintain touch target size and readable selected state.
- Minimal visible progress/error information only when useful. No persistent "Review updated." card, no blank orange warning box, no raw JSON/path/diagnostics panels on Gallery or Organize.
- Images remain clickable for full-resolution zoom/view; search/filter, source browsing, tags, grid density, organization and review must remain functional, not fake icon controls. Avoid a new UI workflow that drops the approved underlying functions.
- Preserve the app's existing dark/warm-gold identity in restrained controls; Samsung Gallery is the spatial/density reference, not a request to copy trademarked branding.

**Current source implementation for this visual slice:** `ui/screens/Screens.kt`, `ui/navigation/AppNavHost.kt`, `ui/components/GalleryGlyph.kt`, `ui/viewmodel/AppViewModel.kt`. On-device screenshot comparison remains required before saying visually accepted.

## End-to-end automation acceptance — still required

1. Import/migrate all 15 Knowledge inputs into native Fusion without losing canonical IDs, aliases, series, transformations, character sheets, user corrections, family hierarchy or provenance. Check record/family counts, FK/integrity, snapshots, round-trip SQLite export and rollback **on the phone**. An FTS4 derived-index fault may be repaired after a recoverable backup, never by clearing the gallery or the Knowledge corpus.
2. Use the installed Android-native OCR, NSFW, image embeddings, aesthetic scoring, Qwen-VL and any **actually compatible/benchmarked** specialist vision model. Never remove existing models to make a UI readiness flag green; choose actual backends using measured inference contracts and real output. No remote/PC fallback. Do not require an uninstalled, untrained specialist as the only automation path.
3. Analyze the actual decoded image and individual subjects with bounded image/crop memory; compare visible evidence against applicable **native Fusion candidates**, not a sampled taxonomy prompt or invented IDs. Preserve HC/HL/HS/EC etc. and all other relevant native families, genuine multi-valued attributes, occlusion, contradiction/parent constraints and family-specific statuses. Distinguish **unverified suggestion**, **accepted visual evidence**, **Unknown**, **Not Visible**, **Not Applicable** and **failed**.
4. Resolve character identity from stable Character Knowledge and validated visual evidence/reference sheets, supporting multiple people, alternate appearances and transformations, OCs and unidentifiable characters. Derive series from the resolved identity; never guess character/series and treat it as verified. Character canonical traits must never become falsely visible image traits.
5. Use the independent NSFW classifier for RT content rating. OCR can inform promotions, but text/provenance/watermarks alone never permit trashing desired art.
6. Complete **one image at a time**, with durable per-image and per-stage checks, typed errors, bounded retries, pause/resume/stop, Android workload and thermal safety. Each image's verified work survives interruption/restart; no unlimited in-memory queue or multi-minute repeated model reloads.
7. On genuine calibrated evidence with compatible writable source/destination, apply the approved canonical naming, series/character grouping, user-specific path and rating rules through copy/verify/journal/rollback. Never guess unrecovered 1–7★ mappings or delete originals without explicit approval.
8. Otherwise **keep the original and send to Review** with visible candidate IDs, per-subject justification, optional source/model detail and one-tap manual correction. Qwen textual fallback is **review-only**, never self-approved auto-file or counted as successfully organized. Offer safe repeat processing only where new information could change the outcome.
9. Count attempts, succeeded analyses, physically organized files, failed, pending/review, source-deferred, and skipped separately. Show model/SAF failures specifically. Do not label `1/19235` as automation success when the first item failed, and do not spin indefinitely at an "optional semantic review" stage.
10. Validate on a fixed ground-truth mixed image set (including multi-subject, anime/photo/cosplay, explicit/neutral, duplicate, promotional and difficult tags); weight **recognition correctness and functionality 80%, performance 20%**. Then test 20 images, 100, 1,000, interruption, device restart, cloud permissions and large-catalog memory. Real on-device proof is necessary before calling automatic organization functional.

### Observed unresolved user-device result

- 2026-11-10 user screenshot: the device reported **19,235 images**, **1 attempted / 1 could not be processed**, image #785 in semantic-review processing. No successful verified automatic classification or safe file organization was demonstrated in that screenshot.
- The Qwen fallback implementation currently produces **candidate review records**, not calibrated automatic tags/character identities. This is an interim safe behavior, **not the user's requested fully automatic organizer**.
- The full trained specialist classifier/candidate-comparison/subject-local inference chain and on-device recognition accuracy remain **unverified/incomplete**. Do not mark automation accepted simply because compilation passes or a queue advances.

### Release gates

- **UI source gate:** compile plus regression checks for image-only previews, overlay navigation, no Previous/Next footer, no idle warning banners and working actions.
- **Knowledge gate:** 15-pack migration and verified Fusion export, stable IDs, preserved manual edits and no unrecoverable data transformation.
- **Model gate:** named installed model versions, successful real visual inference from target providers, candidate coverage, confidence calibration and independently reviewed family/character/RT accuracy.
- **Automation gate:** verified durable source-read → tag/identity → deterministic policy → review or journaled organization, with truthful counts, cancellation and restart recovery.
- **Delivery gate:** signed in-place update with original cert and package; validated on-device against the actual user's library. Keep PR draft until accepted.

No user source content, taxonomy entry, model file, reference image or approved classification may be deleted as part of satisfying these gates.
