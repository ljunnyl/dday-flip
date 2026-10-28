package com.ddayflip.app.domain.ocr

import android.graphics.Bitmap
import android.util.Log
import com.ddayflip.app.core.domain.CalendarRoi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * D-day OCR 스캐너 인터페이스
 */
interface DdayScanner : Closeable {
    /**
     * 이미지 비트맵에서 D-day 숫자를 스캔하여 파싱 결과 반환
     */
    suspend fun scanDday(
        bitmap: Bitmap,
        expectedDay: Int? = null,
        roi: CalendarRoi? = null
    ): DdayParseResult?
}

/**
 * Google ML Kit 기반 온디바이스 D-day 텍스트 인식 엔진 (한글 + 영문/숫자).
 * 네트워크 통신 없이 로컬 프로세서로만 동작하며 ROI 우선 및 전체 스캔 fallback 적용.
 */
class MlKitDdayScanner(
    private val parser: DdayTextParser = DdayTextParser(),
    private val recognizer: TextRecognizer = TextRecognition.getClient(
        KoreanTextRecognizerOptions.Builder().build()
    )
) : DdayScanner {

    private val TAG = "DdayScanner"

    override suspend fun scanDday(
        bitmap: Bitmap,
        expectedDay: Int?,
        roi: CalendarRoi?
    ): DdayParseResult? = withContext(Dispatchers.Default) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return@withContext null

        // 1. ROI 지정 영역 크롭 우선 인식 (마진 25% 확장)
        if (roi != null) {
            val marginRoi = roi.withMargin(0.25f)
            val cropX = (bitmap.width * marginRoi.x).toInt().coerceIn(0, bitmap.width - 1)
            val cropY = (bitmap.height * marginRoi.y).toInt().coerceIn(0, bitmap.height - 1)
            val cropW = (bitmap.width * marginRoi.width).toInt().coerceIn(1, bitmap.width - cropX)
            val cropH = (bitmap.height * marginRoi.height).toInt().coerceIn(1, bitmap.height - cropY)

            var roiCroppedBitmap: Bitmap? = null
            try {
                roiCroppedBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
                val roiBlocks = runOcr(roiCroppedBitmap)
                Log.d(TAG, "ROI OCR detected blocks: $roiBlocks")
                val roiResult = parser.parse(roiBlocks, expectedDay)
                if (roiResult != null) {
                    Log.d(TAG, "ROI OCR Matched D-day: ${roiResult.dday} (conf: ${roiResult.confidence})")
                    return@withContext roiResult
                }
            } catch (e: Exception) {
                Log.e(TAG, "ROI crop error: ${e.message}")
            } finally {
                if (roiCroppedBitmap != null && roiCroppedBitmap != bitmap && !roiCroppedBitmap.isRecycled) {
                    roiCroppedBitmap.recycle()
                }
            }
        }

        // 2. 전체 비트맵 인식 수행
        try {
            val fullBlocks = runOcr(bitmap)
            Log.d(TAG, "Full OCR detected blocks (${fullBlocks.size}): $fullBlocks")
            val fullResult = parser.parse(fullBlocks, expectedDay)
            if (fullResult != null) {
                Log.d(TAG, "Full OCR Matched D-day: ${fullResult.dday} (conf: ${fullResult.confidence})")
                return@withContext fullResult
            }
        } catch (e: Exception) {
            Log.e(TAG, "Full OCR scan error: ${e.message}")
        }

        return@withContext null
    }

    /**
     * ML Kit 비동기 텍스트 인식 실행
     */
    private suspend fun runOcr(bitmap: Bitmap): List<String> =
        suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val textBlocks = visionText.textBlocks.map { it.text }
                    continuation.resume(textBlocks)
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "ML Kit Recognizer failure: ${exception.message}", exception)
                    continuation.resumeWithException(exception)
                }
        }

    override fun close() {
        recognizer.close()
    }
}
