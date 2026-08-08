# screen-translator

Draws Korean over the Japanese in Hot Pepper Beauty (`jp.hotpepper.android.beauty.hair`).

Android cannot edit another app's text — process isolation makes the accessibility API
read-only, so there is no DOM-swap equivalent. Drawing on top is the only route, which
means we redo the layout and colour matching ourselves.

Text and coordinates come from the accessibility tree, not OCR and not screen capture:
the target app is pure native (0 `WebView`, 18 `TextView` on the home screen), every
Japanese string is in `text=` with exact `bounds=`. That gives exact strings, no capture
icon in the status bar, and less battery use.

## Build

```bash
./gradlew installDebug          # builds, installs; no separate adb install
./tools/dev.sh enable           # re-grant accessibility (a reinstall always revokes it)
./tools/dev.sh log              # adb logcat -s ScrTrans
./tools/dev.sh shot out.png     # screencap with an explicit display id
```

`tools/dev.sh install` does the build and the re-grant in one step.

To check alignment, set `OverlayView.DEBUG_GRID = true`. It draws 1px rules at absolute
(500, 1000), outlines every node box in cyan and forces an opaque backdrop.
`python3 tools/measure.py shot.png` then reports, per box, where the ink sits relative to
the box centre — which is how the 3px vertical offset below was found and confirmed fixed.

Toolchain, pinned and verified on this machine:

| | |
|---|---|
| JDK | 21.0.5 (`brew install openjdk@21`, keg-only; pinned in `gradle.properties` via `org.gradle.java.home` so the global `java` is untouched) |
| SDK | `/opt/homebrew/share/android-commandlinetools`, `platforms;android-36`, `build-tools;36.0.0` |
| AGP / Kotlin / Gradle | 8.13.2 / 2.3.21 / 8.14.5 |
| compile/target/min SDK | 36 / 36 / 30 |
| dependency | `com.google.mlkit:translate:17.0.3` — the only one |

No AndroidX, Material or Compose in our own code, and no XML layouts: `MainActivity` is a
plain `android.app.Activity` assembling views in code. Without an IDE preview, XML costs a
dependency tree and buys nothing.

ML Kit needs network **once**, to download the ja and ko models. Glossary terms resolve
offline regardless.

## Shape

```
TranslatorService (AccessibilityService)
  300ms debounce -> walk tree -> text + getBoundsInScreen
  drop strings with no kana/kanji ("10", "¥5,500", "OPEN")
  not the target package -> clear the overlay

CachingTranslator( GlossaryEngine( MlKitEngine() ) )
  CachingTranslator  cache, in-flight dedup, synchronous lookup. Engine-independent,
                     so it lives here and nowhere else.
  GlossaryEngine     exact-match hit skips the engine entirely
  MlKitEngine        ja -> ko, on device

OverlayManager   window lifecycle
OverlayView      Canvas drawing, no layout system (coordinates are already absolute)
TranslationLog   source/result pairs as JSONL
```

`TextTranslator` and `TranslationEngine` are separate on purpose. `onDraw` needs a string
now; translation is async. `translateOrNull` returns null, the source gets drawn, and the
result callback triggers a redraw. That shape is what lets a 0ms local engine and a
several-hundred-ms network engine (DeepL, which does ja→ko directly and skips the English
round-trip) swap in without touching the drawing code. A synchronous
`translate(text): String` would rule the network engine out.

## Things that bite

1. **`TYPE_ACCESSIBILITY_OVERLAY`, never `TYPE_APPLICATION_OVERLAY`.** No
   SYSTEM_ALERT_WINDOW permission needed, and the window is excluded from the
   accessibility tree — otherwise we read back our own Korean and loop. Verified: while
   the Korean overlay is on screen, `uiautomator dump` finds 0 Hangul nodes.
2. **Touch pass-through** — `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN
   | FLAG_LAYOUT_NO_LIMITS`, plus `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`.
3. **Coordinates sit low by the status bar height.** `getBoundsInScreen()` is absolute to
   the display; the overlay window starts below the status bar (109px here). The flags
   above do not remove the inset. `OverlayView.onDraw` calls `getLocationOnScreen` and
   translates the canvas by the negative of it — measured every frame, so it holds on any
   device or OS version.
4. **Do not set `android:packageNames`.** Filtering there means never hearing about
   leaving the target app, so the last screen's boxes linger over whatever came next.
   Take every event; check `rootInActiveWindow.packageName` in `collect()` and clear.
