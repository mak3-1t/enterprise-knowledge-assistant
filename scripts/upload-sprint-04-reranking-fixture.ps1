[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string] $AppBaseUrl = 'http://127.0.0.1:8080',

    [ValidateRange(30, 3600)]
    [int] $ReadyTimeoutSeconds = 900,

    [ValidateRange(1, 30)]
    [int] $PollIntervalSeconds = 5
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$appUri = [Uri]::new($AppBaseUrl)
if ($appUri.Scheme -ne 'http' -or $appUri.Host -notin @('localhost', '127.0.0.1') -or $appUri.UserInfo) {
    throw 'AppBaseUrl must be local http://localhost or http://127.0.0.1.'
}
$baseUrl = $appUri.AbsoluteUri.TrimEnd('/')
$fixtureRoot = Join-Path $PSScriptRoot '..\docs\fixtures\sprint-04-reranking'
$corpus = @(
    Get-Content -Encoding UTF8 (Join-Path $fixtureRoot 'corpus.jsonl') |
        Where-Object { $_.Trim() } |
        ForEach-Object { ConvertFrom-Json -InputObject $_ }
)
$fixtures = @($corpus | Group-Object tenant_id)
$client = [System.Net.Http.HttpClient]::new()
$utf8 = [Text.Encoding]::UTF8
$uploaded = 0

function Invoke-EkaJson {
    param(
        [Parameter(Mandatory)][string] $Method,
        [Parameter(Mandatory)][string] $Path,
        $Value,
        [string] $Token,
        [hashtable] $Headers = @{}
    )

    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$baseUrl$Path")
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    }
    foreach ($header in $Headers.GetEnumerator()) { $null = $request.Headers.TryAddWithoutValidation($header.Key, [string]$header.Value) }
    if ($PSBoundParameters.ContainsKey('Value')) {
        $request.Content = [System.Net.Http.StringContent]::new(($Value | ConvertTo-Json -Compress -Depth 8), $utf8, 'application/json')
    }
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "EKA request failed (HTTP $([int]$response.StatusCode)) for $Method $Path."
        }
        if ([string]::IsNullOrWhiteSpace($body)) { return $null }
        return ConvertFrom-Json -InputObject $body
    } finally {
        $request.Dispose()
        if ($null -ne $response) { $response.Dispose() }
    }
}

function Send-FixtureFile {
    param(
        [Parameter(Mandatory)][string] $Filename,
        [Parameter(Mandatory)][string] $Text,
        [Parameter(Mandatory)][string] $Token
    )

    $bytes = $utf8.GetBytes($Text)
    if ($bytes.Length -lt 1 -or $bytes.Length -gt 20MB) { throw 'Fixture file size is outside EKA upload limits.' }
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { $sha256 = -join ($algorithm.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) }
    finally { $algorithm.Dispose() }
    $intent = Invoke-EkaJson -Method 'POST' -Path '/api/documents/uploads' -Token $Token `
        -Headers @{ 'Idempotency-Key' = [Guid]::NewGuid().ToString() } -Value @{
        filename = $Filename
        sizeBytes = $bytes.Length
        sha256 = $sha256
    }
    if (-not $intent.document.id -or -not $intent.upload.url -or -not $intent.upload.fields) {
        throw 'EKA returned an incomplete upload intent.'
    }

    $form = [System.Net.Http.MultipartFormDataContent]::new()
    try {
        foreach ($field in $intent.upload.fields.PSObject.Properties) {
            $form.Add([System.Net.Http.StringContent]::new([string]$field.Value, $utf8), [string]$field.Name)
        }
        $fileContent = [System.Net.Http.ByteArrayContent]::new($bytes)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('text/markdown')
        $form.Add($fileContent, 'file', $Filename)
        $response = $client.PostAsync([string]$intent.upload.url, $form).GetAwaiter().GetResult()
        try {
            if (-not $response.IsSuccessStatusCode) {
                throw "Fixture object upload failed (HTTP $([int]$response.StatusCode)); signed URL omitted."
            }
        } finally { $response.Dispose() }
    } finally { $form.Dispose() }

    $documentId = [string]$intent.document.id
    $null = Invoke-EkaJson -Method 'POST' -Path "/api/documents/$documentId/complete" -Token $Token -Value @{}
    $deadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
    do {
        Start-Sleep -Seconds $PollIntervalSeconds
        $detail = Invoke-EkaJson -Method 'GET' -Path "/api/documents/$documentId" -Token $Token
        if ($detail.status -eq 'READY') { return }
        if ($detail.status -in @('FAILED', 'REJECTED', 'EXPIRED', 'DELETED')) {
            throw "Fixture upload did not index successfully (state $($detail.status), code $($detail.errorCode))."
        }
    } while ([DateTime]::UtcNow -lt $deadline)
        throw "Timed out waiting for fixture $Filename to reach READY."
}

try {
    $health = Invoke-RestMethod -Method Get -Uri "$baseUrl/actuator/health" -TimeoutSec 5
    if ($health.status -ne 'UP') { throw 'EKA application health is not UP.' }
    foreach ($fixture in $fixtures) {
        $fixtureTenant = [string]$fixture.Name
        $tenantSlug = Read-Host "Local EKA tenant slug for fixture $fixtureTenant"
        $email = Read-Host "ADMIN email for $tenantSlug"
        $securePassword = Read-Host "ADMIN password for $tenantSlug" -AsSecureString
        $password = [Net.NetworkCredential]::new('', $securePassword).Password
        $securePassword.Dispose()
        $securePassword = $null
        $token = $null
        try {
            $login = Invoke-EkaJson -Method 'POST' -Path '/api/auth/login' -Token '' -Value @{
                tenantSlug = $tenantSlug
                email = $email
                password = $password
            }
            $token = [string]$login.accessToken
            if ([string]::IsNullOrWhiteSpace($token)) { throw "Login failed for tenant $tenantSlug." }
            $password = $null
            $files = @($fixture.Group)
            foreach ($document in $files) {
                $text = "# $($document.title)`r`n`r`n$($document.text)`r`n"
                Send-FixtureFile -Filename ($document.doc_id + '.md') -Text $text -Token $token
                $uploaded++
            }
            Write-Output "PASS: Uploaded and indexed $($files.Count) synthetic documents for fixture tenant $fixtureTenant."
        } finally {
            $password = $null
            $token = $null
            $login = $null
            if ($null -ne $securePassword) { $securePassword.Dispose() }
            $securePassword = $null
        }
    }
    Write-Output "PASS: $uploaded fixture documents reached READY across $($fixtures.Count) isolated tenants."
    Write-Output 'No passwords, access tokens, signed URLs, or document text were printed.'
} finally {
    $client.Dispose()
}
