package com.ddayflip.app.ui.components

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddayflip.app.core.domain.CalendarPhotoEntry
import com.ddayflip.app.domain.model.GalleryPhoto
import com.ddayflip.app.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 특정 날짜의 사진을 1초 만에 다른 컷으로 맞교환하는 인스턴트 바텀시트
 * - 피드백 14: 당일 + 앞뒤 ±3일 사진 즉시 노출 + [📂 전체 사진첩에서 직접 찾기] 버튼 제공
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSwapBottomSheet(
    targetEntry: CalendarPhotoEntry,
    sameDayPhotos: List<GalleryPhoto>,
    isLoading: Boolean,
    onPhotoSelected: (GalleryPhoto) -> Unit,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalHapticFeedback.current

    // 전체 사진첩에서 직접 고르기 런처
    val fullGalleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val pickedPhoto = GalleryPhoto(
                id = System.currentTimeMillis(),
                contentUri = uri,
                dateTakenMs = System.currentTimeMillis(),
                dateTakenLocalDate = targetEntry.effectiveDate,
                width = 1080,
                height = 1920,
                orientation = 0
            )
            onPhotoSelected(pickedPhoto)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            // 헤더
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = Color(0xFFE06D53),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "D+${targetEntry.resolvedDday}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "사진 교체하기",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "📅 ${targetEntry.effectiveDate} 및 앞뒤 ±3일 사진 (${sameDayPhotos.size}장)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismissRequest) {
                    Icon(Icons.Default.Close, contentDescription = "닫기")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 피드백 14: 전체 사진첩에서 직접 찾기 버튼 (항상 노출)
            OutlinedButton(
                onClick = {
                    try {
                        fullGalleryPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    } catch (_: Exception) {}
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFE06D53))
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color(0xFFE06D53), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "📂 전체 사진첩에서 직접 찾기",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE06D53)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFFE06D53))
                }
            } else if (sameDayPhotos.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "해당 날짜 전후(±3일)에 촬영된 다른 사진이 없습니다.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "위의 [📂 전체 사진첩에서 직접 찾기] 버튼을 눌러 원하는 사진을 골라주세요.",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }
            } else {
                Text(
                    text = "원하는 사진을 터치하면 바로 교체됩니다 (당일 사진 우선 정렬) 👇",
                    fontSize = 12.sp,
                    color = Color(0xFFE06D53),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 당일 + ±3일 사진 3열 그리드
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sameDayPhotos) { photo ->
                        val isCurrent = photo.contentUri.toString() == targetEntry.photoUri
                        val isSameDate = photo.dateTakenLocalDate == targetEntry.effectiveDate
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    width = if (isCurrent) 3.dp else 1.dp,
                                    color = if (isCurrent) Color(0xFFE06D53) else Color.LightGray.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onPhotoSelected(photo)
                                }
                        ) {
                            ThumbnailImage(
                                uri = photo.contentUri,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (isCurrent) {
                                Surface(
                                    color = Color(0xFFE06D53),
                                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                                    modifier = Modifier.align(Alignment.TopStart)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Done,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(10.dp)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            "현재",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            } else {
                                // 촬영 날짜 뱃지 표시
                                Surface(
                                    color = if (isSameDate) Color(0xCC2E7D32) else Color(0xAA000000),
                                    shape = RoundedCornerShape(topStart = 6.dp),
                                    modifier = Modifier.align(Alignment.BottomEnd)
                                ) {
                                    Text(
                                        text = "${photo.dateTakenLocalDate.monthValue}/${photo.dateTakenLocalDate.dayOfMonth}",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * 시스템 ContentResolver 캐시 및 백그라운드 스케일 다운 썸네일 컴포저블
 */
@Composable
fun ThumbnailImage(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    context.contentResolver.loadThumbnail(uri, android.util.Size(260, 260), null)
                } catch (_: Exception) {
                    BitmapUtils.loadOrientedBitmap(context, uri, 260)
                }
            } else {
                BitmapUtils.loadOrientedBitmap(context, uri, 260)
            }
            bitmap = bmp
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color(0xFFE06D53)
            )
        }
    }
}

/**
 * 문자열 URI를 받는 ThumbnailImage 오버로드
 */
@Composable
fun ThumbnailImage(
    photoUri: String,
    modifier: Modifier = Modifier
) {
    if (photoUri.isNotBlank()) {
        ThumbnailImage(uri = Uri.parse(photoUri), modifier = modifier)
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {}
    }
}
