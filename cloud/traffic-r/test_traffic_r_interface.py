import argparse
import copy
import json
import statistics
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional


VALID_PHASE_CODES = {"ETWT", "NTST", "ELWL", "NLSL"}


def main() -> None:
    parser = argparse.ArgumentParser(description="Run strict Traffic-R interface tests against /predict-batch.")
    parser.add_argument("--dataset", default="cloud/traffic-r/testdata/traffic_r_interface_cases.json")
    parser.add_argument("--base-url", default="http://127.0.0.1:16008")
    parser.add_argument("--output-dir", default="cloud/traffic-r/test-results")
    parser.add_argument("--timeout-sec", type=float, default=180.0)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--request-max-new-tokens", type=int, default=0)
    parser.add_argument("--token-grid", default="")
    args = parser.parse_args()

    dataset_path = Path(args.dataset)
    dataset = json.loads(dataset_path.read_text(encoding="utf-8"))
    cases = dataset.get("cases", [])
    if args.limit > 0:
        cases = cases[: args.limit]

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    detail_path = output_dir / f"traffic_r_interface_results_{run_id}.jsonl"
    summary_path = output_dir / f"traffic_r_interface_summary_{run_id}.json"

    health = get_health(args.base_url, args.timeout_sec)
    results: List[Dict[str, Any]] = []
    token_values = parse_token_values(args.token_grid, args.request_max_new_tokens)
    with detail_path.open("w", encoding="utf-8") as file:
        for token_value in token_values:
            for repeat_index in range(1, args.repeat + 1):
                for case in cases:
                    result = run_case(
                        base_url=args.base_url,
                        case=case,
                        timeout_sec=args.timeout_sec,
                        repeat_index=repeat_index,
                        request_max_new_tokens=token_value,
                    )
                    results.append(result)
                    file.write(json.dumps(result, ensure_ascii=False) + "\n")
                    print(one_line(result))

    summary = build_summary(
        dataset_path=dataset_path,
        base_url=args.base_url,
        health=health,
        results=results,
        detail_path=detail_path,
    )
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote detail results to {detail_path}")
    print(f"wrote summary to {summary_path}")
    print(summary_line(summary))


def get_health(base_url: str, timeout_sec: float) -> Dict[str, Any]:
    try:
        return post_or_get_json(f"{base_url.rstrip('/')}/health", None, timeout_sec)
    except Exception as ex:
        return {
            "status": "UNAVAILABLE",
            "error": str(ex),
        }


def run_case(
    base_url: str,
    case: Dict[str, Any],
    timeout_sec: float,
    repeat_index: int,
    request_max_new_tokens: Optional[int],
) -> Dict[str, Any]:
    request_payload = copy.deepcopy(case.get("request", {}))
    if request_max_new_tokens is not None:
        request_payload["maxNewTokens"] = request_max_new_tokens
    started = time.perf_counter()
    response = None
    error = None
    http_status = None
    try:
        http_status, response = request_json(
            f"{base_url.rstrip('/')}/predict-batch",
            request_payload,
            timeout_sec,
        )
    except urllib.error.HTTPError as ex:
        http_status = ex.code
        error = read_http_error(ex)
    except Exception as ex:
        error = {
            "code": type(ex).__name__,
            "message": str(ex),
        }
    wall_time = time.perf_counter() - started

    validation = validate_response(case, response, http_status, error)
    return {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "repeatIndex": repeat_index,
        "caseId": case.get("caseId"),
        "description": case.get("description"),
        "sceneId": request_payload.get("sceneId"),
        "simTime": request_payload.get("simTime"),
        "metrics": request_payload.get("observation", {}).get("metrics", {}),
        "intersectionCount": len(request_payload.get("intersections", []) or []),
        "requestMaxNewTokens": request_max_new_tokens,
        "httpStatus": http_status,
        "wallTimeSec": round(wall_time, 3),
        "responseInferenceTimeSec": response.get("inferenceTimeSec") if isinstance(response, dict) else None,
        "valid": validation["valid"],
        "validationErrors": validation["errors"],
        "phaseCounts": validation["phaseCounts"],
        "parsedDecisionCount": validation["parsedDecisionCount"],
        "rawOutputPreview": validation["rawOutputPreview"],
        "error": error,
        "response": response,
    }


