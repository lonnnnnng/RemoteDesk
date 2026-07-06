package com.remotedesk.app.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
import org.webrtc.EglBase
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.PlatformSoftwareVideoDecoderFactory
import org.webrtc.Predicate
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import org.webrtc.RendererCommon
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SurfaceViewRenderer
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoDecoderFallback
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
  companion object {
    private const val RTC_TAG = "RemoteDeskRtc"
    private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    private const val MAX_FRAME_B64_LENGTH = 12 * 1024 * 1024
    private const val MAX_FRAME_DIMENSION = 4096
    private const val LEGACY_BITMAP_REUSE_POOL_LIMIT = 2
    private const val PREFS_NAME = "remote_desk_demo"
    private const val PREF_DEVICE_ID = "device_id"
    private const val PREF_WS_URL = "ws_url"
    private const val PREF_TARGET_DEVICE_ID = "target_device_id"
    private const val EXTRA_WS_URL = "rd_ws_url"
    private const val EXTRA_TARGET_DEVICE_ID = "rd_target_device_id"
    private const val EXTRA_AUTO_CONNECT = "rd_auto_connect"
    private const val EXTRA_AUTO_REQUEST_SESSION = "rd_auto_request_session"
    private const val EXTRA_AUTO_PROOF_INPUT = "rd_auto_proof_input"
    private const val EXTRA_DEBUG_SET_CLIPBOARD_TEXT = "rd_debug_set_clipboard_text"
    private const val EXTRA_DEBUG_SEND_CLIPBOARD_TEXT = "rd_debug_send_clipboard_text"
    private const val EXTRA_DEBUG_SEND_CLIPBOARD_FROM_SYSTEM = "rd_debug_send_clipboard_from_system"
    private const val EXTRA_DEBUG_DUMP_CLIPBOARD_TEXT = "rd_debug_dump_clipboard_text"
    private const val EXTRA_DEBUG_SEND_FILE_PATH = "rd_debug_send_file_path"
    private const val EXTRA_DEBUG_SEND_VIEWPORT_INTERACTION = "rd_debug_send_viewport_interaction"
    private const val EXTRA_DEBUG_VIEWPORT_SCALE = "rd_debug_viewport_scale"
    private const val EXTRA_DEBUG_VIEWPORT_X = "rd_debug_viewport_x"
    private const val EXTRA_DEBUG_VIEWPORT_Y = "rd_debug_viewport_y"
    private const val EXTRA_DEBUG_VIEWPORT_WIDTH = "rd_debug_viewport_width"
    private const val EXTRA_DEBUG_VIEWPORT_HEIGHT = "rd_debug_viewport_height"
    private const val EXTRA_DEBUG_VIEWPORT_FOCUS_X = "rd_debug_viewport_focus_x"
    private const val EXTRA_DEBUG_VIEWPORT_FOCUS_Y = "rd_debug_viewport_focus_y"
    private const val EXTRA_DEBUG_FULLSCREEN = "rd_debug_fullscreen"
    private const val EXTRA_DEBUG_FULLSCREEN_ENABLED = "rd_debug_fullscreen_enabled"
    private const val EXTRA_DEBUG_SIMULATE_PINCH = "rd_debug_simulate_pinch"
    private const val EXTRA_DEBUG_PINCH_CENTER_X = "rd_debug_pinch_center_x"
    private const val EXTRA_DEBUG_PINCH_CENTER_Y = "rd_debug_pinch_center_y"
    private const val EXTRA_DEBUG_PINCH_START_SPAN = "rd_debug_pinch_start_span"
    private const val EXTRA_DEBUG_PINCH_END_SPAN = "rd_debug_pinch_end_span"
    private const val EXTRA_DEBUG_PINCH_STEPS = "rd_debug_pinch_steps"
    private const val EXTRA_DEBUG_PINCH_INTERVAL_MS = "rd_debug_pinch_interval_ms"
    private const val DEBUG_TOOL_RETRY_DELAY_MS = 500L
    private const val DEBUG_TOOL_MAX_ATTEMPTS = 20
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
    // 作者: long；PeerConnection 创建是最终媒体链路的入口，真机 native 层卡死时要先释放信令线程，避免 answer/ICE 后续诊断全部丢失。
    private const val RTC_CREATE_PEER_CONNECTION_TIMEOUT_MS = 5000L
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
    private const val REMOTE_VIEWPORT_MAX_SCALE = 3.5f
    // 作者: long；远端鼠标按屏幕刷新节奏尾点合并发送，真实落账时间参与节流，避免 JPEG 解码挤压后旧触摸时间戳把 Mac 光标拖慢。
    private const val REMOTE_POINTER_MOVE_MIN_INTERVAL_MS = 12L
    private const val REMOTE_POINTER_MOVE_MIN_DELTA = 0.00032
    private const val REMOTE_POINTER_MOVE_TRAILING_MAX_DELAY_MS = 12L
    // 作者: long；最大缩放拖动视角时会同时发鼠标移动和 source_rect，单独降低鼠标 move 频率，避免 Mac 输入执行和 Android 全屏合成一起抢主线程。
    private const val REMOTE_ZOOM_PAN_POINTER_MOVE_MIN_INTERVAL_MS = 48L
    private const val REMOTE_ZOOM_PAN_POINTER_MOVE_MIN_DELTA = 0.0015
    private const val REMOTE_ZOOM_PAN_POINTER_MOVE_TRAILING_MAX_DELAY_MS = 48L
    private const val REMOTE_POINTER_MOVE_MAX_HISTORY_SAMPLES = 4
    private const val REMOTE_WHEEL_MIN_INTERVAL_MS = 40L
    private const val REMOTE_WHEEL_DELTA_PER_PIXEL = 3f
    private const val REMOTE_WHEEL_MIN_ABS_DELTA = 24
    private const val REMOTE_WHEEL_MAX_ABS_DELTA = 720
    private const val REMOTE_PINCH_SCALE_EPSILON = 0.00045f
    private const val REMOTE_PINCH_SCALE_FACTOR_SMOOTHING = 0.86f
    private const val REMOTE_PINCH_FOCUS_SMOOTHING = 0.74f
    private const val REMOTE_PINCH_END_SNAP_EPSILON = 0.003f
    private const val REMOTE_VIEWPORT_MIN_RENDER_SCALE = 0.5f
    private const val REMOTE_VIEWPORT_FULLSCREEN_BASE_RENDER_SCALE = 1.0f
    private const val REMOTE_VIEWPORT_MAX_RENDER_SCALE = REMOTE_VIEWPORT_MAX_SCALE
    private const val REMOTE_VIEWPORT_MAX_RENDER_PIXELS = 8_800_000
    // 作者: long；最大缩放真机压测出现 BLASTSyncEngine 回压后，全屏不再提交大于屏幕的承载面，清晰度交给 Mac source_rect 局部源补足。
    private const val REMOTE_VIEWPORT_FULLSCREEN_MAX_RENDER_SCALE = 1.0f
    private const val REMOTE_VIEWPORT_INTERACTION_RENDER_SCALE = 1.0f
    private const val REMOTE_VIEWPORT_FULLSCREEN_INTERACTION_RENDER_SCALE = 1.0f
    private const val REMOTE_VIEWPORT_RENDER_COMMIT_DELAY_MS = 140L
    private const val REMOTE_VIEWPORT_FULLSCREEN_RENDER_COMMIT_DELAY_MS = 240L
    private const val REMOTE_VIEWPORT_HARDWARE_LAYER_RELEASE_DELAY_MS = 420L
    private const val REMOTE_VIEWPORT_POST_PINCH_FULL_FRAME_FREEZE_MS = 1500L
    private const val REMOTE_VIEWPORT_SOURCE_RECT_FULL_FRAME_PROTECT_MS = 5200L
    private const val REMOTE_VIEWPORT_PINCH_PREVIEW_FRAME_INTERVAL_MS = 2200L
    private const val REMOTE_VIEWPORT_DETAIL_RENDER_MIN_SCALE = 1.08f
    private const val REMOTE_VIEWPORT_MIN_SOURCE_RECT_SIZE = 0.286
    private const val REMOTE_ZOOM_MOUSE_VIEWPORT_PAN_MIN_SCALE = 1.05f
    private const val REMOTE_ZOOM_MOUSE_VIEWPORT_DRAG_PAN_FACTOR = 0.72f
    private const val REMOTE_ZOOM_MOUSE_VIEWPORT_EDGE_ZONE_DP = 76
    private const val REMOTE_ZOOM_MOUSE_VIEWPORT_EDGE_MAX_STEP_DP = 18
    private const val REMOTE_VIEWPORT_INTERACTION_HINT_INTERVAL_MS = 160L
    // 作者: long；最大缩放拖动视角时，鼠标和裁剪源会同时推动 Mac/Android 两侧渲染；平移提示降到约 2fps，并在抬手时强制补最终视角，避免连续窗口裁剪重配触发系统合成回压。
    private const val REMOTE_VIEWPORT_PAN_HINT_INTERVAL_MS = 520L
    private const val REMOTE_VIEWPORT_PINCH_HINT_INTERVAL_MS = 220L
    private const val REMOTE_VIEWPORT_PINCH_END_DEDUPE_MS = 450L
    private const val REMOTE_FULLSCREEN_USE_TEXTURE_RENDERER = true
    private const val REMOTE_LOCAL_CURSOR_HIDE_DELAY_MS = 900L
    private const val REMOTE_LOCAL_CURSOR_PREDICTION_MS = 12f
    private const val REMOTE_LOCAL_CURSOR_MAX_PREDICTION_DP = 14
    private const val REMOTE_INPUT_MOVE_UI_UPDATE_INTERVAL_MS = 1200L
    private const val REMOTE_MOVE_SIGNAL_LOG_INTERVAL_MS = 1200L
    private const val REMOTE_FRAME_SIGNAL_LOG_INTERVAL_MS = 2000L
    private const val REMOTE_SOURCE_RECT_MATERIALIZED_LOG_INTERVAL_MS = 650L
    // 作者: long；最大缩放后的局部 JPEG 会连续刷新同一个全屏窗口，接收端再保底限制显示节奏，避免 Mac 端短时连发把系统窗口合成压到闪退/退后台。
    private const val REMOTE_FULLSCREEN_SOURCE_RECT_FRAME_MIN_INTERVAL_MS = 72L
    private const val REMOTE_ZOOM_LABEL_UPDATE_INTERVAL_MS = 220L
    private const val REMOTE_KEYBOARD_MAX_BATCH_CHARS = 64
    private const val REMOTE_VIEWPORT_SMALL_DEFAULT_HEIGHT_DP = 262
    private const val REMOTE_VIEWPORT_SMALL_MIN_HEIGHT_DP = 204
    private const val REMOTE_VIEWPORT_SMALL_MAX_HEIGHT_DP = 360
    private const val REMOTE_KEYBOARD_BUTTON_HEIGHT_DP = 30
    private const val SESSION_FILE_CHUNK_RAW_BYTES = 192 * 1024
    private const val ANDROID_INCOMING_FILE_MAX_BYTES = 64L * 1024L * 1024L
    private const val SESSION_FILE_MAX_CHUNKS = 512
    // 作者: long；远控会话的画面和输入不能依赖系统 MediaProvider，个别 ROM 上 MediaProvider ANR/死亡会把持有稳定 provider 连接的进程一并结束。
    private const val ANDROID_INCOMING_FILE_USE_MEDIASTORE = false
    private const val ANDROID_FILE_MEDIASTORE_TIMEOUT_MS = 8_000L
  }

  private lateinit var binding: ActivityMainBinding
  private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
  private val deviceId: String by lazy { resolveStableDeviceId() }
  private val controller: StubSessionController by lazy { StubSessionController(deviceId) }
  private var token: String = "stub-token"
  private var sessionId: String? = null
  private val logs = ArrayDeque<String>()
  private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
  private val logLineFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
  private val frameParseExecutor = Executors.newSingleThreadExecutor()
  private val frameDecodeExecutor = Executors.newSingleThreadExecutor()
  private val deviceSyncExecutor = Executors.newSingleThreadExecutor()
  private val fileTransferExecutor = Executors.newSingleThreadExecutor()
  private val rtcSignalingExecutor = Executors.newSingleThreadExecutor()
  private val devicesHttpClient = OkHttpClient()
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
  private var rtcRendererInitialized = false
  private var rtcTextureRendererInitialized = false
  private var rtcPeerConnection: PeerConnection? = null
  private var rtcRemoteVideoTrack: VideoTrack? = null
  private var rtcActiveVideoSink: VideoSink? = null
  private var rtcCurrentSessionId: String? = null
  private var rtcNegotiationOwner = RTC_NEGOTIATION_OWNER_UNKNOWN
  private var rtcControllerProfile = "standard"
  private var rtcIceServers: List<PeerConnection.IceServer> = emptyList()
  private var lastRtcStatsUiUpdateAtMs: Long = 0L
  private val reconnectHandler = Handler(Looper.getMainLooper())
  private val rtcWatchdogHandler = Handler(Looper.getMainLooper())
  private val sessionClockHandler = Handler(Looper.getMainLooper())
  private val autoProofHandler = Handler(Looper.getMainLooper())
  private val remoteGestureHandler = Handler(Looper.getMainLooper())
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
  private var legacyRenderFpsSampleSum = 0.0
  private var legacyRenderFpsSampleCount = 0L
  private val legacyFrameDecodeSeq = AtomicInteger(0)
  private var legacyDisplayedBitmap: Bitmap? = null
  private var legacyPreviousDisplayedBitmap: Bitmap? = null
  private val legacyReusableBitmaps = mutableListOf<Bitmap>()
  private var remoteFrameSourceRect = NormalizedRect(0.0, 0.0, 1.0, 1.0)
  private var lastLegacyFrameUiUpdateAtMs = 0L
  private var lastLegacyFrameIgnoredLogAtMs = 0L
  private var legacyPinchFrameSuppressedCount = 0
  private var legacyPostPinchFrameSuppressedCount = 0
  private var legacySourceRectFullFrameSuppressedCount = 0
  private var legacyPinchPreviewFrameAtMs = 0L
  @Volatile
  private var legacyFullFrameFreezeUntilMs = 0L
  @Volatile
  private var legacySourceRectFullFrameProtectUntilMs = 0L
  private val legacyInteractionFrameGateLock = Any()
  @Volatile
  private var legacyPinchFrameGateActive = false
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
  private var remotePendingMoveSessionId: String? = null
  private var remotePendingMovePoint: NormalizedPoint? = null
  private var remotePendingMoveCategory = "pointer"
  private var remotePendingMoveAtMs = 0L
  private var remotePendingMoveRunnable: Runnable? = null
  private var remoteFrameMoveSessionId: String? = null
  private var remoteFrameMovePoint: NormalizedPoint? = null
  private var remoteFrameMoveCategory = "pointer"
  private var remoteFrameMoveProfile = "pointer"
  private var remoteFrameMoveAtMs = 0L
  private var remoteFrameMoveDispatchScheduled = false
  private var remoteFrameMoveDispatchToken = 0L
  private var remoteMouseViewportPanSourceRect: NormalizedRect? = null
  private var remoteScrollGestureActive = false
  private var remoteScrollLastFocusX = 0f
  private var remoteScrollLastFocusY = 0f
  private var remoteLastWheelAtMs = 0L
  private var remoteMultiTouchStartSpan = 0f
  private var remoteLongPressDragArmed = false
  private var remoteLongPressRunnable: Runnable? = null
  private var remotePinchZoomActive = false
  private var remoteManualPinchActive = false
  private var remoteManualPinchLastSpan = 0f
  private var remoteAccumulatedPinchScaleFactor = 1f
  private var remotePinchFocusX = 0f
  private var remotePinchFocusY = 0f
  private var remotePinchFocusInitialized = false
  private var remoteViewportTransformApplyScheduled = false
  private var remoteViewportTransformCommitRequested = false
  private var remoteViewportRenderScale = REMOTE_VIEWPORT_MIN_SCALE
  private var remoteViewportRenderBoundsDirty = false
  private var remoteZoomResetButtonLabel = "1x"
  private var remoteLastPinchScaleLogAtMs = 0L
  private var remoteLastRenderScaleCommitAtMs = 0L
  private var remoteLastViewportInteractionHintAtMs = 0L
  private var remoteLastPinchEndHintAtMs = 0L
  private var remoteLastPinchEndHintKey = ""
  private var remotePinchViewportSourceRect: NormalizedRect? = null
  private var remotePinchViewportLargestSourceRect: NormalizedRect? = null
  private var remoteSourceRectPreviewScale = 1f
  private var remoteSourceRectPreviewOffsetX = 0f
  private var remoteSourceRectPreviewOffsetY = 0f
  private var remoteLastSourceRectMaterializedLogAtMs = 0L
  private var remoteLastFullscreenSourceRectFrameDisplayedAtMs = 0L
  private var remoteFullscreenSourceRectFrameSuppressedCount = 0
  private var remoteLastZoomResetLabelUpdateAtMs = 0L
  private var remoteViewportRenderCommitRunnable: Runnable? = null
  private var remoteViewportHardwareLayerReleaseRunnable: Runnable? = null
  private var remoteLocalCursorHideRunnable: Runnable? = null
  private var remoteLastMoveUiUpdateAtMs = 0L
  private var remoteLastMoveSignalLogAtMs = 0L
  private var remoteLastFrameSignalLogAtMs = 0L
  private var remoteLocalCursorApplyScheduled = false
  private var remotePendingLocalCursorX = 0f
  private var remotePendingLocalCursorY = 0f
  private var remoteLastLocalCursorTouchX = 0f
  private var remoteLastLocalCursorTouchY = 0f
  private var remoteLastLocalCursorTouchAtMs = 0L
  private var remoteOverlayControlTouchActive = false
  private var remoteDebugPinchToken = 0L
  private var remoteDebugPinchDispatchActive = false
  private var remoteSuppressedMoveSignalLogCount = 0
  private var remoteSuppressedFrameSignalLogCount = 0
  private var remoteSuppressedMoveAckCount = 0
  private var remoteSuppressedMoveResultCount = 0
  private var remoteInputResultCount = 0L
  private var remoteInputResultAppliedCount = 0L
  private var remoteInputResultFailedCount = 0L
  private var lastRemoteInputResult = RemoteInputResult()
  private val remoteInputAppliedCategories = linkedSetOf<String>()
  private var lastLiveE2EProofReportAtMs = 0L
  private var lastLiveE2EProofReportKey = ""
  private lateinit var remoteScaleGestureDetector: ScaleGestureDetector
  private var remoteFullscreenActive = false
  private var remoteFullscreenHost: FrameLayout? = null
  private var remoteViewportOriginParent: ViewGroup? = null
  private var remoteViewportOriginLayoutParams: ViewGroup.LayoutParams? = null
  private var remoteViewportOriginIndex = -1
  private var remoteFullscreenPreviousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
  private var remoteKeyboardDialog: Dialog? = null
  private val remoteKeyboardActiveModifiers = linkedSetOf<String>()
  private val remoteKeyboardModifierButtons = mutableMapOf<String, MaterialButton>()
  private val incomingFileTransfers = mutableMapOf<String, IncomingFileTransfer>()
  private val pendingSharedFileUris = ArrayDeque<Uri>()
  private var pendingSharedClipboardText: String? = null
  private val remoteFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) {
      updateRemoteTransferStatus("文件：已取消选择")
      return@registerForActivityResult
    }
    persistDocumentReadPermissionIfPossible(uri)
    updateRemoteTransferStatus("文件：已选择本机文件，准备发送")
    sendAndroidFileToRemote(uri)
  }
  private val rtcProbeSink = VideoSink { frame ->
    if (!isActivityAlive) {
      return@VideoSink
    }
    val frameWidth = frame.rotatedWidth
    val frameHeight = frame.rotatedHeight
    val frameSizeChanged = frameWidth > 0 && frameHeight > 0 &&
      (frameWidth != renderedFrameWidth || frameHeight != renderedFrameHeight)
    renderedFrameWidth = frameWidth
    renderedFrameHeight = frameHeight
    if (frameSizeChanged) {
      runOnUiThread {
        if (!isActivityAlive) {
          return@runOnUiThread
        }
        updateRemoteViewportAspect(frameWidth, frameHeight)
      }
    }
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
        restoreWebRtcRendererAfterFrame("first_frame")
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
      restoreWebRtcRendererAfterFrame("stats_tick")
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
      if (isWebRtcSignalMessage(text)) {
        // 作者: long；真机上创建 PeerConnection 可能阻塞数秒，WebRTC 信令放到独立串行线程，避免主线程卡住后 UI 树和后续 offer/ICE 都停在旧会话。
        rtcSignalingExecutor.execute {
          parseMessage(text)
        }
        return@StubSocketClient
      }
      if (isLegacyFramePushMessage(text)) {
        // 作者: long；JPEG 兜底帧包含大 Base64，先在解码线程解析 JSON，避免主线程因为连续大消息解析触发输入分发超时。
        frameParseExecutor.execute {
          parseLegacyFramePushMessage(text)
        }
        return@StubSocketClient
      }
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
    // 作者: long；远控会话需要持续观察画面和接收手势，Activity 亮屏锁放在窗口层，避免只有视频 View 可见时才生效导致真机测试中途锁屏。
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
    binding.devicesRefreshButton.setOnClickListener {
      refreshDevicesList(force = true, userInitiated = true)
    }
    binding.sessionBackButton.setOnClickListener {
      switchPage(MainPage.MY_DEVICES)
    }
    binding.sessionEndButton.setOnClickListener {
      if (hasActiveSession() || pendingSessionRequest || isRecoveringSession) {
        handleEndSessionAction()
      } else {
        switchPage(MainPage.MY_DEVICES)
      }
    }
    binding.debugModeSwitch.setOnCheckedChangeListener { _, _ ->
      updateDebugPanelVisibility()
    }
    binding.remoteTouchLayer.setOnTouchListener { view, event ->
      handleRemoteFrameTouchV2(view, event)
    }
    initializeRemoteViewportControls()
    initializeRemoteKeyboardControls()
    initializeSessionToolControls()
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
    handleExternalShareIntent(intent)
    handleDebugToolIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleExternalShareIntent(intent)
    handleDebugToolIntent(intent)
  }

  private fun resolveStableDeviceId(): String {
    val savedDeviceId = preferences.getString(PREF_DEVICE_ID, null).orEmpty().trim()
    if (savedDeviceId.isNotBlank()) {
      return savedDeviceId
    }

    val androidId = Settings.Secure
      .getString(contentResolver, Settings.Secure.ANDROID_ID)
      .orEmpty()
      .trim()
      .lowercase(Locale.ROOT)
    val stableSuffix = if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
      androidId
    } else {
      UUID.randomUUID().toString()
    }
    val nextDeviceId = "android-$stableSuffix"
    // 作者: long；中继按 device_id 合并在线状态，安卓端跨重启复用同一 ID，避免一次真机反复连接后被展示成多台设备。
    preferences.edit { putString(PREF_DEVICE_ID, nextDeviceId) }
    return nextDeviceId
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
    frameParseExecutor.shutdownNow()
    frameDecodeExecutor.shutdownNow()
    deviceSyncExecutor.shutdownNow()
    fileTransferExecutor.shutdownNow()
    rtcSignalingExecutor.shutdownNow()
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

  private fun isWebRtcSignalMessage(text: String): Boolean {
    return try {
      when (JSONObject(text).optString("type")) {
        "webrtc.offer", "webrtc.answer", "webrtc.ice_candidate", "webrtc.restart_ice" -> true
        else -> false
      }
    } catch (_: Exception) {
      false
    }
  }

  private fun isLegacyFramePushMessage(text: String): Boolean =
    text.contains("\"type\":\"screen.frame.push\"") || text.contains("\"type\": \"screen.frame.push\"")

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

  @Synchronized
  private fun initializeWebRtc() {
    if (rtcFactory != null) {
      return
    }
    val startedAtMs = SystemClock.elapsedRealtime()
    // 作者: long；真机上 WebRTC factory 创建可能阻塞，日志按步骤打点，避免 answer 卡住时只能看到旧会话 UI 而不知道停在哪个 native 阶段。
    Log.i(RTC_TAG, "webrtc_init_step start thread=${Thread.currentThread().name}")
    ensureWebRtcRendererInitializedBlocking()
    Log.i(RTC_TAG, "webrtc_init_step renderer_ready elapsed_ms=${SystemClock.elapsedRealtime() - startedAtMs}")
    val initOptions = PeerConnectionFactory.InitializationOptions
      .builder(applicationContext)
      .createInitializationOptions()
    Log.i(RTC_TAG, "webrtc_init_step init_options_created elapsed_ms=${SystemClock.elapsedRealtime() - startedAtMs}")
    PeerConnectionFactory.initialize(initOptions)
    Log.i(RTC_TAG, "webrtc_init_step factory_initialized elapsed_ms=${SystemClock.elapsedRealtime() - startedAtMs}")
    val decoderFactory = createRemoteDeskVideoDecoderFactory()
    Log.i(
      RTC_TAG,
      "webrtc_init_step decoder_factory_created class=${decoderFactory.javaClass.simpleName} elapsed_ms=${SystemClock.elapsedRealtime() - startedAtMs}",
    )
    val builder = PeerConnectionFactory.builder()
    Log.i(RTC_TAG, "webrtc_init_step builder_created elapsed_ms=${SystemClock.elapsedRealtime() - startedAtMs}")
    builder.setVideoDecoderFactory(decoderFactory)
    Log.i(RTC_TAG, "webrtc_init_step decoder_factory_set elapsed_ms=${SystemClock.elapsedRealtime() - startedAtMs}")
    // 作者: long；Android 控制端这里只接收 Mac 视频，不发布本地视频；不创建 encoder factory 可以避开部分 MTK 机型枚举硬编时拖住 answer。
    rtcFactory = builder.createPeerConnectionFactory()
    Log.i(RTC_TAG, "webrtc_init_step complete elapsed_ms=${SystemClock.elapsedRealtime() - startedAtMs}")
  }

  private fun ensureWebRtcRendererInitializedBlocking() {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      ensureWebRtcRendererInitializedOnMain()
      return
    }
    if (rtcRendererInitialized && rtcEglBase != null) {
      return
    }
    val latch = CountDownLatch(1)
    var initError: Throwable? = null
    runOnUiThread {
      try {
        ensureWebRtcRendererInitializedOnMain()
      } catch (error: Throwable) {
        initError = error
      } finally {
        latch.countDown()
      }
    }
    if (!latch.await(3, TimeUnit.SECONDS)) {
      throw IllegalStateException("初始化 WebRTC 渲染层超时")
    }
    initError?.let { throw it }
  }

  private fun ensureWebRtcRendererInitializedOnMain() {
    if (rtcRendererInitialized && rtcEglBase != null) {
      return
    }
    if (rtcEglBase == null) {
      rtcEglBase = EglBase.create()
      Log.i(RTC_TAG, "webrtc_init_step egl_created")
    }
    // 作者: long；远端画面仍使用普通 Surface 层级承载，避免全屏时 overlay surface 盖住控制层或在真机截屏中表现成整屏黑图。
    binding.remoteVideoView.setZOrderMediaOverlay(false)
    if (!rtcRendererInitialized) {
      binding.remoteVideoView.init(rtcEglBase?.eglBaseContext, null)
      // 作者: long；远控画面必须完整显示电脑屏幕，WebRTC 默认缩放策略不能依赖库版本，否则全屏横屏时容易被填充裁剪。
      binding.remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
      binding.remoteVideoView.setEnableHardwareScaler(true)
      binding.remoteVideoView.setMirror(false)
      rtcRendererInitialized = true
      Log.i(RTC_TAG, "webrtc_init_step surface_renderer_initialized")
    }
    if (REMOTE_FULLSCREEN_USE_TEXTURE_RENDERER) {
      // 作者: long；TextureView 只是全屏渲染 A/B 路径，默认关闭时不初始化，避免真机多建一个 EglRenderer 干扰主渲染链路。
      binding.remoteTextureVideoView.init(rtcEglBase?.eglBaseContext)
      rtcTextureRendererInitialized = true
      Log.i(RTC_TAG, "webrtc_init_step texture_renderer_initialized")
    }
  }

  private fun createRemoteDeskVideoDecoderFactory(): VideoDecoderFactory {
    val eglContext = rtcEglBase?.eglBaseContext
    val shouldAvoidMtkAvc = shouldAvoidMtkAvcHardwareDecoder()
    if (shouldAvoidMtkAvc) {
      Log.w(
        RTC_TAG,
        "decoder_hardware_factory_retry reason=mtk_software_avc_init_timeout model=${Build.MODEL} device=${Build.DEVICE} sdk=${Build.VERSION.SDK_INT}",
      )
      logH264DecoderCandidatesForMtk()
      // 作者: long；MTK Android 14 在 PeerConnection 建立阶段枚举硬解会卡住，首轮协商只暴露静态 H.264 能力，真正解码时再走受超时保护的软件 AVC 兜底。
      return RemoteDeskMtkSafeH264DecoderFactory(eglContext)
    }
    val hardwarePredicate = Predicate<MediaCodecInfo> { codecInfo ->
      // 作者: long；Redmi Note 8 Pro 的 MTK AVC 硬解会成功建链但不吐帧，只对白名单机型屏蔽，避免影响其他设备的正常硬解。
      val blocked = shouldAvoidMtkAvc && isMtkAvcHardwareDecoder(codecInfo)
      if (blocked) {
        Log.w(
          RTC_TAG,
          "decoder_hardware_blocked name=${codecInfo.name} reason=mtk_avc_zero_frame_workaround model=${Build.MODEL} device=${Build.DEVICE} sdk=${Build.VERSION.SDK_INT}",
        )
      }
      !blocked
    }
    return RemoteDeskVideoDecoderFactory(eglContext, hardwarePredicate)
  }

  private fun shouldAvoidMtkAvcHardwareDecoder(): Boolean {
    val deviceProfile = listOf(
      Build.MANUFACTURER,
      Build.MODEL,
      Build.DEVICE,
      Build.HARDWARE,
      Build.PRODUCT,
    ).joinToString(" ").lowercase(Locale.US)
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
      (deviceProfile.contains("redmi note 8 pro") ||
        deviceProfile.contains("begonia") ||
        deviceProfile.contains("mt6785"))
  }

  private fun isMtkAvcHardwareDecoder(codecInfo: MediaCodecInfo): Boolean {
    val codecName = codecInfo.name.lowercase(Locale.US)
    val isAvcDecoder = codecInfo.supportedTypes.any { type ->
      type.equals("video/avc", ignoreCase = true)
    }
    return isAvcDecoder && codecName.startsWith("omx.mtk.")
  }

  private fun releaseWebRtc() {
    rtcRemoteVideoTrack?.let { removeRemoteVideoSinks(it) }
    rtcActiveVideoSink = null
    if (rtcRendererInitialized) {
      binding.remoteVideoView.release()
      rtcRendererInitialized = false
    }
    if (rtcTextureRendererInitialized) {
      binding.remoteTextureVideoView.release()
      rtcTextureRendererInitialized = false
    }
    rtcPeerConnection?.dispose()
    rtcPeerConnection = null
    rtcRemoteVideoTrack?.dispose()
    rtcRemoteVideoTrack = null
    rtcFactory?.dispose()
    rtcFactory = null
    rtcEglBase?.release()
    rtcEglBase = null
  }

  private fun clearRemoteVideoRendererImage() {
    if (rtcRendererInitialized) {
      binding.remoteVideoView.clearImage()
    }
    if (rtcTextureRendererInitialized) {
      binding.remoteTextureVideoView.clearImage()
    }
  }

  private fun closeWebRtcSession(reason: String = "reset", clearRendererImage: Boolean = true) {
    stopRtcWatchdog(resetState = false)
    val closingSessionId = rtcCurrentSessionId
    if (!closingSessionId.isNullOrBlank()) {
      logRtcSessionSummary(closingSessionId, reason)
    }
    rtcRemoteVideoTrack?.let { removeRemoteVideoSinks(it) }
    rtcRemoteVideoTrack = null
    rtcActiveVideoSink = null
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
      clearRemoteVideoRendererImage()
    }
    updateLiveMetricsPanel()
  }

  private fun removeRemoteVideoSinks(track: VideoTrack) {
    rtcActiveVideoSink?.let { track.removeSink(it) }
    track.removeSink(binding.remoteVideoView)
    if (rtcTextureRendererInitialized) {
      track.removeSink(binding.remoteTextureVideoView)
    }
    track.removeSink(rtcProbeSink)
  }

  private fun activateRemoteVideoRenderer(useTexture: Boolean) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      runOnUiThread {
        if (isActivityAlive) {
          activateRemoteVideoRenderer(useTexture)
        }
      }
      return
    }
    val textureAvailable = useTexture && rtcTextureRendererInitialized
    val nextSink: VideoSink = if (textureAvailable) {
      binding.remoteTextureVideoView
    } else {
      binding.remoteVideoView
    }
    if (rtcActiveVideoSink !== nextSink) {
      rtcRemoteVideoTrack?.let { track ->
        rtcActiveVideoSink?.let { track.removeSink(it) }
        track.addSink(nextSink)
      }
      rtcActiveVideoSink = nextSink
      Log.i(
        RTC_TAG,
        "remote_video_renderer_switch mode=${if (textureAvailable) "texture" else "surface"} fullscreen=$remoteFullscreenActive",
      )
    }
    binding.remoteVideoView.isVisible = !textureAvailable
    binding.remoteTextureVideoView.isVisible = textureAvailable
  }

  private fun restoreWebRtcRendererAfterFrame(reason: String) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      runOnUiThread {
        if (isActivityAlive) {
          restoreWebRtcRendererAfterFrame(reason)
        }
      }
      return
    }
    val legacyVisible = binding.remoteFrameView.isVisible
    val rendererHidden = !binding.remoteVideoView.isVisible && !binding.remoteTextureVideoView.isVisible
    val hadLegacyBitmap = legacyDisplayedBitmap != null || legacyPreviousDisplayedBitmap != null
    if (!legacyVisible && !rendererHidden && !hadLegacyBitmap) {
      return
    }
    // 作者: long；legacy JPEG 只是 WebRTC 首帧超时兜底，一旦 H.264 真帧恢复，必须立刻退回 RTC renderer，否则用户会一直看到旧的低清 Bitmap。
    if (hadLegacyBitmap) {
      clearLegacyFrameBitmap()
    }
    if (legacyVisible) {
      binding.remoteFrameView.isVisible = false
    }
    resetLegacyFailureThrottleState()
    activateRemoteVideoRenderer(useTexture = remoteFullscreenActive)
    Log.i(
      RTC_TAG,
      "webrtc_renderer_restored session=${rtcCurrentSessionId ?: sessionId ?: "-"} reason=$reason fullscreen=$remoteFullscreenActive legacy_visible=$legacyVisible renderer_hidden=$rendererHidden",
    )
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
    legacyRenderFpsSampleSum = 0.0
    legacyRenderFpsSampleCount = 0L
    legacyFrameDecodeSeq.incrementAndGet()
    lastLegacyFrameUiUpdateAtMs = 0L
    resetLegacyInteractionFrameGates()
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
    val legacyFirstFrameMs = if (legacyFirstFrameAtMs > 0L && rtcSessionStartedAtMs > 0L) {
      legacyFirstFrameAtMs - rtcSessionStartedAtMs
    } else {
      null
    }
    val renderFpsAvg = averageMetric(rtcRenderFpsSampleSum, rtcRenderFpsSampleCount)
    val legacyRenderFpsAvg = averageMetric(legacyRenderFpsSampleSum, legacyRenderFpsSampleCount)
    val effectiveFirstFrameMs = firstFrameMs ?: legacyFirstFrameMs
    val effectiveRenderFpsAvg = renderFpsAvg ?: legacyRenderFpsAvg
    val effectiveRenderedFrameCount = if (rtcRenderedFrameCount > 0L) {
      rtcRenderedFrameCount
    } else {
      legacyFrameCount
    }
    val mediaFrameTransport = when {
      rtcRenderedFrameCount > 0L || firstFrameMs != null -> "webrtc"
      legacyFrameCount > 0L || legacyFirstFrameMs != null -> "legacy_jpeg"
      else -> "none"
    }
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
      "session_summary session=$sessionId reason=$reason duration_ms=${if (sessionDurationMs >= 0L) sessionDurationMs else "-"} media_frame_transport=$mediaFrameTransport first_frame_ms=${effectiveFirstFrameMs ?: "-"} webrtc_first_frame_ms=${firstFrameMs ?: "-"} legacy_first_frame_ms=${legacyFirstFrameMs ?: "-"} render_fps_avg=${formatMetric(effectiveRenderFpsAvg)} legacy_render_fps_avg=${formatMetric(legacyRenderFpsAvg)} render_fps_recent=${formatMetric(recentRenderQuality.fpsAvg)} render_recent_samples=${recentRenderQuality.sampleCount} render_recent_window_ms=${recentRenderQuality.windowMs} rendered_frames=$effectiveRenderedFrameCount webrtc_rendered_frames=$rtcRenderedFrameCount legacy_rendered_frames=$legacyFrameCount recv_kbps_avg=${formatMetric(recvKbpsAvg)} rtt_ms_avg=${formatMetric(rttMsAvg)} longest_frame_gap_ms=$rtcLongestFrameGapMs recent_frame_gap_ms=${recentRenderQuality.maxFrameGapMs} low_fps_streak_ms=$rtcLongestLowFpsStreakMs recent_low_fps_streak_ms=${recentRenderQuality.lowFpsStreakMs} frames_dropped_last=$rtcFramesDroppedLast frames_dropped_spike_max=$rtcFramesDroppedSpikeMax frames_dropped_delta_recent=$recentDroppedDelta frames_dropped_spike_recent=$recentDroppedSpikeMax controller_quality_hint=$controllerQualityHint controller_quality_hint_recent=$controllerQualityHintRecent candidate_pair_last=$rtcLastCandidatePair candidate_tier_last=$rtcLastCandidateTier pair_state_last=$rtcLastPairState local_ice_candidate_callbacks=$rtcLocalIceCandidateCallbackCount local_ice_candidate_fallbacks=$rtcLocalIceCandidateFallbackCount local_ice_candidate_sent=$rtcLocalIceCandidateSentCount local_ice_candidate_sdp_count=$rtcLocalIceCandidateSdpCount ice_policy_restarts=$rtcIcePolicyRecoveryAttempts frame_size_last=$frameSize ice_state_last=$iceState",
    )
    val metricsPayload = mapOf<String, Any?>(
      "duration_ms" to if (sessionDurationMs >= 0L) sessionDurationMs else -1L,
      // 作者: long；Redmi 真机当前可见画面走 legacy JPEG fallback，通用视频 proof 需要把这条降级画面也算作“已渲染”，否则自动报告会把有画面的会话误判为无首帧。
      "media_frame_transport" to mediaFrameTransport,
      "first_frame_ms" to (effectiveFirstFrameMs ?: -1L),
      "webrtc_first_frame_ms" to (firstFrameMs ?: -1L),
      "legacy_first_frame_ms" to (legacyFirstFrameMs ?: -1L),
      "render_fps_avg" to metricOrMinusOne(effectiveRenderFpsAvg),
      "webrtc_render_fps_avg" to metricOrMinusOne(renderFpsAvg),
      "legacy_render_fps_avg" to metricOrMinusOne(legacyRenderFpsAvg),
      "render_fps_recent" to metricOrMinusOne(recentRenderQuality.fpsAvg),
      "render_recent_sample_count" to recentRenderQuality.sampleCount,
      "render_recent_window_ms" to recentRenderQuality.windowMs,
      "rendered_frames" to effectiveRenderedFrameCount,
      "webrtc_rendered_frames" to rtcRenderedFrameCount,
      "legacy_rendered_frames" to legacyFrameCount,
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
    } else if (legacyFirstFrameAtMs > 0L && rtcSessionStartedAtMs > 0L) {
      legacyFirstFrameAtMs - rtcSessionStartedAtMs
    } else {
      -1L
    }
    val proofKey = listOf(
      activeSessionId,
      reason,
      if (firstFrameMs >= 0L || rtcRenderedFrameCount > 0L || legacyFrameCount > 0L) "video:1" else "video:0",
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
    initializeWebRtc()
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
    // 作者: long；真机 answer 必须持续产出本地 ICE candidate，Mac native sender 才能拿到回程路径；MTK 卡死风险已转到 decoder factory 和 PC 创建超时保护里处理。
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
    val createStartedAtMs = SystemClock.elapsedRealtime()
    Log.i(RTC_TAG, "create_pc_step create_peer_connection_start session=$sessionId")
    rtcPeerConnection = createPeerConnectionWithTimeout(
      factory = factory,
      config = config,
      observer = observer,
      sessionId = sessionId,
      startedAtMs = createStartedAtMs,
    )
    Log.i(
      RTC_TAG,
      "create_pc_step create_peer_connection_done session=$sessionId ok=${rtcPeerConnection != null} elapsed_ms=${SystemClock.elapsedRealtime() - createStartedAtMs}",
    )
    return rtcPeerConnection
  }

  private fun createPeerConnectionWithTimeout(
    factory: PeerConnectionFactory,
    config: PeerConnection.RTCConfiguration,
    observer: PeerConnection.Observer,
    sessionId: String,
    startedAtMs: Long,
  ): PeerConnection? {
    val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
    val executor = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "rd-create-pc-$sessionId").apply {
        isDaemon = true
      }
    }
    val future = executor.submit<PeerConnection?> {
      val created = factory.createPeerConnection(config, observer)
      if (timedOut.get()) {
        Log.w(
          RTC_TAG,
          "create_pc_step create_peer_connection_late_done session=$sessionId ok=${created != null} elapsed_ms=${SystemClock.elapsedRealtime() - startedAtMs}",
        )
        created?.close()
        return@submit null
      }
      created
    }
    return try {
      future.get(RTC_CREATE_PEER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (error: java.util.concurrent.TimeoutException) {
      timedOut.set(true)
      future.cancel(true)
      Log.e(
        RTC_TAG,
        "create_pc_step create_peer_connection_timeout session=$sessionId timeout_ms=$RTC_CREATE_PEER_CONNECTION_TIMEOUT_MS elapsed_ms=${SystemClock.elapsedRealtime() - startedAtMs}",
        error,
      )
      null
    } catch (error: Throwable) {
      val cause = if (error is java.util.concurrent.ExecutionException) {
        error.cause ?: error
      } else {
        error
      }
      Log.e(
        RTC_TAG,
        "create_pc_step create_peer_connection_failed session=$sessionId elapsed_ms=${SystemClock.elapsedRealtime() - startedAtMs} reason=${cause.message ?: cause.javaClass.simpleName}",
        cause,
      )
      null
    } finally {
      executor.shutdownNow()
    }
  }

  private fun attachRemoteVideoTrack(track: VideoTrack, source: String) {
    rtcRemoteVideoTrack?.let { removeRemoteVideoSinks(it) }
    rtcRemoteVideoTrack = track
    rtcActiveVideoSink = null
    rtcTrackAttachedAtMs = SystemClock.elapsedRealtime()
    resetRtcRenderStats()
    activateRemoteVideoRenderer(useTexture = remoteFullscreenActive)
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
    Log.i(RTC_TAG, "set_remote_offer_start session=$messageSessionId sdp_len=${offer.description.length}")
    peerConnection.setRemoteDescription(SimpleSdpObserver(
      onSetSuccess = {
        Log.i(RTC_TAG, "set_remote_offer_ok session=$messageSessionId")
        flushPendingRemoteIceCandidates()
        Log.i(RTC_TAG, "create_answer_start session=$messageSessionId")
        peerConnection.createAnswer(SimpleSdpObserver(onCreateSuccess = { answer ->
          if (answer == null) {
            appendLog("创建 webrtc.answer 失败：answer 为空")
            return@SimpleSdpObserver
          }
          Log.i(
            RTC_TAG,
            "create_answer_ok session=$messageSessionId sdp_len=${answer.description.length} has_video=${answer.description.contains("m=video")}",
          )
          Log.i(RTC_TAG, "set_local_answer_start session=$messageSessionId")
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
              Log.e(RTC_TAG, "set_local_answer_failed session=$messageSessionId reason=$reason")
              appendLog("设置本地 answer 失败：$reason")
            },
          ), answer)
        }, onCreateFailure = { reason ->
          Log.e(RTC_TAG, "create_answer_failed session=$messageSessionId reason=$reason")
          appendLog("创建 webrtc.answer 失败：$reason")
        }), MediaConstraints())
      },
      onSetFailure = { reason ->
        Log.e(RTC_TAG, "set_remote_offer_failed session=$messageSessionId reason=$reason")
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

  private fun parseLegacyFramePushMessage(text: String) {
    try {
      val json = JSONObject(text)
      val payload = json.optJSONObject("payload")
      logIncomingSignal(json, payload, text.length)
      val messageSessionId = json.optNonBlank("session_id") ?: payload?.optNonBlank("session_id")
      val currentSessionId = sessionId
      if (currentSessionId.isNullOrBlank() || messageSessionId.isNullOrBlank() || messageSessionId != currentSessionId) {
        return
      }
      val frameId = payload?.optNonBlank("frame_id") ?: "frame-${System.currentTimeMillis()}"
      val contentB64 = payload?.optNonBlank("content_b64")
      val frameWidth = payload?.optInt("frame_width") ?: 0
      val frameHeight = payload?.optInt("frame_height") ?: 0
      val sourceRect = parseFrameSourceRect(payload)
      val captureTs = payload?.optLong("capture_ts")?.takeIf { it > 0L } ?: System.currentTimeMillis()
      val generation = frameGeneration
      // 作者: long；兜底 JPEG 是大消息热路径，解析后直接进入后台解码队列，只有最终展示才回主线程，避免全屏 pinch 的矩阵动画被每帧消息预处理打断。
      handleIncomingFrame(
        frameId = frameId,
        contentB64 = contentB64,
        announcedWidth = frameWidth,
        announcedHeight = frameHeight,
        sourceRect = sourceRect,
        captureTs = captureTs,
        generation = generation,
      )
    } catch (error: Exception) {
      Log.w(RTC_TAG, "legacy_frame_parse_failed error=${error.message ?: "unknown"}")
    }
  }

  private fun parseMessage(text: String) {
    try {
      val json = JSONObject(text)
      val payload = json.optJSONObject("payload")
      logIncomingSignal(json, payload, text.length)
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
          processPendingSharedTools()
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
            ensureWebRtcRendererInitializedBlocking()
            // 作者: long；Mac 受控端会主动发 webrtc.offer，会话开始阶段只准备视频承载层；factory/PeerConnection 放到信令线程，避免真机主线程被 native 初始化拖死后停在旧会话。
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
          remoteFrameSourceRect = NormalizedRect(0.0, 0.0, 1.0, 1.0)
          remotePinchViewportSourceRect = null
          remotePinchViewportLargestSourceRect = null
          resetRemoteSourceRectPreviewTransform()
          resetLegacyFrameStats()
          resetLegacyFailureThrottleState()
          clearLegacyFrameBitmap()
          binding.remoteFrameView.isVisible = false
          activateRemoteVideoRenderer(useTexture = remoteFullscreenActive)
          if (currentPage != MainPage.SESSION) {
            switchPage(MainPage.SESSION, autoRefreshDevices = false)
          }
          setStatus("会话中")
          appendLog("进入会话 ${sessionId ?: "unknown"}")
          processPendingSharedTools()
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
          val sourceRect = parseFrameSourceRect(payload)
          val captureTs = payload?.optLong("capture_ts")?.takeIf { it > 0L } ?: System.currentTimeMillis()
          if (binding.remoteVideoView.isVisible) {
            binding.remoteVideoView.isVisible = false
          }
          if (binding.remoteTextureVideoView.isVisible) {
            binding.remoteTextureVideoView.isVisible = false
          }
          if (!binding.remoteFrameView.isVisible) {
            binding.remoteFrameView.isVisible = true
          }
          handleIncomingFrame(
            frameId = frameId,
            contentB64 = contentB64,
            announcedWidth = frameWidth,
            announcedHeight = frameHeight,
            sourceRect = sourceRect,
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
          if (echoType == "input.mouse.move") {
            renderMoveAckThrottled(echoType)
          } else {
            binding.ackText.text = "输入回执：$echoType 已确认"
            appendLog("收到 $echoType 已确认")
          }
        }
        "input.result.push" -> {
          val messageSessionId = json.optNonBlank("session_id") ?: payload.optNonBlank("session_id")
          val currentSessionId = sessionId
          if (currentSessionId.isNullOrBlank() || messageSessionId.isNullOrBlank() || messageSessionId != currentSessionId) {
            appendLog("忽略非当前会话输入执行结果")
            return
          }
          val result = recordRemoteInputResult(payload, json)
          val isMoveResult = result.inputType == "input.mouse.move"
          maybeSendLiveE2EProofReport(
            reason = if (result.applied) "live_controller_input_applied" else "live_controller_input_result",
            force = !isMoveResult,
          )
          val inputType = result.inputType
          val applied = result.applied
          val statusCode = payload.optNonBlank("error_code")
            ?: payload.optNonBlank("status_code")
            ?: "-"
          val executor = result.executor.ifBlank { "-" }
          val summary = result.summary.ifBlank { inputType }
          val resultLabel = if (applied) "目标已执行" else "目标未执行"
          if (isMoveResult) {
            renderMoveResultThrottled(resultLabel, summary, statusCode, executor)
          } else {
            binding.ackText.text = "输入回执：$resultLabel $inputType [$statusCode/$executor]"
            appendLog("收到目标端输入执行结果：$resultLabel $summary [$statusCode/$executor]")
          }
        }
        "clipboard.text" -> {
          handleIncomingClipboardText(json, payload ?: JSONObject())
        }
        "clipboard.result" -> {
          handleIncomingClipboardResult(json, payload ?: JSONObject())
        }
        "file.transfer.start" -> {
          handleIncomingFileTransferStart(json, payload ?: JSONObject())
        }
        "file.transfer.chunk" -> {
          handleIncomingFileTransferChunk(json, payload ?: JSONObject())
        }
        "file.transfer.complete" -> {
          handleIncomingFileTransferComplete(json, payload ?: JSONObject())
        }
        "file.transfer.result" -> {
          handleIncomingFileTransferResult(json, payload ?: JSONObject())
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
          if (currentPage == MainPage.SESSION) {
            switchPage(MainPage.MY_DEVICES)
          }
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
              if (currentPage == MainPage.SESSION) {
                switchPage(MainPage.MY_DEVICES)
              }
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
    if (
      rtcRemoteVideoTrack == null ||
      (!binding.remoteVideoView.isVisible && !binding.remoteTextureVideoView.isVisible)
    ) {
      return true
    }
    val now = SystemClock.elapsedRealtime()
    if (rtcFirstFrameAtMs <= 0L && rtcTrackAttachedAtMs > 0L && now - rtcTrackAttachedAtMs >= 5_000L) {
      if (now - lastLegacyFrameIgnoredLogAtMs >= LEGACY_FRAME_IGNORED_LOG_INTERVAL_MS) {
        lastLegacyFrameIgnoredLogAtMs = now
        Log.w(
          RTC_TAG,
          "legacy_frame_fallback session=$activeSessionId reason=webrtc_waiting_first_frame wait_ms=${now - rtcTrackAttachedAtMs}",
        )
      }
      return true
    }
    if (now - lastLegacyFrameIgnoredLogAtMs >= LEGACY_FRAME_IGNORED_LOG_INTERVAL_MS) {
      lastLegacyFrameIgnoredLogAtMs = now
      Log.i(
        RTC_TAG,
        "legacy_frame_ignored session=$activeSessionId reason=webrtc_track_active",
      )
    }
    return false
  }

  private fun shouldSuppressLegacyFrameDuringInteraction(frameId: String, sourceRect: NormalizedRect): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (isMaterializedRemoteFrameSourceRect(sourceRect)) {
      protectMaterializedSourceRectFromFullFrames(now)
      clearLegacyFullFrameFreeze(reason = "source_rect")
      if (shouldThrottleFullscreenSourceRectFrame(frameId, now)) {
        return true
      }
      return false
    }
    if (legacyPinchFrameGateActive) {
      synchronized(legacyInteractionFrameGateLock) {
        if (!legacyPinchFrameGateActive) {
          return false
        }
        if (now - legacyPinchPreviewFrameAtMs >= REMOTE_VIEWPORT_PINCH_PREVIEW_FRAME_INTERVAL_MS) {
          legacyPinchPreviewFrameAtMs = now
          Log.i(
            RTC_TAG,
            "legacy_pinch_preview_frame_allowed frame_id=$frameId suppressed=$legacyPinchFrameSuppressedCount",
          )
          return false
        }
        // 作者: long；双指缩放时用户看到的是本地矩阵动画，整屏 JPEG 即使低频插入也会抢占解码和绘制预算；手势中冻结整屏帧，停手后再用局部高清帧补真实细节。
        legacyPinchFrameSuppressedCount += 1
        if (legacyPinchFrameSuppressedCount == 1 || legacyPinchFrameSuppressedCount % 30 == 0) {
          Log.i(
            RTC_TAG,
            "legacy_pinch_frame_frozen frame_id=$frameId suppressed=$legacyPinchFrameSuppressedCount",
          )
        }
        return true
      }
    }
    val freezeUntil = legacyFullFrameFreezeUntilMs
    if (freezeUntil > now) {
      synchronized(legacyInteractionFrameGateLock) {
        legacyPostPinchFrameSuppressedCount += 1
        if (legacyPostPinchFrameSuppressedCount == 1 || legacyPostPinchFrameSuppressedCount % 12 == 0) {
          Log.i(
            RTC_TAG,
            "legacy_post_pinch_full_frame_frozen frame_id=$frameId suppressed=$legacyPostPinchFrameSuppressedCount remaining_ms=${freezeUntil - now}",
          )
        }
      }
      return true
    }
    if (freezeUntil > 0L) {
      clearLegacyFullFrameFreeze(reason = "expired")
    }
    val protectUntil = legacySourceRectFullFrameProtectUntilMs
    if (protectUntil > now) {
      synchronized(legacyInteractionFrameGateLock) {
        legacySourceRectFullFrameSuppressedCount += 1
        if (legacySourceRectFullFrameSuppressedCount == 1 || legacySourceRectFullFrameSuppressedCount % 12 == 0) {
          Log.i(
            RTC_TAG,
            "legacy_source_rect_full_frame_protected frame_id=$frameId suppressed=$legacySourceRectFullFrameSuppressedCount remaining_ms=${protectUntil - now}",
          )
        }
      }
      return true
    }
    if (protectUntil > 0L) {
      clearLegacySourceRectFullFrameProtection(reason = "expired")
    }
    return false
  }

  private fun shouldThrottleFullscreenSourceRectFrame(frameId: String, now: Long = SystemClock.elapsedRealtime()): Boolean {
    if (!remoteFullscreenActive) {
      remoteFullscreenSourceRectFrameSuppressedCount = 0
      remoteLastFullscreenSourceRectFrameDisplayedAtMs = now
      return false
    }
    val elapsedMs = now - remoteLastFullscreenSourceRectFrameDisplayedAtMs
    if (remoteLastFullscreenSourceRectFrameDisplayedAtMs > 0L && elapsedMs < REMOTE_FULLSCREEN_SOURCE_RECT_FRAME_MIN_INTERVAL_MS) {
      remoteFullscreenSourceRectFrameSuppressedCount += 1
      if (remoteFullscreenSourceRectFrameSuppressedCount == 1 || remoteFullscreenSourceRectFrameSuppressedCount % 12 == 0) {
        Log.i(
          RTC_TAG,
          "legacy_fullscreen_source_rect_frame_throttled frame_id=$frameId suppressed=$remoteFullscreenSourceRectFrameSuppressedCount elapsed_ms=$elapsedMs",
        )
      }
      return true
    }
    if (remoteFullscreenSourceRectFrameSuppressedCount > 0) {
      Log.i(
        RTC_TAG,
        "legacy_fullscreen_source_rect_frame_throttle_clear displayed=$frameId suppressed=$remoteFullscreenSourceRectFrameSuppressedCount",
      )
      remoteFullscreenSourceRectFrameSuppressedCount = 0
    }
    remoteLastFullscreenSourceRectFrameDisplayedAtMs = now
    return false
  }

  private fun resetLegacyInteractionFrameGates() {
    legacyPinchFrameGateActive = false
    legacyFullFrameFreezeUntilMs = 0L
    legacySourceRectFullFrameProtectUntilMs = 0L
    legacyPinchPreviewFrameAtMs = 0L
    synchronized(legacyInteractionFrameGateLock) {
      legacyPinchFrameSuppressedCount = 0
      legacyPostPinchFrameSuppressedCount = 0
      legacySourceRectFullFrameSuppressedCount = 0
    }
  }

  private fun beginLegacyPinchFrameGate() {
    legacyPinchFrameGateActive = true
    legacyFullFrameFreezeUntilMs = 0L
    legacyPinchPreviewFrameAtMs = SystemClock.elapsedRealtime()
    synchronized(legacyInteractionFrameGateLock) {
      legacyPinchFrameSuppressedCount = 0
      legacyPostPinchFrameSuppressedCount = 0
    }
    // 作者: long；进入双指缩放时旧整屏解码结果已经不再代表当前手势位置，先取消一次旧任务，并推迟下一次预览帧，避免低 FPS JPEG 解码抢走手势合成帧。
    legacyFrameDecodeSeq.incrementAndGet()
  }

  private fun protectMaterializedSourceRectFromFullFrames(now: Long = SystemClock.elapsedRealtime()) {
    legacySourceRectFullFrameProtectUntilMs = now + REMOTE_VIEWPORT_SOURCE_RECT_FULL_FRAME_PROTECT_MS
    synchronized(legacyInteractionFrameGateLock) {
      legacySourceRectFullFrameSuppressedCount = 0
    }
  }

  private fun endLegacyPinchFrameGate(reason: String, keepFullFrameFrozen: Boolean = false) {
    legacyPinchFrameGateActive = false
    val suppressed = synchronized(legacyInteractionFrameGateLock) {
      val count = legacyPinchFrameSuppressedCount
      legacyPinchFrameSuppressedCount = 0
      count
    }
    if (keepFullFrameFrozen) {
      legacyFullFrameFreezeUntilMs = SystemClock.elapsedRealtime() + REMOTE_VIEWPORT_POST_PINCH_FULL_FRAME_FREEZE_MS
      synchronized(legacyInteractionFrameGateLock) {
        legacyPostPinchFrameSuppressedCount = 0
      }
    } else {
      legacyFullFrameFreezeUntilMs = 0L
    }
    if (suppressed > 0) {
      Log.i(RTC_TAG, "legacy_pinch_frame_gate_end reason=$reason suppressed=$suppressed")
    }
  }

  private fun clearLegacyFullFrameFreeze(reason: String) {
    val freezeUntil = legacyFullFrameFreezeUntilMs
    if (freezeUntil <= 0L) {
      return
    }
    legacyFullFrameFreezeUntilMs = 0L
    val suppressed = synchronized(legacyInteractionFrameGateLock) {
      val count = legacyPostPinchFrameSuppressedCount
      legacyPostPinchFrameSuppressedCount = 0
      count
    }
    if (suppressed > 0) {
      Log.i(RTC_TAG, "legacy_full_frame_freeze_clear reason=$reason suppressed=$suppressed")
    }
  }

  private fun clearLegacySourceRectFullFrameProtection(reason: String) {
    val protectUntil = legacySourceRectFullFrameProtectUntilMs
    if (protectUntil <= 0L) {
      return
    }
    legacySourceRectFullFrameProtectUntilMs = 0L
    val suppressed = synchronized(legacyInteractionFrameGateLock) {
      val count = legacySourceRectFullFrameSuppressedCount
      legacySourceRectFullFrameSuppressedCount = 0
      count
    }
    if (suppressed > 0) {
      Log.i(RTC_TAG, "legacy_source_rect_full_frame_protect_clear reason=$reason suppressed=$suppressed")
    }
  }

  private fun handleIncomingFrame(
    frameId: String,
    contentB64: String?,
    announcedWidth: Int,
    announcedHeight: Int,
    sourceRect: NormalizedRect,
    captureTs: Long,
    generation: Int,
  ) {
    if (!isActivityAlive || generation != frameGeneration) {
      return
    }
    if (contentB64.isNullOrBlank()) {
      showFrameDecodeFailureOnUiThread("屏幕帧 $frameId 缺少 content_b64")
      return
    }
    if (announcedWidth <= 0 || announcedHeight <= 0 || announcedWidth > MAX_FRAME_DIMENSION || announcedHeight > MAX_FRAME_DIMENSION) {
      showFrameDecodeFailureOnUiThread("屏幕帧 $frameId 尺寸非法：${formatFrameSize(announcedWidth, announcedHeight)}")
      return
    }
    if (contentB64.length > MAX_FRAME_B64_LENGTH) {
      showFrameDecodeFailureOnUiThread("屏幕帧 $frameId 过大，已拒绝解码")
      return
    }
    if (shouldSuppressLegacyFrameDuringInteraction(frameId, sourceRect)) {
      return
    }

    val decodeSeq = legacyFrameDecodeSeq.incrementAndGet()
    frameDecodeExecutor.execute {
      if (decodeSeq != legacyFrameDecodeSeq.get()) {
        return@execute
      }
      val decodedFrame = decodeFrameBitmap(contentB64, announcedWidth, announcedHeight, sourceRect)
      runOnUiThread {
        if (!isActivityAlive || generation != frameGeneration || decodeSeq != legacyFrameDecodeSeq.get()) {
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
        if (binding.remoteVideoView.isVisible) {
          binding.remoteVideoView.isVisible = false
        }
        if (binding.remoteTextureVideoView.isVisible) {
          binding.remoteTextureVideoView.isVisible = false
        }
        if (!binding.remoteFrameView.isVisible) {
          binding.remoteFrameView.isVisible = true
        }
        val frameWidth = decodedFrame.width.takeIf { it > 0 } ?: announcedWidth
        val frameHeight = decodedFrame.height.takeIf { it > 0 } ?: announcedHeight
        val frameSizeChanged = frameWidth != renderedFrameWidth || frameHeight != renderedFrameHeight
        val sourceRectChanged = sourceRect != remoteFrameSourceRect
        if (sourceRectChanged) {
          val viewportReset = materializeRemoteFrameSourceRect(sourceRect)
          val previewTarget = if (remotePinchZoomActive) {
            remotePinchViewportLargestSourceRect ?: remotePinchViewportSourceRect
          } else {
            null
          }
          val previewChanged = previewTarget != null &&
            updateRemoteSourceRectPreviewTransform(sourceRect, previewTarget)
          // 作者: long；局部高清帧到达时先把旧的本地放大/平移归一，再绘制新裁剪帧，避免新帧先被上一轮 transform 放大一拍造成闪烁和发虚。
          if (viewportReset) {
            applyRemoteViewportTransform(commitRenderScale = false)
          } else if (previewChanged) {
            scheduleRemoteViewportTransformApply(commitRenderScale = false)
          }
        }
        binding.remoteFrameView.setHighQualityScaling(shouldUseHighQualityLegacyScaling(sourceRect))
        setLegacyFrameBitmap(bitmap)
        renderedFrameWidth = frameWidth
        renderedFrameHeight = frameHeight
        remoteFrameSourceRect = sourceRect
        if (frameSizeChanged) {
          // 作者: long；source_rect 平移只改变“看电脑哪一块”，不改变 Android 画面尺寸；最大缩放拖动时避免每个局部帧都触发窗口测量，减少全屏 WM 提交压力。
          updateRemoteViewportAspect(frameWidth, frameHeight)
        }
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
            legacyRenderFpsSampleSum += sampleFps
            legacyRenderFpsSampleCount += 1
            Log.i(
              RTC_TAG,
              "legacy_frame_sample session=$activeSessionId frames_total=$legacyFrameCount sample_frames=$sampleFrames sample_ms=$sampleElapsedMs fps=${"%.2f".format(Locale.US, sampleFps)} size=${formatFrameSize(frameWidth, frameHeight)} source_rect=${formatSourceRectForLog(sourceRect)}",
            )
            legacyFrameSampleAtMs = now
            legacyFrameSampleFrameCount = legacyFrameCount
          }
        }
        if (now - lastLegacyFrameUiUpdateAtMs >= RTC_STATS_UI_UPDATE_INTERVAL_MS) {
          lastLegacyFrameUiUpdateAtMs = now
          val sourceRectLabel = if (sourceRect == NormalizedRect(0.0, 0.0, 1.0, 1.0)) {
            "整屏"
          } else {
            "局部 ${formatCoordinate(sourceRect.width)}x${formatCoordinate(sourceRect.height)}"
          }
          binding.frameMetaText.text = "当前画面：$frameId ${formatFrameSize(frameWidth, frameHeight)} $sourceRectLabel @ ${formatTimestamp(captureTs)}"
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
    if (remoteOverlayControlTouchActive) {
      if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
        remoteOverlayControlTouchActive = false
      }
      return true
    }
    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
      remoteViewportOverlayControlAt(event.x, event.y)?.let { control ->
        // 作者: long；远控画面触控层覆盖整块视频区域，命中工具按钮时直接派发给按钮本身，否则用户点“全屏/键盘”会被误发成 Mac 鼠标点击。
        remoteOverlayControlTouchActive = true
        control.performClick()
        return true
      }
    }
    remoteScaleGestureDetector.onTouchEvent(event)
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        cancelRemoteLongPressDrag()
        cancelFrameCoalescedRemoteMouseMove()
        resetRemoteLocalCursorMotion()
        remoteTouchDownX = event.x
        remoteTouchDownY = event.y
        remoteLastTouchX = event.x
        remoteLastTouchY = event.y
        remotePanMoved = false
        remoteSuppressTap = false
        remoteMouseButtonDown = false
        remoteLongPressDragArmed = false
        remoteScrollGestureActive = false
        remotePinchZoomActive = false
        resetRemotePinchFocus()
        remoteTouchDownPoint = mapTouchToFrame(imageView, event.x, event.y)
        remoteLastInputPoint = remoteTouchDownPoint
        remoteLastSentMovePoint = null
        remoteLastSentMoveAtMs = 0L
        remoteLastWheelAtMs = 0L
        updateRemoteLocalCursor(event.x, event.y)
        scheduleRemoteLongPressDrag(imageView)
        return renderedFrameWidth > 0 && renderedFrameHeight > 0
      }

      MotionEvent.ACTION_POINTER_DOWN -> {
        cancelRemoteLongPressDrag()
        flushFrameCoalescedRemoteMouseMove()
        remoteSuppressTap = true
        remoteMouseViewportPanSourceRect = null
        endRemoteMouseDrag()
        if (event.pointerCount >= 2) {
          remoteScrollGestureActive = false
          remoteScrollLastFocusX = averagePointerX(event)
          remoteScrollLastFocusY = averagePointerY(event)
          remoteMultiTouchStartSpan = pointerSpan(event)
          remoteManualPinchActive = false
          remoteManualPinchLastSpan = remoteMultiTouchStartSpan
        }
        return true
      }

      MotionEvent.ACTION_POINTER_UP -> {
        cancelRemoteLongPressDrag()
        remoteSuppressTap = true
        endRemoteMouseDrag()
        remoteScrollGestureActive = event.pointerCount > 2
        if (remoteScrollGestureActive) {
          remoteScrollLastFocusX = averageRemainingPointerX(event, event.actionIndex)
          remoteScrollLastFocusY = averageRemainingPointerY(event, event.actionIndex)
          remoteMultiTouchStartSpan = pointerSpan(event)
        } else {
          if (remoteManualPinchActive) {
            endRemoteViewportManualPinch(reason = "pointer_up")
          } else if (remoteViewportScale > REMOTE_VIEWPORT_MIN_SCALE && !remotePinchZoomActive) {
            sendRemoteViewportInteractionHint("end", "pan", force = true)
          }
          remoteMultiTouchStartSpan = 0f
          resetRemotePinchFocus()
        }
        return true
      }

      MotionEvent.ACTION_MOVE -> {
        if (event.pointerCount >= 2) {
          cancelRemoteLongPressDrag()
          endRemoteMouseDrag()
          remoteSuppressTap = true
          if (remoteScaleGestureDetector.isInProgress || remotePinchZoomActive || remoteManualPinchActive || remoteMultiTouchSpanChanged(event)) {
            remoteScrollGestureActive = false
            if (!remoteScaleGestureDetector.isInProgress && (remoteManualPinchActive || !remotePinchZoomActive)) {
              handleRemoteViewportManualPinch(event)
            }
          } else if (remoteViewportScale > REMOTE_VIEWPORT_MIN_SCALE) {
            handleRemoteViewportTwoFingerPan(event)
          } else {
            handleRemoteWheelGesture(event)
          }
          remoteSuppressTap = true
          remoteLastTouchX = event.x
          remoteLastTouchY = event.y
          return true
        }

        val movedFromDown = abs(event.x - remoteTouchDownX) > remoteTouchSlopPx ||
          abs(event.y - remoteTouchDownY) > remoteTouchSlopPx
        if (remoteLongPressDragArmed) {
          updateRemoteLocalCursor(event.x, event.y)
          if (movedFromDown) {
            remoteSuppressTap = true
          }
          if (remoteMouseButtonDown) {
            sendRemotePointerMovesFromMotionEvent(imageView, event, inputCategory = "drag")
          }
          val point = mapTouchToFrame(imageView, event.x, event.y)
          remoteLastInputPoint = point ?: remoteLastInputPoint
          remoteLastTouchX = event.x
          remoteLastTouchY = event.y
          return true
        }
        if (movedFromDown) {
          cancelRemoteLongPressDrag()
          remoteSuppressTap = true
          // 作者: long；放大后单指仍承担电脑鼠标移动，同时推动本地/远端局部视角跟随指针；用户不需要再切换“移动窗口视角”的单独模式。
          val viewportPanMoved = handleZoomedViewportPanFromMouseMove(imageView, event.x, event.y)
          updateRemoteLocalCursor(event.x, event.y)
          sendRemotePointerMovesFromMotionEvent(
            imageView,
            event,
            inputCategory = "pointer",
            moveProfile = if (viewportPanMoved) "zoom_pan" else "pointer",
          )
        }
        val point = mapTouchToFrame(imageView, event.x, event.y)
        remoteLastInputPoint = point ?: remoteLastInputPoint
        remoteLastTouchX = event.x
        remoteLastTouchY = event.y
        return true
      }

      MotionEvent.ACTION_UP -> {
        cancelRemoteLongPressDrag()
        if (remoteManualPinchActive) {
          endRemoteViewportManualPinch(reason = "touch_up")
        }
        imageView.performClick()
        val currentSessionId = sessionId
        val upPoint = mapTouchToFrame(imageView, event.x, event.y) ?: remoteLastInputPoint
        if (remoteMouseButtonDown) {
          flushFrameCoalescedRemoteMouseMove()
          if (!currentSessionId.isNullOrBlank() && upPoint != null) {
            sendRemoteMouseMove(currentSessionId, upPoint, logSuccess = false, force = true, inputCategory = "drag")
            sendRemoteMouseButton(currentSessionId, upPoint, "left", "up", logSuccess = false, inputCategory = "drag")
          }
          resetRemoteInputGestureState()
          return true
        }
        if (remotePanMoved || remoteSuppressTap || remoteScaleGestureDetector.isInProgress || remotePinchZoomActive) {
          flushFrameCoalescedRemoteMouseMove()
          flushDeferredRemoteMouseMove()
          if (remotePanMoved) {
            sendRemoteViewportInteractionHint("end", "pan", force = true)
          }
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
        cancelRemoteLongPressDrag()
        cancelFrameCoalescedRemoteMouseMove()
        endRemoteMouseDrag()
        if (remoteManualPinchActive) {
          endRemoteViewportManualPinch(reason = "cancel")
        }
        if (remotePanMoved) {
          sendRemoteViewportInteractionHint("end", "pan", force = true)
        }
        resetRemoteInputGestureState()
        return true
      }
    }
    return false
  }

  private fun remoteViewportOverlayControlAt(touchX: Float, touchY: Float): View? {
    val hitSlop = dp(8).toFloat()
    return listOf(
      binding.remoteZoomResetButton,
      binding.remoteFullscreenButton,
      binding.remoteKeyboardPanelButton,
    ).firstOrNull { control ->
      if (!control.isShown) {
        return@firstOrNull false
      }
      val left = control.left.toFloat() - hitSlop
      val top = control.top.toFloat() - hitSlop
      val right = control.right.toFloat() + hitSlop
      val bottom = control.bottom.toFloat() + hitSlop
      touchX in left..right && touchY in top..bottom
    }
  }

  private fun sendRemotePointerMoveFromTouch(
    imageView: View,
    touchX: Float,
    touchY: Float,
    force: Boolean,
    sampleAtMs: Long = SystemClock.elapsedRealtime(),
    inputCategory: String = "pointer",
  ): Boolean {
    val currentSessionId = sessionId
    if (currentSessionId.isNullOrBlank()) {
      return false
    }
    val point = mapTouchToFrame(imageView, touchX, touchY) ?: return false
    updateRemoteLocalCursor(touchX, touchY)
    remoteLastInputPoint = point
    return if (force) {
      // 作者: long；单指移动只同步光标位置，不按下鼠标键，避免用户想悬停时被误识别成拖拽。
      sendRemoteMouseMove(currentSessionId, point, logSuccess = false, force = true, inputCategory = inputCategory, sentAtMs = sampleAtMs)
    } else {
      maybeSendRemoteMouseMove(currentSessionId, point, sampleAtMs = sampleAtMs, inputCategory = inputCategory)
    }
  }

  private fun sendRemotePointerMovesFromMotionEvent(
    imageView: View,
    event: MotionEvent,
    inputCategory: String,
    moveProfile: String = inputCategory,
  ): Boolean {
    val currentSessionId = sessionId ?: return false
    val now = SystemClock.elapsedRealtime()
    var candidateX = event.x
    var candidateY = event.y
    var point = mapTouchToFrame(imageView, candidateX, candidateY)
    val historySize = event.historySize
    val historyStartIndex = maxOf(0, historySize - REMOTE_POINTER_MOVE_MAX_HISTORY_SAMPLES)
    if (point == null && historySize > 0) {
      for (historyIndex in historySize - 1 downTo historyStartIndex) {
        val historicalX = event.getHistoricalX(historyIndex)
        val historicalY = event.getHistoricalY(historyIndex)
        val historicalPoint = mapTouchToFrame(imageView, historicalX, historicalY) ?: continue
        candidateX = historicalX
        candidateY = historicalY
        point = historicalPoint
        break
      }
    }
    val mappedPoint = point ?: return false
    updateRemoteLocalCursor(candidateX, candidateY, sampleAtMs = now)
    remoteLastInputPoint = mappedPoint
    // 作者: long；真机 move 事件可能一帧内合并多个历史点，远控鼠标更需要稳定尾点而不是把历史轨迹全量灌进 CGEvent 队列；每个 Android vsync 只发送最新坐标，抬手时再强制补尾帧。
    return scheduleFrameCoalescedRemoteMouseMove(
      sessionId = currentSessionId,
      point = mappedPoint,
      sampleAtMs = now,
      inputCategory = inputCategory,
      moveProfile = moveProfile,
    )
  }

  private fun scheduleRemoteLongPressDrag(imageView: View) {
    val startPoint = remoteTouchDownPoint ?: return
    if (sessionId.isNullOrBlank()) {
      return
    }
    cancelRemoteLongPressDrag()
    remoteLongPressRunnable = Runnable {
      val currentSessionId = sessionId
      if (currentSessionId.isNullOrBlank() || remoteSuppressTap || remotePanMoved) {
        return@Runnable
      }
      imageView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
      // 作者: long；长按代表“准备拖拽”，只有确认长按后才按下远端左键，普通滑动则始终只是移动鼠标指针。
      remoteLongPressDragArmed = beginRemoteMouseDrag(currentSessionId, startPoint)
      if (remoteLongPressDragArmed) {
        remoteSuppressTap = true
      }
    }.also { runnable ->
      remoteGestureHandler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
    }
  }

  private fun cancelRemoteLongPressDrag() {
    remoteLongPressRunnable?.let { remoteGestureHandler.removeCallbacks(it) }
    remoteLongPressRunnable = null
  }

  private fun beginRemoteMouseDrag(sessionId: String, point: NormalizedPoint): Boolean {
    sendRemoteMouseMove(sessionId, point, logSuccess = false, force = true, inputCategory = "drag")
    remoteMouseButtonDown = sendRemoteMouseButton(
      sessionId,
      point,
      "left",
      "down",
      logSuccess = false,
      inputCategory = "drag",
    )
    return remoteMouseButtonDown
  }

  private fun resetRemoteInputGestureState() {
    cancelFrameCoalescedRemoteMouseMove()
    cancelDeferredRemoteMouseMove()
    if (legacyPinchFrameGateActive) {
      endLegacyPinchFrameGate(reason = "gesture_reset")
    }
    remotePanMoved = false
    remoteSuppressTap = false
    remoteMouseButtonDown = false
    remoteLongPressDragArmed = false
    remoteTouchDownPoint = null
    remoteLastInputPoint = null
    remoteLastSentMovePoint = null
    remoteLastSentMoveAtMs = 0L
    remoteMouseViewportPanSourceRect = null
    remotePinchViewportSourceRect = null
    remotePinchViewportLargestSourceRect = null
    val sourceRectPreviewChanged = resetRemoteSourceRectPreviewTransform()
    remoteScrollGestureActive = false
    remotePinchZoomActive = false
    remoteManualPinchActive = false
    remoteManualPinchLastSpan = 0f
    remoteAccumulatedPinchScaleFactor = 1f
    remoteLastPinchEndHintAtMs = 0L
    remoteLastPinchEndHintKey = ""
    resetRemotePinchFocus()
    remoteMultiTouchStartSpan = 0f
    remoteLastWheelAtMs = 0L
    resetRemoteLocalCursorMotion()
    if (sourceRectPreviewChanged) {
      scheduleRemoteViewportTransformApply(commitRenderScale = false)
    }
    scheduleRemoteViewportHardwareLayerRelease()
    scheduleRemoteLocalCursorHide()
  }

  private fun releaseRemoteInputState(sendMouseUp: Boolean = true) {
    cancelRemoteLongPressDrag()
    if (sendMouseUp && remoteMouseButtonDown) {
      flushPendingRemoteMouseMoveBeforeDiscreteInput()
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
    cancelRemoteLongPressDrag()
    flushPendingRemoteMouseMoveBeforeDiscreteInput()
    val currentSessionId = sessionId
    val point = remoteLastInputPoint
    if (!currentSessionId.isNullOrBlank() && point != null) {
      sendRemoteMouseButton(currentSessionId, point, "left", "up", logSuccess = false, inputCategory = "drag")
    }
    remoteMouseButtonDown = false
    remoteLongPressDragArmed = false
  }

  private fun updateRemoteLocalCursor(
    touchX: Float,
    touchY: Float,
    sampleAtMs: Long = SystemClock.elapsedRealtime(),
  ) {
    val previousAtMs = remoteLastLocalCursorTouchAtMs
    val predicted = predictRemoteLocalCursor(touchX, touchY, sampleAtMs)
    remotePendingLocalCursorX = predicted.first
    remotePendingLocalCursorY = predicted.second
    remoteLastLocalCursorTouchX = touchX
    remoteLastLocalCursorTouchY = touchY
    remoteLastLocalCursorTouchAtMs = sampleAtMs
    if (previousAtMs <= 0L) {
      remotePendingLocalCursorX = touchX
      remotePendingLocalCursorY = touchY
    }
    if (remoteLocalCursorApplyScheduled) {
      return
    }
    remoteLocalCursorApplyScheduled = true
    binding.remoteViewportContainer.postOnAnimation {
      remoteLocalCursorApplyScheduled = false
      if (!isActivityAlive) {
        return@postOnAnimation
      }
      applyRemoteLocalCursor(remotePendingLocalCursorX, remotePendingLocalCursorY)
    }
  }

  private fun applyRemoteLocalCursor(touchX: Float, touchY: Float) {
    if (renderedFrameWidth <= 0 || renderedFrameHeight <= 0 || sessionId.isNullOrBlank()) {
      hideRemoteLocalCursor()
      return
    }
    val cursor = binding.remoteCursorOverlay
    val cursorWidth = cursor.width.takeIf { it > 0 } ?: dp(18)
    val cursorHeight = cursor.height.takeIf { it > 0 } ?: dp(18)
    val viewportWidth = binding.remoteViewportContainer.width.toFloat()
    val viewportHeight = binding.remoteViewportContainer.height.toFloat()
    if (viewportWidth <= 0f || viewportHeight <= 0f) {
      return
    }
    remoteLocalCursorHideRunnable?.let { remoteGestureHandler.removeCallbacks(it) }
    remoteLocalCursorHideRunnable = null
    // 作者: long；Mac 光标位置仍以远端视频为准，本地指针只反馈手指移动意图；移动事件可能带大量历史点，合并到动画帧后再更新，避免本地 UI 把远端画面渲染挤掉。
    cursor.translationX = (touchX - cursorWidth / 2f).coerceIn(0f, (viewportWidth - cursorWidth).coerceAtLeast(0f))
    cursor.translationY = (touchY - cursorHeight / 2f).coerceIn(0f, (viewportHeight - cursorHeight).coerceAtLeast(0f))
    cursor.alpha = 1f
    if (cursor.visibility != View.VISIBLE) {
      cursor.visibility = View.VISIBLE
    }
  }

  private fun predictRemoteLocalCursor(touchX: Float, touchY: Float, sampleAtMs: Long): Pair<Float, Float> {
    val previousAtMs = remoteLastLocalCursorTouchAtMs
    if (previousAtMs <= 0L || sampleAtMs <= previousAtMs) {
      return touchX to touchY
    }
    val elapsedMs = sampleAtMs - previousAtMs
    if (elapsedMs > 80L) {
      return touchX to touchY
    }
    val maxPredictionPx = dpFloat(REMOTE_LOCAL_CURSOR_MAX_PREDICTION_DP)
    val velocityX = (touchX - remoteLastLocalCursorTouchX) / elapsedMs.toFloat()
    val velocityY = (touchY - remoteLastLocalCursorTouchY) / elapsedMs.toFloat()
    val predictX = (velocityX * REMOTE_LOCAL_CURSOR_PREDICTION_MS).coerceIn(-maxPredictionPx, maxPredictionPx)
    val predictY = (velocityY * REMOTE_LOCAL_CURSOR_PREDICTION_MS).coerceIn(-maxPredictionPx, maxPredictionPx)
    // 作者: long；本地蓝色指针只表示“手指正在把 Mac 光标往这里带”，轻量预测补偿 UI/信令一帧延迟，不改变真实发送到电脑的坐标。
    return (touchX + predictX) to (touchY + predictY)
  }

  private fun resetRemoteLocalCursorMotion() {
    remoteLastLocalCursorTouchX = 0f
    remoteLastLocalCursorTouchY = 0f
    remoteLastLocalCursorTouchAtMs = 0L
  }

  private fun scheduleRemoteLocalCursorHide() {
    remoteLocalCursorHideRunnable?.let { remoteGestureHandler.removeCallbacks(it) }
    remoteLocalCursorHideRunnable = Runnable {
      remoteLocalCursorHideRunnable = null
      hideRemoteLocalCursor()
    }.also { runnable ->
      remoteGestureHandler.postDelayed(runnable, REMOTE_LOCAL_CURSOR_HIDE_DELAY_MS)
    }
  }

  private fun hideRemoteLocalCursor() {
    remoteLocalCursorHideRunnable?.let { remoteGestureHandler.removeCallbacks(it) }
    remoteLocalCursorHideRunnable = null
    if (::binding.isInitialized) {
      binding.remoteCursorOverlay.visibility = View.GONE
    }
  }

  private fun maybeSendRemoteMouseMove(
    sessionId: String,
    point: NormalizedPoint,
    sampleAtMs: Long = SystemClock.elapsedRealtime(),
    inputCategory: String = "drag",
    moveProfile: String = inputCategory,
  ): Boolean {
    val minDelta = remotePointerMoveMinDelta(moveProfile)
    val lastPoint = remoteLastSentMovePoint
    if (lastPoint != null) {
      val movedEnough = abs(point.x - lastPoint.x) >= minDelta ||
        abs(point.y - lastPoint.y) >= minDelta
      if (!movedEnough) {
        return false
      }
      val elapsedSinceLastMove = sampleAtMs - remoteLastSentMoveAtMs
      val minIntervalMs = remotePointerMoveMinIntervalMs(moveProfile)
      if (elapsedSinceLastMove < minIntervalMs) {
        scheduleDeferredRemoteMouseMove(
          sessionId = sessionId,
          point = point,
          inputCategory = inputCategory,
          sampleAtMs = sampleAtMs,
          delayMs = (minIntervalMs - elapsedSinceLastMove)
            .coerceAtMost(remotePointerMoveTrailingMaxDelayMs(moveProfile)),
        )
        return false
      }
    }
    return sendRemoteMouseMove(sessionId, point, logSuccess = false, force = true, inputCategory = inputCategory, sentAtMs = sampleAtMs)
  }

  private fun remotePointerMoveMinIntervalMs(moveProfile: String): Long =
    if (moveProfile == "zoom_pan") REMOTE_ZOOM_PAN_POINTER_MOVE_MIN_INTERVAL_MS else REMOTE_POINTER_MOVE_MIN_INTERVAL_MS

  private fun remotePointerMoveMinDelta(moveProfile: String): Double =
    if (moveProfile == "zoom_pan") REMOTE_ZOOM_PAN_POINTER_MOVE_MIN_DELTA else REMOTE_POINTER_MOVE_MIN_DELTA

  private fun remotePointerMoveTrailingMaxDelayMs(moveProfile: String): Long =
    if (moveProfile == "zoom_pan") REMOTE_ZOOM_PAN_POINTER_MOVE_TRAILING_MAX_DELAY_MS else REMOTE_POINTER_MOVE_TRAILING_MAX_DELAY_MS

  private fun scheduleDeferredRemoteMouseMove(
    sessionId: String,
    point: NormalizedPoint,
    inputCategory: String,
    sampleAtMs: Long,
    delayMs: Long,
  ) {
    remotePendingMoveSessionId = sessionId
    remotePendingMovePoint = point
    remotePendingMoveCategory = inputCategory
    remotePendingMoveAtMs = sampleAtMs
    if (remotePendingMoveRunnable != null) {
      return
    }
    // 作者: long；触摸事件节流时保留最后一个鼠标坐标，延迟任务按真实发送时间落账，避免旧采样时间让下一次 move 被错误节流。
    remotePendingMoveRunnable = Runnable {
      val pendingSessionId = remotePendingMoveSessionId
      val pendingPoint = remotePendingMovePoint
      val pendingCategory = remotePendingMoveCategory
      remotePendingMoveSessionId = null
      remotePendingMovePoint = null
      remotePendingMoveAtMs = 0L
      remotePendingMoveRunnable = null
      if (!pendingSessionId.isNullOrBlank() && pendingSessionId == sessionId && pendingPoint != null) {
        sendRemoteMouseMove(
          pendingSessionId,
          pendingPoint,
          logSuccess = false,
          force = true,
          inputCategory = pendingCategory,
          sentAtMs = SystemClock.elapsedRealtime(),
        )
      }
    }.also { runnable ->
      remoteGestureHandler.postDelayed(runnable, delayMs.coerceAtLeast(1L))
    }
  }

  private fun scheduleFrameCoalescedRemoteMouseMove(
    sessionId: String,
    point: NormalizedPoint,
    sampleAtMs: Long,
    inputCategory: String,
    moveProfile: String = inputCategory,
  ): Boolean {
    remoteFrameMoveSessionId = sessionId
    remoteFrameMovePoint = point
    remoteFrameMoveCategory = inputCategory
    remoteFrameMoveProfile = moveProfile
    remoteFrameMoveAtMs = sampleAtMs
    if (remoteFrameMoveDispatchScheduled) {
      return true
    }
    remoteFrameMoveDispatchScheduled = true
    remoteFrameMoveDispatchToken += 1L
    val dispatchToken = remoteFrameMoveDispatchToken
    binding.remoteViewportContainer.postOnAnimation {
      if (dispatchToken != remoteFrameMoveDispatchToken) {
        return@postOnAnimation
      }
      remoteFrameMoveDispatchScheduled = false
      if (!isActivityAlive) {
        clearFrameCoalescedRemoteMouseMove()
        return@postOnAnimation
      }
      val pendingSessionId = remoteFrameMoveSessionId
      val pendingPoint = remoteFrameMovePoint
      val pendingCategory = remoteFrameMoveCategory
      val pendingProfile = remoteFrameMoveProfile
      clearFrameCoalescedRemoteMouseMove()
      if (!pendingSessionId.isNullOrBlank() && pendingPoint != null) {
        maybeSendRemoteMouseMove(
          pendingSessionId,
          pendingPoint,
          sampleAtMs = SystemClock.elapsedRealtime(),
          inputCategory = pendingCategory,
          moveProfile = pendingProfile,
        )
      }
    }
    return true
  }

  private fun clearFrameCoalescedRemoteMouseMove() {
    remoteFrameMoveSessionId = null
    remoteFrameMovePoint = null
    remoteFrameMoveCategory = "pointer"
    remoteFrameMoveProfile = "pointer"
    remoteFrameMoveAtMs = 0L
  }

  private fun cancelFrameCoalescedRemoteMouseMove() {
    remoteFrameMoveDispatchScheduled = false
    remoteFrameMoveDispatchToken += 1L
    clearFrameCoalescedRemoteMouseMove()
  }

  private fun flushFrameCoalescedRemoteMouseMove() {
    val pendingSessionId = remoteFrameMoveSessionId
    val pendingPoint = remoteFrameMovePoint
    val pendingCategory = remoteFrameMoveCategory
    val pendingProfile = remoteFrameMoveProfile
    cancelFrameCoalescedRemoteMouseMove()
    if (!pendingSessionId.isNullOrBlank() && pendingPoint != null) {
      // 作者: long；手指抬起时必须立即补当前帧缓存的最后坐标，否则下一次 vsync 可能已经被 reset 清掉，Mac 光标会停在旧位置。
      maybeSendRemoteMouseMove(
        pendingSessionId,
        pendingPoint,
        sampleAtMs = SystemClock.elapsedRealtime(),
        inputCategory = pendingCategory,
        moveProfile = pendingProfile,
      )
    }
  }

  private fun cancelDeferredRemoteMouseMove() {
    remotePendingMoveRunnable?.let { remoteGestureHandler.removeCallbacks(it) }
    remotePendingMoveRunnable = null
    remotePendingMoveSessionId = null
    remotePendingMovePoint = null
    remotePendingMoveCategory = "pointer"
    remotePendingMoveAtMs = 0L
  }

  private fun flushDeferredRemoteMouseMove() {
    val pendingSessionId = remotePendingMoveSessionId
    val pendingPoint = remotePendingMovePoint
    val pendingCategory = remotePendingMoveCategory
    cancelDeferredRemoteMouseMove()
    if (!pendingSessionId.isNullOrBlank() && pendingPoint != null) {
      // 作者: long；手指抬起前主动补发尾帧，远端光标最终位置才会贴近用户最后停下的位置。
      sendRemoteMouseMove(
        pendingSessionId,
        pendingPoint,
        logSuccess = false,
        force = true,
        inputCategory = pendingCategory,
        sentAtMs = SystemClock.elapsedRealtime(),
      )
    }
  }

  private fun flushPendingRemoteMouseMoveBeforeDiscreteInput() {
    // 作者: long；按钮、键盘和滚轮是离散动作，发送前先补齐 Android 本地合并队列里的最后鼠标坐标，避免目标端按键或抬起落在旧位置。
    flushFrameCoalescedRemoteMouseMove()
    flushDeferredRemoteMouseMove()
  }

  private fun sendRemoteMouseMove(
    sessionId: String,
    point: NormalizedPoint,
    logSuccess: Boolean,
    force: Boolean = false,
    inputCategory: String = "",
    sentAtMs: Long = SystemClock.elapsedRealtime(),
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
      "remote $inputCategory -> input.mouse.move x=${formatCoordinate(point.x)} y=${formatCoordinate(point.y)}",
      logSuccess = logSuccess,
    )
    if (sent) {
      if (remotePendingMoveAtMs > 0L && remotePendingMoveAtMs <= sentAtMs) {
        cancelDeferredRemoteMouseMove()
      }
      remoteLastSentMovePoint = point
      remoteLastSentMoveAtMs = sentAtMs
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
  ): Boolean {
    flushPendingRemoteMouseMoveBeforeDiscreteInput()
    return sendSocketMessage(
      controller.inputButtonMessage(sessionId, point.x, point.y, button, action, inputCategory),
      "remote drag -> input.mouse.button $button $action",
      logSuccess = logSuccess,
    )
  }

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

    flushPendingRemoteMouseMoveBeforeDiscreteInput()
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

  private fun handleRemoteViewportManualPinch(event: MotionEvent) {
    if (event.pointerCount < 2) {
      return
    }
    val span = pointerSpan(event).takeIf { it > 0f } ?: return
    val focusX = averagePointerX(event)
    val focusY = averagePointerY(event)
    if (!remoteManualPinchActive) {
      remoteManualPinchActive = true
      remotePinchZoomActive = true
      remoteScrollGestureActive = false
      remoteManualPinchLastSpan = remoteMultiTouchStartSpan.takeIf { it > 0f } ?: span
      remoteAccumulatedPinchScaleFactor = 1f
      remotePinchFocusX = focusX
      remotePinchFocusY = focusY
      remotePinchFocusInitialized = true
      remoteLastPinchEndHintAtMs = 0L
      remoteLastPinchEndHintKey = ""
      prepareRemoteViewportForInteractiveTransform()
      beginLegacyPinchFrameGate()
      binding.remoteFrameView.setHighQualityScaling(false)
      sendRemoteViewportInteractionHint("start", "pinch", force = true)
      Log.i(
        RTC_TAG,
        "remote_manual_pinch_begin span=${String.format(Locale.US, "%.1f", span)} focus=${String.format(Locale.US, "%.1f", focusX)},${String.format(Locale.US, "%.1f", focusY)}",
      )
    }
    val previousSpan = remoteManualPinchLastSpan.takeIf { it > 0f } ?: span
    remoteManualPinchLastSpan = span
    val rawScaleFactor = span / previousSpan
    if (rawScaleFactor <= 0f || rawScaleFactor.isNaN() || rawScaleFactor.isInfinite()) {
      return
    }
    if (abs(rawScaleFactor - 1f) < REMOTE_PINCH_SCALE_EPSILON) {
      return
    }
    if (applyRemoteViewportSourceRectBackedPinch(rawScaleFactor, focusX, focusY, source = "manual")) {
      return
    }
    // 作者: long；部分真机/调试注入不会稳定触发 ScaleGestureDetector，但两指距离变化本身已经足够明确；兜底逻辑复用同一套本地矩阵和 source_rect 高清链路，避免手势被误当成滚轮后完全不缩放。
    val smoothedScaleFactor = 1f + ((rawScaleFactor - 1f) * REMOTE_PINCH_SCALE_FACTOR_SMOOTHING)
    val oldScale = remoteViewportScale
    val nextScale = (oldScale * smoothedScaleFactor)
      .coerceIn(REMOTE_VIEWPORT_MIN_SCALE, REMOTE_VIEWPORT_MAX_SCALE)
    if (abs(nextScale - oldScale) < 0.0005f) {
      return
    }
    val ratio = nextScale / oldScale
    val smoothFocusX = smoothRemotePinchFocusX(focusX)
    val smoothFocusY = smoothRemotePinchFocusY(focusY)
    val newOffsetX = smoothFocusX - ((smoothFocusX - remoteViewportOffsetX) * ratio)
    val newOffsetY = smoothFocusY - ((smoothFocusY - remoteViewportOffsetY) * ratio)
    remoteViewportScale = nextScale
    remoteViewportOffsetX = clampRemoteViewportOffsetX(newOffsetX, nextScale)
    remoteViewportOffsetY = clampRemoteViewportOffsetY(newOffsetY, nextScale)
    scheduleRemoteViewportTransformApply(commitRenderScale = false)
    sendRemoteViewportInteractionHint("update", "pinch")
    val now = SystemClock.elapsedRealtime()
    if (remoteDebugPinchDispatchActive || now - remoteLastPinchScaleLogAtMs >= 250L) {
      remoteLastPinchScaleLogAtMs = now
      Log.i(RTC_TAG, "remote_viewport_pinch_scale scale=${String.format(Locale.US, "%.3f", nextScale)} source=manual")
    }
  }

  private fun endRemoteViewportManualPinch(reason: String) {
    if (!remoteManualPinchActive) {
      return
    }
    remoteManualPinchActive = false
    remoteManualPinchLastSpan = 0f
    endLegacyPinchFrameGate(
      reason = "manual_$reason",
      keepFullFrameFrozen = remoteViewportScale >= REMOTE_VIEWPORT_DETAIL_RENDER_MIN_SCALE,
    )
    binding.remoteFrameView.setHighQualityScaling(shouldUseHighQualityLegacyScaling(remoteFrameSourceRect))
    if (remoteViewportScale >= REMOTE_VIEWPORT_DETAIL_RENDER_MIN_SCALE) {
      scheduleRemoteViewportRenderScaleCommit(remoteViewportRenderCommitDelayMs())
    } else {
      scheduleRemoteViewportTransformApply(commitRenderScale = false)
      scheduleRemoteViewportHardwareLayerRelease()
    }
    sendRemoteViewportInteractionHint("end", "pinch", force = true)
    Log.i(
      RTC_TAG,
      "remote_manual_pinch_end reason=$reason scale=${String.format(Locale.US, "%.3f", remoteViewportScale)}",
    )
  }

  private fun applyRemoteViewportSourceRectBackedPinch(
    rawScaleFactor: Float,
    focusX: Float,
    focusY: Float,
    source: String,
  ): Boolean {
    if (rawScaleFactor >= 1f || remoteViewportScale > REMOTE_VIEWPORT_MIN_SCALE + 0.005f) {
      return false
    }
    val baseRect = selectRemoteSourceRectBackedPinchBaseRect()
      ?: return false
    if (!isMaterializedRemoteFrameSourceRect(baseRect)) {
      return false
    }
    val firstSourceRectBackedPinch =
      remotePinchViewportSourceRect == null && remotePinchViewportLargestSourceRect == null
    val smoothedScaleFactor = 1f + ((rawScaleFactor - 1f) * REMOTE_PINCH_SCALE_FACTOR_SMOOTHING)
    if (smoothedScaleFactor >= 0.9995f) {
      return false
    }
    val focusPoint = mapTouchToFrameInSourceRect(
      binding.remoteTouchLayer,
      focusX,
      focusY,
      baseRect,
    ) ?: NormalizedPoint(
      x = (baseRect.x + baseRect.width / 2.0).coerceIn(0.0, 1.0),
      y = (baseRect.y + baseRect.height / 2.0).coerceIn(0.0, 1.0),
    )
    val nextRect = scaleRemoteViewportSourceRectAroundFocus(
      baseRect = baseRect,
      focusPoint = focusPoint,
      scaleFactor = smoothedScaleFactor,
    )
    val changedEnough = abs(nextRect.x - baseRect.x) >= 0.00005 ||
      abs(nextRect.y - baseRect.y) >= 0.00005 ||
      abs(nextRect.width - baseRect.width) >= 0.00005 ||
      abs(nextRect.height - baseRect.height) >= 0.00005
    if (!changedEnough) {
      return false
    }
    remotePinchZoomActive = true
    remotePinchViewportSourceRect = nextRect
    remotePinchViewportLargestSourceRect = selectLargerRemoteViewportSourceRect(
      remotePinchViewportLargestSourceRect,
      nextRect,
    )
    updateRemoteSourceRectPreviewTransform(
      currentRect = remoteFrameSourceRect,
      targetRect = nextRect,
    )
    remoteViewportScale = REMOTE_VIEWPORT_MIN_SCALE
    remoteViewportOffsetX = 0f
    remoteViewportOffsetY = 0f
    remoteViewportRenderScale = remoteViewportBaseRenderScale()
    remoteViewportRenderBoundsDirty = true
    scheduleRemoteViewportTransformApply(commitRenderScale = false)
    // 作者: long；局部高清缩小时本地倍率已经是 1x，没有可见 transform 兜底；首次扩大 source_rect 必须绕过 start 事件留下的节流窗口，短捏合才会立刻回传更大的裁剪源。
    sendRemoteViewportInteractionHint("update", "pinch", force = firstSourceRectBackedPinch)
    val now = SystemClock.elapsedRealtime()
    if (remoteDebugPinchDispatchActive || now - remoteLastPinchScaleLogAtMs >= 250L) {
      remoteLastPinchScaleLogAtMs = now
      Log.i(
        RTC_TAG,
        "remote_viewport_source_rect_pinch scale=${String.format(Locale.US, "%.3f", remoteViewportInteractionScale(nextRect, requestFullViewport = false))} source=$source rect=${formatSourceRectForLog(nextRect)} preview_scale=${String.format(Locale.US, "%.3f", remoteSourceRectPreviewScale)}",
      )
    }
    return true
  }

  private fun selectRemoteSourceRectBackedPinchBaseRect(): NormalizedRect? {
    val candidates = listOfNotNull(
      remotePinchViewportSourceRect,
      remotePinchViewportLargestSourceRect,
      currentRemoteViewportVisibleRect(),
      remoteFrameSourceRect,
    ).filter { rect -> isMaterializedRemoteFrameSourceRect(rect) }
    if (candidates.isEmpty()) {
      return null
    }
    // 作者: long；并拢缩小时只能从本轮已经展开过的最大局部继续扩大，不能让 Mac 延迟回来的较小中间帧把手势目标拉回去。
    return candidates.maxWithOrNull(
      compareBy<NormalizedRect> { rect -> rect.width * rect.height }
        .thenBy { rect -> maxOf(rect.width, rect.height) },
    )
  }

  private fun selectLargerRemoteViewportSourceRect(
    current: NormalizedRect?,
    candidate: NormalizedRect,
  ): NormalizedRect {
    val existing = current ?: return candidate
    val existingArea = existing.width * existing.height
    val candidateArea = candidate.width * candidate.height
    return if (
      candidateArea > existingArea + 0.0000005 ||
        (abs(candidateArea - existingArea) <= 0.0000005 && maxOf(candidate.width, candidate.height) > maxOf(existing.width, existing.height))
    ) {
      candidate
    } else {
      existing
    }
  }

  private fun updateRemoteSourceRectPreviewTransform(
    currentRect: NormalizedRect,
    targetRect: NormalizedRect,
  ): Boolean {
    if (!isMaterializedRemoteFrameSourceRect(currentRect) || !isMaterializedRemoteFrameSourceRect(targetRect)) {
      return resetRemoteSourceRectPreviewTransform()
    }
    val viewportWidth = binding.remoteViewportContainer.width.toFloat()
    val viewportHeight = binding.remoteViewportContainer.height.toFloat()
    if (viewportWidth <= 1f || viewportHeight <= 1f || targetRect.width <= 0.0 || targetRect.height <= 0.0) {
      return resetRemoteSourceRectPreviewTransform()
    }
    val widthRatio = (currentRect.width / targetRect.width).toFloat()
    val heightRatio = (currentRect.height / targetRect.height).toFloat()
    val previewScale = min(widthRatio, heightRatio).takeIf { !it.isNaN() && !it.isInfinite() }
      ?.coerceIn(0.18f, 1f)
      ?: return resetRemoteSourceRectPreviewTransform()
    if (previewScale >= 0.995f) {
      return resetRemoteSourceRectPreviewTransform()
    }
    // 作者: long；本地只有当前局部帧，不能凭空显示目标 rect 外的新内容；先按当前帧在目标 rect 里的相对位置缩小摆放，手势会立即有视觉反馈，真实内容随后由 Mac 回传补齐。
    val currentCenterX = currentRect.x + currentRect.width / 2.0
    val currentCenterY = currentRect.y + currentRect.height / 2.0
    val targetRelativeCenterX = ((currentCenterX - targetRect.x) / targetRect.width)
      .takeIf { !it.isNaN() && !it.isInfinite() }
      ?.coerceIn(0.0, 1.0)
      ?: 0.5
    val targetRelativeCenterY = ((currentCenterY - targetRect.y) / targetRect.height)
      .takeIf { !it.isNaN() && !it.isInfinite() }
      ?.coerceIn(0.0, 1.0)
      ?: 0.5
    val nextOffsetX = (targetRelativeCenterX.toFloat() * viewportWidth) - (viewportWidth * previewScale / 2f)
    val nextOffsetY = (targetRelativeCenterY.toFloat() * viewportHeight) - (viewportHeight * previewScale / 2f)
    val changed = abs(remoteSourceRectPreviewScale - previewScale) >= 0.002f ||
      abs(remoteSourceRectPreviewOffsetX - nextOffsetX) >= 0.5f ||
      abs(remoteSourceRectPreviewOffsetY - nextOffsetY) >= 0.5f
    remoteSourceRectPreviewScale = previewScale
    remoteSourceRectPreviewOffsetX = nextOffsetX
    remoteSourceRectPreviewOffsetY = nextOffsetY
    return changed
  }

  private fun resetRemoteSourceRectPreviewTransform(): Boolean {
    val changed = abs(remoteSourceRectPreviewScale - 1f) >= 0.002f ||
      abs(remoteSourceRectPreviewOffsetX) >= 0.5f ||
      abs(remoteSourceRectPreviewOffsetY) >= 0.5f
    remoteSourceRectPreviewScale = 1f
    remoteSourceRectPreviewOffsetX = 0f
    remoteSourceRectPreviewOffsetY = 0f
    return changed
  }

  private fun scaleRemoteViewportSourceRectAroundFocus(
    baseRect: NormalizedRect,
    focusPoint: NormalizedPoint,
    scaleFactor: Float,
  ): NormalizedRect {
    val safeScale = scaleFactor.toDouble().coerceIn(0.2, 5.0)
    val focusRatioX = ((focusPoint.x - baseRect.x) / baseRect.width)
      .takeIf { !it.isNaN() && !it.isInfinite() }
      ?.coerceIn(0.0, 1.0)
      ?: 0.5
    val focusRatioY = ((focusPoint.y - baseRect.y) / baseRect.height)
      .takeIf { !it.isNaN() && !it.isInfinite() }
      ?.coerceIn(0.0, 1.0)
      ?: 0.5
    val nextWidth = (baseRect.width / safeScale).coerceIn(REMOTE_VIEWPORT_MIN_SOURCE_RECT_SIZE, 1.0)
    val nextHeight = (baseRect.height / safeScale).coerceIn(REMOTE_VIEWPORT_MIN_SOURCE_RECT_SIZE, 1.0)
    // 作者: long；局部高清已经物化后，本地倍率回到 1x；两指并拢应扩大 Mac 裁剪源而不是被本地 1x 下限吞掉，焦点比例保持不变才能让用户感觉是在“从当前手指中心缩小”。
    return clampRemoteViewportSourceRect(
      NormalizedRect(
        x = focusPoint.x - (nextWidth * focusRatioX),
        y = focusPoint.y - (nextHeight * focusRatioY),
        width = nextWidth,
        height = nextHeight,
      ),
    )
  }

  private fun handleRemoteViewportTwoFingerPan(event: MotionEvent) {
    val focusX = averagePointerX(event)
    val focusY = averagePointerY(event)
    if (!remoteScrollGestureActive) {
      remoteScrollGestureActive = true
      remoteScrollLastFocusX = focusX
      remoteScrollLastFocusY = focusY
      sendRemoteViewportInteractionHint("start", "pan", force = true)
      return
    }
    val dx = focusX - remoteScrollLastFocusX
    val dy = focusY - remoteScrollLastFocusY
    val movedEnough = abs(dx) > 0.5f || abs(dy) > 0.5f
    if (!movedEnough) {
      return
    }
    // 作者: long；放大后的视口平移只改变本地观察区域，不向电脑发送输入，防止用户两指找局部内容时误触远端滚轮或鼠标。
    remoteViewportOffsetX = clampRemoteViewportOffsetX(remoteViewportOffsetX + dx, remoteViewportScale)
    remoteViewportOffsetY = clampRemoteViewportOffsetY(remoteViewportOffsetY + dy, remoteViewportScale)
    scheduleRemoteViewportTransformApply()
    sendRemoteViewportInteractionHint("update", "pan")
    remoteScrollLastFocusX = focusX
    remoteScrollLastFocusY = focusY
  }

  private fun handleZoomedViewportPanFromMouseMove(imageView: View, touchX: Float, touchY: Float): Boolean {
    val viewportWidth = imageView.width.toFloat()
    val viewportHeight = imageView.height.toFloat()
    if (viewportWidth <= 0f || viewportHeight <= 0f) {
      return false
    }
    val isLocalZoomed = remoteViewportScale > REMOTE_ZOOM_MOUSE_VIEWPORT_PAN_MIN_SCALE
    val isSourceRectZoomed = isMaterializedRemoteFrameSourceRect(remoteFrameSourceRect)
    if (!isLocalZoomed && !isSourceRectZoomed) {
      return false
    }

    val dragPanX = (touchX - remoteLastTouchX) * REMOTE_ZOOM_MOUSE_VIEWPORT_DRAG_PAN_FACTOR
    val dragPanY = (touchY - remoteLastTouchY) * REMOTE_ZOOM_MOUSE_VIEWPORT_DRAG_PAN_FACTOR
    val edgePanX = remoteMouseViewportEdgePan(touchX, viewportWidth)
    val edgePanY = remoteMouseViewportEdgePan(touchY, viewportHeight)
    val sourcePanX = dragPanX + edgePanX
    val sourcePanY = dragPanY + edgePanY
    if (abs(sourcePanX) < 0.35f && abs(sourcePanY) < 0.35f) {
      return false
    }

    var moved = false
    if (isLocalZoomed) {
      val nextOffsetX = clampRemoteViewportOffsetX(remoteViewportOffsetX - sourcePanX, remoteViewportScale)
      val nextOffsetY = clampRemoteViewportOffsetY(remoteViewportOffsetY - sourcePanY, remoteViewportScale)
      moved = abs(nextOffsetX - remoteViewportOffsetX) >= 0.1f || abs(nextOffsetY - remoteViewportOffsetY) >= 0.1f
      if (moved) {
        remoteViewportOffsetX = nextOffsetX
        remoteViewportOffsetY = nextOffsetY
        scheduleRemoteViewportTransformApply()
      }
    } else if (isSourceRectZoomed) {
      val baseRect = remoteMouseViewportPanSourceRect
        ?: currentRemoteViewportVisibleRect()
        ?: remoteFrameSourceRect
      val shiftX = sourcePanX.toDouble() / viewportWidth.toDouble() * baseRect.width
      val shiftY = sourcePanY.toDouble() / viewportHeight.toDouble() * baseRect.height
      val nextRect = clampRemoteViewportSourceRect(
        baseRect.copy(
          x = baseRect.x + shiftX,
          y = baseRect.y + shiftY,
        ),
      )
      moved = abs(nextRect.x - baseRect.x) >= 0.00005 ||
        abs(nextRect.y - baseRect.y) >= 0.00005
      if (moved) {
        remoteMouseViewportPanSourceRect = nextRect
      }
    }

    if (!moved) {
      return false
    }
    // 作者: long；放大后的单指移动同时驱动 Mac 鼠标和观察窗口：本地未物化时先平移 transform，局部高清已物化后把期望 source_rect 发回 Mac 重新裁剪。
    remotePanMoved = true
    sendRemoteViewportInteractionHint("update", "pan")
    return true
  }

  private fun remoteMouseViewportEdgePan(position: Float, size: Float): Float {
    if (size <= 0f) {
      return 0f
    }
    val edgeZone = dpFloat(REMOTE_ZOOM_MOUSE_VIEWPORT_EDGE_ZONE_DP).coerceAtMost(size / 2f)
    if (edgeZone <= 1f) {
      return 0f
    }
    val maxStep = dpFloat(REMOTE_ZOOM_MOUSE_VIEWPORT_EDGE_MAX_STEP_DP)
    return when {
      position < edgeZone -> -maxStep * ((edgeZone - position) / edgeZone).coerceIn(0f, 1f)
      position > size - edgeZone -> maxStep * ((position - (size - edgeZone)) / edgeZone).coerceIn(0f, 1f)
      else -> 0f
    }
  }

  private fun sendRemoteViewportInteractionHint(
    phase: String,
    interaction: String,
    force: Boolean = false,
  ): Boolean {
    val currentSessionId = sessionId?.trim().orEmpty()
    if (currentSessionId.isBlank()) {
      return false
    }
    val now = SystemClock.elapsedRealtime()
    if (!force && phase == "update") {
      val minIntervalMs = when (interaction) {
        "pinch" -> REMOTE_VIEWPORT_PINCH_HINT_INTERVAL_MS
        "pan" -> REMOTE_VIEWPORT_PAN_HINT_INTERVAL_MS
        else -> REMOTE_VIEWPORT_INTERACTION_HINT_INTERVAL_MS
      }
      if (now - remoteLastViewportInteractionHintAtMs < minIntervalMs) {
        return false
      }
    }
    remoteLastViewportInteractionHintAtMs = now
    val pinchSourceRect = remotePinchViewportSourceRect
    val pinchSourceRectRequestsFullViewport =
      interaction == "pinch" &&
        phase == "end" &&
        pinchSourceRect != null &&
        !isMaterializedRemoteFrameSourceRect(pinchSourceRect)
    val shouldRequestFullViewport =
      interaction == "pinch" &&
        phase == "end" &&
        remoteViewportScale <= REMOTE_VIEWPORT_MIN_SCALE + 0.005f &&
        (pinchSourceRect == null || pinchSourceRectRequestsFullViewport)
    val visibleRect = if (shouldRequestFullViewport) {
      // 作者: long；用户把远控画面缩回 1x 时，Mac 端也要恢复完整桌面采集，否则 Android 只是在局部 source_rect 上做了本地 1x，后续画面仍会停在局部。
      NormalizedRect(0.0, 0.0, 1.0, 1.0)
    } else if (interaction == "pinch" && pinchSourceRect != null) {
      pinchSourceRect
    } else if (interaction == "pan" && remoteMouseViewportPanSourceRect != null) {
      remoteMouseViewportPanSourceRect
    } else {
      currentRemoteViewportVisibleRect()
    }
    val focusPoint = if (shouldRequestFullViewport) {
      NormalizedPoint(0.5, 0.5)
    } else {
      currentRemoteViewportFocusPoint(visibleRect)
    }
    val safeVisibleRect = visibleRect?.let { clampRemoteViewportSourceRect(it) }
    val safeFocusPoint = focusPoint?.let { point ->
      NormalizedPoint(point.x.coerceIn(0.0, 1.0), point.y.coerceIn(0.0, 1.0))
    }
    val interactionScale = remoteViewportInteractionScale(safeVisibleRect, shouldRequestFullViewport)
    val pinchEndHintKey = if (interaction == "pinch" && phase == "end") {
      remoteViewportPinchEndHintKey(currentSessionId, interactionScale, safeVisibleRect, safeFocusPoint)
    } else {
      ""
    }
    if (
      pinchEndHintKey.isNotBlank() &&
      pinchEndHintKey == remoteLastPinchEndHintKey &&
      now - remoteLastPinchEndHintAtMs <= REMOTE_VIEWPORT_PINCH_END_DEDUPE_MS
    ) {
      Log.i(RTC_TAG, "remote_viewport_pinch_end_deduped key=$pinchEndHintKey")
      return false
    }
    // 作者: long；双指缩放、平移和放大后的鼠标移动都是视口操作；上报完整桌面归一化 source_rect，并限制最小窗口，避免最大缩放递归裁到极小区域后触发崩溃或严重卡顿。
    val sent = sendSocketMessage(
      controller.viewportInteractionMessage(
        sessionId = currentSessionId,
        phase = phase,
        interaction = interaction,
        scale = interactionScale,
        viewportX = safeVisibleRect?.x,
        viewportY = safeVisibleRect?.y,
        viewportWidth = safeVisibleRect?.width,
        viewportHeight = safeVisibleRect?.height,
        focusX = safeFocusPoint?.x,
        focusY = safeFocusPoint?.y,
      ),
      "remote viewport $interaction $phase",
      logSuccess = false,
    )
    if (sent && pinchEndHintKey.isNotBlank()) {
      remoteLastPinchEndHintAtMs = now
      remoteLastPinchEndHintKey = pinchEndHintKey
    }
    if (sent && safeVisibleRect != null) {
      applyExpectedRemoteSourceRectFromViewportHint(
        sourceRect = safeVisibleRect,
        interaction = interaction,
        phase = phase,
      )
    }
    return sent
  }

  private fun applyExpectedRemoteSourceRectFromViewportHint(
    sourceRect: NormalizedRect,
    interaction: String,
    phase: String,
  ) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      runOnUiThread {
        if (isActivityAlive) {
          applyExpectedRemoteSourceRectFromViewportHint(sourceRect, interaction, phase)
        }
      }
      return
    }
    val previousSourceRect = remoteFrameSourceRect
    if (previousSourceRect == sourceRect) {
      return
    }
    val viewportReset = materializeRemoteFrameSourceRect(sourceRect)
    val previewTarget = if (remotePinchZoomActive) {
      remotePinchViewportLargestSourceRect ?: remotePinchViewportSourceRect
    } else {
      null
    }
    val previewChanged = previewTarget != null &&
      updateRemoteSourceRectPreviewTransform(sourceRect, previewTarget)
    remoteFrameSourceRect = sourceRect
    if (!isMaterializedRemoteFrameSourceRect(sourceRect)) {
      remoteMouseViewportPanSourceRect = null
      remotePinchViewportSourceRect = null
      remotePinchViewportLargestSourceRect = null
      resetRemoteSourceRectPreviewTransform()
    }
    // 作者: long；H.264 帧没有 legacy 的 source_rect 元数据，viewport hint 一旦发出就把本地坐标基准同步到预期裁剪源，否则高清裁剪后触摸仍会按整屏映射。
    if (viewportReset) {
      applyRemoteViewportTransform(commitRenderScale = false)
    } else if (previewChanged) {
      scheduleRemoteViewportTransformApply(commitRenderScale = false)
    }
    Log.i(
      RTC_TAG,
      "remote_viewport_source_rect_expected interaction=$interaction phase=$phase from=${formatSourceRectForLog(previousSourceRect)} to=${formatSourceRectForLog(sourceRect)} reset=$viewportReset preview_changed=$previewChanged",
    )
  }

  private fun remoteViewportPinchEndHintKey(
    currentSessionId: String,
    interactionScale: Float,
    safeVisibleRect: NormalizedRect?,
    safeFocusPoint: NormalizedPoint?,
  ): String {
    val rect = safeVisibleRect ?: NormalizedRect(0.0, 0.0, 1.0, 1.0)
    val focus = safeFocusPoint ?: NormalizedPoint(0.5, 0.5)
    // 作者: long；同一次松手可能同时走系统 ScaleGestureDetector 和手动兜底链路，按最终视口内容去重，避免 Mac 端重复切采集配置。
    return listOf(
      currentSessionId,
      String.format(Locale.US, "%.3f", interactionScale),
      String.format(Locale.US, "%.4f", rect.x),
      String.format(Locale.US, "%.4f", rect.y),
      String.format(Locale.US, "%.4f", rect.width),
      String.format(Locale.US, "%.4f", rect.height),
      String.format(Locale.US, "%.4f", focus.x),
      String.format(Locale.US, "%.4f", focus.y),
    ).joinToString(separator = "|")
  }

  private fun remoteViewportInteractionScale(visibleRect: NormalizedRect?, requestFullViewport: Boolean): Float {
    if (requestFullViewport || visibleRect == null) {
      return REMOTE_VIEWPORT_MIN_SCALE
    }
    val localScale = remoteViewportScale.coerceIn(REMOTE_VIEWPORT_MIN_SCALE, REMOTE_VIEWPORT_MAX_SCALE)
    val sourceRectScale = if (visibleRect.width < 0.999 || visibleRect.height < 0.999) {
      (1.0 / maxOf(visibleRect.width, visibleRect.height)).toFloat()
        .coerceIn(REMOTE_VIEWPORT_DETAIL_RENDER_MIN_SCALE, REMOTE_VIEWPORT_MAX_SCALE)
    } else {
      REMOTE_VIEWPORT_MIN_SCALE
    }
    // 作者: long；局部高清物化后 Android 本地矩阵会回到 1x，但 Mac 仍要知道当前处在放大视角，否则单指移动窗口视角时会被受控端错误恢复成整屏采集。
    return maxOf(localScale, sourceRectScale)
  }

  private fun remoteMultiTouchSpanChanged(event: MotionEvent): Boolean {
    if (event.pointerCount < 2) {
      return false
    }
    val startSpan = remoteMultiTouchStartSpan.takeIf { it > 0f } ?: return false
    // 作者: long；两指滚动和两指缩放都从多点触控开始，只有指距变化超过触摸阈值时才切到缩放，避免轻微抖动抢走滚轮。
    return abs(pointerSpan(event) - startSpan) > remoteTouchSlopPx
  }

  private fun smoothRemotePinchFocusX(rawFocusX: Float): Float {
    if (!remotePinchFocusInitialized) {
      remotePinchFocusX = rawFocusX
      remotePinchFocusInitialized = true
      return rawFocusX
    }
    // 作者: long；真机两指中心点需要保留抗抖，但全屏放大时焦点跟随太慢会像拖影，响应比例偏高让画面更贴住手势移动。
    remotePinchFocusX += (rawFocusX - remotePinchFocusX) * REMOTE_PINCH_FOCUS_SMOOTHING
    return remotePinchFocusX
  }

  private fun smoothRemotePinchFocusY(rawFocusY: Float): Float {
    if (!remotePinchFocusInitialized) {
      remotePinchFocusY = rawFocusY
      remotePinchFocusInitialized = true
      return rawFocusY
    }
    remotePinchFocusY += (rawFocusY - remotePinchFocusY) * REMOTE_PINCH_FOCUS_SMOOTHING
    return remotePinchFocusY
  }

  private fun resetRemotePinchFocus() {
    remotePinchFocusX = 0f
    remotePinchFocusY = 0f
    remotePinchFocusInitialized = false
  }

  private fun pointerSpan(event: MotionEvent): Float {
    if (event.pointerCount < 2) {
      return 0f
    }
    val dx = event.getX(0) - event.getX(1)
    val dy = event.getY(0) - event.getY(1)
    return kotlin.math.sqrt(((dx * dx) + (dy * dy)).toDouble()).toFloat()
  }

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
    return mapTouchToFrameInSourceRect(imageView, touchX, touchY, remoteFrameSourceRect)
  }

  private fun mapTouchToFrameInSourceRect(
    imageView: View,
    touchX: Float,
    touchY: Float,
    sourceRect: NormalizedRect,
  ): NormalizedPoint? {
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

    val localX = ((mappedTouchX - left) / displayedWidth).coerceIn(0f, 1f).toDouble()
    val localY = ((mappedTouchY - top) / displayedHeight).coerceIn(0f, 1f).toDouble()
    // 作者: long；Mac 端可能只回传手机当前放大的局部桌面帧，输入仍必须还原到完整桌面归一化坐标，避免局部高清后鼠标点击整体偏移。
    return NormalizedPoint(
      x = (sourceRect.x + localX * sourceRect.width).coerceIn(0.0, 1.0),
      y = (sourceRect.y + localY * sourceRect.height).coerceIn(0.0, 1.0),
    )
  }

  private fun currentRemoteViewportVisibleRect(): NormalizedRect? {
    val imageView = binding.remoteTouchLayer
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
    val fitScale = min(
      contentWidth.toFloat() / frameWidth.toFloat(),
      contentHeight.toFloat() / frameHeight.toFloat(),
    )
    if (fitScale <= 0f) {
      return null
    }

    val displayedWidth = frameWidth * fitScale
    val displayedHeight = frameHeight * fitScale
    val frameLeft = imageView.paddingLeft + (contentWidth - displayedWidth) / 2f
    val frameTop = imageView.paddingTop + (contentHeight - displayedHeight) / 2f
    val frameRight = frameLeft + displayedWidth
    val frameBottom = frameTop + displayedHeight
    val sourceRect = remoteFrameSourceRect
    val visibleLeft = (imageView.paddingLeft.toFloat() - remoteViewportOffsetX) / remoteViewportScale
    val visibleTop = (imageView.paddingTop.toFloat() - remoteViewportOffsetY) / remoteViewportScale
    val visibleRight = (imageView.paddingLeft.toFloat() + contentWidth - remoteViewportOffsetX) / remoteViewportScale
    val visibleBottom = (imageView.paddingTop.toFloat() + contentHeight - remoteViewportOffsetY) / remoteViewportScale
    val clippedLeft = maxOf(visibleLeft, frameLeft)
    val clippedTop = maxOf(visibleTop, frameTop)
    val clippedRight = minOf(visibleRight, frameRight)
    val clippedBottom = minOf(visibleBottom, frameBottom)
    if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) {
      return null
    }

    // 作者: long；这里输出的是完整电脑桌面里的可见区域，不是手机 View 像素；后续 Mac 端按这个区域裁剪采集时，Android 输入坐标仍能沿用完整桌面归一化坐标。
    val localX = ((clippedLeft - frameLeft) / displayedWidth).coerceIn(0f, 1f).toDouble()
    val localY = ((clippedTop - frameTop) / displayedHeight).coerceIn(0f, 1f).toDouble()
    val localWidth = ((clippedRight - clippedLeft) / displayedWidth).coerceIn(0f, 1f - localX.toFloat()).toDouble()
    val localHeight = ((clippedBottom - clippedTop) / displayedHeight).coerceIn(0f, 1f - localY.toFloat()).toDouble()
    return NormalizedRect(
      x = (sourceRect.x + localX * sourceRect.width).coerceIn(0.0, 1.0),
      y = (sourceRect.y + localY * sourceRect.height).coerceIn(0.0, 1.0),
      width = (localWidth * sourceRect.width).coerceIn(0.0, 1.0),
      height = (localHeight * sourceRect.height).coerceIn(0.0, 1.0),
    )
  }

  private fun clampRemoteViewportSourceRect(rect: NormalizedRect): NormalizedRect {
    val requestedWidth = if (rect.width > 0.0 && rect.width <= 1.0) rect.width else 1.0
    val requestedHeight = if (rect.height > 0.0 && rect.height <= 1.0) rect.height else 1.0
    val safeWidth = requestedWidth
      .coerceAtLeast(REMOTE_VIEWPORT_MIN_SOURCE_RECT_SIZE)
      .coerceAtMost(1.0)
    val safeHeight = requestedHeight
      .coerceAtLeast(REMOTE_VIEWPORT_MIN_SOURCE_RECT_SIZE)
      .coerceAtMost(1.0)
    val requestedX = if (rect.x >= 0.0 && rect.x <= 1.0) rect.x else 0.0
    val requestedY = if (rect.y >= 0.0 && rect.y <= 1.0) rect.y else 0.0
    val centerX = (requestedX + requestedWidth / 2.0).coerceIn(0.0, 1.0)
    val centerY = (requestedY + requestedHeight / 2.0).coerceIn(0.0, 1.0)
    val maxX = (1.0 - safeWidth).coerceAtLeast(0.0)
    val maxY = (1.0 - safeHeight).coerceAtLeast(0.0)
    // 作者: long；最大缩放时不能把 Mac 采集源递归裁到过小区域，保留用户视角中心并夹紧最小窗口，避免极小 JPEG/Bitmap 抖动诱发闪退或严重卡顿。
    return NormalizedRect(
      x = (centerX - safeWidth / 2.0).coerceIn(0.0, maxX),
      y = (centerY - safeHeight / 2.0).coerceIn(0.0, maxY),
      width = safeWidth,
      height = safeHeight,
    )
  }

  private fun currentRemoteViewportFocusPoint(visibleRect: NormalizedRect?): NormalizedPoint? {
    val touchFocus = if (remotePinchFocusInitialized) {
      mapTouchToFrame(binding.remoteTouchLayer, remotePinchFocusX, remotePinchFocusY)
    } else {
      null
    }
    if (touchFocus != null) {
      return touchFocus
    }
    val rect = visibleRect ?: return null
    return NormalizedPoint(
      x = (rect.x + rect.width / 2.0).coerceIn(0.0, 1.0),
      y = (rect.y + rect.height / 2.0).coerceIn(0.0, 1.0),
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
    binding.remoteKeyboardPanelButton.setOnClickListener {
      showRemoteKeyboardDialog()
    }
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
    binding.remoteShortcutSelectAllButton.setOnClickListener {
      sendRemoteShortcut("KeyA", listOf("MetaLeft"), "⌘A")
    }
    binding.remoteShortcutCopyButton.setOnClickListener {
      sendRemoteShortcut("KeyC", listOf("MetaLeft"), "⌘C")
    }
    binding.remoteShortcutPasteButton.setOnClickListener {
      sendRemoteShortcut("KeyV", listOf("MetaLeft"), "⌘V")
    }
    binding.remoteShortcutUndoButton.setOnClickListener {
      sendRemoteShortcut("KeyZ", listOf("MetaLeft"), "⌘Z")
    }
    binding.remoteShortcutEscapeButton.setOnClickListener {
      sendRemoteKeyPress("Escape", emptyList(), "Esc")
    }
    binding.remoteShortcutTabButton.setOnClickListener {
      sendRemoteKeyPress("Tab", emptyList(), "Tab")
    }
    binding.remoteShortcutArrowLeftButton.setOnClickListener {
      sendRemoteKeyPress("ArrowLeft", emptyList(), "←")
    }
    binding.remoteShortcutArrowDownButton.setOnClickListener {
      sendRemoteKeyPress("ArrowDown", emptyList(), "↓")
    }
    binding.remoteShortcutArrowUpButton.setOnClickListener {
      sendRemoteKeyPress("ArrowUp", emptyList(), "↑")
    }
    binding.remoteShortcutArrowRightButton.setOnClickListener {
      sendRemoteKeyPress("ArrowRight", emptyList(), "→")
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

  private fun initializeSessionToolControls() {
    binding.remoteClipboardSendButton.setOnClickListener {
      sendAndroidClipboardToRemote()
    }
    binding.remoteFileSendButton.setOnClickListener {
      if (sessionId.isNullOrBlank()) {
        updateRemoteTransferStatus("剪贴板/文件：请先建立会话")
        return@setOnClickListener
      }
      remoteFilePicker.launch(arrayOf("*/*"))
    }
  }

  private fun handleExternalShareIntent(intent: Intent?) {
    val action = intent?.action ?: return
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
      return
    }
    val sharedUris = extractSharedFileUris(intent)
    val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
    if (sharedUris.isEmpty() && sharedText.isBlank()) {
      updateRemoteTransferStatus("分享：没有可发送的文本或文件")
      return
    }

    if (sharedText.isNotBlank()) {
      pendingSharedClipboardText = sharedText
    }
    sharedUris.forEach { uri ->
      persistSharedReadPermissionIfPossible(intent, uri)
      pendingSharedFileUris.addLast(uri)
    }
    updateRemoteTransferStatus(
      "分享：已接收 ${if (sharedText.isNotBlank()) "文本" else ""}${if (sharedUris.isNotEmpty()) " ${sharedUris.size} 个文件" else ""}".trim(),
    )
    processPendingSharedTools()
  }

  private fun processPendingSharedTools() {
    if (pendingSharedClipboardText.isNullOrBlank() && pendingSharedFileUris.isEmpty()) {
      return
    }
    if (sessionId.isNullOrBlank()) {
      updateRemoteTransferStatus("分享：等待建立会话后发送")
      return
    }

    pendingSharedClipboardText?.takeIf { it.isNotBlank() }?.let { text ->
      pendingSharedClipboardText = null
      sendAndroidClipboardTextToRemote(text)
    }
    while (pendingSharedFileUris.isNotEmpty()) {
      sendAndroidFileToRemote(pendingSharedFileUris.removeFirst())
    }
  }

  private fun extractSharedFileUris(intent: Intent): List<Uri> {
    return when (intent.action) {
      Intent.ACTION_SEND -> getSharedStreamUri(intent)?.let { listOf(it) }.orEmpty()
      Intent.ACTION_SEND_MULTIPLE -> getSharedStreamUriList(intent)
      else -> emptyList()
    }
  }

  private fun getSharedStreamUri(intent: Intent): Uri? {
    @Suppress("DEPRECATION")
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
      intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
  }

  private fun getSharedStreamUriList(intent: Intent): List<Uri> {
    @Suppress("DEPRECATION")
    val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
      intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
    }
    return uris.orEmpty().filterIsInstance<Uri>()
  }

  private fun persistSharedReadPermissionIfPossible(intent: Intent, uri: Uri) {
    if ((intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) {
      return
    }
    try {
      contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (error: SecurityException) {
      Log.w(RTC_TAG, "shared_uri_persist_permission_failed uri=$uri error=${error.message ?: "unknown"}")
    } catch (error: IllegalArgumentException) {
      Log.w(RTC_TAG, "shared_uri_persist_permission_unavailable uri=$uri error=${error.message ?: "unknown"}")
    }
  }

  private fun persistDocumentReadPermissionIfPossible(uri: Uri) {
    try {
      // 作者: long；系统文件选择器返回的 Uri 可能来自云盘或第三方 DocumentsProvider，能持久化就保留读授权，避免后台分块发送时临时权限过早失效。
      contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (error: SecurityException) {
      Log.w(RTC_TAG, "document_uri_persist_permission_failed uri=$uri error=${error.message ?: "unknown"}")
    } catch (error: IllegalArgumentException) {
      Log.w(RTC_TAG, "document_uri_persist_permission_unavailable uri=$uri error=${error.message ?: "unknown"}")
    }
  }

  private fun sendAndroidClipboardToRemote() {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val text = clipboard
      ?.primaryClip
      ?.takeIf { it.itemCount > 0 }
      ?.getItemAt(0)
      ?.coerceToText(this)
      ?.toString()
      .orEmpty()
    if (text.isBlank()) {
      updateRemoteTransferStatus("剪贴板：本机剪贴板为空")
      return
    }
    sendAndroidClipboardTextToRemote(text)
  }

  private fun sendAndroidClipboardTextToRemote(text: String) {
    val currentSessionId = sessionId
    if (currentSessionId.isNullOrBlank()) {
      updateRemoteTransferStatus("剪贴板：请先建立会话")
      return
    }
    if (text.isBlank()) {
      updateRemoteTransferStatus("剪贴板：待发送文本为空")
      return
    }
    val clipboardId = "clip-${UUID.randomUUID()}"
    val sent = sendSocketMessage(
      controller.clipboardTextMessage(currentSessionId, clipboardId, text),
      "发送剪贴板到远端 ${text.length} 字符",
      logSuccess = false,
    )
    updateRemoteTransferStatus(
      if (sent) "剪贴板：已发送 ${text.length} 字符" else "剪贴板：发送失败",
    )
  }

  private fun handleIncomingClipboardText(json: JSONObject, payload: JSONObject) {
    if (!isCurrentSessionToolMessage(json, payload, "剪贴板")) {
      return
    }
    val clipboardId = payload.optNonBlank("clipboard_id").orEmpty()
    val text = payload.optString("text").orEmpty()
    if (text.isEmpty()) {
      updateRemoteTransferStatus("剪贴板：收到空内容，已忽略")
      sendClipboardApplyResult(clipboardId, applied = false, chars = 0, errorDetail = "clipboard text is empty")
      return
    }
    try {
      val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
      clipboard?.setPrimaryClip(ClipData.newPlainText("RemoteDesk", text))
      if (isDebuggableBuild()) {
        // 作者: long；双向剪贴板验收要能证明文本内容一致，debug 构建记录精确内容，正式构建不走这条调试日志。
        Log.i(RTC_TAG, "debug_clipboard_received length=${text.length} text=$text")
      }
      updateRemoteTransferStatus("剪贴板：已接收 ${text.length} 字符")
      appendLog("收到远端剪贴板 ${text.length} 字符，已写入本机剪贴板")
      sendClipboardApplyResult(clipboardId, applied = true, chars = text.length)
    } catch (error: Exception) {
      val detail = error.message ?: "unknown"
      updateRemoteTransferStatus("剪贴板：接收成功但写入失败 $detail")
      sendClipboardApplyResult(clipboardId, applied = false, chars = text.length, errorDetail = detail)
    }
  }

  private fun sendClipboardApplyResult(
    clipboardId: String,
    applied: Boolean,
    chars: Int,
    errorDetail: String = "",
  ): Boolean {
    val currentSessionId = sessionId
    val cleanClipboardId = clipboardId.trim()
    if (currentSessionId.isNullOrBlank() || cleanClipboardId.isBlank()) {
      return false
    }
    // 作者: long；共享剪贴板必须回传本机应用结果，发送端才能区分 relay 已转发和目标设备已经写入系统剪贴板。
    return sendSocketMessage(
      controller.clipboardResultMessage(
        currentSessionId,
        cleanClipboardId,
        applied,
        chars.coerceAtLeast(0),
        if (applied) "" else errorDetail.ifBlank { "clipboard apply failed" }.take(512),
      ),
      "发送剪贴板应用结果 ${if (applied) "success" else "failed"}",
      logSuccess = false,
    )
  }

  private fun handleIncomingClipboardResult(json: JSONObject, payload: JSONObject) {
    if (!isCurrentSessionToolMessage(json, payload, "剪贴板")) {
      return
    }
    val applied = payload.optBoolean("applied", false)
    val chars = payload.optInt("chars", 0).coerceAtLeast(0)
    val detail = payload.optNonBlank("error_detail").orEmpty()
    updateRemoteTransferStatus(
      if (applied) "剪贴板：对端已写入 $chars 字符" else "剪贴板：对端写入失败 ${detail.ifBlank { "unknown" }}",
    )
  }

  private fun sendAndroidFileToRemote(uri: Uri) {
    val currentSessionId = sessionId
    if (currentSessionId.isNullOrBlank()) {
      updateRemoteTransferStatus("文件：请先建立会话")
      return
    }
    updateRemoteTransferStatus("文件：正在读取本机文件")
    // 作者: long；用户文件读取可能走系统 DocumentsProvider，必须和设备列表/E2E proof 网络同步隔离，避免同步请求卡住后文件发送排队不执行。
    fileTransferExecutor.execute {
      try {
        val name = queryOpenableDisplayName(uri).ifBlank { "remote-file.bin" }
        val mime = contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val declaredSize = queryOpenableSize(uri)
        if (declaredSize > ANDROID_INCOMING_FILE_MAX_BYTES) {
          runOnUiThread {
            updateRemoteTransferStatus("文件：超过 64MB，未发送")
          }
          return@execute
        }
        val bytes = readUriBytesWithLimit(uri, ANDROID_INCOMING_FILE_MAX_BYTES)
        val sent = sendAndroidFileBytesToRemote(currentSessionId, name, mime, bytes)
        runOnUiThread {
          updateRemoteTransferStatus(
            if (sent) "文件：已发送 ${sanitizeRemoteFileName(name)} (${formatBytes(bytes.size.toLong())})" else "文件：发送中断",
          )
        }
      } catch (error: Exception) {
        runOnUiThread {
          updateRemoteTransferStatus("文件：发送失败 ${error.message ?: "unknown"}")
          appendLog("发送文件失败：${error.message ?: "unknown"}")
        }
      }
    }
  }

  private fun sendAndroidFilePathToRemote(path: String) {
    val currentSessionId = sessionId
    if (currentSessionId.isNullOrBlank()) {
      updateRemoteTransferStatus("文件：请先建立会话")
      return
    }
    val file = File(path)
    if (!file.isFile) {
      updateRemoteTransferStatus("文件：调试路径不存在")
      return
    }
    if (file.length() > ANDROID_INCOMING_FILE_MAX_BYTES) {
      updateRemoteTransferStatus("文件：超过 64MB，未发送")
      return
    }
    // 作者: long；debug 文件同样走真实文件传输链路，独立线程能保证验证时不会被设备同步或 proof 刷新阻塞。
    fileTransferExecutor.execute {
      try {
        val bytes = file.readBytes()
        val sent = sendAndroidFileBytesToRemote(
          currentSessionId,
          file.name.ifBlank { "remote-file.bin" },
          "application/octet-stream",
          bytes,
        )
        runOnUiThread {
          updateRemoteTransferStatus(
            if (sent) "文件：已发送 ${sanitizeRemoteFileName(file.name)} (${formatBytes(bytes.size.toLong())})" else "文件：发送中断",
          )
        }
      } catch (error: Exception) {
        runOnUiThread {
          updateRemoteTransferStatus("文件：发送失败 ${error.message ?: "unknown"}")
          appendLog("发送调试文件失败：${error.message ?: "unknown"}")
        }
      }
    }
  }

  private fun sendAndroidFileBytesToRemote(
    currentSessionId: String,
    name: String,
    mime: String,
    bytes: ByteArray,
  ): Boolean {
    val totalChunks = ((bytes.size + SESSION_FILE_CHUNK_RAW_BYTES - 1) / SESSION_FILE_CHUNK_RAW_BYTES).coerceAtLeast(1)
    val fileId = "file-${UUID.randomUUID()}"
    val cleanName = sanitizeRemoteFileName(name)
    val sha256 = sha256Hex(bytes)
    var sent = sendSocketMessage(
      controller.fileTransferStartMessage(
        currentSessionId,
        fileId,
        cleanName,
        mime.ifBlank { "application/octet-stream" },
        bytes.size.toLong(),
        totalChunks,
        sha256,
      ),
      "发送文件开始 $cleanName",
      logSuccess = false,
    )
    if (sent) {
      for (chunkIndex in 0 until totalChunks) {
        val start = chunkIndex * SESSION_FILE_CHUNK_RAW_BYTES
        val end = min(start + SESSION_FILE_CHUNK_RAW_BYTES, bytes.size)
        val encoded = Base64.encodeToString(bytes.copyOfRange(start, end), Base64.NO_WRAP)
        sent = sendSocketMessage(
          controller.fileTransferChunkMessage(currentSessionId, fileId, chunkIndex, totalChunks, encoded),
          "发送文件分块 ${chunkIndex + 1}/$totalChunks",
          logSuccess = false,
        )
        if (!sent) {
          break
        }
      }
    }
    if (sent) {
      sent = sendSocketMessage(
        controller.fileTransferCompleteMessage(
          currentSessionId,
          fileId,
          cleanName,
          mime.ifBlank { "application/octet-stream" },
          bytes.size.toLong(),
          totalChunks,
          sha256,
        ),
        "发送文件完成 $cleanName",
        logSuccess = false,
      )
    }
    return sent
  }

  private fun sendFileTransferApplyResult(
    fileId: String,
    applied: Boolean,
    name: String = "",
    bytes: Long = 0L,
    sha256: String = "",
    location: String = "",
    errorDetail: String = "",
  ): Boolean {
    val currentSessionId = sessionId
    val cleanFileId = fileId.trim()
    if (currentSessionId.isNullOrBlank() || cleanFileId.isBlank()) {
      return false
    }
    // 作者: long；发送完成只说明分块到达对端，file.transfer.result 才证明接收端完成校验并把文件落到本机存储。
    return sendSocketMessage(
      controller.fileTransferResultMessage(
        currentSessionId,
        cleanFileId,
        applied,
        sanitizeRemoteFileName(name),
        bytes.coerceAtLeast(0L),
        sha256.takeIf { it.matches(Regex("^[A-Fa-f0-9]{64}$")) }.orEmpty(),
        location.take(512),
        if (applied) "" else errorDetail.ifBlank { "file receive failed" }.take(512),
      ),
      "发送文件接收结果 ${if (applied) "success" else "failed"}",
      logSuccess = false,
    )
  }

  private fun handleIncomingFileTransferStart(json: JSONObject, payload: JSONObject) {
    if (!isCurrentSessionToolMessage(json, payload, "文件")) {
      return
    }
    val fileId = payload.optNonBlank("file_id")
    val name = sanitizeRemoteFileName(payload.optString("name").orEmpty())
    val totalChunks = payload.optInt("total_chunks", 0)
    val size = payload.optLong("size", -1L)
    if (
      fileId.isNullOrBlank() ||
      name.isBlank() ||
      totalChunks <= 0 ||
      totalChunks > SESSION_FILE_MAX_CHUNKS ||
      size > ANDROID_INCOMING_FILE_MAX_BYTES
    ) {
      updateRemoteTransferStatus("文件：收到无效文件请求")
      sendFileTransferApplyResult(
        fileId.orEmpty(),
        applied = false,
        name = name,
        bytes = size.coerceAtLeast(0L),
        sha256 = payload.optNonBlank("sha256").orEmpty(),
        errorDetail = "invalid file transfer start payload",
      )
      return
    }
    incomingFileTransfers[fileId] = IncomingFileTransfer(
      fileId = fileId,
      name = name,
      mime = payload.optString("mime").orEmpty().ifBlank { "application/octet-stream" },
      size = size,
      totalChunks = totalChunks,
      sha256 = payload.optNonBlank("sha256").orEmpty(),
    )
    updateRemoteTransferStatus("文件：开始接收 $name，共 $totalChunks 块")
  }

  private fun handleIncomingFileTransferChunk(json: JSONObject, payload: JSONObject) {
    if (!isCurrentSessionToolMessage(json, payload, "文件")) {
      return
    }
    val fileId = payload.optNonBlank("file_id") ?: return
    val transfer = incomingFileTransfers[fileId]
    if (transfer == null) {
      updateRemoteTransferStatus("文件：收到未知分块，已忽略")
      sendFileTransferApplyResult(
        fileId,
        applied = false,
        errorDetail = "file chunk arrived before transfer start",
      )
      return
    }
    val chunkIndex = payload.optInt("chunk_index", -1)
    val totalChunks = payload.optInt("total_chunks", -1)
    val dataBase64 = payload.optString("data_base64").orEmpty()
    if (chunkIndex !in 0 until transfer.totalChunks || totalChunks != transfer.totalChunks || dataBase64.isBlank()) {
      updateRemoteTransferStatus("文件：收到无效分块，已忽略")
      sendFileTransferApplyResult(
        fileId,
        applied = false,
        name = transfer.name,
        bytes = transfer.size,
        sha256 = transfer.sha256,
        errorDetail = "invalid file transfer chunk payload",
      )
      return
    }
    try {
      transfer.chunks[chunkIndex] = Base64.decode(dataBase64, Base64.DEFAULT)
      updateRemoteTransferStatus("文件：接收 ${transfer.name} ${transfer.chunks.size}/${transfer.totalChunks}")
    } catch (error: IllegalArgumentException) {
      updateRemoteTransferStatus("文件：分块解码失败")
      sendFileTransferApplyResult(
        fileId,
        applied = false,
        name = transfer.name,
        bytes = transfer.size,
        sha256 = transfer.sha256,
        errorDetail = error.message ?: "base64 decode failed",
      )
    }
  }

  private fun handleIncomingFileTransferComplete(json: JSONObject, payload: JSONObject) {
    if (!isCurrentSessionToolMessage(json, payload, "文件")) {
      return
    }
    val fileId = payload.optNonBlank("file_id") ?: return
    val transfer = incomingFileTransfers[fileId]
    if (transfer == null) {
      updateRemoteTransferStatus("文件：完成消息没有对应文件")
      sendFileTransferApplyResult(
        fileId,
        applied = false,
        errorDetail = "file complete arrived before transfer start",
      )
      return
    }
    if (transfer.chunks.size != transfer.totalChunks) {
      updateRemoteTransferStatus("文件：${transfer.name} 分块未收齐 ${transfer.chunks.size}/${transfer.totalChunks}")
      sendFileTransferApplyResult(
        fileId,
        applied = false,
        name = transfer.name,
        bytes = transfer.size,
        sha256 = transfer.sha256,
        errorDetail = "missing chunks ${transfer.chunks.size}/${transfer.totalChunks}",
      )
      return
    }
    val chunksSnapshot = mutableListOf<ByteArray>()
    for (index in 0 until transfer.totalChunks) {
      val chunk = transfer.chunks[index]
      if (chunk == null) {
        updateRemoteTransferStatus("文件：${transfer.name} 缺少第 ${index + 1} 块")
        sendFileTransferApplyResult(
          fileId,
          applied = false,
          name = transfer.name,
          bytes = transfer.size,
          sha256 = transfer.sha256,
          errorDetail = "missing chunk ${index + 1}",
        )
        return
      }
      chunksSnapshot += chunk
    }
    val expectedSha256 = payload.optNonBlank("sha256") ?: transfer.sha256
    incomingFileTransfers.remove(fileId)
    updateRemoteTransferStatus("文件：正在保存 ${transfer.name}")
    // 作者: long；接收文件默认先落应用私有目录，避免远控热路径持有 MediaProvider 依赖；后续可单独做“导出到下载目录”的显式动作。
    // 作者: long；接收文件保存不能复用设备同步线程；真机上 /devices 或 proof 请求一旦超时，会让用户看到“正在保存”但实际文件永远不落盘。
    fileTransferExecutor.execute {
      try {
      val output = ByteArrayOutputStream()
      for (chunk in chunksSnapshot) {
        output.write(chunk)
        if (output.size().toLong() > ANDROID_INCOMING_FILE_MAX_BYTES) {
          error("file exceeds 64MB")
        }
      }
      val bytes = output.toByteArray()
      if (transfer.size >= 0 && bytes.size.toLong() != transfer.size) {
        sendFileTransferApplyResult(
          fileId,
          applied = false,
          name = transfer.name,
          bytes = bytes.size.toLong(),
          sha256 = transfer.sha256,
          errorDetail = "size mismatch expected=${transfer.size} actual=${bytes.size}",
        )
        runOnUiThread {
          updateRemoteTransferStatus("文件：${transfer.name} 大小不匹配，已丢弃")
        }
        return@execute
      }
      if (expectedSha256.isNotBlank()) {
        val actualSha256 = sha256Hex(bytes)
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
          sendFileTransferApplyResult(
            fileId,
            applied = false,
            name = transfer.name,
            bytes = bytes.size.toLong(),
            sha256 = actualSha256,
            errorDetail = "sha256 mismatch",
          )
          runOnUiThread {
            updateRemoteTransferStatus("文件：${transfer.name} 哈希不匹配，已丢弃")
            appendLog("文件哈希不匹配：expected=$expectedSha256 actual=$actualSha256")
          }
          return@execute
        }
      }
      val savedFile = saveIncomingFileToDownloads(transfer.name, transfer.mime, bytes)
      sendFileTransferApplyResult(
        fileId,
        applied = true,
        name = savedFile.name,
        bytes = bytes.size.toLong(),
        sha256 = expectedSha256.ifBlank { transfer.sha256 },
        location = savedFile.location,
      )
      runOnUiThread {
        updateRemoteTransferStatus("文件：已保存 ${savedFile.name}")
        appendLog("收到远端文件 ${transfer.name}，已保存到 ${savedFile.location}")
      }
      } catch (error: Exception) {
        sendFileTransferApplyResult(
          fileId,
          applied = false,
          name = transfer.name,
          bytes = transfer.size.coerceAtLeast(0L),
          sha256 = transfer.sha256,
          errorDetail = error.message ?: "unknown",
        )
        runOnUiThread {
          updateRemoteTransferStatus("文件：保存失败 ${error.message ?: "unknown"}")
          appendLog("保存远端文件失败：${error.message ?: "unknown"}")
        }
      }
    }
  }

  private fun handleIncomingFileTransferResult(json: JSONObject, payload: JSONObject) {
    if (!isCurrentSessionToolMessage(json, payload, "文件")) {
      return
    }
    val applied = payload.optBoolean("applied", false)
    val name = sanitizeRemoteFileName(payload.optString("name").orEmpty())
    val bytes = payload.optLong("bytes", 0L).coerceAtLeast(0L)
    val location = payload.optNonBlank("location").orEmpty()
    val detail = payload.optNonBlank("error_detail").orEmpty()
    updateRemoteTransferStatus(
      if (applied) "文件：对端已保存 $name (${formatBytes(bytes)})" else "文件：对端保存失败 ${detail.ifBlank { "unknown" }}",
    )
    if (applied && location.isNotBlank()) {
      appendLog("文件：对端保存位置 $location")
    }
  }

  private fun isCurrentSessionToolMessage(json: JSONObject, payload: JSONObject, label: String): Boolean {
    val messageSessionId = json.optNonBlank("session_id") ?: payload.optNonBlank("session_id")
    val currentSessionId = sessionId
    if (currentSessionId.isNullOrBlank() || messageSessionId.isNullOrBlank() || messageSessionId != currentSessionId) {
      appendLog("忽略非当前会话${label}消息")
      return false
    }
    return true
  }

  private fun updateRemoteTransferStatus(text: String) {
    binding.remoteTransferStatusText.text = text
    appendLog(text)
  }

  private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
  }

  private fun handleDebugToolIntent(intent: Intent?, attempt: Int = 0) {
    if (!isDebuggableBuild() || intent == null) {
      return
    }
    val setClipboardText = intent.getStringExtra(EXTRA_DEBUG_SET_CLIPBOARD_TEXT)
    val sendClipboardText = intent.getStringExtra(EXTRA_DEBUG_SEND_CLIPBOARD_TEXT)
    val sendClipboardFromSystem = intent.getBooleanExtra(EXTRA_DEBUG_SEND_CLIPBOARD_FROM_SYSTEM, false)
    val dumpClipboardText = intent.getBooleanExtra(EXTRA_DEBUG_DUMP_CLIPBOARD_TEXT, false)
    val sendFilePath = intent.getStringExtra(EXTRA_DEBUG_SEND_FILE_PATH)?.trim().orEmpty()
    val sendViewportInteraction = intent.getBooleanExtra(EXTRA_DEBUG_SEND_VIEWPORT_INTERACTION, false)
    val debugFullscreen = intent.hasExtra(EXTRA_DEBUG_FULLSCREEN)
    val debugSimulatePinch = intent.getBooleanExtra(EXTRA_DEBUG_SIMULATE_PINCH, false)
    val hasDebugToolAction = setClipboardText != null ||
      sendClipboardText != null ||
      sendClipboardFromSystem ||
      dumpClipboardText ||
      sendFilePath.isNotBlank() ||
      sendViewportInteraction ||
      debugFullscreen ||
      debugSimulatePinch
    if (!hasDebugToolAction) {
      return
    }

    if (setClipboardText != null && attempt == 0) {
      val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
      clipboard?.setPrimaryClip(ClipData.newPlainText("RemoteDesk Debug", setClipboardText))
      updateRemoteTransferStatus("剪贴板：调试文本已写入本机 ${setClipboardText.length} 字符")
    }

    val needsSession = sendClipboardText != null ||
      sendClipboardFromSystem ||
      sendFilePath.isNotBlank() ||
      sendViewportInteraction ||
      debugSimulatePinch
    if (needsSession && sessionId.isNullOrBlank()) {
      if (attempt < DEBUG_TOOL_MAX_ATTEMPTS) {
        // 作者: long；adb 调试入口常和自动建链同时触发，短暂等待会话可避免把验证脚本写成固定 sleep。
        remoteGestureHandler.postDelayed(
          { handleDebugToolIntent(intent, attempt + 1) },
          DEBUG_TOOL_RETRY_DELAY_MS,
        )
      } else {
        updateRemoteTransferStatus("调试工具：等待会话超时，未发送")
      }
      return
    }

    if (sendClipboardText != null) {
      sendAndroidClipboardTextToRemote(sendClipboardText)
    }
    if (sendClipboardFromSystem) {
      sendAndroidClipboardToRemote()
    }
    if (dumpClipboardText) {
      val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
      val text = clipboard
        ?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(this)
        ?.toString()
        .orEmpty()
      // 作者: long；Mac -> Android 剪贴板验收需要能从 adb 日志看到精确文本，调试 dump 只在 debuggable 构建响应。
      Log.i(RTC_TAG, "debug_clipboard_dump length=${text.length} text=$text")
      updateRemoteTransferStatus("剪贴板：调试读取 ${text.length} 字符")
    }
    if (sendFilePath.isNotBlank()) {
      sendAndroidFilePathToRemote(sendFilePath)
    }
    if (debugFullscreen) {
      val enabled = intent.getBooleanExtra(EXTRA_DEBUG_FULLSCREEN_ENABLED, true)
      setRemoteViewportFullscreenForDebug(enabled)
    }
    if (sendViewportInteraction) {
      sendDebugViewportInteraction(intent)
    }
    if (debugSimulatePinch) {
      simulateDebugPinchGesture(intent)
    }
  }

  private fun setRemoteViewportFullscreenForDebug(enabled: Boolean) {
    if (enabled == remoteFullscreenActive) {
      updateRemoteTransferStatus(if (enabled) "视口：已处于全屏" else "视口：已退出全屏")
      return
    }
    // 作者: long；全屏按钮在真机横屏后容易被系统栏和视频层影响命中，adb 调试入口用于验收完整显示和缩放链路，不改变正式交互路径。
    if (enabled) {
      enterRemoteViewportFullscreen()
    } else {
      exitRemoteViewportFullscreen(reason = "debug_intent")
    }
  }

  private fun sendDebugViewportInteraction(intent: Intent) {
    val currentSessionId = sessionId?.trim().orEmpty()
    if (currentSessionId.isBlank()) {
      updateRemoteTransferStatus("视口：调试发送失败，无会话")
      return
    }
    val scale = intent.getDoubleExtra(EXTRA_DEBUG_VIEWPORT_SCALE, 1.6).toFloat()
      .coerceIn(REMOTE_VIEWPORT_MIN_SCALE, REMOTE_VIEWPORT_MAX_SCALE)
    val viewportX = intent.getDoubleExtra(EXTRA_DEBUG_VIEWPORT_X, 0.25)
    val viewportY = intent.getDoubleExtra(EXTRA_DEBUG_VIEWPORT_Y, 0.20)
    val viewportWidth = intent.getDoubleExtra(EXTRA_DEBUG_VIEWPORT_WIDTH, 0.50)
    val viewportHeight = intent.getDoubleExtra(EXTRA_DEBUG_VIEWPORT_HEIGHT, 0.45)
    if (viewportWidth <= 0.0 || viewportHeight <= 0.0 || viewportX < 0.0 || viewportY < 0.0 ||
      viewportX + viewportWidth > 1.0 || viewportY + viewportHeight > 1.0
    ) {
      updateRemoteTransferStatus("视口：调试区域非法，未发送")
      return
    }
    val safeViewport = clampRemoteViewportSourceRect(
      NormalizedRect(
        x = viewportX,
        y = viewportY,
        width = viewportWidth,
        height = viewportHeight,
      ),
    )
    val focusX = intent.getDoubleExtra(EXTRA_DEBUG_VIEWPORT_FOCUS_X, viewportX + viewportWidth / 2.0)
    val focusY = intent.getDoubleExtra(EXTRA_DEBUG_VIEWPORT_FOCUS_Y, viewportY + viewportHeight / 2.0)
    // 作者: long；该入口只用于真机自动验收区域高清链路，仍走 Android -> relay -> Mac 的会话消息，不替代人工双指手感验收。
    val sent = sendSocketMessage(
      controller.viewportInteractionMessage(
        sessionId = currentSessionId,
        phase = "end",
        interaction = "pinch",
        scale = maxOf(scale, remoteViewportInteractionScale(safeViewport, requestFullViewport = false)),
        viewportX = safeViewport.x,
        viewportY = safeViewport.y,
        viewportWidth = safeViewport.width,
        viewportHeight = safeViewport.height,
        focusX = focusX.coerceIn(0.0, 1.0),
        focusY = focusY.coerceIn(0.0, 1.0),
      ),
      "debug viewport pinch end",
      logSuccess = true,
    )
    updateRemoteTransferStatus(if (sent) "视口：已发送调试局部高清提示" else "视口：调试发送失败")
  }

  private fun simulateDebugPinchGesture(intent: Intent) {
    val touchLayer = binding.remoteTouchLayer
    if (renderedFrameWidth <= 0 || renderedFrameHeight <= 0 || touchLayer.width <= 0 || touchLayer.height <= 0) {
      updateRemoteTransferStatus("视口：调试双指缩放失败，远程画面尚未就绪")
      return
    }
    val centerX = (intent.getDoubleExtra(EXTRA_DEBUG_PINCH_CENTER_X, 0.5).toFloat() * touchLayer.width)
      .coerceIn(1f, (touchLayer.width - 1).coerceAtLeast(1).toFloat())
    val centerY = (intent.getDoubleExtra(EXTRA_DEBUG_PINCH_CENTER_Y, 0.5).toFloat() * touchLayer.height)
      .coerceIn(1f, (touchLayer.height - 1).coerceAtLeast(1).toFloat())
    val maxSpan = (min(touchLayer.width, touchLayer.height).toFloat() * 0.86f).coerceAtLeast(dpFloat(80))
    val startSpan = intent.getDoubleExtra(EXTRA_DEBUG_PINCH_START_SPAN, dpFloat(120).toDouble()).toFloat()
      .coerceIn(dpFloat(48), maxSpan)
    val endSpan = intent.getDoubleExtra(EXTRA_DEBUG_PINCH_END_SPAN, dpFloat(310).toDouble()).toFloat()
      .coerceIn(dpFloat(48), maxSpan)
    val steps = intent.getIntExtra(EXTRA_DEBUG_PINCH_STEPS, 18).coerceIn(4, 60)
    val intervalMs = intent.getLongExtra(EXTRA_DEBUG_PINCH_INTERVAL_MS, 16L).coerceIn(8L, 80L)
    val downTime = SystemClock.uptimeMillis()
    remoteDebugPinchToken += 1L
    val token = remoteDebugPinchToken
    postDebugPinchEvent(token, downTime, delayMs = 0L, centerX, centerY, startSpan, MotionEvent.ACTION_DOWN, pointerCount = 1)
    postDebugPinchEvent(
      token,
      downTime,
      delayMs = intervalMs,
      centerX,
      centerY,
      startSpan,
      MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
      pointerCount = 2,
    )
    for (step in 1..steps) {
      val progress = step.toFloat() / steps.toFloat()
      val span = startSpan + ((endSpan - startSpan) * progress)
      postDebugPinchEvent(
        token,
        downTime,
        delayMs = intervalMs * (step + 1),
        centerX,
        centerY,
        span,
        MotionEvent.ACTION_MOVE,
        pointerCount = 2,
      )
    }
    postDebugPinchEvent(
      token,
      downTime,
      delayMs = intervalMs * (steps + 2),
      centerX,
      centerY,
      endSpan,
      MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
      pointerCount = 2,
    )
    postDebugPinchEvent(
      token,
      downTime,
      delayMs = intervalMs * (steps + 3),
      centerX,
      centerY,
      endSpan,
      MotionEvent.ACTION_UP,
      pointerCount = 1,
      afterDispatch = {
        updateRemoteTransferStatus(
          "视口：已注入调试双指缩放 ${"%.2f".format(Locale.US, endSpan / startSpan)}x",
        )
      },
    )
  }

  private fun postDebugPinchEvent(
    token: Long,
    downTime: Long,
    delayMs: Long,
    centerX: Float,
    centerY: Float,
    span: Float,
    action: Int,
    pointerCount: Int,
    afterDispatch: (() -> Unit)? = null,
  ) {
    remoteGestureHandler.postDelayed(
      {
        if (!isActivityAlive || token != remoteDebugPinchToken) {
          return@postDelayed
        }
        // 作者: long；真机调试双指手势按目标帧间隔分发，才能暴露 ScaleGestureDetector 与全屏 transform 在连续 move 下的真实节奏。
        dispatchDebugPinchEvent(
          action = action,
          downTime = downTime,
          eventTime = downTime + delayMs,
          centerX = centerX,
          centerY = centerY,
          span = span,
          pointerCount = pointerCount,
        )
        afterDispatch?.invoke()
      },
      delayMs,
    )
  }

  private fun dispatchDebugPinchEvent(
    action: Int,
    downTime: Long,
    eventTime: Long,
    centerX: Float,
    centerY: Float,
    span: Float,
    pointerCount: Int,
  ) {
    val safeHalfSpan = (span / 2f).coerceAtLeast(1f)
    val properties = Array(pointerCount) { index ->
      MotionEvent.PointerProperties().apply {
        id = index
        toolType = MotionEvent.TOOL_TYPE_FINGER
      }
    }
    val coords = Array(pointerCount) { index ->
      MotionEvent.PointerCoords().apply {
        val direction = if (index == 0) -1f else 1f
        x = centerX + direction * safeHalfSpan
        y = centerY
        pressure = 1f
        size = 1f
      }
    }
    val event = MotionEvent.obtain(
      downTime,
      eventTime,
      action,
      pointerCount,
      properties,
      coords,
      0,
      0,
      1f,
      1f,
      0,
      0,
      InputDevice.SOURCE_TOUCHSCREEN,
      0,
    )
    try {
      // 作者: long；调试双指缩放必须走真实触摸处理链，才能验证 ScaleGestureDetector、viewport hint 和 source_rect 回显，而不是直接伪造后端消息。
      remoteDebugPinchDispatchActive = true
      val consumedByView = binding.remoteTouchLayer.dispatchTouchEvent(event)
      if (!consumedByView) {
        handleRemoteFrameTouchV2(binding.remoteTouchLayer, event)
      }
      Log.i(
        RTC_TAG,
        "remote_debug_pinch_event action=${MotionEvent.actionToString(action and MotionEvent.ACTION_MASK)} pointers=$pointerCount consumed=$consumedByView in_progress=${remoteScaleGestureDetector.isInProgress} scale=${String.format(Locale.US, "%.3f", remoteViewportScale)}",
      )
    } finally {
      remoteDebugPinchDispatchActive = false
      event.recycle()
    }
  }

  private fun isDebuggableBuild(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

  private fun queryOpenableDisplayName(uri: Uri): String {
    queryOpenableColumn(uri, OpenableColumns.DISPLAY_NAME)?.let { return it }
    return uri.lastPathSegment?.substringAfterLast('/').orEmpty()
  }

  private fun queryOpenableSize(uri: Uri): Long {
    val value = queryOpenableColumn(uri, OpenableColumns.SIZE) ?: return -1L
    return value.toLongOrNull() ?: -1L
  }

  private fun queryOpenableColumn(uri: Uri, column: String): String? {
    var cursor: Cursor? = null
    return try {
      cursor = contentResolver.query(uri, arrayOf(column), null, null, null)
      if (cursor != null && cursor.moveToFirst()) {
        val index = cursor.getColumnIndex(column)
        if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
      } else {
        null
      }
    } catch (_: Exception) {
      null
    } finally {
      cursor?.close()
    }
  }

  private fun readUriBytesWithLimit(uri: Uri, maxBytes: Long): ByteArray {
    val output = ByteArrayOutputStream()
    contentResolver.openInputStream(uri)?.use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) {
          break
        }
        output.write(buffer, 0, read)
        if (output.size().toLong() > maxBytes) {
          throw IOException("file exceeds 64MB")
        }
      }
    } ?: throw IOException("无法打开文件")
    return output.toByteArray()
  }

  private fun saveIncomingFileToDownloads(name: String, mime: String, bytes: ByteArray): SavedIncomingFile {
    val cleanName = sanitizeRemoteFileName(name)
    if (ANDROID_INCOMING_FILE_USE_MEDIASTORE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val executor = Executors.newSingleThreadExecutor()
      try {
        val future = executor.submit<SavedIncomingFile> {
          saveIncomingFileToMediaStore(cleanName, mime, bytes)
        }
        return future.get(ANDROID_FILE_MEDIASTORE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      } catch (error: Exception) {
        Log.w(RTC_TAG, "incoming_file_mediastore_save_failed name=$cleanName error=${error.message ?: "unknown"}")
      } finally {
        executor.shutdownNow()
      }
    }
    return saveIncomingFileToPrivateDirectory(cleanName, bytes)
  }

  private fun saveIncomingFileToMediaStore(cleanName: String, mime: String, bytes: ByteArray): SavedIncomingFile {
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
      put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
      put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RemoteDesk")
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      ?: throw IOException("无法创建系统下载文件")
    try {
      contentResolver.openOutputStream(uri)?.use { output ->
        output.write(bytes)
      } ?: throw IOException("无法打开系统下载文件")
      values.clear()
      values.put(MediaStore.MediaColumns.IS_PENDING, 0)
      contentResolver.update(uri, values, null, null)
      // 作者: long；接收远端文件的产品目标是用户能在系统文件管理器找到结果，Android 10+ 优先发布到公开 Downloads/RemoteDesk。
      return SavedIncomingFile(
        name = cleanName,
        location = "Downloads/RemoteDesk/$cleanName (${uri})",
      )
    } catch (error: Exception) {
      contentResolver.delete(uri, null, null)
      throw error
    }
  }

  private fun saveIncomingFileToPrivateDirectory(cleanName: String, bytes: ByteArray): SavedIncomingFile {
    // 作者: long；公开 Downloads 写入如果在个别 ROM 上超时或失败，仍先保住文件本身，避免远端已经传完的数据被丢弃。
    val directory = File(filesDir, "RemoteDeskIncoming")
    if (!directory.exists() && !directory.mkdirs()) {
      throw IOException("无法创建下载目录")
    }
    val file = uniqueFile(directory, cleanName)
    file.outputStream().use { output ->
      output.write(bytes)
    }
    return SavedIncomingFile(
      name = file.name,
      location = "app-private:${file.absolutePath}",
    )
  }

  private fun uniqueFile(directory: File, name: String): File {
    val cleanName = sanitizeRemoteFileName(name).ifBlank { "remote-file.bin" }
    val dotIndex = cleanName.lastIndexOf('.')
    val base = if (dotIndex > 0) cleanName.substring(0, dotIndex) else cleanName
    val ext = if (dotIndex > 0) cleanName.substring(dotIndex) else ""
    var candidate = File(directory, cleanName)
    var index = 1
    while (candidate.exists()) {
      candidate = File(directory, "$base-$index$ext")
      index += 1
    }
    return candidate
  }

  private fun sanitizeRemoteFileName(name: String): String =
    name.trim()
      .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_")
      .take(120)
      .ifBlank { "remote-file.bin" }

  private fun formatBytes(size: Long): String {
    if (size < 1024L) {
      return "$size B"
    }
    val kb = size.toDouble() / 1024.0
    if (kb < 1024.0) {
      return String.format(Locale.US, "%.1f KB", kb)
    }
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
  }

  private fun showRemoteKeyboardDialog() {
    val existing = remoteKeyboardDialog
    if (existing?.isShowing == true) {
      return
    }
    remoteKeyboardModifierButtons.clear()
    val dialog = Dialog(this).apply {
      requestWindowFeature(Window.FEATURE_NO_TITLE)
      setCanceledOnTouchOutside(true)
      setOnDismissListener {
        remoteKeyboardDialog = null
        remoteKeyboardModifierButtons.clear()
      }
    }
    dialog.setContentView(createRemoteKeyboardDialogContent())
    remoteKeyboardDialog = dialog
    dialog.show()
    dialog.window?.apply {
      setGravity(Gravity.BOTTOM)
      setLayout(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
      )
      setBackgroundDrawableResource(android.R.color.transparent)
    }
  }

  private fun createRemoteKeyboardDialogContent(): View {
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(12), dp(8), dp(12), dp(12))
      background = GradientDrawable().apply {
        setColor(Color.parseColor("#101827"))
        cornerRadii = floatArrayOf(dpFloat(14), dpFloat(14), dpFloat(14), dpFloat(14), 0f, 0f, 0f, 0f)
      }
    }

    val keyboardRows = listOf(
      listOf(
        keyboardKey("Esc", "Escape", 42),
        keyboardKey("F1", "F1"),
        keyboardKey("F2", "F2"),
        keyboardKey("F3", "F3"),
        keyboardKey("F4", "F4"),
        keyboardKey("F5", "F5"),
        keyboardKey("F6", "F6"),
        keyboardKey("F7", "F7"),
        keyboardKey("F8", "F8"),
        keyboardKey("F9", "F9"),
        keyboardKey("F10", "F10"),
        keyboardKey("F11", "F11"),
        keyboardKey("F12", "F12"),
        keyboardKey("Prt", "PrintScreen", 42),
        keyboardKey("Scr", "ScrollLock", 42),
        keyboardKey("Pause", "Pause", 48),
      ),
      listOf(
        keyboardKey("`", "Backquote"),
        keyboardKey("1", "Digit1"),
        keyboardKey("2", "Digit2"),
        keyboardKey("3", "Digit3"),
        keyboardKey("4", "Digit4"),
        keyboardKey("5", "Digit5"),
        keyboardKey("6", "Digit6"),
        keyboardKey("7", "Digit7"),
        keyboardKey("8", "Digit8"),
        keyboardKey("9", "Digit9"),
        keyboardKey("0", "Digit0"),
        keyboardKey("-", "Minus"),
        keyboardKey("=", "Equal"),
        keyboardKey("Back", "Backspace", 62),
        keyboardKey("Ins", "Insert", 38),
        keyboardKey("Home", "Home", 46),
        keyboardKey("PgUp", "PageUp", 46),
      ),
      listOf(
        keyboardKey("Tab", "Tab", 50),
        keyboardKey("Q", "KeyQ"),
        keyboardKey("W", "KeyW"),
        keyboardKey("E", "KeyE"),
        keyboardKey("R", "KeyR"),
        keyboardKey("T", "KeyT"),
        keyboardKey("Y", "KeyY"),
        keyboardKey("U", "KeyU"),
        keyboardKey("I", "KeyI"),
        keyboardKey("O", "KeyO"),
        keyboardKey("P", "KeyP"),
        keyboardKey("[", "BracketLeft"),
        keyboardKey("]", "BracketRight"),
        keyboardKey("\\", "Backslash", 42),
        keyboardKey("Del", "Delete", 38),
        keyboardKey("End", "End", 46),
        keyboardKey("PgDn", "PageDown", 46),
      ),
      listOf(
        keyboardKey("Caps", "CapsLock", 56),
        keyboardKey("A", "KeyA"),
        keyboardKey("S", "KeyS"),
        keyboardKey("D", "KeyD"),
        keyboardKey("F", "KeyF"),
        keyboardKey("G", "KeyG"),
        keyboardKey("H", "KeyH"),
        keyboardKey("J", "KeyJ"),
        keyboardKey("K", "KeyK"),
        keyboardKey("L", "KeyL"),
        keyboardKey(";", "Semicolon"),
        keyboardKey("'", "Quote"),
        keyboardKey("Enter", "Enter", 70),
      ),
      listOf(
        keyboardKey("Shift", "ShiftLeft", 70),
        keyboardKey("Z", "KeyZ"),
        keyboardKey("X", "KeyX"),
        keyboardKey("C", "KeyC"),
        keyboardKey("V", "KeyV"),
        keyboardKey("B", "KeyB"),
        keyboardKey("N", "KeyN"),
        keyboardKey("M", "KeyM"),
        keyboardKey(",", "Comma"),
        keyboardKey(".", "Period"),
        keyboardKey("/", "Slash"),
        keyboardKey("Shift", "ShiftRight", 70),
        keyboardSpacer(42),
        keyboardKey("↑", "ArrowUp", 38),
        keyboardSpacer(42),
      ),
      listOf(
        keyboardKey("Ctrl", "ControlLeft", 48),
        keyboardKey("Alt", "AltLeft", 42),
        keyboardKey("Cmd", "MetaLeft", 48),
        keyboardKey("Space", "Space", 178),
        keyboardKey("Cmd", "MetaRight", 48),
        keyboardKey("Alt", "AltRight", 42),
        keyboardKey("Ctrl", "ControlRight", 48),
        keyboardSpacer(22),
        keyboardKey("←", "ArrowLeft", 38),
        keyboardKey("↓", "ArrowDown", 38),
        keyboardKey("→", "ArrowRight", 38),
      ),
    )
    val keyboardContent = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, 0, 0, 0)
    }
    keyboardRows.forEach { rowKeys ->
      addRemoteKeyboardRow(keyboardContent, rowKeys)
    }
    // 作者: long；电脑键盘保留主键区、功能键和导航键六排，右侧独立数字小键盘不放进手机弹框，避免横向宽度挤占远控画面。
    val horizontalScroll = HorizontalScrollView(this).apply {
      overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
      isHorizontalScrollBarEnabled = false
      addView(keyboardContent)
    }
    root.addView(
      horizontalScroll,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
    updateRemoteKeyboardModifierButtons()
    return root
  }

  private fun addRemoteKeyboardRow(parent: LinearLayout, keys: List<RemoteKeyboardKey>) {
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(0, dp(1), 0, dp(1))
    }
    keys.forEach { key ->
      if (key.isSpacer) {
        row.addView(
          View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(key.widthDp), dp(REMOTE_KEYBOARD_BUTTON_HEIGHT_DP))
          },
        )
      } else {
        row.addView(createRemoteKeyboardButton(key))
      }
    }
    parent.addView(row)
  }

  private fun createRemoteKeyboardButton(key: RemoteKeyboardKey): MaterialButton {
    val button = MaterialButton(this).apply {
      layoutParams = LinearLayout.LayoutParams(dp(key.widthDp), dp(REMOTE_KEYBOARD_BUTTON_HEIGHT_DP)).apply {
        marginStart = dp(2)
        marginEnd = dp(2)
      }
      minWidth = 0
      minHeight = 0
      minimumWidth = 0
      minimumHeight = 0
      insetTop = 0
      insetBottom = 0
      includeFontPadding = false
      maxLines = 1
      text = key.label
      textSize = when {
        key.label.length > 5 -> 8.5f
        key.label.length > 3 -> 9.5f
        else -> 11f
      }
      isAllCaps = false
      setPadding(dp(2), 0, dp(2), 0)
      cornerRadius = dp(6)
      strokeWidth = dp(1)
      setTextColor(Color.WHITE)
      strokeColor = ColorStateList.valueOf(Color.parseColor("#35506F"))
      backgroundTintList = ColorStateList.valueOf(Color.parseColor("#19263A"))
      setOnClickListener {
        handleRemoteKeyboardMappedKey(key)
      }
    }
    if (isRemoteKeyboardModifierKey(key.keyCode)) {
      remoteKeyboardModifierButtons[key.keyCode] = button
    }
    return button
  }

  private fun handleRemoteKeyboardMappedKey(key: RemoteKeyboardKey) {
    if (isRemoteKeyboardModifierKey(key.keyCode)) {
      if (remoteKeyboardActiveModifiers.contains(key.keyCode)) {
        remoteKeyboardActiveModifiers.remove(key.keyCode)
      } else {
        remoteKeyboardActiveModifiers.add(key.keyCode)
      }
      updateRemoteKeyboardModifierButtons()
      return
    }
    val modifiers = remoteKeyboardActiveModifiers.toList()
    // 作者: long；电脑键盘面板发送的是标准键位码，修饰键保持为面板状态，方便连续输入组合键或快捷键。
    sendRemoteKeyPress(key.keyCode, modifiers, key.label)
  }

  private fun updateRemoteKeyboardModifierButtons() {
    remoteKeyboardModifierButtons.forEach { (keyCode, button) ->
      val active = remoteKeyboardActiveModifiers.contains(keyCode)
      button.backgroundTintList = ColorStateList.valueOf(
        Color.parseColor(if (active) "#2B6DFF" else "#19263A"),
      )
      button.strokeColor = ColorStateList.valueOf(
        Color.parseColor(if (active) "#8BB5FF" else "#35506F"),
      )
    }
  }

  private fun isRemoteKeyboardModifierKey(keyCode: String): Boolean =
    keyCode == "ShiftLeft" ||
      keyCode == "ShiftRight" ||
      keyCode == "ControlLeft" ||
      keyCode == "ControlRight" ||
      keyCode == "AltLeft" ||
      keyCode == "AltRight" ||
      keyCode == "MetaLeft" ||
      keyCode == "MetaRight"

  private fun keyboardKey(label: String, keyCode: String, widthDp: Int = 34): RemoteKeyboardKey =
    RemoteKeyboardKey(label = label, keyCode = keyCode, widthDp = widthDp)

  private fun keyboardSpacer(widthDp: Int): RemoteKeyboardKey =
    RemoteKeyboardKey(label = "", keyCode = "", widthDp = widthDp, isSpacer = true)

  private fun sendRemoteShortcut(keyCode: String, modifiers: List<String>, label: String): Boolean {
    // 作者: long；Mac 常用组合键用 MetaLeft 表示 Command，远端执行器会按 modifier down -> 主键 -> modifier up 的顺序落地。
    return sendRemoteKeyPress(keyCode, modifiers, label)
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
    flushPendingRemoteMouseMoveBeforeDiscreteInput()
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

  private fun decodeFrameBitmap(
    contentB64: String,
    announcedWidth: Int,
    announcedHeight: Int,
    sourceRect: NormalizedRect,
  ): DecodedFrame {
    return try {
      val bytes = try {
        Base64.decode(contentB64, Base64.NO_WRAP)
      } catch (_: IllegalArgumentException) {
        Base64.decode(contentB64, Base64.DEFAULT)
      }
      if (bytes.size > MAX_FRAME_BYTES) {
        return DecodedFrame(error = "屏幕帧过大，已拒绝渲染")
      }

      val bitmapConfig = preferredLegacyFrameBitmapConfig(announcedWidth, announcedHeight, sourceRect)
      val reusableBitmap = takeReusableLegacyBitmap(announcedWidth, announcedHeight, bitmapConfig)
      val bitmap = decodeLegacyBitmapBytes(bytes, bitmapConfig, reusableBitmap)
        ?: return DecodedFrame(error = "屏幕帧解码失败")
      if (bitmap.width <= 0 || bitmap.height <= 0) {
        return DecodedFrame(error = "屏幕帧不是有效图片")
      }
      if (bitmap.width > MAX_FRAME_DIMENSION || bitmap.height > MAX_FRAME_DIMENSION) {
        return DecodedFrame(error = "屏幕帧尺寸超限 ${bitmap.width}x${bitmap.height}")
      }
      if (bitmap.width != announcedWidth || bitmap.height != announcedHeight) {
        Log.w(
          RTC_TAG,
          "legacy_frame_size_mismatch announced=${formatFrameSize(announcedWidth, announcedHeight)} decoded=${formatFrameSize(bitmap.width, bitmap.height)}",
        )
      }
      // 作者: long；relay 已经校验了帧尺寸边界，Android 这里单次解码后再复核真实 bitmap 尺寸，并优先复用已退场帧内存，减少全屏移动和局部高清阶段的 Bitmap 分配/GC 抖动。
      DecodedFrame(bitmap = bitmap, width = bitmap.width, height = bitmap.height)
    } catch (error: IllegalArgumentException) {
      DecodedFrame(error = "屏幕帧 Base64 非法：${error.message ?: "unknown"}")
    } catch (_: OutOfMemoryError) {
      DecodedFrame(error = "屏幕帧过大，内存不足")
    }
  }

  private fun preferredLegacyFrameBitmapConfig(
    announcedWidth: Int,
    announcedHeight: Int,
    sourceRect: NormalizedRect,
  ): Bitmap.Config =
    if (!isMaterializedRemoteFrameSourceRect(sourceRect) && announcedWidth <= 700 && announcedHeight <= 460) {
      // 作者: long；整屏兜底帧承担的是定位和鼠标反馈，640 宽全屏基础档也要走轻量解码；真正用于读文字的局部裁剪帧仍保留 ARGB_8888。
      Bitmap.Config.RGB_565
    } else {
      // 作者: long；局部裁剪帧会被用户放大查看文字和输入框，哪怕尺寸不大也保留 ARGB_8888，避免 RGB_565 的色阶损失继续放大成发虚。
      Bitmap.Config.ARGB_8888
    }

  private fun shouldUseHighQualityLegacyScaling(sourceRect: NormalizedRect): Boolean {
    if (!remoteFullscreenActive) {
      return true
    }
    if (::remoteScaleGestureDetector.isInitialized && remoteScaleGestureDetector.isInProgress) {
      // 作者: long；双指缩放进行中优先保证本地矩阵动画跟手，停手后的局部高清帧再打开滤波补文字边缘，避免滤波绘制抢走全屏 pinch 帧预算。
      return false
    }
    return isMaterializedRemoteFrameSourceRect(sourceRect) ||
      remoteViewportScale > REMOTE_VIEWPORT_DETAIL_RENDER_MIN_SCALE
  }

  private fun decodeLegacyBitmapBytes(
    bytes: ByteArray,
    bitmapConfig: Bitmap.Config,
    reusableBitmap: Bitmap?,
  ): Bitmap? {
    val decodeOptions = BitmapFactory.Options().apply {
      inPreferredConfig = bitmapConfig
      inDither = true
      inMutable = true
      if (reusableBitmap != null) {
        inBitmap = reusableBitmap
      }
    }
    return try {
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions).also { bitmap ->
        if (bitmap == null) {
          reusableBitmap?.takeIf { !it.isRecycled }?.recycle()
        }
      }
    } catch (error: IllegalArgumentException) {
      if (reusableBitmap == null) {
        throw error
      }
      reusableBitmap.takeIf { !it.isRecycled }?.recycle()
      Log.w(RTC_TAG, "legacy_bitmap_reuse_failed retry_without_inBitmap reason=${error.message ?: "unknown"}")
      BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
          inPreferredConfig = bitmapConfig
          inDither = true
          inMutable = true
        },
      )
    }
  }

  private fun takeReusableLegacyBitmap(
    width: Int,
    height: Int,
    bitmapConfig: Bitmap.Config,
  ): Bitmap? {
    val requiredBytes = width.toLong() * height.toLong() * legacyBitmapBytesPerPixel(bitmapConfig)
    if (requiredBytes <= 0L || requiredBytes > Int.MAX_VALUE) {
      return null
    }
    synchronized(legacyReusableBitmaps) {
      val index = legacyReusableBitmaps.indexOfFirst { bitmap ->
        !bitmap.isRecycled &&
          bitmap.isMutable &&
          bitmap.config == bitmapConfig &&
          bitmap.allocationByteCount >= requiredBytes
      }
      if (index < 0) {
        return null
      }
      return legacyReusableBitmaps.removeAt(index)
    }
  }

  private fun storeReusableLegacyBitmap(bitmap: Bitmap?) {
    if (bitmap == null || bitmap.isRecycled || !bitmap.isMutable) {
      return
    }
    synchronized(legacyReusableBitmaps) {
      legacyReusableBitmaps.removeAll { it === bitmap || it.isRecycled }
      if (legacyReusableBitmaps.size >= LEGACY_BITMAP_REUSE_POOL_LIMIT) {
        bitmap.recycle()
        return
      }
      legacyReusableBitmaps.add(bitmap)
    }
  }

  private fun recycleLegacyReusableBitmapPool() {
    synchronized(legacyReusableBitmaps) {
      legacyReusableBitmaps.forEach { bitmap ->
        bitmap.takeIf { !it.isRecycled }?.recycle()
      }
      legacyReusableBitmaps.clear()
    }
  }

  private fun legacyBitmapBytesPerPixel(bitmapConfig: Bitmap.Config): Long =
    if (bitmapConfig == Bitmap.Config.RGB_565) 2L else 4L

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
    resetRemoteViewportAspect()
    resetLegacyFrameStats()
    clearLegacyFrameBitmap()
    binding.frameMetaText.text = "当前画面：-"
    setStatus("远端画面解码失败")
    if (suppressedCount > 0) {
      appendLog("legacy 解码失败日志已节流，抑制 $suppressedCount 条重复错误")
    }
    appendLog(message)
  }

  private fun showFrameDecodeFailureOnUiThread(message: String) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      showFrameDecodeFailure(message)
      return
    }
    runOnUiThread {
      if (!isActivityAlive) {
        return@runOnUiThread
      }
      showFrameDecodeFailure(message)
    }
  }

  private fun setLegacyFrameBitmap(bitmap: Bitmap) {
    val oldCurrent = legacyDisplayedBitmap
    binding.remoteFrameView.setFrameBitmap(bitmap)
    legacyDisplayedBitmap = bitmap
    legacyPreviousDisplayedBitmap
      ?.takeIf { previous -> previous !== oldCurrent && previous !== bitmap && !previous.isRecycled }
      ?.let { previous -> storeReusableLegacyBitmap(previous) }
    // 作者: long；自定义帧 View 会在下一次 vsync 绘制新图，保留一帧缓冲；更早退场的帧放入复用池，减少高频 JPEG 兜底流的 Bitmap 分配。
    legacyPreviousDisplayedBitmap = oldCurrent?.takeIf { previous -> previous !== bitmap && !previous.isRecycled }
  }

  private fun clearLegacyFrameBitmap() {
    binding.remoteFrameView.clearFrameBitmap()
    val current = legacyDisplayedBitmap
    val previous = legacyPreviousDisplayedBitmap
    legacyDisplayedBitmap = null
    legacyPreviousDisplayedBitmap = null
    current?.takeIf { !it.isRecycled }?.recycle()
    previous?.takeIf { it !== current && !it.isRecycled }?.recycle()
    recycleLegacyReusableBitmapPool()
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
    resetRemoteViewportAspect()
    resetLegacyFrameStats()
    resetLegacyFailureThrottleState()
    resetRemoteInputResultStats()
    resetLiveE2EProofReportState()
    incomingFileTransfers.clear()
    frameGeneration += 1
    updateSessionText()
    binding.transportText.text = "传输方式：-"
    binding.peerText.text = "会话链路：-"
    binding.ackText.text = "输入回执：-"
    binding.frameMetaText.text = "当前画面：-"
    binding.remoteTransferStatusText.text = "剪贴板/文件：等待会话"
    updateLiveMetricsPanel()
    resetRemoteViewportTransform(logReason = false)
    exitRemoteViewportFullscreen(reason = "session_reset")
    activateRemoteVideoRenderer(useTexture = false)
    binding.remoteFrameView.isVisible = false
    if (clearFrame) {
      clearLegacyFrameBitmap()
      clearRemoteVideoRendererImage()
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
    binding.remoteClipboardSendButton.isEnabled = hasSession
    binding.remoteFileSendButton.isEnabled = hasSession
    binding.sessionEndButton.isEnabled = hasSession || pendingSessionRequest || isRecoveringSession

    binding.targetDeviceInput.isEnabled = !hasSession && !pendingSessionRequest && !isRecoveringSession
    binding.remoteFrameCard.isVisible = currentPage == MainPage.SESSION
    if (binding.myDevicesPage.isVisible) {
      renderDeviceList()
    }
  }

  private fun setStatus(statusLine: String) {
    binding.statusText.text = "当前状态：$statusLine"
    binding.topConnectionStatusText.text = "连接状态：${mapTopConnectionStatus(statusLine)}"
    binding.remoteFrameCard.isVisible = currentPage == MainPage.SESSION
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
    binding.remoteAssistPage.isVisible = page == MainPage.SESSION
    binding.profilePage.isVisible = page == MainPage.SETTINGS
    binding.cloudPage.isVisible = false
    binding.bottomNavigationBar.isVisible = page != MainPage.SESSION
    binding.remoteFrameCard.isVisible = page == MainPage.SESSION
    updateDebugPanelVisibility()

    styleNavButton(binding.navMyDevicesButton, page == MainPage.MY_DEVICES)
    styleNavButton(binding.navProfileButton, page == MainPage.SETTINGS)
    binding.navCloudButton.isVisible = false
    binding.navAssistButton.isVisible = false

    if (page == MainPage.MY_DEVICES && autoRefreshDevices) {
      refreshDevicesList()
    }
  }

  private fun updateDebugPanelVisibility() {
    // 作者: long；调试内容只在设置页且用户显式开启时出现，设备列表保持纯净的产品视图。
    binding.debugPanel.isVisible = currentPage == MainPage.SETTINGS && binding.debugModeSwitch.isChecked
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
        isOnline -> "远程"
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
    // 作者: long；设备列表只负责选目标，真正的画面和输入进入独立会话页，避免主列表被调试状态挤占。
    switchPage(MainPage.SESSION, autoRefreshDevices = false)
    handlePrimaryAction()
  }

  private fun handleEndSessionAction() {
    val currentSessionId = resolveActiveSessionId()
    sessionEndRequestedByUser = true
    clearSessionRecoveryIntent("manual_end_session")
    if (currentSessionId.isNullOrBlank()) {
      appendLog("当前没有 session，不能结束")
      pendingSessionRequest = false
      pendingSessionRequestId = null
      pendingSessionRequestIsRecovery = false
      isRecoveringSession = false
      updateSessionButtonState()
      switchPage(MainPage.MY_DEVICES)
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
      switchPage(MainPage.SESSION, autoRefreshDevices = false)
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

  private fun JSONObject?.optNormalizedNumber(name: String): Double? {
    val value = this?.optDouble(name, Double.NaN) ?: return null
    return value.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0.0 && it <= 1.0 }
  }

  private fun parseFrameSourceRect(payload: JSONObject?): NormalizedRect {
    val x = payload.optNormalizedNumber("source_rect_x") ?: return NormalizedRect(0.0, 0.0, 1.0, 1.0)
    val y = payload.optNormalizedNumber("source_rect_y") ?: return NormalizedRect(0.0, 0.0, 1.0, 1.0)
    val width = payload.optNormalizedNumber("source_rect_width") ?: return NormalizedRect(0.0, 0.0, 1.0, 1.0)
    val height = payload.optNormalizedNumber("source_rect_height") ?: return NormalizedRect(0.0, 0.0, 1.0, 1.0)
    if (width <= 0.0 || height <= 0.0 || x + width > 1.000001 || y + height > 1.000001) {
      return NormalizedRect(0.0, 0.0, 1.0, 1.0)
    }
    return NormalizedRect(
      x = x.coerceIn(0.0, 1.0),
      y = y.coerceIn(0.0, 1.0),
      width = width.coerceIn(0.0, 1.0 - x),
      height = height.coerceIn(0.0, 1.0 - y),
    )
  }

  private fun isMaterializedRemoteFrameSourceRect(rect: NormalizedRect = remoteFrameSourceRect): Boolean =
    abs(rect.x) > 0.000001 ||
      abs(rect.y) > 0.000001 ||
      abs(rect.width - 1.0) > 0.000001 ||
      abs(rect.height - 1.0) > 0.000001

  private fun formatSourceRectForLog(rect: NormalizedRect): String {
    if (!isMaterializedRemoteFrameSourceRect(rect)) {
      return "full"
    }
    return "${formatCoordinate(rect.x)},${formatCoordinate(rect.y)},${formatCoordinate(rect.width)},${formatCoordinate(rect.height)}"
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

  private fun renderMoveAckThrottled(echoType: String) {
    val now = SystemClock.elapsedRealtime()
    if (now - remoteLastMoveUiUpdateAtMs < REMOTE_INPUT_MOVE_UI_UPDATE_INTERVAL_MS) {
      remoteSuppressedMoveAckCount += 1
      return
    }
    remoteLastMoveUiUpdateAtMs = now
    val suppressedSuffix = if (remoteSuppressedMoveAckCount > 0) {
      "，已合并 ${remoteSuppressedMoveAckCount} 条移动确认"
    } else {
      ""
    }
    remoteSuppressedMoveAckCount = 0
    // 作者: long；鼠标移动会在真机上形成高频回执，日志只保留节流汇总，避免输入越顺滑反而越挤压视频渲染主线程。
    binding.ackText.text = "输入回执：$echoType 已确认$suppressedSuffix"
    appendLog("收到 $echoType 已确认$suppressedSuffix")
  }

  private fun logIncomingSignal(json: JSONObject, payload: JSONObject?, size: Int) {
    if (json.optString("type") == "screen.frame.push") {
      logIncomingFrameSignalThrottled(json, size)
      return
    }
    if (!isMoveSignalNoise(json, payload)) {
      Log.i(RTC_TAG, "recv_signal ${summarizeEnvelope(json, size)}")
      return
    }
    val now = SystemClock.elapsedRealtime()
    if (now - remoteLastMoveSignalLogAtMs < REMOTE_MOVE_SIGNAL_LOG_INTERVAL_MS) {
      remoteSuppressedMoveSignalLogCount += 1
      return
    }
    val suppressed = remoteSuppressedMoveSignalLogCount
    remoteSuppressedMoveSignalLogCount = 0
    remoteLastMoveSignalLogAtMs = now
    // 作者: long；鼠标移动链路会产生高频 ack/result，逐条写 logcat 会抢占真机解码和合成预算；这里保留节流样本和合并计数用于验收。
    Log.i(
      RTC_TAG,
      "recv_signal ${summarizeEnvelope(json, size)} suppressed_move_signals=$suppressed",
    )
  }

  private fun logIncomingFrameSignalThrottled(json: JSONObject, size: Int) {
    val now = SystemClock.elapsedRealtime()
    if (now - remoteLastFrameSignalLogAtMs < REMOTE_FRAME_SIGNAL_LOG_INTERVAL_MS) {
      remoteSuppressedFrameSignalLogCount += 1
      return
    }
    val suppressed = remoteSuppressedFrameSignalLogCount
    remoteSuppressedFrameSignalLogCount = 0
    remoteLastFrameSignalLogAtMs = now
    // 作者: long；JPEG 帧流的每一帧都是大消息，逐帧写入 RemoteDeskRtc 会把验证日志变成性能瓶颈；这里保留定期样本和合并数量。
    Log.i(
      RTC_TAG,
      "recv_signal ${summarizeEnvelope(json, size)} suppressed_frame_signals=$suppressed",
    )
  }

  private fun isMoveSignalNoise(json: JSONObject, payload: JSONObject?): Boolean {
    return when (json.optString("type")) {
      "input.ack" -> payload.optNonBlank("echo_type") == "input.mouse.move"
      "input.result.push" -> payload.optNonBlank("input_type") == "input.mouse.move"
      else -> false
    }
  }

  private fun renderMoveResultThrottled(
    resultLabel: String,
    summary: String,
    statusCode: String,
    executor: String,
  ) {
    val now = SystemClock.elapsedRealtime()
    if (now - remoteLastMoveUiUpdateAtMs < REMOTE_INPUT_MOVE_UI_UPDATE_INTERVAL_MS) {
      remoteSuppressedMoveResultCount += 1
      return
    }
    remoteLastMoveUiUpdateAtMs = now
    val suppressedSuffix = if (remoteSuppressedMoveResultCount > 0) {
      "，已合并 ${remoteSuppressedMoveResultCount} 条移动结果"
    } else {
      ""
    }
    remoteSuppressedMoveResultCount = 0
    binding.ackText.text = "输入回执：$resultLabel input.mouse.move [$statusCode/$executor]$suppressedSuffix"
    appendLog("收到目标端输入执行结果：$resultLabel $summary [$statusCode/$executor]$suppressedSuffix")
  }

  private fun initializeRemoteViewportControls() {
    remoteTouchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
    // 作者: long；SurfaceView 在部分真机上会绕过普通 View 绘制顺序，远控控制层需要显式抬高，避免全屏和倍率按钮被视频层吞掉。
    binding.remoteTouchLayer.bringToFront()
    binding.remoteCursorOverlay.bringToFront()
    binding.remoteZoomResetButton.apply {
      visibility = View.VISIBLE
      elevation = dpFloat(8)
      bringToFront()
    }
    binding.remoteFullscreenButton.apply {
      visibility = View.VISIBLE
      elevation = dpFloat(8)
      bringToFront()
    }
    binding.remoteKeyboardPanelButton.apply {
      visibility = View.VISIBLE
      elevation = dpFloat(8)
      bringToFront()
    }
    binding.remoteViewportContainer.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
      if (!remoteFullscreenActive) {
        return@addOnLayoutChangeListener
      }
      val sizeChanged = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
      if (!sizeChanged) {
        return@addOnLayoutChangeListener
      }
      // 作者: long；横屏沉浸切换后系统会分多次给出真实可用尺寸，尺寸一变就复算完整适配框，避免全屏短暂按满屏拉伸后看起来像被裁剪。
      scheduleRemoteFullscreenAspectReflow()
    }
	    remoteScaleGestureDetector = ScaleGestureDetector(
	      this,
	      object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
          if (renderedFrameWidth <= 0 || renderedFrameHeight <= 0) {
            return false
          }
          if (remoteDebugPinchDispatchActive) {
            Log.i(
              RTC_TAG,
              "remote_debug_pinch_scale_begin focus=${String.format(Locale.US, "%.1f", detector.focusX)},${String.format(Locale.US, "%.1f", detector.focusY)} span=${String.format(Locale.US, "%.1f", detector.currentSpan)}",
            )
          }
          remoteSuppressTap = true
          remoteScrollGestureActive = false
          remoteAccumulatedPinchScaleFactor = 1f
          remotePinchFocusX = detector.focusX
          remotePinchFocusY = detector.focusY
	          remotePinchFocusInitialized = true
          remoteLastPinchEndHintAtMs = 0L
          remoteLastPinchEndHintKey = ""
	          prepareRemoteViewportForInteractiveTransform()
	          beginLegacyPinchFrameGate()
	          binding.remoteFrameView.setHighQualityScaling(false)
	          sendRemoteViewportInteractionHint("start", "pinch", force = true)
	          return true
	        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
          remoteAccumulatedPinchScaleFactor *= detector.scaleFactor
          if (abs(remoteAccumulatedPinchScaleFactor - 1f) < REMOTE_PINCH_SCALE_EPSILON) {
            return false
	          }
	          // 作者: long；真机两指距离会有细小抖动，但全屏阅读时过度压缩增量会让用户需要反复捏合才获得清晰局部；这里保留轻量抗抖，同时让倍率更贴近手指速度。
	          val rawScaleFactor = remoteAccumulatedPinchScaleFactor
	          remoteAccumulatedPinchScaleFactor = 1f
	          if (applyRemoteViewportSourceRectBackedPinch(rawScaleFactor, detector.focusX, detector.focusY, source = "detector")) {
	            return true
	          }
	          val smoothedScaleFactor = 1f + ((rawScaleFactor - 1f) * REMOTE_PINCH_SCALE_FACTOR_SMOOTHING)
          val oldScale = remoteViewportScale
          val nextScale = (oldScale * smoothedScaleFactor)
            .coerceIn(REMOTE_VIEWPORT_MIN_SCALE, REMOTE_VIEWPORT_MAX_SCALE)
          if (abs(nextScale - oldScale) < 0.0005f) {
            return false
          }
          remotePinchZoomActive = true
          val ratio = nextScale / oldScale
          val focusX = smoothRemotePinchFocusX(detector.focusX)
          val focusY = smoothRemotePinchFocusY(detector.focusY)
          val newOffsetX = focusX - ((focusX - remoteViewportOffsetX) * ratio)
          val newOffsetY = focusY - ((focusY - remoteViewportOffsetY) * ratio)
          remoteViewportScale = nextScale
          remoteViewportOffsetX = clampRemoteViewportOffsetX(newOffsetX, nextScale)
          remoteViewportOffsetY = clampRemoteViewportOffsetY(newOffsetY, nextScale)
	          // 作者: long；双指连续捏合期间只更新合成层 transform，避免频繁改 Surface 尺寸让全屏缩放一顿一顿；停手后再提交更清晰的真实渲染面。
	          scheduleRemoteViewportTransformApply(commitRenderScale = false)
          sendRemoteViewportInteractionHint("update", "pinch")
          val now = SystemClock.elapsedRealtime()
          if (remoteDebugPinchDispatchActive || now - remoteLastPinchScaleLogAtMs >= 250L) {
            remoteLastPinchScaleLogAtMs = now
            Log.i(RTC_TAG, "remote_viewport_pinch_scale scale=${String.format(Locale.US, "%.3f", nextScale)}")
          }
          return true
        }

		        override fun onScaleEnd(detector: ScaleGestureDetector) {
              if (remoteDebugPinchDispatchActive) {
                Log.i(
                  RTC_TAG,
                  "remote_debug_pinch_scale_end scale=${String.format(Locale.US, "%.3f", remoteViewportScale)} span=${String.format(Locale.US, "%.1f", detector.currentSpan)}",
                )
              }
	              if (abs(remoteAccumulatedPinchScaleFactor - 1f) >= REMOTE_PINCH_END_SNAP_EPSILON) {
	                val oldScale = remoteViewportScale
	                val appliedSourceRectPinch = applyRemoteViewportSourceRectBackedPinch(
	                  remoteAccumulatedPinchScaleFactor,
	                  detector.focusX,
	                  detector.focusY,
	                  source = "detector_end",
	                )
	                if (!appliedSourceRectPinch) {
	                  val nextScale = (oldScale * remoteAccumulatedPinchScaleFactor)
	                    .coerceIn(REMOTE_VIEWPORT_MIN_SCALE, REMOTE_VIEWPORT_MAX_SCALE)
	                  if (abs(nextScale - oldScale) >= 0.0005f) {
	                    val ratio = nextScale / oldScale
	                    val focusX = smoothRemotePinchFocusX(detector.focusX)
	                    val focusY = smoothRemotePinchFocusY(detector.focusY)
	                    val newOffsetX = focusX - ((focusX - remoteViewportOffsetX) * ratio)
	                    val newOffsetY = focusY - ((focusY - remoteViewportOffsetY) * ratio)
	                    remoteViewportScale = nextScale
	                    remoteViewportOffsetX = clampRemoteViewportOffsetX(newOffsetX, nextScale)
	                    remoteViewportOffsetY = clampRemoteViewportOffsetY(newOffsetY, nextScale)
	                    scheduleRemoteViewportTransformApply(commitRenderScale = false)
	                  }
	                }
	              }
              remoteAccumulatedPinchScaleFactor = 1f
			          endLegacyPinchFrameGate(
		            reason = "scale_end",
		            keepFullFrameFrozen = remoteViewportScale >= REMOTE_VIEWPORT_DETAIL_RENDER_MIN_SCALE,
		          )
	          resetRemotePinchFocus()
	          binding.remoteFrameView.setHighQualityScaling(shouldUseHighQualityLegacyScaling(remoteFrameSourceRect))
          if (remoteViewportScale >= REMOTE_VIEWPORT_DETAIL_RENDER_MIN_SCALE) {
            scheduleRemoteViewportRenderScaleCommit(remoteViewportRenderCommitDelayMs())
          } else {
            // 作者: long；轻微捏合更像快速定位，停手后不重建高清承载面，避免用户刚放手就被 Surface 重新布局卡一下。
            scheduleRemoteViewportTransformApply(commitRenderScale = false)
            scheduleRemoteViewportHardwareLayerRelease()
          }
          sendRemoteViewportInteractionHint("end", "pinch", force = true)
        }
      },
    )

    binding.remoteZoomResetButton.setOnClickListener {
      resetRemoteViewportTransform(logReason = true)
      sendRemoteViewportInteractionHint("end", "pinch", force = true)
    }
    binding.remoteFullscreenButton.setOnClickListener {
      toggleRemoteViewportFullscreen()
    }
    updateRemoteFullscreenButtonText()
  }

  private fun applyRemoteViewportTransform(commitRenderScale: Boolean = false) {
    val requestedScale = remoteViewportScale
    val tx = remoteViewportOffsetX
    val ty = remoteViewportOffsetY
    val scale = requestedScale
    if (commitRenderScale) {
      remoteViewportRenderScale = coerceRemoteViewportRenderScale(scale)
      remoteViewportRenderBoundsDirty = true
      remoteLastRenderScaleCommitAtMs = SystemClock.elapsedRealtime()
    }
    val renderScale = remoteViewportRenderScale.coerceIn(
      REMOTE_VIEWPORT_MIN_RENDER_SCALE,
      REMOTE_VIEWPORT_MAX_RENDER_SCALE,
    )
    if (remoteViewportRenderBoundsDirty) {
      remoteViewportRenderBoundsDirty = !updateRemoteViewportContentRenderBounds(renderScale)
    }
    val visualScale = (scale / renderScale).coerceIn(
      REMOTE_VIEWPORT_MIN_SCALE / REMOTE_VIEWPORT_MAX_RENDER_SCALE,
      REMOTE_VIEWPORT_MAX_SCALE / REMOTE_VIEWPORT_MIN_RENDER_SCALE,
    )
    val sourceRectPreviewScale = remoteSourceRectPreviewScale.coerceIn(0.18f, 1f)
    binding.remoteViewportContent.apply {
      pivotX = 0f
      pivotY = 0f
      scaleX = visualScale * sourceRectPreviewScale
      scaleY = visualScale * sourceRectPreviewScale
      translationX = tx + remoteSourceRectPreviewOffsetX
      translationY = ty + remoteSourceRectPreviewOffsetY
    }
    val nextLabel = if (requestedScale <= REMOTE_VIEWPORT_MIN_SCALE + 0.005f) {
      "1x"
    } else {
      String.format(Locale.US, "%.1fx", requestedScale)
    }
    if (remoteZoomResetButtonLabel != nextLabel) {
      val now = SystemClock.elapsedRealtime()
      val canUpdateLabel = !remoteScaleGestureDetector.isInProgress ||
        now - remoteLastZoomResetLabelUpdateAtMs >= REMOTE_ZOOM_LABEL_UPDATE_INTERVAL_MS
      if (canUpdateLabel) {
        remoteLastZoomResetLabelUpdateAtMs = now
        remoteZoomResetButtonLabel = nextLabel
        // 作者: long；倍率提示是辅助信息，不参与远控手势本身；全屏捏合时节流文本更新，避免小按钮频繁测量拖慢内容层矩阵动画。
        binding.remoteZoomResetButton.text = nextLabel
      }
    }
  }

  private fun prepareRemoteViewportForInteractiveTransform() {
    cancelRemoteViewportRenderScaleCommit()
    setRemoteViewportHardwareLayerActive(true)
    val interactionRenderScale = remoteViewportInteractionRenderScale()
    if (remoteViewportRenderScale <= interactionRenderScale) {
      return
    }
    // 作者: long；二次捏合时如果继续拖着上一轮超大 Surface 做 transform，全屏合成成本会明显上升；手势中保留轻量但不低清的承载面，停手后再补高清重绘。
    remoteViewportRenderScale = interactionRenderScale
    remoteViewportRenderBoundsDirty = true
    scheduleRemoteViewportTransformApply(commitRenderScale = false)
  }

  private fun scheduleRemoteViewportRenderScaleCommit(delayMs: Long) {
    cancelRemoteViewportRenderScaleCommit()
    remoteViewportRenderCommitRunnable = Runnable {
      remoteViewportRenderCommitRunnable = null
      if (!isActivityAlive) {
        return@Runnable
      }
      // 作者: long；高清承载面提交会触发 Surface 重新布局，延迟到手势稳定后执行，避免用户刚松手或继续微调时出现明显顿挫。
      scheduleRemoteViewportTransformApply(commitRenderScale = true)
      scheduleRemoteViewportHardwareLayerRelease()
    }.also { runnable ->
      remoteGestureHandler.postDelayed(runnable, delayMs.coerceAtLeast(1L))
    }
  }

  private fun remoteViewportRenderCommitDelayMs(): Long =
    if (remoteFullscreenActive) REMOTE_VIEWPORT_FULLSCREEN_RENDER_COMMIT_DELAY_MS else REMOTE_VIEWPORT_RENDER_COMMIT_DELAY_MS

  private fun cancelRemoteViewportRenderScaleCommit() {
    remoteViewportRenderCommitRunnable?.let { remoteGestureHandler.removeCallbacks(it) }
    remoteViewportRenderCommitRunnable = null
  }

  private fun setRemoteViewportHardwareLayerActive(active: Boolean) {
    remoteViewportHardwareLayerReleaseRunnable?.let { remoteGestureHandler.removeCallbacks(it) }
    remoteViewportHardwareLayerReleaseRunnable = null
    val targetLayerType = if (active) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
    if (binding.remoteViewportContent.layerType == targetLayerType) {
      return
    }
    // 作者: long；全屏双指缩放时每一帧只改合成层矩阵，临时硬件层能减少 View 层级重绘；停手后释放，避免后续 JPEG 帧更新一直背着大纹理成本。
    binding.remoteViewportContent.setLayerType(targetLayerType, null)
  }

  private fun scheduleRemoteViewportHardwareLayerRelease() {
    remoteViewportHardwareLayerReleaseRunnable?.let { remoteGestureHandler.removeCallbacks(it) }
    remoteViewportHardwareLayerReleaseRunnable = Runnable {
      remoteViewportHardwareLayerReleaseRunnable = null
      if (!isActivityAlive || remoteScaleGestureDetector.isInProgress) {
        return@Runnable
      }
      setRemoteViewportHardwareLayerActive(false)
    }.also { runnable ->
      remoteGestureHandler.postDelayed(runnable, REMOTE_VIEWPORT_HARDWARE_LAYER_RELEASE_DELAY_MS)
    }
  }

  private fun updateRemoteViewportContentRenderBounds(renderScale: Float): Boolean {
    val viewportWidth = binding.remoteViewportContainer.width
    val viewportHeight = binding.remoteViewportContainer.height
    if (viewportWidth <= 0 || viewportHeight <= 0) {
      return false
    }
    val boundedRenderScale = coerceRemoteViewportRenderScale(renderScale)
    val targetWidth = (viewportWidth.toFloat() * boundedRenderScale).roundToInt().coerceAtLeast(1)
    val targetHeight = (viewportHeight.toFloat() * boundedRenderScale).roundToInt().coerceAtLeast(1)
    val params = (binding.remoteViewportContent.layoutParams as? FrameLayout.LayoutParams)
      ?: FrameLayout.LayoutParams(targetWidth, targetHeight)
    val expectedGravity = Gravity.TOP or Gravity.START
    if (params.width == targetWidth && params.height == targetHeight && params.gravity == expectedGravity) {
      return true
    }
    // 作者: long；捏合结束后提交更大的 WebRTC 渲染面，让后续停留和局部平移尽量使用视频帧重绘结果，而不是一直放大同一张合成位图。
    params.width = targetWidth
    params.height = targetHeight
    params.gravity = expectedGravity
    binding.remoteViewportContent.layoutParams = params
    binding.remoteViewportContent.post {
      if (!isActivityAlive) {
        return@post
      }
      // 作者: long；缩放停手后内容层会变成更大的真实承载面，主动刷新视频子层，避免 Surface 继续沿用旧尺寸合成导致局部放大后发虚。
      binding.remoteVideoView.requestLayout()
      binding.remoteVideoView.invalidate()
      binding.remoteTextureVideoView.requestLayout()
      binding.remoteTextureVideoView.invalidate()
      binding.remoteFrameView.requestLayout()
      binding.remoteFrameView.invalidate()
    }
    return true
  }

  private fun scheduleRemoteViewportTransformApply(commitRenderScale: Boolean = false) {
    if (commitRenderScale) {
      remoteViewportTransformCommitRequested = true
    }
    if (remoteViewportTransformApplyScheduled) {
      return
    }
    remoteViewportTransformApplyScheduled = true
    binding.remoteViewportContainer.postOnAnimation {
      val shouldCommitRenderScale = remoteViewportTransformCommitRequested
      remoteViewportTransformApplyScheduled = false
      remoteViewportTransformCommitRequested = false
      if (!isActivityAlive) {
        return@postOnAnimation
      }
      applyRemoteViewportTransform(commitRenderScale = shouldCommitRenderScale)
    }
  }

  private fun coerceRemoteViewportRenderScale(requestedScale: Float): Float {
    val fallbackScale = requestedScale.coerceIn(REMOTE_VIEWPORT_MIN_RENDER_SCALE, REMOTE_VIEWPORT_MAX_RENDER_SCALE)
    val viewportWidth = binding.remoteViewportContainer.width.takeIf { it > 0 } ?: return fallbackScale
    val viewportHeight = binding.remoteViewportContainer.height.takeIf { it > 0 } ?: return fallbackScale
    val viewportPixels = viewportWidth.toFloat() * viewportHeight.toFloat()
    if (viewportPixels <= 0f) {
      return fallbackScale
    }
    val maxScaleByPixels = sqrt(REMOTE_VIEWPORT_MAX_RENDER_PIXELS.toFloat() / viewportPixels)
      .coerceAtLeast(REMOTE_VIEWPORT_MIN_SCALE)
    if (remoteFullscreenActive) {
      // 作者: long；全屏最大缩放时只改内容层矩阵和远端裁剪源，不再重建大 Surface/Texture 承载面，避免窗口提交和 JPEG 局部帧同时压垮系统合成。
      return requestedScale.coerceIn(
        REMOTE_VIEWPORT_MIN_SCALE,
        min(REMOTE_VIEWPORT_FULLSCREEN_MAX_RENDER_SCALE, maxScaleByPixels),
      )
    }
    // 作者: long；小窗放大可以适度提高承载面，避免普通页面阅读发虚；全屏路径只允许很小的高清补偿，避免横屏远控时反复重建大 Surface。
    return requestedScale.coerceIn(
      REMOTE_VIEWPORT_MIN_SCALE,
      min(REMOTE_VIEWPORT_MAX_RENDER_SCALE, maxScaleByPixels),
    )
  }

  private fun resetRemoteViewportTransform(logReason: Boolean) {
    cancelRemoteViewportRenderScaleCommit()
    clearLegacySourceRectFullFrameProtection(reason = "viewport_reset")
    setRemoteViewportHardwareLayerActive(false)
    remotePinchViewportSourceRect = null
    remotePinchViewportLargestSourceRect = null
    resetRemoteSourceRectPreviewTransform()
    remoteViewportScale = REMOTE_VIEWPORT_MIN_SCALE
    remoteViewportOffsetX = 0f
    remoteViewportOffsetY = 0f
    remoteViewportRenderScale = remoteViewportBaseRenderScale()
    remoteViewportRenderBoundsDirty = true
    remoteLastRenderScaleCommitAtMs = SystemClock.elapsedRealtime()
    remotePanMoved = false
    remoteSuppressTap = false
    applyRemoteViewportTransform(commitRenderScale = false)
    if (logReason) {
      appendLog("远端画面缩放已重置为 1x")
    }
  }

  private fun materializeRemoteFrameSourceRect(sourceRect: NormalizedRect): Boolean {
    val previousSourceRect = remoteFrameSourceRect
    val pinchStillInProgress =
      remoteManualPinchActive ||
        remotePinchZoomActive ||
        (::remoteScaleGestureDetector.isInitialized && remoteScaleGestureDetector.isInProgress)
    // 作者: long；并拢缩小时 Mac 会陆续回显中间 source_rect，目标 rect 跟着手势生命周期走，不能被旧帧物化覆盖；真正清理放在手势 reset。
    val alreadyMaterializedSource =
      isMaterializedRemoteFrameSourceRect(previousSourceRect) && isMaterializedRemoteFrameSourceRect(sourceRect)
    val alreadyNormalizedViewport =
      remoteViewportScale <= REMOTE_VIEWPORT_MIN_SCALE + 0.005f &&
        abs(remoteViewportOffsetX) < 0.5f &&
        abs(remoteViewportOffsetY) < 0.5f
    val needsViewportReset = !alreadyMaterializedSource || !alreadyNormalizedViewport
    if (needsViewportReset) {
      cancelRemoteViewportRenderScaleCommit()
      scheduleRemoteViewportHardwareLayerRelease()
      remoteViewportScale = REMOTE_VIEWPORT_MIN_SCALE
      remoteViewportOffsetX = 0f
      remoteViewportOffsetY = 0f
      remoteViewportRenderScale = remoteViewportBaseRenderScale()
      remoteViewportRenderBoundsDirty = true
    }
    if (!pinchStillInProgress) {
      remotePinchZoomActive = false
      resetRemotePinchFocus()
    }
    if (isMaterializedRemoteFrameSourceRect(sourceRect)) {
      val now = SystemClock.elapsedRealtime()
      if (needsViewportReset || now - remoteLastSourceRectMaterializedLogAtMs >= REMOTE_SOURCE_RECT_MATERIALIZED_LOG_INTERVAL_MS) {
        remoteLastSourceRectMaterializedLogAtMs = now
        Log.i(RTC_TAG, "remote_viewport_source_rect_materialized rect=${formatSourceRectForLog(sourceRect)} reset=$needsViewportReset")
      }
    }
    // 作者: long；最大缩放后鼠标拖动画面会连续收到局部 source_rect 帧，只有从整屏切到局部或本地仍有缩放残留时才重置内容层，避免反复重建全屏窗口导致卡顿/闪退。
    return needsViewportReset
  }

  private fun remoteViewportBaseRenderScale(): Float =
    if (remoteFullscreenActive) REMOTE_VIEWPORT_FULLSCREEN_BASE_RENDER_SCALE else REMOTE_VIEWPORT_MIN_SCALE

  private fun remoteViewportInteractionRenderScale(): Float =
    if (remoteFullscreenActive) REMOTE_VIEWPORT_FULLSCREEN_INTERACTION_RENDER_SCALE else REMOTE_VIEWPORT_INTERACTION_RENDER_SCALE

  private fun setRemoteVideoHardwareScalerEnabled(enabled: Boolean) {
    // 作者: long；手势缩放只改远控内容层，视频 Surface 保持硬件缩放，交给系统合成器处理连续缩放更稳。
    binding.remoteVideoView.setEnableHardwareScaler(enabled)
  }

  private fun updateRemoteViewportAspect(frameWidth: Int, frameHeight: Int) {
    if (frameWidth <= 0 || frameHeight <= 0) {
      return
    }
    if (remoteFullscreenActive) {
      updateRemoteFullscreenViewportAspect(frameWidth, frameHeight)
      return
    }
    val viewport = binding.remoteViewportContainer
    viewport.post {
      if (!isActivityAlive || remoteFullscreenActive) {
        return@post
      }
      val availableWidth = viewport.width.takeIf { it > 0 }
        ?: (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(240))
      val aspectHeight = (availableWidth.toFloat() * frameHeight.toFloat() / frameWidth.toFloat()).roundToInt()
      val boundedHeight = aspectHeight.coerceIn(
        dp(REMOTE_VIEWPORT_SMALL_MIN_HEIGHT_DP),
        dp(REMOTE_VIEWPORT_SMALL_MAX_HEIGHT_DP),
      )
      val params = viewport.layoutParams
      if (params.height != boundedHeight) {
        // 作者: long；小窗高度跟随远端屏幕比例，避免固定高度让用户误以为电脑画面被截掉，同时保留上下限防止页面被极端分辨率撑爆。
        params.height = boundedHeight
        viewport.layoutParams = params
        viewport.post {
          if (isActivityAlive && !remoteFullscreenActive) {
            resetRemoteViewportTransform(logReason = false)
          }
        }
      }
    }
  }

  private fun updateRemoteFullscreenViewportAspect(frameWidth: Int, frameHeight: Int) {
    if (frameWidth <= 0 || frameHeight <= 0) {
      return
    }
    val host = remoteFullscreenHost ?: return
    val viewport = binding.remoteViewportContainer
    host.post {
      if (!isActivityAlive || !remoteFullscreenActive) {
        return@post
      }
      val hostWidth = host.width
      val hostHeight = host.height
      if (hostWidth <= 0 || hostHeight <= 0) {
        return@post
      }
      val frameAspect = frameWidth.toFloat() / frameHeight.toFloat()
      val hostAspect = hostWidth.toFloat() / hostHeight.toFloat()
      val fitWidth: Int
      val fitHeight: Int
      if (hostAspect > frameAspect) {
        fitHeight = hostHeight
        fitWidth = (hostHeight.toFloat() * frameAspect).roundToInt().coerceAtLeast(1)
      } else {
        fitWidth = hostWidth
        fitHeight = (hostWidth.toFloat() / frameAspect).roundToInt().coerceAtLeast(1)
      }
      val params = (viewport.layoutParams as? FrameLayout.LayoutParams)
        ?: FrameLayout.LayoutParams(fitWidth, fitHeight)
      if (params.width != fitWidth || params.height != fitHeight || params.gravity != Gravity.CENTER) {
        // 作者: long；全屏也必须完整呈现远端桌面，容器按远端帧比例居中，宁可留黑边也不让系统把上下或左右裁掉。
        params.width = fitWidth
        params.height = fitHeight
        params.gravity = Gravity.CENTER
        viewport.layoutParams = params
        viewport.post {
          if (isActivityAlive && remoteFullscreenActive) {
            resetRemoteViewportTransform(logReason = false)
          }
        }
      }
    }
  }

  private fun scheduleRemoteFullscreenAspectReflow() {
    if (!remoteFullscreenActive) {
      return
    }
    val frameWidth = renderedFrameWidth
    val frameHeight = renderedFrameHeight
    if (frameWidth <= 0 || frameHeight <= 0) {
      return
    }
    val reflow = Runnable {
      if (!isActivityAlive || !remoteFullscreenActive) {
        return@Runnable
      }
      updateRemoteFullscreenViewportAspect(frameWidth, frameHeight)
      remoteViewportRenderBoundsDirty = true
      scheduleRemoteViewportTransformApply(commitRenderScale = false)
    }
    binding.remoteViewportContainer.post(reflow)
    // 作者: long；系统横屏/隐藏导航栏会在进入全屏后继续调整 Insets，补两次延迟复算能把最终可用区域稳定到完整桌面比例，避免上下或左右被误裁。
    remoteGestureHandler.postDelayed(reflow, 120L)
    remoteGestureHandler.postDelayed(reflow, 320L)
  }

  private fun resetRemoteViewportAspect() {
    if (remoteFullscreenActive) {
      return
    }
    val viewport = binding.remoteViewportContainer
    val params = viewport.layoutParams
    val defaultHeight = dp(REMOTE_VIEWPORT_SMALL_DEFAULT_HEIGHT_DP)
    if (params.height != defaultHeight) {
      params.height = defaultHeight
      viewport.layoutParams = params
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
    if (!remoteFullscreenActive) {
      enterRemoteViewportFullscreen()
    } else {
      exitRemoteViewportFullscreen(reason = "manual_toggle")
    }
  }

  private fun enterRemoteViewportFullscreen() {
    if (remoteFullscreenActive) {
      return
    }
    val viewport = binding.remoteViewportContainer
    val originParent = viewport.parent as? ViewGroup ?: return
    val originLayoutParams = viewport.layoutParams ?: return
    val contentRoot = findViewById<ViewGroup>(android.R.id.content) ?: return
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
      isClickable = true
      isFocusable = true
      addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        val sizeChanged = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
        if (sizeChanged) {
          scheduleRemoteFullscreenAspectReflow()
        }
      }
    }
    val host = remoteFullscreenHost ?: return
    contentRoot.addView(host)
    host.addView(
      viewport,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
        Gravity.CENTER,
      ),
    )

    remoteFullscreenActive = true
    activateRemoteVideoRenderer(useTexture = REMOTE_FULLSCREEN_USE_TEXTURE_RENDERER)
    remoteFullscreenPreviousOrientation = requestedOrientation
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    setRemoteVideoHardwareScalerEnabled(true)
    applyImmersiveMode(enabled = true)
    resetRemoteViewportTransform(logReason = false)
    applyImmersiveMode(enabled = true)
    updateRemoteFullscreenViewportAspect(renderedFrameWidth, renderedFrameHeight)
    scheduleRemoteFullscreenAspectReflow()
    updateRemoteFullscreenButtonText()
    sendRemoteViewportInteractionHint("start", "fullscreen", force = true)
    appendLog("远端画面已进入全屏")
  }

  private fun exitRemoteViewportFullscreen(reason: String) {
    if (!remoteFullscreenActive) {
      return
    }
    val viewport = binding.remoteViewportContainer
    val host = remoteFullscreenHost
    val originParent = remoteViewportOriginParent
    val originLayoutParams = remoteViewportOriginLayoutParams
    val originIndex = remoteViewportOriginIndex
    remoteFullscreenActive = false
    activateRemoteVideoRenderer(useTexture = false)
    remoteFullscreenHost = null
    remoteViewportOriginParent = null
    remoteViewportOriginLayoutParams = null
    remoteViewportOriginIndex = -1

    (viewport.parent as? ViewGroup)?.removeView(viewport)
    host?.removeView(viewport)
    (host?.parent as? ViewGroup)?.removeView(host)
    if (originParent != null && originLayoutParams != null) {
      if (originIndex in 0..originParent.childCount) {
        originParent.addView(viewport, originIndex, originLayoutParams)
      } else {
        originParent.addView(viewport, originLayoutParams)
      }
    }
    requestedOrientation = remoteFullscreenPreviousOrientation
    remoteFullscreenPreviousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    setRemoteVideoHardwareScalerEnabled(true)
    applyImmersiveMode(enabled = false)
    resetRemoteViewportTransform(logReason = false)
    updateRemoteFullscreenButtonText()
    sendRemoteViewportInteractionHint("end", "fullscreen", force = true)
    appendLog("远端画面已退出全屏（$reason）")
  }

  private fun updateRemoteFullscreenButtonText() {
    binding.remoteFullscreenButton.text = if (!remoteFullscreenActive) "全屏" else "退出全屏"
  }

  private fun applyImmersiveMode(enabled: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      window.setDecorFitsSystemWindows(!enabled)
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
    SESSION,
  }

  private data class NormalizedPoint(
    val x: Double,
    val y: Double,
  )

  private data class NormalizedRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
  )

  private data class RemoteKey(
    val keyCode: String,
    val modifiers: List<String> = emptyList(),
  )

  private data class RemoteKeyboardKey(
    val label: String,
    val keyCode: String,
    val widthDp: Int,
    val isSpacer: Boolean = false,
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

  private data class IncomingFileTransfer(
    val fileId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val totalChunks: Int,
    val sha256: String,
    val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
  )

  private data class SavedIncomingFile(
    val name: String,
    val location: String,
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

private val REMOTE_DESK_MTK_DIRECT_H264_CODEC_NAMES = listOf(
  "c2.android.avc.decoder",
  "OMX.google.avc.decoder",
  "OMX.google.h264.decoder",
)
private val REMOTE_DESK_MTK_PREFERRED_H264_COLOR_FORMATS = listOf(
  MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
  MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
  MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
)
private const val REMOTE_DESK_DECODER_LOG_LIMIT = 18
private const val REMOTE_DESK_DECODER_INIT_TIMEOUT_MS = 2500L
private const val REMOTE_DESK_DECODER_DECODE_TIMEOUT_MS = 700L
private const val REMOTE_DESK_DECODER_RELEASE_TIMEOUT_MS = 900L

@Volatile private var remoteDeskMtkH264CodecCandidatesLogged = false
private val remoteDeskBlockedDirectH264Codecs: MutableSet<String> = ConcurrentHashMap.newKeySet()

private fun codecSupportsAvc(codecInfo: MediaCodecInfo): Boolean =
  !codecInfo.isEncoder && codecInfo.supportedTypes.any { type ->
    type.equals("video/avc", ignoreCase = true)
  }

private fun codecSoftwareOnly(codecInfo: MediaCodecInfo): Boolean {
  val codecName = codecInfo.name.lowercase(Locale.US)
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    codecInfo.isSoftwareOnly
  } else {
    codecName.startsWith("c2.android.") || codecName.startsWith("omx.google.")
  }
}

private fun isRemoteDeskSafeSoftwareAvcDecoder(codecInfo: MediaCodecInfo): Boolean {
  val codecName = codecInfo.name.lowercase(Locale.US)
  return codecSupportsAvc(codecInfo) &&
    codecSoftwareOnly(codecInfo) &&
    (codecName.startsWith("c2.android.") || codecName.startsWith("omx.google."))
}

private fun logH264DecoderCandidatesForMtk() {
  if (remoteDeskMtkH264CodecCandidatesLogged) {
    return
  }
  remoteDeskMtkH264CodecCandidatesLogged = true
  val worker = Thread({
    try {
      val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
      codecInfos
        .filter { codecInfo -> codecSupportsAvc(codecInfo) }
        .forEach { codecInfo ->
          val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) codecInfo.isHardwareAccelerated else false
          val software = codecSoftwareOnly(codecInfo)
          val vendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) codecInfo.isVendor else false
          Log.i(
            "RemoteDeskRtc",
            "decoder_h264_candidate name=${codecInfo.name} software=$software hardware=$hardware vendor=$vendor safe=${isRemoteDeskSafeSoftwareAvcDecoder(codecInfo)}",
          )
        }
    } catch (error: Exception) {
      Log.w("RemoteDeskRtc", "decoder_h264_candidate_list_failed reason=${error.message ?: "unknown"}")
    }
  }, "rd-h264-codec-candidates")
  worker.isDaemon = true
  worker.start()
}

