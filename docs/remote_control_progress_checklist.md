# Android 真机远控 Mac 任务与验证清单

Last updated: `2026-07-06 00:26:05 +0800`

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
- 2026-07-05 本轮真机联调已验证：全屏按钮层级恢复、全屏完整显示、鼠标移动尾帧补发、8Mbps native sender 生效、鼠标移动回执和 1546x1000 首帧；仍未验证真实双指捏合手感，且 1000p 清晰档仍只有约 `14fps`。
- 2026-07-05 03:10 本轮继续调整：Android 全屏捏合时不再在手势过程中重建视频渲染面，改为手势中只做合成层 transform、停手后一次性提交清晰渲染面；鼠标移动采样上限提高到 `8ms`，并消费 `MotionEvent` 历史点。真机全屏滑动已验证连续 `input.mouse.move` 落到 Mac，双指 pinch 仍未能通过 adb 自动注入闭环。
- 2026-07-05 03:39 本轮继续调整：新增 `session.viewport.interaction` 视图交互提示，并把 Android 真机交互临时采集档调整到 `800x520@30fps` 上限；全屏 4 秒长滑动验证 `800x517` 纯交互采样达到 `31.25fps`，停手恢复 `1547x1000` 清晰档后仍约 `14fps`。
- 2026-07-05 04:07 本轮继续调整：全屏缩放停手后的真实视频承载面增加 `8,000,000` 像素上限，避免 `4x` 时创建超大 Surface；pinch 停手后 Mac sender `220ms` 优先恢复清晰档，pan 停手后 `450ms` 恢复；macOS CoreGraphics fallback BGRA 缩放改为双线性。真机全屏滑动已验证 Android 发送 `input.mouse.move` 122 条，Mac `applied=true` 126 条；交互档 `31.50fps` 生效，清晰档仍约 `14fps`，Android 长会话渲染仍会跌到 `4-9fps`。
- 2026-07-05 04:44 本轮继续调整：Android pinch 本地承载面上限收紧到 `2,600,000` 像素，Mac 交互档回到 `800x520@30fps`，新增 `session.viewport.interaction` 的 `fullscreen` 类型，并让 Desktop 进入全屏后保持 `1120x728@30fps` 中间档、鼠标移动时临时降到 `800x517`。真机验证显示协议转发和 Mac 档位切换生效，Android -> Mac 鼠标移动 `275` 条发送、Mac `applied=true` `277` 条；但 Android 全屏渲染仍只有约 `6-10fps`，没有闭环。
- 2026-07-05 05:10 本轮继续调整：修复全屏容器重算后 `remoteViewportContent/remoteVideoView` 沿用旧尺寸的问题；全屏 UI tree 已验证 `remoteViewportContainer`、`remoteViewportContent`、`remoteVideoView` 均为 `[372,0][2043,1080]`。鼠标移动采样从 `8ms/0.00075` 收敛到 `16ms/0.001`，4 秒全屏滑动输入量从上一轮 `281/281` 降到 `208/208` 且 Mac 端无丢输入。未闭环：Android 全屏渲染仍会从 `11.88fps` 继续跌到约 `4.37-7.74fps`，Mac sender 交互档已能到 `31.59/31.39/31.22fps`，瓶颈继续指向 Android 全屏 Surface/窗口承载方式。
- 2026-07-05 05:45 本轮继续调整：新增 `TextureView + EglRenderer` 作为全屏渲染 A/B 路径，但默认关闭；真机已验证 `remote_video_renderer_switch mode=texture fullscreen=true` 后 `1120x724` 仍从约 `15fps` 下滑到 `7fps`，`frames_dropped` 从 `8` 增至 `127`，没有根治。临时放开 Redmi Note 8 Pro 的 MTK AVC 硬解后 24 秒仍 `frames_decoded=0`、watchdog `track_no_frame`，因此硬解屏蔽必须保留。把全屏基础档临时降到 `800x520` 后 Mac sender 可达 `31fps`，但 Android 全屏仍约 `8fps`，所以继续降低 sender 分辨率也不能关闭问题。
- 2026-07-05 07:05 本轮继续调整：修复 relay 未转发 `screen.frame.push`，Android 真机已恢复 legacy 首帧；随后对 Android 真机 profile 临时启用 JPEG 帧流专用路径，暂停无首帧的 H.264 native sender，兜底帧间隔调为 `100ms`。真机实测小窗/全屏可见，`1547x1000` legacy 显示约 `9.2-9.6fps`，全屏鼠标移动 Android 发送 `133` 条、Mac `applied=true` `133` 条。H.264/WebRTC 解码仍未修复，双指 pinch adb 自动化仍未闭环。
- 2026-07-05 07:40 本轮继续调整：Android legacy JPEG 解码队列新增过时帧丢弃，本地光标刷新合并到动画帧；Desktop Android 真机 JPEG 档位收紧为交互 `640x416`、全屏 `960x624`、局部高清 `1280x800`，并按档位动态选择 JPEG 质量。真机会话 `sess-1783207811491-1` 已验证：交互 `640x414` 最高样本 `13.40fps`，全屏 `960x621` 稳定约 `7.4-8.0fps`，全屏长滑动交互约 `12.01/12.96fps`，鼠标 move 时间窗内 Android 发送 200 条、Mac applied 232 条。结论：交互流畅性有改善，但视觉 FPS 仍未达 `>=24fps`；双指 pinch 仍未自动化闭环。
- 2026-07-05 07:58 本轮当前真机补证：截图 `/tmp/remotedesk-current-20260705.png`，SHA256 `3841d295e80e5a9232fc966a7edef29e25a434e2c5b621584f87c36289b061e7`，显示全屏远程桌面完整，左右黑边为比例适配。`gfxinfo` 显示普通 Android UI 帧较稳：`Janky frames=40/2752 (1.45%)`、P95 `18ms`、P99 `24ms`，说明当前卡顿主要不在普通 View 绘制。全屏 4 秒长滑动新增日志统计：relay 收到并转发 `input.mouse.move` 231 条，Mac `applied=true` 231 条；Android 交互档视觉样本 `12.86/13.32/13.44fps @640x414`，停手恢复全屏档约 `7.5-8.0fps @960x621`。结论不变：输入链路未丢，但远程画面视觉 FPS 仍不达标。
- 2026-07-05 08:19 本轮继续调整：`session.viewport.interaction` 已扩展 `viewport_x/y/width/height` 和 `focus_x/y`，Android 会按当前本地缩放/平移计算完整桌面归一化可视区域；relay 已校验这些字段并转发。真机会话 `sess-1783210557558-1` 验证：Android 发出的 `session.viewport.interaction` payload keys 包含全部 `viewport_*` 与 `focus_*` 字段，relay `session.tool.forwarded` 同步出现；全屏截图 `/tmp/remotedesk-fullscreen-swipe-0816.png` SHA256 `1bf503e97d6a83904b9706bdbbc7304479b5480a5d29463b4e72adbeb6d62980`。全屏长滑动期间 Mac `input.mouse.move applied=true` 时间窗内约 `249` 条，Android 交互档视觉样本 `13.31/13.53/13.47fps @640x414`，停手后约 `7.7-8.0fps @960x621`；`gfxinfo` 普通 UI `Janky frames=20/1425 (1.40%)`、P95 `17ms`、P99 `30ms`。结论：区域高清视口数据链路已打通，视觉流畅度仍未达 `>=24fps`。
- 2026-07-05 08:57 本轮继续调整：Desktop/Rust capture 已支持按 Android viewport 下发的 `source_rect_ppm` 区域裁剪，`screen.frame.push` 携带 `source_rect_*` 元数据，Android 对局部高清帧按 `source_rect` 反算输入坐标，并在渲染时避免把已裁剪局部帧再次本地放大导致发虚。真机会话 `sess-1783212760043-1` 已验证 debug viewport 链路：Mac 日志 `source_rect_ppm=250000,200000,500000,450000`，Android `legacy_frame_sample ... size=1028x599 source_rect=0.25,0.20,0.50,0.45`，2 秒内截图 `/tmp/remotedesk-during-materialized-source-debug.png` SHA256 `68acfe6ee9f62344196cc6ac9a9071c60388f815513147ed006248377e76fbad` 显示局部高清帧。全屏截图 `/tmp/remotedesk-fullscreen-after-materialized-source.png` SHA256 `0cb8a556540a3d4658fd6031d4146ed24670a2236241fb2434771db98c452c02` 显示桌面完整；全屏 4 秒滑动后 Mac `input.mouse.move applied=true` 从 `4` 增到 `238`，新增约 `234` 条。仍未闭环：真实人工双指 pinch 手感没有验收，JPEG fallback 视觉 FPS 仍约 `7-8fps @960x621`，不能宣称最终流畅。
- 2026-07-05 09:08 本轮继续调整但尚未真机验收：Android 鼠标移动改为接近 60Hz 的 `16ms / 0.0007` 合并，并给 pending move 记录采样时间，避免尾帧被吞或旧尾帧回跳；pinch 焦点滤波从 `0.90` 调到 `0.78`，停手后本地高清承载面提交从 `220ms` 提前到 `160ms`。Desktop Android 真机交互档从 `640x416` 收紧到 `560x364`，普通交互恢复窗口从 `2600ms` 缩短到 `1800ms`，pinch 后局部高清触发阈值从 `1.15x` 降到 `1.08x` 且 `160ms` 后恢复；Rust JPEG 对 `source_rect` 裁剪帧直接使用清晰质量 `82`，全屏质量从 `70` 提到 `72`。这条只记录代码变更，必须通过构建和真机手感/日志后才能写成已验证。
- 2026-07-05 09:16 本轮真机复测：Android debug 构建、Desktop build、Rust `cargo test`、Go `go test ./...`、`git diff --check` 均通过；APK 已安装到真机 `wsvwypiz7xwslvl7`，三端会话 `sess-1783213961511-1` 启动。全屏截图 `/tmp/remotedesk-0915-fullscreen.png` SHA256 `3989f4fe3dfc3aa3d95c7158c3747ab01fd11607425a1ebdee6ece86b0ec7dd6` 显示桌面完整；滑动后截图 `/tmp/remotedesk-0916-fullscreen-swipe.png` SHA256 `2b19c1c393ed9b9b0190ff938bc0755921f80f3b5f6719a0786e8d4c4eb4d9d2`。`560x362` 交互档生效，滑动期间 Android 发送汇总为 `4+61+60+60` 条 mouse move，Mac 时间窗内 `input.mouse.move applied=true` 235 条；Android 视觉样本为 `13.30/14.35fps @560x362`，停手后恢复 `960x621` 约 `7.4-8.0fps`，仍未达 `>=24fps`。debug viewport 局部高清链路通过：Mac `source_rect_ppm=250000,200000,500000,450000`，Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45`；真实人工双指 pinch 手感仍未验收。
- 2026-07-05 09:38 本轮继续调整和复测：中间激进版（全屏基础承载面 `1.0`、兜底帧调度扣除发送耗时、局部 JPEG 质量 `88`）实测未改善流畅性，已撤回前两项，最终保守版只保留 pinch/本地承载面错峰（Android `240ms`、Desktop `260ms`）和局部 JPEG 质量小幅提升到 `84`。最终保守版 `git diff --check`、Go `go test ./...`、Desktop `npm run build`、Rust `cargo test`、Android `assembleDebug` 均通过，并已重新安装到真机。
- 2026-07-05 09:38 最终真机复测：会话 `sess-1783215376937-1`，标准输入 proof 仍为 `11/11 applied`；全屏截图 `/tmp/remotedesk-0937-final-fullscreen.png` SHA256 `f96edc01c1fd7e17d7d2a530eaa72cff5f1fdcb321ab2479d724c57d16de35b6` 和滑动后 `/tmp/remotedesk-0937-final-fullscreen-swipe.png` SHA256 `3537a1d711e72aaf2fe68ef3c781eb7f9a469668fb273620236028aac6a7e2a3` 显示桌面完整；3 秒全屏滑动后 Mac 新增 `input.mouse.move applied=true` 177 条。未达标：全屏基础档多在 `4.36-7.12fps @960x621`，全屏交互档约 `5.73/7.06/6.28/7.46/8.06fps @560x362`，低于上一轮 `13-14fps`，必须继续作为 P0 回归风险处理。
- 2026-07-05 09:42 最终真机补测：debug viewport 后 Mac 切到 `1280x800@24fps source_rect_ppm=250000,200000,500000,450000`，Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45`；局部高清视觉样本仅 `3.56/3.77fps`，说明 source_rect 链路仍通，但 JPEG fallback 的缩放清晰/流畅体验仍未通过。
- 2026-07-05 10:08 本轮继续调整：Android legacy JPEG 解码去掉每帧 `inJustDecodeBounds` 二次解析，改为单次解码后复核 bitmap 尺寸；代码层面不改变协议和 UI。Android 构建、`git diff --check`、真机覆盖安装和三端重启通过。会话 `sess-1783217064539-1`：标准 proof `11/11 applied`；基础档 `800x517` 约 `9.4-10.4fps`；远程画面区域 3 秒滑动后 Mac `input.mouse.move applied=true` 从 `4` 增到 `138`，新增 `134` 条，交互档 `512x331` 样本 `14.70/15.66fps`；debug viewport 后 Mac `1120x700@18fps source_rect_ppm=250000,200000,500000,450000`，Android `1028x599 source_rect=0.25,0.20,0.50,0.45`，局部高清 `7.16/7.54fps`。仍未达 `>=24fps`，小窗“全屏”按钮 adb 坐标点击未触发进入全屏，需人工复测入口。
- 2026-07-05 10:21-10:25 本轮继续调整：全屏默认启用 `TextureView + EglRenderer`，全屏基础承载面恢复 `1.0x`，Android 鼠标 move 回执节流到约每秒 8 条，Desktop 成功 move result 和本地预览重绘节流。真机验证显示小窗按钮第二次坐标点击可进入全屏，日志 `remote_video_renderer_switch mode=texture fullscreen=true`；全屏截图 `/tmp/remotedesk-fullscreen-after-tap2.png` 显示远程桌面完整居中；3 秒全屏滑动 Mac 新增 `input.mouse.move applied=true` `177` 条，约 `59` 条/秒。未达标：基础档仍约 `9.7-10.0fps`，交互档约 `14.35fps`，局部高清约 `7.09-8.40fps`，Desktop 预览节流没有根治 JPEG fallback 瓶颈，真实双指 pinch 手感仍未验收。
- 2026-07-05 12:10-12:14 本轮继续调整：普通交互档收紧到 `448x292@30fps`，局部区域移动档保持 `512x334@30fps`，局部高清档调整为 `800x520@12fps` 且短时保持 `2600ms`；Android 小尺寸 legacy 帧使用 `RGB_565` 解码，停手高清帧仍保留 `ARGB_8888`。真机会话 `sess-1783224608715-1` 已验证：基础档 `800x517` 约 `9.75/10.17/9.88fps`，交互档 `448x290` 约 `15.26fps`，debug viewport 区域链路修复后 Mac 正确切到 `512x334@30fps source_rect_ppm=250000,200000,500000,450000`，随后 `800x520@12fps`；Android 局部高清样本约 `8.66fps @800x466 source_rect=0.25,0.20,0.50,0.45`。全屏截图 `.rd_runtime/screens/rd_fullscreen_try3_1211.png` SHA256 `da05eeea66dee8ea1781d95c18b7b5f4e4bcac786fbb162d30bc6bf26418a7d4` 显示桌面完整；全屏滑动新增 Mac `input.mouse.move applied=true` `71` 条。仍未达标：JPEG fallback 视觉 FPS 未到 `>=24fps`，真实双指手感未人工验收，WebRTC/H.264 未修复。
- 2026-07-05 12:23 本轮指标修正已验证：Android `session.metrics.report` 已把 legacy JPEG 首帧、帧数和平均 FPS 纳入通用 `first_frame_ms/rendered_frames/render_fps_avg`，并新增 `media_frame_transport`、`legacy_*`、`webrtc_*` 明细；relay combined summary 已出现 `media_frame_transport=legacy_jpeg`、`first_frame_ms=43`、`rendered_frames=66`、`render_fps_avg=9.92`、`legacy_rendered_frames=66`，不再把 fallback 有画面会话误判为 `-1/0`。
- 2026-07-05 12:50 本轮继续调整和真机复测：Android 全屏缩放停手后的真实承载面从固定 `1.0x` 改为最多 `1.18x` 且受 `3,600,000` 像素上限保护；legacy JPEG 显示改为启用 `filterBitmap/dither` 的 `BitmapDrawable`，减少本地放大采样毛边。`git diff --check`、`bash -n scripts/triad_ctl.sh`、server `go test ./...`、Desktop `npm run build`、Tauri `cargo test` 36 个测试、Android `assembleDebug` 均通过；APK 已安装到真机 `wsvwypiz7xwslvl7`，三端会话 `sess-1783226876738-1` 建立。全屏截图 `/tmp/remotedesk-fullscreen-1249.png` SHA256 `5bac107777cb25671ec4a1ae4ea3c3d17d02f7e2b0cd996625dd52f51b17519a` 显示桌面完整；3 秒全屏滑动 Mac `input.mouse.move applied=true` 新增 `348` 条，Android 交互档 `16.76/18.43fps @448x290`；debug viewport 后 Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，局部高清样本 `9.32/6.82fps`。仍未达标：视觉 FPS 未到 `>=24fps`，真实双指 pinch 手感未人工验收，当前可见链路仍是 JPEG fallback。
- 2026-07-05 13:09 本轮继续调整和真机复测：Android 鼠标移动从过密 `8ms` 收敛到 `16ms / 0.00055`，pinch 缩放与焦点平滑参数收敛；Desktop 局部高清保持延长到 `7000ms`，局部高清档调整为 `800x520@10fps`，并新增 `source_rect` 明显变化时的节流刷新；Rust 局部 JPEG 质量提升到 `88/90`。`git diff --check`、`bash -n scripts/triad_ctl.sh`、server `go test ./...`、Desktop `npm run build`、Tauri `cargo test` 36 个测试、Android `assembleDebug` 均通过；APK 已安装到真机，三端会话 `sess-1783227836869-1` 建立。标准 proof 为 `video_and_input_observed`，`first_frame_ms=107`，`media_frame_transport=legacy_jpeg`，`render_fps_avg=10.14`，`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_1308.png` SHA256 `c09d6306b81397290b7933554df79d98d5fd59d2e0f6e7272b29fcc4cb3721a2` 显示桌面完整居中；3 秒全屏滑动新增 Mac `input.mouse.move applied=true` `177` 条，约 `59Hz`；debug viewport 后 Mac 先切 `512x334@30fps source_rect_ppm=250000,200000,500000,450000`，再切 `800x520@10fps source_rect`，Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，局部高清样本约 `6.89/6.98fps`。仍未达标：基础档约 `10fps`，交互档约 `15-18fps`，局部高清约 `7fps`，真实双指 pinch 手感未人工验收，当前可见链路仍是 JPEG fallback。
- 2026-07-05 13:28-13:35 本轮继续调整和真机复测：Android legacy JPEG 显示改为复用 `RemoteDeskFrameView` 绘制，不再每帧创建 `BitmapDrawable`；局部 `source_rect` 帧即使尺寸较小也保留 `ARGB_8888`，避免放大后色阶损失；Desktop 局部裁剪区域刷新从 `320ms/30000ppm` 收紧到 `220ms/20000ppm`。本轮 `git diff --check`、`bash -n scripts/triad_ctl.sh`、server `go test ./...`、Desktop `npm run build`、Tauri `cargo test` 36 个测试、Android `assembleDebug` 均通过；APK 已安装到真机 `wsvwypiz7xwslvl7`，三端会话 `sess-1783229340532-1` 建立。标准 proof 为 `video_and_input_observed`，`first_frame_ms=155`，`media_frame_transport=legacy_jpeg`，`render_fps_avg=10.03`，`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_frame_view_entered_1332.png` SHA256 `7bbcf3186bebc5aa8bf98da3b7716e1d1bb8c9910f1b926101fbbe74b3c51e79` 显示桌面完整居中；3 秒全屏滑动新增 Mac `input.mouse.move applied=true` `178` 条，约 `59Hz`，滑动期间最高样本 `16.65fps @800x517`；debug viewport 后 Mac 先切 `512x334@30fps source_rect_ppm=250000,200000,500000,450000`，再切 `800x520@10fps source_rect_ppm=250000,200000,500000,450000`，Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，局部高清截图 `.rd_runtime/screens/rd_debug_viewport_after_frame_view_1334.png` SHA256 `3251a824b6f7168e19792f0ab8ca6e078da1dce638afb16977cdbed754702670`，局部高清样本约 `7.71/7.07fps`。结论：输入链路、全屏完整显示和局部清晰源链路未回归，交互期峰值略有改善；仍未达 `>=24fps`，真实双指 pinch 手感仍未人工验收。
- 2026-07-05 13:46 本轮继续调整和真机复测：Android legacy JPEG 解码加入小型 Bitmap 复用池，已退场帧优先作为下一帧 `inBitmap`，失败时无复用重试；Base64 帧解码优先 `NO_WRAP`，减少降级帧热路径分配。`git diff --check`、`bash -n scripts/triad_ctl.sh`、server `go test ./...`、Desktop `npm run build`、Tauri `cargo test` 36 个测试、Android `assembleDebug` 均通过；APK 已安装到真机 `wsvwypiz7xwslvl7`，三端会话 `sess-1783230202122-1` 建立。标准 proof 为 `video_and_input_observed`，`first_frame_ms=153`，`media_frame_transport=legacy_jpeg`，`render_fps_avg=10.00`，`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_bitmap_reuse_1345.png` SHA256 `b707042907583f5875d1af01f08c15d8915171edbe70d5d2397391cf35abc441` 显示桌面完整居中；3 秒全屏滑动新增 Mac `input.mouse.move applied=true` `181` 条，约 `60Hz`，滑动期间样本 `14.97/17.91fps @448x290`；debug viewport 后 Mac 先切 `512x334@30fps source_rect_ppm=250000,200000,500000,450000`，再切 `800x520@10fps source_rect_ppm=250000,200000,500000,450000`，Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，局部高清截图 `.rd_runtime/screens/rd_debug_viewport_bitmap_reuse_1346.png` SHA256 `e6097023b30e4de0554815fe92f2383e52c3faabf846788d14fa15743a59e02c`，局部高清样本约 `6.86/6.90fps`；日志未出现 `legacy_bitmap_reuse_failed`。结论：Bitmap 复用未造成解码/显示/input/source_rect 回归，但视觉 FPS 仍低于 `>=24fps`，真实双指 pinch 手感仍未人工验收。
- 2026-07-05 16:35 本轮继续调整和真机复测：Desktop 局部高清档从 `1024x668@12fps` 回收到 `960x624@12fps`，保持窗口从 `5200ms` 缩短到 `4400ms`；Rust 局部 JPEG 质量从 `90/88` 回收到 `84/80`，`960x624` 以内仍走锐利质量，超过该尺寸改走平衡质量。`git diff --check`、`bash -n scripts/triad_ctl.sh`、server `go test ./...`、Desktop `npm run build`、Tauri `cargo test` 36 个测试、Android `assembleDebug` 均通过；APK 已安装到真机 `wsvwypiz7xwslvl7`，三端会话 `sess-1783240363109-1` 建立。标准 proof 为 `video_and_input_observed`、`first_frame_ms=55`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=10.07`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_quality_tune.png` SHA256 `3f97b4e4b7d985089183cc54d7168872d6f7993755eb2b79f6ad30c8261543d6` 显示桌面完整；3 秒全屏滑动新增 Mac `input.mouse.move applied=true` `178` 条，约 `59Hz`；debug viewport 后 Mac 先切 `560x364@24fps source_rect`，再切 `960x624@12fps source_rect`，Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清样本先到 `8.06fps`、随后约 `5.18fps`，截图 `.rd_runtime/screens/rd_debug_viewport_after_quality_tune.png` SHA256 `8ec0c3b72907a40e7fc50f60ff9a545a1d49d37526534a5d9580fc3eb32f6727`。结论：输入落地和全屏完整显示未回归，局部高清低谷比 `1024x597` 的约 `4fps` 略稳；但 JPEG fallback 仍不能满足全屏缩放顺滑、缩放后清晰和鼠标视觉跟手目标。
- 2026-07-05 19:45 本轮继续调整和真机复测：Android pinch 过程中关闭整屏 Bitmap 高质量滤波，停手后按 `source_rect` 恢复高质量；Mac/Rust capture worker 增加 `avg_capture_ms/max_capture_ms/last_capture_ms/target_interval_ms/last_gap_ms/overruns` 采样日志，并在完整帧无裁剪且目标尺寸不变时跳过多余 BGRA 拷贝。`git diff --check`、Desktop `npm run build`、Tauri `cargo test`、Android `assembleDebug` 均通过；APK SHA256 `9782281fb41bcea56c71668667eb8a40540990736e39ad813ad37e4e20090e6b` 已安装到真机，三端会话 `sess-1783251823436-1` 建立。标准 proof 为 `video_and_input_observed`、`first_frame_ms=38`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=9.47`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_worker_tune_1944.png` SHA256 `7cf2ea4cd375a343feb3acd841f3d61ad9fd3336e48ee81c46fd5a5a6a5efa97` 显示桌面完整；`gfxinfo` 全屏滑动窗口为 `Janky frames=0 (0.00%)`、P95 `13ms`、P99 `15ms`；debug viewport 后 Mac `448x292@24fps source_rect` -> `960x624@12fps source_rect`，Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清约 `5.46/4.47fps`，截图 `.rd_runtime/screens/rd_debug_viewport_worker_tune_1945.png` SHA256 `fa3e9ad29321850f87f94324c4bdc9a693939c7fe6e9f5424b2cc054252d7b87`。Mac worker 采样显示 `800x517 target_interval_ms=33` 时 `last_capture_ms` 常见约 `99-112ms`，`640x414 target_interval_ms=41` 时约 `73-90ms` 且 overruns 持续增加。结论：Android UI 合成不是主要瓶颈；JPEG fallback 捕获/编码链路已经被量化为不达标，下一步必须转向最终媒体链路或更高效降级媒体通道。
- 2026-07-05 21:11 本轮继续调整和真机复测：Android 鼠标移动现在最多补发最近 `4` 个 `MotionEvent` 历史点，并按真实事件时间入队，避免真机主线程被 JPEG 解码/横屏布局抢占后只发尾点导致 Mac 鼠标跳动；pinch 预览整屏帧间隔调到 `2200ms`，减少双指缩放期间整屏 JPEG 刷新干扰。`git diff --check`、server `go test ./...`、Desktop `npm run build`、Tauri `cargo test` 37 个测试、Android `assembleDebug` 均通过；APK SHA256 `3418a054e54419c73b4155a1d931f1c8244bfc55c1e8b5c2d3904ab4f3ebd93e` 已安装真机 `wsvwypiz7xwslvl7`。三端会话 `sess-1783256887975-1` 建立，Mac agent `agent-19de3117874`、Android controller `android-6b04e47d7cccda58` 在线；标准 proof 为 `video_and_input_observed`、`first_frame_ms=43`、`media=legacy_jpeg`、`render_fps_avg=9.91/11.20`、`remote_input_applied=16/16`，覆盖 `click,drag,keyboard,wheel`。全屏截图 `.rd_runtime/screens/rd_fullscreen_after_history_points_2109.png` SHA256 `81324c457568b2448c52cc7f590d6291be23a5a22215a3bd8225a04666ae46c9` 和滑动后 `.rd_runtime/screens/rd_fullscreen_after_history_points_swipe_2110.png` SHA256 `0f0c2de71865a3fde2fa0b2cb11e2e980999db88b507f983afe0a3dd5c93988f` 显示桌面完整。全屏 3 秒滑动新增 Mac `input.mouse.move applied=true` `157` 条，applied 间隔多为约 `16-20ms`；`gfxinfo` 在全屏/滑动/source_rect 后 jank 约 `0.27-0.45%`、P95 `15ms`、P99 `16-17ms`。debug viewport 后 Mac `source_rect_ppm=250000,200000,500000,450000`，Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，截图 `.rd_runtime/screens/rd_debug_viewport_after_history_points_2111.png` SHA256 `cf449cd4a42bc2f003a5de31a94720b1a0885289b727362bd07f95d49b42f56d`；局部高清首样本约 `10.56fps`，随后约 `6.99/7.07fps`。结论：输入、全屏完整显示和 source_rect 局部高清链路未回归；视觉 FPS 仍未达 `>=24fps`，真实双指 pinch 手感仍未自动化闭环，下一阶段必须转向 WebRTC/H.264/硬编解码或更高效降级媒体通道。
- 上一轮 soak `103151` 仍保留为历史对照：原报告 `visible_frame_gap_ms_max=0`、`phase_frame_gap_ms_max=-` 有统计不足，临时重算 `/tmp/rd_soak_report_103151_recalc.md` 得到可见最大帧间隔 `861ms`
- 2026-07-06 00:15 本轮继续调整和真机复测：Android 鼠标移动采样收敛到 `12ms / 0.00032`、最多消费最近 `4` 个历史触摸点、尾帧最大延迟 `12ms`，本地光标预测为 `18ms`，缩放倍率提示刷新节流为 `220ms`；同时新增 manual span fallback，在 `ScaleGestureDetector` 未进入有效缩放时仍能从双指距离变化触发 `remote_viewport_pinch_scale source=manual`。Desktop Android 真机 JPEG 档位调整为基础 `800x520@30fps`、全屏 `640x416@24fps`、pinch 预览 `320x208@8fps`、局部移动 `512x333@22fps`、局部高清 `896x582@10fps`、局部静止清晰 `1280x832@5fps`；Rust 裁剪 JPEG 锐利阈值提高到 `1,120,000` 像素。`git diff --check`、server `go test ./...`、`bash -n scripts/triad_ctl.sh`、Desktop `npm run build`、Tauri `cargo fmt --check && cargo test`、Android `assembleDebug` 均通过；APK SHA256 `d291a85bfc6cf0d6ac0383549ec8b75b6f2092775025d4b5f9bef4624a55115b` 已安装真机 `wsvwypiz7xwslvl7`。三端会话 `sess-1783267574091-1` 标准 proof 为 `video_and_input_observed`，`first_frame_ms=31`，`media=legacy_jpeg`，`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。全屏 manual pinch 触发 `remote_manual_pinch_begin/end`，倍率约 `1.185 -> 2.437`；Mac 切到 `source_rect_ppm=294671,306641,410657,410305`，Android 物化为 `source_rect=0.29,0.31,0.41,0.41`，局部帧样本约 `7.95fps @896x578`，后续静止清晰约 `4-5fps @1142x737`。缩放后横向滑动 3 秒，Mac `input.mouse.move applied=true` 从 `3` 增至 `182`，新增约 `179` 条，接近 `60Hz`。截图与 gfxinfo 产物：`.rd_runtime/screens/rd_fullscreen_pinch_manual_20260706_000711.png`、`.rd_runtime/screens/gfxinfo_fullscreen_pinch_manual_20260706_000711.txt`、`.rd_runtime/screens/gfxinfo_fullscreen_pinch_manual_20260706_000711_framestats.txt`、`.rd_runtime/screens/rd_after_mouse_swipe_manual_zoom_20260706_000812.png`、`.rd_runtime/screens/gfxinfo_after_mouse_swipe_manual_zoom_20260706_000812.txt`、`.rd_runtime/screens/gfxinfo_after_mouse_swipe_manual_zoom_20260706_000812_framestats.txt`。结论：debug/manual pinch、source_rect 局部清晰链路和缩放后鼠标输入落地未回归；但当前可见媒体仍是 `legacy_jpeg`，视觉 FPS 仍未达 `>=24fps`，真实手指双指 pinch 手感还没有人工验收。
- 当前不能宣称最终目标完成：真机短断、模拟器 UI 回归、首帧、输入和短窗口质量已经有证据支撑；新版 quick soak 已显著改善可见阶段 gap，但平均 FPS 仍低于脚本硬阈值。最新 00:15 真机复测证明 debug/manual pinch、输入落地、全屏完整显示和区域高清链路未回归，但 `legacy_jpeg` 基础 proof 仍约 `10fps`，全屏/局部高清/静止清晰仍只有约 `4-8fps`，完整长时 soak、真机人工肉眼流畅验收，以及真实手指双指捏合局部放大的人工验收还没有通过记录。

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
| D18 | 全屏控制层级和完整显示复测 | 已验证 | 2026-07-05 真机截图 `/tmp/remotedesk-fullscreen-buttons-fix.png` 显示全屏远程桌面完整呈现，左右黑边为比例适配；UI tree 中 `remoteViewportContainer bounds=[424,33][1992,1047]`，`1x`、`退出全屏`、`键盘` 均可见 | 修复了真机上 `1x/全屏` 顶部按钮被视频层级吃掉的问题 |
| D19 | 全屏本地指针反馈和鼠标移动 proof | 已验证 | 2026-07-05 全屏滑动后截图 `/tmp/remotedesk-fullscreen-local-cursor.png` 可见蓝色本地指针反馈点；Android 连续发送 `input.mouse.move`，Mac/Android 回执出现 `目标已执行 鼠标移动 ... [applied/macos.cg_event]` | 本地指针只改善控制端即时反馈，真实输入仍以 Mac 回执为准 |
| D20 | 本轮全屏鼠标移动连续性复测 | 已验证 | 2026-07-05 03:09 真机全屏 `remoteViewportContainer bounds=[424,33][1992,1047]`；滑动后 Android 连续发送 `input.mouse.move`，Mac 回执 `applied/macos.cg_event`，坐标从约 `(0.59,0.58)` 推进到 `(0.72,0.61)`；截图 `/tmp/remotedesk-fullscreen-smooth-after.png` | 证明本轮触摸历史点和 8ms 采样没有破坏全屏输入链路；视觉流畅度仍受 14fps 视频限制 |
| D21 | 全屏长滑动交互临时档 | 已验证 | 2026-07-05 03:39 会话 `sess-1783193870302-1`：全屏 4 秒滑动后 Mac `encoder.ready ... frame=800x517`，纯交互样本 `fps=31.25`、`encode_ms_avg=16.2ms`；relay 汇总 `remote_input_applied=198/198`，截图 `/tmp/remotedesk-fullscreen-long-swipe-800.png` 显示全屏完整 | 证明“移动中临时降采集分辨率”能让 Mac sender 跑到 30fps 级别；停手后会恢复清晰档 |
| D22 | 全屏内容层尺寸同步 | 已验证 | 2026-07-05 05:08 真机 UI tree：`remoteViewportContainer`、`remoteViewportContent`、`remoteVideoView` 均为 `[372,0][2043,1080]`；截图 `/tmp/remotedesk-20260705-fullscreen-60hz-swipe.png`，SHA256 `2ba86b08cb6e2623eadfc946cbdf64240cb5c3fd18077d19af6d5fa9f2fe81d3` | 修复进入全屏后内容层仍使用旧尺寸导致画面看起来小一圈/发虚的问题 |
| D23 | 60Hz 鼠标移动采样链路 | 已验证 | 2026-07-05 05:08 全屏 4 秒滑动：Android `send type=input.mouse.move` 208 条，Mac `input_result type=input.mouse.move applied=true` 208 条；relay live proof 出现 `remote_input_applied=168/168` 和 `video_and_input_observed` | 输入落地未丢；采样收敛降低控制端压力，但没有解决 Android 全屏渲染低 FPS |
| D24 | 全屏渲染结构性 A/B 证据 | 已验证但未通过 | 2026-07-05 05:40 TextureView A/B：`remote_video_renderer_switch mode=texture fullscreen=true`，`1120x724` 后续 `7.24-7.71fps`、`frames_dropped=127`；2026-07-05 05:41 MTK 硬解 A/B：`frames_decoded=0`、`watchdog_recover_skipped reason=track_no_frame`；2026-07-05 05:43 全屏 `800x516` 低档 A/B：Android 仍 `7.78-8.43fps` | 三个方向都不能作为最终修复；TextureView 代码保留为默认关闭的实验路径，硬解屏蔽恢复，全屏基础档恢复到 `1120x728` |
| D25 | Android 真机 legacy JPEG 兜底显示恢复 | 已验证但为临时方案 | 2026-07-05 07:01 会话 `sess-1783206072021-1`：Android `legacy_first_frame ... size=1114x720`；relay `session.tool.forwarded type=screen.frame.push`；Android 收到 `508+` 个 `screen.frame.push`；`legacy_frame_sample` 稳定约 `9.19-9.60fps size=1547x1000`；截图 `/tmp/remotedesk-fullscreen-legacy-only-100ms.png` SHA256 `329cab288f67a5776352a85bf8f2d5876ce48fc14d81cdfbb7db508341021d21` | 证明“看不到远程画面”已恢复；这是 JPEG fallback，不是 WebRTC H.264 最终修复 |
| D26 | JPEG-only 全屏鼠标移动 proof | 已验证但未达最终流畅 | 2026-07-05 07:02 全屏长滑动：Android 发送 `input.mouse.move` 133 条，Mac `input_result type=input.mouse.move applied=true` 133 条；截图显示远程桌面完整，`1x/退出全屏/键盘` 控件可见 | 输入落地不丢；画面约 9fps，比 3.9fps 有改善，但仍未达到 24fps 流畅目标 |
| D27 | JPEG-only 交互档降载与解码队列去积压 | 已验证但未达最终流畅 | 2026-07-05 07:31 会话 `sess-1783207811491-1`：Desktop `config.updated max_width=640 max_height=416`；Android 自动 proof 样本 `fps=13.40 size=640x414`；全屏长滑动期间 `fps=12.01/12.96 size=640x414`；鼠标 move 时间窗内 Android 发送 200 条，Mac 时间窗内 `applied=true` 232 条 | 证明过时帧丢弃、光标动画帧合并、交互档降载后有改善；Mac 结果行无 trace，数量只能按时间窗近似；视觉 FPS 仍低于 24fps |
| D28 | JPEG-only 全屏基础档 960x624 | 已验证但未达最终流畅 | 2026-07-05 07:31 Desktop `config.updated max_width=960 max_height=624`；Android `legacy_frame_sample` 稳定约 `7.40-7.97fps size=960x621`；截图 `/tmp/remotedesk-after-tuning-fullscreen-20260705-0731.png` SHA256 `bd07946d39f0c200237eca43f9c6d31f0d5f309324170c6c684281dee79d7453` | 全屏完整性继续保持，帧率比上一轮约 6fps 略好，但仍明显不流畅 |
| D29 | 07:58 当前真机补充验证 | 已验证但未达最终流畅 | 截图 `/tmp/remotedesk-current-20260705.png` SHA256 `3841d295e80e5a9232fc966a7edef29e25a434e2c5b621584f87c36289b061e7`；`gfxinfo` 为 `Janky frames=40/2752 (1.45%)`、P95 `18ms`、P99 `24ms`；全屏 4 秒滑动新增统计：relay `input.mouse.move` 收到/转发 `231/231`，Mac `applied=true` `231`；交互档视觉样本 `12.86/13.32/13.44fps @640x414` | 再次证明输入链路不丢，普通 Android UI 绘制不是当前主瓶颈；视觉低 FPS 仍来自 JPEG/WebRTC 媒体链路，不能关闭流畅性目标 |
| D30 | 区域高清视口字段数据链路 | 已验证但当时只完成协议层 | 2026-07-05 08:19 会话 `sess-1783210557558-1`：Android `send type=session.viewport.interaction ... payload_keys=phase,interaction,scale,created_at,viewport_x,viewport_y,viewport_width,viewport_height,focus_x,focus_y`；relay `session.tool.forwarded` payload keys 包含同样字段；Go relay 测试覆盖合法区域转发和越界区域拒绝 | 这是 D31 之前的协议层证据；后续已推进到 JPEG fallback 的 `source_rect` 裁剪链路，但真实双指体验和最终 WebRTC 区域媒体链路仍未完成 |
| D31 | 区域高清 source rect 裁剪和局部帧渲染链路 | 已验证但未达最终流畅 | 2026-07-05 08:53 会话 `sess-1783212760043-1`：debug viewport 触发后 Mac `config.updated ... source_rect_ppm=250000,200000,500000,450000`；Android `legacy_frame_sample ... size=1028x599 source_rect=0.25,0.20,0.50,0.45`；截图 `/tmp/remotedesk-during-materialized-source-debug.png` SHA256 `68acfe6ee9f62344196cc6ac9a9071c60388f815513147ed006248377e76fbad` | 证明 Android -> relay -> Mac -> Android 的区域高清 JPEG fallback 数据链路已打通，并修复局部帧二次放大导致发虚的问题；真实双指手感、区域状态下人工点击坐标、WebRTC 区域媒体链路和 `>=24fps` 仍未完成 |
| D32 | 08:19 全屏滑动复测 | 已验证但未达最终流畅 | 截图 `/tmp/remotedesk-fullscreen-swipe-0816.png` SHA256 `1bf503e97d6a83904b9706bdbbc7304479b5480a5d29463b4e72adbeb6d62980`；Mac `input.mouse.move applied=true` 时间窗约 `249` 条；Android 交互档 `13.31/13.53/13.47fps @640x414`，停手恢复 `7.78/7.79/7.88fps @960x621`；`gfxinfo` 普通 UI `Janky frames=20/1425 (1.40%)` | 输入落地继续稳定，普通 UI 不抖；画面 FPS 仍远低于 `>=24fps`，下一步不能再只调事件采样 |
| D33 | 09:16 `560x364` 交互档和局部高清复测 | 已验证但未达最终流畅 | 会话 `sess-1783213961511-1`：全屏截图 `/tmp/remotedesk-0915-fullscreen.png` SHA256 `3989f4fe3dfc3aa3d95c7158c3747ab01fd11607425a1ebdee6ece86b0ec7dd6`；滑动后截图 `/tmp/remotedesk-0916-fullscreen-swipe.png` SHA256 `2b19c1c393ed9b9b0190ff938bc0755921f80f3b5f6719a0786e8d4c4eb4d9d2`；移动中 `13.30/14.35fps @560x362`，Mac 时间窗 `input.mouse.move applied=true` 235 条；debug viewport 后 Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45` | 最新代码没有破坏完整显示、输入落地和区域裁剪；但视觉 FPS 仍不达标，真实双指 pinch 手感仍需人工验收 |
| D34 | 09:38 最终保守版全屏/滑动/局部高清复测 | 已验证但存在回归风险 | 会话 `sess-1783215376937-1`：最终 APK 安装后三端重启；输入 proof `11/11 applied`；全屏截图 `/tmp/remotedesk-0937-final-fullscreen.png` SHA256 `f96edc01c1fd7e17d7d2a530eaa72cff5f1fdcb321ab2479d724c57d16de35b6`；滑动后截图 `/tmp/remotedesk-0937-final-fullscreen-swipe.png` SHA256 `3537a1d711e72aaf2fe68ef3c781eb7f9a469668fb273620236028aac6a7e2a3`；3 秒滑动 Mac 新增 `input.mouse.move applied=true` 177 条；debug viewport 后 Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45` | 桌面完整显示、输入落地、source_rect 链路未回归；视觉 FPS 只有 `5-8fps`，局部高清只有 `3.56/3.77fps`，不能写成流畅性修复 |
| D35 | 10:08 单次 JPEG 解码优化复测 | 已验证但未达最终流畅 | 会话 `sess-1783217064539-1`：Android legacy JPEG 解码改为单次解码后复核尺寸；标准 proof `11/11 applied`；基础档 `800x517` 约 `9.4-10.4fps`；远程画面区域 3 秒滑动 Mac 新增 `input.mouse.move applied=true` `134` 条，交互档 `512x331` 为 `14.70/15.66fps`；debug viewport 后 Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45`，局部高清 `7.16/7.54fps`；截图 `/tmp/remotedesk-1005-viewport.png`、`/tmp/remotedesk-1007-swipe.png`、`/tmp/remotedesk-1008-debug-viewport.png` 有 SHA256 记录 | 证明 Android 解码热路径优化有效，较 09:37/09:42 低谷改善；仍未达到 `>=24fps`，不能关闭缩放/鼠标视觉流畅目标 |
| D36 | 10:21-10:25 全屏 TextureView 和回执降压复测 | 已验证但未达最终流畅 | Android/Go/Desktop/Rust 构建测试均通过；真机 `wsvwypiz7xwslvl7` 三端重启后会话 `sess-1783218311459-1` 建立。全屏入口第二次坐标点击成功，日志 `remote_video_renderer_switch mode=texture fullscreen=true`，截图 `/tmp/remotedesk-fullscreen-after-tap2.png` 显示远端桌面完整居中；debug viewport 后 Mac `source_rect_ppm=250000,200000,500000,450000`，Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45`；3 秒全屏滑动 Mac `input.mouse.move` 从 `4` 到 `181`，新增 `177` 条且 `applied=true`，Android move 回执约每秒 8 条 | 输入落地接近 60Hz 且回执降压生效，全屏显示完整和 source_rect 链路未回归；基础档仍约 `9.7-10.0fps`、交互档约 `14.35fps`、局部高清约 `7.09-8.40fps`，不能写成缩放/鼠标视觉流畅已完成 |
| D37 | 13:28-13:35 自定义 legacy 绘制器和局部帧高色深复测 | 已验证但未达最终流畅 | 会话 `sess-1783229340532-1`：新增 `RemoteDeskFrameView` 复用绘制路径；全屏截图 `.rd_runtime/screens/rd_fullscreen_frame_view_entered_1332.png` SHA256 `7bbcf3186bebc5aa8bf98da3b7716e1d1bb8c9910f1b926101fbbe74b3c51e79` 显示桌面完整；全屏 3 秒滑动 Mac `input.mouse.move applied=true` 从 `5` 到 `183`，新增 `178` 条；debug viewport 后 Mac `512x334@30fps` -> `800x520@10fps source_rect_ppm=250000,200000,500000,450000`，Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，截图 `.rd_runtime/screens/rd_debug_viewport_after_frame_view_1334.png` SHA256 `3251a824b6f7168e19792f0ab8ca6e078da1dce638afb16977cdbed754702670` | 证明本轮改动未破坏输入、全屏完整显示和局部高清裁剪；滑动期最高样本约 `16.65fps @800x517`，局部高清约 `7.71/7.07fps`，仍低于 `>=24fps`，不能宣称缩放/鼠标视觉流畅完成 |
| D38 | 13:46 Bitmap 复用池和真机三端复测 | 已验证但未达最终流畅 | 会话 `sess-1783230202122-1`：Android legacy JPEG 解码新增 Bitmap 复用池；标准 proof `video_and_input_observed`、`first_frame_ms=153`、`render_fps_avg=10.00`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_bitmap_reuse_1345.png` SHA256 `b707042907583f5875d1af01f08c15d8915171edbe70d5d2397391cf35abc441`；全屏 3 秒滑动新增 `181` 条 Mac `input.mouse.move applied=true`；debug viewport 后 Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，截图 `.rd_runtime/screens/rd_debug_viewport_bitmap_reuse_1346.png` SHA256 `e6097023b30e4de0554815fe92f2383e52c3faabf846788d14fa15743a59e02c` | 证明复用池没有引入解码失败、显示裁切、输入或区域高清回归；交互档约 `14.97/17.91fps`，局部高清约 `6.86/6.90fps`，仍低于 `>=24fps`，下一步仍应转向 WebRTC/H.264 或更高效降级媒体通道 |
| D39 | 15:50 全屏缩放合成层和鼠标移动节奏复测 | 已验证但未达最终流畅 | 会话 `sess-1783237518336-1`：标准 proof `video_and_input_observed`、`first_frame_ms=62`、`media_frame_transport=legacy_jpeg`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_smooth_tune_1547.png` SHA256 `de5fb682c200f1cacc168a1b80602bc46ddad985a2a182dbcdf4290ad0a77d7f`；全屏三段滑动新增 Mac `input.mouse.move applied=true` `150` 条，交互档最高 `16.92fps @448x290`；debug viewport 后 Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清 `5.59/5.22fps`，截图 `.rd_runtime/screens/rd_debug_viewport_after_smooth_tune_1552.png` SHA256 `85d0c706a838889323a4bdfde5279cbe3f8acc1a7f50bd93b83fcfdd76c00387` | 临时硬件合成层、高质量缩放和 `16ms / 0.00030` move 节奏没有引入全屏完整显示、输入或区域高清回归；但视觉 FPS 仍低于 `>=24fps`，真实双指 pinch 仍需人工手感验收 |
| D40 | 16:04-16:06 pinch 展示节流、鼠标队列节流和局部 JPEG 锐利阈值复测 | 已验证但未达最终流畅 | 会话 `sess-1783238649036-1`：标准 proof `video_and_input_observed`、`first_frame_ms=57`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=9.66`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_current_tune.png` SHA256 `8e054ea91b2c16a5989983be38b3cc05bdd87f455fd2988d15042e89b648838c` 显示桌面完整；全屏 3 秒滑动 Mac `input.mouse.move applied=true` 从 `3` 增到 `173`，新增 `170` 条，交互档最高 `18.24fps @448x290`；debug viewport 后 Mac 切 `560x364@24fps source_rect` 再切 `960x624@16fps source_rect`，Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清 `5.28/5.39fps`，截图 `.rd_runtime/screens/rd_debug_viewport_current_tune.png` SHA256 `92ae38ab0b93d3654be088513e8d242f4115e5a6c58e1c95ae92f93a5437fff5` | 输入落地和全屏完整显示继续通过；Mac 鼠标 move 实际执行间隔稳定在约 `16-18ms`；但 JPEG fallback 基础约 `10fps`、交互峰值约 `18fps`、局部高清约 `5fps`，真实双指 pinch 仍未自动闭环 |
| D41 | 16:35 局部高清档回收和 JPEG 质量降载复测 | 已验证但未达最终流畅 | 会话 `sess-1783240363109-1`：标准 proof `video_and_input_observed`、`first_frame_ms=55`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=10.07`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_quality_tune.png` SHA256 `3f97b4e4b7d985089183cc54d7168872d6f7993755eb2b79f6ad30c8261543d6`；3 秒全屏滑动 Mac `input.mouse.move applied=true` 从 `3` 增到 `181`，新增 `178` 条，约 `59Hz`；debug viewport 后 Mac `560x364@24fps source_rect` -> `960x624@12fps source_rect`，Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清样本 `8.06/5.18fps`，截图 `.rd_runtime/screens/rd_debug_viewport_after_quality_tune.png` SHA256 `8ec0c3b72907a40e7fc50f60ff9a545a1d49d37526534a5d9580fc3eb32f6727` | 输入落地和全屏完整显示继续通过；降低 JPEG 质量让局部高清首样本从 4fps 低谷改善到 8fps，但稳定后仍约 5fps，不能关闭缩放清晰/流畅目标 |
| D42 | 19:45 worker 采样和全屏/局部高清复测 | 已验证但未达最终流畅 | 会话 `sess-1783251823436-1`：标准 proof `video_and_input_observed`、`first_frame_ms=38`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=9.47`、`remote_input_applied=8/8`；APK SHA256 `9782281fb41bcea56c71668667eb8a40540990736e39ad813ad37e4e20090e6b`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_worker_tune_1944.png` SHA256 `7cf2ea4cd375a343feb3acd841f3d61ad9fd3336e48ee81c46fd5a5a6a5efa97`；`gfxinfo` 全屏滑动窗口 `Janky frames=0 (0.00%)`、P95 `13ms`、P99 `15ms`；debug viewport 截图 `.rd_runtime/screens/rd_debug_viewport_worker_tune_1945.png` SHA256 `fa3e9ad29321850f87f94324c4bdc9a693939c7fe6e9f5424b2cc054252d7b87`，局部高清 `960x559 source_rect=0.25,0.20,0.50,0.45` 约 `5.46/4.47fps`；Mac worker `800x517 target_interval_ms=33` 时 `last_capture_ms` 常见 `99-112ms`，`640x414 target_interval_ms=41` 时 `73-90ms` | 完整显示、输入和区域高清未回归；Android UI 合成不是主要 jank 源，Mac/JPEG fallback 捕获编码已量化 overrun，下一步必须转最终媒体链路/硬编解码或更高效降级通道 |

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
| F11 | 1000p 清晰档帧率 | 未通过 | 2026-07-05 真机联调中 Android `render_frame_sample` 持续约 `13.9-14.5fps`；Mac `probe.sample` 约 `14.1-14.3fps`，`encode_ms_avg` 约 `58ms` | 本地指针反馈改善“手指已移动”的即时观感，但不能把视频画面流畅度写成已完成 |
| F12 | 本轮 adb 双指缩放自动化 | 未闭环 | 2026-07-05 03:10 在全屏 bounds 内执行并发 `adb shell input swipe`，没有出现 `remote_viewport_pinch_scale`；日志表现为普通 `input.mouse.move` 序列 | adb 并发 swipe 不能作为双指捏合验收依据；需要人工真机或 instrumentation 多点事件 |
| F13 | 清晰档最终流畅性 | 未通过 | 2026-07-05 03:39 停手恢复 `1547x1000` 后 Mac `probe.sample` 仍约 `14.18fps`、`encode_ms_avg=58.9ms`；relay 质量提示仍出现 `render_fps_streak` | 交互档解决“移动中跟手”的一部分问题，不能替代清晰档硬件编码/转换优化 |
| F14 | 全屏长会话 Android 渲染退化 | 未通过 | 2026-07-05 04:44 会话 `sess-1783197817272-1`：`fullscreen` hint 已转发，Mac 切到 `1120x724` 后可到约 `23.7fps`；长滑动期间 Mac `800x517` 交互档可到 `31.68/31.41fps`，但 Android 全屏 `render_frame_sample` 仍只有 `6.62-10.02fps`，`recent_quality_hint=render_fps_streak` | 降 sender 分辨率和关闭全屏硬件缩放器仍未解决，下一步应重点评估 Android 全屏渲染组件/窗口承载方式，而不是继续只调 Mac 码率 |
| F15 | 05:10 后全屏渲染复测 | 未通过 | 会话 `sess-1783199256194-1`：进入全屏后内容层尺寸已正确；Mac sender `800x517` 交互档 `31.59/31.39/31.22fps`，`1120x724` 全屏基础档约 `23.6-24.2fps`；Android 端全屏交互档仍从 `11.88fps` 跌到 `7.74/7.67/6.96fps`，之后全屏基础档约 `4.37-5.38fps` | 证明当前失败不在 Mac sender 交互档，也不在内容层尺寸；P0 改为替换 Android 全屏承载方式并做 A/B |
| F16 | 05:45 后结构性 A/B | 未通过 | Activity overlay、TextureView、全屏 `800x520` 低档、MTK AVC 硬解 A/B 均已实测；硬解不吐帧，全屏低档仍个位数 FPS，TextureView 仍大量掉帧 | 当前最可信瓶颈是 Redmi Note 8 Pro 上 WebRTC H.264 软件解码叠加全屏横屏渲染/合成压力；下一步不应继续微调按钮、采样或 sender 分辨率，应改编码/解码策略或建立设备能力分层 |
| F17 | WebRTC H.264 真机视频链路 | 未通过 | 2026-07-05 07:01 Android 真机 JPEG-only 路径无 `decoder_create_request`，可见画面来自 `screen.frame.push`；上一轮 H.264 懒加载平台软件解码仍卡在 decoder 创建后无首帧 | 当前恢复可见性依赖临时 JPEG fallback，不能写成 WebRTC 已修复 |
| F18 | 全屏视觉流畅度 | 未达标 | JPEG-only 后 `1547x1000` legacy 约 `9.2-9.6fps`，全屏输入 133/133 applied | 比 0.8fps/3.9fps 明显改善，但仍低于验收标准 `>=24fps` |
| F19 | adb 双指 pinch 自动化 | 未闭环 | 横屏物理坐标并发 `adb shell input swipe` 后截图 `/tmp/remotedesk-pinch-attempt-legacy-only.png` 仍显示 `1x`，未出现 `remote_viewport_pinch_scale` | 仍需要人工真机两指操作或 instrumentation 多点事件测试 |
| F20 | 07:31 后 JPEG-only 视觉 FPS | 未达标 | 清晰档 `1547x1000` 约 `3.4-3.8fps`，全屏基础档 `960x621` 约 `7.4-8.0fps`，全屏交互档 `640x414` 约 `12.0-13.4fps` | 交互档有提升，但仍离 `>=24fps` 很远；继续只调 JPEG 尺寸/质量不应作为最终路线 |
| F21 | adb 多点事件能力 | 未闭环 | `adb shell input` 显示 `motionevent <DOWN|UP|MOVE|CANCEL> <x> <y>`，没有 pointer id 参数；普通 `swipe`/并发 `swipe` 不能证明真实双指 pinch | 双指缩放必须用人工真机或 instrumentation/测试 APK 验收 |
| F22 | 缩放后清晰度的根因 | 部分闭环 | 已实现 Android 上报可视区域和焦点、Desktop/Rust 按区域 `source_rect_ppm` 裁剪采集、`screen.frame.push` 携带 `source_rect_*`、Android 按区域帧反算触摸坐标；debug viewport 真机链路已验证 | 这解决了“只放大整屏低细节帧”的数据来源问题；但真实双指 pinch 手感、局部状态下人工点击坐标、长时间保持清晰和视觉 FPS 仍未达标 |
| F23 | WebRTC 区域高清媒体链路 | 未完成 | 当前区域高清只在 JPEG fallback 的 `screen.frame.push` 中验证；Redmi 真机 H.264/WebRTC 仍未修复，relay proof 仍为 `input_only` | 下一步要把 `source_rect` 策略接入最终 WebRTC/硬编码链路，或者把 JPEG fallback 产品化为明确降级路径；不能把 debug intent + JPEG 区域帧写成最终体验完成 |
| F24 | 小窗全屏入口自动坐标稳定性 | 部分闭环 | 2026-07-05 10:21 第二次坐标点击已成功进入全屏，Android 日志出现 `remote_video_renderer_switch mode=texture fullscreen=true`；第一次坐标没命中，说明截图估点不稳定 | 入口本身不能按 10:06 记录判定失败，但后续自动验收必须改用 UIAutomator bounds 或人工点击记录，不能继续依赖裸坐标 |
| F25 | JPEG fallback 视觉 FPS | 未通过 | 12:10-12:14 复测：基础档 `800x517` 约 `9.75/10.17/9.88fps`，交互档 `448x290` 约 `15.26fps`，局部高清 `800x466 source_rect=0.25,0.20,0.50,0.45` 约 `8.66fps`；全屏滑动输入新增 `71` 条 Mac `applied=true` | 继续微调 JPEG/Base64/Bitmap 路径收益有限；下一阶段应推进 WebRTC/H.264 修复、硬编码/硬解能力分层，或把 JPEG 降级作为受控兜底而不是最终流畅方案 |
| F26 | legacy JPEG 指标汇总 | 已验证 | 会话 `sess-1783225422776-1` 的 relay `session.metrics.combined` 出现 `media_frame_transport=legacy_jpeg`、`first_frame_ms=43`、`rendered_frames=66`、`render_fps_avg=9.92`、`legacy_first_frame_ms=43`、`legacy_rendered_frames=66`、`webrtc_rendered_frames=0` | fallback 有画面时自动 proof 现在能判定 `video_and_input_observed`；视觉 FPS 仍低，指标修正不等于流畅性完成 |
| F27 | 13:35 后 JPEG fallback 视觉 FPS | 未通过 | 本轮自定义绘制器后，基础档仍约 `10fps @800x517`，全屏滑动期间最高样本约 `16.65fps @800x517`，局部高清约 `7.71/7.07fps @800x466 source_rect=0.25,0.20,0.50,0.45` | 减少 Drawable 抖动和保留局部帧高色深是有效小优化，但没有改变 JPEG/Base64/Bitmap 链路的根本吞吐上限；下一步仍应优先修复 WebRTC/H.264 或建立设备 codec 能力分层 |
| F28 | 13:46 后 Bitmap 复用视觉 FPS | 未通过 | Bitmap 复用池后，基础 proof 仍 `render_fps_avg=10.00`；全屏滑动交互档约 `14.97/17.91fps @448x290`；局部高清约 `6.86/6.90fps @800x466 source_rect=0.25,0.20,0.50,0.45` | 复用池能降低分配/GC 风险，但没有把 JPEG/Base64/Bitmap 降级链路提升到流畅标准；继续微调 Android Bitmap 热路径收益有限 |
| F29 | 15:50 后全屏缩放/鼠标视觉 FPS | 未通过 | 本轮基础 proof `render_fps_avg=9.75`；全屏滑动交互档最高 `16.92fps @448x290`；局部高清 `960x559 source_rect=0.25,0.20,0.50,0.45` 只有 `5.59/5.22fps` | 临时硬件合成层能降低本地 transform 抖动风险，高质量 Bitmap 缩放能减少放大像素感，但 JPEG/Base64/Bitmap 降级链路仍不能满足顺滑和清晰同时达标；下一步必须转向最终媒体链路或能力分层 |
| F30 | 16:06 后全屏缩放/鼠标视觉 FPS | 未通过 | 本轮基础 proof `render_fps_avg=9.66`；全屏滑动交互档最高 `18.24fps @448x290`；局部高清 `960x559 source_rect=0.25,0.20,0.50,0.45` 只有 `5.28/5.39fps` | pinch 期间整屏 legacy 帧节流只降低手势过程中解码/重绘干扰，Mac 鼠标队列节流修正只改善输入执行节奏；两者都不能改变 JPEG/Base64/Bitmap 源帧吞吐，仍不能满足“全屏缩放顺滑、缩放后清晰、鼠标视觉跟手”目标 |
| F31 | 16:35 后局部高清和鼠标视觉 FPS | 未通过 | 本轮基础 proof `render_fps_avg=10.07`；全屏滑动 Mac applied 约 `59Hz`；局部高清 `960x559 source_rect=0.25,0.20,0.50,0.45` 首样本 `8.06fps`，随后约 `5.18fps` | 继续证明鼠标输入链路不是主要瓶颈，用户看到的鼠标和缩放不顺主要来自 `legacy_jpeg` 可见链路；下一步必须推进 WebRTC/H.264/硬编解码或更高效降级媒体通道 |
| F32 | 19:45 后 legacy JPEG worker 吞吐 | 未通过 | 基础 proof `render_fps_avg=9.47`；全屏基础约 `7fps @640x414`；局部高清约 `5.46/4.47fps @960x559 source_rect=0.25,0.20,0.50,0.45`；Mac worker `last_capture_ms` 多次超过目标帧间隔，overruns 持续增加 | 这轮把“鼠标移动不流畅/缩放后不清晰”的剩余主因进一步定位到可见媒体链路；继续微调 Bitmap/JPEG 参数收益有限，P0 改为媒体链路重构和设备能力分层 |
| F33 | 21:11 历史触摸点补偿后视觉 FPS | 未通过 | 标准 proof `render_fps_avg=9.91/11.20`，全屏 `640x414` 约 `11.7-12.7fps`，局部高清 `800x466 source_rect=0.25,0.20,0.50,0.45` 为 `10.56fps` 后降到约 `6.99/7.07fps`；全屏滑动新增 `157` 条 Mac `input.mouse.move applied=true`，`gfxinfo` jank 仅约 `0.27-0.45%` | 历史点补偿让鼠标轨迹更连续，且输入/完整显示/source_rect 未回归；但用户看到的鼠标和缩放画面仍受 `legacy_jpeg` 低 FPS 限制，不能关闭“全屏缩放顺滑、缩放后清晰且流畅、鼠标视觉跟手”目标 |
| F34 | 00:15 manual pinch fallback 与局部静止清晰 | 未通过 | 会话 `sess-1783267574091-1`，proof `video_and_input_observed`，APK SHA256 `d291a85bfc6cf0d6ac0383549ec8b75b6f2092775025d4b5f9bef4624a55115b`；manual pinch 倍率约 `1.185 -> 2.437`，Mac `source_rect_ppm=294671,306641,410657,410305`，Android `source_rect=0.29,0.31,0.41,0.41`；局部样本约 `7.95fps @896x578`，静止清晰约 `4-5fps @1142x737`；缩放后滑动 Mac `input.mouse.move applied=true` 从 `3 -> 182` | debug/manual pinch 和 source_rect 清晰源路径闭环，缩放后输入接近 `60Hz` 落地；但可见链路仍是 `legacy_jpeg`，视觉 FPS 未达标，不能替代真实手指 pinch 手感验收 |

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
| U7 | 早前 `remoteViewportContent` 缩放采用“手势中 transform、跨倍率阈值分段提交渲染面、结束后最高提交 `2.5x` 渲染面”；新增 `remoteCursorOverlay` 本地指针反馈；远程画面控制按钮显式 `bringToFront/elevation` | 减少全屏缩放后的长期合成位图发糊，避免 SurfaceView 层级吞掉 `1x/全屏`，并用本地反馈缓解低帧率下鼠标不跟手的观感 | Android 构建、真机安装、三端启动、全屏截图、鼠标移动回执已验证；本轮 U8 已把“手势中分段提交”改为“停手后一次提交”，双指 pinch 自动注入仍未闭环 |
| U8 | 本轮把缩放期间渲染面提交改为“手势中不改 Surface 尺寸，停手后一次性提交”，并把鼠标移动改为 `8ms / 0.00075` + 历史触摸点 | 减少全屏双指缩放卡顿来源；减少真机慢拖时轨迹断点 | Android 构建通过，真机三端启动通过，全屏滑动回执通过；真实双指缩放手感仍需人工验收 |
| U9 | 新增 `session.viewport.interaction`，并把 Android 真机交互临时采集档调到 `800x520@30fps`，停手后恢复 `1547x1000` 清晰档 | 让鼠标移动、滚轮和后续真实 pinch/pan 都能触发低延迟采集；移动中优先流畅，停手后优先清晰 | relay 单测覆盖合法转发和非法 phase 拒绝；真机长滑动验证交互档 `31.25fps`；adb pinch 仍未触发该消息，真实双指手感未闭环 |
| U10 | 本轮限制全屏缩放停手后的真实承载面到 `8,000,000` 像素、pinch 停手 `220ms` 恢复清晰档、pan 停手 `450ms` 恢复，并把 CoreGraphics fallback 缩放改为双线性 | 避免 4x 全屏创建超大 Surface 拖慢合成；pinch 停手后更快恢复清晰源；fallback 路径缩小桌面文字时减少锯齿 | 工程验证通过，真机全屏和鼠标移动链路通过；真实双指 pinch 未闭环，清晰档长期 FPS/Android 渲染退化仍未解决 |
| U11 | 全屏内容层重置延后到布局完成后一帧；鼠标移动采样收敛到 `16ms / 0.001` 并继续保留尾帧；全屏保持 WebRTC 硬件缩放器开启 | 修复全屏容器变大后内容层仍用旧尺寸；减少高频 move 对 Android 主线程/日志/信令的压力；避免固定 Surface 尺寸继续拉低全屏渲染 | Android 构建、Desktop 构建、`git diff --check`、真机安装、三端启动、全屏 UI tree 和 4 秒滑动已验证；Android 全屏 FPS 仍未达标 |
| U12 | Relay 转发 `screen.frame.push`，Desktop Android 真机临时启用 JPEG-only 帧流，帧间隔 `100ms` | 恢复 H.264 零帧时的可见画面，并避免 Mac 继续执行对 Redmi 无效的 H.264 native sender 编码 | Go server 单测通过；Desktop build 通过；真机三端启动后小窗/全屏画面可见，legacy 约 `9.3fps`，全屏 mouse move `133/133` applied；WebRTC H.264 和 pinch 仍未闭环 |
| U13 | Android 过时 JPEG 解码丢弃、本地光标动画帧合并、Desktop JPEG 档位/质量分层 | 降低 1000p JPEG 解码积压和 move UI 热路径压力；移动中用更轻的 `640x416`，全屏空闲用 `960x624`，缩放停手后短时 `1280x800` 高质量 | Android/桌面/Rust 构建测试通过；真机 `sess-1783207811491-1` 证明交互档提升到最高 `13.40fps`，但全屏和清晰档仍未达标；局部高清需真实 pinch 人工验收 |
| U14 | 当前补证后的设计判断 | 已记录 | `gfxinfo` 显示 UI jank 低，长滑动 `231/231` applied，视觉 FPS 仍只有交互档约 `13fps`、全屏档约 `8fps` | 下一步应转向媒体架构：WebRTC codec/硬解能力矩阵、macOS 硬件编码、区域高清采集；不应继续只靠降低 JPEG 全屏档位或提高鼠标消息频率 |
| U15 | 局部高清帧避免二次放大和保持窗口延长 | 已验证但未完全闭环 | Android 端把非整屏 `source_rect` 帧视为已物化的当前可视区域，渲染和触摸映射不再叠加旧本地倍率；Desktop 将局部高清保持时间从 `1800ms` 调到 `5000ms`。截图 `/tmp/remotedesk-during-materialized-source-debug.png` 显示局部帧完整铺在远控窗口内 | 改善“缩放后发虚/刚看清就恢复”的问题；但真实双指操作未人工验收，且 5 秒后仍会恢复到全屏 JPEG 档 |
| U16 | Android legacy JPEG 单次解码 | 已验证但未完全闭环 | `decodeFrameBitmap` 去掉每帧 bounds 预解码，单次 decode 后复核真实尺寸；真机 10:08 交互档 `512x331` 提升到 `14.70/15.66fps`，局部高清 `1028x599` 提升到 `7.16/7.54fps` | 这是热路径减负，不是最终媒体架构；继续靠 JPEG/Base64/Bitmap 仍达不到 `>=24fps` |
| U17 | 全屏 TextureView 默认启用、1x 承载面和回执/预览节流 | 已验证但未完全闭环 | Android 全屏基础承载面改为 `1.0x`，`REMOTE_FULLSCREEN_USE_TEXTURE_RENDERER=true`，缩放停手高清提交 `240ms`；Desktop 对成功 `input.mouse.move` 回执做 `120ms` 节流，本地 `sendFrame()` 后 UI `render()` 做 `500ms` 节流。10:21-10:25 真机证明全屏完整、TextureView 切换、输入 177 条落地和回执降压生效 | 改善输入落地和避免全屏本地二次放大，但视觉 FPS 仍未过线；下一步必须换媒体链路/codec 策略或设备能力分层 |
| U18 | Android legacy Bitmap 复用池 | 已验证但未完全闭环 | `decodeFrameBitmap` 优先 `Base64.NO_WRAP` 解码，并为已退场 legacy 帧建立最多 2 个 Bitmap 的复用池；`inBitmap` 不兼容时记录 `legacy_bitmap_reuse_failed` 并无复用重试。13:46 真机复测未出现复用失败日志，全屏完整、输入和局部高清链路均未回归 | 降低降级帧热路径内存分配和 GC 风险；视觉 FPS 仍未达标，不能替代 WebRTC/H.264、硬编码或更高效降级媒体通道 |
| U19 | Pinch 期间低成本绘制和 Mac worker 采样 | 已验证但未完全闭环 | Android pinch 开始关闭整屏高质量 Bitmap 滤波，停手后按 `source_rect` 恢复；Mac/Rust worker 增加采样日志，完整帧无裁剪且尺寸不变时跳过多余 BGRA 拷贝。19:45 真机复测证明全屏完整、输入和 source_rect 未回归，并量化 worker overrun | 本轮不是最终流畅性修复，而是把瓶颈证据收敛到 JPEG fallback 媒体链路；下一步要推进 WebRTC/H.264/硬编解码或更高效降级通道 |
| U20 | MotionEvent 历史点补偿和 pinch 帧干扰降低 | 已验证但未完全闭环 | Android 鼠标 move 发送最多补最近 `4` 个历史触摸点，按真实事件时间入队；pinch 期间整屏预览帧间隔调到 `2200ms`。21:11 真机复测证明全屏完整、输入 `157` 条落到 Mac、source_rect 未回归，Android UI/gfxinfo jank 很低 | 该优化改善输入轨迹连续性和手势期间本地干扰，但没有突破 JPEG fallback 源帧吞吐；真实双指手感和 `>=24fps` 视觉目标仍需最终媒体链路解决 |
| U21 | manual pinch fallback、12ms 鼠标采样和局部静止清晰档 | 已验证但未完全闭环 | Android 在 `ScaleGestureDetector` 未进入有效缩放时用双指 span 手动计算倍率；鼠标 move 为 `12ms / 0.00032`、历史点最多 `4`、尾帧最大 `12ms`、本地光标预测 `18ms`。Desktop 当前档位为基础 `800x520@30fps`、全屏 `640x416@24fps`、pinch 预览 `320x208@8fps`、局部移动 `512x333@22fps`、局部高清 `896x582@10fps`、局部静止清晰 `1280x832@5fps` | 00:15 真机证明 debug/manual pinch 能触发局部 source_rect，缩放后 mouse move 接近 `60Hz` 落地；但局部视觉 FPS 仍约 `4-8fps`，真实手指手感和最终媒体链路仍未闭环 |

## 2026-07-06 00:15 全屏 manual pinch fallback、局部清晰和鼠标移动复测

验证目的：继续处理“全屏双指缩放不流畅、缩放后画面不够清晰、鼠标移动不流畅”，并区分手势输入链路、局部清晰源链路和 `legacy_jpeg` 可见媒体吞吐。

本轮代码改动：

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 鼠标移动合并为 `12ms / 0.00032`，最多消费最近 `4` 个历史触摸点，尾帧最大延迟 `12ms`。
  - 本地光标预测为 `18ms`，缩放倍率提示刷新节流为 `220ms`。
  - 新增 manual span fallback：当 `ScaleGestureDetector` 没有稳定进入缩放时，直接按两指距离变化计算倍率，并记录 `remote_manual_pinch_begin/end` 与 `remote_viewport_pinch_scale source=manual`。
- `apps/desktop/src/main.jsx`
  - Android 真机可见链路档位为基础 `800x520@30fps`、全屏 `640x416@24fps`、pinch 预览 `320x208@8fps`、局部移动 `512x333@22fps`、局部高清 `896x582@10fps`、局部静止清晰 `1280x832@5fps`。
  - pinch 松手后先进入较轻的局部预览，再延迟进入 detail/still，减少松手瞬间大 JPEG 解码抢占。
- `apps/desktop/src-tauri/src/platform/capture.rs`
  - 裁剪 JPEG 锐利质量阈值提高到 `1,120,000` 像素，`1280x832` 局部静止帧继续走锐利质量，更大的 `1440x900` 走平衡质量。

已验证事实：

- [x] `git diff --check` 通过。
- [x] `cd apps/server && go test ./...` 通过。
- [x] `bash -n scripts/triad_ctl.sh` 通过。
- [x] `cd apps/desktop/src-tauri && cargo fmt --check && cargo test` 通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain` 通过。
- [x] APK 已安装真机 `wsvwypiz7xwslvl7`，SHA256 `d291a85bfc6cf0d6ac0383549ec8b75b6f2092775025d4b5f9bef4624a55115b`。
- [x] 三端会话 `sess-1783267574091-1` 建立，Android controller `android-6b04e47d7cccda58`，Mac agent `agent-19de3117874`。
- [x] 标准 proof：`session_e2e_proof_status=video_and_input_observed`，`first_frame_ms=31`，`media=legacy_jpeg`，`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`，执行器 `macos.cg_event`。
- [x] 全屏渲染路径进入 `TextureView`：`remote_video_renderer_switch mode=texture fullscreen=true`。
- [x] debug 双指注入通过 manual span fallback 触发缩放：`remote_manual_pinch_begin`、多条 `remote_viewport_pinch_scale source=manual`、`remote_manual_pinch_end`，倍率约 `1.185 -> 2.437`。
- [x] Mac 局部区域切档：先 `320x208@8fps full`，再 `512x333@22fps source_rect_ppm=294671,306641,410657,410305`，随后 `896x582@10fps` 与 `1280x832` 静止清晰档。
- [x] Android 已物化局部区域：`remote_viewport_source_rect_materialized rect=0.29,0.31,0.41,0.41`，legacy 局部帧约 `896x578 source_rect=0.29,0.31,0.41,0.41`，后续静止清晰约 `1142x737 source_rect=0.29,0.31,0.41,0.41`。
- [x] 缩放后 3 秒横向滑动，Mac `input.mouse.move applied=true` 从 `3` 增到 `182`，新增约 `179` 条，接近 `60Hz`。
- [x] 产物已保留：`.rd_runtime/screens/rd_fullscreen_pinch_manual_20260706_000711.png`、`.rd_runtime/screens/gfxinfo_fullscreen_pinch_manual_20260706_000711.txt`、`.rd_runtime/screens/gfxinfo_fullscreen_pinch_manual_20260706_000711_framestats.txt`、`.rd_runtime/screens/rd_after_mouse_swipe_manual_zoom_20260706_000812.png`、`.rd_runtime/screens/gfxinfo_after_mouse_swipe_manual_zoom_20260706_000812.txt`、`.rd_runtime/screens/gfxinfo_after_mouse_swipe_manual_zoom_20260706_000812_framestats.txt`。

