# Android 真机远控 Mac 任务与验证清单

Last updated: `2026-06-26 11:50:51 +0800`

本文档记录当前“Android 真机控制 Mac”路线的已完成事项、已验证证据、未完成事项和下次继续执行的任务/验证清单。结论只按仓库代码、脚本、本地日志和报告证据记录；`.rd_runtime/` 下的日志与报告是运行产物，继续保持 ignored，不提交到仓库。

## 当前快照

- 当前分支：`main`
- 当前远端：`origin=https://github.com/lonnnnnng/RemoteDesk.git`
- 上一已验证基线：`8836c5d 记录远控进度与验证清单`
- 今日阶段保存提交：`a2e6d4d 保存安卓真机远控阶段进度` 已推送到 `origin/main`
- 当前已阶段保存但仍待真机复测的源码/脚本/文档改动：
  - `apps/android/app/src/main/AndroidManifest.xml`
  - `apps/android/app/src/main/java/com/remotedesk/app/controller/StubSessionController.kt`
  - `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - `apps/desktop/src-tauri/src/lib.rs`
  - `apps/desktop/src-tauri/src/native_sender/mod.rs`
  - `apps/server/internal/session/stub.go`
  - `apps/server/internal/session/stub_test.go`
  - `apps/server/internal/transport/ws.go`
  - `apps/server/internal/transport/ws_test.go`
  - `docs/remote_control_progress_checklist.md`
  - `scripts/short_reconnect_check.sh`
  - `scripts/soak_6_5.sh`
  - `scripts/soak_report.py`
  - `scripts/triad_ctl.sh`
- 当前 ignored 产物仍未纳入 Git：`.rd_runtime/`、`apps/android/.gradle/`、`apps/android/app/build/`、`apps/android/build/`、`apps/desktop/dist/`、`apps/desktop/node_modules/`、`apps/desktop/src-tauri/gen/`、`apps/desktop/src-tauri/target/`、`apps/server/.rd_runtime/`、`scripts/__pycache__/`
- 最新真机短断验证已通过：`.rd_runtime/reports/short_reconnect_20260626_105952.md`
- 最新新版真机 soak 仍未通过：`.rd_runtime/reports/soak_6_5_20260626_110819.md`，失败点是 `render_fps_avg=23.52 < 24.0` 和 `visible_frame_gap_ms_max=3692`
- 上一轮 soak `103151` 仍保留为历史对照：原报告 `visible_frame_gap_ms_max=0`、`phase_frame_gap_ms_max=-` 有统计不足，临时重算 `/tmp/rd_soak_report_103151_recalc.md` 得到可见最大帧间隔 `861ms`
- 当前已保存一批针对 `110819` soak 失败点的修复/诊断改动，但这些改动还没有安装到真机，也没有重新跑短断和 soak；下次必须先安装新 APK 再验证，不能把 `105952` 和 `110819` 当作这些新改动的验收结果
- 当前不能宣称最终目标完成：短断、首帧、输入和短窗口质量已经通过最新真机验证，但新版 soak 的平均 FPS 和可见帧间隔仍未达标，且人工肉眼流畅验收还没有记录

## 当前目标

- Android 真机可以稳定看到 Mac 屏幕。
- Android 真机可以控制 Mac 鼠标和键盘。
- 短时间掉线后自动恢复，不需要用户手动重新发起。
- 画质清晰、播放流畅，肉眼无明显卡顿。
- 每个功能点都能用脚本或日志证明，发现 bug 后修正再复测。

## 已完成并通过验证

| ID | 工作项 | 状态 | 当前证据 | 备注 |
| --- | --- | --- | --- | --- |
| D1 | 仓库远端与忽略规则 | 已完成 | `origin=https://github.com/lonnnnnng/RemoteDesk.git`；`HEAD=origin/main=8836c5d`；`git ls-files` 检查未发现 tracked 的 `.rd_runtime/`、`node_modules/`、`dist/`、`build/`、`target/`、`.gradle/`、`__pycache__` 或常见包产物 | 运行日志、报告和构建产物保持 ignored，不提交 |
| D2 | 工程静态检查和构建 | 已验证 | 已通过 `bash -n scripts/short_reconnect_check.sh`、`bash -n scripts/soak_6_5.sh`、`bash -n scripts/triad_ctl.sh`、`python3 -m py_compile scripts/soak_report.py`、`git diff --check`、`cd apps/server && go test ./...`、`cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug --console=plain`、`cd apps/desktop && npm run build`、`cd apps/desktop/src-tauri && cargo test` | 只证明脚本语法、Go 单测和三端构建/测试通过，不等同于最终远控验收完成 |
| D3 | Android 真机 profile 的 TURN host 过滤 | 已验证 | `apps/server/internal/session/stub_test.go` 新增并通过 `TestFilterTurnHostsForAndroidPhone` 和 `TestFilterTurnHostsKeepsEmulatorGatewayForEmulator`；真机 profile 不下发 emulator 专用 `10.0.2.2` | 真机不再拿 emulator 网关；emulator 路径仍保留 `10.0.2.2` |
| D4 | Android 本地 ICE candidate 生成、发送和 Mac 接收 | 已验证 | 短断联调日志已经出现 `local_ice_candidate_callback`、`local_ice_candidate_send`、relay 转发 Android -> agent 的 `webrtc.ice_candidate`，Mac metrics 出现 `native_sender_remote_candidate_count=1`；最新短断 `105952` 已完成 `relay_udp` 链路和视频/输入 proof | 旧问题 `local_ice_candidate_sent=0` 已被真机运行证据打破 |
| D5 | 初始会话首帧 | 已验证 | 最新短断通过报告 `.rd_runtime/reports/short_reconnect_20260626_105952.md`：old session `sess-1782442759990-1` 能进入可恢复流程；此前同批日志已证明初始会话 `first_rendered_frame size=1114x720` | 旧的“初始会话无首帧”不能作为当前结论 |
| D6 | 短断后自动恢复和新会话首帧 | 已验证 | `.rd_runtime/reports/short_reconnect_20260626_105952.md`：`overall=PASS`，旧会话 `sess-1782442759990-1`，新会话 `sess-1782442776709-1`，relay 断开 `4s`，恢复 `15860ms`，新首帧 `1114x720`，`since_track_ms=4332` | 自动恢复生成了新会话，并出现新首帧 |
| D7 | Android -> Mac 输入控制覆盖 | 已验证 | 最新短断报告：`session_e2e_proof_status=video_and_input_observed`、`remote_input_applied=11/11`、`remote_input_failed=0`、覆盖 `click,drag,keyboard,wheel` | 输入链路不是仅 UI 发包，Mac 端已实际应用 |
| D8 | 短断恢复后的短窗口质量 proof | 已验证 | 最新短断报告：`quality_proof_passed=True`，`quality_fps_avg_recent=23.85`，`quality_fps_min_recent=21.89`，`quality_recent_samples=5`，`quality_drop_delta_recent=0`，`quality_candidate_tiers=relay_udp` | 这证明短断恢复后的短窗口质量已过，但不能替代长时间 soak |
| D9 | 历史较长真机 soak 基线 | 部分通过 | `.rd_runtime/reports/soak_6_5_20260626_082105.md` 曾有 `overall=PASS`，`render_fps_avg=24.13`，`rtt_ms_avg=11.05`，`candidate_tier_last=p2p_udp`，`remote_input_applied=11/11` | 不能视为最终流畅验收完成，因为后续分析和新版报告仍暴露平均 FPS 与帧间隔边界问题 |
| D10 | 阶段化 soak 报告能力 | 工具能力已验证 | `scripts/soak_report.py` 已能从 `render_frame_sample` 的 `sample_gap_ms` 重算阶段 gap 和阶段 FPS；最新 `110819` 报告直接给出 `visible_frame_gap_ms_max=3692` 和分阶段 gap/FPS | 原始 `103151` 报告里的 `visible_frame_gap_ms_max=0` 属于统计不足，后续报告已补上阶段化证据 |
| D11 | Mac native sender 首轮性能调优 | 已验证但未闭环 | `apps/desktop/src-tauri/src/native_sender/mod.rs` 已把 encoder 线程从 `2` 调到 `4`，周期关键帧从约 `fps` 帧拉长为 `fps*4`；最新 Mac 日志出现 `encoder.ready ... capture_fps=26 encoder_threads=4 intra_period_sec=4 force_intra_frames=156`；短断 `105952` 仍 PASS | 这只证明改动生效且短断未退化；soak `110819` 仍 FAIL，所以不能视为流畅性完成 |

