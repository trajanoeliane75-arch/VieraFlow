#!/usr/bin/env bash
set -euo pipefail
DEST="${1:-$(dirname "$0")/fixtures}"
mkdir -p "$DEST"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=640x360:rate=25' -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 12 -c:v libx264 -profile:v main -level 3.0 -pix_fmt yuv420p -preset veryfast -g 50 -keyint_min 50 -sc_threshold 0 -b:v 600k -c:a aac -b:a 96k -ac 2 -f hls -hls_time 2 -hls_list_size 0 -hls_segment_filename "$DEST/segment%02d.ts" "$DEST/sample.m3u8"
ffmpeg -hide_banner -loglevel error -y -i "$DEST/sample.m3u8" -c copy -movflags +faststart "$DEST/sample.mp4"
