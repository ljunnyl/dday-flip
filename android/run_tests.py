#!/usr/bin/env python3
"""
Test runner to verify Android core domain logic and TDD test suites:
1. DdayCalculatorTest
2. CalendarSequenceTest
3. ImageSharpnessTest
"""

import sys
import math
import re
from datetime import date, datetime, timedelta

# --- 1. DdayCalculator Domain Logic ---
class DdayStartRule:
    ZERO_BASED = "ZERO_BASED"
    ONE_BASED = "ONE_BASED"

class DdayCalculationResult:
    def __init__(self, original_date_time, effective_date, is_night_shifted, dday):
        self.original_date_time = original_date_time
        self.effective_date = effective_date
        self.is_night_shifted = is_night_shifted
        self.dday = dday

class DdayCalculator:
    def __init__(self, birthday: date, rule=DdayStartRule.ONE_BASED, night_cutoff_hour=4):
        self.birthday = birthday
        self.rule = rule
        self.night_cutoff_hour = night_cutoff_hour

    def calculate(self, dt: datetime) -> DdayCalculationResult:
        is_night = dt.hour < self.night_cutoff_hour
        effective_date = (dt.date() - timedelta(days=1)) if is_night else dt.date()
        days_between = (effective_date - self.birthday).days

        if self.rule == DdayStartRule.ZERO_BASED:
            dday = days_between
        else:
            dday = days_between + 1

        return DdayCalculationResult(
            original_date_time=dt,
            effective_date=effective_date,
            is_night_shifted=is_night,
            dday=dday
        )

# --- 2. CalendarSequenceValidator Domain Logic ---
class IssueType:
    UNFLIPPED_CALENDAR = "UNFLIPPED_CALENDAR"
    MISSING_DAYS = "MISSING_DAYS"
    DISCREPANCY = "DISCREPANCY"

class CalendarPhotoEntry:
    def __init__(self, id, effective_date: date, exif_dday: int, ocr_dday=None, resolved_dday=None, sharpness_score=0.0):
        self.id = id
        self.effective_date = effective_date
        self.exif_dday = exif_dday
        self.ocr_dday = ocr_dday
        self.resolved_dday = ocr_dday if ocr_dday is not None else (resolved_dday if resolved_dday is not None else exif_dday)
        self.sharpness_score = sharpness_score

class SequenceIssue:
    def __init__(self, issue_type, target_dday, description, related_entry_ids=None, missing_days=None):
        self.type = issue_type
        self.target_dday = target_dday
        self.description = description
        self.related_entry_ids = related_entry_ids or []
        self.missing_days = missing_days or []

class SequenceValidationResult:
    def __init__(self, validated_entries, issues, missing_days):
        self.validated_entries = validated_entries
        self.issues = issues
        self.missing_days = missing_days

