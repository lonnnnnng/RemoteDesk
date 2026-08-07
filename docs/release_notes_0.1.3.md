# RemoteDesk v0.1.3 发布说明

发布日期：`2026-08-07`

## 发布范围

- 服务端新增统一 JSON 配置文件，relay API 与 TURN server 共用公网地址、监听端口、TURN realm、用户名、密码及 ICE 策略。
- 支持 `-config <path>` 与 `RD_CONFIG_FILE`，保留现有 `RD_*` 环境变量作为兼容覆盖。
- Desktop 与 Android 设置页新增中继地址、TURN 地址、TURN 用户名、TURN 密码和可选 STUN 地址；手动 ICE 优先，留空时使用服务端下发配置。
- 移除客户端 localhost、Android 模拟器网关和 Google STUN 自动兜底，避免发布版本静默连接错误地址。
- 新增 Linux API/TURN systemd 模板、JSON 配置示例及完整部署/客户端使用说明。
- 统一 Android、Desktop、Tauri、Cargo、协议 fixture、OpenAPI 示例、seed 和运行时 `client_version` 到 `0.1.3`。
- Android `versionCode` 从 `3` 提升到 `4`。

## 发布资产

- `RemoteDesk-Android-v0.1.3.apk`：Android 正式签名 release APK。
- `RemoteDesk-Desktop-macOS-arm64-v0.1.3.tar.gz`：macOS arm64 Desktop 二进制。
- `RemoteDesk-Server-macOS-arm64-v0.1.3.tar.gz`：macOS arm64 relay/TURN 服务。
- `RemoteDesk-Server-Linux-amd64-v0.1.3.tar.gz`：Linux amd64 relay/TURN 服务。
- `RemoteDesk-Server-Linux-arm64-v0.1.3.tar.gz`：Linux arm64 relay/TURN 服务。
- `SHA256SUMS.txt`：全部发布资产的 SHA-256 校验值。

Linux 服务包包含 `remote-desk.example.json`、API/TURN systemd 模板和部署说明。

## 验证与边界

- 发布前运行协议检查、Go 测试、Desktop Vite 构建、Tauri Rust 测试及 Android release 构建。
- Android APK 使用与 v0.1.2 相同的 RemoteDesk 专用证书签名，可覆盖升级；密钥和密码不进入仓库。
- API 与 TURN 已使用同一 JSON 配置完成进程级启动、健康检查及 UDP/TCP 监听验证。
- Desktop 和 Android 客户端配置界面已分别通过 Playwright 和 Android 真机检查。
- 客户端 TURN 密码当前保存在本机 localStorage/SharedPreferences，尚未接入系统 Keychain/Keystore；请使用 RemoteDesk 专用 TURN 凭据。
- macOS/Windows 正式安装包尚未启用，Tauri 配置仍为 `bundle.active=false`。
