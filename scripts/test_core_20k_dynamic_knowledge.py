#!/usr/bin/env python3
"""25K+ evolving Fusion Knowledge fixture: source and SQLite regression gate.

Checks indexed identity/alias/feature lookup, immutable IDs, additive updates,
and bounded streaming behavior. This is not a substitute for device testing.
"""
from pathlib import Path
import sqlite3

root = Path(__file__).resolve().parents[1]
runtime = root / "android_app/app/src/main/java/com/ailm/android/runtime"
builder = (runtime / "FusionModelBuilder.kt").read_text()
reader = (runtime / "FusionKnowledgeReader.kt").read_text()
taxonomy = (runtime / "FusionTaxonomyEngine.kt").read_text()
migration = (runtime / "FusionReferenceMigration.kt").read_text()
schema = (runtime / "FusionDatabaseSchema.kt").read_text()

assert 'Regex("^[A-Z]{2}[0-9]{2,}(?:-[0-9]+)*$")' in builder
assert 'Regex("^CH[0-9]{6}(?:-[0-9]+)*$")' in builder
assert "fun tagById(rawId: String): Candidate?" in taxonomy
assert "val tag = taxonomy.tagById(id)" in reader
assert "taxonomy.allTags(refresh = true)" in reader
assert "UNION SELECT t.tag_id" in reader and "COLLATE NOCASE LIMIT 2" in reader
assert "lower(t.canonical_name)=?" not in reader
assert "val features = mutableListOf<Triple" not in migration
assert "while (featureRows.moveToNext())" in migration
assert "val missingFeatures = missingFeatureLinks" in migration
assert "reference_features\" to referenceFeatureCount" in migration
assert "idx_fusion_char_features_by_tag" in schema
assert "idx_fusion_characters_name" in schema
assert "idx_fusion_character_alias_value" in schema
assert "idx_fusion_tag_aliases_value" in schema

db = sqlite3.connect(":memory:")
db.executescript("""
PRAGMA foreign_keys=ON;
CREATE TABLE fusion_series(series_code TEXT PRIMARY KEY, canonical_title TEXT NOT NULL);
CREATE TABLE fusion_tags(tag_id TEXT PRIMARY KEY, canonical_name TEXT NOT NULL COLLATE NOCASE,
                         category TEXT NOT NULL);
CREATE TABLE fusion_tag_aliases(alias_id TEXT PRIMARY KEY, tag_id TEXT NOT NULL,
                                alias_value TEXT NOT NULL COLLATE NOCASE,
                                FOREIGN KEY(tag_id) REFERENCES fusion_tags(tag_id));
CREATE TABLE fusion_characters(character_id TEXT PRIMARY KEY, primary_series_code TEXT,
                               canonical_name TEXT NOT NULL COLLATE NOCASE,
                               FOREIGN KEY(primary_series_code) REFERENCES fusion_series(series_code));
CREATE TABLE fusion_character_aliases(character_id TEXT NOT NULL, alias_value TEXT NOT NULL COLLATE NOCASE,
                                      PRIMARY KEY(character_id, alias_value),
                                      FOREIGN KEY(character_id) REFERENCES fusion_characters(character_id));
CREATE TABLE fusion_character_features(character_id TEXT NOT NULL, feature_id TEXT NOT NULL,
                                       canonical_weight REAL NOT NULL DEFAULT 1,
                                       PRIMARY KEY(character_id,feature_id),
                                       FOREIGN KEY(character_id) REFERENCES fusion_characters(character_id),
                                       FOREIGN KEY(feature_id) REFERENCES fusion_tags(tag_id));
CREATE INDEX idx_fusion_characters_name ON fusion_characters(canonical_name COLLATE NOCASE);
CREATE INDEX idx_fusion_character_alias_value ON fusion_character_aliases(alias_value COLLATE NOCASE);
CREATE INDEX idx_fusion_tags_name ON fusion_tags(canonical_name COLLATE NOCASE);
CREATE INDEX idx_fusion_tag_aliases_value ON fusion_tag_aliases(alias_value COLLATE NOCASE);
CREATE INDEX idx_fusion_char_features_by_tag ON fusion_character_features(feature_id);
""")
count = 25001
series = [(f"SE{i:04d}",f"Series {i}") for i in range(1, 301)]
tags = [("HC001", "Black", "Hair Color"), ("EC014", "Blue", "Eye Color"),
        ("SP001", "Human", "Species"), ("OF016-1-12", "Nested Outfit", "Outfit")]