private fun remoteDeskSupportedAvcColorFormats(codecName: String): Set<Int> {
  return try {
    MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
      .firstOrNull { codecInfo -> codecInfo.name.equals(codecName, ignoreCase = true) }
      ?.getCapabilitiesForType("video/avc")
      ?.colorFormats
      ?.toSet()
      ?: emptySet()
  } catch (error: Throwable) {
    Log.w(
      "RemoteDeskRtc",
      "decoder_color_formats_query_failed codec_name=$codecName reason=${error.message ?: error.javaClass.simpleName}",
    )
    emptySet()
  }
}

// 作者: long；AndroidVideoDecoder 构造阶段会硬校验 WebRTC 内置 DECODER_COLOR_FORMATS，MTK 兜底链路只能在这些 byte-buffer 格式里试，否则还没进 initDecode 就会被拒绝。
private val REMOTE_DESK_WEBRTC_AVC_COLOR_FORMATS = listOf(
  MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
  MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
  2141391872,
  2141391876,
  2141391873,
  2141391874,
  2141391875,
)

private fun remoteDeskPreferredAvcColorFormats(codecName: String, sharedContext: EglBase.Context?): List<Int> {
  val selected = if (sharedContext != null) {
    listOf(REMOTE_DESK_WEBRTC_AVC_COLOR_FORMATS.first())
  } else {
    REMOTE_DESK_WEBRTC_AVC_COLOR_FORMATS
  }
  Log.i(
    "RemoteDeskRtc",
    "decoder_color_formats_selected codec_name=$codecName selected=${selected.joinToString(",")} source=webrtc_allowed_static output_mode=${if (sharedContext != null) "texture" else "byte_buffer"}",
  )
  return selected
}

