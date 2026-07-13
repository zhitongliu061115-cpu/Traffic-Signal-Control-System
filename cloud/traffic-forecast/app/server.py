from __future__ import annotations

import json
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from .config import model_root, server_host, server_port
from .runtime import ForecastRuntime


class ForecastApplication:
    def __init__(self):
        self.runtime: ForecastRuntime | None = None
        self.load_error: str | None = None
        self.reload()

    def reload(self) -> None:
        try:
            self.runtime = ForecastRuntime(model_root())
            self.load_error = None
        except Exception as exc:  # Service stays observable even when artifacts are missing.
            self.runtime = None
            self.load_error = str(exc)

    def health(self) -> tuple[int, dict[str, Any]]:
        if self.runtime is None:
            return HTTPStatus.SERVICE_UNAVAILABLE, {
                "status": "unavailable",
                "message": self.load_error or "model is unavailable",
            }
        return HTTPStatus.OK, self.runtime.health()

    def predict(self, payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        if self.runtime is None:
            return HTTPStatus.SERVICE_UNAVAILABLE, {
                "available": False,
                "message": self.load_error or "model is unavailable",
            }
        try:
            return HTTPStatus.OK, self.runtime.predict(payload)
        except ValueError as exc:
            return HTTPStatus.UNPROCESSABLE_ENTITY, {"available": False, "message": str(exc)}


APPLICATION = ForecastApplication()


class ForecastRequestHandler(BaseHTTPRequestHandler):
    server_version = "TrafficForecast/1.0"

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            status, payload = APPLICATION.health()
            self._json(status, payload)
            return
        self._json(HTTPStatus.NOT_FOUND, {"message": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path == "/reload":
            APPLICATION.reload()
            status, payload = APPLICATION.health()
            self._json(status, payload)
            return
        if self.path != "/predict":
            self._json(HTTPStatus.NOT_FOUND, {"message": "not found"})
            return
        try:
            payload = json.loads(self._read_request_body().decode("utf-8"))
            if not isinstance(payload, dict):
                raise ValueError("request body must be a JSON object")
            status, response = APPLICATION.predict(payload)
            self._json(status, response)
        except (ValueError, json.JSONDecodeError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"available": False, "message": str(exc)})

    def _read_request_body(self) -> bytes:
        maximum_size = 8 * 1024 * 1024
        transfer_encoding = self.headers.get("Transfer-Encoding", "").lower()
        if transfer_encoding == "chunked":
            body = bytearray()
            while True:
                size_line = self.rfile.readline().strip().split(b";", 1)[0]
                if not size_line:
                    raise ValueError("invalid chunked request body")
                chunk_size = int(size_line, 16)
                if chunk_size == 0:
                    self.rfile.readline()
                    break
                if len(body) + chunk_size > maximum_size:
                    raise ValueError("request body must not exceed 8 MiB")
                body.extend(self.rfile.read(chunk_size))
                self.rfile.read(2)
            if not body:
                raise ValueError("request body must not be empty")
            return bytes(body)

        content_length = int(self.headers.get("Content-Length", "0"))
        if content_length <= 0 or content_length > maximum_size:
            raise ValueError("request body must be between 1 byte and 8 MiB")
        return self.rfile.read(content_length)

    def log_message(self, format: str, *args: object) -> None:
        return

    def _json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(int(status))
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    address = (server_host(), server_port())
    server = ThreadingHTTPServer(address, ForecastRequestHandler)
    print(f"traffic forecast service listening on http://{address[0]}:{address[1]}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
