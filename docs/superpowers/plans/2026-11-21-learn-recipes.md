# Learn Recipes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A fourth Learn chapter, "Recipes": 8 short lessons that each show a D5600 look or setting combination as a before/after photo, the exact steps lit on the camera figure, what to expect and when it won't work.

**Architecture:** Pure maths for the looks lives in `learn/Looks.kt` (unit-tested). Drawing each look's before and after sides lives in `ui/learn/LookLayers.kt`, reusing the playgrounds' layer helpers. A new `Step.Recipe` renders in `ui/learn/RecipeStep.kt` (before/after slider, numbered steps driving `CameraBody`). The 8 recipes are content in `learn/Learn.kt`, guarded by `LearnContentTest`.

**Tech Stack:** Kotlin, Jetpack Compose, AGSL `RuntimeShader` + `RenderEffect` (minSdk 33), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-21-learn-recipes-design.md`

## Global Constraints

- Learn never talks to the camera; nothing here touches `ptp/` or the connection.
- Offline, bundled assets only (`app/src/main/assets/learn/`); every photo shown is credited in `credits.json` (Unsplash licence).
- minSdk 33; AGSL and `RenderEffect` are allowed.
- Every previewed "after" is labelled `≈ PREVIEW` (an approximation of Nikon's processing).
- Every D5600 button path in a recipe is checked against the D5600 Reference Manual: `https://download.nikonimglib.com/archive3/T2D6s009HCPw03w5wYS97chunQ10/D5600RM_(En)04.pdf`. No recipe relies on Silhouette, High key or Low key effects (unconfirmed for the D5600).
- Calm Learn: no scores, streaks or points.
- Repo standards (CLAUDE.md): no hacks, DRY, reuse existing helpers; tests red first for the real reason, hardcoded expectations, a positive control beside every negative assertion.
- The repo is not under git and Falta commits only when asked: there are no commit steps. Each task ends with its tests green and, for UI tasks, an emulator check.
- Run JVM tests with: `cd /Users/falta/work/viewfinder && ./gradlew :app:testDebugUnitTest --console=plain -q` (add `--tests 'app.viewfinder.<Class>'` to narrow). Install with `./gradlew :app:installDebug` and drive emulator-5554 (`ANDROID_SERIAL=emulator-5554`; `/tmp/tap.sh "<text>"` taps a UI element by its text; `adb shell am start -n app.viewfinder/.MainActivity -a app.viewfinder.action.LEARN` opens Learn).

## Review Focus

1. **Reduced motion:** the before/after line's first-show glide does not run; the line simply sits at 50 %. (Task 3, emulator step with animations off.)
2. **Dragging the line inside the scrolling step:** a horizontal drag moves the line and does not scroll; a vertical swipe on the photo still scrolls the step. (Task 3.)
3. **Photo not loaded yet:** while `rememberLearnPhoto` is null the photo box shows the existing placeholder pulse and the line can still be dragged, with no crash. (Task 3.)
4. **A step with no control (menu-only):** tapping it deselects the figure instead of leaving the previous control acting. (Task 3.)
5. **"Why this works" from the Recap:** marks the recipe done and opens the linked lesson; closing that lesson returns to Learn with the recipe ticked. (Task 3.)

---

### Task 1: Looks maths

**Files:**
- Create: `app/src/main/kotlin/app/viewfinder/learn/Looks.kt`
- Test: `app/src/test/kotlin/app/viewfinder/LooksTest.kt`

**Interfaces:**
- Produces:
  - `enum class Look { SoftBackground, FreezeVsFlow, Warm, MonoRed, Miniature, PopColour, Silhouette, Vivid }`
  - `enum class MonoFilter { None, Yellow, Orange, Red, Green }`
  - `data class MonoWeights(val r: Float, val g: Float, val b: Float)`
  - `fun monoWeights(filter: MonoFilter): MonoWeights`
  - `fun hueKeep(hueDeg: Float, targetDeg: Float, widthDeg: Float): Float` — 1 when the circular hue distance `d ≤ widthDeg`, 0 when `d ≥ 2 × widthDeg`, linear between.

