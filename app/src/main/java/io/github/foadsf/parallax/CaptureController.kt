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
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class TorchMode { AUTO, ON, OFF }

/** Primary and secondary frames of one request agree to within this (ns). */
private const val SYNC_NS = 10_000_000L

data class LiveState(
    val points: FloatArray = FloatArray(0),   // normalised display coordinates: x, y, anchorX, anchorY per point
    val progress: Float = 0f,
    val tracked: Int = 0,
    val keyframes: Int = 0,
    val scanning: Boolean = false,
    val torchOn: Boolean = false,
    val hint: String = "",
    val modeLabel: String = "",
    val metric: Boolean = false,
    /** Viewing directions covered by keyframes: 24 azimuth sectors x 2 elevation bands. */
    val coverage: BooleanArray = BooleanArray(Coverage.BINS),
    /** Current viewing direction's bin, or -1 when unknown. */
    val heading: Int = -1,
)

/**
 * Which sides of the object have been seen, from the phone's orientation: the rear camera's
 * viewing direction (game rotation vector, gyro + accelerometer) binned by azimuth relative to
 * the first keyframe and by elevation (level vs looking down by more than 35 degrees).
 */
object Coverage {
    const val SECTORS = 24
    const val BINS = 2 * SECTORS

    fun bin(rotationVector: FloatArray, azimuth0: Double): Int {
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, rotationVector)
        // Rear camera looks along device -Z; in world coordinates that is -(third column).
        val dx = -r[2].toDouble(); val dy = -r[5].toDouble(); val dz = -r[8].toDouble()
        val az = Math.atan2(dx, dy)
        var rel = (az - azimuth0) / (2 * Math.PI)
        rel -= Math.floor(rel)
        val sector = (rel * SECTORS).toInt().coerceIn(0, SECTORS - 1)
        val high = dz < -0.574  // sin 35 degrees
        return if (high) SECTORS + sector else sector
    }

    fun azimuth(rotationVector: FloatArray): Double {
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, rotationVector)
        return Math.atan2(-r[2].toDouble(), -r[5].toDouble())
    }
}

private class ImuTracker {
    private class Sample(val tNs: Long, val x: Float, val y: Float, val z: Float)
    private val gyroBuf = ArrayDeque<Sample>(256)
    private val gravBuf = ArrayDeque<Sample>(256)
    private var lastGyroNs = 0L
    private var qw = 1.0; private var qx = 0.0; private var qy = 0.0; private var qz = 0.0
    private var quatInit = false
    private var bx = 0f; private var by = 0f; private var bz = 0f
    private var lastAcc = floatArrayOf(0f, 0f, 9.81f)
    private var lastMag: FloatArray? = null
    private var hasHardwareGravity = false; private var hasHardwareRot = false; private var hasCalibratedGyro = false
    private var p0Hpa = 0f
    var baroAltitudeM: Float? = null; private set
    var hasGyro = false; private set
    var gyroRate = 0f; private set
    var rotationVector: FloatArray? = null; private set

    fun resetScanOrigin() { p0Hpa = 0f; baroAltitudeM = null }

