# ASTERION CORE — canonical Fusion-native gallery rebuild requirements
**Captured:** 2026-11-08  
**Status:** APPROVED REQUIREMENTS / NOT IMPLEMENTED; preserve this document through future branches and releases.  
**Owner repository:** `Azrazieliz/Asterion-Core`  
**Priority:** P0 actual working Core first; P2 cross-app integrations later.  
**Source:** Explicit user instructions plus existing code/on-phone findings. This is the single requirements baseline, NOT a claim that any fix has been applied.

## Unbreakable product contract

Asterion Core is a **premium, immediately understandable Android artwork gallery and organizer**, NOT a developer dashboard, log viewer, model-management console, browser, or collection of exposed implementation controls. A first-time user who has never heard of Fusion, ONNX, GGUF, or WorkManager must be able to browse, search, tag, organize, correct and manage images. Preserve the existing gallery data, imported tags, canonical IDs, character knowledge, corrections, fingerprints, user settings, references, themes/assets and source URIs. No arbitrary redesign or deletion of user data. Keep phone-first operation and real, interruptible actions.

### User decisions that supersede prior proposals
- **No user-facing JSON Knowledge Pack importers or lists.** The 15 installed JSON documents are **migration inputs, not permanent user-facing dependencies**. Migrate their complete data to native Fusion, verify entry/family counts and source provenance, and archive/retire the app-internal JSON copies only after byte-for-byte recoverable backup and successful data comparison. Never discard approved entries. Do not prompt the user to re-import 15 files.
- **Fusion SQLite is the single operational source of truth** for the taxonomy, character profiles, aliases, rules, relationships and recognition outcomes. Knowledge is internal app data. Imports may exist as private upgrade/migration machinery only, not gallery UI; no taxonomy serialized into VLM prompts per image.
- **Native Add Tag engine:** user types a name; app suggests one or more likely taxonomy families directly (using indexed existing names/aliases/family definitions), indicates why, highlights duplicates/synonyms, asks for choice when ambiguous, lets user confirm/edit suggested category, then creates a permanent native canonical entry and updates indexes. Never invent a random category or ID on behalf of the user; preserve two-letter family rules and stable ID allocation. Support parent/relationship fields when relevant; optional expert editing via contextual detail, not default form. User can edit/deactivate an entry with reference safety checks; no silent deletion of referenced tags.
- **Export solely from Fusion** for health/backup inspection. One clear **Export Fusion** action in Settings > Data & recovery; produce a consistent, verifiable SQLite backup (optionally packaged with readable integrity report / counts / checksum) to a user-selected location. Reopen/validate exported file, check counts and SHA-256 before claiming success. Remove JSON export buttons, previews and all other export controls from the normal interface. Internal migrations/restore may accept legacy formats invisibly, but there is no JSON-import/JSON-export UI.
- **Cross-app is explicitly lower priority.** Investigate and record Asterion Art and Asterion Downloader/TeraBox contracts; do not block a working Core on those applications and do not start cross-app implementation until the standalone gallery/recognition/organizer is accepted.
- **No endless blind cycles.** Fix known architectural faults in source, use offline/unit/integration fixtures, and reserve phone testing for a single controlled end-to-end acceptance against a verified build; no repeated mass automation on a broken candidate.
- **Core is a gallery.** Everyday controls and language must describe outcomes such as Browse, Find, Organize, Review and Add Tag; do not expose internal model names, stage pipelines, database rebuild buttons, import sources, diagnostic command logs or raw execution state as normal workflows.

## P0 — Fusion-native data contract (F01–F13)

