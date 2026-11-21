package app.viewfinder

import app.viewfinder.learn.Third
import app.viewfinder.learn.exposureOffset
import app.viewfinder.learn.shakeBlur
import app.viewfinder.learn.thirdAt
import app.viewfinder.learn.whiteBalanceGains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticsTest {

    @Test
    fun `sunny sixteen is a correct exposure and each dial moves it by stops`() {
        // Bright sun is EV 15: f/16 at 1/125 s, ISO 100 lands on it (within the rounding of 1/125).
        assertEquals(0.0, exposureOffset(16.0, 1.0 / 125, 100, 15.0), 0.05)
        // Twice the ISO is one stop brighter; half the time one stop darker; f/11 is (nominally) one stop wider than f/16.
        assertEquals(1.0, exposureOffset(16.0, 1.0 / 125, 200, 15.0), 0.05)
        assertEquals(-1.0, exposureOffset(16.0, 1.0 / 250, 100, 15.0), 0.05)
        assertEquals(1.12, exposureOffset(11.0, 1.0 / 125, 100, 15.0), 0.01)
        // A dim room (EV 6) at those settings is nine stops too dark.
        assertEquals(-9.0, exposureOffset(16.0, 1.0 / 125, 100, 6.0), 0.05)
    }

    @Test
    fun `shake smears more the longer the shutter stays open`() {
        assertEquals(6.0f, shakeBlur(0.04f, 0.125f, 1200f), 0.001f)
        assertEquals(0.192f, shakeBlur(0.04f, 0.004f, 1200f), 0.001f)
    }

    @Test
    fun `white balance set warmer than the light turns the photo blue, and cooler turns it amber`() {
        val same = whiteBalanceGains(5200, 5200)
        assertEquals(1f, same.r, 0.001f)
        assertEquals(1f, same.b, 0.001f)
        // Incandescent (3000 K) in daylight: the camera cancels orange light that isn't there.
        val tungsten = whiteBalanceGains(3000, 5200)
        assertTrue("blue ${tungsten.b} red ${tungsten.r}", tungsten.b > 1.4f && tungsten.r < 0.85f)
        assertEquals(1f, tungsten.g, 0.001f)
        // Shade (8000 K) in daylight: the camera warms light that was already neutral.
        val shade = whiteBalanceGains(8000, 5200)
        assertTrue("blue ${shade.b} red ${shade.r}", shade.r > 1.1f && shade.b < 0.9f)
    }

    @Test
    fun `a point near a thirds crossing names it, the centre names none`() {
        assertEquals(Third.TopLeft, thirdAt(0.34f, 0.31f))
        assertEquals(Third.BottomRight, thirdAt(0.66f, 0.68f))
        assertEquals(Third.TopRight, thirdAt(0.70f, 0.33f, tolerance = 0.05f))
        assertNull(thirdAt(0.5f, 0.5f))
        assertNull(thirdAt(0.75f, 0.33f, tolerance = 0.05f))
    }
}
