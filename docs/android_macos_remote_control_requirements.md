# Android 控制 macOS 远控需求文档

更新时间：2026-07-06 00:38（北京时间）

## 1. 项目目标

RemoteDesk 当前目标是让 Android 真机作为控制端，远程查看并操作 macOS 桌面。用户不需要切换“触控/移动/放大”模式，远程画面本身必须承载点击、鼠标移动、拖拽、滚轮、局部放大、全屏和电脑键盘输入能力。

## 2. 核心需求

### 2.1 画面显示

- 小窗和全屏都必须完整显示电脑屏幕，允许按比例留黑边，不允许裁切菜单栏、Dock 或窗口边缘。
- 全屏模式下必须保留 `1x/退出全屏/键盘` 控件可见。
- 静止画面优先清晰，移动中优先流畅，停手后恢复清晰。
- 局部放大后文字、按钮、窗口边缘要尽量比旧 720p 清晰；如果源视频分辨率不足，文档必须标明清晰度上限。

### 2.2 手势输入

- 单指点击：映射为远端鼠标移动 + 左键 down/up。
- 单指移动：映射为远端鼠标移动，不自动按下鼠标键。
- 长按后移动：映射为远端左键拖拽。
- 双指同向移动：未放大时映射为滚轮；放大后映射为本地局部视图平移。
- 双指张开/并拢：本地缩放远程画面，并向 macOS sender 发送 `session.viewport.interaction` 质量提示。
- 放大状态下单指仍然移动电脑鼠标，避免用户放大后失去最核心控制能力。

### 2.3 电脑键盘

- 远控窗口点击输入框时，Android 应提供电脑键盘映射面板。
- 键盘需要覆盖常用电脑键位，不包含右侧数字小键盘。
- 键盘弹框不显示多余标题栏。

### 2.4 质量策略

