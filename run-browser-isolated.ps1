param([switch]$GuardOnly)

# Test-only Windows/JDK21 app. Never loads .env, production config or NAS resources.
$ErrorActionPreference = 'Stop'
$savedBrowserEnvironment = @{}
Get-ChildItem Env: | ForEach-Object { $savedBrowserEnvironment[$_.Name] = $_.Value }
$browserAllowedEnvironment = @(
    'PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP',
    'USERPROFILE', 'HOME', 'HOMEDRIVE', 'HOMEPATH', 'APPDATA', 'LOCALAPPDATA',
    'PROGRAMFILES', 'PROGRAMFILES(X86)', 'PROGRAMDATA', 'JAVA_HOME', 'MAVEN_HOME', 'M2_HOME'
)
$browserExit = 1
Push-Location $PSScriptRoot
try {
    Get-ChildItem Env: | Where-Object { $_.Name -notin $browserAllowedEnvironment } |
        ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }
    $env:MAVEN_SKIP_RC = '1'
    if (-not $env:JAVA_HOME) { throw 'JAVA_HOME must point to JDK 21.' }
    $browserJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
    $browserVersion = (& $browserJava -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $browserVersion -notmatch 'version "21[.\"]') {
        throw 'This harness requires JDK 21.'
    }
    $browserMaven = (Get-Command mvn.cmd -ErrorAction Stop).Source
    # Compiles all test-only classes and obtains Maven's exact resolved test classpath.
    & $browserMaven -o -Pisolated-tests '-Dtest=TestNetworkGuardTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Compile/network-guard test failed.' }
    [xml]$browserReport = Get-Content -LiteralPath 'target/surefire-reports/TEST-com.hyunchang.webapp.isolation.TestNetworkGuardTest.xml' -Raw
    $browserClasspath = ($browserReport.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
    if (-not $browserClasspath) { throw 'Maven test classpath was not found.' }
    $browserAgent = ($browserClasspath -split ';' | Where-Object { $_ -match '[\\/]byte-buddy-agent-[^\\/]+\.jar$' } | Select-Object -First 1)
    if (-not $browserAgent) { throw 'Byte Buddy agent was not found in test classpath.' }
    $browserRuntime = Join-Path $PSScriptRoot ('target/browser-runtime-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $browserRuntime | Out-Null
    $browserArgs = @(
        "-javaagent:$browserAgent",
        '-Djava.security.manager=com.hyunchang.browser.BrowserNetworkGuard',
        '-Dio.netty.transport.noNative=true',
        '-Dfile.encoding=UTF-8',
        '--add-opens=java.base/java.lang=ALL-UNNAMED',
        "-Duser.dir=$browserRuntime",
        '-cp', $browserClasspath,
        'com.hyunchang.browser.BrowserApplication'
    )
    if ($GuardOnly) { $browserArgs += '--guard-only' }
    Write-Host '[browser-isolated] Only 127.0.0.1:13200 inbound; all outbound sockets denied.'
    Write-Host '[browser-isolated] H2 memory, test config, no schedulers; stop with Ctrl+C.'
    Push-Location $browserRuntime
    try { & $browserJava @browserArgs; $browserExit = $LASTEXITCODE }
    finally { Pop-Location }
} catch {
    Write-Host "[incomplete] $($_.Exception.Message)"
    $browserExit = 1
} finally {
    Get-ChildItem Env: | ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }
    foreach ($browserEnvName in $savedBrowserEnvironment.Keys) {
        Set-Item -LiteralPath "Env:$browserEnvName" -Value $savedBrowserEnvironment[$browserEnvName]
    }
    Pop-Location
}
exit $browserExit