- [ ] **F01** Define one authoritative Fusion SQLite schema with stable canonical `CH######`, `HC###`, `HL###`, `HS###`, `EC###`, `AG###`, `RT###` etc IDs. Preserve the existing canonical IDs. NEVER replace with `TAG_<hash>` / `CHARACTER_<hash>` derived from Knowledge signatures.
- [ ] **F02** Include EVERY imported tag family, existing definition, alias, canonical name, character, family relationships, source reference and human correction; count and reconcile each of the 15 installed Knowledge JSONs and all other existing canonical data. Category discovery is DB-driven, not a compiled family allow-list.
- [ ] **F03** Create an idempotent, transactional, rollback-capable migration from `KnowledgeDatabase`, existing `fusion_*` tables, installed Knowledge packs and Fusion runtime observations to a unified canonical store. Detect contradictions rather than silently overwriting approved entries.
- [ ] **F04** Update all runtime readers (tag resolver, character resolver, search, gallery metadata, triage, Asterion Art read-only sheet provider) to use the native Fusion authority. Preserve external sheet APIs if possible while replacing their backing data.
- [ ] **F05** Maintain provenance and immutable IDs across Knowledge upgrades; explicit versioning, schema migrations and integrity snapshots. Update only affected entries/indexes.
- [ ] **F06** Add native indexed tag-name/alias matching, family metadata, parent-child relations, incompatibilities, applicability rules, evidence requirements and multi-value rules. Allow a family to have multiple genuinely visible values (hair/eye colors, layered clothing, etc.).
- [ ] **F07** Implement Add Tag by typed name → suggested existing or new tag + likely family (display human-friendly family name and code) → duplicate/alias warning → user confirms → collision-free stable canonical ID and native index update. Uncertain family requires confirmation.
- [ ] **F08** Allow contextual tag rename, alias addition, metadata correction, safe deactivation, relationship edits and audit; manual decisions never overwritten by auto-import.
- [ ] **F09** Remove all user-facing JSON Knowledge import/install/replace/remove UI, the 15-file maintenance list, Knowledge packs count controls and misleading in-memory export preview; retain private migration/backup capability.
- [ ] **F10** Export Fusion ONLY: consistent SQLite snapshot, integrity-check, FK-check, table/family counts, manifest/hash, round-trip read and file picker save; report precisely any failure. No whole-database JSONObject memory materialization.
- [ ] **F11** Guarantee searchable native Fusion lookups without loading entire taxonomies or reindexing images every query; indexes rebuilt incrementally.
- [ ] **F12** Preserve source identifiers/fingerprints/URI permissions and manual tags through all migrations; generate a **reversible migration journal** and backup first.
- [ ] **F13** Expose a minimal intelligible health state ("Library ready" or actionable problem). Deep schema validation remains inside Settings > Data & recovery / Diagnostics.

### Verified source defects to address
- `StandaloneRuntime.runAutonomousImageWorkflow` currently resolves tags from `knowledgeDatabase.resolveTagByFamily` and builds prompt taxonomy contexts even though Fusion exists.
- `ReferenceKnowledgeImporter` writes to `KnowledgeDatabase` and ordinary packs rebuild Fusion separately; data paths diverge.
- `FusionModelBuilder.deterministicFusionId` hashes source ID + Knowledge signature; endangers stable canonical ID agreement.
- `FusionDatabaseRepository.exportJson` materializes every table and legacy image snapshot into a giant `JSONObject`.
- `AppViewModel.exportFusionDatabaseSnapshot` exposes only first 4,000 characters in an in-memory preview; distinguish from successful external file export.
- Existing UI intentionally shows only five packs until "Show all"; migrating to native Fusion eliminates this entire redundant document-management UI.

## P0 — Native taxonomy-directed recognition (R01–R22)

**Mandatory pipeline:** one physical image → full-quality RGB + bounded high-resolution crops → visual encoder/features and individual subject regions → for **every applicable Fusion family**, compare its **actual valid candidates** to visual evidence (batched, cached) → calibrated scores and constraints → accepted/uncertain/none/not-visible/not-applicable/failed outcomes → Fusion IDs → character resolution. Fusion is NEVER serialized to a language model as a prompt or limited sample. A model may use precomputed embeddings of Fusion definitions as **internal classifier weights/features**, not a per-image conversational context. Taxonomy decides eligible candidates, encoder decides visual support, resolver validates canonical IDs.

