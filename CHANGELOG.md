# Changelog

이 프로젝트의 변경 내용을 기록한다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 시맨틱 버전을 따른다.

## [Unreleased]

### Added
- 2단계 안드로이드 앱 프로젝트 구조 및 코어 도메인 로직 구현
  - `DdayCalculator`: EXIF 기반 생일 및 심야(00~04시) 보정 D-day 계산 로직
  - `CalendarSequenceValidator`: 연속일 달력 미넘김 감지, 결측일(gap) 감지, 노이즈 OCR 필터링
  - `ImageSharpnessEvaluator`: Laplacian 분산 기반 동일 날짜 중 최고 선명도 사진 단일 선별 알고리즘
  - Jetpack Compose 기반 4단계 워크플로우 UI 화면 (`StartScreen`, `ScanScreen`, `ReviewScreen`, `CompleteScreen`)
  - 온디바이스 개인정보 보호 원칙 적용 (`INTERNET` 권한 배제, 로컬 전용 처리)
  - 도메인 단위 테스트 14종 및 독립 검증 실행 스크립트(`android/run_tests.py`)
- 프로젝트 지침(`CLAUDE.md`·`AGENTS.md`), 기획 문서, 날짜 처리 흐름 문서 추가
  > 💡 **의도:** 범용 사진 분류앱 아이디어를 "지난 D-day 사진을 자동으로 모아 넘김 영상으로"로 좁혔다. PC에서 먼저 검증하고 갤럭시 앱으로 옮기는 순서와, 아이 사진을 저장소·외부로 내보내지 않는 원칙을 처음부터 적어 둔다.
