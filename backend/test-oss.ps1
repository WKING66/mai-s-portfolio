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

foreach ($name in @('OSS_ENDPOINT', 'OSS_BUCKET_NAME', 'OSS_ACCESS_KEY_ID',
    'OSS_ACCESS_KEY_SECRET')) {
    if (-not $settings.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($settings[$name])) {
        throw ".env 缺少 $name"
    }
    [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
}

if ($settings['OSS_BUCKET_NAME'] -ne 'amai-portfolio') {
    throw '真实 OSS 集成测试只允许使用 amai-portfolio Bucket。'
}

if (-not $settings['OSS_ENDPOINT'].StartsWith('https://', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'OSS_ENDPOINT 必须使用 HTTPS 根地址。'
}

[Environment]::SetEnvironmentVariable('RUN_OSS_INTEGRATION_TEST', 'true', 'Process')
$mavenCommand = Get-Command mvn.cmd -ErrorAction Stop
& $mavenCommand.Source -B -pl ':mai-portfolio-spring-boot-starter-storage' -am `
    '-Dtest=AliyunOssObjectStorageIntegrationTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' test
exit $LASTEXITCODE