- [ ] **R01** Remove open-ended Qwen descriptions as the source of canonical tags; no generated-word-to-arbitrary-tag pipeline.
- [ ] **R02** Read candidate families, IDs, aliases, categories and constraints **directly from Fusion SQLite**, using an efficient indexed native API.
- [ ] **R03** Evaluate **all applicable** current and future families. Persist explicit per-family coverage so omitted/unrecognized families are distinguishable from no visual evidence. No hardcoded HC/HS-only completion.
- [ ] **R04** Evaluate full candidate sets through batched vector comparison/multilabel heads and **cached candidate representations**. Never run a full VLM once per individual tag.
- [ ] **R05** Image processing supports adaptive full-image and region crops with measured native context/RAM limits; replace current Qwen downsample ceiling (192/256/320 px) as primary perception bottleneck.
- [ ] **R06** Detect people/characters/subjects; crop/associate evidence per subject; prohibit cross-subject attribute transfer.
- [ ] **R07** Handle anime/illustration, cosplay/photo, realistic art and scenery using calibrated/domain-appropriate recognition. No forced visual-medium guess.
- [ ] **R08** Remove synthetic `confidence=0.73`, `visible=true` with empty evidence, universal bounding box or invented classification certainty. Store actual score, region, model/version and provenance, and accepted threshold rationale.
- [ ] **R09** Calibrate family-specific acceptance and margin/abstention; support multimatches where taxonomy allows; thresholding must account for model calibration rather than treating similarity scores as calibrated probabilities.
- [ ] **R10** Check contradictions, visibility, occlusion, parent dependency, species-specific traits, pose incompatibility, multi-person distinctions and taxonomy exclusions before persistence.
- [ ] **R11** Use an independent NSFW/content classifier with known supported semantics, verified preprocessing and label order; validate separately for drawings/hentai/photos/explicit/suggestive/neutral against actual Fusion RT taxonomy. Never convert Qwen free-form `RT` guesses into authoritative content ratings.
- [ ] **R12** OCR is a separate text-only feature for text-bearing samples/promotions; do not make unrelated visual tags depend on perfect OCR.
- [ ] **R13** Character resolution uses Fusion character profiles + **verified visible observations**, image features and reference sheets, not model-invented character names; unknown/multiple characters and versions remain supported.
- [ ] **R14** Character canonical profile data cannot be copied into "visibly observed" image tags. Identity and visual observations remain distinct.
- [ ] **R15** Failed or ambiguous model results never overwrite manual tags or become approved visual evidence; recover partial work as explicitly unverified data only.
- [ ] **R16** Keep Qwen, if useful, for selectively triggered secondary reasoning on conflicting family results; it must not authorize taxonomy IDs or generate mandatory tags.
- [ ] **R17** Benchmark candidate encoders (e.g. mobile image-text encoder and anime tagger) before replacing current installed models; retain only measured winners that run on device, not speculative model names.
- [ ] **R18** Ensure family coverage and validation are independent of model output formatting. Use typed internal records and native comparisons.
- [ ] **R19** Cache image embeddings and per-family candidate embeddings; invalidate only when the image, visual model or affected Fusion entries change.
- [ ] **R20** Allow one clear correction flow per image and per family; manual correction survives repeated analysis.
- [ ] **R21** Guarantee no unreviewed auto-deletion, no automatic character assignment from unsupported evidence, and no tag family ignored without status.
- [ ] **R22** Model readiness must test meaningful sample inference, not merely installed/declared execution support. Tag accuracy/functionality weighted 80%, speed 20% in acceptance.

## P0 — Automation and reliable file organization (A01–A18)

