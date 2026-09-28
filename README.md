# Route Collector — Android

Route Collector is a road-fact collection and repeat-route driving companion for Android. It records GPS drives, lets a tester capture road features with very little interaction, and reuses those collected facts on later trips for direction-aware spoken and visual alerts.

The app is deliberately separate from the navigation engine: Google Maps can remain the driver's navigation app while Route Collector runs its foreground tracking service and floating controls. Route Collector also has its own OpenStreetMap/osmdroid map for live route context and recorded-drive review.

> Route Collector is a development/road-testing tool. Its alerts are informational only. The driver remains responsible for posted signs, traffic signals, road conditions, legal speed, and safe operation of the vehicle.

## What is built

Current functionality includes:

- Foreground GPS drive recording with latitude, longitude, timestamp, accuracy, speed and travel bearing.
- Manual **Start / Stop** plus automatic driving start after sustained valid driving fixes.
- Automatic stop after driving has been established and there has been about **10 continuous minutes with no meaningful position movement**.
- A compact collector UI plus floating overlay intended to coexist with Google Maps.
- Live **Posted / Collected / Actual** speed information.
- OpenStreetMap road-speed lookup when a collected speed fact is not active.
- Direction-aware speed-limit facts and advance signs.
- Per-speed overspeed tolerances based on the live GPS **Actual** speed.
- Red-light-camera collection with intersection snapping.
- Deer-area endpoint pairing and direction-aware entry/exit behavior.
- Pedestrian-crossing collection and approach warnings.
- Community safety zones.
- Senior safety zones.
- Directional passing zones.
- Spoken Text-to-Speech acknowledgements and alerts.
- Optional visual alerts.
- Active-zone controls for clearing/removing collected facts.
- Undo of the latest marker.
- Recorded-drive history (latest 10 drives).
- OpenStreetMap live/recorded route display.
- Local JSON export.
- In-app build/version checking and GitHub debug APK updating.
- GitHub Actions cloud builds signed with the same development certificate used by the established test installation.

---

## Driving workflow

1. Start Route Collector, or allow its driving detection to start a drive after sustained valid movement.
2. Keep Route Collector visible or use its floating collector while Google Maps provides navigation.
3. Route Collector records the drive in the background.
4. Tap a marker control when a real road fact is encountered.
5. Route Collector stores the position, type and — where relevant — travel direction and pairing information.
6. On later drives, the tracking service evaluates upcoming facts against current position and direction and provides the appropriate alert.

The design goal is to make **collection quick while driving** and make later playback largely passive.

---

## Collector layout

The primary road-fact controls are grouped by how the facts behave.

### Markers

Point-like observations:

- **📷 Camera**
- **🦌 Deer**
- **🚸 Pedestrian**

### Start / Stop zones

Features that describe an area or directional segment:

- **Community safety zone**
- **Senior safety zone**
- **Passing zone**

### Speed

Speed controls remain directly available below the zone controls:

- posted speed selector;
- **Zone begins / Advance sign** selector;
- **Set**.

Supported manually collected speeds are **30 through 110 km/h in 10 km/h steps**.

Changing the selector alone does not record a GPS marker. The selected value becomes a road fact only when **Set** is tapped.

---

# Road-fact and zone behaviour

The road-fact types are intentionally different. A camera is not treated like a speed zone, and a deer area is not treated like a community safety zone.

## Speed zones

Two speed facts can be collected.

### Zone begins

A **Zone begins** marker records the point where a posted speed actually takes effect.

Stored marker type:

`speed`

When approached later in the appropriate direction, the new speed becomes the active collected posted speed at the boundary and can announce:

> “<limit> kilometre zone active”

### Advance sign

An **Advance sign** records an earlier sign telling the driver that a different speed is coming.

Stored marker type:

`speed_advance`

An advance marker does **not** immediately replace the active posted speed. It is used to warn about the upcoming change, particularly a reduction.

### Direction handling

Speed facts save the travel bearing present when they are captured. Playback compares that bearing with current travel direction.

This prevents a speed marker collected for the opposite carriageway/direction from normally changing the current active limit.

A significant turn can clear the collected road-speed context so the app does not carry a directional speed fact onto a different road. Route Collector can then request a fresh OpenStreetMap posted-speed value.

