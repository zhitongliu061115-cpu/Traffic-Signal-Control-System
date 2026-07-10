from __future__ import annotations

import json
import math
import uuid
from pathlib import Path

from app.config import DEFAULT_FRAME_STEP_SECONDS, DEFAULT_VISIBLE_VEHICLE_LIMIT, ENGINE_MODE, SERVICE_VERSION
from app.engine import RealCityFlowEngine
from app.errors import ApiError
from app.models import JsonDict, SimulationSession
from app.roadnet_parser import PHASE_CODES, RoadnetParser
from app.scene_registry import SceneRegistry


class CityFlowAdapter:
    """Minimal adapter with the same HTTP surface expected by Spring Boot.

    The current implementation uses roadnet/flow files to generate deterministic
    visual frames. Replace internals with a real CityFlow Engine later without
    changing endpoint paths or response DTOs.
    """

    def __init__(self, data_dir: Path):
        self.data_dir = data_dir
        self.engine_mode = self._resolve_engine_mode(ENGINE_MODE)
        self.scene_registry = SceneRegistry(data_dir)
        self.real_engine = RealCityFlowEngine(self.scene_registry) if self.engine_mode == "cityflow" else None
        self.sessions: dict[str, SimulationSession] = {}
        self.parsers: dict[str, RoadnetParser] = {}
        self.flows: dict[str, list[JsonDict]] = {}
        self.road_index: dict[str, dict[str, JsonDict]] = {}
        for scene_id in self.scene_registry.list_scene_ids():
            self._load_scene(scene_id)

    def health(self) -> JsonDict:
        return {
            "status": "UP",
            "service": "sim-python",
            "version": SERVICE_VERSION,
            "engineMode": self.engine_mode,
            "sceneIds": self.scene_registry.list_scene_ids(),
            "activeSessions": self._active_session_count(),
        }

    def get_roadnet(self, scene_id: str) -> JsonDict:
        return self._parser(scene_id).to_response(scene_id)

    def create_simulation(self, scene_id: str, speed: float | None = None) -> JsonDict:
        self._load_scene(scene_id)
        normalized_speed = self._normalize_speed(speed)
        if self.real_engine is not None:
            return self.real_engine.create_session(scene_id, normalized_speed)

        sid = f"run_{uuid.uuid4().hex[:8]}"
        session = SimulationSession(
            sid=sid,
            scene_id=scene_id,
            speed=normalized_speed,
            engine_mode=self.engine_mode,
        )
        self.sessions[sid] = session
        return {
            "sid": sid,
            "sceneId": scene_id,
            "status": "created",
            "engineMode": self.engine_mode,
        }

    def _session_roadnet(self, sid: str) -> JsonDict:
        """Get roadnet dict for a session (real engine only)."""
        if self.real_engine is not None:
            scene_id = self.real_engine.sessions[sid].scene_id
            self._load_scene(scene_id)
            return self.parsers[scene_id].raw
        return {}

    def _session_scene(self, sid: str) -> str:
        """Get scene_id for a session."""
        if self.real_engine is not None and sid in self.real_engine.sessions:
            return self.real_engine.sessions[sid].scene_id
        if sid in self.sessions:
            return self.sessions[sid].scene_id
        return ""

    def dispatch(self, sid: str, params: JsonDict) -> JsonDict:
        """Dispatch an emergency vehicle with coordinate-based routing."""
        if self.real_engine is not None:
            print(f'[dispatch] sid={sid} scene={self._session_scene(sid)}', flush=True)
            try:
                result = self.real_engine.ev_service.dispatch(
                    sid=sid,
                    scene_id=self._session_scene(sid),
                    roadnet=self._session_roadnet(sid),
                    engine=self.real_engine.sessions[sid].engine,
                    params=params,
                )
                print(f'[dispatch] result={result}', flush=True)
                return result
            except Exception as e:
                import traceback; traceback.print_exc()
                raise
        raise ApiError(
            status=400, code="CITYFLOW_ENGINE_NOT_CONFIGURED",
            message="dispatch requires cityflow engine mode",
            retryable=False,
        )

    def next_frame(self, sid: str) -> JsonDict:
        if self.real_engine is not None:
            return self.real_engine.next_frame(sid)

        if sid not in self.sessions:
            raise ApiError(
                status=404,
                code="SESSION_NOT_FOUND",
                message=f"simulation session not found: {sid}",
                retryable=False,
            )

        session = self.sessions[sid]
        with session.lock:
            session.seq += 1
            session.sim_time += DEFAULT_FRAME_STEP_SECONDS * session.speed

            flows = self.flows[session.scene_id]
            road_by_id = self.road_index[session.scene_id]
            active_flows = self._active_flows(flows, session.sim_time)
            vehicles = [
                self._vehicle_state(index, flow, session.sim_time, road_by_id)
                for index, flow in active_flows[:DEFAULT_VISIBLE_VEHICLE_LIMIT]
            ]
            roads = self._road_states(vehicles, road_by_id)
            intersections = self._intersection_states(session.scene_id, roads)
            signals = self._signal_states(session.scene_id, session.sim_time)
            metrics = self._metrics(vehicles, roads, flows, session.sim_time)

            return {
                "sid": session.sid,
                "sceneId": session.scene_id,
                "seq": session.seq,
                "simTime": round(session.sim_time, 3),
                "engineMode": session.engine_mode,
                "vehicles": vehicles,
                "roads": roads,
                "intersections": intersections,
                "signals": signals,
                "metrics": metrics,
            }

    def apply_control_actions(self, sid: str, payload: JsonDict) -> JsonDict:
        decisions = self._validate_decisions_payload(sid, payload)
        if self.real_engine is not None:
            return self.real_engine.apply_control_actions(sid, decisions)

        if sid not in self.sessions:
            raise ApiError(
                status=404,
                code="SESSION_NOT_FOUND",
                message=f"simulation session not found: {sid}",
                retryable=False,
            )

        return {
            "sid": sid,
            "applied": [self._applied_action(decision) for decision in decisions],
        }

    def _load_scene(self, scene_id: str) -> None:
        scene = self.scene_registry.get(scene_id)

        if scene_id not in self.parsers:
            parser = RoadnetParser(scene.roadnet_file)
            self.parsers[scene_id] = parser
            self.road_index[scene_id] = parser.road_by_id()
        if scene_id not in self.flows:
            with scene.flow_file.open("r", encoding="utf-8") as file:
                self.flows[scene_id] = json.load(file)

    def _parser(self, scene_id: str) -> RoadnetParser:
        self._load_scene(scene_id)
        return self.parsers[scene_id]

    def _resolve_engine_mode(self, mode: str) -> str:
        if mode == "mock":
            return "mock"
        if mode == "cityflow":
            return "cityflow"
        raise ApiError(
            status=500,
            code="ENGINE_MODE_INVALID",
            message=f"unsupported SIM_ENGINE_MODE: {mode}",
            retryable=False,
        )

    def _normalize_speed(self, speed: float | None) -> float:
        try:
            value = float(speed if speed is not None else 1.0)
        except (TypeError, ValueError) as ex:
            raise ApiError(
                status=400,
                code="INVALID_REQUEST",
                message="speed must be a number",
                retryable=False,
            ) from ex
        if value <= 0:
            raise ApiError(
                status=400,
                code="INVALID_REQUEST",
                message="speed must be greater than 0",
                retryable=False,
            )
        return value

    def _active_session_count(self) -> int:
        if self.real_engine is not None:
            return self.real_engine.active_session_count()
        return len(self.sessions)

    def _validate_decisions_payload(self, sid: str, payload: JsonDict) -> list[JsonDict]:
        decisions = payload.get("decisions", [])
        if not isinstance(decisions, list):
            raise ApiError(
                status=400,
                code="INVALID_REQUEST",
                message="decisions must be a list",
                retryable=False,
            )
        valid_decisions = []
        for decision in decisions:
            if not isinstance(decision, dict):
                raise ApiError(
                    status=400,
                    code="INVALID_REQUEST",
                    message="each control decision must be an object",
                    retryable=False,
                )
            intersection_id = decision.get("intersectionId")
            phase_index = decision.get("phaseIndex")
            if not intersection_id:
                raise ApiError(
                    status=400,
                    code="INVALID_REQUEST",
                    message="control decision intersectionId is required",
                    retryable=False,
                )
            try:
                phase_index = int(phase_index)
            except (TypeError, ValueError) as ex:
                raise ApiError(
                    status=400,
                    code="INVALID_REQUEST",
                    message="control decision phaseIndex must be an integer",
                    retryable=False,
                ) from ex
            if phase_index < 1:
                raise ApiError(
                    status=400,
                    code="INVALID_REQUEST",
                    message="control decision phaseIndex must be greater than or equal to 1",
                    retryable=False,
                )
            valid_decision = dict(decision)
            valid_decision["phaseIndex"] = phase_index
            valid_decisions.append(valid_decision)
        return valid_decisions

    def _applied_action(self, decision: JsonDict) -> JsonDict:
        phase_index = int(decision["phaseIndex"])
        return {
            "intersectionId": decision["intersectionId"],
            "phaseIndex": phase_index,
            "cityflowPhaseId": phase_index - 1,
            "phaseCode": decision.get("phaseCode"),
            "status": "applied",
        }

    def _active_flows(self, flows: list[JsonDict], sim_time: float) -> list[tuple[int, JsonDict]]:
        active = []
        for index, flow in enumerate(flows):
            start_time = float(flow.get("startTime", 0.0))
            route = flow.get("route", [])
            # Keep vehicles visible long enough to traverse all roads in the mocked visual engine.
            duration = max(30.0, len(route) * 18.0)
            if start_time <= sim_time <= start_time + duration:
                active.append((index, flow))
        return active

    def _vehicle_state(
            self,
            index: int,
            flow: JsonDict,
            sim_time: float,
            road_by_id: dict[str, JsonDict],
    ) -> JsonDict:
        route = flow.get("route", [])
        if not route:
            return self._empty_vehicle(index)

        start_time = float(flow.get("startTime", 0.0))
        elapsed = max(0.0, sim_time - start_time)
        seconds_per_road = 18.0
        route_position = min(int(elapsed // seconds_per_road), len(route) - 1)
        road_id = route[route_position]
        progress = (elapsed % seconds_per_road) / seconds_per_road
        road = road_by_id.get(road_id)
        if road is None:
            return self._empty_vehicle(index, road_id)

        start, end = self._road_endpoints(road)
        x = start["x"] + (end["x"] - start["x"]) * progress
        y = start["y"] + (end["y"] - start["y"]) * progress
        lane_count = max(1, len(road.get("lanes", [])))
        lane = index % lane_count
        angle = math.degrees(math.atan2(end["y"] - start["y"], end["x"] - start["x"]))
        max_speed = float(flow.get("vehicle", {}).get("maxSpeed", 11.111))
        speed = max(0.0, min(max_speed, max_speed * (0.65 + 0.35 * math.sin((sim_time + index) / 12.0))))

        return {
            "id": f"vehicle_{index}",
            "roadId": road_id,
            "lane": lane,
            "x": round(x, 3),
            "y": round(y, 3),
            "angle": round(angle, 3),
            "speed": round(speed, 3),
        }

    def _empty_vehicle(self, index: int, road_id: str = "") -> JsonDict:
        return {
            "id": f"vehicle_{index}",
            "roadId": road_id,
            "lane": 0,
            "x": 0.0,
            "y": 0.0,
            "angle": 0.0,
            "speed": 0.0,
        }

    def _road_endpoints(self, road: JsonDict) -> tuple[JsonDict, JsonDict]:
        points = road.get("points", [])
        if len(points) >= 2:
            return points[0], points[-1]
        if len(points) == 1:
            return points[0], points[0]
        return {"x": 0.0, "y": 0.0}, {"x": 0.0, "y": 0.0}

    def _road_states(self, vehicles: list[JsonDict], road_by_id: dict[str, JsonDict]) -> list[JsonDict]:
        grouped: dict[str, list[JsonDict]] = {road_id: [] for road_id in road_by_id}
        for vehicle in vehicles:
            grouped.setdefault(vehicle["roadId"], []).append(vehicle)

        states = []
        for road_id, road_vehicles in grouped.items():
            vehicle_count = len(road_vehicles)
            queue_count = sum(1 for vehicle in road_vehicles if vehicle["speed"] < 1.0)
            avg_speed = sum(vehicle["speed"] for vehicle in road_vehicles) / vehicle_count if vehicle_count else 0.0
            if vehicle_count >= 12 or queue_count >= 5:
                level = "jammed"
            elif vehicle_count >= 5 or avg_speed < 5.0:
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
        parser = self._parser(scene_id)
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
            avg_wait = queue_count * 4.0
            level = "jammed" if queue_count >= 8 else "slow" if vehicle_count >= 8 else "free"
            states.append({
                "id": intersection_id,
                "queueCount": queue_count,
                "avgWait": round(avg_wait, 3),
                "level": level,
            })
        return states

    def _signal_states(self, scene_id: str, sim_time: float) -> list[JsonDict]:
        parser = self._parser(scene_id)
        phases_by_intersection: dict[str, list[int]] = {}
        for phase in parser.to_response(scene_id).get("phases", []):
            phases_by_intersection.setdefault(phase["intersectionId"], []).append(int(phase["phaseIndex"]))

        signals = []
        for intersection_id in parser.real_intersection_ids():
            phase_indexes = phases_by_intersection.get(intersection_id, [1])
            phase_index = phase_indexes[int(sim_time // 10) % len(phase_indexes)]
            signals.append({
                "intersectionId": intersection_id,
                "phaseIndex": phase_index,
                "phaseCode": PHASE_CODES.get(phase_index),
            })
        return signals

    def _metrics(
            self,
            vehicles: list[JsonDict],
            roads: list[JsonDict],
            flows: list[JsonDict],
            sim_time: float,
    ) -> JsonDict:
        vehicle_count = len(vehicles)
        queue_count = sum(road["queueCount"] for road in roads)
        avg_speed = sum(vehicle["speed"] for vehicle in vehicles) / vehicle_count if vehicle_count else 0.0
        throughput = sum(
            1
            for flow in flows
            if float(flow.get("startTime", 0.0)) + max(30.0, len(flow.get("route", [])) * 18.0) < sim_time
        )
        return {
            "vehicleCount": vehicle_count,
            "queueCount": queue_count,
            "avgSpeed": round(avg_speed, 3),
            "avgWait": round(queue_count * 3.0, 3),
            "throughput": throughput,
        }
