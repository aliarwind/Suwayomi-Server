<#
.SYNOPSIS
  Mines search synonyms (aliases) for the library and writes a search-aliases.json candidate file.

.DESCRIPTION
  Two modes:

  1. Tags (default): reads the most used genre tags of a running library through `searchTagStats`, asks
     the configured chat model for other ways to search each tag, and keeps the candidates the library
     does not match yet (those are the ones that need expansion).

  2. Entities (-Entities): reads the titles of the library, extracts the names in brackets (series,
     characters, circles) and asks the model for the Chinese / Japanese / English / abbreviated ways to
     search them. Unlike the tag mode every candidate is kept: a cross-language name usually already
     matches some books and must be OR-ed with the others (e.g. ブルアカ / 蔚蓝档案).

  The output file is what the server loads from `<data dir>/search-aliases.json` at startup - review it
  first, then copy it over and restart. The built-in groups stay active, the file only adds to them.

.PARAMETER Server
  Base url of the running server, e.g. http://127.0.0.1:4567 or http://192.168.31.180:4567.

.PARAMETER Top
  How many of the most used tags (or entities) to mine.

.PARAMETER Entities
  Mine names found in title brackets instead of genre tags.

.PARAMETER Out
  Where to write the candidate json.

.PARAMETER IncludeCovered
  Also mine tags that already have a synonym group (tags mode only; by default they are skipped).

.PARAMETER Batch
  How many terms to send to the model per request.

.EXAMPLE
  ./mine-aliases.ps1 -Server http://127.0.0.1:4567 -Top 200 -Out ..\alias-candidates.json

.EXAMPLE
  ./mine-aliases.ps1 -Server http://127.0.0.1:4567 -Entities -Top 100 -Out ..\entity-candidates.json
