package io.github.foadsf.parallax

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class TorchMode { AUTO, ON, OFF }

data class LiveState(
    val points: FloatArray = FloatArray(0),   // normalised display coordinates, x0 y0 x1 y1 ...
    val progress: Float = 0f,
    val tracked: Int = 0,
    val keyframes: Int = 0,
    val scanning: Boolean = false,
    val torchOn: Boolean = false,
    val hint: String = "",
    val modeLabel: String = "",
    val metric: Boolean = false,
)

/**
 * Camera2 capture: one YUV stream per lens (two for a rig), live keyframe selection in native
 * code, keyframes saved as JPEG with their metadata. All camera work runs on [thread].
 */
class CaptureController(
    private val context: Context,
    private val onPreview: (Bitmap) -> Unit,
    private val onState: (LiveState) -> Unit,
    private val onError: (String) -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val executor = Executor { r -> handler?.post(r) }

    private var mode: CaptureMode? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var primaryReader: ImageReader? = null
    private var secondaryReader: ImageReader? = null
    private var heldSecondary: Image? = null
    private var size = Size(1280, 960)
    private var rig = false

    // Live state (camera thread).
    var torchMode = TorchMode.AUTO
        set(value) { field = value; handler?.post { updateTorch(force = true) } }
    private var torchOn = false
    private var lastTorchSwitch = 0L
    private var exposureNs = 0L
    private var iso = 0
    private var focusDiopters = 0f
    private var scanning = false
    private var scan: Scan? = null
    private val cameras = mutableListOf<ScanCamera>()
    private val images = mutableListOf<ScanImage>()
    private var keyframes = 0
    private var lastKeyframeMs = 0L
    private val out = FloatArray(7)
    private val pts = FloatArray(600)
    private val previews = arrayOfNulls<Bitmap>(3)
    private var previewIndex = 0

    // Sensors (main thread writes, camera thread reads).
    @Volatile private var gravity = FloatArray(3)
    @Volatile private var gyroRate = 0f

    fun open(m: CaptureMode) {
        close()
        mode = m
        thread = HandlerThread("parallax-camera").also { it.start() }
        handler = Handler(thread!!.looper)
        val g = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        g?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        handler!!.post { openCamera(m) }
    }

    fun close() {
        sensors.unregisterListener(this)
        val h = handler ?: return
        h.post {
            if (scanning) finishScan()
            runCatching { session?.close() }
            runCatching { device?.close() }
            heldSecondary?.close(); heldSecondary = null
            primaryReader?.close(); secondaryReader?.close()
            session = null; device = null; primaryReader = null; secondaryReader = null
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /** Starts a new scan. */
    fun startScan() = handler?.post {
        if (scanning || session == null) return@post
        val s = ScanStore.create(context)
        scan = s
        images.clear()
        keyframes = 0
        NativeCore.trackerReset()
        scanning = true
        triggerFocusLock()
        repeat()
        s.write(cameras, images)
        publish("Move slowly around the object")
    }

    /** Stops the scan and returns it (null if nothing was captured) on the main thread. */
    fun stopScan(done: (Scan?) -> Unit) = handler?.post {
        val s = finishScan()
        main.post { done(s) }
    }

    private fun finishScan(): Scan? {
        if (!scanning) return null
        scanning = false
        repeat(cancelFocus = true)
        val s = scan
        scan = null
        s?.write(cameras, images)
        publish("")
        return s?.takeIf { images.size >= 3 } ?: run { s?.dir?.deleteRecursively(); null }
    }

    // ---- Camera setup ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun openCamera(m: CaptureMode) {
        val secondary = (m as? CaptureMode.Rig)?.secondary
        rig = secondary != null
        size = if (secondary != null) CameraProbe.chooseSize(m.primary, secondary) else CameraProbe.chooseSize(m.primary)
        cameras.clear()
        cameras += ScanCamera(0, m.label, m.primary.openId, m.primary.physicalId, m.primary.sensorOrientation,
            CameraProbe.intrinsics(m.primary, size), m.primary.position)
        if (secondary != null) cameras += ScanCamera(1, m.label, secondary.openId, secondary.physicalId,
            secondary.sensorOrientation, CameraProbe.intrinsics(secondary, size), secondary.position)
        primaryReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4).apply {
            setOnImageAvailableListener({ onPrimary(it) }, handler)
        }
        if (secondary != null) secondaryReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).apply {
            setOnImageAvailableListener({ r -> r.acquireLatestImage()?.let { heldSecondary?.close(); heldSecondary = it } }, handler)
        }
        try {
            manager.openCamera(m.primary.openId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; createSession(d, m) }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); device = null
                    main.post { onError("Camera error $error") }
                }
            }, handler)
        } catch (e: Exception) {
            main.post { onError("Cannot open camera: ${e.message}") }
        }
    }

    private fun createSession(d: CameraDevice, m: CaptureMode) {
        val outputs = mutableListOf(OutputConfiguration(primaryReader!!.surface).apply {
            m.primary.physicalId?.let { setPhysicalCameraId(it) }
        })
        (m as? CaptureMode.Rig)?.let { r ->
            outputs += OutputConfiguration(secondaryReader!!.surface).apply { r.secondary.physicalId?.let { setPhysicalCameraId(it) } }
        }
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                repeat()
                publish("")
            }
            override fun onConfigureFailed(s: CameraCaptureSession) = fallBack(m)
        }
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, cb)
        // Simultaneous physical streams are optional (CameraDevice#isSessionConfigurationSupported).
        val supported = runCatching { d.isSessionConfigurationSupported(config) }.getOrDefault(true)
        if (!supported) { fallBack(m); return }
        try { d.createCaptureSession(config) } catch (e: Exception) { fallBack(m) }
    }

    /** Rig -> its primary lens alone -> the standalone camera; reported to the UI. */
    private fun fallBack(m: CaptureMode) {
        val next: CaptureMode? = when {
            m is CaptureMode.Rig -> CaptureMode.Single(m.primary, m.primary.let { "1×" })
            m.primary.physicalId != null -> runCatching {
                val c = manager.getCameraCharacteristics(m.primary.openId)
                CaptureMode.Single(m.primary.copy(physicalId = null, chars = c), m.label)
            }.getOrNull()
            else -> null
        }
        Log.w("Parallax", "session not supported for ${m.label}, falling back to ${next?.label}")
        runCatching { device?.close() }
        device = null
        primaryReader?.close(); secondaryReader?.close(); heldSecondary?.close()
        primaryReader = null; secondaryReader = null; heldSecondary = null
        if (next == null) { main.post { onError("This camera configuration is not supported") }; return }
        mode = next
        main.post { onError("${m.label} is not available on this phone; using ${next.label}") }
        openCamera(next)
    }

    private fun repeat(cancelFocus: Boolean = false) {
        val d = device ?: return
        val s = session ?: return
        val m = mode ?: return
        val c = m.primary.chars
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(primaryReader!!.surface)
            secondaryReader?.let { b.addTarget(it.surface) }
            applyControls(b, c)
            if (cancelFocus && hasAutofocus(c)) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                s.capture(b.build(), null, handler)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            }
            s.setRepeatingRequest(b.build(), resultCallback, handler)
        } catch (e: Exception) {
            Log.w("Parallax", "repeating request failed", e)
        }
    }

    private fun applyControls(b: CaptureRequest.Builder, c: CameraCharacteristics) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)  // required for FLASH_MODE_TORCH
        if (hasAutofocus(c)) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        // Short exposures: cap the frame duration at 1/30 s when the camera offers [30, 30].
        c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper == 30 }?.maxByOrNull { it.lower }
            ?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.lower, it.upper)) }
        // OIS moves the principal point; turn it and EIS off where the HAL allows.
        if (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true)
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        // Keep the raw lens geometry the factory calibration describes.
        if (c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                ?.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF) == true)
            b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
        // Photometric consistency between views: lock exposure and white balance while scanning.
        b.set(CaptureRequest.CONTROL_AE_LOCK, scanning)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, scanning)
        val flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        b.set(CaptureRequest.FLASH_MODE,
            if (flash && torchOn) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
    }

    private fun hasAutofocus(c: CameraCharacteristics) =
        (c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f &&
            c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true

    /** Focus once, then hold it for the whole scan (focus changes the focal length slightly). */
    private fun triggerFocusLock() {
        val d = device ?: return
        val s = session ?: return
        val c = mode?.primary?.chars ?: return
        if (!hasAutofocus(c)) return
        runCatching {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(primaryReader!!.surface)
            applyControls(b, c)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), null, handler)
        }
    }

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposureNs
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: iso
            focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: focusDiopters
            updateTorch(force = false)
        }
    }

    // ---- Torch ------------------------------------------------------------------------------

    /**
     * Auto torch, decided from what auto-exposure needs (exposure time x ISO), not from mean
     * brightness, which auto-exposure normalises. On when AE needs more than about 1/30 s at
     * ISO 800; off again only when the scene is much brighter, with a 1.5 s hold-off. The torch
     * is not switched automatically during a scan: lighting must stay constant across views.
     */
    private fun updateTorch(force: Boolean) {
        val want = when (torchMode) {
            TorchMode.ON -> true
            TorchMode.OFF -> false
            TorchMode.AUTO -> {
                val now = SystemClock.elapsedRealtime()
                val light = exposureNs / 1e9 * iso / 100.0
                when {
                    scanning || now - lastTorchSwitch < 1500 || exposureNs == 0L -> torchOn
                    !torchOn && light > 0.25 -> true
                    torchOn && light < 0.01 -> false
                    else -> torchOn
                }
            }
        }
        if (want != torchOn || force) {
            torchOn = want
            lastTorchSwitch = SystemClock.elapsedRealtime()
            repeat()
            publish(null)
        }
    }

    // ---- Frames -----------------------------------------------------------------------------

    private var hint = ""
    private var lastProgress = 0f
    private var lastTracked = 0
    private var lastPoints = FloatArray(0)

    private fun onPrimary(reader: ImageReader) {
        val img = reader.acquireLatestImage() ?: return
        try {
            val p = img.planes
            val m = mode ?: return
            val rot = m.primary.sensorOrientation
            val pw = 640.coerceAtMost(img.width)
            val ph = pw * img.height / img.width
            val bw = if (rot % 180 == 0) pw else ph
            val bh = if (rot % 180 == 0) ph else pw
            val bmp = previews[previewIndex]?.takeIf { it.width == bw && it.height == bh }
                ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { previews[previewIndex] = it }
            previewIndex = (previewIndex + 1) % previews.size
            val n = NativeCore.trackFrame(p[0].buffer, p[1].buffer, p[2].buffer, img.width, img.height,
                p[0].rowStride, p[1].rowStride, p[1].pixelStride, bmp, rot, true, out, pts)
            main.post { onPreview(bmp) }
            lastProgress = out[0]
            lastTracked = out[6].toInt()
            lastPoints = rotatePoints(n, rot)

            if (scanning) {
                val now = SystemClock.elapsedRealtime()
                val steady = gyroRate < 0.8f
                hint = when {
                    out[2] < 25f -> "Too dark"
                    !steady -> "Slow down"
                    lastTracked < 40 -> "Point at a textured object"
                    else -> "Move slowly around the object"
                }
                if (out[5] > 0.5f && steady && now - lastKeyframeMs > 350) {
                    saveKeyframe(img)
                    lastKeyframeMs = now
                }
            } else {
                hint = if (lastTracked < 40) "Point at a textured object" else ""
            }
            publish(hint)
        } finally {
            img.close()
        }
    }

    private fun saveKeyframe(primary: Image) {
        val s = scan ?: return
        val up = upInCamera(cameras[0].sensorOrientation)
        val group = keyframes
        if (writeJpeg(primary, File(s.imagesDir, "p_%04d.jpg".format(group)))) {
            images += ScanImage("p_%04d.jpg".format(group), 0, if (rig) group else -1, up, primary.timestamp,
                exposureNs, iso, focusDiopters, torchOn)
        } else return
        val sec = heldSecondary
        // Same request, so the timestamps should agree; accept up to 10 ms for unsynchronised HALs.
        if (rig && sec != null && kotlin.math.abs(sec.timestamp - primary.timestamp) < 10_000_000L) {
            if (writeJpeg(sec, File(s.imagesDir, "s_%04d.jpg".format(group)))) {
                images += ScanImage("s_%04d.jpg".format(group), 1, group, upInCamera(cameras[1].sensorOrientation),
                    sec.timestamp, exposureNs, iso, focusDiopters, torchOn)
            }
        }
        keyframes++
        NativeCore.trackerMarkKeyframe()
        s.write(cameras, images)
    }

    private fun writeJpeg(img: Image, f: File): Boolean {
        val p = img.planes
        return NativeCore.saveJpeg(p[0].buffer, p[1].buffer, p[2].buffer, img.width, img.height,
            p[0].rowStride, p[1].rowStride, p[1].pixelStride, f.absolutePath, 95)
    }

    /** Sensor-frame normalised points -> display frame (rotated clockwise by [rot]). */
    private fun rotatePoints(n: Int, rot: Int): FloatArray {
        val r = FloatArray(2 * n)
        for (i in 0 until n) {
            val u = pts[2 * i]
            val v = pts[2 * i + 1]
            val (x, y) = when (rot) {
                90 -> 1f - v to u
                180 -> 1f - u to 1f - v
                270 -> v to 1f - u
                else -> u to v
            }
            r[2 * i] = x; r[2 * i + 1] = y
        }
        return r
    }

    /**
     * Gravity reaction ("up") in OpenCV camera coordinates (x right, y down, z forward) of a rear
     * camera with the given SENSOR_ORIENTATION, from the device-frame gravity sensor.
     */
    private fun upInCamera(orientation: Int): FloatArray {
        val g = gravity
        val th = Math.toRadians(orientation.toDouble())
        val c = cos(th).toFloat()
        val s = sin(th).toFloat()
        return floatArrayOf(c * g[0] - s * g[1], -s * g[0] - c * g[1], -g[2])
    }

    private fun publish(newHint: String?) {
        val st = LiveState(lastPoints, lastProgress, lastTracked, keyframes, scanning, torchOn,
            newHint ?: hint, mode?.label ?: "", rig && cameras.size == 2 && cameras.all { it.position != null })
        main.post { onState(st) }
    }

    // ---- Sensors (main thread) --------------------------------------------------------------

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> gravity = e.values.copyOf(3)
            Sensor.TYPE_ACCELEROMETER -> {  // low-pass fallback when there is no fused gravity sensor
                val g = gravity
                gravity = FloatArray(3) { 0.9f * g[it] + 0.1f * e.values[it] }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val w = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                gyroRate = 0.7f * gyroRate + 0.3f * w
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
