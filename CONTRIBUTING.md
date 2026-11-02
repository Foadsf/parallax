# Contributing

Thanks for helping. A few ground rules keep Parallax useful and packageable:

- **Canonical methods, cited.** The pipeline follows the textbook incremental SfM + SGM design. If you change an algorithm, say which method you implement and cite it in the code comment.
- **Measure, don't guess.** Changes to `app/src/main/cpp/core` must keep `tools/host-test` passing. Paste the before and after lines of `synth_test` (registration, pose RMS, scale, dense error) in the PR. If you make a stage faster or better, show the numbers.
- **No network access, ever.** No `INTERNET` permission, no analytics, no crash reporting. CI fails if `INTERNET` appears in the APK.
- **No proprietary dependencies.** Everything must be FLOSS and buildable from source, so the app can be packaged for F-Droid. No Google Play services, ARCore or closed SDKs.
- **Pin versions exactly.** Bump a dependency in `gradle/libs.versions.toml` deliberately, one change per PR, and say why.
- **Test on a real phone when you touch capture.** Say which device and Android version, which capture modes the app offered, and whether a scan reconstructed.

## Running the core test

```bash
cmake -S tools/host-test -B build/host-test && cmake --build build/host-test -j
build/host-test/synth_test /tmp/parallax-synth 36
```

It needs OpenCV 4.x development files (`libopencv-dev` on Debian/Ubuntu).

By contributing, you agree that your work is licensed under the GPL-3.0.
