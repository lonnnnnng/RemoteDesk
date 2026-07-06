# Android 真机远控 macOS 系统设计与需求文档

Last updated: `2026-07-06 00:26:05 +0800`

本文档记录 Android 真机控制 macOS 桌面路线的当前需求、系统设计、验证口径和下一步任务。结论按当前代码、真机运行日志和截图记录；未通过人工手感确认的项目不会写成已完成。

## 1. 目标和验收边界

### 1.1 产品目标

- Android 手机打开 `远控` 后，可以连接 relay，发现并控制 macOS Desktop agent。
- 手机端远程画面默认完整显示 macOS 屏幕，不裁剪菜单栏、Dock、左右/上下边缘。
- 用户直接在远程画面上完成操作：点击、移动鼠标、长按拖拽、双指滚轮、双指缩放、缩放后双指平移局部视图。
- 点击电脑输入框时，手机端可以打开电脑键盘映射面板，发送常用字符键、功能键、修饰键、方向键和编辑导航键。
- 断线后可以自动恢复，并能继续看到视频和执行输入。

### 1.2 当前验收状态

- 当前可见媒体状态：真机 `wsvwypiz7xwslvl7` 可连接 Mac agent 并控制 macOS，但 Redmi/begonia 当前可见画面走 `legacy_jpeg` 兜底，不是 WebRTC/H.264 最终链路通过。
- 已验证：Android debug 构建、Desktop 前端构建、Go relay 测试、Tauri Rust 测试、脚本语法检查、真机安装、三端启动均通过。
- 已验证：远程画面顶部 `1x / 全屏` 控制和底部 `键盘` 控制在小窗与全屏都可见；全屏截图 `/tmp/remotedesk-fullscreen-buttons-fix.png` 显示完整桌面。
- 已验证：Android 端本地指针反馈层可在全屏鼠标移动时即时显示；截图 `/tmp/remotedesk-fullscreen-local-cursor.png` 同时可见蓝色本地反馈点和 Mac 真实光标。
- 未达标：当前可见链路仍是 `legacy_jpeg`，00:15 真机复测中局部 detail 约 `7.95fps @896x578`、局部 still 约 `4-5fps @1142x737`，不能宣称全屏缩放、缩放后清晰且流畅、鼠标视觉跟手已经完成。
- 已验证：00:15 debug 双指注入通过 manual span fallback 触发 `remote_manual_pinch_begin/end` 和 `remote_viewport_pinch_scale source=manual`，倍率约 `1.185 -> 2.437`；该证据只证明自动化代码路径，不替代真实手指手感验收。
- 已验证：00:15 缩放后横向滑动期间 Mac `input.mouse.move applied=true` 从 `3` 增到 `182`，新增约 `179` 条，执行器为 `macos.cg_event`，说明输入落地接近 `60Hz`。
- 已验证：00:15 局部清晰链路未回归；Mac `source_rect_ppm=294671,306641,410657,410305`，Android 物化为 `source_rect=0.29,0.31,0.41,0.41`。
- 已验证：2026-07-05 05:08 真机 UI tree 中 `remoteViewportContainer`、`remoteViewportContent`、`remoteVideoView` 均为 `[372,0][2043,1080]`，全屏内容层不再沿用旧尺寸；截图 `/tmp/remotedesk-20260705-fullscreen-60hz-swipe.png` 的 SHA256 为 `2ba86b08cb6e2623eadfc946cbdf64240cb5c3fd18077d19af6d5fa9f2fe81d3`。
- 已验证：全屏 4 秒滑动输入链路未丢，Android `input.mouse.move` 208 条，Mac `applied=true` 208 条；Mac sender 交互档可到 `31.59/31.39/31.22fps`。
- 未达标：同一轮真机中 Android 全屏渲染仍从 `11.88fps` 下滑到 `7.74/7.67/6.96fps`，恢复 `1120x724` 全屏基础档后仍约 `4.37-5.38fps`；当前瓶颈继续指向 Android 全屏 Surface/窗口承载方式。
- 已验证但未通过：2026-07-05 05:45 结构性 A/B 显示，`TextureView + EglRenderer` 全屏路径仍跌到约 `7fps` 且 dropped frames 增长；临时放开 Redmi Note 8 Pro 的 MTK AVC 硬解后仍无首帧、`frames_decoded=0`；全屏基础档降到 `800x520` 后 Android 全屏仍约 `8fps`。这些结果说明问题不是单一 SurfaceView 层级或 sender 分辨率问题。
- 已验证但为临时方案：2026-07-05 07:05 relay 已转发 `screen.frame.push`，Android 真机 profile 暂停 H.264 native sender、改用 JPEG 帧流专用路径；真机 `legacy_frame_sample` 稳定约 `9.2-9.6fps @1547x1000`，全屏截图 `/tmp/remotedesk-fullscreen-legacy-only-100ms.png` 完整显示桌面，全屏 mouse move `133/133` applied。
- 已验证但仍未达标：2026-07-05 07:31 JPEG-only 新档位已生效；交互档从 `800x520` 收紧到 `640x416` 后，Android `640x414` 视觉样本提升到 `13.40fps`，全屏长滑动期间为 `12.01/12.96fps`；全屏基础档从 `1120x728` 收紧到 `960x624` 后，Android `960x621` 稳定约 `7.4-8.0fps`。这比上一轮有改善，但仍远低于 `>=24fps`。
- 已验证：Android 端现在会丢弃已经过时的 legacy JPEG 解码任务，本地光标反馈合并到下一帧动画应用；这降低旧帧解码积压和 move UI 热路径压力，但不能把 JSON/Base64/JPEG 降级链路变成最终媒体方案。
- 已验证：2026-07-05 07:58 当前真机截图 `/tmp/remotedesk-current-20260705.png`，SHA256 `3841d295e80e5a9232fc966a7edef29e25a434e2c5b621584f87c36289b061e7`，全屏远程桌面完整显示，左右黑边来自比例适配。
- 已验证：2026-07-05 07:58 `gfxinfo` 当前进程 `Janky frames=40/2752 (1.45%)`、P95 `18ms`、P99 `24ms`；普通 Android UI 绘制相对稳定，当前瓶颈继续指向媒体帧链路。
- 已验证但仍未达标：2026-07-05 07:58 全屏 4 秒长滑动新增日志中，relay 收到/转发 `input.mouse.move` `231/231`，Mac `applied=true` `231`；Android 交互档视觉样本 `12.86/13.32/13.44fps @640x414`，停手恢复全屏档约 `7.5-8.0fps @960x621`。
- 已验证：2026-07-05 08:19 `session.viewport.interaction` 已带 `viewport_x/y/width/height` 与 `focus_x/y`；真机会话 `sess-1783210557558-1` 中 Android send 日志和 relay `session.tool.forwarded` 均出现这些 payload keys。
- 已验证但仍未达标：2026-07-05 08:19 全屏截图 `/tmp/remotedesk-fullscreen-swipe-0816.png` SHA256 `1bf503e97d6a83904b9706bdbbc7304479b5480a5d29463b4e72adbeb6d62980`；全屏长滑动期间 Mac `input.mouse.move applied=true` 时间窗约 `249` 条，Android 交互档约 `13.3fps @640x414`，停手后全屏档约 `7.7-8.0fps @960x621`。
- 已验证：2026-07-05 08:57 Desktop/Rust capture 已支持按 Android viewport 转成 `source_rect_ppm` 后裁剪采集，`screen.frame.push` 携带 `source_rect_*` 元数据，Android 收到局部帧后按区域反算触摸坐标。
- 已验证：2026-07-05 08:57 会话 `sess-1783212760043-1` 的 debug viewport 链路中，Mac `source_rect_ppm=250000,200000,500000,450000`，Android `legacy_frame_sample ... size=1028x599 source_rect=0.25,0.20,0.50,0.45`；截图 `/tmp/remotedesk-during-materialized-source-debug.png` SHA256 `68acfe6ee9f62344196cc6ac9a9071c60388f815513147ed006248377e76fbad` 显示局部帧完整显示，避免局部帧二次放大导致发虚。
- 已验证但仍未达标：2026-07-05 08:57 全屏截图 `/tmp/remotedesk-fullscreen-after-materialized-source.png` SHA256 `0cb8a556540a3d4658fd6031d4146ed24670a2236241fb2434771db98c452c02` 显示桌面完整；全屏 4 秒滑动后 Mac `input.mouse.move applied=true` 从 `4` 增到 `238`，新增约 `234` 条；Android `gfxinfo` 为 `Janky frames=110/2556 (4.30%)`、P95 `20ms`、P99 `36ms`。
- 已验证但仍未达标：2026-07-05 09:16 会话 `sess-1783213961511-1` 中，全屏截图 `/tmp/remotedesk-0915-fullscreen.png` SHA256 `3989f4fe3dfc3aa3d95c7158c3747ab01fd11607425a1ebdee6ece86b0ec7dd6` 和滑动后截图 `/tmp/remotedesk-0916-fullscreen-swipe.png` SHA256 `2b19c1c393ed9b9b0190ff938bc0755921f80f3b5f6719a0786e8d4c4eb4d9d2` 均显示桌面完整。
- 已验证但仍未达标：2026-07-05 09:16 最新 `560x362` 交互档生效，Android 滑动发送汇总为 `4+61+60+60` 条 mouse move，Mac 时间窗内 `input.mouse.move applied=true` 235 条；视觉样本 `13.30/14.35fps @560x362`，停手后恢复 `960x621` 约 `7.4-8.0fps`。
- 已验证但仍未达标：2026-07-05 09:16 debug viewport 触发后，Mac `config.updated max_width=1280 max_height=800 max_fps=24 ... source_rect_ppm=250000,200000,500000,450000`，Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45`；裁剪链路和高质量 JPEG 路径未破坏，但帧率仍约 `7.4-7.6fps`。
- 已验证但仍未达标：2026-07-05 09:37 最终保守版 APK 已安装并重启三端，会话 `sess-1783215376937-1` 建立；标准输入 proof 仍为 `11/11 applied`。全屏截图 `/tmp/remotedesk-0937-final-fullscreen.png` SHA256 `f96edc01c1fd7e17d7d2a530eaa72cff5f1fdcb321ab2479d724c57d16de35b6` 和滑动后截图 `/tmp/remotedesk-0937-final-fullscreen-swipe.png` SHA256 `3537a1d711e72aaf2fe68ef3c781eb7f9a469668fb273620236028aac6a7e2a3` 显示桌面完整；全屏 3 秒滑动后 Mac 新增 `input.mouse.move applied=true` 177 条。
- 已验证为负面结果：2026-07-05 09:32 中间激进版把全屏基础承载面改为 `1.0`、兜底帧调度改为扣除发送耗时、局部 JPEG 质量提到 `88` 后，未提升流畅性；全屏交互档仅 `11.63/11.06fps @560x362`，局部高清仅 `5.85-6.49fps @1028x599`。最终代码已撤回前两项，只保留 pinch/本地承载面错峰和局部 JPEG 质量小幅提升到 `84`。
- 未达标：2026-07-05 09:37 最终保守版全屏基础档多在 `4.36-7.12fps @960x621`，全屏交互档约 `5.73/7.06/6.28/7.46/8.06fps @560x362`；这低于上一轮 `13-14fps` 观测，必须作为当前回归风险继续处理。
- 已验证但仍未达标：2026-07-05 09:42 最终保守版 debug viewport 后，Mac `config.updated max_width=1280 max_height=800 max_fps=24 ... source_rect_ppm=250000,200000,500000,450000`，Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45`；局部高清视觉样本仅 `3.56/3.77fps`，说明裁剪链路仍通，但 Q84 仍不能关闭缩放清晰/流畅体验目标。
- 已验证但仍未达标：2026-07-05 10:04-10:08 单次 JPEG 解码优化后重新安装真机并重启三端，会话 `sess-1783217064539-1`。标准输入 proof `11/11 applied`；基础档 `800x517` 约 `9.4-10.4fps`；远程画面区域 3 秒滑动后 Mac `input.mouse.move applied=true` 新增 `134` 条，交互档 `512x331` 约 `14.70/15.66fps`；debug viewport 后 Mac `1120x700@18fps source_rect_ppm=250000,200000,500000,450000`，Android `1028x599 source_rect=0.25,0.20,0.50,0.45`，局部高清约 `7.16/7.54fps`。改善了 09:37/09:42 的低谷，但仍未达到 `>=24fps`。
- 已验证但仍未达标：2026-07-05 10:21-10:25 继续调整后重新安装并重启三端。会话 `sess-1783217966286-1` 中，全屏真实点击进入后日志显示 `remote_video_renderer_switch mode=texture fullscreen=true`，截图 `/tmp/remotedesk-fullscreen-after-tap2.png` 显示远端桌面完整居中；debug viewport 后 Mac 切到 `1120x700@18fps source_rect_ppm=250000,200000,500000,450000`，Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45`，截图 `/tmp/remotedesk-debug-viewport-after-change.png` 显示局部帧完整。3 秒全屏滑动 Mac `input.mouse.move` 新增 `177` 条，约 `59/s`，均 `applied=true`。重新加载 Desktop UI 预览节流后，会话 `sess-1783218311459-1` 基础档 `800x517` 仍约 `9.7-10.0fps`、交互档 `512x331` 约 `14.35fps`，说明当前瓶颈仍在 JPEG 捕获/编码/Base64/Android 解码链路。
- 已验证但仍未达标：2026-07-05 10:55 继续调整后重新安装并重启三端，会话 `sess-1783219924972-1` 建立。全屏截图 `.rd_runtime/screens/remote_fullscreen.png` 显示远端桌面完整居中；debug viewport 触发后 Mac 先切交互区域档 `512x334@16fps source_rect_ppm=250000,200000,500000,450000`，随后切局部高清档 `1280x800@16fps source_rect_ppm=250000,200000,500000,450000`，Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45`。全屏单指移动压测中 relay 记录 `input.mouse.move` 约 `16-17ms` 间隔转发，Mac 执行器仍为 `macos.cg_event` 且 `applied=true`。该轮证明输入密度和局部高清切档策略仍通，但没有证明真实双指手感已达标。
- 已验证：2026-07-05 11:25 小窗“全屏”按钮命中后由触控层显式 `performClick()` 派发给按钮，并吞掉同一次手势的 `UP/CANCEL`，不再误发为 Mac 鼠标点击；日志出现 `remote_video_renderer_switch mode=texture fullscreen=true`，截图 `.rd_runtime/screens/rd_fullscreen_final_1125.png` SHA256 `207568e6a32b08f92b30a9d6521bc304fe0655bfb6839741269a152433550bd9` 显示远端桌面完整居中。
- 已验证但仍未达标：2026-07-05 11:25 全屏 3 秒滑动后，Mac `input.mouse.move applied=true` 从 `7` 增到 `193`，新增 `186` 条，约 `62/s`，执行器为 `macos.cg_event`；这证明输入落地足够密，但视觉帧率仍受 JPEG fallback 限制。
- 已验证但仍未达标：2026-07-05 11:30 局部裁剪档从 `1120x700`、`800x520` 继续收敛到 `720x468@18fps`。debug viewport 后 Mac `config.updated max_width=720 max_height=468 max_fps=18 source_rect_ppm=250000,200000,500000,450000`，Android 收到 `720x420 source_rect=0.25,0.20,0.50,0.45`；首个样本 `11.46fps`，稳定后约 `6.68-6.89fps`。该档比 `1120x653` 的 `3fps` 更稳，但仍不能写成缩放后顺滑完成。
- 已验证但仍未达标：2026-07-05 11:45 本轮重新调整并安装后，会话 `sess-1783222978563-1` 建立。全屏按钮进入横屏后日志 `remote_video_renderer_switch mode=texture fullscreen=true`，截图 `.rd_runtime/screens/rd_fullscreen_after_zoom_114538.png` SHA256 `38e15b3c136667150bb9b858b4f4ee8338e203cfee27bf521bdd382c5b8ec00e` 显示远端桌面完整居中。
- 已验证但仍未达标：2026-07-05 11:45 debug viewport 后 Mac 先切 `512x334@16fps source_rect_ppm=250000,200000,500000,450000`，随后切 `864x562@16fps source_rect_ppm=250000,200000,500000,450000`；Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45` 并收到 `864x503 source_rect=0.25,0.20,0.50,0.45`，稳定样本约 `5.10-6.76fps`。
- 已验证：2026-07-05 11:45 横屏全屏 900ms 单指滑动新增 Mac `input.mouse.move applied=true` `59` 条，约 `65/s`，执行器 `macos.cg_event`；滑动期间 Mac 临时切 `512x334@30fps`，停手后恢复 `800x520@30fps`。输入落地密度够，但画面视觉 FPS 仍未达标。
- 已验证但仍未达标：2026-07-05 12:10-12:14 会话 `sess-1783224608715-1` 中，基础档 `800x517` 样本约 `9.75/10.17/9.88fps`，普通交互档 `448x290` 样本约 `15.26fps`，debug viewport 后区域移动档正确切到 `512x334@30fps source_rect_ppm=250000,200000,500000,450000`，随后局部高清 `800x466 source_rect=0.25,0.20,0.50,0.45` 样本约 `8.66fps`。
- 已验证：2026-07-05 12:10-12:14 全屏截图 `.rd_runtime/screens/rd_fullscreen_try3_1211.png` SHA256 `da05eeea66dee8ea1781d95c18b7b5f4e4bcac786fbb162d30bc6bf26418a7d4` 显示远端桌面完整居中，左右黑边为比例适配；全屏滑动新增 Mac `input.mouse.move applied=true` `71` 条。
- 已验证：2026-07-05 12:23 Android `session.metrics.report` 已把 legacy JPEG 首帧、帧数和平均 FPS 纳入通用 `first_frame_ms/rendered_frames/render_fps_avg`，并新增 `media_frame_transport` 与 `legacy_* / webrtc_*` 明细；relay combined summary 已出现 `media_frame_transport=legacy_jpeg`、`first_frame_ms=43`、`rendered_frames=66`、`render_fps_avg=9.92`，避免 fallback 有画面时误判为无首帧。
- 已验证但仍未达标：2026-07-05 12:50 会话 `sess-1783226876738-1` 中，全屏截图 `/tmp/remotedesk-fullscreen-1249.png` SHA256 `5bac107777cb25671ec4a1ae4ea3c3d17d02f7e2b0cd996625dd52f51b17519a` 显示远端桌面完整居中；全屏 3 秒滑动 Mac `input.mouse.move applied=true` 新增 `348` 条，执行器 `macos.cg_event`；Android 交互档样本 `16.76/18.43fps @448x290`，基础档约 `9.8-10.1fps @800x517`。
- 已验证但仍未达标：2026-07-05 12:50 debug viewport 后 Mac 先切 `512x334@30fps source_rect_ppm=250000,200000,500000,450000`，随后切 `800x520@12fps source_rect_ppm=250000,200000,500000,450000`；Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45` 并收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，局部高清样本 `9.32/6.82fps`。区域高清链路未回归，但真实双指手感和 `>=24fps` 仍未通过。
- 已验证但仍未达标：2026-07-05 13:09 会话 `sess-1783227836869-1` 中，标准 E2E proof 为 `video_and_input_observed`，`first_frame_ms=107`，`media_frame_transport=legacy_jpeg`，`render_fps_avg=10.14`，`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`；全屏截图 `.rd_runtime/screens/rd_fullscreen_1308.png` SHA256 `c09d6306b81397290b7933554df79d98d5fd59d2e0f6e7272b29fcc4cb3721a2` 显示桌面完整居中。
- 已验证但仍未达标：2026-07-05 13:09 全屏 3 秒滑动后 Mac `input.mouse.move applied=true` 从 `179` 增到 `356`，新增 `177` 条，约 `59Hz`，执行器为 `macos.cg_event`；debug viewport 后 Mac 先切 `512x334@30fps source_rect_ppm=250000,200000,500000,450000`，随后切 `800x520@10fps source_rect_ppm=250000,200000,500000,450000`，Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，局部高清样本约 `6.89/6.98fps`。这证明输入和区域高清链路未回归，但视觉 FPS 仍低于验收线。
- 已验证但仍未达标：2026-07-05 13:35 Android legacy JPEG 显示改为 `RemoteDeskFrameView` 复用绘制路径，局部裁剪帧保留 `ARGB_8888`，Desktop 局部 source rect 更新节流收紧到 `220ms/20000ppm`。会话 `sess-1783229340532-1` 标准 proof 为 `video_and_input_observed`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_frame_view_entered_1332.png` SHA256 `7bbcf3186bebc5aa8bf98da3b7716e1d1bb8c9910f1b926101fbbe74b3c51e79` 显示桌面完整；3 秒全屏滑动新增 `178` 条 Mac `input.mouse.move applied=true`，约 `59Hz`；debug viewport 后 Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，截图 `.rd_runtime/screens/rd_debug_viewport_after_frame_view_1334.png` SHA256 `3251a824b6f7168e19792f0ab8ca6e078da1dce638afb16977cdbed754702670`。该轮输入、完整显示和局部高清链路未回归，但视觉 FPS 仍是基础档约 `10fps`、滑动期最高约 `16.65fps`、局部高清约 `7fps`，不能关闭缩放/鼠标流畅目标。
- 已验证但仍未达标：2026-07-05 13:46 Android legacy JPEG 解码新增 Bitmap 复用池，已退场帧优先作为下一帧 `inBitmap`，失败时无复用重试；Base64 帧解码优先 `NO_WRAP`。会话 `sess-1783230202122-1` 标准 proof 为 `video_and_input_observed`、`first_frame_ms=153`、`render_fps_avg=10.00`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_bitmap_reuse_1345.png` SHA256 `b707042907583f5875d1af01f08c15d8915171edbe70d5d2397391cf35abc441` 显示桌面完整；3 秒全屏滑动新增 `181` 条 Mac `input.mouse.move applied=true`，约 `60Hz`；debug viewport 后 Mac 先切 `512x334@30fps source_rect_ppm=250000,200000,500000,450000`，随后切 `800x520@10fps source_rect_ppm=250000,200000,500000,450000`，Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，截图 `.rd_runtime/screens/rd_debug_viewport_bitmap_reuse_1346.png` SHA256 `e6097023b30e4de0554815fe92f2383e52c3faabf846788d14fa15743a59e02c`。该轮未出现 `legacy_bitmap_reuse_failed`，输入、完整显示和局部高清链路未回归；视觉 FPS 仍是基础档约 `10fps`、交互档约 `15-18fps`、局部高清约 `6.9fps`。
- 已验证但仍未达标：2026-07-05 14:32 本轮继续调整全屏 pinch、局部高清和鼠标移动。Android move 采样收紧到 `6ms / 0.00024`，Mac move 队列节流收紧到 `6ms`；全屏 pinch update 阶段不再反复重配 `source_rect`，只保持整屏/低延迟帧，pinch end 后再切局部高清；局部高清档从 `1280x834@16fps` 收敛到 `1024x668@20fps`。会话 `sess-1783233039204-1` 标准 proof 为 `video_and_input_observed`、`first_frame_ms=55`、`media_frame_transport=legacy_jpeg`，全屏截图 `/tmp/remotedesk-final-fullscreen.png` 显示桌面完整居中；debug viewport 后 Mac 先切 `800x520@24fps source_rect_ppm=250000,200000,500000,450000`，随后切 `1024x668@20fps source_rect_ppm=250000,200000,500000,450000`，Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45`；全屏长滑后 relay combined 显示 `remote_input_last_count=39`、result push 汇总 `16/16 applied`，Mac 日志中的 `input.mouse.move` 均为 `applied=true executor=macos.cg_event`。该轮证明全屏完整、输入落地和区域高清链路未回归，鼠标落点数较上一轮更密；但 `render_fps_avg≈6.10fps`，仍不能关闭全屏缩放/鼠标视觉流畅目标。
- 已验证：2026-07-05 15:10 本轮补齐会话工具 result 闭环后，真机会话 `sess-1783235440137-3` 标准 proof 仍为 `video_and_input_observed`、`first_frame_ms=111`、`media_frame_transport=legacy_jpeg`、`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。共享剪贴板双向 result 已验证：Android 收到 Mac 文本并回传 `clipboard.result`，Android 发送文本后收到 Mac 回传 `clipboard.result`。文件传输双向 result 已验证：Mac -> Android 文件保存到 app-private fallback 并回传 `file.transfer.result`；Android -> Mac 文件保存到 `~/Downloads/RemoteDesk/remotedesk-android-to-mac-tool-test2.txt`，源/目标 SHA256 均为 `c8feb20cf6ef5cfb5ee153e4b88fe33d883fbded2895b2138ae205cc0b0fa76b`。
- 已验证但仍未达标：2026-07-05 15:33 本轮将局部停手高清档从 `1088x708@18fps` 回收到 `960x624@16fps`，保持窗口从 `7000ms` 缩短为 `4200ms`。会话 `sess-1783236665948-1` 标准 proof 通过，`first_frame_ms=47`、`media_frame_transport=legacy_jpeg`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_detail_tune_1531.png` SHA256 `275a945fa141aee4497d9b69b02ab7ee2d3d993233686d683c6cca7904021109` 显示桌面完整。全屏 3 秒滑动新增 Mac `input.mouse.move applied=true` `159` 条；debug viewport 后 Mac 切到 `560x364@24fps source_rect` 再切 `960x624@16fps source_rect`，Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，样本 `6.76/5.25fps`，截图 `.rd_runtime/screens/rd_debug_viewport_960_1533.png` SHA256 `bc0d7faecc82c4dcf68b639537f8deb5d4b43586d168fb74643783ff5f702394`。
- 已验证但仍未达标：2026-07-05 15:50 本轮新增全屏 pinch 临时硬件合成层、缩放/局部帧高质量 Bitmap 缩放，并把鼠标 move 收敛为 `16ms / 0.00030`。会话 `sess-1783237518336-1` 标准 proof 为 `video_and_input_observed`、`first_frame_ms=62`、`media_frame_transport=legacy_jpeg`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_smooth_tune_1547.png` SHA256 `de5fb682c200f1cacc168a1b80602bc46ddad985a2a182dbcdf4290ad0a77d7f` 显示桌面完整。全屏三段滑动新增 Mac `input.mouse.move applied=true` `150` 条，约 `55Hz`，交互档最高 `16.92fps @448x290`；debug viewport 后 Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清样本 `5.59/5.22fps`。该轮证明本地合成和输入节奏未引入回归，但 JPEG fallback 仍不能满足 `>=24fps`。
- 已验证但仍未达标：2026-07-05 16:04-16:06 本轮新增 pinch 进行中整屏 legacy JPEG 展示节流、Mac 鼠标移动队列从原生输入开始计 `16ms` 节流窗口，并把局部 JPEG 锐利档阈值提高到 `450k` 像素。会话 `sess-1783238649036-1` 标准 proof 为 `video_and_input_observed`、`first_frame_ms=57`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=9.66`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_current_tune.png` SHA256 `8e054ea91b2c16a5989983be38b3cc05bdd87f455fd2988d15042e89b648838c` 显示桌面完整。全屏 3 秒滑动新增 Mac `input.mouse.move applied=true` `170` 条，约 `56-57Hz`，交互档最高 `18.24fps @448x290`；debug viewport 后 Mac 先切 `560x364@24fps source_rect` 再切 `960x624@16fps source_rect`，Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清样本 `5.28/5.39fps`。该轮证明输入执行节奏和 source_rect 链路未回归，但 JPEG fallback 仍不能满足全屏缩放顺滑、缩放后清晰和鼠标视觉跟手目标。
- 已验证但仍未达标：2026-07-05 16:35 本轮把局部高清从 `1024x668@12fps` 回收到 `960x624@12fps`，保持窗口缩短为 `4400ms`，局部 JPEG 质量回收到 `84/80`。会话 `sess-1783240363109-1` 标准 proof 为 `video_and_input_observed`、`first_frame_ms=55`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=10.07`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_quality_tune.png` SHA256 `3f97b4e4b7d985089183cc54d7168872d6f7993755eb2b79f6ad30c8261543d6` 显示桌面完整。全屏 3 秒滑动新增 Mac `input.mouse.move applied=true` `178` 条，约 `59Hz`；debug viewport 后 Mac 先切 `560x364@24fps source_rect` 再切 `960x624@12fps source_rect`，Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清样本 `8.06/5.18fps`。该轮证明输入和全屏显示未回归，局部高清首样本略稳，但 JPEG fallback 仍不能满足最终体验目标。
- 已验证但仍未达标：2026-07-05 19:45 本轮在 Android 全屏 pinch 期间继续关闭整屏 Bitmap 高质量滤波，停手后再恢复局部高清；Mac/Rust capture worker 增加采样日志，并在完整帧无裁剪且目标尺寸不变时跳过多余 BGRA 拷贝。APK SHA256 `9782281fb41bcea56c71668667eb8a40540990736e39ad813ad37e4e20090e6b` 已安装真机。会话 `sess-1783251823436-1` 标准 proof 为 `video_and_input_observed`、`first_frame_ms=38`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=9.47`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_worker_tune_1944.png` SHA256 `7cf2ea4cd375a343feb3acd841f3d61ad9fd3336e48ee81c46fd5a5a6a5efa97` 显示完整桌面；debug viewport 截图 `.rd_runtime/screens/rd_debug_viewport_worker_tune_1945.png` SHA256 `fa3e9ad29321850f87f94324c4bdc9a693939c7fe6e9f5424b2cc054252d7b87`，Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清约 `5.46/4.47fps`。Android `gfxinfo` 在全屏滑动窗口为 `Janky frames=0 (0.00%)`、P95 `13ms`、P99 `15ms`；Mac worker 则显示 `800x517 target_interval_ms=33` 时 `last_capture_ms` 常见约 `99-112ms`，`640x414 target_interval_ms=41` 时约 `73-90ms`，overruns 持续增加。结论：Android UI 合成本身不是主要 jank 源，JPEG fallback 捕获/编码/传输/解码链路已被证据证明不是最终流畅方案。
- 已验证但仍未达标：2026-07-05 21:11 本轮 Android 鼠标 move 消费最近最多 `4` 个 `MotionEvent` 历史点，按真实事件时间入队；pinch 期间整屏预览帧间隔调整到 `2200ms`。APK SHA256 `3418a054e54419c73b4155a1d931f1c8244bfc55c1e8b5c2d3904ab4f3ebd93e` 已安装真机。会话 `sess-1783256887975-1` 标准 proof 为 `video_and_input_observed`、`first_frame_ms=43`、`media=legacy_jpeg`、`render_fps_avg=9.91/11.20`、`remote_input_applied=16/16`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_history_points_2109.png` SHA256 `81324c457568b2448c52cc7f590d6291be23a5a22215a3bd8225a04666ae46c9` 和滑动后截图 `.rd_runtime/screens/rd_fullscreen_after_history_points_swipe_2110.png` SHA256 `0f0c2de71865a3fde2fa0b2cb11e2e980999db88b507f983afe0a3dd5c93988f` 均显示完整桌面。全屏滑动新增 `157` 条 Mac `input.mouse.move applied=true`，applied 间隔多为约 `16-20ms`；Android `gfxinfo` 在全屏/滑动/source_rect 后 jank 约 `0.27-0.45%`、P95 `15ms`、P99 `16-17ms`。debug viewport 后 Mac `source_rect_ppm=250000,200000,500000,450000`，Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，截图 `.rd_runtime/screens/rd_debug_viewport_after_history_points_2111.png` SHA256 `cf449cd4a42bc2f003a5de31a94720b1a0885289b727362bd07f95d49b42f56d`；局部高清首样本约 `10.56fps`，随后约 `6.99/7.07fps`。结论：历史触摸点补偿改善输入轨迹连续性，全屏完整显示和 source_rect 没有回归；但 `legacy_jpeg` 可见链路仍不能满足 `>=24fps` 和真实双指 pinch 体验验收。
- 未验证：双指缩放真实手感和缩放后细节清晰度仍需要人工真机确认；并发 `adb input swipe` 仍未触发 `remote_viewport_pinch_scale`，最新尝试被系统拆成普通鼠标移动。

## 2. 总体架构

```text
Android Controller
  - Kotlin UI
  - WebRTC receiver
  - touch/keyboard input mapper
  - metrics reporter
        |
        | WebSocket JSON envelope
        v
