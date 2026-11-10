# FortisMobile App Backlog & Enhancements

This file tracks upcoming features, UI overhauls, and specific tweaks requested for future updates.

## 1. Advanced Media Player UI
- **Now Playing Data:** Fetch and display the currently playing media title, artist, and album.
- **Source Indicator:** Show *where* the media is playing (e.g., YouTube, Spotify, VLC).
- **Live Progress Bar:** Display a dynamic timeline showing how much of the track has finished and how much is left.

## 2. Dynamic Volume Feedback
- When the Volume Up (`Vol +5%`) or Volume Down (`Vol -5%`) buttons are pressed, intercept the laptop's response and dynamically display the *exact current volume percentage* in the app UI (e.g., via a Toast or updating a status text).

## 3. Critical Action Confirmations
- **Shutdown Confirmation:** Add an "Are you sure?" popup dialog before firing the Shutdown intent to prevent accidentally turning off the laptop.
- **Force Backup Confirmation:** Add a similar popup dialog for the "Force Laptop to Backup Now" button to prevent duplicate backup sessions from accidental double-clicks.

## 4. Complete UI Overhaul
- Redesign the `ActionsScreen` and `DashboardScreen` for a more premium, native Android feel. (e.g., Card-based layouts, better spacing, dynamic theming).

## 5. Android Home Screen Widget
- Build an Android `AppWidgetProvider` for the home screen.
- Add quick-action buttons (Force Backup, Lock Laptop) that fire background intents without opening the FortisMobile app UI.

## 6. Auto-Sync Camera Roll
- Implement Android's `WorkManager` for daily/weekly background syncing.
- Scan the phone's gallery (`content://media/external/images/media`) and keep a lightweight database of sent images.
- Stream only *new* images over Tailscale using the optimized streaming logic.

## 7. Two-Way File Browsing
- **Laptop Side:** Add new Go Agent endpoints (`/api/v1/files/list`, `/api/v1/files/download`) to expose `~/Downloads` and `~/Documents`.
- **Android Side:** Build a "File Explorer" UI screen to browse the laptop's directories and download files directly to the phone.

## 8. Terminal Execution
- Build an interface in the app to type custom shell commands and stream the stdout/stderr back from the Go Agent.

## 9. Notification Sync
- Mirror laptop notifications (using `dbus-monitor`) to the phone, and/or mirror Android notifications (using `NotificationListenerService`) to the laptop via Tailscale.

## 10. Mobile Camera as PC Webcam
- Stream the Android camera feed over Tailscale to the laptop and pipe it into `v4l2loopback` so the laptop recognizes the phone as a native webcam (perfect for Zoom/Google Meet).

## 11. Wake-on-LAN (WOL)
- Send a Magic Packet from the phone to wake the laptop from Suspend/Sleep state.