#>
[CmdletBinding()]
param(
    [string]$Server = 'http://127.0.0.1:4567',
    [int]$Top = 200,
    [string]$Out = (Join-Path (Get-Location) 'alias-candidates.json'),
    [switch]$Entities,
    [switch]$IncludeCovered,
    [int]$Batch = 20,
    [int]$MaxSynonyms = 4,
    [int]$TitlePage = 1000,
    [int]$MinUses = 2,
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

function Get-LibraryTitles {
    param([int]$PageSize = 1000)

    $titles = New-Object System.Collections.Generic.List[string]
    $offset = 0
    while ($true) {
        $data = Invoke-Gql 'query($first:Int!,$offset:Int!){ mangas(condition:{inLibrary:true}, first:$first, offset:$offset){ nodes{ title } } }' @{ first = $PageSize; offset = $offset }
        $nodes = @($data.mangas.nodes)
        if ($nodes.Count -eq 0) { break }
        foreach ($node in $nodes) { $titles.Add($node.title) }
        $offset += $nodes.Count
        Write-Host ("titles: {0}" -f $offset)
    }

    return $titles
}

# Bracketed names inside a title; the release-metadata brackets are skipped.
function Test-EntityCandidate {
    param([string]$Value)

    if ($Value.Length -lt 2 -or $Value.Length -gt 40) { return $false }
    if ($Value -match '^\d+([-.–]\d+)?$') { return $false }
    if ($Value -match '^[0-9a-zA-Z ._\-]{1,6}$') { return $false }
    if ($Value -match '汉化|漢化|翻译|翻譯|翻訳|上色|嵌字|修图|修圖|整合|中文|中国語|chinese|dl版|无修正|無修正|有码|無碼|无码|digital|ai生成|ai翻译|ai翻譯|机翻|機翻|commission|掃圖|扫图|汉化组|漢化組|個人漢化|个人汉化|日語社|日语社|简体|簡體|繁體|繁体|國語|国语') { return $false }
    # English metadata / platform names: they are credits, not series or characters
    if ($Value -match '(?i)^\s*(ai[\s-]?generated|ai[\s-]?art|patreon|fanbox|fantia|pixiv|twitter|x\.com|commission(ed)?|request|sample|preview|re[\s-]?upload|digital|uncensored|full[\s-]?colou?r|translated|english|chinese|japanese|korean|original|doujin|adult[\s-]?comic|comic|manga|ebook)\s*$') { return $false }
    return $true
}

function Get-EntityCounts {
    param([string[]]$Titles)

    $counts = @{}
    $pattern = '[（(【\[](?<value>[^（()）【】\[\]]{2,40})[）)】\]]'

    foreach ($title in $Titles) {
        if (-not $title) { continue }
        foreach ($match in [regex]::Matches($title, $pattern)) {
            $value = $match.Groups['value'].Value.Trim()
            if (-not (Test-EntityCandidate $value)) { continue }
            if ($counts.ContainsKey($value)) { $counts[$value]++ } else { $counts[$value] = 1 }
        }
    }

    return $counts
}

$tagPrompt = @"
你是成人漫画库的搜索助手。用户会给你一批库里已有的标签/词，请给出用户可能用来搜同一个意思的**其它说法**（中文口语、俗称、繁简差异、日文、英文缩写），每个最多 $MaxSynonyms 个。
只输出 JSON 对象，键是原词，值是说法数组，例如 {"巨乳":["大奶","大胸"]}。
规则：
1. 不要重复原词本身；不要给同义但含义不同的词（颜色词不要扩到颜色中性的词，子集词不要扩到父集，例如"白丝"不要给"长筒袜"）。
2. 只给真正的同义说法；拿不准就给空数组。
3. 不要输出解释。
"@

$entityPrompt = @"
你是成人同人漫画库的搜索助手。下面是一批作品/系列/角色名（一般来自标题的括号）。请给出用户可能用来搜它们的**其它写法**：中文译名、日文原名、英文名、常见缩写，每个最多 $MaxSynonyms 个。
只输出 JSON 对象，键是原词，值是写法数组，例如 {"ブルーアーカイブ":["蔚蓝档案","碧蓝档案","Blue Archive"]}。
规则：
1. 不要重复原词本身；只给同一个作品/角色的叫法，不要给名字相似但不同的作品。
2. 日文的常见缩写也要给（例如 ブルーアーカイブ→ブルアカ、艦隊これくしょん→艦これ、アイドルマスター→アイマス）；拿不准就给空数组。
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

$mode = if ($Entities) { 'entities' } else { 'tags' }
Write-Host "server=$Server mode=$mode model=$Model top=$Top batch=$Batch"

# --- what to mine ---
if ($Entities) {
    $titles = Get-LibraryTitles -PageSize $TitlePage
    Write-Host ("loaded {0} titles" -f $titles.Count)
    $counts = Get-EntityCounts -Titles $titles
    $items = @($counts.GetEnumerator() |
        Where-Object { $_.Value -ge $MinUses } |
        Sort-Object Value -Descending |
        Select-Object -First $Top |
        ForEach-Object { [pscustomobject]@{ tag = $_.Key; uses = $_.Value } })
} else {
    $stats = @((Invoke-Gql 'query($limit:Int!){ searchTagStats(limit:$limit){ tag uses inAliasGroup aliasTargets } }' @{ limit = $Top }).searchTagStats)
    $items = @($stats | Where-Object { $_.uses -ge $MinUses })
    if (-not $IncludeCovered) { $items = @($items | Where-Object { -not $_.inAliasGroup }) }
}

Write-Host ("mining {0} {1}" -f $items.Count, $mode)
if ($items.Count -eq 0) { Write-Host 'nothing to mine'; return }

# --- ask the model for other ways to write them, batch by batch ---
$prompt = if ($Entities) { $entityPrompt } else { $tagPrompt }
$proposals = @{}
for ($i = 0; $i -lt $items.Count; $i += $Batch) {
    $end = [Math]::Min($i + $Batch, $items.Count) - 1
    $names = @($items[$i..$end] | ForEach-Object { $_.tag })
    $user = '名称：' + ($names | ConvertTo-Json -Compress -Depth 3)

    try {
        $answer = Invoke-Chat $prompt $user
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

    Write-Host ("batch {0}-{1} done, {2} items with proposals" -f $i, $end, $proposals.Count)
}

if ($proposals.Count -eq 0) { Write-Host 'the model proposed nothing'; return }

# --- check what the library already matches and build the groups ---
$allTerms = @($proposals.Keys)
foreach ($proposal in $proposals.Values) { $allTerms += @($proposal) }
$hits = Get-SearchHits $allTerms

$groups = @()
$review = @()
foreach ($name in $proposals.Keys) {
    $uses = ($items | Where-Object { $_.tag -eq $name }).uses
    $canonicalHits = $hits[$name]

    if ($Entities) {
        # every variant is kept: the ones that already match are the cross-language names to merge, the
        # ones that do not are entry points users can type
        $kept = @($proposals[$name])
        $dropped = @()
    } else {
        $kept = @($proposals[$name] | Where-Object { $hits[$_] -eq 0 })
        $dropped = @($proposals[$name] | Where-Object { $hits[$_] -ne 0 })
    }

    $detail = ($kept | ForEach-Object { "$_ hits=$($hits[$_])" }) -join '; '
    $review += ('{0} (uses {1}, hits {2}) + {3}' -f $name, $uses, $canonicalHits, $detail)
    if ($dropped.Count -gt 0) { $review += ('    skipped (already matches): ' + (($dropped | ForEach-Object { "$_ ($($hits[$_]))" }) -join ', ')) }

    if ($kept.Count -eq 0) { continue }

    $groups += [ordered]@{
        terms  = @($name) + $kept
        source = if ($Entities) { 'llm-entity' } else { 'llm' }
        note   = "$name uses=$uses hits=$canonicalHits; $detail"
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
