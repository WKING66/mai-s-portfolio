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
foreach ($name in @('PSQL_HOST', 'PSQL_PORT', 'PSQL_USERNAME', 'PSQL_PASSWORD', 'OWNER_PASSWORD')) {
    if (-not $settings.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($settings[$name])) {
        throw ".env 缺少 $name"
    }
    [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
}

# 开发配置固定连接独立的 portfolio_dev，不读取 .env 中可能存在的其他库名。
$env:MEDIA_STORAGE = 'local'
$mavenCommand = Get-Command mvn.cmd -ErrorAction Stop
& $mavenCommand.Source spring-boot:run '-Dspring-boot.run.profiles=dev'
exit $LASTEXITCODE
