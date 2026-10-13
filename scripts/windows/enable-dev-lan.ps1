param(
  [Parameter(Mandatory = $true)][string]$InterfaceAlias,
  [Parameter(Mandatory = $true)][int]$ExpectedFrontendPid,
  [string]$OutputPath
)
$ErrorActionPreference = 'Stop'
$ruleName = 'MaiPortfolio-DevHotspot-3000'
$ruleGroup = 'MaiPortfolio temporary device acceptance'
try {
  $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
  if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run this script from an administrator PowerShell.' }
  $address = @(Get-NetIPAddress -InterfaceAlias $InterfaceAlias -AddressFamily IPv4 | Where-Object { $_.IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)' -and $_.AddressState -eq 'Preferred' })
  if ($address.Count -ne 1 -or $address[0].PrefixLength -lt 24) { throw 'Choose a trusted private interface with one IPv4 address and a /24 or narrower subnet.' }
  $listener = @(Get-NetTCPConnection -LocalPort 3000 -State Listen)
  if (-not $listener -or @($listener | Where-Object OwningProcess -ne $ExpectedFrontendPid).Count) { throw 'Port 3000 no longer belongs to the expected frontend process.' }
  $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedFrontendPid"
  if (-not $process -or [IO.Path]::GetFileName($process.ExecutablePath) -ne 'node.exe') { throw 'The frontend must be the existing Node process.' }
  $bytes = ([Net.IPAddress]::Parse($address[0].IPAddress)).GetAddressBytes()
  for ($index = 0; $index -lt 4; $index++) { $bits = [Math]::Max(0, [Math]::Min(8, $address[0].PrefixLength - 8 * $index)); $bytes[$index] = $bytes[$index] -band (256 - [Math]::Pow(2, 8 - $bits)) }
  $subnet = ([Net.IPAddress]::new([byte[]]$bytes)).ToString() + '/' + $address[0].PrefixLength
  $taskNetworkProfile = (Get-NetConnectionProfile -InterfaceAlias $InterfaceAlias).NetworkCategory.ToString()
  if ($taskNetworkProfile -eq 'DomainAuthenticated') { throw 'This temporary rule is restricted to a personal hotspot/LAN, not a domain network.' }
  $existing = Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
  if ($existing) { if ($existing.Group -ne $ruleGroup) { throw 'A rule with this name is owned by another configuration.' }; Remove-NetFirewallRule -Name $ruleName }
  New-NetFirewallRule -Name $ruleName -DisplayName 'MaiPortfolio temporary hotspot HTTP 3000' -Group $ruleGroup -Direction Inbound -Action Allow -Protocol TCP -LocalPort 3000 -LocalAddress $address[0].IPAddress -RemoteAddress $subnet -InterfaceAlias $InterfaceAlias -Program $process.ExecutablePath -Profile $taskNetworkProfile | Out-Null
  $result = @{ success = $true; url = 'http://' + $address[0].IPAddress + ':3000'; interface = $InterfaceAlias; remoteSubnet = $subnet; localAddress = $address[0].IPAddress; profile = $taskNetworkProfile; program = $process.ExecutablePath; rule = $ruleName; backendPort = 9333 }
  if ($OutputPath) { $result | ConvertTo-Json | Set-Content -LiteralPath $OutputPath -Encoding UTF8 }
  $result | ConvertTo-Json
} catch {
  if ($OutputPath) { @{ success = $false; error = $_.Exception.Message } | ConvertTo-Json | Set-Content -LiteralPath $OutputPath -Encoding UTF8 }
  throw
}
