import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from threading import Lock
from time import perf_counter
from typing import Any, Dict, List, Optional

import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel


PHASE_CODE_TO_INDEX = {
    "ETWT": 1,
    "NTST": 2,
    "ELWL": 3,
    "NLSL": 4,
}

PHASE_INDEX_TO_CODE = {value: key for key, value in PHASE_CODE_TO_INDEX.items()}
FOUR_PHASE_LIST = ["ETWT", "NTST", "ELWL", "NLSL"]
LANE_ORDER = ["WT", "WL", "ST", "SL", "ET", "EL", "NT", "NL"]
LOCATION_DICT = {"N": "North", "S": "South", "E": "East", "W": "West"}
PHASE_EXPLANATION = {
    "NTST": "- NTST: Northern and southern through lanes.",
    "NLSL": "- NLSL: Northern and southern left-turn lanes.",
    "ETWT": "- ETWT: Eastern and western through lanes.",
    "ELWL": "- ELWL: Eastern and western left-turn lanes.",
}

MODEL = None
TOKENIZER = None
MODEL_LOCK = Lock()
MODEL_PATH = ""
MODEL_MODE = "not-loaded"
SYSTEM_PROMPT = (
    "You are an expert in traffic management. You can use your knowledge of traffic commonsense "
    "to solve this traffic signal control tasks."
)
PROMPT_MODE = "official-commonsense"
# Match the open-model evaluation settings used by the official LLMTSCS trainer.
MAX_NEW_TOKENS = 1024
TEMPERATURE = 0.1
TOP_K = 50
TOP_P = 1.0
DO_SAMPLE = True
LOG_DIR = Path("logs")


class PhaseCandidate(BaseModel):
    phaseIndex: int
    phaseCode: Optional[str] = None


class IntersectionState(BaseModel):
    intersectionId: str
    currentPhaseIndex: Optional[int] = 1
    currentPhaseCode: Optional[str] = "ETWT"
    phaseCandidates: List[PhaseCandidate] = []


class PredictRequest(BaseModel):
    sceneId: str
    intersectionId: str
    simTime: float = 0.0
    currentPhaseIndex: Optional[int] = 1
    currentPhaseCode: Optional[str] = "ETWT"
    phaseCandidates: List[PhaseCandidate] = []
    observation: Dict[str, Any] = {}


class BatchPredictRequest(BaseModel):
    sceneId: str
    simTime: float = 0.0
    intersections: List[IntersectionState] = []
    observation: Dict[str, Any] = {}
    maxNewTokens: Optional[int] = None


class PredictResponse(BaseModel):
    intersectionId: str
    phaseIndex: int
    phaseCode: str
    durationSec: int
    confidence: float
    reason: str
    parsedFromModel: bool
    rawOutput: str
    inferenceTimeSec: float


class BatchPredictResponse(BaseModel):
    sceneId: str
    simTime: float
    modelMode: str
    modelPath: str
    maxNewTokens: int
    inferenceTimeSec: float
    decisions: List[PredictResponse]


app = FastAPI(title="Traffic-R1 Strict Online Service")


@app.get("/health")
def health():
    return {
        "status": "UP",
        "service": "traffic-r1-online",
        "modelMode": MODEL_MODE,
        "modelLoaded": MODEL is not None and TOKENIZER is not None,
        "modelPath": MODEL_PATH,
        "supportsBatch": True,
        "strictModelOutput": True,
        "promptMode": PROMPT_MODE,
        "maxNewTokens": MAX_NEW_TOKENS,
        "temperature": TEMPERATURE,
        "topK": TOP_K,
        "topP": TOP_P,
        "doSample": DO_SAMPLE,
    }


@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest):
    batch = BatchPredictRequest(
        sceneId=req.sceneId,
        simTime=req.simTime,
        intersections=[
            IntersectionState(
                intersectionId=req.intersectionId,
                currentPhaseIndex=req.currentPhaseIndex,
                currentPhaseCode=req.currentPhaseCode,
                phaseCandidates=req.phaseCandidates,
            )
        ],
        observation=req.observation,
    )
    return predict_batch(batch).decisions[0]


