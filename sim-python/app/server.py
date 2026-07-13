from __future__ import annotations

import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.cityflow_adapter import CityFlowAdapter
from app.config import (
    ALLOWED_ORIGIN,
    API_TOKEN,
    CITYFLOW_CLIENT_HEADER,
    CITYFLOW_TOKEN_HEADER,
    DATA_DIR,
    DEFAULT_SCENE_ID,
    ENGINE_MODE,
    MAX_REQUEST_BYTES,
)
from app.errors import ApiError, error_response


class CityFlowRequestHandler(BaseHTTPRequestHandler):
    adapter: Any

    def do_GET(self) -> None:
        path_parts = self._path_parts()
        try:
            if self._matches(path_parts, ["health"]):
                self._send_json(200, self.adapter.health())
                return
            self._require_cityflow_auth(path_parts)
            if self._matches(path_parts, ["cityflow", "scenes", None, "roadnet"]):
                scene_id = path_parts[2]
                self._send_json(200, self.adapter.get_roadnet(scene_id))
                return
            if self._matches(path_parts, ["cityflow", "simulations", None, "frame"]):
                sid = path_parts[2]
                self._send_json(200, self.adapter.next_frame(sid, self._client_id()))
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
            self._require_cityflow_auth(path_parts)
            if self._matches(path_parts, ["cityflow", "simulations"]):
                body = self._read_json_body()
                scene_id = body.get("sceneId", getattr(self.adapter, "default_scene_id", DEFAULT_SCENE_ID))
                speed = body.get("speed", 1.0)
                warmup_seconds = body.get("warmupSeconds", 0.0)
                self._send_json(200, self.adapter.create_simulation(scene_id, speed, warmup_seconds, self._client_id()))
                return
            if self._matches(path_parts, ["cityflow", "simulations", None, "dispatch"]):
                sid = path_parts[2]
                body = self._read_json_body()
                self._send_json(200, self.adapter.dispatch(sid, body, self._client_id()))
                return

            if self._matches(path_parts, ["cityflow", "simulations", None, "actions"]):
                sid = path_parts[2]
                body = self._read_json_body()
                self._send_json(200, self.adapter.apply_control_actions(sid, body, self._client_id()))
                return
            if self._matches(path_parts, ["cityflow", "simulations", None, "start"]):
                sid = path_parts[2]
                self._send_json(200, self.adapter.start_simulation(sid, self._client_id()))
                return
            if self._matches(path_parts, ["cityflow", "simulations", None, "pause"]):
                sid = path_parts[2]
                self._send_json(200, self.adapter.pause_simulation(sid, self._client_id()))
                return
            if self._matches(path_parts, ["cityflow", "simulations", None, "stop"]):
                sid = path_parts[2]
                self._send_json(200, self.adapter.stop_simulation(sid, self._client_id()))
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

    def _require_cityflow_auth(self, path_parts: list[str]) -> None:
        if not path_parts or path_parts[0] != "cityflow" or not API_TOKEN:
            return
        if self.headers.get(CITYFLOW_TOKEN_HEADER, "") != API_TOKEN:
            raise ApiError(
                status=401,
                code="UNAUTHORIZED",
                message="missing or invalid CityFlow API token",
                retryable=False,
            )

    def _client_id(self) -> str:
        value = self.headers.get(CITYFLOW_CLIENT_HEADER, "default").strip()
        return value or "default"

    def _read_json_body(self) -> dict:
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            total_size = 0
            while True:
                size_line = self.rfile.readline().strip()
                if not size_line:
                    continue
                chunk_size = int(size_line.split(b";", 1)[0], 16)
                if chunk_size == 0:
                    self.rfile.readline()
                    break
                total_size += chunk_size
                if total_size > MAX_REQUEST_BYTES:
                    raise ApiError(
                        status=413,
                        code="REQUEST_TOO_LARGE",
                        message=f"request body exceeds limit: {MAX_REQUEST_BYTES} bytes",
                        retryable=False,
                    )
                chunks.append(self.rfile.read(chunk_size))
                self.rfile.readline()
            raw = b"".join(chunks).decode("utf-8")
            return json.loads(raw) if raw else {}

        length = int(self.headers.get("Content-Length", "0"))
        if length > MAX_REQUEST_BYTES:
            raise ApiError(
                status=413,
                code="REQUEST_TOO_LARGE",
                message=f"request body exceeds limit: {MAX_REQUEST_BYTES} bytes",
                retryable=False,
            )
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
        self.send_header("Access-Control-Allow-Origin", ALLOWED_ORIGIN)
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", f"Content-Type, {CITYFLOW_TOKEN_HEADER}, {CITYFLOW_CLIENT_HEADER}")


def run(host: str, port: int) -> None:
    try:
        if ENGINE_MODE == "sumo":
            from app.sumo_adapter import SumoAdapter

            CityFlowRequestHandler.adapter = SumoAdapter(DATA_DIR)
        else:
            CityFlowRequestHandler.adapter = CityFlowAdapter(DATA_DIR)
    except ApiError as ex:
        print(f"Failed to initialize simulation adapter: {ex.code} {ex.message}", file=sys.stderr)
        raise
    server = ThreadingHTTPServer((host, port), CityFlowRequestHandler)
    print(f"Python simulation service ({ENGINE_MODE}) listening on http://{host}:{port}")
    server.serve_forever()


def main() -> None:
    parser = argparse.ArgumentParser(description="Python CityFlow-compatible simulation HTTP service")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9000)
    args = parser.parse_args()
    run(args.host, args.port)


if __name__ == "__main__":
    main()
