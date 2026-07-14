from pathlib import Path
from types import SimpleNamespace
import unittest

from app.engine import RealCityFlowEngine
from app.roadnet_parser import RoadnetParser


DATA_DIR = Path(__file__).resolve().parents[1] / "data" / "jinan_3x4"


class FakeEngine:
    def get_vehicles(self):
        return ["on-lane", "on-link"]

    def get_vehicle_speed(self):
        return {"on-lane": 8.0, "on-link": 6.0}

    def get_vehicle_distance(self):
        return {"on-lane": 4.0, "on-link": 5.0}

    def get_lane_vehicles(self):
        return {"road_with_underscores_2": ["on-lane"]}

    def get_vehicle_info(self, vehicle_id):
        if vehicle_id == "on-link":
            return {
                "drivable": "road_with_underscores_2_TO_next_road_0",
                "distance": 5.0,
                "speed": 6.0,
                "road": "road_with_underscores",
            }
        return {
            "drivable": "road_with_underscores_2",
            "distance": 4.0,
            "speed": 8.0,
            "road": "road_with_underscores",
        }


class VehicleDrivableStateTest(unittest.TestCase):
    def test_roadnet_parser_exposes_cityflow_lane_link_ids(self):
        parser = RoadnetParser(DATA_DIR / "roadnet_3_4.json")
        lane_links = parser.lane_link_by_id()

        self.assertTrue(lane_links)
        lane_link_id, lane_link = next(iter(lane_links.items()))
        self.assertEqual(
            f"{lane_link['fromRoadId']}_{lane_link['startLaneIndex']}_TO_"
            f"{lane_link['toRoadId']}_{lane_link['endLaneIndex']}",
            lane_link_id,
        )
        response_ids = {
            item["id"]
            for road_link in parser.to_response("jinan_3x4")["roadLinks"]
            for item in road_link["laneLinks"]
        }
        self.assertIn(lane_link_id, response_ids)

    def test_vehicle_states_use_current_drivable_geometry(self):
        adapter = RealCityFlowEngine.__new__(RealCityFlowEngine)
        adapter.lane_link_index = {
            "scene": {
                "road_with_underscores_2_TO_next_road_0": {
                    "fromRoadId": "road_with_underscores",
                    "toRoadId": "next_road",
                    "startLaneIndex": 2,
                    "endLaneIndex": 0,
                    "points": [{"x": 10, "y": 0}, {"x": 10, "y": 10}],
                },
            },
        }
        roads = {
            "road_with_underscores": {
                "points": [{"x": 0, "y": 0}, {"x": 10, "y": 0}],
            },
        }
        session = SimpleNamespace(scene_id="scene", engine=FakeEngine())

        lane_vehicle, link_vehicle = adapter._vehicle_states(session, roads)

        self.assertEqual("lane", lane_vehicle["drivableType"])
        self.assertEqual("road_with_underscores", lane_vehicle["roadId"])
        self.assertEqual(2, lane_vehicle["lane"])
        self.assertEqual((4.0, 0.0), (lane_vehicle["x"], lane_vehicle["y"]))

        self.assertEqual("lane_link", link_vehicle["drivableType"])
        self.assertEqual("road_with_underscores", link_vehicle["roadId"])
        self.assertEqual("next_road", link_vehicle["nextRoadId"])
        self.assertEqual(0, link_vehicle["nextLane"])
        self.assertEqual((10.0, 5.0), (link_vehicle["x"], link_vehicle["y"]))
        self.assertEqual(90.0, link_vehicle["angle"])


if __name__ == "__main__":
    unittest.main()