Relay + TURN
  - device registry
  - session negotiation
  - offer/answer/ICE forwarding
  - input/result forwarding
  - metrics aggregation
        |
        | WebSocket JSON envelope + WebRTC media
        v
macOS Desktop Agent
  - Tauri/React UI
  - ScreenCaptureKit capture
  - Rust native sender
  - OpenH264 video track
  - JPEG frame fallback
  - Core Graphics input executor
```

## 3. Android 控制端设计

### 3.1 远程画面显示

- 小窗和全屏共用 `remoteViewportContainer`。
- 远端 WebRTC 画面使用 `SurfaceViewRenderer`，缩放模式固定为 `SCALE_ASPECT_FIT`。
- 父容器黑底并裁剪子内容，完整显示远端桌面；黑边表示宽高比适配，不表示画面被截断。
- 缩放和平移作用于 `remoteViewportContent`，触摸事件由透明 `remoteTouchLayer` 承接。
- 缩放过程中只使用 View transform 跟随手势，不在连续捏合中重建视频 Surface 尺寸；缩放开始时临时启用 `remoteViewportContent` 硬件合成层，停手并完成必要的承载面提交后再释放，避免每一帧都重绘完整 View 层级。全屏下手势中保持 `1.0x` 轻量合成，停手后最多补到 `1.18x` 且继续受 `3,600,000` 像素上限保护；剩余倍率继续用轻量 transform 和 Mac 端 `source_rect` 局部帧承接。
- 全屏进入和远端帧比例变化后，`remoteViewportContent` 的重置延后到布局完成后的下一帧，避免容器已经变成全屏尺寸、内容层仍停在小窗旧尺寸。
- 全屏状态继续保持 WebRTC hardware scaler 开启。05:08 证据显示固定 Surface 尺寸没有解决低 FPS，后续应做全屏承载方式 A/B，而不是继续在同一 Dialog Surface 上微调。
- 代码中保留 `TextureView + EglRenderer` 渲染路径，当前开关为 `REMOTE_FULLSCREEN_USE_TEXTURE_RENDERER=true`，仅在全屏时接管视频 sink；10:21 真机日志已确认全屏进入后使用 `mode=texture`。该路径解决全屏 View transform 与视频层不同步的风险，但实测仍不能把 JPEG fallback 提升到 `>=24fps`。
- 全屏基础渲染承载面手势中保持 `1.0x`，避免全屏连续捏合时反复重建 Surface；双指缩放停手后最多补到 `1.18x` 且受像素上限保护，不再提交 2x/3x 大 Surface。清晰度主要交给 Mac 端 `source_rect` 局部高清源帧，避免大尺寸 Surface 重建造成 pinch 卡顿。
- 双指缩放和缩放后的双指平移会发送 `session.viewport.interaction` 轻量提示；该消息不执行远端输入，同时携带完整桌面归一化可视区域和焦点，让 macOS sender 临时进入交互采集档，并为后续区域高清裁剪提供输入。
- 当 Android 收到非整屏 `source_rect` 局部帧时，会把该帧视为已经物化的当前可视区域：本地缩放状态归一、偏移清零，再允许用户在该局部帧上继续二次捏合；渲染和触摸映射始终按当前 transform 计算，避免裁剪后的局部帧被旧倍率锁住或二次放大。
- `1x`、全屏和键盘按钮在初始化时显式 `bringToFront` 并提升 elevation。由于远控触控层会覆盖整块视频区域，命中这些工具按钮时由触控层直接调用按钮 `performClick()`，并吞掉同一手势的后续事件，避免“点全屏/键盘”被误发成远端鼠标点击。

### 3.2 手势模型

- 单指点击：映射为 `input.mouse.move` + `input.mouse.button left down/up`。
- 单指移动：映射为 `input.mouse.move`；单指移动和长按拖拽都会消费最近最多 `4` 个 `MotionEvent` 历史点，当前采样限制为 `12ms / 0.00032`，尾帧最大延迟 `12ms`，让远端输入接近屏幕刷新节奏，同时避免过密信令/ACK 抢占 Android 当前 JPEG 兜底画面的解码与 UI 线程。由于当前画面仍是 JPEG fallback，输入落点变密只能改善控制反馈，不能替代媒体帧率达标。
- 长按后拖拽：发送鼠标左键 down，移动中持续发送 mouse move，结束时发送 left up。
- 双指纵向同向移动：未放大时映射为滚轮。
- 双指张开/并拢：本地缩放远程画面，不发送桌面端缩放命令；当系统 `ScaleGestureDetector` 未稳定进入缩放时，manual span fallback 会直接按两指距离变化计算倍率，并记录 `remote_viewport_pinch_scale source=manual`。
- 缩放后单指移动：继续映射为电脑鼠标移动，避免用户放大后丢失指针控制。
- 缩放后双指同向拖动：平移本地局部视图，便于查看放大后的桌面区域，不向电脑发送滚轮或鼠标输入。
- 单指移动时显示蓝色本地指针反馈点。该反馈点只表示控制端已接收移动意图，不替代 Mac 端真实光标，也不替代 `input.result.push` proof；连续 move 的光标 UI 更新合并到 `postOnAnimation`，并按 `18ms` 预测做轻量提前显示，避免每个历史点都触发布局/绘制。
- Mac 端对成功的 `input.mouse.move` 仍逐条执行，但 `input.result.push` 回执按 `240ms` 节流；Android 端只需要周期性知道远端仍在应用移动，避免每 12ms 回传结果消息拖慢控制端消息处理。

### 3.3 键盘映射

- 键盘面板由远程画面内的 `键盘` 按钮打开。
- 面板不包含右侧数字小键盘。
- 按键按 6 排展示，尽量接近真实键盘布局。
- 修饰键支持锁定组合，发送到 Mac 端时由 `macos.cg_event` 执行。

### 3.4 亮屏策略

- 远控 Activity 设置 `FLAG_KEEP_SCREEN_ON`。
- 该策略用于避免会话中途息屏，尤其是真机自动化测试、人工双指测试和长时间观察。

## 4. macOS Agent 设计

### 4.1 采集

- macOS 使用 ScreenCaptureKit。
- Desktop JS 根据 controller profile 下发 capture 配置。
- Android 真机基础档：`800x520@30fps / 9Mbps / scaleResolutionDownBy=1.0`，用于 JPEG fallback 下的小窗/普通状态可见画面。
- Android 真机普通交互档：`384x250@30fps`，鼠标/滚轮/按键输入可触发，优先降低解码和传输压力。
- Android 真机全屏档：`640x416@24fps`，用于全屏完整显示和低强度交互。
- Android 真机 pinch 预览档：`320x208@8fps`，两指开合过程中只提供低频背景帧，主要依靠本地 transform 保持手势连续。
- Android 真机放大区域移动档：`512x333@22fps source_rect`，放大后鼠标移动或双指平移期间只采集当前局部区域，目标是优先跟手。
- Android 真机局部 detail 档：`896x582@10fps source_rect`，停手后先把当前视口物化为局部清晰源。
- Android 真机局部 still 清晰档：`1280x832@5fps source_rect`，用于静止读文字/点输入框；00:15 实测约 `1142x737 source_rect=0.29,0.31,0.41,0.41`，只有约 `4-5fps`，只代表清晰源链路，不代表顺滑。
- 区域高清裁剪已在 JPEG fallback 链路落地：Desktop JS 从 Android `viewport_*` 生成 `source_rect_ppm`，Rust capture 先按完整源采集再裁剪区域并按目标尺寸编码，`screen.frame.push` 附带 `source_rect_*` 和完整桌面尺寸；Android 用这些元数据把局部帧触摸坐标还原到完整桌面归一化坐标。
- 当前区域高清还不是最终媒体方案：Redmi 真机仍绕过 H.264/WebRTC，局部帧经 JSON/Base64/JPEG 传输，视觉 FPS 仍不达标，真实双指手感也未人工验收。
- 本轮实测 Mac 端首帧为 `1547x1000`，Android 端首帧为 `1546x1000`。

### 4.2 编码和发送

- Rust native sender 创建本地 H.264 track。
- 当前编码器为 OpenH264，配置为低复杂度、Baseline、4 线程、目标码率 `8Mbps`。
- 当前主要瓶颈是 `BGRA -> YUV -> H.264` 软件路径；1000p 下平均编码耗时约 `57-61ms`，实际只能到约 `14fps`。
- 这个瓶颈会直接限制 Mac 真实光标在手机画面中的刷新率；Android 本地指针和更密的 move 事件只能改善控制反馈，不能把 14fps 视频变成 24/30fps。
- 05:08 复测进一步分层：`800x517` 交互档在 Mac sender 侧已经能到 `31fps`，`1120x724` 全屏基础档也能到约 `24fps`；但 Android 全屏渲染仍掉到个位数 FPS。因此 P0 不再是继续降低 sender 分辨率，而是替换/对比 Android 全屏承载方式。

### 4.3 输入执行

- macOS 端通过 Core Graphics 执行输入。
- relay 收到 Android 输入后转发到 agent。
- agent 执行后回传 `input.result.push`。
- 本轮真机验证中输入回执为 `applied/macos.cg_event`，覆盖 click、drag、keyboard、wheel。

## 5. 会话工具通道

### 5.1 共享剪贴板

- 协议消息：`clipboard.text`、`clipboard.result`。
- relay 要求 `session_id` 存在，且发送方必须是当前会话参与者。
- payload 包含 `clipboard_id`、`text`、`source_platform`、`created_at`。
- relay 限制文本最大约 `256KB`，避免剪贴板误传大块内容压垮信令通道。
- Android 端通过“发剪贴板”按钮读取本机剪贴板并发送，收到远端剪贴板后写入 Android 系统剪贴板。
- Desktop 端会话工具栏“剪贴板”按钮读取桌面剪贴板并发送，收到远端剪贴板后写入桌面剪贴板。
- 接收端写入系统剪贴板后必须回传 `clipboard.result`。`applied=true` 表示目标系统剪贴板已经写入，`applied=false` 必须带 `error_detail`；发送端不能只把 relay ACK 当作剪贴板成功。

### 5.2 文件传输

- 协议消息：`file.transfer.start`、`file.transfer.chunk`、`file.transfer.complete`、`file.transfer.result`。
- relay 要求 `session_id` 存在，且发送方必须是当前会话参与者。
- Android 和 Desktop 当前单文件上限均为 `64MB`。
- 文件按 `192KB` 原始字节分块，chunk 使用 base64 放入信令消息；relay 限制单块 base64 约 `384KB`，总块数最大 `512`。
- Android 端通过系统文件选择器授权读取本机文件；收到远端文件后，Android 10+ 通过 `MediaStore.Downloads` 保存到公开 `Downloads/RemoteDesk/`，旧系统退回 app 专属下载目录。
- Desktop 端通过会话工具栏“文件”按钮选择文件；收到远端文件后，Tauri 桌面端固定保存到 `$HOME/Downloads/RemoteDesk/`，浏览器环境退回下载链接。
- Android 14 scoped storage 下，debug path 不能直接读取 `/sdcard/Download/...`，否则会触发 `EACCES`；真实用户发送必须走系统文件选择器授权，自动化调试可使用 app-private 文件。
- 接收端必须在分块收齐、大小校验、可选 SHA256 校验和本地保存后回传 `file.transfer.result`。`file.transfer.complete` 只表示发送端分块发完，不能作为目标端保存成功的证据。
- 失败场景需要回传 `file.transfer.result applied=false`，例如 start/chunk payload 非法、分块缺失、大小不匹配、SHA256 不匹配或本地保存失败。
- 当前文件传输适合小文件和 proof，不适合大文件、高速传输或断点续传；后续若要产品化，应改成数据通道或 HTTP 分片通道。

## 6. Legacy JPEG 降级通道

- 协议消息：`screen.frame.push`。
- relay 验证会话参与者后转发，payload 必须包含 `frame_id/content_b64/frame_width/frame_height`，`mime_type` 仅允许 `image/png/image/jpeg/image/webp`。
- Android 真机收到帧后，在 WebRTC 没有可用首帧时显示到 `remoteFrameView`；如果 WebRTC 视频首帧正常，legacy 帧会被忽略。
- Redmi Note 8 Pro 当前临时启用 `ANDROID_PHONE_LEGACY_FRAME_STREAM_ONLY=true`：Desktop 不启动无首帧的 H.264 native sender，只推 JPEG 帧流，避免 CPU 被无效 H.264 编码占用。
- 当前帧间隔按档位动态选择，且发送侧跳过重复 capture timestamp。真机当前折中：基础档 `800x520@30fps`、普通交互档 `384x250@30fps`、全屏档 `640x416@24fps`、pinch 预览 `320x208@8fps`、局部移动 `512x333@22fps source_rect`、局部 detail `896x582@10fps source_rect`、局部 still `1280x832@5fps source_rect`；00:15 实测局部 detail 约 `7.95fps @896x578`，局部 still 约 `4-5fps @1142x737 source_rect=0.29,0.31,0.41,0.41`，清晰度链路可用但流畅性仍未达标。
- 12:23 已验证 legacy JPEG 帧进入统一质量指标：Android 会在 `session.metrics.report` 中同时报告通用视频字段和 `legacy_* / webrtc_*` 明细，relay combined summary 会带 `media_frame_transport=legacy_jpeg`。这只修正验收口径，不代表 JPEG fallback 流畅性达标。
- Android 端 legacy 解码队列只渲染最新帧，过时任务在解码前或回主线程前丢弃，避免 1000p JPEG 解码积压导致画面延迟。
- Android 端 legacy JPEG 解码已改为单次 `BitmapFactory.decodeByteArray` 后复核真实 bitmap 尺寸，不再每帧先 `inJustDecodeBounds` 再完整解码。该优化降低了 JPEG fallback 的 CPU 成本；10:07 交互档从上一低谷提升到约 `15fps`，但仍不能根治 `>=24fps` 目标。
- Android 端 legacy JPEG 显示改为 `BitmapDrawable` 并启用 `filterBitmap/dither`。这只改善全屏本地缩放时的采样毛边，不能增加源帧真实细节；缩放后的真实清晰度仍取决于 `source_rect` 局部帧和最终媒体链路。
- 2026-07-05 13:35 起，Android 端 legacy JPEG 显示改为 `RemoteDeskFrameView`：同一个 View 使用带 `FILTER_BITMAP_FLAG/DITHER_FLAG` 的 `Paint` 绘制最新 Bitmap，不再每帧创建新的 `BitmapDrawable`。局部 `source_rect` 帧即使尺寸低于普通交互阈值也使用 `ARGB_8888` 解码，避免缩放后文字和按钮边缘被 RGB_565 色阶损失继续放大。该优化降低主线程对象抖动并改善局部清晰度上限，但仍受 JPEG/Base64/Bitmap 吞吐限制。
- 2026-07-05 13:46 起，Android 端 legacy JPEG 解码维护一个最多 2 张的 Bitmap 复用池：当前帧和上一帧继续保留给绘制，已退场帧才可作为下一次 `BitmapFactory.Options.inBitmap`；如果设备或尺寸不兼容，会记录 `legacy_bitmap_reuse_failed` 并无复用重试。该设计只降低 Bitmap 分配和 GC 风险，不改变帧传输协议，也不能突破 JPEG/Base64/Bitmap 的整体吞吐上限。
- 2026-07-05 16:06 起，Android 在 `ScaleGestureDetector` 正在进行且当前帧是整屏 legacy JPEG 时，会把展示刷新节流到约 `180ms` 一次；局部 `source_rect` 帧不节流。这个设计让双指捏合期间优先保本地 transform 连续性，避免整屏 JPEG 解码和 View 重绘抢手势帧；它不提升源帧真实 FPS，也不能替代真实双指手感验收。
- 2026-07-06 00:15 起，Android 增加 manual span fallback：当 `ScaleGestureDetector` 未稳定进入缩放时，直接按两指距离计算倍率并触发 `remote_viewport_pinch_scale source=manual`。这解决 debug 注入路径里缩放事件不闭环的问题，但真实手指手感仍必须人工或 instrumentation 验收。
- 该通道只用于恢复可见性和继续人工/脚本测试，不是最终媒体架构。最终仍需要设备能力分层、可用硬解/软解策略或替代 codec。
- Desktop 端 `screen.frame.push` 的实际发送不降频，但本地 React UI 预览刷新节流到 `500ms`；10:25 复测显示该节流没有显著提高 Android 视觉 FPS，因此它只是降低桌面端附带开销，不是根治方案。

## 7. 媒体质量策略

### 7.1 当前策略

- 不再默认使用旧 720p 整屏帧，因为全屏和本地放大时会明显发糊。
- 当前 Redmi/begonia 可见链路是 `legacy_jpeg`，不是最终 WebRTC/H.264；当前策略以“完整可见、输入可落地、局部 source_rect 可读”为临时目标，不能写成最终流畅媒体架构。
- 整屏基础档为 `800x520@30fps`，全屏档为 `640x416@24fps`，普通交互档为 `384x250@30fps`；这些档位降低了解码压力，但真机可见 FPS 仍达不到 `>=24fps`。
- 缩放清晰度当前分三层处理：手势中用 `320x208@8fps` 低频背景帧 + 本地 transform；松手后用 `512x333@22fps` 或 `896x582@10fps` 物化当前 `source_rect`；静止读文字时再尝试 `1280x832@5fps` still 清晰档。
- 鼠标“看起来更跟手”的当前策略是本地指针反馈层、`12ms / 0.00032` 输入采样、历史触摸点消费、`18ms` 本地预测、带采样时间的尾帧补发，以及移动中临时降档；真实远端光标仍受 JPEG/Base64/Bitmap 降级链路低 FPS 影响。
- 00:15 真机复测显示：缩放后输入链路接近 `60Hz` 落到 Mac，但局部 detail 仍只有约 `7.95fps`，still 只有约 `4-5fps`。因此当前结论是“输入链路和局部清晰源路径未回归”，不是“视觉顺滑和清晰同时达标”。
- macOS CoreGraphics 兜底采集的 BGRA 缩放已从最近邻改为双线性，避免 fallback 路径在桌面文字/细线缩小时引入明显锯齿；当前主路径仍是 ScreenCaptureKit。
- 2026-07-05 04:07 真机复测显示：全屏滑动期间 Mac sender 交互档可到 `31.50fps`、`encode_ms_avg=15.6ms`，但恢复 `1547x1000` 清晰档后仍长期约 `14fps`、`encode_ms_avg≈58ms`；Android 渲染侧长会话后还会降到 `4-9fps` 并出现 `render_fps_streak`，不能宣称流畅性完成。
- 2026-07-05 04:24 真机复测显示：Android 端已节流高频 `input.mouse.move` ack/result UI 刷新，Mac sender 交互档进一步降到 `640x416@30fps`，实测 Mac 侧 `640x414` 可到 `30.63fps`、`encode_ms_avg=10.2ms`；但 Android 全屏渲染仍只有 `7.57-9.88fps`，所以瓶颈已转向 Android 全屏渲染/Surface 合成/解码路径，不能宣称鼠标画面流畅完成。
- 2026-07-05 04:44 真机复测显示：`fullscreen` 视图交互提示已通过 relay 转发，Mac sender 可在全屏基础档 `1120x724` 达到约 `23.7fps`，在全屏长滑动低延迟档 `800x517` 达到 `31.68/31.41fps`；但 Android 全屏渲染仍只有 `6.62-10.44fps`，因此单纯调 sender 分辨率不能关闭全屏流畅性问题。
- 2026-07-05 05:45 真机复测显示：TextureView 全屏、MTK AVC 硬解、全屏 `800x520` 低档都不能闭环。当前设计结论是：Redmi Note 8 Pro/begonia/Android 14 需要设备能力分层，优先评估编码格式和解码器兼容性，而不是继续只调 UI 层级或采集分辨率。
- 2026-07-05 07:05 真机复测显示：JPEG-only 后可见性恢复，`1547x1000` 能稳定约 `9.3fps`，全屏 mouse move 输入 `133/133` applied；但该结果仍低于 `>=24fps` 流畅验收，只能作为临时降级。
- 2026-07-05 07:31 真机复测显示：过时 JPEG 解码丢弃、本地光标动画帧合并、交互档 `640x416` 和动态 JPEG 质量生效后，自动 proof 交互样本为 `13.40fps @640x414`，全屏长滑动交互样本为 `12.01/12.96fps @640x414`；全屏基础档 `960x621` 仍只有 `7.4-8.0fps`，清晰档 `1547x1000` 仍只有约 `3.4-3.8fps`。因此只能记录为“改善”，不能关闭视觉流畅度目标。
- 2026-07-05 07:58 真机复测显示：普通 Android UI 帧 jank 只有 `1.45%`，但远控画面仍只有交互档约 `13fps`、全屏档约 `8fps`；继续提高鼠标事件频率或继续压全屏分辨率，都不能根治用户看到的低帧率。
- 2026-07-05 08:19 真机复测显示：区域高清所需视口字段已从 Android 到 relay 再到 Mac 会话参与方；当时仍是 JPEG fallback 整屏帧，视觉 FPS 仍为交互档约 `13.3fps`、全屏档约 `7.7-8.0fps`，不能关闭流畅性或缩放后清晰度目标。
- 2026-07-05 08:57 真机复测显示：区域高清 source rect 已在 JPEG fallback 链路打通，局部帧 `1028x599 source_rect=0.25,0.20,0.50,0.45` 可见且不再二次放大；但视觉 FPS 仍只有约 `7fps` 量级，真实双指手感和最终 WebRTC 区域媒体链路未闭环。
- 2026-07-05 09:16 真机复测显示：交互档降到 `560x364` 后，Android 视觉样本为 `13.30/14.35fps @560x362`，输入链路仍能落到 Mac；pinch debug viewport 后能进入 `1280x800@24fps source_rect` 局部高清档并回传 `1028x599` 局部帧。但两者都没有关闭 `>=24fps` 和真实双指手感目标。
- 2026-07-05 09:37 真机复测显示：输入链路仍稳定，但视觉 FPS 进一步下探到 `5-8fps` 区间；短期继续在 JPEG/Base64/Bitmap 链路上微调参数收益有限，下一步应优先把 WebRTC/硬编码或设备 codec 能力分层拉起来，而不是继续盲目提高 JPEG 频率或质量。
- 2026-07-05 10:08 真机复测显示：单次 JPEG 解码优化后交互档改善到 `14.70/15.66fps @512x331`，局部高清改善到 `7.16/7.54fps @1028x599 source_rect=0.25,0.20,0.50,0.45`；这证明 Android 解码热路径还有优化空间，但结果仍远低于 `>=24fps`，最终设计仍必须转向 WebRTC/硬编码或设备 codec 能力分层。
- 2026-07-05 10:25 真机复测显示：全屏 TextureView 默认启用、全屏基础承载面 `1.0x`、move 回执节流和 Desktop 本地预览节流后，输入链路已达到约 `59` 条 move/s 且全部 applied，但视觉帧率仍停留在基础档约 `10fps`、交互档约 `14fps`、局部高清约 `7-8fps`。因此本轮结论是“输入更顺、1x 全屏不再主动降采样、区域高清链路保持可用”，不是“缩放/远端鼠标视觉流畅已完成”。
- 2026-07-05 11:30 真机复测显示：继续把局部高清降到 `720x468@18fps` 后，Android 收到 `720x420 source_rect=0.25,0.20,0.50,0.45`；首样本 `11.46fps`，稳定样本约 `6.68-6.89fps`。这比 `1120x653` 的 `3fps` 更稳，但 JPEG/Base64/Bitmap 链路仍不能满足缩放顺滑目标。
- 2026-07-05 11:45 真机复测显示：局部高清提高到 `864x562@16fps` 后，Android 收到 `864x503 source_rect=0.25,0.20,0.50,0.45`；稳定样本约 `5.10-6.76fps`。该轮证明“局部更清楚”和“局部更顺滑”在 JPEG fallback 下存在明显冲突，不能继续靠单一档位同时满足两个目标。
- 2026-07-05 13:09 真机复测显示：鼠标输入回到接近 60Hz 后，全屏 3 秒滑动可新增 `177` 条 Mac `input.mouse.move applied=true`，没有出现输入丢失；局部高清保持延长到 `7000ms` 并支持 `source_rect` 明显变化后的节流刷新，能减少缩放/平移后继续看旧局部的问题。但局部高清 `800x466 source_rect` 仍只有约 `7fps`，所以该轮结论仍是“交互链路更稳、清晰源区域更正确”，不是“缩放后流畅完成”。
- 2026-07-05 13:35 真机复测显示：自定义绘制器后全屏滑动期最高样本约 `16.65fps @800x517`，但基础档仍约 `10fps`、局部高清仍约 `7fps`；这说明 Android 每帧 Drawable 抖动不是唯一瓶颈。下一步仍应优先推进 WebRTC/H.264 可用链路、设备 codec 能力分层或更高效的降级媒体通道。
- 2026-07-05 13:46 真机复测显示：Bitmap 复用池未造成显示或输入回归，但基础 proof 仍约 `10fps`，全屏滑动交互档约 `14.97/17.91fps @448x290`，局部高清约 `6.86/6.90fps @800x466 source_rect=0.25,0.20,0.50,0.45`。这说明继续优化 Android Bitmap 分配只能降低抖动风险，不能把当前降级链路提升到流畅标准。
- 2026-07-05 14:32 真机复测显示：输入节流收紧后，长滑 result push 汇总从上一轮 `8/8` 提升到 `16/16 applied`，relay combined 记录 `remote_input_last_count=39`；局部高清从 `1280x746` 极低帧率收敛到 `1024x668` 策略后，避免了上一轮约 `3fps` 的局部高清低谷。但会话仍是 `media_frame_transport=legacy_jpeg`，平均可见帧率约 `6.10fps`，下一步重点仍是最终媒体链路和真实双指手感验收。
- 2026-07-05 15:33 真机复测显示：局部高清从 `1088x634` 回收到 `960x559` 后，样本从约 `4.2fps` 低谷改善到 `6.76/5.25fps`，但仍远低于 `>=24fps`；全屏移动交互档 `448x290` 可到 `16.44/17.41fps`，输入新增 `159` 条 Mac `applied=true`。设计结论是：JPEG fallback 可以保持可见、输入和局部清晰源链路，但不能同时满足全屏缩放顺滑和缩放后清晰两个体验目标。
- 2026-07-05 15:50 真机复测显示：临时硬件合成层和高质量 Bitmap 缩放能让本地 transform/采样路径更稳，但不改变源帧吞吐。标准 proof `render_fps_avg=9.75`，全屏移动交互峰值 `16.92fps @448x290`，局部高清 `960x559 source_rect` 只有 `5.59/5.22fps`。设计结论保持不变：JPEG fallback 只能作为可见和可操作降级路径，不能作为最终流畅远控方案。
- 2026-07-05 16:06 真机复测显示：Mac 鼠标移动队列节流从“执行完成后再等 16ms”改为“原生输入开始时计 16ms”后，全屏 3 秒滑动可新增 `170` 条 `input.mouse.move applied=true`，Mac 日志中的连续 applied 间隔多为 `16-18ms`；但 Android 视觉交互档最高仍只有 `18.24fps @448x290`，局部高清仍约 `5.3fps`。设计结论不变：输入执行已经接近 60Hz，用户觉得鼠标不顺的剩余主要矛盾是视频帧率和降级媒体链路。
- 2026-07-05 16:35 真机复测显示：局部高清回收到 `960x624@12fps` 且 JPEG 质量降到 `84/80` 后，局部高清首样本可到 `8.06fps`，但稳定后仍约 `5.18fps @960x559 source_rect=0.25,0.20,0.50,0.45`；同一会话全屏滑动 Mac applied 约 `59Hz`。设计结论不变：输入执行足够密，视觉跟手和缩放清晰的主要矛盾是 legacy JPEG/Base64/Bitmap 吞吐。
- 2026-07-05 19:45 真机复测显示：Android UI 合成在全屏滑动窗口中 `0.00%` jank，但 legacy JPEG 基础 proof 仍只有 `9.47fps`，局部高清仍约 `4-5fps`。同时 Mac worker 采样显示捕获/编码耗时长期超过目标帧间隔。设计结论升级为：JPEG fallback 后续只能作为“可见可操作”的受控降级链路；产品级全屏缩放清晰和鼠标视觉跟手必须切换到最终媒体链路、硬件编码/硬解或更高效的低延迟媒体通道。
- 2026-07-05 21:11 真机复测显示：历史触摸点补偿后，Mac 鼠标执行间隔多为 `16-20ms`，全屏滑动新增 `157` 条 `input.mouse.move applied=true`，Android UI/gfxinfo jank 仍低；但全屏远控画面约 `11.7-12.7fps @640x414`，局部高清约 `10.56fps` 后降到 `6.99/7.07fps @800x466 source_rect=0.25,0.20,0.50,0.45`。设计结论不变：输入执行已经足够证明链路可用，剩余体验瓶颈是可见媒体链路吞吐和真实双指手感验证。

### 7.2 后续优化方向

- P0：建立编码/解码能力矩阵。对 Redmi Note 8 Pro 当前结论是 MTK AVC 硬解不可用、H.264 软件解码全屏不可达标，legacy JPEG 捕获/编码又长期 overrun；下一步应评估 VP8/VP9/H.265、硬解探测、硬编码和按设备动态选择 codec/profile。
- P1：把区域高清从 JPEG fallback 推进到最终媒体链路。`session.viewport.interaction`、Desktop/Rust `source_rect` 裁剪、`screen.frame.push` 元数据和 Android 坐标反算已经验证；下一步是 WebRTC/硬编码路径支持同等区域媒体能力，并补人工双指体验验收。
- P2：接入 macOS 硬件编码，替代 OpenH264 软件编码/JPEG fallback，先解决清晰档和局部高清档源头帧率限制。
- P3：优化捕获像素路径，减少 BGRA 拷贝、BGRA -> YUV 转换和 JPEG/Base64 传输成本；19:45 worker 采样已经证明完整帧免拷贝只是小幅改善，不能替代媒体链路重构。
- P4：让 controller 质量反馈驱动 sender 降档/恢复。当前 relay 能聚合 `controller_quality_hint=render_fps_streak`，但 05:45 实测已证明降到 `800x520` 仍无法让 Redmi Note 8 Pro 全屏达标，所以它只能作为保护策略。
- P5：继续保留全屏承载方式实验能力，但不要重复已失败路径：Activity overlay、TextureView、全屏低档和 MTK AVC 硬解都需要作为反例记录。
- P6：为双指捏合补 instrumentation 级多点事件测试，或安排人工真机手感验收。

## 8. 区域高清缩放设计

### 8.1 触发条件

- Android 端 `remoteViewportScale >= 1.08`，并且用户结束 pinch 或在放大状态下双指平移停手。
- Android 必须已经知道完整远端帧尺寸和当前本地 viewport 到远端帧的映射关系。
- 如果当前媒体链路是 WebRTC 且设备 codec 能力达标，优先走 WebRTC 区域编码；当前 Redmi 真机仍在 JPEG fallback，因此区域高清先通过临时 `screen.frame.push` 降级通道验证。

### 8.2 消息字段

- 当前已扩展 `session.viewport.interaction`：
  - `viewport_x`、`viewport_y`：当前可视区域左上角，按完整远端画面归一化。
  - `viewport_width`、`viewport_height`：当前可视区域尺寸，按完整远端画面归一化。
  - `focus_x`、`focus_y`：用户双指中心或最后交互焦点，按完整远端画面归一化。
  - `scale`：Android 本地显示倍率。
  - `phase`：继续使用 `start|update|end`。
- 扩展区域帧元数据：
  - `source_rect_x`、`source_rect_y`、`source_rect_width`、`source_rect_height`：本帧对应完整桌面的归一化区域。
  - `full_frame_width`、`full_frame_height`：完整桌面逻辑尺寸，用于输入映射和恢复全屏。
- 当前实现状态：
  - Desktop JS 会将 Android viewport 区域转换成百万分之一整数 `source_rect_ppm`，避免浮点抖动导致采集配置频繁误判变化。
  - Rust capture 的 ScreenCaptureKit、screenshot fallback、CoreGraphics fallback 和 Windows GDI 路径均已支持按区域裁剪。
  - Android legacy JPEG 渲染会识别非整屏 `source_rect`，渲染时不再叠加旧本地倍率，输入坐标会先映射到局部帧再还原为完整桌面坐标。

### 8.3 坐标映射

- Android 显示区域帧时，触摸点先映射到区域帧内坐标，再通过 `source_rect` 反算完整桌面归一化坐标。
- 单指移动、点击、长按拖拽都继续发送完整桌面归一化坐标，Mac 输入执行器不需要知道当前是否区域高清。
- 用户点击 `1x` 或退出全屏时，Android 发送 `session.viewport.interaction phase=end interaction=fullscreen|pinch scale=1`，Desktop 恢复完整桌面采集。

### 8.4 验收口径

- 缩放前后同一桌面文字区域截图，区域高清后有效像素密度必须提升，不能只是本地放大同一张帧。
- 区域高清状态下单指移动和点击仍要落到 Mac 正确位置，至少用输入框点击或菜单点击证明坐标映射正确。
- 区域高清状态下视觉帧率目标仍为 `>=24fps`；当前临时 JPEG fallback 在 00:15 复测中约 `7.95fps @896x578`、静止清晰约 `4-5fps @1142x737`，只能记录为“局部清晰度数据链路已通”，不能记录为流畅完成。

## 9. 验证清单

### 9.1 自动验证

- Android 构建：`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`
- Desktop 前端构建：`cd apps/desktop && npm run build`
- 真机安装：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`
- 三端启动：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto ./scripts/triad_ctl.sh restart`
- 日志检查：`first_rendered_frame`、`render_frame_sample`、`probe.first_frame`、`config.updated`、`input.result.push`
- legacy 降级日志检查：`legacy_first_frame`、`legacy_frame_sample`、relay `session.tool.forwarded type=screen.frame.push`。
- legacy 指标检查：session metrics 应出现 `media_frame_transport=legacy_jpeg`，并且 JPEG fallback 有画面时通用 `first_frame_ms/rendered_frames/render_fps_avg` 不应再是 `-1/0/-1`；仍需同时保留 `legacy_first_frame` 和 `legacy_frame_sample` 作为原始证据。
- 工具消息检查：relay 单测覆盖 `clipboard.text` 和 `file.transfer.start/chunk/complete` 的会话内转发、ack 和非法 payload 拒绝。
- 视图交互提示检查：relay 单测覆盖 `session.viewport.interaction` 的会话内转发、ack、非法 phase 拒绝，以及越界 `viewport_*` 区域拒绝。
- 全屏截图：进入全屏后用 `adb exec-out screencap -p` 保存截图，确认完整显示桌面。
- 本地指针截图：全屏内执行 `adb shell input swipe` 后立即截图，确认蓝色本地指针反馈出现，同时检查 Mac 端 `input.mouse.move applied=true`。
- 双指缩放日志：期望出现 `remote_viewport_pinch_scale`；如果通过 debug 注入触发的是 `source=manual`，只能证明 manual span fallback 路径可用，不能替代人工手指手感验收。
- adb 多点边界：当前真机 `adb shell input` 的 `motionevent` 只支持单点 `DOWN/MOVE/UP <x> <y>`，并发 `swipe` 不能产生可验收的真实双指 pinch。

### 9.2 本轮真机验证记录

- Android 构建通过：`cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`
- Desktop 前端构建通过：`cd apps/desktop && npm run build`
- Tauri Rust 测试通过：`cd apps/desktop/src-tauri && cargo test`，33 个测试通过。
- Go relay 测试通过：`cd apps/server && go test ./...`；本轮新增工具消息转发测试。
- `git diff --check` 通过。
- 真机安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`
- 三端启动通过：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto ./scripts/triad_ctl.sh restart`
- 本轮会话：`sess-1783190775453-1`。
- Mac sender 新码率生效：`.rd_runtime/logs/mac.log` 出现 `encoder.ready ... bitrate_bps=8000000`。
- 首帧通过：Android 日志出现 `first_rendered_frame session=sess-1783190775453-1 size=1546x1000 since_track_ms=304`。
- 标准输入 proof 通过：relay 汇总 `session_e2e_proof_status=video_and_input_observed`，`remote_input_applied=11/11`，覆盖 `click,drag,keyboard,wheel`。
- 全屏完整显示通过：`/tmp/remotedesk-fullscreen-after-adjust.png` 显示远端桌面按比例完整放入横屏，左右黑边来自宽高比适配，不是裁切。
- 鼠标移动 proof 通过：全屏滑动后连续发送 `input.mouse.move`，Android 回执坐标从约 `(0.49, 0.67)` 推进到 `(0.65, 0.72)`，Mac 执行为 `applied/macos.cg_event`；截图 `/tmp/remotedesk-fullscreen-swipe-after-adjust.png` 显示 Mac 黑色真实光标位置已更新。
- 双指缩放自动验证未通过：并发 `adb shell input swipe` 仍没有触发 `remote_viewport_pinch_scale`，截图 `/tmp/remotedesk-pinch-attempt-after-adjust.png` 不能证明真实 pinch 手感。
- 流畅性未达标：Android `render_frame_sample` 约 `14.0-14.3fps`，Mac `probe.sample` 约 `14fps`，`encode_ms_avg` 约 `57-61ms`。
- 剪贴板和文件传输代码接线已通过构建/单测：Android、Desktop 和 relay 均能编译；relay 已用 WebSocket 流程测试验证工具消息转发。
- 2026-07-05 05:57 真机自动验收通过：Mac -> Android 剪贴板 `RD_MAC_CLIP_V2_20260705` 写入 Android；Mac -> Android 文件 `/tmp/remotedesk-mac-to-android-v2.txt` 与 Android `/sdcard/Download/RemoteDesk/remotedesk-mac-to-android-v2.txt` 哈希同为 `45d0018209f37e2680c8e67afd30a2ef7198eea14438838c51e982cf55a0147e`；Android -> Mac 剪贴板 `RD_ANDROID_CLIP_V2_20260705` 写入 Mac；Android -> Mac 文件 `files/android-to-mac-private.txt` 与 `/Users/long/Downloads/RemoteDesk/android-to-mac-private.txt` 哈希同为 `8613621ccb704702f10c66ed2eaaf6623c51f73cc03f195d34b830445146dc5a`。
- 2026-07-05 07:05 真机 legacy 验收通过：会话 `sess-1783206072021-1`；Android `legacy_first_frame ... size=1114x720`；`legacy_frame_sample` 约 `9.19-9.60fps size=1547x1000`；全屏截图 `/tmp/remotedesk-fullscreen-legacy-only-100ms.png` SHA256 `329cab288f67a5776352a85bf8f2d5876ce48fc14d81cdfbb7db508341021d21`；全屏长滑动 Android `input.mouse.move` 133 条、Mac `applied=true` 133 条。
- 2026-07-05 07:58 真机补充验收：截图 `/tmp/remotedesk-current-20260705.png` SHA256 `3841d295e80e5a9232fc966a7edef29e25a434e2c5b621584f87c36289b061e7`；`gfxinfo` 为 `Janky frames=40/2752 (1.45%)`、P95 `18ms`、P99 `24ms`；全屏 4 秒长滑动新增统计为 relay `input.mouse.move` 收到/转发 `231/231`，Mac `applied=true` `231`，Android 交互档视觉样本 `12.86/13.32/13.44fps @640x414`。
- 2026-07-05 08:19 真机补充验收：截图 `/tmp/remotedesk-fullscreen-swipe-0816.png` SHA256 `1bf503e97d6a83904b9706bdbbc7304479b5480a5d29463b4e72adbeb6d62980`；`session.viewport.interaction` 的 `viewport_*` 和 `focus_*` payload keys 经 relay 转发；全屏长滑动窗口内 Mac `input.mouse.move applied=true` 约 `249` 条，Android 交互档视觉样本 `13.31/13.53/13.47fps @640x414`，停手后 `960x621` 约 `7.7-8.0fps`。
- 2026-07-05 11:45 真机补充验收：会话 `sess-1783222978563-1`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_zoom_114538.png` SHA256 `38e15b3c136667150bb9b858b4f4ee8338e203cfee27bf521bdd382c5b8ec00e`；debug viewport 后 Mac `864x562@16fps source_rect_ppm=250000,200000,500000,450000`，Android `864x503 source_rect=0.25,0.20,0.50,0.45`；横屏全屏 900ms 滑动新增 `59` 条 `input.mouse.move applied=true`，约 `65/s`。

