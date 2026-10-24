package com.ddayflip.app.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 02. 온디바이스 스캔 및 프로그레스 화면
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    totalPhotos: Int = 140,
    processedPhotos: Int = 140,
    detectedCalendarPhotos: Int = 110,
    onNavigateToReview: () -> Unit = {}
) {
    val progress = if (totalPhotos > 0) processedPhotos.toFloat() / totalPhotos.toFloat() else 0f
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "scan_progress")
    val isComplete = processedPhotos >= totalPhotos && totalPhotos > 0

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
                    text = if (isComplete) "스캔 및 분석 완료!" else "갤러리 사진을 분석하는 중...",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
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
                            text = "$processedPhotos / $totalPhotos 장",
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
                            title = "온디바이스 OCR 달력 인식 ($detectedCalendarPhotos 장 발견)",
                            isDone = processedPhotos > 50
                        )
                        StepItem(
                            stepNumber = "3",
                            title = "Laplacian 분산 기반 최고 화질 사진 1장 선별",
                            isDone = isComplete
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
                onClick = onNavigateToReview,
                enabled = isComplete,
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
