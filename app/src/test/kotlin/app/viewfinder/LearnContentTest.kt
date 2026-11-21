package app.viewfinder

import app.viewfinder.learn.Control
import app.viewfinder.learn.Learn
import app.viewfinder.learn.Mode
import app.viewfinder.learn.Look
import app.viewfinder.learn.Step
import app.viewfinder.learn.nextLesson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnContentTest {

    private val lessons = Learn.chapters.flatMap { it.lessons }

    private fun photosOf(step: Step): List<String> = when (step) {
        is Step.Read -> listOfNotNull(step.photo)
        is Step.Ask -> listOfNotNull(step.photo)
        is Step.Recipe -> listOf(step.look.photo)
        else -> emptyList()
    }

    @Test
    fun `every photo a lesson shows is bundled and credited`() {
        val assets = File("src/main/assets/learn")
        val credits = File(assets, "credits.json").readText()
        val used = lessons.flatMap { l -> listOf(l.cover) + l.steps.flatMap(::photosOf) }.toSet()
        // Positive control: the lessons really do reference photos (so a broken walk can't pass by finding none).
        assertTrue("only ${used.size} photos referenced", used.size >= 10)
        for (photo in used) {
            assertTrue("$photo.jpg is missing", File(assets, "$photo.jpg").isFile)
            assertTrue("$photo.jpg has no credit", "\"$photo.jpg\"" in credits)
        }
    }

    @Test
    fun `every question's answer is one of its options, and every lesson ends on a recap`() {
        val asks = lessons.flatMap { it.steps }.filterIsInstance<Step.Ask>()
        assertTrue(asks.size >= 8)
        for (ask in asks) assertTrue(ask.question, ask.answer in ask.options.indices)
        for (lesson in lessons) assertTrue(lesson.id, lesson.steps.last() is Step.Recap)
        assertEquals(lessons.size, lessons.map { it.id }.toSet().size)
    }

    @Test
    fun `the next lesson is the first one not done, in path order`() {
        assertEquals("meet", nextLesson(Learn.chapters, emptySet())?.id)
        assertEquals("aperture", nextLesson(Learn.chapters, setOf("meet", "steady"))?.id)
        // Doing a later lesson first doesn't skip the earlier one.
        assertEquals("steady", nextLesson(Learn.chapters, setOf("meet", "aperture"))?.id)
        assertEquals(null, nextLesson(Learn.chapters, lessons.map { it.id }.toSet()))
    }

    @Test
    fun `recipes are the last chapter, and each is a recipe, a question, a try-it and a recap`() {
        val recipes = Learn.chapters.last()
        assertEquals("Recipes", recipes.title)
        assertTrue(recipes.lessons.isNotEmpty())
        for (l in recipes.lessons) {
            assertTrue(l.id, l.id.startsWith("r-"))
            assertEquals(l.id, listOf(Step.Recipe::class, Step.Ask::class, Step.TryIt::class, Step.Recap::class), l.steps.map { it::class })
        }
    }

    @Test
    fun `every why names a lesson that exists`() {
        val whys = lessons.mapNotNull { it.why }
        // Positive control: some lesson really links a why, so an empty walk can't pass.
        assertTrue("no lesson links a why", whys.isNotEmpty())
        for (w in whys) assertTrue(w, Learn.lesson(w) != null)
    }

    @Test
    fun `the recipes chapter has its eight recipes, each look once`() {
        val looks = Learn.chapters.last().lessons.map { (it.steps.first() as Step.Recipe).look }
        assertEquals(Look.entries.toSet(), looks.toSet())
        assertEquals(8, looks.size)
    }

    @Test
    fun `a recipe that sets the dial names its mode, and menu settings need P, S, A or M`() {
        val recipes = Learn.chapters.last().lessons.map { it.steps.first() as Step.Recipe }
        val dialed = recipes.filter { r -> r.steps.any { it.control == Control.ModeDial } }
        // Positive control: the portrait recipe really turns the dial to A.
        assertTrue(recipes.first { it.look == Look.SoftBackground } in dialed)
        for (r in dialed) {
            val mode = r.mode
            assertTrue("${r.title} turns the dial but has no mode", mode != null)
            assertTrue("${r.title} doesn't say to turn to ${mode!!.mark}", r.steps.any { it.control == Control.ModeDial && mode.mark in it.text })
        }
        // White balance and Picture Controls can't be changed in AUTO or the scene modes.
        val menu = recipes.filter { r -> r.steps.any { it.control == Control.Menu || it.control == Control.IButton } }
        assertTrue(menu.size >= 3)
        for (r in menu) assertTrue("${r.title} needs P, S, A or M", r.mode in setOf(Mode.P, Mode.S, Mode.A, Mode.M))
    }
}
