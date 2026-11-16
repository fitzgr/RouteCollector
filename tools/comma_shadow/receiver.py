#!/usr/bin/env python3
"""Read-only DriveBuddy speed-advisory receiver. NEVER controls a vehicle."""
import argparse
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY_BYTES = 4096

def validate(payload, now_ms=None):
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    if not isinstance(payload, dict):
        raise ValueError('JSON object required')
    if payload.get('schema') != 'drivebuddy.speed_advisory.v1':
        raise ValueError('unsupported schema')
    if payload.get('mode') != 'shadow_only':
        raise ValueError('only shadow_only mode is supported')
    target = payload.get('targetSpeedKph')
    if isinstance(target, bool) or not isinstance(target, int) or not 20 <= target <= 130:
        raise ValueError('targetSpeedKph must be an integer between 20 and 130')
    timestamp = payload.get('timestampMs')
    if isinstance(timestamp, bool) or not isinstance(timestamp, int) or abs(now_ms - timestamp) > 10_000:
        raise ValueError('timestampMs must be within 10 seconds of server time')
    source = payload.get('source')
    if source not in ('collected', 'osm', 'manual_test'):
        raise ValueError('invalid speed source')
    return {'accepted': True, 'appliedToVehicle': False, 'mode': 'shadow_only', 'targetSpeedKph': target}

def make_handler(token, log_path):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != '/v1/advisory':
                return self.respond(404, {'error': 'unknown path'})
            if not token or self.headers.get('X-DriveBuddy-Token', '') != token:
                return self.respond(401, {'error': 'unauthorized'})
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if length <= 0 or length > MAX_BODY_BYTES:
                    raise ValueError('body size invalid')
                payload = json.loads(self.rfile.read(length))
                result = validate(payload)
            except (ValueError, TypeError, UnicodeDecodeError, json.JSONDecodeError) as ex:
                return self.respond(400, {'error': str(ex), 'appliedToVehicle': False})
            record = {'receivedAtMs': int(time.time() * 1000), 'advisory': payload, 'result': result}
            with open(log_path, 'a', encoding='utf-8') as handle:
                handle.write(json.dumps(record, separators=(',', ':')) + '\n')
            print(json.dumps(record, separators=(',', ':')), flush=True)
            return self.respond(200, result)

        def respond(self, status, body):
            content = json.dumps(body).encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(content)))
            self.end_headers()
            self.wfile.write(content)

        def log_message(self, fmt, *args):
            pass
    return Handler

def main():
    parser = argparse.ArgumentParser(description='Shadow-only advisory receiver; no vehicle control.')
    parser.add_argument('--host', default='127.0.0.1', help='Use 0.0.0.0 only on trusted test LAN')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--log', default='shadow-advisories.jsonl')
    args = parser.parse_args()
    token = os.environ.get('DRIVEBUDDY_SHADOW_TOKEN', '')
    if not token:
        parser.error('DRIVEBUDDY_SHADOW_TOKEN must be set')
    server = ThreadingHTTPServer((args.host, args.port), make_handler(token, args.log))
    print(f'SHADOW ONLY: http://{args.host}:{args.port}/v1/advisory; no car-control functionality', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()

if __name__ == '__main__':
    main()
