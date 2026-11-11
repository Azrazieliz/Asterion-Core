package com.ailm.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class FusionSpeciesTraitRulesTest {
    private fun tag(
        id: String, name: String, parent: String = "", rule: String = "",
    ) = FusionTaxonomyEngine.Candidate(id, name, id.take(2), parentId = parent, traitRequirement = rule)

    private val species = listOf(
        tag("SP001", "Human"),
        tag("SP002", "Elf"),
        tag("SP003", "Dragon"),
        tag("SA001", "Pointed Ears", "SP002"),
        tag("SA002", "Wings"),
        tag("SA003", "Horns", "SP003"),
        tag("SA004", "Animal Ears"),
        tag("SA005", "Scales", "SP003", "optional"),
        tag("SA006", "Forbidden attribute", "SP003", "forbidden"),
    ).associateBy { it.id }

    private fun rule(speciesId: String, attributeId: String) =
        FusionSpeciesTraitRules.status(species.getValue(speciesId),
            species.getValue(attributeId), species)

    @Test fun elfEarsAreRequiredNotAnUnconditionalVisualObservation() {
        assertEquals(FusionSpeciesTraitRules.Status.REQUIRED, rule("SP002", "SA001"))
        assertEquals(FusionSpeciesTraitRules.Status.FORBIDDEN, rule("SP001", "SA001"))
        assertEquals(listOf("SA001"),
            FusionSpeciesTraitRules.requiredExpectations(setOf("SP002"), species).map { it.traitId })
        assertTrue(FusionSpeciesTraitRules.requiredExpectations(setOf("SP001"), species).isEmpty())
    }

    @Test fun dragonWingsAreOptionalAndNeverGeneratedBySpeciesAlone() {
        assertEquals(FusionSpeciesTraitRules.Status.OPTIONAL, rule("SP003", "SA002"))
        assertEquals(FusionSpeciesTraitRules.Status.FORBIDDEN, rule("SP002", "SA002"))
        assertEquals(FusionSpeciesTraitRules.Status.FORBIDDEN, rule("SP001", "SA002"))
        assertFalse(FusionSpeciesTraitRules.requiredExpectations(setOf("SP003"), species)
            .any { it.traitId == "SA002" })
    }

    @Test fun taggedSpeciesAnatomyUsesImportedParentAndRequirement() {
        assertEquals(FusionSpeciesTraitRules.Status.OPTIONAL, rule("SP003", "SA003"))
        assertEquals(FusionSpeciesTraitRules.Status.OPTIONAL, rule("SP003", "SA005"))
        assertEquals(FusionSpeciesTraitRules.Status.FORBIDDEN, rule("SP003", "SA006"))
        assertEquals(FusionSpeciesTraitRules.Status.UNLINKED, rule("SP002", "SA004"))
        assertEquals(FusionSpeciesTraitRules.Status.FORBIDDEN, rule("SP001", "SA004"))
    }

    @Test fun imageWideSpeciesTagMustBeConfidentBeforeAnatomicalTraitIsAccepted() {
        val linked = species + mapOf(
            "HC001" to tag("HC001", "Black"),
        )
        val scores = linkedMapOf("SA003" to 0.91, "SP003" to 0.90, "HC001" to 0.72)
        val accepted = FusionImageWideEvidencePolicy.reconcile(
            scores, linked, "asterion-wd-vit-tagger-v3")
        assertTrue("SA003" in accepted.accepted)
        assertTrue(accepted.deferred.isEmpty())

        val absentSpecies = FusionImageWideEvidencePolicy.reconcile(
            mapOf("SA003" to 0.91), linked, "asterion-wd-vit-tagger-v3")
        assertTrue(absentSpecies.accepted.isEmpty())
        assertEquals("species_not_enabled_for_attribute", absentSpecies.deferred.single().reason)

        val weakSpecies = FusionImageWideEvidencePolicy.reconcile(
            mapOf("SA003" to 0.91, "SP003" to 0.48), linked, "asterion-wd-vit-tagger-v3")
        assertFalse("SA003" in weakSpecies.accepted)
        assertEquals("species_not_enabled_for_attribute", weakSpecies.deferred.single().reason)
    }

    @Test fun multipleSpeciesAreNotSilentlyMergedIntoOneCharacter() {
        val result = FusionImageWideEvidencePolicy.reconcile(
            mapOf("SP001" to 0.82, "SP003" to 0.84, "SA002" to 0.79),
            species, "asterion-wd-vit-tagger-v3")
        assertTrue("SA002" in result.accepted) // image-wide only
        assertTrue("SP001" in result.accepted)
        assertTrue("SP003" in result.accepted)
    }
}