## 最新未通过和未闭环

| ID | 验证项 | 结果 | 已观察事实 | 结论 |
| --- | --- | --- | --- | --- |
| F1 | 新版真机 soak 整体结果 | 未通过 | `.rd_runtime/reports/soak_6_5_20260626_110819.md`：`overall=FAIL` | 当前代码不能提交为“流畅性完成” |
| F2 | Soak 平均渲染 FPS | 未通过 | `render_fps_avg=23.52 < 24.0`，比 `103151` 的 `22.92` 有提升，但仍未过线 | 主要未达标项；需要继续定位 Android 渲染/采样、动态压力阶段和 Mac sender 节奏之间的真实瓶颈 |
| F3 | Soak 可见帧间隔统计 | 未通过 | 最新报告 `visible_frame_gap_ms_max=3692`，阶段最大 gap：foreground `657`、background `124`、dynamic `3692`、recovery `1739` | 已超过当前 `<1000ms` 验收线，不能直接宣布“肉眼无明显卡顿” |
| F4 | Soak 分阶段 FPS | 未达标 | 最新报告 `phase_render_fps_avg={'background': 23.6, 'dynamic': 23.52, 'foreground': 23.26, 'recovery': 23.95}` | 所有阶段都仍在 `24fps` 下沿附近，dynamic/recovery 同时有 1s 级 gap |
| F5 | Android 渲染/主线程或动态压力影响 | 未闭环 | 最新 soak 的 Mac sender 后半程常见 `24.8-25.4fps`，`encode_ms_avg` 约 `28ms`；但 Android 侧仍记录 `render_frame_gap_spike_count=4`、最大 gap `3692ms`，relay combined 显示 `session_quality_hint=render_frame_stutter` | 下一步不要只放宽阈值，要对照 Android `render_frame_sample`、`render_frame_gap_spike`、动态 swipe 压力和 logcat/GC/Choreographer 证据 |
| F6 | 人工肉眼流畅验收 | 未完成 | 目前只有脚本、日志和报告证据，没有本轮修复后的人工动态场景观察记录 | “肉眼无明显卡顿”必须等 soak 达标后再人工确认 |
| F7 | 长时间后台/弱网稳定性 | 未完成 | 当前主要是短断和约数分钟 soak，未覆盖 30-50 分钟后台、弱网、网络抖动 | 这是最终产品化前的后续验证，不阻塞当前 P0/P1 修复，但不能忘 |
| F8 | Android -> Windows 路线 | 未复测 | README/旧记录保留过 Windows 路线历史问题；本清单只记录 Android 真机 -> Mac 最新证据 | Mac 路线稳定后再用同一套脚本复测 Windows agent |