- Android 真机基础档：当前上限 `800x520@30fps`，用于 JPEG fallback 下的小窗/普通状态可见画面；这是“可见可操作”的保守档，不是最终清晰档。
- 全屏档：当前上限 `640x416@24fps`，用于全屏静止或低强度交互时保完整显示和降低解码压力；当前仍无法达到 `>=24fps` 可见体验。
- 鼠标/滚轮/普通交互档：当前上限 `384x250@30fps`，移动中进一步牺牲清晰度换低延迟反馈；停手后恢复基础档或局部清晰档。
- 双指缩放预览档：当前上限 `320x208@8fps`，只作为 pinch 过程中本地 transform 的背景刷新，避免整屏 JPEG 解码抢手势帧。
- 放大区域移动档：当前上限 `512x333@22fps`，只覆盖当前 `source_rect` 局部区域；目标是缩放后拖动和鼠标移动时优先保交互反馈。
- 放大区域 detail 档：当前上限 `896x582@10fps`，用于停手后先把当前可视区域物化为局部清晰源。
- 放大区域 still 清晰档：当前上限 `1280x832@5fps`，用于静止读文字/点输入框；等效桌面细节密度更高，但视觉 FPS 更低，不能写成“顺滑且清晰”完成。
- JPEG 质量按档位动态选择：交互档优先降低码量，全屏档取中间质量，局部 detail/still 档提高质量；Rust 裁剪 JPEG 当前在 `1,120,000` 像素以内保持锐利质量，超过后走平衡质量，避免大 JPEG 把真机解码拖死。
- 双指缩放结束后，如倍率 `>=1.08x`，macOS sender 可短时进入局部高清档；但当前真机全屏渲染瓶颈未解决，不能把该策略视为已通过体验验收。
- 当前 Android 已能在 `session.viewport.interaction` 中上报可视区域和焦点：`viewport_x/y/width/height`、`focus_x/y`，字段均为完整桌面归一化坐标。
- 当前局部高清档在 JPEG fallback 中已支持区域采集：Android 上报 `viewport_* / focus_*` 后，macOS/Rust capture 可按 `source_rect` 裁剪桌面区域，`screen.frame.push` 会携带 `source_rect_*`，Android 用该区域元数据反算输入坐标。
- 局部高清帧到达 Android 后会被视为已经物化的当前可视区域，Android 会把本地缩放状态归一，再允许用户在该局部帧上继续二次捏合；触摸映射始终按当前 transform 反算，避免“已经裁剪的局部帧又被旧倍率锁住或二次放大”导致顿挫和发虚。
- 该区域高清目前只在 JPEG fallback 链路验证，尚未接入最终 WebRTC/H.264 或硬编码媒体链路；真实双指手感和长时间清晰体验仍未验收。
- Android 端全屏缩放过程中只做 View transform；`ScaleGestureDetector` 未稳定进入缩放时会启用 manual span fallback，按双指距离变化触发 `remote_viewport_pinch_scale source=manual`。缩放后的真实清晰度仍主要由 Mac 端 `source_rect` 局部清晰帧补足。
- 鼠标移动按约 `12ms / 0.00032` 合并历史触摸点，最多消费最近 `4` 个历史点，尾帧最大延迟 `12ms`，本地光标预测 `18ms`；输入链路目标是稳定落地且不回跳，不能用过密信令掩盖视频帧率不足。
- Mac 端成功的 `input.mouse.move` 回执按 `240ms` 节流回传；Mac 仍每条执行鼠标移动，但 Android 不再被 60Hz 级别的 ACK/result 消息反压。
- Desktop JPEG 兜底流本地 UI 预览刷新按 `500ms` 节流；实际 `screen.frame.push` 不降频，用于减少 Mac 端桌面 UI 自身重绘开销。
- Redmi Note 8 Pro / begonia 当前临时走 JPEG 帧流兜底：基础档约 `800x517`，全屏档约 `640x414`，局部 detail 实测约 `896x578 source_rect=0.29,0.31,0.41,0.41`，局部 still 实测约 `1142x737 source_rect=0.29,0.31,0.41,0.41`。这是为了恢复可见性和测试入口，不能替代最终 WebRTC/硬解能力；最新真机复测显示所有 JPEG 档位仍低于 `>=24fps` 流畅目标。
- 2026-07-05 12:14 最新折中参数：基础档 `800x520@30fps`，普通交互档 `448x292@30fps`，局部移动档 `512x334@30fps`，局部高清档 `800x520@12fps` 且短时保持 `2600ms`。真机验证显示基础档约 `9.75-10.17fps @800x517`，普通交互档约 `15.26fps @448x290`，局部高清约 `8.66fps @800x466 source_rect=0.25,0.20,0.50,0.45`；输入落地可用，但视觉流畅度仍未达标。
- legacy JPEG 降级链路必须纳入统一质量指标：Android `session.metrics.report` 应报告 `media_frame_transport=legacy_jpeg`、legacy 首帧、legacy 帧数和 legacy FPS，同时用通用 `first_frame_ms/rendered_frames/render_fps_avg` 表示当前用户实际看到的画面，避免自动汇总把 fallback 会话误判为无画面。
- 2026-07-05 12:50 最新真机复测：会话 `sess-1783226876738-1`，全屏截图 `/tmp/remotedesk-fullscreen-1249.png` SHA256 `5bac107777cb25671ec4a1ae4ea3c3d17d02f7e2b0cd996625dd52f51b17519a` 显示桌面完整；3 秒全屏滑动 Mac `input.mouse.move applied=true` 新增 `348` 条，交互档样本 `16.76/18.43fps @448x290`；debug viewport 后 Mac 切到 `512x334@30fps source_rect` 再到 `800x520@12fps source_rect`，Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，局部高清样本 `9.32/6.82fps`。结论：输入和区域高清链路未回归，但视觉流畅度仍低于 `>=24fps`。
- 2026-07-05 13:09 最新折中参数：鼠标移动采样收敛为 `16ms / 0.00055`，普通交互档保持 `448x292@30fps`，局部移动档保持 `512x334@30fps`，局部高清档调整为 `800x520@10fps` 且短时保持 `7000ms`；Desktop 会在 `source_rect` 明显变化且满足节流窗口时刷新局部裁剪源。真机验证显示全屏完整显示未回归，全屏 3 秒滑动新增 `177` 条 Mac `input.mouse.move applied=true`，约 `59Hz`；debug viewport 后 Android 局部高清约 `6.89/6.98fps @800x466 source_rect=0.25,0.20,0.50,0.45`。结论：输入连续性更稳，缩放后局部源区域更新逻辑更完整，但视觉 FPS 仍未达标。
- 2026-07-05 13:35 最新真机复测：Android legacy JPEG 显示改为 `RemoteDeskFrameView` 复用绘制路径，局部 `source_rect` 帧保留 `ARGB_8888`，Desktop 局部裁剪刷新收紧到 `220ms/20000ppm`。会话 `sess-1783229340532-1` 中标准 proof 为 `video_and_input_observed`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_frame_view_entered_1332.png` SHA256 `7bbcf3186bebc5aa8bf98da3b7716e1d1bb8c9910f1b926101fbbe74b3c51e79` 显示桌面完整；全屏 3 秒滑动新增 `178` 条 Mac `input.mouse.move applied=true`，约 `59Hz`，交互期最高样本 `16.65fps @800x517`；debug viewport 后 Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，局部高清样本约 `7.71/7.07fps`。结论：全屏完整、输入落地和局部高清源链路未回归，局部清晰度路径更稳；视觉 FPS 仍未达 `>=24fps`，真实双指 pinch 手感仍未人工验收。
- 2026-07-05 13:46 最新真机复测：Android legacy JPEG 解码加入 Bitmap 复用池，已退场帧优先作为下一帧 `inBitmap`，失败时无复用重试；Base64 帧解码优先 `NO_WRAP`。会话 `sess-1783230202122-1` 中标准 proof 为 `video_and_input_observed`、`first_frame_ms=153`、`render_fps_avg=10.00`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_bitmap_reuse_1345.png` SHA256 `b707042907583f5875d1af01f08c15d8915171edbe70d5d2397391cf35abc441` 显示桌面完整；全屏 3 秒滑动新增 `181` 条 Mac `input.mouse.move applied=true`，约 `60Hz`，交互档样本 `14.97/17.91fps @448x290`；debug viewport 后 Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，局部高清样本约 `6.86/6.90fps`，日志未出现 `legacy_bitmap_reuse_failed`。结论：复用池没有造成显示、输入或 source_rect 回归，但视觉 FPS 仍未达 `>=24fps`。
- 2026-07-05 14:32 最新真机复测：Android 鼠标 move 发送采样收紧到 `6ms / 0.00024`，Mac move 队列节流收紧到 `6ms`；全屏 pinch 过程中不再频繁重配局部 `source_rect`，停手后再进入局部高清；局部高清档收敛为 `1024x668@20fps`，避免上一轮 `1280x746` 只有约 `3fps`。会话 `sess-1783233039204-1` 中标准 proof 为 `video_and_input_observed`、`first_frame_ms=55`、`media_frame_transport=legacy_jpeg`、`remote_input_applied=16/16`；全屏截图 `/tmp/remotedesk-final-fullscreen.png` 显示桌面完整居中，左右黑边为比例适配。debug viewport 后 Mac 先切 `800x520@24fps source_rect_ppm=250000,200000,500000,450000`，随后切 `1024x668@20fps source_rect_ppm=250000,200000,500000,450000`；Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45`。长滑后 Mac `input.mouse.move` 执行日志均为 `applied=true executor=macos.cg_event`，relay combined 显示 `remote_input_last_count=39`、result push 汇总 `16/16 applied`。结论：全屏完整、局部高清切档、输入落地未回归，鼠标落点比上一轮更密；但 `render_fps_avg` 仍约 `6.10fps`，当前 JPEG fallback 仍未达到全屏/缩放/鼠标视觉流畅目标。
- 2026-07-05 14:54 最新真机复测：Android 远端 move 回到 `16ms / 0.00045`，全屏本地承载面回到 `1.0x`，pinch 倍率/焦点滤波收敛为 `0.78/0.72`；Desktop 真机整屏档为 `800x520@30fps`、移动档 `448x292@30fps`、局部移动档 `512x334@24fps`、局部高清档 `960x624@16fps`，整屏/移动 JPEG 质量降为 `54/46`。会话 `sess-1783234294807-1` 中标准 proof 通过：`first_frame_ms=54`、`media_frame_transport=legacy_jpeg`、`remote_input_applied=8/8`；全屏截图 `/tmp/remotedesk-final-fullscreen-1452.png` SHA256 `755ffb322f10bc7398d0b62ffa375d03954de9da30420b2073157f351c9f54d4` 显示桌面完整。全屏 3 秒滑动新增 Mac `input.mouse.move applied=true` `160` 条；移动交互档最高约 `17.88fps @448x290`。debug viewport 后 Mac 切到 `512x334@24fps source_rect` 再切 `960x624@16fps source_rect`，Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`；局部高清样本约 `5.54fps` 后降到 `2.92fps`。结论：输入和区域高清链路未回归，但 JPEG fallback 仍未达流畅目标。
- 2026-07-05 16:35 最新真机复测：Desktop 局部高清档回收到 `960x624@12fps`，保持窗口 `4400ms`，局部 JPEG 质量回收到 `84/80`。会话 `sess-1783240363109-1` 标准 proof 通过：`first_frame_ms=55`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=10.07`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_quality_tune.png` SHA256 `3f97b4e4b7d985089183cc54d7168872d6f7993755eb2b79f6ad30c8261543d6` 显示桌面完整。全屏 3 秒滑动新增 Mac `input.mouse.move applied=true` `178` 条，约 `59Hz`；debug viewport 后 Mac 切到 `560x364@24fps source_rect` 再切 `960x624@12fps source_rect`，Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`；局部高清样本先到 `8.06fps`、随后约 `5.18fps`。结论：降低 JPEG 质量改善了局部高清首个样本，但稳定帧率仍远低于 `>=24fps`，不能把缩放顺滑、缩放后清晰和鼠标视觉跟手写成已完成。
- 2026-07-05 19:45 最新真机复测：Android 全屏 pinch 过程中继续关闭整屏 Bitmap 高质量滤波，只保留轻量矩阵动画；Mac/Rust capture worker 增加 `avg_capture_ms/max_capture_ms/last_capture_ms/target_interval_ms/last_gap_ms/overruns` 采样日志，并在完整帧无裁剪且尺寸不变时跳过一次 BGRA 拷贝。APK SHA256 `9782281fb41bcea56c71668667eb8a40540990736e39ad813ad37e4e20090e6b` 已安装真机。会话 `sess-1783251823436-1` 标准 proof 通过：`first_frame_ms=38`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=9.47`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_worker_tune_1944.png` SHA256 `7cf2ea4cd375a343feb3acd841f3d61ad9fd3336e48ee81c46fd5a5a6a5efa97` 显示完整桌面；debug viewport 后截图 `.rd_runtime/screens/rd_debug_viewport_worker_tune_1945.png` SHA256 `fa3e9ad29321850f87f94324c4bdc9a693939c7fe6e9f5424b2cc054252d7b87`，Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清约 `5.46/4.47fps`。Mac worker 采样显示 `800x517 target_interval_ms=33` 时 `last_capture_ms` 常见约 `99-112ms`，`448x290` 时约 `53ms`，`640x414 target_interval_ms=41` 时约 `73-90ms` 且 overruns 持续增加。结论：全屏完整、输入和区域高清未回归，但核心瓶颈已实测落在 `legacy_jpeg` 捕获/编码/传输/解码链路，不能继续把 JPEG fallback 当最终流畅方案。
- 2026-07-05 21:11 最新真机复测：Android 鼠标 move 发送会消费最近最多 `4` 个 `MotionEvent` 历史点，pinch 期间整屏预览帧间隔调整到 `2200ms`。APK SHA256 `3418a054e54419c73b4155a1d931f1c8244bfc55c1e8b5c2d3904ab4f3ebd93e` 已安装真机。会话 `sess-1783256887975-1` 标准 proof 通过：`first_frame_ms=43`、`media=legacy_jpeg`、`render_fps_avg=9.91/11.20`、`remote_input_applied=16/16`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_history_points_2109.png` SHA256 `81324c457568b2448c52cc7f590d6291be23a5a22215a3bd8225a04666ae46c9` 和滑动后截图 `.rd_runtime/screens/rd_fullscreen_after_history_points_swipe_2110.png` SHA256 `0f0c2de71865a3fde2fa0b2cb11e2e980999db88b507f983afe0a3dd5c93988f` 均显示远端桌面完整；全屏滑动新增 Mac `input.mouse.move applied=true` `157` 条，applied 间隔多为约 `16-20ms`。debug viewport 后 Mac `source_rect_ppm=250000,200000,500000,450000`，Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，截图 `.rd_runtime/screens/rd_debug_viewport_after_history_points_2111.png` SHA256 `cf449cd4a42bc2f003a5de31a94720b1a0885289b727362bd07f95d49b42f56d`，局部高清约 `10.56fps` 后稳定到 `6.99/7.07fps`。结论：输入轨迹、全屏完整显示和区域高清链路未回归，但视觉 FPS 仍低于 `>=24fps`，真实双指 pinch 手感仍未自动化闭环。
- 2026-07-06 00:15 最新真机复测：Android 鼠标 move 当前为 `12ms / 0.00032`，历史点最多 `4`，尾帧最大 `12ms`，本地光标预测 `18ms`；双指缩放新增 manual span fallback。APK SHA256 `d291a85bfc6cf0d6ac0383549ec8b75b6f2092775025d4b5f9bef4624a55115b` 已安装真机。会话 `sess-1783267574091-1` 标准 proof 为 `video_and_input_observed`、`first_frame_ms=31`、`media=legacy_jpeg`、`remote_input_applied=8/8`；debug 双指注入触发 `remote_manual_pinch_begin/end` 和 `remote_viewport_pinch_scale source=manual`，倍率约 `1.185 -> 2.437`。Mac 切到 `source_rect_ppm=294671,306641,410657,410305`，Android 物化为 `source_rect=0.29,0.31,0.41,0.41`；局部 detail 样本约 `7.95fps @896x578`，still 清晰帧约 `4-5fps @1142x737`。缩放后全屏滑动 Mac `input.mouse.move applied=true` 从 `3` 增到 `182`，新增约 `179` 条。结论：manual pinch、局部清晰源和鼠标输入落地未回归；但当前可见媒体仍是 `legacy_jpeg`，视觉 FPS 仍未达标，真实手指双指 pinch 手感仍未人工验收。

