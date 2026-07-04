# Android 真机远控 Mac 任务与验证清单

Last updated: `2026-07-04 16:50:53 +0800`

本文档记录当前“Android 真机控制 Mac”路线的已完成事项、已验证证据、未完成事项和下次继续执行的任务/验证清单。结论只按仓库代码、脚本、本地日志和报告证据记录；`.rd_runtime/` 下的日志与报告是运行产物，继续保持 ignored，不提交到仓库。

## 当前快照

- 当前分支：`main`
- 当前远端：`origin=https://github.com/lonnnnnng/RemoteDesk.git`
- 上一已验证基线：`8836c5d 记录远控进度与验证清单`
- 历史阶段保存提交：`a2e6d4d 保存安卓真机远控阶段进度` 已推送到 `origin/main`
- 最新已拉取远端提交：`e95ff47 重构双端界面并修复安卓远程会话`
- `e95ff47` 之后已用 Android 模拟器回归 Android -> Mac 路线，报告为 `.rd_runtime/reports/short_reconnect_20260701_004122.md`
- 当前 ignored 产物仍未纳入 Git：`.rd_runtime/`、`apps/android/.gradle/`、`apps/android/app/build/`、`apps/android/build/`、`apps/desktop/dist/`、`apps/desktop/node_modules/`、`apps/desktop/src-tauri/gen/`、`apps/desktop/src-tauri/target/`、`apps/server/.rd_runtime/`、`scripts/__pycache__/`
- 当前 Android 最新本地改动：应用中文名改为 `远控`，新增自定义 adaptive icon，为 Redmi Note 8 Pro / begonia / MTK Android 14 增加 H.264 硬解零帧兜底，并把远程画面的 `触控/移动/放大` 三模式按钮收敛为画面内手势操作。
- 最新真机短断验证已通过：`.rd_runtime/reports/short_reconnect_20260704_165039.md`
- 最新 Android 模拟器 -> Mac 短断/UI 回归验证已通过：`.rd_runtime/reports/short_reconnect_20260701_004122.md`
- 最新真机 quick soak 仍未通过：`.rd_runtime/reports/soak_6_5_20260704_154803.md`，失败点是 `render_fps_avg=23.98 < 24.0`；本轮 `visible_frame_gap_ms_max=87`，动态/恢复阶段最大 gap 为 `83/87ms`，但后台阶段仍记录 `3371ms` gap，且完整长时 soak 未跑。
- 上一轮 soak `103151` 仍保留为历史对照：原报告 `visible_frame_gap_ms_max=0`、`phase_frame_gap_ms_max=-` 有统计不足，临时重算 `/tmp/rd_soak_report_103151_recalc.md` 得到可见最大帧间隔 `861ms`
- 当前不能宣称最终目标完成：真机短断、模拟器 UI 回归、首帧、输入和短窗口质量已经有证据支撑；新版 quick soak 已显著改善可见阶段 gap，但平均 FPS 仍低于脚本硬阈值，完整长时 soak、真机人工肉眼流畅验收，以及双指捏合局部放大的人工/有效多点注入验收还没有通过记录。

## 当前目标

- Android 真机可以稳定看到 Mac 屏幕。
- Android 真机可以控制 Mac 鼠标和键盘。
- 短时间掉线后自动恢复，不需要用户手动重新发起。
- 画质清晰、播放流畅，肉眼无明显卡顿。
- 每个功能点都能用脚本或日志证明，发现 bug 后修正再复测。

## 已完成并通过验证

