# WebRTC Native Media Pipeline Plan

## Context

Current remote-desktop media path still contains a heavy fallback bridge:

1. Native capture screenshot (Rust)
2. PNG encode + Base64
3. JS decode + canvas draw
4. `canvas.captureStream(...)` to WebRTC sender

This introduces high CPU cost, extra copies, and low effective FPS under motion.

## Goals

1. Remove the PNG/Base64 frame bridge from the primary path.
2. Use real video tracks as WebRTC upstream media.
3. Stabilize startup quality ramp-up and reduce stutter.
4. Keep relay/signaling behavior unchanged.

## Non-Goals (This Phase)

1. No protocol version bump.
2. No server signaling redesign.
3. No Android-side codec stack replacement.

## Execution Phases

### Phase 1 - Direct Track First (in progress)

1. Desktop agent prefers direct `getDisplayMedia` track for WebRTC publishing.
2. Keep native PNG bridge as fallback only.
3. Raise capture track constraints from low-FPS defaults to interactive targets.
4. Tune sender parameters (`maxBitrate`, `maxFramerate`) after `addTrack`.
5. Add clear runtime logs for selected media path (direct track vs fallback).

### Phase 2 - Native Continuous Stream (next)

1. Replace screenshot polling with native continuous capture stream.
2. Feed native frames directly to WebRTC sender pipeline (no PNG/base64 hop).
3. Add capability handshake for native real-time media mode.

### Phase 3 - Adaptive Control + Telemetry (next)

1. Add sender adaptation based on RTT/loss/bitrate stats.
2. Add end-to-end metrics:
   - capture FPS
   - encoded/sent bitrate
   - packet loss / RTT
   - Android render FPS and frame resolution timeline
3. Expose quality presets (balanced / quality / low-latency).

## Android Client Optimizations

1. Throttle per-frame UI text updates to avoid UI-thread pressure.
2. Keep renderer sink unchanged; only reduce expensive text/status refresh frequency.

## Acceptance Criteria

1. Session setup succeeds with ICE connected in 3 consecutive runs (`scripts/triad_ctl.sh restart` -> start remote assist).
2. First-frame latency (`first_frame_ms`) is `<= 2000ms` for `standard` profile and `<= 2500ms` for `emulator` profile.
3. Session average render FPS (`render_fps_avg`) is `>= 24` on both Desktop controller stats and Android session summary in normal interaction scenarios.
4. Session average send FPS (`send_fps`) is `>= 24` with no continuous stall window (`< 10 FPS`) longer than 2 seconds.
5. Session average receive bitrate (`recv_kbps_avg`) and send bitrate (`send_kbps`) are both `>= 800 kbps` during dynamic scene tests (video playback / scrolling).
6. Canvas fallback share (`bridge_canvas_share_pct`) is `<= 20%` for stable runtime-capability environments; if this is not reachable due to runtime limits, the session must be explicitly tagged as capability-blocked.
7. Quality hint rules are aligned across Desktop / Android / Relay, and the same session must not output conflicting quality conclusions.
8. No regression for device discovery, session restart, and relay metric aggregation (`session.metrics.combined` present with both controller + agent reports).

## 智能调度策略（静态/动态）v1（验收基线）

目标只看两件事：

1. 静止页面优先清晰（文字/图标不糊）。
2. 动画/视频页面优先流畅（主观不卡顿，且可量化）。

### 场景判定（Desktop Agent）

1. `dynamic`（动态流畅）：
   - `send_kbps` 持续偏高（默认阈值约 `>=1400kbps` 或占当前档位可用码率 `>=34%`）。
   - 或已出现 `quality_limitation` 且 RTT 抬升（拥塞迹象）。
2. `static`（静态清晰）：
   - `send_kbps` 持续偏低（默认阈值 `<=520kbps` 且码率利用率 `<=16%`）。
   - FPS 不低、RTT 稳定、无质量受限信号。
3. 滞回防抖：
   - 进入动态：连续 `2` 个样本触发。
   - 进入静态：连续 `3` 个样本触发。
   - 场景切换最小驻留：`7s`。

### 场景策略（v1）

| 场景 | 目标 | 发送侧策略 | 档位倾向 |
| --- | --- | --- | --- |
| `dynamic` | 保流畅 | `degradationPreference=maintain-framerate`；快降档、慢升档 | 目标不高于 `balanced`（弱网可降到 `smooth`） |
| `static` | 保清晰 | `degradationPreference=maintain-resolution`；升档门槛降低 | 目标回到 `clear` |
| `mixed` | 过渡 | 沿用现有 EWMA + 快降慢升 | 默认 `clear` 起步 |

### 验证指标（本轮按此验收）

| 指标 | 动态场景（视频/滚动） | 静态场景（停留文本页） |
| --- | --- | --- |
| 发送 FPS (`send_fps`) | `>=24` 为达标线，短时波动允许 | `>=20` 可接受（清晰优先） |
| 渲染 FPS (`render_fps_avg`) | `>=24` 为达标线 | `>=20` 可接受 |
| 发送码率 (`send_kbps`) | 随内容上升（不长期锁死低码率） | 可回落，但需保持清晰 |
| 场景日志 | 出现 `webrtc.agent.adaptive.scene_switch` 到 `dynamic` | 出现 `scene_switch` 回 `static` |
| 档位行为 | 高动态时不长期停在 `clear` | 静止后可回 `clear` |

### 日志与报表字段（必须可见）

1. Desktop `mac.log`：
   - `webrtc.agent.adaptive.sample`（含 `scene`、`util`、`ewma_*`）
   - `webrtc.agent.adaptive.scene_switch`
   - `webrtc.agent.adaptive.degrade/upgrade/scene_shift`
2. 会话报表 `session.metrics.combined`：
   - `adaptive_scene`
   - `adaptive_scene_switches`
   - `adaptive_profile`
   - `send_fps/send_kbps/render_fps_avg`

## Audit Additions (2026-04-28)

These items are added from code-and-log audit to prevent wording ambiguity and hidden blockers.

