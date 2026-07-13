import json
import sys
import types
import unittest
from pathlib import Path


class _FastAPIStub:
    def __init__(self, **kwargs):
        pass

    def get(self, *args, **kwargs):
        return lambda function: function

    def post(self, *args, **kwargs):
        return lambda function: function


class _HTTPExceptionStub(Exception):
    def __init__(self, status_code, detail):
        super().__init__(str(detail))
        self.status_code = status_code
        self.detail = detail


if "fastapi" not in sys.modules:
    sys.modules["fastapi"] = types.SimpleNamespace(FastAPI=_FastAPIStub, HTTPException=_HTTPExceptionStub)
if "uvicorn" not in sys.modules:
    sys.modules["uvicorn"] = types.SimpleNamespace(run=lambda *args, **kwargs: None)

import traffic_r_service as service


FIXTURE_PATH = (
    Path(__file__).resolve().parents[2]
    / "sim-python"
    / "tests"
    / "fixtures"
    / "cityflow-contract"
    / "traffic-r-golden.json"
)


class TrafficRPromptContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
        cls.request = service.BatchPredictRequest(**cls.fixture["request"])
        cls.intersection = cls.request.intersections[0]

    def test_state_table_matches_official_golden_fixture(self):
        actual = service.state_to_official_commonsense_table(self.request, self.intersection)
        self.assertEqual(self.fixture["expectedStateTable"], actual)

    def test_prompt_matches_official_finetuning_contract(self):
        messages = service.build_official_commonsense_messages(self.request, self.intersection)

        self.assertEqual(
            "You are an expert in traffic management. You can use your knowledge of traffic commonsense "
            "to solve this traffic signal control tasks.",
            messages[0]["content"],
        )
        user_prompt = messages[1]["content"]
        self.assertTrue(user_prompt.startswith("A traffic light regulates a four-section intersection"))
        self.assertIn(self.fixture["expectedStateTable"], user_prompt)
        self.assertNotIn("A crossroad connects two roads", user_prompt)
        self.assertNotIn("Allowed lanes:", user_prompt)
        self.assertNotIn("\nNote:\n", user_prompt)
        self.assertTrue(user_prompt.endswith("<signal>YOUR_CHOICE</signal>."))

    def test_open_model_generation_defaults_match_official_evaluation(self):
        self.assertEqual(1024, service.MAX_NEW_TOKENS)
        self.assertEqual(0.1, service.TEMPERATURE)
        self.assertEqual(50, service.TOP_K)
        self.assertEqual(1.0, service.TOP_P)
        self.assertTrue(service.DO_SAMPLE)


if __name__ == "__main__":
    unittest.main()
