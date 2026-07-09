# -*- coding: utf-8 -*-
# run_simulation.py - åºæ¥è½¦è¾ä¼åéè¡ä»¿çä¸»ç¨åº?
# ==============================================

import os
import sys
import time
import json
from collections import defaultdict

from app import ev_config as cfg
from app.network_utils import generate_roadnet, generate_flow
from app.ev_priority import (
    DijkstraPathPlanner, LWRQueueModel, LWRParams,
    SignalStrategy, SignalState, IntersectionCoordinator,
    RecoveryManager, ConflictResolver, EVLogger, CoordinateSnapper,
    EVDetector, EVRequest, get_approach_direction
)

try:
    import cityflow
    CITYFLOW_AVAILABLE = True
except ImportError:
    CITYFLOW_AVAILABLE = False
    print("[WARN] CityFlow not installed. Run: pip install cityflow")
    print("[WARN] Running in simulation mode (no CityFlow engine)")

class SimulationRunner:
    def __init__(self, roadnet_path="roadnet.json", flow_path="flow.json"):
        self.roadnet_path = roadnet_path
        self.flow_path = flow_path
        self.roadnet = None
        self.flow = None
        self.eng = None

        self.lwr_model = LWRQueueModel(LWRParams())
        self.strategy = SignalStrategy(self.lwr_model)
        self.coordinator = IntersectionCoordinator()
        self.recovery = RecoveryManager()
        self.conflict_resolver = ConflictResolver()
        self.logger = EVLogger()
        self.detector = EVDetector()
        self.planner = DijkstraPathPlanner()
        
        # Priority override via env var (more reliable than config toggle)
        self._pri_env = os.environ.get("EV_PRIORITY", "").upper()
        if self._pri_env == "1" or self._pri_env == "TRUE":
            self.ev_priority_enabled = True
        elif self._pri_env == "0" or self._pri_env == "FALSE":
            self.ev_priority_enabled = False
        else:
            self.ev_priority_enabled = getattr(cfg, "EV_PRIORITY_ENABLED", True)

        self.signal_states = {}
        self.phase_counts = {}  # per-intersection actual phase count
        self.approach_phases = {}  # {inter_id: {approach_road_id: [green_phase_indices]}}
        self.turn_phases = {}       # {inter_id: {(start_road, end_road): [phase_indices]}}
        self.green_extensions = {}  # {(inter_id, phase): extend_seconds}
        self.phase_shortenings = {}  # {(inter_id, phase): shorten_seconds}
        
        # Multi-EV support
        self.ev_registry = {}  # {ev_id: {config, actual_id, path, route_roads, passed, detected_at, last_decision, ...}}
        self.ev_configs = []   # list of EV configs from cfg.EMERGENCY_VEHICLES

        self._init_network()
        self._init_signal_states()

    def _init_network(self):
        if not os.path.exists(self.roadnet_path):
            self.roadnet = generate_roadnet(self.roadnet_path)
        else:
            with open(self.roadnet_path, "r", encoding="utf-8") as f:
                self.roadnet = json.load(f)

        self.intersections = {i["id"]: i for i in self.roadnet["intersections"]}
        self.roads = {r["id"]: r for r in self.roadnet["roads"]}
        # Store actual phase count per intersection from roadnet
        for iid, idata in self.intersections.items():
            if not idata.get("virtual"):
                self.phase_counts[iid] = len(idata["trafficLight"]["lightphases"])
                # Build approach_road -> green_phase mapping
                approach_map = {}  # start_road -> [phase_indices]
                turn_map = {}       # (start_road, end_road) -> [phase_indices]
                for rl in idata["roadLinks"]:
                    start_road = rl["startRoad"]
                    end_road = rl["endRoad"]
                    if start_road not in approach_map:
                        approach_map[start_road] = []
                    key = (start_road, end_road)
                    if key not in turn_map:
                        turn_map[key] = []
                for pi, lp in enumerate(idata["trafficLight"]["lightphases"]):
                    for rli in lp["availableRoadLinks"]:
                        rl = idata["roadLinks"][rli]
                        start_road = rl["startRoad"]
                        end_road = rl["endRoad"]
                        if pi not in approach_map[start_road]:
                            approach_map[start_road].append(pi)
                        key = (start_road, end_road)
                        if pi not in turn_map[key]:
                            turn_map[key].append(pi)
                self.approach_phases[iid] = approach_map
                self.turn_phases[iid] = turn_map
        for iid in self.approach_phases:
            if iid in ("intersection_0_1", "intersection_0_2", "intersection_1_0"):
                print(f"  [INIT] approach_phases[{iid}] = {self.approach_phases[iid]}")

        if not os.path.exists(self.flow_path):
            self.flow = generate_flow(self.roadnet, self.flow_path)
        else:
            with open(self.flow_path, "r", encoding="utf-8") as f:
                self.flow = json.load(f)

        # Find EV in flow by vehicle ID first, then by unique startTime
        # Interactive coordinate input (before building EV registry)
        if getattr(cfg, 'INTERACTIVE_COORD', False):
            import config as _cfg
            print("\n" + "="*50)
            print("  Enter EV coordinates")
            print("  Roadnet: x=[0,1200], y=[0,1600]")
            print("  (1_1=(0,0) top-left, 4_3=(1200,1600) bottom-right)")
            s_raw = input("  Start x y: ").strip()
            e_raw = input("  End   x y: ").strip()
            if s_raw:
                sx, sy = map(float, s_raw.split())
                from ev_priority import CoordinateSnapper
                snapper = CoordinateSnapper()
                snapper.load_roadnet(self.roadnet)
                snapped = snapper.snap_start(sx, sy)
                print(f"  [SNAP] Start -> {snapped}")
                _cfg.EV_START_INTERSECTION = snapped
                _cfg.EV_START_COORD = (sx, sy)
            if e_raw:
                ex, ey = map(float, e_raw.split())
                if not s_raw:
                    from ev_priority import CoordinateSnapper
                    snapper = CoordinateSnapper()
                    snapper.load_roadnet(self.roadnet)
                snapped = snapper.snap_end(ex, ey)
                print(f"  [SNAP] End   -> {snapped}")
                _cfg.EV_END_INTERSECTION = snapped
                _cfg.EV_END_COORD = (ex, ey)
            print("="*50)
        
        # Load all EVs from config
        self.ev_configs = getattr(cfg, 'EMERGENCY_VEHICLES', [])
        if not self.ev_configs:
            # Fallback: single EV from old config
            self.ev_configs = [{
                "id": cfg.EV_ID, "type": cfg.EV_TYPE, "priority": cfg.EV_PRIORITY,
                "max_speed": cfg.SPEED_LIMIT * cfg.EV_SPEED_FACTOR,
                "start_time": 100, "route": []
            }]
            # Try to find route from flow (only if config has no coords/inter set)
            has_route_input = (bool(ev_cfg.get("route")) or 
                              bool(getattr(cfg, "EV_START_INTERSECTION", "")) or
                              bool(getattr(cfg, "EV_START_COORD", None)))
            if not has_route_input:
                ev_flows = [v for v in self.flow if v.get("vehicle", {}).get("id") == cfg.EV_ID]
                if not ev_flows:
                    ev_flows = [v for v in self.flow if v.get("startTime", 0) >= 100]
                if ev_flows:
                    self.ev_configs[0]["route"] = ev_flows[0]["route"]
        
        # Build registry for each EV
        for ev_cfg in self.ev_configs:
            ev_id = ev_cfg["id"]
            route_roads = ev_cfg.get("route", [])
            # Auto-compute via Dijkstra from roadnet if route is empty
            if not route_roads:
                # If coordinates provided, snap to nearest intersections
                start_inter = getattr(cfg, "EV_START_INTERSECTION", None)
                end_inter = getattr(cfg, "EV_END_INTERSECTION", None)
                start_coord = getattr(cfg, "EV_START_COORD", None)
                end_coord = getattr(cfg, "EV_END_COORD", None)
                if (start_coord or end_coord) and self.roadnet:
                    from ev_priority import CoordinateSnapper
                    snapper = CoordinateSnapper()
                    snapper.load_roadnet(self.roadnet)
                    if start_coord:
                        snapped = snapper.snap_start(start_coord[0], start_coord[1])
                        print(f"[SNAP] Start ({start_coord[0]}, {start_coord[1]}) -> {snapped}")
                        start_inter = snapped
                    if end_coord:
                        snapped = snapper.snap_end(end_coord[0], end_coord[1])
                        print(f"[SNAP] End ({end_coord[0]}, {end_coord[1]}) -> {snapped}")
                        end_inter = snapped
                if start_inter and end_inter and self.roadnet:
                    self.planner.load_roadnet(self.roadnet)
                    ev_intersections = self.planner.find_path(start_inter, end_inter)
                    if ev_intersections:
                        for i in range(len(ev_intersections) - 1):
                            fi, ti = ev_intersections[i], ev_intersections[i+1]
                            fr, fc = int(fi.split("_")[1]), int(fi.split("_")[2])
                            tr, tc = int(ti.split("_")[1]), int(ti.split("_")[2])
                            dr, dc = tr - fr, tc - fc
                            if dr == 1: direction = 0
                            elif dc == 1: direction = 1
                            elif dr == -1: direction = 2
                            elif dc == -1: direction = 3
                            else: continue
                            rid = f"road_{fr}_{fc}_{direction}"
                            if rid in self.roads:
                                route_roads.append(rid)
                        print(f"[INFO] Auto-route from {start_inter} to {end_inter}: {ev_intersections}")
                        # Also update EV route in flow file (memory + disk)
                        for v in self.flow:
                            if v.get("vehicle", {}).get("id") == ev_id:
                                v["route"] = route_roads
                                break
                        else:
                            # No vehicle ID match; update by startTime
                            for v in self.flow:
                                if v.get("startTime", 0) >= 100 and v.get("endTime", 0) <= 100:
                                    v["route"] = route_roads
                                    break
                        # Write back to disk so CityFlow picks it up
                        with open(self.flow_path, "w") as fw:
                            json.dump(self.flow, fw, indent=2, ensure_ascii=False)
                    else:
                        print(f"[WARN] Dijkstra failed for {start_inter} -> {end_inter}")
            ev_path = self._roads_to_intersections(route_roads)
            self.ev_registry[ev_id] = {
                "config": ev_cfg,
                "actual_id": None,  # CityFlow-assigned ID
                "route_roads": route_roads,
                "path": ev_path,
                "passed_intersections": set(),
                "detected_at": {},
                "last_decision_time": {},
                "start_time": ev_cfg.get("start_time", 100),
                "priority": ev_cfg.get("priority", 1),
                "exited": False,
            }
            print(f"[INFO] EV {ev_id} (pri={ev_cfg['priority']}): path={ev_path}")
            print(f"       route={route_roads}")
        
        # Set coordinator with first EV's path (backward compat)
        if self.ev_configs:
            self.coordinator.set_ev_path(self.ev_registry[self.ev_configs[0]["id"]]["path"])

    def _roads_to_intersections(self, road_ids):
        path = []
        for rid in road_ids:
            if rid in self.roads:
                r = self.roads[rid]
                si = r["startIntersection"]
                if not path or path[-1] != si:
                    path.append(si)
        if road_ids and road_ids[-1] in self.roads:
            ei = self.roads[road_ids[-1]]["endIntersection"]
            if not path or path[-1] != ei:
                path.append(ei)
        return path

    def _init_signal_states(self):
        for inter_id in self.intersections:
            idata = self.intersections[inter_id]
            durations = []
            if not idata.get("virtual") and "trafficLight" in idata:
                for lp in idata["trafficLight"]["lightphases"]:
                    durations.append(lp["time"])
            self.signal_states[inter_id] = SignalState(
                intersection_id=inter_id,
                current_phase=0,
                phase_elapsed=0.0,
                phase_durations=durations
            )

    def run(self, max_steps=None):
        steps = max_steps or cfg.SIMULATION_STEPS
        print(f"\n{'='*60}")
        print(f"  Emergency Vehicle Priority Simulation")
        print(f"  Duration: {steps}s ({steps/60:.1f}min)")
        print(f"  Grid: {cfg.GRID_ROWS}x{cfg.GRID_COLS}, {len(self.intersections)} intersections")
        print(f"  EV: {cfg.EV_ID} ({cfg.EV_TYPE}, priority {cfg.EV_PRIORITY})")
        print(f"  Priority Enabled: {self.ev_priority_enabled} (env EV_PRIORITY={os.environ.get('EV_PRIORITY', 'unset')})")
        print(f"{'='*60}\n")

        if CITYFLOW_AVAILABLE:
            self._run_cityflow(steps)
        else:
            self._run_simulated(steps)

        self._print_summary()
        self._save_results()

    def _determine_authorized_evs(self, current_time, vehicles):
        """
        为每个有 EV 接近的路口，唯一指定一个可进行信号控制的 EV。
        返回: dict {intersection_id: ev_id}
        排序规则: 数字越小优先级越高 (fire_truck priority=1 < ambulance priority=2)
        """
        ev_approach = defaultdict(list)

        for ev_id, ev_data in self.ev_registry.items():
            actual_id = ev_data["actual_id"]
            if actual_id is None or actual_id not in vehicles:
                continue
            if ev_data.get("exited"):
                continue

            try:
                info = self.eng.get_vehicle_info(actual_id)
            except RuntimeError:
                continue

            cur_road = info.get("road", "")
            if cur_road not in self.roads:
                continue
            cur_inter = self.roads[cur_road]["endIntersection"]
            if not cur_inter or cur_inter not in self.intersections:
                continue

            if cur_inter in ev_data["passed_intersections"]:
                continue

            distance = float(info.get("distance", 0))
            speed = float(info.get("speed", 0))
            effective_speed = max(speed, 2.0)
            eta = current_time + distance / effective_speed

            ev_approach[cur_inter].append({
                "ev_id": ev_id,
                "eta": eta,
                "priority": ev_data["priority"],
                "distance": distance,
                "speed": speed,
                "ev_data": ev_data,
                "info": info,
            })

        authorized = {}  # {inter_id: set of ev_ids allowed to make decisions}
        far_grant_threshold = getattr(cfg, "AUTH_FAR_GRANT_THRESHOLD", 30.0)

        for inter_id, candidates in ev_approach.items():
            if not candidates:
                continue

            # priority ?????fire priority=1 > ambulance priority=2?
            candidates.sort(key=lambda x: (x["priority"], x["eta"]))

            top = candidates[0]

            # FAR_GRANT: ???? EV ??? ? ??????????? EV
            if len(candidates) > 1 and (top["eta"] - current_time) > far_grant_threshold:
                primary = candidates[1]
            else:
                primary = top

            # ?????????? EV
            allowed = {primary["ev_id"]}
            primary_road = primary["info"].get("road", "")

            # ?????????????????? EV ?????
            # ???????????? EV ??????????
            for c in candidates:
                if c["ev_id"] in allowed:
                    continue
                if c["info"].get("road", "") == primary_road:
                    allowed.add(c["ev_id"])
                    print(f"  [SHARE] {c['ev_id']} shares green with {primary['ev_id']} at {inter_id} (same road {primary_road})")

            authorized[inter_id] = allowed

        return authorized

    def _run_cityflow(self, steps):
        # CityFlow requires a JSON config file
        cf_cfg = {
            "interval": cfg.SIMULATION_STEP_INTERVAL,
            "seed": 42,
            "dir": "",
            "roadnetFile": os.path.abspath(self.roadnet_path),
            "flowFile": os.path.abspath(self.flow_path),
            "rlTrafficLight": True,
            "saveReplay": False
        }
        os.makedirs("cityflow_output", exist_ok=True)
        cfg_path = "cityflow_config.json"
        with open(cfg_path, "w") as f:
            json.dump(cf_cfg, f)

        self.eng = cityflow.Engine(os.path.abspath(cfg_path), thread_num=1)

        for step in range(steps):
            self.eng.next_step()
            current_time = self.eng.get_current_time()
            vehicles = self.eng.get_vehicles()

            if step % 60 == 0 and step > 0:
                print(f"  [{(current_time/60):.0f}min] running...")

            self._process_traffic_lights(current_time)

            # EV priority toggle: set EV_PRIORITY_ENABLED=False for baseline
            ev_priority_on = self.ev_priority_enabled
            
            # Multi-EV detection and processing
            for vid in vehicles:
                # Check if any undetected EV matches this vehicle
                for ev_id, ev_data in list(self.ev_registry.items()):
                    if ev_data["actual_id"] is not None:
                        continue  # already detected
                    if current_time < ev_data["start_time"]:
                        continue  # EV not yet spawned
                    
                    info = self.eng.get_vehicle_info(vid)
                    start_road = ev_data["route_roads"][0] if ev_data["route_roads"] else ""
                    if info and info.get("road", "") == start_road:
                        # Match by flow index
                        ev_idx = None
                        for i, v in enumerate(self.flow):
                            if v.get("vehicle", {}).get("id") == ev_id:
                                ev_idx = i
                                break
                        if ev_idx is not None:
                            expected_vid = f"flow_{ev_idx}_0"
                            if vid == expected_vid:
                                ev_data["actual_id"] = vid
                                self.logger.log(current_time, vid, "EV_IDENTIFIED", detail=f"EV {ev_id} on {start_road}")
                                print(f"  [EV_FOUND] {ev_id} = {vid} at t={current_time:.0f}s (pri={ev_data['priority']})")
            
            # ===== 基于授权的 EV 优先处理 (替代旧的分布式冲突判断) =====
            intersection_auth = self._determine_authorized_evs(current_time, vehicles)

            # Process detected EVs (仅获得该路口授权的 EV 才执行信号决策)
            for ev_id, ev_data in self.ev_registry.items():
                actual_id = ev_data["actual_id"]
                if actual_id is None:
                    continue
                if actual_id not in vehicles:
                    # EV may have exited ? only fire once
                    if len(ev_data["passed_intersections"]) > 0 and not ev_data.get("exited"):
                        ev_data["exited"] = True
                        print(f"  [EXIT] EV {ev_id} exited via {len(ev_data['passed_intersections'])} intersections")
                        for inter_id in ev_data["passed_intersections"]:
                            self.recovery.start_transition(inter_id)
                        self.logger.log(current_time, actual_id, "RECOVERY_START",
                                       detail=f"EV {ev_id} exited")
                    continue

                info2 = self.eng.get_vehicle_info(actual_id)
                if not info2:
                    continue

                # 通过当前道路确定即将到达的路口
                cur_road = info2.get("road", "")
                if cur_road not in self.roads:
                    continue
                cur_inter = self.roads[cur_road]["endIntersection"]

                # 关键：只有获得该路口授权的 EV 才能执行决策
                if ev_id not in intersection_auth.get(cur_inter, set()):
                    continue

                # 授权 EV 执行原有的决策逻辑
                self._process_vehicle(ev_id, ev_data, info2, current_time)
            
            # Debug tracking
            if step % 60 == 0:
                for ev_id, ev_data in self.ev_registry.items():
                    if ev_data["actual_id"] and ev_data["actual_id"] in vehicles:
                        ev_info = self.eng.get_vehicle_info(ev_data["actual_id"])
                        if ev_info:
                            print(f"  [DEBUG t={current_time:.0f}] {ev_id}: road={ev_info.get('road','?')}, dist={float(ev_info.get('distance',0)):.0f}m, speed={float(ev_info.get('speed',0)):.1f}m/s, passed={len(ev_data['passed_intersections'])}")

    def _run_simulated(self, steps):
        print("[INFO] Simulation mode: algorithm demo without CityFlow\n")

        ev_speed = cfg.SPEED_LIMIT * cfg.EV_SPEED_FACTOR
        ev_pos = 0.0
        road_idx = 0

        ev_route_roads = []
        for v in self.flow:
            if v.get("vehicle", {}).get("id") == cfg.EV_ID:
                ev_route_roads = v["route"]
                break
        if not ev_route_roads:
            for v in self.flow:
                if v.get("startTime", 0) >= 100:
                    ev_route_roads = v["route"]
                    break

        for step in range(steps):
            current_time = float(step)
            if step % 60 == 0:
                print(f"  [{(current_time/60):.0f}min] running...")

            self._process_traffic_lights(current_time)

            if road_idx < len(ev_route_roads):
                current_road = ev_route_roads[road_idx]
                ev_pos += ev_speed
                road_len = cfg.ROAD_LENGTH

                veh_info = {
                    "road": current_road,
                    "distance": ev_pos,
                    "speed": ev_speed,
                    "lane": 0
                }
                self._process_vehicle(cfg.EV_ID, veh_info, current_time)

                if ev_pos >= road_len:
                    ev_pos = 0.0
                    road_idx += 1
                    if road_idx >= len(ev_route_roads):
                        print(f"\n[FINISH] EV completed route!")
                        break

    def _process_vehicle(self, ev_id, ev_data, veh_info, current_time):
        # Compute actual road length from roadnet
        cur_road = veh_info.get("road", "")
        road_len = 400.0  # default fallback (replaced below from roadnet)
        if cur_road and cur_road in self.roads:
            pts = self.roads[cur_road].get("points", [])
            if len(pts) >= 2:
                dx = pts[1]["x"] - pts[0]["x"]
                dy = pts[1]["y"] - pts[0]["y"]
                road_len = (dx**2 + dy**2)**0.5

        detection = self.detector.poll_vehicle(ev_data["actual_id"], veh_info, current_time, is_detected_ev=True, road_length=road_len)
        if not detection:
            return

        inter_id = detection["intersection_id"]
        # Cooldown: prevent re-triggering within 5 seconds (响应更灵敏，
        # 配合每个路口单 EV 授权机制，避免低优先级 EV 总吃不到绿灯)
        last = ev_data["last_decision_time"].get(inter_id, -100)
        if current_time - last < 5.0:
            return
        ev_data["last_decision_time"][inter_id] = current_time
        ev_data["detected_at"][inter_id] = current_time
        dist = detection["distance_to_stop"]
        speed = detection["speed"]
        if speed <= 0:
            # EV stuck in queue; use crawl speed as fallback
            speed = 2.0

        t_a = self.lwr_model.compute_ev_arrival_time(current_time, dist, speed)

        signal = self.signal_states.get(inter_id)
        if not signal:
            return

        approach_dir = get_approach_direction(detection["current_road"])

        t_d = self._estimate_queue_dissipation(inter_id, current_time, signal, approach_dir, ev_data)

        pri_green_phases = self._get_pri_green_phases(inter_id, approach_dir, None, ev_data)
        decision, adjustment = self.strategy.decide(
            t_a, t_d, signal, current_time, approach_dir, pri_green_phases
        )

        self.logger.log(
            current_time, ev_data["actual_id"], "EV_DETECTED", inter_id,
            current_phase=signal.current_phase, decision=decision,
            ta=t_a, td=t_d, signal_adjustment=adjustment,
            ev_position=dist, ev_speed=speed,
            detail=f"approach={approach_dir}"
        )

        print(f"  [DECIDE] {inter_id}: ta={t_a:.1f}s, td={t_d:.1f}s, phase={signal.current_phase}, elapsed={signal.phase_elapsed:.1f}, decision={decision}, approach={approach_dir}")
        if decision != SignalStrategy.DECISION_NO_ACTION:
            self._apply_signal_control(inter_id, decision, adjustment, current_time, signal, approach_dir, t_a, t_d, ev_data)

        downstream = self.coordinator.broadcast_eta(inter_id, t_a)
        if downstream:
            self.logger.log(current_time, ev_data["actual_id"], "BROADCAST_ETA", downstream,
                            ta=t_a, detail="downstream notification")
        win = self.coordinator.get_window(inter_id)
        if win and not win.fine_tune_applied:
            fine_tuned = self.coordinator.fine_tune(inter_id, t_a)
            if fine_tuned:
                self.logger.log(current_time, ev_data["actual_id"], "FINE_TUNE", inter_id,
                                ta=fine_tuned, detail="arrival recalibration")

        ev_data["passed_intersections"].add(inter_id)
        # Trigger recovery for this intersection immediately
        self.recovery.start_transition(inter_id)

    def _detect_by_path(self, vid, veh_info, current_time):
        """Fallback detection: track EV by road transitions along known path."""
        cur_road = str(veh_info.get("road", ""))
        distance = float(veh_info.get("distance", 0))
        speed = float(veh_info.get("speed", 0))
        if not cur_road:
            return None
        # Map road to downstream intersection
        parts = cur_road.split("_")
        if len(parts) >= 5:
            to_inter = f"intersection_{parts[3]}_{parts[4]}"
            if to_inter in ev_data["path"] and to_inter not in ev_data["detected_at"]:
                dist_to_int = cfg.ROAD_LENGTH - distance
                return {
                    "ev_id": vid,
                    "intersection_id": to_inter,
                    "distance_to_stop": dist_to_int,
                    "speed": speed if speed > 0 else cfg.SPEED_LIMIT * cfg.EV_SPEED_FACTOR,
                    "current_road": cur_road,
                    "timestamp": current_time
                }
        return None

    def _get_road_length(self, road_id):
        """Get actual road length from roadnet geometry."""
        if road_id in self.roads:
            pts = self.roads[road_id].get("points", [])
            if len(pts) >= 2:
                dx = pts[-1]["x"] - pts[0]["x"]
                dy = pts[-1]["y"] - pts[0]["y"]
                return (dx**2 + dy**2)**0.5
        return 400.0  # fallback

    def _get_queue_length_meters(self, inter_id, approach_road):
        """Get real-time queue length (meters) on the approach road."""
        if not CITYFLOW_AVAILABLE or not self.eng:
            return 0.0
        try:
            road_len = self._get_road_length(approach_road)
            queue_count = 0
            for vid in self.eng.get_vehicles():
                info = self.eng.get_vehicle_info(vid)
                if info.get("road") == approach_road:
                    dist_to_stop = road_len - info.get("distance", 0)
                    if dist_to_stop < 200 and info.get("speed", 0) < 0.5:
                        queue_count += 1
            return queue_count * 7.0  # average vehicle length + gap ~6m
        except:
            return 0.0

    def _estimate_queue_dissipation(self, inter_id, current_time, signal, approach_dir="N", ev_data=None):
        """Compute td using LWR model adapted for generic N-phase system.
        Uses approach_phases to find effective green/red periods in the cycle."""
        approach_road = self._get_ev_approach_road(inter_id, ev_data)
        
        # Get end_road for turn-level phase lookup
        end_road = None
        if approach_road and ev_data["route_roads"]:
            try:
                idx = ev_data["route_roads"].index(approach_road)
                if idx + 1 < len(ev_data["route_roads"]):
                    end_road = ev_data["route_roads"][idx + 1]
            except ValueError:
                pass
        
        # Priority 1: turn-level phases (start_road -> end_road)
        valid_phases = []
        if approach_road and end_road and inter_id in getattr(self, 'turn_phases', {}):
            valid_phases = self.turn_phases[inter_id].get((approach_road, end_road), [])
        # Priority 2: road-level phases
        if not valid_phases and approach_road:
            valid_phases = self.approach_phases.get(inter_id, {}).get(approach_road, [])
        # Priority 3: fallback
        if not valid_phases:
            valid_phases = self._get_pri_green_phases(inter_id, approach_dir, end_road, ev_data)
        if not valid_phases:
            return 60.0

        phase_count = self.phase_counts.get(inter_id, len(signal.phase_durations))
        if not signal.phase_durations:
            return 60.0

        # Compute total cycle length from actual phase durations
        cycle_length = sum(signal.phase_durations[:phase_count])
        t0_in_cycle = current_time % cycle_length

        # Build timeline: for each phase in the cycle, is it "green" (allows approach)?
        # Then find the current effective red/green period
        green_periods = []  # list of (start, end) in cycle seconds
        cumulative = 0.0
        for p in range(phase_count):
            dur = signal.get_phase_total_time(p)
            if p in valid_phases:
                green_periods.append((cumulative, cumulative + dur))
            cumulative += dur

        if not green_periods:
            return 60.0

        # If approach is allowed in ALL phases, use throughput-based queue model
        if len(green_periods) == phase_count:
            # Count vehicles ahead of EV on this approach road
            vehicles_ahead = 0
            if approach_road and CITYFLOW_AVAILABLE and self.eng and ev_data["actual_id"]:
                try:
                    ev_info = self.eng.get_vehicle_info(ev_data["actual_id"])
                    ev_dist = ev_info.get("distance", 0)
                    for vid in self.eng.get_vehicles():
                        if vid == ev_data["actual_id"]:
                            continue
                        info = self.eng.get_vehicle_info(vid)
                        if info.get("road") == approach_road:
                            other_dist = info.get("distance", 0)
                            # Vehicle is ahead if closer to stop line than EV
                            if other_dist < ev_dist:
                                vehicles_ahead += 1
                except:
                    pass
            # Estimate: each vehicle takes ~2s to clear intersection at saturation flow
            td = vehicles_ahead * 2.0 + 5.0
            return max(td, 5.0)

        # Find which period we're in
        in_green = False
        current_red_start = 0.0
        next_green_start = 0.0
        red_duration = 0.0

        for i, (gs, ge) in enumerate(green_periods):
            if gs <= t0_in_cycle < ge:
                in_green = True
                break
            elif t0_in_cycle < gs:
                # In red, next green starts at gs
                current_red_start = green_periods[i-1][1] if i > 0 else green_periods[-1][1] - cycle_length
                next_green_start = gs
                if current_red_start < 0:
                    current_red_start += cycle_length
                red_duration = next_green_start - current_red_start
                if red_duration < 0:
                    red_duration += cycle_length
                break

        if not in_green and next_green_start == 0.0:
            # Wrapped around: t0 is after last green, before first green of next cycle
            current_red_start = green_periods[-1][1]
            next_green_start = green_periods[0][0] + cycle_length
            red_duration = next_green_start - current_red_start

        # Compute tr and tg for LWR model
        if in_green:
            tr = 0.0
            tg = 0.0
        else:
            tr = t0_in_cycle - current_red_start
            if tr < 0:
                tr += cycle_length
            tg = red_duration - tr

        Lq0_m = self._get_queue_length_meters(inter_id, approach_road) if approach_road else 0.0
        Lq0_km = Lq0_m / 1000.0
        td_cycle = self.lwr_model.compute_dissipation_time(tr, tg, t0_in_cycle, Lq0_km)
        if td_cycle >= t0_in_cycle:
            td = td_cycle - t0_in_cycle
        else:
            td = td_cycle + cycle_length - t0_in_cycle
        return max(td, 5.0)

    def _process_traffic_lights(self, current_time):
        """Process traffic lights with green extensions (no phase jumping)."""
        if not self.ev_priority_enabled:
            # Skip priority: just advance signals normally
            for inter_id, signal in self.signal_states.items():
                signal.phase_elapsed += cfg.SIMULATION_STEP_INTERVAL
                phase_count = self.phase_counts.get(inter_id, 8)
                total_time = signal.get_phase_total_time(signal.current_phase)
                if signal.phase_elapsed >= total_time:
                    signal.phase_elapsed = 0.0
                    signal.current_phase = (signal.current_phase + 1) % phase_count
                    if signal.current_phase == 0:
                        self.recovery.cycle_completed(inter_id)
                if CITYFLOW_AVAILABLE and self.eng:
                    try:
                        self.eng.set_tl_phase(inter_id, signal.current_phase)
                    except Exception:
                        pass
            return

        for inter_id, signal in self.signal_states.items():
            signal.phase_elapsed += cfg.SIMULATION_STEP_INTERVAL
            
            # Apply phase shortening (early green)
            shorten_key = (inter_id, signal.current_phase)
            if shorten_key in self.phase_shortenings:
                shorten = self.phase_shortenings.pop(shorten_key)
                signal.phase_elapsed += shorten
                print(f"  [SHORTEN] {inter_id}: Phase{signal.current_phase} shortened by {shorten:.0f}s")
            phase_count = self.phase_counts.get(inter_id, 8)
            total_time = signal.get_phase_total_time(signal.current_phase)
            
            # Check if current phase has a green extension scheduled
            extension_key = (inter_id, signal.current_phase)
            extend_sec = self.green_extensions.get(extension_key, 0)
            
            if extend_sec > 0:
                # Extend by reducing phase_elapsed
                if signal.phase_elapsed < total_time:
                    signal.phase_elapsed -= extend_sec
                    if signal.phase_elapsed < -extend_sec:
                        signal.phase_elapsed = -extend_sec
                    print(f"  [EXTEND] {inter_id}: Phase{signal.current_phase} extended +{extend_sec:.0f}s")
                    del self.green_extensions[extension_key]
                else:
                    del self.green_extensions[extension_key]
            
            # Normal phase transition
            if signal.phase_elapsed >= total_time:
                signal.phase_elapsed = 0.0
                signal.current_phase = (signal.current_phase + 1) % phase_count
                if signal.current_phase == 0:
                    self.recovery.cycle_completed(inter_id)

            # Sync to CityFlow
            if CITYFLOW_AVAILABLE and self.eng:
                try:
                    self.eng.set_tl_phase(inter_id, signal.current_phase)
                except Exception:
                    pass

    def _apply_signal_control(self, inter_id, decision, adjustment, current_time, signal, approach_dir="N", t_a=0.0, t_d=0.0, ev_data=None):
        """Apply signal control WITHOUT phase jumping.
        Only GREEN_EXTEND: wait for natural green, then extend it.
        Stores extensions in self.green_extensions dict for _process_traffic_lights."""
        if not self.ev_priority_enabled:
            return

        # Get the EV's next road for turn-level phase matching
        end_road = None
        approach_road = self._get_ev_approach_road(inter_id, ev_data)
        if approach_road and ev_data["route_roads"]:
            try:
                idx = ev_data["route_roads"].index(approach_road)
                if idx + 1 < len(ev_data["route_roads"]):
                    end_road = ev_data["route_roads"][idx + 1]
            except ValueError:
                pass

        pri_green_phases = self._get_pri_green_phases(inter_id, approach_dir, end_road, ev_data)
        
        if decision == SignalStrategy.DECISION_NO_ACTION:
            return
        
        phase_count = self.phase_counts.get(inter_id, 8)
        durations = signal.phase_durations
        if not durations or phase_count == 0:
            return
        
        # ---- If current phase is already green for EV, extend it NOW ----
        if signal.current_phase in pri_green_phases:
            remaining = max(0, durations[signal.current_phase] - signal.phase_elapsed)
            queue_clear_time = current_time + t_d
            ev_clears_at = max(t_a + max(0, adjustment), queue_clear_time)
            needed = ev_clears_at - (current_time + remaining)
            
            if needed > 1.0:
                extend_sec = min(needed + 5.0, 180.0)
                key = (inter_id, signal.current_phase)
                existing = self.green_extensions.get(key, 0)
                self.green_extensions[key] = min(existing + extend_sec, 240.0)
                print(f"  [EXTEND] {inter_id}: current Phase{signal.current_phase} has {remaining:.0f}s left, queue needs {t_d:.0f}s -> extend +{extend_sec:.0f}s")
            else:
                print(f"  [PASS] {inter_id}: current Phase{signal.current_phase} has {remaining:.0f}s left, queue needs {t_d:.0f}s -> enough")
            
            ev_pass_time = current_time + max(remaining, max(0, t_d))
            self._coordinate_downstream(inter_id, current_time, ev_pass_time, approach_road, end_road, ev_data)
            return
        
        # Find the next green phase that allows EV's turn
        # and compute when it starts/ends
        cycle_length = sum(durations[:phase_count])
        t_in_cycle = current_time % cycle_length
        
        # Build cumulative timeline
        cumul = 0.0
        next_green_phase = None
        next_green_start = None
        next_green_end = None
        
        # Check each phase starting from current position
        for offset in range(phase_count):
            p = (signal.current_phase + offset) % phase_count
            phase_start = (cumul - signal.phase_elapsed) % cycle_length
            if phase_start < 0:
                phase_start += cycle_length
            phase_end = phase_start + durations[p]
            
            if p in pri_green_phases and (next_green_phase is None or phase_start > 0):
                next_green_phase = p
                next_green_start = current_time + ((phase_start - t_in_cycle) % cycle_length)
                if next_green_start <= current_time:
                    next_green_start += cycle_length
                next_green_end = next_green_start + durations[p]
                break
            
            cumul += durations[p]
        
        if next_green_phase is None:
            # No green phase found - shouldn't happen
            return
        
        # Calculate how much extra time is needed
        # ta: EV arrival at stop line. adjustment: queue dissipation time from strategy
        # Queue clears at current_time + t_d. EV clears at t_a + adjustment. Use whichever is later.
        queue_clear_time = current_time + t_d
        ev_clears_at = max(t_a + max(0, adjustment), queue_clear_time)
        green_dur = durations[next_green_phase]
        wait_for_green = next_green_start - t_a  # may be negative if EV arrives during green
        queue_excess = max(0, ev_clears_at - next_green_end)  # time queue needs beyond green

        # ---- Multi-cycle planning: always shorten red phases ----
        # Strategy: if EV has to wait OR queue needs more than one green,
        # aggressively shorten ALL red phases before the target green.
        # This lets the queue start clearing earlier (multi-cycle effect).
        if wait_for_green > 2 or queue_excess > green_dur * 0.3:
            # Shortening target: max of (wait time, queue excess), capped at 240s
            shorten_total = min(max(wait_for_green, queue_excess), 240)
            # At minimum, try to shorten at least 30s if there's any queue
            if queue_excess > 0:
                shorten_total = max(shorten_total, 30)
            print(f"  [PRE-SHORTEN] {inter_id}: wait={wait_for_green:.0f}s, queue_excess={queue_excess:.0f}s -> shorten reds by ~{shorten_total:.0f}s")

            remaining = shorten_total
            for offset in range(phase_count):
                p = (signal.current_phase + offset) % phase_count
                if p == next_green_phase:
                    break
                if p not in pri_green_phases:  # only shorten non-EV phases
                    dur = durations[p]
                    shorten = min(remaining, dur * 0.7)  # max 70% of each phase
                    if shorten > 1:
                        key = (inter_id, p)
                        self.phase_shortenings[key] = max(self.phase_shortenings.get(key, 0), shorten)
                        remaining -= shorten
                if remaining <= 2:
                    break

            # Recalculate green timing after shortening
            next_green_start -= (shorten_total - remaining)  # green comes earlier by what we shortened
            next_green_end = next_green_start + green_dur
            if next_green_start < current_time:
                next_green_start = current_time + 1

        # ---- Extend green if queue still won't clear ----
        if ev_clears_at > next_green_end:
            extend_sec = ev_clears_at - next_green_end + 3.0  # +3s safety margin
            extend_sec = max(extend_sec, 5.0)
            extend_sec = min(extend_sec, 180.0)
            key = (inter_id, next_green_phase)
            existing = self.green_extensions.get(key, 0)
            self.green_extensions[key] = min(existing + extend_sec, 240.0)
            print(f"  [EXTEND] {inter_id}: next_green=Phase{next_green_phase}, EV clears at t={ev_clears_at:.0f} > green_end={next_green_end:.0f} -> extend +{extend_sec:.0f}s")
        else:
            print(f"  [PASS] {inter_id}: green=Phase{next_green_phase} at t={next_green_start:.0f}, EV clears by t={ev_clears_at:.0f} < green_end={next_green_end:.0f}")
        
        # Also schedule downstream coordination
        self._coordinate_downstream(inter_id, current_time, next_green_start, approach_road, end_road, ev_data)
        return
    def _coordinate_downstream(self, inter_id, current_time, ev_pass_time, approach_road, end_road, ev_data=None, depth=0):
        """Pre-adjust downstream intersection(s) so EV arrives during green.
        depth=0: next intersection. depth=1: intersection after that."""
        if depth >= 2 or inter_id not in ev_data["path"]:
            return
        try:
            idx = ev_data["path"].index(inter_id)
        except ValueError:
            return
        if idx + 1 >= len(ev_data["path"]):
            return
        
        downstream = ev_data["path"][idx + 1]
        if downstream not in self.signal_states:
            return

        # Real-time travel time: use EV speed + road length from roadnet
        travel_time = 45.0
        if approach_road and approach_road in self.roads:
            road_len = self._get_road_length(approach_road)
            if ev_data["actual_id"] and CITYFLOW_AVAILABLE and self.eng:
                try:
                    ev_info = self.eng.get_vehicle_info(ev_data["actual_id"])
                    ev_speed = max(float(ev_info.get("speed", 0)), 8.0)
                    ev_dist = float(ev_info.get("distance", 0))
                    travel_time = (road_len - ev_dist) / ev_speed + 10.0  # +10s queue buffer
                except:
                    travel_time = road_len / 11.0 + 10.0
            else:
                travel_time = road_len / 11.0 + 10.0

        ds_arrival = ev_pass_time + travel_time

        # Find EV's turn at downstream intersection
        ds_end_road = None
        if end_road and ev_data["route_roads"]:
            try:
                ridx = ev_data["route_roads"].index(end_road)
                if ridx + 1 < len(ev_data["route_roads"]):
                    ds_end_road = ev_data["route_roads"][ridx + 1]
            except ValueError:
                pass
        
        ds_signal = self.signal_states[downstream]
        ds_phases = self._get_pri_green_phases(downstream, "N", ds_end_road, ev_data)
        if not ds_phases:
            return
        
        ds_phase_count = self.phase_counts.get(downstream, 8)
        ds_durations = ds_signal.phase_durations
        if not ds_durations:
            return
        
        ds_cycle = sum(ds_durations[:ds_phase_count])
        ds_t_in_cycle = ds_arrival % ds_cycle
        
        # Find which green phase EV arrives in or after
        cumul = 0.0
        arrives_in_green = False
        target_green_start = None
        target_phase = None
        for p in range(ds_phase_count):
            dur = ds_durations[p]
            if p in ds_phases and cumul <= ds_t_in_cycle < cumul + dur:
                arrives_in_green = True
                target_phase = p
                target_green_start = ds_arrival - (ds_t_in_cycle - cumul)
                break
            if p in ds_phases and cumul > ds_t_in_cycle:
                target_phase = p
                target_green_start = ds_arrival + (cumul - ds_t_in_cycle)
                break
            cumul += dur
        
        if target_phase is None:
            return
        
        target_green_end = target_green_start + ds_durations[target_phase]
        
        if arrives_in_green:
            time_left = target_green_end - ds_arrival
            if time_left < 20.0:
                extend = 30.0
                key = (downstream, target_phase)
                existing = self.green_extensions.get(key, 0)
                self.green_extensions[key] = min(existing + extend, 240.0)
                print(f"  [DOWNSTREAM] {downstream}: EV arrives t={ds_arrival:.0f} in Phase{target_phase}, {time_left:.0f}s left -> extend +{extend:.0f}s (depth={depth})")
        else:
            wait_time = target_green_start - ds_arrival
            if wait_time < 90 and wait_time > 0:
                # Pre-extend the downstream green
                key = (downstream, target_phase)
                extend = wait_time + 15.0
                existing = self.green_extensions.get(key, 0)
                self.green_extensions[key] = min(existing + extend, 240.0)
                print(f"  [DOWNSTREAM] {downstream}: EV arrives t={ds_arrival:.0f}, next green Phase{target_phase} at t={target_green_start:.0f}, wait={wait_time:.0f}s -> extend +{extend:.0f}s (depth={depth})")
                
                # ALSO: pre-shorten red phases at downstream
                # Distribute shortening across phases before target_phase
                remain = min(wait_time - 10, 120)
                for off in range(ds_phase_count):
                    pp = (ds_signal.current_phase + off) % ds_phase_count
                    if pp == target_phase:
                        break
                    if pp not in ds_phases:
                        dur = ds_durations[pp]
                        shorten = min(remain, dur * 0.7)
                        if shorten > 1:
                            skey = (downstream, pp)
                            self.phase_shortenings[skey] = max(self.phase_shortenings.get(skey, 0), shorten)
                            remain -= shorten
                    if remain <= 2:
                        break

        # Chain: pre-coordinate the next intersection too
        if ds_end_road:
            self._coordinate_downstream(downstream, current_time, target_green_start, end_road, ds_end_road, ev_data, depth + 1)

    def _get_pri_green_phases(self, inter_id, approach_dir, end_road=None, ev_data=None):
        """Get correct priority green phases for EV at this intersection.
        Uses turn-level mapping (start_road->end_road) when end_road is known."""
        approach_road = self._get_ev_approach_road(inter_id, ev_data)
        # Priority 1: turn-level mapping (most precise)
        if approach_road and end_road and inter_id in getattr(self, "turn_phases", {}):
            turn_key = (approach_road, end_road)
            phases = self.turn_phases[inter_id].get(turn_key, [])
            if phases:
                print(f"  [PHASE_MAP] {inter_id}: {approach_road}->{end_road} => phases={phases} (turn-level)")
                return phases
        # Priority 2: road-level mapping
        if approach_road and inter_id in self.approach_phases:
            phases = self.approach_phases[inter_id].get(approach_road, [])
            if phases:
                print(f"  [PHASE_MAP] {inter_id}: road={approach_road} -> phases={phases} (roadnet)")
                return phases
        # Fallback: return all phases
        pc = self.phase_counts.get(inter_id, 8)
        all_green = list(range(pc))
        print(f"  [PHASE_MAP] {inter_id}: fallback for {approach_dir}, all_phases={all_green}")
        return all_green

    def _get_ev_approach_road(self, inter_id, ev_data=None):
        """Get the road the primary EV is on when approaching this intersection."""
        if ev_data and ev_data["actual_id"] and CITYFLOW_AVAILABLE and self.eng:
            try:
                info = self.eng.get_vehicle_info(ev_data["actual_id"])
                road = info.get("road", "")
                if road and road in self.roads:
                    return road
            except:
                pass
        # Fallback: use first tracked EV's route
        for ev_id, ed in self.ev_registry.items():
            if ed["route_roads"]:
                for i, rid in enumerate(ed["route_roads"]):
                    if rid in self.roads and self.roads[rid]["endIntersection"] == inter_id:
                        return rid
        return None
    
    def _print_summary(self):
        print(f"\n{'='*60}")
        print(f"  Simulation Summary")
        print(f"{'='*60}")
        for ev_id, ev_data in self.ev_registry.items():
            passed = ev_data["passed_intersections"]
            seq = sorted(ev_data["detected_at"].keys()) if ev_data["detected_at"] else []
            print(f"  EV {ev_id}: {len(passed)} passed, detected at {seq}")
        print(f"  Log records: {len(self.logger.records)}")
        print(f"  Log file: {os.path.join(cfg.LOG_DIR, cfg.LOG_FILE)}")
        print(f"{'='*60}")

    def _save_results(self):
        os.makedirs(cfg.LOG_DIR, exist_ok=True)
        summary_path = os.path.join(cfg.LOG_DIR, "simulation_summary.json")
        all_ev = {}
        for ev_id, ev_data in self.ev_registry.items():
            all_ev[ev_id] = {
                "priority": ev_data.get("priority"),
                "path": ev_data["path"],
                "passed_intersections": sorted(list(ev_data["passed_intersections"])),
                "detected_at": {str(k): v for k, v in ev_data["detected_at"].items()},
            }
        summary = {
            "emergency_vehicles": all_ev,
            "total_log_records": len(self.logger.records),
        }
        with open(summary_path, "w", encoding="utf-8") as f:
            json.dump(summary, f, indent=2, ensure_ascii=False, default=str)
        print(f"  Summary: {summary_path}")

if __name__ == "__main__":
    runner = SimulationRunner()
    runner.run(max_steps=cfg.SIMULATION_STEPS)