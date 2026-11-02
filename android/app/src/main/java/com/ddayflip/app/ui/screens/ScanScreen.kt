package com.ddayflip.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddayflip.app.core.domain.CalendarRoi
import com.ddayflip.app.core.domain.DdayStartRule
import com.ddayflip.app.core.domain.SequenceValidationResult
import com.ddayflip.app.domain.ocr.DdayScanner
import com.ddayflip.app.domain.repository.PhotoRepository
import com.ddayflip.app.service.PhotoScanForegroundService
import com.ddayflip.app.service.ScanServiceState
import com.ddayflip.app.ui.components.ThumbnailImage
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * R03. 온디바이스 OCR 백그라운드 포그라운드 서비스 연동 라이브 뷰파인더 릴 & 실시간 잔여 시간 화면
 * - 피드백 5: 상단 사진 뷰파인더 좌우 여백 0dp 풀폭 배치 (height 300.dp)
 * - 피드백 6: 감성 문구 무조건 1줄 고정 (maxLines = 1, TextOverflow.Ellipsis)
 * - 피드백 7: "D+50 사진 분석 중..." 기계적 상태 문구/배지 완전 제거
 * - 피드백 8: 스캔 화면 체류 중 화면 꺼짐 방지 (keepScreenOn = true)
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
    matchSceneSimilarity: Boolean = false,
    referencePhotoUri: String? = null,
    photoRepository: PhotoRepository? = null,
    ddayScanner: DdayScanner? = null,
    onCancelScan: () -> Unit = {},
    onNavigateToReview: (SequenceValidationResult) -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scanState by PhotoScanForegroundService.scanState.collectAsState()

    // 피드백 8: 스캔 중 화면 꺼짐 방지
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }

    var scanStartTimeMs by remember { mutableLongStateOf(0L) }

    // 피드백 6: 한 줄 이내의 간결한 감성 롤링 문구
    val emotionalCopies = listOf(
        "우리 아기 예쁜 사진을 쏙쏙 찾고 있어요 ✨",
        "소중한 성장의 순간들을 차곡차곡 모으고 있어요 💖",
        "날짜 순서대로 반듯하게 정리해 드릴게요 👶",
        "하루 한 장, 가장 선명한 표정을 고르고 있어요 🌟"
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
                accuracyMode = accuracyMode,
                matchSceneSimilarity = matchSceneSimilarity,
                referencePhotoUri = referencePhotoUri
            )
        }
    }

    // 서비스 상태 변경 감지
    LaunchedEffect(scanState) {
        if (scanState is ScanServiceState.Completed) {
            onNavigateToReview((scanState as ScanServiceState.Completed).result)
        }
    }

    val (processedPhotos, totalPhotos, detectedCount, isComplete, isError, errorMessage) =
        when (val state = scanState) {
            is ScanServiceState.Idle -> {
                StateDisplay(
                    processed = 0,
                    total = 0,
                    detected = 0,
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
                    detected = entries.count { it.ocrDday != null || it.isCalendarConfirmed },
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
                    isComplete = false,
                    isError = true,
                    errorMessage = state.message
                )
            }
        }

    val currentPhotoUri = (scanState as? ScanServiceState.Scanning)?.currentPhotoUri

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
                .padding(padding),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 피드백 5 & 7: 좌우 여백 없는 풀폭(fillMaxWidth, height 300.dp) 라이브 뷰파인더 & "D+50 사진 분석 중" 배지 완전 제거
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(Color(0xFF1F2421)),
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
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(64.dp)
                            )
                        }
                        !currentPhotoUri.isNullOrBlank() -> {
                            ThumbnailImage(
                                photoUri = currentPhotoUri,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Searching",
                                tint = Color(0xFFE06D53),
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }
                }

                // 하단 컨텐츠 영역 (좌우 24.dp 패딩)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // 피드백 6 & 7: 감성 롤링 카피 무조건 1줄 고정 + 기계적 "D+50 사진 분석 중..." 문구 제거
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isError) "분석 중 문제가 발생했습니다" else if (isComplete) "우리 아기 사진 선별 완료! ✨" else emotionalCopies[currentCopyIndex],
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isError && !errorMessage.isNullOrBlank()) {
                            Text(
                                text = errorMessage,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
                                text = if (totalPhotos > 0) "$processedPhotos / $totalPhotos 장" else "사진 불러오는 중",
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
                                    "남은 시간을 계산하고 있어요..."
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (detectedCount > 0) {
                                    if (accuracyMode == com.ddayflip.app.core.domain.ScanAccuracyMode.BABY_FACE_ONLY) {
                                        "현재까지 우리 아기 사진 $detectedCount 장 발견됨 👶✨"
                                    } else {
                                        "현재까지 달력 사진 $detectedCount 장 발견됨 ✨"
                                    }
                                } else {
                                    if (accuracyMode == com.ddayflip.app.core.domain.ScanAccuracyMode.BABY_FACE_ONLY) {
                                        "우리 아기 얼굴이 가장 또렷한 사진을 고르고 있어요 👶"
                                    } else {
                                        "하루 한 장 가장 또렷한 사진을 고르고 있어요"
                                    }
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                                text = "💡 화면이 자동으로 켜져 있으며, 다른 앱을 보셔도 상단 알림 창에서 멈추지 않고 끝까지 찾아둘게요.",
                                color = Color(0xFF0D47A1),
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            // 하단 액션 버튼
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
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
                                todayMaxDday = todayMaxDday,
                                accuracyMode = accuracyMode,
                                matchSceneSimilarity = matchSceneSimilarity,
                                referencePhotoUri = referencePhotoUri
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
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
                            .height(54.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("스마트 검수 및 타임라인 확인", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private data class StateDisplay(
    val processed: Int,
    val total: Int,
    val detected: Int,
    val isComplete: Boolean,
    val isError: Boolean,
    val errorMessage: String?
)
