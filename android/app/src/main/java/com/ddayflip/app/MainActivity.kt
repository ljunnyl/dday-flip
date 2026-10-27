package com.ddayflip.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.ddayflip.app.core.domain.CalendarRoi
import com.ddayflip.app.core.domain.DdayStartRule
import com.ddayflip.app.core.domain.SequenceValidationResult
import com.ddayflip.app.data.MediaStorePhotoRepository
import com.ddayflip.app.domain.repository.PhotoRepository
import com.ddayflip.app.domain.video.VideoExportConfig
import com.ddayflip.app.ui.screens.CompleteScreen
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
    COMPLETE
}

class MainActivity : ComponentActivity() {

    private val photoRepository: PhotoRepository by lazy {
        MediaStorePhotoRepository(applicationContext)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DdayFlipTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf(AppScreen.START) }
                    var scanStartDate by remember { mutableStateOf(LocalDate.now().minusDays(150)) }
                    var scanEndDate by remember { mutableStateOf(LocalDate.now()) }
                    var ddayRule by remember { mutableStateOf(DdayStartRule.ONE_BASED) }
                    var calendarRoi by remember { mutableStateOf<CalendarRoi?>(null) }
                    var todayMaxDday by remember { mutableIntStateOf(150) }
                    var validationResult by remember {
                        mutableStateOf<SequenceValidationResult?>(null)
                    }
                    var exportedVideoFile by remember { mutableStateOf<File?>(null) }
                    val exportConfig = remember { VideoExportConfig() }

                    when (currentScreen) {
                        AppScreen.START -> {
                            StartScreen(
                                onStartScan = { birthday, rule, roi, maxDday ->
                                    ddayRule = rule
                                    scanStartDate = birthday
                                    // 1단계 목표: 생일 당일부터 약 150일간의 사진 스캔
                                    scanEndDate = birthday.plusDays(150)
                                    calendarRoi = roi
                                    todayMaxDday = maxDday
                                    currentScreen = AppScreen.SCAN
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
                                photoRepository = photoRepository,
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
                                onProceedToExport = { file ->
                                    exportedVideoFile = file
                                    currentScreen = AppScreen.COMPLETE
                                }
                            )
                        }
                        AppScreen.COMPLETE -> {
                            val entries = validationResult?.validatedEntries ?: emptyList()
                            val totalFrames = exportConfig.calculateTotalFrames(entries.size)
                            val durationSeconds = exportConfig.calculateDurationMs(entries.size) / 1000.0

                            CompleteScreen(
                                videoFile = exportedVideoFile,
                                videoDurationSeconds = durationSeconds,
                                totalFrames = totalFrames,
                                onRestart = {
                                    exportedVideoFile = null
                                    validationResult = null
                                    currentScreen = AppScreen.START
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