## 已改但未重新真机验证

这些项目已经通过阶段保存提交进入仓库，但证据只到脚本语法/构建层面；还没有安装新 APK 到 `wsvwypiz7xwslvl7`，也没有跑出新的短断 PASS 和 soak PASS 报告。

| ID | 改动 | 预期解决的问题 | 下一步验证 |
| --- | --- | --- | --- |
| U1 | `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt` 将 RTC 指标面板刷新从 `250ms` 降到 `1000ms`，拖拽输入节流从约 `16ms/0.0025` 调到 `33ms/0.004`，并减少连续拖拽 mouse down/up 的 UI 成功日志 | 降低动态控制阶段 Android 主线程和日志/UI 刷新的压力，避免控制端自己压住 Surface 渲染 | 安装新 APK 后跑短断和 soak，对比 `render_frame_gap_spike`、`recent_gap_ms`、`visible_frame_gap_ms_max` |
| U2 | `scripts/soak_6_5.sh` 新增 `remote_viewport_swipe_points()`，通过 `uiautomator dump` 读取 `remoteViewportContainer` bounds，dynamic swipe 优先落在远端画面控件内 | 避免固定坐标滑到 Android 外层页面或 ScrollView，把与远控无关的页面重排误算成远控卡顿 | 新 soak 的 logcat 需要出现 `RemoteDeskSoak soak_dynamic_bounds source=remoteViewportContainer`；如果是 fallback，需要先解释原因 |
| U3 | `scripts/soak_6_5.sh` 新增阶段标记 `soak_phase`，让报告区分 foreground/background/dynamic/recovery | 把后台切换污染、动态输入压力和恢复阶段卡顿分开定位 | 新 soak 报告应继续输出 `phase_frame_gap_ms_max` 和 `phase_render_fps_avg`，并能对应 Android logcat 阶段标记 |