- [ ] **Step 1: Write the failing tests**

```kotlin
class LooksTest {
    @Test fun `a red filter darkens a blue sky and keeps a red jacket bright`() {
        fun grey(w: MonoWeights, r: Float, g: Float, b: Float) = w.r * r + w.g * g + w.b * b
        val sky = floatArrayOf(0.3f, 0.5f, 0.9f)
        val jacket = floatArrayOf(0.8f, 0.15f, 0.1f)
        assertEquals(0.4858f, grey(monoWeights(MonoFilter.None), sky[0], sky[1], sky[2]), 1e-4f)
        assertEquals(0.34f, grey(monoWeights(MonoFilter.Red), sky[0], sky[1], sky[2]), 1e-4f)
        assertEquals(0.67f, grey(monoWeights(MonoFilter.Red), jacket[0], jacket[1], jacket[2]), 1e-4f)
    }

    @Test fun `filter weights`() {
        assertEquals(MonoWeights(0.299f, 0.587f, 0.114f), monoWeights(MonoFilter.None))
        assertEquals(MonoWeights(0.45f, 0.5f, 0.05f), monoWeights(MonoFilter.Yellow))
        assertEquals(MonoWeights(0.6f, 0.35f, 0.05f), monoWeights(MonoFilter.Orange))
        assertEquals(MonoWeights(0.8f, 0.2f, 0f), monoWeights(MonoFilter.Red))
        assertEquals(MonoWeights(0.2f, 0.7f, 0.1f), monoWeights(MonoFilter.Green))
    }

    @Test fun `hue keep is full inside the band, fades across one width, and wraps at 360`() {
        assertEquals(1f, hueKeep(6f, 6f, 20f), 1e-6f)
        assertEquals(1f, hueKeep(350f, 6f, 20f), 1e-6f)   // 16° away across 0°
        assertEquals(0.5f, hueKeep(36f, 6f, 20f), 1e-6f)  // 30° away: halfway through the fade
        assertEquals(0f, hueKeep(120f, 6f, 20f), 1e-6f)   // green: dropped
    }
}
```

- [ ] **Step 2: Run, expect FAIL** — `--tests 'app.viewfinder.LooksTest'`: compile error, unresolved `monoWeights` / `hueKeep`.
- [ ] **Step 3: Implement `Looks.kt`** with the signatures above and the weight table from the test.
- [ ] **Step 4: Run, expect PASS** — all three tests green, and the full suite still green.

---

### Task 2: Drawing each look's before and after

**Files:**
- Create: `app/src/main/kotlin/app/viewfinder/ui/learn/LookLayers.kt`
- Modify: `app/src/main/kotlin/app/viewfinder/ui/learn/Playgrounds.kt` (share helpers; behaviour unchanged)