    fun onSensor(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> { hasHardwareGravity = true; pushGrav(e.timestamp, e.values[0], e.values[1], e.values[2]) }
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
                if (e.sensor.type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED && e.values.size < 6) return
                val ax = if (e.sensor.type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) e.values[0] - e.values[3] else e.values[0]
                val ay = if (e.sensor.type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) e.values[1] - e.values[4] else e.values[1]
                val az = if (e.sensor.type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) e.values[2] - e.values[5] else e.values[2]
                lastAcc = floatArrayOf(ax, ay, az)
                val norm = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
                if (!quatInit && norm > 5f) initFromAccel(ax / norm, ay / norm, az / norm)
                if (quatInit && norm in 8.2f..11.4f) {
                    val ux = (ax / norm).toDouble(); val uy = (ay / norm).toDouble(); val uz = (az / norm).toDouble()
                    val vx = 2.0 * (qx * qz - qw * qy); val vy = 2.0 * (qy * qz + qw * qx); val vz = qw * qw - qx * qx - qy * qy + qz * qz
                    val kp = if (hasGyro) 0.02 else 0.25
                    integrateQuat((kp * (uy * vz - uz * vy)).toFloat(), (kp * (uz * vx - ux * vz)).toFloat(), (kp * (ux * vy - uy * vx)).toFloat(), 1.0)
                }
                if (!hasHardwareGravity) {
                    if (quatInit) pushGrav(e.timestamp, (2.0 * (qx * qz - qw * qy) * 9.81).toFloat(),
                        (2.0 * (qy * qz + qw * qx) * 9.81).toFloat(), ((qw * qw - qx * qx - qy * qy + qz * qz) * 9.81).toFloat())
                    else pushGrav(e.timestamp, ax, ay, az)
                }
                if (!hasHardwareRot && !hasGyro) updateFallbackRotation()
            }
            Sensor.TYPE_GYROSCOPE -> { hasCalibratedGyro = true; processGyro(e.timestamp, e.values[0], e.values[1], e.values[2]) }
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                if (!hasCalibratedGyro && e.values.size >= 6)
                    processGyro(e.timestamp, e.values[0] - e.values[3], e.values[1] - e.values[4], e.values[2] - e.values[5])
            }
            Sensor.TYPE_MAGNETIC_FIELD -> { lastMag = e.values.copyOf(3); if (!hasHardwareRot && !hasGyro) updateFallbackRotation() }
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> { hasHardwareRot = true; rotationVector = e.values.copyOf() }
            Sensor.TYPE_PRESSURE -> {
                val p = e.values[0]
                if (p > 100f) { if (p0Hpa == 0f) p0Hpa = p; baroAltitudeM = (44330.0 * (1.0 - (p / p0Hpa).toDouble().pow(0.190295))).toFloat() }
            }
        }
    }

    private fun processGyro(tNs: Long, wx: Float, wy: Float, wz: Float) {
        hasGyro = true
        val rawRate = sqrt(wx * wx + wy * wy + wz * wz)
        val accNorm = sqrt(lastAcc[0] * lastAcc[0] + lastAcc[1] * lastAcc[1] + lastAcc[2] * lastAcc[2])
        if (rawRate < 0.06f && abs(accNorm - 9.81f) < 0.35f) {
            bx = 0.995f * bx + 0.005f * wx; by = 0.995f * by + 0.005f * wy; bz = 0.995f * bz + 0.005f * wz
        }
        val cx = wx - bx; val cy = wy - by; val cz = wz - bz
        gyroRate = 0.7f * gyroRate + 0.3f * sqrt(cx * cx + cy * cy + cz * cz)
        if (lastGyroNs != 0L) {
            val dt = ((tNs - lastGyroNs) * 1e-9).coerceIn(0.0, 0.1)
            if (quatInit && dt > 0.0) integrateQuat(cx, cy, cz, dt)
        }
        lastGyroNs = tNs
        gyroBuf.addLast(Sample(tNs, cx, cy, cz))
        while (gyroBuf.size > 250) gyroBuf.removeFirst()
        if (!hasHardwareRot && quatInit) rotationVector = floatArrayOf(qx.toFloat(), qy.toFloat(), qz.toFloat(), qw.toFloat())
    }

    private fun initFromAccel(ux: Float, uy: Float, uz: Float) {
        val s = sqrt((1.0 + uz.toDouble().coerceIn(-1.0, 1.0)) * 2.0)
        if (s > 1e-6) { qw = 0.5 * s; qx = uy / s; qy = -ux / s; qz = 0.0; normQuat(); quatInit = true }
    }

    private fun integrateQuat(wx: Float, wy: Float, wz: Float, dt: Double) {
        val hx = 0.5 * wx * dt; val hy = 0.5 * wy * dt; val hz = 0.5 * wz * dt
        val nw = qw - qx * hx - qy * hy - qz * hz; val nx = qx + qw * hx + qy * hz - qz * hy
        val ny = qy + qw * hy - qx * hz + qz * hx; val nz = qz + qw * hz + qx * hy - qy * hx
        qw = nw; qx = nx; qy = ny; qz = nz; normQuat()
    }

    private fun normQuat() {
        val n = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        if (n > 1e-12) { qw /= n; qx /= n; qy /= n; qz /= n }
    }

    private fun updateFallbackRotation() {
        val mag = lastMag ?: return
        val r = FloatArray(9)
        if (SensorManager.getRotationMatrix(r, null, lastAcc, mag)) {
            val tr = r[0] + r[4] + r[8]
            if (tr > 0f) {
                val s = sqrt(tr + 1.0f) * 2f
                rotationVector = floatArrayOf((r[7] - r[5]) / s, (r[2] - r[6]) / s, (r[3] - r[1]) / s, 0.25f * s)
            }
        }
    }

    private fun pushGrav(tNs: Long, x: Float, y: Float, z: Float) {
        gravBuf.addLast(Sample(tNs, x, y, z))
        while (gravBuf.size > 250) gravBuf.removeFirst()
    }

    fun gravityAt(tMidNs: Long): FloatArray {
        if (gravBuf.isEmpty()) return lastAcc.copyOf()
        val t = if (abs(tMidNs - gravBuf.last().tNs) > 5_000_000_000L) gravBuf.last().tNs else tMidNs
        val best = gravBuf.minByOrNull { abs(it.tNs - t) } ?: gravBuf.last()
        return floatArrayOf(best.x, best.y, best.z)
    }

    fun gyroOverWindow(tStartNs: Long, tEndNs: Long): FloatArray? {
        if (!hasGyro || gyroBuf.isEmpty()) return null
        val last = gyroBuf.last().tNs
        val shift = if (abs(tStartNs - last) > 5_000_000_000L) last - (tStartNs + tEndNs) / 2 else 0L
        val a = tStartNs + shift - 5_000_000L; val b = tEndNs + shift + 5_000_000L
        var sx = 0f; var sy = 0f; var sz = 0f; var n = 0
        for (s in gyroBuf) if (s.tNs in a..b) { sx += s.x; sy += s.y; sz += s.z; ++n }
        if (n > 0) return floatArrayOf(sx / n, sy / n, sz / n)
        val nearest = gyroBuf.minByOrNull { abs(it.tNs - ((tStartNs + tEndNs) / 2 + shift)) } ?: return null
        return floatArrayOf(nearest.x, nearest.y, nearest.z)
    }

    fun cameraQuat(sensorOrientation: Int): FloatArray? {
        if (!quatInit && rotationVector == null) return null
        val th = Math.toRadians(sensorOrientation.toDouble())
        val c = cos(th / 2.0); val s = sin(th / 2.0)
        return floatArrayOf((-(qx * c - qy * s)).toFloat(), (qw * c + qz * s).toFloat(), (-qw * s + qz * c).toFloat(), (-qx * s - qy * c).toFloat())
    }
}

