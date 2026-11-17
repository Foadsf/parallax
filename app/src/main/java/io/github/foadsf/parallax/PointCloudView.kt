package io.github.foadsf.parallax

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Interleaved vertices, 20 bytes: x, y, z (float), r, g, b, a (ubyte), nx, ny, nz (signed byte,
 * normalised; zero for lines and for points without a normal), pad.
 */
const val STRIDE = 20

private fun ByteBuffer.putVertex(x: Float, y: Float, z: Float, rgba: Int, nx: Float = 0f, ny: Float = 0f, nz: Float = 0f) {
    putFloat(x).putFloat(y).putFloat(z)
    put((rgba ushr 24).toByte()).put((rgba ushr 16).toByte()).put((rgba ushr 8).toByte()).put(rgba.toByte())
    put((nx * 127f).toInt().toByte()).put((ny * 127f).toInt().toByte()).put((nz * 127f).toInt().toByte()).put(0)
}

/** [floorZ]: low percentile of z, where the ground grid goes (the model is Z-up). */
class Cloud(val data: ByteBuffer, val count: Int, val centre: FloatArray, val radius: Float, val floorZ: Float = 0f) {
    companion object {
        /** Reads the binary PLY written by the native core (x y z nx ny nz red green blue). */
        fun readPly(f: File): Cloud? = runCatching {
            DataInputStream(BufferedInputStream(f.inputStream(), 1 shl 16)).use { input ->
                var n = 0
                val header = StringBuilder()
                while (true) {
                    val line = StringBuilder()
                    while (true) {
                        val c = input.read()
                        if (c < 0 || c == '\n'.code) break
                        line.append(c.toChar())
                    }
                    header.append(line).append('\n')
                    if (line.startsWith("element vertex")) n = line.substring(15).trim().toInt()
                    if (line.toString().trim() == "end_header" || header.length > 4096) break
                }
                if (n <= 0) return null
                val raw = ByteArray(n * 27)
                input.readFully(raw)
                val src = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                val dst = ByteBuffer.allocateDirect(n * STRIDE).order(ByteOrder.nativeOrder())
                val xs = FloatArray(n); val ys = FloatArray(n); val zs = FloatArray(n)
                for (i in 0 until n) {
                    val o = i * 27
                    val x = src.getFloat(o); val y = src.getFloat(o + 4); val z = src.getFloat(o + 8)
                    xs[i] = x; ys[i] = y; zs[i] = z
                    val rgba = ((raw[o + 24].toInt() and 0xFF) shl 24) or ((raw[o + 25].toInt() and 0xFF) shl 16) or
                        ((raw[o + 26].toInt() and 0xFF) shl 8) or 0xFF
                    dst.putVertex(x, y, z, rgba, src.getFloat(o + 12), src.getFloat(o + 16), src.getFloat(o + 20))
                }
                dst.flip()
                // quantile() sorts, so give it copies: xs/ys/zs must stay paired by index below.
                val centre = floatArrayOf(quantile(xs.copyOf(), 0.5f), quantile(ys.copyOf(), 0.5f), quantile(zs.copyOf(), 0.5f))
                val floorZ = quantile(zs.copyOf(), 0.02f)
                val d = FloatArray(n) { i ->
                    val dx = xs[i] - centre[0]; val dy = ys[i] - centre[1]; val dz = zs[i] - centre[2]
                    sqrt(dx * dx + dy * dy + dz * dz)
                }
                val radius = if (n == 0) 1f else quantile(d, 0.9f).coerceAtLeast(1e-4f)
                Cloud(dst, n, centre, radius, floorZ)
            }
        }.getOrNull()

        /** Sorts in place (primitive array: no boxing of millions of floats). */
        private fun quantile(v: FloatArray, q: Float): Float {
            if (v.isEmpty()) return 0f
            v.sort()
            return v[((v.size - 1) * q).toInt()]
        }

        /** Ground grid at [z] around the cloud: 10 cm lines when the scale is metric. */
        fun grid(c: Cloud, metric: Boolean): Cloud {
            val half = c.radius * 1.6f
            val step = if (metric) {
                listOf(0.01f, 0.05f, 0.1f, 0.5f, 1f).firstOrNull { half * 2 / it <= 30 } ?: 1f
            } else half / 6
            val n = (half / step).toInt()
            val lines = ByteBuffer.allocateDirect((4 * (2 * n + 1)) * STRIDE).order(ByteOrder.nativeOrder())
            val cx = c.centre[0]; val cy = c.centre[1]; val z = c.floorZ
            for (k in -n..n) {
                val a = 0x343B44FF
                lines.putVertex(cx + k * step, cy - n * step, z, a); lines.putVertex(cx + k * step, cy + n * step, z, a)
                lines.putVertex(cx - n * step, cy + k * step, z, a); lines.putVertex(cx + n * step, cy + k * step, z, a)
            }
            lines.flip()
            return Cloud(lines, 4 * (2 * n + 1), c.centre, c.radius)
        }

    }
}

