# Chatso

Chatso is an Android messenger for private, end-to-end encrypted conversations directly between devices. It has no central messaging server and does not require an account.

## How it works

Connect with someone on the same Wi-Fi/LAN or VPN, or pair by QR code or network address. If the devices are on separate networks, a Chatso device can act as a bridge; message contents remain end-to-end encrypted.

You can exchange text, photos, voice messages, files, and stickers. Compare Safety Numbers with your contact to verify their identity.

**Network requirement:** peers need to be reachable on the same local network or VPN, or through a configured bridge. Chatso does not provide cloud relay.

## Install

Download the latest APK from [GitHub Releases](https://github.com/guritso/chatso/releases/latest).

## Build and test

Requirements: JDK 21, Android SDK (API 36), and Gradle 9.3.1.

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

## License

[MIT](LICENSE)
