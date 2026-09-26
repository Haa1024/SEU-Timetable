param([int]$Port = 4173)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
try { $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 2 }
catch { Write-Output 'No preview service is running on this port.'; exit 0 }
if ($health.service -ne 'seu-timetable-preview' -or $health.root.TrimEnd('\','/') -ne $projectRoot.TrimEnd('\','/')) { throw 'This service belongs to another project; it will not be stopped.' }
$process = Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$health.pid)"
$expected = Join-Path $projectRoot 'preview/server.mjs'
if (-not $process -or $process.Name -ne 'node.exe' -or -not $process.CommandLine.Contains($expected)) { throw 'The process does not match this preview server; it will not be stopped.' }
Stop-Process -Id $health.pid
Write-Output 'Preview stopped.'
