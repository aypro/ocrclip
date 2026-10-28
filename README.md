# OCR Clip

Press a button while a game is running and OCR Clip reads the Japanese text on screen, copies it to the clipboard and, if you want, looks it up in a dictionary. It is built for Retroid handhelds (Pocket Classic and Pocket Nova), but works on any Android 11+ device.

It runs as an accessibility service: hotkey → screenshot → optional crop to a region → OCR → clipboard → dictionary.

## Features

- **Hotkey capture**: any button, Volume Up for example. While the hotkey is active, the game never sees that button.
- **Two OCR engines**:
  - **On-device (ML Kit Japanese)**: offline and private, but less accurate on pixel fonts.
  - **Google Lens**: much more accurate on PS1-era pixel fonts. It needs internet and sends the captured image to Google. It uses the same unofficial endpoint as Chrome's Lens overlay and [owocr](https://github.com/AuroraWright/owocr), so it may break without notice.
- **Capture region**: drag a rectangle over the last screenshot so only the text box is read.
- **After capture**:
  - **Just copy**: only copies the text.
  - **Dictionary overlay**: shows a WebView over the game. B, Back, the hotkey or tapping outside it closes it.
  - **Yomitan dictionaries (offline)**: looks the text up in your own [Yomitan](https://github.com/yomidevs/yomitan) dictionaries, over the game. Inflected words are found with Yomitan's deinflection rules (食べなかった → 食べる). Tap a character to look up the word starting there, or use D-pad right/left to move through the sentence. See [Offline Yomitan dictionaries](#offline-yomitan-dictionaries).
  - **Browser**: opens the dictionary in your browser.
- **Dictionary URL template**: `{text}` is replaced with the captured text, e.g. `https://jisho.hlorenzi.com/search/{text}` (the default). The overlay adds ` OCRClip/1` to its user agent, so a self-hosted jisho can switch to a compact layout.
- **Translate hotkey** (optional): a second button that reads the text the same way, then translates it with DeepL instead of opening the dictionary.
  - **With a DeepL API key** (the [free API plan](https://www.deepl.com/pro-api) covers 500,000 characters a month): a compact popup with the text and its translation appears at the top or bottom edge, whichever is away from the capture region. The game keeps the gamepad while it's open. B, Back, either hotkey or a tap closes it. The target language defaults to `EN-US`.
  - **Without a key, or if the API call fails**: the DeepL web page opens over the game instead. The URL template defaults to `https://www.deepl.com/translator#ja/en/{text}`; change `en` to another language code (e.g. `de`), or point it at another translator.
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

Open the app and turn on **OCR Clip hotkey** in accessibility settings, or let the app turn it on if you made the grant above. Then press **Set hotkey** and choose the OCR engine and the after-capture action. For translations, also press **Set translate hotkey**, pick a different button, and paste your DeepL API key if you have one. Typing a key on a handheld is fiddly; `adb shell input text '<key>'` with the key field focused works.

To use a capture region, press the hotkey in a game once, then use **Pick region** in the app to drag over that screenshot.

## Offline Yomitan dictionaries

Reading Yomitan dictionary zips takes a long time on a handheld, so you turn them into one database on a PC (Python 3, nothing else to install), and the device only copies and reads that file.

1. Download the Yomitan dictionaries you want, for example JMdict, a frequency list (JPDB, BCCWJ…), a pitch accent dictionary (Kanjium) and KANJIDIC. Any number of term, frequency, pitch accent and kanji dictionaries can be combined.
2. Build the database. The order you list them in is the default display order:

   ```sh
   python tools/build_dict.py dict.db JMdict_english.zip JPDB_freq.zip Kanjium_pitch.zip KANJIDIC_english.zip
   ```

3. Copy `dict.db` to the device (USB, or `adb push dict.db /sdcard/Download/`).
4. In the app, press **Load dictionary database** and pick the file, then choose **Look up in your Yomitan dictionaries** under "After capture".

The app lists the dictionaries in the database. Untick one to stop using it, or reorder them with ▲▼. These settings are kept by dictionary name, so rebuilding the database on the PC (to add a dictionary, say) keeps them. Images inside dictionaries aren't included; their description is shown instead.

The deinflection rules in `app/src/main/assets/japanese-transforms.json` come from Yomitan. To update them:

```sh
git clone --depth 1 https://github.com/yomidevs/yomitan.git
python tools/export_yomitan_transforms.py yomitan app/src/main/assets/japanese-transforms.json
```

## Privacy

The service only intercepts the hotkeys. It reads the screen only when you press one. The last screenshot stays in the app's cache, and only so the region picker has something to draw on. Nothing leaves the device unless you pick the Google Lens engine or a web dictionary, or use the translate hotkey (which sends the captured text to DeepL). The offline Yomitan lookup runs entirely on the device.

## License

GPL-3.0-or-later, see [LICENSE](LICENSE). The Japanese deinflection rules are from [Yomitan](https://github.com/yomidevs/yomitan), Copyright (C) 2024-2026 Yomitan Authors, also GPL-3.0-or-later.
