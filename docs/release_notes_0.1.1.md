# RemoteDesk v0.1.1 发布说明

发布日期：`2026-07-02`

## 发布范围

- 版本号同步到 `0.1.1`：根目录 `VERSION`、Desktop package/Tauri/Cargo、Android `versionName`、运行时 `client_version`、OpenAPI 示例、协议 fixture 与数据库 seed。
- Android `versionCode` 从 `1` 提升到 `2`，便于设备侧区分新版 APK。
- GitHub Release 产物以当前仓库配置为准：Desktop 默认未启用 Tauri installer，因此发布 macOS release binary 压缩包；Android 同时产出可安装 debug APK 与 unsigned release APK；relay/TURN 产出当前 macOS arm64 二进制包。

## 本轮发布前验证

- Android 模拟器 -> Mac：`short_reconnect_20260701_004122` 通过，覆盖首帧、短断恢复、点击/拖拽/键盘/滚轮输入与短窗口质量。
- Android 真机 -> Windows：`docs/windows_android_real_device_test_report_20260702.md` 记录全流程通过，`android_to_windows.complete=true`，输入 `remote_input_applied=11/11`。
- `bash -n scripts/short_reconnect_check.sh`
- `bash -n scripts/soak_6_5.sh`
- `bash -n scripts/triad_ctl.sh`
- `python3 -m py_compile scripts/soak_report.py`
- `git diff --check`
- `make proto-check`
- `cd apps/server && go test ./...`
- `cd apps/android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug assembleRelease --console=plain`
- `cd apps/desktop && npm run build`
- `cd apps/desktop/src-tauri && cargo test`
- `cd apps/desktop && npm exec tauri build`
- `cd apps/server && go build -o ../../.rd_runtime/release/v0.1.1/server-darwin-arm64/remote-desk-api-server ./cmd/api-server`
- `cd apps/server && go build -o ../../.rd_runtime/release/v0.1.1/server-darwin-arm64/remote-desk-turn-server ./cmd/turn-server`

## 发布产物

产物目录：`.rd_runtime/release/v0.1.1/assets`

| 文件 | SHA256 |
| --- | --- |
| `RemoteDesk-Android-debug-v0.1.1.apk` | `f2c4219fa30ba899076f3488122eb2c78cf92015f66419fad1386800ae81494e` |
| `RemoteDesk-Android-release-unsigned-v0.1.1.apk` | `a011f4c1702b31c5e9f0a9fbfaac68214d2fe93ee0c51e14804f3fdf326bd4ce` |
| `RemoteDesk-Desktop-macOS-arm64-v0.1.1.tar.gz` | `79aac272cac95cc59dae263371409a6b775d4e2eabf78703f37f1dfbec063969` |
| `RemoteDesk-Server-macOS-arm64-v0.1.1.tar.gz` | `f59f22af90b3377f63df82f46b504ba1939e7cd9421c101b77fe82b9d4a09e64` |

APK metadata 已确认：

- debug：`applicationId=com.remotedesk.app`，`versionCode=2`，`versionName=0.1.1`
- release：`applicationId=com.remotedesk.app`，`versionCode=2`，`versionName=0.1.1`

二进制架构已确认：

- Desktop：`Mach-O 64-bit executable arm64`
- Relay API server：`Mach-O 64-bit executable arm64`
- TURN server：`Mach-O 64-bit executable arm64`

## 已知边界

- macOS/Windows 正式安装包尚未启用：`apps/desktop/src-tauri/tauri.conf.json` 中 `bundle.active=false`。
- Android release APK 目前未配置正式签名，只能作为 unsigned 产物保存；可直接安装测试的是 debug APK。
- Android 真机 -> Mac 长时间 soak 仍未达最终流畅性门槛：历史最新失败点包括 `render_fps_avg=23.52 < 24.0` 和 `visible_frame_gap_ms_max=3692`。
- Android 真机 -> Windows 本轮功能链路通过，但仍建议继续补 3-5 分钟稳定性样本，重点观察 `send_fps_low`。
