import unittest
from unittest.mock import patch

from app.ev_service import EVPriorityService, EVSession
from app.ev_priority import ConflictResolver, EVDetector, EVRequest


class MissingVehicleEngine:
    def get_vehicle_info(self, vehicle_id):
        return None


class ExternalVehicleEngine:
    def get_vehicle_info(self, vehicle_id):
        return {
            "road": "sumo_edge_in",
            "distance": 80.0,
            "speed": 10.0,
            "next_intersection": "signalized",
        }

    def get_tl_phase(self, intersection_id):
        return 1


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

    def test_detector_accepts_explicit_intersection_for_sumo_edge_ids(self):
        detection = EVDetector().poll_vehicle(
            "sumo_ev",
            {
                "road": "-259317430#8",
                "distance": 80.0,
                "speed": 10.0,
                "next_intersection": "sumo_tls",
            },
            current_time=12.0,
            is_detected_ev=True,
            road_length=100.0,
        )

        self.assertEqual("sumo_tls", detection["intersection_id"])
        self.assertEqual(20.0, detection["distance_to_stop"])

    @patch("app.ev_service.EVLogger")
    def test_external_vehicle_uses_precise_turn_phase(self, logger_type):
        service = EVPriorityService()
        roadnet = {
            "intersections": [
                {"id": "start", "virtual": True},
                {
                    "id": "signalized",
                    "virtual": False,
                    "roadLinks": [
                        {"startRoad": "sumo_edge_in", "endRoad": "sumo_edge_out", "type": "turn_left"}
                    ],
                    "trafficLight": {
                        "lightphases": [
                            {"availableRoadLinks": []},
                            {"availableRoadLinks": []},
                            {"availableRoadLinks": []},
                            {"availableRoadLinks": [0]},
                            {"availableRoadLinks": []},
                        ]
                    },
                },
                {"id": "end", "virtual": True},
            ],
            "roads": [
                {
                    "id": "sumo_edge_in",
                    "startIntersection": "start",
                    "endIntersection": "signalized",
                    "length": 100.0,
                    "points": [{"x": 0.0, "y": 0.0}, {"x": 100.0, "y": 0.0}],
                },
                {
                    "id": "sumo_edge_out",
                    "startIntersection": "signalized",
                    "endIntersection": "end",
                    "length": 100.0,
                    "points": [{"x": 100.0, "y": 0.0}, {"x": 100.0, "y": 100.0}],
                },
            ],
        }
        service.register_external_vehicle(
            sid="sumo_run",
            roadnet=roadnet,
            ev_id="ambulance_1",
            vehicle_id="sumo_ambulance_1",
            route=["start", "signalized", "end"],
            route_roads=["sumo_edge_in", "sumo_edge_out"],
            sim_time=0.0,
            ev_type="ambulance",
            priority=1,
        )

        overrides, events, _ = service.step("sumo_run", ExternalVehicleEngine(), 1.0)

        self.assertEqual(4, overrides["signalized"])
        self.assertEqual("force_green", events[0]["decision"])
        self.assertEqual(2, events[0]["phaseIndexBefore"])
        self.assertEqual(4, events[0]["phaseIndex"])


if __name__ == "__main__":
    unittest.main()
