#!/usr/bin/env python3
"""Drive Buddy -> comma 4 shadow receiver. No vehicle control of any kind.

Portable Python 3 standard-library HTTP service intended for parked/bench
testing on a laptop first, and later as a *separate* process on comma hardware.
Nothing in this module imports openpilot or accesses CAN, throttle, brakes,
steering, panda or vehicle control interfaces.
"""
import argparse
import hmac
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_BODY_BYTES = 4096
FIXED_OFFSET_KPH = 8


def validate(payload, now_ms=None):
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    if not isinstance(payload, dict):
        raise ValueError("JSON object required")
    if payload.get("schema") != "drivebuddy.speed_advisory.v2":
        raise ValueError("unsupported schema; v2 is required")
    if payload.get("mode") != "shadow_only":
        raise ValueError("only shadow_only mode is supported")
    posted = payload.get("postedSpeedKph")
    target = payload.get("targetSpeedKph")
    offset = payload.get("offsetKph")
    for key, value in (("postedSpeedKph", posted), ("targetSpeedKph", target), ("offsetKph", offset)):
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError(f"{key} must be an integer")
    if not 20 <= posted <= 130:
        raise ValueError("postedSpeedKph must be in 20..130")
    if offset != FIXED_OFFSET_KPH or target != posted + offset:
        raise ValueError("target must equal posted limit + 8 km/h")
    timestamp = payload.get("timestampMs")
    if isinstance(timestamp, bool) or not isinstance(timestamp, int) or abs(now_ms - timestamp) > 10_000:
        raise ValueError("timestampMs must be within 10 seconds of receiver clock")
    if payload.get("source") not in ("collected", "manual_test"):
        raise ValueError("only collected markers or manual connection tests are accepted")
    drive_id = payload.get("driveId")
    if not isinstance(drive_id, str) or not drive_id or len(drive_id) > 128:
        raise ValueError("driveId must be a nonempty string of at most 128 characters")
    return {
        "accepted": True,
        "appliedToVehicle": False,
        "mode": "shadow_only",
        "postedSpeedKph": posted,
        "offsetKph": FIXED_OFFSET_KPH,
        "targetSpeedKph": target,
    }


def make_handler(token, log_path):
    lock = threading.Lock()
    last_message = {"record": None}

    class Handler(BaseHTTPRequestHandler):
        def authorized(self):
            supplied = self.headers.get("X-DriveBuddy-Token", "")
            return bool(token) and hmac.compare_digest(supplied, token)

        def do_GET(self):
            if self.path != "/v1/status":
                return self.respond(404, {"error": "unknown path", "appliedToVehicle": False})
            if not self.authorized():
                return self.respond(401, {"error": "unauthorized", "appliedToVehicle": False})
            with lock:
                record = last_message["record"]
            self.respond(200, {
                "mode": "shadow_only",
                "appliedToVehicle": False,
                "lastReceived": record,
            })

        def do_POST(self):
            if self.path != "/v1/advisory":
                return self.respond(404, {"error": "unknown path", "appliedToVehicle": False})
            if not self.authorized():
                return self.respond(401, {"error": "unauthorized", "appliedToVehicle": False})
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= MAX_BODY_BYTES:
                    raise ValueError("body size invalid")
                payload = json.loads(self.rfile.read(length))
                result = validate(payload)
                record = {
                    "receivedAtMs": int(time.time() * 1000),
                    "advisory": payload,
                    "result": result,
                }
                # Lock prevents concurrent requests from mixing log entries.
                with lock:
                    with Path(log_path).open("a", encoding="utf-8") as out:
                        out.write(json.dumps(record, separators=(",", ":")) + "\n")
                    last_message["record"] = record
            except (ValueError, TypeError, UnicodeDecodeError, json.JSONDecodeError) as ex:
                return self.respond(400, {"error": str(ex), "appliedToVehicle": False})
            except OSError:
                return self.respond(500, {"error": "unable to write advisory log", "appliedToVehicle": False})
            print(json.dumps(record, separators=(",", ":")), flush=True)
            return self.respond(200, result)

        def respond(self, status, body):
            data = json.dumps(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, fmt, *args):
            pass

    return Handler


def main():
    parser = argparse.ArgumentParser(description="No-control Drive Buddy shadow receiver")
    parser.add_argument("--host", default="127.0.0.1",
                        help="Use a private trusted LAN interface only for phone testing")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--log", default="shadow-advisories.jsonl")
    args = parser.parse_args()
    token = os.environ.get("DRIVEBUDDY_SHADOW_TOKEN", "")
    if len(token) < 16:
        parser.error("DRIVEBUDDY_SHADOW_TOKEN must be >=16 characters")
    server = ThreadingHTTPServer((args.host, args.port), make_handler(token, args.log))
    print(f"SHADOW ONLY: http://{args.host}:{args.port}/v1/advisory", flush=True)
    print("No cruise, CAN, acceleration, braking or steering functions", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