未验证边界：U1-U3 目前只能算“待验修复候选”，不能写入“已完成并通过验证”。如果新 soak 仍 FAIL，要优先用这些新增日志判断是真实视频停帧、Android UI/ADB 压测造成的控制端卡顿，还是统计口径问题。

## 任务清单

### 已完成

- [x] 建立仓库远端并保持 ignored 产物不提交。
- [x] 完成服务端 TURN host 真机/emulator 分流测试。
- [x] 完成 Android ICE 配置增强和本地 candidate 上报链路。
- [x] 完成 relay/Mac 侧 candidate 接收链路验证。
- [x] 完成初始首帧和短断恢复首帧验证。
- [x] 完成 Android -> Mac 点击、拖拽、键盘、滚轮输入覆盖验证。
- [x] 完成短断恢复脚本质量 proof 修正，并用 `short_reconnect_20260626_105952` 验证通过。
- [x] 完成 soak 报告阶段 gap/FPS 统计能力，并在 `soak_6_5_20260626_110819` 中直接输出可见 gap 与分阶段指标。
- [x] 完成 Mac native sender 首轮调优，并用日志确认 `encoder_threads=4`、`intra_period_sec=4`、`force_intra_frames=156` 已生效。

### 待完成

- [ ] P0：围绕 `soak_6_5_20260626_110819` 的 4 个 `render_frame_gap_spike` 继续定位 Android 渲染/采样线程、UI 主线程、logcat/GC/Choreographer 和动态 swipe 压力之间的关系。
- [ ] P1：区分真实视频停帧、Android 控制端 UI 被 ADB swipe 压力阻塞、以及采样统计误判；需要用 Android 解码 FPS、render sample、Mac sender probe 三方证据同时说明。
- [ ] P2：让 720p 清晰度下的 soak 达到 `render_fps_avg>=24.0`，并把 `visible_frame_gap_ms_max` 压到 `<1000ms`。
- [ ] P3：如继续改 Mac sender，复查 `apps/desktop/src-tauri/src/native_sender/mod.rs` worker loop 的采帧、编码、写 sample 和 sleep/target interval；不能只靠调大阈值过关。
- [ ] P4：修改后复跑静态检查、Go 测试、Android build；如果改 desktop Rust/前端，还要跑 desktop build/test。
- [ ] P5：复跑真机短断，确认 `105952` 已过项目没有退化。
- [ ] P6：复跑新版真机 soak，必须 `overall=PASS`，且报告中的平均 FPS、recent 窗口、visible gap、输入 proof 同时达标。
- [ ] P7：soak 达标后做人工肉眼观察，记录静止清晰度、动态滚动/窗口移动、连续输入响应、短断恢复后继续操作。
- [ ] P8：把最新报告结果回写到本文档，不把 `.rd_runtime/` 报告文件提交。
- [ ] P9：只有短断和 soak 都通过、人工观察也记录后，再进行阶段性提交和推送；提交说明使用中文，并明确本次验证范围。

## 今日暂停记录

暂停时间：`2026-06-26 11:50:51 +0800`

当前暂停点：

