# RemoteDesk v0.1.2 发布说明

发布日期：`2026-08-07`

## 发布范围

- 统一 Android、Desktop、Tauri、Cargo、协议 fixture、OpenAPI 示例、seed 和运行时 `client_version` 到 `0.1.2`。
- Android `versionCode` 从 `2` 提升到 `3`。
- 纳入全屏局部缩放高清媒体链路、双指缩放恢复和远程输入交互优化。
- 发布一个 Android 正式签名 release APK、macOS arm64 Desktop 二进制，以及 macOS arm64、Linux amd64、Linux arm64 relay/TURN 二进制。
- Linux 服务包包含环境变量示例与启动说明，下载后可直接配置公网 WebSocket/TURN 地址运行。

## 已知边界

- 按本次发布指令未重新运行测试套件或端到端回归；发布资产仅执行必要的 release 构建、签名和静态元数据核对。
- Android release APK 使用本机 RemoteDesk 专用签名密钥签名，密钥与密码不进入仓库。
- macOS/Windows 正式安装包尚未启用，Tauri 配置仍为 `bundle.active=false`。
