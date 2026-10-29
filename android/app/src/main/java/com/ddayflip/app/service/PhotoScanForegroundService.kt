package com.ddayflip.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ddayflip.app.MainActivity
import com.ddayflip.app.R
import com.ddayflip.app.core.domain.CalendarPhotoEntry
import com.ddayflip.app.core.domain.CalendarRoi
import com.ddayflip.app.core.domain.CalendarSequenceValidator
import com.ddayflip.app.core.domain.DdayCalculator
import com.ddayflip.app.core.domain.DdayStartRule
import com.ddayflip.app.core.domain.ImageSharpnessEvaluator
import com.ddayflip.app.core.domain.ScanAccuracyMode
import com.ddayflip.app.core.domain.SceneSimilarityEvaluator
import com.ddayflip.app.data.MediaStorePhotoRepository
import com.ddayflip.app.domain.ocr.DdayScanner
import com.ddayflip.app.domain.ocr.MlKitDdayScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs

/**
 * 갤러리 사진의 D-day 달력 인식 및 OCR 처리를 백그라운드에서 안전하게 수행하는 포그라운드 서비스.
 * 앱이 백그라운드로 전환되거나 화면이 꺼져도 OS에 의해 강제 종료되지 않고 끝까지 분석을 완료합니다.
 */
class PhotoScanForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
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
        val accuracyModeStr = intent.getStringExtra(EXTRA_ACCURACY_MODE)
        val accuracyMode = try {
            if (accuracyModeStr != null) ScanAccuracyMode.valueOf(accuracyModeStr) else ScanAccuracyMode.STRICT
        } catch (_: Exception) {
            ScanAccuracyMode.STRICT
        }

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

        val matchSceneSimilarity = intent.getBooleanExtra(EXTRA_MATCH_SCENE_SIMILARITY, false)
        val referencePhotoUriStr = intent.getStringExtra(EXTRA_REFERENCE_PHOTO_URI)

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
        startScanningJob(
            startDate = startDate,
            endDate = endDate,
            birthday = birthday,
            ddayRule = ddayRule,
            todayMaxDday = todayMaxDday,
            calendarRoi = calendarRoi,
            accuracyMode = accuracyMode,
            matchSceneSimilarity = matchSceneSimilarity,
            referencePhotoUriStr = referencePhotoUriStr
        )

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val notification = buildProgressNotification(0, 0, "아기 사진 목록을 불러오는 중...")
        startForeground(NOTIFICATION_ID_PROGRESS, notification)
    }

    private fun startScanningJob(
        startDate: LocalDate,
        endDate: LocalDate,
        birthday: LocalDate,
        ddayRule: DdayStartRule,
        todayMaxDday: Int?,
        calendarRoi: CalendarRoi?,
        accuracyMode: ScanAccuracyMode,
        matchSceneSimilarity: Boolean,
        referencePhotoUriStr: String?
    ) {
        scanJob?.cancel()
        scanJob = serviceScope.launch {
            runPhotoScan(
                startDate = startDate,
                endDate = endDate,
                birthday = birthday,
                ddayRule = ddayRule,
                todayMaxDday = todayMaxDday,
                calendarRoi = calendarRoi,
                accuracyMode = accuracyMode,
                matchSceneSimilarity = matchSceneSimilarity,
                referencePhotoUriStr = referencePhotoUriStr
            )
        }
    }

    private suspend fun runPhotoScan(
        startDate: LocalDate,
        endDate: LocalDate,
        birthday: LocalDate,
        ddayRule: DdayStartRule,
        todayMaxDday: Int?,
        calendarRoi: CalendarRoi?,
        accuracyMode: ScanAccuracyMode,
        matchSceneSimilarity: Boolean,
        referencePhotoUriStr: String?
    ) {
        val scanner: DdayScanner = MlKitDdayScanner()
        val sharpnessEvaluator = ImageSharpnessEvaluator()
        val similarityEvaluator = SceneSimilarityEvaluator()
        try {
            _scanState.value = ScanServiceState.Scanning(
                processed = 0,
                total = 0,
                detectedCount = 0,
                currentDday = 0,
                currentStatusText = "갤러리 사진 탐색을 시작합니다..."
            )

            // 기준 사진 구도 유사도 매칭이 활성화된 경우 기준 특징 벡터 추출
            val referenceFeatureVector: DoubleArray? = if (matchSceneSimilarity && !referencePhotoUriStr.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    try {
                        val refBmp = com.ddayflip.app.util.BitmapUtils.loadOrientedBitmap(
                            applicationContext,
                            Uri.parse(referencePhotoUriStr),
                            400
                        )
                        if (refBmp != null) {
                            val vec = similarityEvaluator.extractFeatureFromBitmap(refBmp)
                            refBmp.recycle()
                            vec
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }
            } else {
                null
            }

            // 1. 미디어스토어에서 대상 기간 사진 조회 (스크린샷 자동 제외됨)
            val repository = MediaStorePhotoRepository(applicationContext)
            val photos = repository.getPhotosBetween(startDate, endDate)
            val total = photos.size

            if (total == 0) {
                _scanState.value = ScanServiceState.Completed(
                    com.ddayflip.app.core.domain.SequenceValidationResult(
                        emptyList(),
                        emptyList(),
                        emptyList()
                    )
                )
                showCompletionNotification(0, 0)
                return
            }

            val ddayCalculator = DdayCalculator(birthday = birthday, rule = ddayRule)
            val entries = mutableListOf<CalendarPhotoEntry>()
            var processed = 0
            var detectedCalendarPhotos = 0
            var lastNotifyTimeMs = 0L

            // 2. 사진별 온디바이스 OCR 및 아기/구도 분석
            for (photo in photos) {
                processed++

                // 정오(12:00) 기준을 넘겨 자정(00:00) 새벽 컷오프로 인한 날짜 하루 밀림(5/27 -> 5/26) 방지
                val calcResult = ddayCalculator.calculate(
                    dateTime = photo.dateTakenLocalDate.atTime(12, 0)
                )
                val expectedDday = calcResult.dday

                var currentStatus = "소중한 아기 사진을 살피는 중..."

                val bitmap = withContext(Dispatchers.IO) {
                    com.ddayflip.app.util.BitmapUtils.loadOrientedBitmap(applicationContext, photo.contentUri, 1200)
                }

                var detectedDday: Int? = null
                var isExplicitCalendarMatch = false
                var sharpness = 0.0
                var skinToneRatio = 0.0
                var sceneSimilarity = 1.0

                if (bitmap != null) {
                    // 선명도 계산 (160x160 그레이스케일 라플라시안 분산)
                    try {
                        val smallW = 160
                        val smallH = 160
                        val smallBmp = Bitmap.createScaledBitmap(bitmap, smallW, smallH, false)
                        val pixels = IntArray(smallW * smallH)
                        smallBmp.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
                        val grayPixels = IntArray(smallW * smallH)
                        for (i in pixels.indices) {
                            val c = pixels[i]
                            val r = (c shr 16) and 0xFF
                            val g = (c shr 8) and 0xFF
                            val b = c and 0xFF
                            grayPixels[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                        }
                        sharpness = sharpnessEvaluator.calculateLaplacianVariance(grayPixels, smallW, smallH)
                        skinToneRatio = similarityEvaluator.calculateWarmSkinToneRatio(pixels, smallW, smallH)
                        if (referenceFeatureVector != null) {
                            val vec = similarityEvaluator.extractGridFeatureVector(pixels, smallW, smallH)
                            sceneSimilarity = similarityEvaluator.calculateCosineSimilarity(referenceFeatureVector, vec)
                        }
                        if (smallBmp != bitmap) smallBmp.recycle()
                    } catch (_: Exception) {}

                    // BABY_FACE_ONLY 모드가 아닐 때만 OCR 우선 탐색 수행 (BABY_FACE_ONLY에서도 달력이 있으면 보너스 인식)
                    var ocrResult = scanner.scanDday(bitmap, expectedDay = expectedDday, roi = calendarRoi)

                    // HIGH_PRECISION 모드에서 ROI 스캔 결과가 없으면 전체 이미지(roi = null)로 2차 폴백 OCR 수행
                    if (accuracyMode == ScanAccuracyMode.HIGH_PRECISION && calendarRoi != null && ocrResult == null) {
                        ocrResult = scanner.scanDday(bitmap, expectedDay = expectedDday, roi = null)
                    }

                    if (ocrResult != null) {
                        val rawDday = ocrResult.dday
                        Log.d("DdayScanner", "Photo #${photo.id}: OCR found D+$rawDday (explicit: ${ocrResult.isExplicit}, expected: $expectedDday, conf: ${ocrResult.confidence})")

                        val effectiveRelaxed = (accuracyMode == ScanAccuracyMode.RELAXED || accuracyMode == ScanAccuracyMode.HIGH_PRECISION)
                        // 유효 D-day 인정 로직:
                        // - 명시적 달력 표기("D+150", "150일", "DAY 150")는 촬영일 기준 3일 이내 오차 허용 (미넘김 감지 지원)
                        // - 단독 숫자는 옷/장난감/배경 숫자 오인식으로 엉뚱한 D+3, D+4에 중복 배치되는 것을 막기 위해
                        //   반드시 rawDday == expectedDday 일 때만 인정!
                        val isValidDday = if (ocrResult.isExplicit) {
                            abs(rawDday - expectedDday) <= 3 || (effectiveRelaxed && abs(rawDday - expectedDday) <= 5)
                        } else {
                            rawDday == expectedDday
                        }

                        if (isValidDday) {
                            detectedDday = rawDday
                            isExplicitCalendarMatch = ocrResult.isExplicit
                            detectedCalendarPhotos++
                            currentStatus = "D+$detectedDday 달력 발견 ✨"
                        } else {
                            Log.w("DdayScanner", "Photo #${photo.id}: D+$rawDday rejected as noise (expected $expectedDday)")
                        }
                    }
                    bitmap.recycle()
                }

                // 구도 유사도 매칭 스위치가 켜져 있으면 유사도 점수(>= 0.82)를 만족하거나 명시적 달력이 확인된 사진만 통과
                val passesSimilarity = if (referenceFeatureVector != null) {
                    sceneSimilarity >= 0.82 || isExplicitCalendarMatch
                } else {
                    true
                }

                val isConfirmed = (detectedDday != null)
                val shouldInclude = passesSimilarity && when (accuracyMode) {
                    ScanAccuracyMode.STRICT -> isConfirmed
                    ScanAccuracyMode.RELAXED -> true
                    ScanAccuracyMode.HIGH_PRECISION -> isConfirmed || sharpness >= 80.0
                    ScanAccuracyMode.BABY_FACE_ONLY -> {
                        // 아기 피부톤 비율이 충분하거나 선명한 아기 사진을 채택
                        skinToneRatio >= 0.06 || sharpness >= 50.0 || isConfirmed
                    }
                }

                if (shouldInclude) {
                    // 같은 날 여러 장 찍은 사진이 단독 숫자 오인식 등으로 다른 날짜(D+3, D+4)에 쪼개져 중복 배치되지 않도록
                    // 달력 미넘김(어제 달력 그대로 D+(expected-1) 찍힌 명시적 달력)인 경우에만 ocrDday를 쓰고 기본은 expectedDday로 정렬 안정화
                    val finalResolvedDday = if (isExplicitCalendarMatch && detectedDday != null && abs(detectedDday - expectedDday) <= 1) {
                        detectedDday
                    } else {
                        expectedDday
                    }

                    // 구도 유사도 및 피부톤 가점을 반영한 종합 품질 점수
                    val effectiveSharpness = sharpness * (0.7 + 0.3 * sceneSimilarity) + (skinToneRatio * 40.0)

                    entries.add(
                        CalendarPhotoEntry(
                            id = photo.id.toString(),
                            effectiveDate = calcResult.effectiveDate,
                            exifDday = expectedDday,
                            ocrDday = detectedDday,
                            resolvedDday = finalResolvedDday,
                            sharpnessScore = effectiveSharpness,
                            photoUri = photo.contentUri.toString(),
                            isCalendarConfirmed = isConfirmed
                        )
                    )
                }

                _scanState.value = ScanServiceState.Scanning(
                    processed = processed,
                    total = total,
                    detectedCount = detectedCalendarPhotos,
                    currentDday = detectedDday ?: expectedDday,
                    currentStatusText = currentStatus,
                    currentPhotoUri = photo.contentUri.toString()
                )

                // 알림 IPC 스로틀링: 500ms 이상 경과했거나 마지막 사진일 때만 호출하여 시스템 부하 방지
                val now = System.currentTimeMillis()
                if (now - lastNotifyTimeMs >= 500L || processed == total) {
                    lastNotifyTimeMs = now
                    notificationManager.notify(
                        NOTIFICATION_ID_PROGRESS,
                        buildProgressNotification(processed, total, currentStatus)
                    )
                }
            }

            // 3. 전체 시퀀스 스마트 검증
            val validator = CalendarSequenceValidator()
            val filteredEntries = validator.filterByAccuracyMode(entries, accuracyMode)
            val result = validator.validateSequence(filteredEntries, todayMaxDday = todayMaxDday)

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
            .setContentTitle("아기 사진 찾는 중")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setProgress(total, processed, total == 0)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
            .setContentTitle("아기 사진 선별 완료 ✨")
            .setContentText("총 ${total}장 중 소중한 사진을 찾았습니다.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_COMPLETED, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "D-day 사진 분석",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "갤러리 사진에서 D-day 달력을 찾는 백그라운드 작업 알림"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "channel_dday_scan"
        const val NOTIFICATION_ID_PROGRESS = 1001
        const val NOTIFICATION_ID_COMPLETED = 1002

        const val ACTION_CANCEL = "com.ddayflip.app.ACTION_CANCEL_SCAN"
        const val EXTRA_START_DATE = "com.ddayflip.app.EXTRA_START_DATE"
        const val EXTRA_END_DATE = "com.ddayflip.app.EXTRA_END_DATE"
        const val EXTRA_BIRTHDAY = "com.ddayflip.app.EXTRA_BIRTHDAY"
        const val EXTRA_DDAY_RULE = "com.ddayflip.app.EXTRA_DDAY_RULE"
        const val EXTRA_TODAY_MAX_DDAY = "com.ddayflip.app.EXTRA_TODAY_MAX_DDAY"
        const val EXTRA_ACCURACY_MODE = "com.ddayflip.app.EXTRA_ACCURACY_MODE"
        const val EXTRA_HAS_ROI = "com.ddayflip.app.EXTRA_HAS_ROI"
        const val EXTRA_ROI_X = "com.ddayflip.app.EXTRA_ROI_X"
        const val EXTRA_ROI_Y = "com.ddayflip.app.EXTRA_ROI_Y"
        const val EXTRA_ROI_W = "com.ddayflip.app.EXTRA_ROI_W"
        const val EXTRA_ROI_H = "com.ddayflip.app.EXTRA_ROI_H"
        const val EXTRA_MATCH_SCENE_SIMILARITY = "com.ddayflip.app.EXTRA_MATCH_SCENE_SIMILARITY"
        const val EXTRA_REFERENCE_PHOTO_URI = "com.ddayflip.app.EXTRA_REFERENCE_PHOTO_URI"

        private val _scanState = MutableStateFlow<ScanServiceState>(ScanServiceState.Idle)
        val scanState: StateFlow<ScanServiceState> = _scanState.asStateFlow()

        fun startScan(
            context: Context,
            startDate: LocalDate,
            endDate: LocalDate,
            birthday: LocalDate,
            ddayRule: DdayStartRule,
            todayMaxDday: Int? = null,
            calendarRoi: CalendarRoi? = null,
            accuracyMode: ScanAccuracyMode = ScanAccuracyMode.STRICT,
            matchSceneSimilarity: Boolean = false,
            referencePhotoUri: String? = null
        ) {
            val intent = Intent(context, PhotoScanForegroundService::class.java).apply {
                putExtra(EXTRA_START_DATE, startDate.toString())
                putExtra(EXTRA_END_DATE, endDate.toString())
                putExtra(EXTRA_BIRTHDAY, birthday.toString())
                putExtra(EXTRA_DDAY_RULE, ddayRule.name)
                putExtra(EXTRA_ACCURACY_MODE, accuracyMode.name)
                putExtra(EXTRA_MATCH_SCENE_SIMILARITY, matchSceneSimilarity)
                if (!referencePhotoUri.isNullOrBlank()) {
                    putExtra(EXTRA_REFERENCE_PHOTO_URI, referencePhotoUri)
                }
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

        fun startService(
            context: Context,
            startDate: LocalDate,
            endDate: LocalDate,
            birthday: LocalDate,
            ddayRule: DdayStartRule,
            calendarRoi: CalendarRoi?,
            todayMaxDday: Int?,
            accuracyMode: ScanAccuracyMode = ScanAccuracyMode.STRICT,
            matchSceneSimilarity: Boolean = false,
            referencePhotoUri: String? = null
        ) {
            startScan(
                context = context,
                startDate = startDate,
                endDate = endDate,
                birthday = birthday,
                ddayRule = ddayRule,
                todayMaxDday = todayMaxDday,
                calendarRoi = calendarRoi,
                accuracyMode = accuracyMode,
                matchSceneSimilarity = matchSceneSimilarity,
                referencePhotoUri = referencePhotoUri
            )
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