// 作者: long；反射构造失败时真实原因包在 InvocationTargetException 里，日志必须展开到内层异常，才能区分颜色格式拒绝和 codec 本身不可用。
private fun remoteDeskDecoderCreateErrorMessage(error: Throwable): String {
  val cause = when (error) {
    is java.lang.reflect.InvocationTargetException -> error.targetException ?: error
    is java.util.concurrent.ExecutionException -> error.cause ?: error
    else -> error
  }
  val causeMessage = cause.message ?: cause.javaClass.simpleName
  return if (cause === error) {
    causeMessage
  } else {
    "${error.javaClass.simpleName}:$causeMessage"
  }
}

// 作者: long；Mac native sender 现在声明 Baseline Level 4.0，Android 仍保留旧 3.1/High Profile，保证新全屏 H.264 和旧会话 SDP 都能被匹配。
private val REMOTE_DESK_H264_PROFILE_LEVEL_IDS = listOf("42e028", "42e01f", "640c1f")

private fun remoteDeskH264Codec(profileLevelId: String): VideoCodecInfo =
  VideoCodecInfo(
    0,
    "H264",
    mapOf(
      "level-asymmetry-allowed" to "1",
      "packetization-mode" to "1",
      "profile-level-id" to profileLevelId,
    ),
  )

