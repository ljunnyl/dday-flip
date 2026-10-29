package com.ddayflip.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.core.content.ContextCompat
import com.ddayflip.app.core.domain.CalendarRoi
import com.ddayflip.app.core.domain.DdayCalculator
import com.ddayflip.app.core.domain.DdayStartRule
import com.ddayflip.app.domain.ocr.DdayTextParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * R01. 첫 화면 (홈 & 온보딩) + R02. 기준 등록 (실제 사진 OCR 자동 감지 & 탭하여 직접 지정)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartScreen(
    onStartScan: (
        birthday: LocalDate,
        rule: DdayStartRule,
        calendarRoi: CalendarRoi?,
        todayMaxDday: Int
    ) -> Unit = { _, _, _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isConfigMode by remember { mutableStateOf(false) }

    var birthdayYear by remember { mutableStateOf("2024") }
    var birthdayMonth by remember { mutableStateOf("05") }
    var birthdayDay by remember { mutableStateOf("21") }
    var selectedRule by remember { mutableStateOf(DdayStartRule.ONE_BASED) }

    var referencePhotoUri by remember { mutableStateOf<Uri?>(null) }
    var referenceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedRoi by remember { mutableStateOf<CalendarRoi?>(null) }
    var isDetectingRoi by remember { mutableStateOf(false) }
    var detectedLabel by remember { mutableStateOf<String?>(null) }

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
        val y = birthdayYear.toIntOrNull() ?: 2024
        val m = birthdayMonth.toIntOrNull() ?: 5
        val d = birthdayDay.toIntOrNull() ?: 21
        val birthday = LocalDate.of(y, m, d)
        val todayMaxDday = DdayCalculator(birthday, selectedRule)
            .calculate(LocalDateTime.now()).dday
        onStartScan(birthday, selectedRule, selectedRoi, todayMaxDday)
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
            // R01. 첫 화면 (홈 & 온보딩)
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Spacer(modifier = Modifier.height(10.dp))

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
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF9F7F4)),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFFE5E0D8), RoundedCornerShape(18.dp))
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
                                    fontSize = 14.sp
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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFBF9F6)),
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
                                    .height(340.dp)
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
                                        Text("가로", fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(28.dp))
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
                                        Text("세로", fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(28.dp))
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

                // 스캔 시작 CTA
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
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06D53)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("내 사진에서 달력 사진 찾기 시작 →", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
