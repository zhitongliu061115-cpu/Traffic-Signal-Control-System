"""sumo_engine.py"""
from __future__ import annotations
import uuid
import traci
from typing import Any
JsonDict = dict[str, Any]

class SumoEngine:
    def __init__(self, net_file, sumo_cmd=None):
        self._net_file = net_file
        self._sumo_cmd = sumo_cmd or ["sumo"]
        self._label = "sumo_engine"
        self._started = False
        self._step_count = 0
    def start(self, step_length=1.0, gui=False):
        cmd = list(self._sumo_cmd)
        if gui and "sumo-gui" not in cmd[0]:
            cmd[0] = cmd[0].replace("sumo", "sumo-gui", 1)
        cmd += ["--net-file", self._net_file, "--step-length", str(step_length), "--begin", "0", "--no-warnings", "true", "--no-step-log", "true", "--random", "true"]
        traci.start(cmd, label=self._label)
        self._started = True
    def close(self):
        if self._started:
            try: traci.close()
            except Exception: pass
            self._started = False
    def get_current_time(self):
        return traci.simulation.getTime()
    def get_vehicles(self):
        return traci.vehicle.getIDList()
    def push_vehicle(self, veh_info, road_route):
        veh_id = f"ev_{uuid.uuid4().hex[:8]}"
        route_id = f"route_{veh_id}"
        try: traci.route.add(route_id, road_route)
        except traci.exceptions.TraCIException: pass
        vtype = "DEFAULT_VEHTYPE"
        max_speed = veh_info.get("maxSpeed", veh_info.get("speed", 13.89))
        traci.vehicle.add(vehID=veh_id, routeID=route_id, typeID=vtype, departSpeed=float(max_speed), departLane=0)
        return veh_id
    def next_step(self):
        traci.simulationStep()
        self._step_count += 1
    def get_tl_phase(self, inter_id):
        try: return traci.trafficlight.getPhase(inter_id)
        except traci.exceptions.TraCIException: return 0
    # [PAPER-REFACTOR] set_tl_phase - DEPRECATED for EV scenarios, kept for compat
    def set_tl_phase(self, inter_id, phase_index):
        try: traci.trafficlight.setPhase(inter_id, phase_index - 1)
        except traci.exceptions.TraCIException: pass
    # [PAPER-REFACTOR] Duration-based signal control, Section 3.5-3.6 of paper
    def set_tl_phase_duration(self, inter_id, duration):
        try: traci.trafficlight.setPhaseDuration(inter_id, duration)
        except traci.exceptions.TraCIException: pass
    def get_tl_phase_duration(self, inter_id):
        try: return float(traci.trafficlight.getPhaseDuration(inter_id))
        except traci.exceptions.TraCIException: return 30.0
    def get_tl_phase_remaining(self, inter_id):
        try:
            ns = traci.trafficlight.getNextSwitch(inter_id)
            return max(0.0, ns - traci.simulation.getTime())
        except traci.exceptions.TraCIException: return 30.0
    def get_tl_program(self, inter_id):
        try: return traci.trafficlight.getCompleteRedYellowGreenDefinition(inter_id)
        except traci.exceptions.TraCIException: return None
    def set_tl_program(self, inter_id, program):
        try: traci.trafficlight.setCompleteRedYellowGreenDefinition(inter_id, program)
        except traci.exceptions.TraCIException: pass
    def get_vehicle_info(self, vid):
        try:
            if vid not in traci.vehicle.getIDList(): return None
            return {"road": traci.vehicle.getRoadID(vid), "distance": float(traci.vehicle.getLanePosition(vid)), "speed": float(traci.vehicle.getSpeed(vid)), "lane": int(traci.vehicle.getLaneIndex(vid))}
        except traci.exceptions.TraCIException: return None
    def get_vehicle_count(self): return traci.vehicle.getIDCount()
    def get_vehicle_id(self, index):
        ids = traci.vehicle.getIDList()
        if 0 <= index < len(ids): return ids[index]
        return ""
    def get_vehicle_speed(self):
        result = {}
        try:
            for vid in traci.vehicle.getIDList(): result[vid] = float(traci.vehicle.getSpeed(vid))
        except traci.exceptions.TraCIException: pass
        return result
    def get_vehicle_distance(self):
        result = {}
        try:
            for vid in traci.vehicle.getIDList(): result[vid] = float(traci.vehicle.getLanePosition(vid))
        except traci.exceptions.TraCIException: pass
        return result
    def get_lane_vehicles(self):
        result = {}
        try:
            for lid in traci.lane.getIDList():
                vehs = traci.lane.getLastStepVehicleIDs(lid)
                if vehs: result[lid] = list(vehs)
        except traci.exceptions.TraCIException: pass
        return result
    def get_lane_vehicle_count(self):
        result = {}
        try:
            for lid in traci.lane.getIDList(): result[lid] = traci.lane.getLastStepVehicleNumber(lid)
        except traci.exceptions.TraCIException: pass
        return result
    def get_lane_waiting_vehicle_count(self):
        result = {}
        try:
            for lid in traci.lane.getIDList(): result[lid] = traci.lane.getLastStepHaltingNumber(lid)
        except traci.exceptions.TraCIException: pass
        return result
    def get_finished_vehicle_count(self):
        try: return traci.simulation.getArrivedNumber()
        except traci.exceptions.TraCIException: return 0
    def remove_vehicle(self, vid):
        try:
            traci.vehicle.remove(vid)
            return True
        except traci.exceptions.TraCIException: return False
    # [PAPER-REFACTOR] Build EV priority program by compressing intermediate phases
    def build_ev_priority_program(self, inter_id, target_phase, min_green=10.0, ev_extend=60.0):
        # [PAPER-REFACTOR] Build modified TL program using Logic/Phase objects.
        # Tested with traci 1.27.1 where getAllProgramLogics returns Logic objects.
        try:
            program = traci.trafficlight.getAllProgramLogics(inter_id)
        except Exception:
            return None
        if not program:
            return None
        # program is a tuple of Logic objects
        logic = program[0]  # traci.trafficlight.Logic
        current = logic.currentPhaseIndex
        phases = list(logic.getPhases())  # list of traci.trafficlight.Phase
        n = len(phases)
        new_phases = []
        for i in range(n):
            p = phases[i]
            # Forward distance from current to phase i
            if i >= current: dist = i - current
            else: dist = i + n - current
            if target_phase >= current: target_dist = target_phase - current
            else: target_dist = target_phase + n - current
            if dist < target_dist:
                # Intermediate phase: compress to minimum green
                new_phases.append(traci.trafficlight.Phase(min_green, p.state, min_green, p.maxDur))
            elif dist == target_dist:
                # EV priority phase: extend green
                new_phases.append(traci.trafficlight.Phase(ev_extend, p.state, min_green, p.maxDur))
            else:
                # Phases after EV: keep original
                new_phases.append(p)
        # Build new Logic and apply
        new_logic = traci.trafficlight.Logic(
            logic.programID, logic.type, logic.currentPhaseIndex,
            new_phases, logic.subParameter)
        try:
            traci.trafficlight.setCompleteRedYellowGreenDefinition(inter_id, [new_logic])
        except Exception:
            pass
        return [new_logic]
