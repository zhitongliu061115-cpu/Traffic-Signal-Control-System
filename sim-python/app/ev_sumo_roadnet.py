"""
sumo_roadnet.py — Parse SUMO .net.xml into CityFlow-compatible roadnet dict.

Usage:
    from sumo_roadnet import parse_net_xml
    roadnet = parse_net_xml("path/to/network.net.xml")
    # roadnet["intersections"], roadnet["roads"], roadnet["roadLinks"]
"""
from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

JsonDict = dict[str, Any]


def parse_net_xml(net_path: str | Path) -> JsonDict:
    """Parse a SUMO .net.xml and return CityFlow-compatible roadnet dict."""
    net_path = Path(net_path)
    tree = ET.parse(net_path)
    root = tree.getroot()

    # --- Junctions ---
    junction_map: dict[str, dict] = {}
    for j in root.iter("junction"):
        jid = j.get("id", "")
        jtype = j.get("type", "")
        x = float(j.get("x", 0))
        y = float(j.get("y", 0))
        is_virtual = jtype in ("dead_end", "internal") or jid.startswith(":") or jid.startswith("virtual_")
        junction_map[jid] = {
            "id": jid,
            "point": {"x": x, "y": y},
            "virtual": is_virtual,
            "roadLinks": [],
            "trafficLight": {"lightphases": []},
        }

    # --- Edges (roads) ---
    roads: list[dict] = []
    edge_to_road: dict[str, dict] = {}

    for edge in root.iter("edge"):
        eid = edge.get("id", "")
        # Skip SUMO internal edges (prefixed with ':')
        if eid.startswith(":"):
            continue

        from_j = edge.get("from", "")
        to_j = edge.get("to", "")

        lanes = list(edge.iter("lane"))
        num_lanes = len(lanes)
        speed = float(lanes[0].get("speed", "13.89")) if lanes else 13.89

        points: list[dict] = []
        if lanes:
            shape_str = lanes[0].get("shape", "")
            if shape_str:
                for coord in shape_str.split():
                    cx, cy = coord.split(",")[:2]
                    points.append({"x": float(cx), "y": float(cy)})
            else:
                fj = junction_map.get(from_j)
                tj = junction_map.get(to_j)
                if fj and tj:
                    points = [
                        {"x": fj["point"]["x"], "y": fj["point"]["y"]},
                        {"x": tj["point"]["x"], "y": tj["point"]["y"]},
                    ]

        start_int = _resolve_junction(from_j, junction_map)
        end_int = _resolve_junction(to_j, junction_map)

        road = {
            "id": eid,
            "from": start_int,
            "to": end_int,
            "startIntersection": start_int,
            "endIntersection": end_int,
            "points": points or [{"x": 0, "y": 0}, {"x": 0, "y": 0}],
            "numLanes": num_lanes,
            "speed": speed,
        }
        roads.append(road)
        edge_to_road[eid] = road

    # --- Connections → roadLinks ---
    road_links: list[dict] = []
    for conn in root.iter("connection"):
        from_edge = conn.get("from", "")
        to_edge = conn.get("to", "")
        direction = conn.get("dir", "s")

        from_road = edge_to_road.get(from_edge)
        if not from_road:
            continue
        inter_id = from_road["endIntersection"]

        rl = {
            "intersectionId": inter_id,
            "fromRoadId": from_edge,
            "toRoadId": to_edge,
            "startRoad": from_edge,
            "endRoad": to_edge,
            "type": _direction_to_type(direction),
        }
        road_links.append(rl)

        if inter_id in junction_map:
            junction_map[inter_id]["roadLinks"].append(rl)

    # --- Traffic light phases ---
    for tl in root.iter("tlLogic"):
        tl_id = tl.get("id", "")
        if tl_id not in junction_map:
            continue
        phases = []
        for idx, phase in enumerate(tl.iter("phase")):
            duration = float(phase.get("duration", "30"))
            state = phase.get("state", "")
            phases.append({
                "phaseIndex": idx,
                "duration": duration,
                "state": state,
            })
        junction_map[tl_id]["trafficLight"]["lightphases"] = phases

    # --- Auto-enrich intersections lacking phase data ---
    # For grid-based networks with road_X_Y_Z naming, infer phases from roadLinks.
    for jid, jdata in junction_map.items():
        if jdata["virtual"]:
            continue
        tl = jdata["trafficLight"]
        if tl["lightphases"]:
            _populate_links_from_state(tl["lightphases"], jdata["roadLinks"])
            continue
        rls = jdata["roadLinks"]
        if not rls:
            continue
        # Build simple 4-phase cycle from roadLinks
        phases = _infer_phases_from_roadlinks(rls)
        if phases:
            tl["lightphases"] = phases

    intersections = list(junction_map.values())

    return {
        "intersections": intersections,
        "roads": roads,
        "roadLinks": road_links,
    }


def _resolve_junction(jid: str, junction_map: dict) -> str:
    if jid in junction_map:
        return jid
    return jid


def _direction_to_type(direc: str) -> str:
    mapping = {"s": "go_straight", "t": "turn", "l": "turn_left", "r": "turn_right",
               "L": "turn_left", "R": "turn_right"}
    return mapping.get(direc, "go_straight")



