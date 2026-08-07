# Linux 中转服务部署

RemoteDesk Linux 服务包同时包含 relay API/WebSocket 服务和 TURN 服务。两者必须同时运行。

## 选择架构

先在 Linux 服务器执行：

```bash
uname -m
```

- `x86_64`：下载 `RemoteDesk-Server-Linux-amd64-<version>.tar.gz`
- `aarch64` 或 `arm64`：下载 `RemoteDesk-Server-Linux-arm64-<version>.tar.gz`

## 解压与配置

以 `amd64` 为例：

```bash
tar -xzf RemoteDesk-Server-Linux-amd64-<version>.tar.gz
cd server-linux-amd64
cp remote-desk.example.json remote-desk.json
```

编辑 `remote-desk.json`：

- `public_ws_url` 必须填写客户端可访问的公网 WebSocket 地址，例如 `ws://203.0.113.10:18081/ws`。
- `turn_public_ip` 必须填写 Linux 服务器的公网 IPv4，供 TURN 分配 relay 地址。
- `turn_public_host` 可填写客户端可访问的 TURN 域名；留空时自动继承 `turn_public_ip`。
- `turn_bind_addr` 与 `turn_port` 分别是 TURN 的监听地址和对客户端下发的端口；无端口映射时两者应一致。
- `turn_username` 和 `turn_password` 由 relay 下发给客户端并由 TURN server 校验，必须修改示例密码。
- 公网长期部署建议在 relay 前增加 HTTPS 反向代理，并将 `public_ws_url` 改为 `wss://<域名>/ws`。
- 配置文件包含 TURN 密码，建议设置为仅服务账号可读，例如 `chmod 600 remote-desk.json`。

使用同一配置文件分别启动两个进程：

```bash
./remote-desk-turn-server -config ./remote-desk.json
```

另开一个终端启动 relay：

```bash
./remote-desk-api-server -config ./remote-desk.json
```

也可以设置 `RD_CONFIG_FILE=/绝对路径/remote-desk.json` 省略 `-config`。`RD_*` 环境变量仍可覆盖 JSON，仅用于兼容旧部署。

relay 健康检查：

```bash
curl http://127.0.0.1:18081/healthz
```

## 防火墙端口

- TCP `18081`：relay HTTP/WebSocket。
- UDP 和 TCP `3478`：TURN 监听端口。
- Linux UDP 临时端口范围：TURN 为媒体分配的 relay 端口。可用 `cat /proc/sys/net/ipv4/ip_local_port_range` 查看实际范围，并在云安全组和主机防火墙中放行对应 UDP 范围。

Android/Desktop 客户端的中继地址填写 `ws://<公网 IP>:18081/ws`，使用反向代理和 TLS 时填写 `wss://<域名>/ws`。

客户端设置页中的 TURN 地址、用户名、密码和可选 STUN 地址均可手动填写。手动 ICE 配置完整时优先使用；全部留空时，客户端使用本服务端配置通过 `session.start.push` 下发的参数。
