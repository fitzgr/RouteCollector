# Route Collector Backlog

## Cloud and community data — deferred

Keep this work out of the current road-test build until the local collector, voice workflow, route facts, and camera snapping are stable.

- Cloud backup of exported Route Collector data.
- Sign-in / user identity for contributed data.
- Import and merge of another user's route facts.
- Shared-vs-private data layers.
- Duplicate detection for nearby speed, zone, and red-light-camera facts.
- Confidence scoring based on independent observations.
- Conflict resolution when users disagree about a road fact.
- Verification / stale-data workflow for removed or changed cameras and speed zones.
- Server-side history/audit trail rather than destructive overwrites.
- Download of community facts for offline driving use.
- Privacy review for uploaded GPS breadcrumb data; prefer sharing normalized road facts rather than raw trips where possible.

## Other upcoming work

- Import Route Collector JSON exports.
- Direction-aware road facts and route profiles.
- Better wake-word / in-car microphone behavior with music playing.
- OpenPilot/Comma consumer for route-aware speed targets.
- Additional post-drive editing and marker correction tools.


## Zone and safety collection ideas — evaluate before building

Keep these as candidates to revisit rather than automatically adding more driving controls.

- Sharp curve / hazardous bend warnings.
- Blind intersection or poor-visibility stop approaches.
- Railway crossings.
- Hill crests / reduced sight-distance locations.
- Lane drops, merges and difficult lane-positioning points.
- Wildlife areas beyond deer.
- Child-activity / school-bus caution areas.
- Rough-road / pothole-prone segments.
- Flood-prone and recurring ice-prone locations.
- Complex intersections that benefit from an early caution.
- Generic user-defined caution areas.
- Candidate caution points inferred from repeated hard or unusual slowdowns.

Status tags for future review: **Idea → Evaluate → Build**.

## Comma / openpilot automatic sign collection — deferred

Do not include this work in the current Route Collector road-test build.

- Study stock openpilot camera/perception interfaces and VisionIPC using recorded drives first.
- Prototype sign recognition offline on a PC before any in-car integration.
- Explore read-only detection of useful road signs such as stop signs, speed signs, wildlife/deer signs and other Route Collector facts.
- Send only candidate detection events (type, confidence, GPS/time) to Route Collector for review/collection.
- Keep the vehicle-control/openpilot driving stack untouched; Route Collector remains the logging/review layer.
- Only evaluate real-time comma-device integration after the offline prototype is useful and the hardware is available.
