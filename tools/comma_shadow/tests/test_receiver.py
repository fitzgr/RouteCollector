import json
import sys
import tempfile
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from receiver import make_handler, validate


class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.now = int(time.time() * 1000)
        self.payload = {
            "schema": "drivebuddy.speed_advisory.v2",
            "mode": "shadow_only",
            "timestampMs": self.now,
            "postedSpeedKph": 60,
            "offsetKph": 8,
            "targetSpeedKph": 68,
            "source": "collected",
            "driveId": "12345",
        }

    def test_valid_suggestion_acknowledges_without_vehicle_control(self):
        result = validate(self.payload, self.now)
        self.assertTrue(result["accepted"])
        self.assertFalse(result["appliedToVehicle"])
        self.assertEqual((result["postedSpeedKph"], result["targetSpeedKph"]), (60, 68))

    def test_speed_increase_and_decrease_examples(self):
        for posted, expected in [(40, 48), (60, 68), (80, 88), (100, 108), (110, 118)]:
            with self.subTest(posted=posted):
                result = validate({**self.payload, "postedSpeedKph": posted, "targetSpeedKph": expected}, self.now)
                self.assertEqual(result["targetSpeedKph"], expected)

    def test_reject_non_shadow_mode(self):
        with self.assertRaises(ValueError):
            validate({**self.payload, "mode": "control"}, self.now)

    def test_reject_old_schema(self):
        with self.assertRaises(ValueError):
            validate({**self.payload, "schema": "drivebuddy.speed_advisory.v1"}, self.now)

    def test_reject_stale_data(self):
        with self.assertRaises(ValueError):
            validate({**self.payload, "timestampMs": self.now - 11_000}, self.now)

    def test_reject_missing_or_wrong_offset(self):
        for offset in (None, 0, 9, True, "8"):
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                validate({**self.payload, "offsetKph": offset}, self.now)

    def test_reject_target_that_does_not_match_posted_speed(self):
        with self.assertRaises(ValueError):
            validate({**self.payload, "targetSpeedKph": 60}, self.now)

    def test_reject_bad_speed_types_and_ranges(self):
        for speed in (None, True, -1, 250, 60.5, "60"):
            with self.subTest(speed=speed), self.assertRaises(ValueError):
                validate({**self.payload, "postedSpeedKph": speed}, self.now)

    def test_reject_uncollected_speed_source(self):
        with self.assertRaises(ValueError):
            validate({**self.payload, "source": "osm"}, self.now)

    def test_reject_missing_drive_id(self):
        with self.assertRaises(ValueError):
            validate({**self.payload, "driveId": ""}, self.now)


class HTTPReceiverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.log_path = str(Path(self.temp.name) / "shadow.jsonl")
        self.token = "test-token-at-least-16-chars"
        self.http = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.token, self.log_path))
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()
        self.endpoint = f"http://127.0.0.1:{self.http.server_address[1]}"
        self.payload = {
            "schema": "drivebuddy.speed_advisory.v2",
            "mode": "shadow_only",
            "timestampMs": int(time.time() * 1000),
            "postedSpeedKph": 60,
            "offsetKph": 8,
            "targetSpeedKph": 68,
            "source": "manual_test",
            "driveId": "0",
        }

    def tearDown(self):
        self.http.shutdown()
        self.http.server_close()
        self.thread.join(timeout=3)
        self.temp.cleanup()

    def test_post_then_status_and_log(self):
        request = Request(self.endpoint + "/v1/advisory",
                          data=json.dumps(self.payload).encode("utf-8"),
                          headers={"X-DriveBuddy-Token": self.token, "Content-Type": "application/json"},
                          method="POST")
        with urlopen(request, timeout=3) as response:
            ack = json.loads(response.read())
        self.assertEqual(ack["targetSpeedKph"], 68)
        self.assertFalse(ack["appliedToVehicle"])
        status_request = Request(self.endpoint + "/v1/status",
                                 headers={"X-DriveBuddy-Token": self.token})
        with urlopen(status_request, timeout=3) as response:
            status = json.loads(response.read())
        self.assertEqual(status["lastReceived"]["advisory"]["postedSpeedKph"], 60)
        self.assertFalse(status["appliedToVehicle"])
        self.assertEqual(len(Path(self.log_path).read_text().splitlines()), 1)

    def test_reject_missing_token(self):
        request = Request(self.endpoint + "/v1/advisory",
                          data=json.dumps(self.payload).encode("utf-8"),
                          headers={"Content-Type": "application/json"}, method="POST")
        with self.assertRaises(HTTPError) as error:
            urlopen(request, timeout=3)
        self.assertEqual(error.exception.code, 401)
        self.assertFalse(Path(self.log_path).exists())


if __name__ == "__main__":
    unittest.main()