with db:
    db.executemany("INSERT INTO fusion_series VALUES (?,?)", series)
    db.executemany("INSERT INTO fusion_tags VALUES (?,?,?)", tags)
    db.executemany("INSERT INTO fusion_characters VALUES (?,?,?)",
                   ((f"CH{i:06d}",series[i % 300][0],f"Character {i}") for i in range(1,count+1)))
    db.executemany("INSERT INTO fusion_character_features VALUES (?,?,1.0)",
                   ((f"CH{i:06d}",tag) for i in range(1,count+1)
                    for tag in ("HC001","EC014","SP001","OF016-1-12")))
    db.execute("INSERT INTO fusion_character_aliases VALUES (?,?)", ("CH025001","Future Hero"))
    db.execute("INSERT INTO fusion_tag_aliases VALUES (?,?,?)", ("KA_OF016-1-12_1","OF016-1-12","Alternate Attire"))
assert db.execute("SELECT COUNT(*) FROM fusion_characters").fetchone()[0] == count
assert db.execute("SELECT COUNT(*) FROM fusion_character_features").fetchone()[0] == count * 4
assert db.execute("SELECT character_id FROM fusion_character_aliases WHERE alias_value=? COLLATE NOCASE",
                  ("future hero",)).fetchone()[0] == "CH025001"
ranking = ("SELECT character_id,SUM(canonical_weight) FROM fusion_character_features "
           "WHERE feature_id IN (?) GROUP BY character_id ORDER BY SUM(canonical_weight) DESC LIMIT ?")
plan = " ".join(str(row) for row in db.execute("EXPLAIN QUERY PLAN " + ranking,("HC001",80)))
assert "idx_fusion_char_features_by_tag" in plan, plan
tag_lookup = ("SELECT tag_id FROM fusion_tags WHERE canonical_name=? COLLATE NOCASE "
              "UNION SELECT tag_id FROM fusion_tag_aliases WHERE alias_value=? COLLATE NOCASE LIMIT 2")
assert db.execute(tag_lookup,("alternate attire","alternate attire")).fetchall() == [("OF016-1-12",)]
plan = " ".join(str(row) for row in db.execute("EXPLAIN QUERY PLAN " + tag_lookup,("blue","blue")))
assert "idx_fusion_tags_name" in plan and "idx_fusion_tag_aliases_value" in plan, plan
with db:
    db.execute("UPDATE fusion_tags SET canonical_name=? WHERE tag_id=?",("Midnight Black","HC001"))
    db.execute("INSERT INTO fusion_tag_aliases VALUES (?,?,?)",("KA_HC001","HC001","Black"))
    db.execute("INSERT OR IGNORE INTO fusion_characters VALUES (?,?,?)",
               ("CH025001","SE0001","Incorrect duplicate"))
    db.execute("INSERT INTO fusion_characters VALUES (?,?,?)",("CH025002","SE0001","Added Character"))
assert db.execute("SELECT canonical_name FROM fusion_characters WHERE character_id='CH025001'").fetchone()[0] == "Character 25001"
assert db.execute("SELECT tag_id FROM fusion_tags WHERE canonical_name=? COLLATE NOCASE",
                  ("midnight black",)).fetchone()[0] == "HC001"
assert db.execute(tag_lookup,("black","black")).fetchone()[0] == "HC001"
assert db.execute("SELECT COUNT(*) FROM fusion_characters").fetchone()[0] == count+1
assert db.execute("PRAGMA foreign_key_check").fetchall() == []
assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
print("PASS: 25,001 canonical characters + update, 100,004 feature links, indexed lookups, nested IDs")
