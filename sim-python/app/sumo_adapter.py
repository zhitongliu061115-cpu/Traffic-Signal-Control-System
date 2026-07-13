from __future__ import annotations

import math
import heapq
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from threading import Lock, RLock, Thread, current_thread
from typing import Any

from app.config import (
    AUTO_SIGNAL_CYCLE,
    DEFAULT_MIN_REALTIME_TICK_SECONDS,
    DEFAULT_VISIBLE_VEHICLE_LIMIT,
    MAX_ACTIVE_SESSIONS,
    MAX_SPEED,
    SERVICE_VERSION,
    SESSION_ABANDONED_TTL_SECONDS,
    SESSION_CLEANUP_INTERVAL_SECONDS,
    SESSION_DRAIN_TIMEOUT_SECONDS,
    SESSION_IDLE_TTL_SECONDS,
    SESSION_MAX_LIFETIME_SECONDS,
)
from app.errors import ApiError
from app.ev_service import EVPriorityService, VEHICLE_TYPE_PRIORITY
from app.models import JsonDict
from app.roadnet_parser import BUSINESS_PHASE_CODE_TO_INDEX, BUSINESS_PHASE_INDEXES, PHASE_CODES
from app.sumo_config import load_sumo_runtime_config
from app.sumo_roadnet_parser import SumoRoadnetParser
from app.sumo_scene_registry import SumoSceneDefinition, SumoSceneRegistry


LANE_CODES = ("WT", "WL", "ST", "SL", "ET", "EL", "NT", "NL")


def traffic_r_cell_index(lane_length: float, lane_position: float) -> int:
    """Map a moving vehicle to the four distance cells used by LLMTSCS."""
    length = max(1.0, float(lane_length))
    position = max(0.0, min(length, float(lane_position)))
    remaining = length - position
    tolerance = length * 1e-9
    if remaining <= length / 10.0 + tolerance:
        return 0
    if remaining <= length / 3.0 + tolerance:
        return 1
    if remaining <= length * 2.0 / 3.0 + tolerance:
        return 2
    return 3


class SumoEVEngineView:
    """Minimal CityFlow-like engine view consumed by EVPriorityService."""

    def __init__(self, session: "SumoSession"):
        self.session = session

    def get_vehicle_info(self, vehicle_id: str) -> JsonDict | None:
        connection = self.session.connection
        try:
            if vehicle_id not in set(connection.vehicle.getIDList()):
                return None
            road_id = str(connection.vehicle.getRoadID(vehicle_id))
            lane_position = float(connection.vehicle.getLanePosition(vehicle_id))
            if road_id.startswith(":"):
                route = list(connection.vehicle.getRoute(vehicle_id))
                route_index = int(connection.vehicle.getRouteIndex(vehicle_id))
                if route:
                    road_id = route[max(0, min(len(route) - 1, route_index - 1))]
                    lane_position = float(self.session.parser.net.getEdge(road_id).getLength())
            edge = self.session.parser.net.getEdge(road_id)
            next_intersection = edge.getToNode().getID()
            if next_intersection not in set(self.session.parser.traffic_light_ids()):
                next_intersection = ""
            return {
                "road": road_id,
                "distance": lane_position,
                "speed": float(connection.vehicle.getSpeed(vehicle_id)),
                "next_intersection": next_intersection,
            }
        except Exception:
            return None

    def get_tl_phase(self, intersection_id: str) -> int:
        return max(0, int(self.session.current_phases.get(intersection_id, 2)) - 1)

    def get_lane_vehicle_count(self) -> dict[str, int]:
        connection = self.session.connection
        return {
            str(lane_id): int(connection.lane.getLastStepVehicleNumber(lane_id))
            for lane_id in connection.lane.getIDList()
        }

    def has_vehicle_arrived(self, vehicle_id: str) -> bool:
        return vehicle_id in set(self.session.connection.simulation.getArrivedIDList())

    def remove_vehicle(self, vehicle_id: str) -> bool:
        try:
            self.session.connection.vehicle.remove(vehicle_id)
            return True
        except Exception:
            return False

    def get_tl_phase_remaining(self, intersection_id: str) -> float:
        connection = self.session.connection
        return max(
            0.0,
            float(connection.trafficlight.getNextSwitch(intersection_id))
            - float(connection.simulation.getTime()),
        )

    def set_tl_phase_duration(self, intersection_id: str, duration: float) -> None:
        self.session.connection.trafficlight.setPhaseDuration(intersection_id, max(1.0, float(duration)))

    def build_ev_priority_program(
            self,
            intersection_id: str,
            target_phase: int,
            min_green: float = 10.0,
            ev_extend: float = 60.0,
    ) -> None:
        """Apply a business phase using this adapter's verified SUMO movement mapping."""
        phase_code = PHASE_CODES.get(int(target_phase))
        if phase_code is None:
            return
        state = self.session.parser.business_signal_state(intersection_id, phase_code)
        connection = self.session.connection
        connection.trafficlight.setRedYellowGreenState(intersection_id, state)
        connection.trafficlight.setPhaseDuration(intersection_id, max(min_green, ev_extend))
        self.session.current_phases[intersection_id] = int(target_phase)
        self.session.external_control_enabled = True


