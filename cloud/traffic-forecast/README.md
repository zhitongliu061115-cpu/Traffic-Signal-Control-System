# Short-term traffic forecasting

This service trains and serves intersection-level direct multi-horizon LightGBM models for the data-analysis page. It predicts inflow, queue length, and average wait at +2, +4, +6, +8, and +10 minutes.

Each online prediction combines the latest 30 contiguous minutes with the observation at the same clock time on each of the previous 14 days. The model derives separate recent-week and previous-week profiles, same-time 7/14-day lags, and week-over-week changes. The backend only transfers these aligned samples rather than all minute rows from the 14-day range.

Risk levels are calibrated from the training data instead of fixed demo constants. Queue predictions above the training-set 85th percentile are marked slow, and predictions above the 90th percentile are marked jammed. Intersection identities are never hard-coded into the risk classification.

## Data contract

Training reads `traffic_forecast_observation`. Real collection must write one valid row per intersection and minute using `observation_source='REAL'`. Synthetic rows use the same columns and remain distinguishable with `observation_source='SYNTHETIC'`. When both sources exist at the same intersection and minute, training and inference prefer `REAL`.

Training and data-seeding commands require these environment variables. Serving the committed model does not connect to the database directly:

```text
FORECAST_DB_HOST
FORECAST_DB_PORT
FORECAST_DB_NAME
FORECAST_DB_USERNAME
FORECAST_DB_PASSWORD
```

## Local workflow

Create the local environment and install fixed dependencies. `.venv` is intentionally not committed because it contains machine-specific Python executables:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

Generate deterministic synthetic data only when real history is insufficient. This does not modify the existing `analytics_*` tables:

```powershell
.\.venv\Scripts\python.exe seed_training_data.py --days 60
```

Refresh the database-backed demo stream to the current minute before a live demonstration:

```powershell
.\.venv\Scripts\python.exe seed_training_data.py --days 7
```

Train using a time-ordered 70/15/15 split. Training must load more than 14 days so each sample has a complete two-week history. `--history-days` limits remote transfer without deleting older observations:

```powershell
.\.venv\Scripts\python.exe train_model.py --history-days 28 --stride-minutes 3 --max-rounds 120
```

For real-only acceptance training:

```powershell
.\.venv\Scripts\python.exe train_model.py --real-only --history-days 60
```

Start the service:

```powershell
.\.venv\Scripts\python.exe -m app.server
```

The active model is committed under `models/lgbm-20260713T075131Z`, and `models/current.json` uses a repository-relative path. Verify that the model loaded before starting the backend or frontend:

```powershell
Invoke-RestMethod http://127.0.0.1:17008/health
```

The response must contain `status: ok` and `modelCount: 15`. Merely seeing the server listening message is insufficient because the process remains observable when model loading fails.

Endpoints:

```text
GET  /health
POST /predict
POST /reload
```

The active deployment artifact is committed under `models/`; newly trained versions remain ignored until deliberately selected for release. A successful training run writes its evaluation metrics to `manifest.json`, updates `current.json` with a portable relative path, and registers the active version in `traffic_forecast_model_registry`.
