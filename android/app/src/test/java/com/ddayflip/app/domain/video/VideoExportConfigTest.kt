package com.ddayflip.app.domain.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoExportConfigTest {

    @Test
    fun `default config values are 1080x1920 30fps and 0_6s per photo`() {
        val config = VideoExportConfig()

        assertEquals(1080, config.width)
        assertEquals(1920, config.height)
        assertEquals(30, config.frameRate)
        assertEquals(0.6f, config.photoDurationSeconds, 0.001f)
        assertEquals(18, config.framesPerPhoto)
    }

    @Test
    fun `calculateTotalFrames returns exact frame count`() {
        val config = VideoExportConfig(frameRate = 30, photoDurationSeconds = 0.6f)

        assertEquals(0, config.calculateTotalFrames(0))
        assertEquals(18, config.calculateTotalFrames(1))
        assertEquals(180, config.calculateTotalFrames(10))
        assertEquals(1800, config.calculateTotalFrames(100))
    }

    @Test
    fun `calculateDurationMs returns correct milliseconds`() {
        val config = VideoExportConfig(photoDurationSeconds = 0.6f)

        assertEquals(0L, config.calculateDurationMs(0))
        assertEquals(600L, config.calculateDurationMs(1))
        assertEquals(6000L, config.calculateDurationMs(10))
        assertEquals(60000L, config.calculateDurationMs(100))
    }

    @Test
    fun `calculateBadgeRect aligns badge horizontally centered at bottom`() {
        val config = VideoExportConfig(width = 1080, height = 1920)
        val textWidth = 120f
        val textHeight = 36f

        val rect = config.calculateBadgeRect(textWidth = textWidth, textHeight = textHeight)

        // 1. Text is horizontally centered
        assertEquals(540f, rect.textX, 0.1f)

        // 2. Badge is horizontally centered: (left + right) / 2 == 540
        assertEquals(540f, (rect.left + rect.right) / 2f, 0.1f)

        // 3. Horizontal padding is 0.04 * 1080 = 43.2f on each side -> badgeWidth = 120 + 86.4 = 206.4f
        val expectedBadgeWidth = textWidth + (1080f * config.badgePaddingHorizontalRatio * 2f)
        assertEquals(expectedBadgeWidth, rect.right - rect.left, 0.1f)

        // 4. Bottom margin is 0.08 * 1920 = 153.6 -> bottom is 1920 - 153.6 = 1766.4f
        val expectedBottom = 1920f * (1f - config.badgeBottomMarginRatio)
        assertEquals(expectedBottom, rect.bottom, 0.1f)

        // 5. Badge height is 0.045 * 1920 = 86.4f
        val expectedHeight = 1920f * config.badgeHeightRatio
        assertEquals(expectedHeight, rect.bottom - rect.top, 0.1f)

        // 6. Text vertical position is within top and bottom
        assertTrue(rect.textY > rect.top)
        assertTrue(rect.textY < rect.bottom)
    }

    @Test
    fun `custom config with 720x1280 24fps 0_5s exposure calculates accurately`() {
        val config = VideoExportConfig(
            width = 720,
            height = 1280,
            frameRate = 24,
            photoDurationSeconds = 0.5f
        )

        assertEquals(12, config.framesPerPhoto)
        assertEquals(60, config.calculateTotalFrames(5))
        assertEquals(2500L, config.calculateDurationMs(5))

        val rect = config.calculateBadgeRect(textWidth = 100f, textHeight = 24f)
        assertEquals(360f, rect.textX, 0.1f)
        assertEquals(1280f * (1f - 0.08f), rect.bottom, 0.1f)
    }

    @Test
    fun `calculateFitCenter fits square image into 9_16 canvas centered vertically`() {
        val config = VideoExportConfig(width = 1080, height = 1920)
        // Square image 1000x1000 -> scale = 1080 / 1000 = 1.08 -> width=1080, height=1080
        val fit = config.calculateFitCenter(srcWidth = 1000, srcHeight = 1000)

        assertEquals(0f, fit.left, 0.1f)
        assertEquals(1080f, fit.width, 0.1f)
        assertEquals(1080f, fit.height, 0.1f)
        assertEquals((1920f - 1080f) / 2f, fit.top, 0.1f) // 420f
    }

    @Test
    fun `calculateFitCenter fits tall image into 9_16 canvas centered horizontally`() {
        val config = VideoExportConfig(width = 1080, height = 1920)
        // Tall image 1000x2000 -> scale = 1920 / 2000 = 0.96 -> width=960, height=1920
        val fit = config.calculateFitCenter(srcWidth = 1000, srcHeight = 2000)

        assertEquals((1080f - 960f) / 2f, fit.left, 0.1f) // 60f
        assertEquals(0f, fit.top, 0.1f)
        assertEquals(960f, fit.width, 0.1f)
        assertEquals(1920f, fit.height, 0.1f)
    }

    @Test
    fun `calculateSubtitleRect positions capsule banner centered directly above badgeRect`() {
        val config = VideoExportConfig(width = 1080, height = 1920)
        val badgeRect = config.calculateBadgeRect(textWidth = 120f, textHeight = 36f)
        val subTextWidth = 240f
        val subTextHeight = 32f

        val subtitleRect = config.calculateSubtitleRect(
            textWidth = subTextWidth,
            textHeight = subTextHeight,
            badgeRect = badgeRect
        )

        // Centered horizontally at same centerX as badgeRect (540f)
        val badgeCenterX = (badgeRect.left + badgeRect.right) / 2f
        assertEquals(badgeCenterX, (subtitleRect.left + subtitleRect.right) / 2f, 0.1f)
        assertEquals(badgeCenterX, subtitleRect.textX, 0.1f)

        // Positioned above badgeRect.top with gap = 16f
        assertEquals(badgeRect.top - 16f, subtitleRect.bottom, 0.1f)

        // Width includes horizontalPadding = 32f on each side (240 + 64 = 304)
        assertEquals(subTextWidth + 64f, subtitleRect.right - subtitleRect.left, 0.1f)

        // Height includes verticalPadding = 16f on top and bottom (32 + 32 = 64)
        assertEquals(subTextHeight + 32f, subtitleRect.bottom - subtitleRect.top, 0.1f)
    }
}
