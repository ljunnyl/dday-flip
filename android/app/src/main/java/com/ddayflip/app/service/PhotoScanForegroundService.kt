package com.ddayflip.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ddayflip.app.MainActivity
import com.ddayflip.app.core.domain.CalendarPhotoEntry
import com.ddayflip.app.core.domain.CalendarRoi
import com.ddayflip.app.core.domain.CalendarSequenceValidator
import com.ddayflip.app.core.domain.DdayCalculator
import com.ddayflip.app.core.domain.DdayStartRule
import com.ddayflip.app.data.MediaStorePhotoRepository
import com.ddayflip.app.domain.ocr.MlKitDdayScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * 갤러리 사진의 EXIF 및 OCR D-day 분석을 백그라운드에서 끊김 없이 수행하는 포그라운드 서비스
 */
class PhotoScanForegroundService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var scanJob: Job? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val action = intent.action
        if (action == ACTION_CANCEL) {
            cancelCurrentScan()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val startDateStr = intent.getStringExtra(EXTRA_START_DATE)
        val endDateStr = intent.getStringExtra(EXTRA_END_DATE)
        val birthdayStr = intent.getStringExtra(EXTRA_BIRTHDAY)
        val ddayRuleStr = intent.getStringExtra(EXTRA_DDAY_RULE)
        val todayMaxDday = if (intent.hasExtra(EXTRA_TODAY_MAX_DDAY)) {
            intent.getIntExtra(EXTRA_TODAY_MAX_DDAY, 150)
        } else {
            null
        }

        val hasRoi = intent.getBooleanExtra(EXTRA_HAS_ROI, false)
        val calendarRoi = if (hasRoi) {
            CalendarRoi(
                x = intent.getFloatExtra(EXTRA_ROI_X, 0f),
                y = intent.getFloatExtra(EXTRA_ROI_Y, 0f),
                width = intent.getFloatExtra(EXTRA_ROI_W, 1f),
                height = intent.getFloatExtra(EXTRA_ROI_H, 1f)
            )
        } else {
            null
        }

        if (startDateStr == null || endDateStr == null || birthdayStr == null || ddayRuleStr == null) {
            _scanState.value = ScanServiceState.Error("필수 인자가 누락되었습니다.")
            stopSelf()
            return START_NOT_STICKY
        }

        val startDate = LocalDate.parse(startDateStr)
        val endDate = LocalDate.parse(endDateStr)
        val birthday = LocalDate.parse(birthdayStr)
        val ddayRule = DdayStartRule.valueOf(ddayRuleStr)

        startForegroundNotification()

        scanJob?.cancel()
        scanJob = serviceScope.launch {
            runScan(
                startDate = startDate,
                endDate = endDate,
                birthday = birthday,
                ddayRule = ddayRule,
                calendarRoi = calendarRoi,
                todayMaxDday = todayMaxDday
            )
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val notification = buildProgressNotification(
            processed = 0,
            total = 0,
            statusText = "사진 목록 불러오는 중..."
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID_PROGRESS, notification)
        }
    }

    private suspend fun runScan(
        startDate: LocalDate,
        endDate: LocalDate,
        birthday: LocalDate,
        ddayRule: DdayStartRule,
        calendarRoi: CalendarRoi?,
        todayMaxDday: Int?
    ) {
        val repository = MediaStorePhotoRepository(applicationContext)
        val scanner = MlKitDdayScanner()
        val ddayCalculator = DdayCalculator(birthday = birthday, rule = ddayRule)

        try {
            _scanState.value = ScanServiceState.Scanning(
                processed = 0,
                total = 0,
                detectedCount = 0,
                currentDday = null,
                currentStatusText = "갤러리 사진 목록을 불러오는 중..."
            )

            val photos = withContext(Dispatchers.IO) {
                repository.getPhotosBetween(startDate, endDate)
            }
            val total = photos.size

            if (total == 0) {
                val validator = CalendarSequenceValidator()
                val emptyResult = validator.validateSequence(emptyList(), todayMaxDday = todayMaxDday)
                _scanState.value = ScanServiceState.Completed(emptyResult)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            val entries = mutableListOf<CalendarPhotoEntry>()
            var detectedCalendarPhotos = 0

            for ((index, photo) in photos.withIndex()) {
                if (!currentCoroutineContext().isActive) break

                val processed = index + 1

                // 1. 촬영 시간 기반 EXIF D-day 계산 (00~04시 심야 보정 포함)
                val photoDateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(photo.dateTakenMs),
                    ZoneId.systemDefault()
                )
                val calcResult = ddayCalculator.calculate(photoDateTime)
                val expectedDday = calcResult.dday

                var currentStatus = "사진 분석 중 ($processed/$total): EXIF D+$expectedDday"

                // 2. 비트맵 디코딩 및 온디바이스 OCR 스캔 (ROI 전달)
                val bitmap = withContext(Dispatchers.IO) {
                    loadScaledBitmap(applicationContext, photo.contentUri)
                }

                var detectedDday: Int? = null
                if (bitmap != null) {
                    val ocrResult = scanner.scanDday(bitmap, expectedDay = expectedDday, roi = calendarRoi)
                    if (ocrResult != null) {
                        val rawDday = ocrResult.dday
                        // OCR 노이즈 가드: ±3일 초과 편차 시 잡숫자로 간주하여 fallback
                        if (abs(rawDday - expectedDday) <= 3) {
                            detectedDday = rawDday
                            detectedCalendarPhotos++
                            currentStatus = "현재 사진: D+$detectedDday 감지됨 (신뢰도 ${(ocrResult.confidence * 100).toInt()}%)"
                        } else {
                            currentStatus = "현재 사진: OCR 숫자(D+$rawDday) 노이즈 감지 -> EXIF D+$expectedDday 로 대체"
                        }
                    }
                    bitmap.recycle()
                }

                entries.add(
                    CalendarPhotoEntry(
                        id = photo.id.toString(),
                        effectiveDate = calcResult.effectiveDate,
                        exifDday = expectedDday,
                        ocrDday = detectedDday,
                        resolvedDday = detectedDday ?: expectedDday,
                        photoUri = photo.contentUri.toString()
                    )
                )

                _scanState.value = ScanServiceState.Scanning(
                    processed = processed,
                    total = total,
                    detectedCount = detectedCalendarPhotos,
                    currentDday = detectedDday ?: expectedDday,
                    currentStatusText = currentStatus
                )

                notificationManager.notify(
                    NOTIFICATION_ID_PROGRESS,
                    buildProgressNotification(processed, total, currentStatus)
                )
            }

            if (!currentCoroutineContext().isActive) return

            // 3. 전체 시퀀스 스마트 검증
            val validator = CalendarSequenceValidator()
            val result = validator.validateSequence(entries, todayMaxDday = todayMaxDday)

            _scanState.value = ScanServiceState.Completed(result)
            showCompletionNotification(total, detectedCalendarPhotos)
        } catch (e: CancellationException) {
            // 취소 시 상태 초기화
            _scanState.value = ScanServiceState.Idle
        } catch (e: Exception) {
            _scanState.value = ScanServiceState.Error(e.message ?: "사진 분석 중 오류 발생")
        } finally {
            scanner.close()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cancelCurrentScan() {
        scanJob?.cancel()
        _scanState.value = ScanServiceState.Idle
    }

    private fun buildProgressNotification(
        processed: Int,
        total: Int,
        statusText: String
    ): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("D-day 사진 분석 중")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setProgress(total, processed, total == 0)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showCompletionNotification(total: Int, detected: Int) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("D-day 사진 분석 완료")
            .setContentText("총 ${total}장 중 ${detected}장 D-day 달력 인식 완료")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETED, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "D-day 사진 분석 진행률 알림"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanJob?.cancel()
        serviceJob.cancel()
    }

    companion object {
        const val CHANNEL_ID = "dday_scan_channel"
        const val CHANNEL_NAME = "D-day 사진 분석"
        const val NOTIFICATION_ID_PROGRESS = 1001
        const val NOTIFICATION_ID_COMPLETED = 1002

        private const val ACTION_CANCEL = "com.ddayflip.app.action.CANCEL_SCAN"
        private const val EXTRA_START_DATE = "EXTRA_START_DATE"
        private const val EXTRA_END_DATE = "EXTRA_END_DATE"
        private const val EXTRA_BIRTHDAY = "EXTRA_BIRTHDAY"
        private const val EXTRA_DDAY_RULE = "EXTRA_DDAY_RULE"
        private const val EXTRA_TODAY_MAX_DDAY = "EXTRA_TODAY_MAX_DDAY"
        private const val EXTRA_HAS_ROI = "EXTRA_HAS_ROI"
        private const val EXTRA_ROI_X = "EXTRA_ROI_X"
        private const val EXTRA_ROI_Y = "EXTRA_ROI_Y"
        private const val EXTRA_ROI_W = "EXTRA_ROI_W"
        private const val EXTRA_ROI_H = "EXTRA_ROI_H"

        private val _scanState = MutableStateFlow<ScanServiceState>(ScanServiceState.Idle)
        val scanState: StateFlow<ScanServiceState> = _scanState.asStateFlow()

        fun startService(
            context: Context,
            startDate: LocalDate,
            endDate: LocalDate,
            birthday: LocalDate,
            ddayRule: DdayStartRule,
            calendarRoi: CalendarRoi?,
            todayMaxDday: Int?
        ) {
            val intent = Intent(context, PhotoScanForegroundService::class.java).apply {
                putExtra(EXTRA_START_DATE, startDate.toString())
                putExtra(EXTRA_END_DATE, endDate.toString())
                putExtra(EXTRA_BIRTHDAY, birthday.toString())
                putExtra(EXTRA_DDAY_RULE, ddayRule.name)
                if (todayMaxDday != null) {
                    putExtra(EXTRA_TODAY_MAX_DDAY, todayMaxDday)
                }
                if (calendarRoi != null) {
                    putExtra(EXTRA_HAS_ROI, true)
                    putExtra(EXTRA_ROI_X, calendarRoi.x)
                    putExtra(EXTRA_ROI_Y, calendarRoi.y)
                    putExtra(EXTRA_ROI_W, calendarRoi.width)
                    putExtra(EXTRA_ROI_H, calendarRoi.height)
                }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun resetState() {
            _scanState.value = ScanServiceState.Idle
        }

        fun cancelScan(context: Context) {
            val intent = Intent(context, PhotoScanForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            ContextCompat.startForegroundService(context, intent)
            _scanState.value = ScanServiceState.Idle
        }
    }
}

/**
 * 메모리 절약을 위한 스케일 다운 비트맵 디코딩 헬퍼
 */
private fun loadScaledBitmap(context: Context, uri: Uri, targetMaxDim: Int = 1200): Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(stream, null, options)
            val maxDim = maxOf(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxDim / (sampleSize * 2) >= targetMaxDim) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            }
        }
    } catch (e: Exception) {
        null
    }
}
