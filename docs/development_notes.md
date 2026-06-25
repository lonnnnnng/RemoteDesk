# Development notes

## Bootstrap scope

当前仓库已落下：
- remote_desk 目录骨架
- protocol 最小契约
- Go server 最小骨架
- Desktop Tauri + Rust skeleton
- Android skeleton
- Docker Compose / Makefile / README

## Stub demo path

1. 启动 postgres
2. 启动 api-server
3. Desktop 或 Android 连接 `/ws`
4. 发送 `device.register.req`
5. 发送 `presence.heartbeat.req`
6. 发送 `session.request.req`
7. 服务端返回 `session.request.result.push` 和 `session.start.push`

## Current progress snapshot (2026-06-02)

### 已完成

- 服务端 WebSocket 中继主链路可用：设备注册、心跳、会话建立/结束、WebRTC 信令转发、控制端输入转发、受控端输入执行回执与 `/e2e-proof` 观测。
- 服务端 `/devices` 设备列表接口可用，Android 与 Desktop 均已接入设备发现与目标选择流程。
- Android 控制端已形成可操作的主流程：连接、注册、心跳、发起/结束会话、接收 WebRTC 视频、发送点击/拖拽/键盘/滚轮输入、处理输入执行回执，并输出首帧、渲染 FPS、接收码率、候选路径与会话总结。
- Desktop（macOS/Windows）agent 侧已接入原生链路：Tauri 调用 Rust capture 管线采集桌面，WebRTC native sender 以 H.264 track 发布画面，同时将会话内输入桥接到 host input 执行。
- macOS host input 为真实 Core Graphics 执行链路，Windows host input 为真实 SendInput 执行链路；两端都包含权限/能力检查、会话/发送方校验与结构化状态回传。
- Windows 桌面端不再只是代码层占位，已作为真实 agent 参与 Android -> Windows proof 验证；当前已确认输入落地可用，但视频稳定性仍待收口。

### 当前阶段

项目已从“关键能力替换 stub”进入“跨平台真实链路稳定性与产品化打磨”阶段。
当前主推进方向扩展为：Android 控制端 -> relay/server -> Windows/macOS Desktop agent。

### Recent validation snapshot (2026-06-02)

- 环境：本地 Android `Pixel_9` 模拟器（`emulator-5554`）控制 Windows agent `desktop-84a8584c`，relay 为 `http://127.0.0.1:18081`。
- 会话：`sess-1780404079279-9`，Android 控制端 ID `android-19e88591850`。
- E2E proof：单路 `android_to_windows` 完成，`proof_status=video_and_input_observed`，输入 `remote_input_applied=11/11`，覆盖 `click,drag,keyboard,wheel`，执行器 `windows.send_input`。
- 性能摘要：`first_frame_ms=5749`、`render_fps_avg=2.55`、`rendered_frames=104`、`recv_kbps_avg=57.34`、`send_fps=8.94`、`send_kbps=107.00`、`rtt_ms_avg=39.17`、候选路径 `prflx/host/udp`、分级 `p2p_udp`。
- 结论：Windows 端真实输入链路已验证；视频链路 proof 成功但质量不达标，约 `343.5s` 会话内出现长时间 `frame_stalled`，因此稳定性不能判定通过。

### 仍未完成的关键项

- Android 控制端与 Desktop 壳层仍有产品化 UI/交互细节待打磨。
- Android -> Windows 视频路径需要定位低 FPS 与 proof 后 `frame_stalled`：重点排查 Android WebRTC 渲染 stall、Windows sender 出帧连续性、解码/丢帧和 watchdog 恢复条件。
- 主链路需要持续补充稳定性 E2E 回归（断线重连、异常会话状态、权限异常、设备切换、弱网、长时前后台等场景）。
- 文档与运行手册需要与当前实现持续对齐，避免“历史快照”导致误判。

### 下一步建议

1. 优先定位 Android -> Windows `frame_stalled`，确认是 Android render/decoder、Windows sender、还是 WebRTC stats/watchdog 口径问题。
2. 复跑 Android -> Windows 5min+ 稳定性样本，再扩展到 50min soak 与弱网对照。
3. 固化 Android -> relay -> Windows/macOS 的稳定性回归清单与通过标准。
4. 继续围绕主链路做产品化打磨，并保持 Windows 桌面端开发、运行、编译和打包流程文档同步更新。