| ID | 工作项 | 状态 | 当前证据 | 备注 |
| --- | --- | --- | --- | --- |
| D1 | 仓库远端与忽略规则 | 已完成 | `origin=https://github.com/lonnnnnng/RemoteDesk.git`；当前拉取基线 `e95ff47`；`git ls-files` 检查未发现 tracked 的 `.rd_runtime/`、`node_modules/`、`dist/`、`build/`、`target/`、`.gradle/`、`__pycache__` 或常见包产物 | 运行日志、报告和构建产物保持 ignored，不提交 |
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
| D12 | UI 重构后的 Android 模拟器 -> Mac 回归 | 已验证 | `.rd_runtime/reports/short_reconnect_20260701_004122.md`：`overall=PASS`，旧会话 `sess-1782837652857-2`，新会话 `sess-1782837667499-1`，relay 断开 `4s`，恢复 `14870ms`，新首帧 `742x480`，`remote_input_applied=11/11`，覆盖 `click,drag,keyboard,wheel`，`quality_fps_avg_recent=24.2`，`quality_drop_delta_recent=0`，`quality_candidate_tiers=p2p_udp` | 用 `Pixel_9` 模拟器验证 UI 调整没有让 Android -> Mac 首帧、短断恢复、输入 proof 和短窗口质量回归；不能替代真机 soak 和人工肉眼验收 |
| D13 | Android 品牌改名与图标 | 已验证 | `aapt dump badging apps/android/app/build/outputs/apk/debug/app-debug.apk` 输出 `application-label:'远控'`，Manifest 图标为 `res/mipmap-anydpi-v26/ic_launcher.xml`；真机 UI dump 顶部标题为 `远控`，右侧 badge 为 `控`；截图 `/tmp/remotedesk-yuankong-app.png` 无明显布局错位 | 这是本地 debug APK 验证，发布资产仍需下一次发版重新打包 |
| D14 | Redmi Note 8 Pro MTK H.264 零帧兜底 | 已验证 | `.rd_runtime/logs/android-emulator.log` 出现 `decoder_hardware_blocked name=OMX.MTK.VIDEO.DECODER.AVC reason=mtk_avc_zero_frame_workaround model=Redmi Note 8 Pro device=begonia sdk=34`，随后 `first_rendered_frame size=1114x720`，`frames_decoded` 增长到 `510+`；短断报告 `short_reconnect_20260704_154256` 为 PASS | 旧的真机 `frames_decoded=0` / SurfaceViewRenderer 收不到帧问题在本机 Redmi Note 8 Pro 上已回归通过 |
| D15 | 远程画面手势化控制 UI | 已验证 | `activity_main.xml` 已无 `触控`、`移动`、`放大` 三个模式按钮；截图 `/tmp/remotedesk-gesture-before.png`、`/tmp/remotedesk-pinch2-out.png` 显示远程画面只保留倍率重置和全屏按钮；Android debug 构建和真机安装通过，`lastUpdateTime=2026-07-04 16:46:05` | 远程画面交互入口已从模式按钮改为画面内手势；倍率按钮现在会在缩放生效时显示当前倍率 |
| D16 | 远程画面单指移动、长按拖拽和键盘快捷键 | 已验证 | 最终 APK 真机复测：单指滑动 Mac 端 `input.mouse.move applied=true` 7 条且无 `input.mouse.button`；长按拖拽 Mac 端 `input.mouse.move applied=true` 1 条、`input.mouse.button applied=true` 2 条；点击 `⌘C` 后 Android 发送 `KeyC down/up`，回执 `modifiers=MetaLeft [applied/macos.cg_event]`，Mac 端 keyboard applied 2 条 | 普通滑动不再误按鼠标；拖拽由长按触发；键盘映射保留为快捷键栏 |
| D17 | 手势化改动后的真机短断回归 | 已验证 | `.rd_runtime/reports/short_reconnect_20260704_165039.md`：`overall=PASS`，旧会话 `sess-1783155008640-1`，新会话 `sess-1783155024512-1`，恢复 `15879ms`，新首帧 `1114x720`，`remote_input_applied=11/11`，覆盖 `click,drag,keyboard,wheel`，`quality_proof_passed=True` | 证明本轮 Android UI/手势改动没有破坏首帧、短断恢复和标准输入 proof |

## 最新未通过和未闭环

