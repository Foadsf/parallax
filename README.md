# Parallax

A free Android 3D scanner. Walk slowly around an object; Parallax picks sharp keyframes as you move, then reconstructs a coloured 3D point cloud on the phone, with no cloud service.

- **Structure from Motion**: SIFT (RootSIFT) features, mutual ratio-test matching, Nistér five-point RANSAC, incremental registration with P3P-RANSAC + Levenberg-Marquardt, multi-view DLT triangulation and a sparse Schur-complement bundle adjustment that also self-calibrates each lens (focal length, k1, k2).
- **Rolling shutter**: phone sensors read rows one after another (10–33 ms). Each keyframe records the readout time (`SENSOR_ROLLING_SHUTTER_SKEW`) and the gyroscope rate. Bundle adjustment then projects every observation with the camera pose at that row's capture time, using the gyro rotation and a velocity from the neighbouring keyframes.
- **Dense stereo**: for every keyframe, the two best neighbours are rectified and matched with Semi-Global Matching. The disparity range comes from the sparse model, the two depth maps are cross-checked, and all views are fused in a voxel grid with normals.
- **Two rear cameras, metric scale**: if the phone can stream two physical rear cameras in the same request, Parallax captures both. The factory lens positions (`LENS_POSE_TRANSLATION`) fix the scale, so the model comes out in metres.
- **Scale marker, any phone**: print the [ArUco marker](docs/scale-marker-60mm.svg) (4×4 dictionary, id 0, 60 mm), lay it flat next to the object, and enter its measured edge under *Scans → ruler*. The marker's corners are triangulated in the model and its edge sets the scale, so single-camera phones get metres too. Without a marker or a lens pair, the shape is right but the scale is arbitrary.
- **Gravity-up**: the gravity sensor makes +Z point up in the exported model.
- **Camera control**: the factory intrinsics and distortion (`LENS_INTRINSIC_CALIBRATION`, `LENS_DISTORTION`) are mapped into the stream. During a scan, exposure, white balance and focus are locked. OIS, EIS and HAL distortion correction are turned off where the camera allows.
- **Torch**: Auto turns the torch on when auto-exposure needs more than about 1/30 s at ISO 800, and never switches it mid-scan, since lighting must stay constant across views. You can force it on or off.
- **Export**: binary PLY (`x y z nx ny nz red green blue`) to Downloads. Or share the whole scan as a ZIP that is a ready [COLMAP](https://colmap.github.io/) project (`images/` + `sparse/0/*.txt`), for meshing or refinement on a desktop with COLMAP, OpenMVS, Meshroom, MeshLab, CloudCompare or FreeCAD.

**No network permission, no analytics, no ads.** CI fails any build that adds `INTERNET`. Licensed under the [GPL-3.0](LICENSE). It needs Android 10 (API 29) or newer, a 64-bit ARM phone and OpenGL ES 3.0.

## Scanning tips

1. Pick an object with texture (print, wood grain, fabric, labels). Plain white or shiny surfaces give few features. Put it on a textured surface, such as a newspaper.
2. Keep the object filling about half of the frame and walk **around** it in one or two rings at different heights. Turning on the spot gives no parallax.
3. Move slowly. Each tracked point draws a trail back to where it was at the last keyframe, and the ring around the shutter fills as the trails grow. A keyframe is taken when the ring is full, focus has locked and the image is sharp (the phone ticks). The hint line says what is holding it back: focusing, too dark, glare, blur or too fast. 30–80 keyframes is a good scan.
4. Watch the coverage dial in the corner of the viewfinder. Its outer ring is the sides seen from level, its inner ring the views from above. Keep going until both are mostly filled; the white mark shows where you are looking now.
5. Tap stop. Reconstruction takes from about a minute to several minutes, depending on the phone and the number of keyframes. It runs in a foreground service with a progress notification, so you can switch apps or lock the phone.
6. In the viewer, drag to orbit, pinch to zoom and drag with two fingers to pan. The buttons on the right change the point size, show or hide the cameras, and recentre the view. The floor grid has 10 cm squares when the model is metric.

## How the hardware is used

| Part | Use |
|---|---|
| CPU cores | All of them: OpenCV's thread pool runs feature extraction, matching, SGM and rectification in parallel. |
| GPU | OpenGL ES 3 point-cloud viewer. |
| Camera ISP | YUV streams, auto-exposure / white balance / focus, then locked for the scan. |
| Rear cameras | One, or two physical cameras of a logical multi-camera, for metric scale. |
| Storage | Keyframes are encoded and written by a background thread, so tracking never waits for the disk. |
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
build/host-test/synth_test /tmp/parallax-single 36 --single --no-dense --marker   # 60 mm ArUco on the ground
```

The marker case needs OpenCV 4.7 or newer, where ArUco is part of `objdetect`; with older OpenCV the core builds without marker support.

Results on 2026-11-04 (Linux x86-64, OpenCV 4.14.0, 36 orbit positions, 1280×960 images):

| Case | Registered | Camera position error (RMS) | Metric scale | Gravity error | Dense points | Surface error (median / 90th pct) |
|---|---|---|---|---|---|---|
| One camera, sparse | 36/36 | 0.33–0.38 mm | n/a | 0.00–0.02° | n/a | n/a |
| One camera, dense | 36/36 | 0.33 mm | n/a | 0.02° | 1.23 M | 0.75 / 1.78 mm |
| One camera + 60 mm marker, sparse | 36/36 | 0.33 mm | within 0.31 % | 0.02° | n/a | n/a |
| Two-camera rig, dense | 72/72 | 0.12 mm | within 0.18 % | 0.01° | 1.67 M | 0.99 / 2.01 mm |
| One camera, rolling shutter, correction off | 36/36 | 1.71 mm | n/a | 0.04° | n/a | n/a |
| One camera, rolling shutter, correction on | 36/36 | 0.39 mm | n/a | 0.01° | n/a | n/a |

Matching and RANSAC run on several threads, so repeated runs differ slightly (about 0.05 mm in pose error).

The rolling-shutter rows render each image row from the camera pose at that row's time (30 ms readout), along an orbit with a hand-held sway of about 0.25 rad/s. Run them with `synth_test <dir> 36 --single --no-dense --rs` and add `--no-rs-model` to switch the correction off.

The scene is about 0.6 m from the cameras. Bundle adjustment recovers the lens parameters from the 3 %-off start: focal length within 0.05 %, k1 and k2 within 0.002. A synthetic scene is not a phone: it has no motion blur, auto-exposure drift or specular surfaces, and its rolling shutter is an ideal one. It checks the geometry, not real-world robustness.

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
| `app/src/main/java/.../ReconstructionService.kt` | Foreground service that runs the reconstruction, with progress and cancel. |
| `app/src/main/java/.../PointCloudView.kt` | OpenGL ES 3 viewer: normal-lit points, floor grid, camera frustums (orbit, pinch, pan, double-tap to reset). |
| `docs/scale-marker-60mm.svg` | Printable scale marker (A4, print at 100 %); `tools/host-test/make_marker.cpp` generates others. |

## Limitations

- Output is a point cloud with normals, not a mesh. Use Poisson reconstruction in MeshLab or CloudCompare, or OpenMVS on the exported COLMAP project.
- Metric scale needs the printed marker or simultaneous streams from two rear cameras. Many phones do not offer two streams to third-party apps, and the app then falls back to one lens. Which modes your phone offers shows as chips at the top of the capture screen. Marker scale assumes the marker lies flat and was measured after printing.
- Dense matching still assumes a global shutter. On the synthetic rolling-shutter rig, correcting the poses improved them five-fold, but the dense surface error rose from 0.70 to 0.87 mm (median). Making the rectified stereo rolling-shutter aware is open work.
- Shiny, transparent and textureless surfaces do not reconstruct well. That is a limit of passive photogrammetry.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).
