import json
import unittest

from app.errors import ApiError, error_response
from app.server import CityFlowRequestHandler


class ServerContractTest(unittest.TestCase):
    def test_error_response_shape(self):
        payload = error_response(ApiError(404, "SESSION_NOT_FOUND", "missing", False))

        self.assertEqual(False, payload["success"])
        self.assertEqual("SESSION_NOT_FOUND", payload["code"])
        self.assertEqual("missing", payload["message"])
        self.assertEqual(False, payload["retryable"])

    def test_route_matching_accepts_placeholders(self):
        handler = CityFlowRequestHandler

        self.assertTrue(handler._matches(None, ["cityflow", "simulations", "sid", "frame"], ["cityflow", "simulations", None, "frame"]))
        self.assertFalse(handler._matches(None, ["cityflow", "simulations"], ["cityflow", "simulations", None, "frame"]))

    def test_error_payload_is_json_serializable(self):
        payload = error_response(ApiError(400, "INVALID_REQUEST", "speed must be greater than 0", False))
        encoded = json.dumps(payload, ensure_ascii=False)

        self.assertIn("INVALID_REQUEST", encoded)


if __name__ == "__main__":
    unittest.main()
