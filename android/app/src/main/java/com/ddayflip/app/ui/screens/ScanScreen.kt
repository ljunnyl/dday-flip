package com.ddayflip.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
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
import com.ddayflip.app.core.domain.CalendarRoi
import com.ddayflip.app.core.domain.DdayStartRule
import com.ddayflip.app.core.domain.SequenceValidationResult
import com.ddayflip.app.domain.ocr.DdayScanner
import com.ddayflip.app.domain.repository.PhotoRepository
import com.ddayflip.app.service.PhotoScanForegroundService
import com.ddayflip.app.service.ScanServiceState
import java.time.LocalDate

/**
 * 02. 온디바이스 OCR 백그라운드 포그라운드 서비스 연동 스캔 및 실시간 진행 화면
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
    onCancelScan: () -> Unit = {},
    onNavigateToReview: (SequenceValidationResult) -> Unit = {}
) {
    val context = LocalContext.current
    val scanState by PhotoScanForegroundService.scanState.collectAsState()

    // 화면 진입 시 서비스가 유휴 상태(Idle)이면 포그라운드 서비스 시작
    LaunchedEffect(Unit) {
        if (PhotoScanForegroundService.scanState.value is ScanServiceState.Idle) {
            PhotoScanForegroundService.startService(
                context = context,
                startDate = startDate,
                endDate = endDate,
                birthday = birthday,
                ddayRule = ddayRule,
                calendarRoi = calendarRoi,
                todayMaxDday = todayMaxDday
            )
        }
    }

    // 서비스 상태 변경 감지
    LaunchedEffect(scanState) {
        if (scanState is ScanServiceState.Completed) {
            onNavigateToReview((scanState as ScanServiceState.Completed).result)
        }
    }

    val (processedPhotos, totalPhotos, detectedCount, currentStatusText, isComplete, isError, errorMessage) =
        when (val state = scanState) {
            is ScanServiceState.Idle -> {
                StateDisplay(
                    processed = 0,
                    total = 0,
                    detected = 0,
                    statusText = "스캔 준비 중...",
                    isComplete = false,
                    isError = false,
                    errorMessage = null
                )
            }
            is ScanServiceState.Scanning -> {
                StateDisplay(
                    processed = state.processed,
                    total = state.total,
                    detected = state.detectedCount,
                    statusText = state.currentStatusText,
                    isComplete = false,
                    isError = false,
                    errorMessage = null
                )
            }
            is ScanServiceState.Completed -> {
                val entries = state.result.validatedEntries
                StateDisplay(
                    processed = entries.size,
                    total = entries.size,
                    detected = entries.count { it.ocrDday != null },
                    statusText = "분석 완료: 총 ${entries.size}장 분석 완료",
                    isComplete = true,
                    isError = false,
                    errorMessage = null
                )
            }
            is ScanServiceState.Error -> {
                StateDisplay(
                    processed = 0,
                    total = 0,
                    detected = 0,
                    statusText = "오류 발생",
                    isComplete = false,
                    isError = true,
                    errorMessage = state.message
                )
            }
        }

    val progress = if (totalPhotos > 0) {
        processedPhotos.toFloat() / totalPhotos.toFloat()
    } else if (isComplete) {
        1f
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "scan_progress")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("온디바이스 사진 분석", fontWeight = FontWeight.Bold) },
                actions = {
                    if (scanState is ScanServiceState.Scanning) {
                        TextButton(
                            onClick = {
                                PhotoScanForegroundService.cancelScan(context)
                                onCancelScan()
                            }
                        ) {
                            Text("취소", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
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
                when {
                    isError -> {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Analysis Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(72.dp)
                        )
                    }
                    isComplete -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Analysis Complete",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(72.dp)
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Default.HourglassTop,
                            contentDescription = "Analyzing",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(72.dp)
                        )
                    }
                }

                Text(
                    text = when {
                        isError -> "분석 중 문제가 발생했습니다"
                        isComplete -> if (totalPhotos == 0) "해당 기간에 촬영된 사진이 없습니다" else "스캔 및 분석 완료!"
                        else -> "갤러리 사진을 분석하는 중..."
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                // Current Live Status Text
                Text(
                    text = if (isError) (errorMessage ?: "오류 발생") else currentStatusText,
                    fontSize = 14.sp,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
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
                            title = "온디바이스 OCR D-day 달력 인식 ($detectedCount 장 감지)",
                            isDone = detectedCount > 0 || (isComplete && totalPhotos > 0)
                        )
                        StepItem(
                            stepNumber = "3",
                            title = "백그라운드 포그라운드 서비스 및 연속성 검증",
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
            if (isError) {
                Button(
                    onClick = {
                        PhotoScanForegroundService.startService(
                            context = context,
                            startDate = startDate,
                            endDate = endDate,
                            birthday = birthday,
                            ddayRule = ddayRule,
                            calendarRoi = calendarRoi,
                            todayMaxDday = todayMaxDday
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("다시 시도", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        val state = scanState
                        if (state is ScanServiceState.Completed) {
                            onNavigateToReview(state.result)
                        }
                    },
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
}

private data class StateDisplay(
    val processed: Int,
    val total: Int,
    val detected: Int,
    val statusText: String,
    val isComplete: Boolean,
    val isError: Boolean,
    val errorMessage: String?
)

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
