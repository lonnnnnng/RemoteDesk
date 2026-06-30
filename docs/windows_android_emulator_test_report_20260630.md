# Windows 端 + Android 端联调测试报告

报告时间：2026-06-30 11:30:00（北京时间，UTC+8）

## 结论

本轮按用户指示在真机 ADB 不可见后改用 Android 模拟器执行。Android 模拟器到 Windows Desktop Agent 的核心功能链路已通过：两端在线、会话建立、WebRTC 首帧到达、Android 侧四类输入（click / drag / keyboard / wheel）均由 Windows 端 `windows.send_input` 实际应用，`/e2e-proof` 中 `android_to_windows` 路由为 `complete=true`。

但流畅性/稳定性不能判定通过：会话结束时 `session_quality_hint=render_frame_stutter`，`render_fps_avg=9.21`，`render_fps_recent=8.37`，最长可见帧间隔 `5752ms`，低 FPS streak 约 `608194ms`。Android 侧最终 `frames_dropped=248`、`frames_dropped_spike_max=59`；Windows sender 侧最终 `send_fps=8.94`，过程中多次出现 `send_fps_low` / `send_bitrate_low`。

## 测试范围

- Windows 端：`desktop-windows-codex`，角色 `agent`，平台 `windows`。
- Android 端：真机未被 ADB 识别后，使用模拟器 `emulator-5554`，控制端设备 ID `android-19f168646de`。
- 目标链路：Android controller -> Windows agent。
- 未覆盖：Android 真机、macOS 路由、Windows -> Windows、Windows -> macOS、长时间 soak、弱网、断线重连。

## 环境与启动

- 仓库：`D:\yuancheng\RemoteDesk`
- 分支：`main`
- Relay：`http://127.0.0.1:18081`，WebSocket `ws://127.0.0.1:18081/ws`
- TURN：`0.0.0.0:3478`
- Desktop dev URL：`http://127.0.0.1:5174/?role=agent&device_id=desktop-windows-codex&ws_url=ws%3A%2F%2F127.0.0.1%3A18081%2Fws`
- Android 启动参数：

```powershell
adb -s emulator-5554 shell am start -S -n com.remotedesk.app/.ui.MainActivity `
  -e rd_ws_url ws://10.0.2.2:18081/ws `
  --ez rd_auto_connect true `
  -e rd_target_device_id desktop-windows-codex `
  --ez rd_auto_request_session true `
  --ez rd_auto_proof_input true
```

## 执行记录

| 项目 | 结果 | 记录 |
| --- | --- | --- |
| 真机 ADB 识别 | 未通过，已切模拟器 | `adb devices` 只看到 `emulator-5554 device`，未看到真机 |
| Android APK 安装 | 通过 | debug APK 已安装到模拟器，`versionName=0.1.0` |
| Windows agent 在线 | 通过 | `/devices` 显示 `desktop-windows-codex` 为 `online`，`can_be_controlled=true` |
| Android controller 在线 | 通过 | `/devices` 显示 `android-19f168646de` 为 `online` |
| 会话建立 | 通过 | session `sess-1782789338421-2` |
| 首帧 | 通过但偏慢 | `first_frame_ms=6410`，画面尺寸 `848x392` |
| ICE / 路径 | 通过 | `ice_state_last=CONNECTED`，`candidate_tier_last=p2p_udp` |
| 输入 proof | 通过 | `remote_input_applied=11/11`，覆盖 `click,drag,keyboard,wheel` |
| Windows 输入落地 | 通过 | Desktop 日志记录 `input_result ... applied=true executor=windows.send_input` |
| `/e2e-proof` 目标路由 | 通过 | `android_to_windows.complete=true`，`proof_status=video_and_input_observed` |
| `/e2e-proof` 全量 complete | 未通过 | `complete=false`，因为本轮只测 Android -> Windows，其他 3 条路由未跑 |
| UI 树采集 | 未通过 | `uiautomator dump` 返回 `ERROR: could not get idle state.` |
| 约 10 分钟画质/流畅性 | 未通过 | `render_fps_avg=9.21`，`render_fps_recent=8.37`，`longest_frame_gap_ms=5752`，低 FPS 与丢帧明显 |
| 会话清理 | 通过 | Android 点击“断开”后 relay 记录 `session.ended reason=user_end`，Desktop 记录 `webrtc.closed reason=session_end` |

## 关键指标

- `session_id`: `sess-1782789338421-2`
- `controller_device_id`: `android-19f168646de`
- `agent_device_id`: `desktop-windows-codex`
- `proof_status`: `video_and_input_observed`
- `first_frame_ms`: `6410`
- `remote_input_applied`: `11/11`
- `remote_input_executor`: `windows.send_input`
- `remote_input_coverage`: `click,drag,keyboard,wheel`
- `candidate_tier_last`: `p2p_udp`
- 会话结束质量：`render_fps_avg=9.21`，`render_fps_recent=8.37`，`stutter_gap_ms=5752`，`recent_gap_ms=330`
- 会话结束 Android 侧：`rendered_frames=5484`，`frames_dropped=248`，`frames_dropped_spike_max=59`，`render_low_fps_sample_count=288`
- 会话结束 Windows sender：`send_fps=8.94`，`send_kbps=130.00`，过程中多次落在 `6-12fps`
- 会话时长：约 `615501ms`，最终由 Android 控制端主动断开

## 证据文件

证据目录：`D:\yuancheng\RemoteDesk\.tmp\test-runs\win-android-20260630-093732`

- `proof-final.json`：最终 `/e2e-proof` 快照。
- `devices-final.json`：最终设备在线状态。
- `android-logcat-final.txt`：完整 Android logcat。
- `android-logcat-remote-filtered.txt`：Android 远控关键日志过滤版。
- `api-server-final-tail.log`：Relay 关键尾部日志。
- `desktop-tauri-agent-final-tail.log`：Windows Desktop 关键尾部日志。
- `android-final-screen.png`：模拟器最终截图，可见远控画面和 `windows.send_input` 输入回执。
- `android-final-ui.xml`：UI 树采集失败记录，内容为 `ERROR: could not get idle state.`

## 前置验证记录

本轮联调前已执行并通过：

- `scripts/check-e2e-proof-clients.ps1`
- `apps/server`: `go test ./...`
- `git diff --check`
- Android debug APK 构建
- Desktop 前端 `npm run build`

已知限制：

- Desktop Rust `cargo test` / `cargo test --no-run` 多次超时，未拿到完整通过结论。
- 真机不可见，故本报告不能代表 Android 真机验收。

## 风险与下一步

1. 优先定位 Windows native sender 编码/发送 FPS 波动：Desktop 日志显示 `encode_ms_avg` 与 `send_fps_low` 反复出现。
2. 对齐 Android `render_frame_sample`、`net_stats` 与 Desktop `probe.sample` 时间线，确认低 FPS 是发送侧瓶颈、Android 解码/渲染瓶颈，还是两者叠加。
3. 修复后复跑 Android -> Windows 短测，要求 `remote_input_applied=11/11` 继续保持，同时最近窗口 FPS 与丢帧指标达标。
4. 真机重新可见后再复跑真机链路；模拟器 proof 只能作为替代验证，不等于真机验收。
