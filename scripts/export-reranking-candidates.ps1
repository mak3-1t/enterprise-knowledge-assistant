[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string] $AppBaseUrl = 'http://localhost:8080',

    [ValidateNotNullOrEmpty()]
    [string] $OutputPath = '.\target\sprint-04-reranker-candidates.jsonl'
)

$ErrorActionPreference = 'Stop'
$appUri = [Uri]::new($AppBaseUrl)
if ($appUri.Scheme -ne 'http' -or $appUri.Host -notin @('localhost', '127.0.0.1') -or $appUri.UserInfo) {
    throw 'AppBaseUrl must be local http://localhost or http://127.0.0.1.'
}
$baseUrl = $appUri.AbsoluteUri.TrimEnd('/')
$fixtureRoot = Join-Path $PSScriptRoot '..\docs\fixtures\sprint-04-reranking'
$queries = @(
    Get-Content -Encoding UTF8 (Join-Path $fixtureRoot 'queries.jsonl') |
        Where-Object { $_.Trim() } |
        ForEach-Object { ConvertFrom-Json -InputObject $_ }
)
$corpus = @(
    Get-Content -Encoding UTF8 (Join-Path $fixtureRoot 'corpus.jsonl') |
        Where-Object { $_.Trim() } |
        ForEach-Object { ConvertFrom-Json -InputObject $_ }
)
$docsById = @{}
foreach ($doc in $corpus) { $docsById[$doc.doc_id] = $doc }
$fixtureTenants = @($queries.tenant_id | Select-Object -Unique)
$tokens = @{}
$lines = [System.Collections.Generic.List[string]]::new()
$password = $null
$securePassword = $null
$loginJson = $null
$loginBody = $null
$requestJson = $null
$requestBody = $null
$response = $null

try {
    $health = Invoke-RestMethod -Method Get -Uri "$baseUrl/actuator/health" -TimeoutSec 5
    if ($health.status -ne 'UP') { throw 'EKA application health is not UP.' }

    foreach ($fixtureTenant in $fixtureTenants) {
        $tenantSlug = Read-Host "Local EKA tenant slug for fixture $fixtureTenant"
        $email = Read-Host "ADMIN email for $tenantSlug"
        $securePassword = Read-Host "ADMIN password for $tenantSlug" -AsSecureString
        $password = [Net.NetworkCredential]::new('', $securePassword).Password
        $securePassword.Dispose()
        $securePassword = $null
        $loginJson = @{
            tenantSlug = $tenantSlug
            email = $email
            password = $password
        } | ConvertTo-Json -Compress
        $loginBody = [Text.Encoding]::UTF8.GetBytes($loginJson)
        $session = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" `
            -ContentType 'application/json; charset=utf-8' -Body $loginBody -TimeoutSec 30
        if (-not $session.accessToken) { throw "Login failed for tenant $tenantSlug." }
        $tokens[$fixtureTenant] = $session.accessToken
        $password = $null
        $securePassword = $null
        $loginJson = $null
        $loginBody = $null
        $session = $null
    }

    foreach ($query in $queries) {
        $headers = @{ Authorization = "Bearer $($tokens[$query.tenant_id])" }
        $requestJson = @{ question = $query.query; limit = 10 } | ConvertTo-Json -Compress
        $requestBody = [Text.Encoding]::UTF8.GetBytes($requestJson)
        $response = Invoke-RestMethod -Method Post `
            -Uri "$baseUrl/api/evaluation/retrieval" -Headers $headers `
            -ContentType 'application/json; charset=utf-8' -Body $requestBody -TimeoutSec 60
        $candidates = [System.Collections.Generic.List[object]]::new()
        $seen = @{}
        foreach ($candidate in @($response.candidates)) {
            $docId = [IO.Path]::GetFileNameWithoutExtension([string]$candidate.filename)
            if (-not $docsById.ContainsKey($docId) -or $docsById[$docId].tenant_id -ne $query.tenant_id) {
                throw "Unexpected candidate filename returned for query $($query.query_id)."
            }
            # Retrieval returns chunks; the labeled fixture judges documents.
            if ($seen.ContainsKey($docId)) { continue }
            $seen[$docId] = $true
            $candidates.Add(@{
                doc_id = $docId
                tenant_id = $query.tenant_id
                text = [string]$candidate.text
            })
        }
        $lines.Add((@{ query_id = $query.query_id; candidates = @($candidates) } | ConvertTo-Json -Compress -Depth 6))
        $headers = $null
        $requestJson = $null
        $requestBody = $null
        $response = $null
    }

    $fullOutputPath = [IO.Path]::GetFullPath($OutputPath)
    $parent = Split-Path -Parent $fullOutputPath
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    [IO.File]::WriteAllLines($fullOutputPath, $lines, [Text.UTF8Encoding]::new($false))
    Write-Output "PASS: Exported actual tenant-scoped EKA candidate lists for $($queries.Count) synthetic queries."
    Write-Output "Candidate JSONL: $fullOutputPath"
    Write-Output 'No tokens, passwords, or document text were printed.'
} finally {
    $tokens.Clear()
    $password = $null
    if ($null -ne $securePassword) { $securePassword.Dispose() }
    $securePassword = $null
    $loginJson = $null
    $loginBody = $null
    $requestJson = $null
    $requestBody = $null
    $response = $null
}
