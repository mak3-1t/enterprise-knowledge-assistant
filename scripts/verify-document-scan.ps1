#requires -Version 5.1
[CmdletBinding()]
param([string]$LogPath = 'target/document-scan-verify.log')

$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$savedEnvironment = @{}
foreach ($name in @('EKA_CLAMAV_TESTS', 'EKA_CLAMAV_HOST', 'EKA_CLAMAV_PORT')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

Push-Location -LiteralPath $repository
try {
    if (-not (Test-Path -LiteralPath '.env')) {
        throw 'Missing local .env for the existing development Compose configuration.'
    }
    docker compose --env-file .env -f deploy/docker/docker-compose.yml --profile malware up -d --wait --wait-timeout 600 clamav
    if ($LASTEXITCODE -ne 0) {
        throw 'ClamAV did not become healthy. Check Docker and the clamav service logs before running verification.'
    }
    $env:EKA_CLAMAV_TESTS = 'true'
    $env:EKA_CLAMAV_HOST = 'localhost'
    $env:EKA_CLAMAV_PORT = '3310'
    New-Item -ItemType Directory -Force -Path target | Out-Null
    # Windows PowerShell 5 can turn harmless native stderr warnings into errors.
    $ErrorActionPreference = 'Continue'
    & .\mvnw.cmd -o -B verify 2>&1 | Tee-Object -FilePath $LogPath -ErrorAction Stop
    $verifyExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($verifyExit -ne 0) {
        throw "Verification failed. See $LogPath and Surefire reports."
    }
    Write-Host 'Full verification completed, including the live ClamAV smoke.'
}
finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    Pop-Location
}
