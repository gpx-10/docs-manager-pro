#!/bin/zsh
# Makes JPEG and HEIC copies of wide.png, plus wide-exif6.jpg: the same JPEG saved with EXIF
# orientation 6 ("turn 90° clockwise to view"), as phone cameras save portrait photos.
# Needs macOS (sips and swift).
set -e
A=${1:-app/src/androidTest/assets}
sips -s format jpeg -s formatOptions 92 $A/wide.png --out $A/wide.jpg >/dev/null
sips -s format heic $A/wide.png --out $A/wide.heic >/dev/null
swift ${0:a:h}/exif6.swift $A/wide.jpg $A/wide-exif6.jpg