def validate_response(
    case: Dict[str, Any],
    response: Optional[Dict[str, Any]],
    http_status: Optional[int],
    error: Optional[Dict[str, Any]],
) -> Dict[str, Any]:
    errors: List[str] = []
    phase_counts: Dict[str, int] = {}
    raw_preview: Dict[str, str] = {}
    parsed_count = 0

    if error is not None:
        errors.append(f"http_or_request_error:{error.get('code') or http_status}")
        return result(False, errors, phase_counts, parsed_count, raw_preview)

    if http_status != 200:
        errors.append(f"unexpected_http_status:{http_status}")
    if not isinstance(response, dict):
        errors.append("response_not_json_object")
        return result(False, errors, phase_counts, parsed_count, raw_preview)

    decisions = response.get("decisions")
    if not isinstance(decisions, list):
        errors.append("decisions_not_list")
        return result(False, errors, phase_counts, parsed_count, raw_preview)

    expected_count = len(case.get("request", {}).get("intersections", []) or [])
    if len(decisions) != expected_count:
        errors.append(f"decision_count_mismatch:expected={expected_count},actual={len(decisions)}")

    for decision in decisions:
        intersection_id = decision.get("intersectionId", "unknown")
        phase_code = decision.get("phaseCode")
        parsed = decision.get("parsedFromModel")
        raw_output = decision.get("rawOutput")
        phase_counts[phase_code] = phase_counts.get(phase_code, 0) + 1
        if parsed is True:
            parsed_count += 1
        else:
            errors.append(f"{intersection_id}:parsedFromModel_not_true")
        if phase_code not in VALID_PHASE_CODES:
            errors.append(f"{intersection_id}:invalid_phaseCode:{phase_code}")
        if not isinstance(raw_output, str) or not raw_output.strip():
            errors.append(f"{intersection_id}:rawOutput_empty")
        else:
            raw_preview[intersection_id] = raw_output.strip()[:240]

    return result(len(errors) == 0, errors, phase_counts, parsed_count, raw_preview)


def result(
    valid: bool,
    errors: List[str],
    phase_counts: Dict[str, int],
    parsed_count: int,
    raw_preview: Dict[str, str],
) -> Dict[str, Any]:
    return {
        "valid": valid,
        "errors": errors,
        "phaseCounts": phase_counts,
        "parsedDecisionCount": parsed_count,
        "rawOutputPreview": raw_preview,
    }


def build_summary(
    dataset_path: Path,
    base_url: str,
    health: Dict[str, Any],
    results: List[Dict[str, Any]],
    detail_path: Path,
) -> Dict[str, Any]:
    valid_results = [item for item in results if item.get("valid")]
    wall_times = [float(item["wallTimeSec"]) for item in results if isinstance(item.get("wallTimeSec"), (int, float))]
    inference_times = [
        float(item["responseInferenceTimeSec"])
        for item in results
        if isinstance(item.get("responseInferenceTimeSec"), (int, float))
    ]
    phase_counts: Dict[str, int] = {}
    for item in results:
        for phase, count in (item.get("phaseCounts") or {}).items():
            phase_counts[phase] = phase_counts.get(phase, 0) + int(count)

    return {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "dataset": str(dataset_path),
        "baseUrl": base_url,
        "health": health,
        "detailPath": str(detail_path),
        "caseRunCount": len(results),
        "validRunCount": len(valid_results),
        "validRate": round(len(valid_results) / len(results), 4) if results else 0,
        "phaseCounts": phase_counts,
        "tokenSummaries": token_summaries(results),
        "wallTimeSec": stats(wall_times),
        "responseInferenceTimeSec": stats(inference_times),
        "failedCases": [
            {
                "caseId": item.get("caseId"),
                "httpStatus": item.get("httpStatus"),
                "errors": item.get("validationErrors"),
                "error": item.get("error"),
            }
            for item in results
            if not item.get("valid")
        ],
    }


