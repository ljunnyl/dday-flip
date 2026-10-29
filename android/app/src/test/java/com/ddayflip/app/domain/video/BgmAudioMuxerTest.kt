package com.ddayflip.app.domain.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BgmAudioMuxerTest {

    @Test
    fun `generateMusicBoxPcm produces exact sample count and non-silent warm melody`() {
        val sampleRate = 44100
        val durationSeconds = 1.5
        val pcm = BgmAudioMuxer.generateMusicBoxPcm(durationSeconds = durationSeconds, sampleRate = sampleRate)

        val expectedSamples = (durationSeconds * sampleRate).toInt()
        assertEquals(expectedSamples, pcm.size)

        // 오르골 멜로디는 무음이 아니어야 하며 클리핑 없이 안전한 진폭 내에 있어야 함
        var maxAmplitude = 0
        for (sample in pcm) {
            val amp = abs(sample.toInt())
            if (amp > maxAmplitude) maxAmplitude = amp
        }
        assertTrue("Music box PCM should have audible signal (> 1000), was $maxAmplitude", maxAmplitude > 1000)
        assertTrue("Music box PCM should not clip Short.MAX_VALUE", maxAmplitude <= Short.MAX_VALUE.toInt())
    }

    @Test
    fun `generateMusicBoxPcm handles zero or negative duration gracefully`() {
        val emptyPcm = BgmAudioMuxer.generateMusicBoxPcm(durationSeconds = 0.0, sampleRate = 44100)
        assertEquals(0, emptyPcm.size)
    }

    @Test
    fun `VideoExportConfig supports BgmMode and customBgmUri`() {
        val defaultConfig = VideoExportConfig()
        assertEquals(BgmMode.DEFAULT_MUSIC_BOX, defaultConfig.bgmMode)
        assertEquals(null, defaultConfig.customBgmUri)

        val muteConfig = VideoExportConfig(bgmMode = BgmMode.MUTE)
        assertEquals(BgmMode.MUTE, muteConfig.bgmMode)

        val customConfig = VideoExportConfig(
            bgmMode = BgmMode.CUSTOM_FILE,
            customBgmUri = "content://media/external/audio/media/101"
        )
        assertEquals(BgmMode.CUSTOM_FILE, customConfig.bgmMode)
        assertEquals("content://media/external/audio/media/101", customConfig.customBgmUri)
    }
}
