# Fills the structured title index (MangaLlmIndexTable) in the background, batch by batch.
#
# The server resolves its own LLM settings (server.llmQueryEnabled / llmBaseUrl / llmApiKey), so this script
# only needs the API url. Each call extracts at most -Limit books; the loop stops when a batch comes back
# smaller than the limit, which means nothing is pending anymore.
#
#   powershell -File scripts\structure-titles.ps1 -Server http://127.0.0.1:4569 -DryRun -Limit 40
#   powershell -File scripts\structure-titles.ps1 -Server http://127.0.0.1:4567 -Limit 200 -Out title-structure.jsonl
#
param(
    [string]$Server = 'http://127.0.0.1:4567',
    [int]$Limit = 200,
    [switch]$DryRun,
    [string]$Out = 'title-structure.jsonl',
    [int]$MaxBatches = 0
)

$ErrorActionPreference = 'Stop'

# A dry run writes nothing, so the cursor would never move and the loop would repeat the same books.
if ($DryRun -and $MaxBatches -eq 0) {
    $MaxBatches = 1
    Write-Host 'dry run: previewing one batch (nothing is stored)'
}

$Query = @'
mutation($limit:Int!,$dryRun:Boolean!){ llmStructureTitles(dryRun:$dryRun, limit:$limit){
  enabled dryRun version candidates asked written
  rows { mangaId title cleanTitle series characters circle artist lang aliases flags rejects }
} }
'@

function Invoke-GraphQl([string]$Payload) {
    # Invoke-RestMethod decodes a JSON body without charset as Latin-1 on PowerShell 5.1, which turns every
    # Chinese title into mojibake. Read the raw stream as UTF-8 instead.
    $request = [Net.HttpWebRequest]::Create("$Server/api/graphql")
    $request.Method = 'POST'
    $request.ContentType = 'application/json'
    $bytes = [Text.Encoding]::UTF8.GetBytes($Payload)
    $request.ContentLength = $bytes.Length
    $stream = $request.GetRequestStream()
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Close()
    $response = $request.GetResponse()
    $reader = New-Object IO.StreamReader($response.GetResponseStream(), [Text.Encoding]::UTF8)
    try {
        return ($reader.ReadToEnd() | ConvertFrom-Json)
    } finally {
        $reader.Close()
        $response.Close()
    }
}

$utf8 = New-Object Text.UTF8Encoding($false)
$batch = 0
$totalRows = 0
$emptyBatches = 0

while ($true) {
    $batch++
    $payload = @{
        query     = $Query
        variables = @{ limit = $Limit; dryRun = [bool]$DryRun }
    } | ConvertTo-Json -Compress -Depth 5

    $response = Invoke-GraphQl $payload
    if ($response.errors) {
        throw ((($response.errors | ForEach-Object { $_.message }) -join '; '))
    }

    $result = $response.data.llmStructureTitles
    if (-not $result.enabled) {
        Write-Host 'LLM is not configured on the server (server.llmQueryEnabled / llmBaseUrl); nothing done.'
        break
    }

    foreach ($row in $result.rows) {
        [IO.File]::AppendAllText($Out, (($row | ConvertTo-Json -Compress -Depth 5) + "`n"), $utf8)
    }
    $totalRows += $result.rows.Count

    Write-Host ("batch {0}: candidates={1} asked={2} written={3} (saved {4})" -f $batch, $result.candidates, $result.asked, $result.written, $totalRows)

    if ($result.candidates -lt $Limit) {
        Write-Host ("done: no more pending books (version {0}, dryRun={1})" -f $result.version, $result.dryRun)
        break
    }
    if ($result.written -eq 0 -and $result.asked -eq 0) {
        $emptyBatches++
        if ($emptyBatches -ge 3) {
            Write-Host 'stopping: three batches in a row had nothing to write, check the server log'
            break
        }
    } else {
        $emptyBatches = 0
    }
    if ($MaxBatches -gt 0 -and $batch -ge $MaxBatches) {
        Write-Host "stopping at the requested batch limit ($MaxBatches)"
        break
    }
}
