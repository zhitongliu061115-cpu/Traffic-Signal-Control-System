from __future__ import annotations

import json
from datetime import timedelta
from pathlib import Path
from typing import Any

import pandas as pd

from .config import DatabaseConfig


OBSERVATION_COLUMNS = [
    "intersection_id",
    "observed_at",
    "observation_source",
    "inflow_vehicles_per_hour",
    "queue_length_vehicles",
    "average_wait_seconds",
    "average_speed_kmh",
    "saturation_percent",
    "phase_name",
    "control_strategy",
    "device_status",
]


class ForecastRepository:
    def __init__(self, config: DatabaseConfig):
        self.config = config

    def _connect(self):
        import psycopg

        return psycopg.connect(**self.config.connect_kwargs())

    def profile(self) -> list[dict[str, Any]]:
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                """
                select observation_source,
                       count(*) as row_count,
                       count(distinct intersection_id) as intersection_count,
                       min(observed_at) as started_at,
                       max(observed_at) as ended_at
                from traffic_forecast_observation
                where quality_status = 'VALID'
                group by observation_source
                order by observation_source
                """
            )
            return [
                {
                    "source": row[0],
                    "rowCount": row[1],
                    "intersectionCount": row[2],
                    "startedAt": row[3].isoformat(),
                    "endedAt": row[4].isoformat(),
                }
                for row in cursor.fetchall()
            ]

    def fetch_observations(self, include_synthetic: bool, history_days: int | None = None) -> pd.DataFrame:
        source_filter = "" if include_synthetic else "and observation_source = 'REAL'"
        parameters: tuple[Any, ...] = ()
        history_filter = ""
        if history_days is not None:
            if history_days < 7:
                raise ValueError("history_days must be at least 7")
            with self._connect() as connection, connection.cursor() as cursor:
                cursor.execute(
                    "select max(observed_at) from traffic_forecast_observation where quality_status = 'VALID'"
                )
                latest = cursor.fetchone()[0]
            if latest is None:
                return pd.DataFrame(columns=OBSERVATION_COLUMNS)
            history_filter = "and observed_at >= %s"
            parameters = (latest - timedelta(days=history_days),)
        query = f"""
            select {", ".join(OBSERVATION_COLUMNS)}
            from traffic_forecast_observation
            where quality_status = 'VALID'
            {source_filter}
            {history_filter}
            order by intersection_id, observed_at
        """
        chunks: list[pd.DataFrame] = []
        fetched = 0
        with self._connect() as connection, connection.cursor(name="forecast_training_rows") as cursor:
            cursor.execute(query, parameters)
            while True:
                rows = cursor.fetchmany(25_000)
                if not rows:
                    break
                chunks.append(pd.DataFrame(rows, columns=OBSERVATION_COLUMNS))
                fetched += len(rows)
                if fetched % 250_000 == 0:
                    print(f"loaded {fetched:,} forecast observations", flush=True)
        if not chunks:
            return pd.DataFrame(columns=OBSERVATION_COLUMNS)
        frame = pd.concat(chunks, ignore_index=True)
        source_priority = frame["observation_source"].map(
            {"REAL": 0, "IMPORTED": 1, "SYNTHETIC": 2}
        ).fillna(3)
        frame = frame.assign(_source_priority=source_priority).sort_values(
            ["intersection_id", "observed_at", "_source_priority"]
        )
        return (
            frame.drop_duplicates(["intersection_id", "observed_at"], keep="first")
            .drop(columns="_source_priority")
            .sort_values(["intersection_id", "observed_at"])
            .reset_index(drop=True)
        )

    def register_model(self, manifest: dict[str, Any], artifact_dir: Path) -> None:
        source_summary = json.dumps(manifest["sourceCounts"], ensure_ascii=True, sort_keys=True)
        metrics_json = json.dumps(manifest["metrics"], ensure_ascii=True, sort_keys=True)
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute("update traffic_forecast_model_registry set active = false where active = true")
            cursor.execute(
                """
                insert into traffic_forecast_model_registry (
                    model_version, trained_at, data_started_at, data_ended_at,
                    training_row_count, source_summary, metrics_json, artifact_uri, active
                ) values (%s, %s, %s, %s, %s, %s, %s, %s, true)
                on conflict (model_version) do update set
                    trained_at = excluded.trained_at,
                    data_started_at = excluded.data_started_at,
                    data_ended_at = excluded.data_ended_at,
                    training_row_count = excluded.training_row_count,
                    source_summary = excluded.source_summary,
                    metrics_json = excluded.metrics_json,
                    artifact_uri = excluded.artifact_uri,
                    active = true
                """,
                (
                    manifest["modelVersion"],
                    manifest["trainedAt"],
                    manifest["dataStartedAt"],
                    manifest["dataEndedAt"],
                    manifest["observationCount"],
                    source_summary,
                    metrics_json,
                    str(artifact_dir),
                ),
            )
            connection.commit()
