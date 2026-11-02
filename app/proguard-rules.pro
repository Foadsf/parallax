# JNI entry points and the progress callback are looked up by name from C++.
-keep class io.github.foadsf.parallax.NativeCore { *; }
-keep interface io.github.foadsf.parallax.NativeCore$Progress { *; }
-keep class * implements io.github.foadsf.parallax.NativeCore$Progress { *; }
# The OpenCV AAR's Java bindings are unused (the app calls OpenCV from C++ only).
-dontwarn org.opencv.**