/**
 * Camera2 capture: one YUV stream per lens (two for a rig), live keyframe selection in native
 * code, keyframes saved as JPEG with their metadata. All camera work runs on [thread].
 */
class CaptureController(
    private val context: Context,
    private val onPreview: (Bitmap) -> Unit,
    private val onState: (LiveState) -> Unit,
    private val onError: (String) -> Unit,
    private val onFallback: (CaptureMode, CaptureMode) -> Unit = { _, _ -> },
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
    // Rig: the most recent secondary frames, and a keyframe waiting for its secondary frame.
    // The two streams' callbacks arrive in either order, so neither side may assume the other.
    private val recentSecondary = ArrayDeque<Image>()
    private var pendingSecondary: PendingSecondary? = null
    // Keyframes are encoded and written off the camera thread, so tracking never stalls.
    private var writer: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "parallax-writer") }
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
    private val out = FloatArray(8)
    private val pts = FloatArray(4 * 300)
    private var afState: Int? = null
    private var scanStartMs = 0L
    private val previews = arrayOfNulls<Bitmap>(3)
    private var previewIndex = 0

    private val imu = ImuTracker()
    private var rsSkewNs = 0L
    private var azimuth0 = 0.0
    private val coverage = BooleanArray(Coverage.BINS)

    fun open(m: CaptureMode) {
        close()
        if (writer.isShutdown) writer = Executors.newSingleThreadExecutor { r -> Thread(r, "parallax-writer") }
        mode = m
        thread = HandlerThread("parallax-camera").also { it.start() }
        handler = Handler(thread!!.looper)
        for (type in intArrayOf(
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            Sensor.TYPE_GRAVITY, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_PRESSURE,
        )) {
            sensors.getDefaultSensor(type)?.let { s ->
                runCatching { sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, handler) }
                    .onFailure { sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME, handler) }
            }
        }
        handler!!.post { openCamera(m) }
    }

    fun close() {
        sensors.unregisterListener(this)
        val h = handler ?: return
        val w = writer
        h.post {
            if (scanning) finishScan()
            runCatching { session?.close() }
            runCatching { device?.close() }
            recentSecondary.forEach { it.close() }
            recentSecondary.clear()
            pendingSecondary = null
            w.shutdown()
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
        imu.resetScanOrigin()
        coverage.fill(false)
        azimuth0 = imu.rotationVector?.let { Coverage.azimuth(it) } ?: 0.0
        scanning = true
        scanStartMs = SystemClock.elapsedRealtime()
        afState = null
        pendingSecondary = null
        triggerFocusLock()
        repeat()
        val snapshot = images.toList()
        writer.execute { s.write(cameras, snapshot) }
        publish("Focusing…")
    }

    /** Stops the scan and returns it (null if nothing was captured) on the main thread. */
    fun stopScan(done: (Scan?) -> Unit) = handler?.post {
        val s = finishScan()
        keyframes = 0
        main.post { done(s) }
    }

    private fun finishScan(): Scan? {
        if (!scanning) return null
        scanning = false
        repeat(cancelFocus = true)
        val s = scan
        scan = null
        pendingSecondary = null
        val snapshot = images.toList()
        // Wait for queued keyframes: reconstruction must see every file.
        runCatching { writer.submit { s?.write(cameras, snapshot) }.get() }
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
        if (secondary != null) secondaryReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4).apply {
            setOnImageAvailableListener({ r -> r.acquireLatestImage()?.let { onSecondary(it) } }, handler)
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
        primaryReader?.close(); secondaryReader?.close()
        recentSecondary.forEach { it.close() }
        recentSecondary.clear()
        primaryReader = null; secondaryReader = null
        if (next == null) { main.post { onError("This camera configuration is not supported") }; return }
        mode = next
        main.post {
            onFallback(m, next)
            onError("${m.label} is not available on this phone; using ${next.label}")
        }
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
                s.capture(b.build(), resultCallback, handler)
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
            rsSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: rsSkewNs
            afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: afState
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
                val steady = imu.gyroRate < 0.8f
                // Wait for the focus trigger to settle (or 800 ms), so every keyframe shares one focus.
                val focused = !hasAutofocus(m.primary.chars) || now - scanStartMs > 800 ||
                    afState == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    afState == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                hint = when {
                    !focused -> "Focusing…"
                    out[2] < 25f -> "Too dark"
                    out[4] > 0.15f -> "Glare: tilt away from reflections"
                    !steady -> "Slow down"
                    out[7] < 0.5f -> "Hold steady: the image is blurred"
                    lastTracked < 40 -> "Point at a textured object"
                    else -> "Move slowly around the object"
                }
                expirePendingSecondary(now)
                if (focused && out[5] > 0.5f && steady && now - lastKeyframeMs > 350) {
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

    private data class PendingSecondary(val group: Int, val timestamp: Long, val deadlineMs: Long, val image: ScanImage)

    /** A copy of an Image's planes, so the Image can go back to the camera immediately. */
    private class YuvCopy(img: Image) {
        val width = img.width
        val height = img.height
        val yStride = img.planes[0].rowStride
        val uvStride = img.planes[1].rowStride
        val uvPixelStride = img.planes[1].pixelStride
        val y = copy(img.planes[0].buffer)
        val u = copy(img.planes[1].buffer)
        val v = copy(img.planes[2].buffer)

        private fun copy(b: ByteBuffer): ByteBuffer {
            val src = b.duplicate().also { it.rewind() }
            return ByteBuffer.allocateDirect(src.remaining()).put(src).also { it.rewind() }
        }

        fun saveJpeg(f: File) = NativeCore.saveJpeg(y, u, v, width, height, yStride, uvStride, uvPixelStride, f.absolutePath, 95)
    }

    private fun saveKeyframe(primary: Image) {
        val s = scan ?: return
        val tStart = primary.timestamp
        val tEnd = tStart + exposureNs + rsSkewNs
        val tMid = tStart + exposureNs / 2 + rsSkewNs / 2
        val gDev = imu.gravityAt(tMid)
        val w = imu.gyroOverWindow(tStart, tEnd)
        val up = toCamera(cameras[0].sensorOrientation, gDev)
        val group = keyframes
        val name = "p_%04d.jpg".format(group)
        val copy = YuvCopy(primary)
        writer.execute { copy.saveJpeg(File(s.imagesDir, name)) }
        images += ScanImage(name, 0, if (rig) group else -1, up, tMid,
            exposureNs, iso, focusDiopters, torchOn, rsSkewNs,
            w?.let { toCamera(cameras[0].sensorOrientation, it) },
            imu.cameraQuat(cameras[0].sensorOrientation), imu.baroAltitudeM)
        imu.rotationVector?.let {
            if (group == 0) azimuth0 = Coverage.azimuth(it)
            coverage[Coverage.bin(it, azimuth0)] = true
        }
        if (rig) {
            val meta = ScanImage("s_%04d.jpg".format(group), 1, group, toCamera(cameras[1].sensorOrientation, gDev),
                tMid, exposureNs, iso, focusDiopters, torchOn, rsSkewNs,
                w?.let { toCamera(cameras[1].sensorOrientation, it) },
                imu.cameraQuat(cameras[1].sensorOrientation), imu.baroAltitudeM)
            val match = recentSecondary.firstOrNull { abs(it.timestamp - primary.timestamp) < SYNC_NS }
            if (match != null) {
                writeSecondary(match, meta)
                recentSecondary.remove(match)
                match.close()
            } else pendingSecondary = PendingSecondary(group, primary.timestamp, SystemClock.elapsedRealtime() + 200, meta)
        }
        keyframes++
        NativeCore.trackerMarkKeyframe()
        val snapshot = images.toList()
        writer.execute { s.write(cameras, snapshot) }
    }

    /** Secondary frames: complete a waiting keyframe, else keep the two most recent. */
    private fun onSecondary(img: Image) {
        val p = pendingSecondary
        if (scanning && p != null && abs(img.timestamp - p.timestamp) < SYNC_NS) {
            writeSecondary(img, p.image)
            pendingSecondary = null
            img.close()
            scan?.let { s -> val snapshot = images.toList(); writer.execute { s.write(cameras, snapshot) } }
            return
        }
        recentSecondary.addLast(img)
        while (recentSecondary.size > 2) recentSecondary.removeFirst().close()
    }

    private fun writeSecondary(img: Image, meta: ScanImage) {
        val s = scan ?: return
        val copy = YuvCopy(img)
        writer.execute { copy.saveJpeg(File(s.imagesDir, meta.file)) }
        images += meta.copy(timestampNs = img.timestamp + exposureNs / 2 + rsSkewNs / 2)
    }

    private fun expirePendingSecondary(now: Long) {
        if ((pendingSecondary?.deadlineMs ?: Long.MAX_VALUE) < now) pendingSecondary = null
    }

    /** Sensor-frame normalised points -> display frame (rotated clockwise by [rot]). */
    private fun rotatePoints(n: Int, rot: Int): FloatArray {
        // pts holds x, y, anchorX, anchorY per point; rotate both positions.
        val r = FloatArray(4 * n)
        for (k in 0 until 2 * n) {
            val u = pts[2 * k]
            val v = pts[2 * k + 1]
            val (x, y) = when (rot) {
                90 -> 1f - v to u
                180 -> 1f - u to 1f - v
                270 -> v to 1f - u
                else -> u to v
            }
            r[2 * k] = x; r[2 * k + 1] = y
        }
        return r
    }

    /** A device-frame vector (gravity, angular rate) in the rear camera's OpenCV axes. */
    private fun toCamera(orientation: Int, v: FloatArray): FloatArray {
        val th = Math.toRadians(orientation.toDouble())
        val c = cos(th).toFloat()
        val s = sin(th).toFloat()
        return floatArrayOf(c * v[0] - s * v[1], -s * v[0] - c * v[1], -v[2])
    }

    private fun publish(newHint: String?) {
        val heading = imu.rotationVector?.let { Coverage.bin(it, azimuth0) } ?: -1
        val st = LiveState(lastPoints, lastProgress, lastTracked, keyframes, scanning, torchOn,
            newHint ?: hint, mode?.label ?: "", rig && cameras.size == 2 && cameras.all { it.position != null },
            coverage.copyOf(), heading)
        main.post { onState(st) }
    }

    // ---- Sensors (main thread) --------------------------------------------------------------

    override fun onSensorChanged(e: SensorEvent) = imu.onSensor(e)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
