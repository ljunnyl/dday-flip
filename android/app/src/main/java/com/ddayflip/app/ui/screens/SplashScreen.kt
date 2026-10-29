package com.ddayflip.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddayflip.app.R
import kotlinx.coroutines.delay

/**
 * R00 감성 스플래시 & 웰컴 화면
 * - 킨포크 린넨 감성 비주얼 배경
 * - 1.8초 후 또는 화면 탭 시 메인 홈(START)으로 자동 전환
 */
@Composable
fun SplashScreen(
    onFinish: () -> Unit
) {
    val alphaAnim = remember { Animatable(0f) }
    val contentAlphaAnim = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // 페이드인 애니메이션
        alphaAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
        )
        contentAlphaAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
        )
        // 1.8초 대기 후 종료
        delay(1800)
        onFinish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onFinish()
            }
    ) {
        // 1. 킨포크 배경 이미지
        Image(
            painter = painterResource(id = R.drawable.splash_kinfolk),
            contentDescription = "스플래시 배경",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(alphaAnim.value)
        )

        // 2. 상하단 감성 그라데이션 오버레이 (텍스트 가독성 확보)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.45f),
                        0.25f to Color.Transparent,
                        0.65f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.65f)
                    )
                )
        )

        // 3. 상단 타이틀 영역
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 64.dp, start = 28.dp, end = 28.dp)
                .alpha(contentAlphaAnim.value),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .background(
                        Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "D-day Flip v3.0",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "민서의 D-Day 넘김",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "우리 아이가 자라나는 매일의 순간",
                color = Color(0xFFF3EED9),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // 4. 하단 안심 뱃지 및 안내
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 24.dp, end = 24.dp)
                .alpha(contentAlphaAnim.value),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "🔒 100% 온디바이스 · 사진 외부 전송 없음",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "화면을 터치하면 바로 시작합니다",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp
            )
        }
    }
}
