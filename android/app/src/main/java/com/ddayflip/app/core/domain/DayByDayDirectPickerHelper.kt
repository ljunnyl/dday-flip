package com.ddayflip.app.core.domain

import com.ddayflip.app.domain.model.GalleryPhoto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * 빈 날짜(사진을 안 찍은 날)에 앞뒤 날짜(±3일)에서 끌어올 수 있는 후보 사진 정보.
 *
 * @param photo 원본 갤러리 사진
 * @param sourceDday 원래 찍힌 날짜의 D-day 번호 (예: D+12)
 * @param dayDistance 빈 날짜로부터 떨어진 일수 (예: -1 = 어제 찍은 사진, +1 = 내일 찍은 사진)
 * @param isSparePhoto 해당 날짜의 대표 1장으로 선택되지 않고 남아 있는 '여유분 사진'인지 여부 (우선 노출)
 */
data class NearbyPullCandidate(
    val photo: GalleryPhoto,
    val sourceDday: Int,
    val dayDistance: Int,
    val isSparePhoto: Boolean
)

/**
 * 직접 고르기 화면에서 날짜별(D+N)로 구분된 한 줄(버킷) 모델.
 */
data class DirectDayBucket(
    val dday: Int,
    val date: LocalDate,
    val photosOnThatDay: List<GalleryPhoto>,
    val defaultSelectedPhotoId: Long?,
    val nearbyPullCandidates: List<NearbyPullCandidate> = emptyList()
) {
    val isEmptyDay: Boolean
        get() = photosOnThatDay.isEmpty()
}

/**
 * '앨범에서 내가 직접 고르기' 모드를 위한 날짜별(D+N) 자동 분류 및
 * 사진 없는 빈 날에 앞뒤 날짜(±3일) 사진을 끌어오는 순수 도메인 헬퍼.
 */
object DayByDayDirectPickerHelper {