### 9.3 本轮继续调整记录

- Android 构建通过：`cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`
- Desktop 前端构建通过：`cd apps/desktop && npm run build`
- Go relay 测试通过：`cd apps/server && go test ./...`
- Tauri Rust 测试通过：`cd apps/desktop/src-tauri && cargo test`，28 个测试通过。
- 真机安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`
- 三端启动通过：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto ./scripts/triad_ctl.sh restart`
- 当前会话：`sess-1783192048371-1`。
- Android 首帧：`first_rendered_frame ... size=1546x1000 since_track_ms=8639`。
- 全屏 UI tree：`remoteViewportContainer bounds=[424,33][1992,1047]`，`1x`、`退出全屏`、`键盘` 可见。
- 全屏鼠标移动：03:09:33 期间连续发送 `input.mouse.move`，Mac 回执 `applied/macos.cg_event`，坐标从约 `(0.59,0.58)` 推进到 `(0.72,0.61)`；截图 `/tmp/remotedesk-fullscreen-smooth-after.png`。
- E2E proof：relay 汇总 `session_e2e_proof_status=video_and_input_observed`，覆盖 `click,drag,keyboard,wheel`。
- 未闭环：adb 并发双指 swipe 未触发 `remote_viewport_pinch_scale`；Android 仍约 `14.1-14.2fps`，`controller_quality_hint=render_fps_streak`。

