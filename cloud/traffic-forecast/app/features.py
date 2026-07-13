from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

import numpy as np
import pandas as pd


LOOKBACK_MINUTES = 30
HISTORY_DAYS = 14
MINUTES_PER_DAY = 24 * 60
HISTORY_MINUTES = HISTORY_DAYS * MINUTES_PER_DAY
DAILY_HISTORY_LAGS = tuple(range(1, HISTORY_DAYS + 1))
HORIZONS = (2, 4, 6, 8, 10)
LAGS = (0, 1, 2, 3, 5, 10, 15, 20, 29)
ROLLING_WINDOWS = (3, 5, 10, 15, 30)
METRICS = (
    "inflow_vehicles_per_hour",
    "queue_length_vehicles",
    "average_wait_seconds",
    "average_speed_kmh",
    "saturation_percent",
)
TARGETS = {
    "flow": "inflow_vehicles_per_hour",
    "queue": "queue_length_vehicles",
    "wait": "average_wait_seconds",
}
ROLLING_METRICS = tuple(TARGETS.values())
CATEGORY_COLUMNS = ("intersection_id", "phase_name", "control_strategy", "device_status")


@dataclass(frozen=True)
class FeatureMetadata:
    feature_names: list[str]
    category_maps: dict[str, dict[str, int]]


@dataclass(frozen=True)
class TrainingMatrices:
    features: np.ndarray
    targets: dict[str, np.ndarray]
    baselines: dict[str, np.ndarray]
    timestamps: np.ndarray
    metadata: FeatureMetadata


def feature_names() -> list[str]:
    names: list[str] = []
    for metric in METRICS:
        names.extend(f"{metric}_lag_{lag}" for lag in LAGS)
    for metric in ROLLING_METRICS:
        for window in ROLLING_WINDOWS:
            names.extend((f"{metric}_mean_{window}", f"{metric}_std_{window}"))
    names.extend(
        (
            "minute_sin",
            "minute_cos",
            "weekday_sin",
            "weekday_cos",
            "is_weekend",
            "intersection_code",
            "phase_code",
            "strategy_code",
            "device_status_code",
        )
    )
    for metric in METRICS:
        names.extend(
            (
                f"{metric}_same_time_day_7",
                f"{metric}_same_time_day_14",
                f"{metric}_recent_week_mean",
                f"{metric}_recent_week_std",
                f"{metric}_previous_week_mean",
                f"{metric}_previous_week_std",
                f"{metric}_week_over_week_delta",
            )
        )
    return names


def build_category_maps(frame: pd.DataFrame) -> dict[str, dict[str, int]]:
    return {
        column: {
            value: index
            for index, value in enumerate(sorted(str(item) for item in frame[column].dropna().unique()))
        }
        for column in CATEGORY_COLUMNS
    }


def normalize_observations(frame: pd.DataFrame) -> pd.DataFrame:
    normalized = frame.copy()
    normalized["observed_at"] = pd.to_datetime(normalized["observed_at"], errors="coerce")
    for metric in METRICS:
        normalized[metric] = pd.to_numeric(normalized[metric], errors="coerce")
    for column in CATEGORY_COLUMNS:
        normalized[column] = normalized[column].astype("string")
    return normalized.sort_values(["intersection_id", "observed_at"]).reset_index(drop=True)


def contiguous_segments(
    frame: pd.DataFrame,
    minimum_minutes: int = HISTORY_MINUTES + max(HORIZONS) + 1,
) -> Iterable[pd.DataFrame]:
    for _, intersection_frame in frame.groupby("intersection_id", sort=True):
        ordered = intersection_frame.sort_values("observed_at").reset_index(drop=True)
        gaps = ordered["observed_at"].diff().ne(pd.Timedelta(minutes=1))
        segment_ids = gaps.cumsum()
        for _, segment in ordered.groupby(segment_ids, sort=True):
            if len(segment) >= minimum_minutes:
                yield segment.reset_index(drop=True)


def _encoded(series: pd.Series, mapping: dict[str, int]) -> pd.Series:
    return series.astype("string").map(mapping).fillna(-1).astype(float)


