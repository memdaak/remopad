# Changelog

## 0.5.0 — 2026-11-26

- Added a collapsible Page Up / Page Down panel on the Android touchpad. Its visibility is remembered across launches.
- Two-finger vertical swipes now send whole mouse wheel ticks over Bluetooth HID or Wi-Fi. The scroll sensitivity slider adjusts the travel per tick. Two-finger horizontal scrolling, right-click and immediate three-finger dragging remain available.
- Added reliable wheel ticks and extended navigation keys to the Windows Wi-Fi receiver. Upgrade both ends for Wi-Fi or just Android for Bluetooth.

## 0.4.0 — 2026-08-14

### Security

- Added a PSK-authenticated ephemeral P-256 ECDH handshake for every Wi-Fi connection and reconnect.
- Derived independent AES-256-CBC and HMAC-SHA256 session keys, providing forward secrecy for completed sessions.
- Migrated the Windows pairing key from plaintext `pairing-key.txt` to DPAPI `CurrentUser` protected `pairing-key.dat`.
- Added `-ResetPairingKey` for deliberate key rotation.

### Windows

- The receiver now requests administrator elevation through the standard Windows UAC flow by default.
- Added `-NoElevate` for debugging or environments that intentionally run at normal integrity.
- Kept Windows secure desktops outside the injection boundary; the receiver does not bypass UAC consent or login screens.

### Input

- Fixed loss of high-frequency relative move and scroll deltas when the UDP sender thread was busy.
- Added bounded velocity-based two-finger scroll acceleration for custom scroll containers while retaining slow-gesture precision.

### Compatibility and quality

- Wi-Fi protocol v3 requires both the Android 0.4 client and Windows 0.4 receiver.
- Bluetooth HID remains independent of the Wi-Fi protocol.
- Fixed Android Bluetooth permission guarding and API-level lint findings.

## 0.3.2 — 2026-08-07

- Added experimental Android Bluetooth HID mouse and keyboard support with Windows host selection.
- Added encrypted Wi-Fi protocol v2, reliable clicks and keys, automatic discovery/reconnect, and latency statistics.
- Added three-finger drag, orientation switching, dark UI, and a virtual keyboard.