| ID | 验证项 | 结果 | 已观察事实 | 结论 |
| --- | --- | --- | --- | --- |
| F1 | 新版真机 quick soak 整体结果 | 未通过 | `.rd_runtime/reports/soak_6_5_20260704_154803.md`：`overall=FAIL` | 当前代码仍不能提交为“流畅性完成”，但失败点已从秒级可见 gap 收敛到平均 FPS 硬阈值边界 |
| F2 | Soak 平均渲染 FPS | 未通过 | `render_fps_avg=23.98 < 24.0`，历史 `110819` 为 `23.52 < 24.0` | 只差 `0.02fps` 仍按未通过处理；下一步要确认是采样/四舍五入口径、Mac sender 目标节奏，还是 Android 渲染侧真实掉帧 |
| F3 | Soak 可见帧间隔统计 | 部分通过 | 最新 quick soak：`visible_frame_gap_ms_max=87`，阶段最大 gap：foreground `79`、dynamic `83`、recovery `87`、ending `67`；background 阶段记录 `3371` | 可见前台/动态/恢复阶段已低于 `<1000ms`，但后台阶段 spike 仍需解释，且完整长时 soak 未覆盖 |
| F4 | Soak 分阶段 FPS | 未达标 | 最新 quick soak：`phase_render_fps_avg={'background': 23.32, 'dynamic': 23.83, 'ending': 23.88, 'foreground': 23.83, 'recovery': 23.83}` | 各阶段仍贴着 24fps 下沿，不能因为肉眼 gap 改善就关闭流畅性目标 |
| F5 | Android 渲染/主线程或动态压力影响 | 未闭环 | 最新 quick soak 的 Mac sender probe 多数在 `23.8-24.1fps`，Android `render_frame_gap_spike` 只在后台阶段出现 `3371ms`；dynamic 坐标日志为 `RemoteDeskSoak soak_dynamic_bounds source=screen_fallback`，没有命中 `remoteViewportContainer` | 下一步优先解释后台 spike 和 bounds fallback，再决定是修脚本定位、调采样口径，还是继续优化 sender/Android 渲染 |
| F6 | 人工肉眼流畅验收 | 未完成 | 目前只有脚本、日志和报告证据，没有本轮修复后的人工动态场景观察记录 | “肉眼无明显卡顿”必须等 soak 达标后再人工确认 |
| F7 | 长时间后台/弱网稳定性 | 未完成 | 当前主要是短断和约数分钟 soak，未覆盖 30-50 分钟后台、弱网、网络抖动 | 这是最终产品化前的后续验证，不阻塞当前 P0/P1 修复，但不能忘 |
| F8 | Android -> Windows 路线 | 未复测 | README/旧记录保留过 Windows 路线历史问题；本清单只记录 Android 真机 -> Mac 最新证据 | Mac 路线稳定后再用同一套脚本复测 Windows agent |
| F9 | UI 回归验证的覆盖边界 | 未闭环 | `20260701_004122` 是 Android 模拟器 -> Mac 短断回归 PASS，不是真机，也不是新版长时间 soak | 可作为 UI 调整未破坏核心链路的证据，但不能关闭真机稳定/流畅性目标 |
| F10 | 双指捏合局部放大实机验证 | 未闭环 | 代码已接入 `ScaleGestureDetector`，并在缩放生效时更新倍率按钮；但本轮自动注入未成功形成有效 pinch：`uiautomator runtest` 返回 `OK (1 test)` 但没有出现 `remote_viewport_pinch_scale` 日志、截图仍为 `1x`；底层 `sendevent /dev/input/event2` 被系统拒绝 `Permission denied` | 不能把局部放大写成已通过验证；下一步需要人工两指捏合观察，或用 instrumentation/测试 APK 产生真实多点事件 |

## 已改并已复测但未完全闭环

这些项目已经安装新 APK 到 `wsvwypiz7xwslvl7` 并跑过 2026-07-04 真机短断和 quick soak。短断 PASS，quick soak 仍 FAIL，所以不能把它们整体记为“最终流畅性完成”。

