#!/usr/bin/env bash
# Generates the small test videos the mock IPTV server hands out.
set -euo pipefail
OUT="${1:-media}"
mkdir -p "$OUT/hls"
FF="ffmpeg -hide_banner -loglevel error -y"

# Live channel: moving test pattern with a clock and a tone, as MPEG-TS.
$FF -f lavfi -i "testsrc2=size=854x480:rate=25" -f lavfi -i "sine=frequency=440:sample_rate=48000" -t 420 \
  -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p -b:v 600k -maxrate 600k -bufsize 1200k -g 50 \
  -c:a aac -b:a 96k -f mpegts "$OUT/live.ts"

# The same thing cut into HLS segments.
$FF -i "$OUT/live.ts" -c copy -f hls -hls_time 4 -hls_list_size 0 -hls_segment_filename "$OUT/hls/seg%05d.ts" "$OUT/hls/vod.m3u8"

# A two-minute "movie".
$FF -f lavfi -i "smptebars=size=1280x720:rate=24" -f lavfi -i "sine=frequency=220:sample_rate=48000" -t 120 \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 48 -c:a aac -movflags +faststart "$OUT/movie.mp4"

# Posters and channel logos.
i=0
for c in 0x1f4e79 0x7a1f4e 0x1f7a4e 0x7a5a1f; do
  $FF -f lavfi -i "color=c=$c:s=300x450" -frames:v 1 "$OUT/poster$i.png"
  $FF -f lavfi -i "color=c=$c:s=160x120" -frames:v 1 "$OUT/logo$i.png"
  i=$((i+1))
done
ls -la "$OUT" | head -20
echo "HLS segments: $(ls "$OUT/hls" | grep -c '\.ts$')"
