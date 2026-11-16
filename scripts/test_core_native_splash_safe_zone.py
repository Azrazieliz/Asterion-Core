#!/usr/bin/env python3
"""Check the REAL generated Android 12+ system splash raster, not source promises.

The system imposes a circular icon mask. A full-bleed ring is cropped to a
horizontal band; this regression verifies the canonical artwork is fully inside
the icon's conservative 60% content safe zone, with real transparent margins.
"""
from pathlib import Path
import struct
import zlib

root = Path(__file__).resolve().parents[1]
app = root / "android_app/app"
logo = app / "build/generated/asterion-branding/res/drawable-nodpi/asterioncore_logo_splash.png"
assert logo.is_file(), "Run Gradle preBuild before checking the generated splash"
src = (app / "build.gradle.kts").read_text()
activity = (app / "src/main/java/com/ailm/android/MainActivity.kt").read_text()
themes = (app / "src/main/res/values/themes.xml").read_text()
assert "val splashCanvasSize = 768" in src
assert "splashCanvasSize * 0.60f" in src
assert "ImageIO.write(nativeSplash, \"png\", target)" in src
assert 'painterResource(id = R.drawable.asterioncore_logo)' in activity
assert 'painterResource(id = R.drawable.asterioncore_logo_splash)' not in activity
assert '<item name="windowSplashScreenAnimatedIcon">@drawable/asterioncore_logo_splash</item>' in themes
assert "postSplashScreenTheme" in themes

raw = logo.read_bytes()
assert raw[:8] == bytes((137, 80, 78, 71, 13, 10, 26, 10)), "Generated image is not PNG"
pos = 8
idat = bytearray()
width = height = bit_depth = color = None
while pos < len(raw):
    length = struct.unpack_from(">I", raw, pos)[0]
    kind = raw[pos+4:pos+8]
    payload = raw[pos+8:pos+8+length]
    pos += length + 12
    if kind == b"IHDR":
        width, height, bit_depth, color, _, _, interlace = struct.unpack(">IIBBBBB", payload)
        assert interlace == 0
    elif kind == b"IDAT":
        idat.extend(payload)
    elif kind == b"IEND":
        break
assert width == height == 768 and bit_depth == 8 and color == 6, (
    "Splash must be a square, 8-bit RGBA image with transparency"
)
pixels = zlib.decompress(idat)
stride = width * 4
assert len(pixels) == height * (stride + 1), "Unexpected PNG scanline size"

# PNG filtering is independent for each RGBA channel. Reconstruct just alpha,
# so we can inspect visible bounds without adding Pillow or changing CI.
alpha_prev = bytearray(width)
min_x, min_y, max_x, max_y = width, height, -1, -1
offset = 0
def paeth(a, b, c):
    p = a + b - c
    da, db, dc = abs(p-a), abs(p-b), abs(p-c)
    return a if da <= db and da <= dc else (b if db <= dc else c)

for y in range(height):
    filter_type = pixels[offset]
    assert filter_type in range(5), "Invalid PNG filter"
    row = pixels[offset+1:offset+1+stride]
    alpha = bytearray(width)
    for x in range(width):
        v = row[4*x+3]
        left = alpha[x-1] if x else 0
        up = alpha_prev[x]
        upper_left = alpha_prev[x-1] if x else 0
        predictor = (0, 0, 0, 0, 0)
        if filter_type == 1:
            v += left
        elif filter_type == 2:
            v += up
        elif filter_type == 3:
            v += (left + up) // 2
        elif filter_type == 4:
            v += paeth(left, up, upper_left)
        alpha[x] = v & 255
        if alpha[x] > 16:
            min_x = min(min_x, x)
            max_x = max(max_x, x)
            min_y = min(min_y, y)
            max_y = max(max_y, y)
    alpha_prev = alpha
    offset += stride + 1
assert max_x > min_x and max_y > min_y, "Empty logo"
margin = int(width * 0.17)
assert min_x >= margin and min_y >= margin, (
    f"Gold emblem clips top/left Android circular mask: {(min_x,min_y,max_x,max_y)}"
)
assert max_x < width-margin and max_y < height-margin, (
    f"Gold emblem clips bottom/right Android circular mask: {(min_x,min_y,max_x,max_y)}"
)
assert (max_x-min_x) < 0.66*width and (max_y-min_y) < 0.66*height
print(f"PASS: native icon visible bounds={(min_x,min_y,max_x,max_y)} "
      "fit safely in Android system circular mask; Compose uses original full ring")