### 9.4 本轮交互档复测记录

- 当前会话：`sess-1783193870302-1`。
- 真机安装和三端启动通过：`wsvwypiz7xwslvl7`、Mac agent `agent-19de3117874`、relay `127.0.0.1:18081`。
- 全屏 UI tree 通过：`remoteViewportContainer bounds=[424,33][1992,1047]`，`1x`、`退出全屏`、`键盘` 可见。
- 全屏截图通过：`/tmp/remotedesk-fullscreen-long-swipe-800.png` 显示 macOS 菜单栏、Dock 和右侧桌面文件完整呈现，左右黑边为比例适配，不是裁切。
- 长滑动输入通过：全屏 4 秒滑动后 relay 汇总 `remote_input_applied=198/198`，最后执行器 `macos.cg_event`。
- 交互档生效：Mac 日志出现 `encoder.ready ... frame=800x517`；纯交互采样 `fps=31.25`、`encode_ms_avg=16.2ms`。
- 清晰档恢复：停手后 Mac 日志恢复 `encoder.ready ... frame=1547x1000`；随后清晰档仍约 `14.18fps`、`encode_ms_avg=58.9ms`。
- E2E proof 通过：`session_e2e_proof_status=video_and_input_observed`，覆盖 `click,drag,keyboard,wheel`。
- 自动 pinch 未闭环：并发 `adb shell input swipe ... & input swipe ...` 未出现 `remote_viewport_pinch_scale` 或 `session.viewport.interaction`，不能作为双指缩放手感通过证据。

