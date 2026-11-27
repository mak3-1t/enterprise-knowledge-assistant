[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string] $OutputDirectory = '.\target\sprint-04-reranking-upload'
)

$ErrorActionPreference = 'Stop'
$fixtureRoot = Join-Path $PSScriptRoot '..\docs\fixtures\sprint-04-reranking'
$corpus = @(
    Get-Content -Encoding UTF8 (Join-Path $fixtureRoot 'corpus.jsonl') |
        Where-Object { $_.Trim() } |
        ForEach-Object { ConvertFrom-Json -InputObject $_ }
)
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
$utf8NoBom = [Text.UTF8Encoding]::new($false)
$written = 0

foreach ($document in $corpus) {
    if ($document.doc_id -notmatch '^[a-z][a-z0-9_-]{0,63}$' -or
        $document.tenant_id -notmatch '^[a-z][a-z0-9_-]{0,63}$' -or
        [string]::IsNullOrWhiteSpace([string]$document.title) -or
        [string]::IsNullOrWhiteSpace([string]$document.text)) {
        throw 'Fixture contains a document with invalid ID, tenant, title, or text.'
    }

    $tenantDirectory = Join-Path $outputRoot $document.tenant_id
    New-Item -ItemType Directory -Path $tenantDirectory -Force | Out-Null
    $path = Join-Path $tenantDirectory ($document.doc_id + '.md')
    $content = "# $($document.title)`r`n`r`n$($document.text)`r`n"
    [IO.File]::WriteAllText($path, $content, $utf8NoBom)
    $written++
}

Write-Output "Prepared $written synthetic markdown files under $outputRoot"
Write-Output 'Upload each tenant folder only to its matching isolated EKA tenant.'
