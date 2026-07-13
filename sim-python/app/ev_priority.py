# -*- coding: utf-8 -*-

# ev_priority.py - 应急车辆优先通行核心算法模块
# ==============================================

import heapq
import math
import json
import csv
import os
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple
from app import ev_config as cfg


# ============================================================
# 1. Dijkstra 路径规划
# ============================================================

class DijkstraPathPlanner:
    def __init__(self):
        self.graph: Dict[str, Dict[str, float]] = {}
        self.conflict_nodes: set = set()
        self._build_graph()

    def _build_graph(self):
        edge_time = cfg.ROAD_LENGTH / cfg.SPEED_LIMIT
        for r in range(cfg.GRID_ROWS):
            for c in range(cfg.GRID_COLS):
                node = f'intersection_{r}_{c}'
                if node not in self.graph:
                    self.graph[node] = {}
                neighbors = []
                if r > 0: neighbors.append(f'intersection_{r-1}_{c}')
                if r < cfg.GRID_ROWS - 1: neighbors.append(f'intersection_{r+1}_{c}')
                if c > 0: neighbors.append(f'intersection_{r}_{c-1}')
                if c < cfg.GRID_COLS - 1: neighbors.append(f'intersection_{r}_{c+1}')
                for nb in neighbors:
                    self.graph[node][nb] = edge_time
    def load_roadnet(self, roadnet):
        """Rebuild graph from actual roadnet data (replaces grid-based _build_graph)."""
        self.graph = {}
        for inter in roadnet.get("intersections", []):
            self.graph[inter["id"]] = {}
        for road in roadnet.get("roads", []):
            si = road["startIntersection"]
            ei = road["endIntersection"]
            if si not in self.graph or ei not in self.graph:
                continue
            explicit_length = float(road.get("length", 0.0) or 0.0)
            pts = road.get("points", [])
            if explicit_length > 0:
                length = explicit_length
            elif len(pts) >= 2:
                dx = pts[-1]["x"] - pts[0]["x"]
                dy = pts[-1]["y"] - pts[0]["y"]
                length = (dx**2 + dy**2)**0.5
            else:
                length = 400.0
            travel_time = length / 16.67  # cfg.SPEED_LIMIT default
            self.graph[si][ei] = travel_time


    def add_conflict(self, node_id: str):
        self.conflict_nodes.add(node_id)

    def clear_conflicts(self):
        self.conflict_nodes.clear()

    def find_path(self, start: str, end: str) -> Optional[List[str]]:
        if start not in self.graph or end not in self.graph:
            return None
        distances = {node: float('inf') for node in self.graph}
        distances[start] = 0
        previous = {node: None for node in self.graph}
        pq = [(0, start)]
        visited = set()

        while pq:
            dist, current = heapq.heappop(pq)
            if current in visited:
                continue
            visited.add(current)
            if current == end:
                break
            for neighbor, base_weight in self.graph[current].items():
                weight = base_weight
                if neighbor in self.conflict_nodes:
                    weight += cfg.CONFLICT_WEIGHT
                new_dist = dist + weight
                if new_dist < distances[neighbor]:
                    distances[neighbor] = new_dist
                    previous[neighbor] = current
                    heapq.heappush(pq, (new_dist, neighbor))

        if distances[end] == float('inf'):
            return None

        path = []
        node = end
        while node is not None:
            path.append(node)
            node = previous[node]
        path.reverse()
        return path


# ============================================================
# 2. LWR 交通波排队消散模型
# ============================================================

@dataclass

# ============================================================
# 1.5 Coordinate Snapper - snap (x,y) to nearest road/intersection
# ============================================================

