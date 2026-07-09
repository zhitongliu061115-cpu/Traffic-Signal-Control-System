from __future__ import annotations

import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.cityflow_adapter import CityFlowAdapter
from app.config import DATA_DIR, DEFAULT_SCENE_ID
from app.errors import ApiError, error_response


class CityFlowRequestHandler(BaseHTTPRequestHandler):
    adapter: CityFlowAdapter

    def do_GET(self) -> None:
        path_parts = self._path_parts()
        try:
            if self._matches(path_parts, ["health"]):
                self._send_json(200, self.adapter.health())
                return
            if self._matches(path_parts, ["cityflow", "scenes", None, "roadnet"]):
                scene_id = path_parts[2]
                self._send_json(200, self.adapter.get_roadnet(scene_id))
                return
            if self._matches(path_parts, ["cityflow", "simulations", None, "frame"]):
                sid = path_parts[2]
                self._send_json(200, self.adapter.next_frame(sid))
                return
            self._send_error(ApiError(404, "NOT_FOUND", "endpoint not found", False))
        except ApiError as ex:
            self._send_error(ex)
        except json.JSONDecodeError:
            self._send_error(ApiError(400, "INVALID_JSON", "request body must be valid JSON", False))
        except Exception as ex:
            self._send_error(ApiError(500, "INTERNAL_ERROR", str(ex), True))

    def do_POST(self) -> None:
        path_parts = self._path_parts()
        try:
            if self._matches(path_parts, ["cityflow", "simulations"]):
                body = self._read_json_body()
                scene_id = body.get("sceneId", DEFAULT_SCENE_ID)
                speed = body.get("speed", 1.0)
                self._send_json(200, self.adapter.create_simulation(scene_id, speed))
                return
            if self._matches(path_parts, ["cityflow", "simulations", None, "dispatch"]):
                sid = path_parts[2]
                body = self._read_json_body()
                self._send_json(200, self.adapter.dispatch(sid, body))
                return

            if self._matches(path_parts, ["cityflow", "simulations", None, "actions"]):
                sid = path_parts[2]
                body = self._read_json_body()
                self._send_json(200, self.adapter.apply_control_actions(sid, body))
                return
            self._send_error(ApiError(404, "NOT_FOUND", "endpoint not found", False))
        except ApiError as ex:
            self._send_error(ex)
        except json.JSONDecodeError:
            self._send_error(ApiError(400, "INVALID_JSON", "request body must be valid JSON", False))
        except Exception as ex:
            self._send_error(ApiError(500, "INTERNAL_ERROR", str(ex), True))

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self._send_common_headers(0)
        self.end_headers()

    def log_message(self, format: str, *args) -> None:
        print("%s - %s" % (self.address_string(), format % args))

    def _path_parts(self) -> list[str]:
        return [part for part in urlparse(self.path).path.split("/") if part]

    def _matches(self, actual: list[str], expected: list[str | None]) -> bool:
        if len(actual) != len(expected):
            return False
        return all(expected_part is None or actual_part == expected_part for actual_part, expected_part in zip(actual, expected))

    def _read_json_body(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        if length == 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8")
        return json.loads(raw) if raw else {}

    def _send_json(self, status: int, payload: dict) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self._send_common_headers(len(data))
        self.end_headers()
        self.wfile.write(data)

    def _send_error(self, error: ApiError) -> None:
        self._send_json(error.status, error_response(error))

    def _send_common_headers(self, content_length: int) -> None:
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(content_length))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")


def run(host: str, port: int) -> None:
    CityFlowRequestHandler.adapter = CityFlowAdapter(DATA_DIR)
    server = ThreadingHTTPServer((host, port), CityFlowRequestHandler)
    print(f"Python CityFlow service listening on http://{host}:{port}")
    server.serve_forever()


def main() -> None:
    parser = argparse.ArgumentParser(description="Python CityFlow HTTP service")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9000)
    args = parser.parse_args()
    run(args.host, args.port)


if __name__ == "__main__":
    main()
