# Route Collector — Android MVP

Route Collector records road facts on repeat routes while Google Maps remains available for navigation. Collected facts can be used on later drives for direction-aware speed, safety-zone, deer-crossing, and red-light-camera alerts, with longer-term plans for route-aware vehicle integrations.

## Current driving workflow

1. Open Route Collector and tap **Start drive**.
2. Open **Google Maps + overlay**.
3. Navigate normally while Route Collector records GPS breadcrumbs, observed speed, and travel direction in the background.
4. Use the floating overlay to capture road facts without leaving Google Maps.
5. On later drives, Route Collector recognizes collected facts and provides spoken/visual alerts where applicable.

## Floating Google Maps overlay

The compact overlay is designed for road testing and currently provides:

- Current **Posted** or **Collected** speed source and **Actual** GPS speed, with the speed Clear action on the same row.
- Configurable over-speed warning tolerance.
- Primary quick actions ordered **🚦 📷 → 🦌 🚸 → Speed**, using compact icon-only camera and deer-crossing buttons.
- Collapsible speed-marker controls.
- Community safety-zone start/end controls.
- Senior safety-zone start/end controls.
- An **Active zones** panel with clear/delete actions where applicable.
- **Undo** for the latest marker.
- Collapsible **⚙ Settings**.
- Spoken acknowledgements and optional visual alerts.
- A separate marker-map overlay for reviewing nearby collected road facts while testing.

## Speed-zone collection and alerts

The **Speed** panel supports posted speeds of 40, 50, 60, 70, 80, 90, 100, and 110 km/h. Opening **Speed** preselects the current Posted/Collected speed (fallback 60 km/h) and resets the marker type to **Zone begins**. The selected speed remains visible while the panel is open. A tester records either:

- **Zone begins** — the actual boundary where the new posted limit starts; or
- **Advance sign** — the earlier sign warning that a different limit is coming.

Route Collector stores these separately as `speed` and `speed_advance` markers.

### Direction-aware speed facts

Speed markers use the vehicle's travel direction when they were collected. On later drives, Route Collector compares the current travel bearing with the marker direction so a speed marker intended for the opposite direction does not normally change the active posted speed.

- Same-direction advance markers can announce **Speed reduction to <limit> ahead**.
- A lower upcoming speed zone can produce an advance **Speed reduction to <limit>** warning.
- At the zone boundary, the new speed becomes active and can announce **<limit> kilometre zone active**.
- Opposite-direction speed markers are treated as informational rather than changing the active speed.
- The active speed zone can be manually cleared from the overlay.

If no collected speed zone is active, Route Collector can attempt to initialize the posted speed from OpenStreetMap road data. A manually cleared zone is not immediately repopulated by that lookup.

## Posted versus actual speed

The overlay compares GPS-observed vehicle speed with the current posted limit.

Default warning tolerances are:

- posted speed below 100 km/h: **+8 km/h**;
- posted speed 100 km/h or higher: **+9 km/h**.

Each supported posted speed has its own user-editable tolerance under **⚙ Settings**, adjustable from 0 to +20 km/h in 1 km/h steps. When actual speed crosses the configured threshold, the overlay highlights the condition and the tracking service can speak **Speed threshold exceeded**.

These are notification thresholds only; Route Collector does not control vehicle speed.

## Deer crossing zones

The overlay includes a one-tap **🦌 Deer entering** action.

The collection model is intentionally simple while driving:

1. Mark **Deer entering** when entering a known deer-crossing area from one direction.
2. On a drive through the same area in the opposite direction, mark **Deer entering** at that direction's entrance.
3. Route Collector stores the travel bearing with each marker and can pair opposite-direction endpoints when they are within the supported pairing distance and their bearings indicate opposing travel directions.

Once a pair is recognized, later drives can distinguish the boundary by travel direction and announce:

- **Entering deer crossing area**
- **Leaving deer crossing area**

The marker-map overlay displays deer boundaries with a dedicated **D** marker.

## Community and senior safety zones

The overlay provides separate start/end controls for:

- **Community safety zone**
- **Senior safety zone**

On later drives, recognized boundaries can announce:

- **Entering community safety zone**
- **Leaving community safety zone**
- **Entering senior safety zone**
- **Leaving senior safety zone**

The **Active zones** panel shows currently active safety zones. A zone can be cleared by recording its end, and a mistaken nearby safety-zone pair can be deleted from the overlay.

Legacy school-zone marker types remain readable as community-safety-zone facts.

## Red-light cameras

The **🚦 Camera** quick action records a red-light-camera observation.

When a camera is marked, Route Collector asks OpenStreetMap/Overpass for nearby named roads and attempts to snap the marker to the centre of the nearest intersection rather than blindly storing the phone's raw GPS position.

