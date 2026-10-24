import pytest
from pathlib import Path
from PIL import Image
import numpy as np

from src.pipeline import (
    calculate_sharpness,
    create_letterboxed_frame,
    parse_dday_candidate,
)

def test_calculate_sharpness():
    # 단색 이미지는 선명도 0
    img_flat = Image.new("RGB", (100, 100), color=(128, 128, 128))
    score_flat = calculate_sharpness(img_flat)
    assert score_flat == 0.0

    # 노이즈가 있는 이미지는 선명도 > 0
    rng = np.random.default_rng(42)
    noise_data = rng.integers(0, 256, (100, 100, 3), dtype=np.uint8)
    img_noise = Image.fromarray(noise_data)
    score_noise = calculate_sharpness(img_noise)
    assert score_noise > 0.0

def test_parse_dday_candidate():
    # 3자리 완전 매칭
    assert parse_dday_candidate(["145"], expected=145) == 145
    assert parse_dday_candidate(["147"], expected=147) == 147
    
    # 2자리 + expected 매칭 (100단위 보정)
    assert parse_dday_candidate(["43"], expected=143) == 143
    assert parse_dday_candidate(["50"], expected=150) == 150
    
    # 500 오인식 보정
    assert parse_dday_candidate(["500"], expected=150) == 150

    # 유효 숫자 없음
    assert parse_dday_candidate(["CROUS", "HELLO"], expected=145) is None
    assert parse_dday_candidate([], expected=145) is None

def test_create_letterboxed_frame():
    # 300x400 세로 이미지를 1080x1920 프레임으로 변환
    img = Image.new("RGB", (300, 400), color=(200, 100, 100))
    frame = create_letterboxed_frame(img, target_size=(1080, 1920), dday_text="D+145")
    
    assert frame.shape == (1920, 1080, 3)
    assert frame.dtype == np.uint8
