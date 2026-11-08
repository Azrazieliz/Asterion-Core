# ASTERION CORE — cross-app bridge and Downloader/TeraBox intake audit
**Captured:** 2026-11-08  
**Priority:** P2 — strictly deferred until Core gallery, native Fusion taxonomy, recognition, automation and Fusion export are working.  
**Status:** Repository-level findings; NOT an assertion that on-device end-to-end cross-app behavior has been tested or fixed.  
**Canonical master:** [Fusion-native gallery rebuild contract](ASTERION_CORE_FUSION_NATIVE_GALLERY_REBUILD_REQUIREMENTS_2026-11-08.md), requirements X01–X09 and A08–A14.

## Source repos and inspected code
- Core: [`Azrazieliz/Asterion-Core`](https://github.com/Azrazieliz/Asterion-Core) `main` and current PR #41 head branch `hotfix/core-v2033-production-baseline-20261107`.
- Art: [`Azrazieliz/AsterionArt`](https://github.com/Azrazieliz/AsterionArt) `main`.
- Downloader: [`Azrazieliz/AsterionDownloader`](https://github.com/Azrazieliz/AsterionDownloader) `main`.

## Finding B01 — Core → Art reference-sheet reader EXISTS, but reads old Knowledge DB
- Core registers `com.ailm.android.asterionart.sheets` in `android_app/app/src/main/AndroidManifest.xml`, backed by `AsterionArtSheetProvider.kt`.
- Art `core/CoreSheetBridge.kt` queries `content://com.ailm.android.asterionart.sheets/characters?search=...&limit=...`, expecting `sheet_asset_id`, `character_id`, `character_name`, `series_code`, `sha256`, `reference_uri`. Art `ui/CoreSheetBrowser.kt` calls that bridge.
- Provider currently queries `KnowledgeDatabase.knowledge_character_sheets` and `knowledge_characters`; this violates new **Fusion sole-authority** requirement. Migrate provider backing tables to Fusion without changing the consumer's public field names or canonical sheet identifiers unless a versioned compatible API is introduced.
- Provider's read-only `openFile` validates `CS-CH...` and confines sheets to app-private `knowledge/character-sheets`; preserve sandboxing during migration. Do not expose private DB, cloud tokens or arbitrary filesystem paths.
- **TODO:** Verify signature/provider permissions and valid access from installed Art on device only after Core is stable; source presence does NOT prove successful runtime permission, rendering or signing interoperability.

## Finding B02 — Art → Core result publishing is INCOMPLETE
- Art `core/CoreResultUploader.kt` **actively called** by `work/GenerationWorker.kt` (line ~363); Art attempts transactional `insert + openOutputStream + update(commit=1)` to `content://com.ailm.android.asterionart.results/upload` and expects Core to validate SHA and return an asset URI.
- Core `main` and the inspected active PR #41 head `AndroidManifest.xml` register **only `.sheets`**, not the `.results` authority; no matching Core result-provider source appears in the inspected main tree. **Therefore a live Art-to-Core publisher cannot be assumed functional** in those sources.
- Do NOT implement a fake receipt. Future receiver must validate calling UID / app signing identity and explicit provider permissions, job ID, render mode, expected digest, content length/MIME, quota, commit state, rollback on failure, idempotent retry, and durable registration in Fusion image catalog.
- Core should persist external job/source ID, model/mode, timestamp, SHA and provenance, then feed accepted result into the **same native classification/review pipeline** as other images; no duplicate gallery entry on repeated upload.

## Finding B03 — Art ↔ Downloader optional bridge exists separately
- Art `integrations/AsterionDownloaderBridge.kt` sends explicit DOWNLOAD_REQUEST broadcast to Downloader preview/production package, supplies HTTPS URL, name and category, and listens for status broadcasts; package/signing/receiver compatibility requires live verification.
- This bridge is **not** needed for Core to function. Avoid tying Core image recognition or source browsing to Art's download initiation.
- Do not leak credentials, tokens or private reference image paths across apps.

## Finding B04 — Downloader has provenance information Core currently must preserve
- Downloader `galleryflow/FlowLinkEmitter.kt` emits MEDIA_DOWNLOADED events containing `source`, `entity_id`, `gallery_id`, `media_id`, `content_uri`, `sha256`. GALLERY_DOWNLOADED has source/entity/gallery, title and canonical URL.
- Downloader `galleryflow/FlatStoragePolicy.kt` names image pages using source/gallery/page index/stable item ID; the names help but are **not sufficient as the only canonical metadata**.
- Its FlowLink broadcast uses `setPackage(null)`, so the broadcast itself should not be treated as a trusted, authenticated, durable integration record. Future Core handshake should obtain provenance via a permissioned/signed provider or verified app-to-app API with replay-safe IDs. Treat broadcasts at most as hints to reconcile data.
- Core intake stores as available: app/source/site ID, entity/artist/model name, gallery/album ID, media stable ID, original URL, page index and ordering, local URI, remote TeraBox path, upload state, SHA-256, MIME, dimensions and timestamps. Never invent absent fields.
- Cache metadata on scan/admission where available, but **do not delay** initial gallery/recognition release on protocol completion.

## Finding B05 — Downloader TeraBox uploads require end-to-end reconciliation
- Downloader `pixivdumpsync/TeraboxUploader.kt` implements an authorized Open Platform upload and checks final reported path, fs_id and size. Those checks are useful but do not independently prove every remote file is readable through Core's configured provider.
- Core's existing TeraBox/Round Sync bridge is SAF/provider-backed, with experimental document-provider permissions noted in `docs/ROUND_SYNC_TERABOX_BRIDGE.md`. Do not infer that all TeraBox folders are automatically indexed or that every cloud URI supports persistent tree grants/seek.
- Future intake should reconcile upload → remote listing/metadata/read access → Core index; handle pending/partial/unreadable providers without duplicating, deleting or prematurely marking media completed.
- No credential sharing from Downloader/TeraBox/Rounder to Core. Browse source via Android SAF permission when available.

## Required future Core intake classification

**Goal:** Incoming downloads may contain desired art mixed with unwanted material (low-res samples, promotional previews, paywall offers, duplicates, broken/corrupt files, wrong media, badly compressed images). Core must distinguish them **without destructive, unreliable AI guesses**.

| Candidate class | Evidence | Default action |
|---|---|---|
| Desired artwork / illustration / cosplay / photograph | Visually valid image and applicable user preferences | Keep and index; run Fusion-native tagging |
| Scenery/reference art | Valid non-character image | Keep and classify, never "No character" trash automatically |
| Gallery/comic pages | Group/page index and series context | Preserve gallery/page order and source linkage |
| Low-resolution thumbnail/sample | Actual dimensions and similar higher-quality variant; metadata | Review or mark lower priority; user controls threshold |
| Near-duplicate / identical | SHA/perceptual hash with visual check and context | Surface best copy / review; do not erase alternate crop or full-res source |
| Promotional/paywall contact sheet | Strong visual layout + textual offer/preview evidence + provenance | Reversible Quarantine or Review; never irreversible delete |
| Watermarked genuine art | Watermark alone is weak | Keep; never delete as promotion from watermark alone |
| Broken / unsupported media | Genuine decoder error, missing bytes, unsupported MIME | Review/repair; preserve source metadata |
| Borderline quality / unwanted style | Subjective/user-specific preferences | Keep or Review, never forced automatic trash |
| Explicit artwork | Independent validated content rating | Correct rating and user-defined filters; NSFW alone ≠ unwanted |

**Mechanics:** A reversible Quarantine collection, separate from destructive filesystem trash, with Keep / Hide / Exclude similar / Review / Recover controls; visible classification reason and confidence; configurable user rules; exact provenance/fingerprint; dry-run before large batches. Never classify an image solely because OCR saw a creator domain or because a free-form VLM imagined a label. Existing `ContentTriagePolicy` has a suspicious `strongPromotionText` shortcut alongside strict paid-preview criteria; this needs reasoned safety re-evaluation before any automatic relocation.

## Future acceptance checklist — deliberately gated after Core

- [ ] Native Fusion gallery and organizer independently pass the benchmark and install on phone.
- [ ] Core sheet provider returns same versioned records from Fusion and Art can display a reference sheet, with read-only restrictions intact.
- [ ] Art generated asset uploads to a registered, secured Core results provider; SHA/size verified, idempotent registration, no duplicate.
- [ ] Downloader provenance survives TeraBox upload and Core import, preserving source/gallery/page/order, and reconnect/restart.
- [ ] Untrusted FlowLink broadcasts do not silently authorize Core mutations.
- [ ] Promotion, thumbnail, genuine art, comics and near-duplicates land in expected Keep/Review/Quarantine classes without destructive loss.
- [ ] Cross-app failure cannot block standalone Core browsing, tag recognition or automation.

**Implementation status:** none of the bridge changes in this document are claimed complete. Source inspection is the only validation so far.
