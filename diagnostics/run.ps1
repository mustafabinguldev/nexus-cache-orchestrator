$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$originalSigningKey = $env:NEXUS_SIGNING_KEY
$originalUnsignedMode = $env:NEXUS_ALLOW_UNSIGNED_MESSAGES
$originalClusterMode = $env:NEXUS_CLUSTER_MODE
$auditResult = 0
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
    Invoke-Logged $mavenPath @('-B','-ntp','-Dmaven.repo.local=target/maven-repository','verify') 'target/build-fix.log'
    Set-Location target/audit
    jar xf ../nexus-cache-orchestrato-1.6.5-boot.jar BOOT-INF/lib
    if ($LASTEXITCODE -ne 0) { throw 'Could not extract runtime dependencies.' }
    $env:NEXUS_SIGNING_KEY = [guid]::NewGuid().ToString('N')
    $env:NEXUS_ALLOW_UNSIGNED_MESSAGES = 'false'
    $env:NEXUS_CLUSTER_MODE = 'false'
    Invoke-Logged 'java' @('-cp','../classes;BOOT-INF/lib/*','../../diagnostics/RuntimeAudit.java') 'runtime-regression.log'
    Invoke-Logged 'java' @('-cp','BOOT-INF/lib/*','../../diagnostics/WebSmokeAudit.java') 'web-regression.log'
    $env:NEXUS_SIGNING_KEY = ''
    Invoke-Logged 'java' @('-cp','../classes;BOOT-INF/lib/*','../../diagnostics/SignaturePolicyAudit.java') 'signature-default.log'
    $env:NEXUS_ALLOW_UNSIGNED_MESSAGES = 'true'
    Invoke-Logged 'java' @('-cp','../classes;BOOT-INF/lib/*','../../diagnostics/SignaturePolicyAudit.java') 'signature-compatibility.log'
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
