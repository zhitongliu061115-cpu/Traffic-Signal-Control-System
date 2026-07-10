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

    def test_sessions_are_isolated_by_owner(self):
        adapter = CityFlowAdapter(DATA_DIR)
        alice_old = adapter.create_simulation("jinan_3x4", 1.0, owner_id="alice")
        bob = adapter.create_simulation("jinan_3x4", 1.0, owner_id="bob")
        alice_new = adapter.create_simulation("jinan_3x4", 1.0, owner_id="alice")

        with self.assertRaises(ApiError) as context:
            adapter.next_frame(alice_old["sid"], owner_id="alice")
        self.assertEqual(404, context.exception.status)

        self.assertEqual(1, adapter.next_frame(bob["sid"], owner_id="bob")["seq"])
        self.assertEqual(1, adapter.next_frame(alice_new["sid"], owner_id="alice")["seq"])

        with self.assertRaises(ApiError) as owner_context:
            adapter.next_frame(bob["sid"], owner_id="alice")
        self.assertEqual(403, owner_context.exception.status)

    def test_same_session_frame_sequence_is_thread_safe(self):
        adapter = CityFlowAdapter(DATA_DIR)
        session = adapter.create_simulation("jinan_3x4", 1.0)

        with ThreadPoolExecutor(max_workers=4) as executor:
            frames = list(executor.map(lambda _: adapter.next_frame(session["sid"]), range(8)))

        self.assertEqual(list(range(1, 9)), sorted(frame["seq"] for frame in frames))
        self.assertEqual(8.0, max(frame["simTime"] for frame in frames))


if __name__ == "__main__":
    unittest.main()
