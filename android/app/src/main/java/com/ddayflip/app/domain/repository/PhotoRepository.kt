package com.ddayflip.app.domain.repository

import com.ddayflip.app.domain.model.GalleryPhoto
import java.time.LocalDate

/**
 * 갤러리 사진 데이터 접근 인터페이스 (온디바이스 MediaStore 기반)
 */
interface PhotoRepository {
    /**
     * 지정한 시작일(startDate)부터 종료일(endDate)까지 촬영된 갤러리 사진 목록을 반환합니다.
     */
    suspend fun getPhotosBetween(startDate: LocalDate, endDate: LocalDate): List<GalleryPhoto>
}
