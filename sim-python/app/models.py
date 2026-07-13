from __future__ import annotations

from dataclasses import dataclass, field
from threading import Lock
import time
from typing import Any


@dataclass
class SimulationSession:
    sid: str
    scene_id: str
    speed: float = 1.0
    sim_time: float = 0.0
    seq: int = 0
    engine_mode: str = "mock"
    running: bool = False
    stopped: bool = False
    created_at: float = field(default_factory=time.time)
    last_access_at: float = field(default_factory=time.time)
    latest_frame: dict[str, Any] | None = None
    active_vehicle_ids: set[str] = field(default_factory=set)
    current_phases: dict[str, int] = field(default_factory=dict)
    phase_started_at: dict[str, float] = field(default_factory=dict)
    phase_duration_sec: dict[str, float] = field(default_factory=dict)
    mock_vehicle_delays: dict[str, float] = field(default_factory=dict)
    lock: Lock = field(default_factory=Lock, repr=False)


JsonDict = dict[str, Any]
