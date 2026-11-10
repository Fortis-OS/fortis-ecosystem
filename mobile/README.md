# mobile — Android app + Termux backup CLI

| Folder | What it is |
|---|---|
| [android/](./android) | The Fortis Android app (Kotlin, Jetpack Compose), package `com.fortis.mobile`, Android 9+ |
| [cli/](./cli) | `fortis-mobile`, a Bash tool for Termux that the laptop's backup uses to push an off-site copy to the phone |
| [scripts/build-rust.sh](./scripts/build-rust.sh) | Builds `fortis-bindings` for the phone and regenerates the Kotlin bindings |

Spec: [security §6](../docs/security/README.md) (phone), [architecture §5](../docs/architecture/README.md) (data flows), [API](../docs/api/README.md).

## What the app does

- **Vault**: its own encrypted copy of the laptop vault (synced with the one *vault laptop*), unlocked with your fingerprint; TOTP authenticator; password generator.
- **Autofill**: Android autofill service — fills logins in apps and browsers, offers to save new ones (security §6.5).
- **Sudo approvals**: a foreground service holds an outgoing, signed WebSocket to each paired laptop; `sudo` requests appear full-screen and are approved or denied with your fingerprint (security §4).
- **Laptop remote**: telemetry, power/media/volume actions, clipboard, files, home-screen widget; remote keyboard/mouse over the network (fingerprint session) or Bluetooth; phone camera as the laptop's webcam.
- **Phone → laptop**: notification mirroring (per-app allow-list) and camera-roll sync, both off by default.

The phone talks to laptops only over Tailscale. Every request is signed with a per-pairing P-256 key in the Android Keystore; there is no API key or shared secret on the phone, and no remote shell.

## Build

```bash
./scripts/build-rust.sh            # needs cargo-ndk and the Android NDK
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Tests: `./gradlew testDebugUnitTest` (JVM) and `./gradlew connectedDebugAndroidTest` (emulator; interop with fortis-core fixtures). Details: [docs/development §5](../docs/development/README.md).
