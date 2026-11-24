param([string]$InputFile, [switch]$Causal, [switch]$Selection)

# Test-only production-code replay. No application bootstrap, secrets or live connections.
$ErrorActionPreference = 'Stop'
$replayExit = 1
$savedReplayEnvironment = @{}
Get-ChildItem Env: | ForEach-Object { $savedReplayEnvironment[$_.Name] = $_.Value }
$replayAllowed = @('PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP',
    'USERPROFILE', 'HOME', 'HOMEDRIVE', 'HOMEPATH', 'APPDATA', 'LOCALAPPDATA',
    'PROGRAMFILES', 'PROGRAMFILES(X86)', 'PROGRAMDATA', 'JAVA_HOME', 'MAVEN_HOME', 'M2_HOME')
Push-Location $PSScriptRoot
try {
    if ($Causal -and $Selection) { throw 'Choose either -Causal or -Selection.' }
    if ($Selection -and $InputFile) { throw '-Selection runs the fixed comparison matrix; external input is not supported.' }
    Get-ChildItem Env: | Where-Object { $_.Name -notin $replayAllowed } |
        ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }
    $env:MAVEN_SKIP_RC = '1'
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 21.' }
    $replayJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
    $replayVersion = (& $replayJava -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $replayVersion -notmatch 'version "21[.\"]') {
        throw 'The isolated replay guard requires JDK 21.'
    }
    $replayMaven = (Get-Command mvn.cmd -ErrorAction Stop).Source
    if ($Causal -or $Selection) { $replayPython = (Get-Command python -ErrorAction Stop).Source }
    $replayRoot = if ($Selection) { 'target/us-selection-replay/run-' } elseif ($Causal) { 'target/us-causal-replay/run-' } else { 'target/us-production-replay/run-' }
    $replaySuite = if ($Selection) { 'KiwoomUsSelectionReplayTest' } elseif ($Causal) { 'KiwoomUsReplayLedgerTest,KiwoomUsCausalReplayTest' } else { 'KiwoomUsProductionReplayTest' }
    $replayProperty = if ($Selection) { 'us.selection' } elseif ($Causal) { 'us.causal' } else { 'us.replay' }
    $replayFolder = $replayRoot + [Guid]::NewGuid().ToString('N')
    $replayOutput = $replayFolder + '/report.json'
    $replayArgs = @('-o', '-Pisolated-tests', ('-Dtest=' + $replaySuite),
        ('-D' + $replayProperty + '.output=' + $replayOutput), 'test')
    if ($InputFile) {
        $replaySource = Get-Item -LiteralPath $InputFile -ErrorAction Stop
        if ($replaySource.PSIsContainer -or $replaySource.Extension -ne '.json' -or
            $replaySource.Length -gt 20MB) {
            throw 'Input must be a JSON snapshot file no larger than 20 MB.'
        }
        New-Item -ItemType Directory -Path $replayFolder -Force | Out-Null
        # Only generated, shell-safe relative paths are passed through mvn.cmd.
        $replayInput = $replayFolder + '/input.json'
        Copy-Item -LiteralPath $replaySource.FullName -Destination $replayInput
        $replayArgs += ('-D' + $replayProperty + '.input=' + $replayInput)
    } else {
        New-Item -ItemType Directory -Path $replayFolder -Force | Out-Null
    }
    Write-Host '[isolated replay] Production decision code with in-memory broker/repositories.'
    Write-Host '[isolated replay] JVM network/process/credential guard enabled; no application startup.'
    if ($Causal -or $Selection) {
        Write-Host '[causal ledger] Orders generate simulated fills, cash and holdings; no scripted account updates.'
    }
    & $replayMaven @replayArgs
    $replayExit = $LASTEXITCODE
    if ($replayExit -eq 0 -and -not (Test-Path -LiteralPath $replayOutput)) {
        throw 'Tests finished without a replay report. Replay is incomplete.'
    }
    if ($replayExit -eq 0 -and ($Causal -or $Selection)) {
        $replayAuditLines = & $replayPython -B tools/team19_audit.py $replayOutput
        $replayAuditExit = $LASTEXITCODE
        $replayAuditText = $replayAuditLines -join "`n"
        $replayAuditText | Set-Content -LiteralPath ($replayFolder + '/audit.json') -Encoding utf8
        if ($replayAuditExit -ne 0) { throw 'Independent ledger audit failed. See audit.json.' }
        $replayAuditDocument = $replayAuditText | ConvertFrom-Json
        if ($replayAuditDocument.status -ne 'PASS') { throw 'Independent ledger audit did not report PASS.' }
        Write-Host '[passed] Independent event accounting audit: audit.json'
    }
    if ($replayExit -eq 0 -and $Selection) {
        $selectionAnalysisLines = & $replayPython -B tools/team20_analysis.py $replayOutput
        $selectionAnalysisExit = $LASTEXITCODE
        $selectionAnalysisText = $selectionAnalysisLines -join "`n"
        $selectionAnalysisText | Set-Content -LiteralPath ($replayFolder + '/analysis.json') -Encoding utf8
        if ($selectionAnalysisExit -ne 0) { throw 'Selection comparison analysis failed. See analysis.json.' }
        Write-Host '[passed] Synthetic selection comparison: analysis.json (not historical performance).'
    }
    if ($replayExit -eq 0) {
        Write-Host ('[passed] Replay report: ' + (Join-Path $PSScriptRoot $replayOutput))
        Write-Host '[scope] Logic validation is not evidence of market returns or live execution.'
    } else {
        Write-Host '[failed] Replay failed. Review the test failures; no live order was sent.'
    }
} catch {
    $replayExit = 1
    Write-Host "[incomplete] $($_.Exception.Message)"
} finally {
    Get-ChildItem Env: | ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }
    foreach ($replayEnvName in $savedReplayEnvironment.Keys) {
        Set-Item -LiteralPath "Env:$replayEnvName" -Value $savedReplayEnvironment[$replayEnvName]
    }
    Pop-Location
}
exit $replayExit
