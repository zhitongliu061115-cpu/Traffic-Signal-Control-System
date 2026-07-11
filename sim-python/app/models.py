from __future__ import annotations

from dataclasses import dataclass, field
from threading import Lock
from typing import Any


@dataclass
class SimulationSession:
    sid: str
    scene_id: str
    speed: float = 1.0
    sim_time: float = 0.0
    seq: int = 0
    engine_mode: str = "mock"
    owner_id: str = "default"
    running: bool = False
    stopped: bool = False
    latest_frame: dict[str, Any] | None = None
    active_vehicle_ids: set[str] = field(default_factory=set)
    lock: Lock = field(default_factory=Lock, repr=False)


JsonDict = dict[str, Any]
