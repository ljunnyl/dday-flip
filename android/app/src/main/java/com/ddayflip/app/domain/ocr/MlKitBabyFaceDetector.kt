package com.ddayflip.app.domain.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.ddayflip.app.core.domain.DetectedFaceCandidate
import com.ddayflip.app.core.domain.ReferenceBabyProfile
import com.ddayflip.app.core.domain.SceneSimilarityEvaluator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Google ML Kit Face Detection 기반 100% 온디바이스 아기 얼굴 감지기.
 *
 * - 기준 아기 사진 1장에서 아기 얼굴 특징 프로필(`ReferenceBabyProfile`)을 추출하여 성인 사진과 구분합니다.
 * - 각 갤러리 사진에서 얼굴 영역, 눈/입 이목구비 비율, 눈 뜸 확률, 미소 확률을 계산합니다.
 */
class MlKitBabyFaceDetector(
    private val similarityEvaluator: SceneSimilarityEvaluator = SceneSimilarityEvaluator(),
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build()
    )
) : Closeable {

    private val TAG = "BabyFaceDetector"

    /**
     * 사용자가 선택한 '우리 아기 기준 사진 1장'에서 가장 주인공인 얼굴을 찾아 ReferenceBabyProfile을 생성합니다.
     */
    suspend fun extractReferenceProfile(bitmap: Bitmap): ReferenceBabyProfile? = withContext(Dispatchers.Default) {
        val candidates = detectFaces(bitmap)
        val primaryFace = candidates.maxByOrNull {
            it.boundingBoxAreaRatio * (1.2f - it.centerOffsetRatio.coerceIn(0f, 1f))
        } ?: return@withContext null

        ReferenceBabyProfile(
            faceFeatureVector = primaryFace.faceFeatureVector,
            faceWidthToHeightRatio = primaryFace.widthToHeightRatio,
            eyeToMouthRatio = primaryFace.eyeToMouthRatio
        )
    }

    /**
     * 사진 비트맵에서 검출된 모든 얼굴 후보 리스트를 반환합니다.
     */
    suspend fun detectFaces(bitmap: Bitmap): List<DetectedFaceCandidate> = withContext(Dispatchers.Default) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return@withContext emptyList()

        val rawFaces = runFaceDetection(bitmap)
        if (rawFaces.isEmpty()) return@withContext emptyList()

        val imgW = bitmap.width.toFloat()
        val imgH = bitmap.height.toFloat()
        val imgArea = (imgW * imgH).coerceAtLeast(1f)
        val centerX = imgW / 2f
        val centerY = imgH / 2f
        val maxDist = hypot(centerX, centerY).coerceAtLeast(1f)

        val results = mutableListOf<DetectedFaceCandidate>()

        for (face in rawFaces) {
            val box: Rect = face.boundingBox
            val clampedLeft = box.left.coerceIn(0, bitmap.width - 1)
            val clampedTop = box.top.coerceIn(0, bitmap.height - 1)
            val clampedW = box.width().coerceIn(1, bitmap.width - clampedLeft)
            val clampedH = box.height().coerceIn(1, bitmap.height - clampedTop)

            val areaRatio = (clampedW.toFloat() * clampedH.toFloat()) / imgArea
            val faceCenterX = clampedLeft + clampedW / 2f
            val faceCenterY = clampedTop + clampedH / 2f
            val centerOffset = (hypot(faceCenterX - centerX, faceCenterY - centerY) / maxDist).coerceIn(0f, 1f)
            val widthToHeight = (clampedW.toFloat() / clampedH.toFloat()).coerceIn(0.4f, 1.5f)

            // 눈~입 수직 거리 비율 계산 (랜드마크가 있을 때)
            val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
                ?: face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
            val eyeToMouthRatio = if (leftEye != null && rightEye != null && mouthBottom != null && clampedH > 0) {
                val eyeCenterY = (leftEye.y + rightEye.y) / 2f
                (abs(mouthBottom.y - eyeCenterY) / clampedH.toFloat()).coerceIn(0.15f, 0.75f)
            } else {
                0.35f
            }

            // 얼굴 영역 크롭 특징 벡터 추출
            val faceVec = try {
                val faceCrop = Bitmap.createBitmap(bitmap, clampedLeft, clampedTop, clampedW, clampedH)
                val vec = similarityEvaluator.extractFeatureFromBitmap(faceCrop)
                if (faceCrop != bitmap && !faceCrop.isRecycled) {
                    faceCrop.recycle()
                }
                vec
            } catch (_: Exception) {
                DoubleArray(0)
            }

            results.add(
                DetectedFaceCandidate(
                    boundingBoxAreaRatio = areaRatio,
                    centerOffsetRatio = centerOffset,
                    widthToHeightRatio = widthToHeight,
                    eyeToMouthRatio = eyeToMouthRatio,
                    leftEyeOpenProb = face.leftEyeOpenProbability ?: 0.75f,
                    rightEyeOpenProb = face.rightEyeOpenProbability ?: 0.75f,
                    smilingProb = face.smilingProbability ?: 0.50f,
                    faceFeatureVector = faceVec
                )
            )
        }

        results
    }

    private suspend fun runFaceDetection(bitmap: Bitmap): List<Face> =
        suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    continuation.resume(faces ?: emptyList())
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Face detection failed: ${e.message}")
                    continuation.resume(emptyList())
                }
        }

    override fun close() {
        try {
            detector.close()
        } catch (_: Exception) {}
    }
}
