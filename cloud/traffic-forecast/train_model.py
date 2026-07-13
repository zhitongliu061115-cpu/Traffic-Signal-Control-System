from __future__ import annotations

import argparse
import json

from app.config import DatabaseConfig, model_root
from app.repository import ForecastRepository
from app.training import train_models


def main() -> None:
    parser = argparse.ArgumentParser(description="Train direct multi-horizon LightGBM traffic models")
    parser.add_argument("--real-only", action="store_true")
    parser.add_argument("--stride-minutes", type=int, default=2)
    parser.add_argument("--max-rounds", type=int, default=220)
    parser.add_argument("--history-days", type=int)
    args = parser.parse_args()
    repository = ForecastRepository(DatabaseConfig.from_env())
    manifest, artifact_dir = train_models(
        repository,
        model_root(),
        include_synthetic=not args.real_only,
        history_days=args.history_days,
        stride_minutes=args.stride_minutes,
        max_rounds=args.max_rounds,
    )
    print(
        json.dumps(
            {
                "modelVersion": manifest["modelVersion"],
                "artifactDir": str(artifact_dir),
                "observationCount": manifest["observationCount"],
                "sampleCount": manifest["sampleCount"],
                "partitionCounts": manifest["partitionCounts"],
                "metrics": manifest["metrics"],
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
