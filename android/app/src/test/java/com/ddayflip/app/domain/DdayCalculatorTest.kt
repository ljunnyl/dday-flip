package com.ddayflip.app.domain

import com.ddayflip.app.core.domain.DdayCalculator
import com.ddayflip.app.core.domain.DdayStartRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DdayCalculatorTest {

    private val birthday: LocalDate = LocalDate.of(2024, 5, 27)

    @Test
    fun `test zero-based rule on birthday returns D+0`() {
        val calculator = DdayCalculator(birthday = birthday, rule = DdayStartRule.ZERO_BASED)
        val photoTime = LocalDateTime.of(2024, 5, 27, 14, 30)

        val result = calculator.calculate(photoTime)

        assertEquals(0, result.dday)
        assertEquals(LocalDate.of(2024, 5, 27), result.effectiveDate)
        assertFalse(result.isNightShifted)
    }

    @Test
    fun `test one-based rule on birthday returns D+1`() {
        val calculator = DdayCalculator(birthday = birthday, rule = DdayStartRule.ONE_BASED)
        val photoTime = LocalDateTime.of(2024, 5, 27, 10, 15)

        val result = calculator.calculate(photoTime)

        assertEquals(1, result.dday)
        assertEquals(LocalDate.of(2024, 5, 27), result.effectiveDate)
        assertFalse(result.isNightShifted)
    }

    @Test
    fun `test calculation for 100 days after birthday`() {
        val calculatorOneBased = DdayCalculator(birthday = birthday, rule = DdayStartRule.ONE_BASED)
        val calculatorZeroBased = DdayCalculator(birthday = birthday, rule = DdayStartRule.ZERO_BASED)
        val photoTime = LocalDateTime.of(2024, 9, 4, 18, 0) // 100 days after 2024-05-27

        val resultOneBased = calculatorOneBased.calculate(photoTime)
        val resultZeroBased = calculatorZeroBased.calculate(photoTime)

        assertEquals(101, resultOneBased.dday)
        assertEquals(100, resultZeroBased.dday)
        assertEquals(LocalDate.of(2024, 9, 4), resultOneBased.effectiveDate)
    }

    @Test
    fun `test late night photo before 04_00 is attributed to previous calendar day`() {
        val calculator = DdayCalculator(birthday = birthday, rule = DdayStartRule.ONE_BASED)
        
        // 2024-05-28 01:30 should count as 2024-05-27 (D+1)
        val lateNightPhoto1 = LocalDateTime.of(2024, 5, 28, 1, 30)
        val result1 = calculator.calculate(lateNightPhoto1)

        assertTrue(result1.isNightShifted)
        assertEquals(LocalDate.of(2024, 5, 27), result1.effectiveDate)
        assertEquals(1, result1.dday)

        // 2024-05-28 03:59:59 should also count as 2024-05-27 (D+1)
        val lateNightPhoto2 = LocalDateTime.of(2024, 5, 28, 3, 59, 59)
        val result2 = calculator.calculate(lateNightPhoto2)

        assertTrue(result2.isNightShifted)
        assertEquals(LocalDate.of(2024, 5, 27), result2.effectiveDate)
        assertEquals(1, result2.dday)
    }

    @Test
    fun `test morning photo at or after 04_00 is attributed to same calendar day`() {
        val calculator = DdayCalculator(birthday = birthday, rule = DdayStartRule.ONE_BASED)
        
        // 2024-05-28 04:00 should count as 2024-05-28 (D+2)
        val morningPhoto = LocalDateTime.of(2024, 5, 28, 4, 0, 0)
        val result = calculator.calculate(morningPhoto)

        assertFalse(result.isNightShifted)
        assertEquals(LocalDate.of(2024, 5, 28), result.effectiveDate)
        assertEquals(2, result.dday)
    }

    @Test
    fun `test custom night cutoff hour`() {
        val calculator = DdayCalculator(birthday = birthday, rule = DdayStartRule.ONE_BASED, nightCutoffHour = 5)
        
        val photoAt430 = LocalDateTime.of(2024, 5, 28, 4, 30)
        val result = calculator.calculate(photoAt430)

        assertTrue(result.isNightShifted)
        assertEquals(LocalDate.of(2024, 5, 27), result.effectiveDate)
        assertEquals(1, result.dday)
    }
}
