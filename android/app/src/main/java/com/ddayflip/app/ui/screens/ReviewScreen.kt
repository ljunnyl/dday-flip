package com.ddayflip.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddayflip.app.core.domain.CalendarPhotoEntry
import com.ddayflip.app.core.domain.IssueType
import com.ddayflip.app.core.domain.SequenceIssue
import java.time.LocalDate

/**
 * 03. 타임라인 & 스마트 검수 화면
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    issues: List<SequenceIssue> = listOf(
        SequenceIssue(
            type = IssueType.UNFLIPPED_CALENDAR,
            targetDday = 10,
            description = "미넘김 의심: 2024-06-01과 2024-06-02의 달력 D-day가 모두 D+10 입니다."
        ),
        SequenceIssue(
            type = IssueType.MISSING_DAYS,
            targetDday = 11,
            description = "결측일 감지: D+12, D+13 사진 누락",
            missingDays = listOf(12, 13)
        )
    ),
    entries: List<CalendarPhotoEntry> = listOf(
        CalendarPhotoEntry(id = "1", effectiveDate = LocalDate.of(2024, 6, 1), exifDday = 10, ocrDday = 10, sharpnessScore = 142.0),
        CalendarPhotoEntry(id = "2", effectiveDate = LocalDate.of(2024, 6, 2), exifDday = 11, ocrDday = 10, sharpnessScore = 118.0),
        CalendarPhotoEntry(id = "3", effectiveDate = LocalDate.of(2024, 6, 5), exifDday = 14, ocrDday = 14, sharpnessScore = 165.0)
    ),
    onProceedToExport: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("스마트 검수 & 타임라인", fontWeight = FontWeight.Bold) }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Button(
                        onClick = onProceedToExport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("플립 영상 생성하기", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "스마트 검수 결과 요약",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Issues Section
            if (issues.isNotEmpty()) {
                items(issues) { issue ->
                    IssueCard(issue = issue)
                }
            } else {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF2E7D32))
                            Text(
                                text = "모든 날짜가 연속되며 미넘김/누락 없이 완벽합니다!",
                                color = Color(0xFF1B5E20),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "일자별 최적 사진 타임라인",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            // Photos timeline
            items(entries) { entry ->
                TimelineItemCard(entry = entry)
            }

            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun IssueCard(issue: SequenceIssue) {
    val isWarning = issue.type == IssueType.UNFLIPPED_CALENDAR
    val bgColor = if (isWarning) Color(0xFFFFF3E0) else Color(0xFFFFEBEE)
    val iconColor = if (isWarning) Color(0xFFE65100) else Color(0xFFC62828)

    Card(
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (isWarning) Icons.Default.WarningAmber else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = iconColor
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = when (issue.type) {
                        IssueType.UNFLIPPED_CALENDAR -> "달력 미넘김 의심 (D+${issue.targetDday})"
                        IssueType.MISSING_DAYS -> "결측일 감지 (${issue.missingDays.joinToString { "D+$it" }})"
                        IssueType.DISCREPANCY -> "날짜 불일치 확인 필요"
                    },
                    fontWeight = FontWeight.Bold,
                    color = iconColor,
                    fontSize = 14.sp
                )
                Text(
                    text = issue.description,
                    fontSize = 13.sp,
                    color = Color(0xFF37474F)
                )
            }
        }
    }
}

@Composable
private fun TimelineItemCard(entry: CalendarPhotoEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "D+${entry.resolvedDday}",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            text = "선명도: ${entry.sharpnessScore.toInt()}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Text(
                    text = "촬영일자: ${entry.effectiveDate} (EXIF D+${entry.exifDday})",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Thumbnail simulation box
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(Color.DarkGray, RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "D+${entry.resolvedDday}",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
