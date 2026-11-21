# Search regression check for the library search (see notes/PROGRESS.md).
#
# Runs the queries in search-baseline.json against a running server and compares the numbers with the
# expected ones. Two kinds of checks:
#
#   counts  - `mangas(search:)` must return exactly the recorded number of hits. This is what proves that
#             an index or recall change did not lose or gain results.
#   ranking - a book that has to be near the top (targetId/maxRank), or the first N titles of an entity
#             query having to contain one of the expected spellings. This is what proves that the
#             relevance order still works.
#
# The counts are tied to one library, so they drift when the library grows. After a deploy or a library
# update, run with -Update to record the new numbers (and read the diff before committing it).
#
# The LLM query plan changes the counts (the model picks different terms), so the check disables it for
# the run and restores the previous value afterwards. Pass -KeepLlm to measure with the model on.
#
# Usage (PowerShell 5.1 on the server):
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\search-baseline.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\search-baseline.ps1 -Url http://127.0.0.1:4569
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\search-baseline.ps1 -Update
#
# Exit code: 0 everything matches, 1 something differs, 2 the run could not be completed.

param(
    [string]$Url = 'http://127.0.0.1:4567',
    [string]$File = (Join-Path $PSScriptRoot 'search-baseline.json'),
    [switch]$Update,
    [switch]$KeepLlm
)

$ErrorActionPreference = 'Stop'
$script:failed = 0
$script:checked = 0

function Invoke-Gql {
    param([string]$Query, [hashtable]$Variables)

    $payload = @{ query = $Query; variables = $Variables } | ConvertTo-Json -Compress -Depth 10
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
    $response = Invoke-WebRequest -Uri "$Url/api/graphql" -Method Post -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 300 -UseBasicParsing
    $text = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    $parsed = $text | ConvertFrom-Json
    if ($parsed.errors) { throw ("graphql error: " + $text) }
    return $parsed.data
}

function Get-Search {
    param([string]$Query, [int]$First)

    return (Invoke-Gql 'query($q: String!, $n: Int!) { mangas(condition: {inLibrary: true}, search: $q, first: $n) { totalCount nodes { id title } } }' @{ q = $Query; n = $First }).mangas
}

function Test-Expected {
    param([string]$Name, [long]$Expected, [long]$Actual)

    $script:checked++
    if ($Expected -eq $Actual) {
        Write-Output ("PASS  {0}  {1}" -f $Name, $Actual)
        return
    }

    $script:failed++
    Write-Output ("DIFF  {0}  expected {1}, got {2}" -f $Name, $Expected, $Actual)
}

function Set-IfUpdating {
    param($Entry, [long]$Actual)

    if ($Update) { $Entry.expected = $Actual }
}

$config = [IO.File]::ReadAllText($File, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
if (-not $config.counts -or -not $config.ranking) { throw "baseline file has no checks: $File" }

Write-Output ("server   " + $Url)

$settings = (Invoke-Gql 'query { settings { llmQueryEnabled searchRelevanceSortEnabled } }' @{}).settings
$llmWasEnabled = [bool]$settings.llmQueryEnabled
if ($llmWasEnabled -and -not $KeepLlm) {
    Invoke-Gql 'mutation($s: PartialSettingsTypeInput!) { setSettings(input: {settings: $s}) { settings { llmQueryEnabled } } }' @{ s = @{ llmQueryEnabled = $false } } | Out-Null
    Write-Output 'llm      turned off for this run (was on)'
} else {
    Write-Output ("llm      " + $(if ($llmWasEnabled) { 'on (-KeepLlm)' } else { 'off' }))
}
Write-Output ("relevance " + $settings.searchRelevanceSortEnabled)
Write-Output ''

try {
    $library = (Invoke-Gql 'query { mangas(condition: {inLibrary: true}, first: 1) { totalCount } }' @{}).mangas.totalCount
    Write-Output ("library  {0} books in the library (baseline recorded with {1})" -f $library, $config.librarySnapshot.inLibrary)
    if ([Math]::Abs([long]$library - [long]$config.librarySnapshot.inLibrary) -gt 0) {
        Write-Output '         the library changed since the baseline was recorded: counts can drift, re-run with -Update'
    }

    Write-Output ''
    Write-Output '=== hit counts ==='
    foreach ($entry in $config.counts) {
        $actual = (Get-Search $entry.query 1).totalCount
        Test-Expected $entry.query $entry.expected $actual
        Set-IfUpdating $entry $actual
    }

    Write-Output ''
    Write-Output '=== author intent ==='
    foreach ($entry in $config.intent) {
        $actual = (Get-Search $entry.query 1).totalCount
        Test-Expected $entry.query $entry.expected $actual
        Set-IfUpdating $entry $actual
    }

    Write-Output ''
    Write-Output '=== relevance order ==='
    foreach ($check in $config.ranking) {
        $script:checked++
        $mangas = Get-Search $check.query $check.top
        $nodes = @($mangas.nodes)

        if ($check.targetId) {
            $rank = 0
            for ($i = 0; $i -lt $nodes.Count; $i++) { if ($nodes[$i].id -eq $check.targetId) { $rank = $i + 1; break } }
            if ($rank -gt 0 -and $rank -le $check.maxRank) {
                Write-Output ("PASS  {0}  book {1} is rank {2}" -f $check.query, $check.targetId, $rank)
            } else {
                $script:failed++
                Write-Output ("DIFF  {0}  book {1} should be within rank {2}, got {3}" -f $check.query, $check.targetId, $check.maxRank, $(if ($rank -eq 0) { ">$($check.top)" } else { $rank }))
            }
            continue
        }

        $bad = @()
        foreach ($node in $nodes) {
            $matched = $false
            foreach ($needle in $check.titleMatchesAny) {
                if ($node.title.ToLowerInvariant().Contains($needle.ToLowerInvariant())) { $matched = $true; break }
            }
            if (-not $matched) { $bad += $node.title }
        }

        if ($bad.Count -eq 0) {
            Write-Output ("PASS  {0}  top {1} all match" -f $check.query, $nodes.Count)
        } else {
            $script:failed++
            Write-Output ("DIFF  {0}  {1} of the top {2} do not match: {3}" -f $check.query, $bad.Count, $nodes.Count, ($bad -join ' | '))
        }
    }
} finally {
    if ($llmWasEnabled -and -not $KeepLlm) {
        Invoke-Gql 'mutation($s: PartialSettingsTypeInput!) { setSettings(input: {settings: $s}) { settings { llmQueryEnabled } } }' @{ s = @{ llmQueryEnabled = $true } } | Out-Null
        Write-Output ''
        Write-Output 'llm      turned back on'
    }
}

Write-Output ''
if ($Update) {
    $config.librarySnapshot.inLibrary = $library
    $config.librarySnapshot.updatedAt = (Get-Date).ToString('yyyy-MM-dd')
    $json = $config | ConvertTo-Json -Depth 10
    [IO.File]::WriteAllText($File, $json + "`r`n", (New-Object System.Text.UTF8Encoding($false)))
    Write-Output ("baseline updated: " + $File)
    exit 0
}

Write-Output ("{0} checks, {1} failed" -f $script:checked, $script:failed)
if ($script:failed -gt 0) { exit 1 }
exit 0
