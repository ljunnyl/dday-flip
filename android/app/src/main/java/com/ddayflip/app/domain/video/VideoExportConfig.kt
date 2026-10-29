package com.ddayflip.app.domain.video

/**
 * 영상 배경음악(BGM) 모드
 */
enum class BgmMode(val title: String, val description: String) {
    DEFAULT_MUSIC_BOX("🎵 오르골 자장가(내장)", "따뜻하고 포근한 아기 오르골 멜로디를 함께 넣어요"),
    CUSTOM_FILE("📂 내 음악 파일 넣기", "내 폰에 저장된 MP3/M4A 음악 파일을 배경음악으로 넣어요"),
    MUTE("🔇 무음", "배경음악 없이 깔끔한 무음 영상으로 만들어요")
}

/**
 * D-day 뱃지 오버레이 위치 및 치수 정보 (Canvas/Paint 렌더링에 필요한 사각형 및 텍스트 좌표).
 * 단위 테스트 및 UI 독립성을 위해 순수 좌표 데이터 클래스로 분리.
 */
data class BadgeRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cornerRadius: Float,
    val textX: Float,
    val textY: Float,
    val textSize: Float
)

/**
 * 캔버스 내 원본 이미지 피팅(Fit Center) 영역 좌표
 */
data class FitCenterRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

/**
 * 플립 비디오 인코딩 및 렌더링 설정
 */
data class VideoExportConfig(
    val width: Int = 1080,
    val height: Int = 1920,
    val frameRate: Int = 30,
    val photoDurationSeconds: Float = 0.6f, // 사진 1장당 노출 시간 (기본 0.6초)
    val badgeBottomMarginRatio: Float = 0.08f, // 영상 하단 여백 비율 (0.08 * height)
    val badgeHeightRatio: Float = 0.045f, // 뱃지 높이 비율
    val badgePaddingHorizontalRatio: Float = 0.04f, // 뱃지 좌우 여백 비율
    val badgeCornerRadiusRatio: Float = 0.012f, // 뱃지 모서리 둥글기 비율
    val badgeTextSizeRatio: Float = 0.024f, // 텍스트 크기 비율
    val badgeBackgroundColor: Int = 0xCC1E1E1E.toInt(), // 반투명 짙은 그레이 배경
    val badgeTextColor: Int = 0xFFFFFFFF.toInt(), // 흰색 텍스트
    val bitRate: Int = 8_000_000, // 8 Mbps
    val iFrameIntervalSeconds: Int = 1, // 1초마다 I-frame
    val bgmMode: BgmMode = BgmMode.DEFAULT_MUSIC_BOX,
    val customBgmUri: String? = null
) {
    /**
     * 사진 1장당 노출 프레임 수 (기본 30fps * 0.6s = 18프레임)
     */
    val framesPerPhoto: Int
        get() = (frameRate * photoDurationSeconds).toInt()

    /**
     * 사진 개수에 따른 총 프레임 수 계산
     */
    fun calculateTotalFrames(photoCount: Int): Int {
        if (photoCount <= 0) return 0
        return photoCount * framesPerPhoto
    }

    /**
     * 사진 개수에 따른 총 영상 재생 시간 (밀리초) 계산
     */
    fun calculateDurationMs(photoCount: Int): Long {
        if (photoCount <= 0) return 0L
        return (photoCount * photoDurationSeconds * 1000).toLong()
    }

    /**
     * D-day 라벨 텍스트의 폭과 높이에 따른 뱃지 사각형 및 텍스트 렌더링 좌표 계산 (하단 중앙 배치)
     */
    fun calculateBadgeRect(textWidth: Float, textHeight: Float): BadgeRect {
        val badgeHeight = height * badgeHeightRatio
        val horizontalPadding = width * badgePaddingHorizontalRatio
        val badgeWidth = textWidth + horizontalPadding * 2f

        val left = (width - badgeWidth) / 2f
        val right = left + badgeWidth
        val bottom = height * (1f - badgeBottomMarginRatio)
        val top = bottom - badgeHeight
        val cornerRadius = height * badgeCornerRadiusRatio

        // 가로 중앙 정렬 및 세로 중앙 정렬
        val textX = left + badgeWidth / 2f
        val textY = top + (badgeHeight + textHeight) / 2f
        val textSize = height * badgeTextSizeRatio

        return BadgeRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            cornerRadius = cornerRadius,
            textX = textX,
            textY = textY,
            textSize = textSize
        )
    }

    /**
     * 하단 D-day 뱃지(badgeRect) 바로 위에 커스텀 자막/스티커 캡슐 배너 영역 좌표를 계산합니다.
     */
    fun calculateSubtitleRect(
        textWidth: Float,
        textHeight: Float,
        badgeRect: BadgeRect
    ): BadgeRect {
        val gap = 16f
        val horizontalPadding = 32f
        val verticalPadding = 16f

        val centerX = (badgeRect.left + badgeRect.right) / 2f
        val bannerWidth = textWidth + horizontalPadding * 2f
        val bannerHeight = textHeight + verticalPadding * 2f

        val left = centerX - bannerWidth / 2f
        val right = centerX + bannerWidth / 2f
        val bottom = badgeRect.top - gap
        val top = bottom - bannerHeight
        val cornerRadius = bannerHeight / 2f

        val textX = centerX
        val textY = top + (bannerHeight + textHeight) / 2f
        val textSize = height * (badgeTextSizeRatio * 0.85f)

        return BadgeRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            cornerRadius = cornerRadius,
            textX = textX,
            textY = textY,
            textSize = textSize
        )
    }

    /**
     * 원본 이미지 크기(srcWidth, srcHeight)를 캔버스(width, height) 중앙에 비율 유지(FIT_CENTER)하여 배치할 좌표 계산
     */
    fun calculateFitCenter(srcWidth: Int, srcHeight: Int): FitCenterRect {
        if (srcWidth <= 0 || srcHeight <= 0) {
            return FitCenterRect(0f, 0f, width.toFloat(), height.toFloat())
        }

        val scale = minOf(
            width.toFloat() / srcWidth.toFloat(),
            height.toFloat() / srcHeight.toFloat()
        )

        val scaledWidth = srcWidth * scale
        val scaledHeight = srcHeight * scale
        val left = (width - scaledWidth) / 2f
        val top = (height - scaledHeight) / 2f

        return FitCenterRect(
            left = left,
            top = top,
            width = scaledWidth,
            height = scaledHeight
        )
    }
}
