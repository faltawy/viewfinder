# Learn: Recipes chapter

## Intent

Falta wants to learn the camera's presets and setting combinations as "do this, get that": what to set on the D5600 to get a given kind of photo, and what the result will look like before trying it. This is part of the Learn path, taught like the other lessons. It is not a separate tool and never touches the camera.

## Goals

- A fourth chapter, **Recipes**, at the end of the Learn path (after "The three dials" and the light/framing chapter). Each recipe is a short lesson.
- Every recipe shows the result on a real bundled photo, with a before/after line you drag, so you know what to expect.
- Every recipe gives the exact D5600 steps, and each step lights the control it uses on the existing camera figure (Fig 1).
- Every recipe says what to expect and when it won't work.
- The recipes cover both kinds of "preset":
  - looks the camera bakes into the JPEG (Picture Controls, white balance presets, Effects modes);
  - setting combinations for a situation (mode + numbers + where to stand).

## Non-goals

- Changing settings on the camera, or reading them from it. Learn stays separate from the camera link (Falta's standing decision, restated for this feature: "Don't apply these things to the camera").
- Applying a recipe to the user's own photos. That belongs to the planned editor.
- Recipes inside the existing lessons (approach B, not picked).
- Points, streaks, scores (Learn's standing rule: calm, no gamification).

## Anti-goals

- A preview that claims to be exactly what the camera produces. The previews approximate Nikon's processing, and each one says so.
- Steps that are wrong for the D5600. A beginner following a wrong button path is worse than no recipe.
- A recipe that is a wall of text with no picture.

## Constraints

- Lives in the existing Learn code: `learn/Learn.kt` (content, pure), `ui/learn/*` (rendering). Follows the existing step model, motion (`Motion.kt`), palette and the photo/credit system (`LearnPhotos`, `credits.json`).
- Works offline, with no camera linked. minSdk 33, so AGSL `RuntimeShader` and `RenderEffect` are available (already used by the ISO playground).
- Photos: Unsplash licence, credited, bundled in `app/src/main/assets/learn/`. Reuse the 12 bundled photos and the generated layers (`p447` subject/plate, `p88` cars/plate) where they fit; add new ones only where none fits.
- Every button path must be checked against the D5600 Reference Manual (Nikon PDF: `https://download.nikonimglib.com/archive3/T2D6s009HCPw03w5wYS97chunQ10/D5600RM_(En)04.pdf`). Web checks so far confirm Effects mode includes Night Vision, Super Vivid, Pop, Photo Illustration, Toy Camera, Miniature and Selective Color, chosen by turning the command dial with the mode dial on EFFECTS; autofocus in Effects mode works only in live view, and the built-in flash is off. Silhouette, High key and Low key are not confirmed for the D5600, so no recipe depends on them.
- Repo standards: no hacks, DRY, reuse existing helpers; tests red first, hardcoded expectations, a positive control beside every negative assertion.

## Approaches considered

- **A. A Recipes chapter at the end of the path** (picked by Falta). New lessons, each a recipe. Easy to find; the cost is that a recipe sits away from the lesson that explains why it works, so each recipe links back to that lesson.
- **B. A recipe step at the end of each existing lesson, plus a "Looks" chapter.** Recipe right after its why. Not picked.
- **C. Recipes only inside the existing lessons.** Leaves the built-in looks with no home. Dropped.

## Design

### The recipe lesson

Each recipe is a `Lesson` in the new chapter with four steps:

1. **Recipe** (new step type): the before/after photo, the steps, "expect" and "won't work when".
2. **Ask**: one question about when to use it (existing step type).
3. **TryIt**: what to go and shoot (existing).
4. **Recap** (existing; every lesson ends on a Recap — enforced by `LearnContentTest`).

### The Recipe step (new)

`Step.Recipe(look: Look, photo: String, title: String, mode: Mode?, steps: List<RecipeStep>, expect: String, failsWhen: String)`, with `RecipeStep(text: String, control: Control?)`. `mode` is the mode the recipe uses (A, S, P, EFFECTS…); the camera figure's dial is drawn at that mode (`CameraBody(mode = …)`, already supported), so the figure never contradicts the steps. Recipes that don't care about the mode leave it null and the dial shows the default.

The lesson that explains why the recipe works is named on the recipe's **Recap** step (`Lesson.why: String?`, shown under the recap as "Why this works: Aperture →"). It sits on the Recap, not the Recipe step, because opening another lesson leaves this one: navigation keeps a single return slot (`MainActivity.returnTo`), and at the Recap the recipe is already done, so nothing is lost. *(Builder-check call.)*

What it looks like, top to bottom:

```
┌───────────────────────────────┐
│ [ photo: after | before ]     │  drag the line; starts at 50 %
│ BEFORE        ≈ PREVIEW  AFTER│  small labels; "≈ preview" marks the approximation
└───────────────────────────────┘
Moody black & white
 ① Press i, choose Picture Control    ← tapping a step selects its control
 ② Pick Monochrome (MC)                 on the camera figure below and plays
 ③ Filter effects → Red                 its demo; the active step is jade
 ④ Contrast +2
┌ Fig 1 ─────────────── NIKON D5600┐
│      camera, turned to the       │
│      active step's control       │
└──────────────────────────────────┘
Expect: dark skies, bright clouds, strong faces.
Won't work when: the sky is white or grey — there's no blue to darken.
```

- The camera figure is the existing `CameraBody`; its `highlighted` list is the controls used by the steps, and `selected` follows the active step. Steps with `control = null` (menu-only choices) select nothing.
- The before/after line: drag anywhere on the photo, haptic tick at the ends, clip the "after" layer at the line. It starts at 50 % with a single short glide from 35 % to 50 % on first show, so the line is visibly draggable.

### Looks: how the "after" is drawn

`enum class Look` draws both sides: the **before** is what you'd get without the recipe, the **after** is the recipe's result. Either side may be the untouched photo (for p65, already golden, the before is the same photo cooled to Auto white balance, the after is the photo). *(Builder-check call.)* Each is drawn from the bundled photo with the same tools the playgrounds already use; pure maths lives in `learn/` and is unit-tested, drawing lives in `ui/learn/`.

| Look | Drawn by | Reuses |
|---|---|---|
| `SoftBackground` | plate blurred under the sharp subject | Aperture playground layers (`p447`) |
| `FreezeVsFlow` | before = cars sharp (1/1000), after = cars blurred (1/15) | Shutter playground layers (`p88`) |
| `Warm` | white-balance gains (Shade preset on daylight) | `whiteBalanceGains` (Optics.kt) |
| `MonoRed` | monochrome channel mix with red-filter weights, contrast curve | new pure `monoWeights(filter)` |
| `Miniature` | blur graded from a sharp horizontal band, saturation and contrast up | `BlurEffect` + new AGSL band mask |
| `PopColour` | keep hues near one colour, desaturate the rest | new AGSL shader + pure `hueKeep(h, target, width)` |
| `Silhouette` | before: subject exposed normally; after: exposure dropped 2 stops, highlights held | tone curve shader |
| `Vivid` | Picture Control Vivid: saturation and contrast up | ColorMatrix |

### The recipes (first set: 8)

The builder may reorder or swap photos; button paths must be verified (Constraints).

| Recipe | Kind | Steps (gist) | Photo | Why lesson |
|---|---|---|---|---|
| Soft background portrait | settings | A · smallest f-number · zoom to 55 mm · step close · subject far from the background | p447 | aperture |
| Freeze it or let it flow | settings | S · 1/1000 to freeze; 1/15 + steady hands to flow | p88 | shutter |
| Warm golden light | look (WB) | i → White balance → Shade on a sunny evening | p65 | light |
| Moody black & white | look (Picture Control) | i → Picture Control → Monochrome → Filter Red → Contrast + | p1011 | light |
| Pop of colour | look (Effects) | mode dial EFFECTS · command dial to Selective Color · live view · choose the colour | p1011 (red vest) | — |
| Tiny world (miniature) | look (Effects) | mode dial EFFECTS · command dial to Miniature · live view · place the sharp band · shoot from high up | p88 | — |
| Sunset silhouette | settings | A or P · point at the bright sky · ± to −2 · keep the subject dark against it | p399 | together |
| Punchy landscape | look (Picture Control) | i → Picture Control → Vivid (or Landscape) · f/8 | p1043 | aperture |

The chapter's path row, "Up next" card and lesson progress work as they do today; recipes count toward `learnDone` like any lesson.

### Tests

- `LearnContentTest` (extended): every Recipe photo is bundled and credited (with the existing positive control); every `why` names an existing lesson; the Recipes chapter comes last. (No test that recipe controls have a spot on the figure: every `Control` already has one, so it could never fail. *Builder-check call.*)
- Pure-function tests with hardcoded values: `monoWeights(Red)`, and `hueKeep` inside, at the edge and outside the band.

## Left to the builder

- Exact copy for each recipe (plain words, short; match the existing lessons' voice).
- The Ask question for each recipe.
- Shader details and tuning, so each preview reads clearly on its photo.
- Whether `Recipe` renders `CameraBody` inline or reuses `OnCameraStep` internals; whichever avoids duplicating the chip/selection code.
- Swapping in a better photo where a reused one reads poorly, with credits.
