# MirrorSync

An Android app + PC companion app that mirror the phone's screen, transfer files,
and sync the clipboard — all over the **same WiFi network** (no cloud, no internet
account required).

## How it works
- Both devices must be on the same WiFi network/router.
- The Android app opens three TCP servers; the PC companion connects to the
  phone's local IP address on those ports.

| Feature         | Port | Direction               |
|-----------------|------|--------------------------|
| Screen mirror   | 5001 | Phone -> PC (H264 stream)|
| File transfer   | 5002 | Both ways                |
| Clipboard sync  | 5003 | Both ways                |

## 1. Build & install the Android app
1. Open the `android/` folder in Android Studio (Hedgehog or newer).
2. Let Gradle sync, then Run on your phone (USB debugging or wireless debugging).
3. Grant the screen-capture and notification permissions when prompted.
4. On the phone app's home screen you'll see its WiFi IP address — you need this
   for the PC side.
5. Tap "Start Screen Mirror Server", "Start File Server", "Start Clipboard Sync"
   as needed (each runs as a persistent foreground service, shown as a notification).

Files you upload/download live in:
`/storage/emulated/0/Android/data/com.mirrorsync.app/files/`
(You can browse this with any file manager app, or transfer directly through
the PC companion's File Transfer tab.)

## 2. Run the PC companion
Requires Python 3.9+, and **ffmpeg** installed (for `ffplay`, used to view the
mirrored screen) — install via your OS package manager (e.g. `winget install ffmpeg`,
`brew install ffmpeg`, or `apt install ffmpeg`).

```bash
cd pc_companion
pip install -r requirements.txt
python main.py
```

Enter the phone's IP address shown in the Android app, then use the three tabs:
- **Screen Mirror** — launches `ffplay` pointed at the phone's video stream.
- **File Transfer** — list/download/upload files in the phone's shared folder.
- **Clipboard Sync** — two-way clipboard sync while both apps are running.

## Notes & limitations
- This is a from-scratch scaffold, not a packaged/signed release — you build
  the APK yourself in Android Studio.
- Screen mirroring is **view-only** (no remote control/tap injection) in this
  version. Adding touch injection requires either root, an Accessibility
  Service simulating gestures, or ADB's `input` command over a USB/wireless
  ADB connection — let me know if you want that added.
- On Android 10+, background apps can't read the clipboard, so phone -> PC
  clipboard sync only works while the app is in the foreground. PC -> phone
  works regardless.
- No authentication/encryption is implemented — only use this on a trusted
  home WiFi network, not public WiFi.
- File transfer is limited to the app's own storage folder, to avoid needing
  broad "manage all files" permission from Android.
