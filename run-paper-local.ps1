param([switch]$Fixture, [switch]$SkipCompile)

# Paper-only entry point. No .env, production configuration, account keys or broker beans.
$ErrorActionPreference = 'Stop'
$savedPaperEnvironment = @{}
Get-ChildItem Env: | ForEach-Object { $savedPaperEnvironment[$_.Name] = $_.Value }
$paperAllowedEnvironment = @('PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP',
    'USERPROFILE', 'HOME', 'HOMEDRIVE', 'HOMEPATH', 'APPDATA', 'LOCALAPPDATA',
    'PROGRAMFILES', 'PROGRAMFILES(X86)', 'PROGRAMDATA', 'JAVA_HOME', 'MAVEN_HOME', 'M2_HOME')
$paperExit = 1
Push-Location $PSScriptRoot
try {
    Get-ChildItem Env: | Where-Object { $_.Name -notin $paperAllowedEnvironment } |
        ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }
    $env:MAVEN_SKIP_RC = '1'
    if (-not $env:JAVA_HOME) { throw 'JAVA_HOME must point to JDK 21.' }
    $paperJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
    $paperVersion = (& $paperJava -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $paperVersion -notmatch 'version "21[.\"]') { throw 'JDK 21 is required.' }
    if (-not $SkipCompile) {
        $paperMaven = (Get-Command mvn.cmd -ErrorAction Stop).Source
        & $paperMaven -o -Pisolated-tests '-Dtest=TestNetworkGuardTest' test
        if ($LASTEXITCODE -ne 0) { throw 'Isolated compilation and guard test failed.' }
    }
    [xml]$paperReport = Get-Content -LiteralPath 'target/surefire-reports/TEST-com.hyunchang.webapp.isolation.TestNetworkGuardTest.xml' -Raw
    $paperClasspath = ($paperReport.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
    if (-not $paperClasspath) { throw 'Run the isolated tests or compile once before using -SkipCompile.' }
    $paperFolder = if ($Fixture) { 'paper-fixture' } else { 'paper-local' }
    $paperRuntime = Join-Path $PSScriptRoot ('.paper-runtime/' + $paperFolder)
    New-Item -ItemType Directory -Path $paperRuntime -Force | Out-Null
    $paperArgs = @('-Dfile.encoding=UTF-8', '-Dio.netty.transport.noNative=true', '-cp', $paperClasspath,
        'com.hyunchang.paperlocal.PaperLocalApplication')
    if ($Fixture) { $paperArgs += '--fixture' }
    Write-Host '[paper-only] Loopback 13220. Persistent virtual ledger; no real-order adapter.'
    Write-Host '[paper-only] Keep this terminal running. Stop with Ctrl+C; database is retained.'
    Push-Location $paperRuntime
    try { & $paperJava @paperArgs; $paperExit = $LASTEXITCODE }
    finally { Pop-Location }
} catch {
    Write-Host "[incomplete] $($_.Exception.Message)"
} finally {
    Get-ChildItem Env: | ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }
    foreach ($paperEnvName in $savedPaperEnvironment.Keys) {
        Set-Item -LiteralPath "Env:$paperEnvName" -Value $savedPaperEnvironment[$paperEnvName]
    }
    Pop-Location
}
exit $paperExit
