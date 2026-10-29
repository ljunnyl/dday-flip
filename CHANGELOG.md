# Changelog

이 프로젝트의 변경 내용을 기록한다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 시맨틱 버전을 따른다.

## [Unreleased]

### Added
- **v3.5 피그마 6대 화면 UX 리뉴얼 & 온디바이스 인코딩/스캔 최적화 전면 구현**
  - **테마 3종 & 야간 수유등 다크 모드 (`Theme.kt`, `MainActivity.kt`, `StartScreen.kt`)**:
    - `AppVisualTheme` (`WARM_COTTON` ☁️ 웜 코튼, `MINIMAL_WHITE` 🤍 미니멀 화이트, `DARK_ROOM` 🌙 수유등 다크) 도입 및 홈 상단 원터치 전환 칩 제공
  - **R01 최근 만든 영상 홈 플레이어 카드 (`StartScreen.kt`)**:
    - `cacheDir` 내 최근 생성된 `dday_flip_*.mp4` 감지 시 첫 프레임 썸네일 추출, 앱 내 `VideoView` 다이얼로그 `[▶ 바로 재생]` 및 `[📤 공유하기]` 지원
  - **고정밀 다중 패스 분석 모드 (`ScanAccuracyMode.HIGH_PRECISION`, `PhotoScanForegroundService.kt`)**:
    - ROI 지정 스캔 실패 시 전체 이미지(`roi = null`)로 2차 폴백 OCR을 수행하여 우드블록/필기체 달력 인식률 극대화 및 알림 IPC 500ms 스로틀링 적용
  - **R03 폴라로이드 라이브 검사 뷰파인더 릴 (`ScanScreen.kt`, `PhotoScanState.kt`)**:
    - `ScanServiceState.Scanning`에 `currentPhotoUri`를 포함하여 스캔 중 현재 분석 사진을 `200.dp x 220.dp` 폴라로이드 카드와 `D+N 탐색 중` 캡슐 배지로 실시간 표시
  - **R04 갤럭시 스타일 패스트 스크롤러 & 몰아찍기 스마트 분배 & 스티커/자막 꾸미기 (`ReviewScreen.kt`, `CalendarSequenceValidator.kt`, `VideoExportConfig.kt`, `DdayVideoRenderer.kt`)**:
    - 우측 고정 버튼 기둥을 제거하고 스크롤/드래그 시 반응하는 슬림 스크롤 핸들 + 플로팅 날짜 말풍선(`D+N | YYYY-MM-DD`)으로 교체
    - `distributeBurstPhotos` 구현 및 결측일 발생 시 몰아찍은 잉여 사진으로 빈 날짜를 채우는 원터치 액션 배너/버튼 제공
    - 타임라인 카드별 `[✨ 꾸미기]` 다이얼로그(육아 스티커 8종 + 한 줄 축하 자막) 및 비디오 합성 시 D-day 뱃지 상단 반투명 캡슐 배너 오버레이(`calculateSubtitleRect`, `drawCustomOverlay`) 구현
  - **R05 실시간 필름 렌더링 릴 & NV12 버퍼 재사용 (`ExportingScreen.kt`, `DdayVideoRenderer.kt`, `VideoExporter.kt`)**:
    - 인코딩 중인 이전/현재/다음 실제 사진 썸네일을 상단 필름 릴 프레임에 실시간 표시하고, `convertBitmapToNv12`에서 `reusableArgbBuffer`를 재사용하여 매 프레임 `IntArray` 신규 할당(GC 폭증) 원천 차단
  > 💡 **의도 (Why & How):** 야간 육아 환경의 눈부심을 방지하는 수유등 다크 모드와 라이브 뷰파인더/필름 릴로 대기 체감 시간을 줄이고, 몰아찍기 자동 분배 및 스티커·자막 오버레이, 인코더 메모리 재사용으로 감성 완성도와 런타임 안정성을 동시에 확보.