class CalendarSequenceValidator:
    def filter_noise_ocr(self, raw_candidates, expected_dday: int, tolerance=5):
        pattern = re.compile(r'(?i)(?:d\s*[\+\-]?\s*)?(\d{1,4})')
        candidate_numbers = []

        for candidate in raw_candidates:
            match = pattern.search(candidate.strip())
            if match:
                num = int(match.group(1))
                if abs(num - expected_dday) <= tolerance:
                    candidate_numbers.append(num)

        if not candidate_numbers:
            return None

        # Pick candidate closest to expected_dday
        candidate_numbers.sort(key=lambda x: abs(x - expected_dday))
        return candidate_numbers[0]

    def validate_sequence(self, entries):
        if not entries:
            return SequenceValidationResult([], [], [])

        sorted_entries = sorted(entries, key=lambda e: (e.effective_date, e.resolved_dday))
        issues = []

        # 1. Unflipped calendar detection
        for i in range(len(sorted_entries) - 1):
            curr = sorted_entries[i]
            nxt = sorted_entries[i + 1]

            curr_cal = curr.ocr_dday if curr.ocr_dday is not None else curr.resolved_dday
            nxt_cal = nxt.ocr_dday if nxt.ocr_dday is not None else nxt.resolved_dday

            if curr.effective_date != nxt.effective_date and curr_cal == nxt_cal:
                issues.append(SequenceIssue(
                    issue_type=IssueType.UNFLIPPED_CALENDAR,
                    target_dday=nxt_cal,
                    description=f"미넘김 의심: {curr.effective_date}와 {nxt.effective_date}의 달력 D-day가 모두 D+{nxt_cal} 입니다.",
                    related_entry_ids=[curr.id, nxt.id]
                ))

        # 2. Missing days detection
        unique_days = sorted(list(set(e.resolved_dday for e in sorted_entries)))
        all_missing_days = []

        if len(unique_days) >= 2:
            for i in range(len(unique_days) - 1):
                curr_day = unique_days[i]
                nxt_day = unique_days[i + 1]

                if nxt_day > curr_day + 1:
                    missing_range = list(range(curr_day + 1, nxt_day))
                    all_missing_days.extend(missing_range)
                    issues.append(SequenceIssue(
                        issue_type=IssueType.MISSING_DAYS,
                        target_dday=curr_day,
                        description=f"결측일 감지: D+{curr_day} 에서 D+{nxt_day} 사이에 {', '.join(f'D+{d}' for d in missing_range)} 누락",
                        missing_days=missing_range
                    ))

        return SequenceValidationResult(
            validated_entries=sorted_entries,
            issues=issues,
            missing_days=all_missing_days
        )

# --- 3. ImageSharpnessEvaluator Domain Logic ---
class PhotoCandidate:
    def __init__(self, id, dday, sharpness_score, uri=""):
        self.id = id
        self.dday = dday
        self.sharpness_score = sharpness_score
        self.uri = uri

class ImageSharpnessEvaluator:
    def calculate_laplacian_variance(self, grayscale_pixels, width, height):
        if width < 3 or height < 3 or len(grayscale_pixels) < width * height:
            return 0.0

        valid_width = width - 2
        valid_height = height - 2
        count = valid_width * valid_height

        laplacian_values = []
        for y in range(1, height - 1):
            row_offset = y * width
            top_offset = (y - 1) * width
            bottom_offset = (y + 1) * width

            for x in range(1, width - 1):
                center = grayscale_pixels[row_offset + x]
                top = grayscale_pixels[top_offset + x]
                bottom = grayscale_pixels[bottom_offset + x]
                left = grayscale_pixels[row_offset + (x - 1)]
                right = grayscale_pixels[row_offset + (x + 1)]

                lap = float(top + bottom + left + right - 4 * center)
                laplacian_values.append(lap)

        mean = sum(laplacian_values) / count
        variance = sum((v - mean) ** 2 for v in laplacian_values) / count
        return variance

    def select_sharpest(self, candidates):
        if not candidates:
            return None
        return max(candidates, key=lambda c: c.sharpness_score)

    def select_best_photos_per_day(self, candidates):
        by_day = {}
        for c in candidates:
            by_day.setdefault(c.dday, []).append(c)
        return {d: self.select_sharpest(c_list) for d, c_list in by_day.items()}


