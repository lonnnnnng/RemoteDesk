package com.remotedesk.app.model

data class From(
  val device_id: String,
  val role: String,
)

data class Envelope(
  val v: String = "1.0",
  val msg_id: String,
  val type: String,
  val ts: Long,
  val session_id: String? = null,
  val trace_id: String? = null,
  val from: From,
  val payload: Map<String, Any?>,
)
