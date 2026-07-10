# Traffic-R Interface Testing

This folder contains a strict interface test workflow for the cloud Traffic-R
service. The goal is to verify that the model really generates a valid phase
decision before the result is allowed to control CityFlow.

## Build Test Cases

Generate test cases from Spring Boot audit logs:

```powershell
python cloud/traffic-r/build_interface_test_dataset.py `
  --log backend/logs/traffic-r-decisions.jsonl `
  --roadnet sim-python/data/jinan_3x4/roadnet_3_4.json `
  --output cloud/traffic-r/testdata/traffic_r_interface_cases_lane_level.json `
  --max-real-cases 12 `
  --include-derived
```

The generated dataset contains:

- real `backend.predict-batch.request` payloads from previous simulations;
- stress-scene samples when present in the audit log;
- derived pressure cases that reuse real road and intersection ids.
- `observation.laneStates[intersectionId]` in the official
  `WT/WL/ST/SL/ET/EL/NT/NL` format:

```json
{
  "WT": {"queue_len": 1, "avg_wait_time": 0.0, "cells": [1, 0, 0, 0]},
  "WL": {"queue_len": 0, "avg_wait_time": 0.0, "cells": [0, 0, 0, 0]}
}
```

The service keeps the official LLMTSCS four-cell lane state internally and
formats it exactly like `ChatGPTTLCS_Commonsense.state2table()`: Segment 1 uses
cell 0, Segment 2 uses cell 1, and Segment 3 is `cell 2 + cell 3`.

The historical Spring Boot audit log is road-level, so lane states generated
from it are marked as `estimated_from_road_level_log`. The builder uses the
Jinan roadnet `roadLinks[].startRoad` and `roadLinks[].type` fields to map
incoming roads to movement lanes. Future Python CityFlow frames should return
native lane-level counts directly.

## Start Strict Cloud Service

Copy `traffic_r_service.py` to the cloud Traffic-R directory and restart:

```bash
cd /root/autodl-tmp/traffic-R1

WANDB_MODE=disabled \
PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION=python \
TOKENIZERS_PARALLELISM=false \
.venv-autodl/bin/python traffic_r_service.py \
  --host 0.0.0.0 \
  --port 6008 \
  --model-path /root/autodl-tmp/traffic-R1/models/Traffic-R1/huggingface \
  --max-new-tokens 1024 \
  --temperature 0.0 \
  --top-p 1.0 \
  --no-do-sample \
  --system-prompt-file prompts/prompt_commonsense.json
```

Health check:

```bash
curl http://127.0.0.1:6008/health
```

Expected:

```json
{
  "modelMode": "transformers-strict-batch",
  "modelLoaded": true,
  "strictModelOutput": true
}
```

## Run Interface Tests

Smoke test the first 3 cases:

```powershell
python cloud/traffic-r/test_traffic_r_interface.py `
  --dataset cloud/traffic-r/testdata/traffic_r_interface_cases_lane_level.json `
  --base-url http://127.0.0.1:16008 `
  --timeout-sec 240 `
  --limit 3
```

Run all cases:

```powershell
python cloud/traffic-r/test_traffic_r_interface.py `
  --dataset cloud/traffic-r/testdata/traffic_r_interface_cases_lane_level.json `
  --base-url http://127.0.0.1:16008 `
  --timeout-sec 240
```

Sweep token limits:

```powershell
python cloud/traffic-r/test_traffic_r_interface.py `
  --dataset cloud/traffic-r/testdata/traffic_r_interface_cases_lane_level.json `
  --base-url http://127.0.0.1:16008 `
  --timeout-sec 240 `
  --limit 5 `
  --token-grid 32,64,96,128
```

## Passing Criteria

A case passes only if:

- HTTP status is `200`;
- response decision count equals request intersection count;
- every `phaseCode` is one of `ETWT`, `NTST`, `ELWL`, `NLSL`;
- every decision has `parsedFromModel=true`;
- every decision has non-empty `rawOutput`.

The cloud service uses the official LLMTSCS commonsense prompt structure:

- `system_prompt` is loaded from `prompts/prompt_commonsense.json` when present;
- the user prompt starts with `A crossroad connects two roads...`;
- per-intersection state is formatted as official `Signal: ETWT`, `Allowed lanes`,
  `Early queued`, and `Segment 1/2/3` blocks;
- the default generation setting follows `run_open_LLM.py` with
  `max_new_tokens=1024`, `temperature=0.0`, and no sampling;
- output parsing only accepts the official `<signal>YOUR_CHOICE</signal>` tag.

`MODEL_OUTPUT_INVALID` is a valid strict-mode failure. It means the model did
not generate a usable phase decision and the result must not be applied to
CityFlow.
