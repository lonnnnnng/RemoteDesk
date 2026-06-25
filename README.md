# RemoteDesk

RemoteDesk 是一个跨平台远程桌面实验项目，当前已经从初始 skeleton 进入真实链路联调与稳定性收口阶段。

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

Last updated: `2026-06-02`

- Android 控制端可连接 relay、发现设备、发起/结束远控、渲染 WebRTC 视频，并发送点击、拖拽、键盘、滚轮输入。
- Desktop 端可作为 Windows/macOS agent，也可作为 Desktop controller；UI 已支持在线设备列表、目标选择、Live Metrics、E2E proof 控制和桌面自检。
- Relay 支持设备注册、心跳、会话建立/结束、WebRTC offer/answer/ICE 转发、输入转发、输入执行回执、跨端指标聚合和 proof API。
- TURN 服务由 `apps/server/cmd/turn-server` 提供，本地联调可直接启动。
- Windows 端已经完成 Android -> Windows 单路 proof：输入 `11/11` 落地，覆盖 `click,drag,keyboard,wheel`，执行器为 `windows.send_input`。
- 仍未稳定通过：最新 Android -> Windows 约 5.7min 实跑中，视频 proof 成功但 `render_fps_avg=2.55`，proof 后出现长时间 `frame_stalled`。下一步重点是 Android WebRTC render stall 与 Windows sender 连续性。

更详细的进度和证据见：

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

## 快速开始

### 1. 准备环境

复制环境变量：

```bash
cp .env.example .env
```

常用配置：

```env
RD_HTTP_ADDR=:18081
RD_WS_PUBLIC_URL=ws://localhost:18081/ws
RD_ALLOWED_ORIGINS=http://localhost:5173,http://127.0.0.1:5173
RD_LOG_LEVEL=debug
RD_TURN_BIND_ADDR=0.0.0.0:3478
RD_TURN_USERNAME=rd
RD_TURN_PASSWORD=rdpass
```

Windows 上如果缺少 Go、JDK 17 或 Gradle，可运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\bootstrap-windows-toolchains.ps1
```

该脚本会把 repo-local 工具链放到 `.tmp/toolchains`，不修改源码。Rust、Node.js、MSVC、WebView2 和 Android SDK 仍需按本机环境准备。

### 2. 启动 relay 和 TURN

Unix/macOS：

```bash
make turn-run
make server-run
```

Windows PowerShell 可直接运行：

```powershell
cd apps\server
go run .\cmd\turn-server
go run .\cmd\api-server
```

如果 `3478` 被占用，`scripts/triad_ctl.sh` 会在类 Unix 环境中自动回退到临近可用 TURN 端口，并同步 relay 下发的 ICE 配置。

### 3. 启动 Desktop

```powershell
cd apps\desktop
npm install
npm exec tauri dev -- --no-watch
```

不要用 `cargo run` 直接启动主桌面程序；它会绕过 Tauri CLI 的 `beforeDevCommand/devUrl` 联动，容易出现白屏或前端资源未加载。

Windows 桌面端完整开发、编译、打包和 Android -> Windows 验证流程见 `docs/windows_desktop_runbook.md`。

### 4. 构建 Android

```powershell
cd apps\android
gradle.bat :app:assembleDebug
```

在类 Unix shell 中：

```bash
cd apps/android
./gradlew :app:assembleDebug
```

默认包名：`com.remotedesk.app`。

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

## Linux 部署 relay

构建 relay：

```bash
cd apps/server
go build -o api-server ./cmd/api-server
```

推荐放置：

```bash
sudo install -m 0755 api-server /usr/local/bin/api-server
sudo mkdir -p /etc/remote-desk /var/lib/remote-desk
sudo cp ../../infra/api-server.env.example /etc/remote-desk/api-server.env
```

仓库提供 systemd 模板：

```bash
sudo cp infra/api-server.service /etc/systemd/system/remote-desk-api.service
sudo systemctl daemon-reload
sudo systemctl enable --now remote-desk-api.service
```

验证：

```bash
curl http://127.0.0.1:18081/healthz
curl http://127.0.0.1:18081/devices
```

生产环境建议通过 Nginx/Caddy 暴露 HTTPS/WSS，并把 `/ws` 反代到 relay。

## 当前重点待办

- 定位 Android -> Windows proof 后 `frame_stalled` 与低 `render_fps_avg`。
- 对齐 Windows sender `send_fps/send_kbps` 与 Android `render_fps_avg/frames_decoded/frames_dropped`。
- 复跑 Android -> Windows 5min+ 样本，再扩展到 50min soak 和弱网对照。
- 完成 Windows installer 配置、签名和发布校验。
- 继续把 README、runbook 与 `docs/webrtc_native_media_pipeline_plan.md` 的进度口径保持同步。
