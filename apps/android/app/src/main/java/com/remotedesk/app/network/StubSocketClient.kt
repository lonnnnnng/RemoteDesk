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
    private const val NOISY_LOG_INTERVAL_MS = 1000L
    private val LOG_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
  }

  private val client = OkHttpClient()
  private var socket: WebSocket? = null
  private val noisyLogCounts = mutableMapOf<String, Int>()
  private val noisyLogLastAtMs = mutableMapOf<String, Long>()

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
        logEnvelope("recv", text)
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
      logEnvelope("send", text)
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

  private fun logEnvelope(direction: String, text: String) {
    val noisyKey = classifyNoisyEnvelope(text)
    if (noisyKey != null) {
      logNoisyEnvelopeSummary(direction, noisyKey, text.length)
      return
    }
    logInfo("$direction ${summarizeEnvelope(text)}")
  }

  private fun classifyNoisyEnvelope(text: String): String? {
    return when {
      text.contains("\"type\":\"screen.frame.push\"") || text.contains("\"type\": \"screen.frame.push\"") ->
        "screen.frame.push"
      text.contains("\"type\":\"input.mouse.move\"") || text.contains("\"type\": \"input.mouse.move\"") ->
        "input.mouse.move"
      text.contains("\"echo_type\":\"input.mouse.move\"") || text.contains("\"echo_type\": \"input.mouse.move\"") ->
        "input.ack.mouse.move"
      text.contains("\"input_type\":\"input.mouse.move\"") || text.contains("\"input_type\": \"input.mouse.move\"") ->
        "input.result.mouse.move"
      else -> null
    }
  }

  private fun logNoisyEnvelopeSummary(direction: String, noisyKey: String, bytes: Int) {
    val bucket = "$direction:$noisyKey"
    val now = System.currentTimeMillis()
    val nextCount = (noisyLogCounts[bucket] ?: 0) + 1
    noisyLogCounts[bucket] = nextCount
    val lastAtMs = noisyLogLastAtMs[bucket] ?: 0L
    if (now - lastAtMs < NOISY_LOG_INTERVAL_MS) {
      return
    }
    noisyLogLastAtMs[bucket] = now
    noisyLogCounts[bucket] = 0
    // 作者: long；远控画面帧和鼠标移动是热路径，逐条 JSON 解析和 logcat 输出会反向拖慢真机渲染，只保留每秒聚合证据。
    logInfo("$direction noisy=$noisyKey count=$nextCount bytes_last=$bytes")
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
