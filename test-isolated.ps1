param([switch]$Verify)

# Dedicated test entry point. Does not read .env or start the application.
$ErrorActionPreference = 'Stop'
$testExit = 1
$savedEnvironment = @{}
Get-ChildItem Env: | ForEach-Object { $savedEnvironment[$_.Name] = $_.Value }
$allowedEnvironment = @(
    'PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP',
    'USERPROFILE', 'HOME', 'HOMEDRIVE', 'HOMEPATH', 'APPDATA', 'LOCALAPPDATA',
    'PROGRAMFILES', 'PROGRAMFILES(X86)', 'PROGRAMDATA', 'JAVA_HOME', 'MAVEN_HOME', 'M2_HOME'
)
Push-Location $PSScriptRoot
try {
    Get-ChildItem Env: | Where-Object { $_.Name -notin $allowedEnvironment } |
        ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }
    $env:MAVEN_SKIP_RC = '1'
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 installation before testing.' }
    $testJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
    if (-not (Test-Path -LiteralPath $testJava)) { throw 'JAVA_HOME does not contain bin/java.exe.' }
    $testJavaVersion = (& $testJava -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $testJavaVersion -notmatch 'version "21[.\"]') {
        throw 'This isolated test guard requires JDK 21. No tests were started.'
    }
    $testMaven = (Get-Command mvn.cmd -ErrorAction Stop).Source
    $testGoal = if ($Verify) { 'verify' } else { 'test' }
    Write-Host '[isolated] JDK 21; full application not started; dotenv files not loaded.'
    Write-Host '[isolated] Test JVM sockets/DNS/listeners blocked; DB is mock or disposable H2.'
    Write-Host '[isolated] Inherited application secrets/JVM options removed; Maven offline.'
    Write-Host "[running] mvn -o -Pisolated-tests $testGoal (test fork limit: 180 seconds)"
    & $testMaven -o -Pisolated-tests $testGoal
    $testExit = $LASTEXITCODE
    if ($testExit -eq 0) {
        Write-Host '[passed] All checks in the selected Maven lifecycle passed.'
    } else {
        Write-Host '[failed] Test/build/environment failure. See output and target/surefire-reports.'
    }
} catch {
    Write-Host "[incomplete] $($_.Exception.Message)"
    $testExit = 1
} finally {
    Get-ChildItem Env: | ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }
    foreach ($testEnvName in $savedEnvironment.Keys) {
        Set-Item -LiteralPath "Env:$testEnvName" -Value $savedEnvironment[$testEnvName]
    }
    Pop-Location
}
exit $testExit
