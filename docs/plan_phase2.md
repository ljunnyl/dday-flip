# [구현 계획서] 디데이 넘김(dday_flip) 2단계: 안드로이드(갤럭시) 앱 개발

## 1. 비즈니스적 배경 및 의도 (Background & Intent)

### 1.1 해결하고자 하는 문제 (Why)
- **부모의 현실**: D+10~D+150 무렵 매일 D-day 달력과 함께 아기 사진을 찍지만, 수천 장의 사진첩 속에 흩어져 있어 하루 한 장씩 골라 성장 영상을 만드는 일이 막막함.
- **기존 앱의 한계**: 대다수 성장 영상 앱은 '앱 설치 이후 전용 카메라로 촬영'할 것을 강제함. 늦게 앱을 설치한 부모는 지난 사진을 수동으로 일일이 골라야 함.

### 1.2 핵심 가치 & 신뢰 포인트
> **"앱을 늦게 깔아도, 갤러리에 흩어진 지난 매일 사진을 알아서 찾아 줄 세워 영상으로 만들어 준다."**
- **100% 온디바이스 프라이버시**: 아기 얼굴, 생일, 사진 데이터는 기기 외부 서버로 1바이트도 유출되지 않음 (`INTERNET` 권한 배제).

---

## 2. 기술 스택 및 아키텍처 결정

| 영역 | 기술 스택 | 선정 이유 |
|---|---|---|
| **언어 & UI** | **Kotlin 2.x + Jetpack Compose** | 선언형 UI(피그마 일치), 60fps 부드러운 전환, AndroidX 최적화 |
| **아키텍처** | **Clean Architecture + MVI/MVVM** | 단방향 데이터 흐름, 비즈니스 로직과 UI 완벽 분리, 철저한 로컬 유닛 테스트 |
| **온디바이스 비전** | **Google ML Kit Text Recognition v2** | 갤럭시 NPU 하드웨어 가속, 100% 로컬 무료, Thin 번들로 앱 용량 1MB 미만 |
| **비디오 렌더러** | **Jetpack Media3 Transformer** | 1080×1920 세로 릴스 규격, H.264/AAC 30fps CFR 무손실 인코딩, OOM 방지 |
| **미디어 쿼리** | **Android ContentResolver + MediaStore** | 생일 기준 `DATE_TAKEN` 날짜 슬라이싱으로 1만 장 갤러리를 10초 내 압축 탐색 |
| **로컬 영속성** | **Room DB + DataStore** | 아기 생일 및 D-day 계산 규칙(D+0/D+1) 로컬 암호화 저장 |

---

## 3. 2단계 프로젝트 구조 (`android/`)

```text
android/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml (INTERNET 권한 배제)
│       │   ├── java/com/ddayflip/app/
│       │   │   ├── core/
│       │   │   │   ├── vision/          # ML Kit 기반 달력 감지 & OCR 래퍼
│       │   │   │   ├── media/           # MediaStore 날짜 슬라이싱 쿼리 & Media3 렌더러
│       │   │   │   ├── domain/          # D-day 정렬, 선명도 평가, 예외 보정(미넘김/결측일)
│       │   │   │   └── data/            # Room DB (프로필, 생일) & Preferences
│       │   │   ├── ui/
│       │   │   │   ├── screens/
│       │   │   │   │   ├── StartScreen.kt       # 01. 시작 & 생일 입력
│       │   │   │   │   ├── ScanScreen.kt        # 02. 온디바이스 스캔 & 진행률
│       │   │   │   │   ├── ReviewScreen.kt      # 03. 타임라인 & 3초 스마트 검수
│       │   │   │   │   └── CompleteScreen.kt    # 04. 넘김 영상 완성 & 감상
│       │   │   │   ├── theme/           # 내추럴 웜톤 컬러 팔레트 & 폰트
│       │   │   │   └── components/      # 재사용 UI (DdayBadge, FlipCard 등)
│       │   │   └── MainActivity.kt
│       │   └── res/
│       └── test/                        # 로컬 JVM 단위 테스트 (순수 로직)
│           └── java/com/ddayflip/app/
│               ├── domain/DdayCalculatorTest.kt
│               ├── domain/ImageSharpnessTest.kt
│               └── domain/CalendarSequenceTest.kt
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 4. 엄격한 TDD 검증 계획 (Strict TDD)

실패하는 테스트를 먼저 작성하고 구현을 통과시키는 원칙을 준수:

1. **D-day 계산 및 날짜 매핑 단위 테스트 (`DdayCalculatorTest`)**
   - 생일(2024.05.27) 기준 EXIF 촬영 시각과 D+0/D+1 규칙에 따른 정확한 일수 계산 검증.
   - 자정 이후(00:00~04:00) 심야 촬영 사진의 전날 귀속 로직 검증.
2. **달력 시퀀스 및 예외 처리 단위 테스트 (`CalendarSequenceTest`)**
   - 미넘김 감지: 이틀 연속 동일한 D-day 검출 시 확인 플래그 생성 검증.
   - 결측일 감지: 누락된 일자에 대해 부드러운 전이 메타데이터 생성 검증.
   - 노이즈 텍스트 필터링: 유효 범위(`[생일기준일수 ± 2]`) 밖 텍스트 자동 배제 검증.
3. **선명도 평가 단위 테스트 (`ImageSharpnessTest`)**
   - 연사 사진 중 Laplacian 분산 점수가 가장 높은 1장 자동 선택 검증.

---

## 5. 단계별 실행 마일스톤

- [ ] **마일스톤 1: 안드로이드 프로젝트 베이스라인 구축**
  - `android/` Gradle KTS 멀티모듈/단일앱 프로젝트 뼈대 및 의존성 정의
  - Room DB 및 권한 설정 (`AndroidManifest.xml`)
- [ ] **마일스톤 2: 코어 비즈니스 로직 TDD 구현**
  - 실패하는 유닛 테스트 3종 작성 -> 로직 구현 -> 테스트 전건 통과
- [ ] **마일스톤 3: 온디바이스 비전 & 미디어 파이프라인 연동**
  - ML Kit Text Recognition 연동 래퍼 구현
  - Media3 Transformer 영상 렌더링 파이프라인 구현
- [ ] **마일스톤 4: Compose UI 4개 화면 렌더링**
  - 피그마 「디데이사진」 시안 1:1 매칭 Compose 화면 작성
