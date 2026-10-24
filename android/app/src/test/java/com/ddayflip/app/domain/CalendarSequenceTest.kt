package com.ddayflip.app.domain

import com.ddayflip.app.core.domain.CalendarPhotoEntry
import com.ddayflip.app.core.domain.CalendarSequenceValidator
import com.ddayflip.app.core.domain.IssueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarSequenceTest {

    private val validator = CalendarSequenceValidator()

    @Test
    fun `test unflipped calendar detection when consecutive days have identical dday`() {
        val entries = listOf(
            CalendarPhotoEntry(
                id = "p1",
                effectiveDate = LocalDate.of(2024, 6, 1),
                exifDday = 10,
                ocrDday = 10
            ),
            CalendarPhotoEntry(
                id = "p2",
                effectiveDate = LocalDate.of(2024, 6, 2), // Next day, but calendar was NOT flipped!
                exifDday = 11,
                ocrDday = 10
            ),
            CalendarPhotoEntry(
                id = "p3",
                effectiveDate = LocalDate.of(2024, 6, 3),
                exifDday = 12,
                ocrDday = 12
            )
        )

        val result = validator.validateSequence(entries)

        val unflippedIssues = result.issues.filter { it.type == IssueType.UNFLIPPED_CALENDAR }
        assertEquals(1, unflippedIssues.size)
        assertEquals(10, unflippedIssues[0].targetDday)
        assertTrue(unflippedIssues[0].relatedEntryIds.contains("p2"))
    }

    @Test
    fun `test missing days detection in sequence`() {
        val entries = listOf(
            CalendarPhotoEntry(id = "p1", effectiveDate = LocalDate.of(2024, 6, 1), exifDday = 10, resolvedDday = 10),
            CalendarPhotoEntry(id = "p2", effectiveDate = LocalDate.of(2024, 6, 2), exifDday = 11, resolvedDday = 11),
            // Missing D+12, D+13!
            CalendarPhotoEntry(id = "p3", effectiveDate = LocalDate.of(2024, 6, 5), exifDday = 14, resolvedDday = 14)
        )

        val result = validator.validateSequence(entries)

        val missingIssues = result.issues.filter { it.type == IssueType.MISSING_DAYS }
        assertEquals(1, missingIssues.size)
        assertEquals(listOf(12, 13), missingIssues[0].missingDays)
        assertEquals(listOf(12, 13), result.missingDays)
    }

    @Test
    fun `test noise text filtering excludes year numbers and out-of-range text`() {
        // Expected D-day from EXIF is 15. OCR detects text lines in photo
        val rawOcrTexts = listOf("2024", "10", "D+16", "9999", "LOVE")
        
        // Expected around 15, tolerance 3 -> valid range [12..18]
        val filteredDday = validator.filterNoiseOcr(
            rawCandidates = rawOcrTexts,
            expectedDday = 15,
            tolerance = 3
        )

        assertNotNull(filteredDday)
        assertEquals(16, filteredDday) // "D+16" parsed to 16, within 12..18
    }

    @Test
    fun `test noise text filtering returns null when no OCR text is in plausible range`() {
        val rawOcrTexts = listOf("2024", "2024-05-27", "850", "BABY", "HELLO")
        val expectedDday = 30

        val filteredDday = validator.filterNoiseOcr(
            rawCandidates = rawOcrTexts,
            expectedDday = expectedDday,
            tolerance = 5
        )

        assertNull(filteredDday)
    }

    @Test
    fun `test clean consecutive sequence has no issues`() {
        val entries = (1..5).map { day ->
            CalendarPhotoEntry(
                id = "p$day",
                effectiveDate = LocalDate.of(2024, 6, day),
                exifDday = day,
                ocrDday = day,
                resolvedDday = day
            )
        }

        val result = validator.validateSequence(entries)
        assertTrue(result.issues.isEmpty())
        assertTrue(result.missingDays.isEmpty())
    }
}
