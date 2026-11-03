# 连接指南

APK 的 **设置** 页将连接分为两个入口：**局域网**和 **Cloudflare 远程**。两种方式连接同一个电脑插件；配对后保存连接密钥，切换地址不改变 Agent 或会话记录。

## 局域网排查顺序

1. 确认电脑插件日志显示接口已启动。
2. `listen_host` 必须为 `0.0.0.0`，默认 `127.0.0.1` 不能被同网手机访问。
3. 手机、电脑必须能相互通信；同 Wi-Fi 名称仍可能受到访客网络/AP 隔离影响。
4. 插件通过 mDNS/DNS-SD 的 `_astrbot-connect._tcp.local.` 广播电脑名和端口，不广播密钥。
5. Windows 防火墙允许专用网络上的 Python/AstrBot 通信。默认 TCP 8765，发现 UDP 5353。
6. 若自动发现失败，电脑 `ipconfig` 查看 IPv4，手机向导手动填写 `http://电脑IP:8765`。
7. 配对前，电脑配置启用 `enable_pairing`，保存并重载；读取日志中的 6 位码。2 分钟内输入。
8. 显示成功后先发一条普通聊天消息，再测试设备权限。

插件没有自动修改你的防火墙、路由器或 Windows 网络配置。

## Cloudflare 临时隧道

先确认插件已启动。在 Windows PowerShell 执行：

```powershell
winget install --id Cloudflare.cloudflared --exact
```

安装完重新打开终端：

```powershell
cloudflared tunnel --url http://127.0.0.1:8765
```

等待终端出现 `https://….trycloudflare.com`。手机打开远程连接向导，填写这个完整 HTTPS 地址及电脑配对码。

临时隧道需保持该进程运行，重启地址可能变化，不提供持续可用保证。若本机 cloudflared 已有配置文件，Quick Tunnel 行为可能受影响；按 Cloudflare Quick Tunnels 官方文档检查配置。

## Cloudflare 固定域名隧道

1. 将自己的域名接入 Cloudflare DNS。
2. 登录 Cloudflare One 控制台，进入 **Networks → Connectors / Cloudflare Tunnels**。界面名称可能随版本变化。
3. 选择创建 Tunnel，类型 Cloudflared，输入名称。
4. 选择 Windows 环境，执行控制台给出的安装命令。命令里的 token 属于隧道，不属于插件。
5. 等待连接器 Healthy。长期运行可按控制台说明安装 Windows 服务。
6. 创建 Published application route / Public hostname，例如 `astrbot.example.com`。
7. Service 类型 **HTTP**，目标 **localhost:8765**。如果插件端口改过，这里也要改。
8. 电脑插件开启一次性配对；手机填写 `https://astrbot.example.com` 和配对码。
9. 保存成功后切换手机移动网络，再发送普通聊天验证异地访问。

隧道转发的是手机插件 API，不是 AstrBot 管理面板。首版不支持 Cloudflare Access 登录页；若域名前置了 Access，需要自行选择与客户端兼容的接入方式，本版本不提供 Access token 配置。

## 常见错误

| 现象 | 检查项 |
| --- | --- |
| 自动搜索为空 | 监听地址、UDP 5353、防火墙、访客网络、AP 隔离；改手动 IP 验证 |
| 连接超时 | 电脑是否开机，AstrBot 是否运行，地址/端口是否正确 |
| 配对码失效 | 重新生成；有效期 2 分钟；超过 5 次错误尝试会关闭窗口 |
| 401 | 连接密钥被更换或保存失败；重新配对 |
| 403 电脑/手机控制 | 检查插件总开关、APK 限时授权及本条消息的设备勾选 |
| Cloudflare 502 | cloudflared 无法访问本机插件，检查目标地址和插件日志 |
| Cloudflare 1033 | 隧道连接器离线或路由指向错误隧道 |
| 返回网页而非 JSON | 地址错误或 Cloudflare Access 登录拦截 |
| 电脑进入睡眠 | 连接和任务会中断；需要在 Windows 自行设置电源计划 |
| 手机后台停止 | 检查通知权限、电池限制、无障碍服务、厂商自启动设置 |

## 官方参考

- [Wallpaper Engine 手机配对说明](https://help.wallpaperengine.io/en/mobile/pairing.html)
- [Cloudflare Quick Tunnels](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/)
- [Cloudflare Tunnel 文档](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/)
- [Android 网络服务发现](https://developer.android.com/develop/connectivity/wifi/use-nsd)

参考这些产品的交互与官方接入方式，不表示本项目与它们存在合作关系。