未闭环事实：

- [ ] 可见媒体仍是 `legacy_jpeg`，不是最终 WebRTC/H.264 或硬编码媒体链路。
- [ ] 局部 detail 首样本约 `7.95fps @896x578`，静止清晰约 `4-5fps @1142x737`，仍未达到 `>=24fps`。
- [ ] debug 双指注入和 manual span fallback 证明代码路径可用，但不能替代真实手指双指 pinch 手感验收。
- [ ] 普通 `adb input` 多点仍不可用；并发 `swipe` 不能作为双指 pinch 证据。

下一步任务：

- [ ] P0：推进最终媒体链路或更高效降级媒体通道，不能继续把 `legacy_jpeg` 当作最终流畅方案。
- [ ] P1：把 `source_rect` 局部清晰策略接入 WebRTC/硬编码路径，保留 Android 坐标反算与 full-frame freeze 保护。
- [ ] P2：补 instrumentation 级多点触控或人工真机手感验收，覆盖真实双指开合、双指平移、缩放后单指移动鼠标。
- [ ] P3：在局部清晰状态下补点击输入框和电脑键盘映射验证，确保 source_rect 坐标映射不影响输入框命中。

## 2026-07-05 07:05 legacy JPEG 兜底恢复记录

验证目的：在 Redmi Note 8 Pro 真机上，WebRTC H.264 仍无首帧时，先恢复远程画面可见性，并验证全屏显示和鼠标移动不会回归。