def _recent_feature_matrix(
    segment: pd.DataFrame,
    metadata: FeatureMetadata,
    indices: np.ndarray,
) -> np.ndarray:
    columns: list[np.ndarray] = []
    for metric in METRICS:
        values = segment[metric].to_numpy(dtype=np.float32)
        for lag in LAGS:
            columns.append(values[indices - lag])
    for metric in ROLLING_METRICS:
        for window in ROLLING_WINDOWS:
            rolling = segment[metric].rolling(window=window, min_periods=window)
            columns.append(rolling.mean().to_numpy(dtype=np.float32)[indices])
            columns.append(rolling.std(ddof=0).to_numpy(dtype=np.float32)[indices])

    minute_of_day = segment["observed_at"].dt.hour * 60 + segment["observed_at"].dt.minute
    weekday = segment["observed_at"].dt.dayofweek
    minute_values = minute_of_day.to_numpy(dtype=np.float32)[indices]
    weekday_values = weekday.to_numpy(dtype=np.float32)[indices]
    columns.extend(
        (
            np.sin(2 * np.pi * minute_values / 1440.0),
            np.cos(2 * np.pi * minute_values / 1440.0),
            np.sin(2 * np.pi * weekday_values / 7.0),
            np.cos(2 * np.pi * weekday_values / 7.0),
            (weekday_values >= 5).astype(np.float32),
            _encoded(segment["intersection_id"], metadata.category_maps["intersection_id"])
            .to_numpy(dtype=np.float32)[indices],
            _encoded(segment["phase_name"], metadata.category_maps["phase_name"])
            .to_numpy(dtype=np.float32)[indices],
            _encoded(segment["control_strategy"], metadata.category_maps["control_strategy"])
            .to_numpy(dtype=np.float32)[indices],
            _encoded(segment["device_status"], metadata.category_maps["device_status"])
            .to_numpy(dtype=np.float32)[indices],
        )
    )
    return np.column_stack(columns).astype(np.float32, copy=False)


def _historical_feature_matrix(segment: pd.DataFrame, indices: np.ndarray) -> np.ndarray:
    columns: list[np.ndarray] = []
    for metric in METRICS:
        values = segment[metric].to_numpy(dtype=np.float32)
        daily_values = np.column_stack(
            [values[indices - day * MINUTES_PER_DAY] for day in DAILY_HISTORY_LAGS]
        )
        recent_week = daily_values[:, :7]
        previous_week = daily_values[:, 7:]
        recent_mean = np.mean(recent_week, axis=1)
        previous_mean = np.mean(previous_week, axis=1)
        columns.extend(
            (
                daily_values[:, 6],
                daily_values[:, 13],
                recent_mean,
                np.std(recent_week, axis=1),
                previous_mean,
                np.std(previous_week, axis=1),
                recent_mean - previous_mean,
            )
        )
    return np.column_stack(columns).astype(np.float32, copy=False)


def _feature_matrix(
    segment: pd.DataFrame,
    metadata: FeatureMetadata,
    indices: np.ndarray,
) -> np.ndarray:
    matrix = np.column_stack(
        (
            _recent_feature_matrix(segment, metadata, indices),
            _historical_feature_matrix(segment, indices),
        )
    ).astype(np.float32, copy=False)
    if matrix.shape[1] != len(metadata.feature_names):
        raise ValueError("generated feature matrix does not match the model feature schema")
    return matrix


