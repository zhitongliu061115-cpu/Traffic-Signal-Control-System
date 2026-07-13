import time
import unittest
from types import SimpleNamespace

from app.config import DATA_DIR
from app.sumo_adapter import SumoAdapter, traffic_r_cell_index
from app.sumo_config import load_sumo_runtime_config


R_INTERSECTION = "cluster_1928080334_2687328260_5128988656_5128988685"


class TrafficRStateContractTest(unittest.TestCase):
    def test_sumo_initialization_preserves_native_signal_program(self):
        set_calls = []
        trafficlight = SimpleNamespace(
            getRedYellowGreenState=lambda intersection_id: "GGrr",
            setRedYellowGreenState=lambda *args: set_calls.append(args),
        )
        session = SimpleNamespace(
            parser=SimpleNamespace(
                traffic_light_ids=lambda: ["signalized"],
                phase_for_state=lambda intersection_id, state: (3, "NTST"),
            ),
            connection=SimpleNamespace(trafficlight=trafficlight),
            current_phases={},
        )

        SumoAdapter._initialize_signals(SumoAdapter.__new__(SumoAdapter), session)

        self.assertEqual({"signalized": 3}, session.current_phases)
        self.assertEqual([], set_calls)

    def test_best_emergency_route_compares_all_endpoint_edges(self):
        costs = {
            ("start_bad", "end_bad"): (["start_bad", "end_bad"], 50.0),
            ("start_bad", "end_good"): (["start_bad", "end_good"], 30.0),
            ("start_good", "end_bad"): (["start_good", "end_bad"], 20.0),
            ("start_good", "end_good"): (["start_good", "end_good"], 5.0),
        }
        adapter = SumoAdapter.__new__(SumoAdapter)
        adapter._congestion_aware_route = lambda session, start, end: costs[(start, end)]

        route, cost = adapter._best_emergency_route(
            SimpleNamespace(),
            ["start_bad", "start_good"],
            ["end_bad", "end_good"],
        )

        self.assertEqual(["start_good", "end_good"], route)
        self.assertEqual(5.0, cost)

    def test_emergency_route_avoids_live_congestion(self):
        class Edge:
            def __init__(self, edge_id, length):
                self.edge_id = edge_id
                self.length = length
                self.outgoing = {}

            def getID(self): return self.edge_id
            def getLength(self): return self.length
            def getSpeed(self): return 10.0
            def getFunction(self): return ""
            def allows(self, vehicle_class): return vehicle_class == "passenger"
            def getOutgoing(self): return self.outgoing

        edges = {name: Edge(name, length) for name, length in {
            "start": 1.0, "short_busy": 10.0, "long_free": 20.0, "end": 1.0,
        }.items()}
        edges["start"].outgoing = {edges["short_busy"]: [], edges["long_free"]: []}
        edges["short_busy"].outgoing = {edges["end"]: []}
        edges["long_free"].outgoing = {edges["end"]: []}
        occupancy = {"short_busy": 100, "long_free": 0, "end": 0}
        session = SimpleNamespace(
            parser=SimpleNamespace(net=SimpleNamespace(getEdge=lambda edge_id: edges[edge_id])),
            connection=SimpleNamespace(edge=SimpleNamespace(
                getLastStepVehicleNumber=lambda edge_id: occupancy.get(edge_id, 0),
            )),
        )

        route, _ = SumoAdapter._congestion_aware_route(
            SumoAdapter.__new__(SumoAdapter), session, "start", "end",
        )

        self.assertEqual(["start", "long_free", "end"], route)

    def test_official_cell_boundaries_are_measured_from_intersection(self):
        self.assertEqual(0, traffic_r_cell_index(100.0, 90.0))
        self.assertEqual(1, traffic_r_cell_index(100.0, 89.999))
        self.assertEqual(1, traffic_r_cell_index(100.0, 100.0 - 100.0 / 3.0))
        self.assertEqual(2, traffic_r_cell_index(100.0, 100.0 - 100.0 / 3.0 - 0.001))
        self.assertEqual(2, traffic_r_cell_index(100.0, 100.0 / 3.0))
        self.assertEqual(3, traffic_r_cell_index(100.0, 100.0 / 3.0 - 0.001))

    def test_lane_state_separates_queued_and_approaching_vehicles(self):
        vehicle_ids = ["queued", "near", "middle", "far"]
        speeds = {"queued": 0.1, "near": 5.0, "middle": 5.0, "far": 5.0}
        positions = {"queued": 99.0, "near": 95.0, "middle": 80.0, "far": 20.0}

        class Parser:
            @staticmethod
            def traffic_light_ids():
                return ["intersection"]

            @staticmethod
            def lane_movement_map():
                return {"intersection": {"edge_1": ("NT",)}}

        connection = SimpleNamespace(
            lane=SimpleNamespace(
                getLength=lambda lane_id: 100.0,
                getLastStepVehicleIDs=lambda lane_id: vehicle_ids,
            ),
            vehicle=SimpleNamespace(
                getSpeed=lambda vehicle_id: speeds[vehicle_id],
                getWaitingTime=lambda vehicle_id: 12.0 if vehicle_id == "queued" else 0.0,
                getLanePosition=lambda vehicle_id: positions[vehicle_id],
            ),
        )

        result = SumoAdapter._lane_states(
            SumoAdapter.__new__(SumoAdapter),
            SimpleNamespace(parser=Parser()),
            connection,
        )
        north_through = result["intersection"]["lanes"]["NT"]

        self.assertEqual(1, north_through["queue_len"])
        self.assertEqual(12.0, north_through["avg_wait_time"])
        self.assertEqual([1, 1, 0, 1], north_through["cells"])