本轮代码改动：

- `apps/server/internal/transport/ws.go`
  - 将 `screen.frame.push` 纳入会话内消息转发，并增加 `frame_id/content_b64/frame_width/frame_height/mime_type` 边界校验。
- `apps/server/internal/transport/ws_test.go`
  - 增加 `screen.frame.push` 正常转发和非法 MIME 拒绝测试。
- `packages/protocol/schema/messages.json`
  - 记录 `screen.frame.push` 协议字段和约束。
- `apps/desktop/src/main.jsx`
  - Android 真机 profile 临时启用 JPEG 帧流专用路径，不启动无首帧的 H.264 native sender。
  - 兜底帧间隔从 `1200ms` 经 `250ms` 调整到 `100ms`。

已验证事实：

- [x] `cd apps/server && go test ./...` 通过。
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain` 通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] 真机覆盖安装成功：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`。
- [x] 三端启动成功：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto ./scripts/triad_ctl.sh restart`。
- [x] 当前会话：`sess-1783206072021-1`。
- [x] Android legacy 首帧：`legacy_first_frame ... size=1114x720`。
- [x] 小窗 legacy 显示：`legacy_frame_sample` 稳定约 `9.19-9.60fps size=1547x1000`。
- [x] 全屏完整显示：`/tmp/remotedesk-fullscreen-legacy-only-100ms.png`，SHA256 `329cab288f67a5776352a85bf8f2d5876ce48fc14d81cdfbb7db508341021d21`，可见 macOS 桌面完整，左右黑边为比例适配。
- [x] 全屏鼠标移动：Android `input.mouse.move` 发送 `133` 条，Mac `applied=true` `133` 条。

未闭环事实：

- [ ] WebRTC H.264 仍未修复；本轮可见画面来自 JPEG fallback。
- [ ] 9fps 只能算“可操作”，不能算流畅达标。
- [ ] adb 并发 pinch 仍未触发 `remote_viewport_pinch_scale`；截图 `/tmp/remotedesk-pinch-attempt-legacy-only.png` 仍为 `1x`。

## 2026-07-05 03:10 全屏缩放与鼠标移动继续调整记录

验证目的：继续处理“全屏双指缩放不流畅、缩放后画面不够清晰、鼠标移动不流畅”，并区分 Android 手势层优化和 Mac sender 帧率瓶颈。

本轮代码改动：

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 双指缩放过程中不再分段修改 `remoteViewportContent` 布局尺寸，避免连续捏合时触发 Surface 重新布局；缩放结束后再提交最高 `2.5x` 的渲染面。
  - `REMOTE_PINCH_FOCUS_SMOOTHING` 调到 `0.86`，让焦点更贴近手势中心，减少拖影感。
  - 鼠标移动采样从 `16ms / 0.0015` 调到 `8ms / 0.00075`，并处理 `MotionEvent` 历史点；长按拖拽和普通单指移动都走同一批量轨迹发送逻辑。

已验证事实：

- [x] Android debug 构建通过：`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`
- [x] Desktop 前端构建通过：`cd apps/desktop && npm run build`
- [x] Go relay 测试通过：`cd apps/server && go test ./...`
- [x] Tauri Rust 测试通过：`cd apps/desktop/src-tauri && cargo test`，28 个测试通过。
- [x] `git diff --check` 通过。
- [x] 真机覆盖安装成功：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`
- [x] 三端启动成功：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto ./scripts/triad_ctl.sh restart`
- [x] 当前会话：`sess-1783192048371-1`。
- [x] Android 首帧：`first_rendered_frame ... size=1546x1000 since_track_ms=8639`。
- [x] E2E proof：relay 汇总 `session_e2e_proof_status=video_and_input_observed`，覆盖 `click,drag,keyboard,wheel`，最后执行器 `macos.cg_event`。
- [x] 全屏 UI tree：`remoteViewportContainer bounds=[424,33][1992,1047]`，`1x`、`退出全屏`、`键盘` 可见。
- [x] 全屏鼠标移动：03:09:33 期间连续发送 `input.mouse.move`，回执 `applied/macos.cg_event`，坐标从约 `(0.59,0.58)` 推进到 `(0.72,0.61)`。
- [x] 全屏截图：`/tmp/remotedesk-fullscreen-smooth-after.png`。

未闭环事实：

- [ ] adb 并发双指 swipe 仍未触发 `remote_viewport_pinch_scale`，不能证明真实双指缩放手感已经通过。
- [ ] 画面帧率仍未达标：Android `render_frame_sample` 约 `14.1-14.2fps`，`controller_quality_hint=render_fps_streak`；Mac sender `encode_ms_avg` 仍约 `58ms`。
- [ ] 缩放后“更清晰”的上限受源视频 `1546x1000` 和软件编码帧率限制；本轮只保证停手后提交更大的本地渲染面，不能替代更高质量的 Mac sender。

下一步：

- [ ] 用人工真机两指操作验证 pinch 手感、焦点跟随、停手后清晰度。
- [ ] 设计 instrumentation 多点触控测试，替代不可靠的 adb 并发 swipe。
- [ ] 继续处理 macOS sender：优先硬件编码或 BGRA -> YUV 转换优化，目标先把 1000p 档恢复到 `>=24fps`。

## 2026-07-05 全屏缩放清晰度和鼠标反馈复测

验证目的：继续处理全屏双指缩放不流畅、缩放后不够清晰、鼠标移动不流畅的问题，并把可自动验证和不能自动验证的边界写清楚。

本轮代码改动：

- Android `MainActivity.kt`
  - 缩放过程中继续使用 View transform 保持手势跟随；缩放过程中跨过明显倍率阈值时分段提交渲染面，缩放结束后提交最高 `2.5x` 的远程视频渲染面，减少放大后一直拉伸同一层合成结果。
  - 新增 `remoteCursorOverlay` 本地指针反馈层，单指移动/拖拽时即时显示蓝色圆环。
  - `1x`、全屏和键盘按钮在初始化时显式 `bringToFront` 并设置 elevation，修复真机上顶部按钮被视频层级吃掉的问题。
- Android `activity_main.xml`
  - 新增 `remoteCursorOverlay`。
- Android `remote_cursor_overlay.xml`
  - 新增蓝色圆环样式，用作控制端本地指针反馈。
- Relay `ws.go`
  - 修复已有半成品里 `math.IsFinite` 造成的 Go 编译失败，改为 `math.IsNaN/math.IsInf` 判断；这是三端联调的启动阻断点。

已验证事实：

- [x] Android debug 构建通过：`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`
- [x] Desktop 前端构建通过：`cd apps/desktop && npm run build`
- [x] Go relay 测试通过：`cd apps/server && go test ./...`
- [x] `git diff --check` 通过
- [x] 真机覆盖安装成功：`adb -s wsvwypiz7xwslvl7 install -r app/build/outputs/apk/debug/app-debug.apk`
- [x] 三端启动成功：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto ./scripts/triad_ctl.sh restart`
- [x] Android 首帧恢复：`first_rendered_frame session=sess-1783188884722-1 size=1546x1000`
- [x] 全屏完整显示：`/tmp/remotedesk-fullscreen-buttons-fix.png`
- [x] 全屏控件可见：UI tree 记录 `1x`、`退出全屏`、`键盘`，并有 `remoteViewportContainer bounds=[424,33][1992,1047]`
- [x] 全屏鼠标移动输入链路可用：滑动后连续 `input.mouse.move`，回执为 `applied/macos.cg_event`
- [x] 本地指针反馈可见：`/tmp/remotedesk-fullscreen-local-cursor.png` 同时显示蓝色本地反馈点和 Mac 黑色真实光标

