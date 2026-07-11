from pathlib import Path
import os


BASE_DIR = Path(__file__).resolve().parents[1]
DATA_DIR = BASE_DIR / "data"
DEFAULT_SCENE_ID = "jinan_3x4"
DEFAULT_FRAME_STEP_SECONDS = 1.0
DEFAULT_VISIBLE_VEHICLE_LIMIT = int(os.getenv("SIM_VISIBLE_VEHICLE_LIMIT", "300"))
DEFAULT_REALTIME_TICK_SECONDS = float(os.getenv("SIM_REALTIME_TICK_SECONDS", "0.1"))
DEFAULT_MIN_REALTIME_TICK_SECONDS = float(os.getenv("SIM_MIN_REALTIME_TICK_SECONDS", "0.02"))
# 0 means "do not reject new sessions by count". Session lifecycle is protected
# by idle/abandoned/max-lifetime cleanup instead of returning 429 when old sessions leak.
MAX_ACTIVE_SESSIONS = max(0, int(os.getenv("SIM_MAX_ACTIVE_SESSIONS", "0")))
SESSION_IDLE_TTL_SECONDS = max(0.0, float(os.getenv("SIM_SESSION_IDLE_TTL_SECONDS", "900")))
SESSION_ABANDONED_TTL_SECONDS = max(0.0, float(os.getenv("SIM_SESSION_ABANDONED_TTL_SECONDS", "300")))
SESSION_MAX_LIFETIME_SECONDS = max(0.0, float(os.getenv("SIM_SESSION_MAX_LIFETIME_SECONDS", "3600")))
SESSION_CLEANUP_INTERVAL_SECONDS = max(1.0, float(os.getenv("SIM_SESSION_CLEANUP_INTERVAL_SECONDS", "60")))
SESSION_DRAIN_TIMEOUT_SECONDS = max(0.0, float(os.getenv("SIM_SESSION_DRAIN_TIMEOUT_SECONDS", "600")))
MAX_SPEED = float(os.getenv("SIM_MAX_SPEED", "10"))
MAX_REQUEST_BYTES = int(os.getenv("SIM_MAX_REQUEST_BYTES", str(1024 * 1024)))
API_TOKEN = os.getenv("CITYFLOW_API_TOKEN", "").strip()
ALLOWED_ORIGIN = os.getenv("SIM_ALLOWED_ORIGIN", "*").strip() or "*"
CITYFLOW_TOKEN_HEADER = "X-CityFlow-Token"
CITYFLOW_CLIENT_HEADER = "X-CityFlow-Client"
ENGINE_MODE = os.getenv("SIM_ENGINE_MODE", "mock").strip().lower()
AUTO_SIGNAL_CYCLE = os.getenv("SIM_AUTO_SIGNAL_CYCLE", "false").strip().lower() in {"1", "true", "yes", "on"}
SERVICE_VERSION = "0.2.0"
