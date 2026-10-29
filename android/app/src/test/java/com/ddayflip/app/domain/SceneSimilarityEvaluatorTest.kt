package com.ddayflip.app.domain

import com.ddayflip.app.core.domain.SceneSimilarityEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneSimilarityEvaluatorTest {

    private val evaluator = SceneSimilarityEvaluator()

    @Test
    fun `identical pixel arrays produce cosine similarity close to 1_0`() {
        val w = 16
        val h = 16
        // 웜 린넨/침구 배경 픽셀 배열 생성
        val pixels1 = IntArray(w * h) { idx ->
            val r = 230 - (idx % 16)
            val g = 215 - (idx / 16)
            val b = 195
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val pixels2 = pixels1.clone()

        val vec1 = evaluator.extractGridFeatureVector(pixels1, w, h)
        val vec2 = evaluator.extractGridFeatureVector(pixels2, w, h)
        val similarity = evaluator.calculateCosineSimilarity(vec1, vec2)

        assertEquals(1.0, similarity, 0.001)
    }

    @Test
    fun `completely different color distributions have lower similarity than matching scene`() {
        val w = 16
        val h = 16
        // 침대 위 밝은 크림색 상단 + 브라운 하단 구도
        val bedScene1 = IntArray(w * h) { idx ->
            if (idx < (w * h) / 2) {
                (0xFF shl 24) or (240 shl 16) or (235 shl 8) or 220
            } else {
                (0xFF shl 24) or (160 shl 16) or (110 shl 8) or 80
            }
        }
        // 거의 동일한 침대 구도
        val bedScene2 = IntArray(w * h) { idx ->
            if (idx < (w * h) / 2) {
                (0xFF shl 24) or (235 shl 16) or (230 shl 8) or 215
            } else {
                (0xFF shl 24) or (155 shl 16) or (105 shl 8) or 75
            }
        }
        // 반전된 푸른 야외 풍경 구도 (하단 밝은 초록, 상단 진한 파랑)
        val outdoorScene = IntArray(w * h) { idx ->
            if (idx < (w * h) / 2) {
                (0xFF shl 24) or (10 shl 16) or (40 shl 8) or 200
            } else {
                (0xFF shl 24) or (30 shl 16) or (180 shl 8) or 40
            }
        }

        val refVec = evaluator.extractGridFeatureVector(bedScene1, w, h)
        val sameBedVec = evaluator.extractGridFeatureVector(bedScene2, w, h)
        val outdoorVec = evaluator.extractGridFeatureVector(outdoorScene, w, h)

        val simSame = evaluator.calculateCosineSimilarity(refVec, sameBedVec)
        val simDiff = evaluator.calculateCosineSimilarity(refVec, outdoorVec)

        assertTrue("Matching bed scene should have high similarity (>0.98), was $simSame", simSame > 0.98)
        assertTrue("Different scene should have noticeably lower similarity ($simDiff < $simSame)", simDiff < simSame)
    }

    @Test
    fun `calculateWarmSkinToneRatio detects warm baby skin pixels in center region`() {
        val w = 20
        val h = 20
        // 아기 피부톤 (R=235, G=190, B=165 -> R > G > B, 따뜻한 살구색)
        val babyPhotoPixels = IntArray(w * h) {
            (0xFF shl 24) or (235 shl 16) or (190 shl 8) or 165
        }
        // 차가운 회색/파란 사물 사진
        val coldObjectPixels = IntArray(w * h) {
            (0xFF shl 24) or (90 shl 16) or (120 shl 8) or 180
        }

        val warmRatio = evaluator.calculateWarmSkinToneRatio(babyPhotoPixels, w, h)
        val coldRatio = evaluator.calculateWarmSkinToneRatio(coldObjectPixels, w, h)

        assertTrue("Baby skin tone photo should have high warm ratio (>0.5)", warmRatio > 0.5)
        assertEquals(0.0, coldRatio, 0.001)
        assertTrue(evaluator.isLikelyBabyPhoto(babyPhotoPixels, w, h))
        assertFalse(evaluator.isLikelyBabyPhoto(coldObjectPixels, w, h))
    }
}
