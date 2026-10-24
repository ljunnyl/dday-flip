"""D-day Flip Video Pipeline.

Loads photos from input directory, filters and selects the sharpest photo per D-day,
and renders a polished vertical video (1080x1920) with D+N overlays.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from datetime import date, datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import cv2
import imageio.v2 as imageio
import numpy as np
from PIL import ExifTags, Image, ImageDraw, ImageFont, ImageOps
from rapidocr_onnxruntime import RapidOCR


@dataclass
class PhotoRecord:
    path: Path
    datetime_original: Optional[datetime] = None
    date_key: Optional[str] = None
    transposed_size: Tuple[int, int] = (0, 0)
    is_vertical: bool = False
    laplacian_var: float = 0.0
    detected_texts: List[str] = field(default_factory=list)
    dday: Optional[int] = None
    status: str = "pending"
    reason: str = ""


def get_exif_datetime_original(img: Image.Image) -> Optional[datetime]:
    """Extract DateTimeOriginal from EXIF if available."""
    try:
        exif = img._getexif()
        if not exif:
            return None
        for tag_id, value in exif.items():
            tag_name = ExifTags.TAGS.get(tag_id)
            if tag_name == "DateTimeOriginal" and isinstance(value, str):
                # Standard EXIF format: 'YYYY:MM:DD HH:MM:SS'
                clean_val = value.strip().replace("\x00", "")
                parts = clean_val.split(" ")
                if len(parts) >= 2:
                    date_part = parts[0].replace(":", "-")
                    time_part = parts[1]
                    return datetime.fromisoformat(f"{date_part}T{time_part}")
    except Exception:
        pass
    return None


def calculate_sharpness(img: Image.Image) -> float:
    """Calculate sharpness using Laplacian variance on grayscale image."""
    arr = np.array(img.convert("L"))
    var = cv2.Laplacian(arr, cv2.CV_64F).var()
    return float(var)


def parse_dday_candidate(texts: List[str], expected: Optional[int] = None) -> Optional[int]:
    """Parse D-day number from OCR text tokens.

    Supports exact numbers (e.g. '145', '147'),
    2-digit numbers when expected is ~140-150 (e.g. '43' -> 143, '50' -> 150),
    and common OCR misreads like '500' -> 150.
    """
    candidates: List[int] = []

    for t in texts:
        # Clean non-digit characters
        digits = re.sub(r"[^\d]", "", t)
        if not digits:
            continue
        val = int(digits)
        candidates.append(val)

    if not candidates:
        return None

    # 1. Exact match with expected if candidate in [100, 250]
    for val in candidates:
        if 100 <= val <= 250:
            if expected is not None:
                if abs(val - expected) <= 3:
                    return val
            else:
                return val

    # 2. Heuristic for 2-digit numbers (missing hundred digit)
    if expected is not None:
        exp_mod100 = expected % 100
        for val in candidates:
            # e.g., '43' for 143, '50' for 150
            if abs(val - exp_mod100) <= 2:
                hundred = (expected // 100) * 100
                return hundred + val
            # e.g., '500' for 150 (common OCR trailing zero or 1 misread as 5)
            if val == 500 and 145 <= expected <= 155:
                return 150

    # 3. Direct match if single valid 3-digit candidate
    valid_3digit = [v for v in candidates if 100 <= v <= 200]
    if valid_3digit:
        return valid_3digit[0]

    return None


def run_ocr_on_top_crop(img: Image.Image, ocr: RapidOCR) -> List[str]:
    """Run OCR on the top portion (where calendar typically sits)."""
    w, h = img.size
    # Crop upper region (10% to 50% height, 15% to 85% width)
    crop_box = (int(w * 0.10), int(h * 0.05), int(w * 0.90), int(h * 0.50))
    crop_img = img.crop(crop_box)
    crop_np = np.array(crop_img)

    res, _ = ocr(crop_np)
    texts: List[str] = []
    if res:
        for item in res:
            text = str(item[1]).strip()
            if text:
                texts.append(text)
    return texts


def create_letterboxed_frame(
    img: Image.Image,
    target_size: Tuple[int, int] = (1080, 1920),
    dday_text: Optional[str] = None,
) -> np.ndarray:
    """Create a vertical 1080x1920 frame with letterboxing and modern badge overlay."""
    tw, th = target_size
    iw, ih = img.size

    # Fit image within target size preserving aspect ratio
    scale = min(tw / iw, th / ih)
    nw, nh = int(iw * scale), int(ih * scale)
    resized = img.resize((nw, nh), Image.Resampling.LANCZOS)

    # Dark cinematic background canvas
    canvas = Image.new("RGB", (tw, th), color=(18, 18, 20))
    paste_x = (tw - nw) // 2
    paste_y = (th - nh) // 2
    canvas.paste(resized, (paste_x, paste_y))

    # Draw D-day overlay badge
    if dday_text:
        draw = ImageDraw.Draw(canvas, "RGBA")
        
        # Load font
        font_size = 56
        font = None
        font_candidates = [
            "C:/Windows/Fonts/malgunbd.ttf",
            "C:/Windows/Fonts/malgun.ttf",
            "C:/Windows/Fonts/arialbd.ttf",
            "C:/Windows/Fonts/arial.ttf",
        ]
        for font_path in font_candidates:
            if Path(font_path).exists():
                try:
                    font = ImageFont.truetype(font_path, font_size)
                    break
                except Exception:
                    continue
        if font is None:
            font = ImageFont.load_default()

        # Calculate text bounding box
        bbox = draw.textbbox((0, 0), dday_text, font=font)
        text_w = bbox[2] - bbox[0]
        text_h = bbox[3] - bbox[1]

        # Pill badge dimensions
        pad_x = 36
        pad_y = 18
        badge_w = text_w + pad_x * 2
        badge_h = text_h + pad_y * 2
        badge_x = (tw - badge_w) // 2
        badge_y = th - 180  # Placed at bottom center

        # Pill background (rounded rectangle)
        pill_box = [badge_x, badge_y, badge_x + badge_w, badge_y + badge_h]
        radius = badge_h // 2
        
        # Semi-transparent dark frosted pill with subtle border
        draw.rounded_rectangle(
            pill_box,
            radius=radius,
            fill=(10, 10, 14, 215),
            outline=(255, 255, 255, 60),
            width=2,
        )

        # Draw text inside pill
        tx = badge_x + pad_x - bbox[0]
        ty = badge_y + pad_y - bbox[1]
        draw.text((tx, ty), dday_text, fill=(255, 255, 255, 255), font=font)

    return np.array(canvas)


def process_photos(
    photo_dir: Path,
    ocr: Optional[RapidOCR] = None,
) -> Tuple[List[PhotoRecord], List[PhotoRecord]]:
    """Inspect and classify all photos in photo_dir."""
    if ocr is None:
        ocr = RapidOCR()

    photo_files = sorted(list(photo_dir.glob("*.jpg")) + list(photo_dir.glob("*.jpeg")) + list(photo_dir.glob("*.png")))
    records: List[PhotoRecord] = []

    # 1. Load, transpose, extract EXIF date and check orientation
    for p in photo_files:
        raw_img = Image.open(p)
        dto = get_exif_datetime_original(raw_img)
        transposed = ImageOps.exif_transpose(raw_img)
        w, h = transposed.size
        is_vert = h > w
        date_k = dto.strftime("%Y-%m-%d") if dto else None

        rec = PhotoRecord(
            path=p,
            datetime_original=dto,
            date_key=date_k,
            transposed_size=(w, h),
            is_vertical=is_vert,
        )

        if not is_vert:
            rec.status = "excluded"
            rec.reason = "landscape_orientation"
            records.append(rec)
            continue

        # Calculate sharpness
        rec.laplacian_var = calculate_sharpness(transposed)
        records.append(rec)

    vertical_records = [r for r in records if r.is_vertical]

    # 2. Run OCR on vertical photos to find anchor D-day
    base_date: Optional[date] = None

    for rec in vertical_records:
        transposed = ImageOps.exif_transpose(Image.open(rec.path))
        texts = run_ocr_on_top_crop(transposed, ocr)
        rec.detected_texts = texts
        
        # Check for confident 3-digit number (100-200)
        direct_val = parse_dday_candidate(texts, expected=None)
        if direct_val is not None and 100 <= direct_val <= 200:
            rec.dday = direct_val
            if rec.datetime_original:
                shot_d = rec.datetime_original.date()
                calc_base = date.fromordinal(shot_d.toordinal() - direct_val)
                if base_date is None:
                    base_date = calc_base

    # 3. For photos missing explicit 3-digit match, use base_date anchor
    for rec in vertical_records:
        if rec.dday is None and rec.datetime_original and base_date:
            shot_d = rec.datetime_original.date()
            expected = shot_d.toordinal() - base_date.toordinal()
            parsed = parse_dday_candidate(rec.detected_texts, expected=expected)
            if parsed is not None:
                rec.dday = parsed
            else:
                # If no calendar detected, mark as snapshot exclusion
                rec.status = "excluded"
                rec.reason = "no_calendar_detected"

    # Any vertical record without D-day is excluded
    for rec in vertical_records:
        if rec.dday is None and rec.status != "excluded":
            rec.status = "excluded"
            rec.reason = "no_calendar_detected"

    # 4. Filter selected vs unselected, group by D-day / date
    valid_candidates = [r for r in vertical_records if r.status != "excluded" and r.dday is not None]

    # Group by dday
    dday_groups: Dict[int, List[PhotoRecord]] = {}
    for r in valid_candidates:
        dday_groups.setdefault(r.dday, []).append(r)

    selected: List[PhotoRecord] = []
    for dday, group in dday_groups.items():
        # Pick the sharpest photo
        group.sort(key=lambda r: r.laplacian_var, reverse=True)
        winner = group[0]
        winner.status = "selected"
        winner.reason = f"sharpest_for_dday_{dday}"
        selected.append(winner)

        # Mark duplicates
        for dup in group[1:]:
            dup.status = "excluded"
            dup.reason = f"duplicate_dday_{dday}_less_sharp"

    # Sort selected by D-day ascending
    selected.sort(key=lambda r: r.dday or 0)

    return selected, records


def render_video(
    selected_records: List[PhotoRecord],
    output_path: Path,
    duration_per_photo: float = 0.6,
    fps: int = 30,
) -> Path:
    """Render MP4 video with letterboxed frames and D+N overlays."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    frames_per_photo = max(1, int(round(duration_per_photo * fps)))

    writer = imageio.get_writer(
        str(output_path),
        fps=fps,
        codec="libx264",
        format="FFMPEG",
        quality=8,
        macro_block_size=None,
    )

    try:
        for rec in selected_records:
            img = ImageOps.exif_transpose(Image.open(rec.path))
            overlay_text = f"D+{rec.dday}" if rec.dday is not None else ""
            frame = create_letterboxed_frame(img, target_size=(1080, 1920), dday_text=overlay_text)
            
            for _ in range(frames_per_photo):
                writer.append_data(frame)
    finally:
        writer.close()

    return output_path