未闭环事实：

- [ ] 并发 `adb shell input swipe` 没有触发 `remote_viewport_pinch_scale`，因此不能把双指捏合手感写成自动验证通过。
- [ ] 1000p 清晰档下 Android `render_frame_sample` 仍约 `13.9-14.5fps`，Mac sender 约 `14.1-14.3fps`，低帧率仍未解决。
- [ ] 本地指针反馈只证明控制端触摸反馈改善，不证明远端视频光标已经达到 24/30fps。

下一步：

- [ ] 人工真机双指捏合全屏画面，确认倍率变化、焦点跟随和缩放后文字清晰度。
- [ ] 如果要自动化 pinch，优先做 instrumentation/测试 APK 产生真实多点事件，不再依赖 `adb input swipe`。
- [ ] 继续处理 Mac sender 14fps 瓶颈，优先评估硬件编码或 BGRA -> YUV 转换优化。
- [ ] 复跑 short reconnect 和 quick soak，把本轮 UI/反馈改动纳入正式报告。

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

## 2026-07-05 真机全屏、缩放清晰度和鼠标流畅度调整记录

验证目的：针对全屏双指缩放不流畅、缩放后画面发糊、鼠标移动不流畅继续调整，并在真机上验证可自动证明的链路事实。

本轮代码改动：

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 鼠标移动采样从 `33ms / 0.004` 调整为 `16ms / 0.0015`，目标是让单指慢拖和小幅移动不再跳格。
  - 双指缩放焦点平滑系数从 `0.35` 提高到 `0.55`，减少全屏缩放时焦点跟手延迟。
  - `SurfaceViewRenderer` 恢复普通 surface 层级，避免 overlay surface 影响控制层和真机截图判断。
  - Activity 增加 `FLAG_KEEP_SCREEN_ON`，避免真机测试和实际远控中途息屏。
