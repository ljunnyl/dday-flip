package com.ddayflip.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
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
import com.ddayflip.app.core.domain.CalendarPhotoEntry
import com.ddayflip.app.domain.video.MediaCodecVideoExporter
import com.ddayflip.app.domain.video.VideoExporter
import com.ddayflip.app.ui.components.ThumbnailImage
import kotlinx.coroutines.delay
import java.io.File

/**
 * R05. 영상 만드는 중 화면
 * - 실시간 필름 렌더링 릴 (인코딩 중인 이전/현재/다음 실제 사진 썸네일 노출)
 * - 실시간 잔여 시간 (ETA) & 프레임 진행률
 * - 백그라운드 지속 안내
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportingScreen(
    entries: List<CalendarPhotoEntry> = emptyList(),
    videoExporter: VideoExporter = remember { MediaCodecVideoExporter() },
    onExportComplete: (File) -> Unit = {},
    onCancel: () -> Unit = {}
) {
    val context = LocalContext.current

    var exportProgress by remember { mutableFloatStateOf(0f) }
    var currentFrameIndex by remember { mutableIntStateOf(0) }
    var startTimeMs by remember { mutableLongStateOf(0L) }
    var isFailed by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 감성 롤링 문구 목록
    val emotionalCopies = listOf(
        "우리 아기의 소중한 순간들을 영화로 엮고 있어요 🎬",
        "한 장 한 장 예쁜 순간을 착착 이어붙이는 중이에요 ✨",
        "첫날부터 오늘까지 자라난 기록이 완성되고 있어요 🎞️",
        "조금만 기다려주세요, 멋진 릴스 영상으로 만들어 드릴게요 💖"
    )
    var currentCopyIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        startTimeMs = System.currentTimeMillis()
        // 3.5초마다 감성 카피 롤링
        while (true) {
            delay(3500)
            currentCopyIndex = (currentCopyIndex + 1) % emotionalCopies.size
        }
    }

    // 영상 인코딩 실행
    LaunchedEffect(entries) {
        if (entries.isEmpty()) {
            isFailed = true
            errorMessage = "인코딩할 사진 목록이 비어있습니다."
            return@LaunchedEffect
        }

        val outputFile = File(
            context.cacheDir,
            "dday_flip_${System.currentTimeMillis()}.mp4"
        )

        val result = videoExporter.exportVideo(
            context = context,
            entries = entries,
            outputFile = outputFile,
            onProgress = { progress ->
                exportProgress = progress
                currentFrameIndex = (progress * entries.size).toInt().coerceAtMost(entries.size)
            }
        )

        result.onSuccess { file ->
            onExportComplete(file)
        }.onFailure { err ->
            isFailed = true
            errorMessage = err.localizedMessage ?: "알 수 없는 오류로 영상 제작에 실패했습니다."
            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    // 동적 잔여 시간 (ETA) 계산
    val etaSeconds = remember(exportProgress, currentFrameIndex) {
        if (exportProgress > 0.05f && startTimeMs > 0L) {
            val elapsedMs = System.currentTimeMillis() - startTimeMs
            val totalExpectedMs = elapsedMs / exportProgress.toDouble()
            val remainingMs = totalExpectedMs - elapsedMs
            ((remainingMs / 1000).toInt()).coerceAtLeast(1)
        } else {
            null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("영상 제작 중", fontWeight = FontWeight.Bold) }
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

                // R05. 실시간 필름 렌더링 릴 뷰 (이전/현재/다음 실제 사진 표시)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF1F2421)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val centerIdx = if (entries.isNotEmpty()) {
                                currentFrameIndex.coerceIn(0, entries.size - 1)
                            } else 0

                            val prevEntry = entries.getOrNull(centerIdx - 1)
                            val currEntry = entries.getOrNull(centerIdx)
                            val nextEntry = entries.getOrNull(centerIdx + 1)

                            val activeDday = currEntry?.resolvedDday ?: 100

                            FilmFrameItem(
                                ddayText = "D+${prevEntry?.resolvedDday ?: (activeDday - 1).coerceAtLeast(1)}",
                                photoUri = prevEntry?.photoUri,
                                isCurrent = false
                            )
                            FilmFrameItem(
                                ddayText = "D+$activeDday",
                                photoUri = currEntry?.photoUri,
                                isCurrent = true
                            )
                            FilmFrameItem(
                                ddayText = "D+${nextEntry?.resolvedDday ?: (activeDday + 1)}",
                                photoUri = nextEntry?.photoUri,
                                isCurrent = false
                            )
                        }

                        Text(
                            text = "🎬 1080x1920 세로 릴스 인코딩 중",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // 감성 롤링 카피
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = emotionalCopies[currentCopyIndex],
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "음악과 날짜 순서에 맞춰 자연스럽게 엮어내고 있어요",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 프로그레스 바
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { exportProgress },
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
                            text = "${(exportProgress * 100).toInt()}%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "$currentFrameIndex / ${entries.size} 프레임",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 남은 시간 안내 카드
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
                                    "약 ${etaSeconds / 60}분 ${etaSeconds % 60}초 뒤에 완성돼요 ✨"
                                } else {
                                    "약 ${etaSeconds}초 뒤에 완성돼요 ✨"
                                }
                            } else {
                                "영상 생성 예상 시간을 계산하는 중..."
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "하드웨어 가속(MediaCodec H.264)으로 초고속 인코딩 진행 중",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 백그라운드 지속 안내 배너
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
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
                            tint = Color(0xFF2E7D32)
                        )
                        Text(
                            text = "📲 화면을 나가거나 다른 앱을 사용하셔도 괜찮아요!\n백그라운드에서 완성 후 폰 갤러리에 자동으로 보관돼요.",
                            color = Color(0xFF1B5E20),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            if (isFailed) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("돌아가서 다시 시도하기", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text("취소하고 검수 화면으로 돌아가기", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun FilmFrameItem(
    ddayText: String,
    photoUri: String?,
    isCurrent: Boolean
) {
    Box(
        modifier = Modifier
            .width(if (isCurrent) 86.dp else 72.dp)
            .height(if (isCurrent) 118.dp else 100.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = if (isCurrent) 2.5.dp else 1.dp,
                color = if (isCurrent) Color(0xFFE06D53) else Color.White.copy(alpha = 0.25f),
                shape = RoundedCornerShape(10.dp)
            )
            .background(if (isCurrent) Color(0xFFE06D53) else Color(0xFF333835)),
        contentAlignment = Alignment.Center
    ) {
        if (!photoUri.isNullOrBlank()) {
            ThumbnailImage(
                photoUri = photoUri,
                modifier = Modifier.fillMaxSize()
            )
            // 하단 D-day 캡슐 오버레이
            Surface(
                color = if (isCurrent) Color(0xFFE06D53) else Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
            ) {
                Text(
                    text = ddayText,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = if (isCurrent) Color.White else Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = ddayText,
                    color = if (isCurrent) Color.White else Color.LightGray,
                    fontSize = 13.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
