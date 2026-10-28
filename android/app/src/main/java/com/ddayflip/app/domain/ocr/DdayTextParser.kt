package com.ddayflip.app.domain.ocr

import kotlin.math.abs

/**
 * OCR 파싱 결과 모델
 *
 * @param dday 파싱된 D-day 일수
 * @param rawText 매칭된 원본 텍스트 조각
 * @param confidence 신뢰도 (0.0f ~ 1.0f)
 */
data class DdayParseResult(
    val dday: Int,
    val rawText: String,
    val confidence: Float
)

/**
 * 온디바이스 OCR 텍스트에서 D-day 숫자를 추출하고 노이즈를 필터링하는 파서.
 * - 우드 블록 띄어쓰기 ("1 5 5 일", "❤️ 1 5 5 일") 지원
 * - "D+150", "D 150", "150일", 단독 숫자 등 유연한 파싱
 */
class DdayTextParser {

    companion object {
        // 날짜 형식 노이즈: 2024.10.25, 2024-10-25, 2024/10/25 등
        private val DATE_REGEX = Regex("""\b(19|20)\d{2}[.\-/]\d{1,2}[.\-/]\d{1,2}\b""")

        // 시간 형식 노이즈: 14:30, 10:30:15 등
        private val TIME_REGEX = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b""")

        // 해상도/크기 노이즈: 1080x1920, 4032 x 3024 등
        private val RESOLUTION_REGEX = Regex("""\b\d{3,4}\s*[xX*×]\s*\d{3,4}\b""")

        // 흔한 해상도 치수 단독 숫자
        private val COMMON_RESOLUTIONS = setOf(720, 1080, 1440, 1920, 2160, 3024, 4032)

        // D+ 패턴 (공백/줄바꿈 허용): D+143, D + 150, D + 1 5 5
        private val DP_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9가-힣])D\s*\+\s*(\d(?:\s*\d){0,3})(?=[^\d가-힣a-zA-Z]|$)""")

        // D- 패턴: D-5, D - 3
        private val DM_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9가-힣])D\s*-\s*(\d(?:\s*\d){0,3})(?=[^\d가-힣a-zA-Z]|$)""")

        // D [공백] [숫자] 패턴: D 99, D 1 5 5
        private val D_SPACE_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9가-힣])D\s+(\d(?:\s*\d){0,3})(?=[^\d가-힣a-zA-Z]|$)""")

        // [숫자]일 패턴 (우드 블록 띄어쓰기 "1 5 5 일", "❤️ 1 5 5 일", "155일" 완벽 지원)
        private val DAY_KOR_REGEX = Regex("""(?i)(?:^|[^\d가-힣a-zA-Z])(\d(?:\s*\d){0,3})\s*일(?=[^\w가-힣]|$)""")

        // 단독 숫자 패턴
        private val STANDALONE_NUMBER_REGEX = Regex("""\b(\d{1,4})\b""")
    }

    private data class Candidate(
        val dday: Int,
        val rawText: String,
        val priority: Int, // 1: 최우선 (D+, D-, [숫자]일), 2: 차선 (D 99), 3: 단독 숫자
        val baseConfidence: Float
    )

    /**
     * OCR 텍스트 블록 목록 파싱
     */
    fun parse(blocks: List<String>, expectedDay: Int? = null): DdayParseResult? {
        if (blocks.isEmpty()) return null
        val joined = blocks.joinToString(" ")
        return parse(joined, expectedDay)
    }

    /**
     * 단일 OCR 텍스트 파싱
     */
    fun parse(text: String, expectedDay: Int? = null): DdayParseResult? {
        if (text.isBlank()) return null

        // 1. 노이즈 마스킹 (날짜, 시간, 해상도 표현을 공백으로 치환)
        val cleanedText = text
            .replace(DATE_REGEX, " ")
            .replace(TIME_REGEX, " ")
            .replace(RESOLUTION_REGEX, " ")

        val candidates = mutableListOf<Candidate>()

        // 2-1. [숫자]일 매칭 ("1 5 5 일", "❤️ 1 5 5 일", "155일") -> 최우선(Priority 1)
        for (match in DAY_KOR_REGEX.findAll(cleanedText)) {
            val rawDigits = match.groupValues[1].replace("\\s+".toRegex(), "")
            val num = rawDigits.toIntOrNull() ?: continue
            candidates.add(Candidate(num, match.value.trim(), priority = 1, baseConfidence = 0.98f))
        }

        // 2-2. D+ 패턴 매칭
        for (match in DP_REGEX.findAll(cleanedText)) {
            val rawDigits = match.groupValues[1].replace("\\s+".toRegex(), "")
            val num = rawDigits.toIntOrNull() ?: continue
            candidates.add(Candidate(num, match.value.trim(), priority = 1, baseConfidence = 0.95f))
        }

        // 2-3. D- 패턴 매칭
        for (match in DM_REGEX.findAll(cleanedText)) {
            val rawDigits = match.groupValues[1].replace("\\s+".toRegex(), "")
            val num = rawDigits.toIntOrNull() ?: continue
            candidates.add(Candidate(num, match.value.trim(), priority = 1, baseConfidence = 0.90f))
        }

        // 2-4. D [공백] [숫자] 매칭
        for (match in D_SPACE_REGEX.findAll(cleanedText)) {
            val rawDigits = match.groupValues[1].replace("\\s+".toRegex(), "")
            val num = rawDigits.toIntOrNull() ?: continue
            candidates.add(Candidate(num, match.value.trim(), priority = 2, baseConfidence = 0.88f))
        }

        // 2-5. 단독 숫자 매칭
        for (match in STANDALONE_NUMBER_REGEX.findAll(cleanedText)) {
            val num = match.groupValues[1].toIntOrNull() ?: continue
            if (num in 1900..2099) continue
            if (num in COMMON_RESOLUTIONS) continue

            candidates.add(Candidate(num, match.value.trim(), priority = 3, baseConfidence = 0.50f))
        }

        if (candidates.isEmpty()) return null

        // 3. 최적 후보 선별
        val bestCandidate = candidates.minWithOrNull(
            compareBy<Candidate> { it.priority }
                .thenBy { candidate ->
                    if (expectedDay != null) {
                        abs(candidate.dday - expectedDay)
                    } else {
                        0
                    }
                }
                .thenByDescending { it.baseConfidence }
        ) ?: return null

        val finalConfidence = calculateConfidence(bestCandidate, expectedDay)

        return DdayParseResult(
            dday = bestCandidate.dday,
            rawText = bestCandidate.rawText,
            confidence = finalConfidence
        )
    }

    private fun calculateConfidence(candidate: Candidate, expectedDay: Int?): Float {
        var conf = candidate.baseConfidence
        if (expectedDay != null) {
            val distance = abs(candidate.dday - expectedDay)
            if (candidate.priority == 3) {
                conf = maxOf(0.1f, conf / (1f + distance * 0.05f))
            } else {
                if (distance <= 5) {
                    conf = minOf(1.0f, conf + 0.04f)
                }
            }
        }
        return (conf * 100).toInt() / 100f
    }
}
