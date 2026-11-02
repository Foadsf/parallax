# F-Droid submission draft

[`io.github.foadsf.parallax.yml`](io.github.foadsf.parallax.yml) is a draft metadata file for [fdroiddata](https://gitlab.com/fdroid/fdroiddata). It has not been through `fdroid lint` yet. Before submitting, pin `commit:` to the full hash of the release tag.

**Open issue: the OpenCV binary.** The app links OpenCV from the `org.opencv:opencv` AAR on Maven Central, which contains prebuilt `libopencv_java4.so`. F-Droid builds from source and normally rejects prebuilt native libraries. Inclusion will need one of:

1. A `srclibs` entry that builds OpenCV 4.14.0 for Android from source (OpenCV's `platforms/android/build_sdk.py`), wired into CMake in place of the Prefab package; or
2. F-Droid's maintainers accepting the Maven Central artifact, which is unlikely.

Option 1 is the honest path. A build flag that switches `find_package(OpenCV)` from Prefab to a source-built `OpenCV_DIR` is the small code change it needs.

Store text and screenshots come from [`fastlane/`](../fastlane/metadata/android/en-US) in this repo. The phone screenshots are still to be added.
