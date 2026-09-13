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
<action>  ::= Speed <limit>
            | Red light camera
            | Community safety zone <start|end>
            | Senior safety zone <start|end>
            | Undo
```

Examples:

- `Route speed 50`
- `Route speed 80`
- `Route red light camera`
- `Route community safety zone start`
- `Route community safety zone end`
- `Route senior safety zone start`
- `Route senior safety zone end`
- `Route undo`

The **Route** prefix acts as a lightweight wake phrase. Speech without that prefix is ignored by the command parser, which helps reduce false commands from music or conversation.

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
- When a red light camera is marked, Route Collector asks OpenStreetMap/Overpass for nearby named roads and attempts to **snap the marker to the centre of the nearest intersection**, rather than blindly storing the phone's exact GPS position.
- The original observed GPS position is retained in the marker note for later data-quality work.
- If an intersection cannot be resolved confidently, the marker falls back to the observed GPS point and reports **Intersection not confirmed** rather than guessing.
- The snapped intersection name is spoken and shown in the overlay, e.g. `Red light camera snapped to Main Street & King Street`.
- Previously collected legacy `camera` markers are still recognized as red light camera facts.
- On future drives, Route Collector says **Red light camera ahead** before the intersection.
- Inside the camera geofence, the overlay offers **Keep** or **Remove** in case the camera has been removed or deactivated.

## Settings

The overlay includes a **⚙ Settings** panel. Settings are saved locally and do not require rebuilding the app.

- **Visual alerts ON/OFF** — spoken alerts can remain enabled while the overlay stays visually quiet for night driving.
- **Red light camera warning distance** — adjustable in 50 m steps from 100 m to 500 m. Default: 200 m.
- **Export route data** — writes a JSON backup to the Android Downloads folder.

Visual alerts use a dark overlay and do not intentionally wake or brighten the screen.

## Data export and sharing direction

The current export includes drives, GPS breadcrumb points, and markers in `routecollector-export-v1` JSON format. This is the first step toward backup/import/community sharing.

Cloud synchronization, multi-user merge rules, confidence scoring, and shared-vs-private layers are intentionally not enabled yet. Those need a small backend and conflict/data-quality rules so one user's observation cannot silently overwrite another user's verified road fact.

## Map display

Recorded drives are displayed on OpenStreetMap. Red light camera markers use a dedicated traffic-light/camera pin icon instead of the generic marker.

## Data model

- `DriveEntity` — one recorded trip.
- `TrackPointEntity` — timestamp, latitude, longitude, GPS accuracy, and observed speed.
- `MarkerEntity` — geographic facts such as `speed`, `red_light_camera`, `community_safety_zone_start/end`, and `senior_safety_zone_start/end`.

Older `camera` and `school_zone` marker types remain readable for compatibility with data collected in earlier builds.

## Build/install

1. Open the `RouteCollector` project in Android Studio.
2. Use JDK 17.
3. Build with:

```powershell
.\gradlew.bat assembleDebug
```

4. Install to an attached Android device with:

```powershell
& "C:\Users\Grant\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

The app targets Android 10+ (API 29+) and compile/target SDK 35.

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

## Important phone settings

Samsung can aggressively sleep apps. For reliable recording, set Route Collector to **Unrestricted** battery use after installation. Start recording while the app is visible; the foreground notification keeps the location session alive when the app is backgrounded.

## Planned next steps

- GitHub Actions cloud APK builds for cable-free testing.
- Import of exported Route Collector JSON.
- Optional cloud backup and community data sharing.
- Shared-data merge/confidence rules.
- Direction-aware route profiles and approach points.
- OpenPilot/Comma consumer for dynamic cruise-control targets.

## Safety

Do not interact with the phone while driving. Hands-free commands and passive alerts are intended to reduce interaction, but the driver remains responsible for road conditions, legal speed, traffic signals, braking decisions, and safe vehicle operation. Route Collector alerts are informational only.
