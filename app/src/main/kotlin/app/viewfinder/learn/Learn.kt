package app.viewfinder.learn

/** The D5600's controls a lesson can point at. Where each one sits on the body is drawn in `ui/learn/CameraBody.kt`. */
enum class Control(val title: String, val what: String) {
    Shutter("Shutter button", "Press halfway to focus and measure the light: you'll hear a beep and see a green dot. Press all the way to take the photo."),
    Power("On/off switch", "The collar around the shutter button. Flick it to ON and the camera is ready in a moment."),
    MovieRecord("Movie-record button", "The red dot. In live view, press it to start a video and again to stop."),
    ExposureComp("Exposure compensation", "Hold it and turn the command dial to make the next photos brighter (+) or darker (−) than the camera's guess. In M it changes the aperture instead."),
    ModeDial("Mode dial", "Chooses who decides: AUTO does everything, P, S and A share the work with you, and M is all yours."),
    LiveView("Live view switch", "The lever beside the mode dial. Flick it to frame on the screen instead of through the viewfinder; flick again to go back."),
    CommandDial("Command dial", "The ridged wheel under your right thumb. It changes whatever your mode lets you set: shutter speed in S, aperture in A."),
    Menu("MENU button", "Opens every setting the camera has. You won't need it often."),
    Viewfinder("Viewfinder", "Look through it and you see straight through the lens, by mirror and prism, with no delay."),
    Diopter("Viewfinder adjustment", "A tiny dial beside the viewfinder. Turn it until the numbers inside look sharp to your eye."),
    Info("Info button", "Shows the settings screen on the monitor; in live view it changes what's laid over the picture, including a framing grid."),
    AeLock("AE-L/AF-L button", "Holds the focus and brightness you have now, so you can reframe without the camera changing its mind."),
    Playback("Playback button", "Shows the photos you've taken. Half-press the shutter to get back to shooting."),
    IButton("i button", "A quick menu on the monitor for the settings you change most: ISO, white balance, focus and more."),
    MultiSelector("Multi selector and OK", "Four-way pad to move through menus and photos; OK in the middle confirms."),
    ZoomIn("Zoom in", "In playback, magnifies the photo so you can check focus and grain."),
    ZoomOut("Zoom out / help", "Zooms back out, or shows a hint when a question mark is on the screen."),
    Delete("Delete button", "The bin. Press it twice to delete the photo on screen."),
    Monitor("Monitor", "A touch screen that flips out and turns, so you can shoot from the ground or over a crowd."),
    Flash("Flash button", "Pops up the built-in flash in P, S, A and M. Push it down to close."),
    Fn("Fn button", "Out of the box it sets ISO: hold it and turn the command dial."),
    LensRelease("Lens release", "Hold it and turn the lens to take it off."),
    ReleaseMode("Release mode button", "Single shot, continuous bursts, quiet mode or the self-timer."),
    ZoomRing("Zoom ring", "The wide ring on the lens. Turn it to go wide (18 mm) or close in (55 mm). It also unlocks a retracted kit lens."),
    FocusRing("Focus ring", "The narrow ring at the front, for focusing by hand when the camera can't."),
}

/** A mode on the D5600's dial, and who decides each of the three exposure settings in it. */
enum class Mode(val mark: String, val title: String, val what: String, val youSet: Set<Setting>) {
    Auto("AUTO", "Auto", "The camera decides everything, and pops up the flash when it's dark. Perfect while you learn the rest.", emptySet()),
    NoFlash("⚡̸", "Auto, flash off", "Auto without the flash: for museums, concerts and sleeping babies.", emptySet()),
    Scene("SCENE", "Scene", "Tell the camera what you're shooting (portrait, landscape, sports, food…) and it picks settings that suit.", emptySet()),
    Effects("EFFECTS", "Effects", "Looks baked into the photo as you take it: miniature, toy camera, colour sketch and more.", emptySet()),
    P("P", "Program", "The camera balances the exposure; turn the command dial to shift between a faster shutter and a smaller aperture. ISO can be yours.", setOf(Setting.Iso)),
    S("S", "Shutter priority", "You choose the shutter speed to freeze or blur motion; the camera picks the aperture.", setOf(Setting.Shutter, Setting.Iso)),
    A("A", "Aperture priority", "You choose the aperture to blur or sharpen the background; the camera picks the shutter speed. A favourite of most photographers.", setOf(Setting.Aperture, Setting.Iso)),
    M("M", "Manual", "You set all three, guided by the meter in the viewfinder. Slower, but nothing changes unless you change it.", setOf(Setting.Aperture, Setting.Shutter, Setting.Iso)),
}

