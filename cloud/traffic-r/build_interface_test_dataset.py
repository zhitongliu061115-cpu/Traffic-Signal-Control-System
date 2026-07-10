import argparse
import copy
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Tuple


VALID_PHASE_CODES = ["ETWT", "NTST", "ELWL", "NLSL"]
LANE_ORDER = ["WT", "WL", "ST", "SL", "ET", "EL", "NT", "NL"]


def main() -> None:
    parser = argparse.ArgumentParser(description="Build Traffic-R interface test cases from backend audit logs.")
    parser.add_argument("--log", default="backend/logs/traffic-r-decisions.jsonl")
    parser.add_argument("--output", default="cloud/traffic-r/testdata/traffic_r_interface_cases.json")
    parser.add_argument("--roadnet", default="sim-python/data/jinan_3x4/roadnet_3_4.json")
    parser.add_argument("--max-real-cases", type=int, default=12)
    parser.add_argument("--include-derived", action=argparse.BooleanOptionalAction, default=True)
    args = parser.parse_args()

    log_path = Path(args.log)
    output_path = Path(args.output)
    roadnet_index = RoadnetLaneIndex(Path(args.roadnet))
    requests = list(load_predict_batch_requests(log_path))
    selected = select_diverse_requests(requests, args.max_real_cases)

    cases: List[Dict[str, Any]] = []
    for index, payload in enumerate(selected, start=1):
        payload = with_lane_level_observation(payload, roadnet_index, "estimated_from_road_level_log")
        metrics = payload.get("observation", {}).get("metrics", {}) or {}
        cases.append(
            {
                "caseId": case_id("real", index, payload),
                "source": "backend.predict-batch.request",
                "description": (
                    f"Real simulation sample from {payload.get('sceneId')} at "
                    f"simTime={payload.get('simTime')}, vehicles={metrics.get('vehicleCount')}, "
                    f"queue={metrics.get('queueCount')}"
                ),
                "expected": expected_contract(),
                "request": payload,
            }
        )

    if args.include_derived and selected:
        cases.extend(build_derived_cases(
            with_lane_level_observation(selected[-1], roadnet_index, "estimated_from_road_level_log"),
            roadnet_index,
            start_index=len(cases) + 1,
        ))

    dataset = {
        "metadata": {
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "sourceLog": str(log_path),
            "roadnet": str(args.roadnet),
            "availableRequestCount": len(requests),
            "realCaseCount": len(selected),
            "totalCaseCount": len(cases),
            "validPhaseCodes": VALID_PHASE_CODES,
            "notes": [
                "Cases are for Traffic-R interface validation, not for supervised-label accuracy.",
                "A passing response must contain parsedFromModel=true, non-empty rawOutput, and a phaseCode in validPhaseCodes.",
                "Each request contains observation.laneStates[intersectionId] in official WT/WL/ST/SL/ET/EL/NT/NL format.",
                "Lane states are estimated from road-level audit logs using roadnet incoming roadLinks because the old logs do not contain native CityFlow lane counts.",
                "Derived cases reuse real road and intersection ids but modify lane-level queue/cell counts to probe output sensitivity.",
            ],
        },
        "cases": cases,
    }

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(dataset, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {len(cases)} cases to {output_path}")


def load_predict_batch_requests(log_path: Path) -> Iterable[Dict[str, Any]]:
    if not log_path.exists():
        raise FileNotFoundError(f"audit log not found: {log_path}")

    seen = set()
    with log_path.open("r", encoding="utf-8") as file:
        for line in file:
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            if record.get("event") != "backend.predict-batch.request":
                continue
            payload = record.get("payload")
            if not isinstance(payload, dict):
                continue
            key = (
                payload.get("sceneId"),
                payload.get("simTime"),
                json.dumps(payload.get("observation", {}).get("metrics", {}), sort_keys=True),
            )
            if key in seen:
                continue
            seen.add(key)
            yield payload


def select_diverse_requests(requests: List[Dict[str, Any]], limit: int) -> List[Dict[str, Any]]:
    if limit <= 0 or len(requests) <= limit:
        return requests

    candidates: List[Tuple[str, Dict[str, Any]]] = []
    by_scene: Dict[str, List[Dict[str, Any]]] = {}
    for request in requests:
        by_scene.setdefault(str(request.get("sceneId")), []).append(request)

    for scene_requests in by_scene.values():
        candidates.extend(
            [
                ("first", scene_requests[0]),
                ("last", scene_requests[-1]),
                ("max_vehicle", max(scene_requests, key=lambda item: metric(item, "vehicleCount"))),
                ("max_queue", max(scene_requests, key=lambda item: metric(item, "queueCount"))),
                ("max_speed", max(scene_requests, key=lambda item: metric(item, "avgSpeed"))),
                ("min_speed", min(scene_requests, key=lambda item: metric(item, "avgSpeed"))),
            ]
        )

    selected: List[Dict[str, Any]] = []
    selected_keys = set()
    for _, request in candidates:
        key = unique_request_key(request)
        if key not in selected_keys:
            selected.append(request)
            selected_keys.add(key)
        if len(selected) >= limit:
            return selected

    stride = max(1, len(requests) // limit)
    for request in requests[::stride]:
        key = unique_request_key(request)
        if key not in selected_keys:
            selected.append(request)
            selected_keys.add(key)
        if len(selected) >= limit:
            break
    return selected


class RoadnetLaneIndex:
    def __init__(self, roadnet_path: Path) -> None:
        self.roadnet_path = roadnet_path
        self.incoming_movements_by_intersection = self._load(roadnet_path)

    def _load(self, roadnet_path: Path) -> Dict[str, Dict[str, Dict[str, Any]]]:
        roadnet = json.loads(roadnet_path.read_text(encoding="utf-8"))
        index: Dict[str, Dict[str, Dict[str, Any]]] = {}
        for intersection in roadnet.get("intersections", []):
            if intersection.get("virtual"):
                continue
            intersection_id = intersection.get("id")
            movements: Dict[str, Dict[str, Any]] = {}
            for road_link in intersection.get("roadLinks", []):
                link_type = road_link.get("type")
                if link_type not in {"go_straight", "turn_left"}:
                    continue
                start_road = road_link.get("startRoad")
                direction = incoming_direction_for_start_road(start_road)
                turn = "T" if link_type == "go_straight" else "L"
                lane_code = f"{direction}{turn}"
                start_lane_indexes = sorted({
                    int(lane_link.get("startLaneIndex", 0))
                    for lane_link in road_link.get("laneLinks", [])
                    if isinstance(lane_link, dict)
                })
                movements[lane_code] = {
                    "roadId": start_road,
                    "movement": lane_code,
                    "linkType": link_type,
                    "startLaneIndexes": start_lane_indexes,
                }
            index[intersection_id] = movements
        return index

    def lane_state_for_request(self, request: Dict[str, Any], source: str) -> Dict[str, Any]:
        road_state_by_id = {
            road.get("id"): road
            for road in request.get("observation", {}).get("roads", []) or []
            if isinstance(road, dict)
        }
        lane_states: Dict[str, Dict[str, Any]] = {}
        for intersection in request.get("intersections", []) or []:
            intersection_id = intersection.get("intersectionId")
            lane_states[intersection_id] = self._lane_state_for_intersection(
                intersection_id,
                road_state_by_id,
                source,
            )
        return lane_states

    def _lane_state_for_intersection(
            self,
            intersection_id: str,
            road_state_by_id: Dict[str, Dict[str, Any]],
            source: str,
    ) -> Dict[str, Any]:
        movements = self.incoming_movements_by_intersection.get(intersection_id, {})
        lanes = {lane: empty_lane_state() for lane in LANE_ORDER}
        details: Dict[str, Dict[str, Any]] = {}
        movements_by_road: Dict[str, List[str]] = {}
        for lane_code, meta in movements.items():
            movements_by_road.setdefault(meta["roadId"], []).append(lane_code)

        for road_id, lane_codes in movements_by_road.items():
            road_state = road_state_by_id.get(road_id, {})
            queue_count = safe_int(road_state.get("queueCount"))
            vehicle_count = safe_int(road_state.get("vehicleCount"))
            active_count = max(0, vehicle_count - queue_count)

            if not lane_codes:
                continue
            queue_shares = split_count(queue_count, len(lane_codes))
            active_shares = split_count(active_count, len(lane_codes))
            for lane_code, queue_share, active_share in zip(lane_codes, queue_shares, active_shares):
                lanes[lane_code]["queue_len"] += queue_share
                cells = distribute_to_three_segments(active_share)
                lanes[lane_code]["cells"] = [
                    lanes[lane_code]["cells"][index] + cells[index]
                    for index in range(3)
                ]
                details[lane_code] = {
                    "roadId": road_id,
                    "movement": lane_code,
                    "queueSource": queue_count,
                    "vehicleSource": vehicle_count,
                    "estimatedQueueShare": queue_share,
                    "estimatedActiveShare": active_share,
                    "roadnet": movements.get(lane_code),
                }

        return {
            "source": source,
            "intersectionId": intersection_id,
            "lanes": lanes,
            "details": details,
        }


def with_lane_level_observation(
        request: Dict[str, Any],
        roadnet_index: RoadnetLaneIndex,
        source: str,
) -> Dict[str, Any]:
    enriched = copy.deepcopy(request)
    observation = enriched.setdefault("observation", {})
    observation["laneStates"] = roadnet_index.lane_state_for_request(enriched, source)
    observation["laneLevelSource"] = source
    observation["laneLevelFormat"] = "official-commonsense WT/WL/ST/SL/ET/EL/NT/NL"
    return enriched


def build_derived_cases(
        base_request: Dict[str, Any],
        roadnet_index: RoadnetLaneIndex,
        start_index: int,
) -> List[Dict[str, Any]]:
    variants = [
        (
            "derived_ew_heavy",
            "Derived case with heavy east-west road pressure based on real road ids.",
            lambda road_id: road_id.endswith("_0") or road_id.endswith("_2"),
        ),
        (
            "derived_ns_heavy",
            "Derived case with heavy north-south road pressure based on real road ids.",
            lambda road_id: road_id.endswith("_1") or road_id.endswith("_3"),
        ),
        (
            "derived_balanced_gridlock",
            "Derived case with all observed roads congested to test strict output validity under dense state.",
            lambda road_id: True,
        ),
        (
            "derived_left_turn_heavy",
            "Derived lane-level case with heavy left-turn queues on WL/SL/EL/NL.",
            None,
        ),
    ]

    cases = []
    for offset, (name, description, heavy_selector) in enumerate(variants):
        request = copy.deepcopy(base_request)
        request["simTime"] = float(request.get("simTime", 0.0) or 0.0) + 1000 + offset
        request["sceneId"] = request.get("sceneId") or "jinan_3x4"
        if heavy_selector is None:
            total_vehicle, total_queue = rewrite_lane_states_for_left_turn_pressure(request)
        else:
            roads = request.get("observation", {}).get("roads", []) or []
            total_vehicle = 0
            total_queue = 0
            for road in roads:
                road_id = str(road.get("id", ""))
                if heavy_selector(road_id):
                    road["queueCount"] = 12
                    road["vehicleCount"] = 18
                else:
                    road["queueCount"] = 1
                    road["vehicleCount"] = 2
                total_vehicle += int(road["vehicleCount"])
                total_queue += int(road["queueCount"])
            request = with_lane_level_observation(request, roadnet_index, f"{name}_estimated_lane_level")
        request.setdefault("observation", {})["metrics"] = {
            "vehicleCount": total_vehicle,
            "queueCount": total_queue,
            "avgSpeed": 0.0 if total_queue > 0 else 10.0,
            "avgWait": total_queue * 2,
            "throughput": 0,
        }
        cases.append(
            {
                "caseId": f"{start_index + offset:02d}_{name}",
                "source": "derived-from-backend.predict-batch.request",
                "description": description,
                "expected": expected_contract(),
                "request": request,
            }
        )
    return cases


def rewrite_lane_states_for_left_turn_pressure(request: Dict[str, Any]) -> Tuple[int, int]:
    total_vehicle = 0
    total_queue = 0
    lane_states = request.setdefault("observation", {}).setdefault("laneStates", {})
    for lane_state in lane_states.values():
        lanes = lane_state.get("lanes", {})
        lane_state["source"] = "derived_left_turn_heavy_lane_level"
        for lane_code, state in lanes.items():
            if lane_code.endswith("L"):
                state["queue_len"] = 14
                state["cells"] = [6, 4, 2]
            else:
                state["queue_len"] = 1
                state["cells"] = [1, 1, 0]
            total_queue += int(state["queue_len"])
            total_vehicle += int(state["queue_len"]) + sum(int(value) for value in state["cells"])
    request["observation"]["laneLevelSource"] = "derived_left_turn_heavy_lane_level"
    return total_vehicle, total_queue


def empty_lane_state() -> Dict[str, Any]:
    return {
        "queue_len": 0,
        "avg_wait_time": 0.0,
        "cells": [0, 0, 0],
    }


def incoming_direction_for_start_road(road_id: str) -> str:
    road_id = str(road_id or "")
    if road_id.endswith("_0"):
        return "W"
    if road_id.endswith("_1"):
        return "S"
    if road_id.endswith("_2"):
        return "E"
    if road_id.endswith("_3"):
        return "N"
    return "W"


def split_count(total: int, parts: int) -> List[int]:
    if parts <= 0:
        return []
    base = total // parts
    remainder = total % parts
    return [base + (1 if index < remainder else 0) for index in range(parts)]


def distribute_to_three_segments(vehicle_count: int) -> List[int]:
    seg1 = vehicle_count // 2
    seg2 = (vehicle_count - seg1) // 2
    seg3 = vehicle_count - seg1 - seg2
    return [seg1, seg2, seg3]


def metric(request: Dict[str, Any], name: str) -> float:
    value = request.get("observation", {}).get("metrics", {}).get(name, 0)
    try:
        return float(value or 0)
    except (TypeError, ValueError):
        return 0.0


def safe_int(value: Any) -> int:
    try:
        return int(float(value or 0))
    except (TypeError, ValueError):
        return 0


def unique_request_key(request: Dict[str, Any]) -> Tuple[Any, Any, float, float, float]:
    return (
        request.get("sceneId"),
        request.get("simTime"),
        metric(request, "vehicleCount"),
        metric(request, "queueCount"),
        metric(request, "avgSpeed"),
    )


def case_id(prefix: str, index: int, payload: Dict[str, Any]) -> str:
    scene = str(payload.get("sceneId") or "scene").replace(" ", "_")
    sim_time = str(payload.get("simTime") or "0").replace(".", "_")
    queue = int(metric(payload, "queueCount"))
    vehicle = int(metric(payload, "vehicleCount"))
    return f"{index:02d}_{prefix}_{scene}_t{sim_time}_q{queue}_v{vehicle}"


def expected_contract() -> Dict[str, Any]:
    return {
        "validPhaseCodes": VALID_PHASE_CODES,
        "requireParsedFromModel": True,
        "requireRawOutput": True,
        "decisionCountMustMatchIntersectionCount": True,
    }


if __name__ == "__main__":
    main()
