package com.ddayflip.app.core.domain

import kotlin.math.pow

/**
 * Candidate photo evaluated for sharpness.
 */
data class PhotoCandidate(
    val id: String,
    val dday: Int,
    val sharpnessScore: Double,
    val uri: String = ""
)

class ImageSharpnessEvaluator {

    /**
     * Calculates the Laplacian variance (sharpness score) of a grayscale pixel array.
     * Higher variance indicates sharper edges and clearer focus.
     *
     * @param grayscalePixels 1D array of grayscale intensity (0..255) of size width * height
     * @param width Width of the image in pixels
     * @param height Height of the image in pixels
     * @return Variance of the Laplacian convolution values
     */
    fun calculateLaplacianVariance(
        grayscalePixels: IntArray,
        width: Int,
        height: Int
    ): Double {
        if (width < 3 || height < 3 || grayscalePixels.size < width * height) {
            return 0.0
        }

        // 3x3 Laplacian kernel:
        // [ 0,  1,  0]
        // [ 1, -4,  1]
        // [ 0,  1,  0]
        val validWidth = width - 2
        val validHeight = height - 2
        val count = validWidth * validHeight

        var sum = 0.0
        val laplacianValues = DoubleArray(count)
        var idx = 0

        for (y in 1 until height - 1) {
            val rowOffset = y * width
            val topOffset = (y - 1) * width
            val bottomOffset = (y + 1) * width

            for (x in 1 until width - 1) {
                val center = grayscalePixels[rowOffset + x]
                val top = grayscalePixels[topOffset + x]
                val bottom = grayscalePixels[bottomOffset + x]
                val left = grayscalePixels[rowOffset + (x - 1)]
                val right = grayscalePixels[rowOffset + (x + 1)]

                val lap = (top + bottom + left + right - 4 * center).toDouble()
                laplacianValues[idx++] = lap
                sum += lap
            }
        }

        val mean = sum / count
        var varianceSum = 0.0
        for (v in laplacianValues) {
            varianceSum += (v - mean).pow(2)
        }

        return varianceSum / count
    }

    /**
     * Given multiple candidates for a single day, selects the photo with the highest sharpness score.
     */
    fun selectSharpest(candidates: List<PhotoCandidate>): PhotoCandidate? {
        return candidates.maxByOrNull { it.sharpnessScore }
    }

    /**
     * Groups photo candidates by D-day and selects exactly one sharpest photo for each day.
     *
     * @param candidates List of all photo candidates
     * @return Map of D-day to the single sharpest PhotoCandidate
     */
    fun selectBestPhotosPerDay(candidates: List<PhotoCandidate>): Map<Int, PhotoCandidate> {
        return candidates
            .groupBy { it.dday }
            .mapValues { (_, dayCandidates) ->
                selectSharpest(dayCandidates)!!
            }
    }
}
