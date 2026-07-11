import unittest
from unittest.mock import patch

from app.ev_service import EVPriorityService, EVSession
from app.ev_priority import ConflictResolver, EVRequest


class MissingVehicleEngine:
    def get_vehicle_info(self, vehicle_id):
        return None


class EVPriorityServiceTest(unittest.TestCase):
    @patch("app.ev_service.EVLogger")
    def test_completed_ev_releases_all_owned_signal_overrides(self, logger_type):
        service = EVPriorityService()
        sid = "run_test"
        ev = EVSession(
            ev_id="ambulance_1",
            cf_vehicle_id="ev_1",
            seen_in_engine=True,
            missing_since=0.0,
        )
        service.ev_sessions[sid] = {ev.ev_id: ev}
        service.handled[sid] = set()
        service.road_index[sid] = {}
        service.phase_counts[sid] = {}
        service.approach_phases[sid] = {}
        service._active_overrides[sid] = {"intersection_1_1": 2}
        service._override_owners[sid] = {"intersection_1_1": ev.ev_id}

        overrides, _, status = service.step(sid, MissingVehicleEngine(), 2.0)

        self.assertEqual({}, overrides)
        self.assertTrue(status[0]["completed"])
        self.assertFalse(service.has_evs(sid))

    def test_lower_numeric_priority_wins_conflict(self):
        resolver = ConflictResolver()
        resolver.register(EVRequest("low", 3, ["intersection_1_1"], 1.0))
        resolver.register(EVRequest("high", 1, ["intersection_1_1"], 2.0))

        winner = resolver.resolve_at_intersection("intersection_1_1", 3.0)

        self.assertEqual("high", winner.ev_id)


if __name__ == "__main__":
    unittest.main()
