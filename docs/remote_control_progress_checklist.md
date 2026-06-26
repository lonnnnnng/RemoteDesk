# Android 真机远控 Mac 进度清单

Last updated: `2026-06-26 08:49:00 +08:00`

本文档记录当前“Android 真机控制 Mac”路线的已完成事项、已验证证据、未完成事项和下次继续执行的验证清单。这里的结论只按仓库代码、脚本和本地报告证据记录；`.rd_runtime/` 下的日志与报告是运行产物，继续保持 ignored，不提交到仓库。

## 当前目标

- Android 真机可以看到 Mac 屏幕。
- Android 真机可以控制 Mac 鼠标和键盘。
- 短时间掉线后自动恢复，不需要用户手动重新发起。
- 画质清晰、播放流畅，肉眼无明显卡顿。
- 每个功能点都能用脚本或日志证明，发现 bug 后修正再复测。

## 已完成并通过验证

| ID | 工作项 | 状态 | 当前证据 | 备注 |
| --- | --- | --- | --- | --- |
| D1 | 仓库远端与忽略规则 | 已完成 | `origin=https://github.com/lonnnnnng/RemoteDesk.git`；`HEAD=origin/main=c0e01d6f1a507769711b3a2b9e71990c11901ea2`；`.gitignore` 覆盖 `.rd_runtime/`、`node_modules/`、`dist/`、`build/`、`target/`、`.gradle/`、release 包 | 当前只有 ignored 运行/构建产物，没有待提交源码 |
| D2 | Android -> Mac 会话首帧与视频 proof | 已验证 | `.rd_runtime/reports/short_reconnect_20260626_075525.md`：恢复后新会话首帧 `1114x720`；`.rd_runtime/reports/short_reconnect_20260626_080929.md`：恢复后新会话首帧 `1114x720` | 报告文件不提交，只记录路径和关键指标 |
| D3 | Android -> Mac 输入控制覆盖 | 已验证 | 两次短断恢复报告和一次 soak 报告均为 `remote_input_applied=11/11`、`remote_input_failed=0`、覆盖 `click,drag,keyboard,wheel`；soak 报告 executor 为 `macos.cg_event` | 已覆盖点击、拖拽、键盘、滚轮，不只是 UI 发包 |
| D4 | 短时间 relay 断开后自动恢复 | 已验证 | `.rd_runtime/reports/short_reconnect_20260626_075525.md`：断开 `4s`，旧会话 `sess-1782431705253-1`，新会话 `sess-1782431717653-1`，恢复 `12719ms`；`.rd_runtime/reports/short_reconnect_20260626_080929.md`：断开 `4s`，旧会话 `sess-1782432538735-1`，新会话 `sess-1782432552076-1`，恢复 `12699ms` | 已确认新会话不同于旧会话，并观察到 `session_recovery_intent` 与自动请求原目标 |
| D5 | 短断恢复后的质量窗口验收 | 已验证 | `.rd_runtime/reports/short_reconnect_20260626_080929.md`：`quality_proof_passed=True`，最近 `5` 个样本 `quality_fps_avg_recent=24.48`、`quality_fps_min_recent=24.25`、`quality_drop_delta_recent=0`、`quality_candidate_tiers=p2p_udp` | 这个质量窗口只看恢复后新首帧之后的样本，避免 relay 断开期间的累计 gap 污染判断 |
| D6 | 较长真机 soak 基线 | 部分通过 | `.rd_runtime/reports/soak_6_5_20260626_082105.md`：`overall=PASS`，`render_fps_avg=24.13`，`rtt_ms_avg=11.05`，`candidate_tier_last=p2p_udp`，`remote_input_applied=11/11`，`session_e2e_proof_status=video_and_input_observed` | 不能把它视为最终流畅验收完成，因为同一报告里 `relay.session_quality_hint=render_frame_stutter` |
| D7 | 自动化脚本入口 | 已完成 | `scripts/short_reconnect_check.sh`、`scripts/soak_6_5.sh`、`scripts/soak_report.py` | 下次继续开发优先复用这些脚本，不要手工拼临时流程 |

## 未完成和待完成

| ID | 待办 | 优先级 | 当前事实 | 下一步动作 |
| --- | --- | --- | --- | --- |
| P1 | 定位 Android 侧 1s 级帧间隔 spike | 高 | soak 报告整体 PASS，但日志显示曾出现约 `1044ms` 帧间隔 spike，relay 汇总为 `render_frame_stutter` | 重点查 `MainActivity.kt` 的 `rtcProbeSink`、前后台恢复、renderer attach、UI 主线程更新和 stats 采样 |
| P2 | 区分“阶段切换污染”和“可见播放卡顿” | 高 | 当前 `inferRtcQualityHintCode` / relay 质量判断会把整场最大 gap 计入最终 hint；阶段切换期间的 gap 可能和正常播放期间的可见卡顿混在一起 | 增加最近窗口/阶段窗口质量字段；保留真实 gap，不用简单隐藏问题 |
| P3 | 最终流畅验收 | 高 | 平均 FPS 和短断恢复窗口达标，但已有 1s 级 spike 证据；“肉眼无明显卡顿”还没有闭环 | 修复 P1/P2 后跑至少 2 轮 10-15 分钟真机 soak，再做人工动态场景观察 |
| P4 | 更长后台/弱网稳定性 | 中 | 当前较长样本约 7 分钟，尚未覆盖 30-50 分钟后台、弱网、网络抖动等场景 | 扩展 `soak_6_5.sh` 参数或新增专项脚本，记录前台、后台、动态压力、恢复前台分段指标 |
| P5 | Android -> Windows 路线复测 | 中 | README 和旧开发备注保留过 Android -> Windows 历史问题：`render_fps_avg=2.55`、`frame_stalled`；本清单主要记录 Android 真机 -> Mac 的最新证据 | Mac 路线稳定后，再用 Windows agent 复跑同一套输入、短断和 soak 验收 |
| P6 | 产品化 UI 和错误恢复提示 | 中 | 主链路可测，但 UI/交互仍偏联调工具形态 | 在稳定性达标后再打磨连接状态、错误提示、权限提示和用户可理解的恢复状态 |
| P7 | 验收口径文档化 | 中 | 当前阈值散落在 Android、relay 和脚本中，已有口径但还缺最终规格文档 | 把首帧、FPS、丢帧、RTT、candidate tier、输入 proof、质量 hint 的通过/失败规则整理到单独质量规格 |