def run_pipeline(
    photos_dir: Path,
    output_dir: Path,
    duration_per_photo: float = 0.6,
    fps: int = 30,
) -> Dict[str, Any]:
    """Execute end-to-end pipeline and return non-PII summary."""
    photos_dir = Path(photos_dir)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    ocr = RapidOCR()
    selected, all_records = process_photos(photos_dir, ocr=ocr)

    video_path = output_dir / "dday_flip.mp4"
    render_video(selected, video_path, duration_per_photo=duration_per_photo, fps=fps)

    # Generate non-PII summary
    summary = {
        "timestamp": datetime.now().isoformat(),
        "total_photos": len(all_records),
        "excluded_landscape_count": sum(1 for r in all_records if r.reason == "landscape_orientation"),
        "excluded_no_calendar_count": sum(1 for r in all_records if r.reason == "no_calendar_detected"),
        "excluded_duplicate_count": sum(1 for r in all_records if "duplicate_dday" in r.reason),
        "selected_count": len(selected),
        "selected_ddays": [r.dday for r in selected],
        "video_output": {
            "path": str(video_path),
            "size_bytes": video_path.stat().st_size if video_path.exists() else 0,
            "resolution": "1080x1920",
            "fps": fps,
            "duration_per_photo_sec": duration_per_photo,
            "total_duration_sec": round(len(selected) * duration_per_photo, 2),
        },
        "selection_details": [
            {
                "index": i + 1,
                "dday": r.dday,
                "laplacian_variance": round(r.laplacian_var, 2),
                "resolution": r.transposed_size,
            }
            for i, r in enumerate(selected)
        ],
    }

    summary_path = output_dir / "pipeline_summary.json"
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)

    return summary


if __name__ == "__main__":
    base_dir = Path(__file__).resolve().parent.parent
    run_pipeline(base_dir / "photos", base_dir / "output")
