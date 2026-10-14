package dev.omalauncher.theme.liquid

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The colors the liquid is drawn in, as 0xRRGGBB ints: the theme's, or [Default]. */
class LiquidPalette(
    val background: Int,
    val light: Int,
    /** Bottom, middle and top layer. */
    val layers: List<Int>,
) {
    companion object {
        /** Tokyo Night. */
        val Default = LiquidPalette(0x1a1b26, 0xc0caf5, listOf(0x7aa2f7, 0xbb9af7, 0xf7768e))

        /** From a theme's colors ("#rrggbb" by name): its blue, magenta and red over its background. */
        fun from(colors: Map<String, String>): LiquidPalette {
            fun c(vararg names: String) = names.firstNotNullOfOrNull { n -> colors[n]?.let { parse(it) } }
            val accent = c("accent") ?: Default.layers[0]
            return LiquidPalette(
                background = c("background") ?: Default.background,
                light = c("foreground", "bright_foreground") ?: Default.light,
                layers = listOf(c("blue", "accent") ?: accent, c("magenta", "purple", "accent") ?: accent, c("red", "orange", "accent") ?: accent),
            )
        }

        private fun parse(hex: String): Int? = hex.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)
    }
}

/**
 * Draws a [LiquidSim] as a small lofi bitmap: the particles are splatted into a coarse density
 * field, thresholded into a solid body, lit along its surface line, darkened with depth, and
 * snapped to a palette with ordered dithering. The caller scales it up with nearest-neighbour.
 */
class LiquidRenderer(
    val width: Int,
    val height: Int,
    palette: LiquidPalette = LiquidPalette.Default,
) {
    private val bg = rgb(palette.background)
    private val light = rgb(palette.light)
    private val wall = mix(bg, light, 0.08f)
    private val layerColors = palette.layers.map(::rgb)

    /** The palette things are snapped to: the background, wall and light, then each layer in four shades. */
    private val pal: List<FloatArray> =
        buildList {
            add(bg)
            add(wall)
            add(mix(bg, light, 0.25f))
            add(light)
            for (c in layerColors) {
                add(c)
                add(mix(c, bg, 0.35f))
                add(mix(c, bg, 0.62f))
                add(mix(c, light, 0.45f))
            }
        }

    // Per-pixel sums of the particles' colors, and their weight.
    private val acc = FloatArray(width * height * 4)
    private val mask = BooleanArray(width * height)
    private val depth = IntArray(width)
    private var threshold = 0f

    /** Draws [sim] into [out], [width] * [height] ARGB ints. */
    fun render(
        sim: LiquidSim,
        out: IntArray,
    ) {
        acc.fill(0f)
        val sx = width / sim.width
        val sy = height / sim.height
        val kernel = 2.2f
        for (i in 0 until sim.count) {
            val px = sim.pos[2 * i] * sx - 0.5f
            val py = (sim.height - sim.pos[2 * i + 1]) * sy - 0.5f
            val c = layerColors[sim.layer[i].toInt()]
            val x0 = floor(px - kernel).toInt() + 1
            val x1 = floor(px + kernel).toInt()
            val y0 = floor(py - kernel).toInt() + 1
            val y1 = floor(py + kernel).toInt()
            for (yy in max(y0, 0)..min(y1, height - 1)) {
                val wy = 1f - abs(py - yy) / kernel
                if (wy <= 0f) continue
                for (xx in max(x0, 0)..min(x1, width - 1)) {
                    val wx = 1f - abs(px - xx) / kernel
                    if (wx <= 0f) continue
                    val w = wx * wy
                    val k = (yy * width + xx) * 4
                    acc[k] += c[0] * w
                    acc[k + 1] += c[1] * w
                    acc[k + 2] += c[2] * w
                    acc[k + 3] += w
                }
            }
        }
        // The first frame, a fresh pool, calibrates what counts as liquid.
        if (threshold == 0f) {
            var sum = 0f
            var n = 0
            var k = 3
            while (k < acc.size) {
                if (acc[k] > 0.3f) {
                    sum += acc[k]
                    n++
                }
                k += 4
            }
            threshold = 0.5f * sum / max(n, 1)
        }
        for (i in mask.indices) mask[i] = acc[i * 4 + 3] > threshold

        val wallPx = ceil(sim.width / sim.nx * sx).toInt()
        depth.fill(0)
        val col = FloatArray(3)
        val tmp = FloatArray(3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val k = i * 4
                val c: FloatArray
                if (x < wallPx || x >= width - wallPx || y < wallPx || y >= height - wallPx) {
                    c = snap(wall, x, y, 8f)
                } else if (mask[i]) {
                    val w = acc[k + 3]
                    col[0] = acc[k] / w
                    col[1] = acc[k + 1] / w
                    col[2] = acc[k + 2] / w
                    depth[x] = if (y > 0 && mask[i - width]) depth[x] + 1 else 0
                    c =
                        if (depth[x] == 0) {
                            snap(mixInto(tmp, col, light, 0.5f), x, y, 10f) // the lit surface line
                        } else {
                            snap(mixInto(tmp, col, bg, min(depth[x] / 46f, 0.62f)), x, y, 26f) // deeper is darker
                        }
                } else {
                    depth[x] = 0
                    c = snap(bg, x, y, 0f)
                }
                out[i] = 0xFF shl 24 or (c[0].toInt() shl 16) or (c[1].toInt() shl 8) or c[2].toInt()
            }
        }
    }

    /** The palette entry nearest [c] after nudging it by the Bayer matrix, [spread] of 255 either way. */
    private fun snap(
        c: FloatArray,
        x: Int,
        y: Int,
        spread: Float,
    ): FloatArray {
        val d = BAYER[(y and 3) * 4 + (x and 3)] * spread
        var best = Float.MAX_VALUE
        var result = pal[0]
        for (p in pal) {
            val e0 = c[0] + d - p[0]
            val e1 = c[1] + d - p[1]
            val e2 = c[2] + d - p[2]
            val dist = e0 * e0 + e1 * e1 + e2 * e2
            if (dist < best) {
                best = dist
                result = p
            }
        }
        return result
    }

    private companion object {
        val BAYER = FloatArray(16) { listOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)[it] / 16f - 0.5f }

        fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF).toFloat(), ((c shr 8) and 0xFF).toFloat(), (c and 0xFF).toFloat())

        fun mix(
            a: FloatArray,
            b: FloatArray,
            t: Float,
        ) = mixInto(FloatArray(3), a, b, t)

        fun mixInto(
            dst: FloatArray,
            a: FloatArray,
            b: FloatArray,
            t: Float,
        ): FloatArray {
            for (i in 0..2) dst[i] = a[i] + (b[i] - a[i]) * t
            return dst
        }
    }
}
