package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class AnimePersonBoxDecoderTest {
    @Test fun multipleAnimePeopleProduceDistinctStableBoxes() {
        // Raw ONNX layout [1, 5, 3], two separated people + one duplicate.
        val output = listOf(
            170f, 460f, 172f, // center x
            280f, 285f, 281f, // center y
            140f, 140f, 140f, // width
            300f, 300f, 300f, // height
            0.92f, 0.87f, 0.60f, // score
        )
        val boxes = AnimePersonBoxDecoder.decode(
            output, longArrayOf(1, 5, 3), 1280, 720,
        )
        assertEquals(2, boxes.size)
        assertTrue(boxes[0].left < boxes[1].left)
        assertEquals(0.92, boxes[0].score, 0.0001)
        assertTrue(boxes.all { it.width > 50 && it.height > 50 })
        assertTrue(boxes.all { it.left >= 0 && it.right <= 1280 })
        assertTrue(boxes.all { it.top >= 0 && it.bottom <= 720 })
    }

    @Test fun alternateOutputLayoutRetainsSameCoordinateSpace() {
        val byColumn = listOf(
            170f, 280f, 140f, 300f, 0.92f,
            460f, 285f, 140f, 300f, 0.87f,
        )
        val boxes = AnimePersonBoxDecoder.decode(
            byColumn, longArrayOf(1, 2, 5), 1280, 720,
        )
        assertEquals(2, boxes.size)
        assertTrue(boxes[1].right > boxes[0].right)
    }

    @Test fun invalidShapesEmptyImagesAndWeakOutputsNeverCreateSubjects() {
        assertTrue(AnimePersonBoxDecoder.decode(
            listOf(200f, 200f, 300f, 300f, 0.99f),
            longArrayOf(1, 7, 1), 800, 800).isEmpty())
        assertTrue(AnimePersonBoxDecoder.decode(
            listOf(200f, 200f, 300f, 300f, 0.99f),
            longArrayOf(1, 5, 1), 0, 800).isEmpty())
        assertTrue(AnimePersonBoxDecoder.decode(
            listOf(200f, 200f, 300f, 300f, Float.NaN),
            longArrayOf(1, 5, 1), 800, 800).isEmpty())
        assertTrue(AnimePersonBoxDecoder.decode(
            listOf(200f, 200f, 300f, 300f, 0.20f),
            longArrayOf(1, 5, 1), 800, 800).isEmpty())
    }

    @Test fun subjectOverlapIsMeasuredInsteadOfBlindlySharingTags() {
        val a = AnimePersonBoxDecoder.Region(20, 30, 320, 500, 0.9)
        val b = AnimePersonBoxDecoder.Region(210, 200, 480, 550, 0.8)
        val c = AnimePersonBoxDecoder.Region(600, 10, 800, 300, 0.8)
        assertTrue(AnimePersonBoxDecoder.intersectionOverUnion(a, b) > 0)
        assertEquals(0.0, AnimePersonBoxDecoder.intersectionOverUnion(a, c), 0.0001)
    }
}