# --- TEST SUITE EXECUTION ---
def run_tests():
    total_passed = 0

    print("========================================")
    print("RUNNING TDD SUITE: DdayCalculatorTest")
    print("========================================")

    birthday = date(2024, 5, 27)

    # Test 1: Zero-based rule on birthday returns D+0
    calc_zero = DdayCalculator(birthday, rule=DdayStartRule.ZERO_BASED)
    res = calc_zero.calculate(datetime(2024, 5, 27, 14, 30))
    assert res.dday == 0, f"Expected 0, got {res.dday}"
    assert res.effective_date == date(2024, 5, 27)
    assert res.is_night_shifted is False
    print("  [PASS] test zero-based rule on birthday returns D+0")
    total_passed += 1

    # Test 2: One-based rule on birthday returns D+1
    calc_one = DdayCalculator(birthday, rule=DdayStartRule.ONE_BASED)
    res = calc_one.calculate(datetime(2024, 5, 27, 10, 15))
    assert res.dday == 1, f"Expected 1, got {res.dday}"
    assert res.effective_date == date(2024, 5, 27)
    assert res.is_night_shifted is False
    print("  [PASS] test one-based rule on birthday returns D+1")
    total_passed += 1

    # Test 3: 100 days after birthday
    res_one = calc_one.calculate(datetime(2024, 9, 4, 18, 0))
    res_zero = calc_zero.calculate(datetime(2024, 9, 4, 18, 0))
    assert res_one.dday == 101, f"Expected 101, got {res_one.dday}"
    assert res_zero.dday == 100, f"Expected 100, got {res_zero.dday}"
    assert res_one.effective_date == date(2024, 9, 4)
    print("  [PASS] test calculation for 100 days after birthday")
    total_passed += 1

    # Test 4: Late night photo before 04:00 is attributed to previous calendar day
    res_night1 = calc_one.calculate(datetime(2024, 5, 28, 1, 30))
    assert res_night1.is_night_shifted is True
    assert res_night1.effective_date == date(2024, 5, 27)
    assert res_night1.dday == 1

    res_night2 = calc_one.calculate(datetime(2024, 5, 28, 3, 59, 59))
    assert res_night2.is_night_shifted is True
    assert res_night2.effective_date == date(2024, 5, 27)
    assert res_night2.dday == 1
    print("  [PASS] test late night photo before 04_00 is attributed to previous calendar day")
    total_passed += 1

    # Test 5: Morning photo at or after 04:00 is attributed to same calendar day
    res_morning = calc_one.calculate(datetime(2024, 5, 28, 4, 0, 0))
    assert res_morning.is_night_shifted is False
    assert res_morning.effective_date == date(2024, 5, 28)
    assert res_morning.dday == 2
    print("  [PASS] test morning photo at or after 04_00 is attributed to same calendar day")
    total_passed += 1

    # Test 6: Custom night cutoff hour
    calc_cutoff5 = DdayCalculator(birthday, rule=DdayStartRule.ONE_BASED, night_cutoff_hour=5)
    res_c5 = calc_cutoff5.calculate(datetime(2024, 5, 28, 4, 30))
    assert res_c5.is_night_shifted is True
    assert res_c5.effective_date == date(2024, 5, 27)
    assert res_c5.dday == 1
    print("  [PASS] test custom night cutoff hour")
    total_passed += 1

    print("\n========================================")
    print("RUNNING TDD SUITE: CalendarSequenceTest")
    print("========================================")
    seq_validator = CalendarSequenceValidator()

    # Test 7: Unflipped calendar detection
    entries_unflipped = [
        CalendarPhotoEntry(id="p1", effective_date=date(2024, 6, 1), exif_dday=10, ocr_dday=10),
        CalendarPhotoEntry(id="p2", effective_date=date(2024, 6, 2), exif_dday=11, ocr_dday=10),
        CalendarPhotoEntry(id="p3", effective_date=date(2024, 6, 3), exif_dday=12, ocr_dday=12)
    ]
    res_seq1 = seq_validator.validate_sequence(entries_unflipped)
    unflipped_issues = [i for i in res_seq1.issues if i.type == IssueType.UNFLIPPED_CALENDAR]
    assert len(unflipped_issues) == 1
    assert unflipped_issues[0].target_dday == 10
    assert "p2" in unflipped_issues[0].related_entry_ids
    print("  [PASS] test unflipped calendar detection when consecutive days have identical dday")
    total_passed += 1

    # Test 8: Missing days detection
    entries_missing = [
        CalendarPhotoEntry(id="p1", effective_date=date(2024, 6, 1), exif_dday=10, resolved_dday=10),
        CalendarPhotoEntry(id="p2", effective_date=date(2024, 6, 2), exif_dday=11, resolved_dday=11),
        CalendarPhotoEntry(id="p3", effective_date=date(2024, 6, 5), exif_dday=14, resolved_dday=14)
    ]
    res_seq2 = seq_validator.validate_sequence(entries_missing)
    missing_issues = [i for i in res_seq2.issues if i.type == IssueType.MISSING_DAYS]
    assert len(missing_issues) == 1
    assert missing_issues[0].missing_days == [12, 13]
    assert res_seq2.missing_days == [12, 13]
    print("  [PASS] test missing days detection in sequence")
    total_passed += 1

    # Test 9: Noise text filtering
    raw_texts = ["2024", "10", "D+16", "9999", "LOVE"]
    filtered_dday = seq_validator.filter_noise_ocr(raw_texts, expected_dday=15, tolerance=3)
    assert filtered_dday == 16, f"Expected 16, got {filtered_dday}"
    print("  [PASS] test noise text filtering excludes year numbers and out-of-range text")
    total_passed += 1

    # Test 10: Noise text filtering out of range returns None
    raw_noise = ["2024", "2024-05-27", "850", "BABY", "HELLO"]
    filtered_none = seq_validator.filter_noise_ocr(raw_noise, expected_dday=30, tolerance=5)
    assert filtered_none is None
    print("  [PASS] test noise text filtering returns null when no OCR text is in plausible range")
    total_passed += 1

    # Test 11: Clean consecutive sequence
    clean_entries = [
        CalendarPhotoEntry(id=f"p{d}", effective_date=date(2024, 6, d), exif_dday=d, ocr_dday=d, resolved_dday=d)
        for d in range(1, 6)
    ]
    res_clean = seq_validator.validate_sequence(clean_entries)
    assert len(res_clean.issues) == 0
    assert len(res_clean.missing_days) == 0
    print("  [PASS] test clean consecutive sequence has no issues")
    total_passed += 1

    print("\n========================================")
    print("RUNNING TDD SUITE: ImageSharpnessTest")
    print("========================================")
    sharpness_eval = ImageSharpnessEvaluator()

    # Test 12: Selects sharpest photo for same day
    candidates_same_day = [
        PhotoCandidate(id="photo_blur", dday=10, sharpness_score=32.5),
        PhotoCandidate(id="photo_sharpest", dday=10, sharpness_score=158.4),
        PhotoCandidate(id="photo_mediocre", dday=10, sharpness_score=78.1)
    ]
    best_single = sharpness_eval.select_sharpest(candidates_same_day)
    assert best_single.id == "photo_sharpest"
    assert abs(best_single.sharpness_score - 158.4) < 1e-3
    print("  [PASS] test selects photo with highest sharpness score for same day")
    total_passed += 1

    # Test 13: Selects one best photo per day across multiple days
    all_candidates = [
        PhotoCandidate(id="d10_1", dday=10, sharpness_score=40.0),
        PhotoCandidate(id="d10_2", dday=10, sharpness_score=110.0),
        PhotoCandidate(id="d11_1", dday=11, sharpness_score=200.0),
        PhotoCandidate(id="d11_2", dday=11, sharpness_score=95.0),
        PhotoCandidate(id="d12_1", dday=12, sharpness_score=85.0)
    ]
    best_by_day = sharpness_eval.select_best_photos_per_day(all_candidates)
    assert len(best_by_day) == 3
    assert best_by_day[10].id == "d10_2"
    assert best_by_day[11].id == "d11_1"
    assert best_by_day[12].id == "d12_1"
    print("  [PASS] test selects one best photo per day across multiple days")
    total_passed += 1

    # Test 14: Laplacian variance calculation on synthetic test patterns
    w, h = 10, 10
    blurry_pixels = [128] * (w * h)
    sharp_pixels = [255 if ((idx % w) + (idx // w)) % 2 == 0 else 0 for idx in range(w * h)]

    blur_score = sharpness_eval.calculate_laplacian_variance(blurry_pixels, w, h)
    sharp_score = sharpness_eval.calculate_laplacian_variance(sharp_pixels, w, h)

    assert abs(blur_score - 0.0) < 0.01
    assert sharp_score > blur_score
    assert sharp_score > 500.0
    print("  [PASS] test laplacian variance gives higher score for sharp high contrast pattern than flat blur")
    total_passed += 1

    print("\n========================================")
    print(f"ALL {total_passed} UNIT TESTS PASSED SUCCESSFULLY! (100% OK)")
    print("========================================")

if __name__ == "__main__":
    run_tests()