- **R00 감성 스플래시 화면 및 스칸디나비안 우드 앱 아이콘 적용 (v3.0 Visuals)**
  - **스마트폰 앱 아이콘 리뉴얼**:
    - 북유럽 미니멀 자작나무 원목 플립 캘린더 디자인 적용 (`mipmap-*` 전 해상도 mdpi~xxxhdpi 및 Android 8.0+ Adaptive Icon 완비)
    - 배경 크림 베이지(`#F6F3EC`)와 통일감 있는 어댑티브 아이콘 규격 구현
  - **R00 감성 스플래시 화면 (`SplashScreen.kt`)**:
    - 킨포크 크림 린넨 플랫레이, D+100 원목 달력 및 아기 니트 덧신 감성 비주얼 적용
    - 앱 기동 시 1.8초간 부드러운 페이드인 후 메인 홈 자동 진입 (화면 탭 시 즉시 건너뛰기 지원)
    - 상단 "민서의 D-Day 넘김" 타이틀 및 하단 100% 온디바이스 안심 안내 배치
  > 💡 **의도 (Why & How):** 첫인상에서 육아 감성과 브랜드 일체감을 부여하고, 런처 아이콘부터 진입 스플래시까지 따뜻한 원목·린넨 톤앤매너로 시각 경험 완성.

### Added
- **엉뚱한 사진 원천 차단 알고리즘 및 2트랙 하이브리드 UX 구현 (v2.5)**
  - `ScanAccuracyMode` (엄격 모드 / 관대 모드) 도입:
    - **엄격 모드 (Strict)**: OCR 달력 명시적 포맷(`D+`, `일`) 및 높은 신뢰도를 갖춘 진짜 달력 사진만 채택, 비달력 일상 사진(발, 장난감, 음식, 스크린샷 등) 완전 배제
    - **관대 모드 (Relaxed)**: 달력 없는 날은 당일 촬영된 가장 선명한 아기 사진으로 영상 공백을 스마트 보충
  - **시작 화면 2트랙 진입**:
    - 트랙 ① `[✨ AI 자동 찾기]`: 전체 갤러리 백그라운드 탐색 (엄격/관대 선택)
    - 트랙 ② `[🖼️ 직접 사진 고르기]`: Android 시스템 `PickMultipleVisualMedia(150)` 연동으로 원하는 사진을 콕콕 집어 즉시 타임라인 직행
  - **검수 화면(ReviewScreen) 1초 인스턴트 사진 교체 바텀시트**:
    - 타임라인에서 이상한 사진 터치 시 `getPhotosOnDate` 범위 쿼리로 **동일 날짜 사진들이 최상단 3열 그리드로 즉시 노출**
    - 원하는 컷 탭 즉시 1-tap 맞교환 및 햅틱 피드백 적용
  - `ImageSharpnessEvaluator` 라플라시안 분산 기반 실제 선명도 측정 연동 및 달력 확인 사진 최우선 선별 알고리즘 구현
  > 💡 **의도 (Why & How):** 온디바이스 스캔 시 달력 없는 사진이 fallback으로 무차별 투입되던 결함을 차단하고, 부모가 원하는 사진을 직접 고르거나 1초 만에 손쉽게 교체할 수 있는 사용자 통제권을 확립.

### Fixed
- `VideoExporter`: 영상 인코딩 95% 교착 상태(Deadlock) 버그 수정
  - 인코더 EOS 플래그 전달 후 `drainEncoder` 무한 루프에서 일부 디바이스 칩셋이 EOS 출력 플래그를 지연/미반환할 때 `emptyTries` 타임아웃(800ms) 안전 탈출 로직 적용
  - 인코딩 정상 완료 즉시 `onProgress(1.0f)` 호출 및 파일 정상 반환으로 완료 화면(`CompleteScreen`) 진입 보장

### Changed
- `StartScreen`: 기준 사진 등록 UI 고도화
  - 민서 생일 기본값 `2026.05.27` 영구 고정
  - 기준 사진 썸네일 크롭 제거 및 480dp 대화면(`ContentScale.Fit`) 확장
  - 달력 바운딩 박스 가로/세로 영역 조절 슬라이더 탑재로 미세 조정 지원
- 배포 및 테스트 파이프라인
  - Firebase App Distribution 기반 스마트폰 무선 배포 자동화 구축 (`couple-time-6b2ea`)
  - 운영 문서화: `docs/firebase_distribution.md` 작성
  - v3.0 실시간 스캔 릴, 수동 갤러리 선택, 다크모드 기획안 대시보드(HTML) 및 피그마 3종 디자인 구축

