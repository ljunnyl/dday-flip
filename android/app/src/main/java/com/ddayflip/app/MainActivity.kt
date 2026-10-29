package com.ddayflip.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.ddayflip.app.core.domain.CalendarPhotoEntry
import com.ddayflip.app.core.domain.CalendarRoi
import com.ddayflip.app.core.domain.DdayStartRule
import com.ddayflip.app.core.domain.SequenceValidationResult
import com.ddayflip.app.data.MediaStorePhotoRepository
import com.ddayflip.app.domain.repository.PhotoRepository
import com.ddayflip.app.domain.video.VideoExportConfig
import com.ddayflip.app.service.PhotoScanForegroundService
import com.ddayflip.app.service.ScanServiceState
import com.ddayflip.app.ui.screens.CompleteScreen
import com.ddayflip.app.ui.screens.ExportingScreen
import com.ddayflip.app.ui.screens.ReviewScreen
import com.ddayflip.app.ui.screens.ScanScreen
import com.ddayflip.app.ui.screens.StartScreen
import com.ddayflip.app.ui.theme.DdayFlipTheme
import java.io.File
import java.time.LocalDate

enum class AppScreen {
    START,
    SCAN,
    REVIEW,
    EXPORTING,
    COMPLETE
}

class MainActivity : ComponentActivity() {

    private val photoRepository: PhotoRepository by lazy {
        MediaStorePhotoRepository(applicationContext)
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // 권한 허용 여부와 무관하게 서비스는 계속 구동 (알림 노출 여부만 차이)
        }

    private var appScreenState = mutableStateOf(AppScreen.START)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestNotificationPermission()
        syncScreenWithServiceState()

        setContent {
            DdayFlipTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { appScreenState }
                    var scanStartDate by remember { mutableStateOf(LocalDate.now().minusDays(150)) }
                    var scanEndDate by remember { mutableStateOf(LocalDate.now()) }
                    var ddayRule by remember { mutableStateOf(DdayStartRule.ONE_BASED) }
                    var calendarRoi by remember { mutableStateOf<CalendarRoi?>(null) }
                    var todayMaxDday by remember { mutableIntStateOf(150) }
                    var accuracyMode by remember { mutableStateOf(com.ddayflip.app.core.domain.ScanAccuracyMode.STRICT) }
                    var validationResult by remember {
                        mutableStateOf<SequenceValidationResult?>(null)
                    }
                    var entriesToExport by remember {
                        mutableStateOf<List<CalendarPhotoEntry>>(emptyList())
                    }
                    var exportedVideoFile by remember { mutableStateOf<File?>(null) }
                    val exportConfig = remember { VideoExportConfig() }

                    // 서비스 상태 관찰 및 화면 자동 복구
                    val currentServiceState by PhotoScanForegroundService.scanState.collectAsState()
                    LaunchedEffect(currentServiceState) {
                        when (val state = currentServiceState) {
                            is ScanServiceState.Scanning -> {
                                if (currentScreen != AppScreen.SCAN) {
                                    currentScreen = AppScreen.SCAN
                                }
                            }
                            is ScanServiceState.Completed -> {
                                validationResult = state.result
                            }
                            else -> {}
                        }
                    }

                    when (currentScreen) {
                        AppScreen.START -> {
                            StartScreen(
                                onStartScan = { birthday, rule, roi, maxDday, mode ->
                                    ddayRule = rule
                                    scanStartDate = birthday
                                    scanEndDate = birthday.plusDays(150)
                                    calendarRoi = roi
                                    todayMaxDday = maxDday
                                    accuracyMode = mode
                                    currentScreen = AppScreen.SCAN
                                },
                                onDirectPhotosSelected = { directEntries ->
                                    validationResult = SequenceValidationResult(
                                        validatedEntries = directEntries,
                                        issues = emptyList(),
                                        missingDays = emptyList()
                                    )
                                    currentScreen = AppScreen.REVIEW
                                }
                            )
                        }
                        AppScreen.SCAN -> {
                            ScanScreen(
                                startDate = scanStartDate,
                                endDate = scanEndDate,
                                birthday = scanStartDate,
                                ddayRule = ddayRule,
                                calendarRoi = calendarRoi,
                                todayMaxDday = todayMaxDday,
                                accuracyMode = accuracyMode,
                                photoRepository = photoRepository,
                                onCancelScan = {
                                    currentScreen = AppScreen.START
                                },
                                onNavigateToReview = { result ->
                                    validationResult = result
                                    currentScreen = AppScreen.REVIEW
                                }
                            )
                        }
                        AppScreen.REVIEW -> {
                            val result = validationResult
                            ReviewScreen(
                                issues = result?.issues ?: emptyList(),
                                entries = result?.validatedEntries ?: emptyList(),
                                onProceedToExport = { selectedList ->
                                    entriesToExport = selectedList
                                    currentScreen = AppScreen.EXPORTING
                                }
                            )
                        }
                        AppScreen.EXPORTING -> {
                            ExportingScreen(
                                entries = entriesToExport,
                                onExportComplete = { file ->
                                    exportedVideoFile = file
                                    currentScreen = AppScreen.COMPLETE
                                },
                                onCancel = {
                                    currentScreen = AppScreen.REVIEW
                                }
                            )
                        }
                        AppScreen.COMPLETE -> {
                            val entries = entriesToExport.ifEmpty {
                                validationResult?.validatedEntries ?: emptyList()
                            }
                            val totalFrames = exportConfig.calculateTotalFrames(entries.size)
                            val durationSeconds = exportConfig.calculateDurationMs(entries.size) / 1000.0

                            CompleteScreen(
                                videoFile = exportedVideoFile,
                                videoDurationSeconds = durationSeconds,
                                totalFrames = totalFrames,
                                onRestart = {
                                    exportedVideoFile = null
                                    validationResult = null
                                    entriesToExport = emptyList()
                                    PhotoScanForegroundService.resetState()
                                    currentScreen = AppScreen.START
                                },
                                onReedit = {
                                    currentScreen = AppScreen.REVIEW
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        syncScreenWithServiceState()
    }

    private fun syncScreenWithServiceState() {
        when (val state = PhotoScanForegroundService.scanState.value) {
            is ScanServiceState.Scanning -> {
                appScreenState.value = AppScreen.SCAN
            }
            is ScanServiceState.Completed -> {
                appScreenState.value = AppScreen.SCAN
            }
            else -> {}
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