### 2.5 剪贴板和文件传输

- Android 和 macOS 会话中需要支持双向文本剪贴板同步。
- Android 和 macOS 会话中需要支持双向小文件传输，当前单文件上限为 `64MB`。
- Android 接收文件应保存到用户可见的 `Downloads/RemoteDesk/`；macOS 接收文件应保存到 `~/Downloads/RemoteDesk/`。
- Android 发送普通用户文件必须通过系统文件选择器授权；不能依赖直接读取 `/sdcard/Download/...` 路径。
- 剪贴板和文件传输都必须回传目标端应用结果：`clipboard.result` 表示目标系统剪贴板是否写入成功，`file.transfer.result` 表示目标端是否完成校验并保存文件。

## 3. 已验证事实

- Android debug 构建通过。
- Desktop 前端构建通过。
- 真机 `wsvwypiz7xwslvl7`、Mac agent `agent-19de3117874`、relay `127.0.0.1:18081` 三端可启动。
- 标准 E2E proof 通过：`remote_input_applied=11/11`，覆盖 `click,drag,keyboard,wheel`。
- 全屏完整显示通过：截图 `/tmp/remotedesk-20260705-fullscreen-after-current.png` 可见远端桌面完整显示，黑边为比例适配。
- 全屏长滑动输入链路通过：最新窗口内 Android 发送 `input.mouse.move` 170 条，Mac 执行 `applied=true` 170 条。
- Mac sender 交互档通过：`800x517` 下 `probe.sample fps=31.68/31.41`。
- Mac sender 全屏基础档已接通：`1120x724` 下 `probe.sample fps=23.89/23.73`。
- 2026-07-05 05:08 复测：全屏 UI tree 中 `remoteViewportContainer`、`remoteViewportContent`、`remoteVideoView` 均为 `[372,0][2043,1080]`，截图 `/tmp/remotedesk-20260705-fullscreen-60hz-swipe.png` 显示桌面完整；说明“内容层旧尺寸导致显示不完整/发虚”已修复。
- 2026-07-05 05:08 复测：全屏 4 秒滑动 Android 发送 `input.mouse.move` 208 条，Mac `applied=true` 208 条；输入不丢，且比上一轮 `281/281` 降低了信令压力。
- 2026-07-05 05:45 复测：新增 `TextureView + EglRenderer` 全屏 A/B，日志确认切换到 `mode=texture`，但 Android 端 `1120x724` 仍跌到 `7fps` 左右并持续 dropped frames。
- 2026-07-05 05:45 复测：临时放开 Redmi Note 8 Pro 的 MTK AVC 硬解后仍 `frames_decoded=0`，watchdog 报 `track_no_frame`；因此当前硬解屏蔽是必要兜底。
- 2026-07-05 05:45 复测：临时把全屏基础档降到 `800x520` 后 Mac sender 可到 `31fps`，但 Android 全屏仍约 `8fps`；不能把继续降分辨率当作最终修复。
- 2026-07-05 05:57 真机自动验收：Mac -> Android 剪贴板 `RD_MAC_CLIP_V2_20260705` 写入 Android；Android -> Mac 剪贴板 `RD_ANDROID_CLIP_V2_20260705` 写入 Mac。
- 2026-07-05 05:58 真机自动验收：Mac -> Android 文件哈希 `45d0018209f37e2680c8e67afd30a2ef7198eea14438838c51e982cf55a0147e` 一致；Android -> Mac app-private 文件哈希 `8613621ccb704702f10c66ed2eaaf6623c51f73cc03f195d34b830445146dc5a` 一致。
- 2026-07-05 06:03 真机 UI 验收：Android 真实“发剪贴板”按钮发送 `UI_ANDROID_CLIP_20260705` 后，Mac `pbpaste` 输出同值，Android UI 显示 `剪贴板：已发送 24 字符`。
- 2026-07-05 07:05 真机验收：relay 已转发 `screen.frame.push`；Android `legacy_first_frame ... size=1114x720`，后续 `legacy_frame_sample` 约 `9.19-9.60fps size=1547x1000`；全屏截图 `/tmp/remotedesk-fullscreen-legacy-only-100ms.png` 显示完整桌面；全屏长滑动 Android `input.mouse.move` 133 条，Mac `applied=true` 133 条。
- 2026-07-05 07:22 真机验收：JPEG-only 模式下 Desktop 交互档切换已对兜底帧流生效；会话 `sess-1783207273917-1` 中自动 proof 触发 `800x520`，Android `legacy_frame_sample fps=10.22 size=800x517`，停手恢复 `1547x1000`。
- 2026-07-05 07:22 真机验收：全屏按钮触发 `session.viewport.interaction` 后，Mac `config.updated max_width=1120 max_height=728`，Android 收到 `1120x724`；全屏截图 `/tmp/remotedesk-fullscreen-swipe-adaptive-20260705-0722.png` SHA256 `3baca0c87d999e0ba6342ce4f7b75319aedac42e070ff646b4b2b51b386f629f`，画面完整，左右黑边为比例适配。
- 2026-07-05 07:22 真机验收：全屏横屏长滑动期间 Mac `config.updated max_width=800 max_height=520`，Android 收到 `800x517`，`legacy_frame_sample` 约 `7.59/9.42/9.49fps`；Android 发送 `input.mouse.move` 232 条，Mac `applied=true` 232 条，执行器 `macos.cg_event`。
- 2026-07-05 07:31 真机验收：会话 `sess-1783207811491-1`；交互档已调整为 `640x416`，Android 收到 `640x414`，自动 proof 期间 `legacy_frame_sample fps=13.40`，较上一轮 `800x517` 约 `10.22fps` 有改善。
- 2026-07-05 07:31 真机验收：进入全屏后 Mac `config.updated max_width=960 max_height=624`，Android 收到 `960x621`；全屏稳定样本约 `7.40-7.97fps`；截图 `/tmp/remotedesk-after-tuning-fullscreen-20260705-0731.png` SHA256 `bd07946d39f0c200237eca43f9c6d31f0d5f309324170c6c684281dee79d7453`。
- 2026-07-05 07:31 真机验收：全屏长滑动期间 Android 发送 `input.mouse.move` 200 条；Mac 时间窗内 `input.mouse.move applied=true` 232 条，因 Mac 结果行无 trace 字段只能按时间窗统计，多出的记录来自窗口边界前序回执；滑动期间 Android 视觉样本约 `12.01/12.96fps size=640x414`。
- 2026-07-05 07:31 真机验收：Android 系统 `input motionevent` 只支持单点 `DOWN/MOVE/UP`，没有 pointer id 参数；普通 adb 自动化仍不能证明真实双指 pinch。
- 2026-07-05 07:58 真机补证：截图 `/tmp/remotedesk-current-20260705.png`，SHA256 `3841d295e80e5a9232fc966a7edef29e25a434e2c5b621584f87c36289b061e7`，显示全屏远程桌面完整，左右黑边为比例适配。
- 2026-07-05 07:58 真机补证：`gfxinfo` 当前进程 `Total frames rendered=2752`、`Janky frames=40 (1.45%)`、P95 `18ms`、P99 `24ms`，普通 Android UI 绘制不是当前最主要瓶颈。
- 2026-07-05 07:58 真机补证：全屏 4 秒长滑动新增日志中，relay 收到并转发 `input.mouse.move` `231/231`，Mac `input_result type=input.mouse.move applied=true` `231`；移动期间 Android 视觉样本 `12.86/13.32/13.44fps @640x414`，停手后恢复 `960x621` 约 `7.5-8.0fps`。
- 2026-07-05 08:19 真机补证：会话 `sess-1783210557558-1` 中 Android 发出 `session.viewport.interaction`，payload keys 包含 `viewport_x/y/width/height` 与 `focus_x/y`，relay `session.tool.forwarded` 同步转发，证明区域高清所需的视口数据链路已到 Mac。
- 2026-07-05 08:19 真机补证：全屏截图 `/tmp/remotedesk-fullscreen-swipe-0816.png` SHA256 `1bf503e97d6a83904b9706bdbbc7304479b5480a5d29463b4e72adbeb6d62980`；全屏长滑动窗口内 Mac `input.mouse.move applied=true` 约 `249` 条；Android 交互档视觉样本 `13.31/13.53/13.47fps @640x414`，停手后全屏档约 `7.7-8.0fps @960x621`。
- 2026-07-05 08:57 真机补证：会话 `sess-1783212760043-1` 中 debug viewport 触发后，Mac `config.updated ... source_rect_ppm=250000,200000,500000,450000`，Android `legacy_frame_sample ... size=1028x599 source_rect=0.25,0.20,0.50,0.45`，证明区域高清 JPEG fallback 裁剪链路已从 Android 到 Mac 再回 Android。
- 2026-07-05 08:57 真机补证：2 秒内截图 `/tmp/remotedesk-during-materialized-source-debug.png` SHA256 `68acfe6ee9f62344196cc6ac9a9071c60388f815513147ed006248377e76fbad` 显示局部帧完整显示，不再二次放大成发虚/只剩局部一角。
- 2026-07-05 08:57 真机补证：全屏截图 `/tmp/remotedesk-fullscreen-after-materialized-source.png` SHA256 `0cb8a556540a3d4658fd6031d4146ed24670a2236241fb2434771db98c452c02` 显示桌面完整；全屏 4 秒滑动后 Mac `input.mouse.move applied=true` 从 `4` 增到 `238`，新增约 `234` 条；`gfxinfo` 为 `Janky frames=110/2556 (4.30%)`、P95 `20ms`、P99 `36ms`。
- 2026-07-05 09:16 真机补证：会话 `sess-1783213961511-1`，最新 APK 安装后全屏截图 `/tmp/remotedesk-0915-fullscreen.png` SHA256 `3989f4fe3dfc3aa3d95c7158c3747ab01fd11607425a1ebdee6ece86b0ec7dd6` 显示桌面完整，滑动后截图 `/tmp/remotedesk-0916-fullscreen-swipe.png` SHA256 `2b19c1c393ed9b9b0190ff938bc0755921f80f3b5f6719a0786e8d4c4eb4d9d2`。
- 2026-07-05 09:16 真机补证：`560x362` 交互档已生效，滑动期间 Android 发送汇总为 `4+61+60+60` 条 mouse move，Mac 时间窗内 `input.mouse.move applied=true` 235 条；Android 视觉样本为 `13.30/14.35fps @560x362`，停手后恢复 `960x621` 约 `7.4-8.0fps`。
- 2026-07-05 09:16 真机补证：debug viewport 触发局部高清后，Mac `config.updated max_width=1280 max_height=800 max_fps=24 ... source_rect_ppm=250000,200000,500000,450000`，Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45`，证明 `1.08x` 触发后的裁剪/JPEG 质量路径未破坏。
- 2026-07-05 09:37 真机补证：最终保守版 APK 已重新安装，三端重启后会话 `sess-1783215376937-1` 建立；标准输入 proof 仍为 `11/11 applied`，全屏截图 `/tmp/remotedesk-0937-final-fullscreen.png` SHA256 `f96edc01c1fd7e17d7d2a530eaa72cff5f1fdcb321ab2479d724c57d16de35b6` 显示桌面完整。
- 2026-07-05 09:37 真机补证：全屏 3 秒滑动后，Mac 新增 `input.mouse.move applied=true` 177 条；滑动后截图 `/tmp/remotedesk-0937-final-fullscreen-swipe.png` SHA256 `3537a1d711e72aaf2fe68ef3c781eb7f9a469668fb273620236028aac6a7e2a3`。
- 2026-07-05 09:32/09:37 真机补证：激进调度版曾测得全屏交互档 `11.63/11.06fps @560x362`、局部高清 `5.85-6.49fps @1028x599 source_rect=0.25,0.20,0.50,0.45`；最终保守版全屏交互样本仍只有约 `5.73/7.06/6.28/7.46/8.06fps @560x362`，未达 `>=24fps`。
- 2026-07-05 09:42 真机补证：最终保守版 debug viewport 后，Mac 切到 `1280x800@24fps source_rect_ppm=250000,200000,500000,450000`，Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45`；但局部高清视觉样本只有 `3.56/3.77fps`，只能证明裁剪链路仍通，不能证明缩放体验达标。
- 2026-07-05 10:04-10:08 真机补证：Android 单次 JPEG 解码优化后重新安装并重启三端，会话 `sess-1783217064539-1` 建立；标准输入 proof 仍为 `11/11 applied`。基础档 `800x517` 稳定约 `9.4-10.4fps`，远程画面区域 3 秒滑动后 Mac `input.mouse.move applied=true` 从 `4` 增到 `138`，新增 `134` 条；移动期间交互档 `512x331` 达到 `14.70/15.66fps`。debug viewport 后 Mac 切到 `1120x700@18fps source_rect_ppm=250000,200000,500000,450000`，Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45`，局部高清视觉样本 `7.16/7.54fps`。截图 `/tmp/remotedesk-1005-viewport.png` SHA256 `bee17220fd2e6c15472098e84fb39953a3cf8be6f0b76be8691f98ee0dd09aef`，滑动后 `/tmp/remotedesk-1007-swipe.png` SHA256 `6e1db72b74501c6cf0f59b05ed620d727fbd52bd765ec50f1ad372d22cc349cb`，debug viewport `/tmp/remotedesk-1008-debug-viewport.png` SHA256 `fe89f5393c0089b7cbe2ef6638b8139be7b16ee68f4e561f2f70ffaf89ad765a`。
- 2026-07-05 10:25 真机补证：本轮启用全屏 TextureView、全屏基础承载面 `1.0x`、鼠标 move 回执节流和 Desktop 本地预览节流后，重新安装并重启三端，会话 `sess-1783218311459-1` 建立；基础档 `800x517` 仍约 `9.7-10.0fps`，自动 proof 后交互档 `512x331` 约 `14.35fps`，说明 UI 预览节流没有根治 JPEG 链路瓶颈。
- 2026-07-05 10:21-10:22 真机补证：真实点击小窗“全屏”按钮第二次坐标命中后进入全屏，Android 日志 `remote_video_renderer_switch mode=texture fullscreen=true`，截图 `/tmp/remotedesk-fullscreen-after-tap2.png` 显示远端桌面完整居中，左右黑边为比例适配。debug viewport 触发后，Mac `config.updated max_width=1120 max_height=700 max_fps=18 source_rect_ppm=250000,200000,500000,450000`，Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45`，局部高清样本约 `7.09-8.40fps`，截图 `/tmp/remotedesk-debug-viewport-after-change.png` 显示局部帧完整显示。
- 2026-07-05 10:22 真机补证：全屏远程画面 3 秒滑动后，Mac `input.mouse.move` 从 `4` 增到 `181`，新增 `177` 条，约 `59` 条/秒，均 `applied=true`；Android 日志显示 `input.result.mouse.move` 回执被节流为约每秒 8 条，输入落地和 ACK 降压同时成立。
- 2026-07-05 10:55 真机补证：本轮修改后重新安装并重启三端，会话 `sess-1783219924972-1` 建立；`git diff --check`、server `go test ./...`、Desktop `npm run build`、Tauri `cargo test` 33 个测试、Android `assembleDebug` 均通过。
- 2026-07-05 10:55 真机补证：全屏真实按钮点击进入横屏，截图 `.rd_runtime/screens/remote_fullscreen.png` 显示远端桌面完整居中，左右黑边为比例适配；当前画面基础档为 `800x517`。
- 2026-07-05 10:55 真机补证：debug viewport 触发后，relay 转发 `session.viewport.interaction`；Mac 先切 `512x334@16fps source_rect_ppm=250000,200000,500000,450000`，随后切 `1280x800@16fps source_rect_ppm=250000,200000,500000,450000`；Android 收到 `1028x599 source_rect=0.25,0.20,0.50,0.45`。
- 2026-07-05 10:55 真机补证：全屏单指移动压测期间 relay 看到 `input.mouse.move` 约 `16-17ms` 间隔转发，Mac 端 `input.mouse.move applied=true executor=macos.cg_event`；relay 合并指标显示输入覆盖 `click,drag,keyboard,wheel`，最后 mouse move `applied`。
- 2026-07-05 11:25 真机补证：小窗“全屏”按钮点击会直接派发给按钮本身，不再被远程触控层误发成 Mac 鼠标点击；Android 日志 `remote_video_renderer_switch mode=texture fullscreen=true`，截图 `.rd_runtime/screens/rd_fullscreen_final_1125.png` SHA256 `207568e6a32b08f92b30a9d6521bc304fe0655bfb6839741269a152433550bd9` 显示远端桌面完整居中。
- 2026-07-05 11:25 真机补证：全屏 3 秒滑动后，Mac `input.mouse.move applied=true` 从 `7` 增到 `193`，新增 `186` 条，约 `62` 条/秒，执行器为 `macos.cg_event`；截图 `.rd_runtime/screens/rd_fullscreen_swipe_final_1125.png` SHA256 `a08788abe005e4050c75beb03c376f2b11e4dcf85c970976011d34a5c04a9962`。
- 2026-07-05 11:30 真机补证：最终局部裁剪档收敛为 `720x468@18fps`，debug viewport 后 Mac `config.updated max_width=720 max_height=468 max_fps=18 source_rect_ppm=250000,200000,500000,450000`，Android 收到 `720x420 source_rect=0.25,0.20,0.50,0.45`；首个样本 `11.46fps`，稳定后约 `6.68-6.89fps`，截图 `.rd_runtime/screens/rd_debug_viewport_720_final_1129.png` SHA256 `8a3d02ead28527adb24d42b2e35374166fad5a68a01070c6e028385bdffd7770`。
- 2026-07-05 11:45 真机补证：会话 `sess-1783222978563-1`，全屏按钮点击进入横屏，日志 `remote_video_renderer_switch mode=texture fullscreen=true`；截图 `.rd_runtime/screens/rd_fullscreen_after_zoom_114538.png` SHA256 `38e15b3c136667150bb9b858b4f4ee8338e203cfee27bf521bdd382c5b8ec00e` 显示远端桌面完整居中。
- 2026-07-05 11:45 真机补证：debug viewport 后 Mac 先切 `512x334@16fps source_rect_ppm=250000,200000,500000,450000`，随后切 `864x562@16fps source_rect_ppm=250000,200000,500000,450000`；Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45` 并收到 `864x503 source_rect=0.25,0.20,0.50,0.45`。
- 2026-07-05 11:45 真机补证：横屏全屏 900ms 单指滑动新增 Mac `input.mouse.move applied=true` `59` 条，约 `65/s`，执行器 `macos.cg_event`；滑动期间 Mac 临时切到 `512x334@30fps`，停手后恢复 `800x520@30fps`。
- 2026-07-05 12:14 真机补证：会话 `sess-1783224608715-1` 中基础档 `800x517` 样本约 `9.75/10.17/9.88fps`；普通交互档 Mac 切到 `448x292@30fps`，Android 收到 `448x290`，样本约 `15.26fps`。
- 2026-07-05 12:14 真机补证：debug viewport 后 relay 转发 `session.viewport.interaction`，Mac 修复后正确切到 `512x334@30fps source_rect_ppm=250000,200000,500000,450000`，随后 `800x520@12fps source_rect_ppm=250000,200000,500000,450000`；Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，样本约 `8.66fps`。
- 2026-07-05 12:14 真机补证：全屏截图 `.rd_runtime/screens/rd_fullscreen_try3_1211.png` SHA256 `da05eeea66dee8ea1781d95c18b7b5f4e4bcac786fbb162d30bc6bf26418a7d4` 显示远端桌面完整居中；全屏滑动后 Mac `input.mouse.move applied=true` 新增 `71` 条。
- 2026-07-05 12:23 真机补证：Android legacy JPEG 指标已纳入 `session.metrics.report` 通用视频字段，relay combined summary 已出现 `media_frame_transport=legacy_jpeg`、`first_frame_ms=43`、`rendered_frames=66`、`render_fps_avg=9.92`、`legacy_rendered_frames=66`；fallback 会话不再被误判为 `first_frame_ms=-1/rendered_frames=0`。
- 2026-07-05 13:09 真机补证：会话 `sess-1783227836869-1` 中标准 proof 为 `session_e2e_proof_status=video_and_input_observed`，`first_frame_ms=107`，`media_frame_transport=legacy_jpeg`，`render_fps_avg=10.14`，`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。
- 2026-07-05 13:09 真机补证：全屏截图 `.rd_runtime/screens/rd_fullscreen_1308.png` SHA256 `c09d6306b81397290b7933554df79d98d5fd59d2e0f6e7272b29fcc4cb3721a2` 显示远端桌面完整居中，左右黑边为比例适配；全屏 3 秒滑动后 Mac `input.mouse.move applied=true` 从 `179` 增到 `356`，新增 `177` 条，执行器为 `macos.cg_event`。
- 2026-07-05 13:09 真机补证：debug viewport 后 Mac 先切 `512x334@30fps source_rect_ppm=250000,200000,500000,450000`，随后切 `800x520@10fps source_rect_ppm=250000,200000,500000,450000`；Android 记录 `remote_viewport_source_rect_materialized rect=0.25,0.20,0.50,0.45` 并收到 `800x466 source_rect=0.25,0.20,0.50,0.45`，局部高清样本约 `6.89/6.98fps`。
- 2026-07-05 14:54 真机补证：会话 `sess-1783234294807-1` 标准 proof 为 `video_and_input_observed`、`first_frame_ms=54`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=6.14`、`remote_input_applied=8/8`。全屏截图 `/tmp/remotedesk-final-fullscreen-1452.png` SHA256 `755ffb322f10bc7398d0b62ffa375d03954de9da30420b2073157f351c9f54d4` 显示桌面完整；3 秒滑动新增 Mac `input.mouse.move applied=true` `160` 条；debug viewport 后 Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`。
- 2026-07-05 15:10 真机补证：本轮补齐 `clipboard.result` 和 `file.transfer.result` 后，三端重启并安装最新 APK。会话 `sess-1783235440137-3` 标准 proof 为 `video_and_input_observed`、`first_frame_ms=111`、`media_frame_transport=legacy_jpeg`、`remote_input_applied=8/8`，覆盖 `click,drag,keyboard,wheel`。
- 2026-07-05 15:10 真机补证：Mac -> Android 剪贴板 `mac-to-android-clipboard-1783235203` 写入 Android，Android 回传 `clipboard.result`；Mac -> Android 文件保存到 Android app-private fallback：`/data/user/0/com.remotedesk.app/files/RemoteDeskIncoming/remotedesk-mac-to-android-tool-test.txt`，Android 回传 `file.transfer.result`。
- 2026-07-05 15:10 真机补证：Android -> Mac 剪贴板回传成功，Android UI 显示 `剪贴板：对端已写入 35 字符`；Android -> Mac 文件 `remotedesk-android-to-mac-tool-test2.txt (64 B)` 保存到 `~/Downloads/RemoteDesk/`，Mac 目标文件与源文件 SHA256 均为 `c8feb20cf6ef5cfb5ee153e4b88fe33d883fbded2895b2138ae205cc0b0fa76b`，Android UI 显示 `文件：对端已保存 remotedesk-android-to-mac-tool-test2.txt (64 B)`。
- 2026-07-05 15:33 真机补证：本轮把局部停手高清档从 `1088x708@18fps` 回收到 `960x624@16fps`，并把保持窗口从 `7000ms` 缩短为 `4200ms`。会话 `sess-1783236665948-1` 标准 proof 通过，`first_frame_ms=47`、`media_frame_transport=legacy_jpeg`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_detail_tune_1531.png` SHA256 `275a945fa141aee4497d9b69b02ab7ee2d3d993233686d683c6cca7904021109` 显示桌面完整；全屏 3 秒滑动新增 Mac `input.mouse.move applied=true` `159` 条；debug viewport 后 Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清样本 `6.76/5.25fps`，截图 `.rd_runtime/screens/rd_debug_viewport_960_1533.png` SHA256 `bc0d7faecc82c4dcf68b639537f8deb5d4b43586d168fb74643783ff5f702394`。
- 2026-07-05 15:50 真机补证：本轮新增全屏 pinch 期间临时硬件合成层、缩放/局部帧高质量 Bitmap 缩放，并把鼠标 move 收敛为 `16ms / 0.00030`。会话 `sess-1783237518336-1` 标准 proof 通过：`first_frame_ms=62`、`media_frame_transport=legacy_jpeg`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_smooth_tune_1547.png` SHA256 `de5fb682c200f1cacc168a1b80602bc46ddad985a2a182dbcdf4290ad0a77d7f` 显示桌面完整；全屏三段滑动新增 Mac `input.mouse.move applied=true` `150` 条，约 `55Hz`，交互档最高 `16.92fps @448x290`；debug viewport 后 Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清样本 `5.59/5.22fps`。结论：本地缩放合成、输入节奏和区域高清链路未回归，但 JPEG fallback 视觉 FPS 仍未达标。
- 2026-07-05 16:06 真机补证：本轮新增 pinch 进行中整屏 legacy JPEG 展示节流、Mac 鼠标移动队列从原生输入开始计 `16ms` 节流窗口，并把局部 JPEG 锐利档阈值提高到 `450k` 像素。会话 `sess-1783238649036-1` 标准 proof 通过：`first_frame_ms=57`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=9.66`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_current_tune.png` SHA256 `8e054ea91b2c16a5989983be38b3cc05bdd87f455fd2988d15042e89b648838c` 显示桌面完整；全屏 3 秒滑动新增 Mac `input.mouse.move applied=true` `170` 条，约 `56-57Hz`，交互档最高 `18.24fps @448x290`；debug viewport 后 Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清样本 `5.28/5.39fps`。结论：输入执行节奏和全屏完整显示未回归，交互峰值略高；但 JPEG fallback 仍无法满足 `>=24fps`，真实双指 pinch 手感仍未自动闭环。
- 2026-07-05 16:35 真机补证：本轮把局部高清从 `1024x668@12fps` 回收到 `960x624@12fps`，并把局部 JPEG 质量从 `90/88` 回收到 `84/80`。会话 `sess-1783240363109-1` 标准 proof 通过：`first_frame_ms=55`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=10.07`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_quality_tune.png` SHA256 `3f97b4e4b7d985089183cc54d7168872d6f7993755eb2b79f6ad30c8261543d6` 显示桌面完整；全屏 3 秒滑动新增 Mac `input.mouse.move applied=true` `178` 条，约 `59Hz`；debug viewport 后 Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，局部高清样本 `8.06/5.18fps`。结论：局部高清首样本较 1024 宽高质量低谷改善，但稳定视觉 FPS 仍不达标，真实双指 pinch 手感仍未自动闭环。
- 2026-07-05 19:45 真机补证：会话 `sess-1783251823436-1` 中三端可启动，标准 proof 为 `video_and_input_observed`、`first_frame_ms=38`、`media_frame_transport=legacy_jpeg`、`render_fps_avg=9.47`、`remote_input_applied=8/8`；全屏截图 `.rd_runtime/screens/rd_fullscreen_after_worker_tune_1944.png` SHA256 `7cf2ea4cd375a343feb3acd841f3d61ad9fd3336e48ee81c46fd5a5a6a5efa97` 显示桌面完整，左右黑边为比例适配；Android `gfxinfo` 在全屏 3 秒滑动窗口中 `Janky frames=0 (0.00%)`、P95 `13ms`、P99 `15ms`，说明普通 Android UI 合成不是本轮主要瓶颈。Mac worker 日志证明 JPEG fallback 捕获/编码耗时常常超过目标帧间隔：`800x517` 常见 `99-112ms` 对 `33ms` 目标，`640x414` 常见 `73-90ms` 对 `41ms` 目标。
- 2026-07-05 21:11 真机补证：会话 `sess-1783256887975-1` 中三端可启动，标准 proof 为 `video_and_input_observed`、`first_frame_ms=43`、`media=legacy_jpeg`、`render_fps_avg=9.91/11.20`、`remote_input_applied=16/16`；APK SHA256 `3418a054e54419c73b4155a1d931f1c8244bfc55c1e8b5c2d3904ab4f3ebd93e`。全屏截图 `.rd_runtime/screens/rd_fullscreen_after_history_points_2109.png` SHA256 `81324c457568b2448c52cc7f590d6291be23a5a22215a3bd8225a04666ae46c9` 和滑动后截图 `.rd_runtime/screens/rd_fullscreen_after_history_points_swipe_2110.png` SHA256 `0f0c2de71865a3fde2fa0b2cb11e2e980999db88b507f983afe0a3dd5c93988f` 显示桌面完整；全屏滑动新增 `157` 条 Mac `input.mouse.move applied=true`；Android `gfxinfo` 在全屏、滑动和 source_rect 后 jank 约 `0.27-0.45%`、P95 `15ms`、P99 `16-17ms`，继续证明普通 UI 合成不是主要瓶颈。source_rect 链路截图 `.rd_runtime/screens/rd_debug_viewport_after_history_points_2111.png` SHA256 `cf449cd4a42bc2f003a5de31a94720b1a0885289b727362bd07f95d49b42f56d`，Android 收到 `800x466 source_rect=0.25,0.20,0.50,0.45`。
- 2026-07-06 00:15 真机补证：会话 `sess-1783267574091-1` 中三端可启动，标准 proof 为 `video_and_input_observed`、`first_frame_ms=31`、`media=legacy_jpeg`、`remote_input_applied=8/8`；APK SHA256 `d291a85bfc6cf0d6ac0383549ec8b75b6f2092775025d4b5f9bef4624a55115b`。debug 双指注入触发 manual span fallback：`remote_manual_pinch_begin`、多条 `remote_viewport_pinch_scale source=manual`、`remote_manual_pinch_end`，倍率约 `1.185 -> 2.437`；Mac 切到 `source_rect_ppm=294671,306641,410657,410305`，Android 物化为 `source_rect=0.29,0.31,0.41,0.41`。缩放后 3 秒滑动 Mac `input.mouse.move applied=true` 从 `3` 增到 `182`，新增约 `179` 条；截图和 gfxinfo 产物已保存到 `.rd_runtime/screens/rd_fullscreen_pinch_manual_20260706_000711.png`、`.rd_runtime/screens/rd_after_mouse_swipe_manual_zoom_20260706_000812.png` 及同名前缀 `gfxinfo` 文件。

