package com.ddayflip.app.core.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * D-day counting rule:
 * - ZERO_BASED: Birthday is D+0 (days passed = 0)
 * - ONE_BASED: Birthday is D+1 (first day of life = 1)
 */
enum class DdayStartRule {
    ZERO_BASED,
    ONE_BASED
}

/**
 * Result of D-day calculation from EXIF timestamp.
 */
data class DdayCalculationResult(
    val originalDateTime: LocalDateTime,
    val effectiveDate: LocalDate,
    val isNightShifted: Boolean,
    val dday: Int
)

/**
 * Calculates baby D-day based on birthday, shooting time, and late-night cutoff rule.
 * 
 * Late night rule: Photos shot between 00:00:00 and [nightCutoffHour]:00:00 (default 04:00)
 * are attributed to the previous calendar day since baby routines often span across midnight.
 */
class DdayCalculator(
    val birthday: LocalDate,
    val rule: DdayStartRule = DdayStartRule.ONE_BASED,
    val nightCutoffHour: Int = 4
) {
    fun calculate(dateTime: LocalDateTime): DdayCalculationResult {
        val isNight = dateTime.hour < nightCutoffHour
        val effectiveDate = if (isNight) {
            dateTime.toLocalDate().minusDays(1)
        } else {
            dateTime.toLocalDate()
        }

        val daysBetween = ChronoUnit.DAYS.between(birthday, effectiveDate).toInt()
        val dday = when (rule) {
            DdayStartRule.ZERO_BASED -> daysBetween
            DdayStartRule.ONE_BASED -> daysBetween + 1
        }

        return DdayCalculationResult(
            originalDateTime = dateTime,
            effectiveDate = effectiveDate,
            isNightShifted = isNight,
            dday = dday
        )
    }
}
