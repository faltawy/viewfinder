package app.viewfinder

import app.viewfinder.learn.MonoFilter
import app.viewfinder.learn.MonoWeights
import app.viewfinder.learn.hueKeep
import app.viewfinder.learn.monoWeights
import org.junit.Assert.assertEquals
import org.junit.Test

class LooksTest {

    private fun grey(w: MonoWeights, rgb: FloatArray) = w.r * rgb[0] + w.g * rgb[1] + w.b * rgb[2]

    @Test
    fun `a red filter darkens a blue sky and keeps a red jacket bright`() {
        val sky = floatArrayOf(0.3f, 0.5f, 0.9f)
        val jacket = floatArrayOf(0.8f, 0.15f, 0.1f)
        assertEquals(0.4858f, grey(monoWeights(MonoFilter.None), sky), 1e-4f)
        assertEquals(0.34f, grey(monoWeights(MonoFilter.Red), sky), 1e-4f)
        assertEquals(0.67f, grey(monoWeights(MonoFilter.Red), jacket), 1e-4f)
    }

    @Test
    fun `filter weights`() {
        assertEquals(MonoWeights(0.299f, 0.587f, 0.114f), monoWeights(MonoFilter.None))
        assertEquals(MonoWeights(0.45f, 0.5f, 0.05f), monoWeights(MonoFilter.Yellow))
        assertEquals(MonoWeights(0.6f, 0.35f, 0.05f), monoWeights(MonoFilter.Orange))
        assertEquals(MonoWeights(0.8f, 0.2f, 0f), monoWeights(MonoFilter.Red))
        assertEquals(MonoWeights(0.2f, 0.7f, 0.1f), monoWeights(MonoFilter.Green))
    }

    @Test
    fun `hue keep is full inside the band, fades across one width, and wraps at 360`() {
        assertEquals(1f, hueKeep(6f, 6f, 20f), 1e-6f)
        assertEquals(1f, hueKeep(350f, 6f, 20f), 1e-6f)   // 16° away across 0°
        assertEquals(0.5f, hueKeep(36f, 6f, 20f), 1e-6f)  // 30° away: halfway through the fade
        assertEquals(0f, hueKeep(120f, 6f, 20f), 1e-6f)   // green: dropped
    }
}
