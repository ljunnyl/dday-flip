package com.ddayflip.app.data

import android.net.TestUri
import android.net.Uri
import com.ddayflip.app.domain.model.GalleryPhoto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class MediaStorePhotoRepositoryTest {

    private val zoneId: ZoneId = ZoneId.of("Asia/Seoul")

    private fun createTestUri(id: Long): Uri {
        return TestUri("content://media/external/images/media/$id")
    }

    @Test
    fun `test date range conversion to epoch millis`() {
        val startDate = LocalDate.of(2024, 5, 27)
        val endDate = LocalDate.of(2024, 6, 5)

        val (startMs, endMs) = MediaStoreQueryHelper.calculateDateRangeMillis(startDate, endDate, zoneId)

        val expectedStart = ZonedDateTime.of(startDate, LocalTime.MIN, zoneId).toInstant().toEpochMilli()
        val expectedEnd = ZonedDateTime.of(endDate, LocalTime.MAX, zoneId).toInstant().toEpochMilli()

        assertEquals(expectedStart, startMs)
        assertTrue(endMs >= expectedStart)
        assertEquals(endDate, MediaStoreQueryHelper.millisToLocalDate(endMs, zoneId))
        assertEquals(startDate, MediaStoreQueryHelper.millisToLocalDate(startMs, zoneId))
    }

    @Test
    fun `test single day date range calculation`() {
        val singleDate = LocalDate.of(2024, 5, 27)
        val (startMs, endMs) = MediaStoreQueryHelper.calculateDateRangeMillis(singleDate, singleDate, zoneId)

        val expectedStart = ZonedDateTime.of(singleDate, LocalTime.MIN, zoneId).toInstant().toEpochMilli()
        val expectedEnd = ZonedDateTime.of(singleDate, LocalTime.MAX, zoneId).toInstant().toEpochMilli()

        assertEquals(expectedStart, startMs)
        assertEquals(expectedEnd, endMs)
        assertEquals(singleDate, MediaStoreQueryHelper.millisToLocalDate(startMs, zoneId))
        assertEquals(singleDate, MediaStoreQueryHelper.millisToLocalDate(endMs, zoneId))
    }

    @Test
    fun `test leap year date calculation`() {
        val leapDate = LocalDate.of(2024, 2, 29)
        val (startMs, endMs) = MediaStoreQueryHelper.calculateDateRangeMillis(leapDate, leapDate, zoneId)

        assertEquals(leapDate, MediaStoreQueryHelper.millisToLocalDate(startMs, zoneId))
        assertEquals(leapDate, MediaStoreQueryHelper.millisToLocalDate(endMs, zoneId))
    }

    @Test
    fun `test selection query and selectionArgs generation`() {
        val startDate = LocalDate.of(2024, 5, 27)
        val endDate = LocalDate.of(2024, 5, 28)

        val selection = MediaStoreQueryHelper.buildSelection()
        val selectionArgs = MediaStoreQueryHelper.buildSelectionArgs(startDate, endDate, zoneId)

        assertTrue(
            "Selection should contain date condition",
            selection.contains(android.provider.MediaStore.Images.Media.DATE_TAKEN) &&
                selection.contains(">= ?") &&
                selection.contains("<= ?")
        )

        assertEquals(2, selectionArgs.size)
        val (expectedStartMs, expectedEndMs) = MediaStoreQueryHelper.calculateDateRangeMillis(startDate, endDate, zoneId)
        assertArrayEquals(arrayOf(expectedStartMs.toString(), expectedEndMs.toString()), selectionArgs)
    }

    @Test
    fun `test gallery photo data model and date conversion`() {
        val date = LocalDate.of(2024, 5, 27)
        val timeMs = ZonedDateTime.of(date, LocalTime.of(14, 30), zoneId).toInstant().toEpochMilli()
        val uri = createTestUri(12345L)

        val photo = GalleryPhoto(
            id = 12345L,
            contentUri = uri,
            dateTakenMs = timeMs,
            dateTakenLocalDate = date,
            width = 4000,
            height = 3000,
            orientation = 0
        )

        assertEquals(12345L, photo.id)
        assertEquals(uri, photo.contentUri)
        assertEquals(timeMs, photo.dateTakenMs)
        assertEquals(date, photo.dateTakenLocalDate)
        assertEquals(4000, photo.width)
        assertEquals(3000, photo.height)
        assertEquals(0, photo.orientation)
        assertTrue(photo.isLandscape)
    }

    @Test
    fun `test gallery photo orientation handling for landscape detection`() {
        val date = LocalDate.of(2024, 5, 27)
        val timeMs = ZonedDateTime.of(date, LocalTime.NOON, zoneId).toInstant().toEpochMilli()
        val uri = createTestUri(1L)

        // 90 degrees rotated: width 3000, height 4000 -> actually landscape in upright view
        val photoRotated90 = GalleryPhoto(
            id = 1L,
            contentUri = uri,
            dateTakenMs = timeMs,
            dateTakenLocalDate = date,
            width = 3000,
            height = 4000,
            orientation = 90
        )
        assertTrue(photoRotated90.isLandscape)

        // 270 degrees rotated: width 3000, height 4000 -> actually landscape in upright view
        val photoRotated270 = GalleryPhoto(
            id = 2L,
            contentUri = uri,
            dateTakenMs = timeMs,
            dateTakenLocalDate = date,
            width = 3000,
            height = 4000,
            orientation = 270
        )
        assertTrue(photoRotated270.isLandscape)

        // 180 degrees rotated: width 4000, height 3000 -> landscape
        val photoRotated180 = GalleryPhoto(
            id = 3L,
            contentUri = uri,
            dateTakenMs = timeMs,
            dateTakenLocalDate = date,
            width = 4000,
            height = 3000,
            orientation = 180
        )
        assertTrue(photoRotated180.isLandscape)

        // 0 degrees portrait: width 3000, height 4000 -> portrait
        val photoPortrait = GalleryPhoto(
            id = 4L,
            contentUri = uri,
            dateTakenMs = timeMs,
            dateTakenLocalDate = date,
            width = 3000,
            height = 4000,
            orientation = 0
        )
        assertFalse(photoPortrait.isLandscape)
    }
}
