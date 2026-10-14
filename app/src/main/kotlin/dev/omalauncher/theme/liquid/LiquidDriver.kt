package dev.omalauncher.theme.liquid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How the liquid behaves, from style.toml's `liquid.*`. */
data class LiquidSettings(
    /** Gravity in m/s²; lower is calmer and slower. */
    val gravity: Float = 10f,
    /** How much the pool rocks by itself, 0 for none. */
    val wobble: Float = 0.6f,
    /** How much shaking the phone slops it, 0 to ignore. */
    val shake: Float = 0.25f,
    /** How often it's drawn; the physics always runs at 60 steps a second. */
    val fps: Int = 30,
)

/**
 * Runs a [LiquidSim] for the live wallpaper, on a thread of its own so it never takes time from
 * the launcher's UI. Tilt and shake come from the sensors and the finger from [touch]. It runs only while [setVisible] says the wallpaper is on screen.
 */
class LiquidDriver(
    context: Context,
    private val holder: () -> SurfaceHolder,
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("liquid-wallpaper").apply { start() }
    private val handler = Handler(thread.looper)
    private val paint = Paint().apply { isFilterBitmap = false }

    // Everything below is touched only on the driver's thread, except what's @Volatile.
    private var sim: LiquidSim? = null
    private var renderer: LiquidRenderer? = null
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private var settings = LiquidSettings()
    private var palette = LiquidPalette.Default
    private var surfaceW = 0
    private var surfaceH = 0
    private var running = false
    private var visible = false
    private var last = 0L
    private var lastDraw = 0L
    private var accumulator = 0L
    private var time = 0f

    // Tilt, filtered: gravity as a unit-ish vector on screen, y up, plus the shake on top.
    @Volatile private var tiltX = 0f

    @Volatile private var tiltY = -1f

    @Volatile private var shakeX = 0f

    @Volatile private var shakeY = 0f

    @Volatile private var fingerOn = false

    @Volatile private var fingerX = 0f

    @Volatile private var fingerY = 0f
    private var prevFingerX = 0f
    private var prevFingerY = 0f
    private var fingerVx = 0f
    private var fingerVy = 0f
    private var wasFingerOn = false

    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val linear = sensors?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private var haveTilt = false

    fun configure(
        settings: LiquidSettings,
        palette: LiquidPalette,
    ) {
        handler.post {
            val recolor = palette != this.palette
            this.settings = settings
            this.palette = palette
            if (recolor) rebuild()
        }
    }

    fun resize(
        width: Int,
        height: Int,
    ) {
        handler.post {
            surfaceW = width
            surfaceH = height
            rebuild()
        }
    }

    fun setVisible(visible: Boolean) {
        handler.post {
            this.visible = visible
            update()
        }
    }

    /** A finger down or moving at ([fx], [fy]), fractions of the screen; [on] false when it lifts. */
    fun touch(
        on: Boolean,
        fx: Float,
        fy: Float,
    ) {
        fingerX = fx
        fingerY = fy
        fingerOn = on
    }

    /** Stops everything for good, as the wallpaper engine is destroyed. */
    fun destroy() {
        handler.post {
            visible = false
            update()
            thread.quitSafely()
        }
    }

    private fun rebuild() {
        if (surfaceW <= 0 || surfaceH <= 0) return
        val aspect = surfaceW.toFloat() / surfaceH
        val h = RENDER_HEIGHT
        val w = Math.round(h * aspect)
        // A new pool when the shape of the screen changes; a recolor keeps the one running.
        if (sim == null || renderer?.width != w || renderer?.height != h) {
            sim = LiquidSim(aspect)
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            pixels = IntArray(w * h)
        }
        renderer = LiquidRenderer(w, h, palette)
        update()
        if (running) draw()
    }

    private fun update() {
        val shouldRun = visible && sim != null
        if (shouldRun == running) return
        running = shouldRun
        if (running) {
            sensors?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME, handler)
            sensors?.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME, handler)
            last = System.nanoTime()
            lastDraw = 0
            accumulator = 0
            handler.post(tick)
        } else {
            sensors?.unregisterListener(this)
            handler.removeCallbacks(tick)
        }
    }

    private val tick =
        object : Runnable {
            override fun run() {
                if (!running) return
                val sim = sim ?: return
                val now = System.nanoTime()
                val elapsed = min(now - last, MAX_CATCH_UP_NS)
                last = now
                accumulator += elapsed
                updateFinger(sim, elapsed / 1e9f)
                var steps = 0
                while (accumulator >= STEP_NS && steps < MAX_STEPS) {
                    val g = settings.gravity
                    val wobble = settings.wobble * (0.022f * sin(time * 0.45f) + 0.012f * sin(time * 1.1f + 1f))
                    sim.step((tiltX + wobble) * g + shakeX * g * settings.shake, tiltY * g + shakeY * g * settings.shake)
                    time += LiquidSim.DT
                    accumulator -= STEP_NS
                    steps++
                }
                if (now - lastDraw >= 1_000_000_000L / max(settings.fps, 1) - 2_000_000L) {
                    lastDraw = now
                    draw()
                }
                handler.postDelayed(this, FRAME_DELAY_MS)
            }
        }

    /** Hands the finger to the sim, with the speed it moved at since the last tick. */
    private fun updateFinger(
        sim: LiquidSim,
        dt: Float,
    ) {
        val on = fingerOn
        val x = fingerX
        val y = fingerY
        if (on && wasFingerOn && dt > 0f) {
            fingerVx += ((x - prevFingerX) / dt - fingerVx) * 0.5f
            fingerVy += ((y - prevFingerY) / dt - fingerVy) * 0.5f
        } else {
            fingerVx = 0f
            fingerVy = 0f
        }
        prevFingerX = x
        prevFingerY = y
        wasFingerOn = on
        // However fast the finger really goes, it never moves through the pool faster than this: a flick would make waves, not swirls.
        sim.finger(on, x, y, fingerVx.coerceIn(-MAX_FINGER_SPEED, MAX_FINGER_SPEED), fingerVy.coerceIn(-MAX_FINGER_SPEED, MAX_FINGER_SPEED))
    }

    private fun draw() {
        val renderer = renderer ?: return
        val sim = sim ?: return
        val bmp = bitmap ?: return
        renderer.render(sim, pixels)
        bmp.setPixels(pixels, 0, renderer.width, 0, 0, renderer.width, renderer.height)
        val surface = runCatching { holder() }.getOrNull() ?: return
        val canvas = runCatching { surface.lockHardwareCanvas() }.getOrNull() ?: return
        try {
            canvas.drawBitmap(bmp, null, Rect(0, 0, canvas.width, canvas.height), paint)
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            // The accelerometer reads the reaction to gravity: an upright phone reads +y, so gravity on screen is minus that.
            Sensor.TYPE_ACCELEROMETER -> {
                val gx = -v[0] / GRAVITY_EARTH
                val gy = -v[1] / GRAVITY_EARTH
                if (!haveTilt) {
                    tiltX = gx
                    tiltY = gy
                    haveTilt = true
                } else {
                    // Low-pass: hand tremor and sensor noise would otherwise keep the surface jittering.
                    tiltX += (gx - tiltX) * TILT_SMOOTHING
                    tiltY += (gy - tiltY) * TILT_SMOOTHING
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // The phone speeding up pushes the liquid the other way; small jiggles are ignored.
                val tx = -deadzone(v[0] / GRAVITY_EARTH).coerceIn(-1.5f, 1.5f)
                val ty = -deadzone(v[1] / GRAVITY_EARTH).coerceIn(-1.5f, 1.5f)
                shakeX += (tx - shakeX) * 0.35f
                shakeY += (ty - shakeY) * 0.35f
            }
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    private fun deadzone(x: Float): Float {
        val m = abs(x) - 0.12f
        return if (m > 0f) Math.signum(x) * m else 0f
    }

    private companion object {
        const val RENDER_HEIGHT = 234
        const val STEP_NS = 16_666_667L
        const val MAX_STEPS = 6
        const val MAX_CATCH_UP_NS = 100_000_000L
        const val FRAME_DELAY_MS = 8L
        const val GRAVITY_EARTH = 9.81f
        const val MAX_FINGER_SPEED = 1.2f
        const val TILT_SMOOTHING = 0.06f
    }
}
