package com.ddayflip.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.material.icons.filled.Close
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

/**
 * R01. 첫 화면 (홈 & 온보딩 + 최근 영상 플레이어 + 테마 3종) + R02. 기준 등록 (실제 사진 OCR 자동 감지 & 탭하여 직접 지정)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartScreen(
    currentTheme: AppVisualTheme = AppVisualTheme.WARM_COTTON,
    onThemeChange: (AppVisualTheme) -> Unit = {},
    recentVideoFile: File? = null,
    onStartScan: (
        birthday: LocalDate,
        rule: DdayStartRule,
        calendarRoi: CalendarRoi?,
        todayMaxDday: Int,
        accuracyMode: ScanAccuracyMode
    ) -> Unit = { _, _, _, _, _ -> },
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

    var referencePhotoUri by remember { mutableStateOf<Uri?>(null) }
    var referenceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedRoi by remember { mutableStateOf<CalendarRoi?>(null) }
    var isDetectingRoi by remember { mutableStateOf(false) }
    var detectedLabel by remember { mutableStateOf<String?>(null) }

    // R01 최근 만든 영상 탐색 (전달받은 파일 또는 cacheDir 내 가장 최근 dday_flip_*.mp4)
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

                                // 1. D-day 파서 패턴(D+, 숫자일 등) 매칭 블록 우선 탐색
                                for (block in visionText.textBlocks) {
                                    val parsed = parser.parse(block.text)
                                    if (parsed != null) {
                                        val r = block.boundingBox
                                        if (r != null) {
                                            foundRoi = CalendarRoi(
                                                x = (r.left.toFloat() / bmp.width).coerceIn(0f, 0.9f),
                                                y = (r.top.toFloat() / bmp.height).coerceIn(0f, 0.9f),
                                                width = (r.width().toFloat() / bmp.width).coerceIn(0.1f, 1f),
                                                height = (r.height().toFloat() / bmp.height).coerceIn(0.1f, 1f)
                                            ).withMargin(0.20f)
                                            foundText = block.text.trim()
                                            break
                                        }
                                    }
                                }

                                // 2. 숫자가 포함된 블록 차선 탐색
                                if (foundRoi == null) {
                                    for (block in visionText.textBlocks) {
                                        if (block.text.any { it.isDigit() }) {
                                            val r = block.boundingBox
                                            if (r != null) {
                                                foundRoi = CalendarRoi(
                                                    x = (r.left.toFloat() / bmp.width).coerceIn(0f, 0.9f),
                                                    y = (r.top.toFloat() / bmp.height).coerceIn(0f, 0.9f),
                                                    width = (r.width().toFloat() / bmp.width).coerceIn(0.1f, 1f),
                                                    height = (r.height().toFloat() / bmp.height).coerceIn(0.1f, 1f)
                                                ).withMargin(0.20f)
                                                foundText = block.text.trim()
                                                break
                                            }
                                        }
                                    }
                                }

                                if (foundRoi != null) {
                                    selectedRoi = foundRoi
                                    detectedLabel = "AI 달력 감지 성공: '$foundText' ✨ (터치하여 위치 이동 가능)"
                                    Log.d("DdayScanner", "Reference Photo: Auto detected ROI at $foundRoi text=$foundText")
                                } else {
                                    // 텍스트 미감지 시: 사진 중앙 기본 박스 + 탭해서 직접 지정 유도
                                    selectedRoi = CalendarRoi(0.25f, 0.15f, 0.50f, 0.40f)
                                    detectedLabel = "달력 글씨를 자동 감지하지 못했어요. 달력이 있는 곳을 직접 탭해주세요! 👆"
                                    Log.d("DdayScanner", "Reference Photo: Text not found, fallback to center ROI")
                                }
                                isDetectingRoi = false
                            }
                            .addOnFailureListener {
                                selectedRoi = CalendarRoi(0.25f, 0.15f, 0.50f, 0.40f)
                                detectedLabel = "달력이 있는 위치를 사진에서 직접 탭해주세요! 👆"
                                isDetectingRoi = false
                            }
                    } catch (e: Exception) {
                        selectedRoi = CalendarRoi(0.25f, 0.15f, 0.50f, 0.40f)
                        detectedLabel = "달력이 있는 위치를 사진에서 직접 탭해주세요! 👆"
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

    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun proceedToScan() {
        val y = birthdayYear.toIntOrNull() ?: 2026
        val m = birthdayMonth.toIntOrNull() ?: 5
        val d = birthdayDay.toIntOrNull() ?: 27
        val birthday = LocalDate.of(y, m, d)
        val todayMaxDday = DdayCalculator(birthday, selectedRule)
            .calculate(LocalDateTime.now()).dday
        onStartScan(birthday, selectedRule, selectedRoi, todayMaxDday, accuracyMode)
    }

    val directPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 150)
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val y = birthdayYear.toIntOrNull() ?: 2026
                val m = birthdayMonth.toIntOrNull() ?: 5
                val d = birthdayDay.toIntOrNull() ?: 27
                val birthday = LocalDate.of(y, m, d)
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

                        val calcResult = ddayCalc.calculate(dateTaken.atStartOfDay())
                        directEntries.add(
                            CalendarPhotoEntry(
                                id = uri.toString(),
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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            proceedToScan()
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
                title = { Text("D-day Flip", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                actions = {
                    if (isConfigMode) {
                        TextButton(onClick = { isConfigMode = false }) {
                            Text("홈으로", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (!isConfigMode) {
            // ==========================================
            // R01. 첫 화면 (홈 & 온보딩 + 테마 3종 + 최근 영상 카드)
            // ==========================================
            val homeScrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(homeScrollState)
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 상단 테마 3종 선택 칩 (☁️ 웜 코튼 / 🤍 미니멀 화이트 / 🌙 수유등 다크)
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

                    // R01 최근 만든 영상 홈 플레이어 카드 (최근 비디오 파일이 존재할 때 노출)
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
                                // 첫 프레임 썸네일
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
                                        text = "🎬 최근 만든 D-day 릴스 영상",
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

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "🔒 100% 온디바이스: 사진과 영상은 폰 안에서만 안전하게 만들어져요",
                                color = Color(0xFF1B5E20),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color(0xFF1F2421)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp, 70.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFE06D53)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "D+",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 24.sp
                                    )
                                }
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "매일 자라나는 우리 아기,\n한 편의 영화가 되는 순간 ✨",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 28.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "D-day 달력 사진을 찾아 날짜 순으로 착착 넘기는\n감성 릴스 영상으로 만들어 드려요.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp, 64.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFE06D53)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Movie, contentDescription = null, tint = Color.White)
                            }
                            Column {
                                Text(
                                    text = "1일 1장 최고 선명도 자동 선별",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "하루에 여러 장 찍어도 가장 또렷한 1장만 골라줘요",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = { isConfigMode = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06D53)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("디데이 영상 만들기 시작 ✨", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        } else {
            // ==========================================
            // R02. 기준 등록 (실제 사진 OCR 자동 감지 & 탭하여 직접 지정)
            // ==========================================
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = "우리 아기 생일 및 기준 설정",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                // 생일 입력 카드
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "아기 생년월일 (실제 날짜 입력)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
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
                                label = { Text("생일 당일 D+1 (한국식)") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = selectedRule == DdayStartRule.ZERO_BASED,
                                onClick = { selectedRule = DdayStartRule.ZERO_BASED },
                                label = { Text("생일 당일 D+0 (서양식)") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // ==========================================
                // 기준 D-day 달력 사진 등록 및 시각 검증
                // ==========================================
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
                                text = "우리 집 D-day 달력 기준 사진 등록",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Badge(containerColor = Color(0xFFFFECE5)) {
                                Text("핵심 개선 ✨", color = Color(0xFFE06D53), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Text(
                            text = "달력이 나온 사진 1장을 고르면 앱이 달력 위치를 찾고, 원하는 위치를 직접 탭하여 지정할 수도 있어요.",
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
                            // 사진 전체화면(원본 비율 전체 표시) + 드래그 이동 및 크기 조절
                            var roiBoxWidthRatio by remember { mutableFloatStateOf(0.40f) }
                            var roiBoxHeightRatio by remember { mutableFloatStateOf(0.18f) }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(480.dp)
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
                                                .pointerInput(bmp, roiBoxWidthRatio, roiBoxHeightRatio) {
                                                    detectTapGestures { offset ->
                                                        val tapXFrac = offset.x / size.width
                                                        val tapYFrac = offset.y / size.height
                                                        val bw = roiBoxWidthRatio.coerceIn(0.1f, 0.95f)
                                                        val bh = roiBoxHeightRatio.coerceIn(0.05f, 0.95f)
                                                        selectedRoi = CalendarRoi(
                                                            x = (tapXFrac - bw / 2f).coerceIn(0f, 1f - bw),
                                                            y = (tapYFrac - bh / 2f).coerceIn(0f, 1f - bh),
                                                            width = bw,
                                                            height = bh
                                                        )
                                                        detectedLabel = "달력 위치를 직접 지정했어요 🎯 (원하는 곳 탭 또는 드래그 이동)"
                                                    }
                                                }
                                                .pointerInput(bmp, roiBoxWidthRatio, roiBoxHeightRatio) {
                                                    detectDragGestures { change, dragAmount ->
                                                        change.consume()
                                                        val cur = selectedRoi ?: CalendarRoi(0.2f, 0.2f, roiBoxWidthRatio, roiBoxHeightRatio)
                                                        val dx = dragAmount.x / size.width
                                                        val dy = dragAmount.y / size.height
                                                        selectedRoi = CalendarRoi(
                                                            x = (cur.x + dx).coerceIn(0f, 1f - cur.width),
                                                            y = (cur.y + dy).coerceIn(0f, 1f - cur.height),
                                                            width = cur.width,
                                                            height = cur.height
                                                        )
                                                        detectedLabel = "달력 위치를 드래그하여 맞췄어요 🎯"
                                                    }
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

                                                    // 주황색 반투명 채우기 + 테두리
                                                    drawRect(
                                                        color = Color(0x33E06D53),
                                                        topLeft = Offset(left, top),
                                                        size = Size(boxW, boxH)
                                                    )
                                                    drawRect(
                                                        color = Color(0xFFE06D53),
                                                        topLeft = Offset(left, top),
                                                        size = Size(boxW, boxH),
                                                        style = Stroke(width = 5f)
                                                    )
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
                                            text = if (isDetectingRoi) "달력 찾는 중..." else "달력 영역 지정됨 🎯",
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

                            // 달력 영역 박스 크기 미세 조절 컨트롤러
                            Surface(
                                color = Color(0xFFFFF3E0),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "📏 달력 영역 박스 크기 조절",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFD84315)
                                        )
                                        Text(
                                            text = "가로 ${(roiBoxWidthRatio * 100).toInt()}% · 세로 ${(roiBoxHeightRatio * 100).toInt()}%",
                                            fontSize = 11.sp,
                                            color = Color(0xFFBF360C)
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("가로", fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(28.dp), color = Color(0xFF3E2723))
                                        Slider(
                                            value = roiBoxWidthRatio,
                                            onValueChange = { newW ->
                                                roiBoxWidthRatio = newW
                                                selectedRoi?.let { cur ->
                                                    selectedRoi = CalendarRoi(
                                                        x = cur.x.coerceIn(0f, 1f - newW),
                                                        y = cur.y,
                                                        width = newW,
                                                        height = cur.height
                                                    )
                                                }
                                            },
                                            valueRange = 0.15f..0.90f,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("세로", fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(28.dp), color = Color(0xFF3E2723))
                                        Slider(
                                            value = roiBoxHeightRatio,
                                            onValueChange = { newH ->
                                                roiBoxHeightRatio = newH
                                                selectedRoi?.let { cur ->
                                                    selectedRoi = CalendarRoi(
                                                        x = cur.x,
                                                        y = cur.y.coerceIn(0f, 1f - newH),
                                                        width = cur.width,
                                                        height = newH
                                                    )
                                                }
                                            },
                                            valueRange = 0.08f..0.60f,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            // 감지 결과 피드백 & 탭 안내
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
                                        text = detectedLabel ?: "사진에서 실제 달력이 놓인 자리를 직접 탭해주세요!",
                                        fontSize = 11.sp,
                                        color = Color(0xFF1B5E20),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            // 버튼 옵션 행
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
                                    Text("📷 다른 사진", fontSize = 12.sp)
                                }
                                TextButton(
                                    onClick = {
                                        selectedRoi = null
                                        detectedLabel = "위치 제한 없이 전체 사진에서 탐색합니다."
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("전체 탐색", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ==========================================
                // AI 탐색 정확도 모드 선택기 (엄격 / 관대 / 고정밀)
                // ==========================================
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("AI 탐색 정확도 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = accuracyMode == ScanAccuracyMode.STRICT,
                                onClick = { accuracyMode = ScanAccuracyMode.STRICT },
                                label = { Text("🎯 엄격", fontSize = 11.sp) },
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
                                label = { Text("📸 관대", fontSize = 11.sp) },
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

                Spacer(modifier = Modifier.height(12.dp))

                // 2트랙 진입 액션 버튼
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            directPhotoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFE06D53))
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color(0xFFE06D53))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("🖼️ 직접 고르기", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE06D53))
                    }

                    Button(
                        onClick = {
                            val permissionStatus = ContextCompat.checkSelfPermission(context, requiredPermission)
                            if (permissionStatus == PackageManager.PERMISSION_GRANTED) {
                                proceedToScan()
                            } else {
                                permissionLauncher.launch(requiredPermission)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06D53)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("✨ AI 자동 찾기", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
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

private fun loadSampledBitmap(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(stream, null, options)

            var inSampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            context.contentResolver.openInputStream(uri)?.use { secondStream ->
                val decodeOptions = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                }
                BitmapFactory.decodeStream(secondStream, null, decodeOptions)
            }
        }
    } catch (e: Exception) {
        null
    }
}
