from __future__ import annotations

import json
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

from app.errors import ApiError


@dataclass(frozen=True)
class SumoSceneDefinition:
    scene_id: str
    name: str
    config_file: Path
    net_file: Path
    route_files: tuple[Path, ...]
    traffic_r_intersections: frozenset[str]
    max_pressure_intersections: frozenset[str]
    total_vehicle_count: int
    flow_end_time: float

    def strategy_for(self, intersection_id: str) -> str:
        if intersection_id in self.traffic_r_intersections:
            return "traffic-r"
        if intersection_id in self.max_pressure_intersections:
            return "max-pressure"
        return "unmanaged"


class SumoSceneRegistry:
    def __init__(self, data_dir: Path):
        self.data_dir = data_dir
        self._scenes = self._load_scenes()

    def get(self, scene_id: str) -> SumoSceneDefinition:
        scene = self._scenes.get(scene_id)
        if scene is None:
            raise ApiError(404, "SCENE_NOT_FOUND", f"SUMO scene not found: {scene_id}", False)
        return scene

    def list_scene_ids(self) -> list[str]:
        return sorted(self._scenes)

    def _load_scenes(self) -> dict[str, SumoSceneDefinition]:
        config_path = self.data_dir / "sumo_scenes.json"
        if not config_path.exists():
            raise ApiError(500, "SCENE_CONFIG_MISSING", f"SUMO scene config not found: {config_path}", False)
        with config_path.open("r", encoding="utf-8") as file:
            payload = json.load(file)

        scenes: dict[str, SumoSceneDefinition] = {}
        for item in payload.get("scenes", []):
            scene_id = str(item.get("sceneId", "")).strip()
            if not scene_id:
                raise ApiError(500, "SCENE_CONFIG_INVALID", "SUMO sceneId is required", False)
            root = self.data_dir / str(item.get("sceneDir", scene_id))
            config_file = root / str(item.get("configFile", ""))
            net_file = root / str(item.get("netFile", ""))
            route_files = tuple(root / str(value) for value in item.get("routeFiles", []))
            required = (config_file, net_file, *route_files)
            missing = [str(path) for path in required if not path.exists()]
            if missing:
                raise ApiError(500, "SUMO_SCENE_FILE_MISSING", "; ".join(missing), False)

            tls_ids = self._traffic_light_ids(net_file)
            traffic_r = frozenset(str(value) for value in item.get("trafficRIntersections", []))
            unknown = traffic_r - tls_ids
            if unknown:
                raise ApiError(
                    500,
                    "SUMO_STRATEGY_MAP_INVALID",
                    f"Traffic-R intersections are absent from {scene_id}: {sorted(unknown)}",
                    False,
                )
            total, end_time = self._route_summary(route_files)
            scenes[scene_id] = SumoSceneDefinition(
                scene_id=scene_id,
                name=str(item.get("name", scene_id)),
                config_file=config_file,
                net_file=net_file,
                route_files=route_files,
                traffic_r_intersections=traffic_r,
                max_pressure_intersections=frozenset(tls_ids - traffic_r),
                total_vehicle_count=total,
                flow_end_time=end_time,
            )
        if not scenes:
            raise ApiError(500, "SCENE_CONFIG_INVALID", "at least one SUMO scene is required", False)
        return scenes

    def _traffic_light_ids(self, net_file: Path) -> set[str]:
        import gzip

        opener = gzip.open if net_file.suffix == ".gz" else open
        ids: set[str] = set()
        with opener(net_file, "rt", encoding="utf-8") as file:
            for _, element in ET.iterparse(file, events=("end",)):
                if element.tag == "junction" and element.get("type", "").startswith("traffic_light"):
                    ids.add(element.get("id", ""))
                element.clear()
        return ids

    def _route_summary(self, route_files: tuple[Path, ...]) -> tuple[int, float]:
        total = 0
        end_time = 0.0
        for route_file in route_files:
            for _, element in ET.iterparse(route_file, events=("end",)):
                if element.tag in {"trip", "vehicle"}:
                    total += 1
                    try:
                        end_time = max(end_time, float(element.get("depart", "0")))
                    except ValueError:
                        pass
                element.clear()
        return total, end_time