### 9.5 本轮全屏复测记录

- 工程验证通过：Android debug 构建、Desktop 前端构建、Go relay 测试、`cargo test` 均通过；本轮 Rust 测试为 30 个通过，新增 BGRA 双线性缩放测试。
- 真机覆盖安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`。
- 三端启动通过：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto ./scripts/triad_ctl.sh restart`。
- 当前会话：`sess-1783195348278-1`；标准 E2E proof 通过，relay 汇总为 `session_e2e_proof_status=video_and_input_observed`，`remote_input_applied=11/11`，覆盖 `click,drag,keyboard,wheel`。
- 小窗截图：`/tmp/remotedesk-after-scale-smooth-current.png`，当前画面 `webrtc 1546x1000`。
- 全屏 bounds：`remoteViewportContainer [424,33][1992,1047]`，`1x`、`退出全屏`、`键盘` 控件可见。
- 全屏截图：`/tmp/remotedesk-after-scale-smooth-fullscreen-actual.png`，按比例显示完整远端桌面；左右黑边来自宽高比适配。
- 全屏滑动截图：`/tmp/remotedesk-after-scale-smooth-fullscreen-swipe.png`，Mac 光标位置已更新。
- 全屏滑动输入链路通过：Android 在本次全屏长滑动窗口内发送 `input.mouse.move` 122 条；Mac 侧 `input.mouse.move applied=true` 126 条，执行器为 `macos.cg_event`。
- 交互档触发通过：Mac 侧本轮窗口内出现 `config.updated max_width=800` 和 `encoder.ready ... frame=800x517`；交互样本 `fps=31.50`、`encode_ms_avg=15.6ms`。
- 清晰档恢复通过：Mac 侧恢复 `encoder.ready ... frame=1547x1000`；但清晰档长期仍约 `14fps`、`encode_ms_avg≈58ms`。
- 未达标：Android 渲染侧长会话后持续 `render_fps_streak`，全屏滑动后多个样本降到 `4-9fps`，最长 recent gap 到 `355ms`；本轮不能宣称“全屏缩放/鼠标移动最终流畅”。
- 未闭环：仍未用真实双指或 instrumentation 验证 `remote_viewport_pinch_scale`，普通 `adb input swipe` 只能证明单指/长滑动，不能证明双指 pinch 手感。

