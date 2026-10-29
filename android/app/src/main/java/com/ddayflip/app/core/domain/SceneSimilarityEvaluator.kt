package com.ddayflip.app.core.domain

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * 기준 사진(침대/배경 구도)과의 4x4 그리드 색상/루마 코사인 유사도 및
 * 아기 얼굴/피부톤 비율을 계산하는 순수 도메인 평가기.
 */
class SceneSimilarityEvaluator(
    private val gridRows: Int = 4,
    private val gridCols: Int = 4
) {

    /**
     * ARGB 픽셀 배열(width x height)로부터 4x4 그리드별 평균 [R, G, B, Luma] 특징 벡터를 추출합니다.
     */
    fun extractGridFeatureVector(pixels: IntArray, width: Int, height: Int): DoubleArray {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return DoubleArray(gridRows * gridCols * 4)
        }

        val features = DoubleArray(gridRows * gridCols * 4)
        var featureIdx = 0

        for (r in 0 until gridRows) {
            val yStart = (r * height) / gridRows
            val yEnd = ((r + 1) * height) / gridRows

            for (c in 0 until gridCols) {
                val xStart = (c * width) / gridCols
                val xEnd = ((c + 1) * width) / gridCols

                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0
                var sumLuma = 0.0
                var count = 0

                for (y in yStart until yEnd) {
                    val rowOffset = y * width
                    for (x in xStart until xEnd) {
                        val color = pixels[rowOffset + x]
                        val red = ((color shr 16) and 0xFF).toDouble()
                        val green = ((color shr 8) and 0xFF).toDouble()
                        val blue = (color and 0xFF).toDouble()
                        val luma = 0.299 * red + 0.587 * green + 0.114 * blue

                        sumR += red
                        sumG += green
                        sumB += blue
                        sumLuma += luma
                        count++
                    }
                }

                val denom = if (count > 0) count.toDouble() * 255.0 else 1.0
                features[featureIdx++] = sumR / denom
                features[featureIdx++] = sumG / denom
                features[featureIdx++] = sumB / denom
                features[featureIdx++] = sumLuma / denom
            }
        }

        return features
    }

    /**
     * 두 그리드 특징 벡터 간의 코사인 유사도(0.0 ~ 1.0)를 계산합니다.
     */
    fun calculateCosineSimilarity(vecA: DoubleArray, vecB: DoubleArray): Double {
        if (vecA.isEmpty() || vecB.isEmpty() || vecA.size != vecB.size) return 0.0

        var dot = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in vecA.indices) {
            val a = vecA[i]
            val b = vecB[i]
            dot += a * b
            normA += a * a
            normB += b * b
        }

        val denom = sqrt(normA) * sqrt(normB)
        if (denom <= 1e-12) return 0.0
        return (dot / denom).coerceIn(0.0, 1.0)
    }

    /**
     * 사진 중앙 영역(아기가 주로 위치하는 영역)의 따뜻한 피부톤 픽셀 비율(0.0 ~ 1.0)을 계산합니다.
     */
    fun calculateWarmSkinToneRatio(pixels: IntArray, width: Int, height: Int): Double {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return 0.0

        val xStart = (width * 0.15).toInt()
        val xEnd = (width * 0.85).toInt().coerceAtLeast(xStart + 1)
        val yStart = (height * 0.15).toInt()
        val yEnd = (height * 0.85).toInt().coerceAtLeast(yStart + 1)

        var skinCount = 0
        var totalCount = 0

        for (y in yStart until yEnd) {
            val rowOffset = y * width
            for (x in xStart until xEnd) {
                val color = pixels[rowOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                // 따뜻한 아기 피부톤 및 밝은 살구색/크림색 판별 규칙
                val isWarmSkin = r in 115..255 &&
                    g in 75..235 &&
                    b in 55..215 &&
                    r > g &&
                    g >= b - 10 &&
                    (r - b) in 15..140

                if (isWarmSkin) {
                    skinCount++
                }
                totalCount++
            }
        }

        return if (totalCount > 0) skinCount.toDouble() / totalCount.toDouble() else 0.0
    }

    /**
     * 아기 얼굴/신체가 포함된 사진일 가능성을 판별합니다.
     */
    fun isLikelyBabyPhoto(pixels: IntArray, width: Int, height: Int, minSkinRatio: Double = 0.08): Boolean {
        return calculateWarmSkinToneRatio(pixels, width, height) >= minSkinRatio
    }

    /**
     * Android Bitmap에서 64x64 축소 픽셀을 추출해 특징 벡터를 생성하는 헬퍼.
     */
    fun extractFeatureFromBitmap(bitmap: Bitmap): DoubleArray {
        return try {
            val sampleW = 64
            val sampleH = 64
            val scaled = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, true)
            val pixels = IntArray(sampleW * sampleH)
            scaled.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
            if (scaled != bitmap) scaled.recycle()
            extractGridFeatureVector(pixels, sampleW, sampleH)
        } catch (_: Exception) {
            DoubleArray(gridRows * gridCols * 4)
        }
    }

    /**
     * Android Bitmap에서 아기 피부톤 비율을 계산하는 헬퍼.
     */
    fun calculateSkinRatioFromBitmap(bitmap: Bitmap): Double {
        return try {
            val sampleW = 64
            val sampleH = 64
            val scaled = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, true)
            val pixels = IntArray(sampleW * sampleH)
            scaled.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
            if (scaled != bitmap) scaled.recycle()
            calculateWarmSkinToneRatio(pixels, sampleW, sampleH)
        } catch (_: Exception) {
            0.0
        }
    }
}
