# BARDI LIVE STREAMER

Android Java project targeting API 19 / ARMv7 32-bit.

## Included
- Four configurable RTSP inputs
- 2x2 composition (four 640x360 tiles -> 1280x720)
- H.264 output, target 15 FPS, RTMP/RTMPS destination
- Retry with exponential backoff after FFmpeg exits
- CPU/Wi-Fi locks while streaming
- GitHub Actions workflow to build a debug APK

## Important limitations
This is an initial implementation, not a verified 24/7 production release.
Four RTSP decodes plus software scaling/composition and x264 encoding are heavy for a
1 GB Cortex-A7 device. 720p/15 is a target; real performance must be measured on the
Huawei STB. This pipeline does not yet use the Mali GPU or MediaCodec hardware decoding.
RTSP camera discovery via ONVIF is not implemented; paste the tested RTSP URLs from ONVIER.
The stream key is entered on-device and must never be committed to GitHub.

## Build
The GitHub Actions workflow is `.github/workflows/android.yml`. It builds with JDK 8,
Gradle 5.6.4 and Android Gradle Plugin 3.5.4. Download the `bardi-live-streamer-debug`
artifact from a successful Actions run.
