param(
    [string]$Distro = "",
    [string]$CondaEnv = "traffic-rl",
    [string]$HostAddress = "0.0.0.0",
    [int]$Port = 9000,
    [int]$StartupTimeoutSeconds = 30,
    [switch]$ForceRestart
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

function Escape-BashSingleQuoted {
    param([string]$Value)
    return $Value.Replace("'", "'\''")
}

function Test-WindowsHealth {
    param([string]$HealthUrl)
    try {
        return Invoke-RestMethod -Uri $HealthUrl -TimeoutSec 2
    } catch {
        return $null
    }
}

function Test-WindowsPortListening {
    param([int]$Port)
    try {
        $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        return $null -ne $connections
    } catch {
        return $false
    }
}

function Test-LocalPidFile {
    param([string]$RepoRoot)
    return Test-Path (Join-Path $RepoRoot "sim-python\logs\cityflow-service.pid")
}

function Test-WslHealth {
    param([string]$WslRepoRoot, [int]$HealthPort)
    $probe = "python3 - <<'PY'
import json
import urllib.request
try:
    with urllib.request.urlopen('http://127.0.0.1:$HealthPort/health', timeout=2) as response:
        print(response.read().decode('utf-8'))
except Exception as exc:
    print('__WSL_HEALTH_FAILED__ ' + str(exc))
PY"
    Invoke-Wsl -Arguments @("bash", "-lc", $probe)
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$wslRepoRoot = ConvertTo-WslPath "$repoRoot"
$healthUrl = "http://127.0.0.1:$Port/health"

Write-Host "Checking Python CityFlow service on $healthUrl ..."
$health = Test-WindowsHealth $healthUrl
if ($health -ne $null) {
    if ($health.engineMode -eq "cityflow" -and -not $ForceRestart) {
        Write-Host "Python CityFlow service is already running in cityflow mode."
        $health | ConvertTo-Json -Depth 5
        exit 0
    }
    if ($ForceRestart) {
        Write-Host "ForceRestart enabled. Existing service will be stopped before startup."
    } else {
        Write-Warning "Port $Port is occupied, but engineMode is '$($health.engineMode)'. Stop it before starting cityflow mode."
        exit 1
    }
} else {
    Write-Host "No running service detected on port $Port. Starting WSL CityFlow service ..."
}

if ($ForceRestart) {
    $shouldStop = $health -ne $null `
            -or (Test-WindowsPortListening -Port $Port) `
            -or (Test-LocalPidFile -RepoRoot $repoRoot)

    if (-not $shouldStop) {
        Write-Host "ForceRestart enabled, but no existing service or pid file was detected. Skipping stop step."
    } else {
    $stopArgs = @("-Port", "$Port")
    if (-not [string]::IsNullOrWhiteSpace($Distro)) {
        $stopArgs += @("-Distro", $Distro)
    }
    & (Join-Path $PSScriptRoot "stop-cityflow-wsl.ps1") @stopArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Failed to stop existing CityFlow service on port $Port. Startup aborted to avoid attaching to an old process."
    }

    Start-Sleep -Seconds 1
    $postStopHealth = Test-WindowsHealth $healthUrl
    if ($postStopHealth -ne $null) {
        Write-Error "ForceRestart requested, but an existing CityFlow service is still responding on $healthUrl. Kill the WSL process on port $Port before starting, otherwise the script may attach to the old service."
    }
    }
}

$launcherTemplate = @'
#!/usr/bin/env bash
set -e
REPO_ROOT='__REPO_ROOT__'
CONDA_ENV='__CONDA_ENV__'
HOST_ADDRESS='__HOST_ADDRESS__'
PORT='__PORT__'

source ~/miniconda3/etc/profile.d/conda.sh
conda activate "$CONDA_ENV"
cd "$REPO_ROOT/sim-python"
mkdir -p logs

if command -v lsof >/dev/null 2>&1 && lsof -tiTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Port $PORT is already in use."
  exit 1
elif command -v fuser >/dev/null 2>&1 && fuser "$PORT"/tcp >/dev/null 2>&1; then
  echo "Port $PORT is already in use."
  exit 1
elif command -v ss >/dev/null 2>&1 && ss -ltn "sport = :$PORT" | grep -q ":$PORT"; then
  echo "Port $PORT is already in use."
  exit 1
fi

: > logs/cityflow-service.log
echo $$ > logs/cityflow-service.pid
exec env SIM_ENGINE_MODE=cityflow python -u app/server.py --host "$HOST_ADDRESS" --port "$PORT" >> logs/cityflow-service.log 2>&1
'@

$logsDir = Join-Path $repoRoot "sim-python\logs"
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
$launcherWindowsPath = Join-Path $logsDir "start-cityflow-service.sh"
$launcher = $launcherTemplate.Replace("__REPO_ROOT__", (Escape-BashSingleQuoted $wslRepoRoot))
$launcher = $launcher.Replace("__CONDA_ENV__", (Escape-BashSingleQuoted $CondaEnv))
$launcher = $launcher.Replace("__HOST_ADDRESS__", (Escape-BashSingleQuoted $HostAddress))
$launcher = $launcher.Replace("__PORT__", "$Port")
$launcher = $launcher.Replace("`r`n", "`n")
[System.IO.File]::WriteAllText($launcherWindowsPath, $launcher, [System.Text.UTF8Encoding]::new($false))
$wslLauncherPath = ConvertTo-WslPath "$launcherWindowsPath"

$wslArguments = if ([string]::IsNullOrWhiteSpace($Distro)) {
    @("--", "bash", $wslLauncherPath)
} else {
    @("-d", $Distro, "--", "bash", $wslLauncherPath)
}

$startRequestedAt = Get-Date
Start-Process -FilePath "wsl.exe" -ArgumentList $wslArguments -WindowStyle Hidden

$deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
$lastError = $null
while ((Get-Date) -lt $deadline) {
    try {
        $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3
        if ($health.engineMode -ne "cityflow") {
            throw "Service started but engineMode is '$($health.engineMode)'."
        }
        $pidFilePath = Join-Path $logsDir "cityflow-service.pid"
        if (-not (Test-Path $pidFilePath)) {
            throw "Service is healthy but pid file was not created by the new launcher."
        }
        $pidFile = Get-Item $pidFilePath
        if ($pidFile.LastWriteTime -lt $startRequestedAt.AddSeconds(-2)) {
            throw "Service is healthy, but pid file is older than this startup attempt. Refusing to attach to a stale process."
        }
        Write-Host "Python CityFlow service started successfully."
        $health | ConvertTo-Json -Depth 5
        exit 0
    } catch {
        $lastError = $_
        Start-Sleep -Seconds 1
    }
}

Write-Host "Python CityFlow service did not become healthy within $StartupTimeoutSeconds seconds."
Write-Host "Last health check error: $lastError"

Write-Host "WSL-side health check:"
Test-WslHealth -WslRepoRoot $wslRepoRoot -HealthPort $Port

Write-Host "Last 80 lines of WSL service log:"
Invoke-Wsl -Arguments @("bash", "-lc", "cd '$wslRepoRoot/sim-python' && if [ -f logs/cityflow-service.log ]; then tail -n 80 logs/cityflow-service.log; else echo 'logs/cityflow-service.log not found'; fi")
Write-Error "Python CityFlow service did not become healthy from Windows. If WSL-side health is UP, restart WSL with 'wsl --shutdown' and retry, or configure Spring Boot CITYFLOW_BASE_URL to the WSL IP."
