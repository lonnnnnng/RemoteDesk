# RemoteDesk

RemoteDesk 是一个跨平台远程桌面实验项目，当前已经从初始 skeleton 进入真实链路联调与稳定性收口阶段。

快速导航：

- [使用说明](#使用说明)
- [Linux-systemd-部署](#linux-systemd-部署)
- [常用校验](#常用校验)
- [当前状态](#当前状态)

核心链路是：

```text
Android/Desktop controller -> relay + TURN -> Windows/macOS Desktop agent
```

当前 Desktop agent 侧已经接入真实采集与真实输入执行：

- Windows：GDI desktop capture + SendInput
- macOS：ScreenCaptureKit + Core Graphics input
- 媒体：Rust/Tauri native sender，H.264 video track over WebRTC
- 信令/控制：JSON envelope over WebSocket relay
- 观测：`session.metrics.report`、relay `session.metrics.combined`、`GET /e2e-proof`

## 当前状态

Last updated: `2026-08-07`

- Android 控制端可连接 relay、发现设备、发起/结束远控、渲染 WebRTC 视频，并发送点击、拖拽、键盘、滚轮输入。
- Desktop 端可作为 Windows/macOS agent，也可作为 Desktop controller；UI 已支持在线设备列表、目标选择、Live Metrics、E2E proof 控制和桌面自检。
- Relay 支持设备注册、心跳、会话建立/结束、WebRTC offer/answer/ICE 转发、输入转发、输入执行回执、跨端指标聚合和 proof API。
- TURN 服务由 `apps/server/cmd/turn-server` 提供，本地联调可直接启动。
- Android 真机 -> macOS agent 的主链路已经进入稳定性收口：首帧、全屏完整显示、输入覆盖、短断恢复和全屏长滑动输入已有自动化/真机截图证据。
- 当前仍不能宣称最终“肉眼无明显卡顿”：交互临时档 `800x517` 可在长滑动中达到 `31.25fps`，但停手恢复 `1547x1000` 清晰档后软件 H.264 编码仍约 `14fps`；双指缩放手感仍需人工真机确认。

更详细的进度和证据见：

- `docs/remote_control_progress_checklist.md`
- `docs/android_macos_remote_control_system_design.md`
- `docs/development_notes.md`
- `docs/webrtc_native_media_pipeline_plan.md`
- `docs/windows_desktop_runbook.md`

## 技术栈

- Desktop：Tauri 2 + React 19 + Vite + Rust
- Android：Kotlin + Android WebRTC SDK
- Server/Relay/TURN：Go
- Protocol：JSON over WebSocket
- Media：WebRTC + H.264
- Local dev infra：Docker Compose PostgreSQL（当前 server 仍主要使用内存态 registry/session）

## 目录

- `apps/desktop`：Windows/macOS 桌面端，controller + agent 共用 UI
- `apps/android`：Android 控制端
- `apps/server`：relay、TURN server、E2E proof checker
- `packages/protocol`：协议 schema、fixtures、兼容性说明
- `packages/shared-models`：共享模型占位
- `scripts`：联调、proof、soak、Windows toolchain 与预检脚本
- `docs`：进度、开发备注和 Windows 桌面端 runbook
- `infra`：Docker、Compose、systemd 和环境模板

## 使用说明

### 运行角色

完整远控需要同时运行三个角色：

1. **中转服务**：Linux、macOS 或 Windows 上运行 relay API 和 TURN server 两个进程。
2. **被控端**：Windows/macOS Desktop，角色选择“受控端”。
3. **控制端**：Android 或另一台 Windows/macOS Desktop。

最简连接方式是：服务端配置好 TURN，客户端只填写同一个“中继地址”，客户端的 TURN/STUN 四项全部留空。会话建立时，relay 会把服务端配置的 TURN/STUN 参数自动下发给双方。

> `turn_username` / `turn_password` 是 TURN 媒体中继凭据，不是 RemoteDesk 登录账号。当前 relay 注册不要求用户账号登录，设备 ID 和会话 token 由客户端与 relay 自动生成。

### 1. 生成服务端配置

macOS/Linux 在仓库根目录执行：

```bash
make init
```

Windows PowerShell 执行：

```powershell
Copy-Item .\infra\remote-desk.example.json .\remote-desk.json
```

生成的 `remote-desk.json` 已加入 `.gitignore`，API relay 与 TURN server 必须读取同一份文件。不要直接把包含真实密码的配置提交到 Git。

服务端也支持显式指定其他路径：

```bash
./remote-desk-api-server -config /etc/remote-desk/remote-desk.json
./remote-desk-turn-server -config /etc/remote-desk/remote-desk.json
```

配置路径优先级为：命令行 `-config` > `RD_CONFIG_FILE`。现有 `RD_*` 环境变量可继续覆盖 JSON 字段，仅用于兼容旧部署脚本。

### 2. 服务端配置字段

配置模板位于 `infra/remote-desk.example.json`：

| 字段 | 作用 | 常用值/说明 |
| --- | --- | --- |
| `http_addr` | relay HTTP/WebSocket 监听地址 | `0.0.0.0:18081` |
| `protocol_version` | 客户端与 relay 的协议版本 | 当前为 `1.0` |
| `log_level` | 服务日志级别 | 开发用 `debug`，部署用 `info` |
| `public_ws_url` | 下发给客户端的公网/局域网 WebSocket 地址 | 必须是客户端能访问的 `ws://.../ws` 或 `wss://.../ws` |
| `allowed_origins` | WebSocket 允许的 Origin | Tauri 默认 Origin 已写入模板；浏览器调试时加入对应站点 |
| `turn_bind_addr` | TURN UDP/TCP 监听地址 | 通常为 `0.0.0.0:3478` |
| `turn_public_ip` | TURN 分配媒体 relay 地址时使用的 IPv4 | 局域网填服务器局域网 IP；公网填服务器公网 IPv4 |
| `turn_public_host` | 客户端连接 TURN 使用的域名或 IP | 可留空继承 `turn_public_ip`；不能带协议和端口 |
| `turn_port` | relay 向客户端下发的 TURN 端口 | 无端口映射时应与 `turn_bind_addr` 中的端口一致 |
| `turn_realm` | TURN realm | 默认 `remote.desk`，两个服务必须一致 |
| `turn_username` | TURN 用户名 | 正式部署必须修改模板值 |
| `turn_password` | TURN 密码 | 正式部署必须替换为强密码 |
| `stun_urls` | 下发给客户端的 STUN 地址列表 | 不需要 STUN 时使用 `[]`；不会自动加入 Google STUN |
| `ice_mode` | ICE 候选策略 | `default`、`relay_only`、`relay_udp`、`relay_tcp` |
| `ice_turn_transport` | TURN 传输偏好 | `all`、`udp`、`tcp` |
| `ice_relay_udp_high_rtt_ms` | relay UDP 高 RTT 判定阈值 | 默认 `220` 毫秒 |
| `ice_degrade_streak_samples` | 连续多少次劣化后切换策略 | 默认 `3` |

修改 JSON 后需要重启 API 和 TURN 两个进程。配置包含 TURN 密码，Linux 部署建议设置权限：

```bash
chmod 600 /etc/remote-desk/remote-desk.json
```

### 3. 局域网配置示例

假设运行服务端的电脑局域网 IP 是 `192.168.1.20`，修改 `remote-desk.json` 中这些字段：

```json
{
  "http_addr": "0.0.0.0:18081",
  "public_ws_url": "ws://192.168.1.20:18081/ws",
  "turn_bind_addr": "0.0.0.0:3478",
  "turn_public_ip": "192.168.1.20",
  "turn_public_host": "",
  "turn_port": 3478,
  "turn_username": "remote-desk",
  "turn_password": "请替换为自己的强密码",
  "stun_urls": [],
  "ice_mode": "default",
  "ice_turn_transport": "all"
}
```

这只是需要重点修改的字段片段；实际文件应保留模板中的其他字段。Android 手机、控制端电脑和被控端电脑必须能访问 `192.168.1.20`，并处于允许互访的网络中。

不能在 Android 真机上填写 `127.0.0.1` 或 `localhost`，这两个地址指向手机自身。只有服务端和客户端运行在同一台电脑上时才能使用环回地址。

### 4. 公网 Linux 配置示例

假设 relay 域名为 `relay.example.com`，TURN 域名为 `turn.example.com`，服务器公网 IPv4 为 `203.0.113.10`：

```json
{
  "http_addr": "0.0.0.0:18081",
  "public_ws_url": "wss://relay.example.com/ws",
  "turn_bind_addr": "0.0.0.0:3478",
  "turn_public_ip": "203.0.113.10",
  "turn_public_host": "turn.example.com",
  "turn_port": 3478,
  "turn_realm": "remote.desk",
  "turn_username": "your-turn-user",
  "turn_password": "请替换为自己的强密码",
  "stun_urls": [],
  "ice_mode": "default",
  "ice_turn_transport": "all"
}
```

公网部署需要同时满足：

- Nginx/Caddy 把 `wss://relay.example.com/ws` 反向代理到 relay 的 `/ws`，并启用 WebSocket Upgrade。
- 防火墙和云安全组放行 TCP `18081`（如果直接暴露 relay）、UDP/TCP `3478`，以及操作系统为 TURN 分配的 UDP 临时端口范围。
- 如果存在 NAT/端口映射，`turn_public_ip` 填公网 IPv4，`turn_port` 填客户端实际访问的外部端口。
- relay API 和 TURN server 使用同一份 JSON，否则下发地址或凭据会不一致。

更完整的 Linux 二进制与 systemd 部署步骤见 `docs/linux_relay_deployment.md`。

### 5. 启动 relay 和 TURN

macOS/Linux 需要两个终端，仓库根目录先运行 TURN：

```bash
make turn-run
```

另一个终端运行 relay API：

```bash
make server-run
```

Windows PowerShell 同样需要两个窗口：

```powershell
cd apps\server
go run .\cmd\turn-server -config ..\..\remote-desk.json
```

```powershell
cd apps\server
go run .\cmd\api-server -config ..\..\remote-desk.json
```

检查 relay 是否正常：

```bash
curl http://127.0.0.1:18081/healthz
curl http://127.0.0.1:18081/devices
```

`/healthz` 应返回 `status: ok`。如果 `3478` 被占用，修改 `turn_bind_addr` 和 `turn_port` 后同时重启两个服务；`scripts/triad_ctl.sh` 仅在本地自动化联调时会自动寻找临近端口。

### 6. 启动并配置 Desktop

开发环境启动：

```powershell
cd apps\desktop
npm install
npm exec tauri dev -- --no-watch
```

不要用 `cargo run` 启动主桌面程序，否则会绕过 Tauri CLI 的前端资源联动。

Desktop 首次使用步骤：

1. 打开“设置”。
2. “中继地址”填写 `ws://192.168.1.20:18081/ws` 或 `wss://relay.example.com/ws`。
3. 正常使用时将 TURN 地址、TURN 用户名、TURN 密码、STUN 地址全部留空。
4. 被远程控制的电脑将“本机角色”设为“受控端”；主动控制其他设备时设为“控制端”。
5. 点击“连接”。Desktop 会自动注册并发送首次心跳；“注册”和“心跳”按钮用于手动重试。
6. 被控端保持程序运行，并授予操作系统要求的屏幕录制和输入权限。
7. 控制端进入“设备”页，选择在线且可被控的设备，发起远控会话。

Windows 完整开发、编译和验证流程见 `docs/windows_desktop_runbook.md`。macOS 被控权限见下文“macOS 被控权限”。

### 7. 安装并配置 Android

正式使用应从 [GitHub Releases](https://github.com/lonnnnnng/RemoteDesk/releases) 下载经过签名的 release APK，不要安装文件名包含 `unsigned` 或 `debug` 的 APK。开发调试时才从源码构建：

```bash
cd apps/android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 开发环境可使用：

```powershell
cd apps\android
gradle.bat :app:assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Android 包名为 `com.remotedesk.app`。首次使用步骤：

1. 打开底部“设置”。
2. “信令地址”填写与 Desktop 相同的中继地址。
3. 正常使用时将 TURN 地址、TURN 用户名、TURN 密码、STUN 地址全部留空。
4. 点击“连接服务端”。Android 会自动完成注册和首次心跳。
5. 打开“设备”页，刷新并选择在线的 Windows/macOS 受控端。
6. 发起会话，画面出现后即可使用点击、拖动、双指缩放、滚轮和键盘输入。

### 8. 客户端手动 TURN/STUN

手动 ICE 主要用于外部 TURN、临时诊断或覆盖服务端下发配置。一般用户不需要填写。

填写规则：

- TURN 地址格式：`turn:服务器地址:端口` 或 `turns:服务器地址:端口`。
- TURN 地址、用户名、密码必须同时填写，缺少任意一项都会阻止连接。
- STUN 地址可单独填写，格式为 `stun:服务器地址:端口` 或 `stuns:服务器地址:端口`。
- 只要客户端存在完整手动 ICE 配置，就会优先使用手动值，不再使用 relay 下发的 ICE 列表。
- 清空客户端 TURN/STUN 四项即可恢复使用服务端下发配置。
- 手动填写内置 TURN 时，用户名和密码必须与服务端 JSON 完全一致。

当前 Desktop 将客户端连接配置保存在本地 `localStorage`，Android 保存在应用 `SharedPreferences`。TURN 密码尚未接入系统 Keychain/Keystore，请为 RemoteDesk 使用独立、权限受限的 TURN 凭据，不要复用其他系统密码。

### 9. 常见问题

| 现象 | 检查项 |
| --- | --- |
| 客户端提示 WebSocket 连接失败 | 确认中继地址能从客户端访问；Android 真机不能使用 `localhost`；检查 TCP `18081` 和反向代理 WebSocket Upgrade |
| 能连接但设备列表为空 | 两端必须连接同一个 relay；等待自动注册/心跳，或手动点击“注册”“心跳”；检查被控端角色和系统权限 |
| 能建立会话但没有画面 | 检查 UDP/TCP `3478`、TURN UDP 临时端口、`turn_public_ip`、`turn_public_host` 和 NAT 映射 |
| TURN 鉴权失败 | 清空客户端手动 ICE 使用服务端下发值，或确保客户端用户名/密码与 JSON 完全一致 |
| 修改 JSON 后没有生效 | API 和 TURN 两个进程都需要重启，并确认它们使用同一个 `-config` 路径 |
| `wss://` 无法连接 | 检查 TLS 证书、域名解析、反向代理 `/ws` 路由以及 `Upgrade` / `Connection` 请求头 |
| macOS 设备显示但不可被控 | 授予 Screen Recording 和 Accessibility 权限后重启 Desktop 并重新连接 |

## Desktop 自检

Desktop 启动后，可在“设置 -> 桌面自检”运行自检。它会验证：

- 平台能力上报：Windows GDI / macOS ScreenCaptureKit，SendInput / Core Graphics
- 权限状态：macOS Screen Recording 与 Accessibility；Windows 无额外授权
- 采集链路：桌面源发现、首帧 JPEG 采样、MJPEG 本地端点创建
- WebRTC sender：Rust native sender、H.264 video track、offer/ICE、编码 probe
- 输入守卫：无活动会话时 host input 不会实际执行

也可以运行 CLI：

```powershell
cd apps\desktop\src-tauri
cargo run --bin desktop-self-test
```

如需真实执行一次鼠标、键盘和滚轮探针：

```powershell
$env:RD_DESKTOP_SELF_TEST_APPLY_INPUT = "1"
cargo run --bin desktop-self-test
Remove-Item Env:RD_DESKTOP_SELF_TEST_APPLY_INPUT
```

该探针会实际移动鼠标并发送输入，只建议在可控测试环境运行。旧变量 `RD_WINDOWS_SELF_TEST_APPLY_INPUT=1` 仍兼容。

## E2E Proof

Relay 提供 proof API：

- `GET /e2e-proof`：查看当前和最近成功 proof
- `DELETE /e2e-proof`：清空内存 proof 状态

目标路由：

- `android_to_windows`
- `windows_to_windows`
- `windows_to_macos`

完整 proof gate 要求 `/e2e-proof complete=true`，且三个目标路由都达到：

- `last_success.proof_status=video_and_input_observed`
- `remote_input_coverage` 包含 `click,drag,keyboard,wheel`
- 目标端 `input.result.push` 中 `applied=true`

单路稳定性检查可以只看某条 route 的 `last_success`，但不能代表完整三路 gate。对 Android -> Windows 这类专项验证，还需要结合 Android `session_summary` 与 relay `session.metrics.combined` 判断 FPS、RTT、候选路径、丢帧和 stall。

常用命令：

```powershell
cd apps\server
go run .\cmd\e2e-proof-check -url http://127.0.0.1:18081/e2e-proof -reset-only
go run .\cmd\e2e-proof-check -url http://127.0.0.1:18081/e2e-proof -wait 2m
```

Windows 上可用 watcher 辅助安装/启动 Android 并保存 proof 快照：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\watch-e2e-proof.ps1 `
  -ProofUrl http://127.0.0.1:18081/e2e-proof `
  -RequireAndroidDevice `
  -InstallAndroid `
  -LaunchAndroid `
  -AndroidTargetDeviceId <windows-device-id>
```

proof 快照会保存到 `.tmp/e2e-proof-runs/<timestamp>`。

## 常用校验

```bash
make proto-check
make server-test
make test
```

Windows 推荐预检：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-e2e-preflight.ps1
```

可选参数：

- `-RequireAndroidDevice`：要求 ADB 可见 Android 设备
- `-ProofUrl http://<relay-host>:18081/e2e-proof`：同时验证 proof reset
- `-SkipServer` / `-SkipDesktop` / `-SkipAndroid` / `-SkipAdb`：跳过对应项

Desktop 单独校验：

```powershell
cd apps\desktop
npm.cmd run build

cd src-tauri
cargo test
```

Android 单独校验：

```powershell
cd apps\android
gradle.bat :app:assembleDebug
```

## macOS 被控权限

macOS 作为 agent 时需要授予：

- Screen Recording：桌面画面采集
- Accessibility：鼠标、键盘、滚轮输入注入

未授权时，Desktop 会在设备能力中上报权限状态，`can_be_controlled` 会暂时为 `false`，控制端设备列表不会把该 macOS 设备作为可控目标。授权后重启或刷新 Desktop 并重新注册。

## Windows 打包状态

当前 `apps/desktop/src-tauri/tauri.conf.json` 中 `bundle.active=false`，因此默认未启用 MSI/NSIS 安装包产物。

可执行 release 编译入口：

```powershell
cd apps\desktop
npm exec tauri build
```

当前应优先检查：

```text
apps\desktop\src-tauri\target\release\remote_desk_desktop.exe
```

若要发布正式安装包，需要先启用 Tauri bundle、补 icon、选择 `nsis` 或 `msi` target，并加入签名与发布校验流程。

## Linux systemd 部署

以下命令从仓库根目录执行。先构建 Linux 本机架构的 relay 与 TURN 二进制：

```bash
mkdir -p build/server
cd apps/server
go build -o ../../build/server/remote-desk-api-server ./cmd/api-server
go build -o ../../build/server/remote-desk-turn-server ./cmd/turn-server
cd ../..
```

systemd 模板使用专用账号 `remote_desk`。如果该账号不存在，先按照当前 Linux 发行版的方式创建不可登录的系统账号，然后安装文件：

```bash
sudo install -m 0755 build/server/remote-desk-api-server /usr/local/bin/remote-desk-api-server
sudo install -m 0755 build/server/remote-desk-turn-server /usr/local/bin/remote-desk-turn-server
sudo install -d -m 0750 -o remote_desk -g remote_desk /etc/remote-desk
sudo install -d -m 0750 -o remote_desk -g remote_desk /var/lib/remote-desk
sudo install -m 0600 -o remote_desk -g remote_desk infra/remote-desk.example.json /etc/remote-desk/remote-desk.json
```

编辑 `/etc/remote-desk/remote-desk.json`，按照上文“公网 Linux 配置示例”设置公网 WebSocket、TURN 地址、用户名和强密码。API 与 TURN 的 service 文件已经固定读取该路径。

安装并启动两个 systemd 服务：

```bash
sudo cp infra/api-server.service /etc/systemd/system/remote-desk-api.service
sudo cp infra/turn-server.service /etc/systemd/system/remote-desk-turn.service
sudo systemctl daemon-reload
sudo systemctl enable --now remote-desk-api.service remote-desk-turn.service
```

验证：

```bash
curl http://127.0.0.1:18081/healthz
curl http://127.0.0.1:18081/devices
systemctl status remote-desk-api.service
systemctl status remote-desk-turn.service
```

查看启动错误或实时日志：

```bash
journalctl -u remote-desk-api.service -u remote-desk-turn.service --since today
```

修改配置后同时重启两个服务：

```bash
sudo systemctl restart remote-desk-api.service remote-desk-turn.service
```

生产环境建议通过 Nginx/Caddy 暴露 HTTPS/WSS，并把公网 `/ws` 反向代理到 relay。

## 当前重点待办

- 定位 Android -> Windows proof 后 `frame_stalled` 与低 `render_fps_avg`。
- 对齐 Windows sender `send_fps/send_kbps` 与 Android `render_fps_avg/frames_decoded/frames_dropped`。
- 复跑 Android -> Windows 5min+ 样本，再扩展到 50min soak 和弱网对照。
- 完成 Windows installer 配置、签名和发布校验。
- 继续把 README、runbook 与 `docs/webrtc_native_media_pipeline_plan.md` 的进度口径保持同步。
