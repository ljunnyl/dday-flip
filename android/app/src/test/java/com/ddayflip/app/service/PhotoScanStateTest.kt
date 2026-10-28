package com.ddayflip.app.service

import com.ddayflip.app.core.domain.CalendarPhotoEntry
import com.ddayflip.app.core.domain.SequenceValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PhotoScanStateTest {

    @Test
    fun idleState_isCorrectType() {
        val state: ScanServiceState = ScanServiceState.Idle
        assertTrue(state is ScanServiceState.Idle)
    }

    @Test
    fun scanningState_percentageCalculation_handlesZeroTotal() {
        val state = ScanServiceState.Scanning(
            processed = 0,
            total = 0,
            detectedCount = 0,
            currentDday = null,
            currentStatusText = "준비 중"
        )
        assertEquals(0f, state.percentage, 0.001f)
    }

    @Test
    fun scanningState_percentageCalculation_calculatesCorrectRatio() {
        val state = ScanServiceState.Scanning(
            processed = 25,
            total = 100,
            detectedCount = 20,
            currentDday = 25,
            currentStatusText = "사진 분석 중 (25/100)"
        )
        assertEquals(0.25f, state.percentage, 0.001f)
        assertEquals(25, state.processed)
        assertEquals(100, state.total)
        assertEquals(20, state.detectedCount)
        assertEquals(25, state.currentDday)
        assertEquals("사진 분석 중 (25/100)", state.currentStatusText)
    }

    @Test
    fun completedState_containsValidationResult() {
        val dummyResult = SequenceValidationResult(
            validatedEntries = listOf(
                CalendarPhotoEntry(
                    id = "1",
                    effectiveDate = LocalDate.of(2026, 1, 1),
                    exifDday = 1,
                    ocrDday = 1,
                    resolvedDday = 1,
                    photoUri = "content://media/1"
                )
            ),
            issues = emptyList(),
            missingDays = emptyList()
        )
        val state = ScanServiceState.Completed(dummyResult)
        assertEquals(1, state.result.validatedEntries.size)
        assertEquals(1, state.result.validatedEntries.first().resolvedDday)
    }

    @Test
    fun errorState_containsErrorMessage() {
        val state = ScanServiceState.Error("분석 중 오류 발생")
        assertEquals("분석 중 오류 발생", state.message)
    }

    @Test
    fun resetState_resetsScanStateToIdle() {
        PhotoScanForegroundService.resetState()
        assertTrue(PhotoScanForegroundService.scanState.value is ScanServiceState.Idle)
    }
}
