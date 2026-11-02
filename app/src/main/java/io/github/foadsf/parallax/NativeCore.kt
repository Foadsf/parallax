package io.github.foadsf.parallax

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** JNI facade over the C++ core in app/src/main/cpp. */
object NativeCore {
    init {
        System.loadLibrary("opencv_java4")
        System.loadLibrary("parallax")
    }

    fun interface Progress {
        /** Return false to cancel. Called on the reconstruction thread. */
        fun onProgress(stage: String, fraction: Float): Boolean
    }

    external fun buildInfo(): String

    external fun trackerReset()

    external fun trackerMarkKeyframe()

    /**
     * Draws the frame into [preview] (rotated clockwise by [rotation]) and, when [track] is set,
     * runs the keyframe tracker. [out] receives
     * `[progress, sharpness, meanY, darkRatio, clipRatio, keyframe, tracked]`; [pts] receives
     * normalised point coordinates in the sensor frame. Returns the number of points.
     */
    external fun trackFrame(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, width: Int, height: Int,
        yStride: Int, uvStride: Int, uvPixelStride: Int,
        preview: Bitmap?, rotation: Int, track: Boolean, out: FloatArray, pts: FloatArray,
    ): Int

    external fun saveJpeg(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, width: Int, height: Int,
        yStride: Int, uvStride: Int, uvPixelStride: Int, path: String, quality: Int,
    ): Boolean

    /** Runs the whole pipeline; blocks for minutes. Returns the report as JSON. */
    external fun reconstruct(
        cameras: DoubleArray, cameraFlags: IntArray,
        images: Array<String>, imageCams: IntArray, imageRig: IntArray, imageUp: FloatArray,
        outDir: String, imageRoot: String, options: IntArray, progress: Progress,
    ): String
}