## 下次执行顺序

1. 先确认仓库状态：

```zsh
git status --short --branch --ignored
git rev-parse HEAD
git rev-parse origin/main
```

2. 先跑短断恢复，确认基础链路没有退化：

```zsh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
RD_ANDROID_MODE=physical \
RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 \
RD_AGENT_DEVICE_ID=auto \
./scripts/short_reconnect_check.sh \
  --serial wsvwypiz7xwslvl7 \
  --target-device-id auto
```

3. 再跑真机 soak，观察是否还出现 `render_frame_stutter`：

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

4. 如果 soak 仍出现 spike，先不要宣称流畅完成；按 P1/P2 修代码后再复测。

5. 有代码改动时，只显式 stage 目标文件，不使用 `git add -A`。

## 验证清单

### Git 与忽略规则

- [ ] `git status --short --branch --ignored` 只允许出现已知 ignored 产物。
- [ ] `git ls-files | rg '(^|/)(node_modules|dist|build|target|\\.gradle|\\.rd_runtime|__pycache__)(/|$)|\\.(apk|aab|dmg|msi|exe|app\\.tar\\.gz|tar\\.gz|log|pyc)$'` 无输出。
- [ ] 新报告继续落在 `.rd_runtime/reports/`，不要提交。

### 静态检查

- [ ] `bash -n scripts/short_reconnect_check.sh`
- [ ] `bash -n scripts/soak_6_5.sh`
- [ ] `git diff --check`
- [ ] 服务端改动后运行：

```zsh
cd apps/server
go test ./...
```

- [ ] Android 改动后运行：

```zsh
cd apps/android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug --console=plain
```

- [ ] Desktop 改动后运行：

```zsh
cd apps/desktop
npm run build
cd src-tauri
cargo test
```

### 短断恢复验收

- [ ] `overall=PASS`
- [ ] 新会话 ID 不等于旧会话 ID。
- [ ] Android 日志观察到 `session_recovery_intent`。
- [ ] Android 自动请求原目标设备。
- [ ] 新会话首帧发生在 relay 恢复之后，不允许误用旧会话首帧。
- [ ] `session_e2e_proof_status=video_and_input_observed`
- [ ] `remote_input_applied=11/11`
- [ ] `remote_input_failed=0`
- [ ] `remote_input_coverage=click,drag,keyboard,wheel`
- [ ] 恢复后最近质量窗口 `quality_proof_passed=True`
- [ ] 最近质量窗口 FPS 平均值 `>=23.5`
- [ ] 最近质量窗口 dropped-frame 增量 `<30`

### Soak 验收

- [ ] `overall=PASS`
- [ ] `render_fps_avg>=24.0`
- [ ] `longest_low_fps_streak_sec<30.0`
- [ ] `max_frames_dropped_spike<30`
- [ ] `remote_input_applied=11/11`
- [ ] `remote_input_coverage=click,drag,keyboard,wheel`
- [ ] `candidate_tier_last` 优先为 `p2p_udp`
- [ ] `rtt_ms_avg` 没有长时间高 RTT 异常。
- [ ] 如果 `relay.session_quality_hint=render_frame_stutter`，只能记为“soak 基础项通过但流畅性未最终通过”。

### 人工观察验收

- [ ] Android 真机观看 Mac 桌面文字和 UI，静止画面清晰。
- [ ] Mac 端滚动页面或播放动态内容时，Android 端肉眼无明显 1s 卡顿。
- [ ] Android 端连续执行点击、拖拽、键盘、滚轮，Mac 端响应符合预期。
- [ ] 短断恢复后继续操作，画面和输入都能恢复。

## 关键文件

- Android 恢复与渲染质量：`apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
- Relay 会话质量聚合：`apps/server/internal/transport/ws.go`
- Mac native sender：`apps/desktop/src-tauri/src/native_sender/mod.rs`
- 短断恢复验收脚本：`scripts/short_reconnect_check.sh`
- Soak 脚本：`scripts/soak_6_5.sh`
- Soak 报告生成：`scripts/soak_report.py`

## 结论边界

截至本记录时间，可以实事求是地说：Android 真机 -> Mac 的视频首帧、输入控制、4 秒短断自动恢复、恢复后短窗口质量和约 7 分钟 soak 基础指标已经有本地证据支撑。

还不能说：整条链路已经满足“肉眼无明显卡顿”的最终目标。原因是长一点的真机 soak 仍留下 `render_frame_stutter` 和约 1s 帧间隔 spike 线索，必须继续定位和复测。
