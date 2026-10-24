package com.ddayflip.app.core.domain

import java.time.LocalDate
import kotlin.math.abs

/**
 * Represents a photo entry associated with a calendar day.
 */
data class CalendarPhotoEntry(
    val id: String,
    val effectiveDate: LocalDate,
    val exifDday: Int,
    val ocrDday: Int? = null,
    val resolvedDday: Int = ocrDday ?: exifDday,
    val sharpnessScore: Double = 0.0,
    val photoUri: String = ""
)

enum class IssueType {
    UNFLIPPED_CALENDAR, // 달력 미넘김 감지 (날짜가 바뀌었으나 동일 D-day 표시)
    MISSING_DAYS,       // 결측일 감지 (건너뛴 일자 존재)
    DISCREPANCY         // EXIF 계산일자와 OCR 인식일자 불일치
}

data class SequenceIssue(
    val type: IssueType,
    val targetDday: Int,
    val description: String,
    val relatedEntryIds: List<String> = emptyList(),
    val missingDays: List<Int> = emptyList()
)

data class SequenceValidationResult(
    val validatedEntries: List<CalendarPhotoEntry>,
    val issues: List<SequenceIssue>,
    val missingDays: List<Int>
)

class CalendarSequenceValidator {

    /**
     * Filters OCR text tokens to extract valid D-day numbers within plausible range of expected EXIF D-day.
     * Excludes noise such as year numbers (e.g. 2024), phone numbers, or out-of-range digits.
     *
     * @param rawCandidates List of OCR detected text strings in the calendar ROI
     * @param expectedDday The expected D-day calculated from EXIF date
     * @param tolerance Allowed delta around expected D-day (default: 5)
     * @return Plausible D-day integer, or null if no candidate falls within range
     */
    fun filterNoiseOcr(
        rawCandidates: List<String>,
        expectedDday: Int,
        tolerance: Int = 5
    ): Int? {
        val numberPattern = Regex("""(?i)(?:d\s*[\+\-]?\s*)?(\d{1,4})""")
        val candidateNumbers = mutableListOf<Int>()

        for (candidate in rawCandidates) {
            val trimmed = candidate.trim()
            val match = numberPattern.find(trimmed)
            if (match != null) {
                val numStr = match.groupValues[1]
                val num = numStr.toIntOrNull()
                if (num != null) {
                    // Check if within tolerance
                    if (abs(num - expectedDday) <= tolerance) {
                        candidateNumbers.add(num)
                    }
                }
            }
        }

        // Return candidate closest to expectedDday
        return candidateNumbers.minByOrNull { abs(it - expectedDday) }
    }

    /**
     * Validates chronological sequence of daily calendar photo entries:
     * 1. Detects unflipped calendars (consecutive entries on different calendar dates with identical D-day).
     * 2. Detects missing days (gaps between resolved D-days).
     */
    fun validateSequence(entries: List<CalendarPhotoEntry>): SequenceValidationResult {
        if (entries.isEmpty()) {
            return SequenceValidationResult(emptyList(), emptyList(), emptyList())
        }

        // Sort chronologically by effective date and resolved D-day
        val sortedEntries = entries.sortedWith(
            compareBy<CalendarPhotoEntry> { it.effectiveDate }
                .thenBy { it.resolvedDday }
        )

        val issues = mutableListOf<SequenceIssue>()

        // 1. Unflipped calendar detection
        for (i in 0 until sortedEntries.size - 1) {
            val current = sortedEntries[i]
            val next = sortedEntries[i + 1]

            // If effective dates are different, but calendar D-day is the same:
            val currentCalendarDay = current.ocrDday ?: current.resolvedDday
            val nextCalendarDay = next.ocrDday ?: next.resolvedDday

            if (current.effectiveDate != next.effectiveDate && currentCalendarDay == nextCalendarDay) {
                issues.add(
                    SequenceIssue(
                        type = IssueType.UNFLIPPED_CALENDAR,
                        targetDday = nextCalendarDay,
                        description = "미넘김 의심: ${current.effectiveDate}와 ${next.effectiveDate}의 달력 D-day가 모두 D+$nextCalendarDay 입니다.",
                        relatedEntryIds = listOf(current.id, next.id)
                    )
                )
            }
        }

        // 2. Missing days detection
        val uniqueDays = sortedEntries.map { it.resolvedDday }.distinct().sorted()
        val allMissingDays = mutableListOf<Int>()

        if (uniqueDays.size >= 2) {
            for (i in 0 until uniqueDays.size - 1) {
                val currentDay = uniqueDays[i]
                val nextDay = uniqueDays[i + 1]

                if (nextDay > currentDay + 1) {
                    val missingRange = ((currentDay + 1) until nextDay).toList()
                    allMissingDays.addAll(missingRange)
                    issues.add(
                        SequenceIssue(
                            type = IssueType.MISSING_DAYS,
                            targetDday = currentDay,
                            description = "결측일 감지: D+$currentDay 에서 D+$nextDay 사이에 ${missingRange.joinToString { "D+$it" }} 누락",
                            missingDays = missingRange
                        )
                    )
                }
            }
        }

        return SequenceValidationResult(
            validatedEntries = sortedEntries,
            issues = issues,
            missingDays = allMissingDays
        )
    }
}
