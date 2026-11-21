#!/usr/bin/env python3
"""Send a +8 km/h shadow test. Does not interact with the car."""
import json
import os
import time
from urllib.request import Request, urlopen

endpoint = os.getenv("DRIVEBUDDY_SHADOW_URL", "http://127.0.0.1:8765/v1/advisory")
token = os.environ["DRIVEBUDDY_SHADOW_TOKEN"]
posted = int(os.getenv("DRIVEBUDDY_TEST_POSTED_KPH", "60"))
payload = {
    "schema": "drivebuddy.speed_advisory.v2",
    "mode": "shadow_only",
    "timestampMs": int(time.time() * 1000),
    "postedSpeedKph": posted,
    "offsetKph": 8,
    "targetSpeedKph": posted + 8,
    "source": "manual_test",
    "driveId": "sample-drive",
}
request = Request(endpoint, data=json.dumps(payload).encode("utf-8"),
                  headers={"Content-Type": "application/json", "X-DriveBuddy-Token": token},
                  method="POST")
with urlopen(request, timeout=3) as response:
    print(response.read().decode("utf-8"))
