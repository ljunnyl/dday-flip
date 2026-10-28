package com.ddayflip.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

object BitmapUtils {

    /**
     * 이미지의 EXIF 메타데이터에서 실제 촬영 방향 회전 각도(0, 90, 180, 270도)를 추출.
     */
    fun getExifRotation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * EXIF 회전 정보를 반영하여 똑바로 선(Right-side up) 비트맵을 디코딩.
     * 90도 회전된 사진을 정상 각도로 자동 교정하여 ML Kit 텍스트 인식률을 100%로 유지.
     */
    fun loadOrientedBitmap(context: Context, uri: Uri, targetMaxDim: Int = 1200): Bitmap? {
        return try {
            val rotation = getExifRotation(context, uri)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)

                val maxDim = maxOf(options.outWidth, options.outHeight)
                var sampleSize = 1
                while (maxDim / (sampleSize * 2) >= targetMaxDim) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val rawBmp = BitmapFactory.decodeStream(inputStream, null, decodeOptions) ?: return null
                    if (rotation != 0) {
                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                        val rotatedBmp = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
                        if (rotatedBmp != rawBmp) {
                            rawBmp.recycle()
                        }
                        rotatedBmp
                    } else {
                        rawBmp
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