class CoordinateSnapper:
    def __init__(self):
        self.roads = []
        self.intersections = {}

    def load_roadnet(self, roadnet):
        self.roads = roadnet.get("roads", [])
        for inter in roadnet.get("intersections", []):
            self.intersections[inter["id"]] = inter

    def _point_to_segment(self, px, py, x1, y1, x2, y2):
        dx = x2 - x1
        dy = y2 - y1
        if dx == 0 and dy == 0:
            return ((px-x1)**2 + (py-y1)**2)**0.5, 0.0
        t = max(0.0, min(1.0, ((px-x1)*dx + (py-y1)*dy) / (dx*dx + dy*dy)))
        proj_x = x1 + t * dx
        proj_y = y1 + t * dy
        return ((px-proj_x)**2 + (py-proj_y)**2)**0.5, t

    def snap(self, x, y):
        best_dist = float("inf")
        best_road = None
        best_upstream = None
        best_downstream = None
        best_offset = 0.0
        for road in self.roads:
            pts = road.get("points", [])
            if len(pts) < 2:
                continue
            x1, y1 = pts[0]["x"], pts[0]["y"]
            x2, y2 = pts[1]["x"], pts[1]["y"]
            dist, t = self._point_to_segment(x, y, x1, y1, x2, y2)
            if dist < best_dist:
                best_dist = dist
                best_road = road["id"]
                best_upstream = road["startIntersection"]
                best_downstream = road["endIntersection"]
                best_offset = t * ((x2-x1)**2 + (y2-y1)**2)**0.5
        return best_road, best_upstream, best_downstream, best_offset

    def snap_start(self, x, y):
        _, upstream, _, _ = self.snap(x, y)
        return upstream

    def snap_end(self, x, y):
        _, _, downstream, _ = self.snap(x, y)
        return downstream

class LWRParams:
    kj: float = cfg.JAM_DENSITY
    ks: float = cfg.SAT_DENSITY
    qs: float = cfg.SAT_FLOW_RATE
    vf: float = cfg.FREEFLOW_SPEED
    kf: float = cfg.FREEFLOW_DENSITY

class LWRQueueModel:
    def __init__(self, params: LWRParams = None):
        self.p = params or LWRParams()
        self._compute_wave_speeds()

    def _compute_wave_speeds(self):
        qa = self.p.kf * self.p.vf
        qf = self.p.kf * self.p.vf
        self.w1 = qa / (self.p.kj - self.p.kf) if (self.p.kj - self.p.kf) != 0 else 0
        self.w2 = self.p.qs / (self.p.kj - self.p.ks) if (self.p.kj - self.p.ks) != 0 else 0
        self.w3 = (qf - self.p.qs) / (self.p.kf - self.p.ks) if (self.p.kf - self.p.ks) != 0 else 0
        self.w1 = abs(self.w1)
        self.w2 = abs(self.w2)
        self.w3 = abs(self.w3)

    def get_wave_speeds(self):
        return self.w1, self.w2, self.w3

    def compute_dissipation_time(self, tr: float, tg: float,
                                  t0: float, Lq0: float = 0.0) -> float:
        """Compute queue dissipation time using LWR shockwave theory.
        Paper equations 5-12. All times in seconds, wave speeds in km/h.
        tr: red start time in cycle (s), tg: green start time in cycle (s), t0: current time in cycle (s).
        Returns td: absolute dissipation end time in cycle (s)."""
        w1, w2, w3 = self.w1, self.w2, self.w3
        if abs(w2 - w1) < 1e-6:
            return 0.0

        # Convert to hours for km/h wave speed compatibility
        tr_h, tg_h, t0_h = tr / 3600.0, tg / 3600.0, t0 / 3600.0

        # Equation 6: meeting time of stopping wave and starting wave
        te_h = (w1 * tr_h - w2 * tg_h) / (w1 - w2)
        # Equation 7: maximum queue length (km)
        Lq_max = w1 * (te_h - tr_h)
        if Lq_max < 0:
            Lq_max = 0.0
        te = te_h * 3600.0  # back to seconds

        # Determine which regime we are in
        if t0_h <= tr_h:
            # Eq 9: td = [w1*(w2+w3)*tr - w2*(w1+w3)*tg] / [w3*(w1-w2)]
            if w3 > 0:
                num = w1 * (w2 + w3) * tr_h - w2 * (w1 + w3) * tg_h
                den = w3 * (w1 - w2)
                td_h = num / den
            else:
                td_h = 0.0
            td = max(0.0, td_h * 3600.0)
        elif tr_h < t0_h < te_h:
            # Eq 11: td = [w1*(w2+w3)*t0 - w2*(w1+w3)*tg - (w2+w3)*w1*tr] / [w3*(w1-w2)]
            if w3 > 0:
                num = w1 * (w2 + w3) * t0_h - w2 * (w1 + w3) * tg_h - (w2 + w3) * w1 * tr_h
                den = w3 * (w1 - w2)
                td_h = num / den
            else:
                td_h = 0.0
            td = max(0.0, td_h * 3600.0)
        else:
            # Eq 12: td = te + Lq_max / w3
            if w3 > 0:
                td = te + (Lq_max / w3) * 3600.0
            else:
                td = te

        # Incorporate existing queue length Lq0 (km -> additional dissipation seconds)
        if Lq0 > 0 and w3 > 0:
            td += (Lq0 / w3) * 3600.0
        return max(0.0, td)

    def compute_ev_arrival_time(self, t0: float, distance: float, speed: float) -> float:
        if speed <= 0:
            # EV stuck in congestion: estimate based on queue dissipation
            # Use a crawl speed of 2 m/s (7.2 km/h) as worst-case estimate
            crawl_speed = 2.0
            return t0 + distance / crawl_speed
        return t0 + distance / speed


