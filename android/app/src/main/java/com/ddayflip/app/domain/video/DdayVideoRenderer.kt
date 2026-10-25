package com.ddayflip.app.domain.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import com.ddayflip.app.core.domain.CalendarPhotoEntry

object DdayVideoRenderer {

    /**
     * 사진을 FIT_CENTER로 맞추고 하단에 D-day 뱃지를 오버레이 합성한 1080x1920 크기의 비트맵을 생성합니다.
     */
    fun renderCompositeBitmap(
        context: Context,
        entry: CalendarPhotoEntry,
        config: VideoExportConfig = VideoExportConfig()
    ): Bitmap {
        val compositeBitmap = Bitmap.createBitmap(config.width, config.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(compositeBitmap)

        // 1. 검정 배경 채우기
        canvas.drawColor(Color.BLACK)

        // 2. 원본 사진 로드 및 FIT_CENTER 렌더링
        val photoBitmap = loadPhotoBitmap(context, entry.photoUri)
        if (photoBitmap != null) {
            val fitRect = config.calculateFitCenter(photoBitmap.width, photoBitmap.height)
            val srcRect = Rect(0, 0, photoBitmap.width, photoBitmap.height)
            val dstRect = RectF(
                fitRect.left,
                fitRect.top,
                fitRect.left + fitRect.width,
                fitRect.top + fitRect.height
            )
            val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            canvas.drawBitmap(photoBitmap, srcRect, dstRect, photoPaint)
            photoBitmap.recycle()
        } else {
            // 사진 로드 실패 시 대체 그레이 박스 및 메시지
            val placeholderPaint = Paint().apply {
                color = Color.DKGRAY
            }
            canvas.drawRect(0f, 0f, config.width.toFloat(), config.height.toFloat(), placeholderPaint)
        }

        // 3. 하단 D-day 뱃지 오버레이 합성
        drawDdayBadge(canvas, entry.resolvedDday, config)

        return compositeBitmap
    }

    /**
     * D-day 뱃지(라운드 사각형 + 볼드 텍스트)를 캔버스에 그립니다.
     */
    fun drawDdayBadge(
        canvas: Canvas,
        dday: Int,
        config: VideoExportConfig
    ) {
        val labelText = "D+$dday"

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.badgeTextColor
            textSize = config.height * config.badgeTextSizeRatio
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val textBounds = Rect()
        textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)
        val textWidth = textBounds.width().toFloat()
        val textHeight = textBounds.height().toFloat()

        val badge = config.calculateBadgeRect(textWidth, textHeight)

        // 뱃지 배경 박스 그리기
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.badgeBackgroundColor
            style = Paint.Style.FILL
        }
        val badgeRectF = RectF(badge.left, badge.top, badge.right, badge.bottom)
        canvas.drawRoundRect(badgeRectF, badge.cornerRadius, badge.cornerRadius, bgPaint)

        // 텍스트 그리기
        canvas.drawText(labelText, badge.textX, badge.textY, textPaint)
    }

    /**
     * ARGB_8888 비트맵을 H.264 인코더가 직접 처리할 수 있는 NV12 (YUV420SemiPlanar) 바이트 배열로 변환합니다.
     */
    fun convertBitmapToNv12(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        outBytes: ByteArray
    ) {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize

        for (j in 0 until height) {
            for (i in 0 until width) {
                val color = argb[j * width + i]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff

                // RGB -> Y 변환 (ITU-R BT.601)
                var y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                if (y < 16) y = 16 else if (y > 235) y = 235
                outBytes[yIndex++] = y.toByte()

                // 2x2 서브샘플링으로 UV(Cb/Cr) 인터리브 기록 (NV12: U, V 순서)
                if ((j and 1) == 0 && (i and 1) == 0) {
                    var u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    var v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    if (u < 16) u = 16 else if (u > 240) u = 240
                    if (v < 16) v = 16 else if (v > 240) v = 240

                    outBytes[uvIndex++] = u.toByte()
                    outBytes[uvIndex++] = v.toByte()
                }
            }
        }
    }

    private fun loadPhotoBitmap(context: Context, uriString: String): Bitmap? {
        if (uriString.isBlank()) return null
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            null
        }
    }
}
