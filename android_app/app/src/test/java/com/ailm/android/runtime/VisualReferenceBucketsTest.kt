package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class VisualReferenceBucketsTest {
    @Test fun alternateCostumeAndIndependentSubjectsReachReviewOutsideAttributeTop80() {
        fun feature(seed: Int): List<Double> {
            val random = Random(seed)
            return List(768) { random.nextDouble(-1.0,1.0) }
        }
        val profiles = (0..120).associateWith(::feature)
        val index = mutableMapOf<Int,MutableSet<Int>>()
        for ((id,vector) in profiles) for (key in VisualReferenceBuckets.keys(vector))
            index.getOrPut(key) { mutableSetOf() }.add(id)
        for (target in listOf(113,97)) {
            val crop = profiles.getValue(target).mapIndexed { i,v -> v + if (i % 3 == 0) .015 else -.015 }
            val retrieved = VisualReferenceBuckets.keys(crop).flatMap { index[it].orEmpty() }.toSet()
            assertTrue(target in retrieved)
            val attr = (0 until 80).toList()
            val review = VisualReferenceBuckets.reviewCandidates(attr + (retrieved - attr.toSet()), 8) { id ->
                val ref = profiles.getValue(id)
                crop.indices.sumOf { crop[it] * ref[it] }
            }
            assertTrue(target in review)
            assertEquals(8, review.size)
        }
        assertTrue(VisualReferenceBuckets.keys(List(768) { 0.0 }).isEmpty())
        assertTrue(VisualReferenceBuckets.keys(List(32) { Double.NaN }).isEmpty())
    }
}
