# Drive Buddy paired releases (proposal)

The Android APK and Comma bench-only fork are separate installable artifacts, with independent versioning and rollback. Android changes do not require reinstalling Comma software.

## Android
- Build from main, verify the existing APK signing certificate, publish a version-specific APK and verify its SHA-256 before switching the latest-debug update manifest.
- Keep the previous version-specific APK available. Android downgrade may require a new build with a higher versionCode; preserve route data before any uninstall.
- Phone: Settings > Update; no desktop required.

## Comma 4
- Current bench baseline: test/dbsp at 6c8e4f5. Persistent startup candidate: fitzgr/openpilot#2 (draft, not approved for driving).
- Keep development separate from a future approved bench OTA branch. Publish only tested, pinned commits; record upstream base, full SHA, API schema compatibility, CI evidence and rollback release.
- Comma's native software updater checks for internet-delivered updates and prompts for a reboot. Verify the actual installed remote and branch and OTA behavior on independently powered, disconnected hardware before using a fixed branch. No remote flashing, arbitrary SSH execution, or automatic installation from the Android app.
- Proposed phone UX: display installed/available versions and release notes, with explicit status and instructions for the device's native updater. Do not claim version discovery or installation until implemented and tested.
- Receiver stays disabled by default. No vehicle control; no cruise changes; do not deploy the experimental fork for driving. Restore known-good official software before driving.

## Outstanding acceptance tests
- APK publication and upgrade with existing user data; verify CI and artifact SHA.
- Safe authenticated endpoint discovery without transmitting the token to unverified devices.
- Read-only compatibility/status reporting, including protocol versions.
- Comma OTA on isolated hardware, no unexpected receiver startup, no loss of configuration, rollback.
- Physical sensor verification and screen rendering using controlled validation; no reliance on experimental telemetry for driving decisions.
- Measure network usage before changing upload behavior.

Reference: https://comma.ai/support (software updates), https://docs.comma.ai/how-to/turn-the-speed-blue/ (custom fork setup).
