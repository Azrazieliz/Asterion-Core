# AI Illustration Library Manager

> **P0 — Production automation blocked (2 November 2026).** The installed v2.0.20 processed **zero of 152 attempted images**; all were deferred at source access. A v2.0.21 repair candidate is in [PR #31](https://github.com/Azrazieliz/Asterion-Core/pull/31), not yet verified on the phone. The release-blocking record is [issue #30](https://github.com/Azrazieliz/Asterion-Core/issues/30); the [permanent recovery checkpoint](docs/audits/ASTERION_CORE_P0_PRODUCTION_RECOVERY_2026-11-02.md) lists exact causes, edits and gates. **Do not mark as working on build success alone.**

Production-grade AI Illustration Library Manager.

## Android release status

Installed/failed: v2.0.20. Development candidate: v2.0.21 (device unverified).

## Status

Specification Freeze v1.1 (Frozen)

## Canonical Specification

The definitive architecture and behavior specification is maintained in:

- docs/ARCHITECTURE.md

Other documentation summarizes usage or development workflow and must not redefine frozen architecture.

## Release Notes

- Startup orchestration optimized with lazy imports and deterministic diagnostics.
- Added release metadata, runtime dependency validation, and performance timing utilities.
- Added release documentation generation and validation scripts.
- Added release readiness tests for startup, shutdown, diagnostics, logging, and cleanup.

## Validation Commands

- `py -m poetry run python -m compileall engine run.py`
- `py -m poetry run python run.py`
- `py -m poetry run pytest`