### 9.6 本轮 04:24 复测记录

- 工程验证通过：Android debug 构建和 Desktop 前端构建均通过；`git diff --check` 通过。
- 真机三端启动通过：`wsvwypiz7xwslvl7`、Mac agent `agent-19de3117874`、relay `127.0.0.1:18081`。
- 当前会话：`sess-1783196612267-1`。
- Android 高频移动回执 UI 已节流：`input.mouse.move` ack/result 不再每条刷新 `ackText` 和日志；移动 result 不再每条强制发送 live metrics。
- Mac 交互档调整为 `640x416@30fps`，本轮实测编码端出现 `640x414`，`probe.sample fps=30.63`、`encode_ms_avg=10.2ms`。
- 全屏长滑动输入链路通过：Android 本次窗口发送 `input.mouse.move` 170 条，Mac `applied=true` 170 条，执行器 `macos.cg_event`。
- 截图：`/tmp/remotedesk-20260705-640-fullscreen-swipe.png`。
- 未达标：Android 全屏渲染仍只有 `7.57-9.88fps`，`recent_quality_hint=render_fps_streak`；这说明问题已不是单纯 Mac sender 编码帧率，也不是单纯 UI 日志刷新，需要继续攻 Android 渲染/Surface 路径。
- 未闭环：本轮仍没有真实双指 pinch 验收，`remote_viewport_pinch_scale` 未形成自动化证据。

