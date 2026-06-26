package com.remotedesk.app.ui

import android.app.Dialog
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.remotedesk.app.controller.StubSessionController
import com.remotedesk.app.databinding.ActivityMainBinding
import com.remotedesk.app.network.StubSocketClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
  companion object {
    private const val RTC_TAG = "RemoteDeskRtc"
    private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    private const val MAX_FRAME_B64_LENGTH = 12 * 1024 * 1024
    private const val MAX_FRAME_DIMENSION = 4096
    private const val PREFS_NAME = "remote_desk_demo"
    private const val PREF_WS_URL = "ws_url"
    private const val PREF_TARGET_DEVICE_ID = "target_device_id"
    private const val EXTRA_WS_URL = "rd_ws_url"
    private const val EXTRA_TARGET_DEVICE_ID = "rd_target_device_id"
    private const val EXTRA_AUTO_CONNECT = "rd_auto_connect"
    private const val EXTRA_AUTO_REQUEST_SESSION = "rd_auto_request_session"
    private const val EXTRA_AUTO_PROOF_INPUT = "rd_auto_proof_input"
    private const val AUTO_REQUEST_SESSION_DELAY_MS = 1200L
    private const val AUTO_PROOF_INPUT_DELAY_MS = 6500L
    private const val DEFAULT_LOCAL_WS_URL = ""
    private const val LEGACY_LOCAL_WS_URL = "ws://10.0.2.2:18080/ws"
    private const val EMULATOR_WS_URL = "ws://10.0.2.2:18081/ws"
    // 作者: long；远控视频渲染优先级高于指标面板刷新，降低 UI 文本更新频率，避免真机动态控制时主线程挤压 Surface 渲染。
    private const val RTC_STATS_UI_UPDATE_INTERVAL_MS = 1000L
    private const val RTC_WATCHDOG_INTERVAL_MS = 2000L
    private const val RTC_WATCHDOG_NO_TRACK_TIMEOUT_MS = 6000L
    private const val RTC_WATCHDOG_NO_FRAME_TIMEOUT_MS = 7000L
    private const val RTC_WATCHDOG_RECOVERY_COOLDOWN_MS = 8000L
    private const val RTC_WATCHDOG_MAX_RECOVERY_ATTEMPTS = 3
    private const val RTC_RENDER_LOG_SAMPLE_INTERVAL_MS = 2000L
    private const val RTC_NET_STATS_SAMPLE_INTERVAL_MS = 3000L
    private const val RTC_ICE_POLICY_DEGRADE_STREAK_THRESHOLD = 3
    private const val RTC_ICE_POLICY_RECOVERY_COOLDOWN_MS = 20000L
    private const val RTC_ICE_POLICY_MAX_RECOVERY_ATTEMPTS = 2
    private const val RTC_ICE_POLICY_RELAY_UDP_HIGH_RTT_MS = 220.0
    private const val RTC_QUALITY_FPS_LOW_THRESHOLD = 10.0
    private const val RTC_QUALITY_STALL_FPS_THRESHOLD = 16.0
    private const val RTC_QUALITY_BITRATE_LOW_KBPS = 350.0
    private const val RTC_QUALITY_RTT_HIGH_MS = 220.0
    private const val RTC_QUALITY_LOW_FPS_STREAK_THRESHOLD_MS = 6000L
    private const val RTC_QUALITY_FRAME_GAP_SPIKE_MS = 1000L
    private const val RTC_QUALITY_DROPPED_FRAME_SPIKE_THRESHOLD = 12L
    private const val RTC_RECENT_QUALITY_SAMPLE_LIMIT = 5
    private const val LIVE_E2E_PROOF_REPORT_MIN_INTERVAL_MS = 1500L
    private const val LEGACY_FRAME_IGNORED_LOG_INTERVAL_MS = 3000L
    private const val LEGACY_DECODE_FAILURE_UI_INTERVAL_MS = 1200L
    private const val RELAY_RECONNECT_INITIAL_DELAY_MS = 1000L
    private const val RELAY_RECONNECT_MAX_DELAY_MS = 15000L
    private const val RELAY_RECONNECT_RECOVERY_MAX_DELAY_MS = 3000L
    private const val RELAY_SESSION_RECOVERY_DELAY_MS = 800L
    private const val RELAY_SESSION_RECOVERY_RETRY_DELAY_MS = 2500L
    private const val RELAY_SESSION_RECOVERY_MAX_ATTEMPTS = 6
    private const val RTC_NEGOTIATION_OWNER_UNKNOWN = "unknown"
    private const val RTC_NEGOTIATION_OWNER_CONTROLLER = "controller"
    private const val RTC_NEGOTIATION_OWNER_REMOTE = "remote"
    private const val REMOTE_VIEWPORT_MIN_SCALE = 1f
    private const val REMOTE_VIEWPORT_MAX_SCALE = 4f
    // 作者: long；拖拽映射保持约 30fps 的输入粒度，既保留鼠标跟手感，也避免 ADB/手指连续滑动时用过密 WebSocket 消息拖慢控制端 UI。
    private const val REMOTE_POINTER_MOVE_MIN_INTERVAL_MS = 33L
    private const val REMOTE_POINTER_MOVE_MIN_DELTA = 0.004
    private const val REMOTE_WHEEL_MIN_INTERVAL_MS = 40L
    private const val REMOTE_WHEEL_DELTA_PER_PIXEL = 3f
    private const val REMOTE_WHEEL_MIN_ABS_DELTA = 24
    private const val REMOTE_WHEEL_MAX_ABS_DELTA = 720
    private const val REMOTE_KEYBOARD_MAX_BATCH_CHARS = 64
  }

  private lateinit var binding: ActivityMainBinding
  private val deviceId = "android-${System.currentTimeMillis().toString(16)}"
  private val controller = StubSessionController(deviceId)
  private var token: String = "stub-token"
  private var sessionId: String? = null
  private val logs = ArrayDeque<String>()
  private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
  private val logLineFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
  private val frameDecodeExecutor = Executors.newSingleThreadExecutor()
  private val deviceSyncExecutor = Executors.newSingleThreadExecutor()
  private val devicesHttpClient = OkHttpClient()
  private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
  private var renderedFrameWidth = 0
  private var renderedFrameHeight = 0
  private var pendingSessionRequest = false
  private var pendingSessionRequestId: String? = null
  private var activeSessionPeerDeviceId: String? = null
  private var frameGeneration = 0
  private var isSocketConnected = false
  private var isRegistered = false
  private var isPresenceReady = false
  private var currentPage = MainPage.MY_DEVICES
  private var relayDevices: List<RelayDevice> = emptyList()
  private var isFetchingDevices = false
  private var hasLoadedDevices = false
  private var lastDevicesUrl: String? = null
  private var isFetchingE2EProof = false
  private var e2eProofStatusMessage = "E2E proof: not checked"
  private var lastAutoConnectUrl: String? = null
  private var devicesStatusMessage = "连接中继服务后会自动同步设备列表。"
  private var rtcEglBase: EglBase? = null
  private var rtcFactory: PeerConnectionFactory? = null
  private var rtcPeerConnection: PeerConnection? = null
  private var rtcRemoteVideoTrack: VideoTrack? = null
  private var rtcCurrentSessionId: String? = null
  private var rtcNegotiationOwner = RTC_NEGOTIATION_OWNER_UNKNOWN
  private var rtcControllerProfile = "standard"
  private var rtcIceServers: List<PeerConnection.IceServer> = emptyList()
  private var lastRtcStatsUiUpdateAtMs: Long = 0L
  private val reconnectHandler = Handler(Looper.getMainLooper())
  private val rtcWatchdogHandler = Handler(Looper.getMainLooper())
  private val sessionClockHandler = Handler(Looper.getMainLooper())
  private val autoProofHandler = Handler(Looper.getMainLooper())
  private var launchAutoRequestSession = false
  private var launchAutoProofInput = false
  private var autoRequestSentForTargetDeviceId = ""
  private var autoProofInputSentForSessionId = ""
  private var reconnectScheduled = false
  private var reconnectAttempt = 0
  private var reconnectShouldRestoreSession = false
  private var reconnectTargetDeviceId: String? = null
  private var reconnectSessionRestoreScheduled = false
  private var reconnectSessionRestoreAttempt = 0
  private var isRecoveringSession = false
  private var pendingSessionRequestIsRecovery = false
  private var sessionEndRequestedByUser = false
  private var sessionClockToken = 0L
  private var lastDevicesPushAtMs = 0L
  private val rtcPendingRemoteCandidates = ArrayDeque<IceCandidate>()
  private var rtcWatchdogToken = 0L
  private var rtcSessionStartedAtMs = 0L
  private var sessionStartedAtWallClockMs = 0L
  private var rtcTrackAttachedAtMs = 0L
  private var rtcLastFrameRenderedAtMs = 0L
  private var rtcRecoveryAttempts = 0
  private var rtcLastRecoveryAtMs = 0L
  private var rtcRenderedFrameCount = 0L
  private var rtcRenderLogSampleFrameCount = 0L
  private var rtcRenderLogSampleAtMs = 0L
  private var rtcFirstFrameAtMs = 0L
  private var rtcRenderFpsSampleSum = 0.0
  private var rtcRenderFpsSampleCount = 0L
  private var rtcCurrentLowFpsStreakMs = 0L
  private var rtcLongestLowFpsStreakMs = 0L
  private var rtcLowFpsSampleCount = 0L
  private var rtcLongestFrameGapMs = 0L
  private var rtcFrameGapSpikeCount = 0L
  private var rtcRenderLogSampleMaxGapMs = 0L
  private val rtcRecentRenderSamples = ArrayDeque<RtcRenderQualitySample>()
  private var rtcRecentRenderFpsSum = 0.0
  private var rtcLastNetStatsSampleAtMs = 0L
  private var rtcNetStatsRequestInFlight = false
  private var rtcLastInboundBytesReceived = 0.0
  private var rtcLastInboundBytesAtMs = 0L
  private var rtcNetRecvKbpsSampleSum = 0.0
  private var rtcNetRecvKbpsSampleCount = 0L
  private var rtcNetRttMsSampleSum = 0.0
  private var rtcNetRttMsSampleCount = 0L
  private var rtcLastCandidatePair = "-"
  private var rtcLastPairState = "-"
  private var rtcLastCandidateTier = "-"
  private var rtcLastDecodedFps: Double? = null
  private var rtcLastRecvKbps: Double? = null
  private var rtcLastRttMs: Double? = null
  private var rtcLastFramesDroppedValue = -1L
  private var rtcFramesDroppedLast = 0L
  private var rtcFramesDroppedSpikeMax = 0L
  private val rtcRecentDroppedFrameSamples = ArrayDeque<RtcDroppedFrameSample>()
  private var rtcLocalIceCandidateCallbackCount = 0L
  private var rtcLocalIceCandidateFallbackCount = 0L
  private var rtcLocalIceCandidateSentCount = 0L
  private var rtcLocalIceCandidateSdpCount = 0L
  private val rtcSentLocalIceCandidateKeys = mutableSetOf<String>()
  private var rtcIcePolicyDegradeStreak = 0
  private var rtcIcePolicyRecoveryAttempts = 0
  private var rtcIcePolicyLastActionAtMs = 0L
  private var rtcIcePolicyRelayUdpHighRttMs = RTC_ICE_POLICY_RELAY_UDP_HIGH_RTT_MS
  private var rtcIceTransportRelayOnly = false
  private var legacyFrameCount = 0L
  private var legacyFrameSampleFrameCount = 0L
  private var legacyFrameSampleAtMs = 0L
  private var legacyFirstFrameAtMs = 0L
  private var lastLegacyFrameUiUpdateAtMs = 0L
  private var lastLegacyFrameIgnoredLogAtMs = 0L
  private var lastLegacyDecodeFailureAtMs = 0L
  private var legacyDecodeFailureSuppressedCount = 0
  private var remoteViewportScale = REMOTE_VIEWPORT_MIN_SCALE
  private var remoteViewportOffsetX = 0f
  private var remoteViewportOffsetY = 0f
  private var remoteTouchDownX = 0f
  private var remoteTouchDownY = 0f
  private var remoteLastTouchX = 0f
  private var remoteLastTouchY = 0f
  private var remotePanMoved = false
  private var remoteSuppressTap = false
  private var remoteTouchSlopPx = 0f
  private var remoteMouseButtonDown = false
  private var remoteTouchDownPoint: NormalizedPoint? = null
  private var remoteLastInputPoint: NormalizedPoint? = null
  private var remoteLastSentMovePoint: NormalizedPoint? = null
  private var remoteLastSentMoveAtMs = 0L
  private var remoteScrollGestureActive = false
  private var remoteScrollLastFocusX = 0f
  private var remoteScrollLastFocusY = 0f
  private var remoteLastWheelAtMs = 0L
  private var remoteInputResultCount = 0L
  private var remoteInputResultAppliedCount = 0L
  private var remoteInputResultFailedCount = 0L
  private var lastRemoteInputResult = RemoteInputResult()
  private val remoteInputAppliedCategories = linkedSetOf<String>()
  private var lastLiveE2EProofReportAtMs = 0L
  private var lastLiveE2EProofReportKey = ""
  private lateinit var remoteScaleGestureDetector: ScaleGestureDetector
  private var remoteFullscreenDialog: Dialog? = null
  private var remoteFullscreenHost: FrameLayout? = null
  private var remoteViewportOriginParent: ViewGroup? = null
  private var remoteViewportOriginLayoutParams: ViewGroup.LayoutParams? = null
  private var remoteViewportOriginIndex = -1
  private var remoteFullscreenPreviousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
  private val rtcProbeSink = VideoSink { frame ->
    if (!isActivityAlive) {
      return@VideoSink
    }
    renderedFrameWidth = frame.rotatedWidth
    renderedFrameHeight = frame.rotatedHeight
    val now = SystemClock.elapsedRealtime()
    val previousFrameAtMs = rtcLastFrameRenderedAtMs
    if (previousFrameAtMs > 0L) {
      val frameGapMs = now - previousFrameAtMs
      if (frameGapMs > rtcLongestFrameGapMs) {
        rtcLongestFrameGapMs = frameGapMs
      }
      if (frameGapMs > rtcRenderLogSampleMaxGapMs) {
        rtcRenderLogSampleMaxGapMs = frameGapMs
      }
      if (frameGapMs >= RTC_QUALITY_FRAME_GAP_SPIKE_MS) {
        rtcFrameGapSpikeCount += 1
        Log.w(
          RTC_TAG,
          "render_frame_gap_spike session=${rtcCurrentSessionId ?: sessionId ?: "-"} gap_ms=$frameGapMs longest_gap_ms=$rtcLongestFrameGapMs spikes=$rtcFrameGapSpikeCount",
        )
      }
    }
    rtcLastFrameRenderedAtMs = now
    rtcRenderedFrameCount += 1
    val activeSessionId = rtcCurrentSessionId ?: sessionId ?: "-"
    if (rtcFirstFrameAtMs <= 0L) {
      rtcFirstFrameAtMs = now
      val sinceTrackMs = if (rtcTrackAttachedAtMs > 0L) now - rtcTrackAttachedAtMs else -1L
      Log.i(
        RTC_TAG,
        "first_rendered_frame session=$activeSessionId size=${frame.rotatedWidth}x${frame.rotatedHeight} since_track_ms=$sinceTrackMs",
      )
      runOnUiThread {
        if (!isActivityAlive) {
          return@runOnUiThread
        }
        maybeSendLiveE2EProofReport("live_video_frame")
      }
    }
    if (rtcRenderLogSampleAtMs <= 0L) {
      rtcRenderLogSampleAtMs = now
      rtcRenderLogSampleFrameCount = rtcRenderedFrameCount
      rtcRenderLogSampleMaxGapMs = 0L
    } else {
      val sampleElapsedMs = now - rtcRenderLogSampleAtMs
      if (sampleElapsedMs >= RTC_RENDER_LOG_SAMPLE_INTERVAL_MS) {
        val sampleFrames = rtcRenderedFrameCount - rtcRenderLogSampleFrameCount
        val sampleFps = if (sampleElapsedMs > 0L) sampleFrames * 1000.0 / sampleElapsedMs.toDouble() else 0.0
        rtcRenderFpsSampleSum += sampleFps
        rtcRenderFpsSampleCount += 1
        if (sampleFps < RTC_QUALITY_STALL_FPS_THRESHOLD) {
          rtcLowFpsSampleCount += 1
          rtcCurrentLowFpsStreakMs += sampleElapsedMs
          if (rtcCurrentLowFpsStreakMs > rtcLongestLowFpsStreakMs) {
            rtcLongestLowFpsStreakMs = rtcCurrentLowFpsStreakMs
          }
          if (rtcCurrentLowFpsStreakMs >= RTC_QUALITY_LOW_FPS_STREAK_THRESHOLD_MS) {
            Log.w(
              RTC_TAG,
              "render_low_fps_streak session=$activeSessionId streak_ms=$rtcCurrentLowFpsStreakMs longest_ms=$rtcLongestLowFpsStreakMs fps=${"%.2f".format(Locale.US, sampleFps)}",
            )
          }
        } else {
          rtcCurrentLowFpsStreakMs = 0L
        }
        val sampleMaxGapMs = rtcRenderLogSampleMaxGapMs
        recordRtcRenderQualitySample(
          fps = sampleFps,
          windowMs = sampleElapsedMs,
          maxFrameGapMs = sampleMaxGapMs,
          lowFpsStreakMs = rtcCurrentLowFpsStreakMs,
        )
        val recentQuality = currentRecentRenderQuality()
        val recentQualityHint = inferRtcQualityHintCode(
          renderFpsAvg = recentQuality.fpsAvg,
          recvKbpsAvg = rtcLastRecvKbps,
          candidateTier = rtcLastCandidateTier,
          rttMs = rtcLastRttMs,
          frameGapMs = recentQuality.maxFrameGapMs,
          lowFpsStreakMs = recentQuality.lowFpsStreakMs,
          droppedFrameSpikeMax = recentFramesDroppedSpikeMax(),
        )
        Log.i(
          RTC_TAG,
          "render_frame_sample session=$activeSessionId frames_total=$rtcRenderedFrameCount sample_frames=$sampleFrames sample_ms=$sampleElapsedMs fps=${"%.2f".format(Locale.US, sampleFps)} low_fps_streak_ms=$rtcCurrentLowFpsStreakMs sample_gap_ms=$sampleMaxGapMs longest_gap_ms=$rtcLongestFrameGapMs recent_samples=${recentQuality.sampleCount} recent_window_ms=${recentQuality.windowMs} recent_fps=${formatMetric(recentQuality.fpsAvg)} recent_gap_ms=${recentQuality.maxFrameGapMs} recent_quality_hint=$recentQualityHint size=${frame.rotatedWidth}x${frame.rotatedHeight}",
        )
        rtcRenderLogSampleAtMs = now
        rtcRenderLogSampleFrameCount = rtcRenderedFrameCount
        rtcRenderLogSampleMaxGapMs = 0L
      }
    }
    if (now - lastRtcStatsUiUpdateAtMs < RTC_STATS_UI_UPDATE_INTERVAL_MS) {
      return@VideoSink
    }
    lastRtcStatsUiUpdateAtMs = now
    runOnUiThread {
      if (!isActivityAlive) {
        return@runOnUiThread
      }
      binding.frameMetaText.text = "当前画面：webrtc ${formatFrameSize(renderedFrameWidth, renderedFrameHeight)} @ ${formatTimestamp(System.currentTimeMillis())}"
      setStatus("接收远端画面")
      updateLiveMetricsPanel()
    }
  }

  @Volatile
  private var isActivityAlive = true

  private val socketClient = StubSocketClient(
    onOpen = {
      runOnUiThread {
        if (!isActivityAlive) {
          return@runOnUiThread
        }
        if (reconnectAttempt > 0) {
          appendLog("中继信令已自动重连成功（第 $reconnectAttempt 次尝试）")
        }
        reconnectAttempt = 0
        isSocketConnected = true
        isRegistered = false
        isPresenceReady = false
        token = "stub-token"
        hasLoadedDevices = false
        lastDevicesUrl = null
        binding.tokenText.text = "访问凭证：未注册"
        setStatus("已连接")
        updateSessionButtonState()
        appendLog("中继信令连接已建立")
        refreshDevicesList(force = true)
        if (!sendSocketMessage(controller.registerMessage(), "发送 device.register.req")) {
          appendLog("自动注册失败：中继信令未连接")
        } else {
          setStatus("注册中")
          updateSessionButtonState()
        }
      }
    },
    onMessage = { text ->
      runOnUiThread {
        if (!isActivityAlive) {
          return@runOnUiThread
        }
        parseMessage(text)
      }
    },
    onClosed = { reason ->
      runOnUiThread {
        if (!isActivityAlive) {
          return@runOnUiThread
        }
        handleDisconnectedState(statusLine = "已关闭", logLine = "连接关闭：$reason")
      }
    },
    onFailure = { error ->
      runOnUiThread {
        if (!isActivityAlive) {
          return@runOnUiThread
        }
        handleDisconnectedState(statusLine = "连接失败", logLine = "连接失败：$error")
      }
    },
  )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    initializeWebRtc()

    binding.deviceIdText.text = "本机设备 ID：$deviceId"
    setStatus("未连接")
    binding.tokenText.text = "访问凭证：未注册"
    binding.sessionText.text = "当前会话：无"
    binding.sessionStartText.text = "会话开始：-"
    binding.sessionDurationText.text = "会话时长：-"
    binding.transportText.text = "传输方式：-"
    binding.peerText.text = "会话链路：-"
    binding.ackText.text = "输入回执：-"
    binding.frameMetaText.text = "当前画面：-"
    updateE2EProofStatusText()
    updateLiveMetricsPanel()
    binding.remoteFrameCard.isVisible = false

    val launchWsUrl = normalizeWsUrl(intent?.getStringExtra(EXTRA_WS_URL).orEmpty())
    val launchTargetDeviceId = intent?.getStringExtra(EXTRA_TARGET_DEVICE_ID).orEmpty().trim()
    val launchAutoConnect = intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) == true
    launchAutoRequestSession = intent?.getBooleanExtra(EXTRA_AUTO_REQUEST_SESSION, false) == true
    launchAutoProofInput = intent?.getBooleanExtra(EXTRA_AUTO_PROOF_INPUT, false) == true
    val savedWsUrl = normalizeWsUrl(preferences.getString(PREF_WS_URL, null).orEmpty())
    val savedUri = if (savedWsUrl.isNotBlank()) Uri.parse(savedWsUrl) else null
    val shouldMigrateEmulatorHost = isLikelyEmulator() &&
      (savedUri?.host.equals("127.0.0.1", ignoreCase = true) || savedUri?.host.equals("localhost", ignoreCase = true)) &&
      savedUri?.port == 18081
    val initialWsUrl = when {
      launchWsUrl.isNotBlank() -> launchWsUrl
      savedWsUrl.isBlank() -> if (isLikelyEmulator()) EMULATOR_WS_URL else DEFAULT_LOCAL_WS_URL
      shouldMigrateEmulatorHost -> EMULATOR_WS_URL
      savedWsUrl == LEGACY_LOCAL_WS_URL -> EMULATOR_WS_URL
      else -> savedWsUrl
    }
    binding.wsUrlInput.setText(normalizeWsUrl(initialWsUrl))
    binding.targetDeviceInput.setText(
      launchTargetDeviceId.ifBlank {
        preferences.getString(PREF_TARGET_DEVICE_ID, binding.targetDeviceInput.text.toString()).orEmpty()
      },
    )
    binding.wsUrlInput.doAfterTextChanged { editable ->
      val wsUrl = normalizeWsUrl(editable?.toString().orEmpty())
      val targetDeviceId = binding.targetDeviceInput.text?.toString().orEmpty().trim()
      persistConnectionSettings(wsUrl = wsUrl, targetDeviceId = targetDeviceId)
      if (wsUrl.isBlank()) {
        lastAutoConnectUrl = null
        return@doAfterTextChanged
      }
      if (validateWsUrl(wsUrl) != null) {
        return@doAfterTextChanged
      }
      val wsPath = Uri.parse(wsUrl).path.orEmpty()
      if (wsPath.isNotBlank() && !wsPath.endsWith("/ws")) {
        return@doAfterTextChanged
      }
      if (wsUrl == lastAutoConnectUrl) {
        return@doAfterTextChanged
      }
      lastAutoConnectUrl = wsUrl
      beginConnectFlow()
    }
    binding.targetDeviceInput.doAfterTextChanged { editable ->
      val targetDeviceId = editable?.toString().orEmpty().trim()
      persistConnectionSettings(
        wsUrl = normalizeWsUrl(binding.wsUrlInput.text.toString()),
        targetDeviceId = targetDeviceId,
      )
      renderDeviceList()
    }

    renderDeviceList()
    switchPage(currentPage, autoRefreshDevices = false)
    updateSessionButtonState()

    val currentWsUrl = normalizeWsUrl(binding.wsUrlInput.text?.toString().orEmpty())
    val hasWsUrl = currentWsUrl.isNotBlank()
    if (launchWsUrl.isNotBlank()) {
      appendLog("Launch relay URL injected via adb: $launchWsUrl")
    }
    if (hasWsUrl && (launchAutoConnect || savedWsUrl.isNotBlank())) {
      beginConnectFlow()
    } else {
      switchPage(MainPage.SETTINGS, autoRefreshDevices = false)
      setStatus("未连接")
      appendLog("首次使用请先配置中继地址")
    }

    binding.navMyDevicesButton.setOnClickListener {
      switchPage(MainPage.MY_DEVICES)
    }
    binding.navProfileButton.setOnClickListener {
      switchPage(MainPage.SETTINGS)
    }
    binding.remoteVideoView.setOnTouchListener { view, event ->
      handleRemoteFrameTouchV2(view, event)
    }
    binding.remoteFrameView.setOnTouchListener { view, event ->
      handleRemoteFrameTouchV2(view, event)
    }
    initializeRemoteViewportControls()
    initializeRemoteKeyboardControls()
    binding.connectButton.setOnClickListener {
      beginConnectFlow()
    }
    binding.registerButton.setOnClickListener {
      if (sendSocketMessage(controller.registerMessage(), "发送 device.register.req")) {
        setStatus("注册中")
      }
    }
    binding.heartbeatButton.setOnClickListener {
      if (sendSocketMessage(controller.heartbeatMessage(token, sessionId), "发送 presence.heartbeat.req")) {
        setStatus("同步在线状态")
      }
    }
    binding.sessionButton.setOnClickListener {
      requestSession()
    }
    binding.debugTapButton.setOnClickListener {
      val currentSessionId = sessionId
      if (currentSessionId.isNullOrBlank()) {
        appendLog("当前没有 session，不能发送测试点击")
        return@setOnClickListener
      }
      sendPreviewTap(currentSessionId, NormalizedPoint(0.5, 0.5))
    }
    binding.debugKeyboardButton.setOnClickListener {
      val currentSessionId = sessionId
      if (currentSessionId.isNullOrBlank()) {
        appendLog("当前没有 session，不能发送测试键盘事件")
        return@setOnClickListener
      }
      sendKeyboardSample(currentSessionId)
    }
    binding.debugScrollButton.setOnClickListener {
      val currentSessionId = sessionId
      if (currentSessionId.isNullOrBlank()) {
        appendLog("当前没有 session，不能发送测试滚轮")
        return@setOnClickListener
      }
      sendScrollSample(currentSessionId)
    }
    binding.debugProofSequenceButton.setOnClickListener {
      val currentSessionId = sessionId
      if (currentSessionId.isNullOrBlank()) {
        appendLog("No active session; cannot send E2E proof input sequence")
        return@setOnClickListener
      }
      sendE2EProofInputSequence(currentSessionId)
    }
    binding.debugProofRefreshButton.setOnClickListener {
      refreshE2EProofSnapshot(reset = false, userInitiated = true)
    }
    binding.debugProofResetButton.setOnClickListener {
      refreshE2EProofSnapshot(reset = true, userInitiated = true)
    }
  }

  override fun onDestroy() {
    isActivityAlive = false
    releaseRemoteInputState()
    autoProofHandler.removeCallbacksAndMessages(null)
    cancelAutoReconnect()
    stopSessionClockTicker()
    exitRemoteViewportFullscreen(reason = "activity_destroy")
    socketClient.close()
    stopRtcWatchdog()
    closeWebRtcSession(reason = "activity_destroy")
    releaseWebRtc()
    frameDecodeExecutor.shutdownNow()
    deviceSyncExecutor.shutdownNow()
    devicesHttpClient.dispatcher.executorService.shutdown()
    devicesHttpClient.connectionPool.evictAll()
    super.onDestroy()
  }

  override fun onResume() {
    super.onResume()
    Log.i(RTC_TAG, "activity_lifecycle event=resume session=${sessionId ?: "-"} rtc_session=${rtcCurrentSessionId ?: "-"}")
  }

  override fun onPause() {
    Log.i(RTC_TAG, "activity_lifecycle event=pause session=${sessionId ?: "-"} rtc_session=${rtcCurrentSessionId ?: "-"}")
    releaseRemoteInputState()
    super.onPause()
  }

  private fun sendSocketMessage(message: String, successLog: String, logSuccess: Boolean = true): Boolean {
    if (socketClient.send(message)) {
      if (logSuccess) {
        appendLog(successLog)
      }
      return true
    }
    appendLog("发送失败：中继信令未连接")
    return false
  }

  private fun handleDisconnectedState(statusLine: String, logLine: String) {
    val shouldRestoreSession = rememberSessionRecoveryIntent(reason = "signal_disconnected")
    isSocketConnected = false
    if (normalizeWsUrl(binding.wsUrlInput.text.toString()).isBlank()) {
      lastAutoConnectUrl = null
    }
    isRegistered = false
    isPresenceReady = false
    token = "stub-token"
    pendingSessionRequest = false
    pendingSessionRequestId = null
    pendingSessionRequestIsRecovery = false
    autoRequestSentForTargetDeviceId = ""
    autoProofInputSentForSessionId = ""
    autoProofHandler.removeCallbacksAndMessages(null)
    hasLoadedDevices = false
    lastDevicesUrl = null
    releaseRemoteInputState(sendMouseUp = false)
    binding.tokenText.text = "访问凭证：未注册"
    closeWebRtcSession(reason = "signal_disconnected", clearRendererImage = !shouldRestoreSession)
    resetSessionUi(clearFrame = !shouldRestoreSession)
    if (shouldRestoreSession) {
      isRecoveringSession = true
      binding.frameMetaText.text = "当前画面：信令短断，正在自动恢复，输入已暂停"
      setStatus("短断线恢复中")
    } else {
      setStatus(statusLine)
    }
    updateDevicesStatus(
      if (relayDevices.isEmpty()) {
        "连接关闭后无法确认设备在线状态；重新连接后可再次同步。"
      } else {
        "当前展示的是上次同步结果；重新连接后可再次刷新。"
      },
    )
    renderDeviceList()
    updateSessionButtonState()
    appendLog(logLine)
    scheduleAutoReconnect()
  }

  private fun initializeWebRtc() {
    if (rtcFactory != null) {
      return
    }
    val initOptions = PeerConnectionFactory.InitializationOptions
      .builder(applicationContext)
      .createInitializationOptions()
    PeerConnectionFactory.initialize(initOptions)
    rtcEglBase = EglBase.create()
    binding.remoteVideoView.init(rtcEglBase?.eglBaseContext, null)
    binding.remoteVideoView.setEnableHardwareScaler(true)
    binding.remoteVideoView.setMirror(false)
    val encoderFactory = DefaultVideoEncoderFactory(rtcEglBase?.eglBaseContext, true, true)
    val decoderFactory = DefaultVideoDecoderFactory(rtcEglBase?.eglBaseContext)
    rtcFactory = PeerConnectionFactory.builder()
      .setVideoEncoderFactory(encoderFactory)
      .setVideoDecoderFactory(decoderFactory)
      .createPeerConnectionFactory()
  }

  private fun releaseWebRtc() {
    binding.remoteVideoView.release()
    rtcPeerConnection?.dispose()
    rtcPeerConnection = null
    rtcRemoteVideoTrack?.dispose()
    rtcRemoteVideoTrack = null
    rtcFactory?.dispose()
    rtcFactory = null
    rtcEglBase?.release()
    rtcEglBase = null
  }

  private fun closeWebRtcSession(reason: String = "reset", clearRendererImage: Boolean = true) {
    stopRtcWatchdog(resetState = false)
    val closingSessionId = rtcCurrentSessionId
    if (!closingSessionId.isNullOrBlank()) {
      logRtcSessionSummary(closingSessionId, reason)
    }
    rtcRemoteVideoTrack?.removeSink(binding.remoteVideoView)
    rtcRemoteVideoTrack?.removeSink(rtcProbeSink)
    rtcRemoteVideoTrack = null
    rtcPeerConnection?.close()
    rtcPeerConnection?.dispose()
    rtcPeerConnection = null
    rtcCurrentSessionId = null
    rtcNegotiationOwner = RTC_NEGOTIATION_OWNER_UNKNOWN
    rtcPendingRemoteCandidates.clear()
    lastRtcStatsUiUpdateAtMs = 0L
    renderedFrameWidth = 0
    renderedFrameHeight = 0
    rtcTrackAttachedAtMs = 0L
    rtcLastFrameRenderedAtMs = 0L
    resetRtcRenderStats()
    resetRtcNetworkStats()
    resetLegacyFrameStats()
    resetLegacyFailureThrottleState()
    if (clearRendererImage) {
      binding.remoteVideoView.clearImage()
    }
    updateLiveMetricsPanel()
  }

  private fun resetRtcRenderStats() {
    rtcRenderedFrameCount = 0L
    rtcRenderLogSampleFrameCount = 0L
    rtcRenderLogSampleAtMs = 0L
    rtcFirstFrameAtMs = 0L
    rtcLastFrameRenderedAtMs = 0L
    rtcRenderFpsSampleSum = 0.0
    rtcRenderFpsSampleCount = 0L
    rtcCurrentLowFpsStreakMs = 0L
    rtcLongestLowFpsStreakMs = 0L
    rtcLowFpsSampleCount = 0L
    rtcLongestFrameGapMs = 0L
    rtcFrameGapSpikeCount = 0L
    rtcRenderLogSampleMaxGapMs = 0L
    rtcRecentRenderSamples.clear()
    rtcRecentRenderFpsSum = 0.0
  }

  private fun resetRtcNetworkStats() {
    rtcLastNetStatsSampleAtMs = 0L
    rtcNetStatsRequestInFlight = false
    rtcLastInboundBytesReceived = 0.0
    rtcLastInboundBytesAtMs = 0L
    rtcNetRecvKbpsSampleSum = 0.0
    rtcNetRecvKbpsSampleCount = 0L
    rtcNetRttMsSampleSum = 0.0
    rtcNetRttMsSampleCount = 0L
    rtcLastCandidatePair = "-"
    rtcLastPairState = "-"
    rtcLastCandidateTier = "-"
    rtcLastDecodedFps = null
    rtcLastRecvKbps = null
    rtcLastRttMs = null
    rtcLastFramesDroppedValue = -1L
    rtcFramesDroppedLast = 0L
    rtcFramesDroppedSpikeMax = 0L
    rtcRecentDroppedFrameSamples.clear()
    rtcLocalIceCandidateCallbackCount = 0L
    rtcLocalIceCandidateFallbackCount = 0L
    rtcLocalIceCandidateSentCount = 0L
    rtcLocalIceCandidateSdpCount = 0L
    rtcSentLocalIceCandidateKeys.clear()
    rtcIcePolicyDegradeStreak = 0
    rtcIcePolicyRecoveryAttempts = 0
    rtcIcePolicyLastActionAtMs = 0L
  }

  private fun recordRtcRenderQualitySample(
    fps: Double,
    windowMs: Long,
    maxFrameGapMs: Long,
    lowFpsStreakMs: Long,
  ) {
    val sample = RtcRenderQualitySample(
      fps = fps,
      windowMs = windowMs,
      maxFrameGapMs = maxFrameGapMs,
      lowFpsStreakMs = lowFpsStreakMs,
    )
    rtcRecentRenderSamples.addLast(sample)
    rtcRecentRenderFpsSum += fps
    while (rtcRecentRenderSamples.size > RTC_RECENT_QUALITY_SAMPLE_LIMIT) {
      val removed = rtcRecentRenderSamples.removeFirst()
      rtcRecentRenderFpsSum -= removed.fps
    }
  }

  private fun currentRecentRenderQuality(): RtcRecentRenderQuality {
    if (rtcRecentRenderSamples.isEmpty()) {
      return RtcRecentRenderQuality(
        sampleCount = 0,
        windowMs = 0L,
        fpsAvg = null,
        maxFrameGapMs = 0L,
        lowFpsStreakMs = 0L,
      )
    }
    var windowMs = 0L
    var maxFrameGapMs = 0L
    var lowFpsStreakMs = 0L
    for (sample in rtcRecentRenderSamples) {
      windowMs += sample.windowMs
      if (sample.maxFrameGapMs > maxFrameGapMs) {
        maxFrameGapMs = sample.maxFrameGapMs
      }
      if (sample.lowFpsStreakMs > lowFpsStreakMs) {
        lowFpsStreakMs = sample.lowFpsStreakMs
      }
    }
    return RtcRecentRenderQuality(
      sampleCount = rtcRecentRenderSamples.size,
      windowMs = windowMs,
      fpsAvg = rtcRecentRenderFpsSum / rtcRecentRenderSamples.size.toDouble(),
      maxFrameGapMs = maxFrameGapMs,
      lowFpsStreakMs = lowFpsStreakMs,
    )
  }

  private fun recordRtcDroppedFrameSample(framesDropped: Long, droppedSpike: Long) {
    rtcRecentDroppedFrameSamples.addLast(
      RtcDroppedFrameSample(
        framesDropped = framesDropped,
        droppedSpike = droppedSpike.coerceAtLeast(0L),
      ),
    )
    while (rtcRecentDroppedFrameSamples.size > RTC_RECENT_QUALITY_SAMPLE_LIMIT) {
      rtcRecentDroppedFrameSamples.removeFirst()
    }
  }

  private fun recentFramesDroppedDelta(): Long {
    if (rtcRecentDroppedFrameSamples.size < 2) {
      return 0L
    }
    val first = rtcRecentDroppedFrameSamples.first().framesDropped
    val last = rtcRecentDroppedFrameSamples.last().framesDropped
    return (last - first).coerceAtLeast(0L)
  }

  private fun recentFramesDroppedSpikeMax(): Long {
    var maxSpike = 0L
    for (sample in rtcRecentDroppedFrameSamples) {
      if (sample.droppedSpike > maxSpike) {
        maxSpike = sample.droppedSpike
      }
    }
    return maxSpike
  }

  private fun resetLegacyFrameStats() {
    legacyFrameCount = 0L
    legacyFrameSampleFrameCount = 0L
    legacyFrameSampleAtMs = 0L
    legacyFirstFrameAtMs = 0L
    lastLegacyFrameUiUpdateAtMs = 0L
  }

  private fun resetLegacyFailureThrottleState() {
    lastLegacyFrameIgnoredLogAtMs = 0L
    lastLegacyDecodeFailureAtMs = 0L
    legacyDecodeFailureSuppressedCount = 0
  }

  private fun resetRemoteInputResultStats() {
    remoteInputResultCount = 0L
    remoteInputResultAppliedCount = 0L
    remoteInputResultFailedCount = 0L
    lastRemoteInputResult = RemoteInputResult()
    remoteInputAppliedCategories.clear()
  }

  private fun resetLiveE2EProofReportState() {
    lastLiveE2EProofReportAtMs = 0L
    lastLiveE2EProofReportKey = ""
  }

  private fun logRtcSessionSummary(sessionId: String, reason: String) {
    val now = SystemClock.elapsedRealtime()
    val sessionDurationMs = if (rtcSessionStartedAtMs > 0L) now - rtcSessionStartedAtMs else -1L
    val firstFrameMs = if (rtcFirstFrameAtMs > 0L && rtcSessionStartedAtMs > 0L) {
      rtcFirstFrameAtMs - rtcSessionStartedAtMs
    } else {
      null
    }
    val renderFpsAvg = averageMetric(rtcRenderFpsSampleSum, rtcRenderFpsSampleCount)
    val recvKbpsAvg = averageMetric(rtcNetRecvKbpsSampleSum, rtcNetRecvKbpsSampleCount)
    val rttMsAvg = averageMetric(rtcNetRttMsSampleSum, rtcNetRttMsSampleCount)
    val iceState = rtcPeerConnection?.iceConnectionState()?.name ?: "-"
    val recentRenderQuality = currentRecentRenderQuality()
    val recentDroppedDelta = recentFramesDroppedDelta()
    val recentDroppedSpikeMax = recentFramesDroppedSpikeMax()
    val controllerQualityHint = inferRtcQualityHintCode(
      renderFpsAvg = renderFpsAvg,
      recvKbpsAvg = recvKbpsAvg,
      candidateTier = rtcLastCandidateTier,
      rttMs = rtcLastRttMs,
    )
    val controllerQualityHintRecent = inferRtcQualityHintCode(
      renderFpsAvg = recentRenderQuality.fpsAvg,
      recvKbpsAvg = rtcLastRecvKbps ?: recvKbpsAvg,
      candidateTier = rtcLastCandidateTier,
      rttMs = rtcLastRttMs,
      frameGapMs = recentRenderQuality.maxFrameGapMs,
      lowFpsStreakMs = recentRenderQuality.lowFpsStreakMs,
      droppedFrameSpikeMax = recentDroppedSpikeMax,
    )
    val frameWidthLast = if (renderedFrameWidth > 0) renderedFrameWidth else 0
    val frameHeightLast = if (renderedFrameHeight > 0) renderedFrameHeight else 0
    val frameSize = if (renderedFrameWidth > 0 && renderedFrameHeight > 0) {
      formatFrameSize(renderedFrameWidth, renderedFrameHeight)
    } else {
      "-"
    }
    Log.i(
      RTC_TAG,
      "session_summary session=$sessionId reason=$reason duration_ms=${if (sessionDurationMs >= 0L) sessionDurationMs else "-"} first_frame_ms=${firstFrameMs ?: "-"} render_fps_avg=${formatMetric(renderFpsAvg)} render_fps_recent=${formatMetric(recentRenderQuality.fpsAvg)} render_recent_samples=${recentRenderQuality.sampleCount} render_recent_window_ms=${recentRenderQuality.windowMs} rendered_frames=$rtcRenderedFrameCount recv_kbps_avg=${formatMetric(recvKbpsAvg)} rtt_ms_avg=${formatMetric(rttMsAvg)} longest_frame_gap_ms=$rtcLongestFrameGapMs recent_frame_gap_ms=${recentRenderQuality.maxFrameGapMs} low_fps_streak_ms=$rtcLongestLowFpsStreakMs recent_low_fps_streak_ms=${recentRenderQuality.lowFpsStreakMs} frames_dropped_last=$rtcFramesDroppedLast frames_dropped_spike_max=$rtcFramesDroppedSpikeMax frames_dropped_delta_recent=$recentDroppedDelta frames_dropped_spike_recent=$recentDroppedSpikeMax controller_quality_hint=$controllerQualityHint controller_quality_hint_recent=$controllerQualityHintRecent candidate_pair_last=$rtcLastCandidatePair candidate_tier_last=$rtcLastCandidateTier pair_state_last=$rtcLastPairState local_ice_candidate_callbacks=$rtcLocalIceCandidateCallbackCount local_ice_candidate_fallbacks=$rtcLocalIceCandidateFallbackCount local_ice_candidate_sent=$rtcLocalIceCandidateSentCount local_ice_candidate_sdp_count=$rtcLocalIceCandidateSdpCount ice_policy_restarts=$rtcIcePolicyRecoveryAttempts frame_size_last=$frameSize ice_state_last=$iceState",
    )
    val metricsPayload = mapOf<String, Any?>(
      "duration_ms" to if (sessionDurationMs >= 0L) sessionDurationMs else -1L,
      "first_frame_ms" to (firstFrameMs ?: -1L),
      "render_fps_avg" to metricOrMinusOne(renderFpsAvg),
      "render_fps_recent" to metricOrMinusOne(recentRenderQuality.fpsAvg),
      "render_recent_sample_count" to recentRenderQuality.sampleCount,
      "render_recent_window_ms" to recentRenderQuality.windowMs,
      "rendered_frames" to rtcRenderedFrameCount,
      "recv_kbps_avg" to metricOrMinusOne(recvKbpsAvg),
      "rtt_ms_avg" to metricOrMinusOne(rttMsAvg),
      "render_longest_frame_gap_ms" to rtcLongestFrameGapMs,
      "render_recent_max_frame_gap_ms" to recentRenderQuality.maxFrameGapMs,
      "render_frame_gap_spike_count" to rtcFrameGapSpikeCount,
      "render_low_fps_sample_count" to rtcLowFpsSampleCount,
      "render_longest_low_fps_streak_ms" to rtcLongestLowFpsStreakMs,
      "render_recent_low_fps_streak_ms" to recentRenderQuality.lowFpsStreakMs,
      "frames_dropped_last" to rtcFramesDroppedLast,
      "frames_dropped_spike_max" to rtcFramesDroppedSpikeMax,
      "frames_dropped_delta_recent" to recentDroppedDelta,
      "frames_dropped_spike_recent" to recentDroppedSpikeMax,
      "controller_quality_hint" to controllerQualityHint,
      "controller_quality_hint_recent" to controllerQualityHintRecent,
      "candidate_pair_last" to rtcLastCandidatePair,
      "candidate_tier_last" to rtcLastCandidateTier,
      "pair_state_last" to rtcLastPairState,
      "local_ice_candidate_callback_count" to rtcLocalIceCandidateCallbackCount,
      "local_ice_candidate_fallback_count" to rtcLocalIceCandidateFallbackCount,
      "local_ice_candidate_sent_count" to rtcLocalIceCandidateSentCount,
      "local_ice_candidate_sdp_count" to rtcLocalIceCandidateSdpCount,
      "ice_policy_restarts" to rtcIcePolicyRecoveryAttempts,
      "frame_width_last" to frameWidthLast,
      "frame_height_last" to frameHeightLast,
      "ice_state_last" to iceState,
      "remote_input_result_count" to remoteInputResultCount,
      "remote_input_result_applied_count" to remoteInputResultAppliedCount,
      "remote_input_result_failed_count" to remoteInputResultFailedCount,
      "remote_input_applied_click" to remoteInputAppliedCategories.contains("click"),
      "remote_input_applied_drag" to remoteInputAppliedCategories.contains("drag"),
      "remote_input_applied_keyboard" to remoteInputAppliedCategories.contains("keyboard"),
      "remote_input_applied_wheel" to remoteInputAppliedCategories.contains("wheel"),
      "remote_input_required_coverage_complete" to remoteInputCoverageComplete(),
      "remote_input_applied_categories" to remoteInputCoverageSummary().ifBlank { "-" },
      "remote_input_last_type" to lastRemoteInputResult.inputType.ifBlank { "-" },
      "remote_input_last_category" to lastRemoteInputResult.inputCategory.ifBlank { "-" },
      "remote_input_last_trace_id" to lastRemoteInputResult.inputTraceId.ifBlank { "-" },
      "remote_input_last_applied" to lastRemoteInputResult.applied,
      "remote_input_last_executor" to lastRemoteInputResult.executor.ifBlank { "-" },
      "remote_input_last_status_code" to lastRemoteInputResult.statusCode.ifBlank { "-" },
      "remote_input_last_status_detail" to lastRemoteInputResult.statusDetail.ifBlank { "-" },
      "remote_input_last_error_code" to lastRemoteInputResult.errorCode.ifBlank { "-" },
      "remote_input_last_error_detail" to lastRemoteInputResult.errorDetail.ifBlank { "-" },
      "remote_input_last_summary" to lastRemoteInputResult.summary.ifBlank { "-" },
      "remote_input_last_count" to lastRemoteInputResult.inputCount,
      "remote_input_last_target_device_id" to lastRemoteInputResult.targetDeviceId.ifBlank { "-" },
    )
    val metricsMessage = controller.sessionMetricsReportMessage(sessionId, reason, metricsPayload)
    val metricsSent = socketClient.send(metricsMessage)
    if (metricsSent) {
      Log.i(RTC_TAG, "session_metrics_report_sent session=$sessionId reason=$reason")
    } else {
      Log.w(RTC_TAG, "session_metrics_report_send_failed session=$sessionId reason=$reason")
    }
  }

  private fun maybeSendLiveE2EProofReport(reason: String, force: Boolean = false): Boolean {
    val activeSessionId = sessionId?.trim().orEmpty()
    if (activeSessionId.isBlank() || !hasActiveSession()) {
      return false
    }
    val now = SystemClock.elapsedRealtime()
    val firstFrameMs = if (rtcFirstFrameAtMs > 0L && rtcSessionStartedAtMs > 0L) {
      rtcFirstFrameAtMs - rtcSessionStartedAtMs
    } else {
      -1L
    }
    val proofKey = listOf(
      activeSessionId,
      reason,
      if (firstFrameMs >= 0L || rtcRenderedFrameCount > 0L) "video:1" else "video:0",
      remoteInputResultCount.toString(),
      remoteInputResultAppliedCount.toString(),
      lastRemoteInputResult.inputTraceId,
      remoteInputCoverageSummary(),
    ).joinToString("|")
    if (!force && proofKey == lastLiveE2EProofReportKey) {
      return false
    }
    if (!force && now - lastLiveE2EProofReportAtMs < LIVE_E2E_PROOF_REPORT_MIN_INTERVAL_MS) {
      return false
    }
    logRtcSessionSummary(activeSessionId, reason)
    lastLiveE2EProofReportAtMs = now
    lastLiveE2EProofReportKey = proofKey
    return true
  }

  private fun averageMetric(sum: Double, count: Long): Double? {
    if (count <= 0L) {
      return null
    }
    return sum / count.toDouble()
  }

  private fun metricOrMinusOne(value: Double?): Double {
    if (value == null || !value.isFinite()) {
      return -1.0
    }
    return value
  }

  private fun resetRtcWatchdogState() {
    rtcSessionStartedAtMs = 0L
    rtcTrackAttachedAtMs = 0L
    rtcLastFrameRenderedAtMs = 0L
    rtcRecoveryAttempts = 0
    rtcLastRecoveryAtMs = 0L
  }

  private fun stopRtcWatchdog(resetState: Boolean = true) {
    rtcWatchdogToken += 1
    rtcWatchdogHandler.removeCallbacksAndMessages(null)
    if (resetState) {
      resetRtcWatchdogState()
    }
  }

  private fun shouldSuppressControllerRecovery(trigger: String): Boolean {
    if (rtcNegotiationOwner != RTC_NEGOTIATION_OWNER_REMOTE) {
      return false
    }
    return trigger.startsWith("watchdog_") || trigger.startsWith("policy_")
  }

  private fun triggerRtcWatchdogRecovery(
    currentSessionId: String,
    reason: String,
    waitedMs: Long,
    iceState: PeerConnection.IceConnectionState,
  ) {
    val trigger = "watchdog_${reason}_${rtcRecoveryAttempts + 1}"
    if (shouldSuppressControllerRecovery(trigger)) {
      rtcLastRecoveryAtMs = SystemClock.elapsedRealtime()
      if (shouldRequestRemoteIceRestart(reason, iceState)) {
        rtcRecoveryAttempts += 1
        Log.w(
          RTC_TAG,
          "watchdog_remote_restart_ice session=$currentSessionId reason=$reason waited_ms=$waitedMs ice_state=$iceState attempt=$rtcRecoveryAttempts local_ice_sent=$rtcLocalIceCandidateSentCount local_ice_callbacks=$rtcLocalIceCandidateCallbackCount",
        )
        sendSocketMessage(
          controller.webrtcRestartIceMessage(currentSessionId, trigger),
          "发送 webrtc.restart_ice",
        )
        appendLog("WebRTC watchdog 检测到 ICE 无本地候选，已请求远端重启 ICE")
        return
      }
      Log.w(
        RTC_TAG,
        "watchdog_recover_skipped session=$currentSessionId reason=$reason waited_ms=$waitedMs ice_state=$iceState owner=$rtcNegotiationOwner",
      )
      appendLog("WebRTC watchdog 检测到无帧，但当前由远端主导协商，跳过本端 offer 重协商")
      return
    }
    rtcRecoveryAttempts += 1
    rtcLastRecoveryAtMs = SystemClock.elapsedRealtime()
    rtcLastFrameRenderedAtMs = 0L
    val nextTrigger = "watchdog_${reason}_${rtcRecoveryAttempts}"
    Log.w(
      RTC_TAG,
      "watchdog_recover session=$currentSessionId reason=$reason waited_ms=$waitedMs ice_state=$iceState attempt=$rtcRecoveryAttempts",
    )
    appendLog("WebRTC watchdog 触发恢复 ${rtcRecoveryAttempts}/$RTC_WATCHDOG_MAX_RECOVERY_ATTEMPTS ($reason)")
    beginControllerWebRtcOffer(currentSessionId, trigger = nextTrigger)
  }

  private fun shouldRequestRemoteIceRestart(reason: String, iceState: PeerConnection.IceConnectionState): Boolean {
    if (iceState != PeerConnection.IceConnectionState.CHECKING) {
      return false
    }
    if (reason != "no_track" && reason != "track_no_frame") {
      return false
    }
    // 作者: long；远端主导协商时不抢 offer，但 0 本地候选会让双方一直没有回程路径，只能先请 Mac 端重启 ICE 重新发起 offer。
    return rtcLocalIceCandidateSentCount == 0L && rtcLocalIceCandidateCallbackCount == 0L
  }

  private fun maybeRecoverRtcIfStalled(currentSessionId: String, peerConnection: PeerConnection) {
    if (rtcRecoveryAttempts >= RTC_WATCHDOG_MAX_RECOVERY_ATTEMPTS) {
      return
    }
    val now = SystemClock.elapsedRealtime()
    if (rtcLastRecoveryAtMs > 0L && now - rtcLastRecoveryAtMs < RTC_WATCHDOG_RECOVERY_COOLDOWN_MS) {
      return
    }
    val iceState = peerConnection.iceConnectionState()
    maybeSampleRtcNetworkStats(currentSessionId, peerConnection, iceState)
    if (rtcRemoteVideoTrack == null) {
      val waitedMs = now - rtcSessionStartedAtMs
      if (rtcSessionStartedAtMs > 0L && waitedMs >= RTC_WATCHDOG_NO_TRACK_TIMEOUT_MS) {
        triggerRtcWatchdogRecovery(
          currentSessionId = currentSessionId,
          reason = "no_track",
          waitedMs = waitedMs,
          iceState = iceState,
        )
      }
      return
    }

    val shouldInspectFrames = iceState == PeerConnection.IceConnectionState.CONNECTED ||
      iceState == PeerConnection.IceConnectionState.COMPLETED ||
      iceState == PeerConnection.IceConnectionState.CHECKING
    if (!shouldInspectFrames) {
      return
    }

    if (rtcLastFrameRenderedAtMs <= 0L) {
      val waitedAnchor = if (rtcTrackAttachedAtMs > 0L) rtcTrackAttachedAtMs else rtcSessionStartedAtMs
      val waitedMs = now - waitedAnchor
      if (waitedAnchor > 0L && waitedMs >= RTC_WATCHDOG_NO_FRAME_TIMEOUT_MS) {
        triggerRtcWatchdogRecovery(
          currentSessionId = currentSessionId,
          reason = "track_no_frame",
          waitedMs = waitedMs,
          iceState = iceState,
        )
      }
      return
    }

    val stalledMs = now - rtcLastFrameRenderedAtMs
    if (stalledMs >= RTC_WATCHDOG_NO_FRAME_TIMEOUT_MS) {
      triggerRtcWatchdogRecovery(
        currentSessionId = currentSessionId,
        reason = "frame_stalled",
        waitedMs = stalledMs,
        iceState = iceState,
      )
    }
  }

  private fun maybeSampleRtcNetworkStats(
    currentSessionId: String,
    peerConnection: PeerConnection,
    iceState: PeerConnection.IceConnectionState,
  ) {
    val shouldSample = iceState == PeerConnection.IceConnectionState.CONNECTED ||
      iceState == PeerConnection.IceConnectionState.COMPLETED ||
      iceState == PeerConnection.IceConnectionState.CHECKING
    if (!shouldSample || rtcNetStatsRequestInFlight) {
      return
    }
    val now = SystemClock.elapsedRealtime()
    if (rtcLastNetStatsSampleAtMs > 0L && now - rtcLastNetStatsSampleAtMs < RTC_NET_STATS_SAMPLE_INTERVAL_MS) {
      return
    }
    rtcLastNetStatsSampleAtMs = now
    rtcNetStatsRequestInFlight = true
    peerConnection.getStats { report ->
      val deliveredAtMs = SystemClock.elapsedRealtime()
      try {
        if (!isActivityAlive || rtcCurrentSessionId != currentSessionId) {
          return@getStats
        }
        val snapshot = buildRtcNetworkStatsSummary(report, deliveredAtMs)
        Log.i(RTC_TAG, "net_stats session=$currentSessionId ${snapshot.summary}")
        maybeApplyIcePathPolicy(currentSessionId, snapshot, iceState)
        runOnUiThread {
          if (!isActivityAlive || rtcCurrentSessionId != currentSessionId) {
            return@runOnUiThread
          }
          updateLiveMetricsPanel()
        }
      } catch (error: Exception) {
        Log.w(RTC_TAG, "net_stats_failed session=$currentSessionId reason=${error.message ?: "unknown"}")
      } finally {
        rtcNetStatsRequestInFlight = false
      }
    }
  }

  private fun buildRtcNetworkStatsSummary(report: RTCStatsReport, sampleAtMs: Long): RtcNetworkStatsSnapshot {
    val statsMap = report.statsMap
    val inboundVideo = findInboundVideoStats(statsMap)
    val inboundMembers = inboundVideo?.members
    val bytesReceived = inboundMembers?.let { readMemberDouble(it, "bytesReceived") }
    val recvKbps = if (bytesReceived != null && bytesReceived >= 0.0) {
      val prevAtMs = rtcLastInboundBytesAtMs
      val prevBytes = rtcLastInboundBytesReceived
      rtcLastInboundBytesAtMs = sampleAtMs
      rtcLastInboundBytesReceived = bytesReceived
      if (prevAtMs > 0L && bytesReceived >= prevBytes && sampleAtMs > prevAtMs) {
        val deltaBytes = bytesReceived - prevBytes
        val deltaMs = sampleAtMs - prevAtMs
        deltaBytes * 8.0 / deltaMs.toDouble()
      } else {
        null
      }
    } else {
      null
    }
    val framesPerSecond = inboundMembers?.let { readMemberDouble(it, "framesPerSecond") }
    val framesDecoded = inboundMembers?.let { readMemberLong(it, "framesDecoded") }
    val framesDropped = inboundMembers?.let { readMemberLong(it, "framesDropped") }
    val packetsLost = inboundMembers?.let { readMemberLong(it, "packetsLost") }
    val jitterMs = inboundMembers?.let { readMemberDouble(it, "jitter") }?.times(1000.0)
    if (framesDropped != null && framesDropped >= 0L) {
      var droppedSpike = 0L
      rtcFramesDroppedLast = framesDropped
      if (rtcLastFramesDroppedValue >= 0L && framesDropped >= rtcLastFramesDroppedValue) {
        droppedSpike = framesDropped - rtcLastFramesDroppedValue
        if (droppedSpike > rtcFramesDroppedSpikeMax) {
          rtcFramesDroppedSpikeMax = droppedSpike
        }
        if (droppedSpike >= RTC_QUALITY_DROPPED_FRAME_SPIKE_THRESHOLD) {
          Log.w(
            RTC_TAG,
            "frames_dropped_spike session=${rtcCurrentSessionId ?: "-"} spike=$droppedSpike max_spike=$rtcFramesDroppedSpikeMax total=$framesDropped",
          )
        }
      }
      rtcLastFramesDroppedValue = framesDropped
      recordRtcDroppedFrameSample(framesDropped, droppedSpike)
    }

    val selectedPair = findSelectedCandidatePair(statsMap)
    val pairMembers = selectedPair?.members
    val rttMs = pairMembers?.let { readMemberDouble(it, "currentRoundTripTime") }?.times(1000.0)
    val availableIncomingKbps = pairMembers?.let { readMemberDouble(it, "availableIncomingBitrate") }?.div(1000.0)
    val localCandidateId = pairMembers?.let { readMemberString(it, "localCandidateId") }
    val remoteCandidateId = pairMembers?.let { readMemberString(it, "remoteCandidateId") }
    val localCandidate = localCandidateId?.let { statsMap[it] }
    val remoteCandidate = remoteCandidateId?.let { statsMap[it] }
    val localCandidateType = localCandidate?.members?.let { readMemberString(it, "candidateType") }
    val remoteCandidateType = remoteCandidate?.members?.let { readMemberString(it, "candidateType") }
    val localProtocol = localCandidate?.members?.let { readMemberString(it, "protocol") }
    val pairState = pairMembers?.let { readMemberString(it, "state") } ?: "-"
    val candidateTier = classifyIceCandidateTier(
      localCandidateType = localCandidateType,
      remoteCandidateType = remoteCandidateType,
      protocol = localProtocol,
      pairState = pairState,
      rttMs = rttMs,
    )
    val candidatePath = listOfNotNull(localCandidateType, remoteCandidateType, localProtocol)
      .takeIf { it.isNotEmpty() }
      ?.joinToString("/")
      ?: "-"
    if (recvKbps != null) {
      rtcNetRecvKbpsSampleSum += recvKbps
      rtcNetRecvKbpsSampleCount += 1
    }
    if (rttMs != null) {
      rtcNetRttMsSampleSum += rttMs
      rtcNetRttMsSampleCount += 1
    }
    if (candidatePath != "-") {
      rtcLastCandidatePair = candidatePath
    }
    if (pairState != "-") {
      rtcLastPairState = pairState
    }
    if (candidateTier != "-") {
      rtcLastCandidateTier = candidateTier
    }
    rtcLastDecodedFps = framesPerSecond
    rtcLastRecvKbps = recvKbps
    rtcLastRttMs = rttMs

    val summary = buildString {
      append("recv_kbps=${formatMetric(recvKbps)} ")
      append("fps_decoded=${formatMetric(framesPerSecond)} ")
      append("frames_decoded=${framesDecoded ?: "-"} ")
      append("frames_dropped=${framesDropped ?: "-"} ")
      append("frames_dropped_spike_max=$rtcFramesDroppedSpikeMax ")
      append("packets_lost=${packetsLost ?: "-"} ")
      append("jitter_ms=${formatMetric(jitterMs)} ")
      append("rtt_ms=${formatMetric(rttMs)} ")
      append("avail_in_kbps=${formatMetric(availableIncomingKbps)} ")
      append("candidate_pair=$candidatePath ")
      append("candidate_tier=$candidateTier ")
      append("pair_state=$pairState")
    }
    return RtcNetworkStatsSnapshot(
      summary = summary,
      candidatePath = candidatePath,
      candidateTier = candidateTier,
      pairState = pairState,
      rttMs = rttMs,
    )
  }

  private fun maybeApplyIcePathPolicy(
    currentSessionId: String,
    snapshot: RtcNetworkStatsSnapshot,
    iceState: PeerConnection.IceConnectionState,
  ) {
    val shouldInspectPath = iceState == PeerConnection.IceConnectionState.CONNECTED ||
      iceState == PeerConnection.IceConnectionState.COMPLETED
    if (!shouldInspectPath) {
      rtcIcePolicyDegradeStreak = 0
      return
    }

    val degradedPath = when (snapshot.candidateTier) {
      "relay_tcp", "p2p_tcp" -> true
      "relay_udp_high_rtt" -> true
      "relay_udp" -> snapshot.rttMs != null && snapshot.rttMs >= rtcIcePolicyRelayUdpHighRttMs
      else -> false
    }
    rtcIcePolicyDegradeStreak = if (degradedPath) rtcIcePolicyDegradeStreak + 1 else 0
    if (!degradedPath) {
      return
    }

    val now = SystemClock.elapsedRealtime()
    if (rtcIcePolicyRecoveryAttempts >= RTC_ICE_POLICY_MAX_RECOVERY_ATTEMPTS) {
      return
    }
    if (rtcIcePolicyLastActionAtMs > 0L && now - rtcIcePolicyLastActionAtMs < RTC_ICE_POLICY_RECOVERY_COOLDOWN_MS) {
      return
    }
    if (rtcIcePolicyDegradeStreak < RTC_ICE_POLICY_DEGRADE_STREAK_THRESHOLD) {
      return
    }

    val attempt = rtcIcePolicyRecoveryAttempts + 1
    val trigger = "policy_candidate_path_$attempt"
    if (shouldSuppressControllerRecovery(trigger)) {
      rtcIcePolicyLastActionAtMs = now
      rtcLastRecoveryAtMs = now
      Log.w(
        RTC_TAG,
        "ice_policy_recover_skipped session=$currentSessionId trigger=$trigger owner=$rtcNegotiationOwner tier=${snapshot.candidateTier} pair=${snapshot.candidatePath}",
      )
      appendLog("ICE 路径降级(${snapshot.candidateTier})，当前由远端主导协商，跳过本端策略重协商")
      rtcIcePolicyDegradeStreak = 0
      return
    }

    rtcIcePolicyRecoveryAttempts = attempt
    rtcIcePolicyLastActionAtMs = now
    rtcLastRecoveryAtMs = now
    Log.w(
      RTC_TAG,
      "ice_policy_recover session=$currentSessionId tier=${snapshot.candidateTier} pair=${snapshot.candidatePath} rtt_ms=${formatMetric(snapshot.rttMs)} streak=$rtcIcePolicyDegradeStreak attempt=$rtcIcePolicyRecoveryAttempts",
    )
    appendLog("ICE 路径降级(${snapshot.candidateTier})，触发第 $rtcIcePolicyRecoveryAttempts 次策略重协商")
    beginControllerWebRtcOffer(currentSessionId, trigger = trigger)
    rtcIcePolicyDegradeStreak = 0
  }

  private fun classifyIceCandidateTier(
    localCandidateType: String?,
    remoteCandidateType: String?,
    protocol: String?,
    pairState: String?,
    rttMs: Double?,
  ): String {
    val localType = localCandidateType.orEmpty().lowercase(Locale.US)
    val remoteType = remoteCandidateType.orEmpty().lowercase(Locale.US)
    val candidateProtocol = protocol.orEmpty().lowercase(Locale.US)
    val selectedState = pairState.orEmpty().lowercase(Locale.US)
    val hasRelay = localType == "relay" || remoteType == "relay"
    if (selectedState.isNotBlank() && selectedState != "succeeded") {
      return "probing"
    }
    return when {
      candidateProtocol == "udp" && hasRelay -> {
        if (rttMs != null && rttMs >= rtcIcePolicyRelayUdpHighRttMs) "relay_udp_high_rtt" else "relay_udp"
      }
      candidateProtocol == "udp" -> "p2p_udp"
      candidateProtocol == "tcp" && hasRelay -> "relay_tcp"
      candidateProtocol == "tcp" -> "p2p_tcp"
      hasRelay -> "relay_other"
      localType.isNotBlank() || remoteType.isNotBlank() -> "p2p_other"
      else -> "-"
    }
  }

  private fun findInboundVideoStats(statsMap: Map<String, RTCStats>): RTCStats? {
    var fallback: RTCStats? = null
    for (stats in statsMap.values) {
      if (stats.type != "inbound-rtp") {
        continue
      }
      if (fallback == null) {
        fallback = stats
      }
      val members = stats.members
      val mediaKind = readMemberString(members, "kind")
        ?: readMemberString(members, "mediaType")
      if (mediaKind.equals("video", ignoreCase = true)) {
        return stats
      }
      if (stats.id.contains("video", ignoreCase = true)) {
        return stats
      }
    }
    return fallback
  }

  private fun findSelectedCandidatePair(statsMap: Map<String, RTCStats>): RTCStats? {
    var fallback: RTCStats? = null
    for (stats in statsMap.values) {
      if (stats.type != "candidate-pair") {
        continue
      }
      val members = stats.members
      val selected = readMemberBoolean(members, "selected")
      val nominated = readMemberBoolean(members, "nominated")
      val state = readMemberString(members, "state")
      if (selected == true) {
        return stats
      }
      if (fallback == null && nominated == true && state.equals("succeeded", ignoreCase = true)) {
        fallback = stats
      }
    }
    return fallback
  }

  private fun readMemberString(members: Map<String, Any>, key: String): String? {
    val value = members[key] ?: return null
    return when (value) {
      is String -> value.trim().takeIf { it.isNotEmpty() }
      is Number -> value.toString()
      is Boolean -> if (value) "true" else "false"
      else -> value.toString().trim().takeIf { it.isNotEmpty() }
    }
  }

  private fun readMemberDouble(members: Map<String, Any>, key: String): Double? {
    val value = members[key] ?: return null
    return when (value) {
      is Number -> value.toDouble()
      is String -> value.toDoubleOrNull()
      is Boolean -> if (value) 1.0 else 0.0
      else -> null
    }
  }

  private fun readMemberLong(members: Map<String, Any>, key: String): Long? {
    return readMemberDouble(members, key)?.toLong()
  }

  private fun readMemberBoolean(members: Map<String, Any>, key: String): Boolean? {
    val value = members[key] ?: return null
    return when (value) {
      is Boolean -> value
      is Number -> value.toInt() != 0
      is String -> value.equals("true", ignoreCase = true) || value == "1"
      else -> null
    }
  }

  private fun formatMetric(value: Double?): String {
    if (value == null || !value.isFinite()) {
      return "-"
    }
    return "%.2f".format(Locale.US, value)
  }

  private fun formatMetricOrDash(value: Double?, digits: Int = 1): String {
    if (value == null || !value.isFinite() || value < 0.0) {
      return "-"
    }
    return "%.${digits}f".format(Locale.US, value)
  }

  private fun inferRtcQualityHintCode(
    renderFpsAvg: Double?,
    recvKbpsAvg: Double?,
    candidateTier: String,
    rttMs: Double?,
    frameGapMs: Long = rtcLongestFrameGapMs,
    lowFpsStreakMs: Long = rtcLongestLowFpsStreakMs,
    droppedFrameSpikeMax: Long = rtcFramesDroppedSpikeMax,
  ): String {
    if (!hasActiveSession()) {
      return "-"
    }
    if (rtcRemoteVideoTrack == null) {
      return "waiting_track"
    }
    if (rtcFirstFrameAtMs <= 0L) {
      return "waiting_first_frame"
    }
    val tier = candidateTier.lowercase(Locale.US)
    if (tier == "relay_tcp" || tier == "p2p_tcp" || tier == "relay_udp_high_rtt") {
      return "path_$tier"
    }
    if (rttMs != null && rttMs.isFinite() && rttMs >= RTC_QUALITY_RTT_HIGH_MS) {
      return "rtt_high"
    }
    if (frameGapMs >= RTC_QUALITY_FRAME_GAP_SPIKE_MS) {
      return "render_frame_stutter"
    }
    if (lowFpsStreakMs >= RTC_QUALITY_LOW_FPS_STREAK_THRESHOLD_MS) {
      return "render_fps_streak"
    }
    if (droppedFrameSpikeMax >= RTC_QUALITY_DROPPED_FRAME_SPIKE_THRESHOLD) {
      return "frames_dropped_spike"
    }
    if (renderFpsAvg != null && renderFpsAvg.isFinite() && renderFpsAvg >= 0.0 && renderFpsAvg < RTC_QUALITY_FPS_LOW_THRESHOLD) {
      return "render_fps_low"
    }
    val likelyStall = renderFpsAvg != null && renderFpsAvg.isFinite() && renderFpsAvg >= 0.0 && renderFpsAvg < RTC_QUALITY_STALL_FPS_THRESHOLD
    if (likelyStall && recvKbpsAvg != null && recvKbpsAvg.isFinite() && recvKbpsAvg >= 0.0 && recvKbpsAvg < RTC_QUALITY_BITRATE_LOW_KBPS) {
      return "recv_bitrate_low"
    }
    return "stable"
  }

  private fun displayRtcQualityHint(qualityHintCode: String, candidateTier: String): String {
    return when (qualityHintCode) {
      "-" -> "-"
      "waiting_track" -> "等待轨道"
      "waiting_first_frame" -> "等待首帧"
      "rtt_high" -> "RTT偏高"
      "render_frame_stutter" -> "帧间隔卡顿"
      "render_fps_streak" -> "连续低FPS"
      "frames_dropped_spike" -> "掉帧尖峰"
      "render_fps_low" -> "渲染FPS偏低"
      "recv_bitrate_low" -> "接收码率偏低"
      "stable" -> "稳定"
      else -> if (qualityHintCode.startsWith("path_")) {
        "链路受限($candidateTier)"
      } else {
        qualityHintCode
      }
    }
  }

  private fun updateLiveMetricsPanel() {
    val firstFrameMs = if (rtcFirstFrameAtMs > 0L && rtcSessionStartedAtMs > 0L) {
      rtcFirstFrameAtMs - rtcSessionStartedAtMs
    } else {
      null
    }
    val renderFpsAvg = averageMetric(rtcRenderFpsSampleSum, rtcRenderFpsSampleCount)
    val recvKbpsAvg = averageMetric(rtcNetRecvKbpsSampleSum, rtcNetRecvKbpsSampleCount)
    val recentRenderQuality = currentRecentRenderQuality()
    val recentDropSpikeMax = recentFramesDroppedSpikeMax()
    val qualityHintCode = inferRtcQualityHintCode(
      renderFpsAvg = renderFpsAvg,
      recvKbpsAvg = recvKbpsAvg,
      candidateTier = rtcLastCandidateTier,
      rttMs = rtcLastRttMs,
    )
    val recentQualityHintCode = inferRtcQualityHintCode(
      renderFpsAvg = recentRenderQuality.fpsAvg,
      recvKbpsAvg = rtcLastRecvKbps ?: recvKbpsAvg,
      candidateTier = rtcLastCandidateTier,
      rttMs = rtcLastRttMs,
      frameGapMs = recentRenderQuality.maxFrameGapMs,
      lowFpsStreakMs = recentRenderQuality.lowFpsStreakMs,
      droppedFrameSpikeMax = recentDropSpikeMax,
    )
    val qualityHint = displayRtcQualityHint(qualityHintCode, rtcLastCandidateTier)
    val recentQualityHint = displayRtcQualityHint(recentQualityHintCode, rtcLastCandidateTier)
    val candidateLabel = if (rtcLastCandidatePair != "-" || rtcLastCandidateTier != "-") {
      "${rtcLastCandidatePair} / ${rtcLastCandidateTier}"
    } else {
      "-"
    }
    binding.liveMetricsText.text = buildString {
      append("实时指标：\n")
      append("首帧：")
      append(firstFrameMs?.let { "${it}ms" } ?: "-")
      append('\n')
      append("渲染FPS：avg ${formatMetricOrDash(renderFpsAvg)} / latest ${formatMetricOrDash(rtcLastDecodedFps)}")
      append('\n')
      append("最近窗口：avg ${formatMetricOrDash(recentRenderQuality.fpsAvg)} / ${recentRenderQuality.sampleCount}样本 / gap ${recentRenderQuality.maxFrameGapMs}ms")
      append('\n')
      append("接收码率：avg ${formatMetricOrDash(recvKbpsAvg)}kbps / latest ${formatMetricOrDash(rtcLastRecvKbps)}kbps")
      append('\n')
      append("卡顿追踪：gap ${rtcLongestFrameGapMs}ms / low ${rtcLongestLowFpsStreakMs}ms / drop ${rtcFramesDroppedSpikeMax} / recent drop ${recentDropSpikeMax}")
      append('\n')
      append("发送FPS/码率：由 mac 端上报")
      append('\n')
      append("候选路径/分级：")
      append(candidateLabel)
      append('\n')
      append("质量判定：")
      append(qualityHint)
      append(" / 最近 ")
      append(recentQualityHint)
      append(" (RTT ${formatMetricOrDash(rtcLastRttMs)}ms)")
    }
    updateSessionTimingUi()
  }

  private fun startRtcWatchdog(session: String) {
    stopRtcWatchdog(resetState = true)
    rtcSessionStartedAtMs = SystemClock.elapsedRealtime()
    val token = rtcWatchdogToken + 1
    rtcWatchdogToken = token
    val task = object : Runnable {
      override fun run() {
        if (!isActivityAlive || rtcWatchdogToken != token) {
          return
        }
        val currentSessionId = sessionId
        if (currentSessionId.isNullOrBlank() || currentSessionId != session) {
          return
        }
        val peerConnection = rtcPeerConnection
        if (peerConnection != null) {
          maybeRecoverRtcIfStalled(currentSessionId, peerConnection)
        }
        rtcWatchdogHandler.postDelayed(this, RTC_WATCHDOG_INTERVAL_MS)
      }
    }
    rtcWatchdogHandler.postDelayed(task, RTC_WATCHDOG_INTERVAL_MS)
  }

  private fun recordRemoteInputResult(payload: JSONObject?, json: JSONObject): RemoteInputResult {
    val from = json.optJSONObject("from")
    val inputType = payload.optNonBlank("input_type") ?: "input"
    val summary = payload.optNonBlank("summary") ?: ""
    val inputCategory = normalizeRemoteInputCategory(payload.optNonBlank("input_category"), inputType, summary)
    val result = RemoteInputResult(
      inputType = inputType,
      inputCategory = inputCategory,
      inputTraceId = payload.optNonBlank("input_trace_id") ?: "",
      applied = payload?.optBoolean("applied", false) ?: false,
      executor = payload.optNonBlank("executor") ?: "",
      statusCode = payload.optNonBlank("status_code") ?: "",
      statusDetail = payload.optNonBlank("status_detail") ?: "",
      errorCode = payload.optNonBlank("error_code") ?: "",
      errorDetail = payload.optNonBlank("error_detail") ?: "",
      summary = summary,
      inputCount = payload?.optLong("input_count", 0L) ?: 0L,
      targetDeviceId = from.optNonBlank("device_id") ?: "",
    )
    lastRemoteInputResult = result
    remoteInputResultCount += 1
    if (result.applied) {
      remoteInputResultAppliedCount += 1
      if (result.inputCategory.isNotBlank()) {
        remoteInputAppliedCategories.add(result.inputCategory)
      }
    } else {
      remoteInputResultFailedCount += 1
    }
    return result
  }

  private fun normalizeRemoteInputCategory(value: String?, inputType: String = "", summary: String = ""): String {
    return when (value?.trim()?.lowercase(Locale.US)) {
      "tap", "pointer", "click" -> "click"
      "drag" -> "drag"
      "key", "keyboard" -> "keyboard"
      "scroll", "wheel" -> "wheel"
      else -> when (inputType.trim().lowercase(Locale.US)) {
        "input.keyboard.key" -> "keyboard"
        "input.wheel.scroll" -> "wheel"
        "input.mouse.button" -> "click"
        else -> {
          val lowerSummary = summary.lowercase(Locale.US)
          when {
            lowerSummary.contains("drag") -> "drag"
            lowerSummary.contains("wheel") || lowerSummary.contains("scroll") -> "wheel"
            lowerSummary.contains("keyboard") || lowerSummary.contains("key") -> "keyboard"
            else -> ""
          }
        }
      }
    }
  }

  private fun remoteInputCoverageSummary(): String = listOf("click", "drag", "keyboard", "wheel")
    .filter { remoteInputAppliedCategories.contains(it) }
    .joinToString(",")

  private fun remoteInputCoverageComplete(): Boolean = listOf("click", "drag", "keyboard", "wheel")
    .all { remoteInputAppliedCategories.contains(it) }

  private fun createPeerConnection(sessionId: String): PeerConnection? {
    val factory = rtcFactory ?: return null
    closeWebRtcSession(reason = "recreate_pc")
    rtcCurrentSessionId = sessionId
    val config = PeerConnection.RTCConfiguration(
      if (rtcIceServers.isNotEmpty()) rtcIceServers else listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
      ),
    )
    config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
    config.candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
    config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    config.iceCandidatePoolSize = 1
    config.enableIceGatheringOnAnyAddressPorts = true
    config.iceTransportsType = if (rtcIceTransportRelayOnly) {
      PeerConnection.IceTransportsType.RELAY
    } else {
      PeerConnection.IceTransportsType.ALL
    }
    Log.i(
      RTC_TAG,
      "create_pc session=$sessionId profile=$rtcControllerProfile ice_transport=${config.iceTransportsType} candidate_network=${config.candidateNetworkPolicy} continual=${config.continualGatheringPolicy} pool=${config.iceCandidatePoolSize} any_addr_ports=${config.enableIceGatheringOnAnyAddressPorts} ice_servers=${config.iceServers.map { server -> server.urls.toString() }}",
    )
    val observer = object : PeerConnection.Observer {
      override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
      override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
        Log.i(RTC_TAG, "ice_connection_state=$newState session=$sessionId")
      }
      override fun onIceConnectionReceivingChange(receiving: Boolean) {
        Log.i(RTC_TAG, "ice_connection_receiving=$receiving session=$sessionId")
      }
      override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
        val localSdp = rtcPeerConnection?.localDescription?.description.orEmpty()
        val localCandidateLines = countSdpCandidateLines(localSdp)
        Log.i(
          RTC_TAG,
          "ice_gathering_state=$newState session=$sessionId local_candidate_lines=$localCandidateLines local_sdp_len=${localSdp.length} has_ufrag=${localSdp.contains("a=ice-ufrag:")} has_pwd=${localSdp.contains("a=ice-pwd:")} has_video=${localSdp.contains("m=video")}",
        )
        if (newState == PeerConnection.IceGatheringState.COMPLETE) {
          sendLocalIceCandidatesFromLocalDescription(sessionId, "gathering_complete")
        }
      }
      override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
      override fun onAddStream(stream: org.webrtc.MediaStream) {
        val remoteVideo = stream.videoTracks.firstOrNull() ?: return
        Log.i(RTC_TAG, "onAddStream video_track_id=${remoteVideo.id()}")
        attachRemoteVideoTrack(remoteVideo, source = "onAddStream")
      }
      override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
      override fun onDataChannel(dataChannel: org.webrtc.DataChannel) = Unit
      override fun onRenegotiationNeeded() = Unit
      override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) {
        val remoteVideo = receiver.track() as? VideoTrack ?: return
        Log.i(RTC_TAG, "onAddTrack video_track_id=${remoteVideo.id()}")
        attachRemoteVideoTrack(remoteVideo, source = "onAddTrack")
      }

      override fun onIceCandidate(candidate: IceCandidate) {
        val current = rtcCurrentSessionId ?: return
        rtcLocalIceCandidateCallbackCount += 1
        Log.i(
          RTC_TAG,
          "local_ice_candidate_callback session=$current count=$rtcLocalIceCandidateCallbackCount mid=${candidate.sdpMid ?: "-"} mline=${candidate.sdpMLineIndex} type=${extractIceCandidateType(candidate.sdp)}",
        )
        sendLocalIceCandidate(
          sessionId = current,
          candidateText = candidate.sdp,
          sdpMid = candidate.sdpMid,
          sdpMLineIndex = candidate.sdpMLineIndex,
          source = "callback",
        )
      }

      override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
        val track = transceiver.receiver.track() as? VideoTrack ?: return
        Log.i(RTC_TAG, "onTrack video_track_id=${track.id()}")
        attachRemoteVideoTrack(track, source = "onTrack")
      }
    }
    rtcPeerConnection = factory.createPeerConnection(config, observer)
    return rtcPeerConnection
  }

  private fun attachRemoteVideoTrack(track: VideoTrack, source: String) {
    rtcRemoteVideoTrack?.removeSink(binding.remoteVideoView)
    rtcRemoteVideoTrack?.removeSink(rtcProbeSink)
    rtcRemoteVideoTrack = track
    rtcTrackAttachedAtMs = SystemClock.elapsedRealtime()
    resetRtcRenderStats()
    track.addSink(binding.remoteVideoView)
    track.addSink(rtcProbeSink)
    runOnUiThread {
      if (!isActivityAlive) {
        return@runOnUiThread
      }
      binding.remoteFrameView.isVisible = false
      appendLog("WebRTC 视频轨道已就绪（$source）")
    }
  }

  private fun ensureControllerReceiveVideoTransceiver(peerConnection: PeerConnection) {
    val hasVideoTransceiver = peerConnection.transceivers.any { transceiver ->
      transceiver.receiver.track()?.kind() == "video"
    }
    if (hasVideoTransceiver) {
      return
    }
    try {
      val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
      peerConnection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init)
    } catch (error: Exception) {
      appendLog("添加接收视频 transceiver 失败：${error.message ?: "unknown"}")
    }
  }

  private fun flushPendingRemoteIceCandidates() {
    val peerConnection = rtcPeerConnection ?: return
    if (peerConnection.remoteDescription == null) {
      return
    }
    if (rtcPendingRemoteCandidates.isNotEmpty()) {
      Log.i(
        RTC_TAG,
        "flush_pending_remote_ice session=${rtcCurrentSessionId ?: "-"} pending=${rtcPendingRemoteCandidates.size}",
      )
    }
    while (rtcPendingRemoteCandidates.isNotEmpty()) {
      val candidate = rtcPendingRemoteCandidates.removeFirst()
      Log.i(
        RTC_TAG,
        "apply_cached_remote_ice session=${rtcCurrentSessionId ?: "-"} mid=${candidate.sdpMid ?: "-"} mline=${candidate.sdpMLineIndex} type=${extractIceCandidateType(candidate.sdp)}",
      )
      val added = peerConnection.addIceCandidate(candidate)
      if (!added) {
        appendLog("应用缓存 ICE candidate 失败")
      }
    }
  }

  private fun sendLocalIceCandidate(
    sessionId: String,
    candidateText: String,
    sdpMid: String?,
    sdpMLineIndex: Int,
    source: String,
  ): Boolean {
    val normalizedCandidate = candidateText.trim().removePrefix("a=")
    if (normalizedCandidate.isBlank()) {
      Log.w(RTC_TAG, "local_ice_candidate_skip session=$sessionId source=$source reason=empty")
      return false
    }
    val normalizedMid = sdpMid?.trim()?.takeIf { it.isNotEmpty() } ?: "0"
    val normalizedMLineIndex = sdpMLineIndex.takeIf { it >= 0 } ?: 0
    val key = "$normalizedMid|$normalizedMLineIndex|$normalizedCandidate"
    if (!rtcSentLocalIceCandidateKeys.add(key)) {
      Log.i(
        RTC_TAG,
        "local_ice_candidate_skip session=$sessionId source=$source reason=duplicate mid=$normalizedMid mline=$normalizedMLineIndex type=${extractIceCandidateType(normalizedCandidate)}",
      )
      return false
    }
    val candidateType = extractIceCandidateType(normalizedCandidate)
    Log.i(
      RTC_TAG,
      "local_ice_candidate_send session=$sessionId source=$source mid=$normalizedMid mline=$normalizedMLineIndex type=$candidateType",
    )
    val sent = sendSocketMessage(
      controller.webrtcIceCandidateMessage(
        sessionId = sessionId,
        candidate = normalizedCandidate,
        sdpMid = normalizedMid,
        sdpMLineIndex = normalizedMLineIndex,
      ),
      "发送 webrtc.ice_candidate",
    )
    if (sent) {
      rtcLocalIceCandidateSentCount += 1
      if (source != "callback") {
        rtcLocalIceCandidateFallbackCount += 1
      }
    } else {
      rtcSentLocalIceCandidateKeys.remove(key)
    }
    return sent
  }

  // 作者: long；真机上偶发只在最终 SDP 里出现本地候选，补发这些候选可以避免 Mac 端一直拿不到 Android 回程路径。
  private fun sendLocalIceCandidatesFromLocalDescription(sessionId: String, source: String) {
    val localSdp = rtcPeerConnection?.localDescription?.description.orEmpty()
    val candidates = extractSdpIceCandidates(localSdp)
    rtcLocalIceCandidateSdpCount = candidates.size.toLong()
    if (candidates.isEmpty()) {
      Log.w(
        RTC_TAG,
        "local_ice_candidate_fallback_empty session=$sessionId source=$source local_sdp_len=${localSdp.length}",
      )
      return
    }
    var sent = 0
    for (candidate in candidates) {
      if (sendLocalIceCandidate(
          sessionId = sessionId,
          candidateText = candidate.candidate,
          sdpMid = candidate.sdpMid,
          sdpMLineIndex = candidate.sdpMLineIndex,
          source = source,
        )
      ) {
        sent += 1
      }
    }
    Log.i(
      RTC_TAG,
      "local_ice_candidate_fallback_done session=$sessionId source=$source sdp_candidates=${candidates.size} sent=$sent total_sent=$rtcLocalIceCandidateSentCount",
    )
  }

  private fun beginControllerWebRtcOffer(messageSessionId: String, trigger: String) {
    if (shouldSuppressControllerRecovery(trigger)) {
      Log.w(
        RTC_TAG,
        "controller_offer_suppressed session=$messageSessionId trigger=$trigger owner=$rtcNegotiationOwner",
      )
      return
    }
    rtcNegotiationOwner = RTC_NEGOTIATION_OWNER_CONTROLLER
    val peerConnection = rtcPeerConnection ?: createPeerConnection(messageSessionId)
    if (peerConnection == null) {
      appendLog("创建 PeerConnection 失败")
      return
    }
    ensureControllerReceiveVideoTransceiver(peerConnection)
    val shouldIceRestart =
      trigger == "restart_ice" ||
      trigger.startsWith("watchdog_") ||
      trigger.startsWith("policy_")
    val constraints = MediaConstraints().apply {
      mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
      mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
      if (shouldIceRestart) {
        mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
      }
    }
    peerConnection.createOffer(SimpleSdpObserver(
      onCreateSuccess = { offer ->
        if (offer == null || offer.description.isNullOrBlank()) {
          appendLog("创建 webrtc.offer 失败：offer 为空")
          return@SimpleSdpObserver
        }
        peerConnection.setLocalDescription(SimpleSdpObserver(
          onSetSuccess = {
            sendSocketMessage(
              controller.webrtcOfferMessage(messageSessionId, offer.description),
              "发送 webrtc.offer ($trigger)",
            )
          },
          onSetFailure = { reason ->
            appendLog("设置本地 offer 失败：$reason")
          },
        ), offer)
      },
      onCreateFailure = { reason ->
        appendLog("创建 webrtc.offer 失败：$reason")
      },
    ), constraints)
  }

  private fun applyIceServersFromSession(payload: JSONObject?) {
    val parsed = mutableListOf<PeerConnection.IceServer>()
    val webrtc = payload?.optJSONObject("webrtc")
    rtcControllerProfile = normalizeControllerProfile(webrtc?.optString("controller_profile").orEmpty())
      .ifBlank { if (isLikelyEmulator()) "emulator" else "android_phone" }
    val physicalController = rtcControllerProfile == "android_phone"
    val servers = webrtc?.optJSONArray("ice_servers")
    if (servers != null) {
      for (index in 0 until servers.length()) {
        val server = servers.optJSONObject(index) ?: continue
        val urlsJson = server.optJSONArray("urls")
        val urls = mutableListOf<String>()
        if (urlsJson != null) {
          for (urlIndex in 0 until urlsJson.length()) {
            val url = urlsJson.optString(urlIndex).orEmpty().trim()
            if (url.isNotEmpty()) {
              urls += url
            }
          }
        } else {
          val single = server.optString("urls").orEmpty().trim()
          if (single.isNotEmpty()) {
            urls += single
          }
        }
        val filteredUrls = urls
          .map { it.trim() }
          .filter { it.isNotEmpty() }
          .filterNot { physicalController && isUnsupportedPhysicalIceUrl(it) }
        val orderedUrls = orderIceUrls(filteredUrls, physicalController)
        if (urls.size != orderedUrls.size) {
          Log.i(
            RTC_TAG,
            "ice_server_url_filter profile=$rtcControllerProfile original=${urls.size} kept=${orderedUrls.size} removed=${urls.size - orderedUrls.size}",
          )
        }
        if (orderedUrls.isEmpty()) {
          continue
        }
        val username = server.optString("username").orEmpty().trim()
        val credential = server.optString("credential").orEmpty().trim()
        for (url in orderedUrls) {
          val builder = PeerConnection.IceServer.builder(url)
          if (username.isNotEmpty()) {
            builder.setUsername(username)
          }
          if (credential.isNotEmpty()) {
            builder.setPassword(credential)
          }
          parsed += builder.createIceServer()
        }
      }
    }
    rtcIceServers = if (parsed.isNotEmpty()) {
      parsed
    } else {
      listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
    }
    Log.i(
      RTC_TAG,
      "ice_servers_applied profile=$rtcControllerProfile count=${rtcIceServers.size} urls=${rtcIceServers.flatMap { server -> server.urls }}",
    )
  }

  private fun applyIcePolicyFromSession(payload: JSONObject?) {
    rtcIceTransportRelayOnly = false
    rtcIcePolicyRelayUdpHighRttMs = RTC_ICE_POLICY_RELAY_UDP_HIGH_RTT_MS
    val policy = payload
      ?.optJSONObject("webrtc")
      ?.optJSONObject("ice_policy")
      ?: run {
        Log.i(
          RTC_TAG,
          "ice_policy_mode mode=- relay_only=$rtcIceTransportRelayOnly",
        )
        return
      }
    val mode = policy.optString("mode").trim().lowercase(Locale.US)
    rtcIceTransportRelayOnly = mode.startsWith("relay")
    Log.i(
      RTC_TAG,
      "ice_policy_mode mode=${if (mode.isBlank()) "-" else mode} relay_only=$rtcIceTransportRelayOnly",
    )
    val relayUdpHighRttMs = readJsonDouble(policy, "relay_udp_high_rtt_ms")
    if (relayUdpHighRttMs != null && relayUdpHighRttMs >= 0.0) {
      rtcIcePolicyRelayUdpHighRttMs = relayUdpHighRttMs
      Log.i(
        RTC_TAG,
        "ice_policy_applied relay_udp_high_rtt_ms=${formatMetric(relayUdpHighRttMs)}",
      )
    }
  }

  private fun readJsonDouble(obj: JSONObject, key: String): Double? {
    if (!obj.has(key) || obj.isNull(key)) {
      return null
    }
    val raw = obj.opt(key)
    return when (raw) {
      is Number -> raw.toDouble()
      is String -> raw.toDoubleOrNull()
      else -> null
    }
  }

  private fun normalizeControllerProfile(profile: String): String = when (profile.trim().lowercase(Locale.US)) {
    "emulator" -> "emulator"
    "android_phone" -> "android_phone"
    "standard" -> "standard"
    else -> ""
  }

  private fun orderIceUrls(urls: List<String>, physicalController: Boolean): List<String> {
    if (urls.isEmpty()) {
      return urls
    }
    return urls
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .distinct()
      .sortedWith(
        compareBy<String> { url -> iceUrlPriority(url, physicalController) }
          .thenBy { url -> url.length },
      )
  }

  private fun iceUrlPriority(url: String, physicalController: Boolean): Int {
    val normalized = url.lowercase(Locale.US)
    if (physicalController && isLoopbackIceUrl(normalized) && normalized.contains("transport=tcp")) {
      return 4
    }
    return when {
      normalized.startsWith("stun:") -> 0
      normalized.startsWith("turn:") && normalized.contains("transport=udp") -> 1
      normalized.startsWith("turn:") && normalized.contains("transport=tcp") -> 3
      normalized.startsWith("turn:") -> 2
      else -> 4
    }
  }

  private fun isUnsupportedPhysicalIceUrl(url: String): Boolean {
    val normalized = url.lowercase(Locale.US)
    if (!normalized.startsWith("turn:")) {
      return false
    }
    val host = iceUrlHost(normalized)
    if (host == "10.0.2.2") {
      return true
    }
    if (isLoopbackIceHost(host)) {
      return !normalized.contains("transport=tcp")
    }
    return false
  }

  private fun isLoopbackIceUrl(url: String): Boolean = isLoopbackIceHost(iceUrlHost(url))

  private fun isLoopbackIceHost(host: String): Boolean {
    val normalized = host.trim().lowercase(Locale.US).removePrefix("[").removeSuffix("]")
    return normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1"
  }

  private fun iceUrlHost(url: String): String {
    val withoutScheme = url.substringAfter(':', "")
    val authority = withoutScheme.substringBefore('?').substringBefore('/')
    if (authority.startsWith("[")) {
      return authority.substringAfter('[').substringBefore(']').lowercase(Locale.US)
    }
    return authority.substringBeforeLast(':', authority).lowercase(Locale.US)
  }

  private fun handleWebRtcOffer(messageSessionId: String, payload: JSONObject?) {
    val sdpType = payload?.optString("sdp_type").orEmpty()
    if (sdpType.isNotBlank() && sdpType != "offer") {
      appendLog("忽略非法 sdp_type 的 webrtc.offer: $sdpType")
      return
    }
    val sdp = payload?.optString("sdp").orEmpty()
    Log.i(RTC_TAG, "recv_offer session=$messageSessionId sdp_len=${sdp.length} has_video=${sdp.contains("m=video")}")
    if (sdp.isBlank()) {
      appendLog("忽略缺少 SDP 的 webrtc.offer")
      return
    }
    if (rtcNegotiationOwner != RTC_NEGOTIATION_OWNER_CONTROLLER) {
      rtcNegotiationOwner = RTC_NEGOTIATION_OWNER_REMOTE
      Log.i(RTC_TAG, "negotiation_owner session=$messageSessionId owner=$rtcNegotiationOwner reason=remote_offer")
    }
    val peerConnection = rtcPeerConnection ?: createPeerConnection(messageSessionId)
    if (peerConnection == null) {
      appendLog("创建 PeerConnection 失败")
      return
    }
    val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
    peerConnection.setRemoteDescription(SimpleSdpObserver(
      onSetSuccess = {
        flushPendingRemoteIceCandidates()
        peerConnection.createAnswer(SimpleSdpObserver(onCreateSuccess = { answer ->
          if (answer == null) {
            appendLog("创建 webrtc.answer 失败：answer 为空")
            return@SimpleSdpObserver
          }
          peerConnection.setLocalDescription(SimpleSdpObserver(
            onSetSuccess = {
              val localSdp = peerConnection.localDescription?.description.orEmpty()
              Log.i(
                RTC_TAG,
                "set_local_answer_ok session=$messageSessionId sdp_len=${localSdp.length} candidate_lines=${countSdpCandidateLines(localSdp)} has_ufrag=${localSdp.contains("a=ice-ufrag:")} has_pwd=${localSdp.contains("a=ice-pwd:")} has_video=${localSdp.contains("m=video")}",
              )
              sendSocketMessage(
                controller.webrtcAnswerMessage(messageSessionId, localSdp.ifBlank { answer.description }),
                "发送 webrtc.answer",
              )
            },
            onSetFailure = { reason ->
              appendLog("设置本地 answer 失败：$reason")
            },
          ), answer)
        }, onCreateFailure = { reason ->
          appendLog("创建 webrtc.answer 失败：$reason")
        }), MediaConstraints())
      },
      onSetFailure = { reason ->
        appendLog("设置远端 offer 失败：$reason")
      },
    ), offer)
  }

  private fun handleWebRtcAnswer(messageSessionId: String, payload: JSONObject?) {
    if (rtcCurrentSessionId != messageSessionId) {
      return
    }
    val sdpType = payload?.optString("sdp_type").orEmpty()
    if (sdpType.isNotBlank() && sdpType != "answer") {
      appendLog("忽略非法 sdp_type 的 webrtc.answer: $sdpType")
      return
    }
    val sdp = payload?.optString("sdp").orEmpty()
    Log.i(RTC_TAG, "recv_answer session=$messageSessionId sdp_len=${sdp.length} has_video=${sdp.contains("m=video")}")
    if (sdp.isBlank()) {
      appendLog("忽略缺少 SDP 的 webrtc.answer")
      return
    }
    val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
    rtcPeerConnection?.setRemoteDescription(SimpleSdpObserver(
      onSetSuccess = {
        flushPendingRemoteIceCandidates()
      },
      onSetFailure = { reason ->
        appendLog("设置远端 answer 失败：$reason")
      },
    ), answer)
  }

  private fun handleWebRtcIceCandidate(messageSessionId: String, payload: JSONObject?) {
    if (rtcCurrentSessionId != messageSessionId) {
      return
    }
    val candidate = payload?.optString("candidate").orEmpty()
    if (candidate.isBlank()) {
      appendLog("忽略缺少 candidate 的 webrtc.ice_candidate")
      return
    }
    val sdpMid = payload?.optString("sdp_mid").orEmpty().ifBlank { "0" }
    val rawSdpMLineIndex = payload?.optInt("sdp_mline_index") ?: 0
    val sdpMLineIndex = rawSdpMLineIndex.takeIf { it >= 0 } ?: 0
    Log.i(
      RTC_TAG,
      "recv_remote_ice session=$messageSessionId mid=$sdpMid mline=$sdpMLineIndex type=${extractIceCandidateType(candidate)}",
    )
    val parsed = IceCandidate(sdpMid, sdpMLineIndex, candidate)
    val peerConnection = rtcPeerConnection
    if (peerConnection == null || peerConnection.remoteDescription == null) {
      rtcPendingRemoteCandidates += parsed
      Log.i(
        RTC_TAG,
        "queue_remote_ice session=$messageSessionId pending=${rtcPendingRemoteCandidates.size}",
      )
      return
    }
    val added = peerConnection.addIceCandidate(parsed)
    Log.i(
      RTC_TAG,
      "apply_remote_ice session=$messageSessionId applied=$added pending=${rtcPendingRemoteCandidates.size}",
    )
    if (!added) {
      appendLog("应用远端 ICE candidate 失败")
    }
  }

  private fun parseMessage(text: String) {
    try {
      val json = JSONObject(text)
      Log.i(RTC_TAG, "recv_signal ${summarizeEnvelope(json, text.length)}")
      val payload = json.optJSONObject("payload")
      when (json.optString("type")) {
        "device.register.rsp" -> {
          token = payload.optNonBlank("token") ?: token
          isRegistered = true
          isPresenceReady = false
          binding.tokenText.text = "访问凭证：$token"
          if (sessionId.isNullOrBlank()) {
            setStatus("已注册")
          }
          updateSessionButtonState()
          appendLog("收到 device.register.rsp，访问凭证已更新")
          if (!hasActiveSession()) {
            if (!sendSocketMessage(controller.heartbeatMessage(token, sessionId), "发送 presence.heartbeat.req")) {
              appendLog("自动心跳失败：中继信令未连接")
            } else {
              setStatus("同步在线状态")
              updateSessionButtonState()
            }
          }
        }
        "presence.heartbeat.rsp" -> {
          val deviceStatus = payload.optNonBlank("device_status") ?: "unknown"
          isPresenceReady = true
          setStatus(
            if (hasActiveSession()) {
              "会话中"
            } else {
              readyStatusText("已连接（$deviceStatus）")
            },
          )
          updateSessionButtonState()
          appendLog("心跳成功，状态=$deviceStatus")
          maybeScheduleReconnectSessionRestore(source = "presence_ready")
          maybeScheduleAutoRequestSession()
        }
        "device.presence.push" -> {
          applyDevicesPresencePush(payload ?: JSONObject())
        }
        "session.request.result.push" -> {
          val requestId = payload.optNonBlank("request_id")
          val pendingRequestId = pendingSessionRequestId
          if (pendingRequestId.isNullOrBlank()) {
            appendLog("忽略无待处理请求的会话结果 ${requestId ?: "unknown"}")
            return
          }
          if (!requestId.isNullOrBlank() && requestId != pendingRequestId) {
            appendLog("忽略非当前请求结果 ${requestId}")
            return
          }
          val result = payload.optNonBlank("result") ?: "unknown"
          val wasRecoveryRequest = pendingSessionRequestIsRecovery
          pendingSessionRequest = false
          pendingSessionRequestId = null
          pendingSessionRequestIsRecovery = false
          sessionId = if (result == "approved") {
            payload.optNonBlank("session_id")
          } else {
            null
          }
          updateSessionText()
          updateSessionButtonState()
          if (result != "approved") {
            if (wasRecoveryRequest && reconnectSessionRestoreAttempt < RELAY_SESSION_RECOVERY_MAX_ATTEMPTS) {
              isRecoveringSession = true
              setStatus("短断线恢复待重试")
              maybeScheduleReconnectSessionRestore(source = "session_request_$result")
            } else {
              clearSessionRecoveryIntent("session_request_$result")
              setStatus(readyStatusText("会话未建立"))
            }
          }
          appendLog("会话请求结果：$result${sessionId?.let { " session=$it" } ?: ""}")
        }
        "session.start.push" -> {
          pendingSessionRequest = false
          pendingSessionRequestId = null
          pendingSessionRequestIsRecovery = false
          clearSessionRecoveryIntent("session_started")
          sessionEndRequestedByUser = false
          frameGeneration += 1
          sessionId = payload.optNonBlank("session_id") ?: json.optNonBlank("session_id")
          sessionStartedAtWallClockMs = System.currentTimeMillis()
          val controllerDeviceId = payload.optNonBlank("controller_device_id") ?: "-"
          val agentDeviceId = payload.optNonBlank("agent_device_id") ?: "-"
          activeSessionPeerDeviceId = when (deviceId) {
            controllerDeviceId -> agentDeviceId.takeIf { it.isNotBlank() && it != "-" }
            agentDeviceId -> controllerDeviceId.takeIf { it.isNotBlank() && it != "-" }
            else -> null
          }
          activeSessionPeerDeviceId?.let { peerId ->
            if (binding.targetDeviceInput.text.toString().trim() != peerId) {
              binding.targetDeviceInput.setText(peerId)
              binding.targetDeviceInput.setSelection(binding.targetDeviceInput.text?.length ?: 0)
              persistConnectionSettings(
                wsUrl = normalizeWsUrl(binding.wsUrlInput.text.toString()),
                targetDeviceId = peerId,
              )
            }
          }
          sessionId?.let { current ->
            rtcNegotiationOwner = RTC_NEGOTIATION_OWNER_UNKNOWN
            applyIceServersFromSession(payload)
            applyIcePolicyFromSession(payload)
            createPeerConnection(current)
            resetRemoteInputResultStats()
            resetLiveE2EProofReportState()
            startRtcWatchdog(current)
          }
          updateSessionText()
          startSessionClockTicker(sessionId)
          updateSessionButtonState()
          binding.transportText.text = "传输方式：${payload?.optJSONObject("transport").optNonBlank("mode") ?: "-"}"
          binding.peerText.text = "会话链路：$controllerDeviceId → $agentDeviceId"
          binding.ackText.text = "输入回执：-"
          binding.frameMetaText.text = "当前画面：等待视频轨道"
          updateLiveMetricsPanel()
          renderedFrameWidth = 0
          renderedFrameHeight = 0
          resetLegacyFrameStats()
          resetLegacyFailureThrottleState()
          binding.remoteFrameView.setImageDrawable(null)
          binding.remoteFrameView.isVisible = false
          binding.remoteVideoView.isVisible = true
          setStatus("会话中")
          appendLog("进入会话 ${sessionId ?: "unknown"}")
          maybeScheduleAutoProofInput(sessionId)
        }
        "screen.frame.push" -> {
          val messageSessionId = json.optNonBlank("session_id") ?: payload?.optNonBlank("session_id")
          val currentSessionId = sessionId
          if (currentSessionId.isNullOrBlank() || messageSessionId.isNullOrBlank() || messageSessionId != currentSessionId) {
            return
          }
          if (!shouldHandleLegacyFrameStream(currentSessionId)) {
            return
          }
          val frameId = payload?.optNonBlank("frame_id") ?: "frame-${System.currentTimeMillis()}"
          val contentB64 = payload?.optNonBlank("content_b64")
          val frameWidth = payload?.optInt("frame_width") ?: 0
          val frameHeight = payload?.optInt("frame_height") ?: 0
          val captureTs = payload?.optLong("capture_ts")?.takeIf { it > 0L } ?: System.currentTimeMillis()
          binding.remoteVideoView.isVisible = false
          binding.remoteFrameView.isVisible = true
          handleIncomingFrame(
            frameId = frameId,
            contentB64 = contentB64,
            announcedWidth = frameWidth,
            announcedHeight = frameHeight,
            captureTs = captureTs,
            generation = frameGeneration,
          )
        }
        "webrtc.offer" -> {
          val messageSessionId = json.optNonBlank("session_id") ?: payload.optNonBlank("session_id")
          val currentSessionId = sessionId
          if (currentSessionId.isNullOrBlank() || messageSessionId.isNullOrBlank() || messageSessionId != currentSessionId) {
            appendLog("忽略非当前会话 webrtc.offer")
            return
          }
          appendLog("收到 webrtc.offer")
          handleWebRtcOffer(messageSessionId, payload)
        }
        "webrtc.answer" -> {
          val messageSessionId = json.optNonBlank("session_id") ?: payload.optNonBlank("session_id")
          val currentSessionId = sessionId
          if (currentSessionId.isNullOrBlank() || messageSessionId.isNullOrBlank() || messageSessionId != currentSessionId) {
            appendLog("忽略非当前会话 webrtc.answer")
            return
          }
          appendLog("收到 webrtc.answer")
          handleWebRtcAnswer(messageSessionId, payload)
        }
        "webrtc.ice_candidate" -> {
          val messageSessionId = json.optNonBlank("session_id") ?: payload.optNonBlank("session_id")
          val currentSessionId = sessionId
          if (currentSessionId.isNullOrBlank() || messageSessionId.isNullOrBlank() || messageSessionId != currentSessionId) {
            appendLog("忽略非当前会话 webrtc.ice_candidate")
            return
          }
          handleWebRtcIceCandidate(messageSessionId, payload)
        }
        "webrtc.restart_ice" -> {
          val messageSessionId = json.optNonBlank("session_id") ?: payload.optNonBlank("session_id")
          val currentSessionId = sessionId
          if (currentSessionId.isNullOrBlank() || messageSessionId.isNullOrBlank() || messageSessionId != currentSessionId) {
            appendLog("忽略非当前会话 webrtc.restart_ice")
            return
          }
          appendLog("收到 webrtc.restart_ice")
          beginControllerWebRtcOffer(currentSessionId, trigger = "restart_ice")
        }
        "input.ack" -> {
          val messageSessionId = json.optNonBlank("session_id") ?: payload.optNonBlank("session_id")
          val currentSessionId = sessionId
          if (currentSessionId.isNullOrBlank() || messageSessionId.isNullOrBlank() || messageSessionId != currentSessionId) {
            appendLog("忽略非当前会话输入确认 ${payload.optNonBlank("echo_type") ?: "input"}")
            return
          }
          val echoType = payload.optNonBlank("echo_type") ?: "input"
          binding.ackText.text = "输入回执：$echoType 已确认"
          appendLog("收到 $echoType 已确认")
        }
        "input.result.push" -> {
          val messageSessionId = json.optNonBlank("session_id") ?: payload.optNonBlank("session_id")
          val currentSessionId = sessionId
          if (currentSessionId.isNullOrBlank() || messageSessionId.isNullOrBlank() || messageSessionId != currentSessionId) {
            appendLog("忽略非当前会话输入执行结果")
            return
          }
          val result = recordRemoteInputResult(payload, json)
          maybeSendLiveE2EProofReport(
            reason = if (result.applied) "live_controller_input_applied" else "live_controller_input_result",
            force = true,
          )
          val inputType = result.inputType
          val applied = result.applied
          val statusCode = payload.optNonBlank("error_code")
            ?: payload.optNonBlank("status_code")
            ?: "-"
          val executor = result.executor.ifBlank { "-" }
          val summary = result.summary.ifBlank { inputType }
          val resultLabel = if (applied) "目标已执行" else "目标未执行"
          binding.ackText.text = "输入回执：$resultLabel $inputType [$statusCode/$executor]"
          appendLog("收到目标端输入执行结果：$resultLabel $summary [$statusCode/$executor]")
        }
        "session.end.push" -> {
          val reason = payload.optNonBlank("reason") ?: "unknown"
          if (reason.contains("offline", ignoreCase = true)) {
            val maybeOfflineId = payload.optNonBlank("agent_device_id")
              ?: payload.optNonBlank("target_device_id")
              ?: binding.targetDeviceInput.text.toString().trim()
            if (maybeOfflineId.isNotBlank()) {
              val recoveryStarted = handleTargetDeviceOffline(maybeOfflineId, source = "session.end.push")
              if (recoveryStarted) {
                appendLog("会话结束：$reason，已进入短断线恢复")
                return
              }
            }
          }
          pendingSessionRequest = false
          pendingSessionRequestId = null
          pendingSessionRequestIsRecovery = false
          isPresenceReady = true
          clearSessionRecoveryIntent("session_end_$reason")
          resetSessionUi(clearFrame = true)
          setStatus(readyStatusText("会话已结束"))
          updateSessionButtonState()
          appendLog("会话结束：$reason")
        }
        "error.rsp" -> {
          val wasRecoveryRequest = pendingSessionRequestIsRecovery
          pendingSessionRequest = false
          pendingSessionRequestId = null
          pendingSessionRequestIsRecovery = false
          val name = payload.optNonBlank("name") ?: "UNKNOWN"
          val message = payload.optNonBlank("message") ?: "unknown error"
          when (name) {
            "AUTH_INVALID_TOKEN" -> {
              isRegistered = false
              isPresenceReady = false
              token = "stub-token"
              binding.tokenText.text = "访问凭证：未注册"
              clearSessionRecoveryIntent("auth_invalid_token")
              resetSessionUi(clearFrame = true)
              setStatus("鉴权失效，请重新注册")
            }
            "DEVICE_OFFLINE", "DEVICE_NOT_FOUND" -> {
              val maybeOfflineId = if (wasRecoveryRequest) {
                resolveRecoveryOfflineTarget(payload)
              } else {
                payload.optNonBlank("agent_device_id")
                  ?: payload.optNonBlank("target_device_id")
                  ?: payload.optNonBlank("device_id")
                  ?: binding.targetDeviceInput.text.toString().trim()
              }
              if (wasRecoveryRequest && maybeOfflineId.isNotBlank()) {
                reconnectTargetDeviceId = maybeOfflineId
                isRecoveringSession = true
                setStatus("短断线恢复等待目标上线")
                appendLog("短断线恢复：目标暂时离线，继续等待 $maybeOfflineId")
                refreshDevicesList(force = true)
                scheduleSessionRecoveryTargetWait(source = "device_offline", targetDeviceId = maybeOfflineId)
              } else if (maybeOfflineId.isNotBlank()) {
                handleTargetDeviceOffline(maybeOfflineId, source = "error.rsp")
              } else if (sessionId.isNullOrBlank()) {
                setStatus(readyStatusText("目标设备离线"))
              }
            }
            "SESSION_NOT_FOUND", "INPUT_NOT_ALLOWED" -> {
              isPresenceReady = true
              clearSessionRecoveryIntent("error_$name")
              resetSessionUi(clearFrame = true)
              setStatus(readyStatusText("会话不可用"))
            }
          }
          updateSessionButtonState()
          appendLog("错误：$name - $message")
          if (name == "DEVICE_OFFLINE" || name == "DEVICE_NOT_FOUND" || name == "AUTH_INVALID_TOKEN") {
            refreshDevicesList(force = true)
          }
        }
        else -> {
          val type = json.optString("type").ifBlank { "unknown" }
          appendLog("收到 $type")
        }
      }
    } catch (error: Exception) {
      appendLog("解析消息失败：${error.message ?: "unknown"}")
    }
  }

  private fun shouldHandleLegacyFrameStream(activeSessionId: String): Boolean {
    if (rtcRemoteVideoTrack == null || !binding.remoteVideoView.isVisible) {
      return true
    }
    val now = SystemClock.elapsedRealtime()
    if (now - lastLegacyFrameIgnoredLogAtMs >= LEGACY_FRAME_IGNORED_LOG_INTERVAL_MS) {
      lastLegacyFrameIgnoredLogAtMs = now
      Log.i(
        RTC_TAG,
        "legacy_frame_ignored session=$activeSessionId reason=webrtc_track_active",
      )
    }
    return false
  }

  private fun handleIncomingFrame(
    frameId: String,
    contentB64: String?,
    announcedWidth: Int,
    announcedHeight: Int,
    captureTs: Long,
    generation: Int,
  ) {
    if (!isActivityAlive || generation != frameGeneration) {
      return
    }
    if (contentB64.isNullOrBlank()) {
      showFrameDecodeFailure("屏幕帧 $frameId 缺少 content_b64")
      return
    }
    if (announcedWidth <= 0 || announcedHeight <= 0 || announcedWidth > MAX_FRAME_DIMENSION || announcedHeight > MAX_FRAME_DIMENSION) {
      showFrameDecodeFailure("屏幕帧 $frameId 尺寸非法：${formatFrameSize(announcedWidth, announcedHeight)}")
      return
    }
    if (contentB64.length > MAX_FRAME_B64_LENGTH) {
      showFrameDecodeFailure("屏幕帧 $frameId 过大，已拒绝解码")
      return
    }

    frameDecodeExecutor.execute {
      val decodedFrame = decodeFrameBitmap(contentB64)
      runOnUiThread {
        if (!isActivityAlive || generation != frameGeneration) {
          return@runOnUiThread
        }
        val activeSessionId = sessionId ?: "-"
        if (!shouldHandleLegacyFrameStream(activeSessionId)) {
          return@runOnUiThread
        }
        val bitmap = decodedFrame.bitmap
        if (bitmap == null) {
          showFrameDecodeFailure(decodedFrame.error ?: "屏幕帧 $frameId 解码失败")
          return@runOnUiThread
        }
        binding.remoteFrameView.setImageBitmap(bitmap)
        val frameWidth = decodedFrame.width.takeIf { it > 0 } ?: announcedWidth
        val frameHeight = decodedFrame.height.takeIf { it > 0 } ?: announcedHeight
        renderedFrameWidth = frameWidth
        renderedFrameHeight = frameHeight
        legacyDecodeFailureSuppressedCount = 0
        val now = SystemClock.elapsedRealtime()
        legacyFrameCount += 1
        if (legacyFirstFrameAtMs <= 0L) {
          legacyFirstFrameAtMs = now
          Log.i(
            RTC_TAG,
            "legacy_first_frame session=$activeSessionId frame_id=$frameId size=${formatFrameSize(frameWidth, frameHeight)}",
          )
          appendLog("收到 legacy 首帧 $frameId")
        }
        if (legacyFrameSampleAtMs <= 0L) {
          legacyFrameSampleAtMs = now
          legacyFrameSampleFrameCount = legacyFrameCount
        } else {
          val sampleElapsedMs = now - legacyFrameSampleAtMs
          if (sampleElapsedMs >= RTC_RENDER_LOG_SAMPLE_INTERVAL_MS) {
            val sampleFrames = legacyFrameCount - legacyFrameSampleFrameCount
            val sampleFps = if (sampleElapsedMs > 0L) {
              sampleFrames * 1000.0 / sampleElapsedMs.toDouble()
            } else {
              0.0
            }
            Log.i(
              RTC_TAG,
              "legacy_frame_sample session=$activeSessionId frames_total=$legacyFrameCount sample_frames=$sampleFrames sample_ms=$sampleElapsedMs fps=${"%.2f".format(Locale.US, sampleFps)} size=${formatFrameSize(frameWidth, frameHeight)}",
            )
            legacyFrameSampleAtMs = now
            legacyFrameSampleFrameCount = legacyFrameCount
          }
        }
        if (now - lastLegacyFrameUiUpdateAtMs >= RTC_STATS_UI_UPDATE_INTERVAL_MS) {
          lastLegacyFrameUiUpdateAtMs = now
          binding.frameMetaText.text = "当前画面：$frameId ${formatFrameSize(frameWidth, frameHeight)} @ ${formatTimestamp(captureTs)}"
          setStatus("接收远端画面")
        }
      }
    }
  }

  private fun extractIceCandidateType(candidate: String): String {
    val marker = " typ "
    val typIndex = candidate.indexOf(marker)
    if (typIndex < 0) {
      return "unknown"
    }
    val start = typIndex + marker.length
    if (start >= candidate.length) {
      return "unknown"
    }
    val end = candidate.indexOf(' ', start).takeIf { it > start } ?: candidate.length
    return candidate.substring(start, end)
  }

  private fun countSdpCandidateLines(sdp: String): Int {
    if (sdp.isBlank()) {
      return 0
    }
    return sdp.lineSequence().count { line ->
      line.startsWith("a=candidate:", ignoreCase = true)
    }
  }

  private fun extractSdpIceCandidates(sdp: String): List<SdpIceCandidateLine> {
    if (sdp.isBlank()) {
      return emptyList()
    }
    val candidates = mutableListOf<SdpIceCandidateLine>()
    var currentMLineIndex = -1
    var currentMid = ""
    for (rawLine in sdp.lineSequence()) {
      val line = rawLine.trim()
      when {
        line.startsWith("m=", ignoreCase = true) -> {
          currentMLineIndex += 1
          currentMid = ""
        }
        line.startsWith("a=mid:", ignoreCase = true) -> {
          currentMid = line.substringAfter(":", "").trim()
        }
        line.startsWith("a=candidate:", ignoreCase = true) -> {
          candidates += SdpIceCandidateLine(
            candidate = line.removePrefix("a="),
            sdpMid = currentMid.ifBlank { currentMLineIndex.takeIf { it >= 0 }?.toString() ?: "0" },
            sdpMLineIndex = currentMLineIndex.takeIf { it >= 0 } ?: 0,
          )
        }
      }
    }
    return candidates
  }

  private fun handleRemoteFrameTouchV2(imageView: View, event: MotionEvent): Boolean {
    remoteScaleGestureDetector.onTouchEvent(event)
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        remoteTouchDownX = event.x
        remoteTouchDownY = event.y
        remoteLastTouchX = event.x
        remoteLastTouchY = event.y
        remotePanMoved = false
        remoteSuppressTap = false
        remoteMouseButtonDown = false
        remoteScrollGestureActive = false
        remoteTouchDownPoint = mapTouchToFrame(imageView, event.x, event.y)
        remoteLastInputPoint = remoteTouchDownPoint
        remoteLastSentMovePoint = null
        remoteLastSentMoveAtMs = 0L
        remoteLastWheelAtMs = 0L
        return renderedFrameWidth > 0 && renderedFrameHeight > 0
      }

      MotionEvent.ACTION_POINTER_DOWN -> {
        remoteSuppressTap = true
        endRemoteMouseDrag()
        if (event.pointerCount >= 2) {
          remoteScrollGestureActive = true
          remoteScrollLastFocusX = averagePointerX(event)
          remoteScrollLastFocusY = averagePointerY(event)
        }
        return true
      }

      MotionEvent.ACTION_POINTER_UP -> {
        remoteSuppressTap = true
        endRemoteMouseDrag()
        remoteScrollGestureActive = event.pointerCount > 2
        if (remoteScrollGestureActive) {
          remoteScrollLastFocusX = averageRemainingPointerX(event, event.actionIndex)
          remoteScrollLastFocusY = averageRemainingPointerY(event, event.actionIndex)
        }
        return true
      }

      MotionEvent.ACTION_MOVE -> {
        if (event.pointerCount >= 2) {
          handleRemoteWheelGesture(event)
          remoteSuppressTap = true
          remoteLastTouchX = event.x
          remoteLastTouchY = event.y
          return true
        }

        val movedFromDown = abs(event.x - remoteTouchDownX) > remoteTouchSlopPx ||
          abs(event.y - remoteTouchDownY) > remoteTouchSlopPx
        if (event.pointerCount == 1 && remoteViewportScale > REMOTE_VIEWPORT_MIN_SCALE) {
          val dx = event.x - remoteLastTouchX
          val dy = event.y - remoteLastTouchY
          if (movedFromDown) {
            remotePanMoved = true
            remoteSuppressTap = true
            endRemoteMouseDrag()
          }
          if (remotePanMoved) {
            remoteViewportOffsetX = clampRemoteViewportOffsetX(remoteViewportOffsetX + dx, remoteViewportScale)
            remoteViewportOffsetY = clampRemoteViewportOffsetY(remoteViewportOffsetY + dy, remoteViewportScale)
            applyRemoteViewportTransform()
          }
          remoteLastTouchX = event.x
          remoteLastTouchY = event.y
          return true
        }

        val currentSessionId = sessionId
        val point = mapTouchToFrame(imageView, event.x, event.y)
        if (movedFromDown) {
          remoteSuppressTap = true
        }
        if (!currentSessionId.isNullOrBlank() && movedFromDown && point != null) {
          if (!remoteMouseButtonDown) {
            val startPoint = remoteTouchDownPoint ?: point
            sendRemoteMouseMove(currentSessionId, startPoint, logSuccess = false, force = true, inputCategory = "drag")
            remoteMouseButtonDown = sendRemoteMouseButton(
              currentSessionId,
              startPoint,
              "left",
              "down",
              logSuccess = false,
              inputCategory = "drag",
            )
          }
          if (remoteMouseButtonDown) {
            maybeSendRemoteMouseMove(currentSessionId, point)
          }
        }
        remoteLastInputPoint = point ?: remoteLastInputPoint
        remoteLastTouchX = event.x
        remoteLastTouchY = event.y
        return true
      }

      MotionEvent.ACTION_UP -> {
        imageView.performClick()
        val currentSessionId = sessionId
        val upPoint = mapTouchToFrame(imageView, event.x, event.y) ?: remoteLastInputPoint
        if (remoteMouseButtonDown) {
          if (!currentSessionId.isNullOrBlank() && upPoint != null) {
            sendRemoteMouseMove(currentSessionId, upPoint, logSuccess = false, force = true, inputCategory = "drag")
            sendRemoteMouseButton(currentSessionId, upPoint, "left", "up", logSuccess = false, inputCategory = "drag")
          }
          resetRemoteInputGestureState()
          return true
        }
        if (remotePanMoved || remoteSuppressTap || remoteScaleGestureDetector.isInProgress) {
          resetRemoteInputGestureState()
          return true
        }
        if (currentSessionId.isNullOrBlank()) {
          appendLog("No active session; tap was not sent")
          resetRemoteInputGestureState()
          return true
        }
        val point = upPoint
        if (point == null) {
          appendLog("Tap outside remote frame")
          resetRemoteInputGestureState()
          return true
        }
        sendPreviewTap(currentSessionId, point)
        resetRemoteInputGestureState()
        return true
      }

      MotionEvent.ACTION_CANCEL -> {
        endRemoteMouseDrag()
        resetRemoteInputGestureState()
        return true
      }
    }
    return false
  }

  private fun handleRemoteFrameTouch(imageView: View, event: MotionEvent): Boolean {
    remoteScaleGestureDetector.onTouchEvent(event)
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        remoteTouchDownX = event.x
        remoteTouchDownY = event.y
        remoteLastTouchX = event.x
        remoteLastTouchY = event.y
        remotePanMoved = false
        remoteSuppressTap = false
        return renderedFrameWidth > 0 && renderedFrameHeight > 0
      }

      MotionEvent.ACTION_POINTER_DOWN -> {
        remoteSuppressTap = true
        return true
      }

      MotionEvent.ACTION_MOVE -> {
        if (event.pointerCount == 1 && remoteViewportScale > REMOTE_VIEWPORT_MIN_SCALE) {
          val dx = event.x - remoteLastTouchX
          val dy = event.y - remoteLastTouchY
          val movedFromDown = abs(event.x - remoteTouchDownX) > remoteTouchSlopPx ||
            abs(event.y - remoteTouchDownY) > remoteTouchSlopPx
          if (movedFromDown) {
            remotePanMoved = true
            remoteSuppressTap = true
          }
          if (remotePanMoved) {
            remoteViewportOffsetX = clampRemoteViewportOffsetX(remoteViewportOffsetX + dx, remoteViewportScale)
            remoteViewportOffsetY = clampRemoteViewportOffsetY(remoteViewportOffsetY + dy, remoteViewportScale)
            applyRemoteViewportTransform()
          }
          remoteLastTouchX = event.x
          remoteLastTouchY = event.y
          return true
        }
      }

      MotionEvent.ACTION_UP -> {
        imageView.performClick()
        if (remotePanMoved || remoteSuppressTap || remoteScaleGestureDetector.isInProgress) {
          remotePanMoved = false
          remoteSuppressTap = false
          return true
        }
        val currentSessionId = sessionId
        if (currentSessionId.isNullOrBlank()) {
          appendLog("当前没有 session，点击画面不会发送输入")
          return true
        }
        val point = mapTouchToFrame(imageView, event.x, event.y)
        if (point == null) {
          appendLog("请点击远端画面区域")
          return true
        }
        sendPreviewTap(currentSessionId, point)
        return true
      }

      MotionEvent.ACTION_CANCEL -> {
        remotePanMoved = false
        remoteSuppressTap = false
        return true
      }
    }
    return false
  }

  private fun resetRemoteInputGestureState() {
    remotePanMoved = false
    remoteSuppressTap = false
    remoteMouseButtonDown = false
    remoteTouchDownPoint = null
    remoteLastInputPoint = null
    remoteLastSentMovePoint = null
    remoteLastSentMoveAtMs = 0L
    remoteScrollGestureActive = false
    remoteLastWheelAtMs = 0L
  }

  private fun releaseRemoteInputState(sendMouseUp: Boolean = true) {
    if (sendMouseUp && remoteMouseButtonDown) {
      val currentSessionId = sessionId
      val point = remoteLastInputPoint
      if (!currentSessionId.isNullOrBlank() && point != null) {
        sendRemoteMouseButton(currentSessionId, point, "left", "up", logSuccess = false, inputCategory = "drag")
      }
    }
    resetRemoteInputGestureState()
  }

  private fun endRemoteMouseDrag() {
    if (!remoteMouseButtonDown) {
      return
    }
    val currentSessionId = sessionId
    val point = remoteLastInputPoint
    if (!currentSessionId.isNullOrBlank() && point != null) {
      sendRemoteMouseButton(currentSessionId, point, "left", "up", logSuccess = false, inputCategory = "drag")
    }
    remoteMouseButtonDown = false
  }

  private fun maybeSendRemoteMouseMove(sessionId: String, point: NormalizedPoint): Boolean {
    val now = SystemClock.elapsedRealtime()
    val lastPoint = remoteLastSentMovePoint
    if (lastPoint != null) {
      val movedEnough = abs(point.x - lastPoint.x) >= REMOTE_POINTER_MOVE_MIN_DELTA ||
        abs(point.y - lastPoint.y) >= REMOTE_POINTER_MOVE_MIN_DELTA
      if (!movedEnough || now - remoteLastSentMoveAtMs < REMOTE_POINTER_MOVE_MIN_INTERVAL_MS) {
        return false
      }
    }
    return sendRemoteMouseMove(sessionId, point, logSuccess = false, force = true, inputCategory = "drag")
  }

  private fun sendRemoteMouseMove(
    sessionId: String,
    point: NormalizedPoint,
    logSuccess: Boolean,
    force: Boolean = false,
    inputCategory: String = "",
  ): Boolean {
    if (!force) {
      val lastPoint = remoteLastSentMovePoint
      if (lastPoint != null &&
        abs(point.x - lastPoint.x) < REMOTE_POINTER_MOVE_MIN_DELTA &&
        abs(point.y - lastPoint.y) < REMOTE_POINTER_MOVE_MIN_DELTA
      ) {
        return false
      }
    }
    val sent = sendSocketMessage(
      controller.inputMessage(sessionId, point.x, point.y, inputCategory),
      "remote drag -> input.mouse.move x=${formatCoordinate(point.x)} y=${formatCoordinate(point.y)}",
      logSuccess = logSuccess,
    )
    if (sent) {
      remoteLastSentMovePoint = point
      remoteLastSentMoveAtMs = SystemClock.elapsedRealtime()
      remoteLastInputPoint = point
    }
    return sent
  }

  private fun sendRemoteMouseButton(
    sessionId: String,
    point: NormalizedPoint,
    button: String,
    action: String,
    logSuccess: Boolean,
    inputCategory: String = "click",
  ): Boolean = sendSocketMessage(
    controller.inputButtonMessage(sessionId, point.x, point.y, button, action, inputCategory),
    "remote drag -> input.mouse.button $button $action",
    logSuccess = logSuccess,
  )

  private fun handleRemoteWheelGesture(event: MotionEvent) {
    val currentSessionId = sessionId
    val focusX = averagePointerX(event)
    val focusY = averagePointerY(event)
    if (!remoteScrollGestureActive) {
      remoteScrollGestureActive = true
      remoteScrollLastFocusX = focusX
      remoteScrollLastFocusY = focusY
      return
    }

    val now = SystemClock.elapsedRealtime()
    if (now - remoteLastWheelAtMs < REMOTE_WHEEL_MIN_INTERVAL_MS) {
      return
    }

    val deltaX = clampWheelDelta(((focusX - remoteScrollLastFocusX) * REMOTE_WHEEL_DELTA_PER_PIXEL).roundToInt())
    val deltaY = clampWheelDelta(((focusY - remoteScrollLastFocusY) * REMOTE_WHEEL_DELTA_PER_PIXEL).roundToInt())
    val filteredDeltaX = if (abs(deltaX) >= REMOTE_WHEEL_MIN_ABS_DELTA) deltaX else 0
    val filteredDeltaY = if (abs(deltaY) >= REMOTE_WHEEL_MIN_ABS_DELTA) deltaY else 0
    if (filteredDeltaX == 0 && filteredDeltaY == 0) {
      return
    }
    if (currentSessionId.isNullOrBlank()) {
      remoteScrollLastFocusX = focusX
      remoteScrollLastFocusY = focusY
      remoteLastWheelAtMs = now
      return
    }

    val sent = sendSocketMessage(
      controller.inputWheelMessage(currentSessionId, deltaX = filteredDeltaX, deltaY = filteredDeltaY),
      "remote wheel -> input.wheel.scroll dx=$filteredDeltaX dy=$filteredDeltaY",
      logSuccess = false,
    )
    if (sent) {
      remoteScrollLastFocusX = focusX
      remoteScrollLastFocusY = focusY
      remoteLastWheelAtMs = now
    }
  }

  private fun clampWheelDelta(delta: Int): Int =
    delta.coerceIn(-REMOTE_WHEEL_MAX_ABS_DELTA, REMOTE_WHEEL_MAX_ABS_DELTA)

  private fun averagePointerX(event: MotionEvent): Float {
    if (event.pointerCount <= 0) {
      return 0f
    }
    var sum = 0f
    for (index in 0 until event.pointerCount) {
      sum += event.getX(index)
    }
    return sum / event.pointerCount.toFloat()
  }

  private fun averagePointerY(event: MotionEvent): Float {
    if (event.pointerCount <= 0) {
      return 0f
    }
    var sum = 0f
    for (index in 0 until event.pointerCount) {
      sum += event.getY(index)
    }
    return sum / event.pointerCount.toFloat()
  }

  private fun averageRemainingPointerX(event: MotionEvent, liftedIndex: Int): Float {
    var sum = 0f
    var count = 0
    for (index in 0 until event.pointerCount) {
      if (index == liftedIndex) {
        continue
      }
      sum += event.getX(index)
      count += 1
    }
    return if (count > 0) sum / count.toFloat() else averagePointerX(event)
  }

  private fun averageRemainingPointerY(event: MotionEvent, liftedIndex: Int): Float {
    var sum = 0f
    var count = 0
    for (index in 0 until event.pointerCount) {
      if (index == liftedIndex) {
        continue
      }
      sum += event.getY(index)
      count += 1
    }
    return if (count > 0) sum / count.toFloat() else averagePointerY(event)
  }

  private fun mapTouchToFrame(imageView: View, touchX: Float, touchY: Float): NormalizedPoint? {
    val frameWidth = renderedFrameWidth
    val frameHeight = renderedFrameHeight
    if (frameWidth <= 0 || frameHeight <= 0) {
      return null
    }

    val contentWidth = imageView.width - imageView.paddingLeft - imageView.paddingRight
    val contentHeight = imageView.height - imageView.paddingTop - imageView.paddingBottom
    if (contentWidth <= 0 || contentHeight <= 0) {
      return null
    }

    val mappedTouchX = (touchX - remoteViewportOffsetX) / remoteViewportScale
    val mappedTouchY = (touchY - remoteViewportOffsetY) / remoteViewportScale
    val scale = min(
      contentWidth.toFloat() / frameWidth.toFloat(),
      contentHeight.toFloat() / frameHeight.toFloat(),
    )
    if (scale <= 0f) {
      return null
    }

    val displayedWidth = frameWidth * scale
    val displayedHeight = frameHeight * scale
    val left = imageView.paddingLeft + (contentWidth - displayedWidth) / 2f
    val top = imageView.paddingTop + (contentHeight - displayedHeight) / 2f
    val right = left + displayedWidth
    val bottom = top + displayedHeight
    if (mappedTouchX < left || mappedTouchX > right || mappedTouchY < top || mappedTouchY > bottom) {
      return null
    }

    return NormalizedPoint(
      x = ((mappedTouchX - left) / displayedWidth).coerceIn(0f, 1f).toDouble(),
      y = ((mappedTouchY - top) / displayedHeight).coerceIn(0f, 1f).toDouble(),
    )
  }

  private fun sendPreviewTap(sessionId: String, point: NormalizedPoint) {
    if (!sendSocketMessage(
        controller.inputMessage(sessionId, point.x, point.y, "click"),
        "点击画面 -> input.mouse.move x=${formatCoordinate(point.x)} y=${formatCoordinate(point.y)}",
      )) {
      return
    }
    if (!sendSocketMessage(
        controller.inputButtonMessage(sessionId, point.x, point.y, "left", "down", "click"),
        "点击画面 -> input.mouse.button left down",
      )) {
      return
    }
    sendSocketMessage(
      controller.inputButtonMessage(sessionId, point.x, point.y, "left", "up", "click"),
      "点击画面 -> input.mouse.button left up",
    )
  }

  private fun sendDragSample(sessionId: String) {
    val start = NormalizedPoint(0.36, 0.36)
    val mid = NormalizedPoint(0.52, 0.52)
    val end = NormalizedPoint(0.66, 0.62)
    sendRemoteMouseMove(sessionId, start, logSuccess = false, force = true, inputCategory = "drag")
    sendRemoteMouseButton(sessionId, start, "left", "down", logSuccess = true, inputCategory = "drag")
    sendRemoteMouseMove(sessionId, mid, logSuccess = false, force = true, inputCategory = "drag")
    sendRemoteMouseMove(sessionId, end, logSuccess = false, force = true, inputCategory = "drag")
    sendRemoteMouseButton(sessionId, end, "left", "up", logSuccess = true, inputCategory = "drag")
  }

  private fun sendE2EProofInputSequence(sessionId: String) {
    appendLog("Sending E2E proof input sequence: click / drag / keyboard / wheel")
    sendPreviewTap(sessionId, NormalizedPoint(0.5, 0.5))
    sendDragSample(sessionId)
    sendKeyboardSample(sessionId)
    sendScrollSample(sessionId)
    maybeSendLiveE2EProofReport("live_controller_e2e_proof_sequence", force = true)
  }

  private fun sendKeyboardSample(sessionId: String) {
    if (!sendSocketMessage(
        controller.inputKeyboardMessage(sessionId, "KeyA", "down"),
        "发送 input.keyboard.key KeyA down",
      )) {
      return
    }
    sendSocketMessage(
      controller.inputKeyboardMessage(sessionId, "KeyA", "up"),
      "发送 input.keyboard.key KeyA up",
    )
  }

  private fun initializeRemoteKeyboardControls() {
    binding.remoteKeyboardSendButton.setOnClickListener {
      val text = binding.remoteKeyboardInput.text?.toString().orEmpty()
      if (sendRemoteKeyboardText(text)) {
        binding.remoteKeyboardInput.setText("")
      }
    }
    binding.remoteKeyboardEnterButton.setOnClickListener {
      sendRemoteKeyPress("Enter", emptyList(), "回车")
    }
    binding.remoteKeyboardBackspaceButton.setOnClickListener {
      sendRemoteKeyPress("Backspace", emptyList(), "退格")
    }
    binding.remoteKeyboardInput.setOnEditorActionListener { _, actionId, _ ->
      if (actionId == EditorInfo.IME_ACTION_SEND) {
        val text = binding.remoteKeyboardInput.text?.toString().orEmpty()
        if (sendRemoteKeyboardText(text)) {
          binding.remoteKeyboardInput.setText("")
        }
        true
      } else {
        false
      }
    }
  }

  private fun sendRemoteKeyboardText(text: String): Boolean {
    val currentSessionId = sessionId
    if (currentSessionId.isNullOrBlank()) {
      appendLog("当前没有 session，不能发送远端键盘文本")
      return false
    }
    val limitedText = text.take(REMOTE_KEYBOARD_MAX_BATCH_CHARS)
    if (limitedText.isEmpty()) {
      appendLog("请输入要发送到远端的文本")
      return false
    }

    var sentCount = 0
    var unsupportedCount = 0
    for (character in limitedText) {
      val remoteKey = remoteKeyForChar(character)
      if (remoteKey == null) {
        unsupportedCount += 1
        continue
      }
      if (sendRemoteKeyPress(currentSessionId, remoteKey.keyCode, remoteKey.modifiers, logLabel = null)) {
        sentCount += 1
      }
    }
    if (text.length > limitedText.length) {
      appendLog("远端键盘文本已截断到 $REMOTE_KEYBOARD_MAX_BATCH_CHARS 个字符")
    }
    if (unsupportedCount > 0) {
      appendLog("远端键盘暂不支持 $unsupportedCount 个字符；请使用英文、数字和常见符号")
    }
    if (sentCount > 0) {
      appendLog("已发送远端键盘文本 $sentCount 个按键")
      return true
    }
    appendLog("没有可发送的远端键盘按键")
    return false
  }

  private fun sendRemoteKeyPress(keyCode: String, modifiers: List<String>, logLabel: String): Boolean {
    val currentSessionId = sessionId
    if (currentSessionId.isNullOrBlank()) {
      appendLog("当前没有 session，不能发送远端键盘按键")
      return false
    }
    return sendRemoteKeyPress(currentSessionId, keyCode, modifiers, logLabel)
  }

  private fun sendRemoteKeyPress(
    sessionId: String,
    keyCode: String,
    modifiers: List<String>,
    logLabel: String?,
  ): Boolean {
    if (!sendSocketMessage(
        controller.inputKeyboardMessage(sessionId, keyCode, "down", modifiers),
        logLabel?.let { "发送 input.keyboard.key $it down" } ?: "发送 input.keyboard.key $keyCode down",
        logSuccess = logLabel != null,
      )) {
      return false
    }
    return sendSocketMessage(
      controller.inputKeyboardMessage(sessionId, keyCode, "up", modifiers),
      logLabel?.let { "发送 input.keyboard.key $it up" } ?: "发送 input.keyboard.key $keyCode up",
      logSuccess = logLabel != null,
    )
  }

  private fun remoteKeyForChar(character: Char): RemoteKey? {
    if (character in 'a'..'z') {
      return RemoteKey("Key${character.uppercaseChar()}")
    }
    if (character in 'A'..'Z') {
      return RemoteKey("Key$character", listOf("ShiftLeft"))
    }
    if (character in '0'..'9') {
      return RemoteKey("Digit$character")
    }
    return when (character) {
      ' ' -> RemoteKey("Space")
      '\n', '\r' -> RemoteKey("Enter")
      '\t' -> RemoteKey("Tab")
      '-' -> RemoteKey("Minus")
      '_' -> RemoteKey("Minus", listOf("ShiftLeft"))
      '=' -> RemoteKey("Equal")
      '+' -> RemoteKey("Equal", listOf("ShiftLeft"))
      '[' -> RemoteKey("BracketLeft")
      '{' -> RemoteKey("BracketLeft", listOf("ShiftLeft"))
      ']' -> RemoteKey("BracketRight")
      '}' -> RemoteKey("BracketRight", listOf("ShiftLeft"))
      '\\' -> RemoteKey("Backslash")
      '|' -> RemoteKey("Backslash", listOf("ShiftLeft"))
      ';' -> RemoteKey("Semicolon")
      ':' -> RemoteKey("Semicolon", listOf("ShiftLeft"))
      '\'' -> RemoteKey("Quote")
      '"' -> RemoteKey("Quote", listOf("ShiftLeft"))
      ',' -> RemoteKey("Comma")
      '<' -> RemoteKey("Comma", listOf("ShiftLeft"))
      '.' -> RemoteKey("Period")
      '>' -> RemoteKey("Period", listOf("ShiftLeft"))
      '/' -> RemoteKey("Slash")
      '?' -> RemoteKey("Slash", listOf("ShiftLeft"))
      '`' -> RemoteKey("Backquote")
      '~' -> RemoteKey("Backquote", listOf("ShiftLeft"))
      '!' -> RemoteKey("Digit1", listOf("ShiftLeft"))
      '@' -> RemoteKey("Digit2", listOf("ShiftLeft"))
      '#' -> RemoteKey("Digit3", listOf("ShiftLeft"))
      '$' -> RemoteKey("Digit4", listOf("ShiftLeft"))
      '%' -> RemoteKey("Digit5", listOf("ShiftLeft"))
      '^' -> RemoteKey("Digit6", listOf("ShiftLeft"))
      '&' -> RemoteKey("Digit7", listOf("ShiftLeft"))
      '*' -> RemoteKey("Digit8", listOf("ShiftLeft"))
      '(' -> RemoteKey("Digit9", listOf("ShiftLeft"))
      ')' -> RemoteKey("Digit0", listOf("ShiftLeft"))
      else -> null
    }
  }

  private fun sendScrollSample(sessionId: String) {
    sendSocketMessage(
      controller.inputWheelMessage(sessionId, deltaX = 0, deltaY = -120),
      "发送 input.wheel.scroll dy=-120",
    )
  }

  private fun decodeFrameBitmap(contentB64: String): DecodedFrame {
    return try {
      val bytes = Base64.decode(contentB64, Base64.DEFAULT)
      if (bytes.size > MAX_FRAME_BYTES) {
        return DecodedFrame(error = "屏幕帧过大，已拒绝渲染")
      }

      val boundsOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
      }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
      if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
        return DecodedFrame(error = "屏幕帧不是有效 PNG")
      }
      if (boundsOptions.outWidth > MAX_FRAME_DIMENSION || boundsOptions.outHeight > MAX_FRAME_DIMENSION) {
        return DecodedFrame(error = "屏幕帧尺寸超限 ${boundsOptions.outWidth}x${boundsOptions.outHeight}")
      }

      val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: return DecodedFrame(error = "屏幕帧解码失败")
      DecodedFrame(bitmap = bitmap, width = bitmap.width, height = bitmap.height)
    } catch (error: IllegalArgumentException) {
      DecodedFrame(error = "屏幕帧 Base64 非法：${error.message ?: "unknown"}")
    } catch (_: OutOfMemoryError) {
      DecodedFrame(error = "屏幕帧过大，内存不足")
    }
  }

  private fun showFrameDecodeFailure(message: String) {
    val now = SystemClock.elapsedRealtime()
    if (lastLegacyDecodeFailureAtMs > 0L && now - lastLegacyDecodeFailureAtMs < LEGACY_DECODE_FAILURE_UI_INTERVAL_MS) {
      legacyDecodeFailureSuppressedCount += 1
      return
    }
    val suppressedCount = legacyDecodeFailureSuppressedCount
    legacyDecodeFailureSuppressedCount = 0
    lastLegacyDecodeFailureAtMs = now
    renderedFrameWidth = 0
    renderedFrameHeight = 0
    resetLegacyFrameStats()
    binding.remoteFrameView.setImageDrawable(null)
    binding.frameMetaText.text = "当前画面：-"
    setStatus("远端画面解码失败")
    if (suppressedCount > 0) {
      appendLog("legacy 解码失败日志已节流，抑制 $suppressedCount 条重复错误")
    }
    appendLog(message)
  }

  private fun updateSessionText() {
    binding.sessionText.text = "当前会话：${sessionId ?: "无"}"
    updateSessionTimingUi()
  }

  private fun resetSessionUi(clearFrame: Boolean = false) {
    releaseRemoteInputState()
    sessionId = null
    autoProofInputSentForSessionId = ""
    sessionStartedAtWallClockMs = 0L
    stopSessionClockTicker()
    activeSessionPeerDeviceId = null
    closeWebRtcSession(reason = "reset_session_ui", clearRendererImage = clearFrame)
    renderedFrameWidth = 0
    renderedFrameHeight = 0
    resetLegacyFrameStats()
    resetLegacyFailureThrottleState()
    resetRemoteInputResultStats()
    resetLiveE2EProofReportState()
    frameGeneration += 1
    updateSessionText()
    binding.transportText.text = "传输方式：-"
    binding.peerText.text = "会话链路：-"
    binding.ackText.text = "输入回执：-"
    binding.frameMetaText.text = "当前画面：-"
    updateLiveMetricsPanel()
    resetRemoteViewportTransform(logReason = false)
    exitRemoteViewportFullscreen(reason = "session_reset")
    binding.remoteVideoView.isVisible = true
    binding.remoteFrameView.isVisible = false
    if (clearFrame) {
      binding.remoteFrameView.setImageDrawable(null)
      binding.remoteVideoView.clearImage()
    }
  }

  private fun updateSessionButtonState() {
    val hasSession = hasActiveSession()
    val targetDeviceId = binding.targetDeviceInput.text?.toString().orEmpty().trim()
    val canStartAssist = isSocketConnected && isRegistered && isPresenceReady && targetDeviceId.isNotBlank() &&
      !pendingSessionRequest && !hasSession && !isRecoveringSession

    binding.registerButton.isEnabled = isSocketConnected
    binding.heartbeatButton.isEnabled = isSocketConnected && isRegistered
    binding.sessionButton.isEnabled = canStartAssist
    binding.debugTapButton.isEnabled = hasSession
    binding.debugKeyboardButton.isEnabled = hasSession
    binding.debugScrollButton.isEnabled = hasSession
    binding.debugProofSequenceButton.isEnabled = hasSession
    binding.debugProofRefreshButton.isEnabled = !isFetchingE2EProof
    binding.debugProofResetButton.isEnabled = !isFetchingE2EProof
    binding.remoteKeyboardInput.isEnabled = hasSession
    binding.remoteKeyboardSendButton.isEnabled = hasSession
    binding.remoteKeyboardBackspaceButton.isEnabled = hasSession
    binding.remoteKeyboardEnterButton.isEnabled = hasSession

    binding.targetDeviceInput.isEnabled = !hasSession && !pendingSessionRequest && !isRecoveringSession
    binding.remoteFrameCard.isVisible = pendingSessionRequest || hasSession || isRecoveringSession
    if (binding.myDevicesPage.isVisible) {
      renderDeviceList()
    }
  }

  private fun setStatus(statusLine: String) {
    binding.statusText.text = "当前状态：$statusLine"
    binding.topConnectionStatusText.text = "连接状态：${mapTopConnectionStatus(statusLine)}"
    val hasSession = hasActiveSession()
    binding.remoteFrameCard.isVisible = pendingSessionRequest || hasSession || isRecoveringSession
  }

  private fun mapTopConnectionStatus(statusLine: String): String {
    if (isSocketConnected) {
      return "已连接"
    }
    return when {
      statusLine.contains("连接失败") -> "连接失败"
      statusLine.contains("已关闭") -> "已断开"
      statusLine.contains("恢复") -> "恢复中"
      statusLine.contains("连接中") -> "连接中"
      else -> "未连接"
    }
  }

  private fun switchPage(page: MainPage, autoRefreshDevices: Boolean = true) {
    currentPage = page
    binding.myDevicesPage.isVisible = page == MainPage.MY_DEVICES
    binding.remoteAssistPage.isVisible = page == MainPage.MY_DEVICES
    binding.profilePage.isVisible = page == MainPage.SETTINGS
    binding.cloudPage.isVisible = false
    binding.debugPanel.isVisible = false

    styleNavButton(binding.navMyDevicesButton, page == MainPage.MY_DEVICES)
    styleNavButton(binding.navProfileButton, page == MainPage.SETTINGS)
    binding.navCloudButton.isVisible = false
    binding.navAssistButton.isVisible = false

    if (page == MainPage.MY_DEVICES && autoRefreshDevices) {
      refreshDevicesList()
    }
  }

  private fun styleNavButton(button: MaterialButton, selected: Boolean) {
    val textColor = Color.parseColor(if (selected) "#2B6DFF" else "#6A86AD")
    val backgroundColor = Color.parseColor(if (selected) "#E6EFFF" else "#FFFFFF")
    button.alpha = if (selected) 1f else 0.95f
    button.setTextColor(textColor)
    button.iconTint = ColorStateList.valueOf(textColor)
    button.backgroundTintList = ColorStateList.valueOf(backgroundColor)
  }

  private fun refreshDevicesList(force: Boolean = false, userInitiated: Boolean = false) {
    val wsUrl = normalizeWsUrl(binding.wsUrlInput.text.toString())
    if (wsUrl.isBlank()) {
      relayDevices = emptyList()
      hasLoadedDevices = false
      lastDevicesUrl = null
      updateDevicesStatus("先到“中继设置”填写地址，再回设备列表同步。")
      renderDeviceList()
      if (userInitiated) {
        appendLog("同步设备列表失败：未填写中继信令地址")
      }
      return
    }

    val devicesUrl = buildDevicesUrl(wsUrl)
    if (devicesUrl == null) {
      relayDevices = emptyList()
      hasLoadedDevices = false
      lastDevicesUrl = null
      updateDevicesStatus("中继地址格式不正确，请先修正后再同步设备列表。")
      renderDeviceList()
      if (userInitiated) {
        appendLog("同步设备列表失败：中继信令地址不合法")
      }
      return
    }

    if (isFetchingDevices) {
      if (userInitiated) {
        appendLog("设备列表同步中，请稍候")
      }
      return
    }

    if (!force && lastDevicesUrl == devicesUrl && hasLoadedDevices) {
      updateDevicesStatus(buildDevicesStatus(relayDevices))
      renderDeviceList()
      return
    }

    isFetchingDevices = true
    updateDevicesStatus("正在同步设备列表…")
    renderDeviceList()

    val request = Request.Builder()
      .url(devicesUrl)
      .build()

    deviceSyncExecutor.execute {
      try {
        devicesHttpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}")
          }
          val body = response.body?.string().orEmpty()
          val devices = parseRelayDevices(body)
          runOnUiThread {
            if (!isActivityAlive) {
              return@runOnUiThread
            }
            isFetchingDevices = false
            if (buildDevicesUrl(normalizeWsUrl(binding.wsUrlInput.text.toString())) != devicesUrl) {
              hasLoadedDevices = false
              lastDevicesUrl = null
              updateDevicesStatus("中继地址已变更，请重新同步设备列表。")
              renderDeviceList()
              return@runOnUiThread
            }
            relayDevices = normalizeRelayDevices(devices)
            val currentTargetDeviceId = binding.targetDeviceInput.text.toString().trim()
            val targetOnline = currentTargetDeviceId.isNotBlank() &&
              relayDevices.any { it.deviceId == currentTargetDeviceId && it.isReachable() }
            if ((hasActiveSession() || pendingSessionRequest) && currentTargetDeviceId.isNotBlank() && !targetOnline) {
              handleTargetDeviceOffline(currentTargetDeviceId, source = "devices.sync")
            }
            hasLoadedDevices = true
            lastDevicesUrl = devicesUrl
            updateDevicesStatus(buildDevicesStatus(relayDevices))
            renderDeviceList()
            maybeScheduleReconnectSessionRestore(source = "devices_sync")
            if (userInitiated) {
              appendLog("已同步设备列表：${relayDevices.size} 台可控设备")
            }
          }
        }
      } catch (error: Exception) {
        runOnUiThread {
          if (!isActivityAlive) {
            return@runOnUiThread
          }
          isFetchingDevices = false
          hasLoadedDevices = false
          lastDevicesUrl = null
          updateDevicesStatus("同步失败：${error.message ?: "unknown"}")
          renderDeviceList()
          if (userInitiated) {
            appendLog("同步设备列表失败：${error.message ?: "unknown"}")
          }
        }
      }
    }
  }

  private fun buildDevicesUrl(wsUrl: String): String? {
    val validationError = validateWsUrl(wsUrl)
    if (validationError != null) {
      return null
    }
    val uri = Uri.parse(wsUrl)
    val httpScheme = when (uri.scheme?.lowercase(Locale.ROOT)) {
      "ws" -> "http"
      "wss" -> "https"
      else -> return null
    }
    val wsPath = uri.encodedPath.orEmpty().ifBlank { "/ws" }
    val devicesPath = when {
      wsPath == "/ws" -> "/devices"
      wsPath.endsWith("/ws") -> wsPath.removeSuffix("/ws") + "/devices"
      else -> wsPath.trimEnd('/') + "/devices"
    }
    return uri.buildUpon()
      .scheme(httpScheme)
      .encodedPath(devicesPath)
      .encodedQuery(null)
      .fragment(null)
      .build()
      .toString()
  }

  private fun refreshE2EProofSnapshot(reset: Boolean = false, userInitiated: Boolean = false) {
    val proofUrl = buildE2EProofUrl(normalizeWsUrl(binding.wsUrlInput.text.toString()))
    if (proofUrl == null) {
      e2eProofStatusMessage = "E2E proof: relay URL required"
      updateE2EProofStatusText()
      if (userInitiated) {
        appendLog("E2E proof check failed: invalid relay URL")
      }
      return
    }
    if (isFetchingE2EProof) {
      if (userInitiated) {
        appendLog("E2E proof request already running")
      }
      return
    }

    isFetchingE2EProof = true
    e2eProofStatusMessage = if (reset) "E2E proof: resetting..." else "E2E proof: refreshing..."
    updateE2EProofStatusText()
    updateSessionButtonState()

    val requestBuilder = Request.Builder().url(proofUrl)
    if (reset) {
      requestBuilder.delete()
    }
    val request = requestBuilder.build()

    deviceSyncExecutor.execute {
      try {
        devicesHttpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}")
          }
          val body = response.body?.string().orEmpty()
          val status = summarizeE2EProofSnapshot(JSONObject(body), reset)
          runOnUiThread {
            if (!isActivityAlive) {
              return@runOnUiThread
            }
            isFetchingE2EProof = false
            e2eProofStatusMessage = status
            updateE2EProofStatusText()
            updateSessionButtonState()
            if (userInitiated) {
              appendLog(status)
            }
          }
        }
      } catch (error: Exception) {
        runOnUiThread {
          if (!isActivityAlive) {
            return@runOnUiThread
          }
          isFetchingE2EProof = false
          e2eProofStatusMessage = "E2E proof failed: ${error.message ?: "unknown"}"
          updateE2EProofStatusText()
          updateSessionButtonState()
          if (userInitiated) {
            appendLog(e2eProofStatusMessage)
          }
        }
      }
    }
  }

  private fun buildE2EProofUrl(wsUrl: String): String? {
    val validationError = validateWsUrl(wsUrl)
    if (validationError != null) {
      return null
    }
    val uri = Uri.parse(wsUrl)
    val httpScheme = when (uri.scheme?.lowercase(Locale.ROOT)) {
      "ws" -> "http"
      "wss" -> "https"
      else -> return null
    }
    val wsPath = uri.encodedPath.orEmpty().ifBlank { "/ws" }
    val proofPath = when {
      wsPath == "/ws" -> "/e2e-proof"
      wsPath.endsWith("/ws") -> wsPath.removeSuffix("/ws") + "/e2e-proof"
      else -> wsPath.trimEnd('/') + "/e2e-proof"
    }
    return uri.buildUpon()
      .scheme(httpScheme)
      .encodedPath(proofPath)
      .encodedQuery(null)
      .fragment(null)
      .build()
      .toString()
  }

  private fun summarizeE2EProofSnapshot(snapshot: JSONObject, reset: Boolean): String {
    val complete = snapshot.optBoolean("complete", false)
    val completeCount = snapshot.optInt("target_routes_complete", 0)
    val totalCount = snapshot.optInt("target_routes_total", 0)
    val routesJson = snapshot.optJSONArray("routes")
    val routeSummaries = mutableListOf<String>()
    if (routesJson != null) {
      for (index in 0 until routesJson.length()) {
        val route = routesJson.optJSONObject(index) ?: continue
        routeSummaries += summarizeE2EProofRoute(route)
      }
    }
    val prefix = when {
      reset -> "E2E proof reset"
      complete -> "E2E proof complete"
      else -> "E2E proof incomplete"
    }
    val countSummary = "$completeCount/$totalCount routes"
    return listOf(prefix, countSummary, routeSummaries.joinToString("; "))
      .filter { it.isNotBlank() }
      .joinToString(" | ")
  }

  private fun summarizeE2EProofRoute(route: JSONObject): String {
    val key = route.optNonBlank("route_key") ?: route.optNonBlank("route") ?: "route"
    val status = route.optNonBlank("status") ?: "unknown"
    val latest = route.optJSONObject("latest")
    val success = route.optJSONObject("last_success")
    val proof = success ?: latest
    val coverage = proof?.optJSONArray("remote_input_coverage")?.toStringList().orEmpty()
    val missing = route.optJSONArray("missing")?.toStringList().orEmpty()
    val detail = when {
      route.optBoolean("complete", false) -> "ok ${coverage.joinToString(",").ifBlank { "-" }}"
      missing.isNotEmpty() -> "missing ${missing.joinToString(",")}"
      else -> status
    }
    return "$key=$detail"
  }

  private fun updateE2EProofStatusText() {
    binding.debugProofStatusText.text = e2eProofStatusMessage
  }

  private fun parseRelayDevices(body: String): List<RelayDevice> {
    val payload = JSONObject(body)
    val devicesJson = payload.optJSONArray("devices") ?: return emptyList()
    return parseRelayDevices(devicesJson)
  }

  private fun parseRelayDevices(devicesJson: org.json.JSONArray): List<RelayDevice> {
    val devices = mutableListOf<RelayDevice>()
    for (index in 0 until devicesJson.length()) {
      val item = devicesJson.optJSONObject(index) ?: continue
      val id = item.optString("device_id").trim()
      if (id.isBlank()) {
        continue
      }
      val name = item.optString("device_name").trim().ifBlank { id }
      val role = item.optString("role").trim()
      val capabilities = item.optJSONObject("capabilities")
      devices += RelayDevice(
        deviceId = id,
        deviceName = name,
        userId = item.optString("user_id").trim(),
        platform = item.optString("platform").trim(),
        role = role,
        status = item.optString("status").trim(),
        canControl = if (capabilities?.has("can_control") == true) {
          capabilities.optBoolean("can_control", false)
        } else {
          role.equals("controller", ignoreCase = true)
        },
        canBeControlled = if (capabilities?.has("can_be_controlled") == true) {
          capabilities.optBoolean("can_be_controlled", false)
        } else {
          role.equals("agent", ignoreCase = true)
        },
      )
    }
    return devices
  }

  private fun normalizeRelayDevices(devices: List<RelayDevice>): List<RelayDevice> =
    devices
      .filter { it.canReceiveRemoteControl() || it.deviceId == deviceId }
      .sortedWith(
        compareByDescending<RelayDevice> { it.isReachable() }
          .thenByDescending { it.deviceId == deviceId }
          .thenBy { it.deviceName.lowercase(Locale.ROOT) }
          .thenBy { it.deviceId.lowercase(Locale.ROOT) },
      )

  private fun applyDevicesPresencePush(payload: JSONObject) {
    val devicesJson = payload.optJSONArray("devices")
    if (devicesJson == null) {
      appendLog("设备在线推送缺少列表快照，回退 HTTP 同步")
      refreshDevicesList(force = true)
      return
    }
    val reason = payload.optNonBlank("reason") ?: "unknown"
    val changedDeviceId = payload.optNonBlank("changed_device_id") ?: "-"
    relayDevices = normalizeRelayDevices(parseRelayDevices(devicesJson))
    hasLoadedDevices = true
    lastDevicesPushAtMs = SystemClock.elapsedRealtime()
    updateDevicesStatus(buildDevicesStatus(relayDevices))
    renderDeviceList()
    maybeScheduleReconnectSessionRestore(source = "presence_push")
    val currentTargetDeviceId = binding.targetDeviceInput.text.toString().trim()
    val targetOnline = currentTargetDeviceId.isNotBlank() &&
      relayDevices.any { it.deviceId == currentTargetDeviceId && it.isReachable() }
    if ((hasActiveSession() || pendingSessionRequest) && currentTargetDeviceId.isNotBlank() && !targetOnline) {
      handleTargetDeviceOffline(currentTargetDeviceId, source = "device.presence.push")
    }
    Log.i(
      RTC_TAG,
      "device_presence_push reason=$reason changed=$changedDeviceId count=${relayDevices.size}",
    )
  }

  private fun buildDevicesStatus(devices: List<RelayDevice>): String {
    if (isFetchingDevices) {
      return "正在同步设备列表…"
    }
    if (devices.isEmpty()) {
      return if (hasLoadedDevices) {
        "暂未发现在线设备。"
      } else {
        devicesStatusMessage
      }
    }
    val onlineCount = devices.count { it.isReachable() }
    return "共 ${devices.size} 台，在线 $onlineCount 台"
  }

  private fun updateDevicesStatus(message: String) {
    devicesStatusMessage = message
  }

  private fun renderDeviceList() {
    val onlineDevices = relayDevices.filter { it.isReachable() }

    binding.onlineDevicesTitle.text = "在线设备 ${onlineDevices.size} ▾"
    binding.onlineDevicesContainer.removeAllViews()

    onlineDevices.forEachIndexed { index, device ->
      binding.onlineDevicesContainer.addView(createDeviceCard(device, topMargin = if (index == 0) 0 else 8, isOnline = true))
    }

    val noDevices = relayDevices.isEmpty()
    binding.devicesEmptyCard.isVisible = noDevices
    binding.devicesEmptyText.text = when {
      isFetchingDevices -> "正在向中继服务同步设备列表，请稍候。"
      normalizeWsUrl(binding.wsUrlInput.text.toString()).isBlank() -> "先到“中继设置”填写中继地址，再查看设备列表。"
      hasLoadedDevices -> "当前没有可控设备。先启动电脑受控端并保持在线。"
      else -> "暂无设备。先完成连接和注册，或检查中继服务是否可访问。"
    }
  }

  private fun createDeviceCard(device: RelayDevice, topMargin: Int, isOnline: Boolean): MaterialCardView {
    val effectiveTargetDeviceId = when {
      hasActiveSession() && !activeSessionPeerDeviceId.isNullOrBlank() -> activeSessionPeerDeviceId.orEmpty()
      else -> binding.targetDeviceInput.text.toString().trim()
    }
    val selected = effectiveTargetDeviceId == device.deviceId
    val isSelf = device.deviceId == deviceId
    val previewPalettes = arrayOf(
      intArrayOf(Color.parseColor("#EFF3F8"), Color.parseColor("#E2E8F0")),
      intArrayOf(Color.parseColor("#EEF2F7"), Color.parseColor("#E3E9F1")),
      intArrayOf(Color.parseColor("#F1F4F8"), Color.parseColor("#E6EBF3")),
    )
    val palette = previewPalettes[(device.deviceId.hashCode() and Int.MAX_VALUE) % previewPalettes.size]

    val card = MaterialCardView(this).apply {
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ).apply {
        this.topMargin = dp(topMargin)
      }
      radius = dpFloat(16)
      cardElevation = 0f
      strokeWidth = dp(1)
      strokeColor = Color.parseColor(
        when {
          selected -> "#89A6FF"
          else -> "#E4E8EE"
        },
      )
      setCardBackgroundColor(Color.parseColor(if (selected) "#F4F8FF" else "#FFFFFF"))
      isClickable = true
      isFocusable = true
      setOnClickListener {
        if (isSelf) {
          appendLog("本机设备仅展示，不可作为远控目标")
        } else if (isOnline && device.canReceiveRemoteControl()) {
          startAssistForDevice(device)
        } else {
          selectDeviceForAssist(device)
        }
      }
    }

    val row = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      minimumHeight = dp(74)
    }

    val preview = FrameLayout(this).apply {
      layoutParams = LinearLayout.LayoutParams(dp(88), dp(58)).apply {
        marginStart = dp(10)
        this.topMargin = dp(8)
        bottomMargin = dp(8)
      }
      background = GradientDrawable(
        GradientDrawable.Orientation.BL_TR,
        intArrayOf(palette[0], palette[1]),
      ).apply {
        cornerRadius = dpFloat(12)
      }
    }

    val previewText = TextView(this).apply {
      text = when (device.platform.lowercase(Locale.ROOT)) {
        "macos" -> "Mac"
        "windows" -> "Win"
        "android" -> "安卓"
        else -> device.deviceName.take(1).uppercase(Locale.ROOT)
      }
      gravity = Gravity.CENTER
      setTextColor(Color.parseColor("#3A475A"))
      textSize = 12f
      typeface = Typeface.DEFAULT_BOLD
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      )
    }
    preview.addView(previewText)

    val statusBadge = TextView(this).apply {
      text = if (isOnline) "在线" else "离线"
      setTextColor(Color.parseColor(if (isOnline) "#0E8A4B" else "#707B8C"))
      textSize = 9f
      typeface = Typeface.DEFAULT_BOLD
      setPadding(dp(6), dp(2), dp(6), dp(2))
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpFloat(10)
        setColor(Color.parseColor(if (isOnline) "#DFF5EA" else "#ECEFF4"))
      }
    }
    preview.addView(
      statusBadge,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.TOP or Gravity.START,
      ).apply {
        setMargins(dp(8), dp(7), 0, 0)
      },
    )
    row.addView(preview)

    val contentRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      layoutParams = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
      )
      setPadding(dp(10), dp(8), dp(8), dp(8))
    }

    val textColumn = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
      )
    }

    val nameText = TextView(this).apply {
      text = device.deviceName
      setTextColor(Color.parseColor("#111111"))
      textSize = 13f
      typeface = Typeface.DEFAULT_BOLD
    }
    textColumn.addView(nameText)

    val metaRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ).apply {
        this.topMargin = dp(4)
      }
    }

    val platformText = TextView(this).apply {
      text = "${platformIcon(device.platform)} ${device.platformLabel()}"
      setTextColor(Color.parseColor("#5B6472"))
      textSize = 11f
    }
    metaRow.addView(platformText)

    val secondaryText = TextView(this).apply {
      text = when {
        isSelf -> "  · 本机"
        selected -> "  · 已选择"
        else -> ""
      }
      setTextColor(Color.parseColor("#2B6DFF"))
      textSize = 10f
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      )
    }
    metaRow.addView(secondaryText)
    textColumn.addView(metaRow)

    contentRow.addView(textColumn)

    val hasSession = hasActiveSession()
    val showDisconnect = hasSession && selected && isOnline && !isSelf
    val showConnecting = (pendingSessionRequest || isRecoveringSession) && selected
    val canConnect = canStartAssistNow() && isOnline && !isSelf && device.canReceiveRemoteControl()
    if (!isSelf) {
      Log.i(
        RTC_TAG,
        "device_card_state device=${device.deviceId} session_id=${resolveActiveSessionId() ?: "-"} has_session=$hasSession selected=$selected online=$isOnline show_disconnect=$showDisconnect pending_request=$pendingSessionRequest",
      )
    }

    val actionButton = MaterialButton(this).apply {
      text = when {
        isSelf -> "本机"
        showDisconnect -> "断开"
        showConnecting -> "连接中"
        isOnline && !device.canReceiveRemoteControl() -> "不可控"
        isOnline -> "连接"
        else -> "离线"
      }
      isAllCaps = false
      textSize = 10f
      minWidth = 0
      minimumHeight = dp(30)
      insetTop = 0
      insetBottom = 0
      setPadding(dp(10), 0, dp(10), 0)
      cornerRadius = dp(10)
      isEnabled = when {
        isSelf -> false
        showDisconnect -> true
        showConnecting -> false
        !isOnline -> false
        else -> canConnect
      }
      val bgColor = when {
        isSelf -> "#CBD3DF"
        showDisconnect -> "#ED5E5E"
        showConnecting -> "#B3C5EE"
        canConnect -> "#4F7BFF"
        else -> "#CBD3DF"
      }
      backgroundTintList = ColorStateList.valueOf(Color.parseColor(bgColor))
      setTextColor(Color.parseColor("#FFFFFF"))
      setOnClickListener {
        if (showDisconnect) {
          handleEndSessionAction()
        } else if (!showConnecting) {
          startAssistForDevice(device)
        }
      }
    }
    contentRow.addView(actionButton)

    row.addView(contentRow)
    card.addView(row)
    return card
  }

  private fun platformIcon(platform: String): String =
    when (platform.lowercase(Locale.ROOT)) {
      "macos" -> "[M]"
      "windows" -> "[W]"
      "android" -> "[A]"
      else -> "[D]"
    }

  private fun canStartAssistNow(): Boolean {
    val hasSession = hasActiveSession()
    return isSocketConnected && isRegistered && isPresenceReady && !pendingSessionRequest && !hasSession && !isRecoveringSession
  }

  private fun canRestoreSessionNow(targetDeviceId: String): Boolean {
    return reconnectShouldRestoreSession &&
      targetDeviceId.isNotBlank() &&
      isSocketConnected &&
      isRegistered &&
      isPresenceReady &&
      !pendingSessionRequest &&
      !hasActiveSession()
  }

  private fun resolveSessionRecoveryTarget(): String {
    return when {
      !activeSessionPeerDeviceId.isNullOrBlank() -> activeSessionPeerDeviceId.orEmpty()
      !reconnectTargetDeviceId.isNullOrBlank() -> reconnectTargetDeviceId.orEmpty()
      else -> binding.targetDeviceInput.text?.toString().orEmpty().trim()
    }.trim()
  }

  private fun rememberSessionRecoveryIntent(reason: String): Boolean {
    val targetDeviceId = resolveSessionRecoveryTarget()
    val hadSessionIntent = hasActiveSession() || pendingSessionRequest || reconnectShouldRestoreSession || isRecoveringSession
    if (sessionEndRequestedByUser || !hadSessionIntent || targetDeviceId.isBlank()) {
      return false
    }
    val wasAlreadyRecovering = reconnectShouldRestoreSession || isRecoveringSession
    reconnectShouldRestoreSession = true
    reconnectTargetDeviceId = targetDeviceId
    isRecoveringSession = true
    reconnectSessionRestoreScheduled = false
    if (!wasAlreadyRecovering) {
      reconnectSessionRestoreAttempt = 0
    }
    Log.i(
      RTC_TAG,
      "session_recovery_intent target=$targetDeviceId reason=$reason session=${sessionId ?: "-"} pending=$pendingSessionRequest",
    )
    appendLog("已记录短断线恢复目标：$targetDeviceId")
    return true
  }

  private fun clearSessionRecoveryIntent(reason: String) {
    if (reconnectShouldRestoreSession || isRecoveringSession || reconnectSessionRestoreScheduled) {
      Log.i(
        RTC_TAG,
        "session_recovery_clear reason=$reason target=${reconnectTargetDeviceId ?: "-"} attempts=$reconnectSessionRestoreAttempt",
      )
    }
    reconnectShouldRestoreSession = false
    reconnectTargetDeviceId = null
    reconnectSessionRestoreScheduled = false
    reconnectSessionRestoreAttempt = 0
    isRecoveringSession = false
  }

  private fun maybeScheduleReconnectSessionRestore(source: String) {
    val targetDeviceId = reconnectTargetDeviceId?.trim().orEmpty()
    if (!canRestoreSessionNow(targetDeviceId) || reconnectSessionRestoreScheduled) {
      return
    }
    val targetDevice = relayDevices.find { it.deviceId == targetDeviceId }
    if (targetDevice == null) {
      scheduleSessionRecoveryTargetWait(source = "target_missing", targetDeviceId = targetDeviceId)
      return
    }
    if (!targetDevice.canReceiveRemoteControl()) {
      appendLog("短断线恢复停止：目标设备未上报可被控制能力")
      clearSessionRecoveryIntent("target_not_controllable")
      updateSessionButtonState()
      return
    }
    if (!targetDevice.isReachable()) {
      scheduleSessionRecoveryTargetWait(source = "target_unreachable", targetDeviceId = targetDeviceId)
      return
    }
    if (reconnectSessionRestoreAttempt >= RELAY_SESSION_RECOVERY_MAX_ATTEMPTS) {
      appendLog("短断线恢复已达到重试上限，请手动重新连接")
      clearSessionRecoveryIntent("restore_attempt_limit")
      updateSessionButtonState()
      return
    }

    val nextAttempt = reconnectSessionRestoreAttempt + 1
    val delayMs = if (nextAttempt == 1) RELAY_SESSION_RECOVERY_DELAY_MS else RELAY_SESSION_RECOVERY_RETRY_DELAY_MS
    reconnectSessionRestoreScheduled = true
    isRecoveringSession = true
    binding.frameMetaText.text = "当前画面：信令已恢复，等待重新建立会话"
    setStatus("短断线恢复中")
    updateSessionButtonState()
    appendLog("短断线恢复：${delayMs}ms 后自动请求原目标（第 $nextAttempt 次，来源=$source）")
    reconnectHandler.postDelayed({
      reconnectSessionRestoreScheduled = false
      if (!isActivityAlive || !canRestoreSessionNow(targetDeviceId)) {
        return@postDelayed
      }
      if (binding.targetDeviceInput.text?.toString().orEmpty().trim() != targetDeviceId) {
        binding.targetDeviceInput.setText(targetDeviceId)
        binding.targetDeviceInput.setSelection(binding.targetDeviceInput.text?.length ?: 0)
      }
      reconnectSessionRestoreAttempt = nextAttempt
      appendLog("短断线恢复：自动重新请求会话 -> $targetDeviceId")
      requestSession(isRecovery = true)
    }, delayMs)
  }

  private fun resolveRecoveryOfflineTarget(payload: JSONObject?): String {
    val rememberedTarget = reconnectTargetDeviceId?.trim().orEmpty()
    if (rememberedTarget.isNotBlank()) {
      return rememberedTarget
    }
    return payload.optNonBlank("agent_device_id")
      ?: payload.optNonBlank("target_device_id")
      ?: payload.optNonBlank("device_id")
      ?: binding.targetDeviceInput.text.toString().trim()
  }

  private fun scheduleSessionRecoveryTargetWait(source: String, targetDeviceId: String) {
    if (!canRestoreSessionNow(targetDeviceId) || reconnectSessionRestoreScheduled) {
      return
    }
    reconnectSessionRestoreScheduled = true
    isRecoveringSession = true
    binding.frameMetaText.text = "当前画面：等待原 Mac 受控端重新上线"
    setStatus("短断线恢复等待目标上线")
    updateSessionButtonState()
    // long: 短断线时 Mac 端通常晚于安卓重新注册，先等设备列表确认原目标上线，避免把恢复次数耗在必然失败的请求上。
    if (!isFetchingDevices) {
      refreshDevicesList(force = true)
    }
    appendLog("短断线恢复等待：原目标 $targetDeviceId 暂未重新上线，来源=$source")
    reconnectHandler.postDelayed({
      reconnectSessionRestoreScheduled = false
      if (!isActivityAlive || !canRestoreSessionNow(targetDeviceId)) {
        return@postDelayed
      }
      if (!isFetchingDevices) {
        refreshDevicesList(force = true)
      }
      maybeScheduleReconnectSessionRestore(source = "target_wait")
    }, RELAY_SESSION_RECOVERY_RETRY_DELAY_MS)
  }

  private fun maybeScheduleAutoRequestSession() {
    val targetDeviceId = binding.targetDeviceInput.text?.toString().orEmpty().trim()
    if (!launchAutoRequestSession || targetDeviceId.isBlank() || autoRequestSentForTargetDeviceId == targetDeviceId) {
      return
    }
    if (!canStartAssistNow()) {
      return
    }
    autoRequestSentForTargetDeviceId = targetDeviceId
    autoProofHandler.postDelayed({
      if (!isActivityAlive || !canStartAssistNow()) {
        return@postDelayed
      }
      if (binding.targetDeviceInput.text?.toString().orEmpty().trim() != targetDeviceId) {
        return@postDelayed
      }
      appendLog("Auto E2E proof session request via launch extra")
      requestSession()
    }, AUTO_REQUEST_SESSION_DELAY_MS)
  }

  private fun maybeScheduleAutoProofInput(activeSessionId: String?) {
    val current = activeSessionId?.trim().orEmpty()
    if (!launchAutoProofInput || current.isBlank() || autoProofInputSentForSessionId == current) {
      return
    }
    autoProofInputSentForSessionId = current
    autoProofHandler.postDelayed({
      if (!isActivityAlive || sessionId != current) {
        return@postDelayed
      }
      appendLog("Auto E2E proof input sequence via launch extra")
      sendE2EProofInputSequence(current)
    }, AUTO_PROOF_INPUT_DELAY_MS)
  }

  private fun startAssistForDevice(device: RelayDevice) {
    if (device.deviceId == deviceId) {
      appendLog("不能对本机发起远控，请选择其他在线设备")
      return
    }
    if (!device.canReceiveRemoteControl()) {
      appendLog("该设备未上报可被控制能力，无法发起远控")
      return
    }
    selectDeviceForAssist(device)
    handlePrimaryAction()
  }

  private fun handleEndSessionAction() {
    val currentSessionId = resolveActiveSessionId()
    sessionEndRequestedByUser = true
    clearSessionRecoveryIntent("manual_end_session")
    if (currentSessionId.isNullOrBlank()) {
      appendLog("当前没有 session，不能结束")
    } else {
      releaseRemoteInputState()
      sendSocketMessage(controller.endSessionMessage(currentSessionId), "发送 session.end.req")
    }
  }

  private fun selectDeviceForAssist(device: RelayDevice) {
    binding.targetDeviceInput.setText(device.deviceId)
    binding.targetDeviceInput.setSelection(binding.targetDeviceInput.text?.length ?: 0)
    persistConnectionSettings(
      wsUrl = binding.wsUrlInput.text.toString().trim(),
      targetDeviceId = device.deviceId,
    )
    appendLog("已选择伙伴设备：${device.deviceName} (${device.deviceId})")
    renderDeviceList()
    switchPage(MainPage.MY_DEVICES, autoRefreshDevices = false)
  }

  private fun handlePrimaryAction() {
    when {
      pendingSessionRequest -> appendLog("会话请求处理中，请稍候")
      !isSocketConnected -> beginConnectFlow()
      !isRegistered -> {
        if (sendSocketMessage(controller.registerMessage(), "发送 device.register.req")) {
          setStatus("注册中")
          updateSessionButtonState()
        }
      }
      !isPresenceReady -> {
        if (sendSocketMessage(controller.heartbeatMessage(token, sessionId), "发送 presence.heartbeat.req")) {
          setStatus("同步在线状态")
          updateSessionButtonState()
        }
      }
      hasActiveSession() -> appendLog("当前会话已建立，可直接操作预览画面或结束会话")
      else -> requestSession()
    }
  }

  private fun beginConnectFlow(isAutoReconnect: Boolean = false) {
    cancelAutoReconnect(resetAttempt = !isAutoReconnect)
    if (!isAutoReconnect) {
      sessionEndRequestedByUser = false
      clearSessionRecoveryIntent("manual_connect")
    }
    val wsUrl = normalizeWsUrl(binding.wsUrlInput.text.toString())
    lastAutoConnectUrl = wsUrl
    if (binding.wsUrlInput.text.toString() != wsUrl) {
      binding.wsUrlInput.setText(wsUrl)
      binding.wsUrlInput.setSelection(binding.wsUrlInput.text?.length ?: 0)
    }
    if (wsUrl.isBlank()) {
      appendLog("请先填写中继信令地址（ws:// 或 wss://）")
      switchPage(MainPage.SETTINGS, autoRefreshDevices = false)
      return
    }
    val validationError = validateWsUrl(wsUrl)
    if (validationError != null) {
      appendLog(validationError)
      switchPage(MainPage.SETTINGS, autoRefreshDevices = false)
      return
    }
    val uri = Uri.parse(wsUrl)
    if (uri.host.equals("10.0.2.2", ignoreCase = true)) {
      appendLog("10.0.2.2 仅适用于 Android 模拟器，真机请改成局域网 IP 或 wss 域名")
    }
    val targetDeviceId = binding.targetDeviceInput.text.toString().trim()
    persistConnectionSettings(wsUrl = wsUrl, targetDeviceId = targetDeviceId)
    token = "stub-token"
    pendingSessionRequest = false
    pendingSessionRequestId = null
    isSocketConnected = false
    isRegistered = false
    isPresenceReady = false
    hasLoadedDevices = false
    lastDevicesUrl = null
    binding.tokenText.text = "访问凭证：未注册"
    val preserveFrameForRecovery = isAutoReconnect && reconnectShouldRestoreSession
    resetSessionUi(clearFrame = !preserveFrameForRecovery)
    if (preserveFrameForRecovery) {
      isRecoveringSession = true
      binding.frameMetaText.text = "当前画面：正在恢复信令连接，输入已暂停"
      setStatus("短断线恢复中")
      updateDevicesStatus("正在自动重连中继服务；成功后会尝试恢复原 Mac 会话。")
    } else {
      setStatus("连接中")
      updateDevicesStatus("正在连接中继服务，稍后将自动同步设备列表。")
    }
    renderDeviceList()
    updateSessionButtonState()
    appendLog(if (isAutoReconnect) "自动重连 $wsUrl" else "连接 $wsUrl")
    socketClient.connect(wsUrl)
  }

  private fun scheduleAutoReconnect() {
    val wsUrl = normalizeWsUrl(binding.wsUrlInput.text.toString())
    if (!isActivityAlive || wsUrl.isBlank() || reconnectScheduled || isSocketConnected) {
      return
    }
    reconnectAttempt += 1
    val delayMs = reconnectDelayForAttempt(reconnectAttempt)
    reconnectScheduled = true
    appendLog("中继连接中断，${delayMs}ms 后自动重连（第 $reconnectAttempt 次）")
    reconnectHandler.postDelayed({
      reconnectScheduled = false
      if (!isActivityAlive || isSocketConnected) {
        return@postDelayed
      }
      appendLog("中继连接中断，正在自动重连…")
      beginConnectFlow(isAutoReconnect = true)
    }, delayMs)
  }

  private fun reconnectDelayForAttempt(attempt: Int): Long {
    val shift = (attempt - 1).coerceIn(0, 4)
    val delay = RELAY_RECONNECT_INITIAL_DELAY_MS * (1L shl shift)
    // long: 已经有远控会话时，用户看到的是“画面短断”，重连间隔需要比普通待机连接更激进，避免 relay 恢复后还继续等十几秒。
    val maxDelay = if (reconnectShouldRestoreSession || isRecoveringSession) {
      RELAY_RECONNECT_RECOVERY_MAX_DELAY_MS
    } else {
      RELAY_RECONNECT_MAX_DELAY_MS
    }
    return min(delay, maxDelay)
  }

  private fun cancelAutoReconnect(resetAttempt: Boolean = true) {
    reconnectScheduled = false
    reconnectHandler.removeCallbacksAndMessages(null)
    reconnectSessionRestoreScheduled = false
    if (resetAttempt) {
      reconnectAttempt = 0
    }
  }

  private fun requestSession(isRecovery: Boolean = false) {
    val targetDeviceId = binding.targetDeviceInput.text.toString().trim()
    if (targetDeviceId.isBlank()) {
      appendLog("请先填写目标设备 ID")
      switchPage(MainPage.MY_DEVICES)
      return
    }
    if (!isSocketConnected || !isRegistered) {
      appendLog("连接与注册完成后才能发起会话")
      return
    }
    if (!isPresenceReady) {
      appendLog("请先同步在线状态，再发起远程会话")
      return
    }
    val targetDevice = relayDevices.find { it.deviceId == targetDeviceId }
    if (targetDevice != null && !targetDevice.canReceiveRemoteControl()) {
      appendLog("目标设备未上报可被控制能力，请选择 Windows/mac 受控端")
      switchPage(MainPage.MY_DEVICES)
      return
    }
    persistConnectionSettings(
      wsUrl = binding.wsUrlInput.text.toString().trim(),
      targetDeviceId = targetDeviceId,
    )
    if (!isRecovery) {
      sessionEndRequestedByUser = false
      clearSessionRecoveryIntent("manual_session_request")
    } else {
      isRecoveringSession = true
    }
    // long: 真机手机具备比模拟器更稳定的解码与网络表现，首档直接给桌面端 720p 目标，避免远控 Mac 时长期停在低清晰度档位。
    val controllerProfile = if (isLikelyEmulator()) "emulator" else "android_phone"
    val request = controller.requestSessionMessage(targetDeviceId, controllerProfile = controllerProfile)
    if (sendSocketMessage(request.message, "发送 session.request.req -> $targetDeviceId")) {
      pendingSessionRequest = true
      pendingSessionRequestId = request.requestId
      pendingSessionRequestIsRecovery = isRecovery
      setStatus(if (isRecovery) "短断线恢复中" else "会话请求中")
      updateSessionButtonState()
    }
  }

  private fun handleTargetDeviceOffline(offlineDeviceId: String, source: String): Boolean {
    val targetDeviceId = when {
      hasActiveSession() && !activeSessionPeerDeviceId.isNullOrBlank() -> activeSessionPeerDeviceId.orEmpty()
      else -> binding.targetDeviceInput.text.toString().trim()
    }
    relayDevices = relayDevices.filterNot { it.deviceId == offlineDeviceId }
    updateDevicesStatus(buildDevicesStatus(relayDevices))
    renderDeviceList()
    appendLog("目标设备离线（$offlineDeviceId），来源=$source")

    if (targetDeviceId != offlineDeviceId) {
      return false
    }
    val wasSessionActive = hasActiveSession() || pendingSessionRequest
    if (!wasSessionActive) {
      setStatus(readyStatusText("目标设备离线"))
      updateSessionButtonState()
      return false
    }
    val shouldRecover = rememberSessionRecoveryIntent(reason = "target_offline_$source")
    pendingSessionRequest = false
    pendingSessionRequestId = null
    pendingSessionRequestIsRecovery = false
    isPresenceReady = true
    if (shouldRecover) {
      resetSessionUi(clearFrame = false)
      binding.frameMetaText.text = "当前画面：原 Mac 短暂离线，等待重新上线"
      setStatus("短断线恢复等待目标上线")
      updateSessionButtonState()
      scheduleSessionRecoveryTargetWait(source = "target_offline_$source", targetDeviceId = targetDeviceId)
      return true
    }
    clearSessionRecoveryIntent("target_offline_$source")
    resetSessionUi(clearFrame = true)
    binding.remoteFrameCard.isVisible = false
    setStatus(readyStatusText("目标设备离线"))
    updateSessionButtonState()
    appendLog("当前控制目标已离线，已自动关闭远端画面")
    return false
  }

  private fun readyStatusText(base: String): String {
    return when {
      !isSocketConnected -> base
      hasActiveSession() -> "会话中"
      isPresenceReady -> "$base（可发起会话）"
      isRegistered -> "$base（待同步在线状态）"
      else -> "$base（待注册）"
    }
  }

  private fun resolveActiveSessionId(): String? {
    val direct = sessionId?.trim().takeIf { !it.isNullOrBlank() }
    if (direct != null) {
      return direct
    }
    return rtcCurrentSessionId?.trim().takeIf { !it.isNullOrBlank() }
  }

  private fun hasActiveSession(): Boolean = !resolveActiveSessionId().isNullOrBlank()

  private fun persistConnectionSettings(wsUrl: String, targetDeviceId: String) {
    preferences.edit {
      putString(PREF_WS_URL, normalizeWsUrl(wsUrl))
      putString(PREF_TARGET_DEVICE_ID, targetDeviceId)
    }
  }

  private fun formatFrameSize(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) {
      return "-"
    }
    return "${width}x${height}"
  }

  private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0) {
      return "-"
    }
    return timeFormatter.format(Date(timestamp))
  }

  private fun formatSessionDuration(durationMs: Long): String {
    if (durationMs <= 0L) {
      return "00:00:00"
    }
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
  }

  private fun updateSessionTimingUi(nowMs: Long = System.currentTimeMillis()) {
    if (!hasActiveSession() || sessionStartedAtWallClockMs <= 0L) {
      binding.sessionStartText.text = "会话开始：-"
      binding.sessionDurationText.text = "会话时长：-"
      return
    }
    val elapsedMs = (nowMs - sessionStartedAtWallClockMs).coerceAtLeast(0L)
    binding.sessionStartText.text = "会话开始：${formatTimestamp(sessionStartedAtWallClockMs)}"
    binding.sessionDurationText.text = "会话时长：${formatSessionDuration(elapsedMs)}"
  }

  private fun startSessionClockTicker(activeSessionId: String?) {
    stopSessionClockTicker()
    if (activeSessionId.isNullOrBlank() || sessionStartedAtWallClockMs <= 0L) {
      return
    }
    val token = sessionClockToken + 1L
    sessionClockToken = token
    updateSessionTimingUi()
    Log.i(
      RTC_TAG,
      "session_clock_start session=$activeSessionId started_at=${logLineFormatter.format(Date(sessionStartedAtWallClockMs))}",
    )
    val ticker = object : Runnable {
      override fun run() {
        if (!isActivityAlive || sessionClockToken != token || !hasActiveSession()) {
          return
        }
        updateSessionTimingUi()
        sessionClockHandler.postDelayed(this, 1000L)
      }
    }
    sessionClockHandler.postDelayed(ticker, 1000L)
  }

  private fun stopSessionClockTicker() {
    sessionClockToken += 1L
    sessionClockHandler.removeCallbacksAndMessages(null)
  }

  private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.2f", value)

  private fun normalizeWsUrl(raw: String): String {
    if (raw.isBlank()) {
      return ""
    }
    val normalized = raw
      .trim()
      .replace("：", ":")
      .replace("／", "/")
      .replace("。", ".")
      .replace("＼", "/")
      .replace("　", " ")
      .replace("​", "")
      .replace(Regex("\\s+"), "")

    val fixedScheme = when {
      normalized.startsWith("我是://") -> "ws://${normalized.removePrefix("我是://")}"
      normalized.startsWith("我是s://") -> "wss://${normalized.removePrefix("我是s://")}"
      else -> normalized
    }

    return when {
      fixedScheme.startsWith("ws:/") && !fixedScheme.startsWith("ws://") -> fixedScheme.replaceFirst("ws:/", "ws://")
      fixedScheme.startsWith("wss:/") && !fixedScheme.startsWith("wss://") -> fixedScheme.replaceFirst("wss:/", "wss://")
      else -> fixedScheme
    }.replace("://10.0.2.2:18080/ws", "://10.0.2.2:18081/ws")
      .replace("://127.0.0.1:18080/ws", "://127.0.0.1:18081/ws")
      .replace("://localhost:18080/ws", "://localhost:18081/ws")
      .replace("://10.0.2.2:18080", "://10.0.2.2:18081")
      .replace("://127.0.0.1:18080", "://127.0.0.1:18081")
      .replace("://localhost:18080", "://localhost:18081")
  }

  private fun validateWsUrl(wsUrl: String): String? {
    val uri = Uri.parse(wsUrl)
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "ws" && scheme != "wss") {
      return "中继信令地址必须以 ws:// 或 wss:// 开头"
    }
    if (uri.host.isNullOrBlank()) {
      return "中继信令地址缺少主机名"
    }
    return null
  }

  private fun isLikelyEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase(Locale.ROOT)
    val model = Build.MODEL.lowercase(Locale.ROOT)
    val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
    val brand = Build.BRAND.lowercase(Locale.ROOT)
    val device = Build.DEVICE.lowercase(Locale.ROOT)
    val product = Build.PRODUCT.lowercase(Locale.ROOT)
    return fingerprint.startsWith("generic") ||
      fingerprint.contains("emulator") ||
      model.contains("emulator") ||
      model.contains("android sdk built for") ||
      manufacturer.contains("genymotion") ||
      (brand.startsWith("generic") && device.startsWith("generic")) ||
      product.contains("sdk")
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  private fun dpFloat(value: Int): Float = value * resources.displayMetrics.density

  private fun JSONObject?.optNonBlank(name: String): String? {
    val value = this?.optString(name).orEmpty()
    return value.takeIf { it.isNotBlank() }
  }

  private fun org.json.JSONArray.toStringList(): List<String> {
    val values = mutableListOf<String>()
    for (index in 0 until length()) {
      val value = optString(index).trim()
      if (value.isNotBlank()) {
        values += value
      }
    }
    return values
  }

  private fun appendLog(line: String) {
    val entry = "[${synchronized(logLineFormatter) { logLineFormatter.format(Date()) }}] $line"
    Log.i("RemoteDeskUi", entry)
    if (Looper.myLooper() == Looper.getMainLooper()) {
      renderLogEntry(entry)
      return
    }
    runOnUiThread {
      if (!::binding.isInitialized || isFinishing || isDestroyed) {
        return@runOnUiThread
      }
      renderLogEntry(entry)
    }
  }

  private fun initializeRemoteViewportControls() {
    remoteTouchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
    remoteScaleGestureDetector = ScaleGestureDetector(
      this,
      object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
          remoteSuppressTap = true
          return renderedFrameWidth > 0 && renderedFrameHeight > 0
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
          val oldScale = remoteViewportScale
          val nextScale = (oldScale * detector.scaleFactor)
            .coerceIn(REMOTE_VIEWPORT_MIN_SCALE, REMOTE_VIEWPORT_MAX_SCALE)
          if (abs(nextScale - oldScale) < 0.0005f) {
            return false
          }
          val ratio = nextScale / oldScale
          val focusX = detector.focusX
          val focusY = detector.focusY
          val newOffsetX = focusX - ((focusX - remoteViewportOffsetX) * ratio)
          val newOffsetY = focusY - ((focusY - remoteViewportOffsetY) * ratio)
          remoteViewportScale = nextScale
          remoteViewportOffsetX = clampRemoteViewportOffsetX(newOffsetX, nextScale)
          remoteViewportOffsetY = clampRemoteViewportOffsetY(newOffsetY, nextScale)
          applyRemoteViewportTransform()
          return true
        }
      },
    )

    binding.remoteZoomResetButton.setOnClickListener {
      resetRemoteViewportTransform(logReason = true)
    }
    binding.remoteFullscreenButton.setOnClickListener {
      toggleRemoteViewportFullscreen()
    }
    updateRemoteFullscreenButtonText()
  }

  private fun applyRemoteViewportTransform() {
    val tx = remoteViewportOffsetX
    val ty = remoteViewportOffsetY
    val scale = remoteViewportScale
    listOf(binding.remoteVideoView, binding.remoteFrameView).forEach { target ->
      target.pivotX = 0f
      target.pivotY = 0f
      target.scaleX = scale
      target.scaleY = scale
      target.translationX = tx
      target.translationY = ty
    }
  }

  private fun resetRemoteViewportTransform(logReason: Boolean) {
    remoteViewportScale = REMOTE_VIEWPORT_MIN_SCALE
    remoteViewportOffsetX = 0f
    remoteViewportOffsetY = 0f
    remotePanMoved = false
    remoteSuppressTap = false
    applyRemoteViewportTransform()
    if (logReason) {
      appendLog("远端画面缩放已重置为 1x")
    }
  }

  private fun clampRemoteViewportOffsetX(offsetX: Float, scale: Float): Float {
    val viewportWidth = binding.remoteViewportContainer.width.toFloat()
    if (viewportWidth <= 0f || scale <= REMOTE_VIEWPORT_MIN_SCALE) {
      return 0f
    }
    val minOffset = viewportWidth - (viewportWidth * scale)
    return offsetX.coerceIn(minOffset, 0f)
  }

  private fun clampRemoteViewportOffsetY(offsetY: Float, scale: Float): Float {
    val viewportHeight = binding.remoteViewportContainer.height.toFloat()
    if (viewportHeight <= 0f || scale <= REMOTE_VIEWPORT_MIN_SCALE) {
      return 0f
    }
    val minOffset = viewportHeight - (viewportHeight * scale)
    return offsetY.coerceIn(minOffset, 0f)
  }

  private fun toggleRemoteViewportFullscreen() {
    if (remoteFullscreenDialog == null) {
      enterRemoteViewportFullscreen()
    } else {
      exitRemoteViewportFullscreen(reason = "manual_toggle")
    }
  }

  private fun enterRemoteViewportFullscreen() {
    if (remoteFullscreenDialog != null) {
      return
    }
    val viewport = binding.remoteViewportContainer
    val originParent = viewport.parent as? ViewGroup ?: return
    val originLayoutParams = viewport.layoutParams ?: return
    remoteViewportOriginParent = originParent
    remoteViewportOriginLayoutParams = originLayoutParams
    remoteViewportOriginIndex = originParent.indexOfChild(viewport)
    originParent.removeView(viewport)

    remoteFullscreenHost = FrameLayout(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      )
      setBackgroundColor(Color.BLACK)
    }
    remoteFullscreenHost?.addView(
      viewport,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
      ),
    )

    val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    dialog.setCancelable(true)
    dialog.setContentView(remoteFullscreenHost!!)
    dialog.setOnDismissListener {
      if (remoteFullscreenDialog != null) {
        exitRemoteViewportFullscreen(reason = "dialog_dismiss")
      }
    }
    dialog.window?.setLayout(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
    )
    remoteFullscreenDialog = dialog
    remoteFullscreenPreviousOrientation = requestedOrientation
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    applyImmersiveMode(dialog, enabled = true)
    resetRemoteViewportTransform(logReason = false)
    dialog.show()
    applyImmersiveMode(dialog, enabled = true)
    updateRemoteFullscreenButtonText()
    appendLog("远端画面已进入全屏")
  }

  private fun exitRemoteViewportFullscreen(reason: String) {
    val dialog = remoteFullscreenDialog ?: return
    val viewport = binding.remoteViewportContainer
    val host = remoteFullscreenHost
    val originParent = remoteViewportOriginParent
    val originLayoutParams = remoteViewportOriginLayoutParams
    val originIndex = remoteViewportOriginIndex
    remoteFullscreenDialog = null
    remoteFullscreenHost = null
    remoteViewportOriginParent = null
    remoteViewportOriginLayoutParams = null
    remoteViewportOriginIndex = -1

    (viewport.parent as? ViewGroup)?.removeView(viewport)
    host?.removeView(viewport)
    if (originParent != null && originLayoutParams != null) {
      if (originIndex in 0..originParent.childCount) {
        originParent.addView(viewport, originIndex, originLayoutParams)
      } else {
        originParent.addView(viewport, originLayoutParams)
      }
    }
    if (dialog.isShowing) {
      dialog.dismiss()
    }
    requestedOrientation = remoteFullscreenPreviousOrientation
    remoteFullscreenPreviousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    applyImmersiveMode(dialog, enabled = false)
    resetRemoteViewportTransform(logReason = false)
    updateRemoteFullscreenButtonText()
    appendLog("远端画面已退出全屏（$reason）")
  }

  private fun updateRemoteFullscreenButtonText() {
    binding.remoteFullscreenButton.text = if (remoteFullscreenDialog == null) "全屏" else "退出全屏"
  }

  private fun applyImmersiveMode(dialog: Dialog, enabled: Boolean) {
    val window = dialog.window ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val controller = window.insetsController ?: return
      if (enabled) {
        controller.hide(
          android.view.WindowInsets.Type.statusBars() or
            android.view.WindowInsets.Type.navigationBars(),
        )
        controller.systemBarsBehavior =
          android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      } else {
        controller.show(
          android.view.WindowInsets.Type.statusBars() or
            android.view.WindowInsets.Type.navigationBars(),
        )
      }
      return
    }
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = if (enabled) {
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    } else {
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
  }

  private fun renderLogEntry(entry: String) {
    val snapshot = synchronized(logs) {
      logs.addFirst(entry)
      while (logs.size > 64) {
        logs.removeLast()
      }
      logs.toList()
    }
    binding.logText.text = snapshot.joinToString(separator = "\n")
  }

  private fun summarizeEnvelope(json: JSONObject, size: Int): String {
    val type = json.optString("type").ifBlank { "unknown" }
    val msgId = json.optString("msg_id").ifBlank { "-" }
    val session = json.optString("session_id").ifBlank { "-" }
    val trace = json.optString("trace_id").ifBlank { "-" }
    val payloadKeys = json.optJSONObject("payload")
      ?.keys()
      ?.asSequence()
      ?.toList()
      ?.joinToString(",")
      .orEmpty()
      .ifBlank { "-" }
    return "type=$type msg_id=$msgId session=$session trace=$trace payload_keys=$payloadKeys bytes=$size"
  }

  private class SimpleSdpObserver(
    private val onCreateSuccess: ((SessionDescription?) -> Unit)? = null,
    private val onSetSuccess: (() -> Unit)? = null,
    private val onCreateFailure: ((String?) -> Unit)? = null,
    private val onSetFailure: ((String?) -> Unit)? = null,
  ) : SdpObserver {
    override fun onCreateSuccess(sessionDescription: SessionDescription?) {
      onCreateSuccess?.invoke(sessionDescription)
    }

    override fun onSetSuccess() {
      onSetSuccess?.invoke()
    }

    override fun onCreateFailure(reason: String?) {
      onCreateFailure?.invoke(reason)
    }

    override fun onSetFailure(reason: String?) {
      onSetFailure?.invoke(reason)
    }
  }

  private enum class MainPage {
    MY_DEVICES,
    SETTINGS,
  }

  private data class NormalizedPoint(
    val x: Double,
    val y: Double,
  )

  private data class RemoteKey(
    val keyCode: String,
    val modifiers: List<String> = emptyList(),
  )

  private data class RemoteInputResult(
    val inputType: String = "",
    val inputCategory: String = "",
    val inputTraceId: String = "",
    val applied: Boolean = false,
    val executor: String = "",
    val statusCode: String = "",
    val statusDetail: String = "",
    val errorCode: String = "",
    val errorDetail: String = "",
    val summary: String = "",
    val inputCount: Long = 0L,
    val targetDeviceId: String = "",
  )

  private data class DecodedFrame(
    val bitmap: Bitmap? = null,
    val width: Int = 0,
    val height: Int = 0,
    val error: String? = null,
  )

  private data class RtcRenderQualitySample(
    val fps: Double,
    val windowMs: Long,
    val maxFrameGapMs: Long,
    val lowFpsStreakMs: Long,
  )

  private data class RtcRecentRenderQuality(
    val sampleCount: Int,
    val windowMs: Long,
    val fpsAvg: Double?,
    val maxFrameGapMs: Long,
    val lowFpsStreakMs: Long,
  )

  private data class RtcDroppedFrameSample(
    val framesDropped: Long,
    val droppedSpike: Long,
  )

  private data class SdpIceCandidateLine(
    val candidate: String,
    val sdpMid: String,
    val sdpMLineIndex: Int,
  )

  private data class RtcNetworkStatsSnapshot(
    val summary: String,
    val candidatePath: String,
    val candidateTier: String,
    val pairState: String,
    val rttMs: Double?,
  )

  private data class RelayDevice(
    val deviceId: String,
    val deviceName: String,
    val userId: String,
    val platform: String,
    val role: String,
    val status: String,
    val canControl: Boolean,
    val canBeControlled: Boolean,
  ) {
    fun isReachable(): Boolean = status.equals("online", ignoreCase = true) || status.equals("busy", ignoreCase = true)
    fun canReceiveRemoteControl(): Boolean = canBeControlled

    fun statusLabel(): String {
      return when {
        status.equals("busy", ignoreCase = true) -> "忙碌中"
        status.equals("online", ignoreCase = true) -> "在线"
        status.equals("offline", ignoreCase = true) -> "离线"
        status.isBlank() -> "未知状态"
        else -> status
      }
    }

    fun platformLabel(): String {
      return when (platform.lowercase(Locale.ROOT)) {
        "macos" -> "苹果电脑"
        "windows" -> "Windows"
        "android" -> "安卓"
        "ios" -> "苹果手机"
        "linux" -> "Linux"
        else -> platform.ifBlank { "未知平台" }
      }
    }
  }
}
