package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class FusionSpeciesTraitRulesTest {
    private fun tag(
        id: String, name: String, parent: String = "", rule: String = "",
        rules: Map<String, String> = emptyMap(),
    ) = FusionTaxonomyEngine.Candidate(
        id, name, id.take(2), parentId = parent,
        traitRequirement = rule, speciesRules = rules,
    )

    private val catalog = listOf(
        tag("SP001", "Human"),
        tag("SP002", "Elf"),
        tag("SP003", "Dragon"),
        tag("SP004", "High Elf", "SP002"),
        tag("SP005", "Wingless Dragon", "SP003"),
        tag("SP006", "Merperson"),
        tag("SP007", "Celestial"),
        tag("SA001", "Pointed Ears", "SP002", "required"),
        tag("SA002", "Wings", "SP003", "optional",
            mapOf("SP002" to "forbidden", "SP005" to "forbidden", "SP006" to "optional")),
        tag("SA003", "Horns", "SP003", "optional"),
        tag("SA004", "Fins", "SP006", "required"),
        tag("SA005", "Animal Ears"),
        tag("SA006", "Scales", "SP003", "optional"),
        tag("SA007", "Feathered Wings", "SP007", "required"),
        tag("SA008", "Forbidden trait", "SP003", "forbidden"),
    ).associateBy { it.id }

    private fun rule(speciesId: String, attributeId: String) =
        FusionSpeciesTraitRules.status(catalog.getValue(speciesId),
            catalog.getValue(attributeId), catalog)

    @Test fun speciesAreResolvedByTheirCanonicalHierarchyNotTheirNames() {
        assertEquals(FusionSpeciesTraitRules.Status.REQUIRED, rule("SP002", "SA001"))
        assertEquals(FusionSpeciesTraitRules.Status.REQUIRED, rule("SP004", "SA001"))
        assertEquals(FusionSpeciesTraitRules.Status.UNLINKED, rule("SP001", "SA001"))
        assertEquals(listOf("SP004", "SP002"),
            FusionSpeciesTraitRules.speciesLineage("SP004", catalog))
    }

    @Test fun requiredOptionalForbiddenApplyToAnySpeciesAndSubtype() {
        assertEquals(FusionSpeciesTraitRules.Status.OPTIONAL, rule("SP003", "SA002"))
        assertEquals(FusionSpeciesTraitRules.Status.FORBIDDEN, rule("SP005", "SA002"))
        assertEquals(FusionSpeciesTraitRules.Status.FORBIDDEN, rule("SP002", "SA002"))
        assertEquals(FusionSpeciesTraitRules.Status.FORBIDDEN, rule("SP004", "SA002"))
        assertEquals(FusionSpeciesTraitRules.Status.OPTIONAL, rule("SP006", "SA002"))
        assertEquals(FusionSpeciesTraitRules.Status.REQUIRED, rule("SP006", "SA004"))
        assertEquals(FusionSpeciesTraitRules.Status.REQUIRED, rule("SP007", "SA007"))
        assertEquals(FusionSpeciesTraitRules.Status.UNLINKED, rule("SP001", "SA007"))
        assertEquals(FusionSpeciesTraitRules.Status.FORBIDDEN, rule("SP003", "SA008"))
    }

    @Test fun expectedCharacterTraitsDoNotManufactureImageObservations() {
        assertEquals(
            listOf("SA001"),
            FusionSpeciesTraitRules.requiredExpectations(setOf("SP004"), catalog)
                .map { it.traitId },
        )
        assertTrue(FusionSpeciesTraitRules.requiredExpectations(
            setOf("SP003"), catalog).isEmpty())
        assertEquals(listOf("SA004"), FusionSpeciesTraitRules.requiredExpectations(
            setOf("SP006"), catalog).map { it.traitId })
    }

    @Test fun observedTraitSurvivesUnresolvedSpeciesButCannotOverrideKnownIncompatibility() {
        val image = FusionImageWideEvidencePolicy.reconcile(
            mapOf("SA002" to 0.87, "SP003" to 0.91),
            catalog, "asterion-wd-vit-tagger-v3",
        )
        assertTrue("SA002" in image.accepted)
        val missingSpecies = FusionImageWideEvidencePolicy.reconcile(
            mapOf("SA002" to 0.87), catalog, "asterion-wd-vit-tagger-v3",
        )
        assertEquals(mapOf("SA002" to 0.87), missingSpecies.accepted)
        val forbidden = FusionImageWideEvidencePolicy.reconcile(
            mapOf("SA002" to 0.87, "SP005" to 0.89), catalog,
            "asterion-wd-vit-tagger-v3",
        )
        assertFalse("SA002" in forbidden.accepted)
    }

    @Test fun manuallyConfirmedSpeciesAllowsOptionalVisualTraitWithoutFakeSpScore() {
        val result = FusionImageWideEvidencePolicy.reconcile(
            mapOf("SA002" to 0.89), catalog, "asterion-wd-vit-tagger-v3",
            verifiedSpeciesIds = setOf("SP003"),
        )
        assertEquals(mapOf("SA002" to 0.89), result.accepted)
        assertTrue(result.deferred.isEmpty())
        val disallowed = FusionImageWideEvidencePolicy.reconcile(
            mapOf("SA002" to 0.89), catalog, "asterion-wd-vit-tagger-v3",
            verifiedSpeciesIds = setOf("SP005"),
        )
        assertTrue(disallowed.accepted.isEmpty())
    }
}