## 4. 未达标事实

- Android 全屏渲染仍未达标：最新 `sess-1783199256194-1` 中，Mac sender 交互档 `800x517` 已到 `31.59/31.39/31.22fps`，但 Android 全屏渲染仍从 `11.88fps` 下滑到 `7.74/7.67/6.96fps`；恢复 `1120x724` 全屏基础档后仍约 `4.37-5.38fps`，持续 `render_fps_streak`。
- Android 全屏结构性 A/B 未通过：TextureView 未改善，MTK AVC 硬解不吐帧，`800x520` 全屏低档仍个位数 FPS。
- 常规清晰档仍未达标：当前 JPEG-only 轮次 `1547x1000` 只有约 `3.4-3.7fps`；旧 WebRTC/OpenH264 轮次也只有约 `14fps`，两条路径都低于 `>=24fps` 目标。
- 双指 pinch 仍未自动闭环：普通 `adb input swipe` 不能可靠产生真实多点缩放；debug/manual span fallback 已出现 `remote_viewport_pinch_scale source=manual`，但这只能证明 fallback 代码路径，不替代真实手指或 instrumentation 多点验收。
- “鼠标输入落地”已经稳定，但“画面里的鼠标移动肉眼流畅”不能宣称完成。
- 剪贴板和文件传输已通过 debug 自动验收，且本轮 result 回执已验证；Android “发剪贴板”真实按钮已验收，但 Android 系统文件选择器发送、Desktop 会话工具栏“剪贴板/文件”仍未验收。
- Android 14 scoped storage 已确认：debug path 直接读 `/sdcard/Download/...` 会 `EACCES`，用户文件发送必须依赖系统文件选择器授权。
- 本轮 Android “发文件”按钮已确认能打开 DocumentsUI picker，但自动化选文件未闭环：`最近`/`下载` 列表出现空列表或 `暂时无法加载内容`，需要人工手选或进一步修复 picker 可测性。
- WebRTC H.264 仍未修复：当前 Redmi 真机可见画面来自 JPEG fallback，不能写成 WebRTC 视频链路通过。
- JPEG fallback 只达到“可见且可操作”：最新清晰档约 `3.4-3.8fps @1547x1000`，全屏空闲约 `7.4-8.0fps @960x621`，全屏鼠标交互约 `12.0-13.4fps @640x414`，均低于 `>=24fps` 流畅验收。
- 2026-07-05 09:16 已复测 `560x364` 交互档、`1.08x` 局部高清触发和裁剪链路，但视觉 FPS 仍只有约 `13-14fps @560x362`，不能写成流畅完成；真实人工双指 pinch 手感仍未验收。
- 2026-07-05 09:37 最终保守版进一步确认：全屏完整显示和输入落地仍通过，但当前真机视觉 FPS 退化到约 `5-8fps` 区间；本轮不能记录为流畅性修复完成。
- 横屏物理坐标并发 `adb input swipe` 仍未触发 `remote_viewport_pinch_scale`，截图 `/tmp/remotedesk-pinch-attempt-legacy-only.png` 仍为 `1x`。
- 缩放后清晰度已在 JPEG fallback 数据链路部分闭环：source rect 裁剪和 Android 区域帧映射已验证；未根治的是真实双指手感、WebRTC/硬编码区域媒体链路和 `>=24fps` 流畅目标。
- 2026-07-05 10:06 小窗“全屏”按钮在 adb 坐标点击下没有触发进入全屏；截图显示按钮可见，布局中按钮位于触摸层之后且有 `bringToFront/elevation`。这条不能直接判定人工点击必失败，但必须作为待人工复测/可能回归记录，不能把本轮写成全屏入口完全通过。
- 2026-07-05 10:21 已确认小窗“全屏”按钮可通过真实坐标点击进入全屏，但第一次 adb 坐标未命中，说明自动化坐标依赖截图位置，不能作为稳定 UI 自动验收方式；后续仍建议用 UIAutomator bounds 或人工复测全屏入口。
- 2026-07-05 10:55 仍未闭环：ADB `input` 在当前 ROM 上只支持单点 `motionevent`，本轮没有真实多点 pinch 自动化证据；debug viewport 只能证明缩放结束后的区域高清消息和裁剪回传链路，不能替代人工双指手感验收。
- 2026-07-05 11:30 仍未达标：局部高清从 `1120x700`、`800x520` 收敛到 `720x468` 后帧率有所改善，但稳定样本仍只有约 `6.7fps @720x420 source_rect=0.25,0.20,0.50,0.45`；当前只能记录为“区域清晰度链路可用且参数更保守”，不能记录为“缩放后顺滑/清晰体验完成”。
- 2026-07-05 11:45 仍未达标：局部高清提高到 `864x562@16fps` 后，Android 稳定样本约 `5.10-6.76fps @864x503 source_rect=0.25,0.20,0.50,0.45`；清晰度上限提高，但视觉流畅性变差，不能写成缩放体验已完成。
- 2026-07-05 12:23 指标缺口已闭环：Android legacy JPEG 会写入通用 `first_frame_ms/rendered_frames/render_fps_avg`，并额外带 `media_frame_transport=legacy_jpeg` 与 `legacy_* / webrtc_*` 明细；relay combined 已验证该口径。
- 2026-07-05 13:09 仍未达标：基础档约 `9.8-10.5fps @800x517`，普通交互档约 `15-18fps @448x290`，局部高清约 `6.9fps @800x466 source_rect=0.25,0.20,0.50,0.45`；当前优化降低了过密输入信令和局部源区域过期风险，但不能把 JPEG fallback 写成最终流畅方案。
- 2026-07-05 13:35 仍未达标：自定义绘制器后全屏滑动期峰值可到约 `16.65fps`，但基础档长期仍约 `10fps`，局部高清仍约 `7fps`；这说明减少 Android 每帧 Drawable 抖动有帮助，但 JPEG/Base64/Bitmap 兜底链路仍不能承担最终流畅远控。
- 2026-07-05 13:46 仍未达标：Bitmap 复用池后基础 proof 仍约 `10fps`，全屏滑动交互档约 `14.97/17.91fps @448x290`，局部高清约 `6.86/6.90fps @800x466 source_rect=0.25,0.20,0.50,0.45`；该优化降低分配/GC 风险，但没有改变 JPEG/Base64/Bitmap 链路吞吐上限。
- 2026-07-05 14:32 仍未达标：局部高清从 `1280x746` 降到 `1024x668` 后避免了 `3fps` 极端低谷，但整条可见链路仍是 `legacy_jpeg`，relay combined `render_fps_avg≈6.10fps`；debug viewport 只能证明缩放结束后的区域高清消息和裁剪回传链路，不能替代真实双指 pinch 手感验收。
- 2026-07-05 14:54 仍未达标：整屏基础档稳定约 `5.9-6.4fps @800x517`，移动交互峰值约 `17.88fps @448x290`，局部高清 `960x559 source_rect=0.25,0.20,0.50,0.45` 约 `5.54fps` 后降到 `2.92fps`；说明继续微调 JPEG 质量/尺寸不能关闭全屏缩放和鼠标视觉流畅目标。
- 2026-07-05 15:33 仍未达标：回收到 `960x624@16fps` 后，基础档仍约 `10fps @800x517`，全屏移动交互档约 `16-17fps @448x290`，局部高清约 `6.76/5.25fps @960x559 source_rect=0.25,0.20,0.50,0.45`；这比 `1088x634` 的 4fps 低谷略稳，但仍不能满足“缩放后顺滑且清晰”的目标。
- 2026-07-05 15:50 仍未达标：临时硬件合成层和高质量 Bitmap 缩放改善的是本地 transform/采样路径，不改变 JPEG/Base64/Bitmap 的源帧吞吐；本轮基础 proof `render_fps_avg=9.75`，交互峰值 `16.92fps @448x290`，局部高清 `5.59/5.22fps @960x559 source_rect=0.25,0.20,0.50,0.45`，仍不能满足“全屏缩放顺滑、缩放后清晰、鼠标视觉跟手”目标。
- 2026-07-05 16:06 仍未达标：pinch 期间整屏 legacy 帧节流和 Mac 鼠标队列节流修正没有引入输入或显示回归，但基础档仍约 `9.66fps`，全屏滑动交互峰值约 `18.24fps @448x290`，局部高清仍约 `5.28/5.39fps @960x559 source_rect=0.25,0.20,0.50,0.45`；这只能作为小幅稳态优化，不能关闭全屏缩放/清晰度/鼠标视觉流畅目标。
- 2026-07-05 16:35 仍未达标：局部高清回收到 `960x624@12fps` 且 JPEG 质量降到 `84/80` 后，基础 proof 约 `10.07fps`，全屏滑动输入约 `59Hz` 落地，局部高清首样本 `8.06fps` 但后续仍约 `5.18fps @960x559 source_rect=0.25,0.20,0.50,0.45`；这确认输入链路足够密，剩余核心是视频可见链路吞吐。
- 2026-07-05 16:56 仍未达标：本轮将 pinch 期间整屏 legacy JPEG 展示节流到 `320ms`、全屏停手本地承载面提前到 `220ms`，并在 pinch end 后直接切 `960x624@12fps source_rect_ppm=250000,200000,500000,450000` 局部高清。真机会话 `sess-1783241479512-1` 标准 proof 通过，`first_frame_ms=78`、`media_frame_transport=legacy_jpeg`、`remote_input_applied=8/8`；全屏截图 `/tmp/remotedesk-fullscreen-after-tap-165242.png` 显示桌面完整；debug viewport 后 Android 收到 `960x559 source_rect=0.25,0.20,0.50,0.45`，2 秒内截图 `/tmp/remotedesk-debug-viewport-during-current-165617.png` SHA256 `4b9f1a5e1e102e4457776a81cfbadac565f707ae56916535447bf58efb54488c`。但 proof `render_fps_avg=7.00`，局部高清只有 `4.66/5.00fps`，并发 adb swipe 仍未触发真实多点 pinch；本轮只能记录为“清晰源链路更快恢复、手势中绘制成本降低”，不能关闭全屏缩放/鼠标视觉流畅需求。
- 2026-07-05 19:45 仍未达标：完整帧免 BGRA 拷贝后基础档可短时到 `9-10fps @800x517`，交互档短时约 `14.98fps @448x290`，但全屏基础档仍约 `7fps @640x414`，局部高清仍约 `4-5fps @960x559 source_rect=0.25,0.20,0.50,0.45`；Mac worker 采样显示捕获/编码耗时长期超过目标间隔，普通 Android UI 合成又已由 `gfxinfo` 证明不 jank，因此下一步必须转向 WebRTC/H.264/硬编码/硬解能力分层或更高效降级媒体通道。
- 2026-07-05 21:11 仍未达标：历史触摸点补偿后，鼠标执行链路更连续，Mac `input.mouse.move applied=true` 新增 `157` 条且间隔多在 `16-20ms`；但用户看到的全屏远控画面仍约 `11.7-12.7fps @640x414`，局部高清 `800x466 source_rect=0.25,0.20,0.50,0.45` 首样本约 `10.56fps`、随后约 `6.99/7.07fps`。这说明输入链路不是剩余主因，视觉流畅和缩放后清晰且流畅仍必须靠最终媒体链路或更高效降级通道解决。
- 2026-07-06 00:15 仍未达标：manual span fallback 已证明 debug 双指缩放代码路径能闭环，缩放后 mouse move 也能接近 `60Hz` 落到 Mac；但局部 detail 只有约 `7.95fps @896x578`，局部 still 只有约 `4-5fps @1142x737 source_rect=0.29,0.31,0.41,0.41`，当前可见链路仍是 `legacy_jpeg`。debug 注入不是人工手指手感验收，普通 adb 多点能力仍不可作为真实 pinch 证据。

