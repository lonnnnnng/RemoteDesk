# Linux 中转服务部署

RemoteDesk Linux 服务包同时包含 relay API/WebSocket 服务和 TURN 服务。两者必须同时运行。

## 选择架构

先在 Linux 服务器执行：

```bash
uname -m
```

- `x86_64`：下载 `RemoteDesk-Server-Linux-amd64-v0.1.2.tar.gz`
- `aarch64` 或 `arm64`：下载 `RemoteDesk-Server-Linux-arm64-v0.1.2.tar.gz`

## 解压与配置

以 `amd64` 为例：

```bash
tar -xzf RemoteDesk-Server-Linux-amd64-v0.1.2.tar.gz
cd server-linux-amd64
cp remote-desk.env.example remote-desk.env
```

编辑 `remote-desk.env`：

- `RD_WS_PUBLIC_URL` 必须填写客户端可访问的公网 WebSocket 地址，例如 `ws://203.0.113.10:18081/ws`。
- `RD_TURN_PUBLIC_IP` 必须填写 Linux 服务器的公网 IPv4。
- v0.1.2 的 `RD_TURN_USERNAME` 和 `RD_TURN_PASSWORD` 必须保持 `rd` / `rdpass`，否则 relay 下发的凭据与 TURN 服务不一致。
- 公网长期部署建议在 relay 前增加 HTTPS 反向代理，并将 `RD_WS_PUBLIC_URL` 改为 `wss://<域名>/ws`。

加载配置并分别启动两个进程：

```bash
set -a
. ./remote-desk.env
set +a

./remote-desk-turn-server
```

另开一个终端，在同一目录重新加载环境变量后启动 relay：

```bash
set -a
. ./remote-desk.env
set +a

./remote-desk-api-server
```

relay 健康检查：

```bash
curl http://127.0.0.1:18081/healthz
```

## 防火墙端口

- TCP `18081`：relay HTTP/WebSocket。
- UDP 和 TCP `3478`：TURN 监听端口。
- Linux UDP 临时端口范围：TURN 为媒体分配的 relay 端口。可用 `cat /proc/sys/net/ipv4/ip_local_port_range` 查看实际范围，并在云安全组和主机防火墙中放行对应 UDP 范围。

Android/Desktop 客户端的中继地址填写 `ws://<公网 IP>:18081/ws`，使用反向代理和 TLS 时填写 `wss://<域名>/ws`。
