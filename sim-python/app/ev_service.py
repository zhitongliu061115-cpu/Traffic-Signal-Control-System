# -*- coding: utf-8 -*-
# ev_service.py - EV Priority Service Layer
# =========================================
# Bridges EV priority algorithms (ev_priority.py) with RealCityFlowEngine.
# Called by engine.py during simulation steps and by server.py via dispatch.

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from app import ev_config as cfg
from app.errors import ApiError
from app.ev_priority import (
    DijkstraPathPlanner,
    EVDetector,
    EVLogger,
    LWRQueueModel,
    LWRParams,
    SignalStrategy,
    SignalState,
    IntersectionCoordinator,
    RecoveryManager,
    CoordinateSnapper,
    get_approach_direction,
)


@dataclass
class EVSession:
    """EV tracking data for one EV in one simulation session."""
    ev_id: str
    ev_type: str = "fire_truck"
    priority: int = 1
    max_speed: float = 20.0
    start_time: float = 30.0
    route: List[str] = field(default_factory=list)
    route_roads: List[str] = field(default_factory=list)
    passed_intersections: set = field(default_factory=set)


class EVPriorityService:
    """Per-session EV priority controller.

    Does NOT own the CityFlow engine - only reads vehicle state
    and writes signal phase overrides through it.
    """

    def __init__(self):
        self.planner = DijkstraPathPlanner()
        self.snapper = CoordinateSnapper()
        self.detector = EVDetector()
        self.lwr_model = LWRQueueModel(LWRParams())
        self.strategy = SignalStrategy(self.lwr_model)
        self.coordinator = IntersectionCoordinator()
        self.recovery = RecoveryManager()
        self.logger = EVLogger()
        self.ev_sessions: Dict[str, Dict[str, EVSession]] = {}
        self.road_index: Dict[str, Dict[str, Any]] = {}
        self.handled: Dict[str, set] = {}
        self.signal_states: Dict[str, Dict[str, SignalState]] = {}
        self.phase_counts: Dict[str, Dict[str, int]] = {}
        self.approach_phases: Dict[str, Dict[str, Dict[str, List[int]]]] = {}

    def load_roadnet(self, sid: str, roadnet: dict) -> None:
        """Load roadnet data for a session."""
        self.planner.load_roadnet(roadnet)
        self.snapper.load_roadnet(roadnet)
        self.road_index[sid] = {r["id"]: r for r in roadnet.get("roads", [])}

        intersections = {i["id"]: i for i in roadnet.get("intersections", [])}
        pc: Dict[str, int] = {}
        ap: Dict[str, Dict[str, List[int]]] = {}
        for iid, idata in intersections.items():
            if idata.get("virtual"):
                continue
            tl = idata.get("trafficLight", {})
            phases = tl.get("lightphases", [])
            pc[iid] = len(phases)
            approach_map: Dict[str, List[int]] = {}
            road_links = idata.get("roadLinks", [])
            for rl in road_links:
                sr = rl.get("startRoad", "")
                if sr and sr not in approach_map:
                    approach_map[sr] = []
            for pi, lp in enumerate(phases):
                for rli in lp.get("availableRoadLinks", []):
                    if rli < len(road_links):
                        sr = road_links[rli].get("startRoad", "")
                        if sr and pi not in approach_map.get(sr, []):
                            approach_map.setdefault(sr, []).append(pi)
            ap[iid] = approach_map
        self.phase_counts[sid] = pc
        self.approach_phases[sid] = ap

        ss: Dict[str, SignalState] = {}
        for iid in pc:
            ss[iid] = SignalState(
                intersection_id=iid, current_phase=1,
                phase_count=pc[iid], phase_durations=[30] * pc[iid],
                phase_elapsed=0.0,
            )
        self.signal_states[sid] = ss

    def dispatch(self, sid: str, scene_id: str, roadnet: dict, params: dict) -> dict:
        """Plan EV route and register for priority handling.

        Primary mode: provide startCoord/endCoord {"x":N,"y":N} - auto-snaps to nearest road.
        Fallback: provide startIntersection/endIntersection directly.
        """
        if sid not in self.ev_sessions:
            self.ev_sessions[sid] = {}
        if sid not in self.handled:
            self.handled[sid] = set()

        self.load_roadnet(sid, roadnet)

        ev_id = params.get("evId", "ev_default")
        start_coord = params.get("startCoord")
        end_coord = params.get("endCoord")

        # Primary: coordinate-based (auto-snap via CoordinateSnapper)
        if start_coord and end_coord:
            # snap() returns (road_id, upstream_intersection, downstream_intersection, offset)
            start_road, start_inter, _, _ = self.snapper.snap(
                start_coord["x"], start_coord["y"])
            end_road, _, end_inter, _ = self.snapper.snap(
                end_coord["x"], end_coord["y"])
            if not start_inter or not end_inter:
                raise ApiError(
                    status=400, code="INVALID_REQUEST",
                    message="failed to snap coordinates to road network",
                    retryable=False,
                )
        else:
            # Fallback: intersection-based
            start_inter = params.get("startIntersection")
            end_inter = params.get("endIntersection")

        if not start_inter or not end_inter:
            raise ApiError(
                status=400, code="INVALID_REQUEST",
                message="startCoord/endCoord (x,y) or startIntersection/endIntersection required",
                retryable=False,
            )

        path = self.planner.find_path(start_inter, end_inter)
        if not path:
            raise ApiError(
                status=400, code="NO_ROUTE_FOUND",
                message=f"no path from {start_inter} to {end_inter}",
                retryable=False,
            )

        road_route = self._intersections_to_roads(path)

        self.ev_sessions[sid][ev_id] = EVSession(
            ev_id=ev_id,
            ev_type=params.get("evType", "fire_truck"),
            priority=params.get("priority", 1),
            max_speed=params.get("maxSpeed", 20.0),
            start_time=params.get("startTime", 30.0),
            route=path,
            route_roads=road_route,
        )

        edge_time = cfg.ROAD_LENGTH / cfg.SPEED_LIMIT
        return {
            "sid": sid,
            "evId": ev_id,
            "evType": params.get("evType", "fire_truck"),
            "priority": params.get("priority", 1),
            "route": path,
            "routeRoads": road_route,
            "estimatedTravelTime": round(len(path) * edge_time, 1),
        }

    def step(self, sid: str, engine: Any, sim_time: float) -> Dict[str, int]:
        """Run EV detection and signal adjustment for one frame.

        Returns {intersection_id: phase_index} overrides to apply.
        """
        if sid not in self.ev_sessions or not self.ev_sessions[sid]:
            return {}
        if sid not in self.handled:
            self.handled[sid] = set()

        signal_overrides: Dict[str, int] = {}
        road_by_id = self.road_index.get(sid, {})
        phase_counts = self.phase_counts.get(sid, {})
        approach_phases = self.approach_phases.get(sid, {})

        for ev_id, ev_session in self.ev_sessions[sid].items():
            vehicle_info = self._get_vehicle_info(engine, ev_id)
            if not vehicle_info:
                continue

            current_road = str(vehicle_info.get("road", ""))
            distance = float(vehicle_info.get("distance", 0))
            speed = float(vehicle_info.get("speed", 0))

            road_length = self._road_length(current_road, road_by_id)
            detection = self.detector.poll_vehicle(
                ev_id,
                {"road": current_road, "distance": distance, "speed": speed},
                sim_time,
                road_length=road_length,
            )

            if not detection:
                continue

            inter_id = detection["intersection_id"]
            handle_key = f"{ev_id}:{inter_id}"
            if handle_key in self.handled[sid]:
                continue

            ev_session.passed_intersections.add(inter_id)
            self.handled[sid].add(handle_key)

            approach_dir = get_approach_direction(current_road)
            approach_road = self._find_approach_road(ev_session, inter_id, road_by_id)
            pri_green = self._get_pri_green_phases(
                inter_id, approach_road, approach_phases, phase_counts)

            try:
                cityflow_phase = engine.get_tl_phase(inter_id)
            except Exception:
                cityflow_phase = 0
            current_phase = int(cityflow_phase) + 1

            pc = phase_counts.get(inter_id, 4)
            signal = SignalState(
                intersection_id=inter_id, current_phase=current_phase,
                phase_count=pc, phase_durations=[30] * pc,
                phase_elapsed=0.0,
            )

            ta = detection["distance_to_stop"] / max(speed, 0.1)
            td = 30.0

            decision, adjustment = self.strategy.decide(
                ta, td, signal, sim_time, approach_dir, pri_green,
                approach_road or "")

            self.logger.log(
                timestamp=sim_time, ev_id=ev_id, event_type="detection",
                intersection_id=inter_id, current_phase=current_phase,
                decision=decision, ta=ta, td=td,
                signal_adjustment=adjustment,
                ev_position=detection["distance_to_stop"], ev_speed=speed,
            )

            if decision == SignalStrategy.DECISION_GREEN_EXTEND:
                signal_overrides[inter_id] = current_phase
            elif decision == SignalStrategy.DECISION_RED_EARLY:
                if pri_green:
                    signal_overrides[inter_id] = pri_green[0]
            elif decision == SignalStrategy.DECISION_FORCE_GREEN:
                if pri_green:
                    signal_overrides[inter_id] = pri_green[0]

            if sid in self.signal_states and inter_id in self.signal_states[sid]:
                self.signal_states[sid][inter_id].current_phase = (
                    signal_overrides.get(inter_id, current_phase))

        return signal_overrides

    def get_ev_status(self, sid: str, ev_id: Optional[str] = None) -> list:
        """Get status of registered EVs."""
        if sid not in self.ev_sessions:
            return []
        result = []
        for eid, ev in self.ev_sessions[sid].items():
            if ev_id and eid != ev_id:
                continue
            result.append({
                "evId": eid, "evType": ev.ev_type,
                "priority": ev.priority,
                "route": ev.route, "routeRoads": ev.route_roads,
                "passedIntersections": sorted(list(ev.passed_intersections)),
            })
        return result

    def has_evs(self, sid: str) -> bool:
        """Check if session has any registered EVs."""
        return sid in self.ev_sessions and len(self.ev_sessions[sid]) > 0

    # ---- internal helpers ----

    def _get_vehicle_info(self, engine: Any, ev_id: str) -> Optional[dict]:
        """Safely get vehicle info from CityFlow engine."""
        try:
            info = engine.get_vehicle_info(ev_id)
            if info:
                return info
        except Exception:
            pass
        try:
            vehicles = engine.get_vehicles(include_waiting=False)
            for v in vehicles:
                vid = v if isinstance(v, str) else v.get("id", "")
                if vid == ev_id:
                    return engine.get_vehicle_info(ev_id)
        except Exception:
            pass
        return None

    def _road_length(self, road_id: str, road_by_id: dict) -> float:
        """Get road length from road data."""
        if road_id in road_by_id:
            pts = road_by_id[road_id].get("points", [])
            if len(pts) >= 2:
                dx = pts[-1]["x"] - pts[0]["x"]
                dy = pts[-1]["y"] - pts[0]["y"]
                return (dx**2 + dy**2)**0.5
        return cfg.ROAD_LENGTH

    def _find_approach_road(self, ev_session: EVSession, inter_id: str,
                            road_by_id: dict) -> Optional[str]:
        """Find the road the EV uses to approach this intersection."""
        for rid in ev_session.route_roads:
            if rid in road_by_id:
                rd = road_by_id[rid]
                if rd.get("endIntersection") == inter_id:
                    return rid
        return None

    def _get_pri_green_phases(self, inter_id: str, approach_road: Optional[str],
                               approach_phases: dict, phase_counts: dict) -> List[int]:
        """Get priority green phase indices for EV at this intersection."""
        if approach_road and inter_id in approach_phases:
            phases = approach_phases[inter_id].get(approach_road, [])
            if phases:
                return phases
        return list(range(phase_counts.get(inter_id, 4)))

    def _intersections_to_roads(self, path: List[str]) -> List[str]:
        """Convert intersection path to road-level route."""
        roads = []
        for i in range(len(path) - 1):
            fi, ti = path[i], path[i + 1]
            pf = fi.split("_")
            pt = ti.split("_")
            if len(pf) >= 3 and len(pt) >= 3:
                roads.append(f"road_{pf[1]}_{pf[2]}_{pt[1]}_{pt[2]}")
        return roads