- [ ] **A01** Replace mandatory all-or-nothing model-stage gating with explicit dependencies; save safely completed work, isolate failed optional tasks and avoid redoing successful unchanged stages.
- [ ] **A02** Exact per-stage errors: model, error code, invalid output, missing data, decode/access, timeouts, parse, memory pressure, unsafe routing. No generic "one or more stages failed".
- [ ] **A03** Durable per-image checkpoint/status and stage provenance; resume after crash, phone restart, app switch or cancellation without re-scanning/reindexing the whole library.
- [ ] **A04** Clear Start / Pause / Resume / Stop / Retry failed, including Android notification controls, immediate cancellation of native generation, and correct cancellation checkpointing.
- [ ] **A05** Bounded retries for transient failures only; do not repeat identical deterministic model failures three times silently, then stop the whole queue.
- [ ] **A06** Preserve image originals, references and paths. All relocation/rename/delete-like operations require a verified journal, rollback and destination confirmation.
- [ ] **A07** Real gallery-facing statuses: Not organized, Organizing, Needs review, Organized, Could not process (with reason); internal stage statuses accessible only when explicitly requested.
- [ ] **A08** Incremental discovery across local folders and TeraBox/Rounder SAF trees; no full-tree reindex for every search or automation resume; use durable fingerprints/IDs and cursor-based pagination.
- [ ] **A09** Fast duplicate detection (exact SHA-256 and visual near duplicates) with different handling for galleries/alternate crops/versions.
- [ ] **A10** Content triage: desired artwork, scenery, reference material, comics/pages, promotional banner, paid-preview contact sheet, low-quality/thumbnail, corrupted file, wrong-media file, duplicate, uncertain, and user-excluded content.
- [ ] **A11** Promotion classification combines visual/text/provenance signals; mere watermark, artist website, censored imagery, domain name or word "preview" is NOT sufficient for removal.
- [ ] **A12** Quality rules use objective signals (decode corruption, native pixel dimensions, perceptual blur, watermark prominence, compression, duplicate similarity) and user preferences where subjective. Never automatically classify merely unpopular/art style or NSFW as trash.
- [ ] **A13** Safe routing: Keep, Review, Reversible quarantine; ambiguous/borderline outcomes default Keep or Review. No irreversible deletion without explicit user request.
- [ ] **A14** Protect original grouping, page order, galleries/albums, source and uploader IDs, checksum, download title/canonical URL and timestamps where available. Distinguish manga/comic pages from accidental duplicates.
- [ ] **A15** Make batch processing bounded for 300,000+ images (paging, native indexes, lazy previews, backpressure, RAM/thermal limits). Avoid reloading huge models every image.
- [ ] **A16** Prefer tested GPU/Vulkan or NNAPI/NPU paths where genuinely supported; fall back to optimized CPU, measure throughput, avoid swap-driven stalls.
- [ ] **A17** Track meaningful counts: processed, accepted, review, unsupported, error and source/provider status. No fake success rates or invisible queuing.
- [ ] **A18** Keep Core usable for browsing/search while organizing in the foreground/background; Android media-projection/microphone not required by Core automation.

## P0 — Premium gallery-first UI, navigation and commanding (G01–G24)

### Information architecture
Start on **Gallery**, not a developer console. Bottom navigation (maximum four clear destinations): **Gallery**, **Collections**, **Organize**, **Review**. Search is a prominent global gallery action (not a redundant full-screen browser). Settings via one clearly marked icon, with advanced details only in Settings > Diagnostics. Gallery and Collections should integrate local storage and cloud-backed accessible items as one catalog where appropriate without duplicating records. Use human language, consistent controls, excellent Android touch ergonomics, clear empty/loading/error states. Preserve the existing approved dark / warm-gold visual character and real functionality.

