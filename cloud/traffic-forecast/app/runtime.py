from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from .features import FeatureMetadata, HORIZONS, TARGETS, build_inference_features, feature_names


REQUEST_COLUMN_MAP = {
    "intersectionId": "intersection_id",
    "observedAt": "observed_at",
    "observationSource": "observation_source",
    "inflowVehiclesPerHour": "inflow_vehicles_per_hour",
    "queueLengthVehicles": "queue_length_vehicles",
    "averageWaitSeconds": "average_wait_seconds",
    "averageSpeedKmh": "average_speed_kmh",
    "saturationPercent": "saturation_percent",
    "phaseName": "phase_name",
    "controlStrategy": "control_strategy",
    "deviceStatus": "device_status",
}


def resolve_model_dir(artifact_root: Path, configured_path: str) -> Path:
    model_dir = Path(configured_path)
    if not model_dir.is_absolute():
        model_dir = artifact_root / model_dir
    return model_dir.resolve()


class ForecastRuntime:
    def __init__(self, artifact_root: Path):
        import lightgbm as lgb

        pointer_path = artifact_root / "current.json"
        if not pointer_path.exists():
            raise FileNotFoundError(f"model pointer does not exist: {pointer_path}")
        pointer = json.loads(pointer_path.read_text(encoding="utf-8"))
        self.artifact_dir = resolve_model_dir(artifact_root, pointer["modelDir"])
        self.manifest = json.loads((self.artifact_dir / "manifest.json").read_text(encoding="utf-8"))
        expected_feature_names = feature_names()
        if self.manifest.get("featureNames") != expected_feature_names:
            raise ValueError(
                "model feature schema is incompatible with the 14-day history runtime; retrain the model"
            )
        self.metadata = FeatureMetadata(
            feature_names=list(self.manifest["featureNames"]),
            category_maps={
                column: {str(key): int(value) for key, value in mapping.items()}
                for column, mapping in self.manifest["categoryMaps"].items()
            },
        )
        self.models = {
            f"{target}_h{horizon}": lgb.Booster(
                model_file=str(self.artifact_dir / f"{target}_h{horizon}.txt")
            )
            for target in TARGETS
            for horizon in HORIZONS
        }

    def health(self) -> dict[str, Any]:
        return {
            "status": "ok",
            "modelVersion": self.manifest["modelVersion"],
            "modelType": self.manifest["modelType"],
            "dataEndedAt": self.manifest["dataEndedAt"],
            "lookbackMinutes": self.manifest["lookbackMinutes"],
            "historyDays": self.manifest["historyDays"],
            "riskThresholds": self.manifest["riskThresholds"],
            "modelCount": len(self.models),
        }

    def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        observations = payload.get("observations")
        if not isinstance(observations, list) or not observations:
            raise ValueError("observations must be a non-empty array")
        frame = pd.DataFrame(observations).rename(columns=REQUEST_COLUMN_MAP)
        required = set(REQUEST_COLUMN_MAP.values()) - {"observation_source"}
        missing = sorted(required - set(frame.columns))
        if missing:
            raise ValueError(f"missing observation fields: {', '.join(missing)}")
        if "observation_source" not in frame:
            frame["observation_source"] = "UNKNOWN"
        features, intersection_ids, data_until = build_inference_features(frame, self.metadata)
        predictions: dict[str, np.ndarray] = {}
        for key, booster in self.models.items():
            predictions[key] = booster.predict(features)

        slow_threshold = float(self.manifest["riskThresholds"]["slow"])
        jammed_threshold = float(self.manifest["riskThresholds"]["jammed"])
        detailed: list[dict[str, Any]] = []
        intersections: list[dict[str, Any]] = []
        for index, intersection_id in enumerate(intersection_ids):
            points: list[dict[str, Any]] = []
            for horizon in HORIZONS:
                flow = max(0.0, float(predictions[f"flow_h{horizon}"][index]))
                queue = max(0.0, float(predictions[f"queue_h{horizon}"][index]))
                wait = max(0.0, float(predictions[f"wait_h{horizon}"][index]))
                risk, risk_level = _risk(queue, slow_threshold, jammed_threshold)
                points.append(
                    {
                        "horizonMinutes": horizon,
                        "flow": round(flow, 1),
                        "queue": round(queue, 2),
                        "wait": round(wait, 1),
                        "risk": risk,
                        "riskLevel": risk_level,
                    }
                )
            detailed.append({"intersectionId": intersection_id, "points": points})
            final_point = points[-1]
            intersections.append(
                {
                    "id": intersection_id,
                    "label": _intersection_label(intersection_id),
                    **{key: value for key, value in final_point.items() if key != "horizonMinutes"},
                }
            )

        timeline: list[dict[str, Any]] = []
        for point_index, horizon in enumerate(HORIZONS):
            points = [item["points"][point_index] for item in detailed]
            flow = float(np.mean([point["flow"] for point in points]))
            queue = float(np.mean([point["queue"] for point in points]))
            wait = float(np.mean([point["wait"] for point in points]))
            risk, risk_level = _risk(queue, slow_threshold, jammed_threshold)
            timeline.append(
                {
                    "horizonMinutes": horizon,
                    "minute": f"+{horizon}分钟",
                    "flow": round(flow, 1),
                    "queue": round(queue, 2),
                    "wait": round(wait, 1),
                    "risk": risk,
                    "riskLevel": risk_level,
                }
            )

        return {
            "available": True,
            "message": "ok",
            "modelVersion": self.manifest["modelVersion"],
            "modelType": self.manifest["modelType"],
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "dataUntil": data_until.isoformat(),
            "trainedSource": ", ".join(
                f"{source}:{count}" for source, count in self.manifest["sourceCounts"].items()
            ),
            "intersections": intersections,
            "timeline": timeline,
            "details": detailed,
        }


def _risk(queue: float, slow_threshold: float, jammed_threshold: float) -> tuple[str, str]:
    if queue >= jammed_threshold:
        return "拥堵", "jammed"
    if queue >= slow_threshold:
        return "缓行", "slow"
    return "畅通", "free"


def _intersection_label(intersection_id: str) -> str:
    suffix = intersection_id.removeprefix("intersection_").replace("_", "-")
    return f"路口 {suffix}"
