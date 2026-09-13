# Route Collector — Android MVP

Route Collector records verified road facts on repeat routes so they can later drive route-aware notifications and, eventually, feed a Comma/OpenPilot integration.

## Current driving workflow

1. Open Route Collector and tap **Start drive**.
2. Tap **Open Google Maps + overlay**.
3. Navigate in Google Maps normally while Route Collector records GPS in the background.
4. Use the wake word **Route** before every hands-free collection command.

### Voice grammar

```text
<command> ::= Route <action>
<action>  ::= Speed <limit> [ahead]
            | Red light camera
            | Community safety zone <start|end>
            | Senior safety zone <start|end>
            | Undo
```

Examples:

- `Route speed 50`
- `Route speed 60 ahead`
- `Route red light camera`
- `Route community safety zone start`
- `Route community safety zone end`
- `Route senior safety zone start`
- `Route senior safety zone end`
- `Route undo`

The **Route** prefix acts as a lightweight wake phrase. Speech without that prefix is ignored by the command parser.

## Compact driving overlay

The Google Maps overlay has been tightened for road testing. It shows:

- **Posted** speed and current **Actual** GPS speed.
- A warning indicator when actual speed is above the configured tolerance for that posted speed.
- Compact hands-free, red-light-camera and speed-marker controls.
- **Community safety zone** and **Senior safety zone** on one line each, with separate start/end icon buttons.
- Collapsible **Speed** and **⚙ Settings** panels.

## Speed-zone collection

The **Speed** panel lets a tester select:

1. the posted speed;
2. whether the observation is the **Zone begins** point or an **Advance sign**;
3. **Set** to save the current GPS position.

`speed` markers represent the actual start of the posted zone. `speed_advance` markers retain the location of an advance warning sign so route profiling can later use the sign position separately from the zone boundary.

A normal voice command such as `Route speed 60` marks the zone start. `Route speed 60 ahead` marks an advance sign.

## Posted versus actual speed

The overlay reads observed speed from the phone GPS and compares it with the current posted zone.

The default visual-warning tolerances are:

- posted speed below 100 km/h: **+8 km/h**;
- posted speed 100 km/h or higher: **+9 km/h**.

These are only warning thresholds; they do not control the vehicle. Each common posted speed has its own user-editable tolerance under **⚙ Settings**, adjustable without rebuilding the app.

## Driver alerts

Route Collector can speak and optionally display route alerts from previously collected facts:

```text
<alert> ::= Speed reduction to <limit>
          | <limit> kilometre zone active
          | Red light camera ahead
          | Entering community safety zone
          | Leaving community safety zone
          | Entering senior safety zone
          | Leaving senior safety zone
```

Speed reductions get an advance warning. Speed increases are announced only when the new zone becomes active.

## Red light cameras

- The UI and voice grammar use **Red light camera** explicitly.
- When a red light camera is marked, Route Collector asks OpenStreetMap/Overpass for nearby named roads and attempts to **snap the marker to the centre of the nearest intersection** rather than blindly storing the phone's exact GPS position.
- The original observed GPS position is retained in the marker note for later data-quality work.
- If an intersection cannot be resolved confidently, the marker falls back to the observed GPS point and reports **Intersection not confirmed** rather than guessing.
- The snapped intersection name is spoken and shown in the overlay.
- Previously collected legacy `camera` markers remain readable.
- On future drives, Route Collector says **Red light camera ahead** before the intersection.
- Inside the camera geofence, the overlay offers **Keep** or **Remove** in case the camera has been removed or deactivated.

## Settings

The overlay includes a compact **⚙ Settings** panel. Settings are saved locally and do not require rebuilding the app.

- **Visual alerts ON/OFF** — spoken alerts can remain enabled while the overlay stays visually quiet for night driving.
- **Red light camera warning distance** — adjustable in 50 m steps from 100 m to 500 m. Default: 200 m.
- **Over-speed warning tolerance** — select a posted speed and adjust its allowed offset in 1 km/h steps.
- **Export route data** — writes a JSON backup to the Android Downloads folder.

Visual alerts use a dark overlay and do not intentionally wake or brighten the screen.

## Data export and sharing direction

The current export includes drives, GPS breadcrumb points, and markers in `routecollector-export-v1` JSON format.

Cloud synchronization, multi-user merge rules, confidence scoring, and shared-vs-private layers are intentionally deferred and tracked in `BACKLOG.md`.

## Map display

Recorded drives are displayed on OpenStreetMap. Red light camera markers use a dedicated traffic-light/camera pin icon instead of the generic marker.

## Data model

- `DriveEntity` — one recorded trip.
- `TrackPointEntity` — timestamp, latitude, longitude, GPS accuracy, and observed speed.
- `MarkerEntity` — geographic facts such as `speed`, `speed_advance`, `red_light_camera`, `community_safety_zone_start/end`, and `senior_safety_zone_start/end`.

Older marker types remain readable for compatibility with earlier builds.

## Local build/install

```powershell
.\gradlew.bat assembleDebug
```

Install to an attached Android device with:

```powershell
& "C:\Users\Grant\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

The app targets Android 10+ (API 29+) and compile/target SDK 35.

## GitHub Actions cloud build

The repository includes `.github/workflows/android-debug-apk.yml`.

Pushes to `main` or `feature/google-maps-overlay` trigger a cloud debug APK build. Trusted branch builds restore the repository's private debug-signing secret so the cloud APK can update the same installed development app used by local Android Studio builds.

The latest successful cloud build is published to the stable prerelease tag `latest-debug` as `routecollector-debug.apk`, while the normal Actions artifact is also retained temporarily.

## Stack

- Kotlin
- Jetpack Compose
- Room / SQLite
- Google Fused Location Provider
- Android foreground location service
- Android SpeechRecognizer + TextToSpeech
- Android audio focus for spoken acknowledgements
- osmdroid / OpenStreetMap
- OpenStreetMap Overpass API for intersection snapping
- GitHub Actions for cloud debug APK builds

## Important phone settings

Samsung can aggressively sleep apps. For reliable recording, set Route Collector to **Unrestricted** battery use after installation.

## Planned next steps

- Import of exported Route Collector JSON.
- Direction-aware road facts and route profiles.
- Better wake-word / in-car microphone behavior with music playing.
- OpenPilot/Comma consumer for route-aware speed targets.
- Cloud/community work is tracked separately in `BACKLOG.md`.

## Safety

Do not interact with the phone while driving. Hands-free commands and passive alerts are intended to reduce interaction, but the driver remains responsible for road conditions, legal speed, traffic signals, braking decisions, and safe vehicle operation. Route Collector alerts are informational only.