| Audit ID | 分类 | 问题描述 | 归属改造点 | 状态 | 收敛标准 |
| --- | --- | --- | --- | --- | --- |
| A1 | 口径一致性 | Point 1 completion wording is too optimistic while runtime still often lands on canvas fallback path | 1, 3.3 | In Progress | Replace “Done” wording with capability-scoped completion + show latest `canvas_share` trend in tracker |
| A2 | 平台能力阻塞 | `fetch_generator` path is frequently unavailable (`unsupported_track_generator`) on current runtime, blocking no-canvas convergence | 1.2, 1.3, 3.3 | In Progress | 已新增 `bridge_capability_tier` 会话标注，下一步补 runtime 维度能力矩阵报表（supported / unsupported / workaround） |
| A3 | 参数口径一致性 | Emulator caps described in doc diverged from current code constants | 3.3, 2.2 | Open | Keep doc values synchronized with `main.js` constants in every update |
| A4 | 质量判定一致性 | Quality hint thresholds are not fully unified across Desktop / Android / Relay | 2.2, 6.3 | In Progress | Publish single threshold spec and enforce in all three sides |
| A5 | 进度可验证性 | Progress percentages can be over-optimistic without hard runtime guardrails | 1, 3, 6 | Open | Tie progress gates to session metrics (`first_frame_ms`, `send_fps`, `render_fps_avg`, `canvas_share`) |
| A6 | 会话画像粒度 | `controller_profile` only has `standard/emulator`, lacking explicit manual override and richer tiers | 3, 5.2 | Open | Add profile policy doc + optional override flow for controlled A/B testing |
| A7 | 验收标准缺失 | Previous DoD relied on subjective “visibly smoother” wording | 1-6 | Done | Replaced with quantized acceptance criteria above |
| A8 | 更新时效性 | Tracker timestamp and conclusions can become stale vs latest logs/code | All | Open | Update tracker timestamp and audit table on each milestone commit |

## Rollback Strategy

1. Keep fallback bridge path available behind runtime branching.
2. If direct-track path fails, auto-fallback to bridge and surface warning logs.
3. Baseline tag for rollback: `baseline-webrtc-refactor-2026-04-27`.

## Latest Implementation Update (2026-04-27)

1. Added an agent-side adaptive quality controller in the Desktop WebRTC sender pipeline.
2. Introduced a three-level profile ladder: `smooth`, `balanced`, `clear`.
3. Each profile now controls sender-side `maxBitrate`, `maxFramerate`, and `scaleResolutionDownBy`.
4. Adaptation decisions are now driven by outbound stats probe signals including send FPS, send bitrate, RTT, and quality limitation reason.
5. Degradation is triggered quickly under sustained low-FPS/high-RTT pressure; upgrade requires multiple stable healthy samples.
6. Native fallback capture config now follows the active profile at runtime by updating `maxWidth`, `maxHeight`, and `maxFps`.
7. Implemented Phase-2 first cut: native local MJPEG continuous stream endpoint (`capture_get_stream_endpoint`) to feed WebRTC bridge without per-frame JS `invoke + base64 decode + canvas draw` loop.
8. Native bridge now prefers media-element continuous stream and automatically falls back to legacy polling bridge if stream path is unavailable.
9. Current focus after this step: continue improving end-to-end smoothness while preserving signaling/session stability.
10. Added stream-first-frame gating in Desktop UI: MJPEG stream mode now requires the first decodable frame before being accepted, otherwise it falls back immediately.
11. Added session-scoped legacy lock for native bridge fallback: once `mjpeg_stream_*` path is detected as no-frame, the same session is forced to `polling_bridge` to avoid repeated bad-path renegotiation.
12. Added richer capture stream server observability in Rust (`stream.client_accepted`, `stream.client_request` with normalized target) and relaxed request-target parsing for absolute URL/query variations.
13. Updated stream response header to `Connection: keep-alive` for long-lived multipart MJPEG delivery semantics.
14. Added `mjpeg_stream_image_canvas` mode (image element + canvas bridge) as the new preferred fallback after `fetch` mode. This avoids WebKit `fetch/video` load failures while keeping the stream path and removing the invoke+base64 decode loop from the hot path.
15. Added agent offer-stage diagnostics (`agent.peer.ready.*`, `agent.offer.*`) and JS-to-native debug log mirror to make regression triage deterministic from `mac.log`.
16. Added render host null-guard to avoid UI re-render exceptions interfering with bridge loop timing.
17. Latest local triad validation (`RD_TURN_PORT=3500 ./scripts/triad_ctl.sh restart`) shows Android render throughput improved to ~22.5-23.2 FPS in `image_canvas` mode (previously typical ~16.5 FPS under legacy polling bridge).
18. Added session runtime guard for `fetch` mode on macOS WebView: after detecting `Load failed`, mark `fetch` as disabled for the current runtime and go directly to `image_canvas` fallback on subsequent attempts.
19. Refined bridge convergence to reduce canvas dependency: media stream path now tries `mjpeg_stream_direct` first, then a new no-canvas `mjpeg_stream_fetch_generator` path (`MediaStreamTrackGenerator + VideoFrame`), and only falls back to canvas-based bridge modes when necessary.
20. Stage gate (historical step): previously disabled legacy polling bridge by default so normal sessions stayed on video-track-centric paths.
21. Removed legacy polling bridge implementation from Desktop JS runtime path (`capture_take_frame + base64 + canvas`): recovery now keeps pure video-path renegotiation and no longer switches session into legacy mode.
22. Updated stream-bridge attempt order to further reduce canvas fallback hits: `fast direct -> fetch_generator -> long direct (no-canvas retry) -> image_canvas -> video_canvas`.
23. Added session-scoped bridge mode telemetry summary in Desktop runtime: mode hits are now recorded during session (`direct_track` / native bridge modes) and emitted as `native.bridge.mode.summary` on session end/reset.
24. Upgraded session summary to a one-line end-of-session report: bridge mode mix + key sender metrics (`send_fps`, `send_kbps`, `capture_fps`, `rtt_ms`, `frame_size`, candidate type, adaptive profile/decision).
25. Added agent-side permission fail-fast and clearer UI hinting: when capture permission is blocked, offer retry now stops immediately with explicit error, and viewport hint prefers `captureError` over generic "pushed after session starts" text.
26. Added Android-side render observability logs for live session triage: `first_rendered_frame` (first-frame latency after track attach) and periodic `render_frame_sample` (render FPS + size samples every 2s).
27. Added Android-side WebRTC network sampling logs via `PeerConnection.getStats`: periodic `net_stats` now reports receive bitrate, decoded frame counters, packet loss, jitter, RTT, and selected ICE candidate path (`candidate_pair`), enabling faster bottleneck attribution (network vs render).
28. Added Android session-level one-line summary log `session_summary` at session close/reset: includes duration, first-frame latency, average render FPS, average receive bitrate, average RTT, last candidate pair path/state, and final frame size/ICE state.
29. Added Android ICE URL ordering strategy in `applyIceServersFromSession`: URLs are now sorted as `stun -> turn(udp) -> turn(default) -> turn(tcp)` before PeerConnection creation, preferring direct and UDP paths while keeping TCP TURN fallback.
30. Added Desktop ICE URL ordering strategy in `sessionIceServers` with the same priority (`stun -> turn(udp) -> turn(default) -> turn(tcp)`), keeping candidate preference behavior aligned between Android and Desktop.
31. Tightened Desktop adaptive quality controller (`2.2`) to reduce oscillation and large resolution jumps: switched to EWMA-smoothed metrics, added bad-sample streak gates for degrade, and changed to fast-degrade / slow-upgrade hysteresis (`degrade cooldown` shorter, `upgrade cooldown + min dwell` longer, more stable samples before upgrade).
32. Increased native capture default `max_fps` to `24` and added test coverage to lock this interactive baseline.
33. Started `3.3` codec migration path: raw BGRA stream is now controllable in Desktop bridge (`native_raw_bgra`), with runtime capability probe and automatic revert to JPEG if generator path fails.

