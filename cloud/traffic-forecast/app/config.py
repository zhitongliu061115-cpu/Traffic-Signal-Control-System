from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


PROJECT_DIR = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class DatabaseConfig:
    host: str
    port: int
    database: str
    username: str
    password: str

    @classmethod
    def from_env(cls) -> "DatabaseConfig":
        return cls(
            host=os.getenv("FORECAST_DB_HOST", "127.0.0.1"),
            port=int(os.getenv("FORECAST_DB_PORT", "5432")),
            database=os.getenv("FORECAST_DB_NAME", "traffic_signal"),
            username=os.getenv("FORECAST_DB_USERNAME", "traffic_user"),
            password=os.getenv("FORECAST_DB_PASSWORD", ""),
        )

    def connect_kwargs(self) -> dict[str, object]:
        return {
            "host": self.host,
            "port": self.port,
            "dbname": self.database,
            "user": self.username,
            "password": self.password,
            "connect_timeout": 10,
        }


def model_root() -> Path:
    return Path(os.getenv("FORECAST_MODEL_ROOT", str(PROJECT_DIR / "models"))).resolve()


def server_host() -> str:
    return os.getenv("FORECAST_HOST", "127.0.0.1")


def server_port() -> int:
    return int(os.getenv("FORECAST_PORT", "17008"))
