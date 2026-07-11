import json
from dataclasses import dataclass
from pathlib import Path

from app.errors import ApiError
from app.models import JsonDict


@dataclass(frozen=True)
class SceneDefinition:
    scene_id: str
    name: str
    roadnet_file: Path
    flow_file: Path
    total_vehicle_count: int = 0
    flow_end_time: float = 0.0


class SceneRegistry:
    def __init__(self, data_dir: Path):
        self.data_dir = data_dir
        self._scenes = self._load_scenes()

    def get(self, scene_id: str) -> SceneDefinition:
        if scene_id not in self._scenes:
            raise ApiError(
                status=404,
                code="SCENE_NOT_FOUND",
                message=f"scene not found: {scene_id}",
                retryable=False,
            )
        return self._scenes[scene_id]

    def list_scene_ids(self) -> list[str]:
        return sorted(self._scenes.keys())

    def _load_scenes(self) -> dict[str, SceneDefinition]:
        config_path = self.data_dir / "scenes.json"
        if not config_path.exists():
            raise ApiError(
                status=500,
                code="SCENE_CONFIG_MISSING",
                message=f"scene config not found: {config_path}",
                retryable=False,
            )

        with config_path.open("r", encoding="utf-8") as file:
            payload: JsonDict = json.load(file)

        scenes: dict[str, SceneDefinition] = {}
        for item in payload.get("scenes", []):
            scene_id = str(item.get("sceneId", "")).strip()
            if not scene_id:
                raise ApiError(
                    status=500,
                    code="SCENE_CONFIG_INVALID",
                    message="sceneId is required in scenes.json",
                    retryable=False,
                )

            scene_dir = str(item.get("sceneDir", scene_id)).strip() or scene_id
            scene_root = self.data_dir / scene_dir
            roadnet_file = scene_root / str(item.get("roadnetFile", ""))
            flow_file = scene_root / str(item.get("flowFile", ""))
            if not roadnet_file.exists():
                raise ApiError(
                    status=500,
                    code="ROADNET_FILE_MISSING",
                    message=f"roadnet file not found for scene {scene_id}: {roadnet_file}",
                    retryable=False,
                )
            if not flow_file.exists():
                raise ApiError(
                    status=500,
                    code="FLOW_FILE_MISSING",
                    message=f"flow file not found for scene {scene_id}: {flow_file}",
                    retryable=False,
                )

            total_vehicle_count, flow_end_time = self._flow_summary(flow_file)
            scenes[scene_id] = SceneDefinition(
                scene_id=scene_id,
                name=str(item.get("name", scene_id)),
                roadnet_file=roadnet_file,
                flow_file=flow_file,
                total_vehicle_count=total_vehicle_count,
                flow_end_time=flow_end_time,
            )

        if not scenes:
            raise ApiError(
                status=500,
                code="SCENE_CONFIG_INVALID",
                message="at least one scene is required in scenes.json",
                retryable=False,
            )
        return scenes

    def _flow_summary(self, flow_file: Path) -> tuple[int, float]:
        with flow_file.open("r", encoding="utf-8") as file:
            flows = json.load(file)
        count = 0
        flow_end_time = 0.0
        iterable_flows = flows if isinstance(flows, list) else []
        for flow in iterable_flows:
            try:
                start_time = float(flow.get("startTime", 0.0))
                end_time = float(flow.get("endTime", start_time))
                interval = float(flow.get("interval", 1.0))
            except (TypeError, ValueError):
                count += 1
                continue
            flow_end_time = max(flow_end_time, start_time, end_time)
            if interval <= 0 or end_time <= start_time:
                count += 1
            else:
                count += int((end_time - start_time) / interval) + 1
        return count, flow_end_time