5. **Text size is unavailable.** `extraRenderingInfo?.textSizeInPx` was null on every
   node. Box height is the only estimate.
6. **Colour is unavailable.** No background or text colour in the tree. Reading pixels
   would need MediaProjection, which puts a capture icon in the status bar permanently.
   Fixed colours instead.

## Rendering

- Whole node: translucent white `argb(120,255,255,255)`, so button pink and the selected
  tab underline still read through.
- Behind the text: opaque white, full node width, over the line height.
- Text: left aligned, `rgb(24,24,28)`, starting at 0.62 x box height, shrinking 1px at a
  time until it fits, floor 14px, ceiling 60px. Then every node of the same height is
  dropped to the smallest size among them — see below.
- Not yet translated: the source in `rgb(150,150,156)`.
- No borders.

Splitting the node wash from the opaque band is the whole trick. All-translucent keeps the
app's colours but the original shows through and neither is readable; all-opaque is
readable but turns the app into a field of white boxes.

Do not centre the text. Centring moves the problem onto the tab and button labels that
currently read fine, rather than solving it.

Three of these values came from device screenshots during this build rather than from the
original spec, and each is commented at its definition in `OverlayView.kt`:

- **The opaque band spans the node's full width, not just our glyphs.** Glyph-width-only
  assumes the source and the translation occupy the same span. Korean is usually shorter
  and the Japanese is often centred, so the tail stayed legible — "미디엄ディアム".
- **60px text ceiling.** The 検索 node is 144px tall around ~40px text, so 0.62 x height
  gave 89px type and a band thick enough to swallow the button's pink.
- **Vertical centring uses fontMetrics top/bottom, not ascent/descent.** `TextView`
  defaults to `includeFontPadding=true` and centres on top..bottom; centring on
  ascent..descent put every string `(descent - ascent + |top| - bottom) / 2` ~ 0.075em
  high — 3px at 36px, which reads as the whole overlay sitting subtly off. Measured with
  `DEBUG_GRID` and `tools/measure.py`: our ink centre went from -0.4px to +2.1px against
  the node centre, where the Japanese it replaces sits at +2.4px.
- **Equal-height nodes share one text size.** Sizing each label on its own made siblings
  wildly uneven, because shrink-to-fit is driven by how long each translation happens to
  be while the Japanese it replaces was all one size: one list had "핸섬 숏" at 60px
  beside "보브 스타일링 헤어" at 30px. Nodes of equal height held equal-sized text, so
  they are grouped by height and the group takes its smallest member — that list is now
  uniformly 30px. Items that overflow even at the 14px floor are excluded from the vote;
  they are sentences, not labels, and would drag their group down with them.
- **A multi-line source gets its whole node covered.** The message card's node is
  `[48,721][352,916]` — 195px around ~92px of text sitting against the bottom, not
  centred — so a centred band misses the second line. Costs the decorative icon inside
  such nodes; only single-line nodes need their colour showing through, so buttons and
  tabs are unaffected.

## Glossary

ML Kit has no ja→ko model. It routes ja→en→ko and beauty jargon does not survive the
detour; it also keeps turning noun phrases into "-하십시오" imperatives, which is the
English round-trip showing through. `セミロング` came back as "semilone".

Exact match only. Substring replacement splices terms into the middle of longer sentences.

`Glossary.kt` is split in two, because a wrong glossary entry is worse than ML Kit — it is
wrong with confidence:

- `VERIFIED` — observed on a real device, either a confirmed mistranslation or a read UI label.
- `GUESSED` — plausible domain terms never checked against a real screen.

Entries that fire are tagged in the log, so the guesses can be reviewed against what
actually appeared:

```bash
adb pull /sdcard/Android/data/com.scrtrans/files/translations.jsonl
grep '"guessed":true' translations.jsonl     # unverified entries that were actually used
grep '"fromGlossary":false' translations.jsonl   # what ML Kit produced, for new failures
```

```json
{"source":"セミロング","result":"세미롱","fromGlossary":true,"guessed":false}
```

## Known limits

- Text baked into images is invisible to us — the promotional banner stays Japanese.
- A node whose bounds do not match its glyphs renders oddly. `ログイン` reports a 23px-tall
  box, so the label draws tiny.
- Strings with embedded counts (`未読のお知らせが 10件 あります。`) cannot be glossary
  entries; they go to ML Kit whole.
- Only `jp.hotpepper.android.beauty.hair`. `TARGET_PACKAGE` is a constant.
