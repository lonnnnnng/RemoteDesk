# Windows Desktop Runbook

Last updated: `2026-06-02`

本文档记录 Windows 桌面端当前开发运行、编译、打包和实机验证流程。当前 Windows 端已经是可参与联调的真实 agent：采集侧使用 Windows GDI，输入侧使用 SendInput。

## 当前状态

- 平台能力：Windows Desktop 端已接入真实桌面采集与真实输入执行，不再只是代码占位。
- 已验证链路：Android 模拟器 -> relay -> Windows agent。
- 最新会话：`sess-1780404079279-9`，Windows agent `desktop-84a8584c`，Android controller `android-19e88591850`。
- Proof 结果：`android_to_windows` 单路完成，`proof_status=video_and_input_observed`。
- 输入结果：`remote_input_applied=11/11`，覆盖 `click,drag,keyboard,wheel`，执行器 `windows.send_input`。
- 视频结果：首帧可达，但质量未达稳定标准，`first_frame_ms=5749`、`render_fps_avg=2.55`、`rendered_frames=104`，后续出现长时间 `frame_stalled`。
- 网络结果：`rtt_ms_avg=39.17`，候选路径最后为 `prflx/host/udp`，分级 `p2p_udp`。

结论：Windows 输入落地链路已经实跑通过；Android -> Windows 视频 proof 成功但稳定性未通过，下一轮优先排查 Android WebRTC 渲染 stall、Windows sender 出帧连续性与解码/丢帧统计。

## 环境准备

Windows 开发机建议准备：

- Windows 10/11。
- Node.js + npm。
- Rust stable + Cargo。
- Visual Studio Build Tools，包含 MSVC C++ 编译工具链。
- WebView2 Runtime。
- Android 联调时需要 ADB 与 Android SDK。

仓库提供 Windows 验证工具链引导脚本，用于补齐 Go、JDK 17 和 Gradle：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\bootstrap-windows-toolchains.ps1
```

该脚本安装 repo-local 工具链，不修改源码。Rust、Node.js、MSVC 和 WebView2 仍以系统环境为准。

## 开发运行

1. 启动 relay 与 TURN。

```powershell
make turn-run
make server-run
```

如果当前环境没有 `make`，可分别启动 server/TURN 对应 Go 命令或使用已有 relay。桌面端默认 WebSocket 地址是 `ws://localhost:18081/ws`。

2. 启动 Windows Desktop。

```powershell
cd apps\desktop
npm install
npm exec tauri dev -- --no-watch
```

不要用 `cargo run` 直接启动桌面端主程序；直接运行 Rust binary 会绕过 Tauri CLI 的 `beforeDevCommand/devUrl` 联动，容易出现白屏或前端资源未加载。

3. 在桌面端设置中确认：

- Relay 地址为 `ws://localhost:18081/ws` 或目标 relay 的 `/ws` 地址。
- 本机注册为 Desktop agent。
- 设备列表能看到 Android controller 或其他控制端。
- “桌面自检”通过后再开始 E2E。

## 编译

Web 前端编译：

```powershell
cd apps\desktop
npm run build
```

Rust/Tauri 原生测试：

```powershell
cd apps\desktop\src-tauri
cargo test
```

桌面端自检 CLI：

```powershell
cd apps\desktop\src-tauri
cargo run --bin desktop-self-test
```

需要真实执行一次鼠标、键盘和滚轮输入探针时：

```powershell
cd apps\desktop\src-tauri
$env:RD_DESKTOP_SELF_TEST_APPLY_INPUT = "1"
cargo run --bin desktop-self-test
Remove-Item Env:RD_DESKTOP_SELF_TEST_APPLY_INPUT
```

该探针会实际移动鼠标并发送输入，请只在可控测试环境中运行。

## 打包

当前 `apps/desktop/src-tauri/tauri.conf.json` 中：

```json
"bundle": {
  "active": false,
  "targets": "all",
  "icon": []
}
```

因此当前仓库默认没有启用安装包产物。可执行的 release 编译入口是：

```powershell
cd apps\desktop
npm exec tauri build
```

在当前配置下，应优先检查 release binary：

```powershell
apps\desktop\src-tauri\target\release\remote_desk_desktop.exe
```

若要产出正式安装包，需要先完成以下产品化配置后再执行 `npm exec tauri build`：

- 将 `bundle.active` 改为 `true`。
- 明确 Windows 安装包 target，例如 `nsis` 或 `msi`。
- 补齐产品 icon。
- 在 Windows 打包机上安装对应 Tauri Windows bundler 依赖。
- 根据发布策略补充签名证书与签名流程。

安装包启用前，请不要把 `target\release\bundle` 下的产物作为稳定交付物口径。

## Android -> Windows E2E 验证

1. 重置 proof 状态。

```powershell
cd apps\server
go run .\cmd\e2e-proof-check -url http://127.0.0.1:18081/e2e-proof -reset-only
```

2. 启动 Android controller，并选择 Windows agent。

可以使用 watcher 辅助安装、启动 Android 并等待 proof：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\watch-e2e-proof.ps1 `
  -ProofUrl http://127.0.0.1:18081/e2e-proof `
  -RequireAndroidDevice `
  -InstallAndroid `
  -LaunchAndroid `
  -AndroidTargetDeviceId <windows-device-id>
```

3. 在 Android 控制端发起远控，等待首帧后发送 E2E proof input。

通过标准分两层：

- 功能 proof：`/e2e-proof` 中 `android_to_windows.last_success.proof_status=video_and_input_observed`，且 `remote_input_coverage` 包含 `click,drag,keyboard,wheel`。
- 稳定性 proof：会话总结中 `render_fps_avg`、`rendered_frames`、`frames_dropped`、`frame_stalled`、RTT 与候选路径满足当前阶段阈值；单路 proof 成功但出现长时间 `frame_stalled` 时不能判定稳定性通过。

4. 记录证据。

至少保留：

- `/e2e-proof` 快照。
- Android `session_summary`。
- relay `session.metrics.combined`。
- Windows Desktop 日志中的 `native.sender.*`、`webrtc.agent.*` 与 `input.result.push`。

## 当前待办

- 定位 Android -> Windows proof 后的长时间 `frame_stalled`。
- 对比 Windows sender 侧 `send_fps/send_kbps` 与 Android 侧 `render_fps_avg/frames_decoded/frames_dropped`。
- 复跑 5min+ Android -> Windows 稳定性样本。
- 在稳定后再扩展到 50min soak 与弱网对照。
- 启用 Tauri Windows installer 前补齐 bundle 配置、icon、签名与发布校验。