A manually cleared collected speed is also prevented from being immediately reintroduced as though it were still active.

---

## Posted, Collected and Actual speed

These values mean different things:

- **Posted** — the current road limit obtained from OpenStreetMap when available.
- **Collected** — a Route Collector speed fact that has become active and is authoritative for that collected road segment.
- **Actual** — the vehicle's current real-time GPS speed.

The overspeed alert is evaluated against **Actual**, not against another calculated/display value.

For example, with an 80 km/h posted speed and a +8 km/h tolerance, the alert condition is:

`Actual GPS speed > 88 km/h`

The service logs the posted speed, tolerance, resulting threshold, live actual speed and warning decision for road-test diagnosis.

### Default tolerances

- Below 100 km/h: **+8 km/h**
- 100 km/h and above: **+9 km/h**

Each supported speed has its own locally stored tolerance, adjustable from **0 to +20 km/h**.

After an overspeed warning has become active, recovery uses a **2 km/h hysteresis** so the alert does not chatter on/off around one exact GPS reading. Returning sufficiently below the threshold produces the short recovery indication and spoken **“Good”**.

---

## Deer crossing areas

Deer areas are collected from the driver's perspective with a simple **entering** action.

The driver marks an entrance while travelling one direction. On a later pass in the opposite direction, the entrance at the other end can also be marked. Route Collector uses location and opposing travel bearings to associate the endpoints into a deer area.

This lets the same two physical endpoints mean different things depending on direction:

- approaching an endpoint in its recorded direction → **entering the deer area**;
- approaching the opposite endpoint from inside the paired area → **leaving the deer area**.

The active deer state appears in **Active zones** and can be cleared. A collected deer endpoint or its paired area can also be deleted.

During the higher-risk night period — from approximately **30 minutes before local sunset through 30 minutes after local sunrise** — the entry message can additionally remind the driver to use high beams when safe.

This is deliberately different from a generic point warning: the paired endpoints describe an **area with an entrance and exit**.

---

## Pedestrian crossings

A pedestrian crossing is a point feature.

Stored marker type:

`pedestrian_crossing`

Unlike camera collection, the pedestrian marker is stored at the **exact GPS position at which it is captured** rather than being snapped to a nearby intersection.

Pedestrian crossings are treated as bidirectional road facts. The warning distance adapts to speed and is constrained to approximately **120–350 metres**.

The map uses a dedicated 🚸 representation.

---

## Community safety zones

Community safety zones have explicit **start** and **end** boundaries.

Marker types include:

`community_safety_zone_start`  
`community_safety_zone_end`

The current active state is shown in the **Active zones** panel.

Playback can announce entering and leaving the zone. During configured school-activity periods, the community-zone entry alert can additionally call attention to children.

Current weekday school-activity windows used by the app are:

- 7:30–9:30
- 11:00–13:30
- 14:00–16:30

Older school-zone marker types remain readable for compatibility.

A community zone can be:

- ended normally by recording its end;
- **cleared** from the current active state; or
- **deleted** as a nearby paired collected zone when the capture was wrong.

---

## Senior safety zones

Senior safety zones use the same start/end concept as community zones but remain a separate semantic zone type.

Marker types:

`senior_safety_zone_start`  
`senior_safety_zone_end`

They can announce entry/exit, appear independently in **Active zones**, and can be cleared or have their nearby paired markers deleted.

Keeping senior and community zones separate allows their presentation and future warning behaviour to evolve independently.

---

## Passing zones

Passing zones are **directional paired start/end road segments**.

They are not treated as generic point markers. The direction in which the zone was captured matters, allowing the app to avoid applying the same directional passing-zone state indiscriminately to opposing travel.

On entry in the recorded direction, the app can warn the driver to be aware of oncoming traffic.

---

## Red-light cameras

Red-light-camera collection is intersection-oriented rather than simply storing raw phone GPS.

When **Camera** is tapped, Route Collector uses nearby OpenStreetMap/Overpass road information to find a plausible intersection and attempts to snap the camera fact to the intersection centre.

The capture retains the observed position in its metadata so the original GPS observation is not lost.

If a confident intersection cannot be established, the marker remains at the observed location instead of inventing an intersection.

Existing legacy `camera` facts remain readable alongside `red_light_camera`.