- [ ] **G01** Gallery home: immersive adaptive thumbnail grid, ordered previews, user-selectable grid density, fast opening, persistent scroll position and no useless utility tiles at the top.
- [ ] **G02** Gallery viewer: swipe between images, pinch zoom, fullscreen, image information on demand, favorite, tags, remove/hide, share only when appropriate, visible origin/collection.
- [ ] **G03** Unified Search: indexed cross-library filename, characters, tags, source, albums and metadata; filters and sorting are contextual, consistent and reversible; do not search only visible folder entries unless user explicitly selected folder scope.
- [ ] **G04** Collections: albums, series, characters, locations/folders and curated sets; no duplicate Browse/Add/Search workflows or deceptive "Everything" toggles.
- [ ] **G05** Organize screen: concise progress and Start/Pause/Stop plus transparent outcome counts; no exposed model/task/runner/JSON information. One action cannot launch two indistinguishable processes.
- [ ] **G06** Review inbox: visual cards, reason, image, proposed tags/quality disposition and one-tap Accept/Correct/Keep; separate uncertain recognition from processing failure.
- [ ] **G07** Native Add Tag: type name; live category suggestions and existing-tag matches; pick/confirm; permanent Fusion save; no file picker, knowledge-pack JSON, raw IDs or long form as default.
- [ ] **G08** Native taxonomy browser (under Settings > Tags or context from gallery): search/family chips/counts, editing/aliases, data integrity warning only when actionable.
- [ ] **G09** Settings: Library source locations, organization preferences, optional model/hardware preferences, backups/recovery, optional diagnostics. No default exposed "AI Workbench", raw runtimes, import payload forms or database consoles.
- [ ] **G10** Replace confusing developer labels (Autonomous pipeline, Fusion rebuild, Download model, Task assignments, Import control) with meaningful end-user actions. Detailed diagnostics require deliberate expansion.
- [ ] **G11** Never show multiple controls doing the same thing or controls that do nothing; avoid redundant toolbars and arbitrary tabs; hide unsupported features rather than fake success.
- [ ] **G12** Use consistent iconography, spacing, dark/high contrast surfaces, restrained gold accents, accessible typography, touch targets, sheet/drawer patterns and Android back behavior.
- [ ] **G13** Design fully usable offline; show cloud-provider connectivity separately and do not block local gallery.
- [ ] **G14** All image previews and result rows have deterministic order, clear title/label, thumbnail, status, actions and retry/cancel where relevant.
- [ ] **G15** Real progress indicators are bound to actual WorkManager/runtime states; no indefinite spinners, duplicate notifications or misleading "Completed".
- [ ] **G16** Onboarding: first launch shows a simple Add Library / Browse flow and explainable permissions only when needed; app usable without understanding Fusion or AI.
- [ ] **G17** Unify navigation and state across screens; preserve scroll, filters, selections and backstack; no forced return to home after tag edits.
- [ ] **G18** Batch selection actions available from gallery with confirmation, undo for reversible operations and no accidental global "all" changes.
- [ ] **G19** Make failure recovery context-specific: Retry image, Reconnect folder, Review classification, Check backup; show technical code only through Details.
- [ ] **G20** Single source-of-truth view state for image counts, galleries, thumbnails and automation. Avoid stale cards, duplicate controls and contradictory "Loading" health.
- [ ] **G21** No app-internal JSON file list, Knowledge pack manager or imported JSON removal buttons in regular screens.
- [ ] **G22** Export Fusion only in Settings > Data & recovery, with explicit "Save backup" and verified receipt. No other export button.
- [ ] **G23** Keep serious functionality (tag edits, automation, file management) easy to discover, but make controls progressive rather than reducing the app into a crippled image viewer.
- [ ] **G24** Audit user journeys with a novice heuristic: from launch to find an image; find a character; add a tag; correct one wrong tag; start/stop organization; recover a quarantined file; export Fusion backup without knowledge of internal model/database terms.

## P1 — Gallery intake quality and scalable acceptance (Q01–Q09)
- [ ] **Q01** Evaluate a diverse fixed set of at least 10 known images once per completed candidate: illustrations, photos, multiple people, fine hair traits, difficult eyes, NSFW content categories, promotion samples, low-quality/duplicate/comic pages.
- [ ] **Q02** Compare ground truth and manual corrections per family: recall, precision, missed-category counts, false-positive severity, incorrect character assignments and NSFW rating error; 80% quality/functionality, 20% throughput/speed.
- [ ] **Q03** Validate migration: same canonical identifiers, 15-pack content accounted for, no lost manual entries, valid foreign keys, no text-index duplication, app-restart durability.
- [ ] **Q04** Validate exported SQLite backup can reopen and exactly match integrity and counts.
- [ ] **Q05** Verify first-time gallery flows without developer-specific terminology and without importing JSON.
- [ ] **Q06** Validate Start/Pause/Resume/Stop, per-item review, recovery after app restart and failed stage diagnostics.
- [ ] **Q07** Benchmark hot/cold inference, battery/thermal/RAM and extrapolate 300k-image processing time; no pretending 50–70 sec/image is production acceptable.
- [ ] **Q08** Produce actual signed ARM64 installable APK and install via Asterion Control only when sources and tests satisfy preflight; no repeated blind APK cycles.
- [ ] **Q09** Record results, commit SHA, build SHA, installed version, verified/incomplete/blocked statuses. Do not call planned work finished.

