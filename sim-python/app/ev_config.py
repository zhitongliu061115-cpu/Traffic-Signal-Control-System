# -*- coding: utf-8 -*-
# config.py - Emergency Vehicle Priority Simulation
# =================================================
#  Two ways to set EV route:
#    1) Intersection-based: EV_START_INTERSECTION + EV_END_INTERSECTION
#    2) Coordinate-based:   EV_START_COORD + EV_END_COORD  (auto-snaps to nearest road)
#  Route is auto-computed via Dijkstra.

GRID_ROWS = 3
GRID_COLS = 3
ROAD_LENGTH = 400.0
LANE_WIDTH = 3.5
SPEED_LIMIT = 16.67
NUM_LANES = 2
INTERSECTION_WIDTH = 30.0

PHASE_GREEN_TIMES = [48, 12, 24, 24]
YELLOW_TIME = 3
CYCLE_LENGTH = sum(PHASE_GREEN_TIMES) + 4 * YELLOW_TIME
NUM_PHASES = 4

JAM_DENSITY = 125.0
SAT_DENSITY = 80.0
SAT_FLOW_RATE = 1800.0
FREEFLOW_SPEED = 60.0
FREEFLOW_DENSITY = 20.0

# ---- Priority toggle ----
EV_PRIORITY_ENABLED = True

# ---- EV route config ----
# Option A: use intersection IDs (current method, Dijkstra auto-routes)
EV_START_INTERSECTION = "intersection_1_3"
EV_END_INTERSECTION = "intersection_4_3"

# Option B: use raw (x, y) coordinates (set to None to disable)
# Coordinates will be auto-snapped to nearest road + intersection
EV_START_COORD = None   # dashboard click
EV_END_COORD   = None   # dashboard click

# Set True to be prompted for coordinates at runtime
INTERACTIVE_COORD = False

EMERGENCY_VEHICLES = [
    {
        "id": "ev_fire_1",
        "type": "fire_truck",
        "priority": 1,
        "max_speed": 20.0,
        "start_time": 100,
        "route": [],   # <-- auto Dijkstra, leave empty
        "length": 7.0,
        "width": 2.5,
    },
]

EV_ID = EMERGENCY_VEHICLES[0]["id"]
EV_TYPE = EMERGENCY_VEHICLES[0]["type"]
EV_PRIORITY = EMERGENCY_VEHICLES[0]["priority"]
EV_SPEED_FACTOR = 1.5
EV_DETECTION_DISTANCE = 250.0
EV_WHITELIST = [ev["id"] for ev in EMERGENCY_VEHICLES]
EV_START_POS = EMERGENCY_VEHICLES[0]["route"][0] if EMERGENCY_VEHICLES[0]["route"] else ""

CONFLICT_WEIGHT = 999.0
COORDINATION_ADVANCE = 30.0
FINE_TUNE_MARGIN = 5.0
AUTH_FAR_GRANT_THRESHOLD = 30.0

RECOVERY_CYCLES = 3
RECOVERY_PROPORTIONAL = True

FLOW_RATE_PER_LANE = 0.0
FLOW_RANDOM_FACTOR = 0.2

SIMULATION_STEPS = 900
SIMULATION_STEP_INTERVAL = 1.0

LOG_DIR = "logs"
LOG_FILE = "ev_priority_log.csv"
