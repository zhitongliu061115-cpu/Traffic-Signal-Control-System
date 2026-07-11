from __future__ import annotations

import json
import math
import uuid
from pathlib import Path
from threading import RLock

from app.config import (
    AUTO_SIGNAL_CYCLE,
    DEFAULT_FRAME_STEP_SECONDS,
    DEFAULT_VISIBLE_VEHICLE_LIMIT,
    ENGINE_MODE,
    MAX_ACTIVE_SESSIONS,
    MAX_SPEED,
    SESSION_DRAIN_TIMEOUT_SECONDS,
    SERVICE_VERSION,
)
from app.engine import RealCityFlowEngine
from app.errors import ApiError
from app.models import JsonDict, SimulationSession
from app.roadnet_parser import BUSINESS_PHASE_CODE_TO_INDEX, BUSINESS_PHASE_INDEXES, FIRST_BUSINESS_PHASE_INDEX, PHASE_CODES, RoadnetParser
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
        self.sessions_lock = RLock()
        self.parsers: dict[str, RoadnetParser] = {}
        self.flows: dict[str, list[JsonDict]] = {}
        self.road_index: dict[str, dict[str, JsonDict]] = {}
        self.lane_movement_maps: dict[str, dict[str, dict[str, dict[int, str]]]] = {}
        for scene_id in self.scene_registry.list_scene_ids():
            self._load_scene(scene_id)

    def health(self) -> JsonDict:
        return {
            "status": "UP",
            "service": "sim-python",
            "version": SERVICE_VERSION,
            "engineMode": self.engine_mode,
            "autoSignalCycle": AUTO_SIGNAL_CYCLE,
            "maxActiveSessions": MAX_ACTIVE_SESSIONS,
            "maxSpeed": MAX_SPEED,
            "sessionDrainTimeoutSeconds": SESSION_DRAIN_TIMEOUT_SECONDS,
            "sceneIds": self.scene_registry.list_scene_ids(),
            "activeSessions": self._active_session_count(),
        }

    def get_roadnet(self, scene_id: str) -> JsonDict:
        return self._parser(scene_id).to_response(scene_id)

    def create_simulation(
            self,
            scene_id: str,
            speed: float | None = None,
            warmup_seconds: float | None = None,
            owner_id: str = "default",
    ) -> JsonDict:
        self._load_scene(scene_id)
        normalized_speed = self._normalize_speed(speed)
        normalized_warmup_seconds = self._normalize_warmup_seconds(warmup_seconds)
        if self.real_engine is not None:
            return self.real_engine.create_session(scene_id, normalized_speed, normalized_warmup_seconds, owner_id)

        with self.sessions_lock:
            if len(self.sessions) >= MAX_ACTIVE_SESSIONS:
                raise ApiError(
                    status=429,
                    code="SESSION_LIMIT_EXCEEDED",
                    message=f"active simulation sessions reached limit: {MAX_ACTIVE_SESSIONS}",
                    retryable=True,
                )
            sid = f"run_{uuid.uuid4().hex[:16]}"
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

    def next_frame(self, sid: str, owner_id: str = "default") -> JsonDict:
        if self.real_engine is not None:
            return self.real_engine.next_frame(sid, owner_id)

        session = self._mock_session(sid, owner_id)
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
            lane_states = self._lane_states(session.scene_id, vehicles, road_by_id)
            intersections = self._intersection_states(session.scene_id, roads)
            signals = self._signal_states(session.scene_id, session.sim_time)
            metrics = self._metrics(vehicles, roads, flows, session.sim_time)

            status = "finished" if self._mock_simulation_complete(session, active_flows) else "running"
            frame = {
                "sid": session.sid,
                "sceneId": session.scene_id,
                "seq": session.seq,
                "simTime": round(session.sim_time, 3),
                "engineMode": session.engine_mode,
                "status": status,
                "vehicles": vehicles,
                "roads": roads,
                "laneStates": lane_states,
                "intersections": intersections,
                "signals": signals,
                "metrics": metrics,
                "evEvents": [],
                "evStatus": [],
            }
            if status == "finished":
                session.running = False
                session.stopped = True
                with self.sessions_lock:
                    self.sessions.pop(session.sid, None)
            return frame

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

    def dispatch(self, sid: str, params: JsonDict, owner_id: str = "default") -> JsonDict:
        """Dispatch an emergency vehicle with coordinate-based routing."""
        if self.real_engine is not None:
            session = self.real_engine._session(sid, owner_id)
            print(f'[dispatch] sid={sid} scene={self._session_scene(sid)}', flush=True)
            try:
                with session.engine_lock:
                    if session.stopped:
                        raise ApiError(
                            status=409,
                            code="SESSION_STOPPED",
                            message=f"simulation session has already stopped: {sid}",
                            retryable=False,
                        )
                    with self.real_engine.ev_service_lock:
                        result = self.real_engine.ev_service.dispatch(
                            sid=sid,
                            scene_id=session.scene_id,
                            roadnet=self._session_roadnet(sid),
                            engine=session.engine,
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

    def start_simulation(self, sid: str, owner_id: str = "default") -> JsonDict:
        if self.real_engine is not None:
            return self.real_engine.start_session(sid, owner_id)
        session = self._mock_session(sid, owner_id)
        with session.lock:
            session.running = True
            session.stopped = False
        return {"sid": sid, "status": "running"}

    def pause_simulation(self, sid: str, owner_id: str = "default") -> JsonDict:
        if self.real_engine is not None:
            return self.real_engine.pause_session(sid, owner_id)
        session = self._mock_session(sid, owner_id)
        with session.lock:
            session.running = False
        return {"sid": sid, "status": "paused"}

    def stop_simulation(self, sid: str, owner_id: str = "default") -> JsonDict:
        if self.real_engine is not None:
            return self.real_engine.stop_session(sid, owner_id)
        session = self._mock_session(sid, owner_id)
        with session.lock:
            session.running = False
            session.stopped = True
        with self.sessions_lock:
            self.sessions.pop(sid, None)
        return {"sid": sid, "status": "stopped"}

    def apply_control_actions(self, sid: str, payload: JsonDict, owner_id: str = "default") -> JsonDict:
        decisions = self._validate_decisions_payload(sid, payload)
        print(
            f"apply_control_actions sid={sid} received={len(decisions)} "
            f"source={payload.get('source')} simTime={payload.get('simTime')}",
            flush=True,
        )
        if self.real_engine is not None:
            result = self.real_engine.apply_control_actions(sid, decisions, owner_id)
            print(
                f"apply_control_actions sid={sid} applied={len(result.get('applied', []))}",
                flush=True,
            )
            return result

        if sid not in self.sessions:
            raise ApiError(
                status=404,
                code="SESSION_NOT_FOUND",
                message=f"simulation session not found: {sid}",
                retryable=False,
            )

        result = {
            "sid": sid,
            "applied": [self._applied_action(decision) for decision in decisions],
        }
        print(
            f"apply_control_actions sid={sid} applied={len(result.get('applied', []))}",
            flush=True,
        )
        return result

    def _load_scene(self, scene_id: str) -> None:
        scene = self.scene_registry.get(scene_id)

        if scene_id not in self.parsers:
            parser = RoadnetParser(scene.roadnet_file)
            self.parsers[scene_id] = parser
            self.road_index[scene_id] = parser.road_by_id()
            self.lane_movement_maps[scene_id] = parser.lane_movement_map()
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
        if value > MAX_SPEED:
            raise ApiError(
                status=400,
                code="INVALID_REQUEST",
                message=f"speed must be less than or equal to {MAX_SPEED}",
                retryable=False,
            )
        return value

    def _normalize_warmup_seconds(self, warmup_seconds: float | None) -> float:
        try:
            value = float(warmup_seconds if warmup_seconds is not None else 0.0)
        except (TypeError, ValueError) as ex:
            raise ApiError(
                status=400,
                code="INVALID_REQUEST",
                message="warmupSeconds must be a number",
                retryable=False,
            ) from ex
        if value < 0:
            raise ApiError(
                status=400,
                code="INVALID_REQUEST",
                message="warmupSeconds must be greater than or equal to 0",
                retryable=False,
            )
        return min(value, 600.0)

    def _active_session_count(self) -> int:
        if self.real_engine is not None:
            return self.real_engine.active_session_count()
        with self.sessions_lock:
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

    def _mock_session(self, sid: str, owner_id: str = "default") -> SimulationSession:
        with self.sessions_lock:
            session = self.sessions.get(sid)
        if session is None:
            raise ApiError(
                status=404,
                code="SESSION_NOT_FOUND",
                message=f"simulation session not found: {sid}",
                retryable=False,
            )
        return session

    def _mock_simulation_complete(
            self,
            session: SimulationSession,
            active_flows: list[tuple[int, JsonDict]],
    ) -> bool:
        flows = self.flows[session.scene_id]
        completion_time = max(
            (
                float(flow.get("startTime", 0.0))
                + max(30.0, len(flow.get("route", [])) * 18.0)
                for flow in flows
            ),
            default=0.0,
        )
        flow_end_time = self.scene_registry.get(session.scene_id).flow_end_time
        return (
            session.sim_time >= completion_time and not active_flows
        ) or session.sim_time >= flow_end_time + SESSION_DRAIN_TIMEOUT_SECONDS

    def _applied_action(self, decision: JsonDict) -> JsonDict:
        phase_index = self._normalize_control_phase(decision)
        return {
            "intersectionId": decision["intersectionId"],
            "phaseIndex": phase_index,
            "cityflowPhaseId": phase_index - 1,
            "phaseCode": PHASE_CODES.get(phase_index) or decision.get("phaseCode"),
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

    def _lane_states(
            self,
            scene_id: str,
            vehicles: list[JsonDict],
            road_by_id: dict[str, JsonDict],
    ) -> JsonDict:
        states = self._empty_lane_states(self._parser(scene_id))
        movement_map = self.lane_movement_maps.get(scene_id, {})
        for vehicle in vehicles:
            road_id = vehicle.get("roadId")
            road = road_by_id.get(road_id)
            if road is None:
                continue
            intersection_id = road.get("endIntersection")
            lane_index = int(vehicle.get("lane", 0))
            lane_code = movement_map.get(intersection_id, {}).get(road_id, {}).get(lane_index)
            if lane_code is None:
                continue
            lane_state = states[intersection_id]["lanes"][lane_code]
            if float(vehicle.get("speed", 0.0)) < 1.0:
                lane_state["queue_len"] += 1
            else:
                distance_from_start = self._distance_from_start(road, vehicle)
                segment_index = self._segment_index(self._road_length(road), distance_from_start)
                lane_state["cells"][segment_index] += 1
        for intersection_state in states.values():
            for lane_state in intersection_state["lanes"].values():
                lane_state["avg_wait_time"] = round(lane_state["queue_len"] * 3.0, 3)
        return states

    def _empty_lane_states(self, parser: RoadnetParser) -> JsonDict:
        lane_codes = ["WT", "WL", "ST", "SL", "ET", "EL", "NT", "NL"]
        return {
            intersection_id: {
                "lanes": {
                    lane_code: {
                        "queue_len": 0,
                        "avg_wait_time": 0.0,
                        "cells": [0, 0, 0, 0],
                    }
                    for lane_code in lane_codes
                }
            }
            for intersection_id in parser.real_intersection_ids()
        }

    def _segment_index(self, road_length: float, distance_from_start: float) -> int:
        if road_length <= 0:
            return 0
        remaining = max(0.0, min(road_length, road_length - distance_from_start))
        segment_length = road_length / 4.0
        return min(3, max(0, int(remaining / segment_length)))

    def _distance_from_start(self, road: JsonDict, vehicle: JsonDict) -> float:
        points = road.get("points", [])
        if len(points) < 2:
            return 0.0
        start = points[0]
        end = points[-1]
        sx, sy = float(start.get("x", 0.0)), float(start.get("y", 0.0))
        ex, ey = float(end.get("x", 0.0)), float(end.get("y", 0.0))
        vx, vy = float(vehicle.get("x", 0.0)), float(vehicle.get("y", 0.0))
        dx, dy = ex - sx, ey - sy
        length_squared = dx * dx + dy * dy
        if length_squared <= 0:
            return 0.0
        ratio = max(0.0, min(1.0, ((vx - sx) * dx + (vy - sy) * dy) / length_squared))
        return self._road_length(road) * ratio

    def _road_length(self, road: JsonDict) -> float:
        points = road.get("points", [])
        total = 0.0
        for start, end in zip(points, points[1:]):
            total += math.dist(
                (float(start.get("x", 0.0)), float(start.get("y", 0.0))),
                (float(end.get("x", 0.0)), float(end.get("y", 0.0))),
            )
        return total

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
            phase_indexes = phases_by_intersection.get(intersection_id, [FIRST_BUSINESS_PHASE_INDEX])
            controllable_phase_indexes = [phase_index for phase_index in phase_indexes if phase_index in BUSINESS_PHASE_INDEXES]
            cycle_phase_indexes = controllable_phase_indexes or phase_indexes
            phase_index = cycle_phase_indexes[int(sim_time // 10) % len(cycle_phase_indexes)]
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
        scheduled_departure_count = sum(
            1
            for flow in flows
            if float(flow.get("startTime", 0.0)) <= sim_time
        )
        return {
            "vehicleCount": vehicle_count,
            "activeVehicleCount": vehicle_count,
            "scheduledDepartureCount": scheduled_departure_count,
            "queueCount": queue_count,
            "avgSpeed": round(avg_speed, 3),
            "avgWait": round(queue_count * 3.0, 3),
            "throughput": throughput,
        }

    def _normalize_control_phase(self, decision: JsonDict) -> int:
        phase_code = decision.get("phaseCode")
        if isinstance(phase_code, str) and phase_code in BUSINESS_PHASE_CODE_TO_INDEX:
            return BUSINESS_PHASE_CODE_TO_INDEX[phase_code]

        phase_index = int(decision["phaseIndex"])
        if phase_index in BUSINESS_PHASE_INDEXES:
            return phase_index

        legacy_business_index_to_cityflow_phase = {
            1: BUSINESS_PHASE_CODE_TO_INDEX["ETWT"],
            2: BUSINESS_PHASE_CODE_TO_INDEX["NTST"],
            3: BUSINESS_PHASE_CODE_TO_INDEX["ELWL"],
            4: BUSINESS_PHASE_CODE_TO_INDEX["NLSL"],
        }
        if phase_index in legacy_business_index_to_cityflow_phase:
            return legacy_business_index_to_cityflow_phase[phase_index]
        return phase_index
