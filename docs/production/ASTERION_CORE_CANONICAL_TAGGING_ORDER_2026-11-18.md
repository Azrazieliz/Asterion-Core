# Asterion Core canonical recognition order — owner correction (18 Nov 2026)

This is the **current owner-authoritative** contract. It supersedes the older
SP-first wording in issue #47. This page is an implementation contract, not
a claim of on-device acceptance.

## Canonical character attributes per distinct visible subject

1. **SX — Sex**
2. **HC — Hair color**
3. **HL — Hair length**
4. **HS — Hair style**
5. **EC — Eye color**
6. **ET — Eye traits** (native SE0031, including heterochromia, slit pupils,
   unusual pupil shapes); **EA** may be visited *only if the live Fusion SQLite
   taxonomy contains an EA family*. Never rename ET entries or mint EA IDs.
7. **BH — Height**
8. **SC — Skin color**
9. **BT — Body type/build**
10. **BS — Breast size**, where applicable
11. **BY — Body traits**, where applicable
12. **AG — Apparent age group**
13. Any additional *real canonical character attribute family* imported in
    Fusion, identified by its stored character/corporal scope, with its own
    candidate list and evidence requirements — before SA. This is taxonomy
    discovery, **not a UI tag named "other attributes"**.
14. **SA — Visible species attributes**, such as pointed ears, horns, tails
15. **SP — Species** supported by subject-level SA and other visual evidence;
    do not infer a subject's species from decorative objects in the background

A missing, occluded, inapplicable or model-unsupported family has its own
explicit resolution status and may not be counted as a successfully accepted
tag. Each separate character retains its own observations and correction list.

## Image / illustration attributes

Once visible canonical character attributes have been evaluated, visit actual
image-specific families in the current Fusion catalog:

- **AC** Action; **GE** Gesture; **PO** Pose; **OF** Outfit;
  **WP** Weapon; **FR** Framing; **OR** Orientation; **CA** Camera;
  **EN** Environment; **WE** Weather; **LI** Lighting;
  **RT** independent content rating.
- Optional namespaces EY, MO and EX are visited only if present in the
  live catalog; they were not part of the supplied SE0031 workbook.
- Additional imported image-scoped families are evaluated from live
  catalog metadata; none are silently discarded.

**Scenery-only** images skip character-profile requirements and emphasize
EN → WE → LI → FR → OR → CA, then relevant remaining image families.
Mixed compositions retain separate character and scene evidence.

The supplied SE0031 workbook has **26 families, 14 canonical character
attribute families and 12 illustration families**. Code must use live
Fusion SQLite for actual available IDs, not treat this historical workbook
as evidence of the phone's current installed records.

## Routing and learning

- A single unmatched visible character → Original Characters.
- A plausible but unresolved canonical identity → Junk / Uncertain Identity,
  initially in Review.
- Strong promotional preview or suspected junk → its Junk subfolder, with
  initial owner approval. Subsequent routing is allowed only with enough
  reversible, same-kind confirmation evidence.
- Do not delete artwork because of a learned rating. Scores under a user
  threshold are **reviewable deletion suggestions only**.
- Owner ratings are authoritative and provide retraceable, retractable
  supervision for the local visual regressor and Fusion tag preferences.

## Release acceptance

Pass Kotlin and Python regression gates, produce a release APK from the
tested commit, verify the original update signing certificate, and verify
the signed file's native transfer receipt on the Samsung. Device installation
and representative real-image accuracy testing remain separate evidence gates.
