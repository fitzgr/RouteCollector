"""Read-only comma openpilot carState blind-spot snapshot source.

Run only in a test installation that already contains openpilot and opendbc.
Never publishes openpilot messages, writes CAN frames, or changes lane control.
"""
import threading
import time


class CommaCarStateReader:
    """Background reader for openpilot's existing carState telemetry."""

    STALE_AFTER_SECONDS = 2.0

    def __init__(self):
        # Import inside init so the HTTP simulator and tests run on a laptop.
        import openpilot.cereal.messaging as messaging
        from openpilot.common.params import Params
        from opendbc.car.structs import car
        from opendbc.car.toyota.values import ToyotaFlags

        self._messaging = messaging
        self._params = Params()
        self._car = car
        self._bsm_flag = ToyotaFlags.HAS_BSM
        self._lock = threading.Lock()
        self._latest = None
        self._latest_monotonic = None
        self._supported = False
        self._car_name = None
        self._stopped = threading.Event()

    def _detect_capability(self):
        """Mark as supported only after matching the Toyota BSM capability bit."""
        blob = self._params.get("CarParams", block=False)
        if not blob:
            return
        cp = self._messaging.log_from_bytes(blob, self._car.CarParams)
        name = str(cp.carFingerprint)
        supported = "TOYOTA" in name.upper() and bool(int(cp.flags) & int(self._bsm_flag))
        with self._lock:
            self._supported = supported
            self._car_name = name

    def run(self):
        sm = self._messaging.SubMaster(["carState"])
        last_capability_check = 0.0
        while not self._stopped.is_set():
            now = time.monotonic()
            if now - last_capability_check > 5.0:
                try:
                    self._detect_capability()
                except Exception:
                    with self._lock:
                        self._supported = False
                        self._latest = None
                last_capability_check = now
            try:
                sm.update(500)
                if sm.updated["carState"] and sm.alive["carState"] and sm.valid["carState"]:
                    state = sm["carState"]
                    with self._lock:
                        if self._supported:
                            self._latest = {
                                "leftBlindspot": bool(state.leftBlindspot),
                                "rightBlindspot": bool(state.rightBlindspot),
                                "capturedAtMs": int(time.time() * 1000),
                            }
                            self._latest_monotonic = time.monotonic()
            except Exception:
                with self._lock:
                    self._latest = None
                    self._latest_monotonic = None
                self._stopped.wait(0.25)

    def start(self):
        thread = threading.Thread(target=self.run, daemon=True, name="comma-bsm-read-only")
        thread.start()
        self._thread = thread

    def stop(self):
        self._stopped.set()
        if hasattr(self, "_thread"):
            self._thread.join(timeout=2)

    def snapshot(self):
        with self._lock:
            valid = (
                self._supported and
                self._latest is not None and
                self._latest_monotonic is not None and
                time.monotonic() - self._latest_monotonic <= self.STALE_AFTER_SECONDS
            )
            return {
                "schema": "drivebuddy.blindspot_status.v1",
                "mode": "shadow_only",
                "source": "openpilot.carState",
                "appliedToVehicle": False,
                "available": bool(valid),
                "leftBlindspot": self._latest["leftBlindspot"] if valid else None,
                "rightBlindspot": self._latest["rightBlindspot"] if valid else None,
                "capturedAtMs": self._latest["capturedAtMs"] if valid else None,
                "vehicleCapabilityConfirmed": bool(self._supported),
            }