@app.post("/predict-batch", response_model=BatchPredictResponse)
def predict_batch(req: BatchPredictRequest):
    started_at = perf_counter()
    if not req.intersections:
        return BatchPredictResponse(
            sceneId=req.sceneId,
            simTime=req.simTime,
            modelMode=MODEL_MODE,
            modelPath=MODEL_PATH,
            maxNewTokens=MAX_NEW_TOKENS,
            inferenceTimeSec=0.0,
            decisions=[],
        )

    ensure_model_ready(req)

    effective_max_new_tokens = resolve_max_new_tokens(req.maxNewTokens)
    prompts = [build_prompt(req, intersection) for intersection in req.intersections]
    raw_outputs = generate_batch(prompts, effective_max_new_tokens)
    elapsed = perf_counter() - started_at

    decisions: List[PredictResponse] = []
    audit_items = []
    invalid_items = []
    for intersection, prompt, raw in zip(req.intersections, prompts, raw_outputs):
        phase_code = parse_phase_code(raw)
        if phase_code is None:
            invalid_items.append(
                {
                    "intersectionId": intersection.intersectionId,
                    "rawOutput": raw,
                    "message": "model output does not contain a valid phase in the official <signal>...</signal> format",
                }
            )
            audit_items.append(
                {
                    "intersectionId": intersection.intersectionId,
                    "prompt": prompt,
                    "rawOutput": raw,
                    "parsedFromModel": False,
                    "decision": None,
                }
            )
            continue

        response = PredictResponse(
            intersectionId=intersection.intersectionId,
            phaseIndex=PHASE_CODE_TO_INDEX[phase_code],
            phaseCode=phase_code,
            durationSec=30,
            confidence=0.85,
            reason="Traffic-R1 generated a valid phase from model output",
            parsedFromModel=True,
            rawOutput=raw,
            inferenceTimeSec=round(elapsed, 3),
        )
        decisions.append(response)
        audit_items.append(
            {
                "intersectionId": intersection.intersectionId,
                "prompt": prompt,
                "rawOutput": raw,
                "parsedFromModel": True,
                "decision": model_to_dict(response),
            }
        )

    append_audit_log(
        "cloud.predict-batch",
        {
            "sceneId": req.sceneId,
            "simTime": req.simTime,
            "modelMode": MODEL_MODE,
            "modelPath": MODEL_PATH,
            "maxNewTokens": MAX_NEW_TOKENS,
            "requestMaxNewTokens": effective_max_new_tokens,
            "inferenceTimeSec": round(elapsed, 3),
            "items": audit_items,
            "invalidItems": invalid_items,
        },
    )

    if invalid_items:
        raise_model_output_invalid(req, elapsed, invalid_items, effective_max_new_tokens)

    return BatchPredictResponse(
        sceneId=req.sceneId,
        simTime=req.simTime,
        modelMode=MODEL_MODE,
        modelPath=MODEL_PATH,
        maxNewTokens=effective_max_new_tokens,
        inferenceTimeSec=round(elapsed, 3),
        decisions=decisions,
    )


def ensure_model_ready(req: BatchPredictRequest) -> None:
    if MODEL is not None and TOKENIZER is not None:
        return
    detail = {
        "success": False,
        "code": "MODEL_NOT_LOADED",
        "message": "Traffic-R model is not loaded; strict mode forbids heuristic fallback",
        "sceneId": req.sceneId,
        "simTime": req.simTime,
        "modelMode": MODEL_MODE,
        "modelPath": MODEL_PATH,
        "parsedFromModel": False,
        "rawOutput": "",
        "inferenceTimeSec": 0.0,
    }
    append_audit_log("cloud.predict-batch.error", detail)
    raise HTTPException(status_code=503, detail=detail)


def raise_model_output_invalid(
    req: BatchPredictRequest,
    elapsed: float,
    invalid_items: List[Dict[str, Any]],
    max_new_tokens: int,
) -> None:
    detail = {
        "success": False,
        "code": "MODEL_OUTPUT_INVALID",
        "message": "Traffic-R generated no valid four-phase decision for at least one intersection; no fallback decision is allowed",
        "sceneId": req.sceneId,
        "simTime": req.simTime,
        "modelMode": MODEL_MODE,
        "modelPath": MODEL_PATH,
        "maxNewTokens": max_new_tokens,
        "parsedFromModel": False,
        "inferenceTimeSec": round(elapsed, 3),
        "invalidItems": invalid_items,
    }
    append_audit_log("cloud.predict-batch.error", detail)
    raise HTTPException(status_code=422, detail=detail)


def build_prompt(req: BatchPredictRequest, intersection: IntersectionState) -> str:
    official_messages = build_official_commonsense_messages(req, intersection)
    if hasattr(TOKENIZER, "apply_chat_template") and getattr(TOKENIZER, "chat_template", None):
        try:
            return TOKENIZER.apply_chat_template(
                official_messages,
                tokenize=False,
                add_generation_prompt=True,
            )
        except Exception as ex:
            print(f"failed to apply tokenizer chat template; use plain official prompt. error={ex}", flush=True)
    return official_messages_to_text(official_messages)


