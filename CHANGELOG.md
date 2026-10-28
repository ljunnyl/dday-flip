# Changelog

이 프로젝트의 변경 내용을 기록한다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 시맨틱 버전을 따른다.

## [Unreleased]

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