## Progress Tracker (实时更新)

Last updated: `2026-06-02 20:47`

### 6 点总览

| ID | 改造点 | 状态 | 完成度 | 当前结论 |
| --- | --- | --- | --- | --- |
| 1 | 替换发送链路为真实媒体轨道 | In Progress | 95% | Rust 原生 sender owner 路径已稳定跑通，当前三端联调主链路持续 `raw-bgra -> H264 -> WebRTC track`；剩余核心是异常边界与跨环境一致性收口 |
| 2 | WebRTC 发送参数可控化与自适应 | In Progress | 96% | 已接入“静态/动态 v1”场景策略（场景判定 + 滞回 + 场景化发送参数 + scene 指标上报），下一步按实测日志继续收敛阈值，稳定冲刺 `>=24fps` 与清晰度 |
| 3 | 采集配置从图片流转为视频流配置 | In Progress | 86% | 默认 24fps、raw/jpg 过渡与能力分层已落地；本轮新增“显式回退”避免静默跌落 canvas。no-canvas 仍受运行时能力约束，3.2 按需求暂缓 |
| 4 | Android 端渲染线程减负 | Mostly Done | 96% | WebRTC 与 legacy 两条渲染路径都已完成节流/采样与异常防抖；本轮 Android -> Windows 5.7min 实跑暴露 proof 后长时间 `frame_stalled`，渲染稳定性仍需专项收口 |
| 5 | 网络与候选策略优化 | In Progress | 94% | URL 优选、候选对分级、会话级 ICE policy 与降级恢复策略已打通；本轮已把 `relay_udp_high_rtt_ms` 默认阈值统一为 `220ms` 并完成实跑验证 |
| 6 | 端到端性能观测 | In Progress | 95% | Desktop/Android 已支持统一会话指标上报到 relay 且具备可视化；本轮 Android -> Windows proof 与 `session_summary` 已捕获 `render_fps_low`、输入覆盖和长时 stall 线索 |

### 任务明细（函数/文件级）

