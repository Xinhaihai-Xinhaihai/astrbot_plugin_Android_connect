# AstrBot Android Connect

把手机变成 Windows AstrBot 的随身入口：聊天、布置后台任务、远程操作电脑，也可以让 AstrBot Agent 反向操作手机。

[下载 APK 与插件 ZIP](https://github.com/Xinhaihai-Xinhaihai/astrbot_plugin_Android_connect/releases) · [Cloudflare 连接指南](docs/CONNECTION.md) · [使用与权限](docs/USAGE.md) · [MIT License](LICENSE)

> **v0.1.1 预发布。** 适用于 Windows 原生运行的 AstrBot 4.29.4+、Python 3.12+、Android 8.0+。电脑控制和手机控制默认关闭，首次使用请完成下面的配对与授权。

## 能做什么

| 功能 | 首版实现 |
| --- | --- |
| 手机聊天 | 使用 AstrBot 已配置的聊天模型及默认人格，支持独立会话和新对话 |
| 后台任务 | 电脑端排队执行；手机断线后电脑任务继续，重连查看结果 |
| 手机遥控电脑 | 截图预览、点击画面、输入中文、发送 Enter |
| Agent 操作电脑 | 截图、点击、键盘组合键、输入、滚动；PowerShell 有单独开关 |
| Agent 操作手机 | 无障碍界面读取、点击、滑动、文字输入、返回、桌面、按包名打开应用 |
| 局域网配对 | 自动搜索电脑 → 选择电脑 → 输入 6 位一次性配对码 |
| Cloudflare 远程 | HTTPS 隧道；APK 内有临时地址、固定域名、配对和故障引导 |
| 操作记录 | 任务进度、结果、失败原因、工具操作类型；手机本地也保留操作记录 |
| 停止与限时授权 | 电脑/手机分别授权，支持关闭授权、单任务取消、紧急停止 |

局域网交互借鉴“先发现电脑再输入 PIN”的操作习惯；本项目与 Wallpaper Engine 无关联，也不使用它的通信协议。

## 手机界面分类

- **聊天**：发送消息；每条任务分别选择“可操控电脑”“可操控手机”；新建对话。
- **任务**：集中查看排队、运行、完成、失败及停止记录，查看操作记录或停止单个任务。
- **控制**：电脑权限、手机权限、授权到期时间、电脑遥控面板、本机手机操作记录、紧急停止。
- **设置**：搜索电脑、Cloudflare 分步向导、连接地址/密钥、无障碍授权入口、隐私说明。

## 一、安装电脑插件

1. 在 Windows 上启动 **AstrBot 4.29.4 或更高版本**，先配置好聊天模型，确认普通聊天可用。
2. 打开 AstrBot WebUI → 插件 → 从链接安装，粘贴：

   ```text
   https://github.com/Xinhaihai-Xinhaihai/astrbot_plugin_Android_connect
   ```

   也可以从 Releases 下载插件 ZIP，使用本地上传安装。
3. 插件会安装 `requirements.txt` 中的依赖。保持 `api_token` 为空，首次启动自动生成随机连接密钥。
4. 在插件配置中按连接方式填写：

   | 配置 | 仅 Cloudflare | 同时使用局域网与 Cloudflare |
   | --- | --- | --- |
   | `listen_host` | `127.0.0.1` | `0.0.0.0` |
   | `listen_port` | `8765` | `8765` |
   | `enable_lan_discovery` | 可关闭 | 开启 |

5. 保存配置并重载插件。日志出现“Android Connect 已启动”表示接口启动成功。
6. 首次先保持电脑、手机、PowerShell 控制关闭，完成聊天配对后再按需开启。

## 二、安装 APK

1. 打开本仓库 [Releases](https://github.com/Xinhaihai-Xinhaihai/astrbot_plugin_Android_connect/releases)。
2. 下载 `AstrBot-Android-Connect-v0.1.1.apk`，传到手机安装。不要把插件 ZIP 当成手机安装包。
3. 若系统提示，允许当前浏览器/文件管理器安装此 APK。
4. 启动 **AstrBot Connect**，进入 **设置**。

Release 还提供 `SHA256SUMS.txt` 校验文件。后续版本需使用同一签名密钥，才能覆盖安装并保留本机连接信息。

## 三、局域网：像配对桌面应用一样连接

1. 手机与电脑连接同一个 Wi-Fi；电脑用网线连接同一路由器也可以。
2. 电脑插件配置设为 `listen_host = 0.0.0.0`，开启 `enable_lan_discovery`，保存并重载。
3. 在 Windows 防火墙中允许 AstrBot/Python 在**专用网络**通信。自动发现使用 UDP 5353，手机接口默认 TCP 8765。
4. 在电脑插件配置打开 **生成一次手机配对码**（`enable_pairing`），保存并重载。到 AstrBot 日志查看 **6 位数字**。这个开关会自动复位。
   - 如果你已在消息平台设置 AstrBot 管理员，也可在**管理员私聊**发送 `/android_pair`；群聊不会返回配对码。
5. 手机 → 设置 → **局域网 · 搜索电脑与配对**。
6. 点选发现的电脑，输入电脑刚显示的配对码，点击“配对并保存”。
7. 显示连接成功后，到聊天页发送“你好”。普通聊天无须开启任何设备控制。

配对码 **2 分钟内有效、只可使用一次**；连续尝试超过 5 次会关闭本次配对窗口。重新生成即可。连接密钥通过配对保存，日常无须重新输入。

**搜索不到电脑？**

- 确认不是访客 Wi-Fi，路由器没有启用 AP/客户端隔离。
- 检查插件监听地址与 Windows 防火墙；自动发现失败不一定代表接口不可用。
- 点击“没搜到？手动输入电脑地址”。电脑运行 `ipconfig` 查看 IPv4，例如 `192.168.1.10`，手机填写 `http://192.168.1.10:8765`，再用配对码连接。
- LAN 的 HTTP 使用明文传输，只用于你信任的本地网络；外网连接使用下面的 HTTPS 隧道。

## 四、Cloudflare：离开家也能连接

APK 中进入 **设置 → Cloudflare · 远程连接向导**，包含完整步骤。文档版见 [CONNECTION.md](docs/CONNECTION.md)。

### 快速试用：临时 HTTPS 地址

电脑打开 PowerShell：

```powershell
winget install --id Cloudflare.cloudflared --exact
```

安装后重新打开 PowerShell：

```powershell
cloudflared tunnel --url http://127.0.0.1:8765
```

1. 保持插件和此终端运行。
2. 从终端复制 `https://随机名称.trycloudflare.com`。
3. 在电脑生成一次配对码。
4. 手机向导 → 使用远程地址 + 配对码连接 → 填写 HTTPS 地址及配对码。
5. 临时域名重启后会变化；适合验证连接，不适合长期无人值守。

### 长期使用：固定域名隧道

1. 准备 Cloudflare 账户和已由 Cloudflare 管理 DNS 的域名。
2. 在 Cloudflare One 控制台创建 Cloudflared Tunnel，选择 Windows，按控制台命令安装连接器。
3. 确认隧道状态为 **Healthy**。
4. 添加 Published application route / Public hostname，例如 `astrbot.example.com`。
5. Service 类型选 **HTTP**，服务地址填 **localhost:8765**。
6. 手机填写 `https://astrbot.example.com`，用电脑一次性配对码连接。

本项目不用公开 AstrBot 管理面板。隧道安装令牌属于 cloudflared，**不要填进手机密钥框**。首版未实现 Cloudflare Access 的浏览器登录或 service-token 鉴权流程；请使用插件本身的随机密钥鉴权。

Cloudflare 账户、域名及隧道由你创建；仓库和 APK 提供接入能力，不会自动给你开通公网域名。

## 五、给 Agent 开启电脑/手机操作

### 电脑控制

1. 电脑插件配置开启 `allow_desktop_control` 并保存。
2. 手机 → 控制 → 开启限时电脑控制 → 选择时长。
3. 聊天页勾选 **可操控电脑**，明确布置任务。
4. 如确实需要运行命令，另行在电脑配置开启 `allow_shell`。
5. 需要自己操作时，从控制页打开“电脑遥控面板”。

电脑截图会交给已配置的模型，使用电脑视觉操作时应选择支持**图片与工具调用**的模型。模型能力不足会影响操作效果。

### 手机控制

1. 电脑插件配置开启 `allow_phone_control`。
2. 手机 → 控制 → 开启限时手机控制，阅读说明。
3. 在 Android 系统设置中手动开启 **AstrBot 手机控制** 无障碍服务，再返回应用。
4. Android 13+ 允许通知，选择手机授权时长。通知栏出现运行提示和停止按钮。
5. 聊天页勾选 **可操控手机**，例如“打开手机的日历应用，查看今天的安排并告诉我”。
6. 设备操作仅发送给提交任务的那台手机；若要操作其他应用，可由 Agent 先返回桌面/打开应用。

部分厂商需要额外允许后台运行或关闭本应用电池限制。侧载应用的无障碍设置如被系统限制，按手机系统提供的“允许受限制的设置”流程手动开启。APK 不会代替你操作这些授权。

### 睡前布置任务

- 保持电脑开机、联网且 AstrBot 在运行。电脑休眠/关机后无法继续执行。
- 桌面键鼠依赖可交互桌面；Windows 锁屏、UAC 安全桌面和高权限窗口可能阻止操作。
- 手机操作要求手机联网、无障碍服务在线且已解锁。不会绕过手机锁屏。
- 给设备设置覆盖任务时长的授权，并在发送任务时勾选对应设备。
- 首版执行**你已经提交的任务**，没有周期任务/定时唤醒，也不会自行寻找新工作。
- 默认单任务最长 1 小时、最多 80 个 Agent 步骤，可在插件配置调整。授权过期仍会停止相关控制。
- 手机断开不影响已提交的纯电脑任务；需要操作手机的任务在设备离线时会失败或等待至操作超时。

## 六、停止和数据

- **停止这个任务**：取消选定的排队/运行任务。
- **关闭电脑控制**：撤销电脑限时授权，并取消需要电脑控制的任务。
- **关闭手机控制**：本机立即停止接受操作，同时通知电脑取消相关任务。
- **紧急停止**：撤销电脑权限，取消所有排队/运行任务，并关闭手机控制授权；后台自动重连不会重新授权。
- 已经发生的点击、输入或文件更改不会因取消而撤回；已经脱离任务独立运行的外部进程不保证能停止。
- 电脑插件重启后，未完成任务标记为“重启中断”，不会自动重放可能有副作用的操作。

电脑数据位置：

```text
AstrBot/data/plugin_data/astrbot_plugin_Android_connect/
├── token.txt        # 连接密钥（不要分享）
└── tasks.sqlite3    # 会话、任务及操作记录
```

手机用 Android Keystore 加密保存连接密钥；禁用系统备份。截图/界面信息可能传给 AstrBot 配置的模型服务。操作记录保存工具类型和状态，不记录键入文字/命令参数；聊天任务及结果仍会保存于电脑数据库。默认清理超过 30 天的任务记录。

配对手机共享该插件的主人密钥，可查看该插件任务并控制授权。首版是**单主人、多设备**设计，不提供多用户账户、按手机独立密钥或远程注销单一手机。密钥泄露时在电脑修改 `api_token` 并重载，然后重新配对全部设备。

## 配置参考

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `listen_host` | 127.0.0.1 | LAN 改为 0.0.0.0 |
| `listen_port` | 8765 | 手机 API TCP 端口 |
| `api_token` | 自动生成 | 至少 32 字符 |
| `enable_lan_discovery` | true | 局域网 mDNS 发现 |
| `enable_pairing` | false | 生成一次 2 分钟配对码 |
| `provider_id` | 空 | 使用 AstrBot 当前默认聊天模型 |
| `system_prompt` | 中文手机助手提示 | 追加在默认人格之后 |
| `allow_desktop_control` | false | 电脑控制总开关 |
| `allow_phone_control` | false | 手机控制总开关 |
| `allow_shell` | false | PowerShell 工具开关 |
| `max_control_hours` | 8 | 电脑最长授权时长，最高 24 小时 |
| `task_timeout_seconds` | 3600 | 任务时限，最高 8 小时 |
| `max_agent_steps` | 80 | 最大 Agent 步数，最高 200 |
| `history_turns` | 12 | 每会话传给模型的历史轮数 |
| `retention_days` | 30 | 数据保留天数 |

手机当前界面提供 1/2/4/8 小时授权选项。两端配置的限制以更严格者为准。修改插件配置后请保存并重载。

## 首版边界

- 目标系统为 Windows 原生 AstrBot；不把 Docker 容器桌面当作 Windows 主机桌面。
- 电脑目前只操作主显示器，遥控面板为手动刷新截图，不是连续视频/音频串流。
- 手机使用无障碍节点读取，不含 MediaProjection 截屏；Canvas、游戏和不可访问控件可能无法读出文本。
- 不操作本应用的设置、系统权限/设置界面、密码字段，不绕过锁屏。
- APK 中的聊天使用 AstrBot 模型与人格，**不经过全部聊天平台事件管线，不自动继承其他插件工具、MCP、知识库或桌面 Codex 聊天**。
- 没有 WebRTC、USB、蓝牙、Tailscale、FRP 或独立中继的专用配置；首版按需求实现 LAN + Cloudflare。
- 没有断线自动重放手机操作；避免一条指令重复点击。网络中断时请查看任务状态。
- 自动化可用性取决于模型、目标应用、网络、系统授权和厂商后台管理，不能保证所有应用都可无人值守操作。

## 开发与构建

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt pytest pytest-asyncio
.\.venv\Scripts\python -m pytest -q
```

Android 使用 JDK 17、Gradle 8.11.1、Android SDK 35、AGP 8.9.3：

```text
cd android
gradle assembleDebug lintDebug
```

GitHub Actions 自动测试 Python、构建并签名 APK、运行 Android 35 模拟器页面检查。发布 `v*` 标签后将 APK、插件 ZIP 和校验文件上传到 Releases。

签名配置使用仓库 Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`。私钥不在仓库内。维护者应离线备份私钥，后续升级必须保持同一签名。

## 开源许可与依赖

本项目原创插件、Android 客户端及文档采用 [MIT](LICENSE)。AstrBot、Cloudflare/cloudflared、Android SDK 及其他依赖分别适用其自身许可；MIT 不会改变这些项目的许可。本仓库不打包 AstrBot 核心源码。

