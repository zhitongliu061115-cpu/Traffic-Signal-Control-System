from __future__ import annotations

import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np

from .features import (
    HISTORY_DAYS,
    HORIZONS,
    LOOKBACK_MINUTES,
    TARGETS,
    TrainingMatrices,
    build_training_matrices,
)
from .repository import ForecastRepository


def _mae(actual: np.ndarray, predicted: np.ndarray) -> float:
    return float(np.mean(np.abs(actual - predicted)))


def _rmse(actual: np.ndarray, predicted: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(actual - predicted))))


def _time_masks(timestamps: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    epoch_ns = timestamps.astype("datetime64[ns]").astype(np.int64)
    train_boundary = int(np.quantile(epoch_ns, 0.70))
    validation_boundary = int(np.quantile(epoch_ns, 0.85))
    purge_ns = max(HORIZONS) * 60 * 1_000_000_000
    train = epoch_ns <= train_boundary - purge_ns
    validation = (epoch_ns >= train_boundary) & (epoch_ns <= validation_boundary - purge_ns)
    test = epoch_ns >= validation_boundary
    if min(train.sum(), validation.sum(), test.sum()) == 0:
        raise ValueError("time split produced an empty train, validation, or test partition")
    return train, validation, test


def _source_counts(frame) -> dict[str, int]:
    counts = frame["observation_source"].value_counts()
    return {str(source): int(count) for source, count in counts.items()}


def calculate_risk_thresholds(frame) -> dict[str, Any]:
    queue = np.asarray(frame["queue_length_vehicles"], dtype=np.float64)
    queue = queue[np.isfinite(queue)]
    if queue.size == 0:
        raise ValueError("queue history is required to calibrate risk thresholds")
    return {
        "slow": round(float(np.quantile(queue, 0.85)), 6),
        "jammed": round(float(np.quantile(queue, 0.90)), 6),
        "method": "training_queue_quantiles",
        "slowQuantile": 0.85,
        "jammedQuantile": 0.90,
    }


def train_models(
    repository: ForecastRepository,
    artifact_root: Path,
    include_synthetic: bool = True,
    history_days: int | None = None,
    stride_minutes: int = 2,
    max_rounds: int = 220,
) -> tuple[dict[str, Any], Path]:
    import lightgbm as lgb

    if history_days is not None and history_days <= HISTORY_DAYS:
        raise ValueError(
            f"history_days must exceed the {HISTORY_DAYS}-day feature history; "
            f"use at least {HISTORY_DAYS + 1}"
        )
    observations = repository.fetch_observations(
        include_synthetic=include_synthetic,
        history_days=history_days,
    )
    if observations.empty:
        raise ValueError("traffic_forecast_observation contains no valid training rows")
    print(f"building features from {len(observations):,} observations", flush=True)
    matrices: TrainingMatrices = build_training_matrices(observations, stride_minutes=stride_minutes)
    print(f"built {len(matrices.features):,} training windows", flush=True)
    train_mask, validation_mask, test_mask = _time_masks(matrices.timestamps)
    now = datetime.now(timezone.utc)
    version = f"lgbm-{now.strftime('%Y%m%dT%H%M%SZ')}"
    artifact_dir = artifact_root / version
    artifact_dir.mkdir(parents=True, exist_ok=False)
    metrics: dict[str, Any] = {}

    parameters = {
        "objective": "regression_l1",
        "metric": "l1",
        "learning_rate": 0.05,
        "num_leaves": 31,
        "min_data_in_leaf": 80,
        "feature_fraction": 0.9,
        "bagging_fraction": 0.9,
        "bagging_freq": 1,
        "verbosity": -1,
        "seed": 20260713,
        "num_threads": 0,
    }
    train_set = lgb.Dataset(
        matrices.features[train_mask],
        feature_name=matrices.metadata.feature_names,
        free_raw_data=False,
    )
    validation_features = matrices.features[validation_mask]
    test_features = matrices.features[test_mask]
    for target in TARGETS:
        for horizon in HORIZONS:
            key = f"{target}_h{horizon}"
            train_set.set_label(matrices.targets[key][train_mask])
            validation_set = lgb.Dataset(
                validation_features,
                label=matrices.targets[key][validation_mask],
                reference=train_set,
                feature_name=matrices.metadata.feature_names,
            )
            booster = lgb.train(
                parameters,
                train_set,
                num_boost_round=max_rounds,
                valid_sets=[validation_set],
                callbacks=[lgb.early_stopping(25, verbose=False)],
            )
            prediction = booster.predict(test_features, num_iteration=booster.best_iteration)
            actual = matrices.targets[key][test_mask]
            baseline = matrices.baselines[key][test_mask]
            model_mae = _mae(actual, prediction)
            baseline_mae = _mae(actual, baseline)
            metrics[key] = {
                "mae": round(model_mae, 6),
                "rmse": round(_rmse(actual, prediction), 6),
                "ewmaMae": round(baseline_mae, 6),
                "improvementPct": round((baseline_mae - model_mae) / max(baseline_mae, 1e-9) * 100, 3),
                "bestIteration": booster.best_iteration,
            }
            booster.save_model(str(artifact_dir / f"{key}.txt"))
            print(
                f"trained {key}: mae={model_mae:.4f}, ewma_mae={baseline_mae:.4f}, "
                f"improvement={metrics[key]['improvementPct']:.2f}%",
                flush=True,
            )

    category_maps = matrices.metadata.category_maps
    manifest = {
        "modelVersion": version,
        "modelType": "LightGBM direct multi-horizon regression",
        "trainedAt": now.isoformat(),
        "dataStartedAt": observations["observed_at"].min().isoformat(),
        "dataEndedAt": observations["observed_at"].max().isoformat(),
        "observationCount": int(len(observations)),
        "sampleCount": int(len(matrices.features)),
        "partitionCounts": {
            "train": int(train_mask.sum()),
            "validation": int(validation_mask.sum()),
            "test": int(test_mask.sum()),
        },
        "sourceCounts": _source_counts(observations),
        "lookbackMinutes": LOOKBACK_MINUTES,
        "historyDays": HISTORY_DAYS,
        "horizons": list(HORIZONS),
        "targets": list(TARGETS),
        "featureNames": matrices.metadata.feature_names,
        "categoryMaps": category_maps,
        "riskThresholds": calculate_risk_thresholds(observations),
        "metrics": metrics,
    }
    (artifact_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    artifact_root.mkdir(parents=True, exist_ok=True)
    pointer_tmp = artifact_root / "current.json.tmp"
    pointer_tmp.write_text(
        json.dumps({"modelDir": str(artifact_dir)}, ensure_ascii=True), encoding="utf-8"
    )
    shutil.move(str(pointer_tmp), str(artifact_root / "current.json"))
    repository.register_model(manifest, artifact_dir)
    return manifest, artifact_dir
