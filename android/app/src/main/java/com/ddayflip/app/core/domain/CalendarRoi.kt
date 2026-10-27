package com.ddayflip.app.core.domain

/**
 * D-day 달력의 정규화된 관심 영역(ROI) (0.0f ~ 1.0f 비율).
 */
data class CalendarRoi(
    val x: Float = 0.0f,
    val y: Float = 0.0f,
    val width: Float = 1.0f,
    val height: Float = 1.0f
) {
    fun withMargin(marginFraction: Float = 0.15f): CalendarRoi {
        val marginX = width * marginFraction
        val marginY = height * marginFraction
        val newX = (x - marginX).coerceIn(0.0f, 1.0f)
        val newY = (y - marginY).coerceIn(0.0f, 1.0f)
        val newWidth = (width + marginX * 2).coerceAtMost(1.0f - newX)
        val newHeight = (height + marginY * 2).coerceAtMost(1.0f - newY)
        return CalendarRoi(newX, newY, newWidth, newHeight)
    }
}