| ID | 改动 | 预期解决的问题 | 下一步验证 |
| --- | --- | --- | --- |
| U1 | `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt` 将 RTC 指标面板刷新从 `250ms` 降到 `1000ms`，拖拽输入节流从约 `16ms/0.0025` 调到 `33ms/0.004`，并减少连续拖拽 mouse down/up 的 UI 成功日志 | 降低动态控制阶段 Android 主线程和日志/UI 刷新的压力，避免控制端自己压住 Surface 渲染 | `soak_6_5_20260704_154803` 中 dynamic/recovery 可见 gap 降到 `83/87ms`，但 `render_fps_avg=23.98 < 24.0` 仍 FAIL |
| U2 | `scripts/soak_6_5.sh` 新增 `remote_viewport_swipe_points()`，通过 `uiautomator dump` 读取 `remoteViewportContainer` bounds，dynamic swipe 优先落在远端画面控件内 | 避免固定坐标滑到 Android 外层页面或 ScrollView，把与远控无关的页面重排误算成远控卡顿 | 最新 logcat 只出现 `RemoteDeskSoak soak_dynamic_bounds source=screen_fallback`，未命中 `remoteViewportContainer`；需要继续查 bounds 获取失败原因 |
| U3 | `scripts/soak_6_5.sh` 新增阶段标记 `soak_phase`，让报告区分 foreground/background/dynamic/recovery | 把后台切换污染、动态输入压力和恢复阶段卡顿分开定位 | 最新 quick soak 已输出阶段指标，明确 `3371ms` spike 在 background，dynamic/recovery 没有 1s 级 gap |
| U4 | Android 应用名改为 `远控`，并新增自定义 adaptive icon | 提升真机安装后的中文识别和品牌观感 | `aapt dump badging`、Manifest icon 指向、真机 UI dump 和截图均已验证 |
| U5 | Android 解码工厂屏蔽 Redmi Note 8 Pro/begonia/mt6785 Android 14 上的 `OMX.MTK.VIDEO.DECODER.AVC` | 规避该机型 MTK H.264 硬解建链成功但不吐帧，保留平台软件解码兜底 | 最新短断 PASS，日志出现 `decoder_hardware_blocked` 后仍有首帧和 `frames_decoded` 增长 |
| U6 | 远程画面 `触控/移动/放大` 三模式按钮改为手势化操作：单点点击、单指移动指针、长按拖拽、双指滚轮/捏合缩放 | 让远程画面本身承担控制能力，不再要求用户先切换三颗模式按钮 | 单指移动、长按拖拽、键盘快捷键和短断回归已验证；双指滚轮由短断 proof 覆盖；双指捏合缩放代码已落地但还缺有效真机多点实测 |

未验证边界：U1-U6 已经完成短断回归，但 quick soak 仍未达 `overall=PASS`，所以不能写入“最终流畅性完成”。如果下一轮 soak 仍 FAIL，要优先用阶段日志判断是真实视频停帧、Android UI/ADB 压测造成的控制端卡顿，还是统计口径问题。

## 2026-07-01 UI 回归验证记录

验证目的：检查 `e95ff47 重构双端界面并修复安卓远程会话` 之后，Android 控制 Mac 的核心链路是否因为 UI 调整回归。

验证环境：

- Mac agent：`agent-19de3117874`
- Android 控制端：`Pixel_9` 模拟器，serial `emulator-5554`
- Relay：`127.0.0.1:18081`
- TURN：`3478`
- 报告：`.rd_runtime/reports/short_reconnect_20260701_004122.md`
- 截图：`.rd_runtime/screenshots/android_mac_regression_20260701_004122.png`

验证结果：

- [x] 三端启动：relay、TURN、Mac Tauri dev、Android 模拟器均启动，Mac agent 和 Android controller 均进入 `/devices`
- [x] 初始首帧：`sess-1782837652857-2` 渲染 `742x480`
- [x] 短断自动恢复：relay 断开 `4s` 后恢复到新会话 `sess-1782837667499-1`
- [x] 恢复首帧：新会话首帧 `742x480`，`since_track_ms=4072`
- [x] Android -> Mac 输入 proof：`remote_input_applied=11/11`，覆盖 `click,drag,keyboard,wheel`
- [x] Mac 端输入落地：最后输入执行器为 `macos.cg_event`
- [x] 恢复后短窗口质量：`quality_fps_avg_recent=24.2`，`quality_fps_min_recent=24.01`，`quality_drop_delta_recent=0`，`quality_candidate_tiers=p2p_udp`
- [x] 可视 UI：截图显示新“远程会话”页能显示 Mac 画面和输入回执 `input.wheel.scroll [applied/macos.cg_event]`

