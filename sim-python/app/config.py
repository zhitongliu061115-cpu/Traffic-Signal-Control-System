from pathlib import Path
import os


BASE_DIR = Path(__file__).resolve().parents[1]
DATA_DIR = BASE_DIR / "data"
DEFAULT_SCENE_ID = "jinan_3x4"
DEFAULT_FRAME_STEP_SECONDS = 1.0
DEFAULT_VISIBLE_VEHICLE_LIMIT = int(os.getenv("SIM_VISIBLE_VEHICLE_LIMIT", "300"))
DEFAULT_REALTIME_TICK_SECONDS = float(os.getenv("SIM_REALTIME_TICK_SECONDS", "0.1"))
DEFAULT_MIN_REALTIME_TICK_SECONDS = float(os.getenv("SIM_MIN_REALTIME_TICK_SECONDS", "0.02"))
MAX_ACTIVE_SESSIONS = int(os.getenv("SIM_MAX_ACTIVE_SESSIONS", "4"))
MAX_SPEED = float(os.getenv("SIM_MAX_SPEED", "10"))
MAX_REQUEST_BYTES = int(os.getenv("SIM_MAX_REQUEST_BYTES", str(1024 * 1024)))
API_TOKEN = os.getenv("CITYFLOW_API_TOKEN", "").strip()
ALLOWED_ORIGIN = os.getenv("SIM_ALLOWED_ORIGIN", "*").strip() or "*"
CITYFLOW_TOKEN_HEADER = "X-CityFlow-Token"
CITYFLOW_CLIENT_HEADER = "X-CityFlow-Client"
ENGINE_MODE = os.getenv("SIM_ENGINE_MODE", "mock").strip().lower()
AUTO_SIGNAL_CYCLE = os.getenv("SIM_AUTO_SIGNAL_CYCLE", "false").strip().lower() in {"1", "true", "yes", "on"}
SERVICE_VERSION = "0.2.0"
