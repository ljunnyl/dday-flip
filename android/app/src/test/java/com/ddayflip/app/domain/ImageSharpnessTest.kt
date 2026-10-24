package com.ddayflip.app.domain

import com.ddayflip.app.core.domain.ImageSharpnessEvaluator
import com.ddayflip.app.core.domain.PhotoCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSharpnessTest {

    private val evaluator = ImageSharpnessEvaluator()

    @Test
    fun `test selects photo with highest sharpness score for same day`() {
        val candidatesSameDay = listOf(
            PhotoCandidate(id = "photo_blur", dday = 10, sharpnessScore = 32.5, uri = "content://media/1"),
            PhotoCandidate(id = "photo_sharpest", dday = 10, sharpnessScore = 158.4, uri = "content://media/2"),
            PhotoCandidate(id = "photo_mediocre", dday = 10, sharpnessScore = 78.1, uri = "content://media/3")
        )

        val selected = evaluator.selectSharpest(candidatesSameDay)

        assertNotNull(selected)
        assertEquals("photo_sharpest", selected?.id)
        assertEquals(158.4, selected?.sharpnessScore ?: 0.0, 0.001)
    }

    @Test
    fun `test selects one best photo per day across multiple days`() {
        val allCandidates = listOf(
            // Day 10 photos
            PhotoCandidate(id = "d10_1", dday = 10, sharpnessScore = 40.0),
            PhotoCandidate(id = "d10_2", dday = 10, sharpnessScore = 110.0),
            // Day 11 photos
            PhotoCandidate(id = "d11_1", dday = 11, sharpnessScore = 200.0),
            PhotoCandidate(id = "d11_2", dday = 11, sharpnessScore = 95.0),
            // Day 12 single photo
            PhotoCandidate(id = "d12_1", dday = 12, sharpnessScore = 85.0)
        )

        val bestByDay = evaluator.selectBestPhotosPerDay(allCandidates)

        assertEquals(3, bestByDay.size)
        assertEquals("d10_2", bestByDay[10]?.id)
        assertEquals("d11_1", bestByDay[11]?.id)
        assertEquals("d12_1", bestByDay[12]?.id)
    }

    @Test
    fun `test laplacian variance gives higher score for sharp high contrast pattern than flat blur`() {
        val width = 10
        val height = 10

        // Flat uniform blurry image (all pixels nearly identical)
        val blurryPixels = IntArray(width * height) { 128 }
        
        // Sharp high-contrast checkerboard image
        val sharpPixels = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            if ((x + y) % 2 == 0) 255 else 0
        }

        val blurScore = evaluator.calculateLaplacianVariance(blurryPixels, width, height)
        val sharpScore = evaluator.calculateLaplacianVariance(sharpPixels, width, height)

        assertEquals(0.0, blurScore, 0.01)
        assertTrue("Sharp score ($sharpScore) should be much higher than blur score ($blurScore)", sharpScore > blurScore)
        assertTrue(sharpScore > 500.0)
    }
}
