# -*- coding: utf-8 -*-
# ev_service.py - EV Priority Service Layer
# =========================================
# Full EV priority pipeline:
#   dispatch 闂?push_vehicle 闂?step (detect+conflict+signal+events+status)
# Called by engine.py during simulation steps and by server.py via dispatch.

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from app import ev_config as cfg
from app.errors import ApiError
from app.ev_priority import (
    ConflictResolver,
    DijkstraPathPlanner,
    EVDetector,
    EVLogger,
    EVRequest,
    LWRQueueModel,
    LWRParams,
    SignalStrategy,
    SignalState,
    CoordinateSnapper,
    get_approach_direction,
)

# ---- vehicle type default priorities ----
VEHICLE_TYPE_PRIORITY = {
    "fire_truck": 1,
    "ambulance": 1,
    "police": 2,
    "convoy": 3,
}


@dataclass
class EVSession:
    """EV tracking data for one EV in one simulation session."""
    ev_id: str
    ev_type: str = "fire_truck"
    priority: int = 1
    max_speed: float = 20.0
    dispatch_time: float = 0.0
    cf_vehicle_id: str = ""          # CityFlow internal vehicle ID after push
    start_road: str = ""
    pre_dispatch_vehicle_ids: set = field(default_factory=set)
    seen_in_engine: bool = False
    missing_since: Optional[float] = None
    route: List[str] = field(default_factory=list)
    route_roads: List[str] = field(default_factory=list)
    passed_intersections: set = field(default_factory=set)
    completed: bool = False
    completion_time: float = 0.0


