# OCR Clip

Press a button while a game is running and OCR Clip reads the Japanese text on screen, copies it to the clipboard and, if you want, looks it up in a dictionary. It is built for Retroid handhelds (Pocket Classic and Pocket Nova), but works on any Android 11+ device.

It runs as an accessibility service: hotkey → screenshot → optional crop to a region → OCR → clipboard → dictionary.

## Features

- **Hotkey capture**: any button, Volume Up for example. While the hotkey is active, the game never sees that button.
- **Two OCR engines**:
  - **On-device (ML Kit Japanese)**: offline and private, but less accurate on pixel fonts.
  - **Google Lens**: much more accurate on PS1-era pixel fonts. It needs internet and sends the captured image to Google. Without a connection, captures are read on the device instead. It uses the same unofficial endpoint as Chrome's Lens overlay and [owocr](https://github.com/AuroraWright/owocr), so it may break without notice.
- **Capture region**: drag a rectangle over the last screenshot so only the text box is read.
- **After capture**:
  - **Just copy**: only copies the text.
  - **Dictionary overlay**: shows a WebView over the game. B, Back, the hotkey or tapping outside it closes it.
  - **Yomitan dictionaries (offline)**: looks the text up in your own [Yomitan](https://github.com/yomidevs/yomitan) dictionaries, over the game. Inflected words are found with Yomitan's deinflection rules (食べなかった → 食べる). Tap a character to look up the word starting there, or use D-pad right/left to move through the sentence. Cross-reference links in the entries (⇒ 見る) look that word up in place; B goes back. Under **Japanese font** you can load your own TTF/OTF/TTC fonts (UD Digi Kyokasho, Klee One…) for the kana and kanji; the app keeps a copy of each, so you pick between them without the original files. See [Offline Yomitan dictionaries](#offline-yomitan-dictionaries).
    - **Anki cards**: with [AnkiDroid](https://github.com/ankidroid/Anki-Android) installed, every definition gets a **+** button that adds a note with the word, that definition (each sense of a Jitendex or 新和英 definition has its own **+** too, to add only that sense), the captured sentence (the word in bold) and the screenshot. Set it up under **Lookup → Anki**: allow access, then pick the note type and the deck. Fields are filled by name the way Yomitan fills [Lapis](https://github.com/donkuri/lapis): Expression, ExpressionFurigana, ExpressionReading, MainDefinition, Glossary (all of the entry's definitions), Sentence, Picture, ExpressionAudio, PitchPosition, Frequency and FreqSort. Basic's Front/Back and names like Word, Reading or Meaning work too. Definitions keep Yomitan's markup and, with a database built since v0.4, each dictionary's styles, so they look like the cards Yomitan makes.
    - **Word audio**: with an audio database loaded, the note's ExpressionAudio field gets a recording of the word. See [Word audio for Anki cards](#word-audio-for-anki-cards).
- **Dictionary URL template**: `{text}` is replaced with the captured text, e.g. `https://jisho.hlorenzi.com/search/{text}` (the default). The overlay adds ` OCRClip/1` to its user agent, so a self-hosted jisho can switch to a compact layout.
- **Translate hotkey** (optional): a second button that reads the text the same way, then translates it with DeepL instead of opening the dictionary.
  - **With a DeepL API key** (the [free API plan](https://www.deepl.com/pro-api) covers 500,000 characters a month): a compact popup with the text and its translation appears at the top or bottom edge, whichever is away from the capture region. The game keeps the gamepad while it's open. B, Back, either hotkey or a tap closes it. The target language defaults to `EN-US`.
  - **Offline model** (Translate → Offline model): load a [CAT-Translate](https://huggingface.co/cyberagent/CAT-Translate-3.3b) GGUF, such as `CAT-Translate-3.3b` at Q4_K_M (2.1 GB), and the translation is made on the device instead, into English, streaming into the same popup. On the Retroid Pocket Nova that's about 1–2 s for a line and 4 s for a full text box; in testing a PS1 game in DuckStation kept a steady 60 fps meanwhile. Under **Translate with**, pick **DeepL** to use it only when DeepL fails or there's no connection, or **Offline model** to never call DeepL. It loads on the first translation and frees its memory (about 4 GB for the 3.3B) after 10 minutes without one. Needs an ARMv8.2 CPU with dot-product instructions, which every Retroid with Android 11 or later has.
  - **When neither can translate**: the DeepL web page opens over the game instead. The URL template defaults to `https://www.deepl.com/translator#ja/en/{text}`; change `en` to another language code (e.g. `de`), or point it at another translator.
- **Second screen** (Lookup → Second screen): with a second display attached, such as the Retroid Dual Screen Add-on on the Pocket Nova, the dictionary, the Yomitan lookup and translations show on it instead of over the game, and the game keeps the gamepad. Tap the page to pick words, scroll or add Anki cards; ‹ steps back after following a link, and ✕ (or a tap on the black area around a translation) clears the screen until the next capture. While the setting is on the add-on stays black instead of mirroring the game. Without a second display everything opens over the game as before. Brightness: the add-on's own side buttons; Android keeps it at full brightness.
- **Survives reboots**: Retroid firmware turns off third-party accessibility services on every boot. With a one-time adb grant, OCR Clip turns itself back on.

## Build

Requires JDK 17 and the Android SDK.

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Only arm64-v8a is included.

## Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Optional, lets the app re-enable its accessibility service after reboots:
adb shell pm grant com.local.ocrclip android.permission.WRITE_SECURE_SETTINGS
```

Open the app and turn on **OCR Clip hotkey** in accessibility settings, or let the app turn it on if you made the grant above. The settings are split into four sections on the left: **Capture** (hotkey, OCR engine, capture region), **Lookup** (what happens after a capture), **Translate** and **Service**. In **Capture**, tap **Lookup hotkey**, press the button you want, and choose the OCR engine; then pick the after-capture action in **Lookup**. For translations, tap **Translate hotkey** in **Translate**, press a different button, and paste your DeepL API key if you have one. The power button at the bottom of the left rail pauses both hotkeys. Typing a key on a handheld is fiddly; `adb shell input text '<key>'` with the key field focused works.

To use a capture region, press the hotkey in a game once, then use **Pick region** under **Capture** in the app to drag over that screenshot.

## Offline Yomitan dictionaries

Reading Yomitan dictionary zips takes a long time on a handheld, so you turn them into one database on a PC (Python 3, nothing else to install), and the device only copies and reads that file. Definitions are stored compressed, so even a large collection stays manageable: 15 dictionaries including Jitendex, 大辞泉 and four frequency lists come to about 710 MB, against over 3 GB uncompressed. Databases built before v0.3.1 must be rebuilt; the app says so if you load an old one.

1. Download the Yomitan dictionaries you want, for example JMdict, a frequency list (JPDB, BCCWJ…), a pitch accent dictionary (Kanjium) and KANJIDIC. Any number of term, frequency, pitch accent and kanji dictionaries can be combined.
2. Build the database. The order you list them in is the default display order:

   ```sh
   python tools/build_dict.py dict.db JMdict_english.zip JPDB_freq.zip Kanjium_pitch.zip KANJIDIC_english.zip
   ```

3. Copy `dict.db` to the device (USB, or `adb push dict.db /sdcard/Download/`).
4. In the app, open **Lookup**, choose **Yomitan, offline**, then press **Load…** and pick the file.

Three optional scripts rework dictionaries before building. `tools/strip_jitendex.py` removes Jitendex's example sentences and its JMdict | Tatoeba links, and `tools/strip_shinwaei.py` removes the `[ローマ字](romaji)` tags from 新和英. `tools/split_jmnedict.py` is for the JMnedict build that lists all of a name's readings as its definition: readings a frequency dictionary (BCCWJ) knows move to the front of the list, highlighted, and the most common one becomes the entry's reading, so the list shows under the name's normal headword with its frequency. Run them again on each new release of those dictionaries:

```sh
python tools/strip_jitendex.py jitendex-yomitan.zip jitendex-no-examples.zip
python tools/strip_shinwaei.py 新和英.zip 新和英-no-romaji.zip
python tools/split_jmnedict.py JMnedict.zip BCCWJ.zip JMnedict-common-readings.zip
```

The app lists the dictionaries in the database. Untick one to stop using it, or reorder them with ▲▼. These settings are kept by dictionary name, so rebuilding the database on the PC (to add a dictionary, say) keeps them. Images inside dictionaries aren't included; their description is shown instead. Databases from before v0.4 still work, but Anki notes made with them come without the dictionaries' own styles; rebuild to get them.

The deinflection rules in `shared/japanese-transforms.json` (used by both the Android and the desktop app) come from Yomitan. To update them:

```sh
git clone --depth 1 https://github.com/yomidevs/yomitan.git
python tools/export_yomitan_transforms.py yomitan shared/japanese-transforms.json
```

## Word audio for Anki cards

The recordings come from the Japanese collection of [Local Audio Server for Yomichan](https://github.com/yomidevs/local-audio-yomichan) (NHK, Shinmeikai, Forvo and JapanesePod101), in the single-file form that add-on makes for Android:

1. On a PC, install the add-on in Anki and put the collection in its `user_files` folder, as its README describes. The Opus collection is half the size of the MP3 one; AnkiDroid and desktop Anki play both, AnkiWeb only MP3.
2. In Anki, choose **Tools → Local Audio Server → Generate Android database**. This writes `android.db` (several GB) to `user_files`.
3. Copy `android.db` to the device and load it under **Lookup → Anki → Word audio**. The app keeps its own copy, so the file you copied over can be deleted afterwards.

When you tap **+**, the app looks for the word with the entry's reading, taking the first source that has it in the order NHK, Shinmeikai, JapanesePod101, then Forvo (whose recordings don't say which reading they are). A word without a recording still gets its note, and the page says there was no audio. The note type needs a field named ExpressionAudio (or Audio, WordAudio, VocabAudio, TermAudio).

## Desktop (Linux)

`desktop/` is OCR Clip for a Linux desktop (built for Omarchy/Hyprland). There's no hotkey: it watches your screenshots folder, and every new image is read and looked up. Take a screenshot in DuckStation (or with your desktop's screenshot tool), and a window opens with the text, the Yomitan lookup and the translation on top. The text goes to the clipboard too. It does the same as the Android app, with desktop parts:

- **OCR**: Google Lens. When Lens can't be reached, [meikiocr](https://github.com/rtr46/meikiocr) (trained on video game text) reads it on the PC, run from the owocr uv tool's Python: `uv tool install 'owocr[screenai]' --with meikiocr`.
- **Lookup**: the same page as the Android app (both load it from `shared/`), from the same `dict.db`. Click a character to look up from there; ←/→ step through the sentence; Esc steps back after following a link, then closes the window.
- **Translation**: DeepL with your API key, or offline with the CAT-Translate GGUF through llama.cpp's `llama-server` (`sudo pacman -S llama-cpp ggml-vulkan`, or `ggml-cuda`). It's started on the first translation and stopped after 10 minutes without one.
- **Anki**: desktop Anki with [AnkiConnect](https://ankiweb.net/shared/info/2055492159). Word audio comes from the Local Audio Server add-on in the same Anki, so no `android.db` is needed. Set `anki_model` (e.g. your Lapis note type) and optionally `anki_deck` and `anki_tags`. Two minutes after you add cards, OCR Clip has Anki sync with AnkiWeb, at most once every ten minutes, so new cards reach AnkiDroid without waiting for Anki to close (`anki_sync: false` turns that off). Anki has to be running for all of this; it can stay out of sight, e.g. autostarted on a hidden workspace.

Needs Python 3 with PyQt6 and Qt WebEngine (`sudo pacman -S python-pyqt6 python-pyqt6-webengine`), `inotify-tools` and `wl-clipboard`. Download `ocrclip-desktop-<version>.tar.gz` from a Linux release (tags `desktop-v…`; Android releases are `v…`), unpack it somewhere to keep, such as `~/.local/share/ocrclip-desktop`, and run `ocrclip-desktop` from there. From a checkout of this repo, run `desktop/ocrclip-desktop`. Settings live in `~/.config/ocrclip/settings.json` (written on first run): `watch_dir` (default `~/Pictures/Screenshots`), `dictionary` (path to `dict.db`), `deepl_key`, `translate_model` (default `~/.local/share/ocrclip/translate.gguf`), `region` (`[left, top, right, bottom]` as fractions), `crop_skip` and `skip` (file name globs: not cropped / ignored), `lookup_font` (a font name), and the rest named as in the Android app. The tray icon pauses it, picks the capture region over the last screenshot, and switches the OCR and translation engines. The same works from the command line, for a keybinding: `ocrclip-desktop toggle|pause|resume|show|hide|region|quit`. `ocrclip-desktop once <image>` reads one file.

To start it with the session, use the systemd user unit. From the unpacked release folder (in a checkout, use `desktop/ocrclip.service` and `$PWD/desktop`):

```sh
sed "s|@DIR@|$PWD|" ocrclip.service > ~/.config/systemd/user/ocrclip.service
systemctl --user daemon-reload && systemctl --user enable --now ocrclip
```

The window's class is `ocrclip`. On Hyprland, a window rule keeps it floating beside the game, for example:

```lua
o.window({ class = "^ocrclip$", title = "^OCR Clip$" }, {
  float = true, pin = true, size = { 960, 1600 }, move = { "(monitor_w-984)", "(monitor_h*0.04)" },
})
```

**Privacy:** with Google Lens selected, every new image in the watched folder is sent to Google while OCR Clip isn't paused, including screenshots that have nothing to do with a game. Pause it from the tray (or `ocrclip-desktop pause`), or keep files out with `skip`. It never reads the screen itself, only the files that appear in the folder.

## Privacy

The service only intercepts the hotkeys. It reads the screen only when you press one. The last screenshot stays in the app's cache, and only so the region picker has something to draw on. Nothing leaves the device unless you pick the Google Lens engine or a web dictionary, or use the translate hotkey with DeepL (which sends the captured text to DeepL; the offline model doesn't). The offline Yomitan lookup runs entirely on the device. Anki notes go to AnkiDroid on the device, only when you tap +; AnkiDroid's own sync takes them from there.

## License

GPL-3.0-or-later, see [LICENSE](LICENSE). The Japanese deinflection rules are from [Yomitan](https://github.com/yomidevs/yomitan), Copyright (C) 2024-2026 Yomitan Authors, also GPL-3.0-or-later.
