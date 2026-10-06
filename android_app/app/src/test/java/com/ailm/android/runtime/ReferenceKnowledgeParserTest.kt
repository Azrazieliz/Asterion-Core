package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceKnowledgeParserTest {
    @Test
    fun `parses series and heterogeneous tag schemas without characters`() {
        val bundle = ReferenceKnowledgeParser.parseDocuments(
            mapOf(
                "series_1623_entries.json" to """[
                    {"series_code":"SE0001","canonical_name":"Example Series","franchise":"SE0001","aliases":["Example"]}
                ]""",
                "Outfits.json" to """[
                    {"id":"OF001","parent_outfit":"","canonical_name":"Casual","aliases":["Casual Wear"]}
                ]""",
                "Expressions.json" to """{
                    "expressions":[
                        {"tag_id":"EX001","canonical_name":"Neutral","aliases":[]}
                    ]
                }""",
            ),
        )

        assertEquals(1, bundle.series.size)
        assertEquals("SE0001", bundle.series.single().code)
        assertEquals(2, bundle.tags.size)
        assertTrue(bundle.tags.any { it.id == "OF001" && it.category == "outfit" })
        assertTrue(bundle.tags.any { it.id == "EX001" && it.category == "expression" })
    }

    @Test
    fun `detects raw reference schemas without requiring generic id`() {
        val hair = """[
            {"id":"HC001","attribute":"hair_color","canonical_name":"black","aliases":["black hair"],"verified":true}
        ]"""
        val series = """[
            {"series_code":"SE0001","canonical_name":"Example Series","franchise":"SE0001","aliases":["Example"],"verified":true}
        ]"""
        val poses = """[
            {"tag_id":"PO001","canonical_name":"Standing","aliases":["standing"],"parent_tag":null,"verified":true}
        ]"""

        assertTrue(ReferenceKnowledgeParser.looksLikeReferenceDocument("Hair_HC001-HC029.json", hair))
        assertTrue(ReferenceKnowledgeParser.looksLikeReferenceDocument("series_1623_entries.json", series))
        assertTrue(ReferenceKnowledgeParser.looksLikeReferenceDocument("poses_PO001-PO011.json", poses))

        val bundle = ReferenceKnowledgeParser.parseDocuments(
            mapOf(
                "Hair_HC001-HC029.json" to hair,
                "series_1623_entries.json" to series,
                "poses_PO001-PO011.json" to poses,
            ),
        )
        assertEquals(1, bundle.series.size)
        assertEquals("SE0001", bundle.series.single().code)
        assertEquals(setOf("HC001", "PO001"), bundle.tags.map { it.id }.toSet())
    }

    @Test
    fun `character parser preserves multiple ids in one attribute family`() {
        val bundle = ReferenceKnowledgeParser.parseDocuments(
            mapOf(
                "characters.json" to """[
                    {
                      "character_id":"CH000001",
                      "canonical_name":"Example",
                      "primary_series_code":"SE0001",
                      "attributes":{
                        "hair_color":["HC015","HC030"],
                        "eye_color":["EC010","EC014"],
                        "eye_traits":["ET001"]
                      }
                    }
                ]"""
            ),
        )

        val character = bundle.characters.single()
        assertEquals(
            setOf("HC015", "HC030", "EC010", "EC014", "ET001"),
            character.attributeIds.toSet(),
        )
        assertEquals(5, character.attributeIds.size)
    }
    @Test
    fun `series alias collision with child canonical name is ignored safely`() {
        val plan = SeriesAliasPlanner.plan(
            listOf(
                ReferenceSeriesEntry(
                    code = "SE0322",
                    name = "Million Arthur",
                    franchise = "SE0322",
                    aliases = listOf("Kaku-San-Sei Million Arthur"),
                ),
                ReferenceSeriesEntry(
                    code = "SE0322-1",
                    name = "Kaku-San-Sei Million Arthur",
                    franchise = "SE0322",
                    aliases = emptyList(),
                ),
            ),
        )

        assertTrue(plan.canonicalEntries.contains("SE0322-1" to "Kaku-San-Sei Million Arthur"))
        assertTrue(plan.uniqueAliases.none { (_, alias) -> alias == "Kaku-San-Sei Million Arthur" })
        assertEquals(1, plan.ignoredAliases.size)
        assertEquals("SE0322", plan.ignoredAliases.single().seriesCode)
    }

    @Test
    fun `stale duplicate canonical series title does not abort alias planning`() {
        val plan = SeriesAliasPlanner.plan(
            listOf(
                ReferenceSeriesEntry(
                    code = "SE0404",
                    name = "I Left My A-Rank Party to Help My Former Students Reach the Dungeon Depths!",
                    franchise = "SE0404",
                    aliases = emptyList(),
                ),
                ReferenceSeriesEntry(
                    code = "SE1466",
                    name = "I Left My A-Rank Party to Help My Former Students Reach the Dungeon Depths!",
                    franchise = "SE1466",
                    aliases = emptyList(),
                ),
            ),
        )

        assertTrue(plan.canonicalEntries.contains("SE0404" to "SE0404"))
        assertTrue(plan.canonicalEntries.contains("SE1466" to "SE1466"))
        assertTrue(
            plan.canonicalEntries.none { (_, value) ->
                value == "I Left My A-Rank Party to Help My Former Students Reach the Dungeon Depths!"
            },
        )
    }

    @Test
    fun `shared noncanonical alias is not assigned to either series`() {
        val plan = SeriesAliasPlanner.plan(
            listOf(
                ReferenceSeriesEntry(
                    code = "SE1000",
                    name = "Parent A",
                    franchise = "SE1000",
                    aliases = listOf("Shared Title"),
                ),
                ReferenceSeriesEntry(
                    code = "SE1000-1",
                    name = "Child A",
                    franchise = "SE1000",
                    aliases = listOf("Shared Title"),
                ),
            ),
        )

        assertTrue(plan.uniqueAliases.none { (_, alias) -> alias == "Shared Title" })
        assertEquals(2, plan.ignoredAliases.count { it.alias == "Shared Title" })
    }


    @Test
    fun `json null parent is never converted to literal null id`() {
        val bundle = ReferenceKnowledgeParser.parseDocuments(
            mapOf(
                "actions-and-rating-tags.json" to """[
                    {"tag_id":"AC001","parent_action":null,"canonical_name":"Movement","aliases":[],"verified":true},
                    {"tag_id":"AC001-1","parent_action":"AC001","canonical_name":"Walking","aliases":[],"verified":true},
                    {"tag_id":"RT001","parent_action":null,"canonical_name":"Safe","aliases":[],"verified":true}
                ]""",
                "poses_PO001-PO011.json" to """[
                    {"tag_id":"PO001","parent_tag":null,"canonical_name":"Standing","aliases":[],"verified":true},
                    {"tag_id":"PO001-1","parent_tag":"PO001","canonical_name":"Contrapposto","aliases":[],"verified":true}
                ]""",
            ),
        )

        val acRoot = bundle.tags.single { it.id == "AC001" }
        val acChild = bundle.tags.single { it.id == "AC001-1" }
        val poseRoot = bundle.tags.single { it.id == "PO001" }
        val rating = bundle.tags.single { it.id == "RT001" }

        assertEquals("", acRoot.parentId)
        assertEquals("AC001", acChild.parentId)
        assertEquals("", poseRoot.parentId)
        assertEquals("action", acRoot.category)
        assertEquals("rating", rating.category)
        assertEquals("pose", poseRoot.category)
    }

    @Test
    fun `nullish textual parent sentinels are treated as absent ids`() {
        val bundle = ReferenceKnowledgeParser.parseDocuments(
            mapOf(
                "Outfits.json" to """[
                    {"id":"OF001","parent_outfit":"null","canonical_name":"Base Outfit","aliases":[]},
                    {"id":"OF002","parent_outfit":"N/A","canonical_name":"Other Outfit","aliases":[]}
                ]"""
            ),
        )

        assertEquals("", bundle.tags.single { it.id == "OF001" }.parentId)
        assertEquals("", bundle.tags.single { it.id == "OF002" }.parentId)
    }

    @Test
    fun `mixed taxonomy file categories are inferred from ids before filename`() {
        val bundle = ReferenceKnowledgeParser.parseDocuments(
            mapOf(
                "framing_orientation_camera_lighting.json" to """[
                    {"tag_id":"FR001","canonical_name":"Portrait","aliases":[]},
                    {"tag_id":"OR001","canonical_name":"Landscape","aliases":[]},
                    {"tag_id":"CA001","canonical_name":"Eye Level","aliases":[]},
                    {"tag_id":"LI001","canonical_name":"Soft Light","aliases":[]}
                ]""",
                "environments_weather_EN001-WE014.json" to """[
                    {"tag_id":"EN001","canonical_name":"Interior","aliases":[]},
                    {"tag_id":"WE001","canonical_name":"Clear","aliases":[]}
                ]"""
            ),
        )

        assertEquals("framing", bundle.tags.single { it.id == "FR001" }.category)
        assertEquals("orientation", bundle.tags.single { it.id == "OR001" }.category)
        assertEquals("camera", bundle.tags.single { it.id == "CA001" }.category)
        assertEquals("lighting", bundle.tags.single { it.id == "LI001" }.category)
        assertEquals("environment", bundle.tags.single { it.id == "EN001" }.category)
        assertEquals("weather", bundle.tags.single { it.id == "WE001" }.category)
    }

}
