from abc import ABC, abstractmethod

from app.errors import ApiError
from app.models import JsonDict


class SimulationEngine(ABC):
    @abstractmethod
    def create_session(self, scene_id: str, speed: float) -> JsonDict:
        """Create an engine-owned simulation session."""

    @abstractmethod
    def next_frame(self, sid: str) -> JsonDict:
        """Advance one simulation step and return a frame."""


class RealCityFlowEngine(SimulationEngine):
    """Reserved integration point for the local CityFlow Engine.

    The HTTP contract is stable now; this class should be filled after we know
    the exact CityFlow package version, config file layout and local sample code.
    """

    def create_session(self, scene_id: str, speed: float) -> JsonDict:
        raise ApiError(
            status=501,
            code="CITYFLOW_ENGINE_NOT_IMPLEMENTED",
            message="real CityFlow engine adapter is not implemented yet",
            retryable=False,
        )

    def next_frame(self, sid: str) -> JsonDict:
        raise ApiError(
            status=501,
            code="CITYFLOW_ENGINE_NOT_IMPLEMENTED",
            message="real CityFlow engine adapter is not implemented yet",
            retryable=False,
        )