- 真机 `wsvwypiz7xwslvl7` 当前在线，今天按用户要求暂停，不继续启动短断或 soak 验证。
- 已验证完成的事实仍以 `short_reconnect_20260626_105952` 和 `soak_6_5_20260626_110819` 为准：短断 PASS，最新版 soak FAIL。
- U1-U3 是针对 soak 卡顿问题的候选修复/诊断改动，已阶段性提交并推送，但还没有安装到真机复测，不能算通过。
- 下一次恢复任务时，不要直接继续提交；先构建/安装新 APK，再跑短断，短断 PASS 后跑新版 soak。
- 如果新 soak 仍 FAIL，优先看 `RemoteDeskSoak soak_dynamic_bounds`、阶段 `soak_phase`、Android `render_frame_gap_spike/render_frame_sample/net_stats` 和 Mac sender probe 的时间对齐，不要简单放宽阈值。

下次第一步：

```zsh
cd /Users/long/Documents/CodexProjects/RemoteDesk
git status --short --branch --ignored
adb devices
cd apps/android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug --console=plain
cd ../..
adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

## 下次执行顺序

1. 先确认仓库状态和 ignored 产物：

```zsh
git status --short --branch --ignored
git rev-parse --short HEAD
git rev-parse --short origin/main
git ls-files | rg '(^|/)(node_modules|dist|build|target|\.gradle|\.rd_runtime|__pycache__)(/|$)|\.(apk|aab|dmg|msi|exe|app\.tar\.gz|tar\.gz|log|pyc)$'
```

2. 先看最新 PASS/FAIL 报告，不要凭印象改：

```zsh
sed -n '1,120p' .rd_runtime/reports/short_reconnect_20260626_105952.md
sed -n '1,180p' .rd_runtime/reports/soak_6_5_20260626_110819.md
```

3. 聚焦 Android 渲染 spike、动态压力和 Mac sender 对照证据：

```zsh
rg -n 'render_frame_sample|render_frame_gap_spike|onFrame|rtcProbeSink|low_fps_streak|quality_hint|net_stats|activity_lifecycle' \
  apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt

rg -n 'dynamic_pressure_phase|input swipe|fg-sec|bg-sec|dynamic-sec|recover-sec' \
  scripts/soak_6_5.sh

rg -n 'target_loop_interval|probe|encode|write_sample|sleep|target_ms|capture_fps|build_h264_encoder|Complexity|bitrate|num_threads' \
  apps/desktop/src-tauri/src/native_sender/mod.rs \
  apps/desktop/src-tauri/src/capture/mod.rs
```

4. 修改后先跑本地检查：

```zsh
bash -n scripts/short_reconnect_check.sh
bash -n scripts/soak_6_5.sh
bash -n scripts/triad_ctl.sh
python3 -m py_compile scripts/soak_report.py
git diff --check
```

5. 按改动范围跑对应构建/测试：

```zsh
cd apps/server
go test ./...
```

```zsh
cd apps/android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug --console=plain
```

```zsh
cd apps/desktop
npm run build
cd src-tauri
cargo test
```

6. 构建通过后先安装新 APK 到真机，确认 `lastUpdateTime` 已刷新；否则后续短断/soak 仍是在验证旧包：

```zsh
adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
adb -s wsvwypiz7xwslvl7 shell dumpsys package com.remotedesk.app | rg -n "versionName|versionCode|firstInstallTime|lastUpdateTime"
```

7. 复跑真机短断，确认不退化：

```zsh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
RD_ANDROID_MODE=physical \
RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 \
RD_AGENT_DEVICE_ID=auto \
./scripts/short_reconnect_check.sh \
  --serial wsvwypiz7xwslvl7 \
  --target-device-id auto \
  --end-session
```

8. 短断 PASS 后复跑新版真机 soak：

```zsh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
RD_ANDROID_MODE=physical \
RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 \
RD_AGENT_DEVICE_ID=auto \
./scripts/soak_6_5.sh \
  --fg-sec 120 --bg-sec 60 --dynamic-sec 180 --recover-sec 60 \
  --serial wsvwypiz7xwslvl7 \
  --target-device-id auto
