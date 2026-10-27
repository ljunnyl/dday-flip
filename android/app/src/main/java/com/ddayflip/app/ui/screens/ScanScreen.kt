package com.ddayflip.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddayflip.app.core.domain.CalendarPhotoEntry
import com.ddayflip.app.core.domain.CalendarRoi
import com.ddayflip.app.core.domain.CalendarSequenceValidator
import com.ddayflip.app.core.domain.DdayCalculator
import com.ddayflip.app.core.domain.DdayStartRule
import com.ddayflip.app.core.domain.SequenceValidationResult
import com.ddayflip.app.data.MediaStorePhotoRepository
import com.ddayflip.app.domain.ocr.DdayScanner
import com.ddayflip.app.domain.ocr.MlKitDdayScanner
import com.ddayflip.app.domain.repository.PhotoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * 02. 온디바이스 OCR 스캔 및 실시간 진행 화면
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    startDate: LocalDate = LocalDate.now().minusDays(150),
    endDate: LocalDate = LocalDate.now(),
    birthday: LocalDate = startDate,
    ddayRule: DdayStartRule = DdayStartRule.ONE_BASED,
    calendarRoi: CalendarRoi? = null,
    todayMaxDday: Int? = null,
    photoRepository: PhotoRepository? = null,
    ddayScanner: DdayScanner? = null,
    onNavigateToReview: (SequenceValidationResult) -> Unit = {}
) {
    val context = LocalContext.current
    val repository = photoRepository ?: remember { MediaStorePhotoRepository(context) }
    val scanner = ddayScanner ?: remember { MlKitDdayScanner() }
    val ddayCalculator = remember(birthday, ddayRule) {
        DdayCalculator(birthday = birthday, rule = ddayRule)
    }

    var totalPhotos by remember { mutableIntStateOf(0) }
    var processedPhotos by remember { mutableIntStateOf(0) }
    var detectedCalendarPhotos by remember { mutableIntStateOf(0) }
    var currentStatusText by remember { mutableStateOf("스캔 준비 중...") }
    var isScanning by remember { mutableStateOf(true) }
    var validationResult by remember {
        mutableStateOf(SequenceValidationResult(emptyList(), emptyList(), emptyList()))
    }

    LaunchedEffect(startDate, endDate) {
        isScanning = true
        processedPhotos = 0
        detectedCalendarPhotos = 0
        currentStatusText = "갤러리 사진 목록을 불러오는 중..."

        val photos = withContext(Dispatchers.IO) {
            repository.getPhotosBetween(startDate, endDate)
        }
        totalPhotos = photos.size

        if (photos.isEmpty()) {
            currentStatusText = "해당 기간에 촬영된 사진이 없습니다."
            isScanning = false
            return@LaunchedEffect
        }

        val entries = mutableListOf<CalendarPhotoEntry>()

        for ((index, photo) in photos.withIndex()) {
            processedPhotos = index + 1

            // 1. 촬영 시간 기반 EXIF D-day 계산 (00~04시 심야 보정 포함)
            val photoDateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(photo.dateTakenMs),
                ZoneId.systemDefault()
            )
            val calcResult = ddayCalculator.calculate(photoDateTime)
            val expectedDday = calcResult.dday

            currentStatusText = "사진 분석 중 (${index + 1}/${photos.size}): EXIF D+$expectedDday"

            // 2. 비트맵 디코딩 및 온디바이스 OCR 스캔 (ROI 전달)
            val bitmap = withContext(Dispatchers.IO) {
                loadScaledBitmap(context, photo.contentUri)
            }

            var detectedDday: Int? = null
            if (bitmap != null) {
                val ocrResult = scanner.scanDday(bitmap, expectedDay = expectedDday, roi = calendarRoi)
                if (ocrResult != null) {
                    val rawDday = ocrResult.dday
                    // OCR 노이즈 가드: ±3일 초과 편차 시 잡숫자로 간주하여 fallback
                    if (abs(rawDday - expectedDday) <= 3) {
                        detectedDday = rawDday
                        detectedCalendarPhotos++
                        currentStatusText = "현재 사진: D+$detectedDday 감지됨 (신뢰도 ${(ocrResult.confidence * 100).toInt()}%)"
                    } else {
                        currentStatusText = "현재 사진: OCR 숫자(D+$rawDday) 노이즈 감지 -> EXIF D+$expectedDday 로 대체"
                    }
                }
                bitmap.recycle()
            }

            entries.add(
                CalendarPhotoEntry(
                    id = photo.id.toString(),
                    effectiveDate = calcResult.effectiveDate,
                    exifDday = expectedDday,
                    ocrDday = detectedDday,
                    resolvedDday = detectedDday ?: expectedDday,
                    photoUri = photo.contentUri.toString()
                )
            )
        }

        // 3. 전체 시퀀스 스마트 검증 (결측일, 미넘김 의심 등, 오늘 상한선 적용)
        val validator = CalendarSequenceValidator()
        validationResult = validator.validateSequence(entries, todayMaxDday = todayMaxDday)
        currentStatusText = "분석 완료: 총 ${photos.size}장 중 ${detectedCalendarPhotos}장 D-day 달력 인식"
        isScanning = false
    }

    val progress = if (totalPhotos > 0) {
        processedPhotos.toFloat() / totalPhotos.toFloat()
    } else if (!isScanning) {
        1f
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "scan_progress")
    val isComplete = !isScanning && (processedPhotos >= totalPhotos)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("온디바이스 사진 분석", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                // Status Icon
                if (isComplete) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Analysis Complete",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.HourglassTop,
                        contentDescription = "Analyzing",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp)
                    )
                }

                Text(
                    text = if (isComplete) {
                        if (totalPhotos == 0) "해당 기간에 촬영된 사진이 없습니다" else "스캔 및 분석 완료!"
                    } else {
                        "갤러리 사진을 분석하는 중..."
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                // Current Live Status Text
                Text(
                    text = currentStatusText,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Progress Bar
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (totalPhotos > 0) "$processedPhotos / $totalPhotos 장" else "0 장",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Step Status Cards
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StepItem(
                            stepNumber = "1",
                            title = "EXIF 촬영일시 추출 및 심야(00~04시) 보정",
                            isDone = processedPhotos > 0
                        )
                        StepItem(
                            stepNumber = "2",
                            title = "온디바이스 OCR D-day 달력 인식 ($detectedCalendarPhotos 장 감지)",
                            isDone = detectedCalendarPhotos > 0 || (isComplete && totalPhotos > 0)
                        )
                        StepItem(
                            stepNumber = "3",
                            title = "연속성 검증 (D-day 달력 미넘김/누락일 감지: ${validationResult.issues.size}건)",
                            isDone = isComplete && totalPhotos > 0
                        )
                    }
                }

                // Security Note
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF1F8E9), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = Color(0xFF33691E),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "네트워크 요청 없음: 기기 내부 프로세서로만 안전하게 처리 중",
                        color = Color(0xFF33691E),
                        fontSize = 12.sp
                    )
                }
            }

            // Bottom Action Button
            Button(
                onClick = { onNavigateToReview(validationResult) },
                enabled = isComplete && totalPhotos > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("스마트 검수 및 타임라인 확인", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StepItem(stepNumber: String, title: String, isDone: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Badge(
            containerColor = if (isDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ) {
            Text(stepNumber, color = MaterialTheme.colorScheme.surface)
        }
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = if (isDone) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isDone) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 메모리 절약을 위한 스케일 다운 비트맵 디코딩 헬퍼
 */
private fun loadScaledBitmap(context: Context, uri: Uri, targetMaxDim: Int = 1200): Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(stream, null, options)
            val maxDim = maxOf(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxDim / (sampleSize * 2) >= targetMaxDim) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            }
        }
    } catch (e: Exception) {
        null
    }
}
