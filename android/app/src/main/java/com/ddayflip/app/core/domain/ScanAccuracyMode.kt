package com.ddayflip.app.core.domain

/**
 * AI 사진 탐색 정확도 모드
 */
enum class ScanAccuracyMode(val title: String, val description: String) {
    /**
     * 엄격 모드 (정확도 우선)
     * - D-day 달력 숫자가 확실히 인식된 진짜 달력 사진만 채택
     * - 일상 사진(발, 음식, 풍경, 장난감 등) 원천 차단
     */
    STRICT("엄격 모드 (정확도 우선)", "D-day 달력 숫자가 확실한 사진만 모아요"),

    /**
     * 관대 모드 (풍부함 우선)
     * - 달력이 없는 날은 당일 촬영된 가장 선명한 아기 사진으로 영상 공백을 보충
     */
    RELAXED("관대 모드 (풍부함 우선)", "달력이 없는 날은 당일 예쁜 아기 사진으로 채워요")
}