- `apps/desktop/src/main.jsx`
  - Android 真机媒体档位从 720p 均衡档提升到清晰档，并用手机上限收敛到 `1600x1000@30fps / 10Mbps`。
  - 保持 `scaleResolutionDownBy=1.0`，避免再次通过 WebRTC 发送端缩放降低清晰度。
- `apps/desktop/src-tauri/src/native_sender/mod.rs`
  - 同步修正过时注释，说明当前瓶颈是软件 H.264 编码耗时，而不是单纯 720p 策略。

验证环境：

- Mac agent：`agent-19de3117874`
- Android 控制端：Redmi Note 8 Pro，serial `wsvwypiz7xwslvl7`
- Relay：`127.0.0.1:18081`
- TURN：`3478`
- 当前会话：`sess-1783187239007-1`
- 截图：`/tmp/remotedesk-awake.png`、`/tmp/remotedesk-fullscreen-1000p-2.png`

已验证事实：

- [x] Android debug 构建通过：`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`
- [x] Desktop 前端构建通过：`cd apps/desktop && npm run build`
- [x] 真机覆盖安装成功：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`
- [x] 三端启动成功：`./scripts/triad_ctl.sh restart`，Mac agent 与 Android controller 均在线
- [x] Android -> Mac 首帧恢复：`first_rendered_frame session=sess-1783187239007-1 size=1546x1000`
- [x] 桌面端媒体配置生效：Mac 日志 `config.updated max_width=1600 max_height=1000 max_fps=30`，`probe.first_frame ... size=1547x1000`
- [x] 全屏显示完整远程桌面：截图 `/tmp/remotedesk-fullscreen-1000p-2.png` 显示 Mac 菜单栏、Dock、右侧桌面文件均可见；左右黑边来自按比例适配，不是裁剪
- [x] 输入链路仍可用：界面和 relay 指标显示 `input.wheel.scroll [applied/macos.cg_event]`，本轮 proof 覆盖 `click,drag,keyboard,wheel`
- [x] 黑屏截图原因已定位：此前 `/tmp/remotedesk-quality-1000p.png` 全黑是因为真机 `mWakefulness=Asleep` 且 UI dump 为 SystemUI 锁屏；唤醒后 `/tmp/remotedesk-awake.png` 正常显示 RemoteDesk UI 和远程画面

未闭环事实：

- [ ] 1000p 下软件 H.264 编码仍是流畅度瓶颈：Android `render_frame_sample` 约 `14.2-14.4fps`，Mac sender `encode_ms_avg=57-59ms`，未达到 24fps/30fps 级流畅画面。
- [ ] 1080p 试验能提升清晰度但帧率更低：实际帧 `1670x1080`，Android render 约 `12.5fps`，不能作为当前默认档位。
- [ ] 双指缩放“手感流畅”仍需人工真机确认；adb 截图和日志能证明画面、分辨率、帧率，不能可靠替代真实两指捏合手感。
- [ ] 鼠标移动输入采样已提高到 60fps 级别，但视频画面里的 Mac cursor 仍受 sender 14fps 左右限制；要彻底顺滑需要后续引入更快编码路径或本地 cursor overlay。

下一步优先级：

- [ ] P0：人工真机两指捏合全屏画面，记录倍率是否平滑变化、焦点是否跟手、缩放后文字是否比旧 720p 清晰。
- [ ] P1：继续优化视频帧率。候选方向：macOS 硬件编码、减少 BGRA -> YUV 转换成本、动态场景临时降分辨率、Android 端本地 cursor overlay。
- [ ] P2：为远控 Activity 的亮屏策略补充复测记录，确认长时间真机测试不再进入锁屏。
- [ ] P3：复跑短断和 quick soak，把 1000p 档下的 `first_frame_ms`、`render_fps_avg`、`visible_frame_gap_ms_max` 和输入覆盖写入报告。
- [ ] P4：如引入本地 cursor overlay，需要明确只作为控制端即时反馈，不能替代 Mac 端真实输入执行 proof。

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

## 2026-07-05 全屏缩放、清晰度和鼠标移动继续调整

本轮目标：继续处理全屏双指缩放不流畅、缩放后不够清晰、鼠标移动不流畅，并用真机记录可验证边界。

### 本轮代码调整

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 单指移动在放大状态下仍映射为电脑鼠标移动，局部画面平移改为“缩放后双指同向拖动”。这样放大后不会丢失最核心的鼠标控制能力。
  - 双指缩放焦点响应系数调整为 `0.72`；缩放过程中跨过明显倍率阈值时分段提交高清渲染面，缩放结束后的渲染面提交上限为 `2.5x`。
  - 保留 `16ms / 0.0015` 的鼠标移动采样，并补充尾帧发送，避免用户停手时最后一个坐标被节流丢掉。
- `apps/desktop/src-tauri/src/native_sender/mod.rs`
  - OpenH264 仍保持低复杂度、Baseline、4 线程，目标码率提高到 `8Mbps`，日志新增 `bitrate_bps=8000000`。该改动只解决压缩太狠导致的放大糊，不能解决软件编码每帧耗时过高。
- `apps/desktop/src/main.jsx`
  - Android 真机媒体上限同步为 `1600x1000@30fps / 10Mbps / scaleResolutionDownBy=1.0`，保持清晰档但不继续上探 1080p。
- `docs/android_macos_remote_control_system_design.md`
  - 已更新为系统设计与需求文档：明确需求、架构、Android 手势模型、macOS sender、质量策略、自动/人工验证清单和已知风险。

### 本轮已验证

- [x] Android debug 构建通过：`cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`
- [x] Desktop 前端构建通过：`cd apps/desktop && npm run build`
- [x] Tauri Rust 测试通过：`cd apps/desktop/src-tauri && cargo test`，28 个测试通过。
- [x] Server Go 测试通过：`cd apps/server && go test ./...`
- [x] `git diff --check` 通过。
- [x] 真机安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`
- [x] 三端启动通过：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto ./scripts/triad_ctl.sh restart`
- [x] Mac sender 新码率参数生效：`.rd_runtime/logs/mac.log` 出现 `encoder.ready ... bitrate_bps=8000000`
- [x] 小窗画面可见：截图 `/tmp/remotedesk-after-start.png`，当前画面 `webrtc 1546x1000`
- [x] 全屏进入成功：截图 `/tmp/remotedesk-fullscreen-after-adjust.png` 显示横屏全屏状态。
- [x] 全屏完整显示：截图 `/tmp/remotedesk-fullscreen-after-adjust.png` 显示远端桌面按比例完整放入横屏，左右黑边为比例适配，不是裁切。
- [x] 全屏鼠标移动 proof：全屏滑动后 Android 连续发送 `input.mouse.move`，坐标从约 `(0.49, 0.67)` 推进到 `(0.65, 0.72)`，Mac 执行为 `applied/macos.cg_event`；截图 `/tmp/remotedesk-fullscreen-swipe-after-adjust.png` 显示 Mac 黑色真实光标位置已更新。
- [x] 本轮标准视频和输入 proof 通过：relay 汇总 `session_e2e_proof_status=video_and_input_observed`、`remote_input_applied=11/11`、`remote_input_coverage=click,drag,keyboard,wheel`，当前会话 `sess-1783190775453-1`。

### 本轮未通过/未闭环

- [ ] 1000p 软件编码瓶颈仍存在：Mac `probe.sample` 仍约 `14fps`，`encode_ms_avg` 约 `57-61ms`；提高码率不能解决帧率。
- [ ] 自动双指 pinch 仍未闭环：并发 `adb shell input swipe` 没有触发 `remote_viewport_pinch_scale`，截图 `/tmp/remotedesk-pinch-attempt-after-adjust.png` 不能证明真实双指手感。
- [ ] 本地蓝色指针是控制端即时反馈策略；本轮截图主要证明 Mac 黑色真实光标更新，真实视频光标仍受 14fps sender 限制。

### 下一步任务

- [ ] P0：不要再只调 H.264 码率；优先处理 OpenH264 软件编码 `57-59ms/frame`，方向是 macOS 硬件编码或更低成本 BGRA -> YUV。
- [ ] P1：人工真机验证双指缩放手感、缩放后双指平移、缩放后单指仍移动电脑鼠标。
- [ ] P2：如需自动化 pinch，新增 instrumentation 多点事件测试，不再依赖 `adb input swipe` 并发模拟。
- [ ] P3：修复帧率后复跑 `short_reconnect_check.sh`，目标 `overall=PASS` 且质量窗口至少 `>=24fps` 级别。
- [ ] P4：短断 PASS 后再复跑 quick soak，更新 `render_fps_avg`、`visible_frame_gap_ms_max`、输入覆盖和阶段 gap。

## 2026-07-05 04:07 全屏缩放/清晰度/鼠标移动复测记录

验证目的：在上一轮交互档基础上继续处理“全屏双指缩放不流畅、缩放后不够清晰、鼠标移动不流畅”，并明确哪些已经由真机证明，哪些仍未闭环。

本轮代码改动：

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - `REMOTE_VIEWPORT_MAX_RENDER_PIXELS = 8_000_000`，缩放停手后提交真实视频承载面时限制像素上限，避免全屏 `4x` 直接创建超大 Surface。
  - `coerceRemoteViewportRenderScale()` 根据全屏容器大小限制真实承载面倍率，剩余倍率交给 View transform，降低真机合成压力。
- `apps/desktop/src/main.jsx`
  - 新增 `NATIVE_SENDER_PINCH_RESTORE_DELAY_MS = 220` 和 `NATIVE_SENDER_PAN_RESTORE_DELAY_MS = 450`。
  - `session.viewport.interaction` 结束时按交互类型选择恢复窗口：pinch 停手优先快速恢复清晰档，pan 保留较短缓冲，普通鼠标移动仍保留较长交互窗口。
- `apps/desktop/src-tauri/src/platform/capture.rs`
  - macOS CoreGraphics fallback 的 BGRA 缩放从最近邻改为双线性，新增单元测试覆盖同尺寸保持和 2x2 -> 1x1 混合。

已验证事实：

- [x] Android debug 构建通过：`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`
- [x] Desktop 前端构建通过：`cd apps/desktop && npm run build`
- [x] Go relay 测试通过：`cd apps/server && go test ./...`
- [x] Tauri Rust 测试通过：`cd apps/desktop/src-tauri && cargo test`，30 个测试通过。
- [x] `git diff --check` 通过。
- [x] 真机在线：`wsvwypiz7xwslvl7`
- [x] 真机安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`
- [x] 三端启动通过：Mac agent `agent-19de3117874`、relay `127.0.0.1:18081`、Android controller online。
- [x] 当前会话：`sess-1783195348278-1`。
- [x] 标准 E2E proof 通过：relay 汇总 `session_e2e_proof_status=video_and_input_observed`，`remote_input_applied=11/11`，覆盖 `click,drag,keyboard,wheel`。
- [x] 小窗截图：`/tmp/remotedesk-after-scale-smooth-current.png`，当前画面 `webrtc 1546x1000`。
- [x] 全屏进入通过：真实按钮 bounds 为 `[847,1174][969,1262]`，点击中心 `(908,1218)` 后日志出现 `远端画面已进入全屏`。
- [x] 全屏 UI tree 通过：`remoteViewportContainer [424,33][1992,1047]`，`1x`、`退出全屏`、`键盘` 可见。
- [x] 全屏截图：`/tmp/remotedesk-after-scale-smooth-fullscreen-actual.png`，远端桌面按比例显示，黑边为适配，不是裁切。
- [x] 全屏长滑动：`adb shell input swipe 700 540 1700 650 2500` 命中远程画面；Android 在本次窗口发送 `input.mouse.move` 122 条。
- [x] Mac 输入落地：本次全屏滑动窗口 Mac 侧 `input.mouse.move applied=true` 126 条，执行器 `macos.cg_event`。
- [x] 交互档生效：本次窗口 Mac 侧出现 `config.updated max_width=800`、`encoder.ready ... frame=800x517`；交互采样 `fps=31.50`、`encode_ms_avg=15.6ms`。
- [x] 清晰档恢复：本次窗口 Mac 侧恢复 `encoder.ready ... frame=1547x1000`。
- [x] 全屏滑动截图：`/tmp/remotedesk-after-scale-smooth-fullscreen-swipe.png`，Mac 光标位置已更新。

未通过/未闭环事实：

- [ ] 清晰档仍未达标：恢复 `1547x1000` 后 Mac sender 长期约 `14fps`，`encode_ms_avg≈58ms`。
- [ ] Android 渲染侧更严重：全屏长会话后 `render_frame_sample` 从约 `9fps` 继续降到 `4-5fps`，`recent_quality_hint=render_fps_streak`，最长 recent gap 到 `355ms`。
- [ ] 双指 pinch 未自动验证：本轮没有有效触发 `remote_viewport_pinch_scale`；仍需人工真机双指或 instrumentation 多点事件。
- [ ] 本轮改动改善了“移动输入落地”和“交互档触发/恢复策略”，但没有关闭“最终流畅性”和“缩放后真实清晰度”目标。

下一步直接执行：

- [ ] 设计 controller 质量反馈驱动的 sender 降档/恢复机制：当 controller 连续上报 `render_fps_streak` 或 `frames_dropped_spike` 时，Mac sender 自动降到 `800x520` 或中间档；恢复稳定后再回清晰档。
- [ ] 继续评估硬件编码或 BGRA -> YUV 转换优化，目标把 `1547x1000` 从 `14fps` 提升到 `>=24fps`。
- [ ] 做 instrumentation 多点触控测试，验证 `remote_viewport_pinch_scale`、倍率标签、停手后清晰渲染面提交。
- [ ] 人工真机两指验证 pinch 手感和缩放后文字清晰度，把主观结论写回本文档。
- [ ] 在动态降档或编码优化后复跑 quick soak，不要只看短窗口 proof。

## 2026-07-05 剪贴板和文件传输接线记录

本轮目标：继续实现共享剪贴板和文件传输，补齐 Desktop 端会话窗口接线，并给 relay 工具消息增加明确测试。

### 本轮代码调整

- `apps/desktop/src/main.jsx`
  - `handleMessage` 已接入 `clipboard.text`、`file.transfer.start`、`file.transfer.chunk`、`file.transfer.complete`。
  - 会话结束时清空 `incomingFileTransfers`，并把剪贴板/文件状态重置为等待会话。
  - 会话工具栏“剪贴板”按钮已绑定 `sendClipboardToRemote()`。
  - 会话工具栏“文件”按钮已绑定隐藏 file input，选中文件后调用 `sendFileToRemote(file)`。
  - 工具栏展示 `state.clipboardStatus` 和 `state.fileTransferStatus`，方便确认发送/接收进度。
- `apps/server/internal/transport/ws_test.go`
  - 在完整 WebSocket 会话流程中新增 `clipboard.text` 转发和 ack 验证。
  - 新增 `file.transfer.start/chunk/complete` 转发和 ack 验证。
  - 新增非法 `file.transfer.chunk` payload 拒绝验证，期望 `error.rsp` code `4001`。
- `docs/android_macos_remote_control_system_design.md`
  - 新增“会话工具通道”章节，记录剪贴板、文件传输、大小限制、保存策略和风险边界。
- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - Android 10+ 接收文件改为通过 `MediaStore.Downloads` 保存到公开 `Downloads/RemoteDesk/`。
  - Android 接收端补齐 `512` 分块上限，和 relay/Desktop 的文件通道约束保持一致。
- `apps/android/app/src/main/AndroidManifest.xml`
  - `MainActivity` 设置 `singleTop`，让 adb/debug intent 在当前会话中稳定进入 `onNewIntent`，便于不中断会话做工具通道验收。

### 本轮已验证

- [x] Desktop 前端构建通过：`cd apps/desktop && npm run build`
- [x] Server Go 测试通过：`cd apps/server && go test ./...`
- [x] Android debug 构建通过：`cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`
- [x] Tauri Rust 测试通过：`cd apps/desktop/src-tauri && cargo test`，28 个测试通过。
- [x] `git diff --check` 通过。
- [x] 2026-07-05 05:57 真机三端启动通过：`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto RD_DESKTOP_DEBUG_ENABLE_TOOLS=1 ... ./scripts/triad_ctl.sh restart`。
- [x] Mac -> Android 剪贴板通过：Mac 端发送 `RD_MAC_CLIP_V2_20260705`；Android 日志 `debug_clipboard_received length=23 text=RD_MAC_CLIP_V2_20260705`。
- [x] Mac -> Android 文件通过：源文件 `/tmp/remotedesk-mac-to-android-v2.txt` SHA256 `45d0018209f37e2680c8e67afd30a2ef7198eea14438838c51e982cf55a0147e`；Android 接收文件 `/sdcard/Download/RemoteDesk/remotedesk-mac-to-android-v2.txt` 哈希一致。
- [x] Android -> Mac 剪贴板通过：Android debug intent 发送 `RD_ANDROID_CLIP_V2_20260705`；Mac `pbpaste` 输出同值。
- [x] Android -> Mac 文件通过：Android app-private 测试文件 `files/android-to-mac-private.txt` SHA256 `8613621ccb704702f10c66ed2eaaf6623c51f73cc03f195d34b830445146dc5a`；Mac 接收文件 `/Users/long/Downloads/RemoteDesk/android-to-mac-private.txt` 哈希一致。
- [x] relay 双向工具通道转发通过：日志中出现 agent -> android 和 android -> agent 的 `clipboard.text`、`file.transfer.start/chunk/complete` `session.tool.forwarded`。
- [x] Android 真实“发剪贴板”按钮通过：先只用 debug intent 写入 Android 本机剪贴板 `UI_ANDROID_CLIP_20260705`，再点击 UI tree 中 `remoteClipboardSendButton` `[89,2009][529,2114]`；UI 显示 `剪贴板：已发送 24 字符`，Mac `pbpaste` 输出 `UI_ANDROID_CLIP_20260705`，relay 出现 Android -> agent 的 `clipboard.text` `session.tool.forwarded`。

### 本轮未闭环

- [ ] 本轮已完成 Android“发剪贴板”真实按钮验收；仍未完成 Android“发文件”系统文件选择器选中文件后的发送验收，也未完成 Desktop 工具栏“剪贴板/文件”真实点击验收。
- [ ] Android 14 scoped storage 限制已确认：debug file path 直接读 `/sdcard/Download/...` 会 `EACCES`；真实用户文件发送必须走系统文件选择器授权，自动化验证可使用 app-private debug 文件。
- [ ] Android “发文件”按钮能打开系统 DocumentsUI picker，但本轮自动化在 picker 中遇到列表/筛选状态异常：`最近` 变为 `无任何文件`，`下载` 目录显示 `暂时无法加载内容`，shell 写入的 `/sdcard/Download/RemoteDeskProof/ui-android-file.txt` 未出现在 picker 中；需要人工手选或进一步做 picker/SAF 可测性修复。
- [ ] 当前文件传输走 WebSocket 信令分块，只适合小文件 proof；大文件、断点续传和传输速率优化仍未实现。

