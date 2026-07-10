from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
import json
import math
import tempfile
import uuid
from pathlib import Path
from threading import Lock

from app.errors import ApiError
from app.ev_service import EVPriorityService
from app.models import JsonDict
from app.roadnet_parser import PHASE_CODES, RoadnetParser
from app.scene_registry import SceneDefinition, SceneRegistry


class SimulationEngine(ABC):
    @abstractmethod
    def create_session(self, scene_id: str, speed: float) -> JsonDict:
        """Create an engine-owned simulation session."""

    @abstractmethod
    def next_frame(self, sid: str) -> JsonDict:
        """Advance one simulation step and return a frame."""


class RealCityFlowEngine(SimulationEngine):
    """Adapter for the real CityFlow Python Engine.

    This class is only instantiated when SIM_ENGINE_MODE=cityflow. The default
    mock mode never imports CityFlow, so Windows-side tests can still run.
    """

    def __init__(self, scene_registry: SceneRegistry):
        try:
            import cityflow
        except ImportError as ex:
            raise ApiError(
                status=500,
                code="CITYFLOW_IMPORT_FAILED",
                message="cityflow package is not available in current Python environment",
                retryable=False,
            ) from ex

        self.cityflow = cityflow
        self.scene_registry = scene_registry
        self.sessions: dict[str, CityFlowEngineSession] = {}
        self.parsers: dict[str, RoadnetParser] = {}
        self.road_index: dict[str, dict[str, JsonDict]] = {}
        self.phase_indexes: dict[str, dict[str, list[int]]] = {}
        self.ev_service = EVPriorityService()

    def active_session_count(self) -> int:
        return len(self.sessions)

    def create_session(self, scene_id: str, speed: float) -> JsonDict:
        scene = self.scene_registry.get(scene_id)
        self._load_scene(scene)
        config_path = self._write_cityflow_config(scene)

        try:
            engine = self.cityflow.Engine(str(config_path), thread_num=1)
        except Exception as ex:
            raise ApiError(
                status=500,
                code="CITYFLOW_ENGINE_CREATE_FAILED",
                message=f"failed to create CityFlow engine for scene {scene_id}: {ex}",
                retryable=False,
            ) from ex

        sid = f"run_{uuid.uuid4().hex[:8]}"
        self.sessions[sid] = CityFlowEngineSession(
            sid=sid,
            scene_id=scene_id,
            speed=speed,
            engine=engine,
            config_path=config_path,
        )
        return {
            "sid": sid,
            "sceneId": scene_id,
            "status": "created",
            "engineMode": "cityflow",
        }

    def next_frame(self, sid: str) -> JsonDict:
        if sid not in self.sessions:
            raise ApiError(
                status=404,
                code="SESSION_NOT_FOUND",
                message=f"simulation session not found: {sid}",
                retryable=False,
            )

        session = self.sessions[sid]
        with session.lock:
            steps = max(1, int(round(session.speed)))
            for _ in range(steps):
                session.engine.next_step()

            session.seq += 1
            sim_time = float(session.engine.get_current_time())
            parser = self.parsers[session.scene_id]
            road_by_id = self.road_index[session.scene_id]
            self._advance_signal_phases(session, sim_time)

            # ---- EV priority: detect and adjust signals ----

            # EV outputs (default empty)
            ev_events: list[dict] = []
            ev_status: list[dict] = []
            if self.ev_service.has_evs(sid):
                ev_overrides, ev_events, ev_status = self.ev_service.step(sid, session.engine, sim_time)
                for inter_id, phase_idx in ev_overrides.items():
                    try:
                        session.engine.set_tl_phase(inter_id, phase_idx - 1)
                        session.current_phases[inter_id] = phase_idx
                    except Exception:
                        pass
            # -------------------------------------------------

            vehicles = self._vehicle_states(session, road_by_id)
            roads = self._road_states(session, vehicles, road_by_id)
            intersections = self._intersection_states(session.scene_id, roads)
            signals = self._signal_states(session.scene_id, session.current_phases)
            metrics = self._metrics(session, vehicles, roads)

            return {
                "sid": session.sid,
                "sceneId": session.scene_id,
                "seq": session.seq,
                "simTime": round(sim_time, 3),
                "engineMode": "cityflow",
                "vehicles": vehicles,
                "roads": roads,
                "intersections": intersections,
                "signals": signals,
                "metrics": metrics,
                "evEvents": ev_events,
                "evStatus": ev_status,
            }

    def apply_control_actions(self, sid: str, decisions: list[JsonDict]) -> JsonDict:
        if sid not in self.sessions:
            raise ApiError(
                status=404,
                code="SESSION_NOT_FOUND",
                message=f"simulation session not found: {sid}",
                retryable=False,
            )

        session = self.sessions[sid]
        applied = []
        with session.lock:
            for decision in decisions:
                intersection_id = decision["intersectionId"]
                phase_index = int(decision["phaseIndex"])
                cityflow_phase_id = phase_index - 1
                try:
                    session.engine.set_tl_phase(intersection_id, cityflow_phase_id)
                except Exception as ex:
                    raise ApiError(
                        status=500,
                        code="CITYFLOW_SET_PHASE_FAILED",
                        message=f"failed to set phase for {intersection_id}: {ex}",
                        retryable=True,
                    ) from ex
                session.current_phases[intersection_id] = phase_index
                applied.append({
                    "intersectionId": intersection_id,
                    "phaseIndex": phase_index,
                    "cityflowPhaseId": cityflow_phase_id,
                    "phaseCode": decision.get("phaseCode"),
                    "status": "applied",
                })
            if applied:
                session.external_control_enabled = True
        return {
            "sid": sid,
            "applied": applied,
        }

    def _load_scene(self, scene: SceneDefinition) -> None:
        if scene.scene_id in self.parsers:
            return
        parser = RoadnetParser(scene.roadnet_file)
        self.parsers[scene.scene_id] = parser
        self.road_index[scene.scene_id] = parser.road_by_id()
        phase_indexes: dict[str, list[int]] = {}
        for phase in parser.to_response(scene.scene_id).get("phases", []):
            phase_indexes.setdefault(phase["intersectionId"], []).append(int(phase["phaseIndex"]))
        self.phase_indexes[scene.scene_id] = phase_indexes

    def _write_cityflow_config(self, scene: SceneDefinition) -> Path:
        scene_dir = scene.roadnet_file.parent.resolve()
        config_dir = Path(tempfile.mkdtemp(prefix=f"cityflow_{scene.scene_id}_"))
        config_path = config_dir / "config.json"
        payload = {
            "interval": 0.2,
            "seed": 0,
            "dir": f"{scene_dir.as_posix()}/",
            "roadnetFile": scene.roadnet_file.name,
            "flowFile": scene.flow_file.name,
            "rlTrafficLight": True,
            "laneChange": False,
            "saveReplay": False,
            "roadnetLogFile": "replay_roadnet.json",
            "replayLogFile": "replay.txt",
        }
        with config_path.open("w", encoding="utf-8") as file:
            json.dump(payload, file, ensure_ascii=False, indent=2)
        return config_path

    def _vehicle_states(
            self,
            session: "CityFlowEngineSession",
            road_by_id: dict[str, JsonDict],
    ) -> list[JsonDict]:
        engine = session.engine
        vehicle_ids = engine.get_vehicles()
        speed_by_vehicle = engine.get_vehicle_speed()
        distance_by_vehicle = engine.get_vehicle_distance()
        lane_by_vehicle = self._vehicle_lane_index(engine.get_lane_vehicles())

        vehicles = []
        for vehicle_id in vehicle_ids:
            lane_id = lane_by_vehicle.get(vehicle_id, "")
            road_id, lane_index = self._split_lane_id(lane_id)
            distance = float(distance_by_vehicle.get(vehicle_id, 0.0))
            x, y, angle = self._position_on_road(road_by_id.get(road_id), distance)
            vehicles.append({
                "id": vehicle_id,
                "roadId": road_id,
                "lane": lane_index,
                "x": round(x, 3),
                "y": round(y, 3),
                "angle": round(angle, 3),
                "speed": round(float(speed_by_vehicle.get(vehicle_id, 0.0)), 3),
            })
        return vehicles

    def _vehicle_lane_index(self, lane_vehicles: dict[str, list[str]]) -> dict[str, str]:
        lane_by_vehicle = {}
        for lane_id, vehicle_ids in lane_vehicles.items():
            for vehicle_id in vehicle_ids:
                lane_by_vehicle[vehicle_id] = lane_id
        return lane_by_vehicle

    def _split_lane_id(self, lane_id: str) -> tuple[str, int]:
        if "_" not in lane_id:
            return lane_id, 0
        road_id, lane_index = lane_id.rsplit("_", 1)
        try:
            return road_id, int(lane_index)
        except ValueError:
            return lane_id, 0

    def _position_on_road(self, road: JsonDict | None, distance: float) -> tuple[float, float, float]:
        if road is None:
            return 0.0, 0.0, 0.0

        points = road.get("points", [])
        if len(points) < 2:
            point = points[0] if points else {"x": 0.0, "y": 0.0}
            return float(point.get("x", 0.0)), float(point.get("y", 0.0)), 0.0

        remaining = max(0.0, distance)
        for start, end in zip(points, points[1:]):
            sx, sy = float(start.get("x", 0.0)), float(start.get("y", 0.0))
            ex, ey = float(end.get("x", 0.0)), float(end.get("y", 0.0))
            segment_length = math.dist((sx, sy), (ex, ey))
            if segment_length == 0:
                continue
            if remaining <= segment_length:
                ratio = remaining / segment_length
                angle = math.degrees(math.atan2(ey - sy, ex - sx))
                return sx + (ex - sx) * ratio, sy + (ey - sy) * ratio, angle
            remaining -= segment_length

        start = points[-2]
        end = points[-1]
        angle = math.degrees(math.atan2(
            float(end.get("y", 0.0)) - float(start.get("y", 0.0)),
            float(end.get("x", 0.0)) - float(start.get("x", 0.0)),
        ))
        return float(end.get("x", 0.0)), float(end.get("y", 0.0)), angle

    def _road_states(
            self,
            session: "CityFlowEngineSession",
            vehicles: list[JsonDict],
            road_by_id: dict[str, JsonDict],
    ) -> list[JsonDict]:
        lane_counts = session.engine.get_lane_vehicle_count()
        lane_waiting_counts = session.engine.get_lane_waiting_vehicle_count()
        vehicle_speeds_by_road: dict[str, list[float]] = {road_id: [] for road_id in road_by_id}
        for vehicle in vehicles:
            vehicle_speeds_by_road.setdefault(vehicle["roadId"], []).append(float(vehicle["speed"]))

        road_vehicle_counts = {road_id: 0 for road_id in road_by_id}
        road_queue_counts = {road_id: 0 for road_id in road_by_id}
        for lane_id, count in lane_counts.items():
            road_id, _ = self._split_lane_id(lane_id)
            road_vehicle_counts[road_id] = road_vehicle_counts.get(road_id, 0) + int(count)
        for lane_id, count in lane_waiting_counts.items():
            road_id, _ = self._split_lane_id(lane_id)
            road_queue_counts[road_id] = road_queue_counts.get(road_id, 0) + int(count)

        states = []
        for road_id in road_by_id:
            vehicle_count = road_vehicle_counts.get(road_id, 0)
            queue_count = road_queue_counts.get(road_id, 0)
            speeds = vehicle_speeds_by_road.get(road_id, [])
            avg_speed = sum(speeds) / len(speeds) if speeds else 0.0
            if vehicle_count >= 12 or queue_count >= 5:
                level = "jammed"
            elif vehicle_count >= 5 or (vehicle_count > 0 and avg_speed < 5.0):
                level = "slow"
            else:
                level = "free"
            states.append({
                "id": road_id,
                "vehicleCount": vehicle_count,
                "queueCount": queue_count,
                "avgSpeed": round(avg_speed, 3),
                "level": level,
            })
        return states

    def _intersection_states(self, scene_id: str, roads: list[JsonDict]) -> list[JsonDict]:
        parser = self.parsers[scene_id]
        road_state_by_id = {road["id"]: road for road in roads}
        states = []
        for intersection_id in parser.real_intersection_ids():
            related_roads = [
                road_state_by_id[road_id]
                for road_id, road in self.road_index[scene_id].items()
                if road.get("endIntersection") == intersection_id and road_id in road_state_by_id
            ]
            queue_count = sum(road["queueCount"] for road in related_roads)
            vehicle_count = sum(road["vehicleCount"] for road in related_roads)
            level = "jammed" if queue_count >= 8 else "slow" if vehicle_count >= 8 else "free"
            states.append({
                "id": intersection_id,
                "queueCount": queue_count,
                "avgWait": round(queue_count * 3.0, 3),
                "level": level,
            })
        return states

    def _signal_states(self, scene_id: str, current_phases: dict[str, int]) -> list[JsonDict]:
        parser = self.parsers[scene_id]
        signals = []
        for intersection_id in parser.real_intersection_ids():
            phase_index = current_phases.get(intersection_id, 1)
            signals.append({
                "intersectionId": intersection_id,
                "phaseIndex": phase_index,
                "phaseCode": PHASE_CODES.get(phase_index),
            })
        return signals

    def _advance_signal_phases(self, session: "CityFlowEngineSession", sim_time: float) -> None:
        if session.external_control_enabled:
            return
        phase_indexes_by_intersection = self.phase_indexes.get(session.scene_id, {})
        for intersection_id, phase_indexes in phase_indexes_by_intersection.items():
            if not phase_indexes:
                continue
            phase_index = phase_indexes[int(sim_time // 10) % len(phase_indexes)]
            session.current_phases[intersection_id] = phase_index
            try:
                session.engine.set_tl_phase(intersection_id, phase_index - 1)
            except Exception:
                # CityFlow 0.1 does not expose get_tl_phase; keep returning the
                # session-recorded phase so visualization remains debuggable.
                continue

    def _metrics(
            self,
            session: "CityFlowEngineSession",
            vehicles: list[JsonDict],
            roads: list[JsonDict],
    ) -> JsonDict:
        vehicle_count = len(vehicles)
        queue_count = sum(road["queueCount"] for road in roads)
        avg_speed = sum(vehicle["speed"] for vehicle in vehicles) / vehicle_count if vehicle_count else 0.0
        return {
            "vehicleCount": int(session.engine.get_vehicle_count()),
            "queueCount": queue_count,
            "avgSpeed": round(avg_speed, 3),
            "avgWait": round(queue_count * 3.0, 3),
            "throughput": 0,
        }


@dataclass
class CityFlowEngineSession:
    sid: str
    scene_id: str
    speed: float
    engine: object
    config_path: Path
    seq: int = 0
    current_phases: dict[str, int] = field(default_factory=dict)
    external_control_enabled: bool = False
    lock: Lock = field(default_factory=Lock, repr=False)