private class RemoteDeskMtkSafeH264DecoderFactory(
  private val eglContext: EglBase.Context?,
) : VideoDecoderFactory {
  private val softwareFactory = SoftwareVideoDecoderFactory()
  @Volatile private var directDecoderError: String = ""
  @Volatile private var createDecoderLogCount = 0

  override fun createDecoder(codecInfo: VideoCodecInfo): VideoDecoder? {
    logCreateDecoder(codecInfo, "start")
    if (!codecInfo.name.equals("H264", ignoreCase = true)) {
      val softwareDecoder = softwareFactory.createDecoder(codecInfo)
      logCreateDecoder(codecInfo, if (softwareDecoder != null) "software" else "unsupported")
      return softwareDecoder
    }
    if (codecInfo.params["profile-level-id"].orEmpty().isBlank()) {
      logCreateDecoder(codecInfo, "h264_missing_profile")
      return null
    }
    createWebRtcSoftwareH264Decoder(codecInfo)?.let { softwareDecoder ->
      logCreateDecoder(codecInfo, "webrtc_software_h264")
      return RemoteDeskLoggingVideoDecoder("webrtc-software-h264", softwareDecoder)
    }
    // 作者: long；WebRTC 纯软件 H.264 不可用时，才按固定软件 AVC 名称直建 AndroidVideoDecoder，避免全量 MediaCodec 枚举重新选到 MTK 硬解零帧路径。
    val decoder = createDirectSoftwareAvcDecoder()
    if (decoder == null) {
      logCreateDecoder(codecInfo, "direct_software_avc_unavailable:${directDecoderError.ifBlank { "-" }}")
      return null
    }
    logCreateDecoder(codecInfo, "direct_software_avc")
    return decoder
  }

  override fun getSupportedCodecs(): Array<VideoCodecInfo> {
    val codecs = linkedSetOf<VideoCodecInfo>()
    codecs.addAll(softwareFactory.supportedCodecs)
    // 作者: long；MTK 规避机型仍需要向 WebRTC 协商 H.264，但 codec 枚举只暴露通用软件 AVC，避免重新选到 MTK 硬解零帧路径。
    REMOTE_DESK_H264_PROFILE_LEVEL_IDS.forEach { profileLevelId ->
      codecs.add(remoteDeskH264Codec(profileLevelId))
    }
    return codecs.toTypedArray()
  }

  private fun createWebRtcSoftwareH264Decoder(codecInfo: VideoCodecInfo): VideoDecoder? {
    return try {
      val candidates = sequenceOf(codecInfo)
        .plus(REMOTE_DESK_H264_PROFILE_LEVEL_IDS.asSequence().map(::remoteDeskH264Codec))
        .distinctBy { candidate ->
          listOf(
            candidate.name,
            candidate.params["profile-level-id"].orEmpty(),
            candidate.params["packetization-mode"].orEmpty(),
          )
        }
      for (candidate in candidates) {
        // 作者: long；WebRTC AAR 自带 H.264 软件解码时优先走 native software decoder；部分构建只接受 42e01f 能力名，实际码流仍由 SPS/PPS 决定。
        val decoder = softwareFactory.createDecoder(candidate)
        if (decoder != null) {
          Log.i(
            "RemoteDeskRtc",
            "decoder_webrtc_software_h264_created requested_params=${codecInfo.params} software_params=${candidate.params}",
          )
          return decoder
        }
      }
      Log.w(
        "RemoteDeskRtc",
        "decoder_webrtc_software_h264_unavailable requested_params=${codecInfo.params} supported=${softwareFactory.supportedCodecs.joinToString { supported -> "${supported.name}:${supported.params}" }}",
      )
      null
    } catch (error: Throwable) {
      Log.w(
        "RemoteDeskRtc",
        "decoder_webrtc_software_h264_create_failed codec=${codecInfo.name} reason=${error.message ?: error.javaClass.simpleName}",
        error,
      )
      null
    }
  }

  private fun createDirectSoftwareAvcDecoder(): VideoDecoder? {
    // 作者: long；这台 MTK Android 14 设备在 WebRTC 的 MediaCodecVideoDecoderFactory 全量枚举阶段会卡住，
    // 这里按系统软件 AVC 的稳定 codec 名直建 AndroidVideoDecoder，让 H.264 接收链路避开枚举死锁；有 EGL 时走 texture 输出，避免 byte-buffer 初始化拖死首帧。
    val decoders = REMOTE_DESK_MTK_DIRECT_H264_CODEC_NAMES.flatMap { codecName ->
      remoteDeskPreferredAvcColorFormats(codecName, eglContext).mapNotNull { colorFormat ->
        createDirectAndroidVideoDecoder(codecName, colorFormat)
      }
    }
    if (decoders.isEmpty()) {
      return null
    }
    var decoder = decoders.last()
    for (index in decoders.size - 2 downTo 0) {
      decoder = VideoDecoderFallback(decoder, decoders[index])
    }
    return decoder
  }

  private fun createDirectAndroidVideoDecoder(codecName: String, colorFormat: Int): VideoDecoder? {
    if (remoteDeskBlockedDirectH264Codecs.contains(codecName)) {
      Log.w("RemoteDeskRtc", "decoder_direct_h264_skip_blocked codec_name=$codecName")
      return null
    }
    return try {
      val wrapperFactoryClass = Class.forName("org.webrtc.MediaCodecWrapperFactory")
      val wrapperImplClass = Class.forName("org.webrtc.MediaCodecWrapperFactoryImpl")
      val wrapperConstructor = wrapperImplClass.getDeclaredConstructor()
      wrapperConstructor.isAccessible = true
      val wrapperFactory = wrapperConstructor.newInstance()
      val codecTypeClass = Class.forName("org.webrtc.VideoCodecMimeType")
      val h264Type = codecTypeClass.getMethod("valueOf", String::class.java).invoke(null, "H264")
      val decoderClass = Class.forName("org.webrtc.AndroidVideoDecoder")
      val decoderConstructor = decoderClass.getDeclaredConstructor(
        wrapperFactoryClass,
        String::class.java,
        codecTypeClass,
        Int::class.javaPrimitiveType,
        EglBase.Context::class.java,
      )
      decoderConstructor.isAccessible = true
      val decoder = decoderConstructor.newInstance(
        wrapperFactory,
        codecName,
        h264Type,
        colorFormat,
        eglContext,
      ) as VideoDecoder
      directDecoderError = ""
      Log.i(
        "RemoteDeskRtc",
        "decoder_direct_h264_created codec_name=$codecName color_format=$colorFormat output_mode=${if (eglContext != null) "texture" else "byte_buffer"} shared_context_available=${eglContext != null} reflection=true",
      )
      RemoteDeskLoggingVideoDecoder(codecName, decoder)
    } catch (error: Throwable) {
      val reason = remoteDeskDecoderCreateErrorMessage(error)
      directDecoderError = "$codecName:$colorFormat:$reason"
      Log.w(
        "RemoteDeskRtc",
        "decoder_direct_h264_create_failed codec_name=$codecName color_format=$colorFormat reason=$reason",
      )
      null
    }
  }

  private fun logCreateDecoder(codecInfo: VideoCodecInfo, result: String) {
    val count = createDecoderLogCount
    if (count >= 18) {
      return
    }
    createDecoderLogCount = count + 1
    Log.i(
      "RemoteDeskRtc",
      "decoder_create_request codec=${codecInfo.name} params=${codecInfo.params} result=$result count=${count + 1}",
    )
  }
}

