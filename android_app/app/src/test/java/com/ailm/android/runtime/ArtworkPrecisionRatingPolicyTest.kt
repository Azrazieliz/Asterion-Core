package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class ArtworkPrecisionRatingPolicyTest {
    @Test fun scoreIsFineGrainedAndBounded() {
        assertEquals(74, ArtworkPrecisionRatingPolicy.automaticScore(7.4))
        assertEquals(83, ArtworkPrecisionRatingPolicy.automaticScore(83.0))
        assertNull(ArtworkPrecisionRatingPolicy.automaticScore(Double.NaN))
        assertNull(ArtworkPrecisionRatingPolicy.automaticScore(101.0))
    }
    @Test fun zeroIsARealManualRatingAndClearIsDistinct() {
        assertTrue(ArtworkPrecisionRatingPolicy.validManual(0))
        assertTrue(ArtworkPrecisionRatingPolicy.validManual(-1))
        assertFalse(ArtworkPrecisionRatingPolicy.validManual(-2))
        assertEquals(0, ArtworkPrecisionRatingPolicy.displayedScore(0, 80))
        assertEquals(80, ArtworkPrecisionRatingPolicy.displayedScore(null, 80))
    }
    @Test fun facetCatalogIsDistinctFromContentRating() {
        assertTrue(ArtworkPrecisionRatingPolicy.validDimension("artstyle"))
        assertTrue(ArtworkPrecisionRatingPolicy.validDimension("composition"))
        assertTrue(ArtworkPrecisionRatingPolicy.validDimension("anatomy"))
        assertFalse(ArtworkPrecisionRatingPolicy.validDimension("color"))
        assertFalse(ArtworkPrecisionRatingPolicy.validDimension("RT"))
    }
}