/** Camera frustums as line segments, from a COLMAP text model (cameras.txt, images.txt). */
object Frusta {
    fun fromColmap(dir: File, depth: Float): Cloud? = runCatching {
        val cams = HashMap<Int, FloatArray>()  // id -> w, h, fx, fy, cx, cy
        File(dir, "cameras.txt").forEachLine { l ->
            if (l.startsWith("#") || l.isBlank()) return@forEachLine
            val f = l.trim().split(Regex("\\s+"))
            cams[f[0].toInt()] = floatArrayOf(f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), f[5].toFloat(), f[6].toFloat(), f[7].toFloat())
        }
        val lines = ArrayList<Float>()
        var header = true
        File(dir, "images.txt").forEachLine { l ->
            if (l.startsWith("#")) return@forEachLine
            if (!header) { header = true; return@forEachLine }  // the 2D-points line
            header = false
            val f = l.trim().split(Regex("\\s+"))
            if (f.size < 10) return@forEachLine
            val (qw, qx, qy, qz) = listOf(f[1], f[2], f[3], f[4]).map { it.toFloat() }
            val t = floatArrayOf(f[5].toFloat(), f[6].toFloat(), f[7].toFloat())
            val c = cams[f[8].toInt()] ?: return@forEachLine
            // World-to-camera rotation from the unit quaternion.
            val r = floatArrayOf(
                1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
                2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
                2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy))
            // Camera to world: X = R^T (p - t).
            fun world(px: Float, py: Float, pz: Float): FloatArray {
                val a = px - t[0]; val b = py - t[1]; val d = pz - t[2]
                return floatArrayOf(r[0] * a + r[3] * b + r[6] * d, r[1] * a + r[4] * b + r[7] * d, r[2] * a + r[5] * b + r[8] * d)
            }
            val centre = world(0f, 0f, 0f)
            val corners = listOf(0f to 0f, c[0] to 0f, c[0] to c[1], 0f to c[1]).map { (u, v) ->
                world((u - c[4]) / c[2] * depth, (v - c[5]) / c[3] * depth, depth)
            }
            for (k in 0 until 4) {
                lines.addAll(centre.toList()); lines.addAll(corners[k].toList())
                lines.addAll(corners[k].toList()); lines.addAll(corners[(k + 1) % 4].toList())
            }
        }
        val n = lines.size / 3
        val dst = ByteBuffer.allocateDirect(n * STRIDE).order(ByteOrder.nativeOrder())
        for (i in 0 until n) dst.putVertex(lines[3 * i], lines[3 * i + 1], lines[3 * i + 2], 0x5EEAD4FF)
        dst.flip()
        Cloud(dst, n, floatArrayOf(0f, 0f, 0f), 1f)
    }.getOrNull()
}

