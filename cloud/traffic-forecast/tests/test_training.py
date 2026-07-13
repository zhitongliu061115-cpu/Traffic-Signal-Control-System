from __future__ import annotations

import unittest

import pandas as pd

from app.training import calculate_risk_thresholds


class ForecastTrainingTest(unittest.TestCase):
    def test_risk_thresholds_follow_training_queue_quantiles(self) -> None:
        frame = pd.DataFrame({"queue_length_vehicles": range(100)})

        thresholds = calculate_risk_thresholds(frame)

        self.assertEqual(84.15, thresholds["slow"])
        self.assertEqual(89.1, thresholds["jammed"])
        self.assertEqual("training_queue_quantiles", thresholds["method"])


if __name__ == "__main__":
    unittest.main()
