import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from receiver import validate

class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.now = int(time.time() * 1000)
        self.payload = dict(schema='drivebuddy.speed_advisory.v1', mode='shadow_only',
                            timestampMs=self.now, targetSpeedKph=60, source='collected')

    def test_valid_shadow_advisory_is_never_applied(self):
        result = validate(self.payload, self.now)
        self.assertTrue(result['accepted'])
        self.assertFalse(result['appliedToVehicle'])

    def test_reject_non_shadow_mode(self):
        self.payload['mode'] = 'control'
        with self.assertRaises(ValueError):
            validate(self.payload, self.now)

    def test_reject_stale_data(self):
        self.payload['timestampMs'] -= 11000
        with self.assertRaises(ValueError):
            validate(self.payload, self.now)

    def test_reject_bad_speeds(self):
        for speed in [None, True, -1, 250, 60.5, '60']:
            with self.subTest(speed=speed), self.assertRaises(ValueError):
                validate({**self.payload, 'targetSpeedKph': speed}, self.now)

    def test_reject_unknown_source(self):
        with self.assertRaises(ValueError):
            validate({**self.payload, 'source': 'other'}, self.now)

if __name__ == '__main__':
    unittest.main()
