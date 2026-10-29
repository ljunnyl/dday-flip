package com.ddayflip.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt

/**
 * R04. 스마트 검수 & 타임라인 화면
 * - 1일 1장 최고 선명도 자동 선별 (중복 투입 방지)
 * - 1초 인스턴트 사진 교체 바텀시트 연동 (해당 날짜 사진 즉시 포커싱)
 * - 갤럭시 스타일 패스트 스크롤러 (슬림 스크롤 핸들 + 플로팅 날짜 말풍선)
 * - 몰아찍기 스마트 분배 (Idea 08) 원터치 버튼
 * - 스티커 · 이모티콘 · 자막 꾸미기 다이얼로그 (Idea 04)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    issues: List<SequenceIssue> = emptyList(),
    entries: List<CalendarPhotoEntry> = emptyList(),
    missingDays: List<Int> = emptyList(),
    onEntriesChanged: (List<CalendarPhotoEntry>) -> Unit = {},
    onProceedToExport: (List<CalendarPhotoEntry>) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val validator = remember { CalendarSequenceValidator() }

    var rawEntries by remember(entries) { mutableStateOf(entries) }

    // 1일 1장 최고 선명도 사진 자동 선별 목록
    var activeEntries by remember(rawEntries) {
        mutableStateOf(validator.selectBestPhotoPerDay(rawEntries))
    }

    var unresolvedIssues by remember(issues) {
        mutableStateOf(issues)
    }

    val effectiveMissingDays = remember(missingDays, unresolvedIssues) {
        if (missingDays.isNotEmpty()) {
            missingDays
        } else {
            unresolvedIssues.filter { it.type == IssueType.MISSING_DAYS }.flatMap { it.missingDays }.distinct().sorted()
        }
    }

    val hasBurstPhotos = remember(rawEntries) {
        rawEntries.groupBy { it.resolvedDday }.any { it.value.size > 1 }
    }

    // 사진 교체 바텀시트 상태
    var swapTargetEntry by remember { mutableStateOf<CalendarPhotoEntry?>(null) }
    var sameDayPhotos by remember { mutableStateOf<List<GalleryPhoto>>(emptyList()) }
    var isLoadingSameDayPhotos by remember { mutableStateOf(false) }

    // 스티커 & 자막 꾸미기 다이얼로그 상태 (Idea 04)
    var decorateTargetEntry by remember { mutableStateOf<CalendarPhotoEntry?>(null) }

    // 갤럭시 스타일 패스트 스크롤러 상태
    var isDraggingFastScroll by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackHeightPx by remember { mutableIntStateOf(1) }

    val showFastScroller = activeEntries.isNotEmpty() && (listState.isScrollInProgress || isDraggingFastScroll)

    val currentVisibleEntryIndex = remember(
        listState.firstVisibleItemIndex,
        activeEntries.size,
        isDraggingFastScroll,
        dragFraction
    ) {
        if (activeEntries.isEmpty()) 0
        else if (isDraggingFastScroll) {
            (dragFraction * (activeEntries.size - 1)).roundToInt().coerceIn(0, activeEntries.size - 1)
        } else {
            // 상단 헤더/이슈 아이템 오프셋 보정
            val headerItemsCount = 2 + if (unresolvedIssues.isNotEmpty()) unresolvedIssues.size else 1
            (listState.firstVisibleItemIndex - headerItemsCount).coerceIn(0, activeEntries.size - 1)
        }
    }

    val thumbFraction = if (isDraggingFastScroll) {
        dragFraction.coerceIn(0f, 1f)
    } else if (activeEntries.size > 1) {
        (currentVisibleEntryIndex.toFloat() / (activeEntries.size - 1).toFloat()).coerceIn(0f, 1f)
    } else {
        0f
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
                        text = "카드를 탭해 다른 사진으로 교체하거나 [✨ 꾸미기]로 귀여운 스티커·축하 자막을 넣어보세요.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // ==========================================
                // Idea 08. 몰아찍기 스마트 분배 원터치 배너
                // ==========================================
                if (effectiveMissingDays.isNotEmpty() && hasBurstPhotos) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFE06D53))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "💡 같은 날 여러 장 찍은 사진이 있어요!",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFFBF360C)
                                )
                                Text(
                                    text = "비어 있는 날짜(${effectiveMissingDays.joinToString { "D+$it" }})에 몰아찍은 사진들을 순서대로 채워 넣을 수 있어요.",
                                    fontSize = 12.sp,
                                    color = Color(0xFF3E2723)
                                )
                                Button(
                                    onClick = {
                                        val distributed = validator.distributeBurstPhotos(rawEntries, effectiveMissingDays)
                                        rawEntries = distributed
                                        val updatedBest = validator.selectBestPhotoPerDay(distributed)
                                        activeEntries = updatedBest
                                        unresolvedIssues = unresolvedIssues.filterNot { it.type == IssueType.MISSING_DAYS }
                                        onEntriesChanged(distributed)
                                        Toast.makeText(context, "몰아찍은 사진으로 빈 날짜를 자동 채웠습니다 ✨", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06D53)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "✨ 몰아찍은 사진으로 빈 날짜 자동 채우기",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
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
                            canDistributeBurst = issue.type == IssueType.MISSING_DAYS && hasBurstPhotos,
                            onDistributeBurst = {
                                val targetMissing = issue.missingDays.ifEmpty { effectiveMissingDays }
                                val distributed = validator.distributeBurstPhotos(rawEntries, targetMissing)
                                rawEntries = distributed
                                activeEntries = validator.selectBestPhotoPerDay(distributed)
                                unresolvedIssues = unresolvedIssues.toMutableList().also { it.removeAt(index) }
                                onEntriesChanged(distributed)
                                Toast.makeText(context, "몰아찍은 사진으로 빈 날짜를 자동 채웠습니다 ✨", Toast.LENGTH_SHORT).show()
                            },
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
                        },
                        onDecorateClick = {
                            decorateTargetEntry = entry
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(40.dp))
                }
            }

            // ==========================================
            // R04. 갤럭시 스타일 패스트 스크롤러 (우측 슬림 핸들 + 플로팅 날짜 말풍선)
            // ==========================================
            if (activeEntries.isNotEmpty()) {
                val density = LocalDensity.current
                val handleHeightDp = 48.dp
                val handleHeightPx = with(density) { handleHeightDp.toPx() }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(top = 24.dp, bottom = 24.dp, end = 4.dp)
                        .width(240.dp)
                        .onSizeChanged { size ->
                            trackHeightPx = size.height.coerceAtLeast(1)
                        },
                    contentAlignment = Alignment.TopEnd
                ) {
                    val maxOffsetPx = (trackHeightPx - handleHeightPx).coerceAtLeast(0f)
                    val currentOffsetY = (thumbFraction * maxOffsetPx).roundToInt()
                    val currentEntry = activeEntries[currentVisibleEntryIndex.coerceIn(0, activeEntries.size - 1)]

                    // 플로팅 날짜 말풍선 (스크롤 중이거나 드래그 중일 때 노출)
                    if (showFastScroller) {
                        Surface(
                            modifier = Modifier
                                .offset { IntOffset(x = -28, y = currentOffsetY) }
                                .padding(end = 8.dp),
                            color = Color(0xE61E293B),
                            shape = RoundedCornerShape(50),
                            shadowElevation = 6.dp
                        ) {
                            Text(
                                text = "D+${currentEntry.resolvedDday} | ${currentEntry.effectiveDate}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }

                    // 우측 슬림 스크롤 핸들 터치/드래그 영역
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(28.dp)
                            .pointerInput(activeEntries.size, unresolvedIssues.size) {
                                detectVerticalDragGestures(
                                    onDragStart = { offset ->
                                        isDraggingFastScroll = true
                                        val frac = (offset.y / trackHeightPx.toFloat()).coerceIn(0f, 1f)
                                        dragFraction = frac
                                        val targetIdx = (frac * (activeEntries.size - 1)).roundToInt()
                                        val headerOffset = 2 + if (unresolvedIssues.isNotEmpty()) unresolvedIssues.size else 1
                                        coroutineScope.launch {
                                            listState.scrollToItem((headerOffset + targetIdx).coerceAtLeast(0))
                                        }
                                    },
                                    onDragEnd = {
                                        isDraggingFastScroll = false
                                    },
                                    onDragCancel = {
                                        isDraggingFastScroll = false
                                    },
                                    onVerticalDrag = { change, _ ->
                                        change.consume()
                                        val frac = (change.position.y / trackHeightPx.toFloat()).coerceIn(0f, 1f)
                                        dragFraction = frac
                                        val targetIdx = (frac * (activeEntries.size - 1)).roundToInt()
                                        val headerOffset = 2 + if (unresolvedIssues.isNotEmpty()) unresolvedIssues.size else 1
                                        coroutineScope.launch {
                                            listState.scrollToItem((headerOffset + targetIdx).coerceAtLeast(0))
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.TopEnd
                    ) {
                        // 슬림 스크롤 핸들 필(Pill)
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(x = 0, y = currentOffsetY) }
                                .width(if (showFastScroller) 8.dp else 4.dp)
                                .height(handleHeightDp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (showFastScroller) Color(0xFFE06D53)
                                    else Color(0xFFE06D53).copy(alpha = 0.45f)
                                )
                        )
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
                val updatedActive = activeEntries.map { item ->
                    if (item.id == target.id) {
                        item.copy(
                            photoUri = newPhoto.contentUri.toString(),
                            sharpnessScore = 999.0
                        )
                    } else {
                        item
                    }
                }
                activeEntries = updatedActive
                onEntriesChanged(updatedActive)
                Toast.makeText(context, "D+${target.resolvedDday} 사진이 교체되었습니다 ✨", Toast.LENGTH_SHORT).show()
                swapTargetEntry = null
            },
            onDismissRequest = { swapTargetEntry = null }
        )
    }

    // ==========================================
    // Idea 04. 스티커 · 이모티콘 · 자막 꾸미기 다이얼로그
    // ==========================================
    decorateTargetEntry?.let { target ->
        PhotoDecorateDialog(
            entry = target,
            onDismiss = { decorateTargetEntry = null },
            onSave = { selectedSticker, subtitleText ->
                val normalizedSticker = selectedSticker?.takeIf { it.isNotBlank() }
                val normalizedSubtitle = subtitleText.trim().takeIf { it.isNotBlank() }

                val updatedActive = activeEntries.map { item ->
                    if (item.id == target.id) {
                        item.copy(
                            stickerEmoji = normalizedSticker,
                            customSubtitle = normalizedSubtitle
                        )
                    } else {
                        item
                    }
                }
                activeEntries = updatedActive
                rawEntries = rawEntries.map { item ->
                    if (item.id == target.id) {
                        item.copy(
                            stickerEmoji = normalizedSticker,
                            customSubtitle = normalizedSubtitle
                        )
                    } else {
                        item
                    }
                }
                onEntriesChanged(updatedActive)
                decorateTargetEntry = null
                Toast.makeText(context, "D+${target.resolvedDday} 꾸미기가 저장되었습니다 ✨", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoDecorateDialog(
    entry: CalendarPhotoEntry,
    onDismiss: () -> Unit,
    onSave: (stickerEmoji: String?, customSubtitle: String) -> Unit
) {
    val stickers = listOf("👣", "💖", "👑", "🎀", "🍼", "✨", "🎂", "🌱")
    var selectedSticker by remember(entry) { mutableStateOf(entry.stickerEmoji) }
    var subtitleText by remember(entry) { mutableStateOf(entry.customSubtitle ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "✨ D+${entry.resolvedDday} 사진 꾸미기",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "귀여운 육아 스티커 선택 (터치하여 선택/해제)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                // 스티커 2행 4열 배치
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stickers.chunked(4).forEach { rowStickers ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowStickers.forEach { emoji ->
                                val isSelected = selectedSticker == emoji
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedSticker = if (isSelected) null else emoji
                                    },
                                    label = {
                                        Text(
                                            text = emoji,
                                            fontSize = 18.sp
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = subtitleText,
                    onValueChange = { subtitleText = it },
                    label = { Text("한 줄 축하 자막 (선택)") },
                    placeholder = { Text("예: 100일 축하해 💖") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedSticker, subtitleText) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06D53))
            ) {
                Text("저장하기", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

@Composable
private fun ActionableIssueCard(
    issue: SequenceIssue,
    canDistributeBurst: Boolean = false,
    onDistributeBurst: () -> Unit = {},
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
                color = Color(0xFF1F2421),
                lineHeight = 16.sp
            )

            if (canDistributeBurst) {
                Button(
                    onClick = onDistributeBurst,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06D53)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("✨ 몰아찍은 사진으로 빈 날짜 자동 채우기", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

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
    onSwapClick: () -> Unit,
    onDecorateClick: () -> Unit
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
            horizontalArrangement = Arrangement.spacedBy(12.dp)
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

                // 스티커 이모지가 적용된 경우 썸네일 우하단에 뱃지 표시
                if (!entry.stickerEmoji.isNullOrBlank()) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(topStart = 8.dp),
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        Text(
                            text = entry.stickerEmoji,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
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

                // 적용된 스티커 / 축하 자막 배지 표시
                if (!entry.stickerEmoji.isNullOrBlank() || !entry.customSubtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = Color(0xFFFFECE5),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        val badgeText = buildString {
                            if (!entry.stickerEmoji.isNullOrBlank()) append(entry.stickerEmoji)
                            if (!entry.customSubtitle.isNullOrBlank()) {
                                if (isNotEmpty()) append(" ")
                                append(entry.customSubtitle)
                            }
                        }
                        Text(
                            text = badgeText,
                            fontSize = 11.sp,
                            color = Color(0xFFBF360C),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // [✨ 꾸미기] 버튼 (Idea 04)
            OutlinedButton(
                onClick = onDecorateClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "꾸미기",
                    tint = Color(0xFFE06D53),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("✨ 꾸미기", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE06D53))
            }

            // 사진 교체 버튼
            IconButton(
                onClick = onSwapClick,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "사진 바꾸기",
                    tint = Color(0xFFE06D53),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