## 5. 验收标准

- 全屏完整显示：截图中必须完整显示 macOS 桌面主要区域，不能裁切。
- 输入落地：全屏长滑动窗口内 `input.mouse.move` Android 发送数与 Mac `applied=true` 数量基本一致。
- 移动流畅：Android 全屏交互档 `render_fps_recent >= 24fps`，且 recent gap 不应长期超过 `100ms`。
- 清晰恢复：停手后清晰档至少恢复到 `1547x1000`，后续目标为 `>=24fps`。
- 区域高清：当用户放大到 `>=1.08x` 后，JPEG fallback 必须按可视区域发送 `source_rect_*` 局部帧，并证明 Android 不再二次放大该局部帧；最终版本还需要 WebRTC/硬编码链路同样支持区域帧。
- 双指缩放：必须通过人工真机或 instrumentation 证明倍率变化、焦点跟随、停手后清晰重绘。
- 剪贴板：双向发送后，目标端系统剪贴板文本必须和源文本完全一致。
- 文件传输：双向小文件传输后，目标端保存路径可见，SHA256 必须和源文件一致。

## 6. 下一步优先级

- P0：建立 Android 设备能力分层和编码/解码策略：Redmi Note 8 Pro 当前依赖 JPEG fallback 可见但不流畅，H.264/WebRTC 未修复；下一步要评估 VP8/VP9/H.265、硬解兼容性探测、macOS 硬件编码，或按设备降级全屏体验。
- P1：把已验证的 JPEG fallback 区域高清迁移到最终媒体链路：WebRTC/硬编码路径也需要支持 `source_rect` 或等效区域编码，并保留 Android 触摸坐标反算。
- P2：把当前 JPEG fallback 产品化为受控降级路径：明确设备命中条件、码率/帧率上限、恢复 WebRTC 的探测条件和 UI 提示；同时继续优化 Android 全屏渲染，但不要重复已失败的 Activity overlay、TextureView、全屏 800 低档、MTK AVC 硬解路径。
- P3：优化 macOS 捕获/编码路径，优先评估硬件编码、更低成本 BGRA -> YUV 转换或直接从 ScreenCaptureKit 输出可编码像素格式；最新 worker 采样已证明 JPEG fallback 捕获/编码耗时本身经常超过目标帧间隔。
- P4：补 instrumentation 多点触控测试，关闭双指 pinch 自动验收缺口。
- P5：人工真机验收双指缩放、双指平移、缩放后单指移动鼠标和键盘输入体验。
- P6：补 Android 文件选择器发送、Desktop 工具栏剪贴板/文件发送的手动 UI 验收，并评估 WebRTC DataChannel 或 HTTP 分片通道替代当前 WebSocket 信令分块方案。
