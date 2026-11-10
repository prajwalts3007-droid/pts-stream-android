`synthetic-hevc.mp4` is a generated 8-second test pattern and sine tone, not third-party media.

Generated with FFmpeg:

    ffmpeg -f lavfi -i testsrc2=size=320x180:rate=24 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 8 -c:v libx265 -preset ultrafast -x265-params pools=1:frame-threads=1 -pix_fmt yuv420p -tag:v hvc1 -c:a aac -b:a 48k -movflags +faststart synthetic-hevc.mp4

It is packaged only in the instrumentation test APK, never in release builds.

`hls/` contains an fMP4 HLS copy of the same generated clip, produced with `ffmpeg -i synthetic-hevc.mp4 -c copy -hls_time 2 -hls_segment_type fmp4 -hls_playlist_type vod master.m3u8`. It tests native playlist/init/segment loading without production media.
