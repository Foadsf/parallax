# Parallax

A free Android 3D scanner. Walk slowly around an object; Parallax picks sharp keyframes as you move, then reconstructs a coloured 3D point cloud on the phone, with no cloud service.

- **Structure from Motion**: SIFT (RootSIFT) features, mutual ratio-test matching, Nistér five-point RANSAC, incremental registration with P3P-RANSAC + Levenberg-Marquardt, multi-view DLT triangulation and a sparse Schur-complement bundle adjustment that also self-calibrates each lens (focal length, k1, k2).
- **Dense stereo**: for every keyframe, the two best neighbours are rectified and matched with Semi-Global Matching. The disparity range comes from the sparse model, the two depth maps are cross-checked, and all views are fused in a voxel grid with normals.
- **Two rear cameras, metric scale**: if the phone can stream two physical rear cameras in the same request, Parallax captures both. The factory lens positions (`LENS_POSE_TRANSLATION`) fix the scale, so the model comes out in metres. With one camera the shape is right but the scale is arbitrary.
- **Gravity-up**: the gravity sensor makes +Z point up in the exported model.
- **Camera control**: the factory intrinsics and distortion (`LENS_INTRINSIC_CALIBRATION`, `LENS_DISTORTION`) are mapped into the stream. During a scan, exposure, white balance and focus are locked. OIS, EIS and HAL distortion correction are turned off where the camera allows.
- **Torch**: Auto turns the torch on when auto-exposure needs more than about 1/30 s at ISO 800, and never switches it mid-scan, since lighting must stay constant across views. You can force it on or off.
- **Export**: binary PLY (`x y z nx ny nz red green blue`) to Downloads. Or share the whole scan as a ZIP that is a ready [COLMAP](https://colmap.github.io/) project (`images/` + `sparse/0/*.txt`), for meshing or refinement on a desktop with COLMAP, OpenMVS, Meshroom, MeshLab, CloudCompare or FreeCAD.

**No network permission, no analytics, no ads.** CI fails any build that adds `INTERNET`. Licensed under the [GPL-3.0](LICENSE). It needs Android 10 (API 29) or newer, a 64-bit ARM phone and OpenGL ES 3.0.

## Scanning tips

1. Pick an object with texture (print, wood grain, fabric, labels). Plain white or shiny surfaces give few features. Put it on a textured surface, such as a newspaper.
2. Keep the object filling about half of the frame and walk **around** it in one or two rings at different heights. Turning on the spot gives no parallax.
3. Move slowly. The ring around the shutter fills as you move; a keyframe is taken when it is full and the image is sharp. 30–80 keyframes is a good scan.
4. Tap stop. Reconstruction takes from about a minute to several minutes, depending on the phone and the number of keyframes. Keep the app open.

## How the hardware is used

| Part | Use |
|---|---|
| CPU cores | All of them: OpenCV's thread pool runs feature extraction, matching, SGM and rectification in parallel. |
| GPU | OpenGL ES 3 point-cloud viewer. |
| Camera ISP | YUV streams, auto-exposure / white balance / focus, then locked for the scan. |
| Rear cameras | One, or two physical cameras of a logical multi-camera, for metric scale. |
| Gravity sensor, gyroscope | Up direction per keyframe; keyframes are skipped while the phone turns fast (motion blur). |
| Torch | Automatic in dim light. |

The phone's NPU is not used. The pipeline is classical geometry, with no neural network to run, and Android's NNAPI is deprecated as of Android 15. OpenCV's Android build has no OpenCL, and Android does not guarantee OpenCL to apps, so dense matching runs on the CPU. A GPU compute (Vulkan or GLES 3.1) port of the SGM cost volume is a natural next step.

## Your phone, detected automatically

Parallax works out what it is running on at start-up, using public Android APIs only, and adapts:

- **Phone and OS:** model, Android version, and the ROM (stock, LineageOS, /e/OS and others).
- **Compute:** SoC, CPU clusters and clock speeds, ISA extensions, RAM, the GPU behind OpenGL ES, and whether the vendor OpenCL driver is reachable.
- **Cameras:** each rear camera's hardware level, focal length, stream sizes, factory calibration, lens position, OIS, autofocus and torch levels, plus the capture modes they allow.
- **Processing profile:** chosen from RAM and core count. *Full* (5 GB+, 8 cores), *balanced* (3 GB+) or *light* sets the feature and dense-matching resolutions.

*Scans → ⓘ → This phone* shows all of this. The copy button puts it on the clipboard as a Markdown report: please paste it into bug reports. Each scan also records the phone, ROM and SoC in its `scan.json`.

**Older phones and custom ROMs.** The native code targets the ARMv8.0 baseline, and OpenCV dispatches its ARMv8.2 kernels (dot product, FP16 arithmetic) at runtime, so it runs on older 64-bit SoCs such as the Exynos 8895. Rear cameras the camera HAL does not expose cannot be used, so on many older phones only the main lens is available and the model is unscaled.

**Root is not used.** Parallax reports whether `su` is present but never runs it. On ROMs that publish `libOpenCL.so` in `public.libraries.txt`, the app reaches the GPU driver through `<uses-native-library>` without root. Root-only tweaks would not help reconstruction: copying vendor drivers, writing camera properties for cameras the HAL does not report, driving the torch LED through `sysfs` behind the camera HAL's back, or loosening file permissions.

## Build

Needs Java 17+ and an Android SDK with platform `android-37.0`, build-tools `37.0.0`, NDK `30.0.16248370` and CMake `4.1.2`:

```bash
sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "ndk;30.0.16248370" "cmake;4.1.2"
```
```bash
ANDROID_HOME=$HOME/Android/Sdk ./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. The Gradle wrapper fetches Gradle 9.8.1 and checks its SHA-256. Every plugin and library version is pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). OpenCV 4.14.0 (Apache-2.0) comes from Maven Central as an AAR and is linked from C++ through Prefab.

For an x86_64 emulator, add `-Pparallax.abis=arm64-v8a,x86_64`.

### Signing

Release builds made locally or in CI are signed with the debug key so they install directly. Builds signed with different keys cannot update each other: uninstall first. If you build on several machines, put a shared `debug.keystore` (alias `androiddebugkey`, passwords `android`) next to `settings.gradle.kts`. It is gitignored: **never commit a signing key here.**

## Testing the reconstruction core

The C++ core in [`app/src/main/cpp/core`](app/src/main/cpp/core) is plain C++17 + OpenCV and also builds on a desktop. [`tools/host-test`](tools/host-test/synth_test.cpp) ray-traces a textured scene (a box and a sphere on a ground plane) from a two-camera rig circling it. The rig is a wide camera plus an ultra-wide 12 mm away, each with its own lens distortion. The pipeline starts from intrinsics that are 3 % off and have no distortion. The test then checks the result against ground truth:

```bash
cmake -S tools/host-test -B build/host-test && cmake --build build/host-test -j
build/host-test/synth_test /tmp/parallax-synth 36            # two-camera rig + dense
build/host-test/synth_test /tmp/parallax-single 36 --single --no-dense
```

Results on 2026-11-02 (Linux x86-64, OpenCV 4.14.0, 36 orbit positions, 1280×960 images):

| Case | Registered | Camera position error (RMS) | Metric scale | Gravity error | Dense points | Surface error (median / 90th pct) |
|---|---|---|---|---|---|---|
| One camera, sparse | 36/36 | 0.46 mm | n/a | 0.04° | n/a | n/a |
| One camera, dense | 36/36 | 0.46 mm | n/a | 0.04° | 1.22 M | 0.73 / 1.78 mm |
| Two-camera rig, dense | 72/72 | 0.15 mm | within 0.15 % | 0.01° | 1.73 M | 0.99 / 2.06 mm |

The scene is about 0.6 m from the cameras. Bundle adjustment recovers the lens parameters from the 3 %-off start: focal length within 0.05 %, k1 and k2 within 0.002. A synthetic scene is not a phone: it has no motion blur, rolling shutter, auto-exposure drift or specular surfaces. It checks the geometry, not real-world robustness.

CI runs both on every push.

## Layout

| Path | What |
|---|---|
| `app/src/main/cpp/core/` | Platform-independent reconstruction: `features` (SIFT, matching), `geometry` (camera model, two-view, triangulation), `ba` (bundle adjustment), `sfm` (incremental driver, scale, gravity), `dense` (rectified SGM, fusion), `io` (PLY, COLMAP). |
| `app/src/main/cpp/live.cpp` | Live keyframe selection (KLT with forward-backward check, Laplacian sharpness), YUV conversion, preview. |
| `app/src/main/java/.../DeviceProbe.kt`, `app/src/main/cpp/probe.cpp` | Phone, ROM, CPU, GPU, OpenCL and camera detection; processing profile; device report. |
| `app/src/main/java/.../CameraProbe.kt` | Rear-lens discovery, capture modes, stream intrinsics. |
| `app/src/main/java/.../CaptureController.kt` | Camera2 session, locks, torch, keyframes, gravity. |
| `app/src/main/java/.../MainActivity.kt` | Compose UI: capture, processing, viewer, scans. |
| `app/src/main/java/.../PointCloudView.kt` | OpenGL ES 3 viewer (orbit, pinch, pan, double-tap to reset). |

## Limitations

- Output is a point cloud with normals, not a mesh. Use Poisson reconstruction in MeshLab or CloudCompare, or OpenMVS on the exported COLMAP project.
- Metric scale needs simultaneous streams from two rear cameras. Many phones do not offer this to third-party apps, and the app then falls back to one lens. Which modes your phone offers shows as chips at the top of the capture screen.
- Reconstruction runs in the foreground; leaving the app during processing may stop it.
- Shiny, transparent and textureless surfaces do not reconstruct well. That is a limit of passive photogrammetry.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).
