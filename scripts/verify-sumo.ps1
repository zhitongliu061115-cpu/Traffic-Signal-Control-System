param(
    [string]$SumoHome = 'D:\Download\SUMO'
)

$ErrorActionPreference = 'Stop'

$env:SUMO_HOME = $SumoHome
$env:SUMO_BINARY = Join-Path $SumoHome 'bin\sumo.exe'
$env:SUMO_GUI_BINARY = Join-Path $SumoHome 'bin\sumo-gui.exe'
$env:SUMO_NETCONVERT_BINARY = Join-Path $SumoHome 'bin\netconvert.exe'
$env:SUMO_STEP_LENGTH = if ($env:SUMO_STEP_LENGTH) { $env:SUMO_STEP_LENGTH } else { '0.2' }

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $repoRoot 'sim-python')
try {
    python scripts\verify_sumo_setup.py
} finally {
    Pop-Location
}
