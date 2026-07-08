import unittest

from app.cityflow_adapter import CityFlowAdapter
from app.config import DATA_DIR


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
        self.assertIn("vehicles", frame)
        self.assertIn("roads", frame)
        self.assertIn("intersections", frame)
        self.assertIn("signals", frame)
        self.assertIn("metrics", frame)


if __name__ == "__main__":
    unittest.main()
