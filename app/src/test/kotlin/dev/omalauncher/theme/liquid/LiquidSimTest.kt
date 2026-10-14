package dev.omalauncher.theme.liquid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LiquidSimTest {
    private val aspect = 108f / 234f

    private fun LiquidSim.run(
        steps: Int,
        gx: Float = 0f,
        gy: Float = -12f,
    ) = repeat(steps) { step(gx, gy) }

    private fun LiquidSim.meanX() = (0 until count).sumOf { pos[2 * it].toDouble() }.toFloat() / count

    private fun LiquidSim.topY() = (0 until count).maxOf { pos[2 * it + 1] }

    @Test fun keepsEveryParticleInsideTheTank() {
        val sim = LiquidSim(aspect)
        val n = sim.count
        sim.run(300, 8f, -8f)
        sim.run(300, -8f, -8f)
        sim.run(300, 0f, 12f) // upside down
        assertEquals(n, sim.count)
        for (i in 0 until sim.count) {
            val x = sim.pos[2 * i]
            val y = sim.pos[2 * i + 1]
            assertTrue("particle $i is at ($x, $y)", LiquidSim.finite(x) && LiquidSim.finite(y))
            assertTrue("x $x outside the tank", x in 0f..sim.width)
            assertTrue("y $y outside the tank", y in 0f..sim.height)
        }
    }

    @Test fun aPoolAtRestStaysAPool() {
        val sim = LiquidSim(aspect)
        val top = sim.topY()
        sim.run(600)
        // The packed start relaxes a little, but nothing climbs the walls or thins into a film.
        assertTrue("the pool rose from $top to ${sim.topY()}", sim.topY() < top * 1.2f)
        assertTrue("still moving: ${sim.energy()}", sim.energy() < 0.2f)
    }

    @Test fun tiltingSlidesTheLiquidDownhill() {
        val sim = LiquidSim(aspect)
        val before = sim.meanX()
        sim.run(240, 8f, -8f)
        assertTrue("mean x went from $before to ${sim.meanX()}", sim.meanX() > before + 0.1f)
    }

    @Test fun aFingerStirsTheLiquid() {
        val calm = LiquidSim(aspect).apply { run(300) }
        val stirred = LiquidSim(aspect).apply { run(300) }
        repeat(60) { i ->
            val x = 0.2f + 0.6f * i / 60f
            stirred.finger(true, x, 0.75f, 0.6f * 60f / 60f, 0f)
            stirred.step(0f, -12f)
        }
        stirred.finger(false)
        assertTrue("energy ${stirred.energy()} vs ${calm.energy()}", stirred.energy() > calm.energy() * 2f)
    }

    @Test fun theLiquidIsDrawnAndStaysThere() {
        val sim = LiquidSim(aspect)
        val renderer = LiquidRenderer(108, 234)
        val out = IntArray(108 * 234)
        val bg = LiquidPalette.Default.background or (0xFF shl 24)

        fun liquidPixels(): Int {
            renderer.render(sim, out)
            return out.count { it != bg }
        }
        val start = liquidPixels()
        assertTrue("start covers $start of ${out.size}", start in out.size / 8..out.size / 2)
        // Sloshing around, upside down and back, never loses it: the thing the first version did.
        sim.run(400, 9f, -6f)
        sim.run(400, -9f, -6f)
        sim.run(200, 0f, 12f)
        sim.run(400)
        val end = liquidPixels()
        assertTrue("went from $start to $end pixels", abs(end - start) < start * 0.25f)
    }
}
