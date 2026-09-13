# Personal Dashcam (Android)

A minimal background dashcam app: records from the back camera via a
foreground service, so it keeps recording while you have Google Maps
(or anything else) full-screen on top.

## How it works
- `DashcamService` opens the camera and writes directly to a
  `MediaRecorder` surface — there's no on-screen preview, so it works
  headless while another app is in front.
- Recording is split into 5-minute clip files (`CLIP_DURATION_MS` in
  `DashcamService.kt`) saved to the app's own storage folder
  (`Android/data/com.personal.dashcam/files/DashcamClips/`).
- Clips are **not** auto-deleted — you manage them yourself from the
  app (tap a clip to play, long-press to delete), per what you asked for.
- A persistent notification shows while recording (Android requires
  this for any background camera use — it can't be hidden), with a
  Stop action.

## Build & install

### Option A — Android Studio on your computer
1. Install **Android Studio** (free, from developer.android.com).
2. Open this `DashcamApp` folder as a project (File → Open).
3. Let Gradle sync (it will download dependencies automatically —
   needs an internet connection the first time).
4. Connect your Android phone via USB with USB debugging enabled
   (Settings → About phone → tap "Build number" 7 times → Developer
   options → USB debugging), or build an APK via
   Build → Build Bundle(s)/APK(s) → Build APK(s) and sideload it.
5. Run the app. Grant Camera + Microphone (+ Notifications on
   Android 13+) when prompted.

### Option B — GitHub Actions (no Android Studio needed at all)
This repo includes `.github/workflows/build-apk.yml`, which builds the
APK automatically in the cloud every time you push code.

1. Create a new **GitHub repository** (private is fine, it's free).
2. Push this whole `DashcamApp` folder's contents to that repo
   (either via `git push`, or GitHub's web "Upload files" if you don't
   want to use git commands).
3. Go to the **Actions** tab on your repo — a "Build Dashcam APK"
   workflow run should start automatically (takes ~2-4 minutes).
4. Once it finishes (green checkmark), open that run and scroll to
   **Artifacts** — download `dashcam-debug-apk`. It's a zip containing
   `app-debug.apk`.
5. Transfer that `.apk` to your phone (email it to yourself, upload to
   Drive, whatever's easiest), then on the phone tap the file to
   install it. Android will ask you to allow "install from unknown
   sources" for whichever app you used to open it — allow it just for
   this install.
6. Open the app and grant permissions as usual.

This avoids installing Android Studio entirely, but you'll still need
a free GitHub account and a way to move the downloaded APK onto your
phone.


## Using it
1. Mount your phone, aim the camera how you want.
2. Open the Dashcam app, tap **Start Recording**.
3. Press home / open Google Maps full-screen — recording continues
   in the background. The notification bar confirms it's still going.
4. Tap **Stop Recording** (in the app or via the notification) when done.

## Important things to know
- **Battery optimization**: Android may still kill background
  services on some phones (Samsung/Xiaomi/OnePlus especially) to save
  battery. Go to Settings → Apps → Dashcam → Battery → set to
  "Unrestricted" / disable battery optimization for this app, or it
  may get killed after a while in the background.
- **Storage**: clips live in app-private storage, so they won't show
  up in your phone's Gallery app automatically. Use the in-app list to
  play or long-press-delete them, or browse via a file manager at
  `Android/data/com.personal.dashcam/files/DashcamClips/`.
- **Clip length / quality**: edit `CLIP_DURATION_MS` and the
  `CamcorderProfile` quality constant in `DashcamService.kt` to change
  clip length or resolution.
- **No GPS overlay yet** — not asked for, but easy to add later
  (record location alongside timestamps) if you want it.
- This is a first working version, not a polished production app —
  test it on your device and treat edge cases (phone restarts,
  storage full, etc.) as things to harden if you rely on it daily.
