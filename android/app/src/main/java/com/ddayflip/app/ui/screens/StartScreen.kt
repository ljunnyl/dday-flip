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
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
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
import com.ddayflip.app.domain.ocr.MlKitBabyFaceDetector
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
import kotlin.math.max
import kotlin.math.min

/**
 * StartScreen 내부 서브 화면 라우트 (3가지 수집 모드별 맞춤형 화면 분리)
 */
enum class StartSubScreen {
    HOME,               // R01: 액자형 홈 화면
    CONFIG_CALENDAR_AI, // R02-A: 모드 1 (AI 달력 사진 자동 찾기 - 달력 드래그 ROI + 침대 구도 유사도)
    CONFIG_BABY_FACE,   // R02-B: 모드 2 (아기 얼굴 사진만 골라 찾기 - 달력 ROI 없이 우리 아기 기준 얼굴 1장 등록)
    DIRECT_DAY_PICKER   // R02-C: 모드 3 (날짜별 D+1~D+N 구분 직접 고르기 + 빈 날짜 앞뒤 ±3일 사진 끌어오기)
}

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

    var currentSubScreen by remember { mutableStateOf(StartSubScreen.HOME) }

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
    var babyFaceStatusLabel by remember { mutableStateOf<String?>(null) }

    // R01 최근 만든 영상 탐색
    var latestVideoFile by remember(recentVideoFile) { mutableStateOf<File?>(recentVideoFile) }
    var latestVideoThumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var showVideoPlayerDialog by remember { mutableStateOf(false) }

    LaunchedEffect(recentVideoFile, currentSubScreen) {
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

    // 기준 사진 로드 및 모드별 분석:
    // - CONFIG_CALENDAR_AI: ML Kit 한국어 OCR로 달력 ROI 위치 자동 탐색
    // - CONFIG_BABY_FACE: 달력 OCR 없이 ML Kit Face Detection으로 '우리 아기 얼굴' 등록 확인!
    LaunchedEffect(referencePhotoUri, currentSubScreen) {
        val uri = referencePhotoUri
        if (uri != null) {
            val bmp = withContext(Dispatchers.IO) {
                com.ddayflip.app.util.BitmapUtils.loadOrientedBitmap(context, uri, 1200)
            }
            referenceBitmap = bmp

            if (bmp != null) {
                if (currentSubScreen == StartSubScreen.CONFIG_BABY_FACE) {
                    selectedRoi = null
                    isDetectingRoi = true
                    babyFaceStatusLabel = "사진에서 우리 아기 얼굴 특징을 분석하는 중..."
                    val profile = withContext(Dispatchers.Default) {
                        val detector = MlKitBabyFaceDetector()
                        try {
                            detector.extractReferenceProfile(bmp)
                        } catch (_: Exception) {
                            null
                        } finally {
                            detector.close()
                        }
                    }
                    babyFaceStatusLabel = if (profile != null) {
                        "✅ 우리 아기 얼굴 등록 완료! 엄마·아빠 등 어른 사진을 걸러내고 우리 아기 사진만 찾아내요 ✨"
                    } else {
                        "👶 아기 사진이 등록되었어요! (정면 얼굴이 잘 나온 사진일수록 어른 사진과 더 정확히 구분해요)"
                    }
                    isDetectingRoi = false
                } else if (currentSubScreen == StartSubScreen.CONFIG_CALENDAR_AI) {
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
                        } catch (_: Exception) {
                            selectedRoi = CalendarRoi(0.25f, 0.15f, 0.50f, 0.35f)
                            detectedLabel = "👆 손가락으로 드래그하여 달력 영역을 지정하세요"
                            isDetectingRoi = false
                        }
                    }
                }
            }
        } else {
            referenceBitmap = null
            selectedRoi = null
            detectedLabel = null
            babyFaceStatusLabel = null
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        referencePhotoUri = uri
    }

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

    var pendingScanMode by remember { mutableStateOf<ScanAccuracyMode?>(null) }
    var pendingDirectPickerAfterPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (pendingDirectPickerAfterPermission) {
                pendingDirectPickerAfterPermission = false
                currentSubScreen = StartSubScreen.DIRECT_DAY_PICKER
            } else {
                proceedToScan(pendingScanMode)
            }
        } else {
            pendingDirectPickerAfterPermission = false
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "갤러리 사진을 불러오려면 사진 접근 권한이 필요합니다.",
                    withDismissAction = true
                )
            }
        }
    }

    fun openDirectDayByDayPicker() {
        val permissionStatus = ContextCompat.checkSelfPermission(context, requiredPermission)
        if (permissionStatus == PackageManager.PERMISSION_GRANTED) {
            currentSubScreen = StartSubScreen.DIRECT_DAY_PICKER
        } else {
            pendingDirectPickerAfterPermission = true
            permissionLauncher.launch(requiredPermission)
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
                title = {
                    Text(
                        text = when (currentSubScreen) {
                            StartSubScreen.HOME -> "디데이 넘김"
                            StartSubScreen.CONFIG_CALENDAR_AI -> "✨ AI 달력 사진 자동 찾기"
                            StartSubScreen.CONFIG_BABY_FACE -> "👶 아기 얼굴 사진만 골라 찾기"
                            StartSubScreen.DIRECT_DAY_PICKER -> "🖼️ 날짜별로 직접 고르기"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                actions = {
                    if (currentSubScreen != StartSubScreen.HOME) {
                        TextButton(onClick = { currentSubScreen = StartSubScreen.HOME }) {
                            Text("홈으로", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
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

                        Surface(
                            onClick = {
                                val themes = AppVisualTheme.values()
                                val nextIndex = (themes.indexOf(currentTheme) + 1) % themes.size
                                val nextTheme = themes[nextIndex]
                                onThemeChange(nextTheme)
                                Toast.makeText(context, "${nextTheme.emoji} ${nextTheme.title} 테마로 변경되었습니다", Toast.LENGTH_SHORT).show()
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
        when (currentSubScreen) {
            StartSubScreen.HOME -> {
                // ==========================================
                // R01. 첫 화면 — 갤러리 액자 & 단일 버튼 + 3모드 선택 바텀시트
                // ==========================================
                val recentVideo = latestVideoFile
                val hasRecentVideo = recentVideo != null && recentVideo.exists()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
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
                                } else when (currentTheme) {
                                    AppVisualTheme.DARK_ROOM -> Color(0xFF1E293B)
                                    AppVisualTheme.MINIMAL_WHITE -> Color(0xFFFFFFFF)
                                    AppVisualTheme.WARM_COTTON -> Color(0xFFF3ECE1)
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
                                text = "원하는 방식을 고르면 맞춤형 단계로 바로 안내해 드려요.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // 모드 1: AI 달력 사진 자동 찾기 (추천)
                            Card(
                                onClick = {
                                    showModePickerSheet = false
                                    accuracyMode = ScanAccuracyMode.STRICT
                                    currentSubScreen = StartSubScreen.CONFIG_CALENDAR_AI
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

                            // 모드 2: 아기 얼굴 사진만 골라 찾기 (기준 아기 얼굴 1장 등록 -> 어른 사진 배제)
                            Card(
                                onClick = {
                                    showModePickerSheet = false
                                    accuracyMode = ScanAccuracyMode.BABY_FACE_ONLY
                                    currentSubScreen = StartSubScreen.CONFIG_BABY_FACE
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
                                            text = "우리 아기 사진 1장만 알려주면 어른 사진과 구분해 날짜별로 모아요",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // 모드 3: 앨범에서 내가 직접 고르기 (날짜별 구분 + 빈 날짜 앞뒤 끌어오기)
                            Card(
                                onClick = {
                                    showModePickerSheet = false
                                    openDirectDayByDayPicker()
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
                                            text = "🖼️ 날짜별로 보며 내가 직접 고르기",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "D+1부터 날짜별로 묶인 사진에서 톡톡 고르고, 빈 날은 앞뒤에서 끌어와요",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            StartSubScreen.DIRECT_DAY_PICKER -> {
                // ==========================================
                // R02-C. 모드 3: 인앱 날짜별(D+1~D+N) 직접 고르기 + 빈 날짜 앞뒤 ±3일 끌어오기
                // ==========================================
                DirectDayByDayPickerContent(
                    modifier = Modifier.padding(padding),
                    birthdayYear = birthdayYear,
                    birthdayMonth = birthdayMonth,
                    birthdayDay = birthdayDay,
                    selectedRule = selectedRule,
                    onBirthdayChange = { y, m, d, rule ->
                        birthdayYear = y
                        birthdayMonth = m
                        birthdayDay = d
                        selectedRule = rule
                    },
                    onConfirmSelection = { entries ->
                        onDirectPhotosSelected(entries)
                    }
                )
            }

            StartSubScreen.CONFIG_CALENDAR_AI, StartSubScreen.CONFIG_BABY_FACE -> {
                // ==========================================
                // R02-A (AI 달력 사진 찾기) & R02-B (아기 얼굴 사진만 찾기 - 달력 ROI 완전 제거!)
                // ==========================================
                val isBabyFaceMode = (currentSubScreen == StartSubScreen.CONFIG_BABY_FACE)
                val scrollState = rememberScrollState()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(scrollState)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. 생일 입력 카드 (공통)
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

                    if (isBabyFaceMode) {
                        // =========================================================
                        // R02-B 전용: 👶 우리 아기 얼굴 기준 사진 1장 등록 (달력 ROI 드래그 없음!)
                        // =========================================================
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
                                        text = "👶 우리 아기 얼굴 기준 사진 1장 등록",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Badge(containerColor = Color(0xFFFFECE5)) {
                                        Text("어른 사진 구분 ✨", color = Color(0xFFE06D53), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Text(
                                    text = "달력이 없어도 괜찮아요! 우리 아기 얼굴이 잘 나온 사진 1장을 골라주시면 엄마·아빠 등 성인 사진과 구분해 우리 아기 사진만 날짜별로 모아드려요.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (referencePhotoUri == null) {
                                    Button(
                                        onClick = {
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFECE5)),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Icon(Icons.Default.Face, contentDescription = null, tint = Color(0xFFE06D53))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "📷 우리 아기 기준 사진 1장 고르기",
                                            color = Color(0xFFE06D53),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                } else {
                                    // 달력 드래그 박스 없이 깔끔한 아기 기준 사진 프리뷰 + 얼굴 인식 배지
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(260.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Color(0xFF1E201F)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val bmp = referenceBitmap
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = "우리 아기 기준 사진",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit
                                            )
                                        } else {
                                            CircularProgressIndicator(color = Color(0xFFE06D53))
                                        }
                                    }

                                    Surface(
                                        color = Color(0xFFF1F8E9),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = babyFaceStatusLabel ?: "✅ 우리 아기 기준 사진이 등록되었어요!",
                                            fontSize = 11.sp,
                                            color = Color(0xFF1B5E20),
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }

                                    // 침대/배경 유사도 옵션 (선택)
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
                                                    text = "📐 이 사진과 비슷한 침대/배경 사진만 추리기 (선택)",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFBF360C)
                                                )
                                                Text(
                                                    text = "다양한 장소의 일상 아기 사진까지 모두 모으려면 꺼두세요",
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

                                    OutlinedButton(
                                        onClick = {
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("📷 다른 아기 사진으로 바꾸기", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    } else {
                        // =========================================================
                        // R02-A 전용: 📐 기준 사진 & 달력 영역 드래그 지정 카드
                        // =========================================================
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

                        // 달력 탐색 민감도 3종 칩 (AI 달력 모드에서만 표시)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("🔍 달력 글자 인식 범위", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                                    FilterChip(
                                        selected = accuracyMode == ScanAccuracyMode.RELAXED,
                                        onClick = { accuracyMode = ScanAccuracyMode.RELAXED },
                                        label = { Text("📸 달력+일상", fontSize = 11.sp) },
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
                    }

                    // 배경음악(BGM) 설정 카드 (공통)
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

                    // 하단 단일 메인 시작 버튼
                    val targetScanMode = if (isBabyFaceMode) ScanAccuracyMode.BABY_FACE_ONLY else accuracyMode
                    Button(
                        onClick = {
                            pendingScanMode = targetScanMode
                            val permissionStatus = ContextCompat.checkSelfPermission(context, requiredPermission)
                            if (permissionStatus == PackageManager.PERMISSION_GRANTED) {
                                proceedToScan(targetScanMode)
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
                        Icon(
                            imageVector = if (isBabyFaceMode) Icons.Default.ChildCare else Icons.Default.CalendarMonth,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBabyFaceMode) "👶 우리 아기 얼굴 사진 자동 찾기 시작" else "✨ AI 달력 사진 자동 찾기 시작",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
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