```

9. 新 soak 额外检查 dynamic 坐标是否打到远端画面内：

```zsh
rg -n 'RemoteDeskSoak.*soak_dynamic_bounds|RemoteDeskSoak.*soak_phase|render_frame_gap_spike|render_frame_sample|net_stats' .rd_runtime/logs/android-emulator.log
```

10. 如果 soak 仍出现 `render_fps_avg<24.0`、`render_frame_stutter`、`frames_dropped_spike`、`visible_frame_gap_ms_max>=1000` 或 recent FPS 长时间低于阈值，先不要宣称流畅完成；继续回到 P0-P3。

11. 有代码改动时，只显式 stage 目标文件，不使用 `git add -A`。

## 验证清单

### Git 与忽略规则

- [x] 阶段保存提交 `a2e6d4d 保存安卓真机远控阶段进度` 已推送到 `origin/main`
- [x] `git ls-files | rg '(^|/)(node_modules|dist|build|target|\.gradle|\.rd_runtime|__pycache__)(/|$)|\.(apk|aab|dmg|msi|exe|app\.tar\.gz|tar\.gz|log|pyc)$'` 当前无输出
- [x] `git status --short --branch --ignored` 只出现当前任务相关源码改动和已知 ignored 产物
- [x] 新报告继续落在 `.rd_runtime/reports/`，不要提交
- [ ] 提交时显式 stage 文件，例如 `git add docs/remote_control_progress_checklist.md scripts/short_reconnect_check.sh`，不要用 `git add -A`

### 静态检查

- [x] `bash -n scripts/short_reconnect_check.sh`
- [x] `bash -n scripts/soak_6_5.sh`
- [x] `bash -n scripts/triad_ctl.sh`
- [x] `python3 -m py_compile scripts/soak_report.py`
- [x] `git diff --check`
- [x] `cd apps/server && go test ./...`
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug --console=plain`
- [x] `cd apps/desktop && npm run build`
- [x] `cd apps/desktop/src-tauri && cargo test`

### ICE 与首帧验收

- [x] Android 日志出现 local ICE candidate callback
- [x] Android 日志出现 local ICE candidate send
- [x] Relay 日志出现 Android -> agent 的 `webrtc.ice_candidate` accepted 和 forwarded
- [x] Mac agent metrics 中 `native_sender_remote_candidate_count>0`
- [x] Android `ice_state` 进入 `CONNECTED`
- [x] 初始会话出现 `first_rendered_frame`
- [x] 恢复会话出现 `first_rendered_frame`
- [x] Relay 合并指标 `session_e2e_proof_status=video_and_input_observed`
- [ ] 下一轮短断复跑仍满足以上全部项目，且没有退回 `local_ice_candidate_sent=0` 或 `input_only`

### 短断恢复验收

- [x] `overall=PASS`
- [x] 新会话 ID 不等于旧会话 ID
- [x] Android 日志观察到 `session_recovery_intent`
- [x] Android 自动请求原目标设备
- [x] 新会话首帧发生在 relay 恢复之后，不误用旧会话首帧
- [x] `session_e2e_proof_status=video_and_input_observed`
- [x] `remote_input_applied=11/11`
- [x] `remote_input_failed=0`
- [x] `remote_input_coverage=click,drag,keyboard,wheel`
- [x] 恢复后最近质量窗口 `quality_proof_passed=True`
- [x] 最近质量窗口至少 `5` 个 render samples
- [x] 最近质量窗口 dropped-frame 增量 `<30`
- [x] `quality_candidate_tiers=relay_udp`
- [ ] 下一轮修复 sender 后复跑短断仍为 PASS

### Soak 验收

