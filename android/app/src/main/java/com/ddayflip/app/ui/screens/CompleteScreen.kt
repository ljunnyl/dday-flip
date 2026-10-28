package com.ddayflip.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ddayflip.app.domain.video.MediaCodecVideoExporter
import com.ddayflip.app.domain.video.VideoExporter
import java.io.File

/**
 * R06. 완성된 영상 재생 및 저장 & 공유 화면
 */
@OptIn(UnstableApi::class)
@ExperimentalMaterial3Api
@Composable
fun CompleteScreen(
    videoFile: File? = null,
    videoDurationSeconds: Double = 8.0,
    totalFrames: Int = 120,
    videoResolution: String = "1080x1920 (9:16 FHD)",
    videoExporter: VideoExporter = MediaCodecVideoExporter(),
    onRestart: () -> Unit = {},
    onReedit: () -> Unit = {}
) {
    val context = LocalContext.current
    var isSavedToGallery by remember { mutableStateOf(false) }

    // Media3 ExoPlayer 설정
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
        }
    }

    DisposableEffect(videoFile) {
        if (videoFile != null && videoFile.exists()) {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(videoFile))
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
        }
        onDispose {
            exoPlayer.release()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("영상 완성 ✨", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "우리 아기 D-day 릴스가 완성되었어요! 🎉",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                // 9:16 Media3 ExoPlayer 재생 화면
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF1F2421)),
                    contentAlignment = Alignment.Center
                ) {
                    if (videoFile != null && videoFile.exists()) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "미리보기 영상 대기 중",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Video Meta Information Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("해상도", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(videoResolution, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("프레임 수", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$totalFrames 장", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("재생 시간", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${videoDurationSeconds}초", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Action Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 공유 버튼 (Primary)
                Button(
                    onClick = {
                        if (videoFile != null && videoFile.exists()) {
                            try {
                                val contentUri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    videoFile
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, contentUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "우리 아기 디데이 릴스 공유하기"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "영상 공유 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "공유할 비디오 파일이 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE06D53)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("인스타그램 릴스 공유 / 가족에게 보내기 🚀", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                // 갤러리 저장 버튼
                OutlinedButton(
                    onClick = {
                        if (videoFile != null && videoFile.exists()) {
                            val savedUri = videoExporter.saveVideoToGallery(context, videoFile)
                            if (savedUri != null) {
                                isSavedToGallery = true
                                Toast.makeText(
                                    context,
                                    "갤러리에 저장되었습니다! (Movies/DdayFlip)",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(context, "갤러리 저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "저장할 비디오 파일이 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isSavedToGallery) "✓ 갤러리 저장 완료" else "내 폰 갤러리에 저장하기 ✨",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // 편집 및 홈 버튼 행
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onReedit,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("사진 다시 수정하기", fontSize = 13.sp)
                    }
                    TextButton(
                        onClick = onRestart,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("홈으로 돌아가기", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