### 下一步任务

- [x] P0：启动三端，用真机完成 Android -> Mac 剪贴板和 Mac -> Android 剪贴板自动验收。
- [x] P1：准备小文本文件，完成 Android -> Mac 与 Mac -> Android 双向文件传输，并记录保存路径和文件哈希。
- [x] P2：如 Tauri WebView 剪贴板 API 受权限/焦点限制，优先补 Tauri command 兜底读写剪贴板。
- [x] P3：如 Desktop 文件接收下载位置不可控，补 Tauri 固定下载目录策略；当前 Mac 保存到 `/Users/long/Downloads/RemoteDesk/`。
- [ ] P4：继续真实 UI 验收：Android 系统文件选择器选文件发送；Desktop 工具栏点“剪贴板/文件”发送。
- [ ] P5：评估文件通道产品化方案：WebRTC DataChannel 或 HTTP 分片通道、进度/取消、断点续传、文件大小策略。

## 2026-07-05 04:24 全屏交互档和 Android 渲染瓶颈复测

本轮目标：继续调整全屏双指缩放不流畅、缩放后清晰度不足、鼠标移动不流畅，并输出系统设计和需求文档。

### 本轮代码调整

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 全屏双指缩放开始时，如果上一轮已经提交高清承载面，先退回 `1.25x` 轻量承载面；停手后延迟 `180ms` 再提交高清承载面。
  - 停手后高清承载面像素上限从 `8,000,000` 提高到 `12,000,000`，改善缩放后局部清晰度上限。
  - 高频 `input.mouse.move` ack/result UI 更新改为 `650ms` 节流汇总，移动 result 不再每条强制触发 live metrics，避免鼠标轨迹越密越挤压 Android 主线程。
- `apps/desktop/src/main.jsx`
  - Android 真机交互档从 `800x520@30fps` 下调为 `640x416@30fps`，移动中优先流畅。
  - 普通交互恢复窗口从 `1200ms` 增加到 `2600ms`，减少长滑动后立刻回重清晰档。
  - 双指 pinch 结束且倍率 `>=1.15x` 时短时切到 `1920x1080@30fps / 12Mbps` 局部高清档，再恢复常规清晰档。
- `docs/android_macos_remote_control_requirements.md`
  - 新增完整需求文档，记录目标、手势模型、质量策略、验收标准和下一步优先级。

### 本轮已验证

- [x] Android debug 构建通过：`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`
- [x] Desktop 前端构建通过：`cd apps/desktop && npm run build`
- [x] `git diff --check` 通过。
- [x] 真机三端启动通过：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto ./scripts/triad_ctl.sh restart`
- [x] 标准 E2E proof 仍通过：`remote_input_applied=11/11`，覆盖 `click,drag,keyboard,wheel`。
- [x] 全屏显示完整：截图 `/tmp/remotedesk-20260705-fullscreen-after-current.png`。
- [x] 全屏长滑动输入链路通过：最新会话 `sess-1783196612267-1` 中 Android 发送 `input.mouse.move` 170 条，Mac `applied=true` 170 条，执行器 `macos.cg_event`。
- [x] Mac 交互档生效：`640x414` 下 `probe.sample fps=30.63`、`encode_ms_avg=10.2ms`。

### 本轮未通过/未闭环

- [ ] Android 全屏渲染仍未达标：长滑动后 `render_frame_sample` 只有 `7.57-9.88fps`，仍为 `render_fps_streak`。
- [ ] 常规清晰档仍未达标：`1547x1000` 恢复后 Mac sender 仍约 `14fps`、`encode_ms_avg≈58ms`。
- [ ] 双指 pinch 未闭环：仍缺人工真机或 instrumentation 证据证明 `remote_viewport_pinch_scale`、焦点跟随和停手高清提交。

### 下一步任务

- [ ] P0：攻 Android 全屏渲染路径，评估 `SurfaceViewRenderer` -> `TextureViewRenderer` 或自定义渲染。
- [ ] P1：让 controller 的 `render_fps_streak` 质量反馈驱动 Mac sender 自动保持低档或中间档，避免 Android 渲染已经掉帧时恢复重清晰档。
- [ ] P2：继续评估 macOS 硬件编码或 BGRA -> YUV 优化，解决 `1547x1000` 14fps。
- [ ] P3：补 instrumentation 多点触控测试，关闭双指 pinch 自动验收缺口。

## 2026-07-05 04:44 全屏基础档复测

本轮目标：继续验证全屏双指缩放和鼠标移动流畅性。因 adb 仍不能可靠注入真实双指 pinch，本轮重点验证全屏画面承载、Mac sender 档位切换、Android 全屏渲染 FPS 和鼠标移动落地。

### 本轮代码调整

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - pinch 焦点滤波调整为 `0.78`，缩放微小变化阈值调整为 `0.0025`，减少全屏两指抖动带来的细碎重绘。
  - 缩放停手后的 Android 真实视频承载面上限收紧到 `2,600,000` 像素，避免本地为了“高清承载面”把 Surface 撑到过大。
  - 进入/退出全屏时发送 `session.viewport.interaction`，`interaction=fullscreen`。
- `apps/desktop/src/main.jsx`
  - Android 真机交互低延迟档调整为 `800x520@30fps`。
  - 新增 Android 真机全屏基础档 `1120x728@30fps`，进入全屏后保持该档，鼠标移动时临时切到低延迟档，停手后回到全屏基础档。
- `apps/server/internal/transport/ws.go`、`apps/server/internal/transport/ws_test.go`、`packages/protocol/schema/messages.json`
  - `session.viewport.interaction` 新增允许值 `fullscreen`，并补 relay 转发测试。

### 本轮已验证

- [x] Android debug 构建通过。
- [x] Desktop 前端构建通过。
- [x] Server Go 测试通过。
- [x] `git diff --check` 通过。
- [x] 真机安装和三端启动通过：`wsvwypiz7xwslvl7`、Mac agent `agent-19de3117874`、relay `127.0.0.1:18081`。
- [x] `fullscreen` hint 被 relay 转发：relay 日志出现 `session.tool.forwarded ... type=session.viewport.interaction`。
- [x] Mac 全屏基础档生效：`config.updated max_width=1120 max_height=728`，`encoder.ready ... frame=1120x724`，后续 `probe.sample fps=23.89/23.73`。
- [x] 全屏长滑动期间 Mac 低延迟档生效：`frame=800x517`，`probe.sample fps=31.68/31.41`。
- [x] 全屏鼠标移动输入落地：Android `input.mouse.move` 发送 `275` 条，Mac `input_result ... applied=true` 统计 `277` 条。

### 本轮未通过/未闭环

- [ ] Android 全屏渲染仍未达标：全屏基础档 `1120x724` 下 Android `render_frame_sample` 只有 `7.84-10.44fps`；长滑动 `800x516` 下仍只有 `6.62-10.02fps`，持续 `render_fps_streak`。
- [ ] 关闭全屏硬件缩放器没有解决问题：同轮复测仍低于 10fps，说明瓶颈不是单一 `setEnableHardwareScaler` 开关。
- [ ] 双指 pinch 仍未完成真实自动验收：需要人工真机或 instrumentation 多点事件。

### 下一步任务

- [ ] P0：不要继续只调 Mac 分辨率，优先验证 Android 全屏承载方式：Dialog 内 SurfaceViewRenderer、Activity 内全屏 overlay、TextureView/自定义 VideoSink 三条路径。
- [ ] P1：在 Android 端增加“全屏渲染组件实验开关”，同一会话内对比 SurfaceViewRenderer/Dialog 与非 Dialog 全屏承载的 `render_frame_sample`。
- [ ] P2：补真实双指 pinch instrumentation，用同一套日志验证 `remote_viewport_pinch_scale`、焦点稳定性、停手后画质。

## 2026-07-05 10:25 TextureView 默认启用与回执降压复测

本轮目标：继续处理全屏双指缩放不流畅、缩放后画面不清晰、鼠标移动不流畅。由于 adb 仍不能稳定注入真实双指 pinch，本轮重点验证全屏完整显示、TextureView 全屏路径、区域高清链路、鼠标移动输入落地和 JPEG fallback 视觉 FPS。

### 本轮代码调整

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 全屏基础承载面改为 `1.0x`，避免全屏先被本地降采样再放大。
  - 默认启用全屏 `TextureView + EglRenderer` 路径。
  - pinch 焦点滤波保持 `0.78`，缩放停手后高清承载面提交为 `240ms`。
  - `session.viewport.interaction` 发送间隔收敛到 `90ms`。
- `apps/desktop/src/main.jsx`
  - 成功的 `input.mouse.move` 回执按 `120ms` 节流；Mac 仍逐条执行 move，只减少 ACK/result 反压。
  - `sendFrame()` 后的本地 React 预览重绘按 `500ms` 节流；实际 `screen.frame.push` 不降频。

### 本轮已验证

- [x] `git diff --check` 通过。
- [x] `cd apps/server && go test ./...` 通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] `cd apps/desktop/src-tauri && cargo test` 通过，33 个测试通过。
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain` 通过。
- [x] 真机三端启动通过：`wsvwypiz7xwslvl7`、Mac agent `agent-19de3117874`、relay `:18081`、TURN `:3478`。
- [x] 会话 `sess-1783218311459-1` 建立，自动 proof 仍覆盖 `click/drag/keyboard/wheel`。
- [x] 全屏入口第二次坐标点击成功，Android 日志出现 `remote_video_renderer_switch mode=texture fullscreen=true`。
- [x] 全屏截图 `/tmp/remotedesk-fullscreen-after-tap2.png` 显示远端桌面完整居中，左右黑边为比例适配。
- [x] debug viewport 链路通过：Mac `source_rect_ppm=250000,200000,500000,450000`，Android `1028x599 source_rect=0.25,0.20,0.50,0.45`。
- [x] 全屏 3 秒滑动后 Mac `input.mouse.move` 从 `4` 到 `181`，新增 `177` 条，约 `59` 条/秒，均 `applied=true`。
- [x] Android `input.result.mouse.move` 回执被节流到约每秒 8 条，说明输入落地和回执降压同时成立。

### 本轮未通过/未闭环

- [ ] 视觉帧率仍未达 `>=24fps`：基础档 `800x517` 约 `9.7-10.0fps`，交互档 `512x331` 约 `14.35fps`，局部高清 `1028x599` 约 `7.09-8.40fps`。
- [ ] Desktop 本地预览重绘节流没有根治 FPS，瓶颈仍在 JPEG 捕获/编码/Base64/Android 解码链路。
- [ ] 真实双指 pinch 手感仍未通过人工或有效多点自动化验收。
- [ ] 当前可见画面仍是 JPEG fallback，不是最终 WebRTC/H.264 修复。

### 下一步任务

- [ ] P0：推进 WebRTC/H.264 修复或建立 codec/设备能力分层，不再把 JPEG fallback 当作最终流畅方案。
- [ ] P1：将已经验证的 `source_rect` 区域高清策略接入最终 WebRTC/硬编码链路。
- [ ] P2：补 instrumentation 多点触控测试，或人工真机记录双指缩放、焦点跟随、停手清晰度和缩放后鼠标移动体验。
- [ ] P3：全屏入口自动化改用 UIAutomator bounds 或人工验收记录，不继续依赖裸坐标。

## 2026-07-05 10:55 全屏 pinch 承载面与鼠标移动复测

本轮目标：继续调整全屏双指缩放不流畅、缩放后画面不够清晰、鼠标移动不流畅，并把真机验证边界写清。

### 本轮代码调整

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 鼠标移动采样从 `16ms / 0.0007` 调整为 `12ms / 0.00045`，保留尾帧合并，提升远端鼠标轨迹连续性。
  - 全屏缩放的 Android 本地承载面固定为 `1.0x`，不再在缩放结束后创建 2x/3x 大 Surface；清晰度交给 Mac 局部高清源帧补足，减少全屏 pinch 后 Surface 重建卡顿。
  - 缩放停手后的本地提交延迟从 `240ms` 缩短到 `120ms`。
- `apps/desktop/src/main.jsx`
  - Android 真机局部高清档从 `1120x700@18fps` 调整为 `1280x800@16fps`。
  - pinch 停手后恢复/切入局部高清延迟从 `420ms` 缩短到 `160ms`。
- `apps/desktop/src-tauri/src/platform/capture.rs`
  - JPEG detail 质量从 `80` 提升到 `88`，用于缩放后区域高清帧的文字和边缘清晰度。

### 本轮已验证

- [x] `git diff --check` 通过。
- [x] `cd apps/server && go test ./...` 通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] `cd apps/desktop/src-tauri && cargo test` 通过，33 个测试通过。
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain` 通过。
- [x] 真机在线并覆盖安装通过：`wsvwypiz7xwslvl7`。
- [x] 三端重启通过：Mac agent `agent-19de3117874`、relay `127.0.0.1:18081`、Android controller online。
- [x] 当前会话：`sess-1783219924972-1`。
- [x] 全屏完整显示通过：`.rd_runtime/screens/remote_fullscreen.png` 为 `2340x1080` 横屏截图，远端桌面完整居中，黑边为宽高比适配。
- [x] 区域高清链路通过：Android 发出 `session.viewport.interaction`，relay 转发；Mac 出现 `config.updated max_width=1280 max_height=800 max_fps=16 source_rect_ppm=250000,200000,500000,450000`；Android 收到 `legacy_frame_sample ... size=1028x599 source_rect=0.25,0.20,0.50,0.45`。
- [x] 全屏单指移动链路通过：ADB 两段 swipe 后，relay 记录 `input.mouse.move` 约 `16-17ms` 间隔转发，Mac `input.mouse.move applied=true executor=macos.cg_event`。
- [x] 标准输入 proof 仍覆盖 `click,drag,keyboard,wheel`。

### 本轮未通过/未闭环

- [ ] 真实双指 pinch 仍未自动闭环：当前 ROM 的 `adb shell input motionevent` 只支持单点，debug viewport 不能替代人工双指手感。
- [ ] 当前可见画面仍是 JPEG fallback，不是最终 WebRTC/H.264 修复。
- [ ] 视觉流畅性仍不能宣称完成：当前证据能证明输入密度和局部高清切档，但不能证明全屏双指缩放肉眼已经顺滑。

### 下一步任务

- [ ] P0：人工真机验证双指张开/并拢、焦点跟随、停手清晰度、缩放后单指移动电脑鼠标。
- [ ] P1：为 Android 增加 instrumentation 多点触控测试，自动证明 `remote_viewport_pinch_scale` 和停手后区域高清切档。
- [ ] P2：把 `source_rect` 区域高清迁移到最终 WebRTC/硬编码链路，减少 JPEG/Base64 带来的 FPS 瓶颈。
- [ ] P3：继续做设备 codec/renderer 能力分层，Redmi Note 8 Pro 不能把当前 JPEG fallback 当作最终方案。

## 2026-07-05 11:45 局部高清清晰度和全屏鼠标移动复测

本轮目标：继续调整全屏双指缩放不流畅、缩放后画面不够清晰、鼠标移动不流畅，并用真机区分“已验证”和“未达标”。

### 本轮代码调整

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - `source_rect` 局部帧到达后显式把本地缩放状态归一，后续继续捏合时再在该局部帧上重新计算 transform，避免旧倍率锁住局部帧。
  - 触摸映射和当前可见区域计算始终按 `remoteViewportScale/Offset` 反算，再叠加 `source_rect`，保证局部高清状态下继续移动鼠标或二次缩放时坐标不漂。
  - pinch 增量平滑从 `0.82` 收敛到 `0.78`，viewport interaction hint 从 `70ms` 降频到 `120ms`，本地高清提交延迟调到 `220ms`，减少缩放手势末尾和局部高清切档互相抢帧。
- `apps/desktop/src/main.jsx`
  - 局部高清档从 `720x468@18fps` 调整为 `864x562@16fps`，提高缩放停手后的局部文字/边缘清晰度上限。
  - pinch 停手后切入局部高清延迟调到 `280ms`。
  - `input.mouse.move` 成功 result 回传节流从 `120ms` 调到 `180ms`，Mac 仍逐条执行 move，只减少控制端回执压力。
- `apps/desktop/src-tauri/src/platform/capture.rs`
  - JPEG detail 质量提升到 `92`。
  - 局部裁剪有限上采样从 `1.12x` 提到 `1.25x`，并更新单测期望，避免小区域高清仍明显发糊。

### 本轮已验证

- [x] `git diff --check` 通过。
- [x] `cd apps/server && go test ./...` 通过。
- [x] `cd apps/desktop/src-tauri && cargo test` 通过，35 个测试通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain` 通过。
- [x] 真机三端重启通过：`wsvwypiz7xwslvl7`、Mac agent `agent-19de3117874`、relay `127.0.0.1:18081`。
- [x] 会话 `sess-1783222978563-1` 建立；Android `legacy_first_frame ... size=800x517`，普通基础档约 `9.5-10.5fps`。
- [x] 全屏入口通过：点击小窗全屏按钮后 Android 日志 `remote_video_renderer_switch mode=texture fullscreen=true`；截图 `.rd_runtime/screens/rd_fullscreen_after_zoom_114538.png` SHA256 `38e15b3c136667150bb9b858b4f4ee8338e203cfee27bf521bdd382c5b8ec00e`，远端桌面完整居中。
- [x] 局部高清链路通过：debug viewport 后 Mac 先切 `512x334@16fps source_rect_ppm=250000,200000,500000,450000`，随后切 `864x562@16fps source_rect_ppm=250000,200000,500000,450000`；Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45` 并收到 `864x503 source_rect=0.25,0.20,0.50,0.45`。
- [x] 全屏鼠标移动输入落地通过：横屏坐标 `760,560 -> 1540,560`、900ms 滑动后，Mac `input.mouse.move applied=true` 从 `4` 增到 `63`，新增 `59` 条，约 `65/s`，执行器 `macos.cg_event`。
- [x] 移动期间采集档切换通过：Mac 滑动开始时切到 `512x334@30fps`，停手后恢复 `800x520@30fps`。

### 本轮未通过/未闭环

- [ ] 局部高清流畅性未达标：`864x503 source_rect=0.25,0.20,0.50,0.45` 稳定样本约 `5.10-6.76fps`；清晰度上限提高，但 JPEG fallback 下流畅性更差。
- [ ] 真实双指 pinch 仍未自动闭环：本轮仍使用 debug viewport 验证缩放结束后的区域消息和裁剪回传，不能替代人工两指张开/并拢手感。
- [ ] session metrics 仍没有把 legacy JPEG 帧计入 `first_frame_ms/rendered_frames`，自动汇总会显示 `first_frame_ms=-1/rendered_frames=0`；判断画面必须看 `legacy_first_frame/legacy_frame_sample`。
- [ ] 当前可见画面仍是 JPEG fallback，不是最终 WebRTC/H.264 或硬编码修复。

### 下一步任务

- [ ] P0：人工真机验收双指张开/并拢、焦点跟随、停手后清晰度和缩放后继续单指移动鼠标。
- [ ] P1：把 legacy JPEG 帧纳入 Android session metrics，避免自动报告把有画面的 fallback 会话误判为无画面。
- [ ] P2：建立局部高清档位策略：`720x468` 更稳、`864x562` 更清楚但更低 FPS，后续需要根据手势阶段/设备能力自动选择。
- [ ] P3：推进 WebRTC/硬编码或设备 codec 能力分层，把 `source_rect` 区域高清从 JSON/Base64/JPEG fallback 迁移到最终媒体链路。

## 2026-07-05 12:20 交互档、区域移动档和 legacy 指标复测

本轮目标：继续收敛全屏双指缩放不流畅、缩放后画面不清晰、鼠标移动不流畅，并修正 legacy JPEG 画面在自动指标中的误判问题。

### 本轮代码调整

- `apps/desktop/src/main.jsx`
  - 普通交互档收紧为 `448x292@30fps`，减少鼠标移动期间 JPEG/Base64/Bitmap 压力。
  - 区域移动档保持 `512x334` 并恢复到 `30fps`，避免放大后移动被误压到局部高清的 `12fps` cap。
  - 局部高清档调整为 `800x520@12fps`，保持窗口缩短为 `2600ms`，定位为停手后的清晰补帧。
- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 小尺寸 legacy 帧使用 `RGB_565` 解码，减少移动帧 Bitmap 分配；停手高清帧仍使用 `ARGB_8888`。
  - `session.metrics.report` 将 legacy JPEG 首帧、帧数和平均 FPS 纳入通用视频 proof 字段，并保留 `legacy_* / webrtc_*` 明细。
- `apps/server/internal/transport/ws.go`
  - combined summary 透出 `media_frame_transport`，用于区分 `webrtc` 和 `legacy_jpeg`。

### 本轮已验证

- [x] `git diff --check` 通过。
- [x] `bash -n scripts/triad_ctl.sh` 通过。
- [x] `cd apps/server && go test ./...` 通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] `cd apps/desktop/src-tauri && cargo test` 通过，36 个测试通过。
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain` 通过。
- [x] 真机覆盖安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`。
- [x] 三端启动通过：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./scripts/triad_ctl.sh restart`。
- [x] 会话 `sess-1783224608715-1` 建立；Android controller `android-6b04e47d7cccda58`，Mac agent `agent-19de3117874`。
- [x] 基础档可见：Android `legacy_first_frame size=800x517`；样本约 `9.75/10.17/9.88fps @800x517`。
- [x] 普通交互档生效：Mac 切到 `448x292@30fps`，Android 收到 `448x290`，样本约 `15.26fps`。
- [x] 区域移动档 cap 修复：debug viewport 后 Mac 正确切到 `512x334@30fps source_rect_ppm=250000,200000,500000,450000`，随后进入 `800x520@12fps` 局部高清。
- [x] 区域高清链路仍通：Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45`，局部高清样本约 `8.66fps @800x466 source_rect=0.25,0.20,0.50,0.45`。
- [x] 全屏完整显示通过：截图 `.rd_runtime/screens/rd_fullscreen_try3_1211.png`，SHA256 `da05eeea66dee8ea1781d95c18b7b5f4e4bcac786fbb162d30bc6bf26418a7d4`，远端桌面完整居中，左右黑边为比例适配。
- [x] 全屏单指移动输入落地通过：滑动后 Mac `input.mouse.move applied=true` 新增 `71` 条；滑动期间 Mac 切 `448x292@30fps`，停手后恢复 `800x520@30fps`。

### 本轮未通过/未闭环

- [ ] 基础档、交互档和局部高清档均未达到 `>=24fps`：当前最好样本是普通交互档约 `15.26fps @448x290`。
- [ ] 缩放后清晰度只在 debug viewport + JPEG fallback 裁剪链路中验证，真实人工双指 pinch 手感仍未验收。
- [ ] 当前可见画面仍来自 JPEG fallback，WebRTC/H.264 没有修复。
- [x] legacy 指标修正已通过三端验证：会话 `sess-1783225422776-1` 的 relay `session.metrics.combined` 出现 `media_frame_transport=legacy_jpeg`，且通用 `first_frame_ms=43/rendered_frames=66/render_fps_avg=9.92`。

### 下一步任务

- [ ] P0：推进 WebRTC/H.264、硬编码或设备 codec 能力分层，不能把 JPEG fallback 当作最终流畅方案。
- [ ] P1：把 `source_rect` 区域高清迁移到最终媒体链路，并保留 Android 坐标反算。
- [ ] P2：补 instrumentation 多点触控测试或人工真机记录双指缩放手感、焦点跟随、停手清晰度和缩放后鼠标移动体验。
- [ ] P3：将 legacy JPEG 降级产品化：设备命中条件、质量指标、UI 状态提示和恢复 WebRTC 的探测条件。

## 2026-07-05 13:09 全屏缩放和鼠标移动复测记录

本轮目标：继续处理全屏双指缩放不流畅、缩放后画面不够清晰、鼠标移动不流畅；重点验证“不要靠过密输入信令换假流畅”，并记录 JPEG fallback 的真实上限。

### 本轮代码调整

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 鼠标移动采样收敛为 `16ms / 0.00055`，尾帧最大延迟 `16ms`；目标是保持约 60Hz 远端鼠标移动，同时避免 100Hz+ 信令和回包挤压 Android 当前 JPEG 兜底画面的解码与 UI 线程。
  - pinch 缩放和平移焦点平滑参数收敛为 `REMOTE_PINCH_SCALE_FACTOR_SMOOTHING=0.80`、`REMOTE_PINCH_FOCUS_SMOOTHING=0.78`。
- `apps/desktop/src/main.jsx`
  - 局部高清档调整为 `800x520@10fps`，保持窗口延长为 `7000ms`。
  - 新增 `source_rect` 变化节流刷新：当 Android 上报的局部可视区域明显变化，并超过刷新间隔时，Mac 端重新应用局部裁剪源，避免缩放/平移后继续看上一块旧局部。
- `apps/desktop/src-tauri/src/platform/capture.rs`
  - 局部 JPEG 质量小幅提升：balanced `88`，sharp `90`。

### 本轮已验证

- [x] `git diff --check` 通过。
- [x] `bash -n scripts/triad_ctl.sh` 通过。
- [x] `cd apps/server && go test ./...` 通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] `cd apps/desktop/src-tauri && cargo test` 通过，36 个测试通过。
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain` 通过。
- [x] 真机覆盖安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`。
- [x] 三端启动通过：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./scripts/triad_ctl.sh restart`。
- [x] 会话 `sess-1783227836869-1` 建立；Android controller `android-6b04e47d7cccda58`，Mac agent `agent-19de3117874`。
- [x] 标准 E2E proof 通过：`session_e2e_proof_status=video_and_input_observed`，`first_frame_ms=107`，`media_frame_transport=legacy_jpeg`，`render_fps_avg=10.14`，`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。
- [x] 小窗完整显示通过：截图 `.rd_runtime/screens/rd_current_1304.png`，SHA256 `c02b628611e59b36a3b447abee4faa6453b4b164549cf10c479f31dea59f08fc`；基础档约 `9.8-10.5fps @800x517`。
- [x] 全屏完整显示通过：截图 `.rd_runtime/screens/rd_fullscreen_1308.png`，SHA256 `c09d6306b81397290b7933554df79d98d5fd59d2e0f6e7272b29fcc4cb3721a2`，远端桌面完整居中，左右黑边为比例适配。
- [x] 全屏单指移动输入落地通过：3 秒滑动后 Mac `input.mouse.move applied=true` 从 `179` 增到 `356`，新增 `177` 条，约 `59Hz`，执行器 `macos.cg_event`；截图 `.rd_runtime/screens/rd_fullscreen_swipe_1309.png`，SHA256 `ace9e518afce841b79414b5b279445b1e87320f3c8da71a9dea53b9c1e7f2011`。
- [x] 区域高清链路通过：debug viewport 后 Mac 先切 `512x334@30fps source_rect_ppm=250000,200000,500000,450000`，随后切 `800x520@10fps source_rect_ppm=250000,200000,500000,450000`；Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45` 并收到 `800x466 source_rect=0.25,0.20,0.50,0.45`。

