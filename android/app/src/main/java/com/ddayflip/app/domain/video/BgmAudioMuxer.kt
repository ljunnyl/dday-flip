package com.ddayflip.app.domain.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 온디바이스 오르골 자장가(PCM -> AAC) 합성 및 사용자 지정 음악 파일 오디오 트랙 믹싱 헬퍼.
 * 오디오 처리 중 예외가 발생하더라도 비디오 인코딩 자체가 절대 실패하지 않도록 안전 격리합니다.
 */
object BgmAudioMuxer {

    private const val TAG = "BgmAudioMuxer"
    private const val SAMPLE_RATE = 44100
    private const val BIT_RATE = 64000

    /**
     * 부드러운 사인파 + 벨 엔벨로프 화음으로 이루어진 오르골 자장가(브람스 자장가/트윙클 풍) 16-bit PCM 배열을 생성합니다.
     * 순수 함수로 설계되어 단위 테스트에서 즉시 검증 가능합니다.
     */
    fun generateMusicBoxPcm(durationSeconds: Double, sampleRate: Int = SAMPLE_RATE): ShortArray {
        if (durationSeconds <= 0.0 || sampleRate <= 0) return ShortArray(0)
        val totalSamples = (durationSeconds * sampleRate).toInt().coerceAtLeast(0)
        if (totalSamples == 0) return ShortArray(0)

        val pcm = ShortArray(totalSamples)

        // 포근한 오르골 멜로디 주파수 (C5, E5, G5, E5, F5, D5, G5, C5 등)
        val melodyNotes = doubleArrayOf(
            523.25, 523.25, 659.25, 523.25, 659.25, 783.99, 659.25, 523.25,
            587.33, 698.46, 659.25, 587.33, 523.25, 659.25, 783.99, 523.25
        )
        // 따뜻한 베이스 화음 주파수 (C4, G3, F4, C4)
        val bassNotes = doubleArrayOf(
            261.63, 196.00, 349.23, 261.63
        )

        val noteDurationSec = 0.55
        val samplesPerNote = (noteDurationSec * sampleRate).toInt().coerceAtLeast(1)

        // 영상 끝부분 0.8초 페이드아웃
        val fadeOutSamples = (0.8 * sampleRate).toInt().coerceAtLeast(1)

        for (i in 0 until totalSamples) {
            val noteIndex = (i / samplesPerNote) % melodyNotes.size
            val bassIndex = (i / (samplesPerNote * 4)) % bassNotes.size
            val sampleInNote = i % samplesPerNote
            val tNote = sampleInNote.toDouble() / sampleRate.toDouble()
            val tGlobal = i.toDouble() / sampleRate.toDouble()

            val freq = melodyNotes[noteIndex]
            val bassFreq = bassNotes[bassIndex]

            // 오르골 특유의 어택 후 지수 감쇠(Bell envelope)
            val attack = (sampleInNote.toDouble() / (0.015 * sampleRate)).coerceAtMost(1.0)
            val decay = exp(-3.2 * tNote)
            val envelope = attack * decay

            // 기본음 + 2배음(은은한 배음) + 베이스 화음
            val fundamental = sin(2.0 * PI * freq * tGlobal)
            val overtone = 0.25 * sin(2.0 * PI * (freq * 2.0) * tGlobal) * exp(-6.0 * tNote)
            val bass = 0.30 * sin(2.0 * PI * bassFreq * tGlobal) * exp(-1.5 * tNote)

            var fade = 1.0
            val remaining = totalSamples - i
            if (remaining < fadeOutSamples) {
                fade = remaining.toDouble() / fadeOutSamples.toDouble()
            }

            val rawVal = (fundamental + overtone + bass) * envelope * fade * 9500.0
            pcm[i] = rawVal.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return pcm
    }

    /**
     * 인코딩된 AAC 패킷 묶음
     */
    data class EncodedAudioData(
        val format: MediaFormat,
        val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>
    )

    /**
     * BGM 설정에 따라 MediaMuxer에 추가할 오디오 데이터(트랙 포맷 + 샘플 리스트)를 준비합니다.
     * 실패 시 null을 반환하여 비디오 단독 인코딩이 안전하게 완료되도록 보장합니다.
     */
    fun prepareAudioData(
        context: Context,
        config: VideoExportConfig,
        durationMs: Long
    ): EncodedAudioData? {
        if (config.bgmMode == BgmMode.MUTE || durationMs <= 0L) return null

        val durationUs = durationMs * 1000L

        if (config.bgmMode == BgmMode.CUSTOM_FILE && !config.customBgmUri.isNullOrBlank()) {
            val customData = extractCustomAudioTrack(context, Uri.parse(config.customBgmUri), durationUs)
            if (customData != null) {
                return customData
            }
            Log.w(TAG, "Custom audio extraction failed or unsupported format; falling back to default music box.")
        }

        return encodeMusicBoxToAac(durationMs / 1000.0)
    }

    /**
     * 준비된 오디오 샘플들을 시작된 MediaMuxer의 audioTrackIndex에 기록합니다.
     */
    fun writeAudioSamplesToMuxer(
        muxer: MediaMuxer,
        audioTrackIndex: Int,
        audioData: EncodedAudioData
    ) {
        if (audioTrackIndex < 0) return
        try {
            for ((bytes, info) in audioData.samples) {
                val buffer = ByteBuffer.wrap(bytes)
                muxer.writeSampleData(audioTrackIndex, buffer, info)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing audio samples to muxer: ${e.message}")
        }
    }

    private fun extractCustomAudioTrack(
        context: Context,
        uri: Uri,
        maxDurationUs: Long
    ): EncodedAudioData? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIdx = -1
            var trackFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/mp4a") || mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    audioTrackIdx = i
                    trackFormat = format
                    break
                }
            }

