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
assert 'private const val DB_VERSION = 6' in knowledge
assert 'addIfMissing("knowledge_tags", "metadata_json"' in knowledge
assert 'put("metadata_json", mergedMetadata.toString())' in knowledge
assert 'metadataJson: String = "{}"' in importer
assert 'metadataJson = metadata.toString()' in importer
assert '"source_document_hashes" to sourceDocuments.toSortedMap()' in fusion
assert 'val metadata = runCatching { JSONObject(item.metadata) }' in fusion
assert 'for (key in listOf(' in fusion
assert 'parseSpeciesRules(' in taxonomy and 'parseCharacterScope(' in taxonomy
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

# Exercise the actual franchise insert/link statements. Two series share one
# source-defined franchise, while an existing owner association wins.
import re
insert = re.search(r'INSERT OR IGNORE INTO fusion_franchises\s+\(franchise_id.*?VALUES \(.*?\)', fusion, re.S).group()
link = re.search(r'UPDATE fusion_series SET franchise_id=\? WHERE series_code=\? AND franchise_id IS NULL', fusion).group()
db.executescript("""
CREATE TABLE fusion_franchises(franchise_id TEXT PRIMARY KEY,display_name TEXT,canonical_slug TEXT,created_at_ms INTEGER,updated_at_ms INTEGER);
CREATE TABLE fusion_series(series_code TEXT PRIMARY KEY,franchise_id TEXT REFERENCES fusion_franchises);
INSERT INTO fusion_franchises VALUES('owner','Edited','owner',0,0);
INSERT INTO fusion_series VALUES('SE1',NULL),('SE2',NULL),('SE3','owner');
""")
for _ in range(2):
    with db:
        db.execute(insert, ("SE1", "Series One", "se1", 1, 1))
        for code in ("SE1", "SE2", "SE3"):
            db.execute(link, ("SE1", code))
assert db.execute("SELECT franchise_id FROM fusion_series ORDER BY series_code").fetchall() == [("SE1",), ("SE1",), ("owner",)]
assert db.execute("PRAGMA foreign_key_check").fetchall() == []
resolver = (runtime / "FusionResolutionStore.kt").read_text()
query = re.search(r'SELECT embedding_json, weight FROM fusion_character_visual_evidence\s+WHERE[^"]+',resolver).group()
db.execute("CREATE TABLE fusion_character_visual_evidence(character_id,validated,embedding_json,weight)")
db.executemany("INSERT INTO fusion_character_visual_evidence VALUES (?,?,?,?)", [
    ("CH1",1,"[0,1]",1),("CH1",0,"[1,0]",1),("CH2",1,"[1,0]",1),("CH1",1,"[]",1)])
assert db.execute(query, ("CH1",)).fetchall() == [("[0,1]",1)]
print("PASS: migration franchise lineage, owner overrides, replay and validated identity evidence")

# Production receipt SQL must roll back with the migration and retain the first verified receipt on replay.
receipt_sql = re.search(r'INSERT INTO fusion_import_runs\s+\(import_id.*?WHERE import_id=\?\)', fusion, re.S).group()
db.execute("CREATE TABLE fusion_import_runs(import_id PRIMARY KEY,started_at_ms,finished_at_ms,status,source_type,conflict_strategy,summary_json)")
db.commit()
args = ("reference-fixture",1,2,"completed","reference_knowledge","preserve_existing",'{"added":1}',"reference-fixture")
db.execute(receipt_sql,args)
db.rollback()
assert db.execute("SELECT COUNT(*) FROM fusion_import_runs").fetchone()[0] == 0
db.execute(receipt_sql,args)
db.execute(receipt_sql,(*args[:6],'{"added":0}',args[-1]))
assert db.execute("SELECT summary_json FROM fusion_import_runs").fetchall() == [('{"added":1}',)]
