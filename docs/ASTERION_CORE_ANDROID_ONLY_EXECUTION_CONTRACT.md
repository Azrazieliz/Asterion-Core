# Asterion Core — Android-only runtime and preservation contract

**Confirmed by user 2026-11-02. This contract applies to this project only.**

- **Asterion Core performs all production indexing, artwork access, inference, classification, Fusion Database updates, and organization on the Android phone.**
- **The Windows PC is not an Asterion Core runtime, model worker, storage server, fallback or dependency.** Never offload OCR, Qwen-VL, image embeddings, organization or background automation to a PC for Asterion Core.
- GitHub is the canonical development and build record. Android CI runners may compile and test the APK; this is not a PC production runtime. A user's phone installs the existing-key, same-package signed APK in place.
- No uninstall, application data clear, replacement signing identity, clearing imported native models, deletion of source artwork, or destructive migration. Preserve all manually maintained Fusion/Character Knowledge data, IDs, corrections, source references, and SAF grants.
- Preserve the full native Android model pipeline: OCR, NSFW, visual embeddings, aesthetic scoring, Qwen-VL semantics, taxonomy/character matching, reviewed routing, safe organization, durable background continuation. Do not convert it into an OCR-only service or a fixed 15-image job.
- WorkManager/foreground execution must observe Android scheduling, thermal, battery, memory and storage constraints and resume from durable checkpoints. Do not claim unrestricted or permanent foreground runtime.
- **1–7★ organization:** exact previously approved folder hierarchy, star criteria and filenames could not be recovered confidently from the available prior context. In accordance with the user's explicit instruction, **do not invent or add 1–7★ mappings**, do not overwrite existing rating metadata, and do not infer a star rating from the model aesthetic score. Preserve any existing user-defined rating rules unchanged until the canonical specification is available.

Related: [2026-11-02 production readiness audit](audits/ASTERION_CORE_PRODUCTION_READINESS_AUDIT_2026-11-02.md) and [issue #23](https://github.com/Azrazieliz/Asterion-Core/issues/23).