@unittest.skipUnless(load_sumo_runtime_config().binary.exists(), "SUMO runtime is not installed")
class SumoAdapterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from app.sumo_adapter import SumoAdapter

        cls.adapter = SumoAdapter(DATA_DIR)

    def test_health_and_roadnet_keep_cityflow_contract(self):
        health = self.adapter.health()
        roadnet = self.adapter.get_roadnet("xian_5x5")

        self.assertEqual("UP", health["status"])
        self.assertEqual("sumo", health["engineMode"])
        self.assertEqual({"trafficR": 9, "maxPressure": 16}, health["strategyCounts"]["xian_5x5"])
        self.assertEqual("xian_5x5", roadnet["sceneId"])
        self.assertEqual(25, len([item for item in roadnet["intersections"] if not item["virtual"]]))
        self.assertGreater(len(roadnet["roads"]), 0)
        self.assertGreater(len(roadnet["roadLinks"]), 0)
        self.assertEqual(25 * 5, len(roadnet["phases"]))
        self.assertGreater(roadnet["intersections"][0]["lng"], 108.0)
        self.assertGreater(roadnet["intersections"][0]["lat"], 34.0)
        self.assertIn("lng", roadnet["roads"][0]["points"][0])
        self.assertTrue(all(road["lanes"] for road in roadnet["roads"]))
        self.assertTrue(any(link["laneLinks"] for link in roadnet["roadLinks"]))
        self.assertTrue(any(
            len(lane_link["points"]) >= 2
            for link in roadnet["roadLinks"]
            for lane_link in link["laneLinks"]
        ))

        ev_roadnet = self.adapter.parsers["xian_5x5"].ev_priority_roadnet()
        signalized = next(item for item in ev_roadnet["intersections"] if not item["virtual"])
        self.assertEqual(5, len(signalized["trafficLight"]["lightphases"]))
        self.assertEqual([], signalized["trafficLight"]["lightphases"][0]["availableRoadLinks"])
        self.assertTrue(all(road["length"] > 0 for road in ev_roadnet["roads"]))

    def test_real_emergency_green_wave_changes_sumo_phase(self):
        created = self.adapter.create_simulation("xian_5x5", 10.0, 0.0)
        sid = created["sid"]
        try:
            dispatched = self.adapter.dispatch(sid, {
                "evId": "EV_GREEN_WAVE_TEST",
                "evType": "ambulance",
                "priority": 1,
                "startIntersection": "2402915337",
                "endIntersection": "cluster_2609207291_5128988665",
                "maxSpeed": 20.0,
            })
            self.assertGreaterEqual(dispatched["totalIntersections"], 2)
            self.adapter.start_simulation(sid)

            changed = None
            deadline = time.monotonic() + 20.0
            while time.monotonic() < deadline and changed is None:
                time.sleep(0.1)
                frame = self.adapter.next_frame(sid)
                for event in frame["evEvents"]:
                    if event["status"] != "granted" or event["phaseIndex"] == event["phaseIndexBefore"]:
                        continue
                    signal = next(
                        item for item in frame["signals"]
                        if item["intersectionId"] == event["intersectionId"]
                    )
                    if signal["phaseIndex"] == event["phaseIndex"]:
                        changed = (event, signal)
                        break

            self.assertIsNotNone(changed, "emergency priority did not change a SUMO signal phase")
            self.assertEqual("force_green", changed[0]["decision"])
        finally:
            try:
                self.adapter.stop_simulation(sid)
            except Exception:
                pass

    def test_real_lifecycle_frame_and_actions(self):
        created = self.adapter.create_simulation("xian_5x5", 5.0, 0.0)
        sid = created["sid"]
        try:
            self.assertEqual("sumo", created["engineMode"])
            initial = self.adapter.next_frame(sid)
            self.assertEqual(25, len(initial["signals"]))
            self.assertEqual(25, len(initial["laneStates"]))
            self.assertEqual(set(("WT", "WL", "ST", "SL", "ET", "EL", "NT", "NL")),
                             set(initial["laneStates"][R_INTERSECTION]["lanes"]))

            applied = self.adapter.apply_control_actions(sid, {
                "source": "traffic-r",
                "simTime": initial["simTime"],
                "decisions": [{
                    "intersectionId": R_INTERSECTION,
                    "phaseIndex": 1,
                    "phaseCode": "NLSL",
                    "durationSec": 10,
                }],
            })
            self.assertEqual(5, applied["applied"][0]["phaseIndex"])
            self.assertEqual(4, applied["applied"][0]["cityflowPhaseId"])

            self.assertEqual("running", self.adapter.start_simulation(sid)["status"])
            time.sleep(0.25)
            running = self.adapter.next_frame(sid)
            self.assertGreater(running["simTime"], 0.0)
            self.assertEqual("sumo", running["engineMode"])
            self.assertIn("vehicles", running)
            self.assertIn("roads", running)
            self.assertIn("intersections", running)
            self.assertIn("metrics", running)
            if running["vehicles"]:
                self.assertIn("lng", running["vehicles"][0])
                self.assertIn("lat", running["vehicles"][0])
            signal = next(item for item in running["signals"] if item["intersectionId"] == R_INTERSECTION)
            self.assertEqual("NLSL", signal["phaseCode"])
        finally:
            try:
                self.adapter.stop_simulation(sid)
            except Exception:
                pass


if __name__ == "__main__":
    unittest.main()
