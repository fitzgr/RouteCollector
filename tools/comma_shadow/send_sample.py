#!/usr/bin/env python3
"""Send a test advisory to the read-only receiver (no car connection)."""
import json
import os
import time
from urllib.request import Request, urlopen

endpoint = os.getenv('DRIVEBUDDY_SHADOW_URL', 'http://127.0.0.1:8765/v1/advisory')
token = os.environ['DRIVEBUDDY_SHADOW_TOKEN']
payload = {
    'schema': 'drivebuddy.speed_advisory.v1',
    'mode': 'shadow_only',
    'timestampMs': int(time.time() * 1000),
    'targetSpeedKph': 60,
    'source': 'manual_test',
    'driveId': 'sample-drive',
}
request = Request(endpoint, data=json.dumps(payload).encode('utf-8'),
                  headers={'Content-Type': 'application/json', 'X-DriveBuddy-Token': token},
                  method='POST')
with urlopen(request, timeout=3) as response:
    print(response.read().decode('utf-8'))