## P2 — Cross-app bridges (X01–X09), nonblocking
- [ ] **X01** Investigate Asterion Art read-only Core sheet provider compatibility, versioning, signature/permissions, and change backing storage from separate Knowledge DB to Fusion.
- [ ] **X02** Investigate Art-to-Core image publication. Art contains `CoreResultUploader` targeting `com.ailm.android.asterionart.results`, but Core `main` currently registers only a `.sheets` provider: record mismatch; add secure receiving endpoint only after standalone Core works.
- [ ] **X03** Ensure generated art enters Core as tagged provenance-marked assets with checksum, image URI, rendering mode and source job ID; deduplicate idempotently, never create ghost records.
- [ ] **X04** Investigate Downloader/TeraBox source metadata (site, source stable ID, gallery, media/page index, title, canonical URL, SHA-256, cloud path) and usable Android SAF grants.
- [ ] **X05** Avoid losing source provenance when files move to TeraBox; handle remote updates and partial uploads; separate incoming staging and verified gallery admission.
- [ ] **X06** Use Asterion Core's own native image-quality/promotion/duplicate triage regardless of original Downloader site; suspicious content to reversible Review/Quarantine, not unconditional trash.
- [ ] **X07** Preserve media ordering for comics/gallery sets and user-selected source exclusions without changing Downloader backfill/sync behavior during Core stabilization.
- [ ] **X08** Confirm bridge contracts with device integration tests only after gallery and recognition are stable; do not block standalone Core.
- [ ] **X09** See separate `ASTERION_CORE_CROSS_APP_BRIDGES_AND_INTAKE_AUDIT_2026-11-08.md` for source-grounded observations.

## Recommended implementation sequence (no restart)
1. Freeze existing accepted functionality; export pre-migration backups; verify current installed version vs PR branch (recovery APK may differ).
2. Implement F01–F13, migrate user Knowledge JSONs and runtime lookup to native Fusion, and remove file-oriented Knowledge UI.
3. Implement R01–R22, correct NSFW/subject/character observation handoffs, independently benchmark models before replacing.
4. Implement A01–A18 quality intake and robust automation; complete G01–G24 premium gallery commanding in the same integration cycle (not a cosmetic mockup).
5. Complete Q01–Q09; deliver signed APK to phone. Only afterward schedule X01–X09.

## Existing source locations (as inspected)
- `android_app/app/src/main/java/com/ailm/android/runtime/StandaloneRuntime.kt`
- `.../runtime/KnowledgeDatabase.kt`, `FusionDatabaseRepository.kt`, `FusionDatabaseSchema.kt`, `FusionModelBuilder.kt`, `FusionResolutionStore.kt`, `AiWorkflowCoordinator.kt`, `CharacterResolver.kt`, `ContentTriagePolicy.kt`
- `.../runtime/ai/NativeRuntimeBackends.kt`, `ModelPackageInspector.kt`, `LocalAiManager.kt`
- `.../workers/SetupWorkers.kt`, `.../ui/viewmodel/AppViewModel.kt`, `.../ui/screens/Screens.kt`
- `.../runtime/AsterionArtSheetProvider.kt`, `android_app/app/src/main/AndroidManifest.xml`

## Status discipline
**Documented** does NOT equal implemented, tested, signed, or installed. Every checkbox stays open until an actual corresponding code commit and suitable validation are cited. User has authorized requirements recording and architectural changes; this document does not itself authorize data loss, reinstalling a new signature, irreversible file organization, or a mass automation cycle.

## 2026-11-10 — user-confirmed Samsung Gallery visual contract and automation status