### Added
- 리뉴얼 v2.0 감성 육아 UX 및 1일 1장 스마트 액션 플로우 구현
  - `CalendarSequenceValidator`: `selectBestPhotoPerDay` 구현 — 하루 여러 장 촬영 사진 중 `sharpnessScore` 최고 선명도 1장 자동 선별하여 영상 중복 투입 방지
  - `StartScreen`:
    - R01 감성 첫 화면 (홈 & 온보딩) 추가: 로고, 비주얼 히어로 카드, `[디데이 영상 만들기 시작]` CTA, 100% 온디바이스 안심 배지
    - R02 기준 등록 화면 개선: 텍스트 상자 대신 **실제 침대/달력 사진 비트맵 썸네일** + **AI 인식 성공 주황색 바운딩 박스(ROI)** 시각 하이라이트 및 `[다른 사진으로 다시 고르기]` 지원
  - `ScanScreen`: 기계적 디버그 수치 배제, 3.5초 주기 감성 롤링 카피 (*"우리 아기 예쁜 사진을 쏙쏙 찾고 있어요 ✨"*), 실시간 잔여 시간(ETA: `약 X분 X초 남았어요`), 백그라운드 안내 배너 적용
  - `ReviewScreen`:
    - 우측 플로팅 **날짜 고속 점프 스크러버** (`오늘`, `150`, `100`, `50`, `D+1`) 터치 즉시 스크롤 지원 (2천 장 스크롤 피로 해결)
    - **원터치 해결 액션 카드**: 달력 미넘김 의심 시 `[D+N으로 쓸게요]` / `[이 날은 뺄게요]`, 결측일 감지 시 `[+ 직접 추가]` / `[건너뛰기]` 버튼 제공
    - 1일 1장 최고 선명도 자동 선별 타임라인 제공
  - `ExportingScreen` (R05 - 신규): 검수 후 영상 인코딩 전용 화면 구축 — 필름 릴 프레임 애니메이션, 감성 롤링 카피, 실시간 남은 시간(ETA) 및 백그라운드 포그라운드 인코딩 안내
  - `CompleteScreen`: 인스타그램 릴스 공유 / 카카오톡 전송 Intent 연동, 갤러리 저장 완료 토스트, 재편집 및 홈으로 바로가기 버튼 추가
  - `MainActivity`: `START` ➜ `SCAN` ➜ `REVIEW` ➜ `EXPORTING` ➜ `COMPLETE` 5단계 화면 상태 머신 전환 연동
  - TDD 단위 테스트: `test selectBestPhotoPerDay picks highest sharpness per day and deduplicates` 통과 (총 45개 단위 테스트 100% 통과)
  > 💡 **의도:** 피그마 v2.0 사용자 피드백을 전면 반영하여 데이터 중심의 딱딱한 화면을 따뜻한 감성 육아 UX로 개편하고, 2천 장 중복 투입 방지 및 날짜 점프 스크러버/원터치 액션으로 조작 편의성을 대폭 향상.

- 백그라운드 사진 분석 포그라운드 서비스(`PhotoScanForegroundService`) 및 실시간 상태 모델(`ScanServiceState`) 구현
  - `AndroidManifest.xml`: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` 권한 및 `PhotoScanForegroundService` 선언
  - `PhotoScanState.kt`: `ScanServiceState` (Idle, Scanning, Completed, Error) 상태 정의 및 진행률 percentage 계산
  - `PhotoScanForegroundService`: AndroidX NotificationCompat 기반 실시간 프로그레스 알림(ID 1001), 완료 알림(ID 1002) 연결 및 Coroutine 기반 백그라운드 OCR/EXIF 분석 및 취소/예외 처리
  - `ScanScreen.kt` & `MainActivity.kt`: 포그라운드 서비스 StateFlow 관찰 및 Reconnection, 백그라운드 전환 후 복귀 시 진행 상태 즉시 복원, Android 13+ 알림 런타임 권한 요청
  - `PhotoScanStateTest`: 상태 모델 전이, percentage 비율 계산, resetState 동작 TDD 단위 테스트 통과
  > 💡 **의도:** 대용량 사진 분석 중 사용자가 홈 화면으로 나가거나 다른 앱을 사용하더라도 OS의 백그라운드 프로세스 종료(Kill) 위험 없이 끊김 없이 온디바이스 OCR 분석을 완료하고, 진행 상태를 실시간 알림창으로 확인 및 언제든 복귀할 수 있도록 지원.

- 실제 넘김 영상(MP4) H.264 인코딩 생성 및 미디어스토어 갤러리 저장/공유 파이프라인
- 온디바이스(Google ML Kit) D-day 숫자 자동 인식 엔진 및 실시간 스캔 UI 연동
- 갤러리 사진 접근 권한 및 로컬 미디어스토어(MediaStore) 불러오기 연동