def stats(values: List[float]) -> Dict[str, Optional[float]]:
    if not values:
        return {"min": None, "max": None, "avg": None, "median": None}
    return {
        "min": round(min(values), 3),
        "max": round(max(values), 3),
        "avg": round(sum(values) / len(values), 3),
        "median": round(statistics.median(values), 3),
    }


def token_summaries(results: List[Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    groups: Dict[str, List[Dict[str, Any]]] = {}
    for item in results:
        key = str(item.get("requestMaxNewTokens") or "server_default")
        groups.setdefault(key, []).append(item)

    summaries: Dict[str, Dict[str, Any]] = {}
    for key, items in groups.items():
        valid_items = [item for item in items if item.get("valid")]
        wall_times = [
            float(item["wallTimeSec"])
            for item in items
            if isinstance(item.get("wallTimeSec"), (int, float))
        ]
        model_times = [
            float(item["responseInferenceTimeSec"])
            for item in items
            if isinstance(item.get("responseInferenceTimeSec"), (int, float))
        ]
        phase_counts: Dict[str, int] = {}
        for item in items:
            for phase, count in (item.get("phaseCounts") or {}).items():
                phase_counts[phase] = phase_counts.get(phase, 0) + int(count)
        summaries[key] = {
            "caseRunCount": len(items),
            "validRunCount": len(valid_items),
            "validRate": round(len(valid_items) / len(items), 4) if items else 0,
            "phaseCounts": phase_counts,
            "wallTimeSec": stats(wall_times),
            "responseInferenceTimeSec": stats(model_times),
        }
    return summaries


def parse_token_values(token_grid: str, request_max_new_tokens: int) -> List[Optional[int]]:
    if token_grid.strip():
        values: List[Optional[int]] = []
        for part in token_grid.split(","):
            part = part.strip()
            if not part:
                continue
            values.append(int(part))
        return values or [None]
    if request_max_new_tokens > 0:
        return [request_max_new_tokens]
    return [None]


def request_json(url: str, payload: Dict[str, Any], timeout_sec: float) -> tuple[int, Dict[str, Any]]:
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout_sec) as response:
        text = response.read().decode("utf-8")
        return response.status, json.loads(text) if text else {}


def post_or_get_json(url: str, payload: Optional[Dict[str, Any]], timeout_sec: float) -> Dict[str, Any]:
    if payload is None:
        request = urllib.request.Request(url, method="GET")
    else:
        request = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            method="POST",
            headers={"Content-Type": "application/json"},
        )
    with urllib.request.urlopen(request, timeout=timeout_sec) as response:
        text = response.read().decode("utf-8")
        return json.loads(text) if text else {}


def read_http_error(ex: urllib.error.HTTPError) -> Dict[str, Any]:
    text = ex.read().decode("utf-8", errors="replace")
    try:
        payload = json.loads(text) if text else {}
    except json.JSONDecodeError:
        payload = {"raw": text}
    detail = payload.get("detail") if isinstance(payload, dict) else payload
    if isinstance(detail, dict):
        return detail
    return {
        "code": f"HTTP_{ex.code}",
        "message": str(detail or ex),
        "raw": payload,
    }


def one_line(result_item: Dict[str, Any]) -> str:
    status = "PASS" if result_item.get("valid") else "FAIL"
    phases = ",".join(f"{key}={value}" for key, value in (result_item.get("phaseCounts") or {}).items())
    return (
        f"{status} token={result_item.get('requestMaxNewTokens') or 'default'} "
        f"case={result_item.get('caseId')} http={result_item.get('httpStatus')} "
        f"wall={result_item.get('wallTimeSec')}s model={result_item.get('responseInferenceTimeSec')}s "
        f"phases=[{phases}] errors={len(result_item.get('validationErrors') or [])}"
    )


def summary_line(summary: Dict[str, Any]) -> str:
    return (
        f"summary valid={summary.get('validRunCount')}/{summary.get('caseRunCount')} "
        f"rate={summary.get('validRate')} wallAvg={summary.get('wallTimeSec', {}).get('avg')}s "
        f"modelAvg={summary.get('responseInferenceTimeSec', {}).get('avg')}s"
    )


if __name__ == "__main__":
    main()
