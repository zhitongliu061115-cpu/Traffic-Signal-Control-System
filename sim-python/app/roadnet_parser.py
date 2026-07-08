from __future__ import annotations

import json
from pathlib import Path

from app.models import JsonDict


PHASE_CODES = {
    1: "ETWT",
    2: "NTST",
    3: "ELWL",
    4: "NLSL",
}


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
