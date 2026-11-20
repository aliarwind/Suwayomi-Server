<#
.SYNOPSIS
  Mines search synonyms (aliases) for the library and writes a search-aliases.json candidate file.

.DESCRIPTION
  Reads the most used tags of a running library through `searchTagStats`, asks the configured chat model
  for other ways to search each tag, keeps the candidates the library does not match yet (those are the
  ones that need expansion) and writes them as review-ready alias groups.

  The output file is what the server loads from `<data dir>/search-aliases.json` at startup - review it
  first, then copy it over and restart. The built-in groups stay active, the file only adds to them.

.PARAMETER Server
  Base url of the running server, e.g. http://127.0.0.1:4567 or http://192.168.31.180:4567.

.PARAMETER Top
  How many of the most used tags to mine.

.PARAMETER Out
  Where to write the candidate json.

.PARAMETER IncludeCovered
  Also mine tags that already have a synonym group (by default they are skipped to save tokens).

.PARAMETER Batch
  How many tags to send to the model per request.

.EXAMPLE
  ./mine-aliases.ps1 -Server http://127.0.0.1:4569 -Top 60 -Out ..\alias-candidates.json
#>
[CmdletBinding()]
param(
    [string]$Server = 'http://127.0.0.1:4567',
    [int]$Top = 200,
    [string]$Out = (Join-Path (Get-Location) 'alias-candidates.json'),
    [switch]$IncludeCovered,
    [int]$Batch = 20,
    [int]$MaxSynonyms = 3,
    [string]$BaseUrl,
    [string]$ApiKey,
    [string]$Model
)

$ErrorActionPreference = 'Stop'
$Server = $Server.TrimEnd('/')

# Windows PowerShell 5.1 decodes response bodies with the charset of the content type, which is missing for
# JSON from most servers and then defaults to Latin-1: always read the raw bytes and decode UTF-8 ourselves.
function Invoke-PostUtf8 {
    param([string]$Uri, [string]$Body, [hashtable]$Headers)

    if (-not $Headers) { $Headers = @{} }

    $response = Invoke-WebRequest -Uri $Uri -Method Post -Headers $Headers `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes($Body)) -TimeoutSec 180 -UseBasicParsing

    $text = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    return $text | ConvertFrom-Json
}

function Invoke-Gql {
    param([string]$Query, [hashtable]$Variables)

    $body = @{ query = $Query }
    if ($Variables -and $Variables.Count -gt 0) { $body.variables = $Variables }

    $response = Invoke-PostUtf8 -Uri "$Server/api/graphql" -Body ($body | ConvertTo-Json -Compress -Depth 10)
    if ($response.errors) {
        throw ("GraphQL error: " + (($response.errors | ForEach-Object { $_.message }) -join '; '))
    }

    return $response.data
}

function Invoke-Chat {
    param([string]$System, [string]$User)

    $payload = @{
        model           = $script:Model
        temperature     = 0
        max_tokens      = 2000
        response_format = @{ type = 'json_object' }
        messages        = @(
            @{ role = 'system'; content = $System },
            @{ role = 'user'; content = $User }
        )
    } | ConvertTo-Json -Compress -Depth 10

    $headers = @{}
    if ($script:ApiKey) { $headers['authorization'] = "Bearer $script:ApiKey" }

    $response = Invoke-PostUtf8 -Uri "$script:BaseUrl/chat/completions" -Body $payload -Headers $headers
    $content = $response.choices[0].message.content
    if (-not $content) { throw 'empty chat completion answer' }

    return $content
}

function ConvertFrom-JsonObjectText {
    param([string]$Text)

    $start = $Text.IndexOf('{')
    $end = $Text.LastIndexOf('}')
    if ($start -lt 0 -or $end -le $start) { throw ("answer is not a JSON object: " + $Text.Substring(0, [Math]::Min(200, $Text.Length))) }

    return $Text.Substring($start, $end - $start + 1) | ConvertFrom-Json
}

# How many library entries a free text search returns; batched via GraphQL aliases.
function Get-SearchHits {
    param([string[]]$Terms, [int]$Size = 10)

    $hits = @{}
    $terms = @($Terms | Where-Object { $_ } | Select-Object -Unique)

    for ($i = 0; $i -lt $terms.Count; $i += $Size) {
        $end = [Math]::Min($i + $Size, $terms.Count) - 1
        $chunk = @($terms[$i..$end])

        $definitions = @()
        $fields = @()
        $variables = @{}
        for ($j = 0; $j -lt $chunk.Count; $j++) {
            $definitions += "`$q${j}: String!"
            $variables["q$j"] = $chunk[$j]
            $fields += "f${j}: mangas(condition:{inLibrary:true}, search:`$q${j}, first:1){ totalCount }"
        }

        $query = 'query(' + ($definitions -join ', ') + ') { ' + ($fields -join ' ') + ' }'
        $data = Invoke-Gql $query $variables

        for ($j = 0; $j -lt $chunk.Count; $j++) { $hits[$chunk[$j]] = $data."f$j".totalCount }
    }

    return $hits
}

