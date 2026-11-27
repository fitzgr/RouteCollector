# DriveBuddy → comma: shadow-only integration prototype

**Status: prototype only. No vehicle-control integration.** The Python listener is a development-machine simulator, **not** a service installed on stock openpilot. No files here issue commands to Toyota, comma, OBD, CAN, throttle or braking systems.

The DriveBuddy Android app is `fitzgr/RouteCollector`, package `com.grant.routecollector`. In its live tracking service, `TrackingState.currentPostedSpeed` provides the active limit, `TrackingState.currentSpeedIsCollected` distinguishes captured markers from OSM speed data, and `TrackingState.activeDriveId` identifies the drive. `speed` marks a zone beginning; `speed_advance` is a warning and must **not** be treated as a new active posted speed.

## What is included

- Versioned **advisory-only** JSON, `drivebuddy.speed_advisory.v1`.
- A Kotlin `DriveBuddyShadowClient`, connected through a **debug-only, default-off Settings toggle** to the existing DriveBuddy active posted-speed state. Release builds cannot run this bridge.
- A token-protected Python HTTP test receiver with input validation and JSONL logs. Every acknowledgement has `appliedToVehicle=false`.
- A sample producer and validation tests.

## Protocol

POST `/v1/advisory` with HTTP header `X-DriveBuddy-Token: <shared-secret>` and JSON:

```json
{
  "schema": "drivebuddy.speed_advisory.v1",
  "mode": "shadow_only",
  "timestampMs": 1794864000000,
  "targetSpeedKph": 60,
  "source": "collected",
  "driveId": "12345"
}
```

`timestampMs` is UTC Unix milliseconds. A live sender uses `System.currentTimeMillis()`; the receiver rejects requests more than 10 seconds from its clock. Speed is integer km/h. `source` is `collected`, `osm`, or `manual_test` (the last only for sample sender).

## Run locally (no comma required)

```bash
export DRIVEBUDDY_SHADOW_TOKEN='choose-a-long-random-value'
python3 receiver.py --host 127.0.0.1 --port 8765
# From another terminal, with the same token:
python3 send_sample.py
python3 -m unittest discover -s tests -v
```

For Android → development laptop testing on a **trusted Wi-Fi LAN only**, bind the receiver to a reachable interface (`--host 0.0.0.0`). In the **debug build** of DriveBuddy, open **Settings → Comma bridge — SHADOW TEST ONLY**, enter `http://<laptop-lan-ip>:8765/v1/advisory` and the same token as on the laptop. Then use **Test connection (no car control)**; the receiver should log `source=manual_test` and `appliedToVehicle=false`. If it succeeds, optionally enable **Send live shadow advisories** while using DriveBuddy normally. The listener only logs effective collected/OSM speed changes; an advance sign does not change the target.

**Security:** Do **not** expose this unencrypted test service to the internet. The app's `app/src/debug/AndroidManifest.xml` permits cleartext only in debug builds; release builds retain Android defaults. The shared secret is saved in app preferences; treat it as a throwaway test secret, not a reusable password. On a shared or untrusted network, instead use a properly authenticated HTTPS test endpoint.

## Next milestones and gates

1. **Implemented:** default-off debug settings, live posted-speed observation, and a manual connection test. No production service or comma installation changes.
2. **Not yet verified on hardware:** Android-to-laptop delivery, logs, duplicate suppression and timing with recorded routes. Also validate Kotlin compilation with the PR-only Android build.
3. Independently verify the exact Canadian 2024 RAV4 Trail fingerprint, comma hardware, supported longitudinal-control branch and safety-feature interactions using the official compatibility list and parked/bench testing. RAV4 2023–25 lists experimental longitudinal capability in non-release branches such as `nightly-dev`; this is **not** a supported Android speed-command API.
4. Any eventual openpilot control architecture needs a separate design, safety review, and controlled test process. The current code has no such path. Never test unvalidated autonomous acceleration or braking on public roads.

GPS/OSM road speeds may be wrong, stale or directionally mismatched. A speed advance sign is not a new effective limit. There is no evidence from this prototype that the **Toyota factory cruise set-speed** can be changed electronically; openpilot commanding longitudinal motion is a distinct capability.
