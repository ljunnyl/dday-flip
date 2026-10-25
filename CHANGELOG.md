# Changelog

이 프로젝트의 변경 내용을 기록한다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 시맨틱 버전을 따른다.

## [Unreleased]

### Added
- 온디바이스(Google ML Kit) D-day 숫자 자동 인식 엔진 및 실시간 스캔 UI 연동
  - DdayTextParser: D+xxx, D-xxx, D xxx, xxx일, 단독 숫자 등 다양한 달력 표기 파싱, 연도/시간/해상도 노이즈 필터링, expectedDay 거리 가중치 기반 신뢰도(Confidence) 산출 순수 Kotlin 로직 구현
  - MlKitDdayScanner: Google ML Kit TextRecognition 온디바이스 텍스트 인식 엔진 연동, 상단 35% 타일링 우선 감지(고신뢰도 조기 반환) 및 전체 영역 fallback 전략 적용, 코루틴 비동기 suspend 처리
  - ScanScreen & MainActivity: MediaStore 사진 순차 탐색, EXIF D-day 계산 및 온디바이스 OCR 스캔 실시간 진행률(0~100%), 감지된 D-day 상태 표시 및 완료 후 ReviewScreen 연동
  - DdayTextParserTest: 명시적 패턴, 줄바꿈/다중 줄, 노이즈 필터링, expectedDay 최적 후보 선별, 실패 케이스 등 TDD 단위 테스트 추가 및 검증 완료
  > 💡 **의도:** 외부 네트워크 전송 없이 기기 내부 프로세서만으로 아기 D-day 달력 숫자를 고속·고정밀 인식하고 실시간 진행 상태를 부모 사용자에게 직관적으로 피드백.
- 갤러리 사진 접근 권한 및 로컬 미디어스토어(MediaStore) 불러오기 연동
  - GalleryPhoto: 사진 ID, Content URI, 촬영일시(epoch/LocalDate), 가로세로 규격, 회전 각도 및 가로 판별 메타데이터 모델
  - PhotoRepository & MediaStorePhotoRepository: MediaStore Images.Media.EXTERNAL_CONTENT_URI 기반 특정 날짜 구간(LocalDate 시작~종료) 비동기 쿼리 및 매핑 구현체
  - StartScreen: Android 13+(READ_MEDIA_IMAGES) 및 레거시(READ_EXTERNAL_STORAGE) 런타임 권한 요청 런처 및 거부 시 안내 처리
  - ScanScreen & MainActivity: 생일 기준 150일 탐색 범위 설정 및 MediaStore 연동 실시간 스캔 프로그레스 연결
  - MediaStorePhotoRepositoryTest: 날짜 범위 변환, 윤년 계산, Selection 쿼리 매핑 및 데이터 모델 단위 테스트 6종 추가 (총 20개 테스트 전건 통과)
  > 💡 **의도:** 100% 온디바이스 개인정보 보호 철칙을 준수하며 외부 통신 없이 기기 갤러리의 특정 일자 범위 사진만을 안전하게 비동기로 쿼리하여 후속 OCR/품질 선별 파이프라인으로 전달하는 기반 마련.
- 2단계 안드로이드 앱 프로젝트 구조 및 코어 도메인 로직 구현
  - `DdayCalculator`: EXIF 기반 생일 및 심야(00~04시) 보정 D-day 계산 로직
  - `CalendarSequenceValidator`: 연속일 달력 미넘김 감지, 결측일(gap) 감지, 노이즈 OCR 필터링
  - `ImageSharpnessEvaluator`: Laplacian 분산 기반 동일 날짜 중 최고 선명도 사진 단일 선별 알고리즘
  - Jetpack Compose 기반 4단계 워크플로우 UI 화면 (`StartScreen`, `ScanScreen`, `ReviewScreen`, `CompleteScreen`)
  - 온디바이스 개인정보 보호 원칙 적용 (`INTERNET` 권한 배제, 로컬 전용 처리)
  - 도메인 단위 테스트 14종 및 독립 검증 실행 스크립트(`android/run_tests.py`)
- 프로젝트 지침(`CLAUDE.md`·`AGENTS.md`), 기획 문서, 날짜 처리 흐름 문서 추가
  > 💡 **의도:** 범용 사진 분류앱 아이디어를 "지난 D-day 사진을 자동으로 모아 넘김 영상으로"로 좁혔다. PC에서 먼저 검증하고 갤럭시 앱으로 옮기는 순서와, 아이 사진을 저장소·외부로 내보내지 않는 원칙을 처음부터 적어 둔다.
- 자동 선별 및 릴스 규격(1080x1920) 넘김 영상 제작 파이프라인(`src/pipeline.py`) 및 단위 테스트(`tests/test_pipeline.py`) 구현
  > 💡 **의도:** RapidOCR 상단 영역 감지 및 CLAHE 전처리, Laplacian 선명도 기반 연사 선별, D-day 순 정렬 및 자막 오버레이를 통해 실제 1단계 프로토타입 mp4 영상 렌더링 완료.
- 갤럭시(안드로이드) 앱 UI/UX 3종 피그마 상세 기획 문서(`docs/ui_ux_concepts.md`) 및 실제 피그마 클라우드 시안 파일 연동 완료
  > 💡 **의도:** Figma 공식 MCP 서버를 연동하여 사용자의 피그마 계정에 직접 '디데이 넘김 (dday_flip) 앱 UI/UX 초안' 파일을 생성하고 3가지 안(원탭 메모리북, 인텔리전트 타임라인, 릴스/쇼츠 퀵스냅) 프레임을 캔버스에 렌더링.
- 2단계 갤럭시(안드로이드) 앱 개발 계획서(`plan.md`, `docs/plan_phase2.md`) 작성 완료
  > 💡 **의도:** Kotlin + Jetpack Compose 기반 순수 네이티브 아키텍처, ML Kit OCR, Media3 Transformer 영상 생성 파이프라인 및 TDD 검증 계획 수립.
- 기획자 문서(`PLANNER_PROCEDURE.md` 2절) 표준 반영: 피그마 내 세션제목(「디데이사진」) 4개 화면 직하단에 동일 폭(360dp)의 설명 패널(진입 조건·조작 동작·예외 처리·전이 조건) 및 화면 간 전이 흐름 화살표 렌더링 완료
  > 💡 **의도:** 기획자 시안 규칙에 맞춰 화면마다 '고정된다/따라온다/선별한다' 등 사용자에게 보이는 명확한 시스템 동작과 권한/결측/미넘김 예외 처리 기준을 시각화.

### Changed
- 기획 문서(`docs/디데이넘김_기획.md`) 체계화: 핵심 가정 및 확인 방법, 예외 처리 규칙, 1단계 통과 기준(Success Metrics) 추가
  > 💡 **의도:** 구현 착수 전 숨은 가정과 예외 상황(미넘김, 결측일, 야간 촬영, OCR 오인식 등)의 처리 정책을 정비하고 객관적인 1단계 성공 지표를 수립.
