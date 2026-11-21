# Quick sharing

## Intent

Get photos off the camera and into other apps or to other people in as few taps as possible: one photo from the
viewer, many after a selection in the gallery, or the shot just taken on the remote. Falta wants this before the
photo editor; the editor will later plug in here, so sharing sends edited versions.

Today the app can save photos to the phone (`MediaSaver` into `Pictures/Viewfinder`, through the one-at-a-time save
queue in `CameraViewModel`) but has no way to share them; only the connection log has a share button.

## Goals

- Share one photo, a bulk selection, or the latest shot, through Android's share sheet (so Quick Share, WhatsApp,
  Instagram, Drive, mail all work).
- The fastest path is one tap with your saved defaults; options are one more tap away.
- Choose size, format, crop, an optional photo card, and what metadata leaves the phone.
- Never send a worse file than the one chosen, and never leak location when location stripping is on.

## Non-goals (for now)

- Editing (the editor project comes next and will reuse this pipeline).
- Converting RAW into anything (needs the RAW project); RAW files can only be shared as untouched originals.
- Uploading to specific services through their APIs; the share sheet covers it.

## Anti-goals

- Sharing that feels slower than SnapBridge, or a share button that does nothing visible while files download.
- Temporary files piling up on the phone.
- A layout that jumps while files are prepared.

## Where you can share from

1. **Viewer:** a Share button beside Save in the bottom bar. Tap: share with your defaults. Long-press, or the small
   options chevron on the button: open the share panel first.
2. **Gallery selection bar (bulk):** a Share button next to Save and Delete. Same tap / long-press behaviour, for
   every selected photo.
3. **Remote:** the latest-shot thumbnail gets a share action (shoot, then share at once). Long-pressing the thumbnail
   opens the share panel for that shot.

While files are fetched and prepared, the button itself shows progress (a ring for one photo, "Preparing 3 of 12" in
the selection bar) and can be cancelled. The share sheet opens when everything is ready.

## The share panel

A bottom sheet with a live preview of the first photo and these rows. Every choice is remembered the moment
it's made (even if the panel is closed without sharing) and becomes the default for one-tap shares.

| Row | Choices | Default |
|---|---|---|
| Format | Original file · JPEG · HEIF · WebP · ZIP of originals · Contact sheet (PDF) | Original file |
| Size | Full · 2048 px · 1080 px (long edge) | Full |
| Quality | High · Smaller (JPEG 92/80, HEIF and WebP equivalents) | High |
| Crop | As shot · 4:5 · 1:1 · 9:16 | As shot |
| Photo card | Off · White border with a caption line ("D5600 · 35 mm · f/1.8 · 1/250 s · ISO 200") | Off |
| Privacy | Remove location (on by default) · Remove all camera details | Remove location |

Rows that don't apply to the chosen format are hidden, not greyed out: Original and ZIP send files untouched, so
size, quality, crop and card disappear; the contact sheet has no crop or card. A footnote says shared photos are also
saved to `Pictures/Viewfinder`.

Single-photo crops can be repositioned by dragging in the preview; bulk crops are centred.

A "Last used: <app>" chip in the panel shares straight to the app picked last time.

### What each format is for

- **Original file:** exactly what came off the card (JPEG, RAW or video), metadata intact apart from privacy
  stripping. If the original carries no location, it's shared as is, with no copy made.
- **JPEG:** goes everywhere.
- **HEIF:** about half the size of JPEG at the same quality; good for Samsung and Apple devices. Uses the phone's
  hardware encoder (`androidx.heifwriter`); if the phone has none, the row is hidden.
- **WebP:** small files for the web and chats.
- **ZIP of originals:** many originals as one file, for mail, Drive or Telegram, where dozens of attachments are a
  pain. Includes RAW and videos.
- **Contact sheet (PDF):** a grid of the photos with file name and settings under each, for proofing or picking
  shots with someone. Built with Android's `PdfDocument`.

### Bulk rules

