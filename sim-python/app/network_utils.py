# -*- coding: utf-8 -*-
import json
import random
import os
import config as cfg

random.seed(42)


def generate_roadnet(output_path="roadnet.json"):
    """Generate 3x3 grid roadnet for CityFlow"""
    intersections = []
    roads = []

    # ==========================================================
    # 1. Create roads (unidirectional)
    # ==========================================================
    for r in range(cfg.GRID_ROWS):
        for c in range(cfg.GRID_COLS - 1):
            x1, y1 = c * cfg.ROAD_LENGTH, r * cfg.ROAD_LENGTH
            x2, y2 = (c + 1) * cfg.ROAD_LENGTH, r * cfg.ROAD_LENGTH
            # L to R
            roads.append({
                "id": f"road_{r}_{c}_{r}_{c+1}",
                "startIntersection": f"intersection_{r}_{c}",
                "endIntersection": f"intersection_{r}_{c+1}",
                "points": [{"x": x1, "y": y1}, {"x": x2, "y": y2}],
                "lanes": [{"width": cfg.LANE_WIDTH, "maxSpeed": cfg.SPEED_LIMIT}] * cfg.NUM_LANES
            })
            # R to L
            roads.append({
                "id": f"road_{r}_{c+1}_{r}_{c}",
                "startIntersection": f"intersection_{r}_{c+1}",
                "endIntersection": f"intersection_{r}_{c}",
                "points": [{"x": x2, "y": y2}, {"x": x1, "y": y1}],
                "lanes": [{"width": cfg.LANE_WIDTH, "maxSpeed": cfg.SPEED_LIMIT}] * cfg.NUM_LANES
            })

    for r in range(cfg.GRID_ROWS - 1):
        for c in range(cfg.GRID_COLS):
            x1, y1 = c * cfg.ROAD_LENGTH, r * cfg.ROAD_LENGTH
            x2, y2 = c * cfg.ROAD_LENGTH, (r + 1) * cfg.ROAD_LENGTH
            # Top to bottom
            roads.append({
                "id": f"road_{r}_{c}_{r+1}_{c}",
                "startIntersection": f"intersection_{r}_{c}",
                "endIntersection": f"intersection_{r+1}_{c}",
                "points": [{"x": x1, "y": y1}, {"x": x2, "y": y2}],
                "lanes": [{"width": cfg.LANE_WIDTH, "maxSpeed": cfg.SPEED_LIMIT}] * cfg.NUM_LANES
            })
            # Bottom to top
            roads.append({
                "id": f"road_{r+1}_{c}_{r}_{c}",
                "startIntersection": f"intersection_{r+1}_{c}",
                "endIntersection": f"intersection_{r}_{c}",
                "points": [{"x": x2, "y": y2}, {"x": x1, "y": y1}],
                "lanes": [{"width": cfg.LANE_WIDTH, "maxSpeed": cfg.SPEED_LIMIT}] * cfg.NUM_LANES
            })

    road_map = {r["id"]: r for r in roads}

    # ==========================================================
    # 2. Build intersections with roadLinks
    # ==========================================================
    for r in range(cfg.GRID_ROWS):
        for c in range(cfg.GRID_COLS):
            inter_id = f"intersection_{r}_{c}"
            x, y = c * cfg.ROAD_LENGTH, r * cfg.ROAD_LENGTH

            # Collect connected roads
            incoming = []
            outgoing = []
            # N: from (r-1,c) to here
            if r > 0:
                incoming.append(("N", f"road_{r-1}_{c}_{r}_{c}"))
                outgoing.append(("N", f"road_{r}_{c}_{r-1}_{c}"))
            # S: from (r+1,c) to here
            if r < cfg.GRID_ROWS - 1:
                incoming.append(("S", f"road_{r+1}_{c}_{r}_{c}"))
                outgoing.append(("S", f"road_{r}_{c}_{r+1}_{c}"))
            # E: from (r,c+1) to here
            if c < cfg.GRID_COLS - 1:
                incoming.append(("E", f"road_{r}_{c+1}_{r}_{c}"))
                outgoing.append(("E", f"road_{r}_{c}_{r}_{c+1}"))
            # W: from (r,c-1) to here
            if c > 0:
                incoming.append(("W", f"road_{r}_{c-1}_{r}_{c}"))
                outgoing.append(("W", f"road_{r}_{c}_{r}_{c-1}"))

            out_map = {d: rid for d, rid in outgoing}
            all_connected = sorted([rid for _, rid in incoming] + [rid for _, rid in outgoing])

            # Build roadLinks: for each incoming direction, create through/left/right
            road_links = []
            rl_idx = 0

            for d, in_road in incoming:
                # Determine through, left, right outgoing roads based on direction
                if d == "N":   # coming from north, heading south
                    straight_dir, left_dir, right_dir = "S", "E", "W"
                    entry = {"x": x, "y": y - 15}
                    s_exit = {"x": x, "y": y + 15}
                    l_exit = {"x": x + 15, "y": y}
                    r_exit = {"x": x - 15, "y": y}
                elif d == "S": # coming from south, heading north
                    straight_dir, left_dir, right_dir = "N", "W", "E"
                    entry = {"x": x, "y": y + 15}
                    s_exit = {"x": x, "y": y - 15}
                    l_exit = {"x": x - 15, "y": y}
                    r_exit = {"x": x + 15, "y": y}
                elif d == "E": # coming from east, heading west
                    straight_dir, left_dir, right_dir = "W", "N", "S"
                    entry = {"x": x + 15, "y": y}
                    s_exit = {"x": x - 15, "y": y}
                    l_exit = {"x": x, "y": y - 15}
                    r_exit = {"x": x, "y": y + 15}
                else:          # d == "W", coming from west, heading east
                    straight_dir, left_dir, right_dir = "E", "S", "N"
                    entry = {"x": x - 15, "y": y}
                    s_exit = {"x": x + 15, "y": y}
                    l_exit = {"x": x, "y": y + 15}
                    r_exit = {"x": x, "y": y - 15}

                # Helper: generate 4-point path through intersection with lane offset
                # Lane offset: lane 0 is left side (-offset), lane 1 is right side (+offset)
                # For N-S roads, offset is in X; for E-W roads, offset is in Y
                lane_offset = cfg.LANE_WIDTH * 0.5  # half-lane offset from center
                def make_pts(entry_pt, exit_pt, lane_idx=0):
                    ex, ey = entry_pt["x"], entry_pt["y"]
                    xx, xy = exit_pt["x"], exit_pt["y"]
                    # Determine if road is N-S (dx=0) or E-W (dy=0)
                    dx, dy = xx - ex, xy - ey
                    offset = (lane_idx - 0.5) * cfg.LANE_WIDTH
                    if abs(dx) < 1e-6:  # N-S road, offset in X
                        ox, oy = offset, 0
                    else:  # E-W road, offset in Y
                        ox, oy = 0, offset
                    return [
                        {"x": ex + ox, "y": ey + oy},
                        {"x": ex + (xx - ex) * 0.33 + ox, "y": ey + (xy - ey) * 0.33 + oy},
                        {"x": ex + (xx - ex) * 0.67 + ox, "y": ey + (xy - ey) * 0.67 + oy},
                        {"x": xx + ox, "y": xy + oy}
                    ]

                # Through
                if straight_dir in out_map:
                    road_links.append({
                        "type": "go_straight",
                        "startRoad": in_road,
                        "endRoad": out_map[straight_dir],
                        "direction": 0,
                        "laneLinks": [
                            {"startLaneIndex": 0, "endLaneIndex": 0, "points": make_pts(entry, s_exit, 0)},
                            {"startLaneIndex": 1, "endLaneIndex": 1, "points": make_pts(entry, s_exit, 1)}
                        ]
                    })
                    rl_idx += 1

                # Left
                if left_dir in out_map:
                    road_links.append({
                        "type": "turn_left",
                        "startRoad": in_road,
                        "endRoad": out_map[left_dir],
                        "direction": 0,
                        "laneLinks": [
                            {"startLaneIndex": 0, "endLaneIndex": 0, "points": make_pts(entry, l_exit, 0)},
                            {"startLaneIndex": 1, "endLaneIndex": 0, "points": make_pts(entry, l_exit, 1)}
                        ]
                    })
                    rl_idx += 1

                # Right
                if right_dir in out_map:
                    road_links.append({
                        "type": "turn_right",
                        "startRoad": in_road,
                        "endRoad": out_map[right_dir],
                        "direction": 0,
                        "laneLinks": [
                            {"startLaneIndex": 0, "endLaneIndex": 0, "points": make_pts(entry, r_exit, 0)},
                            {"startLaneIndex": 1, "endLaneIndex": 0, "points": make_pts(entry, r_exit, 1)}
                        ]
                    })
                    rl_idx += 1

            # Build signal phases
            # Group roadLinks by direction type
            ns_straight_right = []  # Phase 0: N-S through + right
            ns_left = []            # Phase 1: N-S left
            ew_straight_right = []  # Phase 2: E-W through + right
            ew_left = []            # Phase 3: E-W left

            for idx, rl in enumerate(road_links):
                in_road = rl["startRoad"]
                parts = in_road.split("_")
                from_r, from_c = int(parts[1]), int(parts[2])
                dr, dc = r - from_r, c - from_c
                is_ns = abs(dr) == 1 and dc == 0
                is_ew = dr == 0 and abs(dc) == 1
                is_straight = rl["type"] == "go_straight"
                is_right = rl["type"] == "turn_right"
                is_left = rl["type"] == "turn_left"

                if is_ns and (is_straight or is_right):
                    ns_straight_right.append(idx)
                elif is_ns and is_left:
                    ns_left.append(idx)
                elif is_ew and (is_straight or is_right):
                    ew_straight_right.append(idx)
                elif is_ew and is_left:
                    ew_left.append(idx)

            # Build lightphases - only include non-empty phases
            lightphases = []
            phases_def = [
                (cfg.PHASE_GREEN_TIMES[0], ns_straight_right),
                (cfg.YELLOW_TIME, ns_straight_right),
                (cfg.PHASE_GREEN_TIMES[1], ns_left),
                (cfg.YELLOW_TIME, ns_left),
                (cfg.PHASE_GREEN_TIMES[2], ew_straight_right),
                (cfg.YELLOW_TIME, ew_straight_right),
                (cfg.PHASE_GREEN_TIMES[3], ew_left),
                (cfg.YELLOW_TIME, ew_left),
            ]

            for phase_time, links in phases_def:
                # Keep ALL 8 phases; empty links=[] is valid for edge intersections
                    lightphases.append({
                        "time": phase_time,
                        "availableRoadLinks": links
                    })

            intersections.append({
                "id": inter_id,
                "virtual": False,
                "point": {"x": x, "y": y},
                "width": cfg.INTERSECTION_WIDTH,
                "roads": all_connected,
                "roadLinks": road_links,
                "trafficLight": {
                    "roadLinkIndices": list(range(len(road_links))),
                    "lightphases": lightphases
                }
            })


    # === Add virtual intersections for boundary roads (CityFlow requirement) ===
    virtual_count = 0
    for road in roads:
        sid, eid = road["startIntersection"], road["endIntersection"]
        for intr_id in [sid, eid]:
            parts = intr_id.split("_")
            if len(parts) >= 3:
                ir, ic = int(parts[1]), int(parts[2])
                if ir == 0 or ir == cfg.GRID_ROWS-1 or ic == 0 or ic == cfg.GRID_COLS-1:
                    start_parts = sid.split("_")
                    end_parts = eid.split("_")
                    sr, sc = int(start_parts[1]), int(start_parts[2])
                    er, ec = int(end_parts[1]), int(end_parts[2])
                    dr, dc = er - sr, ec - sc
                    goes_out = ((dr < 0 and sr == cfg.GRID_ROWS-1) or (dr > 0 and sr == 0) or
                               (dc > 0 and sc == 0) or (dc < 0 and sc == cfg.GRID_COLS-1))
                    if goes_out:
                        vx, vy = road["points"][0]["x"], road["points"][0]["y"]
                        vid = f"virtual_{road['id']}"
                        existing = [v for v in intersections if v.get("virtual") and v["id"] == vid]
                        if not existing:
                            intersections.append({
                                "id": vid, "virtual": True,
                                "point": {"x": vx, "y": vy},
                                "width": cfg.INTERSECTION_WIDTH,
                                "roads": [road["id"]],
                                "roadLinks": [],
                                "trafficLight": {"roadLinkIndices": [], "lightphases": [{"time": 9999, "availableRoadLinks": []}]}
                            })
                            virtual_count += 1
    if virtual_count:
        print(f"[OK] Added {virtual_count} virtual intersections for boundary roads")

    roadnet = {"intersections": intersections, "roads": roads}

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(roadnet, f, indent=2, ensure_ascii=False)
    print(f"[OK] roadnet.json generated: {len(intersections)} intersections, {len(roads)} roads")
    return roadnet


