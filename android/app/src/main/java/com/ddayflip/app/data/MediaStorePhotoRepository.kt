package com.ddayflip.app.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.ddayflip.app.domain.model.GalleryPhoto
import com.ddayflip.app.domain.repository.PhotoRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * MediaStore 쿼리 파라미터 생성 및 날짜 변환 헬퍼 (단위 테스트 독립 검증 지원)
 */
object MediaStoreQueryHelper {

    /**
     * 시작일 00:00:00부터 종료일 23:59:59.999까지의 epoch milliseconds 범위를 계산합니다.
     */
    fun calculateDateRangeMillis(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Pair<Long, Long> {
        val startMs = ZonedDateTime.of(startDate, LocalTime.MIN, zoneId).toInstant().toEpochMilli()
        val endMs = ZonedDateTime.of(endDate, LocalTime.MAX, zoneId).toInstant().toEpochMilli()
        return Pair(startMs, endMs)
    }

    /**
     * epoch millisecond를 지정한 타임존의 LocalDate로 변환합니다.
     */
    fun millisToLocalDate(
        millis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): LocalDate {
        return Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
    }

    /**
     * MediaStore DATE_TAKEN 범위 조회를 위한 selection 쿼리 문자열을 반환합니다.
     */
    fun buildSelection(): String {
        return "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} <= ?"
    }

    /**
     * MediaStore DATE_TAKEN 범위 조회를 위한 selectionArgs 배열을 반환합니다.
     */
    fun buildSelectionArgs(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Array<String> {
        val (startMs, endMs) = calculateDateRangeMillis(startDate, endDate, zoneId)
        return arrayOf(startMs.toString(), endMs.toString())
    }
}

/**
 * Android 온디바이스 MediaStore 기반 사진 저장소 구현체.
 *
 * 외부 네트워크 통신 없이 로컬 ContentResolver를 통해 사진 메타데이터를 조회합니다.
 */
class MediaStorePhotoRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : PhotoRepository {

    override suspend fun getPhotosBetween(
        startDate: LocalDate,
        endDate: LocalDate
    ): List<GalleryPhoto> = withContext(ioDispatcher) {
        val photos = mutableListOf<GalleryPhoto>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.ORIENTATION
        )

        val selection = MediaStoreQueryHelper.buildSelection()
        val selectionArgs = MediaStoreQueryHelper.buildSelectionArgs(startDate, endDate, zoneId)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} ASC, ${MediaStore.Images.Media._ID} ASC"

        val collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        context.contentResolver.query(
            collectionUri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val orientationColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateTakenMs = cursor.getLong(dateTakenColumn)
                val width = cursor.getInt(widthColumn)
                val height = cursor.getInt(heightColumn)
                val orientation = cursor.getInt(orientationColumn)

                val contentUri = ContentUris.withAppendedId(collectionUri, id)
                val dateTakenLocalDate = MediaStoreQueryHelper.millisToLocalDate(dateTakenMs, zoneId)

                photos.add(
                    GalleryPhoto(
                        id = id,
                        contentUri = contentUri,
                        dateTakenMs = dateTakenMs,
                        dateTakenLocalDate = dateTakenLocalDate,
                        width = width,
                        height = height,
                        orientation = orientation
                    )
                )
            }
        }

        photos
    }
}
