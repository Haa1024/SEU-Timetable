param([int]$Port = 4173, [switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$url = "http://127.0.0.1:$Port"
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
if (-not $nodeCommand) { throw 'Node.js 20 or newer is required. Install Node.js, then run this launcher again.' }
$version = & $nodeCommand.Source --version
if ([int]($version.TrimStart('v').Split('.')[0]) -lt 20) { throw 'Node.js 20 or newer is required.' }
$previewDir = Join-Path $projectRoot 'preview'
if (-not (Test-Path -LiteralPath (Join-Path $previewDir 'node_modules/marked/lib/marked.esm.js')) -or -not (Test-Path -LiteralPath (Join-Path $previewDir 'node_modules/dompurify/dist/purify.es.mjs'))) {
    $npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if (-not $npmCommand) { throw 'npm is required to install the small Markdown rendering dependencies.' }
    Push-Location $previewDir
    try {
        & $npmCommand.Source ci --ignore-scripts --no-fund --no-audit
        if ($LASTEXITCODE -ne 0) { throw 'Preview dependency installation failed. Check the network and retry.' }
    } finally { Pop-Location }
}

function Get-PreviewHealth {
    try { return Invoke-RestMethod -Uri "$url/api/health" -TimeoutSec 2 } catch { return $null }
}
$health = Get-PreviewHealth
if ($health -and ($health.service -ne 'seu-timetable-preview' -or $health.root.TrimEnd('\','/') -ne $projectRoot.TrimEnd('\','/'))) {
    throw "Port $Port is used by another service or project. Run this script with -Port 4174."
}
if (-not $health) {
    $runtimeDir = Join-Path $projectRoot 'preview/.runtime'
    New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
    $server = Join-Path $projectRoot 'preview/server.mjs'
    $oldPort = $env:PREVIEW_PORT
    try {
        $env:PREVIEW_PORT = [string]$Port
        $process = Start-Process -FilePath $nodeCommand.Source -ArgumentList ('"{0}"' -f $server) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir "server-$Port.log") -RedirectStandardError (Join-Path $runtimeDir "server-$Port.error.log")
    } finally { $env:PREVIEW_PORT = $oldPort }
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        Start-Sleep -Milliseconds 300
        $health = Get-PreviewHealth
        if ($health) { break }
        if ($process.HasExited) { break }
    }
    if (-not $health -or $health.service -ne 'seu-timetable-preview' -or $health.root.TrimEnd('\','/') -ne $projectRoot.TrimEnd('\','/')) {
        $log = Join-Path $runtimeDir "server-$Port.error.log"
        if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log }
        throw "Preview did not start. Check whether port $Port is occupied."
    }
}
Write-Output "Preview running at $url (PID $($health.pid))"
if (-not $NoBrowser) { Start-Process $url }
