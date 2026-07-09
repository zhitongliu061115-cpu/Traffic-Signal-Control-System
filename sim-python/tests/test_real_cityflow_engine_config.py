import json
import unittest
from pathlib import Path

from app.config import DATA_DIR
from app.engine import RealCityFlowEngine
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


if __name__ == "__main__":
    unittest.main()
