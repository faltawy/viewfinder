package app.viewfinder

import app.viewfinder.learn.Program
import app.viewfinder.learn.Shot
import app.viewfinder.learn.explain
import app.viewfinder.learn.programFromExif
import org.junit.Assert.assertEquals
import org.junit.Test

class ShotExplainerTest {

    private fun List<app.viewfinder.learn.Insight>.lines() = map { "${it.label}: ${it.title} → ${it.lesson}" }

    @Test
    fun `a wide-open portrait in aperture priority`() {
        val shot = Shot(fNumber = 1.8, seconds = 1.0 / 500, iso = 100, focalMm = 50.0, flashFired = false, program = Program.AperturePriority)
        assertEquals(
            listOf(
                "Mode A: You picked the aperture → aperture",
                "f/1.8: Wide open → aperture",
                "1/500 s: Freezes people moving → shutter",
                "ISO 100: Clean and bright → iso",
                "50 mm: A natural view → null",
            ),
            explain(shot).lines(),
        )
    }

    @Test
    fun `a dim handheld shot slower than the lens can hold warns about shake`() {
        // 1/20 s at 55 mm on a DX body is slower than 1/(1.5 × 55) ≈ 1/80.
        val shot = Shot(fNumber = 5.6, seconds = 1.0 / 20, iso = 6400, focalMm = 55.0, flashFired = true, program = Program.Program)
        assertEquals(
            listOf(
                "Mode P: The camera balanced it, you could steer → together",
                "f/5.6: The middle of the range → aperture",
                "1/20 s: Slow enough to blur → steady",
                "ISO 6400: A very dark scene → iso",
                "55 mm: A natural view → null",
                "Flash: The flash fired → null",
            ),
            explain(shot).lines(),
        )
        assertEquals(
            "Slower than about 1/80 s at this zoom, so the smallest wobble of your hands shows. Brace yourself, or raise the ISO for a faster shutter.",
            explain(shot)[2].body,
        )
    }

    @Test
    fun `missing fields are left out rather than guessed, and long exposures read as seconds`() {
        val shot = Shot(seconds = 2.0, iso = 100)
        assertEquals(listOf("2 s: A long exposure → shutter", "ISO 100: Clean and bright → iso"), explain(shot).lines())
    }

    @Test
    fun `exif program numbers map to the dial's letters`() {
        assertEquals(Program.Manual, programFromExif(1))
        assertEquals(Program.Program, programFromExif(2))
        assertEquals(Program.AperturePriority, programFromExif(3))
        assertEquals(Program.ShutterPriority, programFromExif(4))
        assertEquals(Program.Scene, programFromExif(7))
        assertEquals(null, programFromExif(0))
    }
}