class SumoAdapter:
    """TraCI-backed simulator that preserves the legacy CityFlow HTTP contract."""

    def __init__(self, data_dir: Path):
        self.default_scene_id = "xian_5x5"
        self.data_dir = data_dir
        self.runtime = load_sumo_runtime_config()
        try:
            self.runtime.validate()
        except RuntimeError as ex:
            raise ApiError(500, "SUMO_RUNTIME_INVALID", str(ex), False) from ex
        self.runtime.add_tools_to_python_path()
        import sumolib
        import traci

        self.sumolib = sumolib
        self.traci = traci
        self.scene_registry = SumoSceneRegistry(data_dir)
        self.parsers = {
            scene_id: SumoRoadnetParser(
                scene_id,
                self.scene_registry.get(scene_id).net_file,
                sumolib,
            )
            for scene_id in self.scene_registry.list_scene_ids()
        }
        self.ev_service = EVPriorityService()
        self.sessions: dict[str, SumoSession] = {}
        self.terminal_frames: dict[str, JsonDict] = {}
        self.sessions_lock = RLock()
        Thread(target=self._cleanup_loop, name="sumo-session-cleanup", daemon=True).start()

    def health(self) -> JsonDict:
        self.cleanup_expired_sessions()
        strategy_counts = {
            scene_id: {
                "trafficR": len(self.scene_registry.get(scene_id).traffic_r_intersections),
                "maxPressure": len(self.scene_registry.get(scene_id).max_pressure_intersections),
            }
            for scene_id in self.scene_registry.list_scene_ids()
        }
        return {
            "status": "UP",
            "service": "sim-python",
            "version": SERVICE_VERSION,
            "engineMode": "sumo",
            "sumoHome": str(self.runtime.home),
            "sumoBinary": str(self.runtime.binary),
            "sumoStepLength": self.runtime.step_length,
            "autoSignalCycle": AUTO_SIGNAL_CYCLE,
            "maxActiveSessions": MAX_ACTIVE_SESSIONS,
            "maxSpeed": MAX_SPEED,
            "sessionAbandonedTtlSeconds": SESSION_ABANDONED_TTL_SECONDS,
            "sessionIdleTtlSeconds": SESSION_IDLE_TTL_SECONDS,
            "sessionMaxLifetimeSeconds": SESSION_MAX_LIFETIME_SECONDS,
            "sessionCleanupIntervalSeconds": SESSION_CLEANUP_INTERVAL_SECONDS,
            "sessionDrainTimeoutSeconds": SESSION_DRAIN_TIMEOUT_SECONDS,
            "sceneIds": self.scene_registry.list_scene_ids(),
            "strategyCounts": strategy_counts,
            "activeSessions": self._active_session_count(),
        }

    def get_roadnet(self, scene_id: str) -> JsonDict:
        self.scene_registry.get(scene_id)
        return self.parsers[scene_id].to_response()

    def create_simulation(
            self,
            scene_id: str,
            speed: float | None = None,
            warmup_seconds: float | None = None,
            owner_id: str = "default",
    ) -> JsonDict:
        scene = self.scene_registry.get(scene_id)
        normalized_speed = self._normalize_speed(speed)
        warmup = self._normalize_warmup_seconds(warmup_seconds)
        sid = f"run_{uuid.uuid4().hex[:16]}"
        command = [
            str(self.runtime.binary),
            "-c", str(scene.config_file),
            "--step-length", str(self.runtime.step_length),
            "--no-step-log", "true",
            "--duration-log.disable", "true",
            "--quit-on-end", "true",
        ]
        try:
            self.traci.start(command, label=sid, verbose=False)
            connection = self.traci.getConnection(sid)
        except Exception as ex:
            raise ApiError(500, "SUMO_START_FAILED", f"failed to start SUMO: {ex}", True) from ex

        session = SumoSession(
            sid=sid,
            scene=scene,
            speed=normalized_speed,
            connection=connection,
            parser=self.parsers[scene_id],
        )
        try:
            with session.engine_lock:
                self._initialize_signals(session)
                for _ in range(max(0, int(round(warmup / self.runtime.step_length)))):
                    connection.simulationStep()
                session.latest_frame = self._build_frame_locked(session)
        except Exception as ex:
            try:
                connection.close(False)
            except Exception:
                pass
            raise ApiError(500, "SUMO_INITIALIZATION_FAILED", str(ex), True) from ex

        with self.sessions_lock:
            self.sessions[sid] = session
        session.worker = Thread(target=self._run_session_loop, args=(session,), name=f"sumo-{sid}", daemon=True)
        session.worker.start()
        return {"sid": sid, "sceneId": scene_id, "status": "created", "engineMode": "sumo"}

    def start_simulation(self, sid: str, owner_id: str = "default") -> JsonDict:
        session = self._session(sid)
        with session.state_lock:
            session.running = True
            session.stopped = False
        return {"sid": sid, "status": "running"}

    def pause_simulation(self, sid: str, owner_id: str = "default") -> JsonDict:
        session = self._session(sid)
        with session.state_lock:
            session.running = False
        return {"sid": sid, "status": "paused"}

    def stop_simulation(self, sid: str, owner_id: str = "default") -> JsonDict:
        session = self._session(sid)
        self._release_session(session, None, join_worker=True)
        return {"sid": sid, "status": "stopped"}

    def next_frame(self, sid: str, owner_id: str = "default") -> JsonDict:
        with self.sessions_lock:
            terminal = self.terminal_frames.pop(sid, None)
        if terminal is not None:
            return terminal
        session = self._session(sid)
        with session.frame_lock:
            if session.latest_frame is not None:
                frame = dict(session.latest_frame)
                with session.ev_event_lock:
                    frame["evEvents"] = list(session.pending_ev_events)
                    session.pending_ev_events.clear()
                return frame
        with session.engine_lock:
            frame = self._build_frame_locked(session)
        with session.frame_lock:
            session.latest_frame = frame
        return dict(frame)

    def apply_control_actions(self, sid: str, payload: JsonDict, owner_id: str = "default") -> JsonDict:
        decisions = self._validate_decisions_payload(payload)
        session = self._session(sid)
        applied = []
        with session.engine_lock:
            tls_ids = set(session.parser.traffic_light_ids())
            for decision in decisions:
                intersection_id = decision["intersectionId"]
                if intersection_id not in tls_ids:
                    raise ApiError(400, "INVALID_INTERSECTION_ID", f"unknown SUMO traffic light: {intersection_id}", False)
                phase_index = self._normalize_control_phase(decision)
                phase_code = PHASE_CODES.get(phase_index)
                if phase_code is None:
                    raise ApiError(400, "INVALID_PHASE_INDEX", f"unsupported business phase: {phase_index}", False)
                state = session.parser.business_signal_state(intersection_id, phase_code)
                try:
                    session.connection.trafficlight.setRedYellowGreenState(intersection_id, state)
                    duration = decision.get("durationSec")
                    if duration is not None:
                        session.connection.trafficlight.setPhaseDuration(
                            intersection_id,
                            max(1.0, float(duration)),
                        )
                except Exception as ex:
                    raise ApiError(500, "SUMO_SET_PHASE_FAILED", f"{intersection_id}: {ex}", True) from ex
                session.current_phases[intersection_id] = phase_index
                session.external_control_enabled = True
                applied.append({
                    "intersectionId": intersection_id,
                    "phaseIndex": phase_index,
                    "cityflowPhaseId": phase_index - 1,
                    "phaseCode": phase_code,
                    "status": "applied",
                })
        return {"sid": sid, "applied": applied}

    def dispatch(self, sid: str, params: JsonDict, owner_id: str = "default") -> JsonDict:
        session = self._session(sid)
        ev_id = str(params.get("evId") or f"EV_{uuid.uuid4().hex[:8]}")
        vehicle_id = f"sumo_{ev_id}"
        with session.engine_lock:
            from_edges = self._dispatch_edges(session, params, "start", outgoing=True)
            to_edges = self._dispatch_edges(session, params, "end", outgoing=False)
            route_edges, estimated_travel_time = self._best_emergency_route(session, from_edges, to_edges)
            if not route_edges:
                raise ApiError(
                    400,
                    "EV_ROUTE_NOT_FOUND",
                    "no SUMO route between any legal start and destination approach",
                    False,
                )
            route_id = f"route_{vehicle_id}"
            session.connection.route.add(route_id, route_edges)
            session.connection.vehicle.add(vehicle_id, route_id, typeID="DEFAULT_VEHTYPE", depart="now")
            max_speed = float(params.get("maxSpeed", 20.0))
            session.connection.vehicle.setMaxSpeed(vehicle_id, max_speed)
            session.connection.vehicle.setColor(vehicle_id, (255, 0, 0, 255))
            ev_type = str(params.get("evType", "ambulance"))
            priority_value = params.get("priority")
            priority = int(priority_value) if priority_value is not None else VEHICLE_TYPE_PRIORITY.get(ev_type, 99)
            session.ev_records[vehicle_id] = {
                "evId": ev_id,
                "evType": ev_type,
                "priority": priority,
            }
            route_roads = route_edges
            intersections = self._route_intersections(session, route_roads)
            self.ev_service.register_external_vehicle(
                sid=sid,
                roadnet=session.parser.ev_priority_roadnet(),
                ev_id=ev_id,
                vehicle_id=vehicle_id,
                route=intersections,
                route_roads=route_roads,
                sim_time=float(session.connection.simulation.getTime()),
                ev_type=ev_type,
                priority=priority,
                max_speed=max_speed,
            )
        return {
            "sid": sid,
            "evId": ev_id,
            "evType": ev_type,
            "priority": priority,
            "cfVehicleId": vehicle_id,
            "route": intersections,
            "routeRoads": route_roads,
            "estimatedTravelTime": round(estimated_travel_time, 3),
            "totalIntersections": len(intersections),
        }

    def cleanup_expired_sessions(self) -> int:
        now = time.time()
        with self.sessions_lock:
            expired = [session for session in self.sessions.values() if self._session_expired(session, now)]
        for session in expired:
            self._release_session(session, None, join_worker=True)
        return len(expired)

    def _run_session_loop(self, session: "SumoSession") -> None:
        while True:
            with session.state_lock:
                if session.stopped:
                    return
                running = session.running
            if not running:
                time.sleep(0.05)
                continue
            started = time.perf_counter()
            try:
                with session.engine_lock:
                    session.connection.simulationStep()
                    session.departed_count += int(session.connection.simulation.getDepartedNumber())
                    session.arrived_count += int(session.connection.simulation.getArrivedNumber())
                    frame = self._build_frame_locked(session)
                with session.frame_lock:
                    session.latest_frame = frame
            except Exception as ex:
                with session.state_lock:
                    session.running = False
                    session.last_error = str(ex)
                return
            if frame["status"] == "finished":
                self._release_session(session, frame, join_worker=False)
                return
            elapsed = time.perf_counter() - started
            interval = max(DEFAULT_MIN_REALTIME_TICK_SECONDS, self.runtime.step_length / session.speed)
            time.sleep(max(0.0, interval - elapsed))

    def _build_frame_locked(self, session: "SumoSession") -> JsonDict:
        connection = session.connection
        session.seq += 1
        sim_time = float(connection.simulation.getTime())
        vehicle_ids = list(connection.vehicle.getIDList())
        active_ev_ids = [vehicle_id for vehicle_id in session.ev_records if vehicle_id in vehicle_ids]
        normal_ids = [vehicle_id for vehicle_id in vehicle_ids if vehicle_id not in session.ev_records]
        visible_ids = (active_ev_ids + normal_ids)[:DEFAULT_VISIBLE_VEHICLE_LIMIT]
        vehicles = [self._vehicle_state(session, connection, vehicle_id) for vehicle_id in visible_ids]
        roads = self._road_states(session, connection)
        lane_states = self._lane_states(session, connection)
        intersections = self._intersection_states(session, roads)
        ev_overrides, ev_events, ev_status = self.ev_service.step(
            session.sid,
            SumoEVEngineView(session),
            sim_time,
        )
        self._apply_ev_overrides(session, ev_overrides)
        if ev_events:
            with session.ev_event_lock:
                session.pending_ev_events.extend(ev_events)
        signals = self._signal_states(session, connection)
        metrics = self._metrics(session, connection, vehicle_ids, roads)
        status = "finished" if self._simulation_complete(session, connection, sim_time) else "running"
        return {
            "sid": session.sid,
            "sceneId": session.scene.scene_id,
            "seq": session.seq,
            "simTime": round(sim_time, 3),
            "engineMode": "sumo",
            "status": status,
            "vehicles": vehicles,
            "roads": roads,
            "laneStates": lane_states,
            "intersections": intersections,
            "signals": signals,
            "metrics": metrics,
            "evEvents": list(ev_events),
            "evStatus": ev_status,
        }

    def _vehicle_state(self, session: "SumoSession", connection: Any, vehicle_id: str) -> JsonDict:
        x, y = connection.vehicle.getPosition(vehicle_id)
        raw_road_id = str(connection.vehicle.getRoadID(vehicle_id))
        lane_id = str(connection.vehicle.getLaneID(vehicle_id))
        route = list(connection.vehicle.getRoute(vehicle_id))
        route_index = int(connection.vehicle.getRouteIndex(vehicle_id))
        road_id = raw_road_id
        next_road_id = route[route_index + 1] if 0 <= route_index < len(route) - 1 else None
        drivable_type = "lane"
        drivable_id = lane_id
        if raw_road_id.startswith(":"):
            drivable_type = "lane_link"
            road_id = route[max(0, min(len(route) - 1, route_index - 1))] if route else raw_road_id
            next_road_id = route[max(0, min(len(route) - 1, route_index))] if route else None
        longitude, latitude = session.parser.to_geo(x, y)
        return {
            "id": vehicle_id,
            "roadId": road_id,
            "lane": int(connection.vehicle.getLaneIndex(vehicle_id)),
            "x": round(float(x), 3),
            "y": round(float(y), 3),
            "lng": round(longitude, 8),
            "lat": round(latitude, 8),
            "angle": round(float(connection.vehicle.getAngle(vehicle_id)), 3),
            "speed": round(float(connection.vehicle.getSpeed(vehicle_id)), 3),
            "drivableId": drivable_id,
            "drivableType": drivable_type,
            "distance": round(float(connection.vehicle.getLanePosition(vehicle_id)), 3),
            "nextRoadId": next_road_id,
            "nextLane": None,
        }

    def _road_states(self, session: "SumoSession", connection: Any) -> list[JsonDict]:
        states = []
        for edge in session.parser.net.getEdges():
            if edge.getFunction():
                continue
            edge_id = edge.getID()
            vehicle_count = int(connection.edge.getLastStepVehicleNumber(edge_id))
            queue_count = sum(int(connection.lane.getLastStepHaltingNumber(lane.getID())) for lane in edge.getLanes())
            avg_speed = float(connection.edge.getLastStepMeanSpeed(edge_id)) if vehicle_count else 0.0
            states.append({
                "id": edge_id,
                "vehicleCount": vehicle_count,
                "queueCount": queue_count,
                "avgSpeed": round(max(0.0, avg_speed), 3),
                "level": self._traffic_level(vehicle_count, queue_count, avg_speed),
            })
        return states

    def _lane_states(self, session: "SumoSession", connection: Any) -> JsonDict:
        result: JsonDict = {}
        movement_map = session.parser.lane_movement_map()
        for intersection_id in session.parser.traffic_light_ids():
            accumulators = {
                code: {"queue": 0, "wait": 0.0, "cells": [0, 0, 0, 0]}
                for code in LANE_CODES
            }
            for lane_id, movements in movement_map.get(intersection_id, {}).items():
                supported = [value for value in movements if value in accumulators]
                if not supported:
                    continue
                movement = next((value for value in supported if value.endswith("T")), supported[0])
                lane_length = max(1.0, float(connection.lane.getLength(lane_id)))
                vehicle_ids = list(connection.lane.getLastStepVehicleIDs(lane_id))
                accumulator = accumulators[movement]
                for vehicle_id in vehicle_ids:
                    speed = float(connection.vehicle.getSpeed(vehicle_id))
                    if speed <= 0.1:
                        accumulator["queue"] += 1
                        accumulator["wait"] += float(connection.vehicle.getWaitingTime(vehicle_id))
                        continue
                    position = float(connection.vehicle.getLanePosition(vehicle_id))
                    accumulator["cells"][traffic_r_cell_index(lane_length, position)] += 1
            lanes = {}
            for code, accumulator in accumulators.items():
                queue_count = accumulator["queue"]
                lanes[code] = {
                    "queue_len": queue_count,
                    "avg_wait_time": round(accumulator["wait"] / queue_count, 3) if queue_count else 0.0,
                    "cells": accumulator["cells"],
                }
            result[intersection_id] = {"lanes": lanes}
        return result

    def _intersection_states(self, session: "SumoSession", roads: list[JsonDict]) -> list[JsonDict]:
        road_by_id = {road["id"]: road for road in roads}
        states = []
        for intersection_id in session.parser.traffic_light_ids():
            incoming = [road_by_id.get(edge.getID()) for edge in session.parser.incoming_edges(intersection_id)]
            incoming = [road for road in incoming if road is not None]
            queue = sum(road["queueCount"] for road in incoming)
            vehicle_count = sum(road["vehicleCount"] for road in incoming)
            states.append({
                "id": intersection_id,
                "queueCount": queue,
                "avgWait": round(queue * 3.0, 3),
                "level": self._traffic_level(vehicle_count, queue, 0.0),
            })
        return states

    def _signal_states(self, session: "SumoSession", connection: Any) -> list[JsonDict]:
        signals = []
        for intersection_id in session.parser.traffic_light_ids():
            state = connection.trafficlight.getRedYellowGreenState(intersection_id)
            phase_index, phase_code = session.parser.phase_for_state(intersection_id, state)
            session.current_phases[intersection_id] = phase_index
            remaining = max(
                0.0,
                float(connection.trafficlight.getNextSwitch(intersection_id))
                - float(connection.simulation.getTime()),
            )
            signals.append({
                "intersectionId": intersection_id,
                "phaseIndex": phase_index,
                "phaseCode": phase_code,
                "remainingSec": round(remaining, 3),
            })
        return signals

    def _metrics(self, session: "SumoSession", connection: Any, vehicle_ids: list[str], roads: list[JsonDict]) -> JsonDict:
        queue_count = sum(road["queueCount"] for road in roads)
        speeds = [float(connection.vehicle.getSpeed(vehicle_id)) for vehicle_id in vehicle_ids]
        waits = [float(connection.vehicle.getAccumulatedWaitingTime(vehicle_id)) for vehicle_id in vehicle_ids]
        return {
            "vehicleCount": len(vehicle_ids),
            "activeVehicleCount": len(vehicle_ids),
            "scheduledDepartureCount": session.departed_count,
            "queueCount": queue_count,
            "avgSpeed": round(sum(speeds) / len(speeds), 3) if speeds else 0.0,
            "avgWait": round(sum(waits) / len(waits), 3) if waits else 0.0,
            "throughput": session.arrived_count,
        }

    def _initialize_signals(self, session: "SumoSession") -> None:
        """Observe SUMO's native programs without freezing every junction to one phase."""
        for intersection_id in session.parser.traffic_light_ids():
            state = session.connection.trafficlight.getRedYellowGreenState(intersection_id)
            phase_index, _ = session.parser.phase_for_state(intersection_id, state)
            session.current_phases[intersection_id] = phase_index

    def _apply_ev_overrides(self, session: "SumoSession", overrides: dict[str, int]) -> None:
        for intersection_id, phase_index in overrides.items():
            phase_code = PHASE_CODES.get(int(phase_index))
            if phase_code is None:
                continue
            state = session.parser.business_signal_state(intersection_id, phase_code)
            session.connection.trafficlight.setRedYellowGreenState(intersection_id, state)
            session.current_phases[intersection_id] = int(phase_index)
            session.external_control_enabled = True

    def _simulation_complete(self, session: "SumoSession", connection: Any, sim_time: float) -> bool:
        return sim_time >= session.scene.flow_end_time and int(connection.simulation.getMinExpectedNumber()) == 0

    def _dispatch_edges(
            self,
            session: "SumoSession",
            params: JsonDict,
            prefix: str,
            outgoing: bool,
    ) -> list[str]:
        intersection_id = params.get(f"{prefix}Intersection")
        if intersection_id:
            try:
                node = session.parser.net.getNode(str(intersection_id))
            except KeyError as ex:
                raise ApiError(400, "INVALID_INTERSECTION_ID", str(intersection_id), False) from ex
            edges = node.getOutgoing() if outgoing else node.getIncoming()
            candidates = [edge for edge in edges if not edge.getFunction() and edge.allows("passenger")]
            if candidates:
                return sorted({edge.getID() for edge in candidates})
            direction = "outgoing" if outgoing else "incoming"
            raise ApiError(
                400,
                "INVALID_INTERSECTION_ID",
                f"intersection {intersection_id} has no passenger {direction} edge",
                False,
            )
        coord = params.get(f"{prefix}Coord")
        if isinstance(coord, dict) and "x" in coord and "y" in coord:
            return [self._nearest_edge(session, float(coord["x"]), float(coord["y"]))]
        raise ApiError(400, "INVALID_REQUEST", f"{prefix}Intersection or {prefix}Coord is required", False)

    def _nearest_edge(self, session: "SumoSession", x: float, y: float) -> str:
        best: tuple[float, str] | None = None
        for edge in session.parser.net.getEdges():
            if edge.getFunction() or not edge.allows("passenger"):
                continue
            for px, py in edge.getShape():
                distance = math.hypot(px - x, py - y)
                if best is None or distance < best[0]:
                    best = (distance, edge.getID())
        if best is None:
            raise ApiError(400, "EV_ROUTE_NOT_FOUND", "no passenger edge near coordinate", False)
        return best[1]

    def _congestion_aware_route(
            self,
            session: "SumoSession",
            from_edge_id: str,
            to_edge_id: str,
    ) -> tuple[list[str], float]:
        """Dijkstra route using live edge occupancy as a deterministic penalty."""
        net = session.parser.net
        try:
            start = net.getEdge(from_edge_id)
            destination = net.getEdge(to_edge_id)
        except KeyError:
            return [], 0.0

        def edge_cost(edge: Any) -> float:
            speed = max(0.1, float(edge.getSpeed()))
            base = float(edge.getLength()) / speed
            try:
                vehicles = int(session.connection.edge.getLastStepVehicleNumber(edge.getID()))
            except Exception:
                vehicles = 0
            return base * (1.0 + max(0, vehicles) * 0.015)

        start_cost = edge_cost(start)
        distances = {start.getID(): start_cost}
        paths: dict[str, list[str]] = {start.getID(): [start.getID()]}
        queue: list[tuple[float, str, Any]] = [(start_cost, start.getID(), start)]
        while queue:
            distance, edge_id, edge = heapq.heappop(queue)
            if distance > distances.get(edge_id, math.inf):
                continue
            if edge_id == destination.getID():
                return paths[edge_id], distance
            outgoing = edge.getOutgoing()
            candidates = outgoing.keys() if isinstance(outgoing, dict) else outgoing
            for candidate in candidates:
                next_edge = candidate.getTo() if hasattr(candidate, "getTo") else candidate
                if next_edge.getFunction() or not next_edge.allows("passenger"):
                    continue
                next_id = next_edge.getID()
                next_distance = distance + edge_cost(next_edge)
                if next_distance >= distances.get(next_id, math.inf):
                    continue
                distances[next_id] = next_distance
                paths[next_id] = [*paths[edge_id], next_id]
                heapq.heappush(queue, (next_distance, next_id, next_edge))
        return [], 0.0

    def _best_emergency_route(
            self,
            session: "SumoSession",
            from_edge_ids: list[str],
            to_edge_ids: list[str],
    ) -> tuple[list[str], float]:
        best_route: list[str] = []
        best_cost = math.inf
        for from_edge_id in from_edge_ids:
            for to_edge_id in to_edge_ids:
                route, cost = self._congestion_aware_route(session, from_edge_id, to_edge_id)
                if route and cost < best_cost:
                    best_route, best_cost = route, cost
        if best_route:
            return best_route, best_cost

        for from_edge_id in from_edge_ids:
            for to_edge_id in to_edge_ids:
                fallback = session.connection.simulation.findRoute(
                    from_edge_id,
                    to_edge_id,
                    vType="DEFAULT_VEHTYPE",
                )
                if fallback.edges and float(fallback.travelTime) < best_cost:
                    best_route = list(fallback.edges)
                    best_cost = float(fallback.travelTime)
        return (best_route, best_cost) if best_route else ([], 0.0)

    def _route_intersections(self, session: "SumoSession", edge_ids: list[str]) -> list[str]:
        if not edge_ids:
            return []
        edges = [session.parser.net.getEdge(edge_id) for edge_id in edge_ids]
        result = [edges[0].getFromNode().getID()]
        result.extend(edge.getToNode().getID() for edge in edges)
        return result

    def _ev_status(self, session: "SumoSession", connection: Any) -> list[JsonDict]:
        active = set(connection.vehicle.getIDList())
        result = []
        for vehicle_id, record in session.ev_records.items():
            item = dict(record)
            item["cfVehicleId"] = vehicle_id
            item["status"] = "running" if vehicle_id in active else "finished"
            result.append(item)
        return result

    def _validate_decisions_payload(self, payload: JsonDict) -> list[JsonDict]:
        decisions = payload.get("decisions", [])
        if not isinstance(decisions, list):
            raise ApiError(400, "INVALID_REQUEST", "decisions must be a list", False)
        result = []
        for decision in decisions:
            if not isinstance(decision, dict) or not decision.get("intersectionId"):
                raise ApiError(400, "INVALID_REQUEST", "control decision intersectionId is required", False)
            try:
                phase_index = int(decision.get("phaseIndex"))
            except (TypeError, ValueError) as ex:
                raise ApiError(400, "INVALID_REQUEST", "control decision phaseIndex must be an integer", False) from ex
            if phase_index < 1:
                raise ApiError(400, "INVALID_REQUEST", "control decision phaseIndex must be greater than or equal to 1", False)
            normalized = dict(decision)
            normalized["phaseIndex"] = phase_index
            result.append(normalized)
        return result

    def _normalize_control_phase(self, decision: JsonDict) -> int:
        phase_code = decision.get("phaseCode")
        if isinstance(phase_code, str) and phase_code in BUSINESS_PHASE_CODE_TO_INDEX:
            return BUSINESS_PHASE_CODE_TO_INDEX[phase_code]
        phase_index = int(decision["phaseIndex"])
        if phase_index in BUSINESS_PHASE_INDEXES:
            return phase_index
        legacy = {1: 2, 2: 3, 3: 4, 4: 5}
        return legacy.get(phase_index, phase_index)

    def _normalize_speed(self, speed: float | None) -> float:
        try:
            value = 1.0 if speed is None else float(speed)
        except (TypeError, ValueError) as ex:
            raise ApiError(400, "INVALID_REQUEST", "speed must be numeric", False) from ex
        if value <= 0 or value > MAX_SPEED:
            raise ApiError(400, "INVALID_REQUEST", f"speed must be within (0, {MAX_SPEED}]", False)
        return value

    def _normalize_warmup_seconds(self, value: float | None) -> float:
        try:
            warmup = 0.0 if value is None else float(value)
        except (TypeError, ValueError) as ex:
            raise ApiError(400, "INVALID_REQUEST", "warmupSeconds must be numeric", False) from ex
        if warmup < 0:
            raise ApiError(400, "INVALID_REQUEST", "warmupSeconds must not be negative", False)
        return warmup

    def _traffic_level(self, vehicle_count: int, queue_count: int, avg_speed: float) -> str:
        if queue_count >= 8 or vehicle_count >= 20:
            return "congested"
        if queue_count >= 3 or vehicle_count >= 10 or (vehicle_count and avg_speed < 4.0):
            return "slow"
        return "free"

    def _session(self, sid: str) -> "SumoSession":
        with self.sessions_lock:
            session = self.sessions.get(sid)
        if session is None:
            raise ApiError(404, "SESSION_NOT_FOUND", f"simulation session not found: {sid}", False)
        session.last_access_at = time.time()
        return session

    def _active_session_count(self) -> int:
        with self.sessions_lock:
            return len(self.sessions)

    def _session_expired(self, session: "SumoSession", now: float) -> bool:
        if session.stopped:
            return True
        if SESSION_MAX_LIFETIME_SECONDS > 0 and now - session.created_at >= SESSION_MAX_LIFETIME_SECONDS:
            return True
        if SESSION_ABANDONED_TTL_SECONDS > 0 and session.running and now - session.last_access_at >= SESSION_ABANDONED_TTL_SECONDS:
            return True
        if SESSION_IDLE_TTL_SECONDS > 0 and not session.running and now - session.last_access_at >= SESSION_IDLE_TTL_SECONDS:
            return True
        return False

    def _release_session(self, session: "SumoSession", terminal_frame: JsonDict | None, join_worker: bool) -> None:
        with session.state_lock:
            already_stopped = session.stopped
            session.stopped = True
            session.running = False
        if not already_stopped:
            with session.engine_lock:
                try:
                    session.connection.close(False)
                except Exception:
                    pass
        if join_worker and session.worker is not None and session.worker is not current_thread():
            session.worker.join(timeout=2.0)
        with self.sessions_lock:
            self.sessions.pop(session.sid, None)
            if terminal_frame is not None:
                self.terminal_frames[session.sid] = terminal_frame
        self.ev_service.release_session(session.sid)

    def _cleanup_loop(self) -> None:
        while True:
            time.sleep(SESSION_CLEANUP_INTERVAL_SECONDS)
            try:
                self.cleanup_expired_sessions()
            except Exception:
                pass


@dataclass
class SumoSession:
    sid: str
    scene: SumoSceneDefinition
    speed: float
    connection: Any
    parser: SumoRoadnetParser
    seq: int = 0
    departed_count: int = 0
    arrived_count: int = 0
    current_phases: dict[str, int] = field(default_factory=dict)
    ev_records: dict[str, JsonDict] = field(default_factory=dict)
    pending_ev_events: list[JsonDict] = field(default_factory=list)
    external_control_enabled: bool = False
    running: bool = False
    stopped: bool = False
    last_error: str | None = None
    created_at: float = field(default_factory=time.time)
    last_access_at: float = field(default_factory=time.time)
    latest_frame: JsonDict | None = None
    worker: Thread | None = field(default=None, repr=False)
    state_lock: Lock = field(default_factory=Lock, repr=False)
    engine_lock: Lock = field(default_factory=Lock, repr=False)
    frame_lock: Lock = field(default_factory=Lock, repr=False)
    ev_event_lock: Lock = field(default_factory=Lock, repr=False)
