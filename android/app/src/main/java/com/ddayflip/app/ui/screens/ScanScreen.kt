package com.ddayflip.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * 02. 온디바이스 OCR 백그라운드 포그라운드 서비스 연동 감성 스캔 & 실시간 잔여 시간 화면
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
    accuracyMode: com.ddayflip.app.core.domain.ScanAccuracyMode = com.ddayflip.app.core.domain.ScanAccuracyMode.STRICT,
    photoRepository: PhotoRepository? = null,
    ddayScanner: DdayScanner? = null,
    onCancelScan: () -> Unit = {},
    onNavigateToReview: (SequenceValidationResult) -> Unit = {}
) {
    val context = LocalContext.current
    val scanState by PhotoScanForegroundService.scanState.collectAsState()

    var scanStartTimeMs by remember { mutableLongStateOf(0L) }

    // 감성 롤링 문구
    val emotionalCopies = listOf(
        "우리 아기 예쁜 사진을 쏙쏙 찾고 있어요 ✨",
        "침대 위 D-day 달력을 찰떡같이 발견하는 중이에요 🔍",
        "하루하루 쑥쑥 자란 소중한 순간들을 모으고 있어요 💖",
        "D-day 날짜 순서대로 꼼꼼히 정리해 드릴게요 👶"
    )
    var currentCopyIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        scanStartTimeMs = System.currentTimeMillis()
        while (true) {
            delay(3500)
            currentCopyIndex = (currentCopyIndex + 1) % emotionalCopies.size
        }
    }

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
                todayMaxDday = todayMaxDday,
                accuracyMode = accuracyMode
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
                    statusText = "분석 완료: 총 ${entries.size}장 준비됨",
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

    // 동적 잔여 시간 계산
    val etaSeconds = remember(processedPhotos, totalPhotos) {
        if (processedPhotos > 10 && totalPhotos > processedPhotos && scanStartTimeMs > 0L) {
            val elapsedMs = System.currentTimeMillis() - scanStartTimeMs
            val msPerPhoto = elapsedMs.toDouble() / processedPhotos
            val remainingPhotos = totalPhotos - processedPhotos
            ((remainingPhotos * msPerPhoto) / 1000).toInt().coerceAtLeast(1)
        } else {
            null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("아기 사진 찾는 중", fontWeight = FontWeight.Bold) },
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
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Spacer(modifier = Modifier.height(10.dp))

                // 비주얼 원형 아이콘 영역
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(RoundedCornerShape(70.dp))
                        .background(Color(0xFFFFF0ED)),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isError -> {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(64.dp)
                            )
                        }
                        isComplete -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Complete",
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(64.dp)
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Searching",
                                tint = Color(0xFFE06D53),
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    }
                }

                // 감성 롤링 카피
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isError) "분석 중 문제가 발생했습니다" else if (isComplete) "우리 아기 사진 선별 완료! ✨" else emotionalCopies[currentCopyIndex],
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isError) (errorMessage ?: "오류 발생") else if (isComplete) "스마트 검수 화면으로 이동합니다" else "침대 위 D-day 달력을 꼼꼼히 탐색하고 있어요",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 프로그레스 바
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (totalPhotos > 0) "$processedPhotos / $totalPhotos 장" else "분석 대기 중",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 잔여 시간 (ETA) 카드
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (etaSeconds != null) {
                                if (etaSeconds >= 60) {
                                    "약 ${etaSeconds / 60}분 ${etaSeconds % 60}초 남았어요 ✨"
                                } else {
                                    "약 ${etaSeconds}초 남았어요 ✨"
                                }
                            } else {
                                "예상 소요 시간을 계산하는 중..."
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (detectedCount > 0) "현재까지 D-day 달력 $detectedCount 장 발견됨" else "사진 속 D-day 달력 영역을 찾는 중입니다",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 백그라운드 포그라운드 서비스 안내
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = Color(0xFF1565C0)
                        )
                        Text(
                            text = "💡 창을 닫거나 다른 앱을 보셔도 괜찮아요!\n상단 알림 창에서 멈추지 않고 계속 찾아둘게요.",
                            color = Color(0xFF0D47A1),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Bottom Actions
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
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("다시 시도하기", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                    shape = RoundedCornerShape(14.dp)
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