def _populate_links_from_state(lightphases: list[dict], road_links: list[dict]) -> None:
    """Set availableRoadLinks for each phase based on its state string.
    
    Each char in the state string corresponds to a roadLink index.
    'G'/'g' (green) = this connection is active in this phase.
    'y'/'Y' (yellow) = also active (yellow still allows passage).
    'r'/'R' (red) = not active.
    """
    if not road_links:
        return
    for p in lightphases:
        state = p.get("state", "")
        if len(state) != len(road_links):
            continue
        active = []
        for i, ch in enumerate(state):
            if ch.lower() in ("g", "y"):
                active.append(i)
        if active:
            p["availableRoadLinks"] = active

def _infer_phases_from_roadlinks(road_links: list[dict]) -> list[dict]:
    """Infer simplified 4-phase traffic light from roadLinks."""
    if len(road_links) <= 1:
        return [{"phaseIndex": 0, "duration": 30.0, "state": "GGGgrrrrGGGgrrrr", "availableRoadLinks": list(range(len(road_links)))}]

    ns_straight = []
    ew_straight = []
    ns_left = []
    ew_left = []

    for idx, rl in enumerate(road_links):
        in_rid = rl.get("startRoad", "")
        out_rid = rl.get("endRoad", "")
        in_d = _parse_road_direction(in_rid)
        out_d = _parse_road_direction(out_rid)
        if in_d is None or out_d is None:
            ew_straight.append(idx)
            continue
        if in_d in (1, 3):
            if in_d == out_d:
                ns_straight.append(idx)
            else:
                ns_left.append(idx)
        else:
            if in_d == out_d:
                ew_straight.append(idx)
            else:
                ew_left.append(idx)

    phases = []
    for gi, indices in enumerate([ns_straight, ew_straight, ns_left, ew_left]):
        if not indices:
            continue
        phases.append({"phaseIndex": gi, "duration": 30.0, "state": "GGGgrrrrGGGgrrrr", "availableRoadLinks": indices})

    if not phases:
        phases = [{"phaseIndex": 0, "duration": 30.0, "state": "GGGgrrrrGGGgrrrr", "availableRoadLinks": list(range(len(road_links)))}]
    return phases

def enrich_grid_roadnet(roadnet: JsonDict) -> JsonDict:
    """Add roadLinks + phases for grid networks with road_X_Y_Z naming.

    Call this after parse_net_xml() if the .net.xml has no <connection> elements.
    """
    roads = roadnet.get("roads", [])
    intersections = roadnet.get("intersections", [])

    # Build lookup: (from_inter, to_inter) → road
    inter_to_road: dict[tuple, str] = {}
    for r in roads:
        si = r.get("startIntersection", "")
        ei = r.get("endIntersection", "")
        if si and ei:
            inter_to_road[(si, ei)] = r["id"]

    # Build lookup: intersection → roads that end at it
    incoming: dict[str, list[dict]] = {}
    for r in roads:
        ei = r.get("endIntersection", "")
        if ei:
            incoming.setdefault(ei, []).append(r)

    new_links: list[dict] = []
    inter_map = {i["id"]: i for i in intersections}

    for inter in intersections:
        iid = inter["id"]
        if inter.get("virtual"):
            continue

        # Skip if this intersection already has roadLinks from XML parsing
        if inter.get("roadLinks"):
            # Still ensure phases exist
            # Enrich existing phases with availableRoadLinks from state strings
            tl = inter.get("trafficLight", {})
            phases = tl.get("lightphases", [])
            rls = inter.get("roadLinks", [])
            if phases and rls:
                _populate_links_from_state(phases, rls)
            continue

        # Find roads entering and leaving this intersection
        in_roads = incoming.get(iid, [])
        rls = []

        for in_r in in_roads:
            in_rid = in_r["id"]
            si = in_r.get("startIntersection", "")

            # Find roads leaving from this intersection
            # A leaving road starts at this intersection and goes somewhere else
            in_direction = _parse_road_direction(in_rid)

            for out_r in roads:
                if out_r.get("startIntersection", "") != iid:
                    continue
                out_rid = out_r["id"]
                ei = out_r.get("endIntersection", "")
                if ei == si:
                    continue  # no U-turn for now

                out_direction = _parse_road_direction(out_rid)
                # Determine turn type
                if in_direction is not None and out_direction is not None:
                    diff = (out_direction - in_direction) % 4
                    if diff == 0:
                        ttype = "go_straight"
                    elif diff == 1:
                        ttype = "turn_right"
                    elif diff == 2:
                        ttype = "turn"
                    else:
                        ttype = "turn_left"
                else:
                    ttype = "go_straight"

                rls.append({
                    "intersectionId": iid,
                    "fromRoadId": in_rid,
                    "toRoadId": out_rid,
                    "startRoad": in_rid,
                    "endRoad": out_rid,
                    "type": ttype,
                })
                new_links.append(rls[-1])

        inter["roadLinks"] = rls

        # Build phases
        if not inter.get("trafficLight", {}).get("lightphases"):
            phases = _infer_phases_from_roadlinks(rls)
            inter.setdefault("trafficLight", {})["lightphases"] = phases

    # Update roadnet
    roadnet["roadLinks"] = (roadnet.get("roadLinks", []) or []) + new_links
    roadnet["intersections"] = intersections
    return roadnet


def _parse_road_direction(road_id: str) -> int | None:
    """Parse direction from road_X_Y_Z naming. Returns 0-3 or None."""
    parts = road_id.rsplit("_", 1)
    if len(parts) == 2:
        try:
            return int(parts[1])
        except ValueError:
            pass
    return None
