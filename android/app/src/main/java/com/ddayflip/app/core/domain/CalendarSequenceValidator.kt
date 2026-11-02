package com.ddayflip.app.core.domain

import java.time.LocalDate
import kotlin.math.abs

/**
 * D-day 달력 사진 엔트리.
 *
 * @param isCalendarConfirmed OCR 또는 템플릿 검증을 통해 달력 객체가 확인된 사진인지 여부
 * @param stickerEmoji 사진 위에 오버레이할 귀여운 육아 스티커 이모지 (선택)
 * @param customSubtitle 사진 하단 배지 위에 오버레이할 한 줄 축하 자막 (선택)
 */
data class CalendarPhotoEntry(
    val id: String,
    val effectiveDate: LocalDate,
    val exifDday: Int,
    val ocrDday: Int? = null,
    val resolvedDday: Int = ocrDday ?: exifDday,
    val sharpnessScore: Double = 0.0,
    val photoUri: String = "",
    val isCalendarConfirmed: Boolean = (ocrDday != null),
    val stickerEmoji: String? = null,
    val customSubtitle: String? = null
)

enum class IssueType {
    UNFLIPPED_CALENDAR, // D-day 달력 미넘김 감지 (날짜가 바뀌었으나 동일 D-day 표시)
    MISSING_DAYS,       // D-day 달력 결측일 감지 (건너뛴 일자 존재)
    DISCREPANCY         // EXIF 계산일자와 D-day 달력 OCR 인식일자 불일치
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
     * OCR 감지 텍스트 토큰 중 EXIF D-day 기준 허용 오차 범위 내의 유효 D-day 숫자를 추출.
     * 연도(예: 2024), 전화번호 등 노이즈 숫자를 배제.
     *
     * @param rawCandidates D-day 달력 영역에서 검출된 OCR 텍스트 목록
     * @param expectedDday EXIF 날짜로부터 계산된 예상 D-day
     * @param tolerance 예상 D-day 기준 허용 편차 (기본값: 3)
     * @return 유효한 D-day 정수, 또는 범위 내 후보가 없는 경우 null
     */
    fun filterNoiseOcr(
        rawCandidates: List<String>,
        expectedDday: Int,
        tolerance: Int = 3
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
                    if (abs(num - expectedDday) <= tolerance) {
                        candidateNumbers.add(num)
                    }
                }
            }
        }

        // expectedDday에 가장 가까운 후보 반환
        return candidateNumbers.minByOrNull { abs(it - expectedDday) }
    }

    /**
     * 일별 D-day 달력 사진 엔트리의 연속성을 검증:
     * 1. OCR 노이즈 정제: ocrDday가 존재하나 exifDday와 편차가 3일 초과인 경우 잡숫자로 간주하여 EXIF 일수로 대체.
     * 2. D-day 달력 미넘김 감지 (날짜가 바뀌었으나 동일 D-day 유지).
     * 3. D-day 달력 결측일 감지 (resolvedDday 간 공백 탐색, todayMaxDday 상한선 및 큰 점프 방어).
     *
     * @param entries 검증할 달력 사진 엔트리 목록
     * @param todayMaxDday 오늘 날짜 기준 D-day 상한선 (미래 일자 결측 방지용)
     */
    fun validateSequence(
        entries: List<CalendarPhotoEntry>,
        todayMaxDday: Int? = null
    ): SequenceValidationResult {
        if (entries.isEmpty()) {
            return SequenceValidationResult(emptyList(), emptyList(), emptyList())
        }

        // 1. OCR 노이즈 정제: ocrDday가 존재하나 exifDday와 편차가 3일 초과인 경우 잡숫자로 간주하여 EXIF 일수로 대체
        val sanitizedEntries = entries.map { entry ->
            if (entry.ocrDday != null && abs(entry.ocrDday - entry.exifDday) > 3) {
                entry.copy(ocrDday = null, resolvedDday = entry.exifDday, isCalendarConfirmed = false)
            } else {
                entry
            }
        }

        // 날짜 및 resolvedDday 기준 오름차순 정렬
        val sortedEntries = sanitizedEntries.sortedWith(
            compareBy<CalendarPhotoEntry> { it.effectiveDate }
                .thenBy { it.resolvedDday }
        )

        val issues = mutableListOf<SequenceIssue>()

        // 2. D-day 달력 미넘김 감지
        for (i in 0 until sortedEntries.size - 1) {
            val current = sortedEntries[i]
            val next = sortedEntries[i + 1]

            val currentOcr = current.ocrDday
            val nextOcr = next.ocrDday

            if (currentOcr != null && nextOcr != null &&
                current.effectiveDate != next.effectiveDate &&
                currentOcr == nextOcr
            ) {
                issues.add(
                    SequenceIssue(
                        type = IssueType.UNFLIPPED_CALENDAR,
                        targetDday = nextOcr,
                        description = "D-day 달력 미넘김 의심: ${current.effectiveDate}와 ${next.effectiveDate}의 D-day 달력이 모두 D+$nextOcr 입니다.",
                        relatedEntryIds = listOf(current.id, next.id)
                    )
                )
            }
        }

        // 3. D-day 달력 결측일 감지
        val uniqueDays = sortedEntries.map { it.resolvedDday }.distinct().sorted()
        val allMissingDays = mutableListOf<Int>()

        if (uniqueDays.size >= 2) {
            for (i in 0 until uniqueDays.size - 1) {
                val currentDay = uniqueDays[i]
                val nextDay = uniqueDays[i + 1]

                if (nextDay > currentDay + 1) {
                    val rawMissing = ((currentDay + 1) until nextDay).toList()

                    // todayMaxDday 상한선 적용 (오늘 이후 미래 일자는 결측일에 포함하지 않음)
                    val boundedMissing = if (todayMaxDday != null) {
                        rawMissing.filter { it <= todayMaxDday }
                    } else {
                        rawMissing
                    }

                    // 비정상적으로 큰 점프(예: nextDay - currentDay >= 10) 방어:
                    // todayMaxDday가 없으면 최대 9개로 제한
                    val missingRange = if (todayMaxDday == null && (nextDay - currentDay >= 10)) {
                        boundedMissing.take(9)
                    } else {
                        boundedMissing
                    }

                    if (missingRange.isNotEmpty()) {
                        allMissingDays.addAll(missingRange)
                        issues.add(
                            SequenceIssue(
                                type = IssueType.MISSING_DAYS,
                                targetDday = currentDay,
                                description = "D-day 달력 결측일 감지: D+$currentDay 에서 D+$nextDay 사이에 ${missingRange.joinToString { "D+$it" }} 누락",
                                missingDays = missingRange
                            )
                        )
                    }
                }
            }
        }

        return SequenceValidationResult(
            validatedEntries = sortedEntries,
            issues = issues,
            missingDays = allMissingDays
        )
    }

    /**
     * 동일 날짜(D-day)에 여러 장의 사진이 있는 경우:
     * 1순위: 달력이 확인된 사진(isCalendarConfirmed == true) 최우선 선별
     * 2순위: 선명도(sharpnessScore) 최고점 선별
     */
    fun selectBestPhotoPerDay(entries: List<CalendarPhotoEntry>): List<CalendarPhotoEntry> {
        return entries.groupBy { it.resolvedDday }
            .map { (_, photosOfDay) ->
                photosOfDay.maxWithOrNull(
                    compareBy<CalendarPhotoEntry> { if (it.isCalendarConfirmed) 1 else 0 }
                        .thenBy { it.sharpnessScore }
                ) ?: photosOfDay.first()
            }
            .sortedBy { it.resolvedDday }
    }

    /**
     * AI 탐색 정확도 모드에 따라 후보 엔트리 필터링
     * - STRICT: 달력이 명시적으로 확인된 사진만 남김 (비달력 일상 사진 완전 배제)
     * - RELAXED: 모든 후보 사진 보존
     * - HIGH_PRECISION: 달력이 확인되었거나 선명도가 높은(>= 80.0) 후보 사진을 보존
     * - BABY_FACE_ONLY: 아기 얼굴/일상 사진 모드로 수집된 후보를 보존
     */
    fun filterByAccuracyMode(
        entries: List<CalendarPhotoEntry>,
        mode: ScanAccuracyMode
    ): List<CalendarPhotoEntry> {
        return when (mode) {
            ScanAccuracyMode.STRICT -> entries.filter { it.isCalendarConfirmed }
            ScanAccuracyMode.RELAXED -> entries
            ScanAccuracyMode.HIGH_PRECISION -> entries.filter {
                it.isCalendarConfirmed || it.ocrDday != null || it.sharpnessScore >= 80.0
            }
            ScanAccuracyMode.BABY_FACE_ONLY -> entries
        }
    }

    /**
     * 몰아찍기 스마트 분배 (Idea 08):
     * 동일 resolvedDday에 2장 이상 존재하는 잉여 사진(버스트 촬영본)을 찾아,
     * 첫 번째 대표 사진은 원래 날짜에 남기고 나머지 잉여 사진들을 missingDays(오름차순)에 순서대로 재할당하여 빈 날짜를 채움.
     */
    fun distributeBurstPhotos(
        entries: List<CalendarPhotoEntry>,
        missingDays: List<Int>
    ): List<CalendarPhotoEntry> {
        if (entries.isEmpty() || missingDays.isEmpty()) return entries

        val sortedMissing = missingDays.distinct().sorted()
        var missingIndex = 0

        // 날짜별로 대표 사진을 제외한 잉여 사진 ID -> 재할당할 D-day 매핑 생성
        val reassignedMap = mutableMapOf<String, Int>()
        val groupedByDay = entries.groupBy { it.resolvedDday }.toSortedMap()

        for ((_, photosOfDay) in groupedByDay) {
            if (photosOfDay.size > 1 && missingIndex < sortedMissing.size) {
                // 대표 사진 1장(달력 확인 우선 -> 선명도 순, 동점이면 첫 번째) 선정
                val sortedGroup = photosOfDay.sortedWith(
                    compareByDescending<CalendarPhotoEntry> { if (it.isCalendarConfirmed) 1 else 0 }
                        .thenByDescending { it.sharpnessScore }
                )
                val surplusPhotos = sortedGroup.drop(1)
                for (surplus in surplusPhotos) {
                    if (missingIndex >= sortedMissing.size) break
                    reassignedMap[surplus.id] = sortedMissing[missingIndex]
                    missingIndex++
                }
            }
        }

        if (reassignedMap.isEmpty()) return entries

        return entries.map { entry ->
            val targetMissingDay = reassignedMap[entry.id]
            if (targetMissingDay != null) {
                entry.copy(resolvedDday = targetMissingDay)
            } else {
                entry
            }
        }.sortedBy { it.resolvedDday }
    }
}
