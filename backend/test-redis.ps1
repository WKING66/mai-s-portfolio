Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$configPath = Join-Path $PSScriptRoot '..\.env'
if (-not (Test-Path -LiteralPath $configPath)) {
    throw '请先在仓库根目录创建被 Git 忽略的 .env 文件。'
}

$settings = @{}
Get-Content -LiteralPath $configPath | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        $settings[$matches[1]] = $matches[2].Trim().Trim('"', "'")
    }
}

$javaHome = if ($settings.ContainsKey('JAVA_21_HOME') `
    -and -not [string]::IsNullOrWhiteSpace($settings['JAVA_21_HOME'])) {
    $settings['JAVA_21_HOME']
} else {
    $env:JAVA_HOME
}
$javaRelease = if ([string]::IsNullOrWhiteSpace($javaHome)) { $null } else { Join-Path $javaHome 'release' }
if ([string]::IsNullOrWhiteSpace($javaRelease) -or -not (Test-Path -LiteralPath $javaRelease) `
    -or -not (Select-String -LiteralPath $javaRelease -Pattern '^JAVA_VERSION="21(?:\.|\")' -Quiet)) {
    throw '项目要求 Java 21。请设置 JAVA_HOME，或在 .env 中设置 JAVA_21_HOME。'
}
[Environment]::SetEnvironmentVariable('JAVA_HOME', $javaHome, 'Process')
[Environment]::SetEnvironmentVariable('PATH',
    ((Join-Path $javaHome 'bin') + [IO.Path]::PathSeparator + $env:PATH), 'Process')

foreach ($name in @('REDIS_HOST', 'REDIS_PORT')) {
    if (-not $settings.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($settings[$name])) {
        throw ".env 缺少 $name"
    }
    [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
}

foreach ($name in @('REDIS_PASSWORD', 'REDIS_DATABASE', 'REDIS_CONNECT_TIMEOUT',
    'REDIS_COMMAND_TIMEOUT')) {
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
}
$redisDefaults = @{
    REDIS_DATABASE = '0'
    REDIS_CONNECT_TIMEOUT = '3s'
    REDIS_COMMAND_TIMEOUT = '3s'
}
foreach ($name in $redisDefaults.Keys) {
    $value = if ($settings.ContainsKey($name) -and -not [string]::IsNullOrWhiteSpace($settings[$name])) {
        $settings[$name]
    } else {
        $redisDefaults[$name]
    }
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
if ($settings.ContainsKey('REDIS_PASSWORD') `
    -and -not [string]::IsNullOrWhiteSpace($settings['REDIS_PASSWORD'])) {
    [Environment]::SetEnvironmentVariable('REDIS_PASSWORD', $settings['REDIS_PASSWORD'], 'Process')
}
[Environment]::SetEnvironmentVariable('RUN_REDIS_INTEGRATION_TEST', 'true', 'Process')

$mavenCommand = Get-Command mvn.cmd -ErrorAction Stop
$testExitCode = 0
Push-Location $PSScriptRoot
try {
    & $mavenCommand.Source -B `
        -pl 'mai-portfolio-framework/mai-portfolio-spring-boot-starter-security' `
        -am `
        '-Dtest=RedissonIntegrationTest,NamespacedSaTokenDaoIntegrationTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' `
        test
    $testExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $testExitCode
