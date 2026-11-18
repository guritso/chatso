# Chatso

End-to-end encrypted peer-to-peer Android messenger for local networks, VPNs, and localhost over direct TCP sockets with zero central servers.

## Features

- **Serverless P2P Messaging**: Direct encrypted TCP sockets over Wi-Fi/LAN, VPN tunnels (WireGuard, Tailscale, OpenVPN), or localhost (`127.0.0.1`).
- **Local Public-Key Identity**: ECDSA P-256 identity generated on-device and wrapped with Android Keystore. Peers are identified by the SHA-256 hash of their public key.
- **End-to-End Encryption**: Ephemeral ECDH P-256 key exchange signed with identity keys, HKDF-SHA256 transcript-bound key derivation, and full-duplex AES-256-GCM frames with monotonic anti-replay nonces.
- **Trust & Key Verification**: 60-digit Safety Numbers and Trust-On-First-Use (TOFU) alerts if a peer's public key changes.
- **Network Discovery & Invisible Mode**: Discover peers via mDNS/NSD, UDP broadcast, and concurrent subnet probing, or enable Invisible Mode to silence all discovery beacons and probes while still allowing direct QR or IP connections.
- **QR Code Pairing**: Encrypted `chatso://connect` QR invites with rotating invite tokens and a strict unknown-peer blocking toggle.
- **Rich Media & Self-Destruct**: Send chunked encrypted photos, `.m4a` voice messages, and arbitrary files, with optional per-chat self-destruct timers (`30s`, `5m`, `1h`).
- **Profile Image Cropper**: Interactive pan-and-pinch cropper before saving profile avatars.
- **Background Connectivity**: Foreground service (`ChatsoConnectionService`) keeps listening sockets alive in the background with an in-app battery optimization exemption prompt.
- **Localization & Themes**: Automatic Light/Dark themes and support for English, Portuguese, Spanish, French, and German.

## Requirements

- Android Studio or Android SDK (API 36, minSdk 24)
- JDK 17+
- Gradle 9.3.1

## Build & Test

Build a debug APK:

```bash
gradle :app:assembleDebug
```

Run the unit and Robolectric test suite:

```bash
gradle :app:testDebugUnitTest
```

Build a release APK (reads `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` environment variables):

```bash
gradle :app:assembleRelease
```

## GitHub Releases Workflow

The workflow at `.github/workflows/release.yml` runs on version tags (`v*`) or manual `workflow_dispatch`:

1. Runs `:app:testDebugUnitTest`.
2. Builds `:app:assembleRelease` when `KEYSTORE_BASE64`, `STORE_PASSWORD`, and `KEY_PASSWORD` repository secrets are configured; otherwise builds `:app:assembleDebug`.
3. Creates a GitHub Release (or updates the matching tag release) and attaches the built APK.
