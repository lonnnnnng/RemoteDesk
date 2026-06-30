# RemoteDesk UI Prototype

本目录是 RemoteDesk 新 UI 的静态原型，不依赖现有桌面端或安卓端运行时。

## 打开方式

- 主原型：`index.html`
- 远程会话窗口：`remote-window.html`
- 设计概念图：`assets/concept-remotedesk-ui.png`

## 交互范围

- 桌面端主应用：只保留“设备”和“设置”两个页面。
- 移动端主应用：只保留“设备”和“设置”两个页面。
- 设备列表中的“远程”是新窗口链接，会打开 `remote-window.html`。
- 调试信息默认不在主页面显示；在设置页开启“调试模式”后才出现调试入口。
