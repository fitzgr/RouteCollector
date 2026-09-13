# Route Collector — Android MVP

A small native Android collector for building your own verified road-data layer before adding a Comma/Openpilot device.

## Version 0.1 features

- Start/stop a drive.
- Record a GPS breadcrumb every ~2 seconds while a foreground service is active.
- Continue recording when the screen is off / app is backgrounded after tracking has been started from the foreground.
- One-tap **Camera intersection** marker.
- Tap **Voice marker** and say things such as:
  - `camera intersection`
  - `speed changes to 80`
  - `school zone begins`
- Store everything locally in Room/SQLite.
- Replay any recorded drive on an OpenStreetMap map.
- Route facts and later personal driving rules can remain separate. This MVP records facts only.

## Stack

- Kotlin
- Jetpack Compose
- Room
- Google Fused Location Provider
- Android foreground location service
- Android speech recognizer
- osmdroid / OpenStreetMap (no Google Maps API key required)

## Open it

1. Install Android Studio (JDK 17 is recommended by current Android Gradle tooling).
2. Open the `RouteCollector` folder as a project.
3. Allow Gradle to sync dependencies.
4. Connect your Samsung phone with USB debugging enabled, or use an emulator.
5. Run the `app` configuration.
6. Grant Location, Microphone, and Notification permissions when asked.

The project targets Android 10+ (API 29+) and compile/target SDK 35.

## Important phone settings

Samsung can aggressively sleep apps. For reliable drive recording, set Route Collector to **Unrestricted** battery use after installation. Start recording while the app is visible; the foreground notification then keeps the location session alive when the screen is off.

## Data model

- `DriveEntity` — one recorded trip.
- `TrackPointEntity` — timestamp, latitude, longitude, GPS accuracy and speed.
- `MarkerEntity` — a geographic fact attached to the trip (`camera`, `speed`, `school_zone`, `note`).

This is deliberately separate from future private preferences such as `+8 km/h on this road`.

## Sensible next milestones

1. Edit/nudge a marker after the drive.
2. Draw/select a road segment and attach a verified posted speed.
3. Export/import the shared facts layer as GeoJSON.
4. Add automatic road snapping and route segmentation.
5. Add a private policy layer (for example a personal max-offset table) without mixing it into shared map facts.
6. Add voice phrases such as `mark speed 80` and parse the value automatically.
7. Sync the facts layer to a small backend so a future Comma client can download it.

## Safety

Do not interact with the phone while driving. The marker controls are intentionally simple, but use them only when it is safe/legal to do so. A later version can support hands-free triggers or passenger input.