环境问题与处理：

- 默认 AVD `Pixel_6_API_29` 不存在，本机实际可用 AVD 为 `Pixel_9` 和 `CASKA_1024x600_API27`；本轮改用 `RD_AVD_NAME=Pixel_9`
- 真机和模拟器同时在线时，短断脚本需要显式 `--serial emulator-5554`，否则 `adb` 会报 `more than one device/emulator`
- Mac Tauri debug 构建缓存中 build script 缺执行位，清理 ignored 的 `apps/desktop/src-tauri/target/` 下对应 debug 缓存后恢复；这不是源码改动
- `uiautomator dump` 返回 `ERROR: could not get idle state.`，本轮使用截图和脚本日志作为 UI/链路证据

结论边界：本轮可以说明 UI 重构后 Android 模拟器 -> Mac 的核心短断链路没有回归；仍不能说明 Android 真机 -> Mac 的长时间流畅性已经达标。

## 2026-07-04 真机品牌与 Mac 回归验证记录

验证目的：检查 Android 应用改名为 `远控`、更换图标、增加 Redmi Note 8 Pro MTK H.264 解码兜底后，真机 Android -> Mac 是否恢复首帧、短断和输入链路。

验证环境：

- Mac agent：`agent-19de3117874`
- Android 控制端：Redmi Note 8 Pro，serial `wsvwypiz7xwslvl7`，device `begonia`，Android 14
- Relay：`127.0.0.1:18081`
- TURN：`3478`
- 短断报告：`.rd_runtime/reports/short_reconnect_20260704_154256.md`
- Quick soak 报告：`.rd_runtime/reports/soak_6_5_20260704_154803.md`
- 应用内截图：`/tmp/remotedesk-yuankong-app.png`

验证结果：

