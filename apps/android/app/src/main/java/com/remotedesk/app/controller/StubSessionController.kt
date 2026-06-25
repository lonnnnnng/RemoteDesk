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
      "client_version" to "0.1.0",
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