- [ ] 新 soak 使用的是已安装后的新 APK，不能复用 `105952` 或 `110819` 作为 U1-U3 的验收报告
- [ ] logcat 出现 `RemoteDeskSoak soak_dynamic_bounds source=remoteViewportContainer`；如果只出现 fallback，要记录 fallback 原因
- [ ] logcat 出现 foreground/background/dynamic/recovery 的 `RemoteDeskSoak soak_phase`
- [ ] 最新代码跑出的 `overall=PASS`
- [ ] `render_fps_avg>=24.0`
- [x] `session_quality_hint_recent=stable`
- [x] `render_fps_recent>=23.5`，当前 `110819` 为 `24.563274579507244`
- [x] `render_recent_max_frame_gap_ms<1000`，当前 `110819` 为 `46.0`
- [ ] `visible_frame_gap_ms_max<1000`，当前 `110819` 为 `3692`
- [ ] 分阶段 `phase_frame_gap_ms_max` 全部 `<1000`，当前 dynamic `3692`、recovery `1739` 未通过
- [x] `longest_low_fps_streak_sec<30.0`
- [x] `max_frames_dropped_spike<30`
- [x] `frames_dropped_delta_recent<30`
- [x] `remote_input_applied=11/11`
- [x] `remote_input_coverage=click,drag,keyboard,wheel`
- [x] `candidate_tier_last=relay_udp`
- [x] `rtt_ms_avg` 没有长时间高 RTT 异常，当前 `2.44`
- [ ] 如果 `relay.session_quality_hint=render_frame_stutter` 或 `frames_dropped_spike`，只能记为“基础项通过但流畅性未最终通过”

### 人工观察验收

- [ ] Android 真机观看 Mac 桌面文字和 UI，静止画面清晰
- [ ] Mac 端滚动页面或播放动态内容时，Android 端肉眼无明显 1s 卡顿
- [ ] Android 端连续执行点击、拖拽、键盘、滚轮，Mac 端响应符合预期
- [ ] 短断恢复后继续操作，画面和输入都能恢复
- [ ] 人工观察结论需要写入本文件或新的报告，不只口头说“看起来可以”

### 提交和推送验收

- [x] 短断 PASS 报告路径写入本文档：`.rd_runtime/reports/short_reconnect_20260626_105952.md`
- [x] 最新 Soak FAIL 报告路径写入本文档：`.rd_runtime/reports/soak_6_5_20260626_110819.md`
- [ ] Soak PASS 报告路径写入本文档
- [ ] 人工观察记录写入本文档或单独报告
- [x] `git diff --check` 通过
- [x] 显式 stage 当前任务文件，不提交 `.rd_runtime/` 和构建产物
- [x] 中文 commit message 说明真实范围，不把未完成项写成已完成：`保存安卓真机远控阶段进度`
- [x] `git push origin main` 已完成，阶段保存 commit：`a2e6d4d`

## 关键文件

- Android 恢复与渲染质量：`apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
- Android session 消息构造：`apps/android/app/src/main/java/com/remotedesk/app/controller/StubSessionController.kt`
- Android 权限：`apps/android/app/src/main/AndroidManifest.xml`
- Relay 会话质量聚合：`apps/server/internal/transport/ws.go`
- Session/TURN host 组装：`apps/server/internal/session/stub.go`
- Mac native sender：`apps/desktop/src-tauri/src/native_sender/mod.rs`
- Mac capture 默认参数：`apps/desktop/src-tauri/src/capture/mod.rs`
- 短断恢复验收脚本：`scripts/short_reconnect_check.sh`
- Soak 脚本：`scripts/soak_6_5.sh`
- Soak 报告生成：`scripts/soak_report.py`
- 三端启动与 logcat 采集：`scripts/triad_ctl.sh`

## 结论边界

截至本记录时间，可以实事求是地说：Android 真机 -> Mac 的 ICE candidate 链路、初始首帧、短断恢复首帧、输入控制覆盖、短断恢复短窗口质量、Mac native sender 首轮调优生效和工程检查都有本地证据支撑。最新短断报告 `short_reconnect_20260626_105952` 已经是 `PASS`。

还不能说：整条链路已经满足“手机端稳定看到并流畅控制 Mac”的最终目标。最新新版 soak `soak_6_5_20260626_110819` 仍是 `FAIL`，核心失败点是 `render_fps_avg=23.52 < 24.0` 和 `visible_frame_gap_ms_max=3692`；dynamic 与 recovery 阶段都出现了 1s 级可见 gap。下次应优先定位 Android 渲染/采样与动态压力阶段的 spike，再复跑短断和 soak，最后补人工肉眼观察记录。
