# Windows + Android 真机全流程验证报告（2026-07-02）

## 结论

- Android 真机 -> Windows 桌面端远控全流程通过。
- `/e2e-proof` 中 `android_to_windows.complete=true`，`proof_status=video_and_input_observed`。
- Android 真机端成功显示 Windows 远程画面，截图确认不是黑屏。
- Windows 端实际执行远程输入，`remote_input_applied=11/11`，覆盖 `click / drag / keyboard / wheel`。
- 本轮只验证 Android -> Windows 单路线，完整 `/e2e-proof complete=false` 是预期结果，因为 macOS 和 Windows->Windows 路线未跑。

## 环境

| 项目 | 值 |
| --- | --- |
| 仓库 HEAD | `c9e8402 记录Windows和安卓验证结果` |
| Windows agent | `desktop-windows-codex` |
| Android 真机序列号 | `wsvwypiz7xwslvl7` |
| Android 机型 | `Redmi Note 8 Pro` |
| Android 版本 | `14` |
| Android controller | `android-43806e26dacc341f` |
| Relay | `http://127.0.0.1:18081` / `ws://127.0.0.1:18081/ws` |
| TURN | `0.0.0.0:3478` |
| 证据目录 | `.tmp/run-20260702-003141` |

## 执行结果

| 验证项 | 结果 | 证据 |
| --- | --- | --- |
| 拉取最新代码 | 通过 | `git pull --ff-only origin main` 返回 `Already up to date` |
| ADB 真机识别 | 通过 | `wsvwypiz7xwslvl7 device product:lineage_begonia model:Redmi_Note_8_Pro` |
| 桌面前端构建 | 通过 | `npm.cmd run build` 成功 |
| Windows 桌面自检 | 通过 | `desktop-self-test ok=true`，GDI 采集、SendInput、native sender probe 均通过 |
| Android Debug 构建 | 通过 | `:app:assembleDebug` 返回成功，APK 可安装 |
| 双端在线 | 通过 | `/devices` 同时存在 `desktop-windows-codex` 和 `android-43806e26dacc341f` |
| 会话建立 | 通过 | `session_id=sess-1782923914064-1` |
| 远程画面 | 通过 | `video_observed=true`，截图 `android-session-final-binary.png` 可见 Windows 桌面 |
| 远程输入 | 通过 | `remote_input_applied=11/11`，executor=`windows.send_input` |
| 输入覆盖 | 通过 | `click, drag, keyboard, wheel` |

## 关键 Proof

- `route_key`: `android_to_windows`
- `proof_status`: `video_and_input_observed`
- `first_frame_ms`: `13782`
- `rendered_frames`: `1`（proof 聚合值）
- `remote_input_applied`: `11/11`
- `remote_input_status`: `applied`
- `remote_input_executor`: `windows.send_input`
- `candidate_path`: `relay/relay/udp`
- `quality_hint`: `send_fps_low`

## 连续画面观察

虽然 `/e2e-proof` 聚合里 `rendered_frames=1`，但 Android logcat 中 WebRTC 渲染器持续输出 4 秒窗口统计：

- `Frames received`: 约 `32-36`
- `Rendered`: 约 `32-36`
- `Dropped`: `0`
- `Render fps`: 约 `8.0-9.0`

这说明真机端持续接收并渲染远程画面，当前主要问题不是黑屏，而是帧率偏低。

## 注意事项

- 真机上原有 `com.remotedesk.app` 与当前 Debug 包签名不一致，覆盖安装失败。
- 已先尝试 `pm uninstall -k` 保留数据卸载，但仍被旧签名数据阻塞。
- 最终执行完整 `adb uninstall com.remotedesk.app` 后安装成功，因此真机旧应用数据已被清除。

## 证据文件

- `.tmp/run-20260702-003141/proof-final.json`
- `.tmp/run-20260702-003141/devices-final.json`
- `.tmp/run-20260702-003141/android-session-final-binary.png`
- `.tmp/run-20260702-003141/android-logcat-final.txt`
- `.tmp/run-20260702-003141/android-after-launch-ui.xml`

## 后续建议

- 功能链路已通过，下一步建议针对 `send_fps_low` 做 3-5 分钟稳定性样本，重点看 sender FPS、Android render FPS、relay/UDP 路径和编码耗时。