private class RemoteDeskLoggingVideoDecoder(
  private val codecName: String,
  private val delegate: VideoDecoder,
) : VideoDecoder {
  @Volatile private var initLogCount = 0
  @Volatile private var decodeLogCount = 0
  @Volatile private var decodedFrameLogCount = 0
  @Volatile private var releaseLogCount = 0
  private val decoderExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "rd-h264-decoder-${codecName.replace('.', '-')}").apply {
      isDaemon = true
    }
  }

  override fun createNative(nativeDecoder: Long): Long {
    return delegate.createNative(nativeDecoder)
  }

  override fun initDecode(settings: VideoDecoder.Settings, callback: VideoDecoder.Callback): VideoCodecStatus {
    val initCount = initLogCount + 1
    initLogCount = initCount
    Log.i(
      "RemoteDeskRtc",
      "decoder_init_start codec_name=$codecName impl=${implementationName} count=$initCount width=${settings.width} height=${settings.height} cores=${settings.numberOfCores}",
    )
    val wrappedCallback = VideoDecoder.Callback { frame: VideoFrame, decodeTimeMs: Int?, qp: Int? ->
      val frameCount = decodedFrameLogCount + 1
      decodedFrameLogCount = frameCount
      if (frameCount <= REMOTE_DESK_DECODER_LOG_LIMIT) {
        Log.i(
          "RemoteDeskRtc",
          "decoder_decoded_frame codec_name=$codecName count=$frameCount size=${frame.rotatedWidth}x${frame.rotatedHeight} decode_time_ms=${decodeTimeMs ?: "-"} qp=${qp ?: "-"}",
        )
      }
      callback.onDecodedFrame(frame, decodeTimeMs, qp)
    }
    val status = callDecoder(
      operation = "initDecode",
      timeoutMs = REMOTE_DESK_DECODER_INIT_TIMEOUT_MS,
      fallback = VideoCodecStatus.FALLBACK_SOFTWARE,
    ) {
      delegate.initDecode(settings, wrappedCallback)
    }
    Log.i(
      "RemoteDeskRtc",
      "decoder_init_done codec_name=$codecName count=$initCount status=$status",
    )
    return status
  }

  override fun decode(image: EncodedImage, info: VideoDecoder.DecodeInfo): VideoCodecStatus {
    val decodeCount = decodeLogCount + 1
    decodeLogCount = decodeCount
    val shouldLog = decodeCount <= REMOTE_DESK_DECODER_LOG_LIMIT ||
      image.frameType == EncodedImage.FrameType.VideoFrameKey
    if (shouldLog) {
      val encodedBytes = image.buffer?.remaining() ?: -1
      Log.i(
        "RemoteDeskRtc",
        "decoder_decode_start codec_name=$codecName count=$decodeCount frame_type=${image.frameType} encoded_size=${image.encodedWidth}x${image.encodedHeight} bytes=$encodedBytes missing=${info.isMissingFrames} render_time_ms=${info.renderTimeMs}",
      )
    }
    val status = callDecoder(
      operation = "decode#$decodeCount",
      timeoutMs = REMOTE_DESK_DECODER_DECODE_TIMEOUT_MS,
      fallback = VideoCodecStatus.FALLBACK_SOFTWARE,
    ) {
      delegate.decode(image, info)
    }
    if (shouldLog || status != VideoCodecStatus.OK) {
      Log.i(
        "RemoteDeskRtc",
        "decoder_decode_done codec_name=$codecName count=$decodeCount status=$status",
      )
    }
    return status
  }

  override fun release(): VideoCodecStatus {
    val releaseCount = releaseLogCount + 1
    releaseLogCount = releaseCount
    val status = callDecoder(
      operation = "release#$releaseCount",
      timeoutMs = REMOTE_DESK_DECODER_RELEASE_TIMEOUT_MS,
      fallback = VideoCodecStatus.ERROR,
    ) {
      delegate.release()
    }
    decoderExecutor.shutdownNow()
    Log.i(
      "RemoteDeskRtc",
      "decoder_release_done codec_name=$codecName count=$releaseCount status=$status",
    )
    return status
  }

  override fun getImplementationName(): String {
    return try {
      "rd-log:${delegate.implementationName}"
    } catch (_: Throwable) {
      "rd-log:$codecName"
    }
  }

  private fun <T> callDecoder(
    operation: String,
    timeoutMs: Long,
    fallback: T,
    block: () -> T,
  ): T {
    val future = try {
      decoderExecutor.submit<T> { block() }
    } catch (error: Throwable) {
      Log.e(
        "RemoteDeskRtc",
        "decoder_operation_rejected codec_name=$codecName operation=$operation reason=${error.message ?: error.javaClass.simpleName}",
        error,
      )
      return fallback
    }
    return try {
      future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (error: Throwable) {
      future.cancel(true)
      val cause = if (error is java.util.concurrent.ExecutionException) {
        error.cause ?: error
      } else {
        error
      }
      val timedOut = error is java.util.concurrent.TimeoutException
      val level = if (timedOut) "timeout" else "failed"
      // 作者: long；MTK 真机上部分软件 AVC 名称会在 MediaCodec configure/start 阶段长时间卡住，
      // 这里同步返回 fallback 状态，让 WebRTC 继续试下一个 decoder，而不是把首帧链路堵死。
      if (operation == "initDecode" && timedOut) {
        remoteDeskBlockedDirectH264Codecs.add(codecName)
        Log.w("RemoteDeskRtc", "decoder_direct_h264_blocked codec_name=$codecName reason=init_timeout timeout_ms=$timeoutMs")
      }
      Log.w(
        "RemoteDeskRtc",
        "decoder_operation_$level codec_name=$codecName operation=$operation timeout_ms=$timeoutMs fallback=$fallback reason=${cause.message ?: cause.javaClass.simpleName}",
        cause,
      )
      fallback
    }
  }
}

