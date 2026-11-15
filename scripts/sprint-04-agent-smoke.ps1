[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9-]{1,63}$')]
    [string] $TenantSlug,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[^\s@]+@[^\s@]+\.[^\s@]+$')]
    [string] $Email,

    [ValidateNotNullOrEmpty()]
    [string] $BaseUrl = 'http://localhost:8080',

    [ValidateNotNullOrEmpty()]
    [string] $Question = 'How many days per week may employees work remotely?'
)

$ErrorActionPreference = 'Stop'
$uri = [Uri]::new($BaseUrl)
if ($uri.Scheme -ne 'http' -or $uri.Host -notin @('localhost', '127.0.0.1') -or $uri.UserInfo) {
    throw 'BaseUrl must be a local http://localhost or http://127.0.0.1 URL.'
}

$securePassword = Read-Host "Password for $Email in tenant $TenantSlug" -AsSecureString
$password = [Net.NetworkCredential]::new('', $securePassword).Password
$loginJson = $null
$agentJson = $null
$session = $null
$answer = $null
try {
    $loginJson = @{
        tenantSlug = $TenantSlug
        email = $Email
        password = $password
    } | ConvertTo-Json
    $session = Invoke-RestMethod -Method Post -Uri "$($uri.AbsoluteUri.TrimEnd('/'))/api/auth/login" `
        -ContentType 'application/json' -Body $loginJson
    if (-not $session.accessToken) {
        throw 'Login did not return an access token.'
    }
    Write-Output 'PASS: Local tenant login'

    $headers = @{ Authorization = "Bearer $($session.accessToken)" }
    $agentJson = @{ question = $Question } | ConvertTo-Json
    $answer = Invoke-RestMethod -Method Post `
        -Uri "$($uri.AbsoluteUri.TrimEnd('/'))/api/agent" `
        -Headers $headers -ContentType 'application/json' -Body $agentJson
    if ($answer.status -ne 'ANSWERED' -or @($answer.citations).Count -lt 1) {
        throw 'Agent returned no evidence-backed answer. Check that this tenant has READY documents and that Ollama is running.'
    }
    Write-Output "PASS: Agent returned an evidence-backed answer with $(@($answer.citations).Count) citation(s)"
    Write-Output 'PASS: No access token, password, answer text, or document content printed'
} finally {
    $password = $null
    $securePassword = $null
    $loginJson = $null
    $agentJson = $null
    $headers = $null
    $session = $null
    $answer = $null
}
