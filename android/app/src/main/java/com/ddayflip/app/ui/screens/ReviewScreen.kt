package com.ddayflip.app.ui.screens

import android.net.Uri
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
import androidx.compose.material.icons.filled.Edit
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
import com.ddayflip.app.data.MediaStorePhotoRepository
import com.ddayflip.app.domain.model.GalleryPhoto
import com.ddayflip.app.ui.components.PhotoSwapBottomSheet
import com.ddayflip.app.ui.components.ThumbnailImage
import kotlinx.coroutines.launch

/**
 * R04. 스마트 검수 & 타임라인 화면
 * - 1일 1장 최고 선명도 자동 선별 (중복 투입 방지)
 * - 1초 인스턴트 사진 교체 바텀시트 연동 (해당 날짜 사진 즉시 포커싱)
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

    // 사진 교체 바텀시트 상태
    var swapTargetEntry by remember { mutableStateOf<CalendarPhotoEntry?>(null) }
    var sameDayPhotos by remember { mutableStateOf<List<GalleryPhoto>>(emptyList()) }
    var isLoadingSameDayPhotos by remember { mutableStateOf(false) }

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
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "아기 D-day 릴스 검수",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "이상한 사진은 카드를 탭하여 그날의 다른 사진으로 바로 교체할 수 있습니다.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // ==========================================
                // 해결해야 할 이슈 카드 목록 (원터치 액션 카드)
                // ==========================================
                if (unresolvedIssues.isNotEmpty()) {
                    item {
                        Text(
                            text = "확인이 필요한 날짜 (${unresolvedIssues.size}건)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD97706)
                        )
                    }
                    itemsIndexed(unresolvedIssues) { index, issue ->
                        ActionableIssueCard(
                            issue = issue,
                            onResolveAction = { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                unresolvedIssues = unresolvedIssues.toMutableList().also { it.removeAt(index) }
                            }
                        )
                    }
                } else {
                    item {
                        Surface(
                            color = Color(0xFFE8F5E9),
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
                    PhotoTimelineItem(
                        entry = entry,
                        onSwapClick = {
                            swapTargetEntry = entry
                            coroutineScope.launch {
                                isLoadingSameDayPhotos = true
                                val repository = MediaStorePhotoRepository(context)
                                sameDayPhotos = repository.getPhotosOnDate(entry.effectiveDate)
                                isLoadingSameDayPhotos = false
                            }
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(40.dp))
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

    // ==========================================
    // 1초 인스턴트 사진 교체 바텀시트
    // ==========================================
    swapTargetEntry?.let { target ->
        PhotoSwapBottomSheet(
            targetEntry = target,
            sameDayPhotos = sameDayPhotos,
            isLoading = isLoadingSameDayPhotos,
            onPhotoSelected = { newPhoto ->
                activeEntries = activeEntries.map { item ->
                    if (item.id == target.id) {
                        item.copy(
                            photoUri = newPhoto.contentUri.toString(),
                            sharpnessScore = 999.0
                        )
                    } else {
                        item
                    }
                }
                Toast.makeText(context, "D+${target.resolvedDday} 사진이 교체되었습니다 ✨", Toast.LENGTH_SHORT).show()
                swapTargetEntry = null
            },
            onDismissRequest = { swapTargetEntry = null }
        )
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
private fun PhotoTimelineItem(
    entry: CalendarPhotoEntry,
    onSwapClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSwapClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 실제 사진 썸네일
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.LightGray.copy(alpha = 0.3f))
            ) {
                if (entry.photoUri.isNotBlank()) {
                    ThumbnailImage(
                        uri = Uri.parse(entry.photoUri),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "D+${entry.resolvedDday}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE06D53)
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "D+${entry.resolvedDday}",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = Color(0xFFE06D53)
                    )
                    Text(
                        text = "(${entry.effectiveDate})",
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.isCalendarConfirmed) {
                        Surface(
                            color = Color(0xFFE8F5E9),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "달력 확인됨 ✨",
                                fontSize = 10.sp,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Surface(
                            color = Color(0xFFFFF3E0),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "일상 사진 (달력 없음)",
                                fontSize = 10.sp,
                                color = Color(0xFFE65100),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // 사진 교체 버튼
            IconButton(
                onClick = onSwapClick,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "사진 바꾸기",
                    tint = Color(0xFFE06D53),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
