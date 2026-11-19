# BARDI LIVE STREAMER

Android Java app targeting Android 4.4 / API 19 and 32-bit ARMv7.

## Current app flow

1. Enter the tested RTSP URL for each of the four BARDI cameras.
2. Paste the YouTube Live Stream Key from YouTube Studio.
3. Tap BUKA YOUTUBE STUDIO when you need to prepare or check the broadcast.
4. Tap MULAI LIVE KE YOUTUBE to start the 2x2 camera mosaic encoder.
5. Tap STOP LIVE in the app or in the persistent Android notification to stop it.

The app stores camera URLs and the Stream Key locally in Android SharedPreferences. The
Stream Key is not included in the source code or GitHub repository. The output is RTMP
to YouTube's standard ingest address. YouTube Studio may still require the creator to
press its own Go Live control after the incoming preview is detected.

## Stream pipeline

- Four RTSP inputs over TCP
- Four 640x360 tiles combined as a 2x2 1280x720 mosaic
- H.264 / libx264, ultrafast, baseline, 15 FPS, target 2500 kbit/s
- Audio disabled
- Automatic reconnect with exponential backoff (3 to 60 seconds)
- Foreground service, CPU wake lock and Wi-Fi high-performance lock
- Stop control in the app and persistent notification

## Hardware and runtime caveat

The APK target is Android API 19 and armeabi-v7a. The current FFmpegAndroid binary
and filter/encoder availability still need to be verified on the Huawei STB. Four
software video decoders, scaling, mosaic composition and x264 encoding are demanding
for a 1 GB Cortex-A7 device. The 720p/15 FPS profile is a target, not a measured
performance guarantee. A successful APK build does not prove that the STB can maintain
all four camera feeds continuously; device testing is required.

## Build

GitHub Actions workflow: .github/workflows/android.yml.

A successful run uploads an artifact named bardi-live-streamer-debug. Download the
APK from the workflow run's Artifacts section.
