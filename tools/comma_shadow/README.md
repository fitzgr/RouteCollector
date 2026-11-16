# DriveBuddy → comma: shadow-only integration prototype

**Status: prototype only. No vehicle-control integration.** The Python listener is a development-machine simulator, **not** a service installed on stock openpilot. No files here issue commands to Toyota, comma, OBD, CAN, throttle or braking systems.

The DriveBuddy Android app is `fitzgr/RouteCollector`, package `com.grant.routecollector`. In its live tracking service, `TrackingState.currentPostedSpeed` provides the active limit, `TrackingState.currentSpeedIsCollected` distinguishes captured markers from OSM speed data, and `TrackingState.activeDriveId` identifies the drive. `speed` marks a zone beginning; `speed_advance` is a warning and must **not** be treated as a new active posted speed.

## What is included

- Versioned **advisory-only** JSON, `drivebuddy.speed_advisory.v1`.
- A Kotlin `DriveBuddyShadowClient` for a developer to connect to a debug-only, opt-in test flow later. **It is not called by the production tracking service yet.**
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

For Android → development laptop testing on a **trusted Wi-Fi LAN only**, bind the receiver to a reachable interface (`--host 0.0.0.0`) and configure the Android test client for `http://<laptop-lan-ip>:8765/v1/advisory`. Do **not** expose this unencrypted test service to the internet. Android API 28+ blocks plaintext traffic by default; use a TLS test endpoint or a debug-only network security policy, not a production-wide cleartext exception.

## Next milestones and gates

1. Add a **default-off debug setting** for endpoint, secret and shadow-mode enablement. Connect it only to changes in the active posted speed, preserving all existing markers and history.
2. Verify Android-to-laptop delivery, logs, duplicate suppression and timing with recorded routes.
3. Independently verify the exact Canadian 2024 RAV4 Trail fingerprint, comma hardware, supported longitudinal-control branch and safety-feature interactions using the official compatibility list and parked/bench testing. RAV4 2023–25 lists experimental longitudinal capability in non-release branches such as `nightly-dev`; this is **not** a supported Android speed-command API.
4. Any eventual openpilot control architecture needs a separate design, safety review, and controlled test process. The current code has no such path. Never test unvalidated autonomous acceleration or braking on public roads.

GPS/OSM road speeds may be wrong, stale or directionally mismatched. A speed advance sign is not a new effective limit. There is no evidence from this prototype that the **Toyota factory cruise set-speed** can be changed electronically; openpilot commanding longitudinal motion is a distinct capability.