def build_official_commonsense_messages(req: BatchPredictRequest, intersection: IntersectionState) -> List[Dict[str, str]]:
    state_txt = state_to_official_commonsense_table(req, intersection)
    return [
        {
            "role": "system",
            "content": SYSTEM_PROMPT,
        },
        {
            "role": "user",
            "content": (
                "A traffic light regulates a four-section intersection with northern, southern, eastern, and western "
                "sections, each containing two lanes: one for through traffic and one for left-turns. Each lane is "
                "further divided into three segments. Segment 1 is the closest to the intersection. Segment 2 is in the "
                "middle. Segment 3 is the farthest. In a lane, there may be early queued vehicles and approaching "
                "vehicles traveling in different segments. Early queued vehicles have arrived at the intersection and "
                "await passage permission. Approaching vehicles will arrive at the intersection in the future.\n\n"
                "The traffic light has 4 signal phases. Each signal relieves vehicles' flow in the group of two "
                "specific lanes. The state of the intersection is listed below. It describes:\n"
                "- The group of lanes relieving vehicles' flow under each signal phase.\n"
                "- The number of early queued vehicles of the allowed lanes of each signal.\n"
                "- The number of approaching vehicles in different segments of the allowed lanes of each signal.\n\n"
                + state_txt +
                "Please answer:\n"
                "Which is the most effective traffic signal that will most significantly improve the traffic "
                "condition during the next phase?\n\n"
                "Requirements:\n"
                "- Let's think step by step.\n"
                "- You can only choose one of the signals listed above.\n"
                "- You must follow the following steps to provide your analysis: Step 1: Provide your analysis "
                "for identifying the optimal traffic signal. Step 2: Answer your chosen signal.\n"
                "- Your choice can only be given after finishing the analysis.\n"
                "- Your choice must be identified by the tag: <signal>YOUR_CHOICE</signal>."
            ),
        },
    ]


def official_messages_to_text(messages: List[Dict[str, str]]) -> str:
    system = messages[0]["content"] if messages else ""
    user = messages[1]["content"] if len(messages) > 1 else ""
    return f"{system}\n\n### Instruction:\n{user}\n\n### Response:\n"


def state_to_official_commonsense_table(req: BatchPredictRequest, intersection: IntersectionState) -> str:
    lane_state = estimate_official_lane_state(req, intersection)
    state_txt = ""
    for phase in FOUR_PHASE_LIST:
        lane_1 = phase[:2]
        lane_2 = phase[2:]
        queue_len_1 = int(lane_state[lane_1]["queue_len"])
        queue_len_2 = int(lane_state[lane_2]["queue_len"])
        cells_1 = lane_state[lane_1]["cells"]
        cells_2 = lane_state[lane_2]["cells"]
        seg_1_lane_1 = int(cells_1[0])
        seg_2_lane_1 = int(cells_1[1])
        seg_3_lane_1 = int(cells_1[2]) + int(cells_1[3])
        seg_1_lane_2 = int(cells_2[0])
        seg_2_lane_2 = int(cells_2[1])
        seg_3_lane_2 = int(cells_2[2]) + int(cells_2[3])

        state_txt += (
            f"Signal: {phase}\n"
            f"Relieves: {allowed_lanes_text(phase)}\n"
            f"- Early queued: {queue_len_1} ({LOCATION_DICT[lane_1[0]]}), "
            f"{queue_len_2} ({LOCATION_DICT[lane_2[0]]}), {queue_len_1 + queue_len_2} (Total)\n"
            f"- Segment 1: {seg_1_lane_1} ({LOCATION_DICT[lane_1[0]]}), "
            f"{seg_1_lane_2} ({LOCATION_DICT[lane_2[0]]}), {seg_1_lane_1 + seg_1_lane_2} (Total)\n"
            f"- Segment 2: {seg_2_lane_1} ({LOCATION_DICT[lane_1[0]]}), "
            f"{seg_2_lane_2} ({LOCATION_DICT[lane_2[0]]}), {seg_2_lane_1 + seg_2_lane_2} (Total)\n"
            f"- Segment 3: {seg_3_lane_1} ({LOCATION_DICT[lane_1[0]]}), "
            f"{seg_3_lane_2} ({LOCATION_DICT[lane_2[0]]}), {seg_3_lane_1 + seg_3_lane_2} (Total)\n\n"
        )
    return state_txt