| Task ID | 对应点 | 具体改造内容（文件/函数） | 状态 | 完成度 | 剩余动作 |
| --- | --- | --- | --- | --- | --- |
| 1.1 | 1 | 直推共享屏轨道：`requestAgentMediaStream` 优先 `getDisplayMedia`，并在 `ensureAgentPeerReady` 中 `addTrack` | Done | 100% | 持续观察直推命中率 |
| 1.2 | 1 | 新增无 canvas 连续流桥接：`startNativeWebRtcBridgeViaMultipartTrackGenerator`（`TrackGenerator + VideoFrame`），并扩展为多实现探测（`MediaStreamTrackGenerator` / `VideoTrackGenerator` / `webkitMediaStreamTrackGenerator`）；新增 tauri 直推轨道预备复用路径（`selectCaptureSource/requestAgentMediaStream`）；新增 Rust `native_sender` 控制面命令与 JS 生命周期接线（`start/stop/push_signal/create_offer/drain_outbound_signals`）；新增 Rust 侧媒体采样 worker（`probe.first_frame/probe.sample`），并接入会话上报；新增完整 SDP/ICE 信令负载透传与 Rust 侧信令状态机（`remote/local offer/answer/ice/restart_ice` 计数与方向）；新增 Rust owner 出站队列（offer/ice）与 JS 转发泵；新增 shadow 本地视频轨道绑定（`add_track(video/H264)`）；新增 `openh264` 编码 + `TrackLocalStaticSample.write_sample` 出帧循环 | In Progress | 99% | 控制面、采样 worker、双向信令镜像、owner 出站信令泵、本地 track 绑定与 sample pump 已接通；剩余核心是 owner 模式下实网稳定性验证与异常边界收敛 |
| 1.3 | 1 | 连续流路径收敛：`startNativeWebRtcBridgeViaMediaStream` 调整为 direct -> fetch_generator -> long_direct_retry -> image_canvas -> video_canvas，并新增 canvas 回退“显式确认” | In Progress | 90% | 路径顺序与显式回退已完成，继续压降 `image_canvas` 命中率并满足 `canvas_share` 硬指标 |
| 1.4 | 1 | legacy 热路径退场：`startNativeWebRtcBridgeLegacy`（`capture_take_frame + base64 + canvas`）从常态路径移除 | Done | 100% | Desktop JS 中 legacy 函数、开关、会话锁与回退分支已删除；后续仅做稳定性回归 |
| 1.5 | 1 | 启动失败可观测性增强：`scheduleAgentWebRtcOfferRetry` 对采集权限阻断场景 fail-fast，避免重复重试；Agent 视口提示优先显示 `captureError` | Done | 100% | 后续可增加“一键打开系统设置授权页”入口 |
| 2.1 | 2 | 发送参数可控化：`tuneVideoSender` 设置 `maxBitrate/maxFramerate/scaleResolutionDownBy` | Done | 100% | 根据实测再微调参数上限 |
| 2.2 | 2 | 自适应升降档：`maybeAdjustAgentAdaptiveProfile` 改为 EWMA 平滑 + 连续坏样本降档门限 + 快降慢升滞回（`degrade/upgrade` 分离冷却与最小驻留时间）；新增 `scene=v1` 场景层（`static/dynamic/mixed`）与场景化 sender 策略 | In Progress | 97% | 已落地场景判定/切换日志、场景化 `degradationPreference` 与报表字段（`adaptive_scene`），下一步按三端实测进一步收敛阈值 |
| 2.3 | 2 | 首帧加速：增加 direct fast probe（`NATIVE_STREAM_DIRECT_FAST_*`）缩短等待回退耗时 | Done | 100% | 观察是否需要按机型分级超时 |
| 3.1 | 3 | 默认采集帧率升级：`apps/desktop/src-tauri/src/capture/mod.rs` 中 `DEFAULT_CAPTURE_MAX_FPS` 从 `12 -> 24`，并新增 `capture_config_default_uses_interactive_fps` 测试锁定默认值 | Done | 100% | 已完成，无剩余动作 |
| 3.2 | 3 | 会话级采集参数动态更新收敛：`capture_update_config`（JS `ensureNativeFallbackCaptureConfig` / `applyNativeFallbackProfile`）的策略统一与阈值整合 | Deferred | 70% | 按当前排期暂缓，待 3.3 主链路收敛后再集中处理 |
| 3.3 | 3 | 纯视频采集管线过渡：Rust 端支持 `raw-bgra-frame-stream` 编码与 `X-Frame-Format`，Desktop `fetch_generator` 支持 BGRA `VideoFrame` 解帧，并新增 raw 模式探测；当 raw 解帧失败时，先在 `fetch_generator` 内自动回退 `jpeg-frame-stream` 再重试，只有重试仍失败才继续降级到 canvas 路径；新增 `bridge_pipeline` 计数与比例（raw 成功率 / jpeg 重试成功率 / canvas 占比），并把 TrackGenerator/VideoFrame 能力判定前移到分支入口，避免无效 codec 切换与重复失败；新增 `fetch_skips` 原因计数与跨端聚合，直接标记 canvas 回退触发因子；新增 emulator 会话 canvas 输出分辨率硬上限（避免高动态场景桥接负载过高）；新增 canvas 回退显式确认机制；新增 `fetch_generator` 失败禁用的会话级强制重试与冷却恢复机制，避免单次失败长期锁死 canvas 路径 | In Progress | 85% | 继续做三端联调验证 raw 命中率与稳定性，重点观察 `bridge_pipeline + fetch_skips + stream_*_output_size` 指标后再进一步压缩 canvas fallback |
| 3.4 | 3 | 运行时能力分层矩阵：按 `MediaStreamTrackGenerator/VideoFrame/fetch` 能力把环境划分为 `no-canvas-ready` / `capability-blocked`，并把该标签写入会话总结；新增 `scripts/capability_matrix_report.py` 自动汇总 `session.metrics.combined` 的 tier/quality/path 分布与最近会话明细；Desktop 会话上报新增 `runtime_kernel/runtime_capability_signature/runtime_cap_*` 并由 relay 聚合到顶层；新增 `runtime_cap_native_sender/runtime_native_sender_*` 字段用于区分“能力缺失”与“原生 sender 媒体面未就绪”；新增 `native_sender_signaling_state/ns_signaling` 维度 | In Progress | 99% | 已支持从矩阵直接查看 native sender 运行等级、owner 模式信令状态与阻塞码；剩余跨 runtime 样本补齐后收口 |
| 4.1 | 4 | Android WebRTC 渲染文案节流：`rtcProbeSink` 按 `RTC_STATS_UI_UPDATE_INTERVAL_MS=250ms` 更新 UI | Done | 100% | 长会话压测验证无回退 |
| 4.2 | 4 | Android 旧帧流 UI 开销清理：`handleIncomingFrame` 已改为“首帧日志 + 2s 采样日志 + 250ms UI 节流”；新增 `screen.frame.push` 兼容入口、WebRTC 活跃时 legacy 帧解码短路、解码失败节流（1200ms）与重复错误聚合日志 | In Progress | 93% | 已完成三端联调 + 长会话压测；最新 Android -> Windows 实跑显示 legacy 风暴已被压住，但 WebRTC 渲染后续 stall 仍需定位 |
| 5.1 | 5 | 服务端 ICE 下发：`BuildStart` 返回 `stun + turn(udp/tcp)` | Done | 100% | 持续校验端口回退场景的 ICE 正常性 |
| 5.2 | 5 | 候选优选策略：Android `buildRtcNetworkStatsSummary + maybeApplyIcePathPolicy + classifyIceCandidateTier` 基于 selected candidate-pair 分级（`p2p_udp/relay_udp/relay_tcp/...`）并触发策略重协商；Desktop `collectAgentOutboundVideoStats + classifyIceCandidateTier` 对齐候选路径/分级并接入自适应决策；Relay `session.start.push.webrtc.ice_policy` 下发会话级策略（`mode/stun_enabled/turn_transport/relay_udp_high_rtt_ms`） | In Progress | 95% | 默认高 RTT 阈值已统一为 `220ms` 并经三端实跑验证；剩余长会话弱网样本回归统计 |
| 5.3 | 5 | ICE 观测：Desktop/Android 打通 `ice_*`、candidate type、selected pair 日志（Android 新增 `candidate_pair`/`pair_state`）；relay `session.metrics.combined` 顶层新增 `candidate_pair_last/candidate_tier_last/candidate_path/candidate_tier` 自动汇总 | In Progress | 90% | 继续补实网回归样本并观察阈值触发质量 |
| 5.4 | 5 | 控制端 profile 策略收敛：`controller_profile` 从 `standard/emulator` 扩展为可文档化策略层，并支持联调时手动覆盖 | Open | 10% | 明确 profile 与媒体上限映射，减少“隐式限流”导致的误读 |
| 6.1 | 6 | Desktop 发送侧观测：`collectAgentOutboundVideoStats` + `webrtc.agent.outbound_stats` + 会话结束总报表 `native.bridge.mode.summary` | Done | 100% | 已完成；后续仅补跨端统一汇总格式 |
| 6.2 | 6 | Android 端观测：`MainActivity.kt` 新增 `first_rendered_frame`（首帧时延）、`render_frame_sample`（2s FPS/分辨率样本）、`net_stats`（码率/丢包/抖动/RTT/候选路径）与 `session_summary`（会话一行总结）日志；并在 `activity_main.xml` + `MainActivity.kt` 新增实时指标面板（首帧、渲染FPS、接收码率、候选路径/分级、质量判定） | In Progress | 94% | 将 Android 观测与 Desktop 会话总结合并成统一端到端报表，并补“远端发送指标回传”直显 |
| 6.3 | 6 | 统一端到端报表：一次会话输出 capture/send/network/render 汇总；relay `session.metrics.combined` 顶层新增 `first_frame_ms/render_fps_avg/recv_kbps_avg/send_fps/send_kbps/capture_fps`，并附 `session_perf_summary + session_quality_hint` | In Progress | 78% | Android -> Windows 实跑已在 relay proof 与 Android `session_summary` 中串起发送、接收、首帧、RTT、候选路径和输入覆盖；后续继续按失败样本校准质量口径 |
| 6.4 | 6 | 质量判定阈值统一：Desktop `inferAgent/ControllerQualityHint`、Android `inferRtcQualityHint`、Relay `inferSessionQualityHint` 输出规则收口 | In Progress | 84% | 三端核心阈值与 `capability_blocked` 判定已一致；本轮新增 relay `rtt_high` 与 `path_*` 对齐规则，待补多会话统计与规则文档最终收口 |
| 6.5 | 6 | 长时后台稳定性压测（新增）：固定三端联调后执行“前台远控 5min + 后台驻留 30min + 动态场景压力（视频/滚动）10min + 回前台 5min”，全程抓取三端日志（`android-emulator.log/mac.log/relay.log/turn.log`）并输出会话级结论 | In Progress | 62% | 已新增 Android 模拟器 -> Windows agent 5.7min 稳定性实跑；proof 与输入覆盖通过，但 `render_fps_avg=2.55` 且 proof 后长时间 `frame_stalled`，因此稳定性未通过；剩余定位 Android 渲染 stall / Windows sender 连续性并复跑长时与弱网样本 |

