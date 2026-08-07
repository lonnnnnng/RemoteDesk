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

`session.start.push.webrtc.ice_servers` 由 relay 的共享服务端配置生成，可包含 STUN URL，以及 TURN URL、`username` 和 `credential`。`ice_policy` 同步描述 ICE 模式、TURN 传输方式和链路降级阈值；客户端只有在用户未手动配置 ICE 时才使用这些下发值。
