package dev.omalauncher.theme.liquid

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A pool of liquid in a tank the size of the screen: a FLIP/PIC particle fluid (Matthias
 * Müller's "Ten Minute Physics" scheme). Particles carry the liquid, a staggered grid makes it
 * incompressible each step, and the particle count never changes, so the volume is conserved:
 * it sloshes and splashes but can't thin out or leave the screen.
 *
 * Pure Kotlin, no Android types: the wallpaper's driver steps it and [LiquidRenderer] draws it.
 * Positions are in world units (the tank is [height] tall); the finger and gravity are given in
 * screen terms (y down for the finger, a unit-ish vector for gravity, y up as the world is).
 */
class LiquidSim(
    /** Screen width / height. */
    aspect: Float,
    private val ny: Int = 100,
    /** Share of the tank's height the pool fills, 0 to 1. */
    fill: Float = 0.38f,
    private val flip: Float = 0.82f,
    private val damping: Float = 0.9985f,
    private val pressureIterations: Int = 30,
) {
    val nx: Int = Math.round(ny * aspect)
    private val h = SIM_HEIGHT / ny
    private val invH = 1f / h
    private val r = 0.3f * h

    /** The tank's size in world units. */
    val width: Float = nx * h
    val height: Float = ny * h

    private val nCells = nx * ny
    private val u = FloatArray(nCells)
    private val v = FloatArray(nCells)
    private val du = FloatArray(nCells)
    private val dv = FloatArray(nCells)
    private val prevU = FloatArray(nCells)
    private val prevV = FloatArray(nCells)
    private val p = FloatArray(nCells)
    private val s = FloatArray(nCells)
    private val cellType = IntArray(nCells)
    private val density = FloatArray(nCells)
    private var restDensity = 0f

    /** Particle positions, x then y for each; read by the renderer. */
    val pos: FloatArray

    /** Which of the three layers each particle started in. */
    val layer: ByteArray
    private val vel: FloatArray
    val count: Int

    // A spatial hash to push overlapping particles apart.
    private val pInv = 1f / (2.2f * r)
    private val pNx = floor(nx * h * pInv).toInt() + 2
    private val pNy = floor(ny * h * pInv).toInt() + 2
    private val pCells = pNx * pNy
    private val cellCount = IntArray(pCells)
    private val cellFirst = IntArray(pCells + 1)
    private val cellIds: IntArray

    // The finger: a solid disc that moves through the liquid.
    private var fingerOn = false
    private var fingerX = 0f
    private var fingerY = 0f
    private var fingerVx = 0f
    private var fingerVy = 0f

    init {
        for (i in 0 until nx) for (j in 0 until ny) s[i * ny + j] = if (i == 0 || i == nx - 1 || j == 0 || j == ny - 1) 0f else 1f

        val xs = ArrayList<Float>()
        val layers = ArrayList<Byte>()
        val dx = 2 * r
        val dy = sqrt(3f) / 2f * dx
        val fillRows = fill * ny * h
        var y = h + r
        var row = 0
        while (y < h + fillRows) {
            var x = h + r + (row and 1) * r
            while (x < (nx - 1) * h - r) {
                xs.add(x)
                xs.add(y)
                layers.add(min(2, floor((y - h) / fillRows * 3f).toInt()).toByte())
                x += dx
            }
            y += dy
            row++
        }
        count = xs.size / 2
        pos = FloatArray(xs.size) { xs[it] }
        layer = ByteArray(count) { layers[it] }
        vel = FloatArray(count * 2)
        cellIds = IntArray(count)
        // The packed pool's density is the rest density the projection holds the liquid to.
        transfer(true, 0f)
        updateDensity()
    }

    /** Puts a finger at ([fx], [fy]) as fractions of the screen (y down), moving at ([vfx], [vfy]) screens/s; null [fx] lifts it. */
    fun finger(
        on: Boolean,
        fx: Float = 0f,
        fy: Float = 0f,
        vfx: Float = 0f,
        vfy: Float = 0f,
    ) {
        fingerOn = on
        fingerX = fx * width
        fingerY = (1f - fy) * height
        fingerVx = vfx * width
        fingerVy = -vfy * height
    }

    /** One step of [dt] seconds, under gravity ([gx], [gy]) in m/s², y up. */
    fun step(
        gx: Float,
        gy: Float,
        dt: Float = DT,
    ) {
        integrate(dt, gx, gy)
        pushApart(2)
        collisions()
        transfer(true, flip)
        updateDensity()
        placeFinger()
        solve(pressureIterations, dt, 1.9f)
        transfer(false, flip)
        for (i in vel.indices) vel[i] *= damping
    }

    /** Mean kinetic energy per particle: near 0 for a pool at rest. */
    fun energy(): Float {
        var e = 0f
        for (i in 0 until count) e += vel[2 * i] * vel[2 * i] + vel[2 * i + 1] * vel[2 * i + 1]
        return e / max(count, 1)
    }

    private fun integrate(
        dt: Float,
        gx: Float,
        gy: Float,
    ) {
        for (i in 0 until count) {
            vel[2 * i] += dt * gx
            vel[2 * i + 1] += dt * gy
            pos[2 * i] += vel[2 * i] * dt
            pos[2 * i + 1] += vel[2 * i + 1] * dt
        }
    }

    private fun hashCell(
        x: Float,
        y: Float,
    ): Int = min(max(floor(x * pInv).toInt(), 0), pNx - 1) * pNy + min(max(floor(y * pInv).toInt(), 0), pNy - 1)

    private fun pushApart(iterations: Int) {
        cellCount.fill(0)
        for (i in 0 until count) cellCount[hashCell(pos[2 * i], pos[2 * i + 1])]++
        var first = 0
        for (i in 0 until pCells) {
            first += cellCount[i]
            cellFirst[i] = first
        }
        cellFirst[pCells] = first
        for (i in 0 until count) cellIds[--cellFirst[hashCell(pos[2 * i], pos[2 * i + 1])]] = i

        val minDist = 2 * r
        val minDist2 = minDist * minDist
        repeat(iterations) {
            for (i in 0 until count) {
                val px = pos[2 * i]
                val py = pos[2 * i + 1]
                val pxi = floor(px * pInv).toInt()
                val pyi = floor(py * pInv).toInt()
                for (xi in max(pxi - 1, 0)..min(pxi + 1, pNx - 1)) {
                    for (yi in max(pyi - 1, 0)..min(pyi + 1, pNy - 1)) {
                        val c = xi * pNy + yi
                        for (k in cellFirst[c] until cellFirst[c + 1]) {
                            val id = cellIds[k]
                            if (id == i) continue
                            var dx = pos[2 * id] - pos[2 * i]
                            var dy = pos[2 * id + 1] - pos[2 * i + 1]
                            val d2 = dx * dx + dy * dy
                            if (d2 > minDist2 || d2 == 0f) continue
                            val d = sqrt(d2)
                            val sc = 0.5f * (minDist - d) / d
                            dx *= sc
                            dy *= sc
                            pos[2 * i] -= dx
                            pos[2 * i + 1] -= dy
                            pos[2 * id] += dx
                            pos[2 * id + 1] += dy
                        }
                    }
                }
            }
        }
    }

    private fun collisions() {
        val minX = h + r
        val maxX = (nx - 1) * h - r
        val minY = h + r
        val maxY = (ny - 1) * h - r
        for (i in 0 until count) {
            var x = pos[2 * i]
            var y = pos[2 * i + 1]
            if (fingerOn) {
                val dx = x - fingerX
                val dy = y - fingerY
                val md = FINGER_RADIUS + r
                if (dx * dx + dy * dy < md * md) {
                    vel[2 * i] = fingerVx
                    vel[2 * i + 1] = fingerVy
                }
            }
            if (x < minX) {
                x = minX
                vel[2 * i] = 0f
            }
            if (x > maxX) {
                x = maxX
                vel[2 * i] = 0f
            }
            if (y < minY) {
                y = minY
                vel[2 * i + 1] = 0f
            }
            if (y > maxY) {
                y = maxY
                vel[2 * i + 1] = 0f
            }
            pos[2 * i] = x
            pos[2 * i + 1] = y
        }
    }

    private fun transfer(
        toGrid: Boolean,
        flipRatio: Float,
    ) {
        val n = ny
        val h2 = 0.5f * h
        if (toGrid) {
            u.copyInto(prevU)
            v.copyInto(prevV)
            du.fill(0f)
            dv.fill(0f)
            u.fill(0f)
            v.fill(0f)
            for (i in 0 until nCells) cellType[i] = if (s[i] == 0f) SOLID else AIR
            for (i in 0 until count) {
                val xi = min(max(floor(pos[2 * i] * invH).toInt(), 0), nx - 1)
                val yi = min(max(floor(pos[2 * i + 1] * invH).toInt(), 0), ny - 1)
                val c = xi * n + yi
                if (cellType[c] == AIR) cellType[c] = FLUID
            }
        }
        for (comp in 0..1) {
            val dx = if (comp == 0) 0f else h2
            val dy = if (comp == 0) h2 else 0f
            val f = if (comp == 0) u else v
            val pf = if (comp == 0) prevU else prevV
            val d = if (comp == 0) du else dv
            for (i in 0 until count) {
                val x = min(max(pos[2 * i], h), (nx - 1) * h)
                val y = min(max(pos[2 * i + 1], h), (ny - 1) * h)
                val x0 = min(floor((x - dx) * invH).toInt(), nx - 2)
                val tx = ((x - dx) - x0 * h) * invH
                val x1 = min(x0 + 1, nx - 2)
                val y0 = min(floor((y - dy) * invH).toInt(), ny - 2)
                val ty = ((y - dy) - y0 * h) * invH
                val y1 = min(y0 + 1, ny - 2)
                val sx = 1f - tx
                val sy = 1f - ty
                val d0 = sx * sy
                val d1 = tx * sy
                val d2 = tx * ty
                val d3 = sx * ty
                val n0 = x0 * n + y0
                val n1 = x1 * n + y0
                val n2 = x1 * n + y1
                val n3 = x0 * n + y1
                if (toGrid) {
                    val pv = vel[2 * i + comp]
                    f[n0] += pv * d0
                    d[n0] += d0
                    f[n1] += pv * d1
                    d[n1] += d1
                    f[n2] += pv * d2
                    d[n2] += d2
                    f[n3] += pv * d3
                    d[n3] += d3
                } else {
                    val off = if (comp == 0) n else 1
                    val a0 = if (cellType[n0] != AIR || cellType[n0 - off] != AIR) 1f else 0f
                    val a1 = if (cellType[n1] != AIR || cellType[n1 - off] != AIR) 1f else 0f
                    val a2 = if (cellType[n2] != AIR || cellType[n2 - off] != AIR) 1f else 0f
                    val a3 = if (cellType[n3] != AIR || cellType[n3 - off] != AIR) 1f else 0f
                    val dd = a0 * d0 + a1 * d1 + a2 * d2 + a3 * d3
                    if (dd > 0f) {
                        val pv = vel[2 * i + comp]
                        val pic = (a0 * d0 * f[n0] + a1 * d1 * f[n1] + a2 * d2 * f[n2] + a3 * d3 * f[n3]) / dd
                        val corr =
                            (a0 * d0 * (f[n0] - pf[n0]) + a1 * d1 * (f[n1] - pf[n1]) + a2 * d2 * (f[n2] - pf[n2]) + a3 * d3 * (f[n3] - pf[n3])) / dd
                        vel[2 * i + comp] = (1f - flipRatio) * pic + flipRatio * (pv + corr)
                    }
                }
            }
            if (toGrid) {
                for (i in f.indices) if (d[i] > 0f) f[i] /= d[i]
                for (i in 0 until nx) {
                    for (j in 0 until ny) {
                        val solid = cellType[i * n + j] == SOLID
                        if (solid || (i > 0 && cellType[(i - 1) * n + j] == SOLID)) u[i * n + j] = prevU[i * n + j]
                        if (solid || (j > 0 && cellType[i * n + j - 1] == SOLID)) v[i * n + j] = prevV[i * n + j]
                    }
                }
            }
        }
    }

    private fun updateDensity() {
        val n = ny
        val h2 = 0.5f * h
        density.fill(0f)
        for (i in 0 until count) {
            val x = min(max(pos[2 * i], h), (nx - 1) * h)
            val y = min(max(pos[2 * i + 1], h), (ny - 1) * h)
            val x0 = floor((x - h2) * invH).toInt()
            val tx = ((x - h2) - x0 * h) * invH
            val x1 = min(x0 + 1, nx - 2)
            val y0 = floor((y - h2) * invH).toInt()
            val ty = ((y - h2) - y0 * h) * invH
            val y1 = min(y0 + 1, ny - 2)
            val sx = 1f - tx
            val sy = 1f - ty
            if (x0 < nx && y0 < ny) density[x0 * n + y0] += sx * sy
            if (x1 < nx && y0 < ny) density[x1 * n + y0] += tx * sy
            if (x1 < nx && y1 < ny) density[x1 * n + y1] += tx * ty
            if (x0 < nx && y1 < ny) density[x0 * n + y1] += sx * ty
        }
        if (restDensity == 0f) {
            var sum = 0f
            var fluid = 0
            for (i in 0 until nCells) {
                if (cellType[i] == FLUID) {
                    sum += density[i]
                    fluid++
                }
            }
            if (fluid > 0) restDensity = sum / fluid
        }
    }

    private fun solve(
        iterations: Int,
        dt: Float,
        overRelax: Float,
    ) {
        p.fill(0f)
        u.copyInto(prevU)
        v.copyInto(prevV)
        val n = ny
        val cp = h / dt
        repeat(iterations) {
            for (i in 1 until nx - 1) {
                for (j in 1 until ny - 1) {
                    val c = i * n + j
                    if (cellType[c] != FLUID) continue
                    val l = c - n
                    val rr = c + n
                    val b = c - 1
                    val t = c + 1
                    val sx0 = s[l]
                    val sx1 = s[rr]
                    val sy0 = s[b]
                    val sy1 = s[t]
                    val ss = sx0 + sx1 + sy0 + sy1
                    if (ss == 0f) continue
                    var div = u[rr] - u[c] + v[t] - v[c]
                    // Squeezed particles push back, so the liquid keeps its volume.
                    if (restDensity > 0f) {
                        val compression = density[c] - restDensity
                        if (compression > 0f) div -= compression
                    }
                    val pp = -div / ss * overRelax
                    p[c] += cp * pp
                    u[c] -= sx0 * pp
                    u[rr] += sx1 * pp
                    v[c] -= sy0 * pp
                    v[t] += sy1 * pp
                }
            }
        }
    }

    private fun placeFinger() {
        for (i in 1 until nx - 2) {
            for (j in 1 until ny - 2) {
                s[i * ny + j] = 1f
                if (!fingerOn) continue
                val dx = (i + 0.5f) * h - fingerX
                val dy = (j + 0.5f) * h - fingerY
                if (dx * dx + dy * dy < FINGER_RADIUS * FINGER_RADIUS) {
                    s[i * ny + j] = 0f
                    u[i * ny + j] = fingerVx
                    u[(i + 1) * ny + j] = fingerVx
                    v[i * ny + j] = fingerVy
                    v[i * ny + j + 1] = fingerVy
                }
            }
        }
    }

    /** Where the finger is, in the same fractions [finger] takes, for drawing its ring; null when it's up. */
    fun fingerFraction(): Pair<Float, Float>? = if (fingerOn) fingerX / width to 1f - fingerY / height else null

    companion object {
        const val DT = 1f / 60f

        /** The tank's height in world units; with the default grid a cell is 3 cm. */
        const val SIM_HEIGHT = 3f

        /** The finger's radius in world units: about a seventh of the screen's width. */
        const val FINGER_RADIUS = 0.2f

        private const val FLUID = 0
        private const val AIR = 1
        private const val SOLID = 2

        fun finite(x: Float) = !x.isNaN() && abs(x) < Float.MAX_VALUE
    }
}
