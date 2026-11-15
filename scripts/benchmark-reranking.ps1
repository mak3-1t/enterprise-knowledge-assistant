[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string] $Endpoint = 'http://localhost:8081',

    [ValidateRange(1, 100)]
    [int] $Iterations = 5,

    [ValidateNotNullOrEmpty()]
    [string] $Model = 'Alibaba-NLP/gte-multilingual-reranker-base',

    [ValidateNotNullOrEmpty()]
    [string] $ModelRevision = 'TEI CPU image 1.9; record pinned model digest before sharing results'
)

$ErrorActionPreference = 'Stop'
$uri = [Uri]::new($Endpoint)
if ($uri.Scheme -ne 'http' -or $uri.Host -notin @('localhost', '127.0.0.1') -or $uri.UserInfo) {
    throw 'Endpoint must be a local http://localhost or http://127.0.0.1 URL.'
}
$uri = "$($uri.AbsoluteUri.TrimEnd('/'))/rerank"

$fixtureRoot = Join-Path $PSScriptRoot '..\docs\fixtures\sprint-03'
$tenantFixtures = @(
    @{ slug = 'alpha'; path = 'tenant-a\alpha-remote-work.md' },
    @{ slug = 'beta'; path = 'tenant-b\beta-remote-work.md' }
)
$cases = [System.Collections.Generic.List[object]]::new()
foreach ($tenant in $tenantFixtures) {
    $document = Get-Content -Raw (Join-Path $fixtureRoot $tenant.path)
    $sentences = @($document -split '(?<=[.!?])\s+' | Where-Object { $_.Trim() -and $_ -notmatch '^#' })
    if ($sentences.Count -lt 2) {
        throw "Expected two policy sentences in $($tenant.path)."
    }
    $weeklyQuery = 'Nhân viên được làm việc từ xa tối đa mấy ngày mỗi tuần?'
    $registrationQuery = 'Nhân viên đăng ký làm việc từ xa ở đâu?'
    $distractors = @(
        'Nhà ăn phục vụ bữa trưa các ngày trong tuần.',
        'Nhân viên nhận thiết bị làm việc từ bộ phận công nghệ.'
    )
    $cases.Add(@{
            id = "$($tenant.slug)-weekly-days"
            query = $weeklyQuery
            texts = @($distractors[0], $sentences[1], $sentences[0], $distractors[1])
            relevance = @(0, 1, 3, 0)
        })
    $cases.Add(@{
            id = "$($tenant.slug)-registration"
            query = $registrationQuery
            texts = @($distractors[0], $sentences[0], $sentences[1], $distractors[1])
            relevance = @(0, 1, 3, 0)
        })
}

function Get-RankingMetrics([int[]] $grades, [int] $cutoff) {
    $reciprocalRank = 0.0
    for ($index = 0; $index -lt $grades.Count; $index++) {
        if ($grades[$index] -gt 0) {
            $reciprocalRank = 1.0 / ($index + 1)
            break
        }
    }
    $dcg = 0.0
    for ($index = 0; $index -lt [Math]::Min($cutoff, $grades.Count); $index++) {
        $dcg += ([Math]::Pow(2, $grades[$index]) - 1) / ([Math]::Log($index + 2, 2))
    }
    $ideal = @($grades | Sort-Object -Descending)
    $idealDcg = 0.0
    for ($index = 0; $index -lt [Math]::Min($cutoff, $ideal.Count); $index++) {
        $idealDcg += ([Math]::Pow(2, $ideal[$index]) - 1) / ([Math]::Log($index + 2, 2))
    }
    [pscustomobject]@{
        Mrr = $reciprocalRank
        NdcgAt3 = if ($idealDcg -eq 0) { 0.0 } else { $dcg / $idealDcg }
    }
}

function Get-Percentile([double[]] $values, [double] $percentile) {
    $sorted = @($values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($percentile * $sorted.Count) - 1)
    return [Math]::Round($sorted[$index], 1)
}

$baselineMrr = [System.Collections.Generic.List[double]]::new()
$baselineNdcg = [System.Collections.Generic.List[double]]::new()
$rerankedMrr = [System.Collections.Generic.List[double]]::new()
$rerankedNdcg = [System.Collections.Generic.List[double]]::new()
$latencies = [System.Collections.Generic.List[double]]::new()

# Warm each query once, then measure only subsequent HTTP requests.
foreach ($case in $cases) {
    $body = @{
        query = $case.query
        texts = $case.texts
        truncate = $true
    } | ConvertTo-Json -Depth 4
    $null = Invoke-RestMethod -Method Post -Uri $uri -ContentType 'application/json' -Body $body -TimeoutSec 30
}

for ($iteration = 0; $iteration -lt $Iterations; $iteration++) {
    foreach ($case in $cases) {
        $baseline = Get-RankingMetrics -grades $case.relevance -cutoff 3
        $baselineMrr.Add($baseline.Mrr)
        $baselineNdcg.Add($baseline.NdcgAt3)

        $body = @{
            query = $case.query
            texts = $case.texts
            truncate = $true
        } | ConvertTo-Json -Depth 4
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $scores = Invoke-RestMethod -Method Post -Uri $uri -ContentType 'application/json' -Body $body -TimeoutSec 30
        $timer.Stop()
        $latencies.Add($timer.Elapsed.TotalMilliseconds)

        if (@($scores).Count -ne $case.texts.Count) {
            throw "TEI returned an unexpected score count for case $($case.id)."
        }
        $ordered = @($scores | Sort-Object @{ Expression = 'score'; Descending = $true }, @{ Expression = 'index'; Ascending = $true })
        $grades = @($ordered | ForEach-Object { [int]$case.relevance[[int]$_.index] })
        $ranked = Get-RankingMetrics -grades $grades -cutoff 3
        $rerankedMrr.Add($ranked.Mrr)
        $rerankedNdcg.Add($ranked.NdcgAt3)
    }
}

[pscustomobject]@{
    dataset = 'sprint-03 synthetic tenant policy fixtures (4 queries; two tenants evaluated independently)'
    candidateBaseline = 'fixed seeded candidate order; this is a reranker smoke/regression measure, not the production vector/RRF ranker'
    model = $Model
    modelRevision = $ModelRevision
    measuredUtc = [DateTime]::UtcNow.ToString('o')
    iterationsPerQuery = $Iterations
    queryCount = $cases.Count
    baseline = @{
        mrr = [Math]::Round(($baselineMrr | Measure-Object -Average).Average, 4)
        ndcgAt3 = [Math]::Round(($baselineNdcg | Measure-Object -Average).Average, 4)
    }
    reranked = @{
        mrr = [Math]::Round(($rerankedMrr | Measure-Object -Average).Average, 4)
        ndcgAt3 = [Math]::Round(($rerankedNdcg | Measure-Object -Average).Average, 4)
    }
    rerankerHttpLatencyMs = @{
        p50 = Get-Percentile -values $latencies.ToArray() -percentile 0.50
        p95 = Get-Percentile -values $latencies.ToArray() -percentile 0.95
    }
    limitations = @(
        'Synthetic fixtures contain two short policy paragraphs and are only a smoke/regression set.',
        'The baseline order is seeded; this does not benchmark production vector-only or RRF retrieval.',
        'Do not enable reranking by default or claim corpus-wide gains from this result.'
    )
} | ConvertTo-Json -Depth 6
