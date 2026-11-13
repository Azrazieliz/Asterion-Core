# Asterion Core v2.0.43 — Tab continuity, Qwen retirement, identity architecture

## Canonical scope
Asterion Core is an entirely offline Android gallery with a native SQLite Fusion taxonomy and source-of-truth image/subject observations. Android performs all real inference, classification, and organization; Windows is not a processing dependency. This is a continuation of existing work, not a restart.

## Tab continuity regression
The Gallery and Organize/Automation displays were reconstructed on navigation. Previously `ScreenScaffold` ran application initialization and a full dashboard refresh in `LaunchedEffect(Unit)` on every screen entry. Returning to Organize also requested an expensive full AI snapshot. These operations caused a temporary empty/resetting view even though the underlying gallery and automation records had not disappeared.

v2.0.43:
- The Activity-scoped `AppViewModel.claimBootstrapOnce()` gates the bootstrap and initial catalog load; re-entering a tab does not reinitialize.
- Dashboard refresh copies the current UI state rather than replacing it with a default `AppUiState`, preserving unrelated per-tab state.
- Automation retains its prior snapshot and requests heartbeat polling when the snapshot already exists; full refresh only on first visit.
- Gallery bounded lazy scroll and navigation saved-state behavior remain intact.
- `scripts/test_core_tab_navigation_contract.py` guards these source invariants.

These are committed source changes. Device transition latency and pixel continuity have not been measured on the final v2.0.43 APK.

## Qwen retirement
Qwen is not an approved visual tagger or identity source. The old fallback, semantic review generation, and generative stage invocation have been removed from automation. Native-only WD classifier readiness now governs automated tagging. The Llama/Qwen provider is not registered. The old JNI CMake build, C++ source, Kotlin native bridge, image/prompt policies, and Qwen-specific tests were removed. Existing registered Qwen models are marked retired on startup; app-private primary model files are deleted only when canonical paths are safely inside the app sandbox. User external files are never deleted by this migration.

Historical audit documents and old review records remain for provenance; they are not an active generative inference route.

## Recognition architecture and authoritative ownership
1. The original gallery and approved file metadata are authoritative for filenames, paths, organization receipts and user edits.
2. A native anime-person detector provides distinct character regions. The WD ViT v3 ONNX multi-label tagger classifies the whole artwork and each detected character crop independently, preserving genuine classifier scores.
3. The Fusion SQLite taxonomy stores canonical tag identifiers, categories, aliases, hierarchy, scope rules and classification observations. Its family-first deterministic resolution covers HC/HL/HS, EC/ET and all other installed families dynamically; there is no top-K family allowlist or language-model prompt taxonomy. It does not invent missing catalog entries or pixel evidence.
4. Character Knowledge owns the canonical character identities, aliases, series, forms and valid reference attributes; reference sheets provide trusted visual exemplars. Both remain incomplete. Fusion observations do not constitute proof of character identity.
5. The CharacterResolver may persist observations and unknown/review status, but must not allocate or assert new canonical character IDs before validated Knowledge/reference evidence is available. It must never copy image-wide person attributes onto all characters.

## Future exact character identification (not implemented in v2.0.43)
Use a CCIP-style, pose/style/wardrobe-robust character re-identification embedding head on each detected character crop. Compare the vectors against multiple validated Character Knowledge reference-sheet images in an on-device approximate-nearest-neighbor index. Combine embedding similarity with deterministic Fusion attribute consistency, variant/series rules and a top-two margin. Require verified evidence before assigning a canonical ID; ambiguous matches remain unknown or Review. The preferred long-term 1–2-model architecture is the existing person detector plus one specialist visual encoder with both taxonomy and identity-embedding outputs, instead of adding a third unrelated general-purpose model.

No Character Knowledge records, references, sheets or canonical IDs were invented by this change.
