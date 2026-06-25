package com.remotedesk.app.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StubSocketClient(
  private val onOpen: () -> Unit,
  private val onMessage: (String) -> Unit,
  private val onClosed: (String) -> Unit,
  private val onFailure: (String) -> Unit,
) {
  companion object {
    private const val TAG = "RemoteDeskWs"
    private val LOG_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
  }

  private val client = OkHttpClient()
  private var socket: WebSocket? = null

  fun connect(url: String) {
    logInfo("connect url=$url")
    socket?.close(1000, "reconnect")
    val request = Request.Builder().url(url).build()
    val nextSocket = client.newWebSocket(request, object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        if (this@StubSocketClient.socket !== webSocket) {
          return
        }
        logInfo("onOpen")
        onOpen()
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        if (this@StubSocketClient.socket !== webSocket) {
          return
        }
        logInfo("recv ${summarizeEnvelope(text)}")
        onMessage(text)
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (this@StubSocketClient.socket !== webSocket) {
          return
        }
        logInfo("onClosed code=$code reason=$reason")
        this@StubSocketClient.socket = null
        onClosed("code=$code reason=$reason")
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (this@StubSocketClient.socket !== webSocket) {
          return
        }
        this@StubSocketClient.socket = null
        val responseSummary = response?.let { " (http ${it.code} ${it.message})" }.orEmpty()
        logError("onFailure: ${t.message ?: t::class.java.simpleName}$responseSummary", t)
        onFailure("${t.message ?: t::class.java.simpleName}$responseSummary")
      }
    })
    socket = nextSocket
  }

  fun send(text: String): Boolean {
    val result = socket?.send(text) ?: false
    if (result) {
      logInfo("send ${summarizeEnvelope(text)}")
    } else {
      logWarn("send failed (socket not ready) ${summarizeEnvelope(text)}")
    }
    return result
  }

  fun close() {
    logInfo("close requested")
    socket?.close(1000, "user close")
    socket = null
  }

  private fun summarizeEnvelope(text: String): String {
    return try {
      val json = JSONObject(text)
      val type = json.optString("type").ifBlank { "unknown" }
      val msgId = json.optString("msg_id").ifBlank { "-" }
      val sessionId = json.optString("session_id").ifBlank { "-" }
      val traceId = json.optString("trace_id").ifBlank { "-" }
      val payload = json.optJSONObject("payload")
      val payloadKeys = payload?.keys()?.asSequence()?.toList()?.joinToString(",") ?: "-"
      "type=$type msg_id=$msgId session=$sessionId trace=$traceId payload_keys=$payloadKeys bytes=${text.length}"
    } catch (_: Exception) {
      "type=unknown bytes=${text.length}"
    }
  }

  private fun nowLogTime(): String = synchronized(LOG_TIME_FORMAT) { LOG_TIME_FORMAT.format(Date()) }

  private fun logInfo(message: String) {
    Log.i(TAG, "ts=${nowLogTime()} $message")
  }

  private fun logWarn(message: String) {
    Log.w(TAG, "ts=${nowLogTime()} $message")
  }

  private fun logError(message: String, throwable: Throwable? = null) {
    if (throwable == null) {
      Log.e(TAG, "ts=${nowLogTime()} $message")
    } else {
      Log.e(TAG, "ts=${nowLogTime()} $message", throwable)
    }
  }
}
