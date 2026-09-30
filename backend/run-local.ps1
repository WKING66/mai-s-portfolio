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

foreach ($name in @('PSQL_HOST', 'PSQL_PORT', 'PSQL_USERNAME', 'PSQL_PASSWORD', 'OWNER_PASSWORD')) {
    if (-not $settings.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($settings[$name])) {
        throw ".env 缺少 $name"
    }
    [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
}

$redisDefaults = @{
    REDIS_HOST = '127.0.0.1'
    REDIS_PORT = '6379'
    REDIS_DATABASE = '0'
    REDIS_CONNECT_TIMEOUT = '3s'
    REDIS_COMMAND_TIMEOUT = '3s'
    REDIS_KEY_PREFIX = 'mai-portfolio'
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
} else {
    [Environment]::SetEnvironmentVariable('REDIS_PASSWORD', $null, 'Process')
}

# 开发配置固定连接独立的 portfolio_dev，不读取 .env 中可能存在的其他库名。
$mediaStorage = if ($settings.ContainsKey('MEDIA_STORAGE') `
    -and -not [string]::IsNullOrWhiteSpace($settings['MEDIA_STORAGE'])) {
    $settings['MEDIA_STORAGE'].ToLowerInvariant()
} else {
    'oss'
}
if ($mediaStorage -notin @('local', 'oss')) {
    throw 'MEDIA_STORAGE 只允许 local 或 oss'
}
[Environment]::SetEnvironmentVariable('MEDIA_STORAGE', $mediaStorage, 'Process')

foreach ($name in @('MEDIA_LOCAL_DIRECTORY', 'OSS_ENDPOINT', 'OSS_BUCKET_NAME',
    'OSS_ACCESS_KEY_ID', 'OSS_ACCESS_KEY_SECRET', 'OSS_CONNECT_TIMEOUT',
    'OSS_SOCKET_TIMEOUT', 'OSS_MAX_CONNECTIONS')) {
    if ($settings.ContainsKey($name) -and -not [string]::IsNullOrWhiteSpace($settings[$name])) {
        [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
    }
}

if ($mediaStorage -eq 'oss') {
    foreach ($name in @('OSS_ENDPOINT', 'OSS_BUCKET_NAME', 'OSS_ACCESS_KEY_ID',
        'OSS_ACCESS_KEY_SECRET')) {
        if (-not $settings.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($settings[$name])) {
            throw "OSS 模式下 .env 缺少 $name"
        }
    }
}

$mavenCommand = Get-Command mvn.cmd -ErrorAction Stop
& $mavenCommand.Source -B -DskipTests install
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
& $mavenCommand.Source -f 'mai-portfolio-launch/pom.xml' spring-boot:run '-Dspring-boot.run.profiles=dev'
exit $LASTEXITCODE
