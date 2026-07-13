from __future__ import annotations

import json
from pathlib import Path

from app.models import JsonDict


PHASE_CODES = {
    2: "ETWT",
    3: "NTST",
    4: "ELWL",
    5: "NLSL",
}

BUSINESS_PHASE_CODE_TO_INDEX = {phase_code: phase_index for phase_index, phase_code in PHASE_CODES.items()}
BUSINESS_PHASE_INDEXES = set(PHASE_CODES.keys())
FIRST_BUSINESS_PHASE_INDEX = 2


class RoadnetParser:
    def __init__(self, roadnet_path: Path):
        self.roadnet_path = roadnet_path
        self._raw: JsonDict | None = None
        self._roadnet_response: JsonDict | None = None

    @property
    def raw(self) -> JsonDict:
        if self._raw is None:
            with self.roadnet_path.open("r", encoding="utf-8") as file:
                self._raw = json.load(file)
        return self._raw

    def to_response(self, scene_id: str) -> JsonDict:
        if self._roadnet_response is None:
            self._roadnet_response = self._build_response(scene_id)
        return self._roadnet_response

    def _build_response(self, scene_id: str) -> JsonDict:
        intersections = []
        roads = []
        road_links = []
        phases = []

        for item in self.raw.get("intersections", []):
            point = item.get("point", {})
            intersections.append({
                "id": item["id"],
                "x": float(point.get("x", 0.0)),
                "y": float(point.get("y", 0.0)),
                "virtual": bool(item.get("virtual", False)),
            })

            for index, road_link in enumerate(item.get("roadLinks", [])):
                road_links.append({
                    "intersectionId": item["id"],
                    "index": index,
                    "fromRoadId": road_link.get("startRoad"),
                    "toRoadId": road_link.get("endRoad"),
                    "type": road_link.get("type", "unknown"),
                })

            traffic_light = item.get("trafficLight", {})
            for phase_index, phase in enumerate(traffic_light.get("lightphases", []), start=1):
                phases.append({
                    "intersectionId": item["id"],
                    "phaseIndex": phase_index,
                    "phaseCode": PHASE_CODES.get(phase_index),
                    "roadLinkIndexes": phase.get("availableRoadLinks", []),
                })

        for item in self.raw.get("roads", []):
            roads.append({
                "id": item["id"],
                "from": item["startIntersection"],
                "to": item["endIntersection"],
                "points": [
                    {"x": float(point.get("x", 0.0)), "y": float(point.get("y", 0.0))}
                    for point in item.get("points", [])
                ],
                "laneCount": len(item.get("lanes", [])),
            })

        return {
            "sceneId": scene_id,
            "intersections": intersections,
            "roads": roads,
            "roadLinks": road_links,
            "phases": phases,
        }

    def road_by_id(self) -> dict[str, JsonDict]:
        return {road["id"]: road for road in self.raw.get("roads", [])}

    def real_intersection_ids(self) -> list[str]:
        return [
            item["id"]
            for item in self.raw.get("intersections", [])
            if not item.get("virtual", False)
        ]

    def lane_movement_map(self) -> dict[str, dict[str, dict[int, str]]]:
        mapping: dict[str, dict[str, dict[int, str]]] = {}
        roads_by_id = self.road_by_id()
        for intersection in self.raw.get("intersections", []):
            if intersection.get("virtual", False):
                continue
            intersection_id = intersection["id"]
            point = intersection.get("point", {})
            movement_by_road = mapping.setdefault(intersection_id, {})
            for road_link in intersection.get("roadLinks", []):
                movement_type = road_link.get("type")
                if movement_type not in {"go_straight", "turn_left"}:
                    continue
                start_road_id = road_link.get("startRoad")
                if not start_road_id:
                    continue
                approach = self._approach_code(roads_by_id.get(start_road_id), point)
                turn_code = "T" if movement_type == "go_straight" else "L"
                lane_code = f"{approach}{turn_code}"
                lane_by_index = movement_by_road.setdefault(start_road_id, {})
                for lane_link in road_link.get("laneLinks", []):
                    try:
                        lane_index = int(lane_link.get("startLaneIndex"))
                    except (TypeError, ValueError):
                        continue
                    lane_by_index[lane_index] = lane_code
        return mapping

    def _approach_code(self, road: JsonDict | None, intersection_point: JsonDict) -> str:
        if not road:
            return "W"
        points = road.get("points", [])
        if not points:
            return "W"
        start = points[0]
        ix = float(intersection_point.get("x", 0.0))
        iy = float(intersection_point.get("y", 0.0))
        dx = float(start.get("x", 0.0)) - ix
        dy = float(start.get("y", 0.0)) - iy
        if abs(dx) >= abs(dy):
            return "W" if dx < 0 else "E"
        # Jinan roadnet uses screen-style coordinates: negative y is the south
        # approach and positive y is the north approach.
        return "S" if dy < 0 else "N"
