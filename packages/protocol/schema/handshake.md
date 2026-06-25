# Handshake

## Bootstrap stage

在 bootstrap 阶段，Desktop / Android / Server 使用统一 JSON envelope：

```json
{
  "v": "1.0",
  "msg_id": "uuid",
  "type": "string",
  "ts": 0,
  "session_id": "optional-string",
  "trace_id": "optional-string",
  "from": {
    "device_id": "string",
    "role": "controller|agent|server"
  },
  "payload": {}
}
```

最小握手顺序：
1. client 建立 WebSocket 连接
2. client 发送 `device.register.req`
3. server 返回 `device.register.rsp`
4. client 定时发送 `presence.heartbeat.req`
5. server 返回 `presence.heartbeat.rsp`
6. controller 可发送 `session.request.req`
7. server 返回 stub `session.request.result.push` 与 `session.start.push`