enum class Setting(val title: String) { Aperture("Aperture"), Shutter("Shutter"), Iso("ISO") }

/** The modes in the order they sit around the dial, clockwise from the top as you look down on it. */
val DIAL_ORDER = listOf(Mode.P, Mode.S, Mode.A, Mode.M, Mode.Effects, Mode.NoFlash, Mode.Auto, Mode.Scene)

/** The real-photo playgrounds, each built on one bundled photo. */
enum class Playground { Steady, Aperture, Shutter, Iso, Exposure, WhiteBalance, Thirds }

sealed interface Step {
    /** A page to read, over a photo when [photo] is set. */
    data class Read(val title: String, val body: String, val photo: String? = null) : Step

    /** A playground to touch. */
    data class Play(val playground: Playground, val title: String, val prompt: String) : Step

    /** The camera, turned to show [controls]; each can be tapped for what it does. */
    data class OnCamera(val title: String, val body: String, val controls: List<Control>) : Step

    /** The mode dial, to turn. */
    data class Dial(val title: String, val body: String) : Step

    /** A question with one best answer; the explanation shows whatever is picked. */
    data class Ask(val question: String, val options: List<String>, val answer: Int, val why: String, val photo: String? = null) : Step

    /** Things to try with the camera, next time it's out. */
    data class TryIt(val title: String, val tasks: List<String>) : Step

    /** What to remember, at the end. */
    data class Recap(val points: List<String>) : Step

    /**
     * "Do this, get that": the [look] as a before/after photo, the [steps] that make it on the camera (each lights
     * its control), the [mode] the dial must be on (null only when no step depends on it), what to [expect] and when it [failsWhen].
     */
    data class Recipe(
        val look: Look,
        val title: String,
        val mode: Mode?,
        val steps: List<RecipeStep>,
        val expect: String,
        val failsWhen: String,
    ) : Step
}

/** One thing to do for a recipe, and the control it's done with (null for menu choices and where to stand). */
data class RecipeStep(val text: String, val control: Control? = null)

/** [why] names the lesson that explains why this one works; its Recap links there. */
data class Lesson(val id: String, val title: String, val summary: String, val minutes: Int, val cover: String, val steps: List<Step>, val why: String? = null)

data class Chapter(val title: String, val lessons: List<Lesson>)

/** The first lesson in path order not yet done; null when every lesson is done. */
fun nextLesson(chapters: List<Chapter>, done: Set<String>): Lesson? =
    chapters.flatMap { it.lessons }.firstOrNull { it.id !in done }

object Learn {
    fun lesson(id: String): Lesson? = chapters.flatMap { it.lessons }.firstOrNull { it.id == id }