**Interfaces:**
- Consumes: `Look`, `MonoFilter`, `monoWeights`, `hueKeep` semantics (Task 1); `whiteBalanceGains` (Optics.kt).
- Produces:
  - `@Composable fun LookLayer(look: Look, after: Boolean)` — fills its box (a `BoxScope` child of `LearnPhoto`/`CreditedPhoto`) with one side of the look.
  - `fun Look.photo(): String` — the credited photo id each look draws from.
  - Shared from `Playgrounds.kt`, changed from `private` to `internal` and used by both the playgrounds and `LookLayers.kt`: `Layer(...)`, `SensorLayer(...)`; new extracted helpers `internal fun apertureBlur(fNumber: Double, widthPx: Float): Float` (the Aperture playground's `open² × width × 0.022`), `internal const val TRAFFIC_PER_SECOND = 0.2f` (fraction of frame height cars cover per second), `internal fun wbFilter(g: Gains): ColorFilter` (the white-balance colour matrix).

What each side draws (tuning is the builder's; the pairs are fixed):

| Look | Photo | Before | After |
|---|---|---|---|
| SoftBackground | p447 | plate blurred for f/16, subject sharp | plate blurred as the playground shows f/2.8, subject sharp |
| FreezeVsFlow | p88 | plate + cars sharp (1/1000) | cars streaked for 1/15 (`TRAFFIC_PER_SECOND`) |
| Warm | p65 | `wbFilter(whiteBalanceGains(4000, 5200))` (cooled, as Auto would) | the photo untouched |
| MonoRed | p1011 | the photo in colour | `monoWeights(Red)` grey, contrast 1.25 about mid-grey: `out = 1.25 × (w·rgb − 0.5) + 0.5` |
| Miniature | p88 | the photo | a blurred copy over the sharp photo, masked so a horizontal band centred at 55 % height (±8 %) stays sharp and blur is full beyond ±25 %; saturation 1.3 |
| PopColour | p1011 | the photo | AGSL shader: `mix(luma, colour, keep)` where `keep = hueKeep(hue, 6°, 20°) × smoothstep(0.25, 0.45, saturation)` (the vest measures ~6° hue) |
| Silhouette | p399 | `SensorLayer` gain 2.0, sigma 0 (+1 stop: subject visible) | `SensorLayer` gain 0.5, sigma 0 (−1 stop: subject dark, sky held) |
| Vivid | p1043 | the photo | saturation 1.35, contrast 1.1 |

The AGSL pop shader re-implements `hueKeep`'s formula; keep a comment pointing at `Looks.kt` as its tested source.

- [ ] **Step 1: Refactor `Playgrounds.kt`** to use `apertureBlur`, `TRAFFIC_PER_SECOND` and `wbFilter`, and make `Layer` and `SensorLayer` internal.
- [ ] **Step 2: Run the suite, expect PASS** (no behaviour change), then install and open the Aperture, Shutter and White balance playgrounds: same look as before at f/11, 1/1000 and Incandescent.
- [ ] **Step 3: Implement `LookLayer` and `Look.photo()`** per the table.
- [ ] **Step 4: Verify** — `./gradlew :app:compileDebugKotlin` succeeds and the suite is green. Nothing shows a `LookLayer` until Task 3; all 16 sides are checked by eye in Task 4, Step 5.

---

### Task 3: The Recipe step, the link from the Recap, and the first recipe

**Files:**
- Modify: `app/src/main/kotlin/app/viewfinder/learn/Learn.kt` (types; the Recipes chapter with its first lesson)
- Create: `app/src/main/kotlin/app/viewfinder/ui/learn/RecipeStep.kt`
- Modify: `app/src/main/kotlin/app/viewfinder/ui/learn/LessonScreen.kt` (`StepContent` branch; Recap link; `Title`, `Body` made internal)
- Modify: `app/src/main/kotlin/app/viewfinder/MainActivity.kt:188-200` (pass `onOpenLesson`)
- Test: `app/src/test/kotlin/app/viewfinder/LearnContentTest.kt`

**Interfaces:**
- Consumes: `Look` (Task 1); `LookLayer`, `Look.photo()` (Task 2); `CameraBody(highlighted, visited, selected, onSelect, modifier, mode)`; `Mode`; `Control`.
- Produces:
  - `data class RecipeStep(val text: String, val control: Control? = null)`
  - `Step.Recipe(val look: Look, val title: String, val mode: Mode?, val steps: List<RecipeStep>, val expect: String, val failsWhen: String) : Step` (the photo is `look.photo()`)
  - `Lesson(..., val why: String? = null)` — new last parameter.
  - `LessonScreen(lesson, onClose, onFinish, onOpenLesson: (String) -> Unit)`
  - `@Composable fun RecipeStepContent(step: Step.Recipe)` and `@Composable fun BeforeAfter(photo: String, before: @Composable BoxScope.() -> Unit, after: @Composable BoxScope.() -> Unit)`
  - A fourth chapter `Chapter("Recipes", …)` last in `Learn.chapters`, with lesson ids prefixed `r-`. This task adds `r-portrait` (SoftBackground, mode A, why = "aperture"); Task 4 adds the other seven.

Behaviour:
- `BeforeAfter`: a `CreditedPhoto(photo)` whose left of the line shows `before`, right shows `after` (clip the after layer). Labels `BEFORE` (top-left) and `AFTER · ≈ PREVIEW` (top-right) in the existing `Badge` look. The line starts at 50 %; on first show it glides from 35 % to 50 % once with `Motion.current.move()` (none under reduced motion). Horizontal drag moves it (0..1), with a haptic tick on reaching either end; vertical gestures pass through to the scroll. Position is `rememberSaveable`. Semantics: a slider with `setProgress`.
- `RecipeStepContent`: `BeforeAfter(look.photo(), { LookLayer(look, false) }, { LookLayer(look, true) })`, the title, numbered steps, then `CameraBody(highlighted = distinct non-null step controls, visited, selected = active step's control, onSelect, mode = step.mode ?: Mode.Auto)` in the same bordered card `OnCameraStep` uses, then "Expect" and "Won't work when" blocks. Tapping a step makes it active (accent number) and selects its control, or deselects when its control is null. Tapping a control on the figure activates the first step that uses it. Active step index is `rememberSaveable`.
- Recap: when `lesson.why != null`, a row under the points, "Why this works: {lesson title} →", calls `onOpenLesson(why)`.
- `MainActivity`: `onOpenLesson = { learnDone = learnDone + lesson.id; settings.learnDone = learnDone; go(Screen.Lesson(it)) }`.

- [ ] **Step 1: Write the failing content tests** (extend `LearnContentTest`):

```kotlin
private fun photosOf(step: Step): List<String> = when (step) {
    is Step.Read -> listOfNotNull(step.photo)
    is Step.Ask -> listOfNotNull(step.photo)
    is Step.Recipe -> listOf(step.look.photo())
    else -> emptyList()
}

@Test fun `recipes are the last chapter, and each is a recipe, a question, a try-it and a recap`() {
    val recipes = Learn.chapters.last()
    assertEquals("Recipes", recipes.title)
    assertTrue(recipes.lessons.isNotEmpty())
    for (l in recipes.lessons) {
        assertTrue(l.id, l.id.startsWith("r-"))
        assertEquals(l.id, listOf(Step.Recipe::class, Step.Ask::class, Step.TryIt::class, Step.Recap::class), l.steps.map { it::class })
    }
}

@Test fun `every why names a lesson that exists`() {
    val whys = lessons.mapNotNull { it.why }
    assertTrue("no lesson links a why", whys.isNotEmpty())   // positive control
    for (w in whys) assertTrue(w, Learn.lesson(w) != null)
}
```

  Also update `the next lesson is the first one not done, in path order`: its last assertion already covers all lessons; no change needed.
- [ ] **Step 2: Run, expect FAIL** — compile error on `Step.Recipe` / `Lesson.why`, then (after the types exist) `expected:<Recipes> but was:<Seeing>`.
- [ ] **Step 3: Add the types, `r-portrait`, `RecipeStep.kt`, the `StepContent` branch, the Recap link and the `MainActivity` wiring.** `r-portrait` steps: mode dial to A (ModeDial); command dial to the smallest f-number (CommandDial); zoom to 55 mm (ZoomRing); step close, with the background far behind (no control). Copy, Ask and TryIt are the builder's, in the lessons' voice.
- [ ] **Step 4: Run the suite, expect PASS.**
- [ ] **Step 5: Emulator: the recipe works end to end.** Open Learn, scroll to Recipes, open the portrait recipe:
  - the line glides once to the middle; dragging it shows f/16 sharp mountains on the left, soft mountains on the right;
  - tapping step 1 turns the figure to the mode dial with the dial reading A; tapping step 4 deselects the figure;
  - a vertical swipe starting on the photo scrolls the step (Review Focus 2);
  - with `adb shell settings put global animator_duration_scale 0` and the app restarted, the line starts at 50 % with no glide (Review Focus 1); restore to 1 afterwards;
  - force-stop the app, reopen it straight into the recipe: the placeholder pulse shows until the photo lands and the line already drags (Review Focus 3);
  - on the Recap, "Why this works: Aperture →" opens Aperture; closing it returns to Learn with the portrait recipe ticked (Review Focus 5).
  Read every screenshot you take.

---

### Task 4: The other seven recipes, verified against the manual

**Files:**
- Modify: `app/src/main/kotlin/app/viewfinder/learn/Learn.kt`
- Modify: `app/src/main/assets/learn/credits.json` (only if a photo is swapped)
- Test: `app/src/test/kotlin/app/viewfinder/LearnContentTest.kt`

**Interfaces:**
- Consumes: everything from Task 3.

| id | Look | mode | Steps (controls) | why |
|---|---|---|---|---|
| r-motion | FreezeVsFlow | S | mode dial S (ModeDial) · command dial to 1/1000 to freeze (CommandDial) · or 1/15 and hold steady to let it flow (CommandDial) · half-press, then press (Shutter) | shutter |
| r-warm | Warm | P | i button (IButton) · White balance → Shade (MultiSelector) · shoot into evening light (Shutter) | light |
| r-mono | MonoRed | null | i button (IButton) · Set Picture Control → Monochrome (MultiSelector) · Filter effects → Red (MultiSelector) · Contrast + (MultiSelector) | light |
| r-pop | PopColour | Effects | mode dial EFFECTS (ModeDial) · command dial to Selective Color (CommandDial) · live view (LiveView) · pick the colour to keep (per manual) | null |
| r-miniature | Miniature | Effects | mode dial EFFECTS (ModeDial) · command dial to Miniature (CommandDial) · live view (LiveView) · place the sharp band (per manual) · shoot from high up (no control) | null |
| r-silhouette | Silhouette | A | mode dial A (ModeDial) · frame the bright sky behind the subject (no control) · hold ± and turn the command dial to −2 (ExposureComp, CommandDial) | together |
| r-landscape | Vivid | A | i button (IButton) · Set Picture Control → Vivid (MultiSelector) · command dial to f/8 (CommandDial) | aperture |

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `the recipes chapter has its eight recipes, each look once`() {
    val recipes = Learn.chapters.last().lessons.map { (it.steps.first() as Step.Recipe).look }
    assertEquals(Look.entries.toSet(), recipes.toSet())
    assertEquals(8, recipes.size)
}
```

- [ ] **Step 2: Run, expect FAIL** — `expected:<[SoftBackground, FreezeVsFlow, …]> but was:<[SoftBackground]>`.
- [ ] **Step 3: Check every button path in the manual.** Download the PDF to `/tmp/d5600rm/` and read the pages for: Effects mode (Selective Color and Miniature live-view options), Set Picture Control and its Monochrome filter effects, White balance via the i button, exposure compensation. Where the manual differs from the table, the manual wins; record the page number in a comment beside each recipe.
- [ ] **Step 4: Add the seven lessons** (copy, Ask, TryIt are the builder's; "Expect" and "Won't work when" must be specific to the look, e.g. MonoRed won't work under a white or grey sky). Run the suite, expect PASS.
- [ ] **Step 5: Emulator: every look reads at a glance.** Open each recipe, drag the line to 50 %, screenshot, and read it: both sides must differ clearly and in the direction the recipe promises. Retune `LookLayer` where a side is weak; swap a photo (with credit) only if tuning can't save it.

---

## Self-review notes

- Spec coverage: chapter last (T3/T4 tests), Recipe step with before/after, steps lighting the figure, mode on the dial, expect/fails (T3), recap link via `Lesson.why` (T3), looks drawing both sides (T2), 8 recipes and manual verification (T4), approximation label (T3), maths tests (T1), photos credited (T3 `photosOf`).
- Not built, by the spec: anything on the camera, applying looks to the user's own photos, recipes inside existing lessons.