$systemPrompt = @"
你是成人漫画库的搜索助手。用户会给你一批库里已有的标签/词，请给出用户可能用来搜同一个意思的**其它说法**（中文口语、俗称、繁简差异、日文、英文缩写），每个最多 $MaxSynonyms 个。
只输出 JSON 对象，键是原词，值是说法数组，例如 {"巨乳":["大奶","大胸"]}。
规则：
1. 不要重复原词本身；不要给同义但含义不同的词（颜色词不要扩到颜色中性的词，子集词不要扩到父集，例如"白丝"不要给"长筒袜"）。
2. 只给真正的同义说法；拿不准就给空数组。
3. 不要输出解释。
"@

# --- settings (the server already holds the key, reuse it unless overridden) ---
if (-not $BaseUrl -or -not $Model) {
    $settings = (Invoke-Gql 'query { settings { llmBaseUrl llmApiKey llmChatModel } }' @{}).settings
    if (-not $BaseUrl) { $BaseUrl = $settings.llmBaseUrl }
    if (-not $ApiKey) { $ApiKey = $settings.llmApiKey }
    if (-not $Model) { $Model = $settings.llmChatModel }
}
$BaseUrl = $BaseUrl.TrimEnd('/')
if (-not $BaseUrl) { throw 'no chat base url configured; pass -BaseUrl' }

Write-Host "server=$Server model=$Model top=$Top batch=$Batch"

# --- library vocabulary ---
$stats = @((Invoke-Gql 'query($limit:Int!){ searchTagStats(limit:$limit){ tag uses inAliasGroup aliasTargets } }' @{ limit = $Top }).searchTagStats)
$tags = @($stats | Where-Object { $_.uses -ge 2 })
if (-not $IncludeCovered) { $tags = @($tags | Where-Object { -not $_.inAliasGroup }) }

Write-Host ("mining {0} tags ({1} already covered)" -f $tags.Count, ($stats.Count - $tags.Count))
if ($tags.Count -eq 0) { Write-Host 'nothing to mine'; return }

# --- ask the model for synonyms, batch by batch ---
$proposals = @{}
for ($i = 0; $i -lt $tags.Count; $i += $Batch) {
    $end = [Math]::Min($i + $Batch, $tags.Count) - 1
    $names = @($tags[$i..$end] | ForEach-Object { $_.tag })
    $user = '标签：' + ($names | ConvertTo-Json -Compress -Depth 3)

    try {
        $answer = Invoke-Chat $systemPrompt $user
        $parsed = ConvertFrom-JsonObjectText $answer
    } catch {
        Write-Warning ("batch {0}-{1} failed: {2}" -f $i, $end, $_.Exception.Message)
        continue
    }

    foreach ($name in $names) {
        $value = $parsed.$name
        if (-not $value) { continue }
        $clean = @($value | Where-Object { $_ -is [string] } |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_.Length -ge 1 -and $_.Length -le 40 -and $_ -ne $name } |
            Select-Object -Unique)
        if ($clean.Count -gt 0) { $proposals[$name] = $clean }
    }

    Write-Host ("batch {0}-{1} done, {2} tags with proposals" -f $i, $end, $proposals.Count)
}

if ($proposals.Count -eq 0) { Write-Host 'the model proposed nothing'; return }

# --- keep the candidates the library does not match yet (those need expansion) ---
$allTerms = @($proposals.Keys)
foreach ($proposal in $proposals.Values) { $allTerms += @($proposal) }
$hits = Get-SearchHits $allTerms

$groups = @()
$review = @()
foreach ($name in $proposals.Keys) {
    $uses = ($tags | Where-Object { $_.tag -eq $name }).uses
    $canonicalHits = $hits[$name]
    $kept = @($proposals[$name] | Where-Object { $hits[$_] -eq 0 })
    $skipped = @($proposals[$name] | Where-Object { $hits[$_] -ne 0 })

    $review += ('{0} (uses {1}, hits {2}) + {3}' -f $name, $uses, $canonicalHits, ($kept -join ', '))
    if ($skipped.Count -gt 0) { $review += ('    skipped (already matches): ' + ($skipped -join ', ')) }

    if ($kept.Count -eq 0) { continue }

    $groups += [ordered]@{
        terms  = @($name) + $kept
        source = 'llm'
        note   = "$name uses=$uses hits=$canonicalHits; " + (($kept | ForEach-Object { "$_ hits=0" }) -join '; ')
    }
}

$document = [ordered]@{
    version     = 1
    generatedAt = (Get-Date).ToString('s')
    model       = $Model
    groups      = $groups
}

$directory = Split-Path -Parent $Out
if ($directory -and -not (Test-Path $directory)) { New-Item -ItemType Directory -Path $directory | Out-Null }

[IO.File]::WriteAllText($Out, ($document | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))

$reviewPath = [IO.Path]::ChangeExtension($Out, '.txt')
[IO.File]::WriteAllLines($reviewPath, $review, [Text.UTF8Encoding]::new($false))

Write-Host ("wrote {0} groups to {1}" -f $groups.Count, $Out)
Write-Host ("review notes: {0}" -f $reviewPath)