### 9.7 本轮 10:55 复测记录

- 工程验证通过：`git diff --check`、`cd apps/server && go test ./...`、`cd apps/desktop && npm run build`、`cd apps/desktop/src-tauri && cargo test`、`cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`。
- 真机安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`。
- 三端启动通过：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto ./scripts/triad_ctl.sh restart`。
- 当前会话：`sess-1783219924972-1`；Mac agent `agent-19de3117874`、Android controller `android-6b04e47d7cccda58` 均在线。
- 全屏完整显示通过：`.rd_runtime/screens/remote_fullscreen.png` 为 `2340x1080` 横屏截图，远端桌面完整居中，左右黑边为比例适配。
- 区域高清链路通过但未达流畅目标：Android debug viewport 发出 `session.viewport.interaction`，relay `session.tool.forwarded`，Mac 最终切到 `config.updated max_width=720 max_height=468 max_fps=18 source_rect_ppm=250000,200000,500000,450000`，Android `legacy_frame_sample ... size=720x420 source_rect=0.25,0.20,0.50,0.45`；稳定约 `6.7fps`。
- 全屏单指移动链路通过：ADB 在全屏远控画面内执行两段 swipe，relay 记录 `input.mouse.move` 约 `16-17ms` 间隔转发，Mac 端 `input.mouse.move applied=true executor=macos.cg_event`。
- 当前仍未闭环：该轮没有真实多点 pinch 自动化证据；debug viewport 只能证明缩放结束后的区域高清消息和裁剪回传链路，不能替代人工双指张开/并拢手感验收。

### 9.8 本轮 13:09 复测记录

- 工程验证通过：`git diff --check`、`bash -n scripts/triad_ctl.sh`、`cd apps/server && go test ./...`、`cd apps/desktop && npm run build`、`cd apps/desktop/src-tauri && cargo test`、`cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`。
- 真机安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`。
- 三端启动通过：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./scripts/triad_ctl.sh restart`。
- 当前会话：`sess-1783227836869-1`；Mac agent `agent-19de3117874`、Android controller `android-6b04e47d7cccda58` 均在线。
- 标准 proof 通过：relay combined 为 `session_e2e_proof_status=video_and_input_observed`，`first_frame_ms=107`，`media_frame_transport=legacy_jpeg`，`render_fps_avg=10.14`，`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。
- 小窗完整显示通过：截图 `.rd_runtime/screens/rd_current_1304.png` SHA256 `c02b628611e59b36a3b447abee4faa6453b4b164549cf10c479f31dea59f08fc`；空闲基础档约 `9.8-10.5fps @800x517`。
- 全屏完整显示通过：截图 `.rd_runtime/screens/rd_fullscreen_1308.png` SHA256 `c09d6306b81397290b7933554df79d98d5fd59d2e0f6e7272b29fcc4cb3721a2`，远端桌面完整居中，左右黑边为比例适配。
- 全屏单指移动链路通过：3 秒滑动后 Mac `input.mouse.move applied=true` 从 `179` 增到 `356`，新增 `177` 条，约 `59Hz`，执行器为 `macos.cg_event`；滑动后截图 `.rd_runtime/screens/rd_fullscreen_swipe_1309.png` SHA256 `ace9e518afce841b79414b5b279445b1e87320f3c8da71a9dea53b9c1e7f2011`。
- 区域高清链路通过但未达流畅目标：debug viewport 后 Mac 先切 `512x334@30fps source_rect_ppm=250000,200000,500000,450000`，随后切 `800x520@10fps source_rect_ppm=250000,200000,500000,450000`；Android `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45`，并收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，样本约 `6.89/6.98fps`。
- 当前仍未闭环：真实双指 pinch 手感未人工验收；ADB `input` 仍不能可靠产生多点 pinch；当前可见画面仍是 `legacy_jpeg`，不是 WebRTC/H.264。

### 9.9 人工验证

### 9.10 本轮 14:54 复测记录

- 工程验证通过：`git diff --check`、`cd apps/server && go test ./...`、`cd apps/desktop && npm run build`、`cd apps/desktop/src-tauri && cargo test`、`cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`。
- 真机安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`。
- 三端启动通过：`RD_ANDROID_MODE=physical RD_ANDROID_SERIAL=wsvwypiz7xwslvl7 RD_AGENT_DEVICE_ID=auto JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./scripts/triad_ctl.sh restart`。
- 当前会话：`sess-1783234294807-1`；Mac agent `agent-19de3117874`、Android controller `android-6b04e47d7cccda58` 均在线。
- 本轮参数：Android 远端 move 为 `16ms / 0.00045`，全屏本地承载面 `1.0x`，pinch 滤波 `scale=0.78/focus=0.72`；Desktop 整屏 `800x520@30fps`、移动 `448x292@30fps`、局部移动 `512x334@24fps`、局部高清 `960x624@16fps`；整屏/移动 JPEG 质量为 `54/46`，局部高清为 `86/88`。
- 标准 proof 通过：relay combined 为 `session_e2e_proof_status=video_and_input_observed`，`first_frame_ms=54`，`media_frame_transport=legacy_jpeg`，`render_fps_avg=6.14`，`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。
- 全屏完整显示通过：截图 `/tmp/remotedesk-final-fullscreen-1452.png` SHA256 `755ffb322f10bc7398d0b62ffa375d03954de9da30420b2073157f351c9f54d4`，远端桌面完整居中，左右黑边为比例适配。
- 全屏单指移动链路通过：3 秒滑动后 Mac `input.mouse.move applied=true` 从 `3` 增到 `163`，新增 `160` 条；滑动后截图 `/tmp/remotedesk-final-fullscreen-swipe-1452.png` SHA256 `a293807db867328bbf88565b72953eb97caf2a22d2a9999cd44b058e8fc0cdb0`。
- 区域高清链路通过但未达流畅目标：debug viewport 后 relay 转发 `session.viewport.interaction`；Mac 先切 `512x334@24fps source_rect_ppm=250000,200000,500000,450000`，随后切 `960x624@16fps source_rect_ppm=250000,200000,500000,450000`；Android `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45`，并收到 `960x559 source_rect=0.25,0.20,0.50,0.45`。
- 当前未达标：整屏基础档约 `5.9-6.4fps @800x517`，移动交互峰值约 `17.88fps @448x290`，局部高清约 `5.54fps` 后降到 `2.92fps`。这轮证明输入、完整显示和区域高清链路未回归，但也进一步证明 JPEG fallback 不能作为最终流畅媒体链路。

### 9.11 本轮 16:35 复测记录

- 工程验证通过：`git diff --check`、`bash -n scripts/triad_ctl.sh`、`cd apps/server && go test ./...`、`cd apps/desktop && npm run build`、`cd apps/desktop/src-tauri && cargo test`、`cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`。
- 真机安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`。
- 当前会话：`sess-1783240363109-1`；Mac agent `agent-19de3117874`、Android controller `android-6b04e47d7cccda58`。
- 本轮参数：Desktop 局部高清 `960x624@12fps`、保持 `4400ms`；Rust 局部 JPEG 质量 `84/80`，`960x624` 以内走锐利质量，超过该范围走平衡质量。
- 标准 proof 通过：`session_e2e_proof_status=video_and_input_observed`，`first_frame_ms=55`，`media_frame_transport=legacy_jpeg`，`render_fps_avg=10.07`，`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。
- 全屏完整显示通过：截图 `.rd_runtime/screens/rd_fullscreen_after_quality_tune.png` SHA256 `3f97b4e4b7d985089183cc54d7168872d6f7993755eb2b79f6ad30c8261543d6`，远端桌面完整居中，左右黑边为比例适配。
- 全屏单指移动链路通过：3 秒滑动后 Mac `input.mouse.move applied=true` 从 `3` 增到 `181`，新增 `178` 条，约 `59Hz`。
- 区域高清链路通过但未达流畅目标：debug viewport 后 Mac 先切 `560x364@24fps source_rect_ppm=250000,200000,500000,450000`，随后切 `960x624@12fps source_rect_ppm=250000,200000,500000,450000`；Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清样本 `8.06/5.18fps`；截图 `.rd_runtime/screens/rd_debug_viewport_after_quality_tune.png` SHA256 `8ec0c3b72907a40e7fc50f60ff9a545a1d49d37526534a5d9580fc3eb32f6727`。
- 当前结论：该轮没有引入输入、全屏完整显示或 `source_rect` 回归；局部高清首样本比 `1024x597` 高质量档的约 `4fps` 低谷更稳，但稳定视觉 FPS 仍远低于 `>=24fps`。最终方案必须推进 WebRTC/H.264/硬编码/硬解能力分层或更高效降级媒体通道。

### 9.12 本轮 16:56 复测记录

- 工程验证通过：`git diff --check`、`bash -n scripts/triad_ctl.sh`、`cd apps/server && go test ./...`、`cd apps/desktop && npm run build`、`cd apps/desktop/src-tauri && cargo test`、`cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`。
- 真机安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`。
- 当前会话：`sess-1783241479512-1`；Mac agent `agent-19de3117874`、Android controller `android-6b04e47d7cccda58`。
- 本轮设计取舍：Android 全屏 pinch 期间整屏 legacy JPEG 只保留 `320ms` 低频背景刷新，并临时关闭整屏 Bitmap 高质量滤波；停手后本地承载面 `220ms` 提交，Mac 端 pinch end 直接切 `960x624@12fps` 的 `source_rect` 局部高清，不再先等待低清交互档。
- 标准 proof 通过：`session_e2e_proof_status=video_and_input_observed`，`first_frame_ms=78`，`media_frame_transport=legacy_jpeg`，`render_fps_avg=7.00`，`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。
- 全屏完整显示通过：截图 `/tmp/remotedesk-fullscreen-after-tap-165242.png` 显示远端桌面完整居中，左右黑边为比例适配。
- 全屏单指移动链路未回归但本轮 adb 采样偏低：3 秒 swipe 后 Mac `input.mouse.move applied=true` 从 `3` 增到 `49`，新增 `46` 条；该数值不能替代人工滑动手感，上轮同机同链路曾验证约 `59Hz`。
- 区域高清链路通过但未达流畅目标：debug viewport 使用 `--ez rd_debug_send_viewport_interaction true` 和 `--ed` double extra 后，Android 记录 `debug viewport pinch end`，Mac 立即切到 `config.updated max_width=960 max_height=624 max_fps=12 source_rect_ppm=250000,200000,500000,450000`，Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45` 并收到 `960x559 source_rect=0.25,0.20,0.50,0.45`。
- 局部高清截图：`/tmp/remotedesk-debug-viewport-during-current-165617.png`，SHA256 `4b9f1a5e1e102e4457776a81cfbadac565f707ae56916535447bf58efb54488c`，2 秒内显示局部区域，文字可读性优于整屏放大。
- 当前结论：清晰源链路恢复更直接，手势中整屏绘制成本更低；但 `legacy_jpeg` 仍只有 `4.66/5.00fps @960x559 source_rect`，并发 adb swipe 未触发真实 `remote_viewport_pinch_scale`，所以本轮仍不能关闭全屏双指缩放顺滑、缩放后清晰且流畅、鼠标视觉跟手三项需求。

### 9.13 本轮 19:45 复测记录