### 本轮联调验证记录（2026-04-28）

1. 执行 `./scripts/triad_ctl.sh restart`，三端（relay/turn/mac/android）成功拉起，Android 与 Agent 均在线。
2. 新会话 `sess-1777341672533-1` 中已出现 `webrtc.agent.adaptive.sample`，日志包含 `ewma_fps/ewma_kbps/ewma_rtt` 字段，确认 EWMA 统计在运行。
3. 在临时网络压测后，同会话出现 `webrtc.agent.adaptive.degrade ... from=balanced to=smooth`，确认“快降”触发生效。
4. 同会话 `adaptive` 统计结果：`degrade=1`、`upgrade=0`（观察窗口内），且额外观测 70s 无升级事件，符合“慢升”门控预期。
5. 自动化触发新会话 `sess-1777349557333-1`（adb 点击“开始远控”）后，Desktop 日志出现 `webrtc.agent.adaptive.sample ... path_tier=p2p_udp`；Android `net_stats` 同步出现 `candidate_pair=srflx/srflx/udp candidate_tier=p2p_udp`，确认 5.2 跨端字段与分级策略已在实跑链路生效。
6. 强制策略联调（`RD_ICE_MODE=relay_only RD_ICE_DISABLE_STUN=1 RD_ICE_POLICY_RELAY_UDP_HIGH_RTT_MS=0`）会话 `sess-1777350903813-1` 中，Android `create_pc` 已切到 `ice_transport=RELAY`，`candidate_tier=relay_udp_high_rtt` 连续触发后出现 `ice_policy_recover` 与 `webrtc.offer (policy_candidate_path_1/2)`；Desktop 同会话出现 `path_tier=relay_udp_high_rtt`，确认 5.2 的“会话级策略下发 -> 双端统一分级 -> 控制端恢复动作”链路生效。

### 新增改造记录（2026-04-28）

1. Relay 新增 `session.metrics.report` 消息处理：允许控制端/受控端在会话结束后（短时窗口内）继续上报指标，避免 `session.end.push` 后立刻上报被判定为 `SESSION_NOT_FOUND`。
2. Relay 新增跨端聚合日志：当同一 `session_id` 收到 controller + agent 两端指标后，输出统一 `session.metrics.combined` 日志。
3. Desktop 在会话结束时新增 `session.metrics.report` 上报（复用会话总结数据）。
4. Android 在 `session_summary` 输出后新增 `session.metrics.report` 上报（静默发送，不污染 UI 操作日志）。
5. 三端联调实测会话 `sess-1777342657059-1` 已出现：
   - `session.metrics.reported`（controller + agent 各 1 条）
   - `session.metrics.combined`（跨端聚合 1 条）
