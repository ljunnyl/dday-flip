package com.ddayflip.app.service

import com.ddayflip.app.core.domain.SequenceValidationResult

/**
 * 포그라운드 사진 분석 서비스의 상태 모델
 */
sealed interface ScanServiceState {
    object Idle : ScanServiceState

    data class Scanning(
        val processed: Int,
        val total: Int,
        val detectedCount: Int,
        val currentDday: Int?,
        val currentStatusText: String
    ) : ScanServiceState {
        val percentage: Float
            get() = if (total > 0) processed.toFloat() / total.toFloat() else 0f
    }

    data class Completed(
        val result: SequenceValidationResult
    ) : ScanServiceState

    data class Error(
        val message: String
    ) : ScanServiceState
}
