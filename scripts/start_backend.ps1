param([string]$TomcatHome=$env:CATALINA_HOME,[string]$BaseDirectory,[int]$HttpPort=8080,[int]$ShutdownPort=8005,[string]$ShutdownCommand='EZAM_LOCAL_SHUTDOWN',[switch]$NoBuild,[switch]$NoEnvFile)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$backendPath=Join-Path $projectRoot 'backend'
# Import whitelisted values as data only; process environment wins. Never print private config.
if (!$NoEnvFile -and (Test-Path -LiteralPath (Join-Path $backendPath '.env'))) {
    $allowed=@('DATABASE_URL','JWT_SECRET_KEY','ACCESS_TOKEN_EXPIRE_MINUTES','GEMINI_API_KEY','GOOGLE_API_KEY','GEMINI_MODEL','CORS_ALLOWED_ORIGINS','SOCKET_HOST','SOCKET_PORT','RUNTIME_DIRECTORY')
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $backendPath '.env'))) {
        if ($line -match '^\s*([A-Z_]+)\s*=\s*(.*?)\s*$' -and $allowed -contains $Matches[1]) {
            $settingName=$Matches[1];$settingValue=$Matches[2]
            if ($settingValue.Length -ge 2 -and (($settingValue.StartsWith('"') -and $settingValue.EndsWith('"')) -or ($settingValue.StartsWith("'") -and $settingValue.EndsWith("'")))) {$settingValue=$settingValue.Substring(1,$settingValue.Length-2)}
            if (![Environment]::GetEnvironmentVariable($settingName,'Process')) {[Environment]::SetEnvironmentVariable($settingName,$settingValue,'Process')}
        }
    }
}
if (!$TomcatHome) {$TomcatHome=Join-Path $projectRoot '.tools/apache-tomcat-9.0.123'}
$TomcatHome=(Resolve-Path -LiteralPath $TomcatHome).Path
if (!(Test-Path -LiteralPath (Join-Path $TomcatHome 'bin/bootstrap.jar'))) {throw 'Set CATALINA_HOME to Apache Tomcat 9.'}
if ((Get-Content -LiteralPath (Join-Path $TomcatHome 'RELEASE-NOTES') -Raw) -notmatch 'Apache Tomcat Version 9\.0\.') {throw 'Only Apache Tomcat 9 is supported.'}
if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) {throw 'Set JAVA_HOME to JDK 17.'}
$version=& (Join-Path $env:JAVA_HOME 'bin/javac.exe') -version
if ($version -notmatch '^javac 17\.') {throw 'Java 17 is required.'}
if (!$env:DATABASE_URL -or !$env:JWT_SECRET_KEY) {throw 'Configure DATABASE_URL and JWT_SECRET_KEY on the backend.'}
if (!$env:SOCKET_HOST) {$env:SOCKET_HOST='0.0.0.0'}
if (!$env:SOCKET_PORT) {$env:SOCKET_PORT='8001'}
if (!$env:RUNTIME_DIRECTORY) {$env:RUNTIME_DIRECTORY=Join-Path $backendPath '.runtime-java'}
if (!$BaseDirectory) {$BaseDirectory=Join-Path $backendPath ('target/local-tomcat-'+[Guid]::NewGuid().ToString('N'))}
if ($HttpPort -lt 1 -or $HttpPort -gt 65535 -or $ShutdownPort -lt 1 -or $ShutdownPort -gt 65535 -or $ShutdownCommand -notmatch '^[A-Za-z0-9_-]+$') {throw 'Invalid Tomcat port/shutdown configuration.'}
if ($env:SOCKET_PORT -notmatch '^\d{1,5}$' -or [int]$env:SOCKET_PORT -lt 1 -or [int]$env:SOCKET_PORT -gt 65535 -or [int]$env:SOCKET_PORT -eq $HttpPort -or [int]$env:SOCKET_PORT -eq $ShutdownPort -or $HttpPort -eq $ShutdownPort) {throw 'HTTP, Socket and shutdown ports must be valid and distinct.'}
if (!$NoBuild) {
    Push-Location $backendPath
    try {
        $buildArgs=@('--batch-mode','--no-transfer-progress','package')
        if ($env:MAVEN_REPO_LOCAL) {$buildArgs=@("-Dmaven.repo.local=$env:MAVEN_REPO_LOCAL")+$buildArgs}
        & (Join-Path $backendPath 'mvnw.cmd') @buildArgs
        if ($LASTEXITCODE -ne 0) {throw 'Maven build failed.'}
    } finally {Pop-Location}
}
$warPath=Join-Path $backendPath 'target/ROOT.war'
if (!(Test-Path -LiteralPath $warPath)) {throw 'Build backend/target/ROOT.war first.'}
foreach ($folder in @('conf','logs','webapps','work','temp')) {New-Item -ItemType Directory -Force -Path (Join-Path $BaseDirectory $folder) | Out-Null}
foreach ($file in @('web.xml','context.xml','catalina.properties','logging.properties')) {Copy-Item -LiteralPath (Join-Path $TomcatHome "conf/$file") -Destination (Join-Path $BaseDirectory "conf/$file") -Force}
$xml="<Server port=`"$ShutdownPort`" address=`"127.0.0.1`" shutdown=`"$ShutdownCommand`"><Service name=`"Catalina`"><Connector address=`"127.0.0.1`" port=`"$HttpPort`" protocol=`"HTTP/1.1`"/><Engine name=`"Catalina`" defaultHost=`"localhost`"><Host name=`"localhost`" appBase=`"webapps`" autoDeploy=`"false`" unpackWARs=`"true`"/></Engine></Service></Server>"
[IO.File]::WriteAllText((Join-Path $BaseDirectory 'conf/server.xml'),$xml,[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath $warPath -Destination (Join-Path $BaseDirectory 'webapps/ROOT.war') -Force
$classPath=(Join-Path $TomcatHome 'bin/bootstrap.jar')+';'+(Join-Path $TomcatHome 'bin/tomcat-juli.jar')
Write-Host "EZam Java HTTP: http://localhost:$HttpPort ; Socket: http://localhost:$env:SOCKET_PORT"
& (Join-Path $env:JAVA_HOME 'bin/java.exe') '--add-opens=java.base/java.lang=ALL-UNNAMED' '--add-opens=java.base/java.io=ALL-UNNAMED' '--add-opens=java.base/java.util=ALL-UNNAMED' '--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED' "-Dcatalina.home=$TomcatHome" "-Dcatalina.base=$BaseDirectory" "-Djava.io.tmpdir=$(Join-Path $BaseDirectory 'temp')" "-Djava.util.logging.config.file=$(Join-Path $BaseDirectory 'conf/logging.properties')" '-cp' $classPath 'org.apache.catalina.startup.Bootstrap' 'start'
exit $LASTEXITCODE
