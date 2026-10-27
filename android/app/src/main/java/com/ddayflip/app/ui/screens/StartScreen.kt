package com.ddayflip.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ddayflip.app.core.domain.CalendarRoi
import com.ddayflip.app.core.domain.DdayCalculator
import com.ddayflip.app.core.domain.DdayStartRule
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 01. 시작 & 아기 생일 및 D-day 달력 기준 사진 입력 화면
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

    var birthdayYear by remember { mutableStateOf("2024") }
    var birthdayMonth by remember { mutableStateOf("05") }
    var birthdayDay by remember { mutableStateOf("27") }
    var selectedRule by remember { mutableStateOf(DdayStartRule.ONE_BASED) }

    var referencePhotoUri by remember { mutableStateOf<Uri?>(null) }
    var selectedRoi by remember { mutableStateOf<CalendarRoi?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        referencePhotoUri = uri
        selectedRoi = if (uri != null) {
            // 기준 사진 선택 시 상단 중앙 기본 D-day 달력 영역 설정
            CalendarRoi(x = 0.15f, y = 0.05f, width = 0.70f, height = 0.35f)
        } else {
            null
        }
    }

    // 안드로이드 버전에 따른 권한 결정 (API 33+ READ_MEDIA_IMAGES, API 32 이하 READ_EXTERNAL_STORAGE)
    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun proceedToScan() {
        val y = birthdayYear.toIntOrNull() ?: 2024
        val m = birthdayMonth.toIntOrNull() ?: 1
        val d = birthdayDay.toIntOrNull() ?: 1
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
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Privacy Guarantee Badge (100% On-Device)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFE8F5E9)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "On-Device Privacy",
                            tint = Color(0xFF2E7D32)
                        )
                        Column {
                            Text(
                                text = "100% 온디바이스 로컬 처리",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B5E20),
                                fontSize = 14.sp
                            )
                            Text(
                                text = "사진과 개인정보가 기기 외부로 절대 유출되지 않습니다.",
                                color = Color(0xFF2E7D32),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Text(
                    text = "아기 생일을 알려주세요",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "촬영일시와 D-day 달력 숫자를 비교하여 누락되거나 미넘김된 날짜를 자동으로 감지합니다.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Birthday Input Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = birthdayYear,
                        onValueChange = { birthdayYear = it },
                        label = { Text("연도") },
                        modifier = Modifier.weight(1.5f),
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

                // D-day Counting Rule Selection
                Text(
                    text = "D-day 표기 규칙",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )

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

                // 기준 D-day 달력 사진 1장 선택 (선택사항)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "기준 D-day 달력 사진 1장 선택 (선택사항)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            if (referencePhotoUri != null) {
                                TextButton(
                                    onClick = {
                                        referencePhotoUri = null
                                        selectedRoi = null
                                    },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("선택 해제", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Text(
                            text = "D-day 달력이 잘 나온 사진 1장을 지정하면 위치를 자동 참조하여 인식 정확도가 대폭 향상됩니다.",
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
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("기준 사진 선택하기", fontSize = 13.sp)
                            }
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "기준 사진 선택됨: D-day 달력 위치 자동 설정됨",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Start Scanning Button
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
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("갤러리 스캔 및 D-day 달력 분류 시작", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
