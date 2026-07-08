from pathlib import Path
import os


BASE_DIR = Path(__file__).resolve().parents[1]
DATA_DIR = BASE_DIR / "data"
DEFAULT_SCENE_ID = "jinan_3x4"
DEFAULT_FRAME_STEP_SECONDS = 1.0
DEFAULT_VISIBLE_VEHICLE_LIMIT = 300
ENGINE_MODE = os.getenv("SIM_ENGINE_MODE", "mock").strip().lower()
SERVICE_VERSION = "0.2.0"