def estimate_official_lane_state(req: BatchPredictRequest, intersection: IntersectionState) -> Dict[str, Dict[str, Any]]:
    provided_lane_state = provided_official_lane_state(req, intersection.intersectionId)
    if provided_lane_state is not None:
        return provided_lane_state

    lanes = {
        lane: {"queue_len": 0, "cells": [0, 0, 0, 0]}
        for lane in LANE_ORDER
    }
    roads = roads_for_intersection(req.observation.get("roads") or [], intersection.intersectionId)
    for road in roads:
        through_lane, left_lane = lanes_for_road_id(str(road.get("id", "")))
        queue_count = safe_int(road.get("queueCount"))
        vehicle_count = max(0, safe_int(road.get("vehicleCount")) - queue_count)
        through_queue = int(round(queue_count * 0.75))
        left_queue = queue_count - through_queue
        through_vehicles = int(round(vehicle_count * 0.75))
        left_vehicles = vehicle_count - through_vehicles
        add_lane_counts(lanes[through_lane], through_queue, through_vehicles)
        add_lane_counts(lanes[left_lane], left_queue, left_vehicles)
    return lanes


def provided_official_lane_state(req: BatchPredictRequest, intersection_id: str) -> Optional[Dict[str, Dict[str, Any]]]:
    lane_states = req.observation.get("laneStates") if isinstance(req.observation, dict) else None
    if not isinstance(lane_states, dict):
        return None
    intersection_state = lane_states.get(intersection_id)
    if not isinstance(intersection_state, dict):
        return None
    lanes = intersection_state.get("lanes")
    if not isinstance(lanes, dict):
        return None

    normalized: Dict[str, Dict[str, Any]] = {}
    for lane_code in LANE_ORDER:
        lane = lanes.get(lane_code, {})
        if not isinstance(lane, dict):
            lane = {}
        cells = lane.get("cells", [0, 0, 0])
        if not isinstance(cells, list):
            cells = [0, 0, 0, 0]
        normalized[lane_code] = {
            "queue_len": safe_int(lane.get("queue_len")),
            "avg_wait_time": float(lane.get("avg_wait_time", 0.0) or 0.0),
            "cells": [safe_int(cells[index]) if index < len(cells) else 0 for index in range(4)],
        }
    return normalized


def lanes_for_road_id(road_id: str) -> tuple[str, str]:
    if road_id.endswith("_0"):
        return "WT", "WL"
    if road_id.endswith("_1"):
        return "ST", "SL"
    if road_id.endswith("_2"):
        return "ET", "EL"
    if road_id.endswith("_3"):
        return "NT", "NL"
    return "WT", "WL"


def add_lane_counts(lane: Dict[str, Any], queue_count: int, vehicle_count: int) -> None:
    lane["queue_len"] += max(0, queue_count)
    cells = distribute_to_four_segments(max(0, vehicle_count))
    lane["cells"] = [lane["cells"][index] + cells[index] for index in range(4)]


def distribute_to_four_segments(vehicle_count: int) -> List[int]:
    seg1 = vehicle_count // 4
    seg2 = vehicle_count // 4
    seg3 = vehicle_count // 4
    seg4 = vehicle_count - seg1 - seg2 - seg3
    return [seg1, seg2, seg3, seg4]


def safe_int(value: Any) -> int:
    try:
        return int(float(value or 0))
    except (TypeError, ValueError):
        return 0


def allowed_lanes_text(phase: str) -> str:
    return PHASE_EXPLANATION.get(phase, f"- {phase}: Unknown.")[8:-1]


def roads_for_intersection(roads: List[Dict[str, Any]], intersection_id: str) -> List[Dict[str, Any]]:
    match = re.search(r"intersection_(\d+)_(\d+)", intersection_id)
    if not match:
        return roads
    row, col = match.group(1), match.group(2)
    token = f"road_{row}_{col}_"
    selected = [road for road in roads if token in str(road.get("id", ""))]
    return selected or roads


def resolve_max_new_tokens(request_max_new_tokens: Optional[int]) -> int:
    if request_max_new_tokens is None:
        return MAX_NEW_TOKENS
    try:
        value = int(request_max_new_tokens)
    except (TypeError, ValueError):
        return MAX_NEW_TOKENS
    return max(1, min(value, 1024))


