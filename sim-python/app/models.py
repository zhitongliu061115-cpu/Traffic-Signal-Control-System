from dataclasses import dataclass, field
from typing import Any


@dataclass
class SimulationSession:
    sid: str
    scene_id: str
    speed: float = 1.0
    sim_time: float = 0.0
    seq: int = 0
    active_vehicle_ids: set[str] = field(default_factory=set)


JsonDict = dict[str, Any]
