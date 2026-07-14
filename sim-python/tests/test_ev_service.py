import unittest
from unittest.mock import patch

from app.ev_service import EVPriorityService, EVSession
from app.ev_priority import ConflictResolver, EVRequest, SignalState, SignalStrategy


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
        service._override_started_at[sid] = {"intersection_1_1": 0.0}

        overrides, _, status = service.step(sid, MissingVehicleEngine(), 2.0)

        self.assertEqual({}, overrides)
        self.assertTrue(status[0]["completed"])
        self.assertTrue(service.has_evs(sid))
        self.assertEqual({}, service._override_started_at[sid])

        service._status_seq[sid] = ev.completion_seq + service.STATUS_LINGER_FRAMES + 1
        self.assertFalse(service.has_evs(sid))

    def test_turn_level_phases_override_permissive_road_level_mapping(self):
        signal = SignalState(
            intersection_id="intersection_2_1",
            current_phase=2,
            phase_count=9,
            phase_durations=[30] * 9,
            approach_phases={"road_1_1_0": list(range(1, 10))},
        )

        decision, _ = SignalStrategy(None).decide(
            t_a=500.0,
            t_d=30.0,
            signal=signal,
            current_time=100.0,
            approach_dir="E",
            pri_green_phases=[4, 6],
            approach_road="road_1_1_0",
        )

        self.assertEqual(SignalStrategy.DECISION_FORCE_GREEN, decision)

    @patch("app.ev_service.EVLogger")
    def test_missing_turn_mapping_does_not_fallback_to_all_phases(self, logger_type):
        service = EVPriorityService()
        approach_phases = {
            "intersection_2_1": {
                "by_road": {"road_1_1_0": list(range(9))},
                "by_turn": {},
            }
        }

        phases = service._get_pri_green_phases(
            "intersection_2_1",
            "road_1_1_0",
            "road_2_1_1",
            approach_phases,
            {"intersection_2_1": 9},
        )

        self.assertEqual([], phases)

    @patch("app.ev_service.EVLogger")
    def test_transition_phase_is_excluded_when_turn_has_green_alternatives(self, logger_type):
        service = EVPriorityService()
        approach_phases = {
            "intersection_2_1": {
                "by_road": {},
                "by_turn": {("road_1_1_0", "road_2_1_3"): [0, 1, 2]},
            }
        }

        phases = service._get_pri_green_phases(
            "intersection_2_1",
            "road_1_1_0",
            "road_2_1_3",
            approach_phases,
            {"intersection_2_1": 9},
        )

        self.assertEqual([1, 2], phases)

    @patch("app.ev_service.EVLogger")
    def test_stale_override_is_released_after_maximum_duration(self, logger_type):
        service = EVPriorityService()
        sid = "run_timeout"
        service._active_overrides[sid] = {"intersection_2_1": 2}
        service._override_owners[sid] = {"intersection_2_1": "ambulance_1"}
        service._override_started_at[sid] = {"intersection_2_1": 0.0}

        service._expire_stale_overrides(sid, 60.0)

        self.assertEqual({}, service._active_overrides[sid])
        self.assertEqual({}, service._override_owners[sid])
        self.assertEqual({}, service._override_started_at[sid])

    def test_lower_numeric_priority_wins_conflict(self):
        resolver = ConflictResolver()
        resolver.register(EVRequest("low", 3, ["intersection_1_1"], 1.0))
        resolver.register(EVRequest("high", 1, ["intersection_1_1"], 2.0))

        winner = resolver.resolve_at_intersection("intersection_1_1", 3.0)

        self.assertEqual("high", winner.ev_id)


if __name__ == "__main__":
    unittest.main()