def generate_batch(prompts: List[str], max_new_tokens: int) -> List[str]:
    with MODEL_LOCK:
        import torch

        device = next(MODEL.parameters()).device
        inputs = TOKENIZER(
            prompts,
            return_tensors="pt",
            padding=True,
            truncation=True,
        ).to(device)
        generation_kwargs = {
            "max_new_tokens": max_new_tokens,
            "pad_token_id": TOKENIZER.pad_token_id,
            "eos_token_id": TOKENIZER.eos_token_id,
        }
        if DO_SAMPLE:
            generation_kwargs.update(
                {
                    "do_sample": True,
                    "temperature": TEMPERATURE,
                    "top_k": TOP_K,
                    "top_p": TOP_P,
                }
            )
        else:
            generation_kwargs["do_sample"] = False

        with torch.no_grad():
            output_ids = MODEL.generate(**inputs, **generation_kwargs)

        generated_ids = output_ids[:, inputs["input_ids"].shape[1]:]
        decoded = TOKENIZER.batch_decode(generated_ids, skip_special_tokens=True)
    return [output.strip() for output in decoded]


def parse_phase_code(text: str) -> Optional[str]:
    raw = text or ""
    upper = raw.upper()

    signal_match = re.search(r"<\s*SIGNAL\s*>\s*(ETWT|NTST|ELWL|NLSL)\s*<\s*/\s*SIGNAL\s*>", upper)
    if signal_match:
        return signal_match.group(1)

    return None


def load_model(model_path: str):
    global MODEL, TOKENIZER, MODEL_PATH, MODEL_MODE
    if not model_path:
        MODEL_MODE = "not-loaded"
        MODEL_PATH = ""
        return

    from transformers import AutoModelForCausalLM, AutoTokenizer
    import torch

    MODEL_PATH = model_path
    TOKENIZER = AutoTokenizer.from_pretrained(model_path, trust_remote_code=True)
    TOKENIZER.padding_side = "left"
    if TOKENIZER.pad_token is None:
        TOKENIZER.pad_token = TOKENIZER.eos_token
    MODEL = AutoModelForCausalLM.from_pretrained(
        model_path,
        trust_remote_code=True,
        torch_dtype=torch.float16 if torch.cuda.is_available() else torch.float32,
        device_map="auto",
    )
    MODEL.eval()
    MODEL_MODE = "transformers-strict-batch"


def load_system_prompt(prompt_file: str) -> None:
    global SYSTEM_PROMPT
    if not prompt_file:
        return
    path = Path(prompt_file)
    if not path.exists():
        print(f"system prompt file not found; use fallback prompt. path={path}", flush=True)
        return
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        value = payload.get("system_prompt")
        if isinstance(value, str) and value.strip():
            SYSTEM_PROMPT = value
            print(f"loaded official system prompt from {path}", flush=True)
    except Exception as ex:
        print(f"failed to load system prompt file; use fallback prompt. path={path}, error={ex}", flush=True)


def append_audit_log(event: str, payload: Dict[str, Any]) -> None:
    try:
        LOG_DIR.mkdir(parents=True, exist_ok=True)
        record = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "event": event,
            "payload": payload,
        }
        with (LOG_DIR / "traffic-r-prompts.jsonl").open("a", encoding="utf-8") as file:
            file.write(json.dumps(record, ensure_ascii=False) + "\n")
    except Exception as ex:
        print(f"failed to write Traffic-R audit log: {ex}", flush=True)


def model_to_dict(model: BaseModel) -> Dict[str, Any]:
    if hasattr(model, "model_dump"):
        return model.model_dump()
    return model.dict()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=6008)
    parser.add_argument("--model-path", "--model-dir", dest="model_path", default="")
    parser.add_argument("--max-new-tokens", type=int, default=1024)
    parser.add_argument("--temperature", type=float, default=0.1)
    parser.add_argument("--top-k", type=int, default=50)
    parser.add_argument("--top-p", type=float, default=1.0)
    parser.add_argument("--do-sample", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--log-dir", default="logs")
    parser.add_argument("--system-prompt-file", default="prompts/prompt_commonsense.json")
    args = parser.parse_args()

    global MAX_NEW_TOKENS, TEMPERATURE, TOP_K, TOP_P, DO_SAMPLE, LOG_DIR
    MAX_NEW_TOKENS = args.max_new_tokens
    TEMPERATURE = args.temperature
    TOP_K = args.top_k
    TOP_P = args.top_p
    DO_SAMPLE = args.do_sample
    LOG_DIR = Path(args.log_dir)
    load_system_prompt(args.system_prompt_file)
    load_model(args.model_path)
    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()
