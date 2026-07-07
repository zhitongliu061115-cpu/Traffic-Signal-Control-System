from fastapi import APIRouter


router = APIRouter(tags=["health"])


@router.get("/health")
def health_check() -> dict[str, object]:
    return {
        "code": 0,
        "msg": "ok",
        "data": {
            "status": "ok",
        },
    }
