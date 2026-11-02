package com.ddayflip.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddayflip.app.core.domain.CalendarPhotoEntry
import com.ddayflip.app.core.domain.DayByDayDirectPickerHelper
import com.ddayflip.app.core.domain.DdayCalculator
import com.ddayflip.app.core.domain.DdayStartRule
import com.ddayflip.app.core.domain.DirectDayBucket
import com.ddayflip.app.data.MediaStorePhotoRepository
import com.ddayflip.app.domain.model.GalleryPhoto
import com.ddayflip.app.ui.components.ThumbnailImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * R02-C: '앨범에서 내가 직접 고르기' 전용 인앱 날짜별(D+1, D+2...) 선택기.
 * - 구글 포토 하단창(100장 제한) 없이 기기 내 사진을 D+1~오늘까지 날짜별로 미리 묶어서 보여줌.
 * - 각 날짜마다 가장 잘 나온 사진 1장이 기본 체크(✅)되어 있어 바꿀 날만 터치하면 됨.
 * - 사진이 없는 빈 날짜(isEmptyDay)에는 앞뒤 날짜(±3일)의 남는 사진을 바로 보여줘서 원터치로 끌어올 수 있음.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DirectDayByDayPickerContent(
    modifier: Modifier = Modifier,
    birthdayYear: String,
    birthdayMonth: String,
    birthdayDay: String,
    selectedRule: DdayStartRule,
    onBirthdayChange: (String, String, String, DdayStartRule) -> Unit,
    onConfirmSelection: (List<CalendarPhotoEntry>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var showBirthdayEditor by remember { mutableStateOf(false) }
    var isLoadingPhotos by remember { mutableStateOf(true) }
    var allPhotos by remember { mutableStateOf<List<GalleryPhoto>>(emptyList()) }
    var selectedFolderFilter by remember { mutableStateOf("전체") }

    val parsedBirthday = remember(birthdayYear, birthdayMonth, birthdayDay) {
        val y = birthdayYear.toIntOrNull() ?: 2026
        val m = (birthdayMonth.toIntOrNull() ?: 5).coerceIn(1, 12)
        val d = (birthdayDay.toIntOrNull() ?: 27).coerceIn(1, 31)
        try {
            LocalDate.of(y, m, d)
        } catch (_: Exception) {
            LocalDate.of(2026, 5, 27)
        }
    }

    val maxDday = remember(parsedBirthday, selectedRule) {
        DdayCalculator(parsedBirthday, selectedRule)
            .calculate(LocalDateTime.now())
            .dday
            .coerceIn(1, 400)
    }

    // MediaStore에서 생일 전날 ~ 오늘 사진 고속 로드
    LaunchedEffect(parsedBirthday) {
        isLoadingPhotos = true
        val loaded = withContext(Dispatchers.IO) {
            try {
                val repo = MediaStorePhotoRepository(context.applicationContext)
                repo.getPhotosBetween(
                    startDate = parsedBirthday.minusDays(1),
                    endDate = LocalDate.now()
                )
            } catch (_: Exception) {
                emptyList()
            }
        }
        allPhotos = loaded
        isLoadingPhotos = false
    }

    // 폴더(앨범) 목록 추출
    val folderNames = remember(allPhotos) {
        val counts = allPhotos.groupingBy { it.bucketName }.eachCount()
        listOf("전체") + counts.entries.sortedByDescending { it.value }.map { it.key }
    }

    val filteredPhotos = remember(allPhotos, selectedFolderFilter) {
        if (selectedFolderFilter == "전체") {
            allPhotos
        } else {
            allPhotos.filter { it.bucketName == selectedFolderFilter }
        }
    }

    // 날짜별(D+1 ~ D+maxDday) 버킷 및 빈 날짜 앞뒤 ±3일 끌어오기 후보 생성
    val dayBuckets: List<DirectDayBucket> = remember(filteredPhotos, parsedBirthday, selectedRule, maxDday) {
        DayByDayDirectPickerHelper.buildDayBuckets(
            photos = filteredPhotos,
            birthday = parsedBirthday,
            rule = selectedRule,
            maxDday = maxDday,
            includeDdayZero = false,
            nearbyWindowDays = 3
        )
    }

    // 날짜별 현재 선택된 사진 상태 (dday -> GalleryPhoto)
    var selectedPhotoPerDay by remember(dayBuckets) {
        mutableStateOf(DayByDayDirectPickerHelper.buildInitialSelectionMap(dayBuckets))
    }

    // 특정 빈 날짜에 대해 전체 갤러리에서 1장 직접 골라 넣기용 상태
    // 길게 누른(Long Press) 사진을 다른 날짜로 이동시키기 위한 팝업 상태 (photo, fromDday, fromDateText)
    var movingPhotoInfo by remember { mutableStateOf<Triple<GalleryPhoto, Int, String>?>(null) }

    var manualPickTargetDday by remember { mutableStateOf<Int?>(null) }
    val singleDayPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        val targetDday = manualPickTargetDday
        if (uri != null && targetDday != null) {
            val targetDate = dayBuckets.firstOrNull { it.dday == targetDday }?.date ?: parsedBirthday
            val customPhoto = GalleryPhoto(
                id = System.currentTimeMillis(),
                contentUri = uri,
                dateTakenMs = System.currentTimeMillis(),
                dateTakenLocalDate = targetDate,
                width = 1080,
                height = 1920,
                orientation = 0,
                bucketName = "직접추가"
            )
            selectedPhotoPerDay = selectedPhotoPerDay.toMutableMap().apply {
                put(targetDday, customPhoto)
            }
        }
    }

    val emptyDayCount = remember(dayBuckets, selectedPhotoPerDay) {
        dayBuckets.count { selectedPhotoPerDay[it.dday] == null }
    }

    val fillableEmptyDayCount = remember(dayBuckets, selectedPhotoPerDay) {
        dayBuckets.count { selectedPhotoPerDay[it.dday] == null && it.nearbyPullCandidates.isNotEmpty() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 1. 상단 컨트롤 영역 (생일 확인/수정 + 폴더 필터 + 원클릭 자동 채우기)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 생일 요약 및 수정 토글 바
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "📅 날짜별로 미리 묶어둔 사진첩",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "🎂 ${birthdayYear}.${birthdayMonth}.${birthdayDay} 출생 (${if (selectedRule == DdayStartRule.ONE_BASED) "생일=D+1" else "생일=D+0"}) · 오늘 D+$maxDday",
                            fontSize = 12.sp,
                            color = Color(0xFFE06D53),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    OutlinedButton(
                        onClick = { showBirthdayEditor = !showBirthdayEditor },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.EditCalendar, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFE06D53))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (showBirthdayEditor) "닫기" else "생일 변경", fontSize = 11.sp, color = Color(0xFFE06D53), fontWeight = FontWeight.Bold)
                    }
                }

                // 접이식 생일 즉시 수정 패널
                if (showBirthdayEditor) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = birthdayYear,
                                    onValueChange = { onBirthdayChange(it, birthdayMonth, birthdayDay, selectedRule) },
                                    label = { Text("연도") },
                                    modifier = Modifier.weight(1.3f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = birthdayMonth,
                                    onValueChange = { onBirthdayChange(birthdayYear, it, birthdayDay, selectedRule) },
                                    label = { Text("월") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = birthdayDay,
                                    onValueChange = { onBirthdayChange(birthdayYear, birthdayMonth, it, selectedRule) },
                                    label = { Text("일") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = selectedRule == DdayStartRule.ONE_BASED,
                                    onClick = { onBirthdayChange(birthdayYear, birthdayMonth, birthdayDay, DdayStartRule.ONE_BASED) },
                                    label = { Text("생일 당일 D+1", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = selectedRule == DdayStartRule.ZERO_BASED,
                                    onClick = { onBirthdayChange(birthdayYear, birthdayMonth, birthdayDay, DdayStartRule.ZERO_BASED) },
                                    label = { Text("생일 당일 D+0", fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                // 폴더(앨범) 필터 칩
                if (folderNames.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(folderNames) { folder ->
                            val isSelected = selectedFolderFilter == folder
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedFolderFilter = folder },
                                label = {
                                    Text(
                                        text = if (folder == "전체") "📂 전체 (${allPhotos.size}장)" else "📁 $folder",
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }

                // 퀵 액션 버튼 바: [날짜별 1장 기본 선택] + [빈 날짜 앞뒤 사진으로 자동 채우기]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (fillableEmptyDayCount > 0) {
                        Button(
                            onClick = {
                                val updated = selectedPhotoPerDay.toMutableMap()
                                val alreadyUsedIds = updated.values.map { it.id }.toMutableSet()
                                for (b in dayBuckets) {
                                    if (updated[b.dday] == null && b.nearbyPullCandidates.isNotEmpty()) {
                                        val bestUnused = b.nearbyPullCandidates.firstOrNull { it.photo.id !in alreadyUsedIds }
                                            ?: b.nearbyPullCandidates.first()
                                        updated[b.dday] = bestUnused.photo
                                        alreadyUsedIds.add(bestUnused.photo.id)
                                    }
                                }
                                selectedPhotoPerDay = updated
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "빈 날짜 ${fillableEmptyDayCount}일 앞뒤 사진으로 한번에 채우기 ✨",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            selectedPhotoPerDay = DayByDayDirectPickerHelper.buildInitialSelectionMap(dayBuckets)
                        }
                    ) {
                        Text("기본 1장씩 리셋", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // 2. 중단: 날짜별(D+1 ~ D+maxDday) 구분 리스트
        if (isLoadingPhotos) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(color = Color(0xFFE06D53))
                    Text("날짜별로 사진을 정리하는 중이에요...", fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = dayBuckets,
                    key = { it.dday }
                ) { bucket ->
                    val chosenPhoto = selectedPhotoPerDay[bucket.dday]
                    val isPulledFromNearby = chosenPhoto != null &&
                        bucket.photosOnThatDay.none { it.id == chosenPhoto.id }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (bucket.isEmptyDay && chosenPhoto == null) {
                                Color(0xFFFFF8E1)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (chosenPhoto != null) 1.5.dp else 1.dp,
                                color = when {
                                    isPulledFromNearby -> Color(0xFF2E7D32)
                                    chosenPhoto != null -> Color(0xFFE06D53).copy(alpha = 0.65f)
                                    else -> Color(0xFFFFB74D)
                                },
                                shape = RoundedCornerShape(16.dp)
                            )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 날짜 헤더 줄
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        color = when {
                                            isPulledFromNearby -> Color(0xFF2E7D32)
                                            chosenPhoto != null -> Color(0xFFE06D53)
                                            else -> Color(0xFF9E9E9E)
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "D+${bucket.dday}",
                                            color = Color.White,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 13.sp,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                        )
                                    }

                                    Text(
                                        text = "${bucket.date.monthValue}월 ${bucket.date.dayOfMonth}일",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    if (!bucket.isEmptyDay) {
                                        Text(
                                            text = "· ${bucket.photosOnThatDay.size}장 촬영",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // 우측 상태 표시 & 다른 사진 직접 추가 버튼
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isPulledFromNearby) {
                                        Surface(
                                            color = Color(0xFFE8F5E9),
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = "✨ 앞뒤 날짜에서 끌어옴",
                                                color = Color(0xFF2E7D32),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    } else if (bucket.isEmptyDay && chosenPhoto == null) {
                                        Surface(
                                            color = Color(0xFFFFECB3),
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = "사진 없는 날",
                                                color = Color(0xFFE65100),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }

                                    TextButton(
                                        onClick = {
                                            manualPickTargetDday = bucket.dday
                                            singleDayPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("앨범검색", fontSize = 10.sp)
                                    }
                                }
                            }

                            // 본문 1: 당일 찍은 사진이 있는 경우 -> 가로 썸네일 리스트 (터치로 즉시 대표 교체)
                            if (!bucket.isEmptyDay) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(
                                        items = bucket.photosOnThatDay,
                                        key = { it.id }
                                    ) { photo ->
                                        val isSelected = chosenPhoto?.id == photo.id
                                        Box(
                                            modifier = Modifier
                                                .size(width = 88.dp, height = 112.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .border(
                                                    width = if (isSelected) 3.dp else 1.dp,
                                                    color = if (isSelected) Color(0xFFE06D53) else Color.LightGray.copy(alpha = 0.5f),
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .combinedClickable(
                                                    onClick = {
                                                        selectedPhotoPerDay = selectedPhotoPerDay.toMutableMap().apply {
                                                            if (isSelected) {
                                                                remove(bucket.dday)
                                                            } else {
                                                                put(bucket.dday, photo)
                                                            }
                                                        }
                                                    },
                                                    onLongClick = {
                                                        movingPhotoInfo = Triple(photo, bucket.dday, "${bucket.date.monthValue}월 ${bucket.date.dayOfMonth}일")
                                                    }
                                                )
                                        ) {
                                            ThumbnailImage(
                                                uri = photo.contentUri,
                                                modifier = Modifier.fillMaxSize()
                                            )

                                            if (isSelected) {
                                                Surface(
                                                    color = Color(0xFFE06D53),
                                                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                                                    modifier = Modifier.align(Alignment.TopStart)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            Icons.Default.CheckCircle,
                                                            contentDescription = null,
                                                            tint = Color.White,
                                                            modifier = Modifier.size(11.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(2.dp))
                                                        Text(
                                                            text = "선택됨",
                                                            color = Color.White,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // 본문 2: 당일 찍은 사진이 없는 빈 날(isEmptyDay) -> 어제/내일 퀵 끌어오기 칩 & 앞뒤 날짜(±3일) 남는 사진 끌어오기 UI!
                                val prevDday = bucket.dday - 1
                                val nextDday = bucket.dday + 1
                                val prevDayPhoto = selectedPhotoPerDay[prevDday]
                                val nextDayPhoto = selectedPhotoPerDay[nextDday]

                                if (prevDayPhoto != null || nextDayPhoto != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (prevDayPhoto != null) {
                                            SuggestionChip(
                                                onClick = {
                                                    selectedPhotoPerDay = DayByDayDirectPickerHelper.movePhotoToTargetDay(
                                                        currentSelection = selectedPhotoPerDay,
                                                        buckets = dayBuckets,
                                                        photoToMove = prevDayPhoto,
                                                        fromDday = prevDday,
                                                        targetDday = bucket.dday
                                                    )
                                                    Toast.makeText(context, "D+${prevDday} 사진을 D+${bucket.dday}로 끌어왔어요 ✨", Toast.LENGTH_SHORT).show()
                                                },
                                                label = { Text("👈 어제(D+$prevDday) 끌어오기", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                                colors = SuggestionChipDefaults.suggestionChipColors(
                                                    containerColor = Color(0xFFE8F5E9),
                                                    labelColor = Color(0xFF1B5E20)
                                                )
                                            )
                                        }
                                        if (nextDayPhoto != null) {
                                            SuggestionChip(
                                                onClick = {
                                                    selectedPhotoPerDay = DayByDayDirectPickerHelper.movePhotoToTargetDay(
                                                        currentSelection = selectedPhotoPerDay,
                                                        buckets = dayBuckets,
                                                        photoToMove = nextDayPhoto,
                                                        fromDday = nextDday,
                                                        targetDday = bucket.dday
                                                    )
                                                    Toast.makeText(context, "D+${nextDday} 사진을 D+${bucket.dday}로 끌어왔어요 ✨", Toast.LENGTH_SHORT).show()
                                                },
                                                label = { Text("👉 내일(D+$nextDday) 끌어오기", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                                colors = SuggestionChipDefaults.suggestionChipColors(
                                                    containerColor = Color(0xFFE8F5E9),
                                                    labelColor = Color(0xFF1B5E20)
                                                )
                                            )
                                        }
                                    }
                                }
                                if (bucket.nearbyPullCandidates.isNotEmpty()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SwapHoriz,
                                            contentDescription = null,
                                            tint = Color(0xFF2E7D32),
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = "앞뒤 날짜(±3일)에 여러 장 찍어둔 사진이에요! 터치하면 D+${bucket.dday} 사진으로 끌어옵니다 👇",
                                            fontSize = 11.sp,
                                            color = Color(0xFF1B5E20),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        items(
                                            items = bucket.nearbyPullCandidates,
                                            key = { "${bucket.dday}_${it.photo.id}" }
                                        ) { cand ->
                                            val isSelectedHere = chosenPhoto?.id == cand.photo.id
                                            val distText = when {
                                                cand.dayDistance == -1 -> "어제(D+${cand.sourceDday})"
                                                cand.dayDistance == 1 -> "내일(D+${cand.sourceDday})"
                                                cand.dayDistance < 0 -> "${cand.dayDistance}일(D+${cand.sourceDday})"
                                                else -> "+${cand.dayDistance}일(D+${cand.sourceDday})"
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .size(width = 88.dp, height = 112.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .border(
                                                        width = if (isSelectedHere) 3.dp else 1.dp,
                                                        color = if (isSelectedHere) Color(0xFF2E7D32) else Color.Gray.copy(alpha = 0.45f),
                                                        shape = RoundedCornerShape(12.dp)
                                                    )
                                                    .clickable {
                                                        selectedPhotoPerDay = selectedPhotoPerDay.toMutableMap().apply {
                                                            if (isSelectedHere) {
                                                                remove(bucket.dday)
                                                            } else {
                                                                put(bucket.dday, cand.photo)
                                                            }
                                                        }
                                                    }
                                            ) {
                                                ThumbnailImage(
                                                    uri = cand.photo.contentUri,
                                                    modifier = Modifier.fillMaxSize()
                                                )

                                                // 상단: 끌어오기 선택 상태 또는 여유분 배지
                                                Surface(
                                                    color = if (isSelectedHere) {
                                                        Color(0xFF2E7D32)
                                                    } else if (cand.isSparePhoto) {
                                                        Color(0xCC1B5E20)
                                                    } else {
                                                        Color(0xAA000000)
                                                    },
                                                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                                                    modifier = Modifier.align(Alignment.TopStart)
                                                ) {
                                                    Text(
                                                        text = if (isSelectedHere) "✅ 끌어옴" else if (cand.isSparePhoto) "남는 사진" else "대표 중복",
                                                        color = Color.White,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                    )
                                                }

                                                // 하단: 원래 찍힌 날짜 표시
                                                Surface(
                                                    color = Color(0xCC000000),
                                                    shape = RoundedCornerShape(topStart = 6.dp),
                                                    modifier = Modifier.align(Alignment.BottomEnd)
                                                ) {
                                                    Text(
                                                        text = distText,
                                                        color = Color.White,
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "이 날과 앞뒤 3일 내에 찍힌 사진이 없습니다. 우측 상단 [앨범검색]으로 원하는 사진을 넣을 수 있어요.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. 하단 고정 완료 버튼 바 (장수 제한 무제한)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (emptyDayCount > 0) {
                    Text(
                        text = "💡 전체 ${dayBuckets.size}일 중 ${selectedPhotoPerDay.size}일 선택됨 (비어 있는 ${emptyDayCount}일은 앞뒤 사진으로 채우거나 건너뛸 수 있어요)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = {
                        val finalEntries = DayByDayDirectPickerHelper.toCalendarPhotoEntries(
                            buckets = dayBuckets,
                            selectedPhotoPerDay = selectedPhotoPerDay
                        )
                        if (finalEntries.isNotEmpty()) {
                            onConfirmSelection(finalEntries)
                        }
                    },
                    enabled = selectedPhotoPerDay.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06D53)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Movie, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "🎬 총 ${selectedPhotoPerDay.size}장 선택 완료 · 타임라인 검수로 이동",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}