- The original observed GPS position is retained in the marker note.
- If an intersection cannot be resolved confidently, the marker remains at the observed GPS position and reports that the intersection was not confirmed.
- When an intersection is resolved, its name is included in the acknowledgement.
- Previously collected legacy `camera` markers remain readable.
- The warning distance is configurable from **100–500 m** in 50 m steps; default is **200 m**.
- Previously collected cameras can trigger an approaching-camera warning.
- Near a known camera, Route Collector shows a temporary **Remove camera** action; if it is not used, it disappears automatically after the intersection.

## Spoken and visual driver alerts

Route Collector uses Android Text-to-Speech for automatic route alerts and spoken capture acknowledgements. Current automatic alert categories include:

```text
Speeding
Reduce to <limit> ahead
Reduce to <limit>
<limit> kilometre zone active
Deer crossing area
Leaving deer area
Entering community zone
Leaving community zone
Entering senior zone
Leaving senior zone
Red-light-camera approach / verification alerts
```

Capture actions also provide spoken acknowledgements for speed markers, deer points/pairs, safety-zone boundaries, camera marking/verification, clearing/deleting zones, undo, and export operations.

When music is active, spoken prompts pause playback, wait about 250 ms, speak at full TTS prompt volume, and resume playback when speech finishes. Visual alerts can be disabled independently while spoken alerts remain available. The overlay uses a dark alert surface and does not intentionally wake or brighten the screen.

## Direction and GPS tracking

While a drive is active, Route Collector records:

- timestamp;
- latitude and longitude;
- GPS accuracy;
- GPS-observed speed; and
- current travel bearing when available.

Travel bearing comes from GPS bearing when available and can fall back to movement between recent locations. Direction is used to distinguish road facts that apply to the current direction of travel from markers collected for the opposite direction.

## Automatic stopped-drive backup

If Route Collector detects that the vehicle has remained below the driving-speed threshold for approximately **10 minutes**, it can automatically export the route data, end the drive, and stop the tracking/overlay services. This protects collected test data if a drive is left running after arrival.

## Settings

The compact **⚙ Settings** panel stores preferences locally and currently includes:

- **Visual alerts ON/OFF**.
- **Red-light camera warning distance**, 100–500 m in 50 m increments.
- **Over-speed warning tolerance** for each supported posted speed.
- **Export route data** to Android Downloads.

Settings persist without rebuilding the app.

## Data export

Route Collector exports a JSON backup to the Android Downloads folder. The export includes drives, GPS breadcrumb points, and collected markers using the `routecollector-export-v1` format.

Cloud synchronization, multi-user merge rules, confidence scoring, and shared-vs-private data layers are intentionally deferred and tracked in `BACKLOG.md`.

## Map display

Recorded drives can be displayed using OpenStreetMap/osmdroid. The floating marker-map overlay provides a compact road-testing view of collected facts, including speed markers, red-light cameras, safety-zone boundaries, and deer-zone boundaries.

Red-light cameras use a dedicated traffic-light/camera representation and deer boundaries use a dedicated **D** marker.

## Data model

- `DriveEntity` — one recorded trip.
- `TrackPointEntity` — timestamp, latitude, longitude, GPS accuracy, and observed speed.
- `MarkerEntity` — collected geographic facts.

Current marker types include:

```text
speed
speed_advance
red_light_camera
deer_zone_enter
community_safety_zone_start
community_safety_zone_end
senior_safety_zone_start
senior_safety_zone_end
```

Older marker types remain readable for compatibility with earlier builds.

## Local build/install

Build a debug APK with:

```powershell
.\gradlew assembleDebug
```

Build and install directly to an attached Android device with:

```powershell
.\gradlew installDebug
```

Or install an already-built APK with ADB:

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
- Android Text-to-Speech
- Android audio focus for spoken acknowledgements
- osmdroid / OpenStreetMap
- OpenStreetMap Overpass API for intersection snapping
- GitHub Actions for cloud debug APK builds

## Important phone settings

Some Android devices, including Samsung phones, can aggressively sleep background apps. For reliable road recording, set Route Collector to **Unrestricted** battery use after installation.

The floating collector and marker-map overlays also require Android permission to display over other apps.

## Development direction

Current work is focused on making road-fact collection and playback reliable enough for repeat-route testing. Likely follow-on work includes:

- Import of exported Route Collector JSON.
- Further refinement of direction-aware road facts and route profiles.
- Editable/customizable spoken warning phrases.
- Better in-car microphone / hands-free collection behavior.
- More robust validation and cleanup of collected road facts.
- OpenPilot/Comma consumer for route-aware speed targets.
- Cloud/community sharing work tracked separately in `BACKLOG.md`.

## Safety

Do not interact with the phone while driving. The overlay, spoken acknowledgements, and passive alerts are intended to minimize interaction, but the driver remains responsible for road conditions, legal speed, traffic signals, braking decisions, and safe vehicle operation. Route Collector alerts are informational only.
