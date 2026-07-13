from __future__ import annotations

from collections import defaultdict
from pathlib import Path
from typing import Any

from app.models import JsonDict
from app.roadnet_parser import PHASE_CODES
from app.sumo_geo import parse_utm_projection, utm_to_wgs84, wgs84_to_gcj02


PHASE_MOVEMENTS = {
    "ETWT": {"ET", "WT"},
    "NTST": {"NT", "ST"},
    "ELWL": {"EL", "WL"},
    "NLSL": {"NL", "SL"},
}


class SumoRoadnetParser:
    def __init__(self, scene_id: str, net_file: Path, sumolib_module: Any):
        self.scene_id = scene_id
        self.net_file = net_file
        self.sumolib = sumolib_module
        self.net = sumolib_module.net.readNet(str(net_file), withPrograms=True)
        self._offset = self.net.getLocationOffset()
        projection = self.net._location.get("projParameter", "")
        self._zone, self._northern = parse_utm_projection(projection)
        self._response: JsonDict | None = None
        self._lane_movements: dict[str, dict[str, tuple[str, ...]]] | None = None
        self._link_movements: dict[str, dict[int, str]] | None = None

    def to_response(self) -> JsonDict:
        if self._response is None:
            self._response = self._build_response()
        return self._response

    def ev_priority_roadnet(self) -> JsonDict:
        """Expose the SUMO network in the CityFlow shape used by EVPriorityService."""
        response = self.to_response()
        road_links_by_intersection: dict[str, list[JsonDict]] = defaultdict(list)
        for road_link in response["roadLinks"]:
            road_links_by_intersection[road_link["intersectionId"]].append(road_link)
        phases_by_intersection: dict[str, list[JsonDict]] = defaultdict(list)
        for phase in response["phases"]:
            phases_by_intersection[phase["intersectionId"]].append(phase)

        intersections = []
        for intersection in response["intersections"]:
            intersection_id = intersection["id"]
            links = sorted(road_links_by_intersection.get(intersection_id, []), key=lambda item: item["index"])
            phases = sorted(phases_by_intersection.get(intersection_id, []), key=lambda item: item["phaseIndex"])
            intersections.append({
                "id": intersection_id,
                "point": {"x": intersection["x"], "y": intersection["y"]},
                "virtual": intersection["virtual"],
                "roadLinks": [
                    {
                        "startRoad": link["fromRoadId"],
                        "endRoad": link["toRoadId"],
                        "type": link["type"],
                    }
                    for link in links
                ],
                "trafficLight": {
                    "lightphases": [
                        {
                            "availableRoadLinks": (
                                [] if phase["phaseCode"] is None else list(phase["roadLinkIndexes"])
                            )
                        }
                        for phase in phases
                    ]
                },
            })
        return {
            "intersections": intersections,
            "roads": [
                {
                    "id": road["id"],
                    "startIntersection": road["from"],
                    "endIntersection": road["to"],
                    "points": list(road["points"]),
                    "length": float(self.net.getEdge(road["id"]).getLength()),
                }
                for road in response["roads"]
            ],
        }

    def traffic_light_ids(self) -> list[str]:
        return sorted(tls.getID() for tls in self.net.getTrafficLights())

    def lane_movement_map(self) -> dict[str, dict[str, tuple[str, ...]]]:
        self._ensure_movement_maps()
        return self._lane_movements or {}

    def business_signal_state(self, intersection_id: str, phase_code: str) -> str:
        self._ensure_movement_maps()
        movements = (self._link_movements or {}).get(intersection_id)
        if movements is None:
            raise KeyError(intersection_id)
        target = PHASE_MOVEMENTS[phase_code]
        size = max(movements, default=-1) + 1
        state = ["r"] * size
        for index, movement in movements.items():
            if movement.endswith("R"):
                state[index] = "g"
            elif movement in target:
                state[index] = "G"
        return "".join(state)

    def phase_for_state(self, intersection_id: str, state: str) -> tuple[int, str | None]:
        best_code = None
        best_score = -1
        for phase_index, phase_code in PHASE_CODES.items():
            desired = self.business_signal_state(intersection_id, phase_code)
            score = sum(
                1
                for actual, expected in zip(state, desired)
                if actual.lower() == "g" and expected.lower() == "g"
            )
            if score > best_score:
                best_score = score
                best_code = phase_code
        if best_code is None:
            return 1, None
        return next(index for index, code in PHASE_CODES.items() if code == best_code), best_code

    def incoming_edges(self, intersection_id: str) -> list[Any]:
        node = self.net.getNode(intersection_id)
        return [edge for edge in node.getIncoming() if not edge.getFunction()]

    def to_geo(self, x: float, y: float) -> tuple[float, float]:
        easting = float(x) - float(self._offset[0])
        northing = float(y) - float(self._offset[1])
        longitude, latitude = utm_to_wgs84(easting, northing, self._zone, self._northern)
        return wgs84_to_gcj02(longitude, latitude)

    def _build_response(self) -> JsonDict:
        self._ensure_movement_maps()
        tls_ids = set(self.traffic_light_ids())
        intersections = []
        for node in self.net.getNodes():
            if node.getID().startswith(":"):
                continue
            x, y = node.getCoord()
            longitude, latitude = self.to_geo(x, y)
            intersections.append({
                "id": node.getID(),
                "x": float(x),
                "y": float(y),
                "lng": round(longitude, 8),
                "lat": round(latitude, 8),
                "virtual": node.getID() not in tls_ids,
            })
        roads = []
        for edge in self.net.getEdges():
            if edge.getFunction():
                continue
            points = []
            for point in edge.getShape():
                longitude, latitude = self.to_geo(point[0], point[1])
                points.append({
                    "x": float(point[0]),
                    "y": float(point[1]),
                    "lng": round(longitude, 8),
                    "lat": round(latitude, 8),
                })
            roads.append({
                "id": edge.getID(),
                "from": edge.getFromNode().getID(),
                "to": edge.getToNode().getID(),
                "points": points,
                "laneCount": len(edge.getLanes()),
            })

        road_links = []
        phases = []
        for tls in sorted(self.net.getTrafficLights(), key=lambda value: value.getID()):
            intersection_id = tls.getID()
            grouped: dict[tuple[str, str, str], list[tuple[int, int]]] = defaultdict(list)
            movement_by_group: dict[tuple[str, str, str], str] = {}
            for from_lane, to_lane, _ in tls.getConnections():
                connection = self._find_connection(from_lane, to_lane)
                if connection is None:
                    continue
                direction = connection.getDirection() or "s"
                key = (from_lane.getEdge().getID(), to_lane.getEdge().getID(), direction)
                grouped[key].append((from_lane.getIndex(), to_lane.getIndex()))
                movement_by_group[key] = self._movement_code(from_lane.getEdge(), tls.getID(), direction)

            indexes_by_movement: dict[str, list[int]] = defaultdict(list)
            right_indexes: list[int] = []
            for index, key in enumerate(sorted(grouped)):
                from_id, to_id, direction = key
                road_links.append({
                    "intersectionId": intersection_id,
                    "index": index,
                    "fromRoadId": from_id,
                    "toRoadId": to_id,
                    "type": self._road_link_type(direction),
                })
                movement = movement_by_group[key]
                indexes_by_movement[movement].append(index)
                if movement.endswith("R"):
                    right_indexes.append(index)

            phases.append({
                "intersectionId": intersection_id,
                "phaseIndex": 1,
                "phaseCode": None,
                "roadLinkIndexes": sorted(right_indexes),
            })
            for phase_index, phase_code in PHASE_CODES.items():
                available = list(right_indexes)
                for movement in PHASE_MOVEMENTS[phase_code]:
                    available.extend(indexes_by_movement.get(movement, []))
                phases.append({
                    "intersectionId": intersection_id,
                    "phaseIndex": phase_index,
                    "phaseCode": phase_code,
                    "roadLinkIndexes": sorted(set(available)),
                })

        return {
            "sceneId": self.scene_id,
            "intersections": intersections,
            "roads": roads,
            "roadLinks": road_links,
            "phases": phases,
        }

    def _ensure_movement_maps(self) -> None:
        if self._lane_movements is not None:
            return
        lane_movements: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
        link_movements: dict[str, dict[int, str]] = defaultdict(dict)
        for tls in self.net.getTrafficLights():
            intersection_id = tls.getID()
            for from_lane, to_lane, link_index in tls.getConnections():
                connection = self._find_connection(from_lane, to_lane)
                if connection is None:
                    continue
                movement = self._movement_code(
                    from_lane.getEdge(),
                    intersection_id,
                    connection.getDirection() or "s",
                )
                lane_movements[intersection_id][from_lane.getID()].add(movement)
                link_movements[intersection_id][int(link_index)] = movement
        self._lane_movements = {
            intersection_id: {
                lane_id: tuple(sorted(movements))
                for lane_id, movements in lanes.items()
            }
            for intersection_id, lanes in lane_movements.items()
        }
        self._link_movements = {key: dict(value) for key, value in link_movements.items()}

    def _find_connection(self, from_lane: Any, to_lane: Any) -> Any | None:
        for connection in from_lane.getOutgoing():
            if connection.getToLane().getID() == to_lane.getID():
                return connection
        return None

    def _movement_code(self, incoming_edge: Any, intersection_id: str, direction: str) -> str:
        approach = self._approach_code(incoming_edge, intersection_id)
        suffix = {"l": "L", "r": "R", "s": "T"}.get(direction, "T")
        return f"{approach}{suffix}"

    def _approach_code(self, incoming_edge: Any, intersection_id: str) -> str:
        junction_x, junction_y = self.net.getNode(intersection_id).getCoord()
        shape = incoming_edge.getShape()
        outside_x, outside_y = shape[-2] if len(shape) > 1 else incoming_edge.getFromNode().getCoord()
        dx = outside_x - junction_x
        dy = outside_y - junction_y
        if abs(dx) >= abs(dy):
            return "W" if dx < 0 else "E"
        return "S" if dy < 0 else "N"

    def _road_link_type(self, direction: str) -> str:
        return {
            "s": "go_straight",
            "l": "turn_left",
            "r": "turn_right",
            "t": "turn_around",
        }.get(direction, "unknown")
