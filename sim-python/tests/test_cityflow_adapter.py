import unittest
from concurrent.futures import ThreadPoolExecutor

from app.cityflow_adapter import CityFlowAdapter
from app.config import DATA_DIR
from app.errors import ApiError


class CityFlowAdapterTest(unittest.TestCase):
    def test_get_roadnet(self):
        adapter = CityFlowAdapter(DATA_DIR)
        roadnet = adapter.get_roadnet("jinan_3x4")

        self.assertEqual("jinan_3x4", roadnet["sceneId"])
        self.assertGreater(len(roadnet["intersections"]), 0)
        self.assertGreater(len(roadnet["roads"]), 0)
        self.assertIn("roadLinks", roadnet)
        self.assertIn("phases", roadnet)
        self.assertEqual(1, roadnet["phases"][0]["phaseIndex"])
        self.assertEqual(4.0, roadnet["roads"][0]["lanes"][0]["width"])
        lane_links = [
            lane_link
            for road_link in roadnet["roadLinks"]
            for lane_link in road_link["laneLinks"]
        ]
        self.assertGreater(len(lane_links), 0)
        self.assertGreater(len(lane_links[0]["points"]), 1)

    def test_create_simulation_and_next_frame(self):
        adapter = CityFlowAdapter(DATA_DIR)
        session = adapter.create_simulation("jinan_3x4", 1.0)
        frame = adapter.next_frame(session["sid"])

        self.assertEqual("created", session["status"])
        self.assertEqual("mock", session["engineMode"])
        self.assertEqual(1, frame["seq"])
        self.assertEqual(1.0, frame["simTime"])
        self.assertEqual("mock", frame["engineMode"])
        self.assertIn("vehicles", frame)
        self.assertIn("roads", frame)
        self.assertIn("intersections", frame)
        self.assertIn("signals", frame)
        self.assertIn("metrics", frame)

    def test_apply_control_actions_accepts_unified_decision_shape(self):
        adapter = CityFlowAdapter(DATA_DIR)
        session = adapter.create_simulation("jinan_3x4", 1.0)

        result = adapter.apply_control_actions(session["sid"], {
            "source": "fixed-time",
            "simTime": 10.0,
            "decisions": [
                {
                    "intersectionId": "intersection_1_1",
                    "controllerType": "fixed-time",
                    "phaseIndex": 2,
                    "phaseCode": "NTST",
                    "durationSec": 10,
                    "confidence": 1.0,
                    "reason": "test decision",
                    "metadata": {"cycleIndex": 1},
                }
            ],
        })

        self.assertEqual(session["sid"], result["sid"])
        self.assertEqual(1, len(result["applied"]))
        self.assertEqual("intersection_1_1", result["applied"][0]["intersectionId"])
        self.assertEqual("NTST", result["applied"][0]["phaseCode"])
        self.assertEqual(3, result["applied"][0]["phaseIndex"])
        self.assertEqual(2, result["applied"][0]["cityflowPhaseId"])
        self.assertEqual("applied", result["applied"][0]["status"])

    def test_mock_control_action_updates_reported_signal_phase(self):
        adapter = CityFlowAdapter(DATA_DIR)
        created = adapter.create_simulation("jinan_3x4", 1.0)

        adapter.apply_control_actions(created["sid"], {
            "decisions": [{
                "intersectionId": "intersection_1_1",
                "phaseIndex": 3,
                "phaseCode": "NTST",
            }],
        })
        frame = adapter.next_frame(created["sid"])
        signal = next(item for item in frame["signals"] if item["intersectionId"] == "intersection_1_1")

        self.assertEqual(3, signal["phaseIndex"])
        self.assertEqual("NTST", signal["phaseCode"])
        self.assertNotIn("remainingSec", signal)

    def test_mock_signal_reports_authoritative_remaining_duration(self):
        adapter = CityFlowAdapter(DATA_DIR)
        created = adapter.create_simulation("jinan_3x4", 1.0)
        session = adapter._mock_session(created["sid"])
        session.sim_time = 4.0

        decision = {
            "intersectionId": "intersection_1_1",
            "phaseIndex": 3,
            "phaseCode": "NTST",
            "durationSec": 12,
        }
        adapter.apply_control_actions(created["sid"], {"decisions": [decision]})
        first = adapter.next_frame(created["sid"])
        first_signal = next(item for item in first["signals"] if item["intersectionId"] == "intersection_1_1")

        self.assertEqual(11.0, first_signal["remainingSec"])
        self.assertEqual(4.0, first_signal["phaseStartedAt"])
        self.assertEqual(12.0, first_signal["appliedDurationSec"])

        adapter.apply_control_actions(created["sid"], {"decisions": [decision]})
        second = adapter.next_frame(created["sid"])
        second_signal = next(item for item in second["signals"] if item["intersectionId"] == "intersection_1_1")

        self.assertEqual(10.0, second_signal["remainingSec"])
        self.assertEqual(4.0, second_signal["phaseStartedAt"])

    def test_mock_vehicle_uses_route_lane_and_waits_at_red_signal(self):
        adapter = CityFlowAdapter(DATA_DIR)
        created = adapter.create_simulation("jinan_3x4", 1.0)
        session = adapter._mock_session(created["sid"])
        flow_index, flow = next(
            (index, item)
            for index, item in enumerate(adapter.flows["jinan_3x4"])
            if any(
                current == "road_1_2_3" and following == "road_1_1_3"
                for current, following in zip(item["route"], item["route"][1:])
            )
        )
        approach_index = flow["route"].index("road_1_2_3")
        # Whole-second frames previously skipped the final approach frame.
        session.sim_time = float(flow["startTime"]) + approach_index * 18.0 + 17.0

        stopped = adapter._vehicle_state(flow_index, flow, session, adapter.road_index["jinan_3x4"])

        self.assertEqual("road_1_2_3", stopped["roadId"])
        self.assertEqual(1, stopped["lane"])
        self.assertTrue(stopped["waitingForSignal"])
        self.assertEqual(0.0, stopped["speed"])
        self.assertAlmostEqual(776.0, stopped["distance"])

        queue = [dict(stopped, id=f"vehicle_{vehicle_id}") for vehicle_id in (5, 15, 25)]
        adapter._apply_mock_queue_spacing(queue, adapter.road_index["jinan_3x4"])
        self.assertEqual([776.0, 768.0, 760.0], [vehicle["distance"] for vehicle in queue])

        session.current_phases["intersection_1_1"] = 3
        session.sim_time += 1.0
        released = adapter._vehicle_state(flow_index, flow, session, adapter.road_index["jinan_3x4"])

        self.assertFalse(released["waitingForSignal"])
        self.assertGreater(released["speed"], 0.0)
        self.assertGreaterEqual(released["distance"], stopped["distance"])

    def test_apply_control_actions_rejects_invalid_phase(self):
        adapter = CityFlowAdapter(DATA_DIR)
        session = adapter.create_simulation("jinan_3x4", 1.0)

        with self.assertRaises(ApiError) as context:
            adapter.apply_control_actions(session["sid"], {
                "source": "fixed-time",
                "decisions": [
                    {
                        "intersectionId": "intersection_1_1",
                        "phaseIndex": 0,
                    }
                ],
            })

        self.assertEqual(400, context.exception.status)
        self.assertEqual("INVALID_REQUEST", context.exception.code)

    def test_health_exposes_engine_mode_and_scenes(self):
        adapter = CityFlowAdapter(DATA_DIR)
        health = adapter.health()

        self.assertEqual("UP", health["status"])
        self.assertEqual("sim-python", health["service"])
        self.assertEqual("mock", health["engineMode"])
        self.assertIn("jinan_3x4", health["sceneIds"])

    def test_unknown_scene_returns_standard_error(self):
        adapter = CityFlowAdapter(DATA_DIR)

        with self.assertRaises(ApiError) as context:
            adapter.get_roadnet("missing_scene")

        self.assertEqual(404, context.exception.status)
        self.assertEqual("SCENE_NOT_FOUND", context.exception.code)

    def test_unknown_session_returns_standard_error(self):
        adapter = CityFlowAdapter(DATA_DIR)

        with self.assertRaises(ApiError) as context:
            adapter.next_frame("missing_sid")

        self.assertEqual(404, context.exception.status)
        self.assertEqual("SESSION_NOT_FOUND", context.exception.code)

    def test_invalid_speed_is_rejected(self):
        adapter = CityFlowAdapter(DATA_DIR)

        with self.assertRaises(ApiError) as context:
            adapter.create_simulation("jinan_3x4", 0)

        self.assertEqual(400, context.exception.status)
        self.assertEqual("INVALID_REQUEST", context.exception.code)

    def test_multiple_sessions_coexist_without_owner_isolation(self):
        adapter = CityFlowAdapter(DATA_DIR)
        alice = adapter.create_simulation("jinan_3x4", 1.0, owner_id="alice")
        bob = adapter.create_simulation("jinan_3x4", 1.0, owner_id="bob")

        self.assertEqual(1, adapter.next_frame(alice["sid"], owner_id="bob")["seq"])
        self.assertEqual(1, adapter.next_frame(bob["sid"], owner_id="bob")["seq"])
        self.assertEqual(2, adapter.health()["activeSessions"])

    def test_create_simulation_does_not_reject_by_session_count(self):
        adapter = CityFlowAdapter(DATA_DIR)

        created = [adapter.create_simulation("jinan_3x4", 1.0) for _ in range(12)]

        self.assertEqual(12, len({session["sid"] for session in created}))
        self.assertEqual(12, adapter.health()["activeSessions"])

    def test_cleanup_releases_expired_idle_mock_session(self):
        adapter = CityFlowAdapter(DATA_DIR)
        created = adapter.create_simulation("jinan_3x4", 1.0)
        session = adapter._mock_session(created["sid"])
        session.running = False
        session.created_at = 0.0
        session.last_access_at = 0.0

        released = adapter.cleanup_expired_sessions()

        self.assertEqual(1, released)
        self.assertEqual(0, adapter.health()["activeSessions"])

    def test_cleanup_releases_abandoned_running_mock_session(self):
        adapter = CityFlowAdapter(DATA_DIR)
        created = adapter.create_simulation("jinan_3x4", 1.0)
        session = adapter._mock_session(created["sid"])
        session.running = True
        session.last_access_at = 0.0

        released = adapter.cleanup_expired_sessions()

        self.assertEqual(1, released)
        self.assertEqual(0, adapter.health()["activeSessions"])

    def test_stop_releases_mock_session(self):
        adapter = CityFlowAdapter(DATA_DIR)
        session = adapter.create_simulation("jinan_3x4", 1.0)

        self.assertEqual("stopped", adapter.stop_simulation(session["sid"])["status"])
        self.assertEqual(0, adapter.health()["activeSessions"])
        with self.assertRaises(ApiError) as context:
            adapter.next_frame(session["sid"])
        self.assertEqual(404, context.exception.status)

    def test_mock_session_releases_after_natural_completion(self):
        adapter = CityFlowAdapter(DATA_DIR)
        created = adapter.create_simulation("jinan_3x4", 1.0)
        session = adapter._mock_session(created["sid"])
        session.sim_time = 100000.0

        frame = adapter.next_frame(created["sid"])

        self.assertEqual("finished", frame["status"])
        self.assertEqual(0, adapter.health()["activeSessions"])

    def test_same_session_frame_sequence_is_thread_safe(self):
        adapter = CityFlowAdapter(DATA_DIR)
        session = adapter.create_simulation("jinan_3x4", 1.0)

        with ThreadPoolExecutor(max_workers=4) as executor:
            frames = list(executor.map(lambda _: adapter.next_frame(session["sid"]), range(8)))

        self.assertEqual(list(range(1, 9)), sorted(frame["seq"] for frame in frames))
        self.assertEqual(8.0, max(frame["simTime"] for frame in frames))


if __name__ == "__main__":
    unittest.main()
