package com.ddayflip.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.ddayflip.app.ui.screens.CompleteScreen
import com.ddayflip.app.ui.screens.ReviewScreen
import com.ddayflip.app.ui.screens.ScanScreen
import com.ddayflip.app.ui.screens.StartScreen
import com.ddayflip.app.ui.theme.DdayFlipTheme

enum class AppScreen {
    START,
    SCAN,
    REVIEW,
    COMPLETE
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DdayFlipTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf(AppScreen.START) }

                    when (currentScreen) {
                        AppScreen.START -> {
                            StartScreen(
                                onStartScan = { _, _ ->
                                    currentScreen = AppScreen.SCAN
                                }
                            )
                        }
                        AppScreen.SCAN -> {
                            ScanScreen(
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
