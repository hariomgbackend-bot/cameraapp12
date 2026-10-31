# Gallery Camera

Registers as a handler for the standard `android.media.action.IMAGE_CAPTURE` intent. When an app
asks the system for a camera photo and you choose Gallery Camera, you pick an existing image
(Photo Picker or Files) and it is returned as if it had been captured.

**Status: source only. This project has NOT been compiled or run on a device yet.**
It was written without an Android SDK or network access, so no APK exists. Build it
in Android Studio (below) and treat the first build as untested: you may need to fix minor
compile or Gradle-version issues.

## Build on Windows
1. Install the latest Android Studio. In SDK Manager, install **Android 16 (API 36)** platform and build tools.
2. File > Open > select the `GalleryCamera` folder. Let Gradle sync (it downloads Gradle 8.13 itself,
   because the `gradlew` wrapper jar is not included).
   If Studio complains about the AGP/Kotlin versions, use its "Upgrade" suggestion.
3. Build > Build Bundle(s) / APK(s) > **Build APK(s)**.
4. Output: `app\build\outputs\apk\debug\app-debug.apk`.

## Install
- USB: enable Developer options and USB debugging on the Fold6, connect, press Run in Studio, or
  `adb install -r app-debug.apk`.
- Sideloading the APK file by hand: One UI's **Auto Blocker** (Settings > Security and privacy) blocks
  unknown-source installs. Turn it off temporarily, or install via USB/adb.

## Behaviour
| Requester sends | We return |
|---|---|
| `EXTRA_OUTPUT` (content URI) | Image written to that URI; `RESULT_OK` with `data` = same URI. No thumbnail (matches the platform camera). |
| No `EXTRA_OUTPUT` | `RESULT_OK` with `"data"` Bitmap thumbnail (max 320 px, EXIF-rotated) **and** `data` = content URI of the full-size image (read grant). |
| Back / Cancel | `RESULT_CANCELED` |

- Bytes are copied unmodified (original quality, size and EXIF). The only exception: if the destination
  is declared `image/jpeg` and the picked file is PNG/WebP/HEIC etc., it is converted to JPEG q100 so the file matches its type.
- No permissions in the manifest. Photo Picker with automatic fallback to the Storage Access Framework.
- The picked image is copied to app cache immediately, so it survives rotation, fold/unfold and process death.
- Output URIs pointing at this app's own FileProvider (other than the self-test folder) are rejected.
- Errors shown: unreadable/non-image file, no space, write denied, file gone, image too large.

## Testing guide
1. Open the app, tap **Test: save to file**. In the chooser, pick Gallery Camera (and once without it, to compare with the stock camera). Select a photo, **Use this photo**. Expect "RESULT_OK. EXTRA_OUTPUT file: W×H" matching your original.
2. **Test: thumbnail only**: expect a thumbnail size and "Full image URI readable".
3. Cancel test: start either test, press Cancel or Back. Expect "Cancelled".
4. Permission test: in step 1 the write works only because the caller granted a URI permission. A caller that grants nothing will produce "didn't allow writing".
5. Lifecycle: while the picker is open or after choosing, fold/unfold the phone or rotate. The selection should remain.
6. Real app: use an app with a "Take photo" action that uses the system camera intent (e.g. a contact photo or attachment flow). Behaviour varies by app; many apps use their own camera.

## Limitations (please read)
- Only works for apps that call `ACTION_IMAGE_CAPTURE`. Apps using CameraX, Camera2 or an embedded camera UI never ask the system and cannot be redirected without root/hooking.
- `ACTION_IMAGE_CAPTURE_SECURE` is not registered: it's the lock-screen camera path, launched by the system to the system camera; third-party apps are not meant to handle it.
- **Default handler on Android 16 / One UI: not verified.** Android has no "default camera" role. If several apps handle the intent, the system shows a chooser, and choosing "Always" may store a preference. Samsung Camera may already be stored as the preferred handler; clear it under Settings > Apps > Camera > Set as default > Clear defaults, then the chooser should list Gallery Camera. If a requesting app targets Samsung Camera directly by package, you cannot override that.
- Apps may detect or reject images that aren't fresh captures (old timestamps, no capture metadata, liveness/KYC checks, Play Integrity). Use only where that's acceptable.
- EXIF in the returned image comes from the original photo (including its date and possibly location).
- Android 16 edge-to-edge and large-screen resizing rules are handled (insets, max content width), but untested on hardware.
