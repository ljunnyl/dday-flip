package com.ddayflip.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.WarningAmber
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
import com.ddayflip.app.core.domain.CalendarSequenceValidator
import com.ddayflip.app.core.domain.IssueType
import com.ddayflip.app.core.domain.SequenceIssue
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * R04. 스마트 검수 & 타임라인 화면
 * - 1일 1장 최고 선명도 자동 선별 (중복 투입 방지)
 * - 날짜 고속 점프 스크러버 (D+1, 50, 100, 150, 오늘)
 * - 원터치 문제 해결 액션 카드 (미넘김 의심 / 결측일)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    issues: List<SequenceIssue> = emptyList(),
    entries: List<CalendarPhotoEntry> = emptyList(),
    onProceedToExport: (List<CalendarPhotoEntry>) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val validator = remember { CalendarSequenceValidator() }

    // 1일 1장 최고 선명도 사진 자동 선별 목록
    var activeEntries by remember(entries) {
        mutableStateOf(validator.selectBestPhotoPerDay(entries))
    }

    var unresolvedIssues by remember(issues) {
        mutableStateOf(issues)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("스마트 검수 & 타임라인", fontWeight = FontWeight.Bold) }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Button(
                        onClick = {
                            if (activeEntries.isEmpty()) {
                                Toast.makeText(context, "선별된 사진이 없습니다.", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            onProceedToExport(activeEntries)
                        },
                        enabled = activeEntries.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06D53)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${activeEntries.size}장으로 릴스 영상 만들기 ✨",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 56.dp) // 우측 스크러버 공간 확보
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "스마트 검수 결과 요약",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${activeEntries.size}일 준비 완료 • 하루 여러 장 중 최고 선명도 1장 자동 선택됨",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 원터치 액션 카드 (Issues)
                if (unresolvedIssues.isNotEmpty()) {
                    itemsIndexed(unresolvedIssues) { index, issue ->
                        ActionableIssueCard(
                            issue = issue,
                            onResolveAction = { resolution ->
                                unresolvedIssues = unresolvedIssues.filterIndexed { i, _ -> i != index }
                                Toast.makeText(context, resolution, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                } else {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF2E7D32))
                                Text(
                                    text = "모든 날짜가 연속되며 미넘김/누락 없이 완벽합니다! ✨",
                                    color = Color(0xFF1B5E20),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "1일 1장 선별 타임라인 (${activeEntries.size}장)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 사진 목록
                itemsIndexed(activeEntries) { _, entry ->
                    PhotoTimelineItem(entry = entry)
                }

                item {
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            // ==========================================
            // 날짜 고속 점프 스크러버 (우측 플로팅 바)
            // ==========================================
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .width(42.dp)
                    .clip(RoundedCornerShape(21.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ScrubberItem(label = "오늘") {
                        coroutineScope.launch {
                            listState.animateScrollToItem((activeEntries.size - 1).coerceAtLeast(0))
                        }
                    }
                    ScrubberItem(label = "150") {
                        val target = activeEntries.indexOfFirst { it.resolvedDday >= 150 }
                        if (target != -1) {
                            coroutineScope.launch { listState.animateScrollToItem(target) }
                        }
                    }
                    ScrubberItem(label = "100") {
                        val target = activeEntries.indexOfFirst { it.resolvedDday >= 100 }
                        if (target != -1) {
                            coroutineScope.launch { listState.animateScrollToItem(target) }
                        }
                    }
                    ScrubberItem(label = "50") {
                        val target = activeEntries.indexOfFirst { it.resolvedDday >= 50 }
                        if (target != -1) {
                            coroutineScope.launch { listState.animateScrollToItem(target) }
                        }
                    }
                    ScrubberItem(label = "D+1") {
                        coroutineScope.launch { listState.animateScrollToItem(0) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrubberItem(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = if (label == "오늘" || label == "D+1") Color(0xFFE06D53) else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun ActionableIssueCard(
    issue: SequenceIssue,
    onResolveAction: (String) -> Unit
) {
    val isUnflipped = issue.type == IssueType.UNFLIPPED_CALENDAR
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUnflipped) Color(0xFFFFF8E1) else Color(0xFFFBE9E7)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (isUnflipped) Color(0xFFD97706) else Color(0xFFE06D53)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = if (isUnflipped) Color(0xFFD97706) else Color(0xFFE06D53),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = if (isUnflipped) "⚠️ 달력 미넘김 의심 (D+${issue.targetDday})" else "🗓️ 결측일 감지",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = if (isUnflipped) Color(0xFFB45309) else Color(0xFFC2410C)
                )
            }

            Text(
                text = issue.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 16.sp
            )

            // 원터치 해결 액션 버튼 행
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isUnflipped) {
                    Button(
                        onClick = { onResolveAction("D+${issue.targetDday + 1}로 반영했습니다.") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06D53)),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("D+${issue.targetDday + 1}로 쓸게요", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { onResolveAction("해당 날짜 사진을 제외했습니다.") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("이 날은 뺄게요", fontSize = 11.sp)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onResolveAction("사진 추가를 준비합니다.") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("+ 직접 추가", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onResolveAction("해당 일자를 건너뜁니다.") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF78909C)),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("건너뛰기", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoTimelineItem(entry: CalendarPhotoEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE06D53).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "D+${entry.resolvedDday}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE06D53)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "D+${entry.resolvedDday} (${entry.effectiveDate})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "가장 또렷한 사진 자동 선별됨 ✨",
                    fontSize = 11.sp,
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