- [x] Android debug 构建通过：`cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`
- [x] 真机安装成功：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`，`lastUpdateTime=2026-07-04 15:40:26`
- [x] APK 中文名：`application-label:'远控'`
- [x] APK 图标：`application icon='res/mipmap-anydpi-v26/ic_launcher.xml'`
- [x] 真机页面标题：UI dump 显示顶部 `远控`，右侧 badge 为 `控`
- [x] 真机短断：`overall=PASS`，旧会话 `sess-1783150945066-1`，新会话 `sess-1783150961069-1`，恢复 `15814ms`
- [x] 初始首帧：`1114x720`，`since_track_ms=4478`
- [x] 恢复首帧：`1114x720`，`since_track_ms=4513`
- [x] Android -> Mac 输入 proof：`remote_input_applied=11/11`，覆盖 `click,drag,keyboard,wheel`
- [x] 恢复后短窗口质量：`quality_proof_passed=True`，`quality_fps_avg_recent=23.83`，`quality_drop_delta_recent=0`，`quality_candidate_tiers=p2p_udp`
- [x] MTK AVC 兜底命中：Android 日志出现 `decoder_hardware_blocked name=OMX.MTK.VIDEO.DECODER.AVC reason=mtk_avc_zero_frame_workaround`
- [ ] Quick soak 整体未过：`overall=FAIL`，`render_fps_avg=23.98 < 24.0`
- [x] Quick soak 可见阶段 gap 改善：`visible_frame_gap_ms_max=87`，dynamic/recovery 阶段最大 gap `83/87ms`
- [x] Quick soak 输入 proof：`remote_input_applied=11/11`，覆盖 `click,drag,keyboard,wheel`
- [ ] Quick soak bounds 仍需修：logcat 为 `RemoteDeskSoak soak_dynamic_bounds source=screen_fallback`，未命中 `remoteViewportContainer`

结论边界：本轮可以说明 `远控` 品牌改动和自定义图标已经落地，且 Redmi Note 8 Pro 上 Android -> Mac 的首帧、短断恢复、输入 proof 和短窗口质量没有回归；旧的 MTK H.264 硬解零帧问题已有针对性兜底并通过短断回归。仍不能说最终流畅性完成，因为 quick soak 仍是 `FAIL`，完整长时 soak 和人工肉眼流畅验收还没有通过记录。

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
- [x] 完成 `e95ff47` UI 重构后的 Android 模拟器 -> Mac 短断回归验证，报告 `short_reconnect_20260701_004122` 为 PASS。
- [x] 完成 Android app 中文名改为 `远控`，并新增自定义 adaptive icon。
- [x] 完成 Redmi Note 8 Pro / begonia / MTK Android 14 的 H.264 硬解零帧兜底，并用真机短断 `short_reconnect_20260704_154256` 验证通过。
- [x] 完成 2026-07-04 真机 quick soak 复测，报告 `soak_6_5_20260704_154803` 生成；结论为 FAIL，不能关闭流畅性目标。
- [x] 完成远程画面 `触控/移动/放大` 三模式按钮移除，改为画面内手势：点击、单指移动、长按拖拽、双指滚轮/捏合缩放。
- [x] 完成最终 APK 的单指移动、长按拖拽、`⌘C` 键盘映射真机 -> Mac 实测，并复跑真机短断 `short_reconnect_20260704_165039`，结论为 PASS。

### 待完成

- [ ] P0：围绕 `soak_6_5_20260704_154803` 的 `render_fps_avg=23.98 < 24.0` 和 background `3371ms` gap，继续定位 Android 渲染/采样线程、UI 主线程、logcat/GC/Choreographer 和后台切换影响之间的关系。
- [ ] P1：区分真实视频停帧、Android 控制端 UI 被 ADB swipe 压力阻塞、以及采样统计误判；需要用 Android 解码 FPS、render sample、Mac sender probe 三方证据同时说明。
- [ ] P2：让 720p 清晰度下的 soak 达到 `render_fps_avg>=24.0`；可见阶段 gap 继续保持 `<1000ms`。
- [ ] P3：如继续改 Mac sender，复查 `apps/desktop/src-tauri/src/native_sender/mod.rs` worker loop 的采帧、编码、写 sample 和 sleep/target interval；不能只靠调大阈值过关。
- [ ] P4：修改后复跑静态检查、Go 测试、Android build；如果改 desktop Rust/前端，还要跑 desktop build/test。
- [ ] P5：下一轮代码改动后复跑真机短断，确认 `154256` 已过项目没有退化。
- [ ] P6：复跑新版真机 soak，必须 `overall=PASS`，且报告中的平均 FPS、recent 窗口、visible gap、输入 proof 同时达标。
- [ ] P7：soak 达标后做人工肉眼观察，记录静止清晰度、动态滚动/窗口移动、连续输入响应、短断恢复后继续操作。
- [ ] P8：把最新报告结果回写到本文档，不把 `.rd_runtime/` 报告文件提交。
- [ ] P9：只有短断和 soak 都通过、人工观察也记录后，再进行阶段性提交和推送；提交说明使用中文，并明确本次验证范围。
- [ ] P10：把脚本默认 AVD 从不存在的 `Pixel_6_API_29` 调整为本机可用配置，或在文档/环境变量中固定 `RD_AVD_NAME=Pixel_9`，避免下一次三端模拟器验证卡在启动阶段。
- [ ] P11：如果真机和模拟器同时在线，脚本或文档必须显式要求 `--serial`，避免 `adb: more than one device/emulator` 误中断验证。
- [ ] P12：对双指捏合局部放大做有效验收：优先人工两指捏合确认倍率按钮从 `1x` 变化并可单指平移局部视图；若继续自动化，改用 instrumentation/测试 APK 注入真实多点事件，不再把本轮无效的 `uiautomator`/`sendevent` 当成功证据。

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

模拟器回归验证如需复现本轮结果，先显式指定可用 AVD 和 serial：

```zsh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
RD_ANDROID_MODE=emulator \
RD_AVD_NAME=Pixel_9 \
RD_AGENT_DEVICE_ID=auto \
./scripts/short_reconnect_check.sh \
  --target-device-id auto \
  --end-session
