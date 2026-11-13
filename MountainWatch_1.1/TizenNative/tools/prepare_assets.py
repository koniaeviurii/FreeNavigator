#!/usr/bin/env python3
"""
Prepare independent circular widget PNGs from the approved Mountain Watch art.

Usage:
  python3 prepare_assets.py approved-dial.png output-directory

The output widgets have transparent corners and preserve the original glossy
gradients, icons and numerals. This is an extraction/preparation step only:
the approved source still contains the original widgets and hands. Do NOT
overlay these extracted PNGs on top of the untouched source image; that would
duplicate them. A clean reconstructed background and separate hands are
required before enabling drag/recolor in the watch face.
"""
import os
import sys
import cv2
import numpy as np

if len(sys.argv) != 3:
    raise SystemExit("Usage: prepare_assets.py approved-dial.png output-directory")

source, output = sys.argv[1], sys.argv[2]
image = cv2.imread(source, cv2.IMREAD_COLOR)
if image is None:
    raise SystemExit("Cannot read source image: " + source)

height, width = image.shape[:2]
# Centers/radii measured against the approved 1254x1254 reference.
reference = 1254.0
widgets = [
    ("battery", 280, 306, 112),
    ("pulse", 974, 309, 114),
    ("steps", 279, 868, 118),
    ("distance", 973, 873, 119),
]
os.makedirs(output, exist_ok=True)

for name, cx, cy, radius in widgets:
    scale = min(width, height) / reference
    x, y, r = round(cx * scale), round(cy * scale), round(radius * scale)
    x0, y0 = max(0, x-r-3), max(0, y-r-3)
    x1, y1 = min(width, x+r+4), min(height, y+r+4)
    crop = image[y0:y1, x0:x1].copy()
    alpha = np.zeros(crop.shape[:2], dtype=np.uint8)
    cv2.circle(alpha, (x-x0, y-y0), r, 255, -1)
    rgba = cv2.cvtColor(crop, cv2.COLOR_BGR2BGRA)
    rgba[:, :, 3] = alpha
    path = os.path.join(output, name + ".png")
    if not cv2.imwrite(path, rgba):
        raise SystemExit("Could not write " + path)
    print(path)
