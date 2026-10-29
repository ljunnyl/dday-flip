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
                    }
                }
            )
        }
    ) { padding ->
        if (!isConfigMode) {
            // ==========================================
            // R01. 첫 화면 (스튜디오 카드형 홈 & 온보딩 + 테마 3종 + 최근 영상 카드)
            // ==========================================
            val homeScrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(homeScrollState)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 상단 테마 3종 선택 칩
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppVisualTheme.values().forEach { themeOption ->
                            FilterChip(
                                selected = currentTheme == themeOption,
                                onClick = { onThemeChange(themeOption) },
                                label = {
                                    Text(
                                        text = "${themeOption.emoji} ${themeOption.title}",
                                        fontSize = 11.sp,
                                        fontWeight = if (currentTheme == themeOption) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // 최근 만든 영상 홈 플레이어 카드
                    val recentVideo = latestVideoFile
                    if (recentVideo != null && recentVideo.exists()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.5.dp, Color(0xFFE06D53), RoundedCornerShape(18.dp))
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp, 88.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF1F2421)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (latestVideoThumbnail != null) {
                                        Image(
                                            bitmap = latestVideoThumbnail!!.asImageBitmap(),
                                            contentDescription = "최근 만든 영상 썸네일",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier
                                                .padding(6.dp)
                                                .size(20.dp)
                                        )
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "🎬 최근 만든 디데이 릴스",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = recentVideo.name,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { showVideoPlayerDialog = true },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06D53)),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text("▶ 바로 재생", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        }
                                        OutlinedButton(
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
                                                    context.startActivity(Intent.createChooser(shareIntent, "최근 디데이 릴스 공유하기"))
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "공유 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("📤 공유하기", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 스튜디오 히어로 배너 카드
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color(0xFF1F2421)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp, 54.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFE06D53)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "D+",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "하루 한 장, 아이가 자라나는\n시간의 기록 ✨",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 24.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "매일 찍은 달력·아기 사진을 날짜순으로 줄 세워 따뜻한 오르골 릴스로 엮어 드려요.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }

                    // 핵심 기능 요약 2열 카드
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Movie, contentDescription = null, tint = Color(0xFFE06D53))
                                Text("1일 1장 자동 선별", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("스크린샷은 빼고 가장 선명한 컷만 골라요", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFFE06D53))
                                Text("오르골 BGM 합성", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("내장 오르골 자장가와 내 음악 파일을 넣어요", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // 안심 온디바이스 배지
                    Surface(
                        color = Color(0xFFE8F5E9),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "100% 온디바이스 · 모든 사진과 영상은 내 폰 안에서만 안전하게 처리됩니다",
                                color = Color(0xFF1B5E20),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 하단 엄지 조작 3트랙 진입 액션 영역
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            accuracyMode = ScanAccuracyMode.STRICT
                            isConfigMode = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06D53)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("✨ AI 달력 자동 찾기 (기준 설정)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                accuracyMode = ScanAccuracyMode.BABY_FACE_ONLY
                                isConfigMode = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFE06D53))
                        ) {
                            Icon(Icons.Default.ChildCare, contentDescription = null, tint = Color(0xFFE06D53), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("👶 아기 얼굴만 찾기", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE06D53))
                        }

                        OutlinedButton(
                            onClick = { launchDirectPhotoPickerSafely() },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("🖼️ 내가 직접 고르기", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
