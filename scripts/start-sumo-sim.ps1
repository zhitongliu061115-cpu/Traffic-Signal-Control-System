param(
    [string]$SumoHome = 'D:\Download\SUMO',
    [string]$PythonExecutable = 'python.exe',
    [string]$ListenHost = '127.0.0.1',
    [int]$Port = 9001
)

$ErrorActionPreference = 'Stop'

$env:SIM_ENGINE_MODE = 'sumo'
$env:SIM_DEFAULT_SCENE_ID = 'xian_5x5'
$env:SUMO_HOME = $SumoHome
$env:SUMO_BINARY = Join-Path $SumoHome 'bin\sumo.exe'
$env:SUMO_GUI_BINARY = Join-Path $SumoHome 'bin\sumo-gui.exe'
$env:SUMO_NETCONVERT_BINARY = Join-Path $SumoHome 'bin\netconvert.exe'
$env:SUMO_STEP_LENGTH = if ($env:SUMO_STEP_LENGTH) { $env:SUMO_STEP_LENGTH } else { '0.2' }

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $repoRoot 'sim-python')
try {
    & $PythonExecutable app\server.py --host $ListenHost --port $Port
} finally {
    Pop-Location
}