```

如果真机也在线，复用已启动三端时需要指定模拟器 serial：

```zsh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
RD_ANDROID_SERIAL=emulator-5554 \
RD_ANDROID_MODE=emulator \
RD_AGENT_DEVICE_ID=agent-19de3117874 \
./scripts/short_reconnect_check.sh \
  --no-restart \
  --serial emulator-5554 \
  --target-device-id agent-19de3117874 \
  --end-session
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
- [x] 2026-07-04 真机短断复跑仍满足以上全部项目，报告 `short_reconnect_20260704_154256` 没有退回 `local_ice_candidate_sent=0` 或 `input_only`
- [ ] 下一轮代码改动后仍需复跑短断，确认 `154256` 结果不退化
- [x] UI 重构后 Android 模拟器 -> Mac 回归短断已满足以上项目：`.rd_runtime/reports/short_reconnect_20260701_004122.md`

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
- [x] `quality_candidate_tiers=p2p_udp`
- [x] 2026-07-04 真机短断 `short_reconnect_20260704_154256` 为 PASS
- [x] 远程画面手势化最终 APK 真机短断 `short_reconnect_20260704_165039` 为 PASS
- [ ] 下一轮修复 sender 或 Android 渲染后复跑短断仍为 PASS
- [x] UI 重构后 Android 模拟器 -> Mac 短断回归仍为 PASS：`short_reconnect_20260701_004122`

### 远程画面手势验收

- [x] UI 上不再提供 `触控`、`移动`、`放大` 三个模式按钮，远程画面只保留倍率重置和全屏控制。
- [x] 单点点击仍由短断 proof 覆盖：`remote_input_coverage=click`，Mac 执行器 `macos.cg_event`。
- [x] 单指滑动表示移动鼠标指针：最终 APK 真机复测 Mac 端 `input.mouse.move applied=true` 7 条，新增 `input.mouse.button` 为 0。
- [x] 长按拖动表示鼠标拖拽：最终 APK 真机复测 Mac 端 `input.mouse.move applied=true` 1 条，`input.mouse.button applied=true` 2 条。
- [x] 双指滚轮仍由短断 proof 覆盖：`remote_input_coverage=wheel`，`remote_input_applied=11/11`。
- [ ] 双指捏合局部放大还缺有效真机验收：`uiautomator` 多点注入返回 OK 但没有触发 `remote_viewport_pinch_scale`，`sendevent` 因 `/dev/input/event2: Permission denied` 无法注入。
- [x] 键盘快捷键栏保留并通过 `⌘C` 实测：Android 发送 `KeyC down/up`，回执含 `modifiers=MetaLeft [applied/macos.cg_event]`，Mac 端 keyboard applied 2 条。

### Soak 验收

- [x] 新 quick soak 使用的是已安装后的新 APK：安装时间 `2026-07-04 15:40:26`，报告 `soak_6_5_20260704_154803`
- [ ] logcat 出现 `RemoteDeskSoak soak_dynamic_bounds source=remoteViewportContainer`；如果只出现 fallback，要记录 fallback 原因
- [x] logcat 出现 foreground/background/dynamic/recovery 的 `RemoteDeskSoak soak_phase`
- [ ] 最新代码跑出的 `overall=PASS`
- [ ] `render_fps_avg>=24.0`
- [x] `session_quality_hint_recent=stable`
- [x] `render_fps_recent>=23.5`，当前 `154803` 为 `23.976023976023978`
- [x] `render_recent_max_frame_gap_ms<1000`，当前 `154803` 为 `57.0`
- [x] `visible_frame_gap_ms_max<1000`，当前 `154803` 为 `87`
- [ ] 分阶段 `phase_frame_gap_ms_max` 全部 `<1000`，当前 background `3371` 未通过；foreground/dynamic/recovery/ending 分别为 `79/83/87/67`
- [x] `longest_low_fps_streak_sec<30.0`
- [x] `max_frames_dropped_spike<30`
- [x] `frames_dropped_delta_recent<30`
- [x] `remote_input_applied=11/11`
- [x] `remote_input_coverage=click,drag,keyboard,wheel`
- [x] `candidate_tier_last=p2p_udp`
- [x] `rtt_ms_avg` 没有长时间高 RTT 异常，当前 `4.0`
- [ ] 如果 `overall=FAIL`、`relay.session_quality_hint=render_frame_stutter` 或 `frames_dropped_spike`，只能记为“基础项通过但流畅性未最终通过”

