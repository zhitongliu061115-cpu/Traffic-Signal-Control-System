import argparse
import importlib.util
import sys
import unittest
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[2]
GENERATOR_PATH = ROOT_DIR / "scripts" / "build_cityflow_from_real_map.py"
SPEC = importlib.util.spec_from_file_location("build_cityflow_from_real_map", GENERATOR_PATH)
generator = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = generator
SPEC.loader.exec_module(generator)


class RealMapGeneratorTest(unittest.TestCase):
    def test_cross_network_generates_jinan_style_control_fields(self):
        payload = {
            "sceneId": "unit_cross",
            "coordinateSystem": "cartesian",
            "intersections": [
                {"id": "W_IN", "x": -100.0, "y": 0.0, "virtual": True},
                {"id": "E_OUT", "x": 100.0, "y": 0.0, "virtual": True},
                {"id": "S_IN", "x": 0.0, "y": -100.0, "virtual": True},
                {"id": "N_OUT", "x": 0.0, "y": 100.0, "virtual": True},
                {"id": "C", "x": 0.0, "y": 0.0, "signalized": True},
            ],
            "edges": [
                {"from": "W_IN", "to": "C", "bidirectional": True, "laneCount": 3},
                {"from": "C", "to": "E_OUT", "bidirectional": True, "laneCount": 3},
                {"from": "S_IN", "to": "C", "bidirectional": True, "laneCount": 3},
                {"from": "C", "to": "N_OUT", "bidirectional": True, "laneCount": 3},
            ],
        }
        args = argparse.Namespace(
            fetch_amap=False,
            amap_key=None,
            right_turn_policy="always",
            flow_interval=3.0,
            flow_start=0.0,
            flow_end=60.0,
            scene_id="unit_cross",
        )

        roadnet, flows, report = generator.build(payload, args)

        self.assertTrue(report.ok, report.errors)
        self.assertEqual(5, len(roadnet["intersections"]))
        self.assertEqual(8, len(roadnet["roads"]))

        center = next(item for item in roadnet["intersections"] if item["id"] == "C")
        self.assertFalse(center["virtual"])
        self.assertEqual(12, len(center["roadLinks"]))
        self.assertEqual(list(range(12)), center["trafficLight"]["roadLinkIndices"])
        self.assertEqual(9, len(center["trafficLight"]["lightphases"]))

        first_link = center["roadLinks"][0]
        self.assertIn(first_link["type"], {"go_straight", "turn_left", "turn_right"})
        self.assertEqual(3, len(first_link["laneLinks"]))
        self.assertEqual(11, len(first_link["laneLinks"][0]["points"]))

        business_phase_sizes = [
            len(center["trafficLight"]["lightphases"][idx]["availableRoadLinks"])
            for idx in range(1, 5)
        ]
        self.assertTrue(any(size > 0 for size in business_phase_sizes))
        self.assertTrue(flows)
        for flow in flows:
            self.assertGreaterEqual(len(flow["route"]), 2)


if __name__ == "__main__":
    unittest.main()
