from __future__ import annotations

import unittest

import numpy as np
import pandas as pd

from app.features import (
    HISTORY_DAYS,
    HISTORY_MINUTES,
    LOOKBACK_MINUTES,
    build_inference_features,
    build_training_matrices,
)


class ForecastFeatureTest(unittest.TestCase):
    def test_training_and_inference_share_the_same_feature_schema(self) -> None:
        frame = self._observations(HISTORY_MINUTES + 80)
        training = build_training_matrices(frame, stride_minutes=2)
        prediction_history = self._prediction_history(frame)

        features, intersection_ids, data_until = build_inference_features(
            prediction_history, training.metadata
        )

        self.assertEqual((70, 119), training.features.shape)
        self.assertEqual(15, len(training.targets))
        self.assertEqual((2, 119), features.shape)
        self.assertEqual(["intersection_1_1", "intersection_1_2"], intersection_ids)
        self.assertEqual("2026-01-15T01:19:00", data_until.isoformat())

    def test_missing_minute_prevents_an_invalid_inference_window(self) -> None:
        frame = self._observations(HISTORY_MINUTES + 80)
        training = build_training_matrices(frame, stride_minutes=2)
        prediction_history = self._prediction_history(frame)
        latest = prediction_history["observed_at"].max()
        prediction_history = prediction_history.drop(
            prediction_history[
                (prediction_history["intersection_id"] == "intersection_1_1")
                & (prediction_history["observed_at"] == latest - pd.Timedelta(minutes=10))
            ].index
        )

        _, intersection_ids, _ = build_inference_features(prediction_history, training.metadata)

        self.assertEqual(["intersection_1_2"], intersection_ids)

    def test_missing_daily_history_prevents_an_invalid_inference_window(self) -> None:
        frame = self._observations(HISTORY_MINUTES + 80)
        training = build_training_matrices(frame, stride_minutes=2)
        prediction_history = self._prediction_history(frame)
        latest = prediction_history["observed_at"].max()
        prediction_history = prediction_history.drop(
            prediction_history[
                (prediction_history["intersection_id"] == "intersection_1_1")
                & (prediction_history["observed_at"] == latest - pd.Timedelta(days=7))
            ].index
        )

        _, intersection_ids, _ = build_inference_features(prediction_history, training.metadata)

        self.assertEqual(["intersection_1_2"], intersection_ids)

    def test_training_and_inference_values_match_at_the_same_timestamp(self) -> None:
        frame = self._observations(HISTORY_MINUTES + 80)
        training = build_training_matrices(frame, stride_minutes=1)
        prediction_at = frame["observed_at"].max() - pd.Timedelta(minutes=10)
        prediction_frame = self._prediction_history(frame, prediction_at)

        inference, intersection_ids, _ = build_inference_features(
            prediction_frame, training.metadata
        )

        matching_rows = np.flatnonzero(
            training.timestamps == np.datetime64(prediction_at.to_datetime64())
        )
        self.assertEqual(["intersection_1_1", "intersection_1_2"], intersection_ids)
        self.assertEqual(2, len(matching_rows))
        np.testing.assert_allclose(
            inference, training.features[matching_rows], rtol=1e-6, atol=1e-6
        )

    def _prediction_history(
        self, frame: pd.DataFrame, latest: pd.Timestamp | None = None
    ) -> pd.DataFrame:
        latest = frame["observed_at"].max() if latest is None else latest
        timestamps = set(
            pd.date_range(
                latest - pd.Timedelta(minutes=LOOKBACK_MINUTES - 1),
                latest,
                freq="min",
            )
        )
        timestamps.update(latest - pd.Timedelta(days=day) for day in range(1, HISTORY_DAYS + 1))
        return frame[frame["observed_at"].isin(timestamps)].copy()

    def _observations(self, periods: int) -> pd.DataFrame:
        rows = []
        for intersection_id in ("intersection_1_1", "intersection_1_2"):
            for index, observed_at in enumerate(
                pd.date_range("2026-01-01", periods=periods, freq="min")
            ):
                rows.append(
                    {
                        "intersection_id": intersection_id,
                        "observed_at": observed_at,
                        "observation_source": "SYNTHETIC",
                        "inflow_vehicles_per_hour": 400 + index,
                        "queue_length_vehicles": 4 + index / 30,
                        "average_wait_seconds": 20 + index / 10,
                        "average_speed_kmh": 40 - index / 20,
                        "saturation_percent": 50 + index / 10,
                        "phase_name": "东西直行",
                        "control_strategy": "Traffic-R1",
                        "device_status": "normal",
                    }
                )
        return pd.DataFrame(rows)


if __name__ == "__main__":
    unittest.main()
