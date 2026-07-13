from __future__ import annotations

import argparse
import json

from app.config import DatabaseConfig
from app.repository import ForecastRepository
from app.seed import seed_synthetic


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate deterministic minute-level traffic observations")
    parser.add_argument("--days", type=int, default=60)
    parser.add_argument("--replace-synthetic", action="store_true")
    args = parser.parse_args()
    repository = ForecastRepository(DatabaseConfig.from_env())
    result = seed_synthetic(repository, args.days, replace=args.replace_synthetic)
    print(json.dumps(result, ensure_ascii=False, indent=2, default=str))


if __name__ == "__main__":
    main()
