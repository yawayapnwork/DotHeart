#!/usr/bin/env python3
"""
heart_sprite.py - generates a small transparent pixel-art heart PNG,
procedurally, with zero external dependencies (stdlib zlib + struct only -
no Pillow). Exists so the pipeline can be tested end-to-end without
LibreSprite open or any binary fixture checked into git.

The heart is rasterized from the classic implicit heart curve
    f(x, y) = (x^2 + y^2 - 1)^3 - x^2*y^3,  filled where f(x, y) <= 0
sampled once per pixel center (no antialiasing/supersampling) so the output
has genuinely hard pixel edges, matching the hard-edge, nearest-neighbor-
scaled pixel art this project's Android client (PixelArtRenderer) and
backend are built around - not a smooth/antialiased placeholder.

Usage as a CLI:
    python scripts/heart_sprite.py [size] [output_path]
    python scripts/heart_sprite.py 32 scripts/fixtures/heart_32.png

Usage as a library (e.g. from test_pipeline.py):
    from heart_sprite import generate_heart_png
    png_bytes = generate_heart_png(32)
"""

from __future__ import annotations

import struct
import sys
import zlib
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"

# Crop window on the implicit heart curve, tuned so both a 16x16 and a
# 32x32 raster show a clean two-lobe notch at the top with no stray
# disconnected pixels - see the comment on heart_mask() below.
_X_RANGE = (-1.2, 1.2)
_Y_RANGE = (-1.0, 1.22)


def _png_chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def encode_png(width: int, height: int, rgba_rows: list[list[tuple[int, int, int, int]]]) -> bytes:
    """Encodes a full RGBA pixel grid as a PNG (color type 6, 8-bit depth,
    filter type 0/None per scanline - simplest valid encoding, more than
    sufficient for a tiny procedurally generated sprite)."""
    if len(rgba_rows) != height or any(len(row) != width for row in rgba_rows):
        raise ValueError("rgba_rows dimensions do not match width/height")

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)

    raw = bytearray()
    for row in rgba_rows:
        raw.append(0)  # filter type 0 (None) for this scanline
        for r, g, b, a in row:
            raw.extend((r, g, b, a))
    idat = zlib.compress(bytes(raw), 9)

    return (
        PNG_SIGNATURE
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"IDAT", idat)
        + _png_chunk(b"IEND", b"")
    )


def heart_mask(size: int) -> list[list[bool]]:
    """True where pixel (row, col) is inside the heart shape."""
    x0, x1 = _X_RANGE
    y0, y1 = _Y_RANGE
    mask: list[list[bool]] = []
    for py in range(size):
        row: list[bool] = []
        y = y1 - (py + 0.5) / size * (y1 - y0)
        for px in range(size):
            x = x0 + (px + 0.5) / size * (x1 - x0)
            f = (x * x + y * y - 1) ** 3 - (x * x) * (y**3)
            row.append(f <= 0)
        mask.append(row)
    return mask


def generate_heart_png(
    size: int = 32,
    fill_rgb: tuple[int, int, int] = (224, 82, 122),  # matches android placeholder_heart tint
) -> bytes:
    """Returns PNG bytes for a size x size transparent pixel-art heart."""
    if size < 4:
        raise ValueError("size must be >= 4 to render a recognizable heart")

    mask = heart_mask(size)
    r, g, b = fill_rgb
    opaque = (r, g, b, 255)
    transparent = (0, 0, 0, 0)

    rows = [[opaque if inside else transparent for inside in row] for row in mask]
    return encode_png(size, size, rows)


def _main(argv: list[str]) -> int:
    size = int(argv[0]) if len(argv) >= 1 else 32
    default_out = Path(__file__).resolve().parent / "fixtures" / f"heart_{size}.png"
    out_path = Path(argv[1]) if len(argv) >= 2 else default_out

    png_bytes = generate_heart_png(size)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(png_bytes)
    print(f"Wrote {len(png_bytes)}-byte {size}x{size} heart PNG to {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv[1:]))
