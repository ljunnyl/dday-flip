package com.ddayflip.app.domain

import com.ddayflip.app.core.domain.CalendarPhotoEntry
import com.ddayflip.app.core.domain.CalendarSequenceValidator
import com.ddayflip.app.core.domain.DdayCalculator
import com.ddayflip.app.core.domain.DdayStartRule
import com.ddayflip.app.core.domain.ScanAccuracyMode
import com.ddayflip.app.data.MediaStoreQueryHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FeedbackRegressionTest {

    @Test
    fun `MediaStoreQueryHelper isScreenshot excludes screenshot folders and filenames only`() {
        assertTrue(MediaStoreQueryHelper.isScreenshot("Screenshots", "IMG_001.png"))
        assertTrue(MediaStoreQueryHelper.isScreenshot("Camera", "Screenshot_20260527.jpg"))
        assertTrue(MediaStoreQueryHelper.isScreenshot("스크린샷", "photo.jpg"))
        assertTrue(MediaStoreQueryHelper.isScreenshot("DCIM", "화면_스크린샷_1.png"))

        // 일반 폴더(Camera, KakaoTalk, Download)는 절대 제외하지 않음
        assertFalse(MediaStoreQueryHelper.isScreenshot("Camera", "20260527_123456.jpg"))
        assertFalse(MediaStoreQueryHelper.isScreenshot("KakaoTalk", "KakaoTalk_20260527.jpg"))
        assertFalse(MediaStoreQueryHelper.isScreenshot("Download", "saved_baby.jpg"))
        assertFalse(MediaStoreQueryHelper.isScreenshot(null, null))
    }

    @Test
    fun `LocalDate atTime 12_00 prevents 2026-05-27 photo from shifting to 2026-05-26`() {
        val birthday = LocalDate.of(2026, 5, 27)
        val calculator = DdayCalculator(birthday = birthday, rule = DdayStartRule.ONE_BASED)

        // 정오(12:00) 기준 호출 시 5월 27일 사진이 5월 26일(D+0)로 밀리지 않고 5월 27일(D+1) 유지
        val result = calculator.calculate(birthday.atTime(12, 0))
        assertEquals(LocalDate.of(2026, 5, 27), result.effectiveDate)
        assertEquals(1, result.dday)
        assertFalse(result.isNightShifted)
    }

    @Test
    fun `ScanAccuracyMode includes BABY_FACE_ONLY and validator preserves baby face entries`() {
        val validator = CalendarSequenceValidator()
        val entries = listOf(
            CalendarPhotoEntry(
                id = "face_1",
                effectiveDate = LocalDate.of(2026, 5, 27),
                exifDday = 1,
                ocrDday = null,
                resolvedDday = 1,
                sharpnessScore = 92.0,
                isCalendarConfirmed = false
            )
        )

        val filtered = validator.filterByAccuracyMode(entries, ScanAccuracyMode.BABY_FACE_ONLY)
        assertEquals(1, filtered.size)
        assertEquals("face_1", filtered[0].id)
    }
}