    /**
     * 비트맵 디코딩 없이 메타데이터만으로 날짜별 대표 1장 우선순위를 매기는 점수 공식.
     * - Camera/DCIM 원본 폴더 가점 (+30)
     * - 세로(Portrait) 9:16 릴스 비율 가점 (+20)
     * - 고해상도 가점 (0 ~ +25)
     * - 낮 자연광 시간대(13시 전후) 가점 (0 ~ +15)
     */
    fun scorePhotoMetadata(
        photo: GalleryPhoto,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Double {
        var score = 0.0

        val bucket = photo.bucketName
        if (bucket.equals("Camera", ignoreCase = true) ||
            bucket.contains("DCIM", ignoreCase = true) ||
            bucket.contains("카메라", ignoreCase = true)
        ) {
            score += 30.0
        }

        if (!photo.isLandscape) {
            score += 20.0
        }

        val pixels = (photo.width.toLong() * photo.height.toLong()).coerceAtLeast(0L)
        score += (pixels.coerceAtMost(12_000_000L).toDouble() / 480_000.0)

        if (photo.dateTakenMs > 0L) {
            val hour = Instant.ofEpochMilli(photo.dateTakenMs).atZone(zoneId).hour
            score += (15.0 - abs(hour - 13) * 1.2).coerceAtLeast(0.0)
        }

        return score
    }

    /**
     * 생일~최대 D-day 구간의 사진들을 날짜별(D+N) 슬롯으로 묶고,
     * 사진이 있는 날은 가장 좋은 사진 1장을 기본 체크하며,
     * 사진이 없는 빈 날(isEmptyDay)에는 앞뒤 ±nearbyWindowDays일 내의 여유분 사진을 끌어오기 후보로 연결합니다.
     */
    fun buildDayBuckets(
        photos: List<GalleryPhoto>,
        birthday: LocalDate,
        rule: DdayStartRule,
        maxDday: Int,
        includeDdayZero: Boolean = false,
        nearbyWindowDays: Int = 3,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<DirectDayBucket> {
        val calc = DdayCalculator(birthday = birthday, rule = rule)
        val startDday = if (includeDdayZero) 0 else if (rule == DdayStartRule.ONE_BASED) 1 else 0
        val endDday = maxDday.coerceAtLeast(startDday)

        // 1. 사진들을 D-day별로 그룹핑하고 메타데이터 점수순 정렬
        val groupedByDday: Map<Int, List<GalleryPhoto>> = photos
            .groupBy { photo ->
                calc.calculate(photo.dateTakenLocalDate.atTime(12, 0)).dday
            }
            .mapValues { (_, dayPhotos) ->
                dayPhotos.sortedByDescending { scorePhotoMetadata(it, zoneId) }
            }

        // 2. 각 날짜의 기본 대표 사진 ID 결정
        val defaultRepIds = mutableMapOf<Int, Long>()
        for ((dday, sortedList) in groupedByDday) {
            val best = sortedList.firstOrNull()
            if (best != null) {
                defaultRepIds[dday] = best.id
            }
        }

        // 3. startDday..endDday 전체 슬롯 생성 및 빈 날짜에 앞뒤 ±3일 끌어오기 후보 주입
        val buckets = mutableListOf<DirectDayBucket>()
        for (targetDday in startDday..endDday) {
            val dayOffset = if (rule == DdayStartRule.ONE_BASED) {
                (targetDday - 1).toLong()
            } else {
                targetDday.toLong()
            }
            val slotDate = birthday.plusDays(dayOffset)
            val dayPhotos = groupedByDday[targetDday] ?: emptyList()
            val defaultId = defaultRepIds[targetDday]

            val nearbyCandidates = if (dayPhotos.isEmpty()) {
                val list = mutableListOf<NearbyPullCandidate>()
                for (delta in -nearbyWindowDays..nearbyWindowDays) {
                    if (delta == 0) continue
                    val neighborDday = targetDday + delta
                    val neighborPhotos = groupedByDday[neighborDday] ?: emptyList()
                    val neighborRepId = defaultRepIds[neighborDday]
                    for (np in neighborPhotos) {
                        list.add(
                            NearbyPullCandidate(
                                photo = np,
                                sourceDday = neighborDday,
                                dayDistance = delta,
                                isSparePhoto = (np.id != neighborRepId)
                            )
                        )
                    }
                }
                // 다른 날 대표로 안 쓰인 '남는 사진(isSparePhoto)'을 1순위로, 날짜가 가까운 순(abs(dayDistance))을 2순위로 정렬
                list.sortedWith(
                    compareByDescending<NearbyPullCandidate> { it.isSparePhoto }
                        .thenBy { abs(it.dayDistance) }
                        .thenByDescending { scorePhotoMetadata(it.photo, zoneId) }
                ).take(12)
            } else {
                emptyList()
            }

            buckets.add(
                DirectDayBucket(
                    dday = targetDday,
                    date = slotDate,
                    photosOnThatDay = dayPhotos,
                    defaultSelectedPhotoId = defaultId,
                    nearbyPullCandidates = nearbyCandidates
                )
            )
        }

        return buckets
    }

    /**
     * 초기 상태: 사진이 있는 모든 날짜에 대해 대표 사진 1장씩 자동 선택된 맵(dday -> GalleryPhoto) 반환
     */
    fun buildInitialSelectionMap(buckets: List<DirectDayBucket>): Map<Int, GalleryPhoto> {
        val map = linkedMapOf<Int, GalleryPhoto>()
        for (b in buckets) {
            val defaultId = b.defaultSelectedPhotoId ?: continue
            val chosen = b.photosOnThatDay.firstOrNull { it.id == defaultId } ?: b.photosOnThatDay.firstOrNull()
            if (chosen != null) {
                map[b.dday] = chosen
            }
        }
        return map
    }

    /**
     * 특정 날짜의 사진을 다른 날짜(예: 7월 4일 사진을 사진 없는 7월 3일로)로 이동/끌어옵니다.
     * - 이동 대상 날짜(targetDday)의 대표 사진으로 배치됩니다.
     * - 원래 날짜(fromDday)에 다른 후보 사진이 남아있다면, 다음 순위 사진이 자동으로 대표로 승격(auto-replenish)되어
     *   원래 날짜의 대표 사진이 비지 않도록 똑똑하게 보존합니다.
     */
    fun movePhotoToTargetDay(
        currentSelection: Map<Int, GalleryPhoto>,
        buckets: List<DirectDayBucket>,
        photoToMove: GalleryPhoto,
        fromDday: Int,
        targetDday: Int
    ): Map<Int, GalleryPhoto> {
        val updated = currentSelection.toMutableMap()
        // 1. 타겟 날짜에 사진 배치
        updated[targetDday] = photoToMove

        // 2. 원래 날짜(fromDday)의 대표 사진이 방금 이동한 사진이었다면?
        if (updated[fromDday]?.id == photoToMove.id) {
            val fromBucket = buckets.firstOrNull { it.dday == fromDday }
            val remainingPhotos = fromBucket?.photosOnThatDay?.filter { it.id != photoToMove.id }
            if (!remainingPhotos.isNullOrEmpty()) {
                // 남은 사진 중 가장 점수가 높은 1장을 새 대표 사진으로 자동 승격!
                updated[fromDday] = remainingPhotos.first()
            } else {
                // 다른 사진이 없다면 원래 날짜는 비워둠
                updated.remove(fromDday)
            }
        }
        return updated
    }

    /**
     * 사용자가 선택한 날짜별 사진 맵(빈 날짜에 앞뒤에서 끌어온 사진 포함)을
     * 검수 및 영상 제작용 CalendarPhotoEntry 리스트로 변환합니다.
     */
    fun toCalendarPhotoEntries(
        buckets: List<DirectDayBucket>,
        selectedPhotoPerDay: Map<Int, GalleryPhoto>
    ): List<CalendarPhotoEntry> {
        val bucketByDday = buckets.associateBy { it.dday }
        return selectedPhotoPerDay.entries
            .sortedBy { it.key }
            .map { (targetDday, photo) ->
                val slotDate = bucketByDday[targetDday]?.date ?: photo.dateTakenLocalDate
                val isPulledFromAnotherDay = bucketByDday[targetDday]?.photosOnThatDay?.none { it.id == photo.id } == true
                val entryId = if (isPulledFromAnotherDay) {
                    "${photo.id}_pulled_to_$targetDday"
                } else {
                    photo.id.toString()
                }
                CalendarPhotoEntry(
                    id = entryId,
                    effectiveDate = slotDate,
                    exifDday = targetDday,
                    ocrDday = null, // 직접 고르기 모드는 달력 OCR이 아니므로 null (오인식 방지)
                    resolvedDday = targetDday,
                    sharpnessScore = 100.0,
                    photoUri = photo.contentUri.toString(),
                    isCalendarConfirmed = true
                )
            }
    }
}