- A RAW+JPEG pair shares its JPEG, except under Original or ZIP, where both go.
- RAW-only files are skipped for JPEG, HEIF and WebP, with a note ("2 RAW files skipped: they can only be shared as
  originals").
- Videos are always sent as original files and never converted.

## How it works (decided)

1. **Get the file onto the phone through the existing save queue.** Sharing a photo that isn't saved yet enqueues it
   like Save does and waits for it; a saved photo whose gallery copy was deleted is fetched again. One download path,
   one place that knows what's saved, and the photo ends up in the gallery anyway. The queue needs a way to await a
   handle's completion.
2. **Share the gallery copy directly when nothing needs changing** (Original format, and no location to strip).
3. **Otherwise render a copy** into the app's cache (`cache/share/`), shared through a `FileProvider`: decode at the
   target size with `ImageDecoder`, apply the crop, draw the card, encode in the chosen format, then copy the EXIF
   tags back (`androidx.exifinterface`) minus location or minus all camera details, with orientation baked in.
   ZIP and PDF are written to the same folder.
4. **Clean up:** files in `cache/share/` older than a day are deleted when the app starts and before each new share.
5. **Hand off:** `ACTION_SEND` or `ACTION_SEND_MULTIPLE`, with all URIs in `ClipData` and read permission granted;
   the MIME type is the common one (`image/jpeg`, `image/*`, or `*/*` for mixed). The chooser is created with an
   `IntentSender` callback so the picked app is remembered for the "Last used" chip.

## Where the code goes

- `ptp/` is untouched; this is all app side.
- `Sharer` (beside `MediaSaver`): the rendering, ZIP, PDF, EXIF and intent building.
- `ShareOptions` (format, size, quality, crop, card, privacy, last app) persisted in `Settings`.
- `CameraViewModel.share(items, options)` with a `sharing` progress value in `UiState`, cancellable.
- The choice of which file each item sends (the bulk rules above) and the crop and caption maths are pure functions,
  so they get JVM unit tests (the app gets a `src/test` with JUnit).

## Design rules that apply

From `AGENTS.md`: tokens not raw colours, `Motion.current` for every animation (the sheet uses `expand`, the progress
ring springs), `pressScale` on new buttons, 48dp touch targets, content descriptions on icon-only buttons, nothing
jumps (the selection bar reserves the progress label's space), and everything respects reduced motion.

## Left to the builder

Icons, exact sheet layout and spacing, the photo card's typography (Inter, from the theme), the PDF grid size,
HEIF/WebP quality numbers for "High" and "Smaller", and error wording.

## Decisions after the builder check (my calls)

1. **Privacy on files the app can't rewrite.** JPEG originals with location get a cleaned copy (metadata rewritten,
   pixels untouched). RAW, HEIF and video can be read but not cleaned: with "Remove location" on, such a file is sent
   only if it has no location (EXIF read for RAW/HEIF, `MediaMetadataRetriever` for video); otherwise it's skipped with
   a note ("2 files skipped: their location can't be removed. Turn off Remove location to send them"). "Remove all
   camera details" applies to rendered copies and JPEG originals only; RAW and video keep theirs (stripping would break
   them), and the panel says so. The guarantee holds: location never leaves while the switch is on.
2. **Only the files being sent are downloaded.** JPEG, HEIF and WebP fetch the JPEG of a pair; Original and ZIP fetch
   both.
3. **The contact sheet uses the camera's previews,** not full downloads: fast, covers RAW-only files and videos (from
   their preview or thumbnail), and nothing is saved to the gallery for it. It's one PDF; nothing else is attached.
4. **One tap opens Android's share sheet** with the saved options. "Last used" stays a chip inside the panel; if that
   app is gone, the chip disappears.
5. **Format is remembered separately for one photo and for a selection;** the other rows are shared. ZIP and Contact
   sheet are offered only for selections, so a single photo never inherits them.
6. **Cancel stops the share, not saving in progress.** A download already running finishes into the gallery; files the
   share queued that haven't started are dropped, unless Save had also asked for them.
7. **Remote:** a small Share button sits beside the latest-shot thumbnail (tap shares, long-press opens the panel).
   Tapping the thumbnail still opens the viewer.
8. **Panel order:** Format comes first and decides which rows show; rows appear and disappear with the expand/collapse
   motion. The earlier "JPEG whenever a size, crop or card is chosen" line is replaced by this.