### Camera playback

- Configurable approach-warning distance: **100–500 m**, default **200 m**.
- Camera facts are not dependent on having captured the exact same travel direction.
- Approaching a known camera can produce a spoken warning.
- Near the camera, Route Collector exposes contextual **Keep / Remove** verification.
- The camera state is reflected in the Active zones/road-alert UI while relevant.

The live map uses a dedicated camera pin.

---

# Spoken and visual alerts

Route Collector uses Android Text-to-Speech for both collection acknowledgements and automatic road alerts.

Examples include:

- speed reduction ahead;
- speed-zone activation;
- overspeed warning and recovery;
- deer-area entry/exit;
- community-zone entry/exit;
- senior-zone entry/exit;
- pedestrian crossing;
- passing-zone warning;
- red-light-camera approach;
- marker captured/deleted/cleared acknowledgements.

When music is playing, Route Collector can pause playback, allow a short delay for the spoken prompt, speak, and resume music when the prompt sequence finishes.

Visual alerts can be switched off independently.

---

# GPS, direction and drive lifecycle

While a drive is active Route Collector records GPS breadcrumb points containing:

- timestamp;
- latitude;
- longitude;
- accuracy;
- GPS-observed speed.

The service also maintains current travel bearing. GPS-provided bearing is preferred when available; movement between recent valid locations can provide a fallback.

Direction is used by features such as speed facts, deer boundaries and passing zones.

## Automatic start

When Route Collector is not already recording and location permission is available, sustained accurate driving fixes at driving speed can automatically start a drive.

This avoids requiring a manual Start every time.

## Automatic stop

Auto-stop is deliberately **position based**, not simply “speed is low.”

After real driving has been confirmed, Route Collector watches for meaningful GPS position movement. Approximately **10 continuous minutes without meaningful movement** can end the drive.

Meaningful movement clears the pending stationary timer. A live GPS speed above the stationary range also prevents a contradictory “no movement” countdown from being shown.

---

# Active zones and correction tools

The **Active zones** section is the driver's current road-context summary.

Depending on what is active it can show:

- current speed;
- community safety;
- senior safety;
- deer area;
- red-light camera.

Actions are context dependent:

- **Clear** changes the current active state without necessarily deleting the underlying road fact.
- **Delete** removes the relevant collected marker/pair where supported.
- **Remove** is used for camera correction.
- **Undo** removes the latest marker from the current drive.

That distinction matters: clearing a state and deleting collected geographic data are intentionally not the same operation.

---

# Map and drive history

Route Collector uses **osmdroid/OpenStreetMap** for its own map.

During a live drive:

- the vehicle remains horizontally centred;
- it is positioned at roughly **67% of the map height from the top** (about one-third up from the bottom);
- more map is therefore visible in the direction of travel;
- the map rotates with travel direction;
- zoom changes with driving speed;
- upcoming relevant markers are shown on the map;
- active zone context is visually indicated around the current vehicle position.

Marker representations distinguish cameras, pedestrian crossings, deer points, safety zones and speed facts.

## Recorded drives

The app retains the latest **10 drives** for quick history.

Selecting a recorded drive displays its route fitted to the map with **Start** and **End** markers plus the road facts associated with that drive.

---

# Data storage and export

Route Collector uses Room/SQLite locally.

Core entities:

- **DriveEntity** — one drive/session.
- **TrackPointEntity** — GPS breadcrumb data.
- **MarkerEntity** — a collected geographic road fact.

The app can export its data to Android Downloads as JSON using the `routecollector-export-v1` format.

The existing app database is intentionally preserved when installing development updates over the existing package with ADB `install -r`.

Cloud/community synchronization and multi-user merge/confidence models are not part of the current local MVP; related future work belongs in `BACKLOG.md`.

---

# Settings

Current local settings include:

- Visual alerts on/off.
- Red-light-camera warning distance.
- Per-posted-speed overspeed tolerance.
- Route-data export.
- Installed/latest build information.
- GitHub update checking and installation.

Preferences persist locally.

---

# In-app build/update checker

The Settings panel displays the **installed version name/build number** and checks the stable GitHub `latest-debug` publication.

The checker now follows this sequence:

