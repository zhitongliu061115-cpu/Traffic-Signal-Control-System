from dataclasses import dataclass

from app.models import JsonDict


@dataclass
class ApiError(Exception):
    status: int
    code: str
    message: str
    retryable: bool = False


def error_response(error: ApiError) -> JsonDict:
    return {
        "success": False,
        "code": error.code,
        "message": error.message,
        "retryable": error.retryable,
    }