def build_training_matrices(frame: pd.DataFrame, stride_minutes: int = 2) -> TrainingMatrices:
    if stride_minutes < 1:
        raise ValueError("stride_minutes must be positive")
    normalized = normalize_observations(frame)
    metadata = FeatureMetadata(feature_names(), build_category_maps(normalized))
    feature_parts: list[np.ndarray] = []
    timestamp_parts: list[np.ndarray] = []
    target_parts = {f"{target}_h{horizon}": [] for target in TARGETS for horizon in HORIZONS}
    baseline_parts = {f"{target}_h{horizon}": [] for target in TARGETS for horizon in HORIZONS}

    for segment in contiguous_segments(normalized):
        candidate_indices = np.arange(
            HISTORY_MINUTES,
            len(segment) - max(HORIZONS),
            stride_minutes,
            dtype=int,
        )
        candidate = _feature_matrix(segment, metadata, candidate_indices)
        valid = np.isfinite(candidate).all(axis=1)
        selected_indices = candidate_indices[valid]
        if selected_indices.size == 0:
            continue
        feature_parts.append(candidate[valid])
        timestamp_parts.append(segment.iloc[selected_indices]["observed_at"].to_numpy())
        for target, column in TARGETS.items():
            ewma = segment[column].ewm(span=5, adjust=False).mean().to_numpy(dtype=np.float32)
            values = segment[column].to_numpy(dtype=np.float32)
            for horizon in HORIZONS:
                key = f"{target}_h{horizon}"
                target_parts[key].append(values[selected_indices + horizon])
                baseline_parts[key].append(ewma[selected_indices])

    if not feature_parts:
        raise ValueError(
            f"no contiguous observation windows with {HISTORY_DAYS} days of history"
        )
    return TrainingMatrices(
        features=np.concatenate(feature_parts),
        targets={key: np.concatenate(parts) for key, parts in target_parts.items()},
        baselines={key: np.concatenate(parts) for key, parts in baseline_parts.items()},
        timestamps=np.concatenate(timestamp_parts),
        metadata=metadata,
    )


def build_inference_features(
    frame: pd.DataFrame, metadata: FeatureMetadata
) -> tuple[np.ndarray, list[str], pd.Timestamp]:
    normalized = normalize_observations(frame)
    rows: list[np.ndarray] = []
    intersection_ids: list[str] = []
    data_until: pd.Timestamp | None = None
    for intersection_id, intersection_frame in normalized.groupby("intersection_id", sort=True):
        intersection_frame = intersection_frame.drop_duplicates("observed_at", keep="last")
        latest_timestamp = intersection_frame["observed_at"].max()
        if pd.isna(latest_timestamp):
            continue
        indexed = intersection_frame.set_index("observed_at")
        recent_timestamps = pd.date_range(
            latest_timestamp - pd.Timedelta(minutes=LOOKBACK_MINUTES - 1),
            latest_timestamp,
            freq="min",
        )
        historical_timestamps = [
            latest_timestamp - pd.Timedelta(days=day) for day in DAILY_HISTORY_LAGS
        ]
        recent = indexed.reindex(recent_timestamps).rename_axis("observed_at").reset_index()
        historical = indexed.reindex(historical_timestamps)
        recent_features = _recent_feature_matrix(
            recent,
            metadata,
            np.asarray([LOOKBACK_MINUTES - 1], dtype=int),
        )[0]
        historical_columns: list[float] = []
        for metric in METRICS:
            daily_values = historical[metric].to_numpy(dtype=np.float32)
            recent_week = daily_values[:7]
            previous_week = daily_values[7:]
            recent_mean = float(np.mean(recent_week))
            previous_mean = float(np.mean(previous_week))
            historical_columns.extend(
                (
                    float(daily_values[6]),
                    float(daily_values[13]),
                    recent_mean,
                    float(np.std(recent_week)),
                    previous_mean,
                    float(np.std(previous_week)),
                    recent_mean - previous_mean,
                )
            )
        feature_row = np.concatenate(
            (recent_features, np.asarray(historical_columns, dtype=np.float32))
        )
        if feature_row.shape[0] != len(metadata.feature_names):
            raise ValueError("generated feature matrix does not match the model feature schema")
        if not np.isfinite(feature_row).all():
            continue
        rows.append(feature_row)
        intersection_ids.append(str(intersection_id))
        data_until = (
            latest_timestamp if data_until is None else max(data_until, latest_timestamp)
        )
    if not rows or data_until is None:
        raise ValueError(
            f"prediction requires {LOOKBACK_MINUTES} recent contiguous minutes and "
            f"same-time observations from each of the previous {HISTORY_DAYS} days"
        )
    return np.stack(rows), intersection_ids, data_until
