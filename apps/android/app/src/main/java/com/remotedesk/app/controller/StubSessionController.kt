package com.remotedesk.app.controller

import org.json.JSONObject

class StubSessionController(
  private val deviceId: String,
  private val role: String = "controller",
) {
  data class SessionRequestMessage(
    val requestId: String,
    val message: String,
  )

  private var messageSeq: Long = 0

  fun registerMessage(): String = buildMessage(
    type = "device.register.req",
    payload = mapOf(
      "device_id" to deviceId,
      "user_id" to "android-user-001",
      "platform" to "android",
      "client_version" to "0.1.1",
      "device_name" to "Android Controller",
      "capabilities" to mapOf(
        "can_control" to true,
        "can_be_controlled" to false,
      ),
    ),
  )

  fun heartbeatMessage(token: String, sessionId: String?): String = buildMessage(
    type = "presence.heartbeat.req",
    sessionId = sessionId,
    payload = mapOf(
      "token" to token,
      "status" to if (sessionId.isNullOrBlank()) "online" else "busy",
      "active_session_id" to sessionId,
    ),
  )

  fun requestSessionMessage(targetDeviceId: String, controllerProfile: String = "standard"): SessionRequestMessage {
    val normalizedProfile = when (controllerProfile.trim().lowercase()) {
      "emulator" -> "emulator"
      "android_phone" -> "android_phone"
      else -> "standard"
    }
    val requestId = "req-${System.currentTimeMillis()}"
    return SessionRequestMessage(
      requestId = requestId,
      message = buildMessage(
        type = "session.request.req",
        payload = mapOf(
          "target_device_id" to targetDeviceId,
          "request_id" to requestId,
          "auth_mode" to "consent_required",
          "controller_profile" to normalizedProfile,
        ),
      ),
    )
  }

  fun webrtcOfferMessage(sessionId: String, sdp: String? = null): String = buildMessage(
    type = "webrtc.offer",
    sessionId = sessionId,
    payload = mapOf(
      "sdp_type" to "offer",
      "sdp" to (sdp ?: buildStubSdp("offer", sessionId)),
    ),
  )

  fun webrtcAnswerMessage(sessionId: String, sdp: String? = null): String = buildMessage(
    type = "webrtc.answer",
    sessionId = sessionId,
    payload = mapOf(
      "sdp_type" to "answer",
      "sdp" to (sdp ?: buildStubSdp("answer", sessionId)),
    ),
  )

  fun webrtcIceCandidateMessage(
    sessionId: String,
    candidate: String,
    sdpMid: String?,
    sdpMLineIndex: Int?,
  ): String = buildMessage(
    type = "webrtc.ice_candidate",
    sessionId = sessionId,
    payload = mapOf(
      "candidate" to candidate,
      "sdp_mid" to (sdpMid?.trim()?.takeIf { it.isNotEmpty() } ?: "0"),
      "sdp_mline_index" to (sdpMLineIndex?.takeIf { it >= 0 } ?: 0),
    ),
  )

  fun webrtcRestartIceMessage(sessionId: String, reason: String): String = buildMessage(
    type = "webrtc.restart_ice",
    sessionId = sessionId,
    payload = mapOf(
      "reason" to reason,
    ),
  )

  fun inputMessage(sessionId: String, x: Double, y: Double, inputCategory: String = ""): String = buildMessage(
    type = "input.mouse.move",
    sessionId = sessionId,
    payload = mapOf(
      "x" to x,
      "y" to y,
      "input_category" to inputCategory,
    ),
  )

  fun inputButtonMessage(
    sessionId: String,
    x: Double,
    y: Double,
    button: String,
    action: String,
    inputCategory: String = "click",
  ): String = buildMessage(
    type = "input.mouse.button",
    sessionId = sessionId,
    payload = mapOf(
      "button" to button,
      "action" to action,
      "x" to x,
      "y" to y,
      "input_category" to inputCategory,
    ),
  )

  fun inputKeyboardMessage(sessionId: String, keyCode: String, action: String, modifiers: List<String> = emptyList()): String = buildMessage(
    type = "input.keyboard.key",
    sessionId = sessionId,
    payload = mapOf(
      "key_code" to keyCode,
      "action" to action,
      "modifiers" to modifiers,
      "input_category" to "keyboard",
    ),
  )

  fun inputWheelMessage(sessionId: String, deltaX: Int, deltaY: Int): String = buildMessage(
    type = "input.wheel.scroll",
    sessionId = sessionId,
    payload = mapOf(
      "delta_x" to deltaX,
      "delta_y" to deltaY,
      "input_category" to "wheel",
    ),
  )

  fun clipboardTextMessage(sessionId: String, clipboardId: String, text: String): String = buildMessage(
    type = "clipboard.text",
    sessionId = sessionId,
    payload = mapOf(
      "clipboard_id" to clipboardId,
      "text" to text,
      "source_platform" to "android",
      "created_at" to System.currentTimeMillis(),
    ),
  )

  fun clipboardResultMessage(
    sessionId: String,
    clipboardId: String,
    applied: Boolean,
    chars: Int,
    errorDetail: String = "",
  ): String = buildMessage(
    type = "clipboard.result",
    sessionId = sessionId,
    payload = mapOf(
      "clipboard_id" to clipboardId,
      "applied" to applied,
      "chars" to chars,
      "error_detail" to errorDetail,
      "created_at" to System.currentTimeMillis(),
    ),
  )

  fun fileTransferStartMessage(
    sessionId: String,
    fileId: String,
    name: String,
    mime: String,
    size: Long,
    totalChunks: Int,
    sha256: String,
  ): String = buildMessage(
    type = "file.transfer.start",
    sessionId = sessionId,
    payload = mapOf(
      "file_id" to fileId,
      "name" to name,
      "mime" to mime,
      "size" to size,
      "total_chunks" to totalChunks,
      "sha256" to sha256,
    ),
  )

  fun fileTransferChunkMessage(
    sessionId: String,
    fileId: String,
    chunkIndex: Int,
    totalChunks: Int,
    dataBase64: String,
  ): String = buildMessage(
    type = "file.transfer.chunk",
    sessionId = sessionId,
    payload = mapOf(
      "file_id" to fileId,
      "chunk_index" to chunkIndex,
      "total_chunks" to totalChunks,
      "data_base64" to dataBase64,
    ),
  )

  fun fileTransferCompleteMessage(
    sessionId: String,
    fileId: String,
    name: String,
    mime: String,
    size: Long,
    totalChunks: Int,
    sha256: String,
  ): String = buildMessage(
    type = "file.transfer.complete",
    sessionId = sessionId,
    payload = mapOf(
      "file_id" to fileId,
      "name" to name,
      "mime" to mime,
      "size" to size,
      "total_chunks" to totalChunks,
      "sha256" to sha256,
    ),
  )

  fun fileTransferResultMessage(
    sessionId: String,
    fileId: String,
    applied: Boolean,
    name: String,
    bytes: Long,
    sha256: String,
    location: String,
    errorDetail: String = "",
  ): String = buildMessage(
    type = "file.transfer.result",
    sessionId = sessionId,
    payload = mapOf(
      "file_id" to fileId,
      "applied" to applied,
      "name" to name,
      "bytes" to bytes,
      "sha256" to sha256,
      "location" to location,
      "error_detail" to errorDetail,
      "created_at" to System.currentTimeMillis(),
    ),
  )

  fun viewportInteractionMessage(
    sessionId: String,
    phase: String,
    interaction: String,
    scale: Float,
    viewportX: Double? = null,
    viewportY: Double? = null,
    viewportWidth: Double? = null,
    viewportHeight: Double? = null,
    focusX: Double? = null,
    focusY: Double? = null,
  ): String {
    val payload = mutableMapOf<String, Any?>(
      "phase" to phase,
      "interaction" to interaction,
      "scale" to scale,
      "created_at" to System.currentTimeMillis(),
    )
    // 作者: long；局部放大想变清晰，桌面端必须知道手机当前正在看完整桌面的哪一块，这些归一化字段会作为后续区域高清采集和输入反算的共同依据。
    putFiniteNumber(payload, "viewport_x", viewportX)
    putFiniteNumber(payload, "viewport_y", viewportY)
    putFiniteNumber(payload, "viewport_width", viewportWidth)
    putFiniteNumber(payload, "viewport_height", viewportHeight)
    putFiniteNumber(payload, "focus_x", focusX)
    putFiniteNumber(payload, "focus_y", focusY)
    return buildMessage(
      type = "session.viewport.interaction",
      sessionId = sessionId,
      payload = payload,
    )
  }

  fun endSessionMessage(sessionId: String): String = buildMessage(
    type = "session.end.req",
    sessionId = sessionId,
    payload = mapOf(
      "session_id" to sessionId,
      "reason" to "user_end",
    ),
  )

  fun sessionMetricsReportMessage(
    sessionId: String,
    reason: String,
    metrics: Map<String, Any?> = emptyMap(),
  ): String {
    val payload = mutableMapOf<String, Any?>(
      "session_id" to sessionId,
      "report_version" to 1,
      "source_client" to "android",
      "source_platform" to "android",
      "source_role" to role,
      "reason" to reason,
    )
    payload.putAll(metrics)
    return buildMessage(
      type = "session.metrics.report",
      sessionId = sessionId,
      payload = payload,
    )
  }

  private fun buildMessage(type: String, payload: Map<String, Any?>, sessionId: String? = null): String {
    val now = System.currentTimeMillis()
    messageSeq += 1
    return JSONObject().apply {
      put("v", "1.0")
      put("msg_id", "$type-$now-$messageSeq")
      put("type", type)
      put("ts", now)
      if (sessionId != null) put("session_id", sessionId)
      put("trace_id", "trace-$now-$messageSeq")
      put("from", JSONObject().apply {
        put("device_id", deviceId)
        put("role", role)
      })
      put("payload", JSONObject(payload))
    }.toString()
  }

  private fun putFiniteNumber(payload: MutableMap<String, Any?>, key: String, value: Double?) {
    if (value == null || value.isNaN() || value.isInfinite()) {
      return
    }
    payload[key] = value
  }

  private fun buildStubSdp(type: String, sessionId: String): String {
    val now = System.currentTimeMillis()
    return listOf(
      "v=0",
      "o=- $now $now IN IP4 127.0.0.1",
      "s=remote_desk_webrtc",
      "t=0 0",
      "a=group:BUNDLE 0",
      "a=msid-semantic: WMS $sessionId",
      "a=setup:actpass",
      if (type == "offer") "a=recvonly" else "a=sendonly",
    ).joinToString("\r\n")
  }
}