1. Read the latest published GitHub build metadata.
2. Compare its actual APK build version with `BuildConfig.VERSION_CODE` installed on the phone.
3. If the GitHub build is newer, report that an update is available and enable **Update**.
4. If the two builds are in sync, explicitly report that state.
5. Check GitHub Actions for an active push build.
6. If a build is queued/in progress, show that Actions is building and monitor it.
7. Recheck every 10 seconds while the build remains active.
8. Once Actions completes, re-read the published build metadata and either offer the new APK or report that the phone remains in sync.

Requests use cache-busting/no-cache behaviour so a stale `update.json` is less likely to masquerade as the current release.

The published `update.json` takes its `versionCode` and `versionName` directly from the **built APK's Gradle output metadata**. It does not independently invent a second timestamp version during publication.

This is important because Android will reject an APK whose version code is lower than the installed package.

---

# GitHub Actions build pipeline

Workflow:

`.github/workflows/android-debug-apk.yml`

Pushes to `main` and `feature/google-maps-overlay` build the debug APK.

For trusted non-PR builds the workflow:

1. checks out the repository;
2. configures JDK 17 and Gradle caching;
3. restores the development debug keystore from the GitHub secret;
4. builds the APK;
5. verifies the expected signing-certificate SHA-256;
6. reads the APK's actual Gradle version metadata;
7. publishes/updates the stable `latest-debug` prerelease;
8. publishes `routecollector-debug.apk` and `update.json`;
9. verifies that the updater assets are live;
10. uploads a temporary Actions artifact when artifact quota permits.

Pull-request runs build/test the branch but skip trusted signing/release publication.

---

# Local build and install

For a PowerShell session:

```powershell
git pull
.\gradlew clean assembleDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

For a reusable Windows `.cmd` file, remember that PowerShell's `$env:` syntax and leading `&` do not apply. Also use `call` for the Gradle batch file so control returns to the script:

```bat
@echo off
git pull
call gradlew.bat clean assembleDebug

"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" install -r "app\build\outputs\apk\debug\app-debug.apk"

pause
```

The `-r` installation updates the existing package in place and preserves its application data, provided Android accepts the version/signature.

The app targets Android 10+ (API 29+) and compile/target SDK 35.

---

# Technology stack

- Kotlin
- Jetpack Compose
- Room / SQLite
- Google Fused Location Provider
- Android foreground location service
- Android Text-to-Speech
- Android media controls
- osmdroid / OpenStreetMap
- OpenStreetMap Overpass data
- GitHub Actions

---

# Important Android settings

For reliable background road testing:

- grant precise/fine location permission;
- allow notifications where Android requires them for foreground-service visibility;
- grant display-over-other-apps permission when using the floating overlay;
- on phones with aggressive battery management, such as many Samsung devices, set Route Collector to **Unrestricted** battery use.

Do not uninstall the development app merely to resolve an update problem if its local Room data needs to be preserved. Diagnose version/signature/install errors and update the package in place instead.

---

# Current design principles

1. **Actual means actual.** Driver-speed warnings use the live GPS speed shown as Actual.
2. **Direction matters where the road fact is directional.** Speed, deer and passing behavior should not blindly apply to the opposite direction.
3. **Point facts and zones are different.** A pedestrian crossing or camera is not modeled like a paired safety area.
4. **Collected speed is authoritative while active.** OSM is a useful posted-speed bootstrap/fallback, not a reason to overwrite an active collected fact.
5. **Collection should require minimal interaction.** Capture controls are compact and acknowledgements are spoken.
6. **Correction must be possible.** Undo, Clear, Delete and camera Keep/Remove serve different correction cases.
7. **Local data survives normal updates.** Development installation is designed around package replacement rather than uninstall/reinstall.
8. **Update status should be observable.** The app distinguishes installed-vs-published synchronization from a GitHub build that is still underway.

---

# Future work

Items still appropriate for future development include:

- richer collected-point/map editing and cleanup workflows;
- import/restore of exported Route Collector JSON;
- cloud/community road-fact sharing;
- confidence scoring and conflict resolution for shared observations;
- further route-profile and direction refinements;
- configurable spoken phrases;
- deeper hands-free collection;
- OpenPilot/Comma integration and use of Route Collector as a route-data broker for companion apps.

See `BACKLOG.md` for deferred ideas and implementation notes.
