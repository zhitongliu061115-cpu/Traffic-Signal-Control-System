from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import pandas as pd

from app.runtime import resolve_model_dir
from app.training import calculate_risk_thresholds, write_model_pointer


class ForecastTrainingTest(unittest.TestCase):
    def test_risk_thresholds_follow_training_queue_quantiles(self) -> None:
        frame = pd.DataFrame({"queue_length_vehicles": range(100)})

        thresholds = calculate_risk_thresholds(frame)

        self.assertEqual(84.15, thresholds["slow"])
        self.assertEqual(89.1, thresholds["jammed"])
        self.assertEqual("training_queue_quantiles", thresholds["method"])

    def test_model_pointer_is_portable_across_checkout_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_dir:
            artifact_root = Path(temporary_dir) / "models"
            artifact_dir = artifact_root / "lgbm-test"
            artifact_dir.mkdir(parents=True)

            write_model_pointer(artifact_root, artifact_dir)

            pointer = json.loads((artifact_root / "current.json").read_text(encoding="utf-8"))
            self.assertEqual("lgbm-test", pointer["modelDir"])
            self.assertEqual(artifact_dir.resolve(), resolve_model_dir(artifact_root, pointer["modelDir"]))

    def test_model_pointer_keeps_absolute_path_compatibility(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_dir:
            artifact_root = Path(temporary_dir) / "models"
            artifact_dir = Path(temporary_dir) / "legacy-model"

            self.assertEqual(
                artifact_dir.resolve(),
                resolve_model_dir(artifact_root, str(artifact_dir)),
            )


if __name__ == "__main__":
    unittest.main()
