package com.ddayflip.app.domain.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ddayflip.app.core.domain.CalendarPhotoEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

interface VideoExporter {
    suspend fun exportVideo(
        context: Context,
        entries: List<CalendarPhotoEntry>,
        outputFile: File,
        config: VideoExportConfig = VideoExportConfig(),
        onProgress: (Float) -> Unit = {}
    ): Result<File>

    fun saveVideoToGallery(context: Context, videoFile: File): Uri?
}

class MediaCodecVideoExporter : VideoExporter {

    override suspend fun exportVideo(
        context: Context,
        entries: List<CalendarPhotoEntry>,
        outputFile: File,
        config: VideoExportConfig,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.Default) {
        if (entries.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Export entries cannot be empty"))
        }

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var isMuxerStarted = false
        var videoTrackIndex = -1

        try {
            // 부모 디렉토리 생성
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }

            // H.264 / AVC 비디오 포맷 설정
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                config.width,
                config.height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                )
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val totalFrames = config.calculateTotalFrames(entries.size)
            val framesPerPhoto = config.framesPerPhoto
            val frameDurationUs = 1_000_000L / config.frameRate
            val nv12BufferSize = config.width * config.height * 3 / 2
            val nv12Buffer = ByteArray(nv12BufferSize)

            var currentFrameIndex = 0
            val bufferInfo = MediaCodec.BufferInfo()

            // 사진별 프레임 렌더링 & 인코딩 파이프라인
            for ((photoIndex, entry) in entries.withIndex()) {
                // 1. 해당 사진의 합성 프레임 생성 (FIT_CENTER + D-day 뱃지)
                val compositeBitmap = DdayVideoRenderer.renderCompositeBitmap(context, entry, config)
                DdayVideoRenderer.convertBitmapToNv12(compositeBitmap, config.width, config.height, nv12Buffer)
                compositeBitmap.recycle()

                // 2. 해당 사진에 할당된 프레임 수(기본 18프레임)만큼 인코더에 큐잉
                for (f in 0 until framesPerPhoto) {
                    val ptsUs = currentFrameIndex * frameDurationUs

                    // 코덱 입력 버퍼 대기
                    var inputIndex = -1
                    while (inputIndex < 0) {
                        inputIndex = encoder.dequeueInputBuffer(10_000)
                        drainEncoder(encoder, muxer, bufferInfo, isMuxerStarted) { isMuxerStarted = true; videoTrackIndex = it }
                    }

                    val inputBuffer = encoder.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(nv12Buffer)
                    encoder.queueInputBuffer(inputIndex, 0, nv12BufferSize, ptsUs, 0)

                    drainEncoder(encoder, muxer, bufferInfo, isMuxerStarted) { isMuxerStarted = true; videoTrackIndex = it }

                    currentFrameIndex++
                    val progress = (currentFrameIndex.toFloat() / totalFrames.toFloat()).coerceIn(0f, 0.95f)
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
            }

            // 3. 인코딩 종료 플래그(EOS) 전송
            var eosQueued = false
            while (!eosQueued) {
                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val ptsUs = currentFrameIndex * frameDurationUs
                    encoder.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                }
                drainEncoder(encoder, muxer, bufferInfo, isMuxerStarted) { isMuxerStarted = true; videoTrackIndex = it }
            }

            // 4. 남은 모든 출력 버퍼 드레인 완료 (최대 100회 시도 후 안전 탈출)
            drainEncoder(encoder, muxer, bufferInfo, isMuxerStarted, drainAll = true) { isMuxerStarted = true; videoTrackIndex = it }

            withContext(Dispatchers.Main) {
                onProgress(1.0f)
            }

            android.util.Log.i("VideoExporter", "Encoding completed successfully: ${outputFile.length()} bytes")
            Result.success(outputFile)
        } catch (e: Exception) {
            android.util.Log.e("VideoExporter", "Encoding failed with exception", e)
            outputFile.delete()
            Result.failure(e)
        } finally {
            try {
                encoder?.stop()
            } catch (e: Exception) {
                android.util.Log.w("VideoExporter", "encoder.stop failed: ${e.message}")
            }
            try {
                encoder?.release()
            } catch (_: Exception) {}

            try {
                if (isMuxerStarted) {
                    muxer?.stop()
                }
            } catch (e: Exception) {
                android.util.Log.w("VideoExporter", "muxer.stop failed: ${e.message}")
            }
            try {
                muxer?.release()
            } catch (_: Exception) {}
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        isMuxerStarted: Boolean,
        drainAll: Boolean = false,
        onMuxerStart: (trackIndex: Int) -> Unit
    ) {
        var started = isMuxerStarted
        var emptyTries = 0
        val maxEmptyTries = if (drainAll) 80 else 1 // drainAll 모드에서 최대 800ms 대기 후 타임아웃 종료

        while (emptyTries < maxEmptyTries) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!drainAll) break
                emptyTries++
                Thread.sleep(10)
                continue
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!started) {
                    val newFormat = encoder.outputFormat
                    val trackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    started = true
                    onMuxerStart(trackIndex)
                }
            } else if (outputIndex >= 0) {
                emptyTries = 0 // 새 버퍼를 받았으므로 카운트 리셋
                val outputBuffer = encoder.getOutputBuffer(outputIndex)
                if (outputBuffer != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && started && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(0, outputBuffer, bufferInfo)
                }
                encoder.releaseOutputBuffer(outputIndex, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    android.util.Log.i("VideoExporter", "EOS buffer encountered in drainEncoder")
                    break
                }
            }
        }
    }

    override fun saveVideoToGallery(context: Context, videoFile: File): Uri? {
        if (!videoFile.exists() || videoFile.length() == 0L) return null

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/DdayFlip")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = resolver.insert(collectionUri, values) ?: return null

        return try {
            resolver.openOutputStream(itemUri)?.use { outStream ->
                FileInputStream(videoFile).use { inStream ->
                    inStream.copyTo(outStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            }
            itemUri
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            null
        }
    }
}
