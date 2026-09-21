$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$originalSigningKey = $env:NEXUS_SIGNING_KEY
$originalUnsignedMode = $env:NEXUS_ALLOW_UNSIGNED_MESSAGES
$originalClusterMode = $env:NEXUS_CLUSTER_MODE
$auditResult = 0
$javaCommand = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$jarCommand = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/jar.exe' } else { 'jar' }
$containersStarted = $false
function Invoke-Logged {
    param([string]$Executable, [string[]]$Arguments, [string]$Log)
    # Windows PowerShell wraps native stderr in ErrorRecord even for successful Java runs.
    $ErrorActionPreference = 'Continue'
    & $Executable @Arguments *> $Log
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed (exit $LASTEXITCODE); see $Log." }
}
Push-Location $projectRoot
try {
    $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    $mavenPath = if ($mavenCommand) { $mavenCommand.Source } else { Join-Path $projectRoot 'target/tools/apache-maven-3.9.16/bin/mvn.cmd' }
    if (-not (Test-Path -LiteralPath $mavenPath)) { throw 'Install Maven or restore the portable Maven installation first.' }
    docker start nexus-audit-redis nexus-audit-mongo
    if ($LASTEXITCODE -ne 0) { throw 'Create the dedicated test containers as described in README.md first.' }
    $containersStarted = $true
    New-Item -ItemType Directory -Force target/audit | Out-Null
    Invoke-Logged $mavenPath @('-B','-ntp','verify') 'target/build-fix.log'
    [xml]$projectPom = Get-Content (Join-Path $projectRoot 'pom.xml')
    $bootJar = Join-Path $projectRoot ('target/{0}-{1}-boot.jar' -f $projectPom.project.artifactId, $projectPom.project.version)
    Set-Location target/audit
    & $jarCommand xf $bootJar BOOT-INF/lib
    if ($LASTEXITCODE -ne 0) { throw 'Could not extract runtime dependencies.' }
    $env:NEXUS_SIGNING_KEY = [guid]::NewGuid().ToString('N')
    $env:NEXUS_ALLOW_UNSIGNED_MESSAGES = 'false'
    $env:NEXUS_CLUSTER_MODE = 'false'
    Invoke-Logged $javaCommand @('-cp','../classes;BOOT-INF/lib/*','../../diagnostics/RuntimeAudit.java') 'runtime-regression.log'
    Invoke-Logged $javaCommand @('-cp','BOOT-INF/lib/*','../../diagnostics/WebSmokeAudit.java',$bootJar) 'web-regression.log'
    $env:NEXUS_SIGNING_KEY = ''
    Invoke-Logged $javaCommand @('-cp','../classes;BOOT-INF/lib/*','../../diagnostics/SignaturePolicyAudit.java') 'signature-default.log'
    $env:NEXUS_ALLOW_UNSIGNED_MESSAGES = 'true'
    Invoke-Logged $javaCommand @('-cp','../classes;BOOT-INF/lib/*','../../diagnostics/SignaturePolicyAudit.java') 'signature-compatibility.log'
    Select-String -Path runtime-regression.log,web-regression.log,signature-default.log,signature-compatibility.log -Pattern '^(AUDIT|WEB|SECURITY) '
} catch {
    Write-Host $_ -ForegroundColor Red
    $auditResult = 1
} finally {
    $env:NEXUS_SIGNING_KEY = $originalSigningKey
    $env:NEXUS_ALLOW_UNSIGNED_MESSAGES = $originalUnsignedMode
    $env:NEXUS_CLUSTER_MODE = $originalClusterMode
    if ($containersStarted) { docker stop nexus-audit-redis nexus-audit-mongo }
    Pop-Location
}
exit $auditResult
