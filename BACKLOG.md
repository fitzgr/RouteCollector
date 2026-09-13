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
