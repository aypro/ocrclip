# AGENTS.md

Notes for coding agents working on OCR Clip. See README.md for what the app does.

## Layout

Single module, `app/`, package `com.local.ocrclip`. Plain Activities and Views, no Compose, no AndroidX, no XML other than one layout. Keep it that way unless there is a real need.

| File | Role |
|---|---|
| `OcrService.kt` | Accessibility service: key filter for the hotkey, `takeScreenshot`, crop, OCR dispatch, clipboard, dictionary. Also the `com.local.ocrclip.CAPTURE` test broadcast. |
| `LensOcr.kt` | Google Lens OCR via `lensfrontend-pa.googleapis.com/v1/crupload` (owocr's "glens" request). The protobuf is hand-encoded and decoded, with no protobuf dependency. Field numbers come from Chromium's `lens_overlay_*.proto`. |
| `DictionaryOverlay.kt` | `TYPE_ACCESSIBILITY_OVERLAY` WebView over the game. While it's open it takes key focus. |
| `Prefs.kt` | Everything in SharedPreferences file `settings`. Keys: `hotkey`, `enabled`, `engine` (`local`/`lens`), `keep_newlines`, `copy_to_clipboard`, `show_toast`, `dictionary_mode` (`off`/`overlay`/`browser`), `dictionary_url`, `last_result`, `region_l/t/r/b`. |
| `MainActivity.kt` + `res/layout/activity_main.xml` | Settings screen. It also binds the hotkey, through `OcrService.bindingHotkey`. |
| `RegionActivity.kt` | Drag a capture region over the last screenshot, stored as fractions of the screen. |
| `ServiceEnabler.kt` | Re-adds the service to `enabled_accessibility_services` using `WRITE_SECURE_SETTINGS`. `BootReceiver` runs on boot or update and re-checks at 30 s, 2 min and 5 min, because Retroid firmware resets the list on boot. |

## Build

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug
```

AGP 9.x, Gradle 9.x, compileSdk/targetSdk 36, minSdk 30 (needed for `AccessibilityService.takeScreenshot`), arm64-v8a only. Output: `app/build/outputs/apk/debug/app-debug.apk`. There are no tests; verify on a device.

## Testing on a device

- `adb shell input keyevent` **bypasses** accessibility key filters, so it can't trigger the hotkey. Use `adb shell am broadcast -a com.local.ocrclip.CAPTURE` instead. The receiver is DUMP-protected: adb shell can send it, normal apps can't.
- It's a debug build, so `run-as` works. Read the result with `adb shell run-as com.local.ocrclip cat shared_prefs/settings.xml` (`last_result`).
- To preload settings, force-stop the app, then copy a prefs XML in with `run-as com.local.ocrclip sh -c "mkdir -p shared_prefs && cp /data/local/tmp/x.xml shared_prefs/settings.xml"`.
- Don't toggle the accessibility service on someone's device without asking. Opening the app re-enables it through `ServiceEnabler`.
- Retroid devices kill background processes in standby. The app can end up force-stopped, so `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` may not arrive. Opening the app fixes it.

## Conventions

- Kotlin, 4-space indent, ~120-column lines. Comments are sparse and explain *why*, not what.
- Text is Japanese. `OcrService.format` joins lines without separators and removes stray spaces between CJK characters.
- UI strings are inline in code or the layout, not in `strings.xml`, except the app and service labels.
- The service must never read or store screen content except when the hotkey is pressed. Keep `service_description` in `strings.xml` accurate if that changes.
