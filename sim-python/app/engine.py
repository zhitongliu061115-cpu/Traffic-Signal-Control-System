from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
import json
import math
import tempfile
import time
import uuid
from pathlib import Path
from threading import Lock, Thread

from app.config import (
    AUTO_SIGNAL_CYCLE,
    DEFAULT_MIN_REALTIME_TICK_SECONDS,
    DEFAULT_REALTIME_TICK_SECONDS,
    DEFAULT_VISIBLE_VEHICLE_LIMIT,
    MAX_ACTIVE_SESSIONS,
)
from app.errors import ApiError
from app.ev_service import EVPriorityService
from app.models import JsonDict
from app.roadnet_parser import BUSINESS_PHASE_CODE_TO_INDEX, BUSINESS_PHASE_INDEXES, FIRST_BUSINESS_PHASE_INDEX, PHASE_CODES, RoadnetParser
from app.scene_registry import SceneDefinition, SceneRegistry


class SimulationEngine(ABC):
    @abstractmethod
    def create_session(
            self,
            scene_id: str,
            speed: float,
            warmup_seconds: float = 0.0,
            owner_id: str = "default",
    ) -> JsonDict:
        """Create an engine-owned simulation session."""

    @abstractmethod
    def next_frame(self, sid: str, owner_id: str = "default") -> JsonDict:
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
        self.lane_movement_maps: dict[str, dict[str, dict[str, dict[int, str]]]] = {}
        self.ev_service = EVPriorityService()

    def active_session_count(self) -> int:
        return len(self.sessions)

    def destroy_all_sessions(self) -> None:
        old_sessions = list(self.sessions.values())
        self._stop_and_join_sessions(old_sessions)
        self.sessions.clear()

    def destroy_sessions_for_owner(self, owner_id: str) -> None:
        old_sessions = [
            session for session in self.sessions.values()
            if session.owner_id == owner_id
        ]
        self._stop_and_join_sessions(old_sessions)
        for session in old_sessions:
            self.sessions.pop(session.sid, None)

    def _stop_and_join_sessions(self, old_sessions: list["CityFlowEngineSession"]) -> None:
        for session in old_sessions:
            with session.state_lock:
                session.running = False
                session.stopped = True
        for session in old_sessions:
            worker = session.worker
            if worker is not None and worker.is_alive():
                worker.join(timeout=1.0)

    def create_session(
            self,
            scene_id: str,
            speed: float,
            warmup_seconds: float = 0.0,
            owner_id: str = "default",
    ) -> JsonDict:
        self.destroy_sessions_for_owner(owner_id)
        if len(self.sessions) >= MAX_ACTIVE_SESSIONS:
            raise ApiError(
                status=429,
                code="SESSION_LIMIT_EXCEEDED",
                message=f"active simulation sessions reached limit: {MAX_ACTIVE_SESSIONS}",
                retryable=True,
            )
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
            owner_id=owner_id,
            total_vehicle_count=scene.total_vehicle_count,
            engine=engine,
            config_path=config_path,
        )
        self._initialize_signal_phases(self.sessions[sid])
        if warmup_seconds > 0:
            self._warmup_session(self.sessions[sid], warmup_seconds)
        self._refresh_latest_frame(self.sessions[sid], advance=False)
        return {
            "sid": sid,
            "sceneId": scene_id,
            "status": "created",
            "engineMode": "cityflow",
        }

    def start_session(self, sid: str, owner_id: str = "default") -> JsonDict:
        session = self._session(sid, owner_id)
        with session.state_lock:
            if session.stopped:
                raise ApiError(
                    status=409,
                    code="SESSION_STOPPED",
                    message=f"simulation session has already stopped: {sid}",
                    retryable=False,
                )
            session.running = True
            if session.worker is None or not session.worker.is_alive():
                session.worker = Thread(
                    target=self._run_session_loop,
                    args=(session,),
                    name=f"cityflow-session-{sid}",
                    daemon=True,
                )
                session.worker.start()
        return {"sid": sid, "status": "running"}

    def pause_session(self, sid: str, owner_id: str = "default") -> JsonDict:
        session = self._session(sid, owner_id)
        with session.state_lock:
            session.running = False
        return {"sid": sid, "status": "paused"}

    def stop_session(self, sid: str, owner_id: str = "default") -> JsonDict:
        session = self._session(sid, owner_id)
        with session.state_lock:
            session.running = False
            session.stopped = True
        return {"sid": sid, "status": "stopped"}

    def next_frame(self, sid: str, owner_id: str = "default") -> JsonDict:
        session = self._session(sid, owner_id)
        with session.frame_lock:
            if session.latest_frame is not None:
                return session.latest_frame

        return self._refresh_latest_frame(session, advance=False)

    def apply_control_actions(self, sid: str, decisions: list[JsonDict], owner_id: str = "default") -> JsonDict:
        session = self._session(sid, owner_id)
        applied = []
        with session.engine_lock:
            for decision in decisions:
                intersection_id = decision["intersectionId"]
                phase_index = self._normalize_control_phase(decision)
                valid_phase_indexes = self.phase_indexes.get(session.scene_id, {}).get(intersection_id, [])
                if phase_index not in valid_phase_indexes:
                    raise ApiError(
                        status=400,
                        code="INVALID_PHASE_INDEX",
                        message=f"phaseIndex must exist in CityFlow roadnet phases for {intersection_id}: {phase_index}",
                        retryable=False,
                    )
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
                    "phaseCode": PHASE_CODES.get(phase_index) or decision.get("phaseCode"),
                    "status": "applied",
                })
            if applied:
                session.external_control_enabled = True
        return {
            "sid": sid,
            "applied": applied,
        }

    def _session(self, sid: str, owner_id: str = "default") -> "CityFlowEngineSession":
        if sid not in self.sessions:
            raise ApiError(
                status=404,
                code="SESSION_NOT_FOUND",
                message=f"simulation session not found: {sid}",
                retryable=False,
            )
        session = self.sessions[sid]
        if session.owner_id != owner_id:
            raise ApiError(
                status=403,
                code="SESSION_OWNER_MISMATCH",
                message=f"simulation session does not belong to client: {owner_id}",
                retryable=False,
            )
        return session

    def _run_session_loop(self, session: "CityFlowEngineSession") -> None:
        while True:
            with session.state_lock:
                if session.stopped:
                    return
                running = session.running
            if running:
                started_at = time.perf_counter()
                try:
                    self._refresh_latest_frame(session, advance=True)
                except Exception as ex:
                    with session.state_lock:
                        session.running = False
                        session.last_error = str(ex)
                    return
                elapsed = time.perf_counter() - started_at
                time.sleep(max(0.0, self._snapshot_interval_seconds(session) - elapsed))
            else:
                time.sleep(0.05)

    def _refresh_latest_frame(self, session: "CityFlowEngineSession", advance: bool) -> JsonDict:
        frame = self._build_frame(session, advance)
        with session.frame_lock:
            session.latest_frame = frame
        return frame

    def _build_frame(self, session: "CityFlowEngineSession", advance: bool) -> JsonDict:
        with session.engine_lock:
            if advance:
                session.engine.next_step()

            session.seq += 1
            sim_time = float(session.engine.get_current_time())
            road_by_id = self.road_index[session.scene_id]
            if AUTO_SIGNAL_CYCLE:
                self._advance_signal_phases(session, sim_time)
            ev_events: list[dict] = []
            ev_status: list[dict] = []
            if self.ev_service.has_evs(session.sid):
                ev_overrides, ev_events, ev_status = self.ev_service.step(session.sid, session.engine, sim_time)
                for intersection_id, phase_index in ev_overrides.items():
                    try:
                        session.engine.set_tl_phase(intersection_id, phase_index - 1)
                        session.current_phases[intersection_id] = phase_index
                    except Exception:
                        continue
            vehicles = self._vehicle_states(session, road_by_id)
            lane_states = self._lane_states(session, road_by_id)
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
                "laneStates": lane_states,
                "intersections": intersections,
                "signals": signals,
                "metrics": metrics,
                "evEvents": ev_events,
                "evStatus": ev_status,
            }

    def _snapshot_interval_seconds(self, session: "CityFlowEngineSession") -> float:
        speed = max(1.0, float(session.speed))
        return max(DEFAULT_MIN_REALTIME_TICK_SECONDS, DEFAULT_REALTIME_TICK_SECONDS / speed)

    def _load_scene(self, scene: SceneDefinition) -> None:
        if scene.scene_id in self.parsers:
            return
        parser = RoadnetParser(scene.roadnet_file)
        self.parsers[scene.scene_id] = parser
        self.road_index[scene.scene_id] = parser.road_by_id()
        self.lane_movement_maps[scene.scene_id] = parser.lane_movement_map()
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
        for vehicle_id in vehicle_ids[:DEFAULT_VISIBLE_VEHICLE_LIMIT]:
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

    def _lane_states(
            self,
            session: "CityFlowEngineSession",
            road_by_id: dict[str, JsonDict],
    ) -> JsonDict:
        parser = self.parsers[session.scene_id]
        states = self._empty_lane_states(parser)
        movement_map = self.lane_movement_maps.get(session.scene_id, {})
        lane_vehicles = session.engine.get_lane_vehicles()
        lane_waiting_counts = session.engine.get_lane_waiting_vehicle_count()
        speed_by_vehicle = session.engine.get_vehicle_speed()
        distance_by_vehicle = session.engine.get_vehicle_distance()

        for lane_id, vehicle_ids in lane_vehicles.items():
            road_id, lane_index = self._split_lane_id(lane_id)
            road = road_by_id.get(road_id)
            if road is None:
                continue
            intersection_id = road.get("endIntersection")
            lane_code = movement_map.get(intersection_id, {}).get(road_id, {}).get(lane_index)
            if lane_code is None:
                continue
            lane_state = states[intersection_id]["lanes"][lane_code]
            lane_state["queue_len"] += int(lane_waiting_counts.get(lane_id, 0))
            road_length = self._road_length(road)
            for vehicle_id in vehicle_ids:
                if float(speed_by_vehicle.get(vehicle_id, 0.0)) < 0.1:
                    continue
                segment_index = self._segment_index(
                    road_length,
                    float(distance_by_vehicle.get(vehicle_id, 0.0)),
                )
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
            phase_index = current_phases.get(intersection_id, FIRST_BUSINESS_PHASE_INDEX)
            signals.append({
                "intersectionId": intersection_id,
                "phaseIndex": phase_index,
                "phaseCode": PHASE_CODES.get(phase_index),
            })
        return signals

    def _advance_signal_phases(self, session: "CityFlowEngineSession", sim_time: float) -> None:
        phase_indexes_by_intersection = self.phase_indexes.get(session.scene_id, {})
        for intersection_id, phase_indexes in phase_indexes_by_intersection.items():
            if not phase_indexes:
                continue
            controllable_phase_indexes = [phase_index for phase_index in phase_indexes if phase_index in BUSINESS_PHASE_INDEXES]
            cycle_phase_indexes = controllable_phase_indexes or phase_indexes
            phase_index = cycle_phase_indexes[int(sim_time // 10) % len(cycle_phase_indexes)]
            session.current_phases[intersection_id] = phase_index
            try:
                session.engine.set_tl_phase(intersection_id, phase_index - 1)
            except Exception:
                # CityFlow 0.1 does not expose get_tl_phase; keep returning the
                # session-recorded phase so visualization remains debuggable.
                continue

    def _initialize_signal_phases(self, session: "CityFlowEngineSession") -> None:
        phase_indexes_by_intersection = self.phase_indexes.get(session.scene_id, {})
        for intersection_id, phase_indexes in phase_indexes_by_intersection.items():
            if not phase_indexes:
                continue
            controllable_phase_indexes = [phase_index for phase_index in phase_indexes if phase_index in BUSINESS_PHASE_INDEXES]
            phase_index = FIRST_BUSINESS_PHASE_INDEX if FIRST_BUSINESS_PHASE_INDEX in phase_indexes else (controllable_phase_indexes[0] if controllable_phase_indexes else phase_indexes[0])
            session.current_phases[intersection_id] = phase_index
            try:
                session.engine.set_tl_phase(intersection_id, phase_index - 1)
            except Exception:
                continue

    def _warmup_session(self, session: "CityFlowEngineSession", warmup_seconds: float) -> None:
        target_steps = max(0, int(round(warmup_seconds / 0.2)))
        for _ in range(target_steps):
            session.engine.next_step()

    def _metrics(
            self,
            session: "CityFlowEngineSession",
            vehicles: list[JsonDict],
            roads: list[JsonDict],
    ) -> JsonDict:
        vehicle_count = len(vehicles)
        queue_count = sum(road["queueCount"] for road in roads)
        avg_speed = sum(vehicle["speed"] for vehicle in vehicles) / vehicle_count if vehicle_count else 0.0
        active_vehicle_count = int(session.engine.get_vehicle_count())
        finished_vehicle_count = self._finished_vehicle_count(session.engine)
        scheduled_departure_count = min(session.total_vehicle_count, finished_vehicle_count + active_vehicle_count)
        return {
            "vehicleCount": active_vehicle_count,
            "activeVehicleCount": active_vehicle_count,
            "scheduledDepartureCount": scheduled_departure_count,
            "queueCount": queue_count,
            "avgSpeed": round(avg_speed, 3),
            "avgWait": round(queue_count * 3.0, 3),
            "throughput": finished_vehicle_count,
        }

    def _finished_vehicle_count(self, engine: object) -> int:
        try:
            return int(engine.get_finished_vehicle_count())
        except Exception:
            return 0

    def _normalize_control_phase(self, decision: JsonDict) -> int:
        phase_code = decision.get("phaseCode")
        if isinstance(phase_code, str) and phase_code in BUSINESS_PHASE_CODE_TO_INDEX:
            return BUSINESS_PHASE_CODE_TO_INDEX[phase_code]

        phase_index = int(decision["phaseIndex"])
        if phase_index in BUSINESS_PHASE_INDEXES:
            return phase_index

        # Backward compatibility for stale clients that still send Traffic-R's
        # business indices 1..4. CityFlow phase 1 is right-turn-only in Jinan
        # and must not be treated as ETWT.
        legacy_business_index_to_cityflow_phase = {
            1: BUSINESS_PHASE_CODE_TO_INDEX["ETWT"],
            2: BUSINESS_PHASE_CODE_TO_INDEX["NTST"],
            3: BUSINESS_PHASE_CODE_TO_INDEX["ELWL"],
            4: BUSINESS_PHASE_CODE_TO_INDEX["NLSL"],
        }
        if phase_index in legacy_business_index_to_cityflow_phase:
            return legacy_business_index_to_cityflow_phase[phase_index]
        return phase_index


@dataclass
class CityFlowEngineSession:
    sid: str
    scene_id: str
    speed: float
    owner_id: str
    total_vehicle_count: int
    engine: object
    config_path: Path
    seq: int = 0
    current_phases: dict[str, int] = field(default_factory=dict)
    external_control_enabled: bool = False
    running: bool = False
    stopped: bool = False
    last_error: str | None = None
    latest_frame: JsonDict | None = None
    worker: Thread | None = field(default=None, repr=False)
    state_lock: Lock = field(default_factory=Lock, repr=False)
    engine_lock: Lock = field(default_factory=Lock, repr=False)
    frame_lock: Lock = field(default_factory=Lock, repr=False)