- 工程验证通过：`git diff --check`、`cd apps/desktop/src-tauri && cargo test`、`cd apps/desktop && npm run build`、`cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`。
- 真机安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`；APK SHA256 `9782281fb41bcea56c71668667eb8a40540990736e39ad813ad37e4e20090e6b`。
- 当前会话：`sess-1783251823436-1`；Mac agent `agent-19de3117874`、Android controller `android-6b04e47d7cccda58`。
- 本轮代码取舍：Android pinch 过程中不启用整屏高质量 Bitmap 滤波，优先本地矩阵动画跟手，停手后局部高清帧再恢复高质量滤波；Rust capture worker 增加 `avg_capture_ms/max_capture_ms/last_capture_ms/target_interval_ms/last_gap_ms/overruns` 采样日志，完整帧无裁剪且尺寸不变时跳过多余 BGRA 拷贝。
- 标准 proof 通过：`session_e2e_proof_status=video_and_input_observed`，`first_frame_ms=38`，`media_frame_transport=legacy_jpeg`，`render_fps_avg=9.47`，`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。
- 全屏完整显示通过：截图 `.rd_runtime/screens/rd_fullscreen_after_worker_tune_1944.png` SHA256 `7cf2ea4cd375a343feb3acd841f3d61ad9fd3336e48ee81c46fd5a5a6a5efa97`，远端桌面完整居中，左右黑边为比例适配。
- Android UI 合成不是主要 jank 源：全屏 3 秒滑动的 `gfxinfo` 记录为 `Total frames rendered=207`、`Janky frames=0 (0.00%)`、P50 `6ms`、P95 `13ms`、P99 `15ms`。
- 输入链路未回归：全屏 3 秒滑动后 Mac `input.mouse.move applied=true` 新增 `45` 条；该 adb 采样低于前几轮人工/坐标滑动窗口，不能单独代表最大输入能力，但仍证明 Mac 输入执行通路可用。
- 区域高清链路通过但未达流畅目标：debug viewport 后 Mac 先切 `448x292@24fps source_rect_ppm=250000,200000,500000,450000`，再切 `960x624@12fps source_rect_ppm=250000,200000,500000,450000`；Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45` 并收到 `960x559 source_rect=0.25,0.20,0.50,0.45`。截图 `.rd_runtime/screens/rd_debug_viewport_worker_tune_1945.png` SHA256 `fa3e9ad29321850f87f94324c4bdc9a693939c7fe6e9f5424b2cc054252d7b87`；局部高清样本约 `5.46/4.47fps`。
- Mac worker 采样结论：`800x517 target_interval_ms=33` 时 `last_capture_ms` 常见约 `99-112ms`，`448x290` 时约 `53ms`，`640x414 target_interval_ms=41` 时约 `73-90ms`，`overruns` 持续增加。完整帧免 BGRA 拷贝带来小幅改善，但仍不能把 legacy JPEG 提升到 `>=24fps`。
- 当前结论：全屏完整显示、输入落地、source_rect 区域高清和工程构建均未回归；视觉流畅度、缩放后清晰且流畅、真实双指 pinch 手感仍未达标。下一阶段系统设计必须优先推进 WebRTC/H.264/硬编码/硬解能力分层或更高效降级媒体通道。

### 9.14 本轮 21:11 复测记录

- 工程验证通过：`git diff --check`、`cd apps/server && go test ./...`、`cd apps/desktop && npm run build`、`cd apps/desktop/src-tauri && cargo test`、`cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`。
- 真机安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`；APK SHA256 `3418a054e54419c73b4155a1d931f1c8244bfc55c1e8b5c2d3904ab4f3ebd93e`。
- 当前会话：`sess-1783256887975-1`；Mac agent `agent-19de3117874`、Android controller `android-6b04e47d7cccda58`。
- 本轮代码取舍：Android 鼠标 move 从 `MotionEvent` 中补最近最多 `4` 个历史触摸点，按事件时间进入现有 `12ms` 合并队列；pinch 期间整屏预览帧间隔调整为 `2200ms`，把手势阶段预算留给本地 transform 和输入。
- 标准 proof 通过：`session_e2e_proof_status=video_and_input_observed`，`first_frame_ms=43`，`media=legacy_jpeg`，`render_fps_avg=9.91/11.20`，`remote_input_applied=16/16`，覆盖 `click,drag,keyboard,wheel`。
- 全屏完整显示通过：截图 `.rd_runtime/screens/rd_fullscreen_after_history_points_2109.png` SHA256 `81324c457568b2448c52cc7f590d6291be23a5a22215a3bd8225a04666ae46c9`；滑动后截图 `.rd_runtime/screens/rd_fullscreen_after_history_points_swipe_2110.png` SHA256 `0f0c2de71865a3fde2fa0b2cb11e2e980999db88b507f983afe0a3dd5c93988f`。
- 全屏单指移动链路通过：3 秒滑动后 Mac `input.mouse.move applied=true` 新增 `157` 条，连续 applied 间隔多为约 `16-20ms`，执行器为 `macos.cg_event`。
- Android UI 合成不是主要 jank 源：全屏、滑动和 source_rect 后的 `gfxinfo` 记录 jank 约 `0.27-0.45%`，P95 `15ms`，P99 `16-17ms`。
- 区域高清链路通过但未达流畅目标：debug viewport 后 Mac 使用 `source_rect_ppm=250000,200000,500000,450000`，Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`；截图 `.rd_runtime/screens/rd_debug_viewport_after_history_points_2111.png` SHA256 `cf449cd4a42bc2f003a5de31a94720b1a0885289b727362bd07f95d49b42f56d`；局部高清样本约 `10.56fps` 后稳定到 `6.99/7.07fps`。
- 当前结论：全屏完整显示、输入落地和 source_rect 区域高清均未回归；历史触摸点补偿改善了鼠标轨迹连续性，但视觉 FPS 仍未达 `>=24fps`，真实双指 pinch 手感仍未自动化闭环。下一阶段继续以 WebRTC/H.264/硬编码/硬解能力分层或更高效降级媒体通道为 P0。

### 9.15 本轮 00:15 复测记录

- 工程验证通过：`git diff --check`、`cd apps/server && go test ./...`、`bash -n scripts/triad_ctl.sh`、`cd apps/desktop/src-tauri && cargo fmt --check && cargo test`、`cd apps/desktop && npm run build`、`cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug --console=plain`。
- 真机安装通过：`adb -s wsvwypiz7xwslvl7 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`；APK SHA256 `d291a85bfc6cf0d6ac0383549ec8b75b6f2092775025d4b5f9bef4624a55115b`。
- 当前会话：`sess-1783267574091-1`；Mac agent `agent-19de3117874`、Android controller `android-6b04e47d7cccda58`。
- 本轮代码取舍：Android 鼠标 move 为 `12ms / 0.00032`，历史点最多 `4` 个，尾帧最大 `12ms`，本地光标预测 `18ms`；`ScaleGestureDetector` 未接管时启用 manual span fallback。Desktop 当前档位为基础 `800x520@30fps`、普通交互 `384x250@30fps`、全屏 `640x416@24fps`、pinch 预览 `320x208@8fps`、局部移动 `512x333@22fps`、局部 detail `896x582@10fps`、局部 still `1280x832@5fps`。
- 标准 proof 通过：`session_e2e_proof_status=video_and_input_observed`，`first_frame_ms=31`，`media=legacy_jpeg`，`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。
- debug 双指缩放闭环：日志出现 `remote_manual_pinch_begin`、多条 `remote_viewport_pinch_scale source=manual`、`remote_manual_pinch_end`，倍率约 `1.185 -> 2.437`。
- 区域清晰链路通过但未达流畅目标：Mac 切到 `source_rect_ppm=294671,306641,410657,410305`，Android 记录 `remote_viewport_source_rect_materialized rect=0.29,0.31,0.41,0.41`；局部 detail 样本约 `7.95fps @896x578`，局部 still 约 `4-5fps @1142x737`。
- 缩放后单指移动链路通过：3 秒横向滑动后 Mac `input.mouse.move applied=true` 从 `3` 增到 `182`，新增约 `179` 条，接近 `60Hz`。
- 产物已保存：`.rd_runtime/screens/rd_fullscreen_pinch_manual_20260706_000711.png`、`.rd_runtime/screens/gfxinfo_fullscreen_pinch_manual_20260706_000711.txt`、`.rd_runtime/screens/gfxinfo_fullscreen_pinch_manual_20260706_000711_framestats.txt`、`.rd_runtime/screens/rd_after_mouse_swipe_manual_zoom_20260706_000812.png`、`.rd_runtime/screens/gfxinfo_after_mouse_swipe_manual_zoom_20260706_000812.txt`、`.rd_runtime/screens/gfxinfo_after_mouse_swipe_manual_zoom_20260706_000812_framestats.txt`。
- 当前结论：manual pinch、source_rect 局部清晰和缩放后输入落地未回归；但可见媒体仍是 `legacy_jpeg`，视觉 FPS 仍未达 `>=24fps`，真实手指双指 pinch 手感仍未验收。下一阶段 P0 仍是最终媒体链路或更高效降级媒体通道。

### 9.16 人工验证待办

- 双指张开/并拢是否平滑。
- 缩放后文字、图标和窗口边缘是否比旧 720p 清楚。
- 缩放后双指平移是否跟手，且单指仍能移动电脑鼠标。
- 鼠标连续移动时，手机端视觉反馈是否可接受。
- 长时间会话是否保持亮屏且不被锁屏打断。
- Android -> Mac 剪贴板发送后，Mac 剪贴板内容是否更新：debug intent 自动验收已通过；Android 真实“发剪贴板”按钮也已通过，测试文本 `UI_ANDROID_CLIP_20260705`。
- Mac -> Android 剪贴板发送后，Android 剪贴板内容是否更新：debug env 自动验收已通过，仍需手动 UI 验收。
- Android -> Mac 文件发送后，Mac 下载保存是否成功，文件内容是否一致：app-private debug 文件自动验收已通过；Android “发文件”按钮已确认打开 DocumentsUI picker，但自动选文件未闭环，仍需人工或进一步修复 picker 可测性。
- Mac -> Android 文件发送后，Android `Downloads/RemoteDesk/` 是否保存成功，文件内容是否一致：debug env 自动验收已通过，仍需 Desktop 工具栏手动验收。

## 10. 已知风险

- 当前可见媒体仍是 `legacy_jpeg`，不是最终 WebRTC/H.264；鼠标画面流畅度和缩放后清晰且流畅不能宣称完成。
- debug/manual pinch 只能证明自动化代码路径可用，不能替代真实手指手感验收；adb 并发 `swipe` 也不能作为双指 pinch 证据。
- 手机锁屏会导致截图全黑；验证前必须确认 `mWakefulness=Awake` 且焦点在 `com.remotedesk.app/.ui.MainActivity`。
- `SurfaceViewRenderer` 对截图、父容器变换和层级较敏感；如果后续继续出现全屏/缩放异常，需要评估 TextureView 或自定义渲染路径。
- 剪贴板 API 已通过 debug 自动验收，Android 发送按钮也已通过；Desktop 工具栏仍可能受到系统权限、窗口焦点或 Tauri WebView 安全策略影响，需要补 UI 验收。
- 文件传输当前走 WebSocket 信令分块，适合小文件验证，不适合大文件和断点续传。
- Android 14 scoped storage 下，不能把 `/sdcard/Download/...` 直接当作 app 可读路径；用户文件必须通过系统文件选择器授权。
- DocumentsUI picker 在本轮 adb 自动化中出现 `最近` 空列表和 `下载` 目录 `暂时无法加载内容`；这不否定工具通道，但文件选择器 UI 路径仍需人工确认或专项修复。
- 交互档会牺牲移动中的画面清晰度；停手进入局部 detail/still 后细节更清楚，但 00:15 复测中局部 detail 仍约 `7.95fps`、still 仍约 `4-5fps`。
- 当前 JPEG-only 降级会绕过 WebRTC 视频链路，适合救回 Redmi 真机画面和测试入口，但不适合长期作为唯一方案；网络带宽、CPU、耗电和安全边界需要专项设计。

## 11. 下一步任务

- [x] 将 Android 真机 legacy JPEG 首帧、帧数和 FPS 纳入统一质量指标上报，并用真机 combined 日志复核通过。
- [ ] 为 Android 真机 legacy JPEG 降级补正式设备能力探测、开关策略和 UI 状态提示。
- [x] 实现 JPEG fallback 区域高清采集：Android 上报可视区域和焦点，Desktop/Rust 按区域采集/编码，Android 按 `source_rect` 反算输入坐标。
- [x] 实现并验证 debug/manual pinch fallback：当系统缩放检测未接管时，按两指 span 触发 `remote_viewport_pinch_scale source=manual`。
- [ ] 将区域高清能力接入最终 WebRTC/硬编码媒体链路，并达到 `>=24fps` 验收线。
- [ ] 人工真机验证 Mac -> Android 剪贴板 UI 操作，以及 Desktop 工具栏文件发送。
- [ ] 人工真机验证 Android 系统文件选择器发送文件到 Mac，并比对文件内容。
- [ ] 人工真机验证全屏双指缩放手感、缩放后双指平移，以及缩放后单指仍移动电脑鼠标，并记录结论。
- [ ] 为 Android 增加 instrumentation 多点触控测试，自动证明 `remote_viewport_pinch_scale`、倍率标签变化和停手后清晰渲染面提交。
- [ ] 继续优化 sender 清晰档帧率，目标先让 `1547x1000` 恢复到 `>=24fps`，再追求 30fps。
- [ ] 评估 macOS 硬件编码或更低成本的 BGRA -> YUV 转换；当前本地指针只能改善控制端反馈，不能解决远端视频低帧率。
- [ ] 评估 Android 端 codec/renderer 组合，不再重复已失败的 `TextureViewRenderer`、MTK AVC 硬解和单纯降分辨率路径；最新 JPEG-only 只证明降级可见，不证明 WebRTC 可用。
- [ ] 修复 1000p 下 `14fps` 质量窗口后复跑 short reconnect，当前最新报告 `short_reconnect_20260705_023015` 为 FAIL。
- [ ] 复跑 quick soak，记录 `render_fps_avg`、`visible_frame_gap_ms_max` 和输入覆盖。
- [ ] 根据帧率结果决定是否引入硬件编码或动态分辨率策略。