private class RemoteDeskVideoDecoderFactory(
  eglContext: EglBase.Context?,
  hardwareAllowedPredicate: Predicate<MediaCodecInfo>,
) : VideoDecoderFactory {
  private val softwareFactory = SoftwareVideoDecoderFactory()
  private val platformSoftwareFactory = PlatformSoftwareVideoDecoderFactory(eglContext)
  private val hardwareFactory = HardwareVideoDecoderFactory(eglContext, hardwareAllowedPredicate)

  override fun createDecoder(codecInfo: VideoCodecInfo): VideoDecoder? {
    val softwareDecoder = softwareFactory.createDecoder(codecInfo)
      ?: platformSoftwareFactory.createDecoder(codecInfo)
    val hardwareDecoder = hardwareFactory.createDecoder(codecInfo)
    return when {
      // 作者: long；VideoDecoderFallback 第二个参数是 primary，硬解可用时仍优先硬解，被屏蔽或初始化失败时再走软件后备。
      softwareDecoder != null && hardwareDecoder != null -> VideoDecoderFallback(softwareDecoder, hardwareDecoder)
      hardwareDecoder != null -> hardwareDecoder
      else -> softwareDecoder
    }
  }

  override fun getSupportedCodecs(): Array<VideoCodecInfo> {
    val codecs = linkedSetOf<VideoCodecInfo>()
    // 作者: long；真机远控优先保留平台软件 H.264 后备，部分 MTK 硬解会建链成功但不吐帧，不能让首帧验收卡死在硬解路径。
    codecs.addAll(softwareFactory.supportedCodecs)
    codecs.addAll(platformSoftwareFactory.supportedCodecs)
    codecs.addAll(hardwareFactory.supportedCodecs)
    REMOTE_DESK_H264_PROFILE_LEVEL_IDS.forEach { profileLevelId ->
      codecs.add(remoteDeskH264Codec(profileLevelId))
    }
    return codecs.toTypedArray()
  }
}

