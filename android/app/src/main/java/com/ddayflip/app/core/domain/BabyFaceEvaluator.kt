package com.ddayflip.app.core.domain

import kotlin.math.abs

/**
 * 사용자가 등록한 '우리 아기 기준 사진 1장'에서 추출한 아기 얼굴 고유 프로필.
 *
 * @param faceFeatureVector 얼굴 영역 크롭 이미지의 4x4 그리드 색상/루마 특징 벡터
 * @param faceWidthToHeightRatio 얼굴 가로/세로 비율 (아기는 0.82~1.05 부근으로 둥글고, 성인은 상대적으로 좁고 긺)
 * @param eyeToMouthRatio 얼굴 높이 대비 눈~입 수직 거리 비율 (아기는 이마 비중이 커서 0.28~0.40 수준)
 */
data class ReferenceBabyProfile(
    val faceFeatureVector: DoubleArray,
    val faceWidthToHeightRatio: Float = 0.90f,
    val eyeToMouthRatio: Float = 0.35f
)

/**
 * 온디바이스 ML Kit Face Detection이 사진 1장에서 검출한 개별 얼굴 후보 정보.
 */
data class DetectedFaceCandidate(
    val boundingBoxAreaRatio: Float,
    val centerOffsetRatio: Float,
    val widthToHeightRatio: Float,
    val eyeToMouthRatio: Float = 0.35f,
    val leftEyeOpenProb: Float = 0.8f,
    val rightEyeOpenProb: Float = 0.8f,
    val smilingProb: Float = 0.5f,
    val faceFeatureVector: DoubleArray = DoubleArray(0)
)

/**
 * 사진 1장의 아기 얼굴 판정 및 품질 점수 결과.
 */
data class BabyFaceEvaluationResult(
    val isAcceptedBabyPhoto: Boolean,
    val totalScore: Double,
    val matchedBabySimilarity: Double
)

/**
 * '아기 얼굴만 골라 찾기(BABY_FACE_ONLY)' 모드에서
 * 1) 기준 아기 사진과의 얼굴 특징 유사도 & 아기 얼굴 비율(이목구비 비례)을 통해 성인 사진을 배제하고,
 * 2) 눈 뜸·미소·화면 중앙 위치·선명도를 종합해 그날 가장 예쁜 아기 얼굴 대표 사진을 선정하는 도메인 평가기.
 */
class BabyFaceEvaluator(
    private val similarityEvaluator: SceneSimilarityEvaluator = SceneSimilarityEvaluator()
) {

    fun evaluatePhotoFaces(
        faces: List<DetectedFaceCandidate>,
        referenceProfile: ReferenceBabyProfile?,
        sharpness: Double
    ): BabyFaceEvaluationResult {
        if (faces.isEmpty() || sharpness < 15.0) {
            return BabyFaceEvaluationResult(
                isAcceptedBabyPhoto = false,
                totalScore = 0.0,
                matchedBabySimilarity = 0.0
            )
        }

        var bestFaceScore = -1.0
        var bestSimilarity = 0.0
        var anyAccepted = false

        // 성인 여러 명이 찍힌 단체 사진 감점
        val multiFacePenalty = if (faces.size >= 3) 20.0 else 0.0

        for (face in faces) {
            // 너무 작게 찍힌 배경 행인 얼굴(면적 1.2% 미만)은 제외
            if (face.boundingBoxAreaRatio < 0.012f) continue

            // 1. 아기 얼굴 형태학(Morphology) 점수:
            // 아기는 두상이 둥글고(width/height >= 0.78), 눈~입 거리가 얼굴 전체 대비 짧음(eyeToMouthRatio <= 0.42)
            val isAdultElongatedFace = face.widthToHeightRatio < 0.74f && face.eyeToMouthRatio > 0.44f
            val babyProportionBonus = if (isAdultElongatedFace) {
                -25.0
            } else {
                val roundnessScore = (1.0 - abs(face.widthToHeightRatio - 0.90f).coerceIn(0f, 0.4f)) * 18.0
                val eyeMouthScore = (1.0 - abs(face.eyeToMouthRatio - 0.35f).coerceIn(0f, 0.25f)) * 15.0
                roundnessScore + eyeMouthScore
            }

            // 2. 기준 아기 사진이 있을 때 얼굴 특징 유사도 및 비례 일치도 검증 (성인/타인 배제 핵심)
            val faceSim = if (referenceProfile != null &&
                referenceProfile.faceFeatureVector.isNotEmpty() &&
                face.faceFeatureVector.isNotEmpty()
            ) {
                similarityEvaluator.calculateCosineSimilarity(
                    referenceProfile.faceFeatureVector,
                    face.faceFeatureVector
                )
            } else {
                0.85
            }

            val proportionDiffFromRef = if (referenceProfile != null) {
                abs(face.widthToHeightRatio - referenceProfile.faceWidthToHeightRatio) +
                    abs(face.eyeToMouthRatio - referenceProfile.eyeToMouthRatio)
            } else {
                0f
            }

            val passesReferenceCheck = if (referenceProfile != null) {
                faceSim >= 0.78 && !isAdultElongatedFace && proportionDiffFromRef <= 0.28f
            } else {
                !isAdultElongatedFace
            }

            if (!passesReferenceCheck) continue

            // 3. 표정 점수 (눈을 뜨고 있을수록, 웃고 있을수록 가점)
            val eyeOpenAvg = ((face.leftEyeOpenProb.coerceIn(0f, 1f) + face.rightEyeOpenProb.coerceIn(0f, 1f)) / 2.0)
            val smileScore = face.smilingProb.coerceIn(0f, 1f).toDouble()
            val expressionBonus = (eyeOpenAvg * 22.0) + (smileScore * 18.0)

            // 4. 구도 점수 (중앙에 가깝고 얼굴이 적당히 큼직할수록 가점)
            val centerBonus = (1.0 - face.centerOffsetRatio.coerceIn(0f, 1f)) * 12.0
            val sizeBonus = face.boundingBoxAreaRatio.coerceIn(0.01f, 0.45f) * 35.0

            val candidateTotal = (sharpness * 0.45) +
                (faceSim * 40.0) +
                babyProportionBonus +
                expressionBonus +
                centerBonus +
                sizeBonus -
                multiFacePenalty

            anyAccepted = true
            if (candidateTotal > bestFaceScore) {
                bestFaceScore = candidateTotal
                bestSimilarity = faceSim
            }
        }

        return if (anyAccepted) {
            BabyFaceEvaluationResult(
                isAcceptedBabyPhoto = true,
                totalScore = bestFaceScore.coerceAtLeast(1.0),
                matchedBabySimilarity = bestSimilarity
            )
        } else {
            BabyFaceEvaluationResult(
                isAcceptedBabyPhoto = false,
                totalScore = 0.0,
                matchedBabySimilarity = bestSimilarity
            )
        }
    }
}