### 本轮未通过/未闭环

- [ ] 视觉流畅度未达标：基础档约 `9.8-10.5fps @800x517`，普通交互档约 `15-18fps @448x290`，局部高清约 `6.89/6.98fps @800x466 source_rect=0.25,0.20,0.50,0.45`，均低于 `>=24fps`。
- [ ] 真实双指 pinch 手感未人工验收：ADB `input` 仍不能可靠产生多点 pinch；debug viewport 只能证明缩放结束后的区域高清消息和裁剪回传链路。
- [ ] 当前可见画面仍来自 `legacy_jpeg`，不是最终 WebRTC/H.264 或硬编码链路。

### 下一步任务

- [ ] P0：建立 Android 设备 codec 能力分层，修复或替换 Redmi Note 8 Pro 上的 WebRTC/H.264 路线，评估 VP8/VP9/H.265、硬编硬解和软件解码兜底。
- [ ] P1：把已验证的 `source_rect` 区域高清能力迁移到最终媒体链路，保留 Android 触摸坐标反算。
- [ ] P2：补 instrumentation 多点触控测试或人工真机记录双指缩放手感、焦点跟随、停手清晰度和缩放后单指移动鼠标体验。
- [ ] P3：把 JPEG fallback 产品化为受控降级路径，明确设备命中条件、质量指标、UI 状态提示和恢复 WebRTC 的探测条件。

## 2026-07-05 14:32 全屏 pinch、局部高清和鼠标移动最终复测

本轮目标：继续调整全屏双指缩放不流畅、缩放后画面不够清晰、鼠标移动不流畅；使用真机和 Mac 端验证，并把系统设计、需求和清单同步更新。

### 本轮代码调整

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 鼠标移动采样从 `8ms / 0.00032` 收紧到 `6ms / 0.00024`，尾帧最大延迟同步为 `6ms`。
  - 全屏缩放停手后的高清承载面提交延迟区分小窗/全屏：小窗 `140ms`，全屏 `220ms`，减少横屏全屏 Surface/Texture 合成重建和 pinch 结束抢帧。
  - 新增 `rd_debug_fullscreen` / `rd_debug_fullscreen_enabled` intent 调试入口，用于稳定触发全屏验证，避免裸坐标点击误差影响验收。
- `apps/desktop/src/main.jsx`
  - Mac 端 mouse move 队列节流从 `12ms` 收紧到 `6ms`，只保留最新坐标并串行执行，非 move 输入前仍 flush 尾帧。
  - pinch update 阶段不再反复应用局部 `source_rect`，避免两指开合时采集源频繁重配导致卡顿；pinch end 后再进入局部高清。
  - 局部高清档从 `1280x834@16fps` 收敛到 `1024x668@20fps`，避免上一轮 `1280x746 source_rect` 只有约 `3fps`。
  - pinch restore delay 从 `90ms` 调整到 `180ms`，与 Android 全屏停手提交错峰。

### 本轮已验证

- [x] `git diff --check` 通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain` 通过。
- [x] 真机覆盖安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`。
- [x] 三端重启通过：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./scripts/triad_ctl.sh restart`。
- [x] 会话 `sess-1783233039204-1` 建立；Android controller `android-6b04e47d7cccda58`，Mac agent `agent-19de3117874`。
- [x] 标准 E2E proof 通过：relay combined 显示 `session_e2e_proof_status=video_and_input_observed`、`first_frame_ms=55`、`media_frame_transport=legacy_jpeg`、`remote_input_applied=16/16`，覆盖 `click,drag,keyboard,wheel`。
- [x] 全屏完整显示通过：`/tmp/remotedesk-final-fullscreen.png` 显示远端桌面完整居中，左右黑边为比例适配；Android 日志 `remote_video_renderer_switch mode=texture fullscreen=true`。
- [x] 区域高清链路通过：debug viewport 后 Mac 先切 `800x520@24fps source_rect_ppm=250000,200000,500000,450000`，随后切 `1024x668@20fps source_rect_ppm=250000,200000,500000,450000`；Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45`。
- [x] 全屏单指移动输入落地通过：长滑后 Mac `input.mouse.move` 执行日志均为 `applied=true executor=macos.cg_event`；relay combined 显示 `remote_input_last_count=39`，result push 汇总 `16/16 applied`。由于 move result push 被节流，result 数量小于 Mac 实际执行日志数量，这是预期行为。

### 本轮未通过/未闭环

- [ ] 视觉流畅度仍未达标：当前可见链路仍是 `legacy_jpeg`，relay combined `render_fps_avg≈6.10fps`，低于 `>=24fps` 验收线。
- [ ] 真实双指 pinch 手感仍未人工验收：debug viewport 只能证明缩放结束后的区域消息和裁剪回传链路，不能替代两指张开/并拢的主观顺滑度、焦点跟随和停手清晰度。
- [ ] 鼠标输入落地更密，但“画面里的远端光标视觉流畅”仍受 JPEG 帧率限制，不能写成完成。
- [ ] 当前没有修复 WebRTC/H.264/native sender；`native_sender_lifecycle=idle`，`webrtc_first_frame_ms=-1`，Redmi 真机仍走 JPEG fallback。

### 下一步任务

- [ ] P0：优先推进最终媒体链路：修复 WebRTC/H.264 或建立 VP8/VP9/H.265/硬编硬解的设备能力分层，不能继续把 JPEG fallback 当作最终流畅方案。
- [ ] P1：将 `source_rect` 区域高清从 JPEG fallback 迁移到 WebRTC/硬编码链路，并保持 Android 坐标反算。
- [ ] P2：安排人工真机验收真实双指 pinch：张开/并拢、焦点跟随、缩放后双指平移、缩放后单指移动电脑鼠标、停手后清晰恢复。
- [ ] P3：补 instrumentation 多点触控或可重复手势脚本，自动产生 `remote_viewport_pinch_scale` 和 `session.viewport.interaction` 的真实手势证据。
- [ ] P4：继续评估鼠标 move 队列和 Android 发送端节流，但只把它当作输入反馈优化；流畅体验的主瓶颈仍应在媒体链路解决。

## 2026-07-05 14:54 全屏缩放/鼠标移动继续调参与真机复测

本轮目标：继续处理“全屏双指缩放不流畅、缩放后画面不够清晰、鼠标移动不流畅”。结论按代码、构建、真机日志和截图记录；不能把当前 JPEG fallback 写成已达流畅目标。

### 本轮代码调整

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 鼠标 move 发送从 `6ms / 0.00024` 收敛到 `16ms / 0.00045`，尾帧最大延迟 `16ms`；本地光标继续用动画帧反馈，避免远端信令/ACK 过密挤压帧解码。
  - pinch 增量和焦点滤波收敛为 `REMOTE_PINCH_SCALE_FACTOR_SMOOTHING=0.78`、`REMOTE_PINCH_FOCUS_SMOOTHING=0.72`，减少两指细抖造成的画面跳动。
  - 全屏基础/交互承载面均回到 `1.0x`，停手后的清晰度主要交给 Mac `source_rect` 局部高清帧补足。
- `apps/android/app/src/main/java/com/remotedesk/app/ui/RemoteDeskFrameView.kt`
  - 新增按场景切换 bitmap 缩放滤波：全屏整屏移动时走低成本绘制，局部高清和小窗继续启用滤波，兼顾移动期合成压力和缩放后文字边缘。
- `apps/desktop/src/main.jsx`
  - Android 真机默认/全屏整屏档收敛为 `800x520@30fps`，普通移动档收敛为 `448x292@30fps`，局部移动档为 `512x334@24fps`，局部高清档为 `960x624@16fps`。
  - Mac mouse move 队列节流回到 `16ms`，成功 move result push 节流到 `240ms`。
- `apps/desktop/src-tauri/src/platform/capture.rs`
  - 整屏/移动 JPEG 质量下调为 interactive `46`、fullscreen `54`，减少 Base64/JPEG 解码压力；局部高清仍保留 balanced `86`、sharp `88`。

### 本轮已验证

- [x] `git diff --check` 通过。
- [x] `cd apps/server && go test ./...` 通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] `cd apps/desktop/src-tauri && cargo test` 通过，36 个测试通过。
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain` 通过。
- [x] 真机覆盖安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`。
- [x] 三端重启通过：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./scripts/triad_ctl.sh restart`。
- [x] 最终会话 `sess-1783234294807-1` 建立；Android controller `android-6b04e47d7cccda58`，Mac agent `agent-19de3117874`。
- [x] 标准 E2E proof 通过：relay combined 显示 `session_e2e_proof_status=video_and_input_observed`、`first_frame_ms=54`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=6.14`、`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。
- [x] 全屏完整显示通过：截图 `/tmp/remotedesk-final-fullscreen-1452.png`，SHA256 `755ffb322f10bc7398d0b62ffa375d03954de9da30420b2073157f351c9f54d4`，远端桌面完整居中，左右黑边为比例适配。
- [x] 全屏单指移动输入落地通过：3 秒滑动后 Mac `input.mouse.move applied=true` 从 `3` 增到 `163`，新增 `160` 条；滑动后截图 `/tmp/remotedesk-final-fullscreen-swipe-1452.png`，SHA256 `a293807db867328bbf88565b72953eb97caf2a22d2a9999cd44b058e8fc0cdb0`。
- [x] 移动交互档切换通过：Mac 日志出现 `config.updated max_width=448 max_height=292 max_fps=30`；Android 在滑动窗口内出现 `448x290` 样本，最高约 `17.88fps`。
- [x] 区域高清链路通过：debug viewport 后 relay 转发 `session.viewport.interaction`；Mac 先切 `512x334@24fps source_rect_ppm=250000,200000,500000,450000`，随后切 `960x624@16fps source_rect_ppm=250000,200000,500000,450000`；Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45` 并收到 `960x559 source_rect=0.25,0.20,0.50,0.45`。

### 本轮未通过/未闭环

- [ ] 视觉流畅度仍未达标：基础整屏稳定约 `5.9-6.4fps @800x517`，移动交互峰值约 `17.88fps @448x290`，仍低于 `>=24fps`。
- [ ] 缩放后局部高清链路可用但不流畅：`960x559 source_rect=0.25,0.20,0.50,0.45` 样本先约 `5.54fps`，随后约 `2.92fps`。
- [ ] 真实双指 pinch 手感仍未人工验收：debug viewport 只证明 `source_rect` 消息、Mac 裁剪和 Android 回传显示链路，不能替代两指张开/并拢的顺滑度和焦点跟随验收。
- [ ] 当前可见画面仍是 `legacy_jpeg`，不是 WebRTC/H.264/native sender；继续调 JPEG 参数收益有限。

### 下一步任务

- [ ] P0：停止把 JPEG fallback 当最终流畅方案，优先修复 WebRTC/H.264 或接入可用硬编码/硬解能力分层。
- [ ] P1：把 `source_rect` 区域高清迁移到最终媒体链路，保留 Android 坐标反算和 pinch end 后局部清晰恢复。
- [ ] P2：补 instrumentation 多点触控或人工真机验收，明确记录双指缩放、双指平移、缩放后单指鼠标移动和停手清晰恢复的真实手感。
- [ ] P3：如果继续保留 JPEG fallback，应产品化为受控降级路径，并在 UI/文档中标明当前真机只能“可见可操作”，不是流畅远控。

## 2026-07-05 15:10 远程输入、共享剪贴板和文件传输 result 闭环

本轮目标：继续实现远程输入、文件传输和共享剪贴板；重点补齐接收端应用结果回传，让发送端能区分“relay 已转发”和“目标端已写入剪贴板/已保存文件”。

### 本轮代码调整

- `apps/desktop/src/main.jsx`
  - 收到 `clipboard.text` 后写入桌面剪贴板，并回传 `clipboard.result`；发送端收到 result 后更新为“对端已写入/写入失败”。
  - 收到 `file.transfer.*` 后完成分块校验、SHA256 校验和 `~/Downloads/RemoteDesk/` 保存，并回传 `file.transfer.result`；发送端收到 result 后更新为“对端已保存/保存失败”。
- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 收到远端剪贴板后写入 Android 系统剪贴板并回传 `clipboard.result`。
  - 收到远端文件后保存到 MediaStore 或 app-private fallback，并回传 `file.transfer.result`；发送端收到 result 后显示对端保存结果。

### 本轮已验证

- [x] `git diff --check` 通过。
- [x] `cd apps/server && go test ./...` 通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] `cd apps/desktop/src-tauri && cargo test` 通过，36 个测试通过。
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain` 通过。
- [x] 真机覆盖安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`。
- [x] 三端启动通过：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto RD_DESKTOP_DEBUG_ENABLE_TOOLS=1 ... ./scripts/triad_ctl.sh restart`。
- [x] 远程输入未回归：会话 `sess-1783235440137-3` 标准 proof 为 `video_and_input_observed`，`first_frame_ms=111`，`media_frame_transport=legacy_jpeg`，`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。
- [x] Mac -> Android 剪贴板闭环：relay 转发 `clipboard.text`，Android 日志 `debug_clipboard_received length=35 text=mac-to-android-clipboard-1783235203`，Android 回传 `clipboard.result`，relay 转发到 Mac。
- [x] Mac -> Android 文件闭环：Mac 发送 `file.transfer.start/chunk/complete`；Android 保存到 `app-private:/data/user/0/com.remotedesk.app/files/RemoteDeskIncoming/remotedesk-mac-to-android-tool-test.txt`，回传 `file.transfer.result`。
- [x] Android -> Mac 剪贴板闭环：Android 发送 `clipboard.text`，Mac 回传 `clipboard.result`，Android UI 显示 `剪贴板：对端已写入 35 字符`。
- [x] Android -> Mac 文件闭环：Android 发送 `remotedesk-android-to-mac-tool-test2.txt (64 B)` 的 `file.transfer.start/chunk/complete`，Mac 回传 `file.transfer.result`，Android UI 显示 `文件：对端已保存 remotedesk-android-to-mac-tool-test2.txt (64 B)`。
- [x] Android -> Mac 文件落盘校验：Mac 文件 `~/Downloads/RemoteDesk/remotedesk-android-to-mac-tool-test2.txt` 存在；源文件 `/tmp/remotedesk-android-to-mac-tool-test2.txt` 与目标文件 SHA256 均为 `c8feb20cf6ef5cfb5ee153e4b88fe33d883fbded2895b2138ae205cc0b0fa76b`。

### 本轮未通过/未闭环

- [ ] Android 系统文件选择器人工发送文件未复测；本轮 Android -> Mac 文件使用 `/data/local/tmp` 调试文件路径验证链路。
- [ ] Desktop 会话工具栏“剪贴板/文件”人工点击未复测；本轮 Mac -> Android 使用 `RD_DESKTOP_DEBUG_ENABLE_TOOLS` 自动调试入口。
- [ ] 文件传输仍走 WebSocket 信令分块，适合小文件 proof；大文件、高速传输、断点续传和取消/进度控制还未产品化。
- [ ] 远控画面仍是 `legacy_jpeg`，视觉流畅度问题未因本轮工具通道修改而解决。

### 下一步任务

- [ ] P0：把 Android 文件选择器发送和 Desktop 工具栏发送纳入人工 UI 验收，补齐非 debug 入口证据。
- [ ] P1：为 `clipboard.result` / `file.transfer.result` 增加前端/Android 可重复自动化断言，避免未来只看到 relay 转发却不知道目标端是否应用。
- [ ] P2：评估 DataChannel 或 HTTP 分片通道，替代当前 WebSocket 大 payload 文件分块方案。
- [ ] P3：继续推进最终媒体链路和真实双指 pinch 验收；工具通道已闭环不代表远控视觉体验完成。

## 2026-07-05 15:33 全屏缩放/局部高清档位回收复测