    val chapters: List<Chapter> = listOf(
        Chapter(
            "Your camera",
            listOf(
                Lesson(
                    "meet", "Meet your camera", "The handful of controls you'll actually use, and the dial that decides who's in charge.", 4, "p250",
                    listOf(
                        Step.Read(
                            "A photo is light, caught",
                            "Your camera is a dark box. The lens lets light in, a sensor records it, and almost every control decides one of three things: how much light, for how long, and what ends up sharp.",
                            "p250",
                        ),
                        Step.OnCamera(
                            "The controls you'll use most",
                            "Tap each glowing spot. Turn the camera with your finger to look around it.",
                            listOf(Control.Power, Control.Shutter, Control.ModeDial, Control.CommandDial, Control.ZoomRing, Control.LiveView),
                        ),
                        Step.Dial(
                            "The mode dial decides who's in charge",
                            "Turn it. Each mode shares the three settings differently between you and the camera.",
                        ),
                        Step.Ask(
                            "You want the camera to take care of everything while you get to know it. Where do you turn the dial?",
                            listOf("M", "AUTO", "S"),
                            1,
                            "AUTO picks every setting for you. M is the opposite: you set everything yourself.",
                        ),
                        Step.OnCamera(
                            "Half-press, then press",
                            "The shutter button has two stages. Halfway, the camera focuses and measures the light. All the way, it takes the photo.",
                            listOf(Control.Shutter),
                        ),
                        Step.TryIt(
                            "Next time the camera's out",
                            listOf(
                                "Flick it ON and turn the mode dial to AUTO.",
                                "Point at something close, press halfway and wait for the beep and the green dot.",
                                "Press all the way. Then press Playback to see it.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "Every control decides how much light, for how long, and what's sharp.",
                                "The mode dial decides who's in charge: AUTO is the camera, M is you.",
                                "Half-press to focus, then press all the way.",
                            ),
                        ),
                    ),
                ),
                Lesson(
                    "steady", "Hold it steady", "Why some photos come out soft, and how to hold a camera so they don't.", 4, "p122",
                    listOf(
                        Step.Read(
                            "Blur from your hands",
                            "While the shutter is open, the picture keeps landing on the sensor. If the camera moves even a little in that time, everything smears. In dim light the shutter stays open longer, so this is when it happens.",
                            "p122",
                        ),
                        Step.Play(
                            Playground.Steady,
                            "Your hands, on camera",
                            "Hold your phone like a camera. This is what each shutter speed records from the way you're holding it right now.",
                        ),
                        Step.OnCamera(
                            "The grip",
                            "Right hand around the grip, finger resting on the shutter button. Left hand underneath, cradling the zoom ring. Elbows tucked against your body, eye to the viewfinder. Breathe out, and squeeze the button gently rather than stabbing it.",
                            listOf(Control.Shutter, Control.ZoomRing, Control.Viewfinder),
                        ),
                        Step.Ask(
                            "A rule of thumb: on your D5600, hand-hold no slower than 1/(1.5 × zoom). Zoomed to 50 mm, which speed is safe?",
                            listOf("1/15 s", "1/100 s", "1/4 s"),
                            1,
                            "1.5 × 50 is 75, so 1/75 s or faster. 1/100 s is safely quicker; 1/15 s and 1/4 s will almost certainly blur.",
                        ),
                        Step.TryIt(
                            "Feel the difference",
                            listOf(
                                "Indoors in the evening, shoot the same thing one-handed, then with the full grip and your elbows in.",
                                "Rest the camera on a table or wall and shoot again.",
                                "Zoom into each in playback and compare the sharpness.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "Slow shutter + moving hands = blur.",
                                "Grip with two hands, elbows in, breathe out, squeeze.",
                                "Hand-hold at 1/(1.5 × zoom) or faster.",
                            ),
                        ),
                    ),
                ),
            ),
        ),
        Chapter(
            "The three dials",
            listOf(
                Lesson(
                    "aperture", "Aperture", "How wide the lens opens, and how it melts a background into blur.", 5, "p447",
                    listOf(
                        Step.Read(
                            "A hole that opens and closes",
                            "Inside the lens is an opening that widens and narrows, like the pupil of your eye. It's measured in f-numbers, and they run backwards: f/1.8 is a big opening, f/22 a tiny one.",
                        ),
                        Step.Play(
                            Playground.Aperture,
                            "Open it up",
                            "Drag the dial. The camera keeps the brightness right; watch the mountains.",
                        ),
                        Step.Read(
                            "Those circles are bokeh",
                            "Points of light far behind your subject turn into soft discs when the aperture is wide open. The wider the opening, the bigger and softer they get.",
                            "p56",
                        ),
                        Step.Ask(
                            "This portrait has a smooth, blurred background. Which aperture made it?",
                            listOf("f/1.8", "f/16"),
                            0,
                            "A wide opening like f/1.8 keeps only a thin slice sharp: her face. At f/16 the wall behind would be sharp too.",
                            "p1027",
                        ),
                        Step.Ask(
                            "Ten people, three rows deep, and everyone must be sharp. Which aperture?",
                            listOf("f/1.8", "f/8"),
                            1,
                            "A smaller opening like f/8 keeps more depth sharp, front row to back.",
                        ),
                        Step.OnCamera(
                            "On your camera",
                            "Turn the mode dial to A, then turn the command dial. The f-number shows in the viewfinder and on the screen.",
                            listOf(Control.ModeDial, Control.CommandDial),
                        ),
                        Step.TryIt(
                            "Melt a background",
                            listOf(
                                "Set A and turn the command dial to the smallest f-number.",
                                "Zoom in, get close to a face or a flower with space behind it, and shoot.",
                                "Now set f/11 and take the same photo. Compare.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "Small f-number = big opening = blurred background.",
                                "Big f-number = small opening = sharp from front to back.",
                                "In A you choose the aperture, the camera does the rest.",
                            ),
                        ),
                    ),
                ),
                Lesson(
                    "shutter", "Shutter speed", "How long the camera looks, and how it freezes or flows motion.", 5, "p88",
                    listOf(
                        Step.Read(
                            "A curtain that opens",
                            "The shutter is a curtain in front of the sensor. Shutter speed is how long it stays open: 1/1000 s is a blink, 1 s is a long, slow look.",
                        ),
                        Step.Play(
                            Playground.Shutter,
                            "Freeze or flow",
                            "Slow the shutter down and watch the traffic. The road stays put; only what moves changes.",
                        ),
                        Step.Read(
                            "Choosing a speed",
                            "Fast speeds freeze: 1/1000 s stops a running child or a splash. Slow speeds flow: on a tripod, 1 s turns a waterfall into silk and cars into streaks of light.",
                        ),
                        Step.Ask(
                            "Kids racing around the park, and you want them crisp. Which speed?",
                            listOf("1/15 s", "1/1000 s", "1 s"),
                            1,
                            "1/1000 s is short enough that even running feet freeze. Slower speeds would smear them.",
                        ),
                        Step.OnCamera(
                            "On your camera",
                            "Turn the mode dial to S and turn the command dial to change the speed. The camera picks the aperture to match.",
                            listOf(Control.ModeDial, Control.CommandDial),
                        ),
                        Step.TryIt(
                            "Make motion",
                            listOf(
                                "Set S at 1/1000 s and shoot moving water or traffic.",
                                "Now 1/15 s, with the camera resting on a wall.",
                                "Compare: frozen drops against flowing streaks.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "Shutter speed is how long the camera looks.",
                                "Fast freezes motion, slow turns it into streaks.",
                                "Slow speeds need steady hands or something to rest on.",
                            ),
                        ),
                    ),
                ),
                Lesson(
                    "iso", "ISO", "Brightness when light runs short, and the grain it costs.", 4, "p122",
                    listOf(
                        Step.Read(
                            "Turning up the sensor",
                            "ISO is how much the camera amplifies what the sensor catches. Doubling it doubles the brightness, but it also amplifies the noise, which shows as grain.",
                        ),
                        Step.Play(
                            Playground.Iso,
                            "A night on the river",
                            "Same aperture, same shutter speed. Only the ISO changes. Zoom in with two fingers to see the grain.",
                        ),
                        Step.OnCamera(
                            "On your camera",
                            "Out of the box, the Fn button on the front sets ISO: hold it and turn the command dial. Or press i and pick ISO.",
                            listOf(Control.Fn, Control.CommandDial, Control.IButton),
                        ),
                        Step.Ask(
                            "A bright beach at noon. Which ISO?",
                            listOf("ISO 100", "ISO 6400"),
                            0,
                            "There's plenty of light, so there's nothing to amplify. ISO 100 gives the cleanest photo.",
                        ),
                        Step.TryIt(
                            "See the grain",
                            listOf(
                                "Indoors in the evening, shoot the same scene at ISO 400 and at ISO 6400.",
                                "In playback, press zoom in and compare the shadows.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "ISO amplifies the light the sensor catches.",
                                "Low ISO is clean; high ISO is bright but grainy.",
                                "Raise it only when aperture and shutter can't give more light.",
                            ),
                        ),
                    ),
                ),
                Lesson(
                    "together", "All three together", "One amount of light, three ways to get it, and the meter that keeps score.", 5, "p1043",
                    listOf(
                        Step.Read(
                            "Three dials, one picture",
                            "Aperture, shutter speed and ISO all change how bright a photo is. Give with one and you can take with another: open the aperture a stop and you can use a shutter twice as fast.",
                            "p1043",
                        ),
                        Step.Play(
                            Playground.Exposure,
                            "Balance the meter",
                            "This is the meter you'll see in the viewfinder. Set the three dials so it sits at zero, then try another way to get there.",
                        ),
                        Step.Ask(
                            "Your photo is too dark, and the people in it are moving, so you can't slow the shutter. What now?",
                            listOf("A bigger f-number", "A wider aperture or a higher ISO", "A faster shutter"),
                            1,
                            "Both let in or amplify more light without slowing the shutter. A bigger f-number and a faster shutter would make it darker still.",
                        ),
                        Step.OnCamera(
                            "When the camera guesses wrong",
                            "In P, S and A the camera balances the exposure. If it comes out too dark or too bright, hold exposure compensation and turn the command dial.",
                            listOf(Control.ExposureComp, Control.CommandDial),
                        ),
                        Step.TryIt(
                            "Find zero",
                            listOf(
                                "Set M. Look through the viewfinder at the meter along the bottom.",
                                "Turn the command dial (shutter) until it centres. Hold ± and turn for the aperture.",
                                "In A, shoot a bright window, then dial in +1 and shoot again.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "Aperture, shutter and ISO trade light with each other.",
                                "The meter shows whether they add up: zero is balanced.",
                                "Exposure compensation corrects the camera's guess.",
                            ),
                        ),
                    ),
                ),
            ),
        ),
        Chapter(
            "Seeing",
            listOf(
                Lesson(
                    "light", "Light and colour", "Golden hour, backlight, and telling the camera what colour the light is.", 5, "p65",
                    listOf(
                        Step.Read(
                            "Golden hour",
                            "In the hour after sunrise and before sunset, the sun is low. Its light is warm, soft and comes from the side, with long shadows. It flatters nearly everything.",
                            "p65",
                        ),
                        Step.Ask(
                            "Where is the sun in this photo?",
                            listOf("Behind the camera", "Behind her", "Straight above"),
                            1,
                            "Backlight: the sun behind her lights the edges of her hair and leaves a soft glow. Lovely, but the camera may make her face too dark; that's when exposure compensation helps.",
                            "p399",
                        ),
                        Step.Play(
                            Playground.WhiteBalance,
                            "What colour is the light?",
                            "Light has a colour: candles are orange, shade is blue. White balance tells the camera which, so it can make white look white. Tell it the wrong light and watch.",
                        ),
                        Step.OnCamera(
                            "On your camera",
                            "White balance lives in the i menu: press i, choose White balance, and pick the light you're in. Auto is right most of the time.",
                            listOf(Control.IButton, Control.MultiSelector),
                        ),
                        Step.TryIt(
                            "Chase the light",
                            listOf(
                                "Photograph the same tree or street at noon and at golden hour.",
                                "Put someone with their back to a low sun and shoot; then add +1 exposure compensation.",
                                "Set white balance to Incandescent outdoors and see the blue.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "Low, warm sun makes everything look better.",
                                "Backlight glows, but may need brightening.",
                                "White balance tells the camera the colour of the light.",
                            ),
                        ),
                    ),
                ),
                Lesson(
                    "framing", "Framing", "Where things go in the frame, and where you stand.", 4, "p1011",
                    listOf(
                        Step.Read(
                            "The rule of thirds",
                            "Split the frame into a three-by-three grid. Placing your subject on one of the lines, or where two cross, usually feels more alive than dead centre, and leaves room for the scene around it.",
                        ),
                        Step.Play(
                            Playground.Thirds,
                            "Move the frame",
                            "She's dead centre in the original. Drag the frame until she sits on a third, with room in the direction she's looking.",
                        ),
                        Step.Read(
                            "Change where you stand",
                            "Height changes everything. Shooting a dog from above, like this, shows its world; crouching to its eye level makes it the hero. Before you press, try one step closer and one change of height.",
                            "p237",
                        ),
                        Step.Ask(
                            "A dramatic sky above a plain lake. Where should the horizon go?",
                            listOf("Through the middle", "On the lower third", "On the upper third"),
                            1,
                            "Put the horizon low to give two thirds of the frame to the sky, the interesting part. If the lake were the star, it would go on the upper third.",
                        ),
                        Step.OnCamera(
                            "A grid on your screen",
                            "Flick the live view switch, then press info until the framing grid appears over the picture.",
                            listOf(Control.LiveView, Control.Info, Control.Monitor),
                        ),
                        Step.TryIt(
                            "Frame three ways",
                            listOf(
                                "Turn on the grid. Shoot one scene with the subject centred, then on a third.",
                                "Shoot it again from your knees, and once more from as high as you can.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "Put the subject on a third, with room where it looks.",
                                "Give the sky or the ground two thirds, whichever is more interesting.",
                                "Move your feet: closer, lower, higher.",
                            ),
                        ),
                    ),
                ),
            ),
        ),
        Chapter(
            "Recipes",
            listOf(
                Lesson(
                    "r-portrait", "Soft background portrait", "Your subject sharp, everything behind melted into soft shapes.", 3, "p447",
                    listOf(
                        Step.Recipe(
                            Look.SoftBackground,
                            "Soft background portrait",
                            Mode.A,
                            listOf(
                                RecipeStep("Turn the mode dial to A.", Control.ModeDial),
                                RecipeStep("Turn the command dial to the smallest f-number it offers.", Control.CommandDial),
                                RecipeStep("Zoom all the way in, to 55 mm.", Control.ZoomRing),
                                RecipeStep("Step close to your subject, with the background far behind them."),
                            ),
                            "Your subject sharp, the background melted into soft shapes. The closer you stand and the farther away the background is, the softer it gets.",
                            "Your subject is right against a wall: there's no distance for the blur to grow in. The kit lens opens only to f/5.6 at 55 mm, so its blur is gentler than this preview.",
                        ),
                        Step.Ask(
                            "You've set A and the smallest f-number, but the background is still sharp. What helps most?",
                            listOf("Move them away from what's behind", "Raise the ISO", "Turn on the flash"),
                            0,
                            "Blur grows with the distance between your subject and the background. ISO and flash change how bright the photo is, not how blurred.",
                        ),
                        Step.TryIt(
                            "Melt it",
                            listOf(
                                "Find a person or a flower a few steps in front of a distant background.",
                                "Set A, the smallest f-number, zoom to 55 mm, and fill the frame.",
                                "Ask them to step farther from the background and shoot again. Compare.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "A, the smallest f-number, zoomed in and close: soft background.",
                                "Distance behind your subject matters as much as the f-number.",
                            ),
                        ),
                    ),
                    why = "aperture",
                ),
                // D5600 Reference Manual: Mode S p.121 (command dial sets shutter speed).
                Lesson(
                    "r-motion", "Freeze it or let it flow", "One street, two speeds: every car frozen, or streaming past.", 3, "p88",
                    listOf(
                        Step.Recipe(
                            Look.FreezeVsFlow,
                            "Freeze it or let it flow",
                            Mode.S,
                            listOf(
                                RecipeStep("Turn the mode dial to S.", Control.ModeDial),
                                RecipeStep("To freeze: turn the command dial to 1/1000.", Control.CommandDial),
                                RecipeStep("To let it flow: turn it down to 1/15, and rest the camera on something steady.", Control.CommandDial),
                                RecipeStep("Half-press to focus, then press all the way.", Control.Shutter),
                            ),
                            "At 1/1000 every car is frozen mid-lane. At 1/15 they stream into streaks while the road stays sharp.",
                            "Your hands move during the slow shot: then everything streaks, not just the traffic. And in dim light 1/1000 lets in so little light that the photo comes out dark.",
                        ),
                        Step.Ask(
                            "A waterfall, and you want the water silky. Which speed?",
                            listOf("1/1000 s", "1/8 s, camera resting on a rock"),
                            1,
                            "A slow speed lets the water move while the shutter is open, so it smooths into silk. Resting the camera keeps the rocks sharp.",
                        ),
                        Step.TryIt(
                            "Freeze, then flow",
                            listOf(
                                "Find moving traffic, a fountain or a friend jumping.",
                                "In S, shoot at 1/1000, then at 1/15 with the camera resting on a wall.",
                                "Compare what moved and what stayed sharp.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "S and a fast speed (1/1000) freezes motion.",
                                "S and a slow speed (1/15) with a steady camera lets it flow.",
                            ),
                        ),
                    ),
                    why = "shutter",
                ),
                // D5600 Reference Manual: White balance p.140–141.
                Lesson(
                    "r-warm", "Warm golden light", "Keep golden-hour light golden instead of letting the camera clean it up.", 3, "p65",
                    listOf(
                        Step.Recipe(
                            Look.Warm,
                            "Warm golden light",
                            Mode.P,
                            listOf(
                                RecipeStep("Turn the mode dial to P.", Control.ModeDial),
                                RecipeStep("Press the i button, highlight white balance and press OK.", Control.IButton),
                                RecipeStep("Choose Shade and press OK.", Control.MultiSelector),
                                RecipeStep("Shoot into the low evening light.", Control.Shutter),
                            ),
                            "The camera thinks it's in blue shade and warms everything up, so evening light stays golden instead of being neutralised.",
                            "You're indoors under lamps: they're already orange, and Shade makes them very orange. The camera remembers Shade, so set white balance back to Auto afterwards.",
                        ),
                        Step.Ask(
                            "You shot sunset portraits on Shade. Next morning you shoot indoors under lamps without changing anything. What happens?",
                            listOf("Everything comes out very orange", "Nothing changes"),
                            0,
                            "Shade warms every photo until you change it back, and lamp light is already orange.",
                        ),
                        Step.TryIt(
                            "Keep the gold",
                            listOf(
                                "An hour before sunset, shoot something lit by the low sun on Auto white balance.",
                                "Switch white balance to Shade and shoot it again.",
                                "Compare, then set white balance back to Auto.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "White balance Shade keeps warm light warm.",
                                "Set it back to Auto when you're done.",
                            ),
                        ),
                    ),
                    why = "light",
                ),
                // D5600 Reference Manual: Set Picture Control p.155–160 (Filter effects p.160).
                Lesson(
                    "r-mono", "Moody black & white", "Dark skies, bright clouds: black and white with a red filter.", 3, "p1011",
                    listOf(
                        Step.Recipe(
                            Look.MonoRed,
                            "Moody black & white",
                            Mode.P,
                            listOf(
                                RecipeStep("Turn the mode dial to P: Picture Controls can't be changed in AUTO.", Control.ModeDial),
                                RecipeStep("Press MENU and open the shooting menu.", Control.Menu),
                                RecipeStep("Choose Set Picture Control, highlight Monochrome and press ▶.", Control.MultiSelector),
                                RecipeStep("Highlight Filter effects and choose R, for red.", Control.MultiSelector),
                                RecipeStep("Highlight Contrast, press ▶ twice, then press OK.", Control.MultiSelector),
                            ),
                            "Blue sky turns dark, clouds and faces stay bright, and the whole picture gets punch.",
                            "The sky is white or grey: there's no blue for the red filter to darken. To go back to colour, choose Standard.",
                        ),
                        Step.Ask(
                            "Black and white, a blue sky with white clouds. Which filter makes the clouds stand out most?",
                            listOf("Red", "Green"),
                            0,
                            "Red holds back blue, so the sky goes dark and the white clouds stand out. Green does the opposite and softens skin.",
                        ),
                        Step.TryIt(
                            "Paint the sky dark",
                            listOf(
                                "On a clear day, find a scene with lots of sky and a few clouds.",
                                "Shoot it in Monochrome with no filter, then with red.",
                                "Compare the skies, then choose Standard again.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "Monochrome with a red filter: dark sky, bright clouds.",
                                "Choose Standard to go back to colour.",
                            ),
                        ),
                    ),
                    why = "light",
                ),
                // D5600 Reference Manual: Special effects p.65–68, Selective Color p.73–74.
                Lesson(
                    "r-pop", "Pop of colour", "One colour kept, everything else black and white.", 3, "p1011",
                    listOf(
                        Step.Recipe(
                            Look.PopColour,
                            "Pop of colour",
                            Mode.Effects,
                            listOf(
                                RecipeStep("Turn the mode dial to EFFECTS.", Control.ModeDial),
                                RecipeStep("Turn the command dial to Selective Color.", Control.CommandDial),
                                RecipeStep("Flip the live view switch, then press OK.", Control.LiveView),
                                RecipeStep("Frame the coloured thing in the white square, press ▲ to keep its colour, then press OK.", Control.MultiSelector),
                            ),
                            "One colour stays, everything else turns black and white: a red coat in a grey street jumps out.",
                            "The colour is pale: the camera struggles to pick up washed-out colours. Things of a similar colour elsewhere in the scene stay coloured too.",
                        ),
                        Step.Ask(
                            "Which subject suits Selective Color best?",
                            listOf("A red umbrella on a grey day", "A beige sofa against a beige wall"),
                            0,
                            "The effect needs one strong, saturated colour that nothing else in the scene shares.",
                        ),
                        Step.TryIt(
                            "Keep one colour",
                            listOf(
                                "Find one bright colour in a dull scene.",
                                "Set Selective Color and pick that colour.",
                                "Press ▲ or ▼ to widen or narrow the range of kept colours and shoot again.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "EFFECTS, then Selective Color, keeps one colour.",
                                "Pick a strong, saturated colour.",
                            ),
                        ),
                    ),
                ),
                // D5600 Reference Manual: Special effects p.65–68, Miniature Effect p.72–73.
                Lesson(
                    "r-miniature", "Tiny world", "A real street turned into a model railway.", 3, "p88",
                    listOf(
                        Step.Recipe(
                            Look.Miniature,
                            "Tiny world",
                            Mode.Effects,
                            listOf(
                                RecipeStep("Turn the mode dial to EFFECTS.", Control.ModeDial),
                                RecipeStep("Turn the command dial to Miniature Effect.", Control.CommandDial),
                                RecipeStep("Flip the live view switch.", Control.LiveView),
                                RecipeStep("Move the focus point with the multi selector onto what should stay sharp, and half-press.", Control.MultiSelector),
                                RecipeStep("Press OK, set the sharp band's width with ▲ and ▼, then press OK.", Control.MultiSelector),
                                RecipeStep("Shoot from high up, looking down."),
                            ),
                            "A thin sharp band, soft above and below, colours punched up: a real place that looks like a model.",
                            "You shoot at eye level: the trick only works looking down from high above, like a bridge, a balcony or a hill.",
                        ),
                        Step.Ask(
                            "Where does the miniature effect work best?",
                            listOf("From a balcony, looking down on a square", "Face to face with a friend"),
                            0,
                            "A model is always seen from above. Looking down on a real scene is what makes the soft band read as tiny.",
                        ),
                        Step.TryIt(
                            "Shrink a street",
                            listOf(
                                "Find a view from high up: a bridge, a balcony, a hill.",
                                "Set Miniature Effect and put the sharp band on the busiest part.",
                                "Shoot a few with people or cars moving through it.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "EFFECTS, then Miniature Effect: a sharp band, soft above and below.",
                                "Shoot from high up for the model look.",
                            ),
                        ),
                    ),
                ),
                // D5600 Reference Manual: Exposure compensation p.132, Silhouette effect p.68.
                Lesson(
                    "r-silhouette", "Sunset silhouette", "Your subject as a dark shape against a glowing sky.", 3, "p399",
                    listOf(
                        Step.Recipe(
                            Look.Silhouette,
                            "Sunset silhouette",
                            Mode.A,
                            listOf(
                                RecipeStep("Turn the mode dial to A.", Control.ModeDial),
                                RecipeStep("Put your subject in front of the bright sky."),
                                RecipeStep("Hold the ± button…", Control.ExposureComp),
                                RecipeStep("…and turn the command dial to −2.0.", Control.CommandDial),
                                RecipeStep("Shoot.", Control.Shutter),
                            ),
                            "Your subject becomes a dark shape with a clean outline against a glowing sky. The Silhouette effect on the EFFECTS setting does much the same in one step.",
                            "The sky behind isn't much brighter than your subject, or something bright lights them from the front. The camera keeps −2.0, so set ± back to 0 afterwards.",
                        ),
                        Step.Ask(
                            "For a silhouette, where should the light be?",
                            listOf("Behind your subject", "Behind you, on their face"),
                            0,
                            "A silhouette is a dark subject against a bright background, so the light has to come from behind them.",
                        ),
                        Step.TryIt(
                            "Shoot a shape",
                            listOf(
                                "Near sunset, put a person or a tree between you and the bright sky.",
                                "Shoot once at 0, then at −2.0.",
                                "Set ± back to 0.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "Subject against a bright sky, exposure −2: a silhouette.",
                                "Set ± back to 0 afterwards.",
                            ),
                        ),
                    ),
                    why = "together",
                ),
                // D5600 Reference Manual: Mode A p.122, Set Picture Control p.155–156.
                Lesson(
                    "r-landscape", "Punchy landscape", "Rich colour and sharp from front to back.", 3, "p1043",
                    listOf(
                        Step.Recipe(
                            Look.Vivid,
                            "Punchy landscape",
                            Mode.A,
                            listOf(
                                RecipeStep("Turn the mode dial to A.", Control.ModeDial),
                                RecipeStep("Turn the command dial to f/8.", Control.CommandDial),
                                RecipeStep("Press the i button, highlight the Picture Control and press OK.", Control.IButton),
                                RecipeStep("Choose Vivid and press OK.", Control.MultiSelector),
                            ),
                            "Richer greens and blues and more contrast, and sharp all the way from the front to the hills.",
                            "There are faces in it: Vivid makes skin look red and blotchy. Choose Standard or Portrait for people.",
                        ),
                        Step.Ask(
                            "You're photographing a friend. Which Picture Control?",
                            listOf("Portrait", "Vivid"),
                            0,
                            "Portrait keeps skin smooth and natural. Vivid pushes colour, which suits landscapes and flowers, not faces.",
                        ),
                        Step.TryIt(
                            "Make it sing",
                            listOf(
                                "Find a landscape with greens and blue sky.",
                                "In A at f/8, shoot it on Standard, then on Vivid.",
                                "Compare, then choose Standard again.",
                            ),
                        ),
                        Step.Recap(
                            listOf(
                                "A at f/8 with Vivid: sharp, rich landscapes.",
                                "Choose Standard again for people.",
                            ),
                        ),
                    ),
                    why = "aperture",
                ),
            ),
        ),
    )
}
