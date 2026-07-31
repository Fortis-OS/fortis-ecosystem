# FortisMobile V3: The Ultimate Ecosystem

This is the architectural roadmap to implement the four massive features you requested for FortisMobile V3. Because this is a huge undertaking, we will break it into manageable sprints.

## Source of Truth Architecture
Before we begin, let's solidify where code lives:
1. **The Android App:** `/home/ganeshak11/AndroidStudioProjects/FortisMobile`
2. **The Laptop Agent (Go):** `/home/ganeshak11/dev/Fortis/Fortis-Tools/FortisMobile/agent`
3. **The Termux Scripts:** `/home/ganeshak11/dev/Fortis/Fortis-Tools/FortisMobile/cli`

## The V3 Roadmap

### Sprint 1: The Remote Control Expansion
We will add new endpoints to the Go Agent (`handlers.go`) to execute system commands, and add the corresponding UI buttons to the Android app.
- **Media Controls:** Emulate media keys (`playerctl` on Linux) to Play/Pause, Next Track, and adjust Volume.
- **Power Controls:** `systemctl suspend` and `shutdown` commands.
- **Custom Scripts:** A way to trigger any script on your laptop.

### Sprint 2: The Android Home Screen Widget
We will build an Android `AppWidgetProvider`.
- It will live directly on your phone's home screen.
- It will have quick-action buttons (Force Backup, Lock Laptop).
- Tapping a button will fire a background intent to trigger the action without ever opening the FortisMobile app UI.

### Sprint 3: Auto-Sync Camera Roll
This requires a background worker on Android.
- We will implement Android's `WorkManager` to run a daily or weekly background job.
- It will scan `content://media/external/images/media` (your gallery).
- It will keep a lightweight database (`SharedPreferences` or Room) of which images were already sent.
- It will stream only the *new* images over Tailscale using the exact same highly-optimized infinite-limit streaming logic we just built.

### Sprint 4: Two-Way File Browsing
We will build a completely new UI screen in FortisMobile.
- **Laptop Side:** The Go Agent gets a new endpoint (`/api/v1/files/list`) that returns a JSON tree of files in your `~/Downloads` and `~/Documents`. It also gets a `/api/v1/files/download` endpoint.
- **Android Side:** A "File Explorer" screen that parses the JSON and displays folders/files. Tapping a file triggers a download to the Android `Downloads` folder.

## User Review Required
Since this is a massive expansion, I recommend we tackle **Sprint 1 (Remote Control)** and **Sprint 2 (Home Screen Widget)** first, as they build perfectly on our current UI. 

Once those are flawless, we will move into the complex file-syncing in Sprints 3 & 4. 

Does this roadmap sound good to you? If so, click **Proceed** and we'll start Sprint 1!