6. Android `4.2` 已推进：`handleIncomingFrame` 从“每帧 UI+日志”改为“首帧日志 + 2s 采样日志 + 250ms UI 文案节流”，并在会话切换/关闭/解码失败时重置 legacy 帧统计状态，降低 UI 线程抖动风险。
7. Android `4.2` 继续收敛：补回 `screen.frame.push` 兼容入口；当 WebRTC 轨道已活跃时自动短路 legacy 帧解码并做 3s 采样日志；对 legacy 解码失败增加 1200ms 节流与抑制条数聚合日志，避免异常帧风暴拖垮 UI。
8. `4.2` 长会话压测（2026-04-28 23:42~23:45）通过：Android 连续输出 `render_frame_sample` 与 `net_stats`，会话保持稳定；未观察到 `legacy_*` 高频异常日志，说明新增 short-circuit 与失败节流未引入回归。
9. `5.2` 候选优选策略推进：Android 新增 `candidate_tier_last/ice_policy_restarts` 会话汇总字段，按 candidate-pair 分级触发 `policy_candidate_path_*` 重协商；Desktop 新增 `candidate_path/candidate_tier` 统计并用于自适应判定，完成跨端策略字段对齐。
10. `5.2` 继续收敛：Relay `session.start.push` 新增 `webrtc.ice_policy`（支持 `RD_ICE_MODE`、`RD_ICE_DISABLE_STUN`、`RD_ICE_TURN_TRANSPORT`、`RD_ICE_POLICY_RELAY_UDP_HIGH_RTT_MS`），Android/Desktop 均改为按会话策略读取 `relay_udp_high_rtt_ms`；Android `RTCConfiguration.iceTransportsType` 会按策略切换 `ALL/RELAY`，用于稳定复现退化路径并验证恢复策略。
11. `3.3` 继续收敛：Desktop `startNativeWebRtcBridgeViaMediaStream` 增加“raw-bgra 失败 -> 自动切回 `jpeg-frame-stream` 并在 `fetch_generator` 内重试”分支，且补充 `fetch_unavailable` 日志字段（`jpeg_retry_attempted/succeeded/reason`），减少因单次 raw 失败直接跌落 canvas 回退的概率。
12. `3.3` 继续收敛：Desktop `bridgeModeStats` 新增 `bridge_pipeline` 计数（`raw_attempts/raw_success/raw_failed/jpeg_retry_*`）并接入 `native.bridge.mode.summary + session.metrics.report`；同时对“不支持 `MediaStreamTrackGenerator/VideoFrame`”场景做快速判定，直接跳过无意义重试，缩短回退耗时。
13. `3.3` 继续收敛：将 `fetch_generator` 能力检测（`fetch + MediaStreamTrackGenerator + VideoFrame + MediaStream`）前移到分支入口；不支持时直接标记 `fetch_skipped: unsupported_track_generator`，避免先切 `raw-bgra` 再回滚的无效路径。
14. `3.3` 继续收敛：对 `fetch_skipped` 做会话内去重，避免同一次能力短路同时打印 `unsupported_track_generator` 与 `previous_load_failed` 双日志，减少排查噪音。
15. `3.3` 继续收敛：`native.bridge.mode.summary` 与 `session.metrics.report` 新增 `bridge_pipeline_ratios`（`raw_success_rate / jpeg_retry_success_rate / canvas_share`）和对应数值字段，支持按会话直接比较收敛效果。
16. `3.3` 继续收敛：为 `fetch_skipped` 增加“同会话同原因只记录一次”去重键（`session|reason`），避免受控端 offer 重试时重复刷 `unsupported_track_generator` 日志。
17. `3.3` 继续收敛：relay `session.metrics.combined` 顶层新增桥接摘要字段（`bridge_pipeline/bridge_pipeline_ratios/bridge_canvas_share_pct/...`），并由单测覆盖，后续跨端聚合日志无需再手动展开 `agent` 子对象即可读取关键收敛指标。
18. `3.3` 继续收敛：Desktop 会话指标新增 `bridge_fetch_skips/bridge_fetch_skip_total`（按原因计数 `fetch_skipped`），并在 relay `session.metrics.combined` 顶层透出；三端联调会话 `sess-1777355977466-1` 已验证顶层字段可直接读取（`bridge_pipeline`、`bridge_pipeline_ratios`、`bridge_fetch_skips`、`bridge_fetch_skip_total`、`bridge_canvas_share_pct`）。
19. `5.3 + 6.3` 收敛推进：relay `session.metrics.combined` 新增顶层自动汇总字段（`first_frame_ms/render_fps_avg/recv_kbps_avg/send_fps/send_kbps/capture_fps/candidate_pair_last/candidate_tier_last/candidate_path/candidate_tier`）以及 `session_perf_summary`、`session_quality_hint`，并补单测覆盖，减少跨端联调时人工 grep 成本。
20. 清日志三端联调验证（会话 `sess-1777362877808-1`）已确认：`session.metrics.combined` 顶层字段已落盘（`first_frame_ms/render_fps_avg/recv_kbps_avg/send_fps/send_kbps/capture_fps/candidate_pair_last/candidate_tier_last/candidate_path/candidate_tier/session_perf_summary/session_quality_hint`），可直接用于跨端质量判读。
21. 观测可视化推进：Android `activity_main.xml` 新增 `liveMetricsText` 指标面板，`MainActivity.kt` 复用现有渲染/网络采样更新首帧、渲染 FPS、接收码率、候选路径/分级与质量判定；Desktop `main.js` 新增 `Live Metrics` 卡片与控制端 inbound stats 探针，在 UI 直接展示首帧、渲染 FPS、接收码率、发送 FPS/码率、候选路径/分级、质量判定。
22. 码率/清晰度收敛推进：Desktop 自适应默认档位从 `balanced` 调整为 `clear`，并上调各档 `maxBitrate`、降低 `scaleResolutionDownBy`，减少会话初期被压到 `619x400` 的概率；Android/Desktop 的“低码率”质量判定改为“需同时出现 FPS 下降”才触发，避免静态画面场景误报。
23. 模拟器高动态卡顿专项收敛：Desktop 新增 `controller_profile=emulator` 会话硬上限（当前代码口径：`640x360@24fps@3.2Mbps` + `scaleResolutionDownBy<=1.0`，并固定 `EMULATOR_SESSION_PROFILE_INDEX=1`），并在 `mjpeg_stream_image_canvas/mjpeg_stream_canvas/mjpeg_stream_fetch_canvas` 三条 canvas 路径统一按会话上限缩放输出，新增 `stream_*_output_size` 日志用于定位“源分辨率 -> 输出分辨率”变化与限流命中。
24. A2 能力分层推进：Desktop 会话指标新增 `bridge_capability_tier`（`no_canvas_ready/capability_blocked/canvas_only_unknown`），并写入 `native.bridge.mode.summary` 与 `session.metrics.report`；relay 聚合层已透出该字段，便于直接识别 no-canvas 被 runtime 能力阻塞。
25. 6.4 阈值统一推进：Desktop/Android/Relay 三端质量判定核心阈值已对齐（`fps_low<10`、`stall_fps<16 + low_bitrate<350kbps`、`rtt_high>=220ms`），relay 质量判定新增 `capability_blocked` 优先级，减少“同会话不同结论”。
26. 结束会话实跑校验（会话 `sess-1777372181227-1`，`2026-04-28 18:30`）通过：relay 已连续记录 `session.end.req -> session.ended -> session.metrics.combined`；同一条 combined 日志内 `bridge_capability_tier=capability_blocked` 与 `session_quality_hint=capability_blocked` 一致，且顶层保留 `bridge_canvas_share_pct/bridge_fetch_skips`，满足 A2 与 6.4 当前阶段验证目标。
27. `3.4` 报表自动化已落地：新增 `scripts/capability_matrix_report.py`，可从 `relay.log` 自动输出能力分层矩阵（`tier/runtime_bucket/quality_hint/candidate_tier` 分布）和最近会话明细，替代人工 grep。
28. `3.4` 收口推进：Desktop `session.metrics.report` 新增 runtime 签名字段（`runtime_kernel/runtime_capability_signature/runtime_cap_*`）；relay `session.metrics.combined` 顶层已透出对应字段；矩阵脚本已新增 runtime 维度统计（`Runtime Kernel Distribution/Runtime Signature Distribution`）与 recent 明细列，具备跨环境对比能力。
29. `3.4` 会话实测验证通过（会话 `sess-1777374695791-1`，`2026-04-28 19:12`）：relay `session.metrics.combined` 顶层已包含 `runtime_kernel/runtime_capability_signature/runtime_cap_track_generator/...`，矩阵脚本已输出 runtime 分布与签名分布，形成可直接对比的跨环境矩阵基础能力。
30. tauri 去 canvas 收敛推进：Desktop `fetch_generator` 新增 TrackGenerator 多实现探测与实例化（`MediaStreamTrackGenerator` / `VideoTrackGenerator` / `webkitMediaStreamTrackGenerator`），并在 `native.bridge.stream.started` 增加 `generator_mode` 字段；runtime 签名新增 `track_generator_impl`，用于矩阵中直接识别当前运行时的可用实现。
31. tauri 去 canvas 收敛推进：新增“用户手势预备直推轨道并在会话开始复用”路径。`selectCaptureSource` 在 tauri + `getDisplayMedia` 可用时会优先预备直推轨道；`requestAgentMediaStream` 会优先复用已预备轨道，避免会话启动时再次非手势触发 `getDisplayMedia` 失败而落回 `image_canvas`。
32. tauri 原生 sender 主线启动：新增 Rust `native_sender` 模块（`apps/desktop/src-tauri/src/native_sender/mod.rs`）与 Tauri 命令导出（`native_sender_get_capabilities/status/start/stop/push_signal`），先完成可观测控制面骨架并明确 blocker（当前口径已更新为 `native_sender.webrtc_transport_missing`）。
33. Desktop JS 接入原生 sender 状态面：`main.js` 增加 `nativeSenderCapabilities/nativeSenderStatus` 归一化与刷新逻辑，并把 `runtime_cap_native_sender/runtime_native_sender_support_level/runtime_native_sender_blocker` 注入会话上报与 runtime 签名。
34. Desktop JS 接入原生 sender 信令控制面：在 `session.start.push/session.end.push` 与连接重置路径执行 `start/stopNativeSenderControlPlane`，并在接收 `webrtc.offer/webrtc.answer/webrtc.ice_candidate/webrtc.restart_ice` 时同步 `pushNativeSenderSignal(session_id, type, trace_id)`，为后续 Rust 媒体面替换提供完整信令上下文。
35. 修复 native sender 控制面参数映射错误：`invoke("native_sender_start/push_signal")` 入参从 `camelCase` 改为 Rust 结构体对应的 `snake_case`（`session_id/signal_type/trace_id`），解决“控制面未真正启动/未接收信令”的隐性失败。
36. 增强 native sender 观测：`main.js` 新增 `native.sender.start_ok/start_failed/signal_pushed/stop_ok` trace 事件，并纳入 JS->native 日志镜像规则（`shouldMirrorTraceToNative`），便于直接从 `mac.log` 核验控制面行为。
37. relay + 矩阵报表补齐 native sender 维度：`session.metrics.combined` 顶层新增 `runtime_cap_native_sender/runtime_native_sender_support_level/runtime_native_sender_blocker`，`scripts/capability_matrix_report.py` 新增支持等级/阻塞码分布与 recent 列；实跑会话 `sess-1777377565098-2` 已验证字段与分布输出生效。
38. Rust 原生 sender 媒体面推进（实验态）：`native_sender` 新增后台采样 worker（`rd-native-sender-probe`），会话期间持续调用 Rust capture 管线并产出 `media_probe_fps/media_probe_kbps/frame_count/total_bytes` 状态字段，同时输出 `probe.first_frame/probe.sample/probe.stopped` 日志。
39. 采样阻塞码口径收敛：native sender 能力从 `planned` 提升为 `prototype`，阻塞码从 `native_sender.encoder_pipeline_missing` 调整为更精确的 `native_sender.webrtc_transport_missing`，用于区分“媒体采样已具备”与“WebRTC 原生发送未接通”两个阶段。
40. 矩阵分类规则对齐新阻塞码：`scripts/capability_matrix_report.py` 将 `native_sender.webrtc_transport_missing` 纳入 `native_sender_pipeline_missing` 桶，避免报表出现误导性的 `blocked_other` 分类。
41. 去 Base64 化推进（Rust 内部链路）：`capture` 模块新增 `capture_take_frame_bytes()`，`native_sender` 采样 worker 改为直接消费编码字节，移除“Base64 长度反推码率”逻辑，降低采样侧额外开销并减少统计误差。
42. native sender 状态回传增强：Desktop `main.js` 新增 `native sender status probe` 定时刷新，并在 `session.metrics.report` 写入 `native_sender_probe_*` 字段（`probe_fps/probe_kbps/probe_frame_count/...`）；relay `session.metrics.combined` 顶层已透出对应字段，矩阵 recent 明细新增 `ns_lifecycle/ns_probe_fps/ns_probe_kbps`。
43. 三端实跑验证（会话 `sess-1777378571403-1`，`2026-04-28 20:16`）通过：relay `session.metrics.combined` 已包含 `native_sender_lifecycle=running`、`native_sender_probe_fps=16.75`、`native_sender_probe_kbps=3333.94`、`native_sender_probe_frame_count=90`，确认“Rust 采样 -> JS 上报 -> relay 聚合 -> 矩阵展示”链路闭环。
44. Rust 原生 sender 信令接入升级：`NativeSenderSignalEnvelope` 扩展为完整 SDP/ICE 负载（`sdp/candidate/sdp_mid/sdp_mline_index`），并在 Rust 侧新增信令状态机字段（`native_sender_signaling_state`、`remote_answer_count`、`remote_candidate_count`、`last_remote_candidate_type` 等），不再只记录 `signal_type`。
45. JS->Rust 信令透传升级：`pushNativeSenderSignal` 现已传递完整 payload 并上报 `payload_bytes`，会话报表新增 `native_sender_last_signal_*` 与 `native_sender_remote_*` 系列字段，relay 顶层聚合同步透出。
46. 阻塞码向下收敛：native sender 运行时能力口径从 `prototype/webrtc_transport_missing` 进阶为 `transport_signaling_ingested/peer_connection_runtime_missing`，表示“信令与状态机已接通，下一阻塞点是原生 PeerConnection + RTP 轨道发布”。
47. 三端实跑验证（会话 `sess-1777380314020-1`，`2026-04-28 20:45`）通过：`session.metrics.combined` 顶层已包含 `native_sender_signaling_state=ice_syncing`、`native_sender_remote_answer_count=1`、`native_sender_remote_candidate_count=4`、`native_sender_last_remote_candidate_type=relay`，并保持 `native_sender_probe_fps=16.55`，确认“Rust 信令状态机 + 采样报表”双链路生效。
48. `native_sender` 信令方向收敛推进：Desktop `sendEnvelope` 已将所有出站 `webrtc.*` 同步镜像到 Rust 控制面（`signal_direction=outbound`），入站信令明确标记 `inbound`；Rust 状态新增 `last_signal_direction`、`inbound/outbound` 计数、`local_*` 与 `remote_*` 细分计数，并透出到 `session.metrics.report -> session.metrics.combined`，为下一步原生 PeerConnection 接管提供方向完备的信令上下文。
49. Rust WebRTC runtime 接入里程碑：`native_sender_start` 已初始化 shadow `RTCPeerConnection`，`native_sender_push_signal` 会对 mirrored 信令执行真实 `set_local_description/set_remote_description/add_ice_candidate`，并输出 `webrtc.signal_applied` 日志；能力口径更新为 `peer_connection_signaling_applied`，阻塞码更新为 `native_sender.track_publish_missing`（剩余工作收敛到媒体轨道发布）。
50. Rust 媒体面推进到“轨道已绑定”阶段：shadow `RTCPeerConnection` 初始化时已创建并 `add_track(video/H264)`，能力口径更新为 `peer_connection_track_bound`，阻塞码更新为 `native_sender.video_encoder_missing`，明确剩余工作从“轨道接线”收敛到“编码 + write_sample 出帧”。
51. 原生 sender 观测增强：新增 `native_sender_shadow_runtime_ready/native_sender_shadow_track_bound/native_sender_shadow_last_apply_action` 指标并接入 `session.metrics.report -> session.metrics.combined`，可直接区分“runtime 未建好 / track 未绑定 / 信令 apply 动作失败”三类问题，降低后续编码阶段排障成本。
52. Rust 媒体面推进到“编码出帧”阶段：`native_sender` worker 已接入 `openh264`，对 `raw-bgra` 捕获帧执行 `H264 encode -> TrackLocalStaticSample.write_sample`，并新增 `encoder.ready/encoder.sample_published/encoder.skip_unsupported_mime` 日志；能力口径更新为 `track_sample_pump_experimental`，阻塞码更新为 `native_sender.shadow_signaling_ownership_missing`（剩余核心收敛到 native 信令主导权与会话绑定）。
53. 兼容路径增强：`native_sender` H264 sample pump 现支持 `image/jpeg` 输入（`jpeg-decoder -> RGB -> YUV -> openh264 encode`），不再仅依赖 `raw-bgra` 帧源，显著降低不同 runtime/codec 下的“编码入口不可用”概率。
54. Rust 信令主导权实验态接通：`native_sender_start` 新增 `ice_servers` 入参并在 Rust 侧构建 owner `RTCPeerConnection`；新增 `native_sender_create_offer` 与 `native_sender_drain_outbound_signals` 命令，结合 `on_ice_candidate` 出站队列实现“Rust 产出 offer/ice，JS 仅转发 relay”；Desktop agent 会话默认切换到 owner 路径（`dry_run=false`），入站 answer/ice 仅交给 Rust apply，出站信令不再镜像回 Rust，避免双重 apply。
55. 修复 Desktop Local Shared View 预览黑屏/底部残留：`raw-bgra` 预览转换改为不透明 alpha，并调整视口渲染层级逻辑（仅在存在真实媒体流时渲染 `<video>`），避免黑色视频层覆盖预览图层。
56. sender 性能瓶颈修复：`native_sender` worker 从“固定 42ms 轮询等待”改为“按目标帧间隔做剩余时间补偿”，消除处理链路后的二次等待；三端联调会话 `sess-1777389563559-1` 实测 `probe_fps` 从 `~12.6-13` 提升到 `~23.1-23.4`，Android `render_frame_sample` 同步稳定在 `~23.2fps`。
57. 新增 canvas 回退显式确认：`startNativeWebRtcBridgeViaMediaStream` 在 no-canvas 路径不可用时，不再静默跌落 `image_canvas/video_canvas`，改为会话级一次确认（允许后仅当会话生效，拒绝则保持严格无 canvas 并明确报错）。
58. 新增回退确认可观测性：增加 `native.bridge.canvas_fallback.confirmed` 与 `native.bridge.canvas_fallback.blocked` 日志事件，并记录 `session_id/reason/stream_url`，便于回归与定位“用户决策导致的回退/拒绝”链路。
59. Android 高频连接/断开闪退修复（`2026-04-29`）：定位并修复 `MainActivity.appendLog` 并发访问导致的 `ConcurrentModificationException`（`ArrayDeque` 迭代期间被异步写入）。修复方式为“非主线程统一切回 UI 线程 + 日志队列加锁写入并快照渲染”；完成后执行高强度压测（清空 logcat 后连续 30 次连接/断开），结果：无 `FATAL EXCEPTION`、无 `ConcurrentModificationException`、进程持续存活（`pid=3287`），`android-emulator.log` 记录 `session_summary` 共 30 条，判定通过。
60. `3.3` 收敛补丁（`2026-04-29`）：Desktop `fetch_generator` 新增“禁用后恢复”机制。对于瞬时失败（如 `load failed`）仅临时禁用并按 `NATIVE_BRIDGE_FETCH_REENABLE_COOLDOWN_MS=15000` 自动恢复重试；新会话 `session.start.push` 会强制 re-enable 一次并重试 no-canvas 路径。新增 `native.bridge.stream.fetch_disabled/fetch_reenabled` 日志（含 `reason/permanent/trigger/elapsed_ms/force`），用于追踪“为何走到 canvas fallback”与“何时恢复无画布桥接”。
61. `5.2 + 6.3/6.4` 收口推进（`2026-04-29`）：统一三端 `relay_udp_high_rtt_ms` 默认阈值到 `220ms`（server policy 下发默认、Android/Desktop fallback 默认同步调整）；relay `inferSessionQualityHint` 规则新增 `path_*` 与 `rtt_high` 判定，并移除“仅接收码率低就判低质”的单因子分支，避免静态页面误报。
62. 三端实跑验证（会话 `sess-1777478208185-1`，`2026-04-29 23:56`）通过：Android 日志出现 `ice_policy_applied relay_udp_high_rtt_ms=220.00`；同会话 `render_fps_avg=27.01`、`recv_kbps_avg=147.55`、`candidate_tier=p2p_udp` 条件下，relay `session.metrics.combined` 输出 `session_quality_hint=stable`，确认“低码率但不掉帧”不再误判。
63. `6.5` 自动化落地（`2026-04-30`）：新增 `scripts/soak_6_5.sh`（自动四阶段压测：前台/后台/动态压力/恢复，支持 `--quick` 与阶段时长参数）和 `scripts/soak_report.py`（自动解析 Android/mac/relay/turn 日志并输出 PASS/FAIL 报告到 `.rd_runtime/reports`）。
64. `6.5` quick 自检通过（会话 `sess-1777478954885-1`，`2026-04-30 00:09`）：脚本全流程成功执行并产出报告 `soak_6_5_20260430_001226.md`；判定结果 `PASS`，关键指标 `render_fps_avg=26.95`、`longest_low_fps_streak_sec=0.0`、`max_frames_dropped_spike=1`、`session_quality_hint=stable`。
65. `6.5` 会话绑定口径修复（`2026-05-01`）：修复 `scripts/soak_6_5.sh` 在历史日志存在时误命中旧 `进入会话` 行的问题。新增 `wait_for_new_pattern/current_line_count`，仅匹配“本次启动后新增日志”并据此提取 `session_id`；修复后 quick 回归会话 `sess-1777566095130-3` 报告 `PASS`（`render_fps_avg=26.76`），确认报表不再误绑历史会话。
66. Android -> Windows 稳定性实跑（`2026-06-02`，本地 `Pixel_9` 模拟器 `emulator-5554` -> Windows agent `desktop-84a8584c`）：relay `http://127.0.0.1:18081` 上会话 `sess-1780404079279-9` 完成单路 proof，`proof_status=video_and_input_observed`，`controller_platform=android`，`agent_platform=windows`，`first_frame_ms=5749`，`remote_input_applied=11/11`，输入覆盖 `click,drag,keyboard,wheel`，执行器为 `windows.send_input`。同次汇总捕获 `render_fps_avg=2.55`、`rendered_frames=104`、`recv_kbps_avg=57.34`、`send_fps=8.94`、`send_kbps=107.00`、`rtt_ms_avg=39.17`、`candidate_pair=prflx/host/udp`、`candidate_tier=p2p_udp`，质量提示为 `render_fps_low`。结论：Windows 端真实输入落地链路可用，Android 控制侧可观察到视频与四类输入 proof；但会话约 `343.5s` 后的最终总结显示视频平均 FPS 偏低，proof 后出现长时间 `frame_stalled`（watchdog 持续跳过恢复，`frames_decoded` 基本停在约 `105`，`frames_dropped` 增长到约 `205`），因此 `6.5` 不能判定稳定性通过，下一轮优先定位 Android WebRTC 渲染 stall 与 Windows sender 出帧连续性。
