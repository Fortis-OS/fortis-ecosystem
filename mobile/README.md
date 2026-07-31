# fortis-mobile

> A unified Android application built with Jetpack Compose. It serves as the biometric authenticator, vault browser, and TOTP code generator for the Fortis Ecosystem.

## Overview

`fortis-mobile` is the companion Android app that connects to the `fortis-agent` daemon over gRPC (via Tailscale VPN). Its key roles:

- **Biometric Authentication** — Approve sudo and browser unlock requests by scanning your fingerprint
- **Vault Browser** — View and manage passwords, cards, identities, and secure notes
- **TOTP Authenticator** — Generate time-based one-time passwords for 2FA
- **Password Generator** — Create strong random passwords on the go
- **Device Pairing** — Securely pair with your Linux machine using a 6-digit code

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Android Studio | Latest (Ladybug+) |
| Kotlin | 2.0+ |
| Min SDK | 26 (Android 8.0) |
| Tailscale | Installed on both devices |

## Build & Install

```bash
cd fortis-mobile/android

# Open in Android Studio
# Or build from CLI:
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Pairing with fortis-agent

1. On your Linux machine, run:
   ```bash
   fortis-cli pair
   # Output: "Pairing Code: 482916"
   ```
2. Open `fortis-mobile` on your phone
3. Go to **Settings → Pair Device**
4. Enter the agent's Tailscale IP address (e.g., `100.x.y.z`)
5. Enter the 6-digit code
6. Press **Pair** — the app sends its Ed25519 public key to the agent
7. Done! Your device is now paired for biometric authentication

## How Biometric Auth Works

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│ sudo / lock   │────▶│ fortis-agent │────▶│ fortis-mobile │
│ (PAM/Browser) │     │ (gRPC push)  │     │ (fingerprint) │
│               │◀────│              │◀────│ (Ed25519 sig) │
│ ✅ Authorized │     └──────────────┘     └──────────────┘
```

1. A sudo command or browser lock triggers a challenge on `fortis-agent`
2. The agent sends the challenge via gRPC to the paired mobile device
3. The phone prompts the user for biometric verification
4. On success, the phone signs the challenge with its Ed25519 private key (stored in Android Hardware Keystore)
5. The signature is sent back to the agent, which verifies it against the stored public key
6. The operation is approved

## App Screens

| Screen | Description |
|--------|-------------|
| `VaultScreen` | Browse and search vault entries |
| `AuthenticatorScreen` | TOTP codes with countdown timers |
| `PasswordGeneratorScreen` | Generate secure passwords with customizable rules |
| `PairDeviceScreen` | Manual IP + code pairing flow |

## Project Structure

```
fortis-mobile/
├── android/
│   ├── app/
│   │   └── src/main/
│   │       ├── java/com/example/fortismobile/
│   │       │   ├── MainActivity.kt
│   │       │   ├── encrypt/
│   │       │   │   ├── VaultScreen.kt
│   │       │   │   ├── AuthenticatorScreen.kt
│   │       │   │   ├── PasswordGeneratorScreen.kt
│   │       │   │   └── PairDeviceScreen.kt
│   │       │   ├── service/
│   │       │   │   └── FortisAuthService.kt    # Background gRPC listener
│   │       │   └── crypto/
│   │       │       └── FortisKeyManager.kt     # Android Keystore
│   │       └── res/
│   └── build.gradle.kts
├── docs/
└── README.md
```

## Security

- Private keys are generated and stored exclusively in the **Android Hardware Keystore** — they never leave the secure enclave. (Uses standard Keystore algorithms, typically ECDSA P-256 by default).
- Biometric verification uses Android's `BiometricPrompt` API with `BIOMETRIC_STRONG` classifier
- All gRPC communication is encrypted over the Tailscale WireGuard VPN tunnel
