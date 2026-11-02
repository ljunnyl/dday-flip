package com.ddayflip.app.domain

import android.net.TestUri
import com.ddayflip.app.core.domain.BabyFaceEvaluator
import com.ddayflip.app.core.domain.DayByDayDirectPickerHelper
import com.ddayflip.app.core.domain.DdayStartRule
import com.ddayflip.app.core.domain.DetectedFaceCandidate
import com.ddayflip.app.core.domain.ReferenceBabyProfile
import com.ddayflip.app.domain.model.GalleryPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class BabyFaceAndDirectPickerTest {

    @Test
    fun `기준 아기 사진이 등록되면 성인 얼굴을 배제하고 우리 아기 얼굴만 통과시킨다`() {
        val evaluator = BabyFaceEvaluator()

        // 우리 아기 기준 얼굴 프로필 (따뜻하고 밝은 톤 벡터 + 둥근 아기 얼굴 비율)
        val babyFeatureVec = DoubleArray(64) { idx -> if (idx % 2 == 0) 0.85 else 0.65 }
        val refProfile = ReferenceBabyProfile(
            faceFeatureVector = babyFeatureVec,
            faceWidthToHeightRatio = 0.92f,
            eyeToMouthRatio = 0.34f
        )

        // 후보 1: 우리 아기 사진 (유사한 특징 벡터 + 둥근 얼굴 비율 + 눈 뜸/미소)
        val matchingBabyFace = DetectedFaceCandidate(
            boundingBoxAreaRatio = 0.18f,
            centerOffsetRatio = 0.10f,
            widthToHeightRatio = 0.90f,
            eyeToMouthRatio = 0.35f,
            leftEyeOpenProb = 0.92f,
            rightEyeOpenProb = 0.95f,
            smilingProb = 0.80f,
            faceFeatureVector = DoubleArray(64) { idx -> if (idx % 2 == 0) 0.83 else 0.67 }
        )

        // 후보 2: 성인 셀카/타인 사진 (특징 벡터 상이 + 턱이 긴 성인 얼굴 비율)
        val adultFace = DetectedFaceCandidate(
            boundingBoxAreaRatio = 0.25f,
            centerOffsetRatio = 0.08f,
            widthToHeightRatio = 0.68f,
            eyeToMouthRatio = 0.48f,
            leftEyeOpenProb = 0.90f,
            rightEyeOpenProb = 0.90f,
            smilingProb = 0.50f,
            faceFeatureVector = DoubleArray(64) { idx -> if (idx % 2 == 0) 0.15 else 0.90 }
        )

        val babyEval = evaluator.evaluatePhotoFaces(
            faces = listOf(matchingBabyFace),
            referenceProfile = refProfile,
            sharpness = 85.0
        )
        val adultEval = evaluator.evaluatePhotoFaces(
            faces = listOf(adultFace),
            referenceProfile = refProfile,
            sharpness = 90.0
        )

        assertTrue("기준 아기 얼굴과 유사하고 비율이 일치하는 사진은 통과해야 함", babyEval.isAcceptedBabyPhoto)
        assertFalse("기준 아기 얼굴과 다른 성인 사진은 탈락해야 함", adultEval.isAcceptedBabyPhoto)
        assertTrue(babyEval.totalScore > adultEval.totalScore)
    }

    @Test
    fun `같은 날 아기 사진 여러 장 중 눈을 뜨고 웃으며 선명한 사진이 가장 높은 점수를 받는다`() {
        val evaluator = BabyFaceEvaluator()

        val sleepingOrBlurryBaby = DetectedFaceCandidate(
            boundingBoxAreaRatio = 0.15f,
            centerOffsetRatio = 0.15f,
            widthToHeightRatio = 0.91f,
            eyeToMouthRatio = 0.35f,
            leftEyeOpenProb = 0.10f,
            rightEyeOpenProb = 0.12f,
            smilingProb = 0.05f,
            faceFeatureVector = DoubleArray(64) { 0.7 }
        )

        val smilingOpenEyesBaby = DetectedFaceCandidate(
            boundingBoxAreaRatio = 0.20f,
            centerOffsetRatio = 0.05f,
            widthToHeightRatio = 0.91f,
            eyeToMouthRatio = 0.35f,
            leftEyeOpenProb = 0.95f,
            rightEyeOpenProb = 0.96f,
            smilingProb = 0.88f,
            faceFeatureVector = DoubleArray(64) { 0.7 }
        )

        val scoreSleep = evaluator.evaluatePhotoFaces(
            faces = listOf(sleepingOrBlurryBaby),
            referenceProfile = null,
            sharpness = 45.0
        )
        val scoreSmile = evaluator.evaluatePhotoFaces(
            faces = listOf(smilingOpenEyesBaby),
            referenceProfile = null,
            sharpness = 110.0
        )

        assertTrue(scoreSmile.isAcceptedBabyPhoto)
        assertTrue(scoreSmile.totalScore > scoreSleep.totalScore + 25.0)
    }

    @Test
    fun `직접 고르기 - 사진을 날짜별로 구분하고 빈 날짜는 앞뒤 날짜의 남는 사진을 끌어올 후보로 제공한다`() {
        val birthday = LocalDate.of(2026, 5, 27) // D+1 = 5/27, D+2 = 5/28, D+3 = 5/29
        val zone = ZoneId.of("Asia/Seoul")

        fun makePhoto(id: Long, date: LocalDate, hour: Int, bucket: String = "Camera"): GalleryPhoto {
            val ms = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, 0)
                .atZone(zone).toInstant().toEpochMilli()
            return GalleryPhoto(
                id = id,
                contentUri = TestUri("content://media/external/images/media/$id"),
                dateTakenMs = ms,
                dateTakenLocalDate = date,
                width = 1080,
                height = 1920,
                orientation = 0,
                bucketName = bucket
            )
        }

        // D+1 (5/27): 사진 2장 (101번 오전 9시 카톡, 102번 오후 1시 카메라 원본)
        // D+2 (5/28): 사진 0장 (깜빡하고 안 찍은 빈 날!)
        // D+3 (5/29): 사진 2장 (301번 오후 1시 카메라, 302번 오후 3시 카메라)
        val photos = listOf(
            makePhoto(101L, LocalDate.of(2026, 5, 27), 9, "KakaoTalk"),
            makePhoto(102L, LocalDate.of(2026, 5, 27), 13, "Camera"),
            makePhoto(301L, LocalDate.of(2026, 5, 29), 13, "Camera"),
            makePhoto(302L, LocalDate.of(2026, 5, 29), 15, "Camera")
        )

        val buckets = DayByDayDirectPickerHelper.buildDayBuckets(
            photos = photos,
            birthday = birthday,
            rule = DdayStartRule.ONE_BASED,
            maxDday = 3,
            includeDdayZero = false
        )

        assertEquals(3, buckets.size)

        val day1 = buckets.first { it.dday == 1 }
        val day2 = buckets.first { it.dday == 2 }

        // 1. D+1에는 Camera 폴더 오후 1시 사진(102번)이 대표 1장으로 자동 프리체크되어야 함
        assertEquals(102L, day1.defaultSelectedPhotoId)
        assertEquals(2, day1.photosOnThatDay.size)

        // 2. D+2는 당일 찍은 사진이 0장이므로 isEmptyDay == true 이며,
        //    앞뒤 날짜(D+1의 남는 사진 101번, D+3의 남는 사진 302번 등)가 끌어오기 후보(nearbyPullCandidates)로 제공되어야 함!
        assertTrue(day2.isEmptyDay)
        assertTrue(day2.nearbyPullCandidates.isNotEmpty())
        val candidateIds = day2.nearbyPullCandidates.map { it.photo.id }
        assertTrue(candidateIds.contains(101L) || candidateIds.contains(302L))
        assertTrue(day2.nearbyPullCandidates.first().isSparePhoto)

        // 3. 사용자가 빈 날짜(D+2)에 D+1의 남는 사진(101번)을 끌어오면,
        //    최종 엔트리 리스트에 D+1(102번), D+2(101번 - 끌어온 사진), D+3(301번) 총 3일이 빈틈없이 만들어져야 함
        val selectionMap = DayByDayDirectPickerHelper.buildInitialSelectionMap(buckets).toMutableMap()
        selectionMap[2] = day2.nearbyPullCandidates.first { it.photo.id == 101L }.photo

        val finalEntries = DayByDayDirectPickerHelper.toCalendarPhotoEntries(
            buckets = buckets,
            selectedPhotoPerDay = selectionMap
        )

        assertEquals(3, finalEntries.size)
        assertEquals(listOf(1, 2, 3), finalEntries.map { it.resolvedDday })
        assertEquals("102", finalEntries[0].id)
        assertEquals("101_pulled_to_2", finalEntries[1].id)
        assertEquals("301", finalEntries[2].id)
    }

    @Test
    fun `빈 날짜로 다른 날짜의 사진을 이동하면 해당 날짜에 배치되고 원래 날짜의 남은 후보 사진이 대표로 자동 승격된다`() {
        val birthday = LocalDate.of(2026, 5, 27)
        val zone = ZoneId.of("Asia/Seoul")

        fun makePhoto(id: Long, date: LocalDate, hour: Int, bucket: String = "Camera"): GalleryPhoto {
            val ms = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, 0)
                .atZone(zone).toInstant().toEpochMilli()
            return GalleryPhoto(
                id = id,
                contentUri = TestUri("content://media/external/images/media/$id"),
                dateTakenMs = ms,
                dateTakenLocalDate = date,
                width = 1080,
                height = 1920,
                orientation = 0,
                bucketName = bucket
            )
        }

        // D+1 (5/27): 1장 (101번)
        // D+2 (5/28): 0장 (빈 날!)
        // D+3 (5/29): 2장 (301번 대표 후보, 302번 여유분 후보)
        val p101 = makePhoto(101L, LocalDate.of(2026, 5, 27), 13)
        val p301 = makePhoto(301L, LocalDate.of(2026, 5, 29), 13)
        val p302 = makePhoto(302L, LocalDate.of(2026, 5, 29), 15)

        val buckets = DayByDayDirectPickerHelper.buildDayBuckets(
            photos = listOf(p101, p301, p302),
            birthday = birthday,
            rule = DdayStartRule.ONE_BASED,
            maxDday = 3
        )

        val initialSelection = DayByDayDirectPickerHelper.buildInitialSelectionMap(buckets)
        assertEquals(101L, initialSelection[1]?.id)
        assertEquals(null, initialSelection[2])
        assertEquals(301L, initialSelection[3]?.id)

        // 케이스 1: D+3의 대표 사진(301번)을 D+2(빈 날짜)로 이동!
        // 기대 동작:
        // - D+2의 대표로 301번이 배치됨.
        // - D+3에는 남은 사진(302번)이 있으므로, 302번이 자동으로 대표 사진으로 승격(auto-replenish)되어 D+3이 빈 날이 되지 않음!
        val afterMove1 = DayByDayDirectPickerHelper.movePhotoToTargetDay(
            currentSelection = initialSelection,
            buckets = buckets,
            photoToMove = p301,
            fromDday = 3,
            targetDday = 2
        )

        assertEquals(101L, afterMove1[1]?.id)
        assertEquals(301L, afterMove1[2]?.id)
        assertEquals(302L, afterMove1[3]?.id) // 302번 자동 승격 확인!

        // 케이스 2: 1장뿐인 D+1 사진(101번)을 다른 날짜로 이동하면 원래 D+1은 빈 날짜(null)가 됨
        val afterMove2 = DayByDayDirectPickerHelper.movePhotoToTargetDay(
            currentSelection = afterMove1,
            buckets = buckets,
            photoToMove = p101,
            fromDday = 1,
            targetDday = 2
        )
        assertEquals(null, afterMove2[1]) // 남은 사진 없으므로 비워짐
        assertEquals(101L, afterMove2[2]?.id)

        // 케이스 3: 최종 toCalendarPhotoEntries 변환 시 ocrDday는 null이어야 함 (달력 오인식 방지)
        val entries = DayByDayDirectPickerHelper.toCalendarPhotoEntries(buckets, afterMove1)
        assertEquals(3, entries.size)
        assertTrue(entries.all { it.ocrDday == null })
        assertTrue(entries.all { it.isCalendarConfirmed })
    }
}
