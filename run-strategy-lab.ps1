param([switch]$Fixture)
$ErrorActionPreference = 'Stop'
$labPython = (Get-Command python -CommandType Application | Select-Object -First 1).Source
if (-not $labPython) { throw 'Python 3.11 or later is required.' }
$labSavedEnvironment = @{}
Get-ChildItem Env: | ForEach-Object { $labSavedEnvironment[$_.Name] = $_.Value }
$labAllowed = @('PATH', 'PATHEXT', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE', 'LOCALAPPDATA', 'APPDATA', 'COMSPEC', 'NUMBER_OF_PROCESSORS', 'PROCESSOR_ARCHITECTURE')
try {
    Get-ChildItem Env: | Where-Object { $labAllowed -notcontains $_.Name } | ForEach-Object { Remove-Item -LiteralPath ('Env:' + $_.Name) }
    $env:PYTHONUTF8 = '1'
    $labArguments = @('-I', (Join-Path $PSScriptRoot 'tools/research_lab/server.py'))
    if ($Fixture) { $labArguments += '--fixture' }
    & $labPython @labArguments
    if ($LASTEXITCODE -ne 0) { throw "Research service exited with code $LASTEXITCODE" }
} finally {
    Get-ChildItem Env: | ForEach-Object { Remove-Item -LiteralPath ('Env:' + $_.Name) }
    foreach ($labName in $labSavedEnvironment.Keys) { Set-Item -LiteralPath ('Env:' + $labName) -Value $labSavedEnvironment[$labName] }
}
