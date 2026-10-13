# 手机热点与真机验收

本阶段沿用前端 3000、后端 9333。只使用可信的个人热点/局域网；生产地址、权限、Cookie 和登录加密协议不变。

## Windows 启动

在 `frontend` 目录执行：

```powershell
npm run dev:lan
```

需要构建版时，先 `npm run build`，再 `npm run preview:lan`。先确认 3000 没有其他前端占用，不能让 Nuxt 自动换端口。默认 `dev`、`preview` 仍仅监听 127.0.0.1。测试专用 Showcase 需原有 `NUXT_PUBLIC_DESIGN_SHOWCASE_ENVIRONMENT=test` 配置，不在生产开放。

取得当前热点网卡与 IPv4，而非使用 `0.0.0.0` 作为访问地址：

```powershell
Get-NetIPConfiguration | Select-Object InterfaceAlias,IPv4Address,IPv4DefaultGateway
Get-NetTCPConnection -State Listen -LocalPort 3000,9333
```

手机访问 `http://<电脑热点IPv4>:3000/`。本次实际地址为 `http://192.168.43.236:3000/`，地址随 DHCP 改变，不写入项目配置。

客户端 API 使用同源 `/api/v1/...`，Nuxt/Nitro 代理访问电脑上的 127.0.0.1:9333；手机无需访问后端端口。资源仍由现有投影/OSS 体系提供，不将临时地址写回正文。

## 最小防火墙规则

在管理员 PowerShell 中，从项目根目录执行，PID 使用上一步 3000 的真实进程号：

```powershell
./scripts/windows/enable-dev-lan.ps1 -InterfaceAlias 'WLAN' -ExpectedFrontendPid <3000的PID>
```

脚本确认该端口仍属于指定 Node 进程，只允许当前网卡、当前私网 IPv4、/24 或更窄热点子网、该 Node 程序和 TCP 3000。不会放行 9333、修改网络类型或关闭防火墙。网卡名称不同则替换 `WLAN`。同名规则如不属于本工具，脚本拒绝覆盖。

本次已设置规则 `MaiPortfolio-DevHotspot-3000`，范围为 WLAN / 192.168.43.236 / 192.168.43.0/24 / TCP 3000。维护者 Mate60 Pro 已实际访问并报告测试通过。iPad 9 不在手边，尚未实机验证。

结束验收时，在管理员 PowerShell 删除本次规则：

```powershell
Remove-NetFirewallRule -Name 'MaiPortfolio-DevHotspot-3000'
```

前台开发服务用 Ctrl+C 停止；其他启动方式先核实 3000 所属进程，再停止该进程。随后使用默认 `npm run dev` 恢复仅本机访问。不要终止无关 Node 或后端进程。热点重连后检查 IP，必要时重新运行脚本更新本次规则。

## 连接排查与验收

先在电脑访问本机 3000，再访问电脑热点 IPv4；然后用手机访问同一 IPv4。核实两端在同一热点、前端监听 0.0.0.0:3000、防火墙规则与当前 IP/程序一致。VPN、热点客户端隔离、系统网络策略均可能阻止访问；不通过公网隧道绕过限制。HMR 是开发体验，正式阅读对照优先使用构建版。

真机依次检查首页、博客列表、故障文章、连续滚动、目录展开与最后章节定位、章节折叠、代码主题和复制、图片/附件、Showcase Mermaid、横竖屏切换、403/404。在 `/design-showcase#device-info` 可复制真实视口、DPR、浏览器及安全上下文信息；不要将模拟设备截图当作硬件验证。

本次 HTTP 仅用于临时阅读验收。手机的 HTTP 私网来源不是安全上下文，现有 WebCrypto RSA 登录依赖 `crypto.subtle`，且后端保留原有来源保护。因此没有降级登录加密或放宽生产安全配置；手机登录/管理的完整验收仍需受信任 HTTPS。可使用 Nuxt 当前 CLI 的 `--https.cert`、`--https.key` 配合手机信任的开发证书，证书及私钥不提交仓库。电脑 localhost 上的隔离管理 fixture 回归与真实手机登录是不同覆盖项。
