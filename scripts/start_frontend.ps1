param([string]$ApiUrl='http://localhost:8080',[string]$SocketUrl='http://localhost:8001')
$ErrorActionPreference='Stop'
$env:VITE_API_BASE_URL=$ApiUrl
$env:VITE_SOCKET_URL=$SocketUrl
Set-Location (Join-Path (Split-Path -Parent $PSScriptRoot) 'frontend')
& npm.cmd run dev
exit $LASTEXITCODE
