param(
    [string]$Distro = "",
    [int]$Port = 9000
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
try {
    $wslRepoRoot = ConvertTo-WslPath "$repoRoot"
} catch {
    Write-Error "Failed to convert repository path to WSL path: $_"
    throw
}

$bashTemplate = @'
set -e
REPO_ROOT='__REPO_ROOT__'
PORT='__PORT__'
cd "$REPO_ROOT/sim-python"
if [ -f logs/cityflow-service.pid ]; then
  PID="$(cat logs/cityflow-service.pid || true)"
  if [ -n "$PID" ] && kill -0 "$PID" >/dev/null 2>&1; then
    kill "$PID"
    echo "Stopped Python CityFlow service pid $PID."
  else
    echo "PID file exists, but process is not running."
  fi
  rm -f logs/cityflow-service.pid
else
  echo "No PID file found."
fi

echo "Trying to stop process by port $PORT."
if command -v lsof >/dev/null 2>&1; then
  PID="$(lsof -ti :"$PORT" || true)"
  if [ -n "$PID" ]; then
    kill $PID
    echo "Stopped process on port ${PORT}: $PID."
  else
    echo "No process found on port $PORT."
  fi
else
  echo "lsof is unavailable."
fi
'@

$bash = $bashTemplate.Replace("__REPO_ROOT__", $wslRepoRoot).Replace("__PORT__", "$Port")
Invoke-Wsl -Arguments @("bash", "-lc", $bash)