private class RemoteDeskPlatformSoftwareVideoDecoderFactory(
  eglContext: EglBase.Context?,
) : VideoDecoderFactory {
  private val softwareFactory = SoftwareVideoDecoderFactory()
  private val platformSoftwareFactory = PlatformSoftwareVideoDecoderFactory(eglContext)

  override fun createDecoder(codecInfo: VideoCodecInfo): VideoDecoder? {
    return softwareFactory.createDecoder(codecInfo)
      ?: platformSoftwareFactory.createDecoder(codecInfo)
  }

  override fun getSupportedCodecs(): Array<VideoCodecInfo> {
    val codecs = linkedSetOf<VideoCodecInfo>()
    // 作者: long；Redmi Note 8 Pro 上只暴露软件/平台软件解码，保留 H.264 能力但不触碰 MTK AVC 硬解零帧路径。
    codecs.addAll(softwareFactory.supportedCodecs)
    codecs.addAll(platformSoftwareFactory.supportedCodecs)
    REMOTE_DESK_H264_PROFILE_LEVEL_IDS.forEach { profileLevelId ->
      codecs.add(remoteDeskH264Codec(profileLevelId))
    }
    return codecs.toTypedArray()
  }
}

private class RemoteDeskLazyPlatformH264DecoderFactory(
  private val eglContext: EglBase.Context?,
) : VideoDecoderFactory {
  private val softwareFactory = SoftwareVideoDecoderFactory()
  @Volatile private var platformSoftwareFactory: PlatformSoftwareVideoDecoderFactory? = null
  @Volatile private var createDecoderLogCount = 0

  override fun createDecoder(codecInfo: VideoCodecInfo): VideoDecoder? {
    logCreateDecoder(codecInfo, "start")
    val softwareDecoder = softwareFactory.createDecoder(codecInfo)
    if (softwareDecoder != null) {
      logCreateDecoder(codecInfo, "software")
      return softwareDecoder
    }
    if (!codecInfo.name.equals("H264", ignoreCase = true)) {
      logCreateDecoder(codecInfo, "unsupported")
      return null
    }
    val decoder = platformFactory().createDecoder(codecInfo)
    logCreateDecoder(codecInfo, if (decoder != null) "platform_software" else "platform_software_null")
    return decoder
  }

  override fun getSupportedCodecs(): Array<VideoCodecInfo> {
    val codecs = mutableListOf<VideoCodecInfo>()
    codecs.addAll(softwareFactory.supportedCodecs)
    // 作者: long；建 PeerConnection 时只静态声明 H.264，避免提前枚举 MTK MediaCodec；真正收到 H.264 帧时再懒加载平台软件 decoder。
    codecs.addAll(REMOTE_DESK_H264_PROFILE_LEVEL_IDS.map(::remoteDeskH264Codec))
    return codecs.toTypedArray()
  }

  private fun platformFactory(): PlatformSoftwareVideoDecoderFactory {
    val current = platformSoftwareFactory
    if (current != null) {
      return current
    }
    synchronized(this) {
      val existing = platformSoftwareFactory
      if (existing != null) {
        return existing
      }
      val created = PlatformSoftwareVideoDecoderFactory(eglContext)
      platformSoftwareFactory = created
      Log.i("RemoteDeskRtc", "decoder_platform_software_factory_created lazy=true")
      return created
    }
  }

  private fun logCreateDecoder(codecInfo: VideoCodecInfo, result: String) {
    val count = createDecoderLogCount
    if (count >= 12) {
      return
    }
    createDecoderLogCount = count + 1
    Log.i(
      "RemoteDeskRtc",
      "decoder_create_request codec=${codecInfo.name} params=${codecInfo.params} result=$result count=${count + 1}",
    )
  }
}

private class RemoteDeskSoftwareVideoDecoderFactory : VideoDecoderFactory {
  private val softwareFactory = SoftwareVideoDecoderFactory()

  override fun createDecoder(codecInfo: VideoCodecInfo): VideoDecoder? {
    return softwareFactory.createDecoder(codecInfo)
  }

  override fun getSupportedCodecs(): Array<VideoCodecInfo> {
    val codecs = linkedSetOf<VideoCodecInfo>()
    // 作者: long；MTK 规避机型只暴露 WebRTC 纯软件解码能力，连平台 software codec 枚举也跳过，防止 MediaCodec 列表卡住协商线程。
    codecs.addAll(softwareFactory.supportedCodecs)
    return codecs.toTypedArray()
  }
}