            if (audioTrackIdx < 0 || trackFormat == null) {
                return null
            }

            extractor.selectTrack(audioTrackIdx)
            val samples = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
            val readBuffer = ByteBuffer.allocate(64 * 1024)

            while (true) {
                readBuffer.clear()
                val sampleSize = extractor.readSampleData(readBuffer, 0)
                if (sampleSize < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > maxDurationUs) break

                val bytes = ByteArray(sampleSize)
                readBuffer.position(0)
                readBuffer.get(bytes, 0, sampleSize)

                val info = MediaCodec.BufferInfo().apply {
                    offset = 0
                    size = sampleSize
                    presentationTimeUs = sampleTimeUs.coerceAtLeast(0L)
                    flags = extractor.sampleFlags
                }
                samples.add(bytes to info)
                extractor.advance()
            }

            if (samples.isEmpty()) null else EncodedAudioData(trackFormat, samples)
        } catch (e: Exception) {
            Log.w(TAG, "extractCustomAudioTrack error: ${e.message}")
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {}
        }
    }

    private fun encodeMusicBoxToAac(durationSeconds: Double): EncodedAudioData? {
        var codec: MediaCodec? = null
        return try {
            val pcmShorts = generateMusicBoxPcm(durationSeconds, SAMPLE_RATE)
            if (pcmShorts.isEmpty()) return null

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val samples = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
            var outputFormat: MediaFormat? = null
            val bufferInfo = MediaCodec.BufferInfo()

            var sampleOffset = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        inputBuffer?.clear()

                        if (sampleOffset >= pcmShorts.size) {
                            val ptsUs = (sampleOffset.toLong() * 1_000_000L) / SAMPLE_RATE
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                ptsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else if (inputBuffer != null) {
                            val maxShorts = (inputBuffer.remaining() / 2).coerceAtMost(2048)
                            val count = (pcmShorts.size - sampleOffset).coerceAtMost(maxShorts)
                            val byteArr = ByteArray(count * 2)
                            for (i in 0 until count) {
                                val s = pcmShorts[sampleOffset + i].toInt()
                                byteArr[i * 2] = (s and 0xFF).toByte()
                                byteArr[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                            }
                            inputBuffer.put(byteArr)
                            val ptsUs = (sampleOffset.toLong() * 1_000_000L) / SAMPLE_RATE
                            codec.queueInputBuffer(inputIndex, 0, byteArr.size, ptsUs, 0)
                            sampleOffset += count
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                    }
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val bytes = ByteArray(bufferInfo.size)
                            outBuf.get(bytes)
                            val copyInfo = MediaCodec.BufferInfo().apply {
                                set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                            }
                            samples.add(bytes to copyInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) {
                            break
                        }
                    }
                }
            }

            val finalFormat = outputFormat
            if (finalFormat != null && samples.isNotEmpty()) {
                EncodedAudioData(finalFormat, samples)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "encodeMusicBoxToAac failed: ${e.message}")
            null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {}
            try {
                codec?.release()
            } catch (_: Exception) {}
        }
    }
}
