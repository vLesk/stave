# Stave: a touch-first MuseScore port for Android

Stave is an Android music notation editor that ports the core of **MuseScore Studio** to phones and
tablets. It reads and writes MuseScore's own file format (`.mscz` / `.mscx`), uses MuseScore's
**Leland** music font and **Edwin** text font, and follows MuseScore's editing model: selection,
note input mode, palettes, voices, and MuseScore's keyboard shortcuts. The desktop interface was
redesigned for touch screens.

> **Download:** the latest APK from [Releases](https://github.com/vLesk/musescore-android/releases/latest).
> Android 7.0+, targets Android 15.
> Enable *Install unknown apps* for your browser or file manager, then open the APK.

*Unofficial project. "MuseScore" is a trademark of Muse Group, and this app is not affiliated
with or endorsed by Muse Group, so it ships under its own name.*

| Library | Editor (imported .mscx) | Floating toolbar | Note input | Palettes |
|---|---|---|---|---|
| ![](docs/screenshots/library.png) | ![](docs/screenshots/editor-imported.png) | ![](docs/screenshots/floating-toolbar.png) | ![](docs/screenshots/note-input.png) | ![](docs/screenshots/palettes.png) |

*Screenshots come from the Robolectric UI tests, rendered with Android's real graphics stack on the JVM.
They show version 0.2.0; later versions added controls (slur, hairpin, grace, respell, the lyric bar).*

## Why not a direct recompile of MuseScore?

MuseScore 4 is about a million lines of C++/Qt 6/QML. Building it for Android needs Qt for Android and
the Android NDK. Even with those, the result would be a desktop UI (menus, docked panels, mouse
hover, keyboard-first input) squeezed onto a 6-inch touchscreen. Stave reimplements the parts
musicians use day to day as a native Android app (Java, Android framework only, no third-party
libraries, about 600 KB). It stays file-compatible with MuseScore, so you can start a score on
your phone and finish it on the desktop, or the other way round.

## Features

| MuseScore (desktop) | Stave (Android) |
|---|---|
| Score files `.mscz`/`.mscx` (3.x–5.x) | Open and save. Writes MuseScore 4.20-format `.mscz` that MuseScore Studio opens directly |
| MusicXML import/export | `.musicxml`, `.xml`, `.mxl` import; MusicXML 4.0 export |
| Export PDF / MIDI | A4 PDF at MuseScore's default staff size; Standard MIDI File |
| Note input mode (`N`), durations, dots and double dots, rests, ties | Same, from a thumb-reachable bottom toolbar |
| Tuplets (`Ctrl+2`–`Ctrl+9`) | Triplet button; long-press it (or `Ctrl+2`–`Ctrl+6`) for duplets, quadruplets, quintuplets and sextuplets. The value selected when you turn tuplets on is the tuplet's unit |
| Typing note names A–G | **On-screen piano keyboard** (scrollable octaves): keys pressed while others are held **play a chord**. Plus tap-on-staff entry |
| Mouse note input with a shadow note | Touch the staff and a **ghost note follows your finger**. A **pitch bubble above the finger** names the note your finger is covering. Lift to enter |
| Voices 1–4 (blue/green/orange/purple) | Same colors; voice chips in the toolbar |
| Add to chord (Shift+letter) | Held piano keys, or the "Add to chord" toggle |
| ↑/↓, Ctrl+↑/↓ transpose, `J` respell | Buttons, plus the same hardware-keyboard shortcuts |
| Slurs (`S`) and hairpins (`<` `>`) | Same keys and toolbar buttons. In note input a slur or hairpin follows the notes you enter until pressed again; on a selected note each press extends it by one note. Hairpins shape playback volume, slurs play legato |
| Grace notes | Grace button (long-press: acciaccatura, appoggiatura, 16th appoggiatura) adds one before the selected note; the next piano key sets its pitch |
| Palettes (clefs, keys, times, accidentals, articulations, dynamics, barlines, text) | **Bottom-sheet palette** with large 76 dp cells and category chips. All 33 of MuseScore's standard-staff clefs (octave, C, French, 19th-century and percussion clefs); like MuseScore, glyphs Leland lacks are drawn from Bravura |
| Lyrics (type, Space → next syllable, `-` hyphen, `_` melisma) | **Lyric entry mode, typed directly on the score** with the virtual or a hardware keyboard: Space moves to the next note, `-` and `_` work as in MuseScore. A bar above the keyboard has previous/next, `-`, `_` and **Done**. Hyphens and melisma lines are laid out as MuseScore does |
| Chord symbols, staff text, tempo, rehearsal marks | Yes |
| Repeats, double/final/dashed barlines, pickup measure | Yes |
| Copy/paste, range selection (Shift+click) | Tap a measure, **long-press** to extend the range |
| Instruments panel | Add, remove and reorder instruments. **All of MuseScore's instruments** (every template in its `instruments.xml`), with MuseScore's groups, names ("Clarinet in A"), MIDI sounds and clefs, in a searchable list |
| Mixer, playback with cursor | Playback through Android's built-in General MIDI synth, a cursor that moves smoothly through each measure, speed 50–200 %, per-part volume and mute |
| Page view | **Reflowed continuous view** that wraps to the screen width, with pinch-to-zoom. A line too dense for the screen keeps its spacing and **scrolls sideways** on its own. Turning the device keeps your place in the score |
| New score wizard | One-screen form with templates (piano, SATB, string quartet, lead sheet …) and sensible defaults |

## Android usability and design choices

These choices follow Android and Material Design 3 guidance, plus established touch-notation
patterns (Soundslice's tablet piano mode, Sibelius for mobile):

* **Material 3**, including **Material You dynamic color** on Android 12+ (the UI picks up wallpaper
  colors) and a full **dark theme**. The score paper also goes dark.
* **48 dp minimum touch targets** everywhere. Palette cells are 76 dp. Taps on notes are matched with a
  "fat-finger" tolerance radius, so you don't need pixel precision.
* **Primary actions in the thumb zone.** Note input happens in a docked toolbar and piano at the
  bottom, and everything else is in the overflow menu or the floating toolbar.
* **Edge-to-edge** layout with correct system-bar and display-cutout insets, as Android 15 requires
  for apps targeting API 35.
* **Predictive back** (`OnBackInvokedCallback` on Android 13+). Back closes the palette, leaves
  note input and clears the selection before it exits.
* **Adaptive layout.** On wide screens (≥ 720 dp: tablets, foldables, landscape) the two toolbars
  merge into one row, the palette sheet is width-capped and the keyboard grows taller. The score
  re-flows to any width, and rotating keeps the same place in the score and the zoom.
* **Haptic and audio feedback** on every note entry. A low-latency synth previews each pitch.
* **Hardware keyboards and Chromebooks** get MuseScore's shortcuts: `N`, `A–G`, `1–7`, `.`, `0`,
  `+`, `S`, `<`, `>`, `J`, arrows, `Ctrl+2–6`, `Ctrl+Z/Y/C/V/A/S`, `Space`, and more. See
  *⋮ → Keyboard shortcuts*.
* **Fast on long scores.** After an edit only the systems it touches are laid out again; the
  rest of the engraving is reused.
* **Storage Access Framework** for import and export (no storage permission requested). The
  **share sheet** sends PDF, MusicXML, MIDI or `.mscz`, and Stave registers as an *Open with* target
  for MuseScore and MusicXML files.
* **Autosave.** Every edit is saved 1.5 s later and when the app goes to the background. The
  undo history keeps 80 steps.
* **Accessibility:** content descriptions and tooltips on all controls, state descriptions on toggles,
  TalkBack pitch announcements on the piano keyboard, and sp-based text.

### Design patterns adopted from reference apps

| Pattern | Where it comes from | In Stave |
|---|---|---|
| **Tools fade away so the music is in focus** | forScore's "clean interface … then fades away" | With nothing selected, the editor shows only the score and an extended **Write** FAB. The FAB shrinks to an icon while you scroll |
| **Floating toolbar for contextual actions, paired with a FAB** | Material 3 Expressive toolbars | Selecting a note shows a floating pill with duration, pitch ▲▼, accidentals, tie, delete, palettes and more. Selecting a range shows transpose, copy, paste and delete. The FAB collapses beside it |
| **Docked toolbar for the active mode** | Material 3 Expressive (the docked toolbar replaces the bottom app bar) | Note input mode has a docked toolbar above the piano keyboard |
| **Connected button groups / split buttons** | Material 3 Expressive | Durations, dot/triplet and voices are connected single-select groups whose selection morphs into a filled pill. The duration and accidental buttons in the floating toolbar open small button groups |
| **Labelled tools** | StaffPad reviewers found its unlabelled toolbar confusing | Every note-input tool has a text label (Rest, Tie, Flat, Chord, Voice, Keys …) |
| **Tap a note, then change it right there** | Flat's mobile editor ("tap a note again to change its length or delete it") | The floating toolbar edits the selected note in place |
| **Touch piano + tap-the-staff entry** | Flat, Soundslice, Dorico for iPad | Piano keyboard, plus a ghost note with a pitch bubble when you touch the staff |
| **Undo instead of confirmation** | Material confirmation guidance: reversible actions don't need dialogs | Deleting notes, measures or library scores, and pasting, show a snackbar with **Undo**. Toasts are replaced by snackbars |
| **Search bar + filter chips** | Material 3 search bar | The library has a search pill (title, composer, instruments) and Recent / A–Z chips |
| **One-time coach mark** | Common onboarding pattern | A dismissible "Write your first notes" card on first open |

## Building

The APK is built **without Gradle**, by `tools/build.sh` (aapt2 → javac → dx → zipalign →
apksigner). Ubuntu/Debian packages provide the tools, and the Android 14/15 platform files come
from Robolectric's `android-all` artifacts on Maven Central:

```sh
sudo apt install openjdk-21-jdk aapt zipalign apksigner dalvik-exchange
tools/build.sh                 # -> build/stave.apk (signed with keystore/debug.jks)
```

For a release key, set `STAVE_KEYSTORE`, `STAVE_KEYSTORE_PASS` and `STAVE_KEY_ALIAS`. The included
`keystore/debug.jks` (password `android`) is a public debug key. Don't use it for Play Store releases.

Android Studio users can open the project with the provided Gradle files instead (standard
`app/src/main` layout). Those files were not exercised in this repository's build environment.

## Versioning and releases

Releases start at **0.1.0**, and each release increases the minor version (0.2.0, 0.3.0, …).
The Android `versionCode` is derived from the version (`major·10000 + minor·100 + patch`), so
every release installs over the previous one.

To publish a release, run **Actions → Build and release APK → Run workflow**. Leave the version
empty to get the next minor version automatically. Pushing a tag such as `v0.3.0` also works.
The workflow runs the tests, builds and signs the APK, and attaches it with its SHA-256 checksum
to a GitHub release. *Delete release* is a maintenance workflow that removes a release and its tag.

| Version | Highlights |
|---|---|
| 0.1.0 | First Android port: score model, MuseScore/MusicXML/MIDI/PDF I/O, engraving, note input, piano keyboard, palettes, playback |
| 0.2.0 | Redesign: floating contextual toolbar, docked note-input toolbar with button groups and labels, Undo snackbars, library search and sorting, first-run tip |
| 0.3.0 | System barlines, brackets and braces stay visible while scrolling |
| 0.4.0 | The playback cursor moves continuously through each measure |
| 0.5.0 | Lyrics typed directly on the score in a lyric entry mode, with MuseScore's hyphen, melisma and layout rules |
| 0.6.0 | Slurs, hairpins, grace notes, double dots, duplets to sextuplets, respell, chords played on the piano, sideways scrolling of dense lines, faster editing of long scores, place kept on rotation, `rf` dynamic; fixes found by entering five real scores |

## Tests

```sh
tools/test.sh [dir-with-.mscx-files]      # JVM tests: model, editing, file formats, playback
RENDER_DIR=/tmp/png tools/test.sh         # also renders engraving samples to PNG via Java2D
ROBO_LIB=/path/to/robolectric-jars tools/robolectric.sh   # UI smoke tests + screenshots
tools/simulate.sh                         # enters five complex scores as a user would and compares
```

`tools/robolectric.sh` needs Robolectric 4.14 and its dependencies from Maven Central, plus
`androidx.test` *monitor* classes. Where Google's Maven repository is unreachable, compile those
classes from the Apache-2.0 sources in `github.com/android/android-test` (`runner/monitor`).

`tools/simulate.sh` installs music21 (from PyPI) into the build cache and takes Schubert's *Der
Lindenbaum*, Joplin's *Maple Leaf Rag*, the first movement of Beethoven's Op. 18 No. 1, Clara
Schumann's Polonaise Op. 1 No. 2 and the second movement of Beethoven's Op. 59 No. 2 from its
corpus. It enters every note, grace note, slur, hairpin, dynamic and lyric through the editor with
the controls a user would tap, then checks the result note by note and the layout at phone width.
Mistakes in the source files (a tie into another pitch, a note past the barline) are counted
separately from the app's problems.

`tools/test.sh` also loads MuseScore's own engraving test corpus when you pass its directory
(`src/engraving/tests` in the MuseScore repository). It round-trips every file through the
`.mscx` writer and the MusicXML exporter.

## Source layout

```
app/src/main/java/io/github/vlesk/stave/
  model/    score model + editing engine (pure Java, unit-tested)
  edit/     EditorState: selection, note input, undo/redo (pure Java)
  engrave/  layout engine -> display list (beams, accidentals, ties, tuplets, systems)
  io/       .mscz/.mscx reader+writer, MusicXML reader+writer, score library
  audio/    playback timeline, MIDI writer, MediaPlayer playback, preview synth
  ui/       activities and custom views (score canvas, piano, palettes)
tools/      build.sh, test.sh, robolectric.sh, simulate.sh (+ sim/extract.py), gen_glyphs.py,
            gen_instruments.py (instrument catalog from MuseScore's instruments.xml),
            gen_supplement_font.py (Bravura glyphs Leland lacks)
test/       JVM tests, Java2D renderer, input simulation, Robolectric UI tests
```

## Known limitations

* Scores are shown and edited at concert pitch. Transposing-instrument display is not implemented yet,
  so variants such as Clarinet in B♭ and Clarinet in A differ only in name. Tablature instruments
  are not offered (they open as standard notation), and neither are the four tablature clefs.
* MusicXML has no clef style variants: French, 19th-century and parenthesised clefs export as
  their plain form, as in MuseScore.
* Not yet supported: other lines (8va, pedal, voltas, text lines), cross-staff notation, tablature,
  measure repeats and multi-measure rests. Imported files keep their notes but drop these elements;
  after import the app lists what it simplified (tablature, nested tuplets, measure repeats,
  rhythms it could not read).
* One slur and one hairpin can start on a note, so `< >` over a single held note is not possible.
  Slurs from a grace note to its main note, and slurs across staves or voices, are not supported.
* Septuplets and larger tuplets, triple dots and values shorter than a 64th are not offered.
* Playback uses the platform's General MIDI synthesizer, not MuseScore's MuseSounds or SoundFonts.
* Mid-measure clef changes and nested tuplets are not supported.

## License

GPL-3.0, like MuseScore. The fonts (Leland, Edwin; © MuseScore Ltd) are under the SIL Open Font
License 1.1, with license files in `app/src/main/assets/fonts`. `StaveSupplement.otf` is a renamed
subset of Bravura (© Steinberg Media Technologies, SIL OFL 1.1) with the six clef glyphs Leland lacks. The Material Symbols icons are
Apache-2.0 (`third_party/MATERIAL-ICONS-LICENSE`). The instrument data comes from MuseScore's
`instruments.xml` (GPL-3.0).