def generate_flow(roadnet, output_path="flow.json"):
    roads = roadnet["roads"]
    road_map = {r["id"]: r for r in roads}

    def is_entry_road(road):
        sid = road["startIntersection"]
        eid = road["endIntersection"]
        sr, sc = int(sid.split("_")[1]), int(sid.split("_")[2])
        er, ec = int(eid.split("_")[1]), int(eid.split("_")[2])
        dr, dc = er - sr, ec - sc
        if dr < 0 and sr == cfg.GRID_ROWS - 1: return True
        if dr > 0 and sr == 0: return True
        if dc > 0 and sc == 0: return True
        if dc < 0 and sc == cfg.GRID_COLS - 1: return True
        return False

    entry_roads = [r for r in roads if is_entry_road(r)]
    flows = []
    vid = 0
    random.seed(42)

    # Vehicle properties (matching CityFlow example format)
    veh_props = {
        "length": 5.0,
        "width": 2.0,
        "maxPosAcc": 2.0,
        "maxNegAcc": 4.5,
        "usualPosAcc": 2.0,
        "usualNegAcc": 4.5,
        "minGap": 2.5,
        "maxSpeed": cfg.SPEED_LIMIT,
        "headwayTime": 1.5
    }

    # Background vehicles as flows
    for road in entry_roads:
        lane_count = len(road["lanes"])
        total_flow = cfg.FLOW_RATE_PER_LANE * lane_count
        interval = 3600.0 / total_flow if total_flow > 0 else 10.0
        start_offset = 0.0

        cur = road["endIntersection"]
        route = [road["id"]]
        for _ in range(random.randint(2, 4)):
            nxt = [r2["id"] for r2 in roads
                   if r2["startIntersection"] == cur and r2["id"] != route[-1]]
            if not nxt: break
            nid = random.choice(nxt)
            route.append(nid)
            cur = road_map[nid]["endIntersection"]

        if len(route) >= 2:
            flows.append({
                "vehicle": veh_props,
                "route": route[:6],
                "interval": round(interval, 1),
                "startTime": 0,
                "endTime": cfg.SIMULATION_STEPS
            })

    # EV as single vehicle (startTime == endTime)
    from ev_priority import DijkstraPathPlanner
    planner = DijkstraPathPlanner()
    ev_intersections = planner.find_path("intersection_2_0", "intersection_0_2")
    if not ev_intersections:
        ev_intersections = ["intersection_2_0","intersection_1_0","intersection_0_0","intersection_0_1","intersection_0_2"]

    ev_road_route = []
    for i in range(len(ev_intersections) - 1):
        fi, ti = ev_intersections[i], ev_intersections[i + 1]
        fr, fc = int(fi.split("_")[1]), int(fi.split("_")[2])
        tr, tc = int(ti.split("_")[1]), int(ti.split("_")[2])
        rid = f"road_{fr}_{fc}_{tr}_{tc}"
        if rid in road_map: ev_road_route.append(rid)

    flows.append({
        "vehicle": {
            "length": 5.0,
            "width": 2.0,
            "maxPosAcc": 3.0,
            "maxNegAcc": 6.0,
            "usualPosAcc": 3.0,
            "usualNegAcc": 6.0,
            "minGap": 2.5,
            "maxSpeed": cfg.SPEED_LIMIT * cfg.EV_SPEED_FACTOR,
            "headwayTime": 1.0
        },
        "route": ev_road_route,
        "interval": 1.0,
        "startTime": 30.0,
        "endTime": 30.0
    })

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(flows, f, indent=2, ensure_ascii=False)
    print(f"[OK] flow.json: {len(flows)} flows ({len(flows)-1} bg + 1 EV)")
    print(f"     EV route: {' -> '.join(ev_intersections)}")
    return flows

if __name__ == "__main__":
    rn = generate_roadnet()
    generate_flow(rn)