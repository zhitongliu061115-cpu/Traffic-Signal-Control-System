#!/usr/bin/env python3
"""Build a CityFlow roadnet from an adjusted real-map network.

The input is an explicit, human-audited graph:

{
  "sceneId": "custom_real",
  "coordinateSystem": "lnglat",
  "intersections": [
    {"id": "I001", "lng": 117.1, "lat": 36.6, "signalized": true},
    {"id": "W_IN", "lng": 117.0, "lat": 36.6, "virtual": true}
  ],
  "edges": [
    {
      "from": "W_IN",
      "to": "I001",
      "bidirectional": false,
      "laneCount": 3,
      "path": [{"lng": 117.0, "lat": 36.6}, {"lng": 117.1, "lat": 36.6}]
    }
  ]
}

When an edge has no path, the script can either use AMap driving routes
(--fetch-amap) or fall back to a straight segment and emit a validation warning.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
import urllib.parse
import urllib.request
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


DEFAULT_LANE_COUNT = 3
DEFAULT_LANE_WIDTH = 3.2
DEFAULT_MAX_SPEED = 11.111
ROADLINK_DISTANCE_METERS = 15.0
ROADLINK_CURVE_POINTS = 11
PHASE_COUNT = 9


JsonDict = dict[str, Any]
Point = tuple[float, float]


@dataclass
class Report:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    summary: JsonDict = field(default_factory=dict)

    def error(self, message: str) -> None:
        self.errors.append(message)

    def warn(self, message: str) -> None:
        self.warnings.append(message)

    @property
    def ok(self) -> bool:
        return not self.errors


@dataclass(frozen=True)
class Node:
    id: str
    point: Point
    lnglat: Point | None
    virtual: bool
    signalized: bool
    name: str


@dataclass(frozen=True)
class DirectedRoad:
    id: str
    start: str
    end: str
    points: list[Point]
    lane_count: int
    max_speed: float
    source_edge_id: str


@dataclass(frozen=True)
class RoadLinkMeta:
    index: int
    start_road: str
    end_road: str
    movement: str
    approach: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate CityFlow roadnet/flow files from a real-map source network."
    )
    parser.add_argument("--input", required=True, help="Adjusted source network JSON.")
    parser.add_argument("--out-dir", required=True, help="Output scene directory under sim-python/data or any target path.")
    parser.add_argument("--scene-id", help="Scene id to embed in validation metadata.")
    parser.add_argument("--fetch-amap", action="store_true", help="Fetch missing edge paths from AMap driving API.")
    parser.add_argument("--amap-key", default=os.getenv("AMAP_WEB_KEY") or os.getenv("VITE_AMAP_WEB_KEY"))
    parser.add_argument("--flow-interval", type=float, default=3.0)
    parser.add_argument("--flow-start", type=float, default=0.0)
    parser.add_argument("--flow-end", type=float, default=600.0)
    parser.add_argument("--right-turn-policy", choices=["always", "own-phase"], default="always")
    parser.add_argument("--allow-invalid", action="store_true", help="Write outputs even when validation has errors.")
    return parser.parse_args()


def read_json(path: Path) -> JsonDict:
    with path.open("r", encoding="utf-8") as file:
        return json.load(file)


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as file:
        json.dump(payload, file, ensure_ascii=False, indent=2)
        file.write("\n")


def sanitize_id(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_]+", "_", value.strip())
    return cleaned.strip("_") or "unnamed"


def coord_from_item(item: JsonDict) -> Point:
    if "x" in item and "y" in item:
        return float(item["x"]), float(item["y"])
    if "lng" in item and "lat" in item:
        return float(item["lng"]), float(item["lat"])
    if isinstance(item, list) and len(item) >= 2:
        return float(item[0]), float(item[1])
    raise ValueError(f"point requires x/y or lng/lat: {item}")


def lnglat_from_item(item: JsonDict | list[Any]) -> Point | None:
    if isinstance(item, dict) and "lng" in item and "lat" in item:
        return float(item["lng"]), float(item["lat"])
    if isinstance(item, list) and len(item) >= 2:
        return float(item[0]), float(item[1])
    return None


def build_projection(payload: JsonDict) -> tuple[str, float, float, float]:
    coordinate_system = str(payload.get("coordinateSystem", "lnglat")).lower()
    intersections = payload.get("intersections", [])
    if coordinate_system in {"cartesian", "xy", "meter", "meters"}:
        return "cartesian", 0.0, 0.0, 1.0

    lngs = [float(item["lng"]) for item in intersections if "lng" in item]
    lats = [float(item["lat"]) for item in intersections if "lat" in item]
    if not lngs or not lats:
        return "cartesian", 0.0, 0.0, 1.0
    lng0 = float(payload.get("originLng", sum(lngs) / len(lngs)))
    lat0 = float(payload.get("originLat", sum(lats) / len(lats)))
    return "lnglat", lng0, lat0, math.cos(math.radians(lat0))


def project_point(point: Point, projection: tuple[str, float, float, float]) -> Point:
    mode, lng0, lat0, cos_lat = projection
    x, y = point
    if mode == "cartesian":
        return x, y
    lng, lat = x, y
    return (lng - lng0) * cos_lat * 111_320.0, (lat - lat0) * 110_540.0


def load_nodes(payload: JsonDict, projection: tuple[str, float, float, float], report: Report) -> dict[str, Node]:
    nodes: dict[str, Node] = {}
    for item in payload.get("intersections", []):
        raw_id = str(item.get("id", "")).strip()
        if not raw_id:
            report.error("intersection id is required")
            continue
        node_id = sanitize_id(raw_id)
        if node_id in nodes:
            report.error(f"duplicate intersection id: {node_id}")
            continue
        raw_point = coord_from_item(item)
        lnglat = lnglat_from_item(item)
        virtual = bool(item.get("virtual", False))
        signalized = bool(item.get("signalized", not virtual))
        nodes[node_id] = Node(
            id=node_id,
            point=project_point(raw_point, projection),
            lnglat=lnglat,
            virtual=virtual,
            signalized=signalized,
            name=str(item.get("name", node_id)),
        )
    return nodes


def distance(a: Point, b: Point) -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])


def path_length(points: list[Point]) -> float:
    return sum(distance(points[i - 1], points[i]) for i in range(1, len(points)))


def normalize(vector: Point) -> Point:
    length = math.hypot(vector[0], vector[1])
    if length <= 1e-9:
        return 0.0, 0.0
    return vector[0] / length, vector[1] / length


def point_along(points: list[Point], from_end: bool, meters: float) -> Point:
    if len(points) < 2:
        return points[0]
    ordered = list(reversed(points)) if from_end else points
    remaining = meters
    for idx in range(1, len(ordered)):
        prev = ordered[idx - 1]
        cur = ordered[idx]
        seg_len = distance(prev, cur)
        if seg_len >= remaining and seg_len > 0:
            ratio = remaining / seg_len
            return prev[0] + (cur[0] - prev[0]) * ratio, prev[1] + (cur[1] - prev[1]) * ratio
        remaining -= seg_len
    return ordered[-1]


def vector_near_end(points: list[Point], at_end: bool) -> Point:
    if len(points) < 2:
        return 0.0, 0.0
    if at_end:
        return normalize((points[-1][0] - points[-2][0], points[-1][1] - points[-2][1]))
    return normalize((points[1][0] - points[0][0], points[1][1] - points[0][1]))


def offset_point(point: Point, direction: Point, lane_index: int, lane_count: int) -> Point:
    offset = (lane_index - (lane_count - 1) / 2.0) * DEFAULT_LANE_WIDTH
    left = (-direction[1], direction[0])
    return point[0] + left[0] * offset, point[1] + left[1] * offset


def quadratic_curve(start: Point, control: Point, end: Point, count: int) -> list[Point]:
    result = []
    for step in range(count):
        t = step / (count - 1)
        x = (1 - t) ** 2 * start[0] + 2 * (1 - t) * t * control[0] + t**2 * end[0]
        y = (1 - t) ** 2 * start[1] + 2 * (1 - t) * t * control[1] + t**2 * end[1]
        result.append((x, y))
    return result


def to_cityflow_points(points: list[Point]) -> list[JsonDict]:
    return [{"x": round(x, 3), "y": round(y, 3)} for x, y in points]


def fetch_amap_path(origin: Point, destination: Point, amap_key: str, report: Report, edge_id: str) -> list[Point] | None:
    query = urllib.parse.urlencode(
        {
            "origin": f"{origin[0]},{origin[1]}",
            "destination": f"{destination[0]},{destination[1]}",
            "extensions": "all",
            "key": amap_key,
        }
    )
    url = f"https://restapi.amap.com/v3/direction/driving?{query}"
    try:
        with urllib.request.urlopen(url, timeout=15) as response:
            data = json.loads(response.read().decode("utf-8"))
    except Exception as exc:  # pragma: no cover - network path
        report.warn(f"{edge_id}: AMap request failed: {exc}")
        return None

    if data.get("status") != "1" or not data.get("route", {}).get("paths"):
        report.warn(f"{edge_id}: AMap returned no path: {data.get('info') or data}")
        return None

    path: list[Point] = []
    for step in data["route"]["paths"][0].get("steps", []):
        polyline = step.get("polyline")
        if not polyline:
            continue
        for raw in polyline.split(";"):
            lng, lat = raw.split(",")
            path.append((float(lng), float(lat)))
    return path if len(path) >= 2 else None


def edge_path(
    edge: JsonDict,
    from_node: Node,
    to_node: Node,
    projection: tuple[str, float, float, float],
    fetch_amap: bool,
    amap_key: str | None,
    report: Report,
    edge_id: str,
) -> list[Point]:
    raw_path = edge.get("path") or edge.get("points")
    if raw_path:
        points = [project_point(coord_from_item(item), projection) for item in raw_path]
    elif fetch_amap and amap_key and from_node.lnglat and to_node.lnglat:
        fetched = fetch_amap_path(from_node.lnglat, to_node.lnglat, amap_key, report, edge_id)
        points = [project_point(point, projection) for point in fetched] if fetched else []
    else:
        points = []

    if len(points) < 2:
        report.warn(f"{edge_id}: no path geometry; using straight segment")
        points = [from_node.point, to_node.point]

    # Snap endpoints so CityFlow topology and geometry agree exactly.
    points[0] = from_node.point
    points[-1] = to_node.point
    direct = max(distance(from_node.point, to_node.point), 1.0)
    actual = path_length(points)
    if actual / direct > float(edge.get("maxDetourRatio", 2.2)):
        report.warn(f"{edge_id}: path detour ratio is high ({actual / direct:.2f})")
    return points


def make_road_id(start: str, end: str) -> str:
    return f"road_{sanitize_id(start)}_{sanitize_id(end)}"


def build_roads(
    payload: JsonDict,
    nodes: dict[str, Node],
    projection: tuple[str, float, float, float],
    fetch_amap: bool,
    amap_key: str | None,
    report: Report,
) -> dict[str, DirectedRoad]:
    roads: dict[str, DirectedRoad] = {}
    seen_edges: set[tuple[str, str]] = set()
    for idx, edge in enumerate(payload.get("edges", [])):
        from_id = sanitize_id(str(edge.get("from", "")))
        to_id = sanitize_id(str(edge.get("to", "")))
        edge_id = sanitize_id(str(edge.get("id") or f"{from_id}_{to_id}_{idx}"))
        if from_id not in nodes or to_id not in nodes:
            report.error(f"{edge_id}: edge endpoints must exist: {from_id}->{to_id}")
            continue
        if from_id == to_id:
            report.error(f"{edge_id}: self-loop edge is not supported")
            continue
        if (from_id, to_id) in seen_edges:
            report.warn(f"{edge_id}: duplicate directed edge {from_id}->{to_id}")
        seen_edges.add((from_id, to_id))

        lane_count = int(edge.get("laneCount", DEFAULT_LANE_COUNT))
        if lane_count <= 0:
            report.error(f"{edge_id}: laneCount must be positive")
            lane_count = DEFAULT_LANE_COUNT
        max_speed = float(edge.get("maxSpeed", DEFAULT_MAX_SPEED))
        path = edge_path(edge, nodes[from_id], nodes[to_id], projection, fetch_amap, amap_key, report, edge_id)

        forward_id = make_road_id(from_id, to_id)
        roads[forward_id] = DirectedRoad(forward_id, from_id, to_id, path, lane_count, max_speed, edge_id)

        if bool(edge.get("bidirectional", True)):
            reverse_id = make_road_id(to_id, from_id)
            roads[reverse_id] = DirectedRoad(reverse_id, to_id, from_id, list(reversed(path)), lane_count, max_speed, edge_id)
            seen_edges.add((to_id, from_id))
    return roads


def movement_type(in_vector: Point, out_vector: Point) -> str:
    cross = in_vector[0] * out_vector[1] - in_vector[1] * out_vector[0]
    dot = in_vector[0] * out_vector[0] + in_vector[1] * out_vector[1]
    if dot > math.cos(math.radians(35)):
        return "go_straight"
    return "turn_left" if cross > 0 else "turn_right"


def approach_code(in_road: DirectedRoad, intersection: Node) -> str:
    start = in_road.points[0]
    dx = start[0] - intersection.point[0]
    dy = start[1] - intersection.point[1]
    if abs(dx) >= abs(dy):
        return "W" if dx < 0 else "E"
    return "S" if dy < 0 else "N"


def lane_indexes_for_movement(movement: str, lane_count: int) -> list[int]:
    if lane_count <= 1:
        return [0]
    if movement == "turn_right":
        start_lane = 0
    elif movement == "turn_left":
        start_lane = lane_count - 1
    else:
        start_lane = lane_count // 2
    return [start_lane] * lane_count


def build_lane_links(in_road: DirectedRoad, out_road: DirectedRoad, intersection: Node, movement: str) -> list[JsonDict]:
    in_dir = vector_near_end(in_road.points, at_end=True)
    out_dir = vector_near_end(out_road.points, at_end=False)
    in_base = point_along(in_road.points, from_end=True, meters=ROADLINK_DISTANCE_METERS)
    out_base = point_along(out_road.points, from_end=False, meters=ROADLINK_DISTANCE_METERS)
    lane_count = min(in_road.lane_count, out_road.lane_count)
    start_lanes = lane_indexes_for_movement(movement, lane_count)
    links = []
    for end_lane, start_lane in enumerate(start_lanes):
        start = offset_point(in_base, in_dir, start_lane, in_road.lane_count)
        end = offset_point(out_base, out_dir, end_lane, out_road.lane_count)
        curve = quadratic_curve(start, intersection.point, end, ROADLINK_CURVE_POINTS)
        local_curve = [(x - intersection.point[0], y - intersection.point[1]) for x, y in curve]
        links.append(
            {
                "startLaneIndex": start_lane,
                "endLaneIndex": end_lane,
                "points": to_cityflow_points(local_curve),
            }
        )
    return links


def build_phases(links: list[RoadLinkMeta], right_turn_policy: str) -> list[JsonDict]:
    right = [link.index for link in links if link.movement == "turn_right"]
    ew_straight = [link.index for link in links if link.approach in {"E", "W"} and link.movement == "go_straight"]
    ns_straight = [link.index for link in links if link.approach in {"N", "S"} and link.movement == "go_straight"]
    ew_left = [link.index for link in links if link.approach in {"E", "W"} and link.movement == "turn_left"]
    ns_left = [link.index for link in links if link.approach in {"N", "S"} and link.movement == "turn_left"]

    def with_right(items: list[int]) -> list[int]:
        merged = set(items)
        if right_turn_policy == "always":
            merged.update(right)
        return sorted(merged)

    phase_links = [
        sorted(right),
        with_right(ew_straight),
        with_right(ns_straight),
        with_right(ew_left),
        with_right(ns_left),
        with_right(ew_straight + ew_left),
        with_right(ns_straight + ns_left),
        with_right(ew_straight + ns_straight),
        with_right(ew_left + ns_left),
    ]
    return [
        {"time": 5 if idx == 0 else 30, "availableRoadLinks": values}
        for idx, values in enumerate(phase_links)
    ]


def build_intersections(nodes: dict[str, Node], roads: dict[str, DirectedRoad], right_turn_policy: str, report: Report) -> list[JsonDict]:
    incoming_by_node: dict[str, list[DirectedRoad]] = {node_id: [] for node_id in nodes}
    outgoing_by_node: dict[str, list[DirectedRoad]] = {node_id: [] for node_id in nodes}
    for road in roads.values():
        outgoing_by_node.setdefault(road.start, []).append(road)
        incoming_by_node.setdefault(road.end, []).append(road)

    result = []
    for node in nodes.values():
        connected_roads = sorted({road.id for road in incoming_by_node[node.id] + outgoing_by_node[node.id]})
        road_links = []
        link_meta: list[RoadLinkMeta] = []
        if not node.virtual:
            link_idx = 0
            for in_road in sorted(incoming_by_node[node.id], key=lambda item: item.id):
                for out_road in sorted(outgoing_by_node[node.id], key=lambda item: item.id):
                    if in_road.start == out_road.end:
                        continue
                    in_vec = vector_near_end(in_road.points, at_end=True)
                    out_vec = vector_near_end(out_road.points, at_end=False)
                    movement = movement_type(in_vec, out_vec)
                    approach = approach_code(in_road, node)
                    road_links.append(
                        {
                            "type": movement,
                            "startRoad": in_road.id,
                            "endRoad": out_road.id,
                            "direction": {"go_straight": 0, "turn_left": 1, "turn_right": 2}[movement],
                            "laneLinks": build_lane_links(in_road, out_road, node, movement),
                        }
                    )
                    link_meta.append(RoadLinkMeta(link_idx, in_road.id, out_road.id, movement, approach))
                    link_idx += 1
            if len(incoming_by_node[node.id]) > 0 and len(outgoing_by_node[node.id]) > 0 and not road_links:
                report.warn(f"{node.id}: no legal roadLinks generated")

        result.append(
            {
                "id": node.id,
                "point": {"x": round(node.point[0], 3), "y": round(node.point[1], 3)},
                "width": 0,
                "roads": connected_roads,
                "roadLinks": road_links,
                "trafficLight": {
                    "roadLinkIndices": list(range(len(road_links))),
                    "lightphases": build_phases(link_meta, right_turn_policy) if not node.virtual else [
                        {"time": 5 if idx == 0 else 30, "availableRoadLinks": []}
                        for idx in range(PHASE_COUNT)
                    ],
                },
                "virtual": node.virtual,
            }
        )
    return result


def build_roadnet(nodes: dict[str, Node], roads: dict[str, DirectedRoad], right_turn_policy: str, report: Report) -> JsonDict:
    road_payload = []
    for road in sorted(roads.values(), key=lambda item: item.id):
        road_payload.append(
            {
                "id": road.id,
                "startIntersection": road.start,
                "endIntersection": road.end,
                "points": to_cityflow_points(road.points),
                "lanes": [
                    {"width": DEFAULT_LANE_WIDTH, "maxSpeed": road.max_speed}
                    for _ in range(road.lane_count)
                ],
            }
        )
    return {
        "intersections": build_intersections(nodes, roads, right_turn_policy, report),
        "roads": road_payload,
    }


def road_graph(roads: dict[str, DirectedRoad]) -> dict[str, list[DirectedRoad]]:
    graph: dict[str, list[DirectedRoad]] = {}
    for road in roads.values():
        graph.setdefault(road.start, []).append(road)
    return graph


def shortest_route(graph: dict[str, list[DirectedRoad]], start_road: DirectedRoad, target_road: DirectedRoad) -> list[str] | None:
    queue: deque[tuple[DirectedRoad, list[str]]] = deque([(start_road, [start_road.id])])
    visited = {start_road.id}
    while queue:
        current_road, route = queue.popleft()
        if current_road.end == target_road.start:
            return route + [target_road.id]
        for next_road in graph.get(current_road.end, []):
            # Keep generated routes compatible with roadLinks, which omit U-turns.
            if next_road.end == current_road.start:
                continue
            if next_road.id in visited:
                continue
            visited.add(next_road.id)
            queue.append((next_road, route + [next_road.id]))
    return None


def vehicle_template(max_speed: float = DEFAULT_MAX_SPEED) -> JsonDict:
    return {
        "length": 5.0,
        "width": 2.0,
        "maxPosAcc": 2.0,
        "maxNegAcc": 4.5,
        "usualPosAcc": 2.0,
        "usualNegAcc": 4.5,
        "minGap": 2.5,
        "maxSpeed": max_speed,
        "headwayTime": 2.0,
    }


def build_flows(
    nodes: dict[str, Node],
    roads: dict[str, DirectedRoad],
    interval: float,
    start_time: float,
    end_time: float,
    report: Report,
) -> list[JsonDict]:
    graph = road_graph(roads)
    entry_roads = [road for road in roads.values() if nodes[road.start].virtual and not nodes[road.end].virtual]
    exit_roads = [road for road in roads.values() if not nodes[road.start].virtual and nodes[road.end].virtual]
    if not entry_roads or not exit_roads:
        report.warn("no virtual boundary entry/exit roads found; flow.json will be empty")
        return []

    flows = []
    for entry in sorted(entry_roads, key=lambda item: item.id):
        for exit_road in sorted(exit_roads, key=lambda item: item.id):
            if entry.start == exit_road.end:
                continue
            if entry.end == exit_road.start:
                route = [entry.id, exit_road.id]
            else:
                route = shortest_route(graph, entry, exit_road)
            if not route or len(route) < 2:
                report.warn(f"no route from {entry.id} to {exit_road.id}")
                continue
            flows.append(
                {
                    "vehicle": vehicle_template(min(entry.max_speed, exit_road.max_speed)),
                    "route": route,
                    "interval": interval,
                    "startTime": start_time,
                    "endTime": end_time,
                }
            )
    return flows


def validate_outputs(roadnet: JsonDict, flows: list[JsonDict], report: Report) -> None:
    road_ids = {road["id"] for road in roadnet["roads"]}
    intersection_ids = {intersection["id"] for intersection in roadnet["intersections"]}
    road_by_id = {road["id"]: road for road in roadnet["roads"]}
    legal_transitions: set[tuple[str, str]] = set()

    for road in roadnet["roads"]:
        if road["startIntersection"] not in intersection_ids or road["endIntersection"] not in intersection_ids:
            report.error(f"{road['id']}: road endpoint is missing")
        if len(road.get("points", [])) < 2:
            report.error(f"{road['id']}: road requires at least two points")

    for intersection in roadnet["intersections"]:
        links = intersection.get("roadLinks", [])
        for idx, link in enumerate(links):
            if link.get("startRoad") not in road_ids or link.get("endRoad") not in road_ids:
                report.error(f"{intersection['id']}: roadLink {idx} references missing roads")
            else:
                legal_transitions.add((link["startRoad"], link["endRoad"]))
            start_road = road_by_id.get(link.get("startRoad"))
            end_road = road_by_id.get(link.get("endRoad"))
            for lane_link in link.get("laneLinks", []):
                if start_road and int(lane_link.get("startLaneIndex", -1)) >= len(start_road.get("lanes", [])):
                    report.error(f"{intersection['id']}: roadLink {idx} startLaneIndex out of range")
                if end_road and int(lane_link.get("endLaneIndex", -1)) >= len(end_road.get("lanes", [])):
                    report.error(f"{intersection['id']}: roadLink {idx} endLaneIndex out of range")
        max_link_index = len(links) - 1
        for phase_idx, phase in enumerate(intersection.get("trafficLight", {}).get("lightphases", []), start=1):
            for link_idx in phase.get("availableRoadLinks", []):
                if link_idx < 0 or link_idx > max_link_index:
                    report.error(f"{intersection['id']}: phase {phase_idx} has invalid roadLink index {link_idx}")

    for flow_idx, flow in enumerate(flows):
        route = flow.get("route", [])
        if not route:
            report.error(f"flow {flow_idx}: route is empty")
            continue
        for road_id in route:
            if road_id not in road_by_id:
                report.error(f"flow {flow_idx}: missing road {road_id}")
        for left, right in zip(route, route[1:]):
            left_road = road_by_id.get(left)
            right_road = road_by_id.get(right)
            if left_road and right_road and left_road["endIntersection"] != right_road["startIntersection"]:
                report.error(f"flow {flow_idx}: route is not contiguous at {left}->{right}")
            elif left_road and right_road:
                transition_intersection = left_road["endIntersection"]
                if not next(item for item in roadnet["intersections"] if item["id"] == transition_intersection).get("virtual"):
                    if (left, right) not in legal_transitions:
                        report.error(f"flow {flow_idx}: route transition has no roadLink at {transition_intersection}: {left}->{right}")


def build(payload: JsonDict, args: argparse.Namespace) -> tuple[JsonDict, list[JsonDict], Report]:
    report = Report()
    projection = build_projection(payload)
    nodes = load_nodes(payload, projection, report)
    roads = build_roads(payload, nodes, projection, args.fetch_amap, args.amap_key, report)
    roadnet = build_roadnet(nodes, roads, args.right_turn_policy, report)
    flows = build_flows(nodes, roads, args.flow_interval, args.flow_start, args.flow_end, report)
    validate_outputs(roadnet, flows, report)
    report.summary = {
        "sceneId": args.scene_id or payload.get("sceneId"),
        "intersections": len(roadnet["intersections"]),
        "realIntersections": sum(1 for item in roadnet["intersections"] if not item.get("virtual")),
        "roads": len(roadnet["roads"]),
        "flows": len(flows),
        "errors": len(report.errors),
        "warnings": len(report.warnings),
    }
    return roadnet, flows, report


def main() -> int:
    args = parse_args()
    input_path = Path(args.input)
    out_dir = Path(args.out_dir)
    payload = read_json(input_path)
    roadnet, flows, report = build(payload, args)

    write_json(out_dir / "validation_report.json", {"summary": report.summary, "errors": report.errors, "warnings": report.warnings})
    if not report.ok and not args.allow_invalid:
        print(f"Validation failed with {len(report.errors)} errors. See {out_dir / 'validation_report.json'}", file=sys.stderr)
        return 2

    write_json(out_dir / "roadnet.json", roadnet)
    write_json(out_dir / "flow.json", flows)
    print(json.dumps(report.summary, ensure_ascii=False, indent=2))
    if report.warnings:
        print(f"Wrote outputs with {len(report.warnings)} warnings. Review validation_report.json.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
