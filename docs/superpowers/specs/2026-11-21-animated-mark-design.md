# Animated mark

**Why:** the Shutter + Frame logo doubles as the app's status language, so a glance at it says what the app is doing.
Approved from the preview video (`demos/icons/states/states.mp4`, rendered by `demos/icons/states/render_states.py`).

## States (approved)

| State | Motion |
|---|---|
| Idle | still |
| Searching | corners breathe like autofocus hunting (1.4 s), a jade arc circles the ring (1.2 s), the dot dims a little |
| Connected | arriving from another state: corners snap in with a small overshoot, the dot gives one bright beat |
| Working | the dot pulses once a second |
| Shot (event) | the dot presses in (~90 ms), springs back, a thin flash ring spreads out |
| Lost | corners drift apart and fade with a slow wobble, the dot goes grey |

## Where it shows (my calls)

- **Connect screen header:** form = Idle, connecting = Searching, failed or dropped = Lost.
- **Gallery header,** beside the camera name: reading the card or saving = Working, otherwise Connected; a new photo arriving fires a shot. The lock therefore plays when the card finishes reading or a save finishes ("done"), never on simply opening the screen.
- **Remote status pill:** shooting, bulb or recording = Working; every shot fires the shot event.
- The connect mark flies into the gallery mark on connect (shared element), like photo tiles do.
- Not on the Quick Settings tile (tiles can't animate), not on the empty state (the header mark is already on screen).

## Rules

- One component, `ViewfinderMark(state, shots)` in `ui/Mark.kt`; it replaces `LensMark`.
- All timing from `Motion`; with reduced motion each state is shown as a still pose, no loops.
- Decorative: no content description; the surrounding text says the state.
- Drawn in the draw phase only (animated values read inside the draw lambda), so frames never recompose.