@SuppressLint("ViewConstructor")
class PointCloudView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private class Scene(val cloud: Cloud?, val frusta: Cloud?, val grid: Cloud?)
    @Volatile private var pending: Scene? = null
    private var cloud: Cloud? = null
    private var frusta: Cloud? = null
    private var grid: Cloud? = null
    private val vbo = IntArray(3)

    /** Point size multiplier, set from the UI. */
    @Volatile var pointScale = 1f
        set(v) { field = v; requestRender() }
    @Volatile var showFrusta = true
        set(v) { field = v; requestRender() }
    private var program = 0
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private var aspect = 1f
    private var heightPx = 1

    // Orbit camera, Z up.
    @Volatile private var yaw = -1.2f
    @Volatile private var pitch = 0.45f
    @Volatile private var distance = 3f
    @Volatile private var target = floatArrayOf(0f, 0f, 0f)
    private var home = floatArrayOf(0f, 0f, 0f)
    private var homeDistance = 3f

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** [cams]: frustum line segments; [metric]: the model is in metres (grid in 10 cm steps). */
    fun show(c: Cloud?, cams: Cloud?, metric: Boolean = false, resetCamera: Boolean = true) {
        if (c != null && resetCamera) {
            home = c.centre.copyOf()
            target = home.copyOf()
            homeDistance = c.radius * 3.2f
            distance = homeDistance
        }
        pending = Scene(c, cams, c?.let { Cloud.grid(it, metric) })
        requestRender()
    }

    fun resetView() {
        target = home.copyOf(); distance = homeDistance; yaw = -1.2f; pitch = 0.45f
        requestRender()
    }

    // ---- Gestures ---------------------------------------------------------------------------

    private var lastX = 0f
    private var lastY = 0f
    private var lastPointers = 0
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            distance = (distance / d.scaleFactor).coerceIn(homeDistance * 0.05f, homeDistance * 8f)
            requestRender()
            return true
        }
    })
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { resetView(); return true }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        taps.onTouchEvent(e)
        var cx = 0f
        var cy = 0f
        for (i in 0 until e.pointerCount) { cx += e.getX(i); cy += e.getY(i) }
        cx /= e.pointerCount; cy /= e.pointerCount
        if (e.actionMasked == MotionEvent.ACTION_MOVE && e.pointerCount == lastPointers) {
            val dx = cx - lastX
            val dy = cy - lastY
            if (e.pointerCount == 1) {
                yaw -= dx * 0.008f
                pitch = (pitch + dy * 0.008f).coerceIn(-1.5f, 1.5f)
            } else {
                // Pan in the view plane.
                val k = distance / heightPx * 1.2f
                val rx = -sin(yaw); val ry = cos(yaw)
                val ux = -sin(pitch) * cos(yaw); val uy = -sin(pitch) * sin(yaw); val uz = cos(pitch)
                target = floatArrayOf(
                    target[0] - (rx * dx - ux * dy) * k,
                    target[1] - (ry * dx - uy * dy) * k,
                    target[2] + uz * dy * k,
                )
            }
            requestRender()
        }
        lastX = cx; lastY = cy; lastPointers = e.pointerCount
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP || e.actionMasked == MotionEvent.ACTION_UP) lastPointers = 0
        return true
    }

    // ---- Rendering --------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.043f, 0.051f, 0.063f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        program = link(VS, FS)
        GLES30.glGenBuffers(3, vbo, 0)
        if (cloud != null) pending = Scene(cloud, frusta, grid)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
        heightPx = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        pending?.let { sc ->
            pending = null
            cloud = sc.cloud; frusta = sc.frusta; grid = sc.grid
            upload(0, cloud); upload(1, frusta); upload(2, grid)
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val c = cloud ?: return
        val t = target
        val ex = t[0] + distance * cos(pitch) * cos(yaw)
        val ey = t[1] + distance * cos(pitch) * sin(yaw)
        val ez = t[2] + distance * sin(pitch)
        Matrix.perspectiveM(proj, 0, 45f, aspect, distance * 0.01f, distance * 20f)
        Matrix.setLookAtM(view, 0, ex, ey, ez, t[0], t[1], t[2], 0f, 0f, 1f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(program, "uEye"), ex, ey, ez)
        // World-space point diameter, about 0.4 % of the cloud radius, projected to pixels.
        val pxPerUnit = heightPx / (2f * 0.4142f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uSize"), c.radius * 0.008f * pxPerUnit * pointScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uPoints"), 0f)
        GLES30.glLineWidth(1f)
        grid?.let { draw(2, it.count, GLES30.GL_LINES) }
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uPoints"), 1f)
        draw(0, c.count, GLES30.GL_POINTS)
        frusta?.takeIf { showFrusta }?.let {
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uPoints"), 0f)
            GLES30.glLineWidth(2f)
            draw(1, it.count, GLES30.GL_LINES)
        }
    }

    private fun upload(slot: Int, c: Cloud?) {
        if (c == null) return
        c.data.rewind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[slot])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, c.count * STRIDE, c.data, GLES30.GL_STATIC_DRAW)
    }

    private fun draw(slot: Int, count: Int, mode: Int) {
        if (count == 0) return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[slot])
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_UNSIGNED_BYTE, true, STRIDE, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_BYTE, true, STRIDE, 16)
        GLES30.glDrawArrays(mode, 0, count)
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String) = GLES30.glCreateShader(type).also {
            GLES30.glShaderSource(it, src)
            GLES30.glCompileShader(it)
        }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, compile(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(it, compile(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(it)
        }
    }

    private companion object {
        const val VS = """#version 300 es
uniform mat4 uMvp;
uniform float uSize;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aColor;
layout(location = 2) in vec3 aNormal;
out vec4 vColor;
out vec3 vNormal;
out vec3 vPos;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    gl_PointSize = clamp(uSize / gl_Position.w, 1.5, 32.0);
    vColor = aColor;
    vNormal = aNormal;
    vPos = aPos;
}"""
        const val FS = """#version 300 es
precision mediump float;
uniform float uPoints;
uniform vec3 uEye;
in vec4 vColor;
in vec3 vNormal;
in vec3 vPos;
out vec4 frag;
void main() {
    if (uPoints < 0.5) { frag = vColor; return; }  // lines
    vec2 d = gl_PointCoord - vec2(0.5);
    float r2 = dot(d, d);
    if (r2 > 0.25) discard;
    if (dot(vNormal, vNormal) < 0.25) {  // no normal (sparse points): sphere-like shading
        frag = vec4(vColor.rgb * (1.0 - 0.6 * r2), 1.0);
        return;
    }
    // Surface normal, turned towards the viewer; key light from above plus sky/ground ambient.
    vec3 n = normalize(vNormal);
    if (dot(n, uEye - vPos) < 0.0) n = -n;
    vec3 l = normalize(vec3(0.35, -0.3, 0.88));
    float hemi = 0.5 + 0.5 * n.z;
    float diff = max(dot(n, l), 0.0);
    frag = vec4(vColor.rgb * (0.45 + 0.2 * hemi + 0.45 * diff) * (1.0 - 0.25 * r2), 1.0);
}"""
    }
}
