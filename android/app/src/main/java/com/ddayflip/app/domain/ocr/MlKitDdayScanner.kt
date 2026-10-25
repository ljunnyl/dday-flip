package com.ddayflip.app.domain.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
    suspend fun scanDday(bitmap: Bitmap, expectedDay: Int? = null): DdayParseResult?
}

/**
 * Google ML Kit 기반 온디바이스 D-day 텍스트 인식 엔진.
 * 네트워크 통신 없이 로컬 프로세서로만 동작하며 상단 35% 타일링 우선 인식 전략을 적용.
 */
class MlKitDdayScanner(
    private val parser: DdayTextParser = DdayTextParser(),
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
) : DdayScanner {

    override suspend fun scanDday(bitmap: Bitmap, expectedDay: Int?): DdayParseResult? =
        withContext(Dispatchers.Default) {
            if (bitmap.width <= 0 || bitmap.height <= 0) return@withContext null

            // 1. 타일링 전략: 상단 35% 영역(아기 달력 거치 위치) 크롭 우선 인식
            val topCropHeight = (bitmap.height * 0.35f).toInt().coerceAtLeast(1)
            var croppedBitmap: Bitmap? = null
            try {
                croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, topCropHeight)
                val topBlocks = runOcr(croppedBitmap)
                val topResult = parser.parse(topBlocks, expectedDay)

                // 신뢰도가 높은 결과(0.85 이상)가 나오면 전체 스캔 생략하고 즉시 반환
                if (topResult != null && topResult.confidence >= 0.85f) {
                    return@withContext topResult
                }
            } catch (e: Exception) {
                // 상단 크롭/인식 오류 시 전체 비트맵 인식으로 fallback
            } finally {
                if (croppedBitmap != null && croppedBitmap != bitmap && !croppedBitmap.isRecycled) {
                    croppedBitmap.recycle()
                }
            }

            // 2. 상단에서 발견하지 못했거나 신뢰도가 낮으면 전체 비트맵 인식 수행
            try {
                val fullBlocks = runOcr(bitmap)
                return@withContext parser.parse(fullBlocks, expectedDay)
            } catch (e: Exception) {
                return@withContext null
            }
        }

    /**
     * ML Kit 비동기 텍스트 인식을 코루틴 suspend 함수로 변환
     */
    private suspend fun runOcr(bitmap: Bitmap): List<String> =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (continuation.isActive) {
                        val blocks = visionText.textBlocks.map { it.text }
                        continuation.resume(blocks)
                    }
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
        }

    override fun close() {
        recognizer.close()
    }
}
