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
$appBaseUrl = $uri.AbsoluteUri.TrimEnd('/')
try {
    $health = Invoke-RestMethod -Method Get -Uri "$appBaseUrl/actuator/health" -TimeoutSec 3
} catch {
    throw "EKA app is not reachable at $appBaseUrl. Start the application, then run this smoke test again."
}
if ($health.status -ne 'UP') {
    throw "EKA app health is '$($health.status)', not UP. Check application startup before retrying."
}
Write-Output 'PASS: EKA app health is UP'

$securePassword = Read-Host "Password for $Email in tenant $TenantSlug" -AsSecureString
$password = [Net.NetworkCredential]::new('', $securePassword).Password
$loginJson = $null
$agentJson = $null
$loginBody = $null
$agentBody = $null
$session = $null
$answer = $null
try {
    $loginBody = @{
        tenantSlug = $TenantSlug
        email = $Email
        password = $password
    } | ConvertTo-Json -Depth 4
    $session = Invoke-RestMethod -Method Post -Uri "$appBaseUrl/api/auth/login" `
        -ContentType 'application/json' -Body $loginBody
    if (-not $session.accessToken) {
        throw 'Login did not return an access token.'
    }
    Write-Output 'PASS: Local tenant login'

    $headers = @{ Authorization = "Bearer $($session.accessToken)" }
    $agentJson = @{ question = $Question } | ConvertTo-Json -Compress
    $agentBody = [Text.Encoding]::UTF8.GetBytes($agentJson)
    $answer = Invoke-RestMethod -Method Post `
        -Uri "$appBaseUrl/api/agent" `
        -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $agentBody
    if ($answer.status -ne 'ANSWERED' -or @($answer.citations).Count -lt 1) {
        if ($answer.status -eq 'NO_EVIDENCE') {
            Write-Output 'Agent status: NO_EVIDENCE (the request completed, but no validated citation was returned).'
            try {
                $documents = Invoke-RestMethod -Method Get `
                    -Uri "$appBaseUrl/api/documents?page=0&size=100" -Headers $headers
                $states = @($documents | Group-Object status | ForEach-Object {
                    "$($_.Name)=$($_.Count)"
                })
                if ($states.Count -eq 0) {
                    Write-Output 'Tenant document states: no visible documents.'
                } else {
                    Write-Output "Tenant document states (metadata only): $($states -join ', ')"
                }
            } catch {
                $statusCode = $null
                if ($_.Exception.Response) {
                    $statusCode = [int]$_.Exception.Response.StatusCode
                }
                if ($statusCode) {
                    Write-Output "Could not read tenant document status metadata (HTTP $statusCode)."
                } else {
                    Write-Output 'Could not read tenant document status metadata (request failed before an HTTP response).'
                }
            }
            throw 'No evidence-backed answer. Confirm a relevant document is READY and readable by this account; inspect the app log for agent_tool_outcome.'
        }
        throw "Agent returned status '$($answer.status)' with $(@($answer.citations).Count) citation(s); expected ANSWERED with at least one citation."
    }
    Write-Output "PASS: Agent returned an evidence-backed answer with $(@($answer.citations).Count) citation(s)"
    Write-Output 'PASS: No access token, password, answer text, or document content printed'
} finally {
    $password = $null
    $securePassword = $null
    $health = $null
    $loginJson = $null
    $agentJson = $null
    $loginBody = $null
    $agentBody = $null
    $headers = $null
    $session = $null
    $answer = $null
}
