package com.ddayflip.app.domain.model

import android.net.Uri
import java.time.LocalDate

/**
 * 기기 갤러리에서 읽어온 사진 메타데이터 모델.
 *
 * @param id MediaStore._ID
 * @param contentUri content://media/external/images/media/{id} 형태의 로컬 Uri
 * @param dateTakenMs 촬영 일시(epoch ms)
 * @param dateTakenLocalDate 촬영 일자(LocalDate)
 * @param width 이미지 너비(px)
 * @param height 이미지 높이(px)
 * @param orientation 회전 각도(0, 90, 180, 270 등)
 */
data class GalleryPhoto(
    val id: Long,
    val contentUri: Uri,
    val dateTakenMs: Long,
    val dateTakenLocalDate: LocalDate,
    val width: Int = 0,
    val height: Int = 0,
    val orientation: Int = 0
) {
    /**
     * 회전 각도를 감안한 실제 가로 방향 사진 여부
     */
    val isLandscape: Boolean
        get() = if (orientation == 90 || orientation == 270) {
            height > width
        } else {
            width > height
        }
}
