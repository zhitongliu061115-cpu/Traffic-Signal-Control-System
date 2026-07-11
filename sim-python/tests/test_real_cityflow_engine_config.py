import json
import tempfile
import unittest
from pathlib import Path
from threading import RLock
from unittest.mock import Mock

from app.config import DATA_DIR
from app.engine import CityFlowEngineSession, RealCityFlowEngine
from app.scene_registry import SceneRegistry


class RealCityFlowEngineConfigTest(unittest.TestCase):
    def test_generated_config_uses_absolute_scene_directory(self):
        engine = RealCityFlowEngine.__new__(RealCityFlowEngine)
        engine.scene_registry = SceneRegistry(DATA_DIR)
        scene = engine.scene_registry.get("jinan_3x4")

        config_path = RealCityFlowEngine._write_cityflow_config(engine, scene)

        with config_path.open("r", encoding="utf-8") as file:
            payload = json.load(file)

        self.assertTrue(Path(payload["dir"]).is_absolute())
        self.assertTrue(payload["dir"].endswith("/"))
        self.assertEqual(scene.roadnet_file.name, payload["roadnetFile"])
        self.assertEqual(scene.flow_file.name, payload["flowFile"])

    def test_releasing_session_drops_engine_runtime_and_ev_state(self):
        engine = RealCityFlowEngine.__new__(RealCityFlowEngine)
        engine.sessions = {}
        engine.terminal_frames = {}
        engine.sessions_lock = RLock()
        engine.ev_service_lock = RLock()
        engine.ev_service = Mock()
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = Path(temp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            session = CityFlowEngineSession(
                sid="run_release",
                scene_id="jinan_3x4",
                speed=1.0,
                total_vehicle_count=1,
                flow_end_time=10.0,
                hard_end_time=20.0,
                engine=object(),
                config_path=config_path,
            )
            engine.sessions[session.sid] = session

            engine._release_session(session, terminal_frame=None, join_worker=True)

            self.assertNotIn(session.sid, engine.sessions)
            self.assertFalse(Path(temp_dir).exists())
            engine.ev_service.release_session.assert_called_once_with(session.sid)

    def test_cleanup_releases_expired_real_session(self):
        engine = RealCityFlowEngine.__new__(RealCityFlowEngine)
        engine.sessions = {}
        engine.terminal_frames = {}
        engine.sessions_lock = RLock()
        engine.ev_service_lock = RLock()
        engine.ev_service = Mock()
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = Path(temp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            session = CityFlowEngineSession(
                sid="run_expired",
                scene_id="jinan_3x4",
                speed=1.0,
                total_vehicle_count=1,
                flow_end_time=10.0,
                hard_end_time=20.0,
                engine=object(),
                config_path=config_path,
            )
            session.running = False
            session.created_at = 0.0
            session.last_access_at = 0.0
            engine.sessions[session.sid] = session

            released = engine.cleanup_expired_sessions()

            self.assertEqual(1, released)
            self.assertNotIn(session.sid, engine.sessions)
            self.assertFalse(Path(temp_dir).exists())
            engine.ev_service.release_session.assert_called_once_with(session.sid)

    def test_cleanup_releases_abandoned_running_real_session(self):
        engine = RealCityFlowEngine.__new__(RealCityFlowEngine)
        engine.sessions = {}
        engine.terminal_frames = {}
        engine.sessions_lock = RLock()
        engine.ev_service_lock = RLock()
        engine.ev_service = Mock()
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = Path(temp_dir) / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            session = CityFlowEngineSession(
                sid="run_abandoned",
                scene_id="jinan_3x4",
                speed=1.0,
                total_vehicle_count=1,
                flow_end_time=10.0,
                hard_end_time=20.0,
                engine=object(),
                config_path=config_path,
            )
            session.running = True
            session.last_access_at = 0.0
            engine.sessions[session.sid] = session

            released = engine.cleanup_expired_sessions()

            self.assertEqual(1, released)
            self.assertNotIn(session.sid, engine.sessions)
            self.assertFalse(Path(temp_dir).exists())
            engine.ev_service.release_session.assert_called_once_with(session.sid)

    def test_simulation_finishes_only_after_last_departure_and_empty_network(self):
        engine = RealCityFlowEngine.__new__(RealCityFlowEngine)
        session = Mock(flow_end_time=100.0, hard_end_time=200.0)

        self.assertFalse(engine._simulation_complete(session, 99.0, {"activeVehicleCount": 0}))
        self.assertFalse(engine._simulation_complete(session, 100.0, {"activeVehicleCount": 1}))
        self.assertTrue(engine._simulation_complete(session, 100.0, {"activeVehicleCount": 0}))
        self.assertTrue(engine._simulation_complete(session, 200.0, {"activeVehicleCount": 1}))


if __name__ == "__main__":
    unittest.main()
