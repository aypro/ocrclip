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
  - **Browser**: opens the dictionary in your browser.
- **Dictionary URL template**: `{text}` is replaced with the captured text, e.g. `https://jisho.hlorenzi.com/search/{text}` (the default). The overlay adds ` OCRClip/1` to its user agent, so a self-hosted jisho can switch to a compact layout.
- **Translate hotkey** (optional): a second button that reads the text the same way, then shows DeepL over the game instead of the dictionary. B, Back, either hotkey or tapping outside it closes it. The URL template defaults to `https://www.deepl.com/translator#ja/en/{text}`; change `en` to another language code (e.g. `de`), or point it at another translator.
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

Open the app and turn on **OCR Clip hotkey** in accessibility settings, or let the app turn it on if you made the grant above. Then press **Set hotkey** and choose the OCR engine and the after-capture action. For translations, also press **Set translate hotkey** and pick a different button.

To use a capture region, press the hotkey in a game once, then use **Pick region** in the app to drag over that screenshot.

## Privacy

The service only intercepts the hotkeys. It reads the screen only when you press one. The last screenshot stays in the app's cache, and only so the region picker has something to draw on. Nothing leaves the device unless you pick the Google Lens engine or a web dictionary, or use the translate hotkey (which sends the captured text to DeepL).
