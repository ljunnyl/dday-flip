package com.ddayflip.app.domain.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DdayTextParserTest {

    private val parser = DdayTextParser()

    @Test
    fun parse_explicitDdayPatterns_returnsCorrectDday() {
        // D+143
        val r1 = parser.parse("D+143")
        assertNotNull(r1)
        assertEquals(143, r1?.dday)
        assertTrue((r1?.confidence ?: 0f) >= 0.9f)
        assertTrue(r1?.isExplicit == true)

        // D+ 150
        val r2 = parser.parse("D+ 150")
        assertNotNull(r2)
        assertEquals(150, r2?.dday)
        assertTrue(r2?.isExplicit == true)

        // D-5
        val r3 = parser.parse("D-5")
        assertNotNull(r3)
        assertEquals(5, r3?.dday)
        assertTrue(r3?.isExplicit == true)

        // 145일
        val r4 = parser.parse("145일")
        assertNotNull(r4)
        assertEquals(145, r4?.dday)
        assertTrue(r4?.isExplicit == true)

        // D 99
        val r5 = parser.parse("D 99")
        assertNotNull(r5)
        assertEquals(99, r5?.dday)
        assertTrue(r5?.isExplicit == true)
    }

    @Test
    fun parse_multilineAndSeparators_returnsCorrectDday() {
        // 줄바꿈 포함: D+\n143
        val r1 = parser.parse("D+\n143")
        assertNotNull(r1)
        assertEquals(143, r1?.dday)

        // 문맥 속 다중 줄: Baby\nD + 100\nToday
        val r2 = parser.parse("Baby\nD + 100\nToday")
        assertNotNull(r2)
        assertEquals(100, r2?.dday)

        // List<String> 블록 파싱 지원
        val r3 = parser.parse(listOf("Baby", "D+", "100", "Days"))
        assertNotNull(r3)
        assertEquals(100, r3?.dday)
    }

    @Test
    fun parse_noiseFilter_ignoresYearTimeAndResolution() {
        // 2024.10.25 14:30\nD+143\n1080x1920
        val text = "2024.10.25 14:30\nD+143\n1080x1920"
        val result = parser.parse(text)
        assertNotNull(result)
        assertEquals(143, result?.dday)
    }

    @Test
    fun parse_standaloneNumbersWithExpectedDay_picksClosestCandidate() {
        // 노이즈(연도, 시간 등)와 여러 숫자 후보가 있는 경우
        // 50, 105, 300 세 숫자가 있을 때 expectedDay가 100이면 105 선택
        val text = "Room 50\nCount: 105\nWeight: 300g"
        val result = parser.parse(text, expectedDay = 100)
        assertNotNull(result)
        assertEquals(105, result?.dday)
        assertFalse(result?.isExplicit == true)
    }

    @Test
    fun parse_explicitPatternTakesPrecedenceOverCloserStandaloneNumber() {
        // D+150 이 있고 단독 숫자 101이 있을 때, expectedDay가 100이더라도 명시적 D+150 우선
        val text = "101\nD+150"
        val result = parser.parse(text, expectedDay = 100)
        assertNotNull(result)
        assertEquals(150, result?.dday)
        assertTrue(result?.isExplicit == true)
    }

    @Test
    fun parse_invalidOrEmptyInput_returnsNull() {
        assertNull(parser.parse(""))
        assertNull(parser.parse("   "))
        assertNull(parser.parse("2024-10-25 14:30"))
        assertNull(parser.parse("Hello World Only Text"))
        assertNull(parser.parse("1920x1080 4032x3024"))
    }

    @Test
    fun parse_spacedKoreanWoodBlocks_returns155() {
        val text = "❤️ 1 5 5 일"
        val r = parser.parse(text)
        assertNotNull(r)
        assertEquals(155, r?.dday)
        assertTrue((r?.confidence ?: 0f) >= 0.95f)
        assertTrue(r?.isExplicit == true)
    }

    @Test
    fun parse_spacedKoreanWoodBlocksList_returns155() {
        val blocks = listOf("❤️", "1", "5", "5", "일")
        val r = parser.parse(blocks)
        assertNotNull(r)
        assertEquals(155, r?.dday)
        assertTrue(r?.isExplicit == true)
    }
}
