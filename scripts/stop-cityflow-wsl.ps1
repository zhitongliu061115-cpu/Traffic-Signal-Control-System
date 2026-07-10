param(
    [string]$Distro = "",
    [int]$Port = 9000
)

$ErrorActionPreference = "Stop"

function Invoke-Wsl {
    param([string[]]$Arguments)

    if ([string]::IsNullOrWhiteSpace($Distro)) {
        $wslArgs = @("--") + $Arguments
    } else {
        $wslArgs = @("-d", $Distro, "--") + $Arguments
    }
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        & wsl.exe @wslArgs
        if ($LASTEXITCODE -eq 0) {
            return
        }
        if ($attempt -lt 5) {
            Write-Warning "wsl.exe failed with exit code $LASTEXITCODE on attempt $attempt/5. Retrying after WSL startup settles..."
            Start-Sleep -Seconds 2
        }
    }
}

function ConvertTo-WslPath {
    param([string]$WindowsPath)

    $fullPath = [System.IO.Path]::GetFullPath($WindowsPath)
    if ($fullPath -notmatch "^([A-Za-z]):\\(.*)$") {
        throw "Only drive-letter paths are supported: $fullPath"
    }

    $drive = $Matches[1].ToLowerInvariant()
    $rest = $Matches[2].Replace("\", "/")
    return "/mnt/$drive/$rest"
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$wslRepoRoot = ConvertTo-WslPath "$repoRoot"

$bash = @'
set -u
REPO_ROOT="$1"
PORT="$2"
SERVICE_DIR="$REPO_ROOT/sim-python"

echo "Stopping Python CityFlow service on port $PORT ..."

collect_pids() {
  {
    if [ -f "$SERVICE_DIR/logs/cityflow-service.pid" ]; then
      cat "$SERVICE_DIR/logs/cityflow-service.pid" 2>/dev/null || true
    fi

    if command -v lsof >/dev/null 2>&1; then
      lsof -nP -t -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true
    fi

    if command -v fuser >/dev/null 2>&1; then
      fuser -n tcp "$PORT" 2>/dev/null || true
      fuser "$PORT"/tcp 2>/dev/null || true
    fi

    if command -v ss >/dev/null 2>&1; then
      ss -ltnp "sport = :$PORT" 2>/dev/null | sed -n 's/.*pid=\([0-9]\+\).*/\1/p' || true
    fi

    ps -eo pid=,args= | awk -v port="$PORT" '
      /python/ && /app\/server.py/ {
        for (i = 1; i <= NF; i++) {
          if ($i == "--port" && (i + 1) <= NF && $(i + 1) == port) {
            print $1
          }
        }
      }
    '
  } | tr ' ' '\n' | sed '/^[[:space:]]*$/d' | sort -u
}

kill_pids() {
  PIDS="$1"
  if [ -z "$PIDS" ]; then
    return 0
  fi

  echo "Stopping process(es): $PIDS"
  kill $PIDS >/dev/null 2>&1 || true
  sleep 1
  for PID in $PIDS; do
    if kill -0 "$PID" >/dev/null 2>&1; then
      kill -9 "$PID" >/dev/null 2>&1 || true
    fi
  done
}

port_is_listening() {
  if command -v lsof >/dev/null 2>&1 && lsof -nP -t -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    return 0
  fi
  if command -v fuser >/dev/null 2>&1 && fuser -n tcp "$PORT" >/dev/null 2>&1; then
    return 0
  fi
  if command -v ss >/dev/null 2>&1 && ss -ltn "sport = :$PORT" 2>/dev/null | grep -q ":$PORT"; then
    return 0
  fi
  return 1
}

if [ -d "$SERVICE_DIR" ]; then
  cd "$SERVICE_DIR"
else
  echo "Service directory not found: $SERVICE_DIR"
fi

PIDS="$(collect_pids)"
if [ -z "$PIDS" ]; then
  echo "No CityFlow process found by pid file, port, or command line."
else
  kill_pids "$PIDS"
fi

rm -f "$SERVICE_DIR/logs/cityflow-service.pid"

for ATTEMPT in 1 2 3 4 5; do
  if ! port_is_listening; then
    echo "Port $PORT is free."
    exit 0
  fi
  PIDS="$(collect_pids)"
  if [ -n "$PIDS" ]; then
    echo "Port $PORT is still busy after stop attempt $ATTEMPT."
    kill_pids "$PIDS"
  fi
  sleep 1
done

echo "Failed to stop CityFlow service: port $PORT is still listening."
if command -v ss >/dev/null 2>&1; then
  ss -ltnp "sport = :$PORT" 2>/dev/null || true
fi
exit 1
'@

$logsDir = Join-Path $repoRoot "sim-python\logs"
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
$stopScriptWindowsPath = Join-Path $logsDir "stop-cityflow-service.sh"
$stopScript = $bash.Replace("`r`n", "`n")
[System.IO.File]::WriteAllText($stopScriptWindowsPath, $stopScript, [System.Text.UTF8Encoding]::new($false))
$wslStopScriptPath = ConvertTo-WslPath "$stopScriptWindowsPath"

Invoke-Wsl -Arguments @("bash", $wslStopScriptPath, $wslRepoRoot, "$Port")
if ($LASTEXITCODE -ne 0) {
    throw "Failed to stop Python CityFlow service in WSL. wsl.exe exit code: $LASTEXITCODE"
}
