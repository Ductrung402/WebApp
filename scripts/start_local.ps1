$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$logsPath=Join-Path $projectRoot 'backend/target/local-logs'
New-Item -ItemType Directory -Force -Path $logsPath | Out-Null
$backendProcess=$null;$frontendProcess=$null
$shutdownCommand='ezam-'+[Guid]::NewGuid().ToString('N')
$env:SOCKET_PORT='8001'
foreach ($localPort in @(8080,8001,8005,5173)) {
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$localPort)
    try {$listener.Start()} finally {$listener.Stop()}
}
try {
    $backendProcess=Start-Process powershell.exe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+(Join-Path $PSScriptRoot 'start_backend.ps1')+'"'),'-ShutdownCommand',$shutdownCommand) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logsPath 'backend.out.log') -RedirectStandardError (Join-Path $logsPath 'backend.err.log')
    $frontendProcess=Start-Process powershell.exe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+(Join-Path $PSScriptRoot 'start_frontend.ps1')+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logsPath 'frontend.out.log') -RedirectStandardError (Join-Path $logsPath 'frontend.err.log')
    Write-Host 'EZam: frontend http://localhost:5173 ; API http://localhost:8080 ; Socket http://localhost:8001'
    Write-Host 'Keep this console open. Ctrl+C stops both child process trees. Logs: backend/target/local-logs.'
    while (!$backendProcess.HasExited -and !$frontendProcess.HasExited) {Start-Sleep -Seconds 1}
    throw 'A local process stopped. Inspect the local logs.'
} finally {
    if ($backendProcess -and !$backendProcess.HasExited) {
        try {$connection=[Net.Sockets.TcpClient]::new('127.0.0.1',8005);$bytes=[Text.Encoding]::ASCII.GetBytes($shutdownCommand);$connection.GetStream().Write($bytes,0,$bytes.Length);$connection.Dispose();$backendProcess.WaitForExit(20000) | Out-Null} catch {Write-Host 'Graceful backend stop unavailable.'}
    }
    # Only process handles created above; never kill another user's Java/Node process by name.
    foreach ($childProcess in @($frontendProcess,$backendProcess)) {if ($null -ne $childProcess -and !$childProcess.HasExited) {& taskkill.exe /PID $childProcess.Id /T /F | Out-Null}}
}