本轮目标：继续处理“全屏双指缩放不流畅、缩放后画面不够清晰、鼠标移动不流畅”。15:26 复测发现 `1088x634 source_rect` 局部高清只有 `5.68/4.21/4.23fps`，因此本轮不再继续加大 JPEG 局部帧，而是回收停手高清档并缩短保持窗口。

### 本轮代码调整

- `apps/desktop/src/main.jsx`
  - 局部移动档保持 `560x364@24fps/8.2Mbps`，用于缩放/平移后的跟手阶段。
  - 局部停手高清档从 `1088x708@18fps/11Mbps` 回收到 `960x624@16fps/9.6Mbps`。
  - 局部高清保持窗口从 `7000ms` 缩短到 `4200ms`，避免停手后长时间停留在低 FPS 局部高清档。

### 本轮已验证

- [x] `git diff --check` 通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] `RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto ./scripts/triad_ctl.sh restart` 已启动 relay、turn、Mac agent；脚本仍在 Android install 阶段退出，随后使用 adb 手动启动 Android。
- [x] 真机会话 `sess-1783236665948-1` 建立；标准 proof 通过：`session_e2e_proof_status=video_and_input_observed`、`first_frame_ms=47`、`media_frame_transport=legacy_jpeg`、`remote_input_applied=8/8`。
- [x] 基础档显示：Android `legacy_frame_sample` 稳定约 `9.9-10.0fps @800x517`。
- [x] 全屏完整显示：截图 `.rd_runtime/screens/rd_fullscreen_after_detail_tune_1531.png`，SHA256 `275a945fa141aee4497d9b69b02ab7ee2d3d993233686d683c6cca7904021109`，远端桌面完整居中，黑边为比例适配。
- [x] 全屏鼠标移动输入落地：3 秒滑动后 Mac `input.mouse.move applied=true` 从 `4` 增到 `163`，新增 `159` 条；Android 滑动期间切到 `448x290` 交互档，样本 `16.44/17.41fps`。
- [x] 区域高清链路通过：debug viewport 后 Mac 先切 `560x364@24fps source_rect_ppm=250000,200000,500000,450000`，随后切 `960x624@16fps source_rect_ppm=250000,200000,500000,450000`，约 `4200ms` 后回到 `800x520@24fps` 整屏档。
- [x] Android 收到局部帧：日志 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45`，`legacy_frame_sample ... size=960x559 source_rect=0.25,0.20,0.50,0.45`；截图 `.rd_runtime/screens/rd_debug_viewport_960_1533.png`，SHA256 `bc0d7faecc82c4dcf68b639537f8deb5d4b43586d168fb74643783ff5f702394`。

### 本轮未通过/未闭环

- [ ] 视觉流畅度仍未达标：基础档约 `10fps`，全屏移动交互档约 `16-17fps`，局部高清 `960x559` 样本 `6.76/5.25fps`，均低于 `>=24fps`。
- [ ] 缩放后清晰度只能说“source_rect 局部源链路可用”，不能写成“缩放后既清晰又流畅”；JPEG fallback 下清晰度和流畅度仍明显冲突。
- [ ] 真实双指 pinch 手感仍未人工验收；debug viewport 不能替代两指张开/并拢的焦点跟随和顺滑度验收。
- [ ] 当前可见媒体仍是 `legacy_jpeg`，不是 WebRTC/H.264/native sender 修复。

### 下一步任务

- [ ] P0：推进 WebRTC/H.264、硬编码或设备 codec 能力分层；不要继续把 JPEG fallback 参数微调当作最终修复。
- [ ] P1：把已验证的 `source_rect` 区域高清迁移到最终媒体链路，并保持 Android 触摸坐标反算。
- [ ] P2：补人工真机双指缩放、双指平移、缩放后单指移动鼠标和键盘输入手感验收。

## 2026-07-05 15:50 全屏缩放合成层/高质量缩放/鼠标节奏复测

本轮目标：继续处理“全屏双指缩放不流畅、缩放后画面不够清晰、鼠标移动不流畅”。这轮只做低风险调整，不再继续抬高 JPEG 局部帧尺寸。

### 本轮代码调整

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 鼠标移动发送从上一轮更激进节奏收敛为 `16ms / 0.00030`，尾帧最大延迟 `16ms`；本地蓝色指针仍按动画帧反馈，远端输入保持约 60Hz 上限，避免信令和 ACK 抢占 JPEG 解码。
  - Pinch 缩放阈值调为 `0.0012`，倍率/焦点滤波为 `0.78 / 0.70`，全屏高清承载面提交延迟为 `320ms`。
  - 双指缩放期间临时启用 `remoteViewportContent` 硬件合成层，只改 transform；停手后延迟释放，避免一直持有大纹理。
  - 全屏放大、pinch 中和局部 `source_rect` 帧启用 `RemoteDeskFrameView` 的高质量 Bitmap 缩放；全屏 1x 普通移动仍可关闭滤波以保低成本绘制。

### 本轮已验证

- [x] `git diff --check` 通过。
- [x] `bash -n scripts/triad_ctl.sh` 通过。
- [x] `cd apps/server && go test ./...` 通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] `cd apps/desktop/src-tauri && cargo test` 通过，36 个测试通过；仍有既有 deprecated/unused warning。
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain` 通过；仍有既有 deprecated warning。
- [x] APK 已覆盖安装到真机 `wsvwypiz7xwslvl7`。
- [x] 三端会话 `sess-1783237518336-1` 建立；标准 proof 通过：`video_and_input_observed`、`first_frame_ms=62`、`media_frame_transport=legacy_jpeg`、`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。
- [x] 全屏完整显示：截图 `.rd_runtime/screens/rd_fullscreen_after_smooth_tune_1547.png`，SHA256 `de5fb682c200f1cacc168a1b80602bc46ddad985a2a182dbcdf4290ad0a77d7f`，远端桌面完整居中，黑边为比例适配。
- [x] 全屏鼠标移动输入落地：三段全屏滑动后 Mac `input.mouse.move applied=true` 从 `2` 增到 `152`，新增 `150` 条，约 `55Hz`；Android 交互档最高样本 `16.92fps @448x290`。
- [x] 区域高清链路未回归：debug viewport 后 Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45`，收到 `560x326` 局部移动帧和 `960x559 source_rect=0.25,0.20,0.50,0.45` 局部高清帧；截图 `.rd_runtime/screens/rd_debug_viewport_after_smooth_tune_1552.png`，SHA256 `85d0c706a838889323a4bdfde5279cbe3f8acc1a7f50bd93b83fcfdd76c00387`。

### 本轮未通过/未闭环

- [ ] 视觉流畅度仍未达标：基础 proof `render_fps_avg=9.75`，全屏交互峰值 `16.92fps @448x290`，局部高清 `960x559` 只有 `5.59/5.22fps`，均低于 `>=24fps`。
- [ ] 高质量 Bitmap 缩放只减少本地放大的像素感，不能增加源帧真实细节；缩放后真正清晰仍依赖 `source_rect` 局部源帧和最终媒体链路。
- [ ] adb 标准 `input` 工具只支持单点 `tap/swipe/motionevent`，没有 pointer id 参数；本轮没有自动化真实双指 pinch 手感证据。
- [ ] 当前可见媒体仍是 `legacy_jpeg`，不是 WebRTC/H.264/native sender 修复。

### 下一步任务

- [ ] P0：修复或替代 Android 真机最终媒体链路：优先 WebRTC/H.264、硬编码/硬解能力分层，或更高效降级媒体通道。
- [ ] P1：补人工真机双指缩放、双指平移、缩放后单指移动鼠标手感验收；debug viewport 只能证明区域高清链路，不能替代真实手势。
- [ ] P2：把 `source_rect` 区域高清迁移到最终媒体链路，并保留 Android 触摸坐标反算。

## 2026-07-05 16:56 全屏 pinch 期间低成本绘制与停手后快速局部高清复测

本轮目标：继续处理“全屏双指缩放不流畅、缩放后画面不够清晰、鼠标移动不流畅”。本轮没有继续放大 JPEG 局部帧，而是把 pinch 手势中的整屏 JPEG 绘制成本降下来，并让 pinch end 后直接切 `source_rect` 局部高清。

### 本轮代码调整

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 全屏 pinch 期间整屏 legacy JPEG 展示节流从 `240ms` 调整为 `320ms`，减少手势中解码/绘制抢占。
  - 全屏停手后的本地高清承载面提交从 `320ms` 提前到 `220ms`。
  - pinch 开始时关闭 `RemoteDeskFrameView` 高质量 Bitmap 滤波，停手后按当前 `source_rect` / 缩放状态恢复；局部高清帧仍走高质量缩放。
- `apps/desktop/src/main.jsx`
  - Android pinch end 后直接切 `buildAndroidPhoneZoomDetailNativeProfile()`，不再先等待一轮低清交互档，缩短缩放后从本地放大到真实局部采集的等待时间。

### 本轮已验证

- [x] `git diff --check` 通过。
- [x] `bash -n scripts/triad_ctl.sh` 通过。
- [x] `cd apps/server && go test ./...` 通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] `cd apps/desktop/src-tauri && cargo test` 通过，36 个测试通过；仍有既有 deprecated/unused warning。
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain` 通过；仍有既有 deprecated warning。
- [x] APK 已覆盖安装到真机 `wsvwypiz7xwslvl7`。
- [x] 三端会话 `sess-1783241479512-1` 建立；标准 proof 通过：`video_and_input_observed`、`first_frame_ms=78`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=7.00`、`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。
- [x] 全屏完整显示通过：截图 `/tmp/remotedesk-fullscreen-after-tap-165242.png` 显示远端桌面完整居中，左右黑边为比例适配。
- [x] 全屏单指滑动输入仍能落地：本轮 adb 3 秒 swipe 后 Mac `input.mouse.move applied=true` 计数从 `3` 增到 `49`，新增 `46` 条；该 adb 采样明显低于前几轮，不能当作人工滑动手感上限。
- [x] 区域高清链路通过：debug viewport 正确使用 `--ez rd_debug_send_viewport_interaction true` 与 `--ed` double extra 后，Android 记录 `debug viewport pinch end`，Mac 立即切到 `960x624@12fps source_rect_ppm=250000,200000,500000,450000`，Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45`。
- [x] Android 收到局部高清帧：`legacy_frame_sample ... size=960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清样本 `4.66/5.00fps`；2 秒内截图 `/tmp/remotedesk-debug-viewport-during-current-165617.png`，SHA256 `4b9f1a5e1e102e4457776a81cfbadac565f707ae56916535447bf58efb54488c`，显示局部区域比整屏放大更可读。

### 本轮未通过/未闭环

- [ ] 视觉流畅度仍未达标：标准 proof `render_fps_avg=7.00`，局部高清仅 `4.66/5.00fps @960x559`，低于 `>=24fps`。
- [ ] 鼠标“远端执行”可验证，但“远程画面里看到的鼠标移动流畅”仍受 legacy JPEG 低 FPS 限制，不能宣称完成。
- [ ] 并发 `adb input swipe` 未触发真实多点 pinch，没有出现 `remote_viewport_pinch_scale`；本轮真实双指手感仍未自动化闭环。
- [ ] debug viewport 已证明缩放结束后的清晰源链路，但不能替代人工双指张开/并拢的焦点跟随、顺滑度和手感验收。
- [ ] 当前可见媒体仍是 `legacy_jpeg`，不是 WebRTC/H.264/native sender 修复。

### 下一步任务

- [ ] P0：推进最终媒体链路，不再把 JPEG fallback 参数微调当作根治方案。
- [ ] P1：做人工真机双指 pinch 验收，重点记录手势中是否顿挫、停手后多久清晰、缩放后单指鼠标是否跟手。
- [ ] P2：为 Android 增加 instrumentation 多点触控测试，自动证明 `remote_viewport_pinch_scale` 和 pinch end 后局部高清恢复。

## 2026-07-06 远程输入采样和 Desktop 执行顺序优化

本轮目标：先继续优化远程输入，不再继续加密鼠标 move 信令，而是降低反压风险并修正 Desktop 端离散输入与最后移动点的执行顺序。

### 本轮代码调整

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 鼠标移动参数收敛为 `12ms / 0.00032`，尾帧最大延迟 `12ms`，最多消费最近 `4` 个 `MotionEvent` 历史点。
  - 这组参数和最新需求文档记录保持一致，避免当前代码里的 `10ms / 0.00024` 过密信令继续放大 WebSocket、ACK 和 Android UI 更新压力。
- `apps/desktop/src/main.jsx`
  - 新增 `hostMouseMoveApplyPromise`，让 Desktop agent 知道最后一个 `input.mouse.move` 是否仍在系统输入执行器中。
  - `flushQueuedHostMouseMove()` 现在会等待正在执行的 move 完成，再补 pending move，最后才允许按钮、键盘或滚轮继续执行，避免点击/抬起落到上一帧坐标。
  - `drainHostMouseMoveQueue()` 和 `flushQueuedHostMouseMove()` 共用 `applyQueuedHostMouseMove()`，统一维护 in-flight 状态和后续 pending move 调度。
  - 新增 Desktop 端离散输入串行队列；按钮、键盘、滚轮会按 relay 到达顺序执行，队列未清空时暂停后台 mouse move drain，避免多个离散事件在同一个 move promise 后并发落地。
- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - Android 发送鼠标按钮、键盘和滚轮前会先 flush 本地帧级合并 move 与尾部 deferred move，避免发送端还没发出的尾点晚于按钮/键盘到达目标端。
  - 拖拽释放和会话释放路径也先补最后一个 pending move 再发 `left up`，减少拖拽抬手落在旧坐标的概率。

### 本轮已验证

- [x] `git diff --check` 通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain` 通过；仍有既有 deprecated warning。
- [x] 真机 `wsvwypiz7xwslvl7` 覆盖安装并重启三端通过：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto RD_ANDROID_AUTO_PROOF_INPUT=1 ... ./scripts/triad_ctl.sh restart`。
- [x] 新会话 `sess-1783301222263-1` 自动 proof 通过：`video_and_input_observed`，`first_frame_ms=217`，`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`，执行器 `macos.cg_event`。
- [x] Mac 日志显示离散输入顺序落地：drag proof 中 `input.mouse.move` 后接 `input.mouse.button down`，最后一个 drag move 后接 `input.mouse.button up`，键盘 `KeyA down/up` 和滚轮均 `applied=true`。
- [x] 3 秒真机长滑动复测通过：Mac `input.mouse.move applied=true` 从 `3` 增到 `168`，新增 `165` 条；统计窗口 `2869ms`，间隔 `min=5ms / max=165ms / avg=17.49ms`。

### 本轮未闭环

- [ ] 仍缺人工手感验收：自动 proof 和 adb swipe 证明链路与落账节奏，不能替代真实手指连续滑动、长按拖拽、键盘面板点击的主观手感确认。
- [ ] 视觉流畅度仍受 `legacy_jpeg` 低 FPS 影响；本轮只优化输入落点顺序和信令节奏，不解决最终媒体链路。

## 2026-07-06 两指并拢缩小 source_rect 连续性与本地预览优化

本轮目标：修复局部高清物化后两指并拢缩小不跟手的问题。核心原因是 Android 本地倍率已经回到 `1x`，继续并拢会被最小倍率吞掉，只能等待 Mac 回传更大的 `source_rect`，中间没有本地视觉反馈。

### 本轮代码调整

- `apps/android/app/src/main/java/com/remotedesk/app/ui/MainActivity.kt`
  - 已处于局部 `source_rect` 且本地倍率为 `1x` 时，两指并拢缩小改为扩大目标 `source_rect`，持续发送 `session.viewport.interaction`，不再走会被 `1x` 下限吞掉的本地缩放。
  - 记录本轮 pinch 已展开过的最大 `source_rect`，避免 Mac 延迟回显的较小中间帧把目标区域拉回去，缩小过程不再回弹。
  - 增加本地 source_rect 缩小预览：手势中按“当前局部帧在目标 rect 中的位置”先缩小内容层，等 Mac 新帧到达后再补真实内容；输入坐标映射仍按真实 `source_rect` 计算。

### 本轮已验证

- [x] `git diff --check` 通过。
- [x] `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain` 通过；仍有既有 deprecated warning。
- [x] APK 已覆盖安装到真机 `wsvwypiz7xwslvl7`，三端通过 `scripts/triad_ctl.sh restart` 重启。
- [x] 新会话 `sess-1783303675727-1` 自动 proof 通过：`media_frame_transport=legacy_jpeg`、`first_frame_ms=76`、`render_fps_avg=10.04`，输入执行保持 applied。
- [x] debug 双指张开放大后，Android 物化局部 `source_rect`：`rect=0.28,0.34,0.43,0.32`。
- [x] debug 两指并拢缩小时，目标 rect 连续扩大：`0.45x0.33 -> 0.70x0.51 -> 1.00x0.99`，没有再被中间物化帧拉回小区域。
- [x] 本地预览已触发：`preview_scale=0.956 -> 0.700 -> 0.531 -> 0.369`，并拢过程中 Android 侧会先给出本地缩小反馈。
- [x] Mac 最终切到接近整屏：`source_rect_ppm=0,5946,1000000,988107`。

### 仍需关注

- [ ] 主观手感还需要用户实手确认；本轮修的是缩小手势的即时本地反馈和 source_rect 连续性。
- [ ] 当前可见媒体仍是 `legacy_jpeg`，低 FPS 会继续影响远端新内容补齐速度；最终跟手感仍依赖 WebRTC/H.264/native sender 媒体链路。

## 2026-07-06 全屏低清帧放大修复和高清档加码

本轮目标：修复点击全屏后页面发糊的问题。根因是 Desktop 端全屏 profile 曾把完整桌面帧限制在 `640x416`，第一次提升到 `800x520` 后用户仍反馈模糊；继续排查发现 `currentSessionMediaCaps()` 仍用 Android 真机普通会话上限卡住全屏清晰档，所以全屏横屏仍在放大低清整页图。

### 本轮代码调整

- `apps/desktop/src/main.jsx`
  - 真机全屏 profile 从 `640x416` 提升到当前真机清晰档 `800x520`。
  - 用户反馈 `800x520` 仍然模糊后，新增 Android 真机全屏专用高清 cap：`1600x1040@6fps`、`14Mbps`，并通过 `allowAndroidFullscreenDetail` 绕开普通真机会话的 `800x520` cap。
  - `buildEffectiveAdaptiveProfile()` 的 FPS 下限从固定 `8fps` 放开，允许全屏静态高清档用 `6fps` 换更接近源尺寸的文字清晰度。
  - 全屏查看、全屏 idle restore 和 viewport interaction 都走 `allowAndroidFullscreenDetail`；输入、拖动、捏合仍使用小尺寸交互档，避免大 JPEG 帧抢占输入链路。
  - `legacyFrameStreamIntervalMs()` 对完整桌面高清帧使用低频节奏，定位为“静态看清楚优先”，不是最终流畅视频方案。

### 已验证事实

- [x] Mac 日志复现旧问题：全屏期间持续下发 `max_width=640 max_height=416 source_rect_ppm=0,0,1000000,1000000`，Android 侧收到 `size=640x414 source_rect=full`，整页文字被横屏放大后自然发糊。
- [x] `git diff --check` 通过。
- [x] `cd apps/desktop && npm run build` 通过。
- [x] 三端重启后新会话 `sess-1783305199304-1` 建立，自动 proof 通过：`first_frame_ms=431`、`media_frame_transport=legacy_jpeg`、输入 `8/8 applied`。
- [x] 真实点击 Android “全屏”按钮后，Android 切到 `remote_video_renderer_switch mode=texture fullscreen=true`，Mac 全屏 profile 下发为 `max_width=800 max_height=520 max_fps=24 source_rect_ppm=0,0,1000000,1000000`。
- [x] 全屏后 Android 持续收到 `size=800x517 source_rect=full`，没有再退回旧的 `640x414` 低清整页帧。
- [x] 继续加码高清档后，`git diff --check` 和 `cd apps/desktop && npm run build` 再次通过。
- [x] 三端重启后新会话 `sess-1783307530284-1` 建立，标准 proof 通过：`first_frame_ms=158`、`media_frame_transport=legacy_jpeg`、`remote_input_applied=8/8`。
- [x] 真实点击 Android “全屏”按钮后，Mac 日志确认下发 `max_width=1600 max_height=1040 max_fps=6 codec=jpeg-frame-stream source_rect_ppm=0,0,1000000,1000000`。
- [x] Android 日志确认进入全屏：`remote_video_renderer_switch mode=texture fullscreen=true`、`远端画面已进入全屏`，并持续收到 `size=1600x1034 source_rect=full`。
- [x] 全屏截图 `.rd_runtime/screens/rd_fullscreen_hd_1600_20260706.png` 已保存，SHA256 `12aed879d2912725632ea412b56193e6965e220f5d5692c1ae9bd1fa12c8ba13`，截图为 `2340x1080` PNG。

### 仍需关注

- [ ] 清晰度已从 `640x414`、`800x517` 提升到 `1600x1034`，但当前可见媒体仍是 `legacy_jpeg`；全屏高清样本约 `2.9-4.0fps`，静态文字更清楚，动态流畅度会更差。
- [ ] 继续单纯加大 JPEG 分辨率收益有限；真正同时做到高清和跟手，需要把 `legacy_jpeg` 切回 WebRTC/H.264/native sender 的可用媒体链路。