### 人工观察验收

- [ ] Android 真机观看 Mac 桌面文字和 UI，静止画面清晰
- [ ] Mac 端滚动页面或播放动态内容时，Android 端肉眼无明显 1s 卡顿
- [ ] Android 端连续执行点击、拖拽、键盘、滚轮，Mac 端响应符合预期
- [ ] 短断恢复后继续操作，画面和输入都能恢复
- [ ] 人工观察结论需要写入本文件或新的报告，不只口头说“看起来可以”

### 提交和推送验收

- [x] 短断 PASS 报告路径写入本文档：`.rd_runtime/reports/short_reconnect_20260626_105952.md`
- [x] 最新 Soak FAIL 报告路径写入本文档：`.rd_runtime/reports/soak_6_5_20260626_110819.md`
- [x] UI 回归 PASS 报告路径写入本文档：`.rd_runtime/reports/short_reconnect_20260701_004122.md`
- [x] 最新真机短断 PASS 报告路径写入本文档：`.rd_runtime/reports/short_reconnect_20260704_154256.md`
- [x] 手势化最终 APK 真机短断 PASS 报告路径写入本文档：`.rd_runtime/reports/short_reconnect_20260704_165039.md`
- [x] 最新真机 quick soak FAIL 报告路径写入本文档：`.rd_runtime/reports/soak_6_5_20260704_154803.md`
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

截至本记录时间，可以实事求是地说：Android 真机 -> Mac 的 ICE candidate 链路、初始首帧、短断恢复首帧、输入控制覆盖、短断恢复短窗口质量、Mac native sender 首轮调优生效和工程检查都有本地证据支撑。远程画面已经去掉 `触控`、`移动`、`放大` 三个模式按钮，控制能力改为画面内手势；最终 APK 上单指移动、长按拖拽和 `⌘C` 键盘映射都已用真机 -> Mac 日志验证。最新真机短断报告 `short_reconnect_20260704_165039` 为 `PASS`，`remote_input_applied=11/11`，覆盖 `click,drag,keyboard,wheel`。在 `e95ff47` UI 重构后，Android 模拟器 -> Mac 回归报告 `short_reconnect_20260701_004122` 也是 `PASS`，说明 UI 调整没有破坏模拟器到 Mac 的核心短断链路。Android app 已改名为 `远控`，自定义图标已打入 APK；Redmi Note 8 Pro 上的 MTK H.264 硬解零帧问题已通过 `decoder_hardware_blocked` 兜底和短断回归验证。

还不能说：整条链路已经满足“手机端稳定看到并流畅控制 Mac”的最终目标。最新真机 quick soak `soak_6_5_20260704_154803` 仍是 `FAIL`，核心失败点是 `render_fps_avg=23.98 < 24.0`；虽然前台、动态和恢复阶段可见 gap 已压到 100ms 内，但 background 阶段仍出现 `3371ms` gap，且完整长时 soak 与人工肉眼观察还没有通过记录。双指捏合局部放大的代码已落地，但本轮自动注入没有形成有效多点手势：`uiautomator` 返回 OK 但没有触发倍率日志，`sendevent` 被系统拒绝 `Permission denied`，所以还需要人工两指捏合或新的 instrumentation 验收。下次应优先解释 background spike、`remoteViewportContainer` bounds fallback、23.98fps 边界和 pinch 验收方式，再复跑真机 soak。
