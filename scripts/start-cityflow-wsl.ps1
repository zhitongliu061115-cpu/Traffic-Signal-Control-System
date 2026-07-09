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
        & wsl.exe @wslArgs
    } else {
        $wslArgs = @("-d", $Distro, "--") + $Arguments
        & wsl.exe @wslArgs
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

$distroLabel = if ([string]::IsNullOrWhiteSpace($Distro)) { "default" } else { $Distro }
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$healthUrl = "http://127.0.0.1:$Port/health"

Write-Host "Checking Python CityFlow service on $healthUrl ..."
try {
    $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 2
    if ($health.engineMode -eq "cityflow" -and -not $ForceRestart) {
        Write-Host "Python CityFlow service is already running in cityflow mode."
        $health | ConvertTo-Json -Depth 5
        exit 0
    }
    if ($ForceRestart) {
        Write-Host "ForceRestart enabled. Existing service will be stopped before startup."
    }
    Write-Warning "Port $Port is occupied, but engineMode is '$($health.engineMode)'. Stop it before starting cityflow mode."
    if (-not $ForceRestart) {
        exit 1
    }
} catch {
    Write-Host "No running service detected on port $Port. Starting WSL CityFlow service ..."
}

try {
    $wslRepoRoot = ConvertTo-WslPath "$repoRoot"
} catch {
    Write-Error "Failed to convert repository path to WSL path: $_"
    throw
}

$bash = @"
set -e
source ~/miniconda3/etc/profile.d/conda.sh
conda activate $CondaEnv
cd '$wslRepoRoot/sim-python'
mkdir -p logs
if [ "$ForceRestart" = "True" ]; then
  if [ -f logs/cityflow-service.pid ]; then
    OLD_PID=`$(cat logs/cityflow-service.pid)
    if kill -0 `$OLD_PID >/dev/null 2>&1; then
      kill `$OLD_PID || true
      sleep 1
    fi
    rm -f logs/cityflow-service.pid
  fi
  if command -v lsof >/dev/null 2>&1; then
    OLD_PORT_PIDS=`$(lsof -ti :$Port || true)
    if [ -n "`$OLD_PORT_PIDS" ]; then
      kill `$OLD_PORT_PIDS || true
      sleep 1
    fi
  fi
fi
if command -v lsof >/dev/null 2>&1 && lsof -i :$Port >/dev/null 2>&1; then
  echo 'Port $Port is already in use.'
  exit 1
fi
: > logs/cityflow-service.log
echo `$$ > logs/cityflow-service.pid
exec env SIM_ENGINE_MODE=cityflow python -u app/server.py --host $HostAddress --port $Port >> logs/cityflow-service.log 2>&1
"@

$wslArguments = if ([string]::IsNullOrWhiteSpace($Distro)) {
    @("--", "bash", "-lc", $bash)
} else {
    @("-d", $Distro, "--", "bash", "-lc", $bash)
}

Start-Process -FilePath "wsl.exe" -ArgumentList $wslArguments -WindowStyle Hidden

$deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
$lastError = $null
while ((Get-Date) -lt $deadline) {
    try {
        $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3
        if ($health.engineMode -ne "cityflow") {
            throw "Service started but engineMode is '$($health.engineMode)'."
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
Write-Host "Last 80 lines of WSL service log:"
Invoke-Wsl -Arguments @("bash", "-lc", "cd '$wslRepoRoot/sim-python' && if [ -f logs/cityflow-service.log ]; then tail -n 80 logs/cityflow-service.log; else echo 'logs/cityflow-service.log not found'; fi")
Write-Error "Python CityFlow service did not become healthy. See the log above."
