package com.ddayflip.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ddayflip.app.core.domain.CalendarPhotoEntry
import com.ddayflip.app.core.domain.CalendarRoi
import com.ddayflip.app.core.domain.DdayCalculator
import com.ddayflip.app.core.domain.DdayStartRule
import com.ddayflip.app.core.domain.ScanAccuracyMode
import com.ddayflip.app.domain.ocr.DdayTextParser
import com.ddayflip.app.domain.video.BgmMode
import com.ddayflip.app.ui.theme.AppVisualTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * R01. 첫 화면 (스튜디오 카드형 홈 & 테마 3종 & 최근 영상 플레이어) +
 * R02. 기준 등록 (손가락 드래그 달력 영역 지정, 침대 구도 유사도 매칭, 아기 얼굴 전용 모드, BGM 설정, 직접 고르기)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartScreen(
    currentTheme: AppVisualTheme = AppVisualTheme.WARM_COTTON,
    onThemeChange: (AppVisualTheme) -> Unit = {},
    recentVideoFile: File? = null,
    selectedBgmMode: BgmMode = BgmMode.DEFAULT_MUSIC_BOX,
    customBgmUri: String? = null,
    onBgmConfigChange: (BgmMode, String?) -> Unit = { _, _ -> },
    onStartScan: (
        birthday: LocalDate,
        rule: DdayStartRule,
        calendarRoi: CalendarRoi?,
        todayMaxDday: Int,
        accuracyMode: ScanAccuracyMode,
        matchSceneSimilarity: Boolean,
        referencePhotoUri: String?
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onDirectPhotosSelected: (List<CalendarPhotoEntry>) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isConfigMode by remember { mutableStateOf(false) }

    var birthdayYear by remember { mutableStateOf("2026") }
    var birthdayMonth by remember { mutableStateOf("05") }
    var birthdayDay by remember { mutableStateOf("27") }
    var selectedRule by remember { mutableStateOf(DdayStartRule.ONE_BASED) }
    var accuracyMode by remember { mutableStateOf(ScanAccuracyMode.STRICT) }
    var matchSceneSimilarity by remember { mutableStateOf(true) }

    var referencePhotoUri by remember { mutableStateOf<Uri?>(null) }
    var referenceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedRoi by remember { mutableStateOf<CalendarRoi?>(null) }
    var isDetectingRoi by remember { mutableStateOf(false) }
    var detectedLabel by remember { mutableStateOf<String?>(null) }

    // R01 최근 만든 영상 탐색
    var latestVideoFile by remember(recentVideoFile) { mutableStateOf<File?>(recentVideoFile) }
    var latestVideoThumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var showVideoPlayerDialog by remember { mutableStateOf(false) }

    LaunchedEffect(recentVideoFile, isConfigMode) {
        withContext(Dispatchers.IO) {
            val candidate = recentVideoFile?.takeIf { it.exists() && it.length() > 0L }
                ?: context.cacheDir.listFiles { file ->
                    file.isFile && file.name.startsWith("dday_flip_") && file.name.endsWith(".mp4") && file.length() > 0L
                }?.maxByOrNull { it.lastModified() }

            latestVideoFile = candidate
            if (candidate != null) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(candidate.absolutePath)
                    latestVideoThumbnail = retriever.getFrameAtTime(0)
                } catch (_: Exception) {
                    latestVideoThumbnail = null
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {}
                }
            } else {
                latestVideoThumbnail = null
            }
        }
    }

    // 비트맵 로드 및 실제 ML Kit 텍스트 감지 (달력 위치 자동 탐색)
    LaunchedEffect(referencePhotoUri) {
        val uri = referencePhotoUri
        if (uri != null) {
            val bmp = withContext(Dispatchers.IO) {
                com.ddayflip.app.util.BitmapUtils.loadOrientedBitmap(context, uri, 1200)
            }
            referenceBitmap = bmp

            if (bmp != null) {
                isDetectingRoi = true
                detectedLabel = "사진 속 D-day 달력 위치를 탐색하는 중..."
                withContext(Dispatchers.Default) {
                    try {
                        val recognizer = TextRecognition.getClient(
                            KoreanTextRecognizerOptions.Builder().build()
                        )
                        val inputImage = InputImage.fromBitmap(bmp, 0)
                        recognizer.process(inputImage)
                            .addOnSuccessListener { visionText ->
                                val parser = DdayTextParser()
                                var foundRoi: CalendarRoi? = null
                                var foundText: String? = null

                                for (block in visionText.textBlocks) {
                                    val parsed = parser.parse(block.text)
                                    if (parsed != null) {
                                        val r = block.boundingBox
                                        if (r != null) {
                                            foundRoi = CalendarRoi(
                                                x = (r.left.toFloat() / bmp.width).coerceIn(0f, 0.9f),
                                                y = (r.top.toFloat() / bmp.height).coerceIn(0f, 0.9f),
                                                width = (r.width().toFloat() / bmp.width).coerceIn(0.12f, 1f),
                                                height = (r.height().toFloat() / bmp.height).coerceIn(0.10f, 1f)
                                            ).withMargin(0.20f)
                                            foundText = block.text.trim()
                                            break
                                        }
                                    }
                                }

                                if (foundRoi == null) {
                                    for (block in visionText.textBlocks) {
                                        if (block.text.any { it.isDigit() }) {
                                            val r = block.boundingBox
                                            if (r != null) {
                                                foundRoi = CalendarRoi(
                                                    x = (r.left.toFloat() / bmp.width).coerceIn(0f, 0.9f),
                                                    y = (r.top.toFloat() / bmp.height).coerceIn(0f, 0.9f),
                                                    width = (r.width().toFloat() / bmp.width).coerceIn(0.12f, 1f),
                                                    height = (r.height().toFloat() / bmp.height).coerceIn(0.10f, 1f)
                                                ).withMargin(0.20f)
                                                foundText = block.text.trim()
                                                break
                                            }
                                        }
                                    }
                                }

                                if (foundRoi != null) {
                                    selectedRoi = foundRoi
                                    detectedLabel = "AI 달력 감지 성공: '$foundText' ✨ (👆 손가락으로 드래그하여 영역을 새로 그리거나 이동하세요)"
                                    Log.d("DdayScanner", "Reference Photo: Auto detected ROI at $foundRoi text=$foundText")
                                } else {
                                    selectedRoi = CalendarRoi(0.25f, 0.15f, 0.50f, 0.35f)
                                    detectedLabel = "👆 손가락으로 드래그하여 달력 영역을 지정하세요"
                                }
                                isDetectingRoi = false
                            }
                            .addOnFailureListener {
                                selectedRoi = CalendarRoi(0.25f, 0.15f, 0.50f, 0.35f)
                                detectedLabel = "👆 손가락으로 드래그하여 달력 영역을 지정하세요"
                                isDetectingRoi = false
                            }
                    } catch (e: Exception) {
                        selectedRoi = CalendarRoi(0.25f, 0.15f, 0.50f, 0.35f)
                        detectedLabel = "👆 손가락으로 드래그하여 달력 영역을 지정하세요"
                        isDetectingRoi = false
                    }
                }
            }
        } else {
            referenceBitmap = null
            selectedRoi = null
            detectedLabel = null
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        referencePhotoUri = uri
    }

    // 내 음악 파일 선택기 (MP3/M4A 등 오디오 파일)
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            onBgmConfigChange(BgmMode.CUSTOM_FILE, uri.toString())
            Toast.makeText(context, "배경음악 파일이 선택되었습니다 🎵", Toast.LENGTH_SHORT).show()
        }
    }

    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun proceedToScan(overrideMode: ScanAccuracyMode? = null) {
        val y = birthdayYear.toIntOrNull() ?: 2026
        val m = (birthdayMonth.toIntOrNull() ?: 5).coerceIn(1, 12)
        val d = (birthdayDay.toIntOrNull() ?: 27).coerceIn(1, 31)
        val birthday = try {
            LocalDate.of(y, m, d)
        } catch (_: Exception) {
            LocalDate.of(2026, 5, 27)
        }
        val modeToUse = overrideMode ?: accuracyMode
        val todayMaxDday = DdayCalculator(birthday, selectedRule)
            .calculate(LocalDateTime.now()).dday
        onStartScan(
            birthday,
            selectedRule,
            if (modeToUse == ScanAccuracyMode.BABY_FACE_ONLY) null else selectedRoi,
            todayMaxDday,
            modeToUse,
            matchSceneSimilarity && referencePhotoUri != null,
            referencePhotoUri?.toString()
        )
    }

    // 피드백 1: 인자 없는 기본 생성자 PickMultipleVisualMedia()를 사용하여 갤럭시 Android 13+ 100장 제한 IllegalArgumentException 방지
    val directPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val y = birthdayYear.toIntOrNull() ?: 2026
                val m = (birthdayMonth.toIntOrNull() ?: 5).coerceIn(1, 12)
                val d = (birthdayDay.toIntOrNull() ?: 27).coerceIn(1, 31)
                val birthday = try {
                    LocalDate.of(y, m, d)
                } catch (_: Exception) {
                    LocalDate.of(2026, 5, 27)
                }
                val ddayCalc = DdayCalculator(birthday = birthday, rule = selectedRule)
                val directEntries = mutableListOf<CalendarPhotoEntry>()

                withContext(Dispatchers.IO) {
                    for ((idx, uri) in uris.withIndex()) {
                        val dateTaken = try {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                val exif = androidx.exifinterface.media.ExifInterface(stream)
                                val dateStr = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                                    ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
                                if (dateStr != null) {
                                    val parts = dateStr.split(" ")[0].split(":")
                                    LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                                } else null
                            }
                        } catch (_: Exception) { null } ?: birthday.plusDays(idx.toLong())

                        // 피드백 13: 정오(12:00) 기준을 넘겨 5월 27일 사진이 5월 26일로 하루 밀리는 버그 차단
                        val calcResult = ddayCalc.calculate(dateTaken.atTime(12, 0))
                        directEntries.add(
                            CalendarPhotoEntry(
                                id = "${uri}_${idx}",
                                effectiveDate = calcResult.effectiveDate,
                                exifDday = calcResult.dday,
                                ocrDday = calcResult.dday,
                                resolvedDday = calcResult.dday,
                                sharpnessScore = 100.0,
                                photoUri = uri.toString(),
                                isCalendarConfirmed = true
                            )
                        )
                    }
                }

                if (directEntries.isNotEmpty()) {
                    onDirectPhotosSelected(directEntries)
                }
            }
        }
    }

    fun launchDirectPhotoPickerSafely() {
        try {
            directPhotoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (e: Exception) {
            Toast.makeText(context, "사진 선택기를 열 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    var pendingScanMode by remember { mutableStateOf<ScanAccuracyMode?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            proceedToScan(pendingScanMode)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "갤러리 사진을 분석하려면 사진 접근 권한이 필요합니다.",
                    withDismissAction = true
                )
            }
        }
    }

    var showModePickerSheet by remember { mutableStateOf(false) }

    val calculatedTodayDday = remember(birthdayYear, birthdayMonth, birthdayDay, selectedRule) {
        val y = birthdayYear.toIntOrNull() ?: 2026
        val m = (birthdayMonth.toIntOrNull() ?: 5).coerceIn(1, 12)
        val d = (birthdayDay.toIntOrNull() ?: 27).coerceIn(1, 31)
        val bday = try {
            LocalDate.of(y, m, d)
        } catch (_: Exception) {
            LocalDate.of(2026, 5, 27)
        }
        DdayCalculator(bday, selectedRule).calculate(LocalDateTime.now()).dday
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("디데이 넘김", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                actions = {
                    if (isConfigMode) {
                        TextButton(onClick = { isConfigMode = false }) {
                            Text("홈으로", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        // 상단 미니 오르골 BGM 토글 칩
                        Surface(
                            onClick = {
                                val nextBgm = when (selectedBgmMode) {
                                    BgmMode.DEFAULT_MUSIC_BOX -> BgmMode.MUTE
                                    BgmMode.CUSTOM_FILE -> BgmMode.MUTE
                                    BgmMode.MUTE -> BgmMode.DEFAULT_MUSIC_BOX
                                }
                                onBgmConfigChange(nextBgm, customBgmUri)
                            },
                            color = if (selectedBgmMode != BgmMode.MUTE) Color(0xFFFFECE5) else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                text = when (selectedBgmMode) {
                                    BgmMode.DEFAULT_MUSIC_BOX -> "🎵 오르골 ON"
                                    BgmMode.CUSTOM_FILE -> "🎵 내 음악 ON"
                                    BgmMode.MUTE -> "🔇 무음"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedBgmMode != BgmMode.MUTE) Color(0xFFE06D53) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        // 상단 미니 테마 순환 아이콘 버튼
                        Surface(
                            onClick = {
                                val themes = AppVisualTheme.values()
                                val nextIndex = (themes.indexOf(currentTheme) + 1) % themes.size
                                onThemeChange(themes[nextIndex])
                            },
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = currentTheme.emoji, fontSize = 15.sp)
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (!isConfigMode) {
            // ==========================================
            // R01. 첫 화면 — 시안 3 (갤러리 액자 & 단일 버튼 + 3모드 선택 바텀시트)
            // ==========================================
            val recentVideo = latestVideoFile
            val hasRecentVideo = recentVideo != null && recentVideo.exists()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 중앙 65% 갤러리 액자 영역
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Card(
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (hasRecentVideo) {
                                Color(0xFF1F2421)
                            } else if (currentTheme == AppVisualTheme.DARK_ROOM) {
                                Color(0xFF1E293B)
                            } else {
                                Color(0xFFF3ECE1)
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .border(
                                width = 1.5.dp,
                                color = if (hasRecentVideo) Color(0xFFE06D53).copy(alpha = 0.45f) else Color(0xFFE6DEC8),
                                shape = RoundedCornerShape(28.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (hasRecentVideo && recentVideo != null) {
                                if (latestVideoThumbnail != null) {
                                    Image(
                                        bitmap = latestVideoThumbnail!!.asImageBitmap(),
                                        contentDescription = "최근 완성한 디데이 릴스",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.28f))
                                    )
                                }

                                // 좌상단 배지
                                Surface(
                                    color = Color.Black.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = "🎬 최근 완성한 릴스",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }

                                // 우상단 D+ 배지
                                Surface(
                                    color = Color(0xFFE06D53),
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = "D+1 ~ D+$calculatedTodayDday",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }

                                // 중앙 큼직한 재생 버튼
                                Surface(
                                    onClick = { showVideoPlayerDialog = true },
                                    color = Color(0xFFE06D53),
                                    shape = RoundedCornerShape(50),
                                    shadowElevation = 8.dp,
                                    modifier = Modifier.size(64.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "영상 재생",
                                            tint = Color.White,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                }

                                // 하단 공유 버튼 바
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = recentVideo.name,
                                        color = Color.White.copy(alpha = 0.9f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        onClick = {
                                            try {
                                                val contentUri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    recentVideo
                                                )
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "video/mp4"
                                                    putExtra(Intent.EXTRA_STREAM, contentUri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "디데이 릴스 공유하기"))
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "공유 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        color = Color.White,
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = null,
                                                tint = Color(0xFF1F2421),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "📤 카톡 · 릴스 바로 공유",
                                                color = Color(0xFF1F2421),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Empty State: 아직 만든 영상이 없을 때 — 따뜻한 폴라로이드 탁상 달력 액자
                                Surface(
                                    color = if (currentTheme == AppVisualTheme.DARK_ROOM) Color(0xFF0F172A) else Color.White.copy(alpha = 0.88f),
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = "🎂 오늘 D+$calculatedTodayDday",
                                        color = Color(0xFFE06D53),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                ) {
                                    Card(
                                        shape = RoundedCornerShape(22.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (currentTheme == AppVisualTheme.DARK_ROOM) Color(0xFF0F172A) else Color.White
                                        ),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                                        modifier = Modifier.widthIn(max = 250.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 22.dp, horizontal = 18.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(text = "🗓️", fontSize = 36.sp)
                                            Text(
                                                text = "D+1 ➔ D+$calculatedTodayDday",
                                                fontSize = 21.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFFE06D53)
                                            )
                                            Text(
                                                text = "${birthdayYear}.${birthdayMonth}.${birthdayDay} 출생 기준",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.Medium
                                            )
                                            HorizontalDivider(
                                                modifier = Modifier.padding(vertical = 4.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant
                                            )
                                            Text(
                                                text = "매일 한 장씩 찍은 사진이\n착착 넘어가는 영화가 돼요",
                                                fontSize = 12.sp,
                                                lineHeight = 17.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.SemiBold,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }

                                    Text(
                                        text = "첫 영상을 만들면 이 액자에서 바로 재생돼요 ✨",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // 안심 온디바이스 한 줄 캡션
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "100% 온디바이스 · 모든 사진은 내 폰 안에서만 안전하게 처리됩니다",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // 하단 고정 바텀 도크 (단일 메인 CTA 버튼 1개)
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 10.dp,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )

                        Button(
                            onClick = { showModePickerSheet = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06D53)),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Icon(Icons.Default.Movie, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (hasRecentVideo) "+ 새 디데이 영상 만들기" else "+ 첫 디데이 영상 만들기",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // [+ 디데이 영상 만들기] 터치 시 올라오는 3모드 선택 바텀시트
            if (showModePickerSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showModePickerSheet = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "어떤 방식으로 사진을 모을까요?",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "원하는 방식을 고르면 다음 단계로 바로 안내해 드려요.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 모드 1: AI 달력 사진 자동 찾기 (추천)
                        Card(
                            onClick = {
                                showModePickerSheet = false
                                accuracyMode = ScanAccuracyMode.STRICT
                                isConfigMode = true
                            },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4EE)),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.5.dp, Color(0xFFE06D53), RoundedCornerShape(18.dp))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Surface(
                                    color = Color(0xFFE06D53),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Color.White)
                                    }
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "✨ AI 달력 사진 자동 찾기 (추천)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color(0xFF1F2421)
                                    )
                                    Text(
                                        text = "달력 위치와 침대 구도가 비슷한 사진을 하루 1장씩 골라요",
                                        fontSize = 12.sp,
                                        color = Color(0xFF6E6A63)
                                    )
                                }
                            }
                        }

                        // 모드 2: 아기 얼굴 사진만 골라 찾기
                        Card(
                            onClick = {
                                showModePickerSheet = false
                                accuracyMode = ScanAccuracyMode.BABY_FACE_ONLY
                                isConfigMode = true
                            },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.ChildCare, contentDescription = null, tint = Color(0xFFE06D53))
                                    }
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "👶 아기 얼굴 사진만 골라 찾기",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "달력이 없어도 아기 얼굴이 나온 대표 사진을 날짜별로 모아요",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // 모드 3: 앨범에서 내가 직접 고르기
                        Card(
                            onClick = {
                                showModePickerSheet = false
                                launchDirectPhotoPickerSafely()
                            },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "🖼️ 앨범에서 내가 직접 고르기",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "갤러리에서 원하는 사진만 직접 체크해서 바로 영상을 만들어요",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ==========================================
            // R02. 기준 등록 (생일 + 손가락 드래그 ROI + 침대 구도 유사도 + BGM 설정 + 3트랙 시작)
            // ==========================================
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "생일 및 맞춤 탐색 설정",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                // 1. 생일 입력 카드
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "🎂 아기 생년월일 (출산 전날 D+0 사진도 함께 찾아드려요)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = birthdayYear,
                                onValueChange = { birthdayYear = it },
                                label = { Text("연도") },
                                modifier = Modifier.weight(1.4f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = birthdayMonth,
                                onValueChange = { birthdayMonth = it },
                                label = { Text("월") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = birthdayDay,
                                onValueChange = { birthdayDay = it },
                                label = { Text("일") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedRule == DdayStartRule.ONE_BASED,
                                onClick = { selectedRule = DdayStartRule.ONE_BASED },
                                label = { Text("생일 당일 D+1 (한국식)", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = selectedRule == DdayStartRule.ZERO_BASED,
                                onClick = { selectedRule = DdayStartRule.ZERO_BASED },
                                label = { Text("생일 당일 D+0 (서양식)", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 2. 기준 사진 등록 & 손가락 드래그 ROI 지정 & 침대 구도 유사도 매칭 카드
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, Color(0xFFE06D53), RoundedCornerShape(20.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📐 기준 사진 & 달력 영역 드래그 지정",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Badge(containerColor = Color(0xFFFFECE5)) {
                                Text("드래그 지원 ✨", color = Color(0xFFE06D53), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Text(
                            text = "기준 사진 1장을 고른 뒤 손가락으로 드래그해 달력 위치 박스를 그리거나, 침대/배경 구도 유사도 매칭으로 같은 장소 사진을 정확히 찾을 수 있어요.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (referencePhotoUri == null) {
                            OutlinedButton(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = Color(0xFFE06D53))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("기준 사진 고르기 (아기 침대/달력 사진)", color = Color(0xFFE06D53), fontWeight = FontWeight.Bold)
                            }
                        } else {
                            var dragStartOffset by remember { mutableStateOf<Offset?>(null) }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(420.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF1E201F)),
                                contentAlignment = Alignment.Center
                            ) {
                                val bmp = referenceBitmap
                                if (bmp != null) {
                                    val bmpRatio = bmp.width.toFloat() / bmp.height.toFloat()
                                    BoxWithConstraints(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val containerW = maxWidth
                                        val containerH = maxHeight
                                        val containerRatio = containerW.value / containerH.value

                                        val (imgW, imgH) = if (bmpRatio > containerRatio) {
                                            containerW to (containerW / bmpRatio)
                                        } else {
                                            (containerH * bmpRatio) to containerH
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(imgW, imgH)
                                                .pointerInput(bmp) {
                                                    detectTapGestures { offset ->
                                                        val tapXFrac = (offset.x / size.width).coerceIn(0f, 1f)
                                                        val tapYFrac = (offset.y / size.height).coerceIn(0f, 1f)
                                                        val cur = selectedRoi ?: CalendarRoi(0.25f, 0.15f, 0.36f, 0.20f)
                                                        val bw = cur.width.coerceIn(0.12f, 0.90f)
                                                        val bh = cur.height.coerceIn(0.08f, 0.90f)
                                                        selectedRoi = CalendarRoi(
                                                            x = (tapXFrac - bw / 2f).coerceIn(0f, 1f - bw),
                                                            y = (tapYFrac - bh / 2f).coerceIn(0f, 1f - bh),
                                                            width = bw,
                                                            height = bh
                                                        )
                                                        detectedLabel = "달력 위치를 이동했어요 🎯 (드래그하여 영역 크기를 직접 그릴 수도 있어요)"
                                                    }
                                                }
                                                .pointerInput(bmp) {
                                                    detectDragGestures(
                                                        onDragStart = { startOffset ->
                                                            dragStartOffset = startOffset
                                                        },
                                                        onDragEnd = {
                                                            dragStartOffset = null
                                                            detectedLabel = "손가락 드래그로 달력 영역을 지정했어요 🎯"
                                                        },
                                                        onDragCancel = {
                                                            dragStartOffset = null
                                                        },
                                                        onDrag = { change, _ ->
                                                            change.consume()
                                                            val start = dragStartOffset ?: change.position
                                                            val curr = change.position
                                                            val w = size.width.toFloat().coerceAtLeast(1f)
                                                            val h = size.height.toFloat().coerceAtLeast(1f)

                                                            val x1 = (min(start.x, curr.x) / w).coerceIn(0f, 0.92f)
                                                            val y1 = (min(start.y, curr.y) / h).coerceIn(0f, 0.92f)
                                                            val x2 = (max(start.x, curr.x) / w).coerceIn(x1 + 0.08f, 1f)
                                                            val y2 = (max(start.y, curr.y) / h).coerceIn(y1 + 0.08f, 1f)

                                                            selectedRoi = CalendarRoi(
                                                                x = x1,
                                                                y = y1,
                                                                width = (x2 - x1).coerceIn(0.08f, 1f - x1),
                                                                height = (y2 - y1).coerceIn(0.08f, 1f - y1)
                                                            )
                                                        }
                                                    )
                                                }
                                        ) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = "기준 사진 원본 전체보기",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit
                                            )

                                            val roi = selectedRoi
                                            if (roi != null) {
                                                Canvas(modifier = Modifier.fillMaxSize()) {
                                                    val w = size.width
                                                    val h = size.height

                                                    val left = roi.x * w
                                                    val top = roi.y * h
                                                    val boxW = roi.width * w
                                                    val boxH = roi.height * h
                                                    val right = left + boxW
                                                    val bottom = top + boxH

                                                    // 반투명 채우기 + 테두리
                                                    drawRect(
                                                        color = Color(0x38E06D53),
                                                        topLeft = Offset(left, top),
                                                        size = Size(boxW, boxH)
                                                    )
                                                    drawRect(
                                                        color = Color(0xFFE06D53),
                                                        topLeft = Offset(left, top),
                                                        size = Size(boxW, boxH),
                                                        style = Stroke(width = 6f)
                                                    )

                                                    // 네 모서리 드래그 핸들 원 표시
                                                    val corners = listOf(
                                                        Offset(left, top),
                                                        Offset(right, top),
                                                        Offset(left, bottom),
                                                        Offset(right, bottom)
                                                    )
                                                    for (pt in corners) {
                                                        drawCircle(
                                                            color = Color.White,
                                                            radius = 12f,
                                                            center = pt
                                                        )
                                                        drawCircle(
                                                            color = Color(0xFFE06D53),
                                                            radius = 8f,
                                                            center = pt
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Surface(
                                        color = Color(0xFFE06D53),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            text = if (isDetectingRoi) "달력 찾는 중..." else "👆 손가락 드래그로 영역 지정",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                } else {
                                    CircularProgressIndicator(color = Color(0xFFE06D53))
                                }
                            }

                            // 손가락 드래그 안내 배너
                            Surface(
                                color = Color(0xFFF1F8E9),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.TouchApp,
                                        contentDescription = null,
                                        tint = Color(0xFF2E7D32)
                                    )
                                    Text(
                                        text = detectedLabel ?: "👆 손가락으로 드래그하여 달력 영역을 지정하세요",
                                        fontSize = 11.sp,
                                        color = Color(0xFF1B5E20),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            // 침대/배경 구도 유사도 매칭 스위치
                            Surface(
                                color = Color(0xFFFFF3E0),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "📐 기준 사진과 침대/배경 구도 유사도 매칭 켜기",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFBF360C)
                                        )
                                        Text(
                                            text = "비슷한 침구·조명·배경 구도에서 찍은 아기 사진을 우선 선별해요",
                                            fontSize = 11.sp,
                                            color = Color(0xFF5D4037)
                                        )
                                    }
                                    Switch(
                                        checked = matchSceneSimilarity,
                                        onCheckedChange = { matchSceneSimilarity = it }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("📷 다른 사진 고르기", fontSize = 12.sp)
                                }
                                TextButton(
                                    onClick = {
                                        selectedRoi = null
                                        detectedLabel = "위치 제한 없이 전체 사진에서 탐색합니다."
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("전체 영역 탐색", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                // 3. AI 탐색 모드 선택기 (엄격 / 고정밀 / 관대 / 아기 얼굴만)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🔍 AI 사진 탐색 모드", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = accuracyMode == ScanAccuracyMode.STRICT,
                                onClick = { accuracyMode = ScanAccuracyMode.STRICT },
                                label = { Text("🎯 달력 엄격", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = accuracyMode == ScanAccuracyMode.HIGH_PRECISION,
                                onClick = { accuracyMode = ScanAccuracyMode.HIGH_PRECISION },
                                label = { Text("🔍 고정밀", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = accuracyMode == ScanAccuracyMode.RELAXED,
                                onClick = { accuracyMode = ScanAccuracyMode.RELAXED },
                                label = { Text("📸 달력+일상", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = accuracyMode == ScanAccuracyMode.BABY_FACE_ONLY,
                                onClick = { accuracyMode = ScanAccuracyMode.BABY_FACE_ONLY },
                                label = { Text("👶 아기 얼굴만", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            text = accuracyMode.description,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 4. 배경음악(BGM) 설정 카드 (내장 오르골 자장가 / 내 음악 파일 / 무음)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🎵 배경음악(BGM) 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = selectedBgmMode == BgmMode.DEFAULT_MUSIC_BOX,
                                onClick = { onBgmConfigChange(BgmMode.DEFAULT_MUSIC_BOX, customBgmUri) },
                                label = { Text("🎵 오르골(내장)", fontSize = 11.sp) },
                                modifier = Modifier.weight(1.2f)
                            )
                            FilterChip(
                                selected = selectedBgmMode == BgmMode.CUSTOM_FILE,
                                onClick = {
                                    audioPickerLauncher.launch("audio/*")
                                },
                                label = { Text("📂 내 음악", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = selectedBgmMode == BgmMode.MUTE,
                                onClick = { onBgmConfigChange(BgmMode.MUTE, customBgmUri) },
                                label = { Text("🔇 무음", fontSize = 11.sp) },
                                modifier = Modifier.weight(0.8f)
                            )
                        }
                        Text(
                            text = if (selectedBgmMode == BgmMode.CUSTOM_FILE && !customBgmUri.isNullOrBlank()) {
                                "선택된 내 음악 파일이 영상에 합성됩니다 🎵"
                            } else {
                                selectedBgmMode.description
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 5. 하단 시작 액션 버튼들
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            pendingScanMode = accuracyMode
                            val permissionStatus = ContextCompat.checkSelfPermission(context, requiredPermission)
                            if (permissionStatus == PackageManager.PERMISSION_GRANTED) {
                                proceedToScan(accuracyMode)
                            } else {
                                permissionLauncher.launch(requiredPermission)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06D53)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (accuracyMode == ScanAccuracyMode.BABY_FACE_ONLY) "👶 아기 얼굴 사진 자동 찾기 시작" else "✨ AI 달력 사진 자동 찾기 시작",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    OutlinedButton(
                        onClick = { launchDirectPhotoPickerSafely() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFE06D53))
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color(0xFFE06D53))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("🖼️ 내가 직접 사진 고르기 (장수 제한 없음)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE06D53))
                    }
                }
            }
        }
    }

    // 앱 내 VideoView 즉시 재생 다이얼로그
    val videoToPlay = latestVideoFile
    if (showVideoPlayerDialog && videoToPlay != null && videoToPlay.exists()) {
        Dialog(onDismissRequest = { showVideoPlayerDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF14181B),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🎬 최근 만든 영상 재생",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        IconButton(onClick = { showVideoPlayerDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "닫기", tint = Color.White)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(9f / 16f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoPath(videoToPlay.absolutePath)
                                    val mediaController = MediaController(ctx)
                                    mediaController.setAnchorView(this)
                                    setMediaController(mediaController)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        start()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
