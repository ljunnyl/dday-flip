package com.ddayflip.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.ddayflip.app.core.domain.DdayStartRule
import com.ddayflip.app.data.MediaStorePhotoRepository
import com.ddayflip.app.domain.repository.PhotoRepository
import com.ddayflip.app.ui.screens.CompleteScreen
import com.ddayflip.app.ui.screens.ReviewScreen
import com.ddayflip.app.ui.screens.ScanScreen
import com.ddayflip.app.ui.screens.StartScreen
import com.ddayflip.app.ui.theme.DdayFlipTheme
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

                    when (currentScreen) {
                        AppScreen.START -> {
                            StartScreen(
                                onStartScan = { birthday, rule ->
                                    ddayRule = rule
                                    scanStartDate = birthday
                                    // 1단계 목표: 생일 당일부터 약 150일간의 사진 스캔
                                    scanEndDate = birthday.plusDays(150)
                                    currentScreen = AppScreen.SCAN
                                }
                            )
                        }
                        AppScreen.SCAN -> {
                            ScanScreen(
                                startDate = scanStartDate,
                                endDate = scanEndDate,
                                photoRepository = photoRepository,
                                onNavigateToReview = {
                                    currentScreen = AppScreen.REVIEW
                                }
                            )
                        }
                        AppScreen.REVIEW -> {
                            ReviewScreen(
                                onProceedToExport = {
                                    currentScreen = AppScreen.COMPLETE
                                }
                            )
                        }
                        AppScreen.COMPLETE -> {
                            CompleteScreen(
                                onRestart = {
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
