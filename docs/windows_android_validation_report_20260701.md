# Windows + Android 验证报告（2026-07-01）

## 结论

- 最新代码已从 `origin/main` 拉取到本地，当前 HEAD 为 `897be27 记录安卓模拟器到Mac回归验证`。
- Windows 桌面端编译与本机能力自检通过。
- Android Debug 包编译通过。
- Android 真机未被 ADB 识别，本轮无法在真机上执行安装/启动/远控验证。
- 按兜底方案启动 `Pixel_9` 模拟器后，模拟器 ADB 曾进入 `device`，但 package/activity 服务多次卡死或返回异常，无法稳定启动 Android 应用，因此 Android 运行态与 Android -> Windows 远控 proof 未完成。

## 已验证项

| 验证项 | 结果 | 证据 |
| --- | --- | --- |
| `git pull --ff-only origin main` | 通过 | `e95ff47..897be27` 快进，更新 `docs/remote_control_progress_checklist.md` |
| 桌面前端构建 | 通过 | `npm.cmd run build`，Vite 构建成功 |
| 桌面 Rust/Tauri 单测 | 通过 | `cargo test`，26 个测试全部通过 |
| Windows 桌面自检 | 通过 | `cargo run --bin desktop-self-test`，`ok=true` |
| Windows 采集能力 | 通过 | `capture_backend=windows.gdi`，采集帧 `960x443`，JPEG `61986` bytes |
| Windows 输入能力 | 通过 | `host_input_backend=windows.send_input`，pointer/keyboard/wheel 能力可用 |
| Android Debug 构建 | 通过 | `gradle -p . :app:assembleDebug --console=plain --no-daemon`，`BUILD SUCCESSFUL` |
| Relay/TURN 启动 | 通过 | Relay `:18081`、TURN `0.0.0.0:3478` 监听成功 |
| `/e2e-proof` reset | 通过 | `E2E proof reset confirmed` |

## 阻塞项

1. 真机 ADB 不可见
   - `adb devices -l` 未列出真实 Android 设备。
   - Windows 设备枚举只看到普通 `USB Composite Device`，未看到 ADB 接口。

2. 模拟器 package/activity 服务不稳定
   - `Pixel_9` 可启动并进入 `emulator-5554 device`。
   - Android 包安装曾成功，但启动 Activity 失败：
     - `Error type 3`
     - `Activity class {com.remotedesk.app/com.remotedesk.app.ui.MainActivity} does not exist`
   - `dumpsys package com.remotedesk.app` 又能看到 `com.remotedesk.app/.ui.MainActivity`，说明 APK manifest 本身存在 Activity，异常更偏向模拟器 package/query 状态。
   - 后续 `pm list packages`、`pm path`、`service list` 等命令多次超时或返回 `cmd: Can't find service: package` / `cmd: Can't find service: activity`。

3. Android -> Windows 远控 proof 未完成
   - 因 Android 端无法稳定启动，`/e2e-proof` 仍为 `android_to_windows not_observed`。

## 本轮未通过/未完成

- Android 真机安装与启动：未完成，原因是真机未被 ADB 识别。
- Android 模拟器安装后启动：未完成，原因是模拟器 package/activity 服务异常。
- Android -> Windows 远程画面和输入 proof：未完成，原因是 Android 控制端未能稳定启动。

## 建议下一步

- 真机侧优先确认 USB 调试授权、数据线模式、设备管理器中的 ADB Interface 驱动。
- 如果继续用模拟器，建议冷启动 AVD 或新建一个干净 AVD 后重跑，避免当前 `Pixel_9` package manager 状态污染。
- 桌面端无需优先排查，当前 Windows 本机采集、输入、native sender probe 均已通过。
