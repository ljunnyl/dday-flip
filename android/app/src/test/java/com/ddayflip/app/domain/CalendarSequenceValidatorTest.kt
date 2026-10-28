package com.ddayflip.app.domain

import com.ddayflip.app.core.domain.CalendarPhotoEntry
import com.ddayflip.app.core.domain.CalendarRoi
import com.ddayflip.app.core.domain.CalendarSequenceValidator
import com.ddayflip.app.core.domain.IssueType
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalendarSequenceValidatorTest {

    private val validator = CalendarSequenceValidator()

    @Test
    fun `test CalendarRoi withMargin expands bounding box correctly within bounds`() {
        val roi = CalendarRoi(x = 0.2f, y = 0.2f, width = 0.4f, height = 0.4f)
        val expanded = roi.withMargin(0.15f)

        // marginX = 0.4 * 0.15 = 0.06
        // newX = 0.2 - 0.06 = 0.14
        // newWidth = 0.4 + 0.12 = 0.52
        assertEquals(0.14f, expanded.x, 0.001f)
        assertEquals(0.14f, expanded.y, 0.001f)
        assertEquals(0.52f, expanded.width, 0.001f)
        assertEquals(0.52f, expanded.height, 0.001f)

        // Boundary clamp test
        val edgeRoi = CalendarRoi(x = 0.05f, y = 0.05f, width = 0.95f, height = 0.95f)
        val clamped = edgeRoi.withMargin(0.15f)
        assertEquals(0.0f, clamped.x, 0.001f)
        assertEquals(0.0f, clamped.y, 0.001f)
        assertEquals(1.0f, clamped.width, 0.001f)
        assertEquals(1.0f, clamped.height, 0.001f)
    }

    @Test
    fun `test noise OCR 2000+ year number is sanitized to exifDday`() {
        val entries = listOf(
            CalendarPhotoEntry(
                id = "p1",
                effectiveDate = LocalDate.of(2024, 6, 1),
                exifDday = 150,
                ocrDday = 2024, // Year number noise!
                resolvedDday = 2024
            ),
            CalendarPhotoEntry(
                id = "p2",
                effectiveDate = LocalDate.of(2024, 6, 2),
                exifDday = 151,
                ocrDday = 151,
                resolvedDday = 151
            )
        )

        val result = validator.validateSequence(entries, todayMaxDday = 155)

        // Entry 1 ocrDday should be dropped, resolvedDday sanitized to exifDday (150)
        val sanitizedFirst = result.validatedEntries.first { it.id == "p1" }
        assertNull(sanitizedFirst.ocrDday)
        assertEquals(150, sanitizedFirst.resolvedDday)

        // Since resolved days are 150 and 151, there should be NO missing days explosion
        assertTrue(result.missingDays.isEmpty())
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `test todayMaxDday caps missing days preventing future missing days`() {
        val entries = listOf(
            CalendarPhotoEntry(
                id = "p1",
                effectiveDate = LocalDate.of(2024, 6, 1),
                exifDday = 150,
                resolvedDday = 150
            ),
            // Suppose resolvedDday jumps into future beyond today (e.g. 165)
            CalendarPhotoEntry(
                id = "p2",
                effectiveDate = LocalDate.of(2024, 6, 16),
                exifDday = 165,
                resolvedDday = 165
            )
        )

        // Today is D+155: missing days should NOT exceed 155
        val result = validator.validateSequence(entries, todayMaxDday = 155)

        assertEquals(listOf(151, 152, 153, 154, 155), result.missingDays)
        assertTrue(result.missingDays.all { it <= 155 })

        val missingIssue = result.issues.firstOrNull { it.type == IssueType.MISSING_DAYS }
        assertNotNull(missingIssue)
        assertEquals(listOf(151, 152, 153, 154, 155), missingIssue!!.missingDays)
    }

    @Test
    fun `test large jump defense limits missing days to 9 when todayMaxDday is null`() {
        val entries = listOf(
            CalendarPhotoEntry(
                id = "p1",
                effectiveDate = LocalDate.of(2024, 6, 1),
                exifDday = 10,
                resolvedDday = 10
            ),
            // Large jump: 10 to 50 (difference 40 >= 10)
            CalendarPhotoEntry(
                id = "p2",
                effectiveDate = LocalDate.of(2024, 7, 10),
                exifDday = 50,
                resolvedDday = 50
            )
        )

        val result = validator.validateSequence(entries, todayMaxDday = null)

        // Without todayMaxDday, large jump is capped at 9 missing days to avoid flooding
        assertEquals(9, result.missingDays.size)
        assertEquals((11..19).toList(), result.missingDays)
    }

    @Test
    fun `test filterNoiseOcr default tolerance is 3`() {
        // expected: 10. Candidates: 14 (delta 4), 13 (delta 3)
        val candidates = listOf("14", "D+13")
        val filtered = validator.filterNoiseOcr(candidates, expectedDday = 10)

        // 13 is within tolerance 3, 14 is not
        assertNotNull(filtered)
        assertEquals(13, filtered)
    }

    @Test
    fun `test selectBestPhotoPerDay picks highest sharpness per day and deduplicates`() {
        val entries = listOf(
            CalendarPhotoEntry(
                id = "p1_blurry",
                effectiveDate = LocalDate.of(2024, 6, 1),
                exifDday = 10,
                resolvedDday = 10,
                sharpnessScore = 50.0
            ),
            CalendarPhotoEntry(
                id = "p1_sharp",
                effectiveDate = LocalDate.of(2024, 6, 1),
                exifDday = 10,
                resolvedDday = 10,
                sharpnessScore = 185.0
            ),
            CalendarPhotoEntry(
                id = "p1_mid",
                effectiveDate = LocalDate.of(2024, 6, 1),
                exifDday = 10,
                resolvedDday = 10,
                sharpnessScore = 90.0
            ),
            CalendarPhotoEntry(
                id = "p2_single",
                effectiveDate = LocalDate.of(2024, 6, 2),
                exifDday = 11,
                resolvedDday = 11,
                sharpnessScore = 110.0
            )
        )

        val selected = validator.selectBestPhotoPerDay(entries)

        assertEquals(2, selected.size)
        assertEquals("p1_sharp", selected[0].id)
        assertEquals(10, selected[0].resolvedDday)
        assertEquals(185.0, selected[0].sharpnessScore, 0.01)

        assertEquals("p2_single", selected[1].id)
        assertEquals(11, selected[1].resolvedDday)
    }
}
