#!/usr/bin/env python3
"""Regression gate for persistent, additive 15-pack Knowledge/Fusion integration.

This is structural + SQLite data-integrity testing. It does not assert that the
Samsung device has imported all 15 files or that artwork has been recognized.
"""
from pathlib import Path
import sqlite3

root = Path(__file__).resolve().parents[1]
runtime = (root / "android_app/app/src/main/java/com/ailm/android/runtime")
knowledge = (runtime / "KnowledgeDatabase.kt").read_text()
importer = (runtime / "ReferenceKnowledgeImporter.kt").read_text()
fusion = (runtime / "FusionReferenceMigration.kt").read_text()
standalone = (runtime / "StandaloneRuntime.kt").read_text()
reader = (runtime / "FusionKnowledgeReader.kt").read_text()
mapper = (runtime / "FusionVisualLabelMapper.kt").read_text()
tagger = (runtime / "FusionClassifierTagGate.kt").read_text()
taxonomy = (runtime / "FusionTaxonomyEngine.kt").read_text()

upgrade = knowledge.split("override fun onUpgrade(", 1)[1].split("fun importedDocumentHashes(", 1)[0]
assert "DROP TABLE" not in upgrade, "A schema upgrade must never erase 15 packs"
assert "ALTER TABLE" in upgrade and "onCreate(db)" in upgrade
assert 'private const val DB_VERSION = 5' in knowledge
assert "knowledge_imported_documents" in knowledge
assert "recordImportedDocuments(documents:" in knowledge
assert "mergeCharacterKnowledge(" in knowledge
assert "atomicReferenceImport(" in knowledge
assert "UPDATE knowledge_characters SET primary_series_code" not in knowledge
assert "knowledgeDatabase.atomicReferenceImport" in importer
assert "knowledgeDatabase.mergeCharacterKnowledge" in importer
assert "knowledgeDatabase.replaceCharacterKnowledge(bundle.characters" not in importer
assert "knowledgeDatabase.recordImportedDocuments(documents)" in importer
assert "restoreInstalledReferenceKnowledge()" in standalone
assert '"source_manifest_entries" to knowledgeDatabase.importedDocumentHashes().size' in standalone
assert '"knowledge_sources_complete"' in standalone
assert "original.delete()" not in standalone
assert "replaceExisting = false)" in standalone
assert 'val missingTags =' in fusion and 'val missingCharacters =' in fusion
assert '"missing_features" to missingFeatures' in fusion
assert 'entry_type, COALESCE(parent_character_id' in fusion
assert "fusion_character_features WHERE character_id=?" in reader
for path, source in (("fusion migration", fusion), ("classification", tagger),
                     ("fixed-head mapper", mapper), ("taxonomy editor", taxonomy)):
    assert '(?:-[0-9]+)*' in source, path + " cannot reject canonical nested IDs"

# Model the durable-store invariants with SQLite transactions, not a mocked
# counter. A second import, and a deliberate canonical-name conflict, must
# preserve the original identity and never change its ID.
db = sqlite3.connect(":memory:")
db.executescript("""
PRAGMA foreign_keys=ON;
CREATE TABLE knowledge_imported_documents (
 document_name TEXT PRIMARY KEY, sha256 TEXT NOT NULL,
 entry_count INTEGER NOT NULL, imported_at_ms INTEGER NOT NULL);
CREATE TABLE knowledge_characters (
 character_id TEXT PRIMARY KEY, canonical_name TEXT NOT NULL,
 primary_series_code TEXT NOT NULL);
CREATE TABLE knowledge_tags (
 tag_id TEXT PRIMARY KEY, canonical_name TEXT NOT NULL);
CREATE TABLE knowledge_character_features (
 character_id TEXT NOT NULL, feature_id TEXT NOT NULL,
 PRIMARY KEY(character_id,feature_id),
 FOREIGN KEY(character_id) REFERENCES knowledge_characters(character_id),
 FOREIGN KEY(feature_id) REFERENCES knowledge_tags(tag_id));
""")
packs = ["hair", "eyes", "styles", "length", "skin", "body", "age",
         "species", "outfits", "weapons", "expressions", "poses",
         "environment", "actions", "ratings"]
ids = ["HC001", "EC014", "HS010", "HL003", "SC002", "BH002", "AG002",
       "SP001", "OF016-1-12", "WP001-3", "EX001", "PO001", "EN001",
       "AC001-1", "RT001"]
assert len(packs) == len(set(packs)) == len(ids) == len(set(ids)) == 15
with db:
    db.execute("INSERT INTO knowledge_characters VALUES (?,?,?)",
               ("CH000001", "Yuka Minase", "SE0646"))
    for ix, (name, tag) in enumerate(zip(packs, ids), start=1):
        db.execute("INSERT INTO knowledge_imported_documents VALUES (?,?,?,?)",
                   (name+".json", f"source-hash-{ix}", 1, ix))
        db.execute("INSERT OR IGNORE INTO knowledge_tags VALUES (?,?)", (tag, name))
        db.execute("INSERT OR IGNORE INTO knowledge_character_features VALUES (?,?)",
                   ("CH000001", tag))
with db:
    db.execute("INSERT OR IGNORE INTO knowledge_characters VALUES (?,?,?)",
               ("CH000001", "Incorrect duplicate", "SE9999"))
    for tag in ids:
        db.execute("INSERT OR IGNORE INTO knowledge_character_features VALUES (?,?)",
                   ("CH000001", tag))
assert db.execute("SELECT canonical_name FROM knowledge_characters").fetchone()[0] == "Yuka Minase"
assert db.execute("SELECT count(*) FROM knowledge_imported_documents").fetchone()[0] == 15
assert db.execute("SELECT count(*) FROM knowledge_tags").fetchone()[0] == 15
assert db.execute("SELECT count(*) FROM knowledge_character_features").fetchone()[0] == 15
assert db.execute("PRAGMA foreign_key_check").fetchall() == []
assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
print("PASS: additive 15-source registry, nested IDs, stable CH identity and Fusion guards")