The approved [Samsung Gallery UI and complete automation acceptance amendment](ASTERION_CORE_SAMSUNG_GALLERY_UI_AND_AUTOMATION_ACCEPTANCE_2026-11-10.md) is binding alongside this document and the frozen older architecture/resolver/Android contracts. It records the user's screenshots, full-bleed original-aspect images, floating controls, compact dock, scroll-only gallery, and the explicit continuing requirement for **actually automatic** Fusion-validated identity/tagging and safe organization. A Qwen-only Review fallback or a passing build is **not** a completed organizer. Preserve all original A01–A18, R01–R22 and Q01–Q09 release gates.

### Character Knowledge scope and sheet-based matching clarification — 2026-11-11

User-confirmed corrections to prevent an incomplete database or a simplified attribute test from being mistaken for final recognition acceptance:

1. **Roster size is a historical checkpoint, not completion.** SE0001–SE0031 contain 1,331 recorded character entries (1,325 OK / 6 REVIEW at the recorded checkpoint), with next character ID CH001330 reserved for selection. This is NOT a complete franchise-wide or alphabetical character roster. Character Knowledge authoring continues beyond Bleach (SE0031), with Blue Archive queued as SE0032. The app must support arbitrary future series, growth, additional identities, transformations, aliases, and incomplete records. Do not allocate IDs without user selection.
2. **Use all observed relevant attributes, not a hair/eye shortcut.** Candidate comparison must incorporate every applicable canonical attribute family and relationship with evidence, including multi-value same-family traits (for example multicolour hair, heterochromia), physical attributes, species, distinctive anatomy, eyes/hair styles, clothing, accessories, weapons, outfit and contextual observations as relevant. Unknown/unrecorded/occluded fields are missing data, not matches or contradictions. Different kinds of attributes must not be collapsed into a single string or ignored just because the deployed visual tagger cannot score them. Report family coverage separately.
3. **One definitive visual character sheet per character is part of the planned recognition design.** The indexed canonical sheet is a character-specific reference asset; it is not an illustration tag. Resolve subject crops and full-image context against the structured character reference graph (attributes, inheritance, alternate appearances, alias/series relationships and user-approved visual reference evidence), then compare corresponding visual embeddings/features and apply family/identity confidence calibration, runner-up margin and conflict checks. Similarity alone is not a calibrated probability.
4. **The existing roster can be used now, despite unfinished sheets.** Run audited attribute-level and candidate-rank tests against currently verified entries, report coverage, evidence count and ambiguities, and save review suggestions. Do not assert sheet-level accuracy before those sheets are actually indexed, and do not silently auto-assign identity from invented visual evidence.
5. **Do not misuse sparse-record collisions as a uniqueness test.** Counting equal signatures when many feature fields are blank/incomplete measures data sparsity, not proof that characters are indistinguishable across the full taxonomy. For reliable benchmarks, compare only shared observed features, report completeness per family and ground-truth candidate sets, and keep source of image evidence separate from canonical character traits.
6. **Preserve subject-specific learning.** Never use a group-image embedding as a validated reference for all persons pictured; a single-subject confirmed image may contribute a validated reference, and genuine character-sheet images are the primary future comparison source. User-selected character identities remain durable even when machine-learned visual evidence is absent.


## 2026-11-12 — living 20,000+ character corpus and frequent updates (binding)

- Character count is **not fixed** at the 1,331-entry historic checkpoint or at 20,000. The deployed store must support 20,000+ evolving identities and versions with stable CH IDs, indexed character/series/tag/alias/feature relations, and no app rebuild for normal catalog updates.
- Series, tags, aliases, species rules, character profiles and reference sheets are continually expanded. Every accepted edit or additive import must become visible to subsequent recognition immediately, including when the editor and image analyzer use separate runtime instances. Cached taxonomy snapshots cannot be a second authority.
- Canonical identifiers, including hierarchical suffixes such as `OF016-1-12`, must survive import/reimport and renames unchanged; unknown noncanonical identifiers require reconciliation rather than silent regeneration on every source signature change.
- Source updates are transactional, additive, provenance-preserving, recoverable, and conflict-aware. They must never delete earlier images, source documents, manually approved tags, user corrections or existing character associations.
- Startup/source migration must bound memory independent of the growing character-feature association count. Do not construct all associations as an in-memory list before reconciliation. Audit missing relationships explicitly.
- Acceptance must include an indexed SQLite fixture with **at least 25,000 characters**, many-to-many features, multiple series, nested tags, incremental additions, alias conflicts, repeated imports, canonical ID preservation and integrity checks, plus real device latency/memory and 20K+ package migration tests. The fixture is a code regression gate, **not** proof of a complete Samsung device acceptance.