class EVPriorityService:

    def __init__(self):
        self.planner = DijkstraPathPlanner()
        self.snapper = CoordinateSnapper()
        self.detector = EVDetector()
        self.lwr_model = LWRQueueModel(LWRParams())
        self.strategy = SignalStrategy(self.lwr_model)
        self.conflict_resolver = ConflictResolver()
        self._active_overrides: Dict[str, Dict[str, int]] = {}  # sid -> {inter_id: phase}
        self._override_owners: Dict[str, Dict[str, str]] = {}  # sid -> {inter_id: ev_id}
        self.logger = EVLogger()
        self.ev_sessions: Dict[str, Dict[str, EVSession]] = {}
        self.road_index: Dict[str, Dict[str, Any]] = {}
        self.handled: Dict[str, set] = {}
        self.phase_counts: Dict[str, Dict[str, int]] = {}
        self.approach_phases: Dict[str, Dict[str, Dict[str, List[int]]]] = {}

    # ================================================================
    #  Roadnet loading
    # ================================================================

    def load_roadnet(self, sid: str, roadnet: dict) -> None:
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
            road_links = idata.get("roadLinks", [])
            # approach_map: startRoad -> [phase_indices]  (road-level, for fallback)
            approach_map: Dict[str, List[int]] = {}
            # turn_map: (startRoad, endRoad) -> [phase_indices]  (turn-level, precise)
            turn_map: Dict[tuple, List[int]] = {}
            for pi, lp in enumerate(phases):
                for rli in lp.get("availableRoadLinks", []):
                    if rli < len(road_links):
                        rl_data = road_links[rli]
                        sr = rl_data.get("startRoad", "")
                        er = rl_data.get("endRoad", "")
                        if sr:
                            if pi not in approach_map.get(sr, []):
                                approach_map.setdefault(sr, []).append(pi)
                        if sr and er:
                            key = (sr, er)
                            if pi not in turn_map.get(key, []):
                                turn_map.setdefault(key, []).append(pi)
            ap[iid] = {"by_road": approach_map, "by_turn": turn_map}
        self.phase_counts[sid] = pc
        self.approach_phases[sid] = ap

    # ================================================================
    #  Dispatch: plan route + push vehicle into CityFlow
    # ================================================================

    def dispatch(self, sid: str, scene_id: str, roadnet: dict,
                 engine: Any, params: dict) -> dict:
        """Plan route, inject EV into CityFlow via push_vehicle, return route info."""
        if sid not in self.ev_sessions:
            self.ev_sessions[sid] = {}
        if sid not in self.handled:
            self.handled[sid] = set()

        self.load_roadnet(sid, roadnet)

        ev_id = params.get("evId", "ev_default")
        start_coord = params.get("startCoord")
        end_coord = params.get("endCoord")

        if start_coord and end_coord:
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
            start_inter = params.get("startIntersection")
            end_inter = params.get("endIntersection")

        if not start_inter or not end_inter:
            raise ApiError(
                status=400, code="INVALID_REQUEST",
                message="startCoord/endCoord or startIntersection/endIntersection required",
                retryable=False,
            )

        path = self.planner.find_path(start_inter, end_inter)
        if not path:
            raise ApiError(
                status=400, code="NO_ROUTE_FOUND",
                message=f"no path from {start_inter} to {end_inter}",
                retryable=False,
            )

        road_route = self._intersections_to_roads(sid, path)
        if not road_route:
            raise ApiError(
                status=400, code="INVALID_REQUEST",
                message="could not convert intersection path to road route",
                retryable=False,
            )

        ev_type = params.get("evType", "fire_truck")
        priority = params.get("priority")
        if priority is None:
            priority = VEHICLE_TYPE_PRIORITY.get(ev_type, 99)

        max_speed = params.get("maxSpeed", 20.0)

        # Push the EV into CityFlow
        sim_time = engine.get_current_time()
        veh_info = {
            "length": params.get("length", 7.0),
            "width": params.get("width", 2.5),
            "maxSpeed": max_speed,
        }
        try:
            before_ids = set()
            try:
                before_ids = set(engine.get_vehicles())
            except Exception:
                pass
            push_result = engine.push_vehicle(veh_info, road_route)
            print(f'[dispatch] push_vehicle returned: {push_result} type={type(push_result).__name__}', flush=True)
        except Exception as ex:
            raise ApiError(
                status=500, code="CITYFLOW_PUSH_FAILED",
                message=f"failed to push EV into CityFlow: {ex}",
                retryable=False,
            ) from ex

        # Find the vehicle ID CityFlow assigned
        cf_vehicle_id = push_result.strip() if isinstance(push_result, str) and push_result.strip() else self._find_pushed_vehicle(engine, road_route[0], before_ids, sid)

        self.ev_sessions[sid][ev_id] = EVSession(
            ev_id=ev_id,
            ev_type=ev_type,
            priority=priority,
            max_speed=max_speed,
            dispatch_time=sim_time,
            cf_vehicle_id=cf_vehicle_id,
            start_road=road_route[0],
            pre_dispatch_vehicle_ids=before_ids,
            route=path,
            route_roads=road_route,
        )

        self.logger.log(
            timestamp=sim_time, ev_id=ev_id, event_type="dispatched",
            detail=f"route={'->'.join(path)} cf_id={cf_vehicle_id}",
        )

        edge_time = cfg.ROAD_LENGTH / cfg.SPEED_LIMIT
        return {
            "sid": sid,
            "evId": ev_id,
            "evType": ev_type,
            "priority": priority,
            "cfVehicleId": cf_vehicle_id,
            "route": path,
            "routeRoads": road_route,
            "estimatedTravelTime": round(len(path) * edge_time, 1),
            "totalIntersections": len(path),
        }

    # ================================================================
    #  Step: detect EV, resolve conflicts, adjust signals, build events
    # ================================================================

    def step(self, sid: str, engine: Any, sim_time: float
             ) -> Tuple[Dict[str, int], List[dict], List[dict]]:
        """Run one step. Returns (signal_overrides, evEvents, evStatus)."""
        if sid not in self.ev_sessions or not self.ev_sessions[sid]:
            return {}, [], []
        if sid not in self.handled:
            self.handled[sid] = set()

        road_by_id = self.road_index.get(sid, {})
        phase_counts = self.phase_counts.get(sid, {})
        approach_phases = self.approach_phases.get(sid, {})

        # ---- Phase 1: detect all active EVs ----
        detections: List[dict] = []

        for ev_id, ev_session in self.ev_sessions[sid].items():
            if ev_session.completed:
                continue

            vehicle_info = self._get_vehicle_info(engine, ev_session)
            if not vehicle_info:
                if ev_session.missing_since is None:
                    ev_session.missing_since = sim_time
                grace_seconds = 1.0 if ev_session.seen_in_engine else 5.0
                if sim_time - ev_session.missing_since >= grace_seconds:
                    self._complete_ev(sid, ev_session, sim_time)
                continue
            ev_session.seen_in_engine = True
            ev_session.missing_since = None

            current_road = str(vehicle_info.get("road", ""))
            distance = float(vehicle_info.get("distance", 0))
            speed = float(vehicle_info.get("speed", 0))
            road_length = self._road_length(current_road, road_by_id)

            detection = self.detector.poll_vehicle(
                ev_session.cf_vehicle_id or ev_id,
                {"road": current_road, "distance": distance, "speed": speed},
                sim_time,
                road_length=road_length,
                is_detected_ev=True,
            )
            if not detection:
                continue

            inter_id = detection["intersection_id"]
            handle_key = f"{ev_id}:{inter_id}"
            if handle_key in self.handled[sid]:
                continue

            detections.append({
                "ev_id": ev_id,
                "ev_session": ev_session,
                "detection": detection,
                "handle_key": handle_key,
                "current_road": current_road,
            })

        if not detections:
            return dict(self._active_overrides.get(sid, {})), [], self._build_ev_status(sid, sim_time)

        # ---- Phase 2: conflict resolution per intersection ----
        by_intersection: Dict[str, List[dict]] = {}
        for d in detections:
            by_intersection.setdefault(d["detection"]["intersection_id"], []).append(d)

        signal_overrides: Dict[str, int] = {}
        signal_override_owners: Dict[str, str] = {}
        ev_events: List[dict] = []

        for inter_id, group in by_intersection.items():
            requests: List[EVRequest] = []
            for d in group:
                ev = d["ev_session"]
                req = EVRequest(
                    ev_id=d["ev_id"],
                    priority=ev.priority,
                    path=ev.route,
                    trigger_time=sim_time,
                )
                requests.append(req)

            for req in requests:
                self.conflict_resolver.register(req)

            winner = self.conflict_resolver.resolve_at_intersection(inter_id, sim_time)
            self.conflict_resolver.active_requests.clear()

            for d in group:
                ev_id = d["ev_id"]
                ev_session = d["ev_session"]
                detection = d["detection"]
                is_winner = (winner is not None and winner.ev_id == ev_id)

                if is_winner:
                    self.handled[sid].add(d["handle_key"])
                    ev_session.passed_intersections.add(inter_id)

                    approach_dir = get_approach_direction(d["current_road"])
                    approach_road, next_road = self._find_approach_road(
                        ev_session, inter_id, road_by_id)
                    pri_green = self._get_pri_green_phases(
                        inter_id, approach_road, next_road, approach_phases, phase_counts)
                    pri_green = [p + 1 for p in pri_green]  # convert to 1-based
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
                        approach_phases={
                            road_id: [phase + 1 for phase in phases]
                            for road_id, phases in approach_phases.get(inter_id, {}).get("by_road", {}).items()
                        },
                    )

                    ta = detection["distance_to_stop"] / max(detection["speed"], 0.1)
                    # LWR dynamic queue dissipation time (paper Eq 5-12)
                    if pri_green:
                        cycle_length = pc * 30.0
                        t0_cycle = sim_time % cycle_length
                        tg = (pri_green[0] - 1) * 30.0
                        tr = pri_green[0] * 30.0
                        td = self.lwr_model.compute_dissipation_time(tr, tg, t0_cycle)
                    else:
                        td = 30.0

                    decision, adjustment = self.strategy.decide(
                        ta, td, signal, sim_time, approach_dir, pri_green,
                        approach_road or "")

                    resolved = current_phase
                    if decision == SignalStrategy.DECISION_GREEN_EXTEND:
                        resolved = current_phase
                    elif decision == SignalStrategy.DECISION_RED_EARLY:
                        resolved = pri_green[0] if pri_green else current_phase
                    elif decision == SignalStrategy.DECISION_FORCE_GREEN:
                        resolved = pri_green[0] if pri_green else current_phase

                    signal_overrides[inter_id] = resolved
                    signal_override_owners[inter_id] = ev_id

                    self.logger.log(
                        timestamp=sim_time, ev_id=ev_id, event_type="detection",
                        intersection_id=inter_id, current_phase=current_phase,
                        decision=decision, ta=ta, td=td,
                        signal_adjustment=adjustment,
                        ev_position=detection["distance_to_stop"],
                        ev_speed=detection["speed"],
                    )

                    ev_events.append({
                        "evId": ev_id,
                        "evType": ev_session.ev_type,
                        "priority": ev_session.priority,
                        "intersectionId": inter_id,
                        "decision": decision,
                        "phaseIndex": resolved,
                        "phaseIndexBefore": current_phase,
                        "timestamp": round(sim_time, 3),
                        "status": "granted",
                    })
                else:
                    ev_events.append({
                        "evId": ev_id,
                        "evType": ev_session.ev_type,
                        "priority": ev_session.priority,
                        "intersectionId": inter_id,
                        "decision": "blocked",
                        "phaseIndex": -1,
                        "phaseIndexBefore": -1,
                        "timestamp": round(sim_time, 3),
                        "status": "blocked",
                        "blockedBy": winner.ev_id if winner else None,
                    })
                    self.logger.log(
                        timestamp=sim_time, ev_id=ev_id, event_type="blocked",
                        intersection_id=inter_id, decision="blocked",
                        detail=f"blocked by {winner.ev_id}" if winner else "",
                    )

        # Persist overrides: keep overriding until EV passes the intersection
        if sid not in self._active_overrides:
            self._active_overrides[sid] = {}
        if sid not in self._override_owners:
            self._override_owners[sid] = {}
        self._active_overrides[sid].update(signal_overrides)
        self._override_owners[sid].update(signal_override_owners)

        # Clear overrides for intersections the EV has already passed
        for ev_id, ev in self.ev_sessions.get(sid, {}).items():
            if ev.completed:
                for iid in ev.route:
                    self._active_overrides.get(sid, {}).pop(iid, None)
                continue
            if not ev.cf_vehicle_id:
                continue
            try:
                info = engine.get_vehicle_info(ev.cf_vehicle_id)
                if info:
                    cur_road = str(info.get('road', ''))
                    # Find current road index in route_roads
                    for idx, rid in enumerate(ev.route_roads):
                        if rid == cur_road:
                            # EV has passed intersections 0..idx
                            # (intersection idx+1 is the next one)
                            passed = set(ev.route[:idx + 1])
                            stale = [iid for iid in self._active_overrides.get(sid, {})
                                     if iid in passed]
                            for iid in stale:
                                del self._active_overrides[sid][iid]
                                self._override_owners.get(sid, {}).pop(iid, None)
                            break
            except Exception:
                pass

        # Merge new + persisted overrides
        result = dict(self._active_overrides.get(sid, {}))
        return result, ev_events, self._build_ev_status(sid, sim_time)

    # ================================================================
    #  Status
    # ================================================================

    def _build_ev_status(self, sid: str, sim_time: float) -> List[dict]:
        result = []
        for ev_id, ev in self.ev_sessions.get(sid, {}).items():
            elapsed = sim_time - ev.dispatch_time if not ev.completed else (
                ev.completion_time - ev.dispatch_time)
            result.append({
                "evId": ev_id,
                "evType": ev.ev_type,
                "priority": ev.priority,
                "route": ev.route,
                "passedCount": len(ev.passed_intersections),
                "totalCount": len(ev.route),
                "completed": ev.completed,
                "elapsedTime": round(max(0, elapsed), 1),
            })
        return result

    def has_evs(self, sid: str) -> bool:
        return any(not ev.completed for ev in self.ev_sessions.get(sid, {}).values()) or bool(
            self._active_overrides.get(sid)
        )

    def release_session(self, sid: str) -> None:
        self.ev_sessions.pop(sid, None)
        self.road_index.pop(sid, None)
        self.handled.pop(sid, None)
        self.phase_counts.pop(sid, None)
        self.approach_phases.pop(sid, None)
        self._active_overrides.pop(sid, None)
        self._override_owners.pop(sid, None)

    def _complete_ev(self, sid: str, ev: EVSession, sim_time: float) -> None:
        if ev.completed:
            return
        ev.completed = True
        ev.completion_time = sim_time
        owned_intersections = [
            intersection_id
            for intersection_id, ev_id in self._override_owners.get(sid, {}).items()
            if ev_id == ev.ev_id
        ]
        for intersection_id in owned_intersections:
            self._active_overrides.get(sid, {}).pop(intersection_id, None)
            self._override_owners.get(sid, {}).pop(intersection_id, None)
        self.logger.log(
            timestamp=sim_time,
            ev_id=ev.ev_id,
            event_type="completed",
            detail="vehicle left CityFlow; signal overrides released",
        )

    # ================================================================
    #  Internal helpers
    # ================================================================

    def _find_pushed_vehicle(self, engine: Any, start_road: str,
                             before_ids: set = None, sid: str = "") -> str:
        """Find the CityFlow vehicle ID of the just-pushed EV."""
        # Collect already-assigned vehicle IDs to avoid cross-EV conflict
        occupied = set()
        if sid and sid in self.ev_sessions:
            for ev in self.ev_sessions[sid].values():
                if ev.cf_vehicle_id:
                    occupied.add(ev.cf_vehicle_id)
        # Approach 1: set difference AND filter by start_road, exclude occupied
        if before_ids is not None:
            try:
                current = set(engine.get_vehicles())
                new = current - before_ids
                for vid in new:
                    if str(vid) in occupied:
                        continue
                    try:
                        info = engine.get_vehicle_info(str(vid))
                        if info and str(info.get('road', '')) == start_road:
                            return str(vid)
                    except Exception:
                        pass
            except Exception:
                pass
        # Approach 2: iterate by index
        try:
            count = engine.get_vehicle_count()
            for i in range(count):
                vid = engine.get_vehicle_id(i)
                if str(vid) in occupied:
                    continue
                info = engine.get_vehicle_info(vid)
                if info and str(info.get("road", "")) == start_road:
                    return str(vid)
        except Exception:
            pass
        return ""

    def _get_vehicle_info(self, engine: Any, ev: EVSession) -> Optional[dict]:
        if not ev.cf_vehicle_id:
            ev.cf_vehicle_id = self._find_pushed_vehicle(
                engine,
                ev.start_road,
                ev.pre_dispatch_vehicle_ids,
            )
        if ev.cf_vehicle_id:
            try:
                return engine.get_vehicle_info(ev.cf_vehicle_id)
            except Exception:
                pass
        return None

    def _road_length(self, road_id: str, road_by_id: dict) -> float:
        if road_id in road_by_id:
            pts = road_by_id[road_id].get("points", [])
            if len(pts) >= 2:
                dx = pts[-1]["x"] - pts[0]["x"]
                dy = pts[-1]["y"] - pts[0]["y"]
                return (dx**2 + dy**2)**0.5
        return cfg.ROAD_LENGTH

    def _find_approach_road(self, ev: EVSession, inter_id: str,
                            road_by_id: dict):
        """Return (approach_road, next_road) for the given intersection.
        Uses roadnet data: approach_road is the road whose endIntersection == inter_id.
        next_road is the subsequent road in the EV route."""
        for i, rid in enumerate(ev.route_roads):
            if rid in road_by_id:
                rd = road_by_id[rid]
                if rd.get("endIntersection") == inter_id:
                    next_road = ev.route_roads[i + 1] if i + 1 < len(ev.route_roads) else None
                    return rid, next_road
        return None, None

    def _get_pri_green_phases(self, inter_id: str, approach_road: Optional[str],
                               next_road: Optional[str],
                               approach_phases: dict, phase_counts: dict) -> List[int]:
        """Get correct priority green phases for EV at this intersection.
        Priority 1: turn-level mapping (approach_road, next_road) -> most precise.
        Priority 2: road-level mapping (approach_road) -> fallback.
        Priority 3: all phases."""
        if inter_id in approach_phases:
            ap_data = approach_phases[inter_id]
            # Priority 1: turn-level (tuple key)
            if approach_road and next_road:
                turn_map = ap_data.get("by_turn", {})
                key = (approach_road, next_road)
                phases = turn_map.get(key, [])
                if phases:
                    return phases
            # Priority 2: road-level
            if approach_road:
                road_map = ap_data.get("by_road", {})
                phases = road_map.get(approach_road, [])
                if phases:
                    return phases
        # Fallback: all phases
        return list(range(phase_counts.get(inter_id, 4)))

    def _intersections_to_roads(self, sid: str, path: List[str]) -> List[str]:
        roads = []
        road_by_id = self.road_index.get(sid, {})
        inter_to_road = {}
        for rid, rdata in road_by_id.items():
            si = rdata.get("startIntersection", "")
            ei = rdata.get("endIntersection", "")
            if si and ei:
                inter_to_road[(si, ei)] = rid
        for i in range(len(path) - 1):
            key = (path[i], path[i + 1])
            if key in inter_to_road:
                roads.append(inter_to_road[key])
        return roads