# ============================================================
# 3. 路口优先信号控制策略
# ============================================================

@dataclass
class SignalState:
    intersection_id: str
    current_phase: int = 0
    phase_elapsed: float = 0.0
    priority_phase_group: int = 0
    phase_durations: list = None
    approach_phases: dict = None  # {approach_road: [phase_indices]}
    phase_count: int = 8  # total number of phases for this intersection

    def __post_init__(self):
        if self.phase_durations is None:
            self.phase_durations = []
        if self.approach_phases is None:
            self.approach_phases = {}

    def get_phase_green_time(self, phase_idx: int) -> float:
        '''In generic N-phase system, green time = phase total time.'''
        return self.get_phase_total_time(phase_idx)

    def get_phase_total_time(self, phase_idx: int) -> float:
        # Generic: return actual duration for any phase
        if self.phase_durations and phase_idx < len(self.phase_durations):
            return self.phase_durations[phase_idx]
        # Fallback for old 4-phase model
        base_idx = (phase_idx // 2) % cfg.NUM_PHASES
        return cfg.PHASE_GREEN_TIMES[base_idx] + cfg.YELLOW_TIME

    def approach_allowed(self, approach_road: str) -> bool:
        '''Check if current phase allows traffic from this approach road.'''
        if approach_road in self.approach_phases:
            return self.current_phase in self.approach_phases[approach_road]
        # Fallback for old model
        return self.current_phase % 2 == 0

    def time_until_next_valid_phase(self, approach_road: str) -> float:
        '''Time until next phase that allows this approach road.'''
        valid_phases = self.approach_phases.get(approach_road, [])
        if not valid_phases:
            return 999.0
        if self.current_phase in valid_phases:
            return 0.0
        remaining = self.get_phase_total_time(self.current_phase) - self.phase_elapsed
        total = remaining
        pc = max(self.phase_count, len(self.phase_durations))
        p = (self.current_phase + 1) % pc
        while p not in valid_phases:
            total += self.get_phase_total_time(p)
            p = (p + 1) % pc
        return total

    def time_until_phase_end(self) -> float:
        '''Time remaining in current phase.'''
        return self.get_phase_total_time(self.current_phase) - self.phase_elapsed

    def is_priority_phase(self, approach_dir: str, approach_road: str = "") -> bool:
        '''Check if current phase allows the EV's approach.'''
        if approach_road and approach_road in self.approach_phases:
            return self.current_phase in self.approach_phases[approach_road]
        # Fallback for old model
        phase_group = self.current_phase // 2 % cfg.NUM_PHASES
        if approach_dir in ('N', 'S'):
            return phase_group in (0, 1)
        else:
            return phase_group in (2, 3)


class SignalStrategy:
    DECISION_NO_ACTION = 'no_action'
    DECISION_GREEN_EXTEND = 'green_extend'
    DECISION_RED_EARLY = 'red_early'
    DECISION_FORCE_GREEN = 'force_green'

    def __init__(self, lwr_model: LWRQueueModel):
        self.lwr = lwr_model

    def decide(self, t_a: float, t_d: float,
               signal: SignalState, current_time: float,
               approach_dir: str, pri_green_phases=None,
               approach_road: str = "") -> Tuple[str, float]:
        """Decision logic for generic N-phase system.
        Uses approach_phases from roadnet instead of green/yellow model.
        GS = clearance loss time = 3s."""
        GS = 3.0
        if pri_green_phases is None:
            pri_green_phases = self._get_priority_green_phases(approach_dir)
        if not pri_green_phases:
            return (self.DECISION_NO_ACTION, 0.0)

        # Is EV's approach currently allowed?
        approach_allowed = signal.approach_allowed(approach_road) if approach_road else signal.current_phase in pri_green_phases
        phase_remaining = signal.time_until_phase_end()

        if approach_allowed:
            # Current phase allows EV
            phase_end = current_time + phase_remaining
            if t_a <= phase_end:
                # EV arrives before phase ends
                queue_clear = current_time + t_d  # when queue actually clears
                if t_a >= queue_clear:
                    return (self.DECISION_NO_ACTION, 0.0)  # queue clears in time
                else:
                    # Need more time - extend until queue + EV are through
                    extend_needed = queue_clear - phase_end + GS
                    if extend_needed > 0:
                        return (self.DECISION_GREEN_EXTEND, max(0, extend_needed))
                    return (self.DECISION_FORCE_GREEN, 0.0)
            else:
                # EV arrives after phase ends
                queue_clear = current_time + t_d
                time_to_next = signal.time_until_next_valid_phase(approach_road)
                if time_to_next < 60 and t_d < t_a:
                    return (self.DECISION_GREEN_EXTEND, queue_clear - phase_end + GS)
                else:
                    return (self.DECISION_FORCE_GREEN, 0.0)
        else:
            # Current phase does NOT allow EV
            time_to_valid = signal.time_until_next_valid_phase(approach_road)
            valid_start = current_time + time_to_valid
            if t_a < valid_start + 60 and t_d < t_a:
                # Close enough to wait or advance
                return (self.DECISION_RED_EARLY, 0.0)
            else:
                # Force immediate switch
                return (self.DECISION_FORCE_GREEN, 0.0)

    def _get_priority_green_phases(self, approach_dir: str) -> List[int]:
        # Return phase indices matching the 8-phase structure:
        # 0=N-S through/right, 2=N-S left, 4=E-W through/right, 6=E-W left
        if approach_dir in ('N', 'S'):
            return [0, 2]
        else:
            return [4, 6]


# ============================================================
# 4. 多路口联动协调
# ============================================================

@dataclass
class CoordinationWindow:
    intersection_id: str
    locked_start: float
    locked_end: float
    confirmed: bool = False
    fine_tune_applied: bool = False


class IntersectionCoordinator:
    def __init__(self):
        self.windows: Dict[str, CoordinationWindow] = {}
        self.ev_path: List[str] = []

    def set_ev_path(self, path: List[str]):
        self.ev_path = path

    def broadcast_eta(self, from_intersection: str, eta: float):
        try:
            idx = self.ev_path.index(from_intersection)
            if idx + 1 < len(self.ev_path):
                downstream = self.ev_path[idx + 1]
                window = CoordinationWindow(
                    intersection_id=downstream,
                    locked_start=eta - cfg.COORDINATION_ADVANCE,
                    locked_end=eta + cfg.COORDINATION_ADVANCE,
                    confirmed=False
                )
                self.windows[downstream] = window
                return downstream
        except ValueError:
            pass
        return None

    def get_window(self, intersection_id: str) -> Optional[CoordinationWindow]:
        return self.windows.get(intersection_id)

    def fine_tune(self, intersection_id: str, t_a_actual: float) -> Optional[float]:
        win = self.windows.get(intersection_id)
        if win:
            win.fine_tune_applied = True
            if abs(t_a_actual - (win.locked_start + win.locked_end) / 2) > cfg.FINE_TUNE_MARGIN:
                return t_a_actual
        return None


# ============================================================
# 5. 信号恢复与绿灯补偿
# ============================================================

@dataclass
class PhaseLoss:
    phase_index: int
    green_lost: float  # 损失的绿灯秒数

@dataclass
class IntersectionRecovery:
    intersection_id: str
    losses: List[PhaseLoss] = field(default_factory=list)
    cycles_remaining: int = cfg.RECOVERY_CYCLES
    active: bool = False


class RecoveryManager:
    def __init__(self):
        self.recoveries: Dict[str, IntersectionRecovery] = {}
        self.transition_active: Dict[str, bool] = {}

    def record_loss(self, intersection_id: str, phase_index: int, green_lost: float):
        if intersection_id not in self.recoveries:
            self.recoveries[intersection_id] = IntersectionRecovery(intersection_id=intersection_id)
        if intersection_id not in self.transition_active:
            self.transition_active[intersection_id] = False
        rec = self.recoveries[intersection_id]
        for loss in rec.losses:
            if loss.phase_index == phase_index:
                loss.green_lost += green_lost
                return
        rec.losses.append(PhaseLoss(phase_index=phase_index, green_lost=green_lost))
        rec.active = True

    def start_transition(self, intersection_id: str):
        self.transition_active[intersection_id] = True

    def get_compensation(self, intersection_id: str, phase_index: int) -> float:
        if intersection_id not in self.recoveries:
            return 0.0
        if not self.transition_active.get(intersection_id, False):
            return 0.0
        rec = self.recoveries[intersection_id]
        total_loss = sum(l.green_lost for l in rec.losses)
        if total_loss <= 0:
            return 0.0
        for loss in rec.losses:
            if loss.phase_index == phase_index:
                proportion = loss.green_lost / total_loss if total_loss > 0 else 0
                return total_loss * proportion
        return 0.0

    def cycle_completed(self, intersection_id: str):
        if intersection_id in self.recoveries:
            if self.transition_active.get(intersection_id, False):
                self.transition_active[intersection_id] = False
                self.recoveries[intersection_id].active = False
                self.recoveries[intersection_id].losses.clear()
                self.recoveries[intersection_id].cycles_remaining = 0


# ============================================================
# 6. 多车冲突处理
# ============================================================

@dataclass
class EVRequest:
    ev_id: str
    priority: int
    path: List[str]
    trigger_time: float
    eta_map: Dict[str, float] = field(default_factory=dict)

class ConflictResolver:
    def __init__(self):
        self.active_requests: List[EVRequest] = []

    def register(self, request: EVRequest):
        self.active_requests.append(request)
        self.active_requests.sort(key=lambda r: (r.priority, r.trigger_time))

    def resolve_at_intersection(self, intersection_id: str,
                                 current_time: float) -> Optional[EVRequest]:
        candidates = [r for r in self.active_requests
                      if intersection_id in r.path]
        if not candidates:
            return None
        candidates.sort(key=lambda r: (r.priority, r.trigger_time))
        return candidates[0]

    def remove(self, ev_id: str):
        self.active_requests = [r for r in self.active_requests if r.ev_id != ev_id]


# ============================================================
# 7. 日志记录
# ============================================================

class EVLogger:
    def __init__(self, log_dir: str = cfg.LOG_DIR):
        self.log_dir = log_dir
        os.makedirs(log_dir, exist_ok=True)
        self.log_path = os.path.join(log_dir, cfg.LOG_FILE)
        self.records: List[Dict] = []
        self._init_csv()

    def _init_csv(self):
        with open(self.log_path, 'w', newline='', encoding='utf-8') as f:
            writer = csv.writer(f)
            writer.writerow([
                'timestamp', 'ev_id', 'event_type', 'intersection_id',
                'current_phase', 'decision', 'ta', 'td', 'signal_adjustment',
                'ev_position', 'ev_speed', 'detail'
            ])

    def log(self, timestamp: float, ev_id: str, event_type: str,
            intersection_id: str = '', current_phase: int = -1,
            decision: str = '', ta: float = 0, td: float = 0,
            signal_adjustment: float = 0, ev_position: float = 0,
            ev_speed: float = 0, detail: str = ''):
        record = {
            'timestamp': round(timestamp, 1),
            'ev_id': ev_id,
            'event_type': event_type,
            'intersection_id': intersection_id,
            'current_phase': current_phase,
            'decision': decision,
            'ta': round(ta, 2),
            'td': round(td, 2),
            'signal_adjustment': round(signal_adjustment, 2),
            'ev_position': round(ev_position, 2),
            'ev_speed': round(ev_speed, 2),
            'detail': detail
        }
        self.records.append(record)
        with open(self.log_path, 'a', newline='', encoding='utf-8') as f:
            writer = csv.writer(f)
            writer.writerow(record.values())


# ============================================================
# 8. EV探测与触发管理
# ============================================================

class EVDetector:
    def __init__(self, whitelist: List[str] = None):
        self.whitelist = whitelist or cfg.EV_WHITELIST
        self.active_evs: Dict[str, EVRequest] = {}
        self.planner = DijkstraPathPlanner()
        self.triggered_intersections: Dict[str, set] = defaultdict(set)

    def poll_vehicle(self, veh_id: str, veh_info: dict,
                     current_time: float, is_detected_ev: bool = False,
                     road_length: float = 400.0) -> Optional[Dict]:
        if veh_id not in self.whitelist and not is_detected_ev:
            return None
        current_road = str(veh_info.get('road', ''))
        distance = float(veh_info.get('distance', 0))
        speed = float(veh_info.get('speed', 0))
        # Use passed road_length or fall back to config
        if road_length is None or road_length <= 0:
            road_length = cfg.ROAD_LENGTH

        dist_to_intersection = road_length - distance

        if dist_to_intersection <= cfg.EV_DETECTION_DISTANCE:
            to_inter = str(veh_info.get('next_intersection') or '')
            if not to_inter:
                parts = current_road.split('_')
                # road_r_c_r_c format (5 parts)
                if len(parts) >= 5:
                    to_inter = f'intersection_{parts[3]}_{parts[4]}'
                # road_X_Y_Z format (4 parts)
                elif len(parts) >= 4:
                    try:
                        x, y, z = int(parts[1]), int(parts[2]), int(parts[3])
                        if z == 0: to_inter = f'intersection_{x+1}_{y}'
                        elif z == 1: to_inter = f'intersection_{x}_{y+1}'
                        elif z == 2: to_inter = f'intersection_{x-1}_{y}'
                        elif z == 3: to_inter = f'intersection_{x}_{y-1}'
                        else: return None
                    except ValueError:
                        return None
                else:
                    return None
            return {
                'ev_id': veh_id,
                'intersection_id': to_inter,
                'distance_to_stop': dist_to_intersection,
                'speed': speed,
                'current_road': current_road,
                'timestamp': current_time
            }
        return None


# ============================================================
# 9. 方向判断辅助
# ============================================================

def get_approach_direction(current_road: str) -> str:
    parts = current_road.split('_')
    if len(parts) == 4:
        # format: road_X_Y_Z where Z=direction (0=S, 1=E, 2=N, 3=W)
        try:
            direction = int(parts[3])
            return {0: 'S', 1: 'E', 2: 'N', 3: 'W'}.get(direction, 'N')
        except ValueError:
            return 'N'
    if len(parts) >= 5:
        # format: road_r1_c1_r2_c2
        r1, c1, r2, c2 = int(parts[1]), int(parts[2]), int(parts[3]), int(parts[4])
        dr = r2 - r1
        dc = c2 - c1
        if dr == -1: return 'N'
        if dr == 1:  return 'S'
        if dc == 1:  return 'E'
        if dc == -1: return 'W'
        return 'N'
    return 'N'
