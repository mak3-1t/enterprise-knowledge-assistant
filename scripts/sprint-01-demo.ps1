# Requires PowerShell 7+, Java 21, Docker Desktop and a packaged application.
# No connection to the persistent development database is made.
[CmdletBinding()]
param([string]$Jar = 'app/target/app-0.1.0-SNAPSHOT.jar', [switch]$IncludeRegistration)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Run with PowerShell 7 (pwsh).' }
$root = Split-Path $PSScriptRoot -Parent
$jarPath = (Resolve-Path (Join-Path $root $Jar)).Path
$container = 'eka-s1-demo-' + [guid]::NewGuid().ToString('N')
$temp = Join-Path ([IO.Path]::GetTempPath()) $container
$process = $null
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(10)
$results = [Collections.Generic.List[string]]::new()
function Assert-Demo($condition, $name) {
    if (-not $condition) { throw "FAIL: $name" }
    $results.Add("PASS: $name")
    Write-Host "PASS: $name"
}
function Request-Demo($method, $path, $status, $token = '', $body = $null) {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($method), "$script:base$path")
    if ($token) { $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token) }
    if ($null -ne $body) {
        $request.Content = [Net.Http.StringContent]::new(($body | ConvertTo-Json -Compress), [Text.Encoding]::UTF8, 'application/json')
    }
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            if ([int]$response.StatusCode -ne $status) { throw "Expected HTTP $status for $method $path; got $([int]$response.StatusCode)." }
            $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ($text) { return ($text | ConvertFrom-Json) }
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}
function Stop-DemoApp {
    if ($null -ne $script:process) {
        if (-not $script:process.HasExited) { $script:process.Kill($true); $script:process.WaitForExit() }
        $script:process.Dispose()
        $script:process = $null
    }
}
function Get-DemoFileUri([string]$path) {
    # A Unix absolute path cast to [Uri] can remain relative and yield an empty
    # AbsoluteUri. Build an explicit file URI on both Linux and Windows.
    $builder = [UriBuilder]::new()
    $builder.Scheme = 'file'
    $builder.Host = ''
    $builder.Path = $path
    return $builder.Uri.AbsoluteUri
}
function Start-DemoApp($slug, $email, $role, $bootstrap = $true, $registration = $false) {
    Stop-DemoApp
    $start = [Diagnostics.ProcessStartInfo]::new('java')
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($arg in @('-jar', $jarPath, '--spring.profiles.active=dev', '--server.address=127.0.0.1', "--server.port=$script:port")) { $start.ArgumentList.Add($arg) }
    # Child-only configuration overrides inherited bootstrap, DB and signing settings.
    $settings = @{
        EKA_AUTH_REGISTRATION_ENABLED = $registration.ToString().ToLowerInvariant()
        POSTGRES_USER = 'demo'; POSTGRES_PASSWORD = $script:password
        EKA_DB_URL = "jdbc:postgresql://127.0.0.1:$script:dbPort/demo"
        EKA_AUTH_PRIVATE_KEY = Get-DemoFileUri (Join-Path $temp 'private.pem')
        EKA_AUTH_PUBLIC_KEY = Get-DemoFileUri (Join-Path $temp 'public.pem')
        EKA_AUTH_ISSUER = 'https://eka.demo.local'; EKA_AUTH_AUDIENCE = 'eka-api'
        EKA_AUTH_BOOTSTRAP_ENABLED = $bootstrap.ToString().ToLowerInvariant()
        EKA_AUTH_BOOTSTRAP_TENANT_SLUG = $slug; EKA_AUTH_BOOTSTRAP_EMAIL = $email
        EKA_AUTH_BOOTSTRAP_ROLE = $role; EKA_AUTH_BOOTSTRAP_PASSWORD = $script:password
    }
    foreach ($key in $settings.Keys) { $start.Environment[$key] = $settings[$key] }
    $script:process = [Diagnostics.Process]::Start($start)
    # Drain both streams to avoid pipe deadlocks; never print application output/tokens.
    $script:stdout = $process.StandardOutput.ReadToEndAsync()
    $script:stderr = $process.StandardError.ReadToEndAsync()
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) { throw 'Demo app exited during startup; check local Java/configuration.' }
        try {
            $health = Request-Demo GET '/actuator/health' 200
            if ($health.status -eq 'UP') {
                # ApplicationRunner provisioning completes after the HTTP server starts.
                if (-not $bootstrap) { return }
                $ready = Login-Demo $slug $email
                if ($ready) { return }
            }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw 'Demo app startup/provisioning timed out.'
}
function Login-Demo($slug, $email) {
    $reply = Request-Demo POST '/api/auth/login' 200 '' @{ tenantSlug = $slug; email = $email; password = $script:password }
    return $reply.accessToken
}
try {
    & docker version --format '{{.Server.Version}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Engine unavailable. Start Docker Desktop and run in your own terminal.' }
    New-Item -ItemType Directory $temp | Out-Null
    if ($IsWindows) {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        & icacls $temp /inheritance:r /grant:r "${identity}:(OI)(CI)F" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not restrict temporary signing directory permissions.' }
    } else { & chmod 700 $temp; if ($LASTEXITCODE -ne 0) { throw 'Could not restrict temporary directory permissions.' } }
    & java (Join-Path $root 'auth/src/main/java/com/eka/auth/tools/GenerateAuthKeys.java') $temp | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed.' }
    $script:password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    # Pass the DB password through the child environment, not the command line.
    $dockerStart = [Diagnostics.ProcessStartInfo]::new('docker')
    $dockerStart.UseShellExecute = $false
    $dockerStart.RedirectStandardInput = $true
    $dockerStart.RedirectStandardOutput = $true
    $dockerStart.Environment['POSTGRES_PASSWORD'] = $password
    foreach ($arg in @('run','--detach','--rm','--name',$container,'-e','POSTGRES_PASSWORD','-e','POSTGRES_USER=demo','-e','POSTGRES_DB=demo','-p','127.0.0.1::5432','postgres:17-alpine')) { $dockerStart.ArgumentList.Add($arg) }
    $dockerProcess = [Diagnostics.Process]::Start($dockerStart)
    $dockerProcess.StandardInput.Close()
    $null = $dockerProcess.StandardOutput.ReadToEnd()
    $dockerProcess.WaitForExit()
    $dockerExit = $dockerProcess.ExitCode
    $dockerProcess.Dispose()
    if ($dockerExit -ne 0) { throw 'Temporary PostgreSQL startup failed.' }
    $script:dbPort = ((& docker port $container 5432/tcp) -split ':')[-1].Trim()
    if ($LASTEXITCODE -ne 0 -or $dbPort -notmatch '^\d+$') { throw 'Could not determine PostgreSQL port.' }
    $dbReady = $false
    for ($i = 0; $i -lt 60; $i++) {
        & docker exec $container pg_isready -U demo -d demo *> $null
        if ($LASTEXITCODE -eq 0) { $dbReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $dbReady) { throw 'PostgreSQL readiness timed out.' }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start(); $script:port = $listener.LocalEndpoint.Port; $listener.Stop()
    $script:base = "http://127.0.0.1:$port"
    Start-DemoApp 'demo-a' 'admin@example.test' 'ADMIN'
    Start-DemoApp 'demo-a' 'viewer@example.test' 'VIEWER'
    Start-DemoApp 'demo-a' 'editor@example.test' 'EDITOR'
    Start-DemoApp 'demo-b' 'admin@example.test' 'ADMIN'
    $a = Login-Demo 'demo-a' 'admin@example.test'
    $b = Login-Demo 'demo-b' 'admin@example.test'
    $viewer = Login-Demo 'demo-a' 'viewer@example.test'
    $editor = Login-Demo 'demo-a' 'editor@example.test'
    $meA = Request-Demo GET '/api/me' 200 $a
    $meB = Request-Demo GET '/api/me' 200 $b
    Assert-Demo ($meA.tenantId -ne $meB.tenantId -and $meA.role -eq 'ADMIN' -and $meB.role -eq 'ADMIN') 'Two distinct tenant ADMIN identities'
    $viewerMe = Request-Demo GET '/api/me' 200 $viewer
    Assert-Demo ($viewerMe.role -eq 'VIEWER' -and $viewerMe.tenantId -eq $meA.tenantId) 'VIEWER identity belongs to A'
    $editorMe = Request-Demo GET '/api/me' 200 $editor
    Assert-Demo ($editorMe.role -eq 'EDITOR' -and $editorMe.tenantId -eq $meA.tenantId) 'EDITOR identity belongs to A'
    foreach ($pair in @(@($a,$meA,3), @($b,$meB,1))) {
        $list = Request-Demo GET '/api/tenant/members?page=0&size=20' 200 $pair[0]
        Assert-Demo (@($list.items).Count -eq $pair[2] -and @($list.items | Where-Object tenantId -ne $pair[1].tenantId).Count -eq 0) 'List contains only own tenant fixtures'
        $page0 = Request-Demo GET '/api/tenant/members?page=0&size=1' 200 $pair[0]
        Assert-Demo (@($page0.items).Count -eq 1 -and $page0.size -eq 1) 'Bounded pagination'
        $own = Request-Demo GET "/api/tenant/members/$($pair[1].userId)" 200 $pair[0]
        Assert-Demo ($own.id -eq $pair[1].userId) 'Own member detail'
    }
    $foreignA = Request-Demo GET "/api/tenant/members/$($meB.userId)" 404 $a
    $foreignB = Request-Demo GET "/api/tenant/members/$($meA.userId)" 404 $b
    $missing = Request-Demo GET "/api/tenant/members/$([guid]::NewGuid())" 404 $a
    Assert-Demo (($foreignA | ConvertTo-Json -Compress) -eq ($missing | ConvertTo-Json -Compress) -and ($foreignB | ConvertTo-Json -Compress) -eq ($missing | ConvertTo-Json -Compress)) 'Both foreign directions match missing-ID JSON 404'
    $null = Request-Demo GET '/api/tenant/members' 403 $viewer
    $null = Request-Demo GET "/api/tenant/members/$($meA.userId)" 403 $viewer
    Assert-Demo $true 'VIEWER list and detail return 403'
    $null = Request-Demo GET '/api/tenant/members' 403 $editor
    $null = Request-Demo GET "/api/tenant/members/$($meA.userId)" 403 $editor
    Assert-Demo $true 'EDITOR list and detail return 403'
    $null = Request-Demo GET '/api/me' 401
    $null = Request-Demo GET '/api/me' 401 'invalid-token'
    Assert-Demo $true 'Missing and invalid token return 401'
    # Repeat provisioning; existing password, identity and tenant must survive.
    Start-DemoApp 'demo-a' 'admin@example.test' 'ADMIN'
    $a = Login-Demo 'demo-a' 'admin@example.test'
    $again = Request-Demo GET '/api/me' 200 $a
    $list = Request-Demo GET '/api/tenant/members' 200 $a
    Assert-Demo ($again.userId -eq $meA.userId -and @($list.items).Count -eq 3) 'Restart preserves identity and does not duplicate accounts'
    $migrationCount = & docker exec $container psql -U demo -d demo -Atc 'SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL'
    if ($LASTEXITCODE -ne 0) { throw 'Migration history verification failed.' }
    Assert-Demo ($migrationCount.Trim() -eq '2') 'Flyway applies V1 and V2 exactly once across restarts'
    if ($IncludeRegistration) {
        $registration = @{ tenantSlug = 'signup-a'; email = 'signup@example.test'; password = $password }
        $off = Request-Demo POST '/api/auth/register' 404 '' $registration
        Assert-Demo ($off.error -eq 'not_found') 'Registration disabled by default'
        Start-DemoApp '' '' '' $false $true
        $registered = Request-Demo POST '/api/auth/register' 201 '' $registration
        Assert-Demo ($registered.role -eq 'ADMIN' -and $registered.tenantSlug -eq 'signup-a') 'Opt-in registration creates ADMIN'
        $signupA = Login-Demo 'signup-a' 'signup@example.test'
        $identityA = Request-Demo GET '/api/me' 200 $signupA
        Assert-Demo ($identityA.userId -eq $registered.userId -and $identityA.tenantId -eq $registered.tenantId) 'Registered ADMIN can login and read identity'
        $duplicate = Request-Demo POST '/api/auth/register' 409 '' $registration
        Assert-Demo ($duplicate.error -eq 'registration_conflict') 'Duplicate registration returns generic 409'
        $registration.tenantSlug = 'signup-b'
        $registeredB = Request-Demo POST '/api/auth/register' 201 '' $registration
        $signupB = Login-Demo 'signup-b' 'signup@example.test'
        $foreignA = Request-Demo GET "/api/tenant/members/$($registeredB.userId)" 404 $signupA
        $foreignB = Request-Demo GET "/api/tenant/members/$($registered.userId)" 404 $signupB
        Assert-Demo ($registeredB.tenantId -ne $registered.tenantId -and $foreignA.error -eq 'not_found' -and $foreignB.error -eq 'not_found') 'Registered tenants remain isolated in both directions'
        Start-DemoApp '' '' '' $false $false
        $registration.tenantSlug = 'must-not-be-created'
        $offAgain = Request-Demo POST '/api/auth/register' 404 '' $registration
        $stillActive = Request-Demo GET '/api/me' 200 $signupA
        Assert-Demo ($offAgain.error -eq 'not_found' -and $stillActive.userId -eq $registered.userId) 'Disabling registration preserves existing login'
    }
    Write-Host "Acceptance demo complete: $($results.Count) checks passed. No tokens or passwords printed."
} finally {
    Stop-DemoApp
    $client.Dispose()
    # Only remove this run's exact randomly named disposable container/directory.
    & docker rm -f $container *> $null
    if (Test-Path -LiteralPath $temp) {
        $resolved = (Resolve-Path -LiteralPath $temp).Path
        $expected = Join-Path ([IO.Path]::GetTempPath()) $container
        if ($resolved -ne $expected.TrimEnd('\','/')) { throw 'Unexpected cleanup path; refusing removal.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    $script:password = $null
}