## 2026-11-12 — Ordered exhaustive family evaluation (v2.0.39)

Character Knowledge families (canonical completeness is required for all
applicable character fields): **SP, SA, SX, AG, SC, BH, BT, BS, BY, HC,
HL, HS, EC, ET**.

Image-specific families (assign only when actually observed): **EY, MO, EX,
GE, PO, AC, OF, WP, EN, WE, LI, FR, OR, CA, RT**.

These 29 established two-letter codes are a processing ORDER, **not** a
whitelist or a maximum. Every newly imported family in the live Fusion
taxonomy is automatically included in stable code order, without rebuilding
the APK. Species is evaluated before species-dependent attributes.

The native image model may score all supported classes once for device
efficiency; the resolver must then adjudicate each installed family
**one family at a time**, from first through last. Each creates an ordered
receipt: accepted, evaluated with no confident match, deferred for review,
unsupported by the model, or independently rated. After the ordered pass,
validate species requirements and mutually incompatible observations.
Unsupported/unseen families may NOT be filled with guessed values.

Save and expose the exhaustive ordered per-image family audit in Review,
including trained coverage, accepted counts, deferred results and missing
model support. Multiple people receive independent crop-based family
receipts, never a copy of a whole-image person's attributes. RT is always
adjudicated by the separate actual rating evidence before its receipt is
finalized. Preserve manually corrected tags, original images, stable IDs,
and provenance during repeated analyses or future taxonomy updates.


## 2026-11-12 — One-build release gate, v2.0.40

User authorized one production build to address Issue #47. This must be a
**single atomic branch update** after preflight, not iterative shipped APKs.
Canonical snapshot: Fusion SQLite export (19,237 images, 1,238 tags, 298
canonical image-tag rows, zero characters/sheets, 27 installed families,
missing EC/ET at export time). Never invent missing taxonomy IDs or character
reference sheets; missing families remain explicit unfulfilled catalog sources
until canonical source entries are available.

**Execution invariant**: load live taxonomy before native inference; score
the image with the reusable fixed trained visual head, then, in order,
select each family and compare each of its valid trained candidate values
against this image's cached pixel evidence, assigning observable evidence and
saving one receipt before moving to the next. All character-attribute families
precede image families. Subject-local character facts must originate on the
subject crop, never background foliage, group-wide tags or Qwen prose.
No global top-K classifier truncation, and new Fusion families must be
reconciled automatically. A truly unsupported or absent catalog remains
explicit; an unreadable region never becomes a guessed positive tag.

**Scalability**: cache taxonomy+vocabulary mapping, one bounded warm specialist
ONNX session, avoid recreating per-image 1,238-candidate maps, index 300K+
rows with bounded keyset batches, preserve per-image checkpoints, and use
provider-level circuit breaking without retrying every broken TeraBox item.
Asterion Art technical scorer for bulk 1-5 star auto estimates avoids a
simultaneous 1.7 GB aesthetic inference; user stars override and calibrate
automatic scores. RT content safety remains distinct from quality stars.

**UI**: preserve native approved gallery; ensure reviewer content clears the
bottom navigation inset, show real missing/unsupported family statuses,
separate personal labels from canonical Fusion tags, make saved thumbnail
fallback available in native viewer with explicit full-resolution control,
and show transparent automation counts and controls.

**Outstanding evidence**: native unit tests and 300K SQLite index checks are
pre-release gates; only on-device tests can establish actual throughput,
crash-free run time, real cosplay recall, pixel accuracy, or 300K phone
acceptance. Do NOT mark these complete without evidence. Preserve all
original images, stable Fusion tags/IDs, user edits and signing identity.
