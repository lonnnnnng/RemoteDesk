import { invoke, isTauri } from "@tauri-apps/api/core"
import { WebviewWindow } from "@tauri-apps/api/webviewWindow"
import { useEffect, useMemo, useState, useSyncExternalStore } from "react"
import { createRoot } from "react-dom/client"

const params = new URLSearchParams(window.location.search)
const tauriRuntime = isTauri()
let fatalOverlayShown = false
let cachedRawBgraVideoFrameSupport = null

function parseBooleanFlag(value, fallback = false) {
  const normalized = `${value || ""}`.trim().toLowerCase()
  if (!normalized) {
    return fallback
  }
  if (["1", "true", "yes", "on"].includes(normalized)) {
    return true
  }
  if (["0", "false", "no", "off"].includes(normalized)) {
    return false
  }
  return fallback
}

function escapeFatalText(value) {
  return String(value || "").replace(/[&<>"']/g, (ch) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  }[ch]))
}

function describeUnhandledReason(reason) {
  if (typeof reason === "string") {
    return reason
  }
  if (reason?.message && typeof reason.message === "string") {
    return reason.message
  }
  try {
    return JSON.stringify(reason)
  } catch {
    return String(reason)
  }
}

function showFatalOverlay(title, message) {
  if (fatalOverlayShown) {
    return
  }
  fatalOverlayShown = true
  const host = document.body || document.documentElement
  if (!host) {
    return
  }
  host.innerHTML = `<div style="padding:24px;font:14px/1.6 -apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;color:#111827;background:#f8fafc;"><h2 style="margin:0 0 10px;">${escapeFatalText(title)}</h2><pre style="white-space:pre-wrap;word-break:break-word;margin:0;">${escapeFatalText(message || "unknown error")}</pre></div>`
}

window.addEventListener("error", (event) => {
  try {
    const message = event?.error?.stack || event?.error?.message || event?.message || "unknown error"
    showFatalOverlay("Desktop UI 启动失败", message)
  } catch (error) {
    console.error("fatal error handler failed", error)
  }
}, { once: true })

window.addEventListener("unhandledrejection", (event) => {
  try {
    showFatalOverlay("Desktop UI Promise 异常", describeUnhandledReason(event?.reason) || "unknown rejection")
  } catch (error) {
    console.error("fatal rejection handler failed", error)
  }
}, { once: true })
const queryWsUrl = params.get("ws_url")?.trim() || ""
const queryDeviceId = params.get("device_id")?.trim() || ""
const queryRole = params.get("role")?.trim() || ""
const queryTargetDeviceId = params.get("target_device_id")?.trim() || ""
const sessionWindowMode = parseBooleanFlag(params.get("session_window"), false)
const querySessionDeviceName = params.get("session_device_name")?.trim() || ""

function resolveStorage(type) {
  try {
    return window[type]
  } catch {
    return null
  }
}

function safeStorageGet(storage, key) {
  try {
    if (!storage) {
      return ""
    }
    return storage.getItem(key)?.trim() || ""
  } catch {
    return ""
  }
}

function safeStorageSet(storage, key, value) {
  try {
    if (!storage) {
      return
    }
    storage.setItem(key, value)
  } catch {
    // ignore storage write failures on restricted webview origins
  }
}

const localStore = resolveStorage("localStorage")
const sessionStore = resolveStorage("sessionStorage")

const storedWsUrl = safeStorageGet(localStore, "rd-ws-url")
const storedDeviceId = safeStorageGet(localStore, "rd-device-id")
const storedRole = safeStorageGet(localStore, "rd-role")
const storedTargetDeviceId = safeStorageGet(localStore, "rd-target-device-id")
const storedDebugView = parseBooleanFlag(safeStorageGet(localStore, "rd-ui-debug-view"), false)
const hasExplicitWsUrlOverride = Boolean(queryWsUrl || storedWsUrl)

function randomDeviceIdSuffix() {
  try {
    if (window.crypto?.getRandomValues) {
      const bytes = new Uint8Array(4)
      window.crypto.getRandomValues(bytes)
      return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("")
    }
  } catch {
    // fall back to Math.random below
  }
  return Math.random().toString(36).slice(2, 10)
}

function createDefaultDeviceId() {
  const platform = tauriRuntime ? "desktop" : "browser"
  return `${platform}-${randomDeviceIdSuffix()}`
}

const defaultDeviceId = createDefaultDeviceId()
const defaultRole = tauriRuntime ? "agent" : "controller"
const demoTargetDeviceId = "agent-demo-01"
const defaultTargetDeviceId = ""
const initialDeviceId = queryDeviceId || storedDeviceId || defaultDeviceId
const initialRole = normalizeRole(queryRole || storedRole || defaultRole)
const initialStoredTargetDeviceId = storedTargetDeviceId === demoTargetDeviceId ? "" : storedTargetDeviceId
const initialTargetDeviceId = queryTargetDeviceId || initialStoredTargetDeviceId || defaultTargetDeviceId

const CAPTURE_MAX_WIDTH = 1920
const CAPTURE_MAX_HEIGHT = 1080
const CAPTURE_DIRECT_TRACK_IDEAL_FPS = 24
const CAPTURE_DIRECT_TRACK_MAX_FPS = 30
const CAPTURE_DIRECT_SENDER_MAX_BITRATE = 12000000
const NATIVE_CAPTURE_FALLBACK_MAX_FPS = 26
const NATIVE_CAPTURE_FALLBACK_CODEC = "jpeg-frame-stream"
const NATIVE_CAPTURE_RAW_BGRA_CODEC = "raw-bgra-frame-stream"
const NATIVE_CAPTURE_RAW_BGRA_MIME = "application/x-rd-raw-bgra"
const NATIVE_CAPTURE_RAW_BGRA_ENABLED = parseBooleanFlag(params.get("native_raw_bgra"), tauriRuntime)
const NATIVE_STREAM_DIRECT_FAST_READY_TIMEOUT_MS = 2800
const NATIVE_STREAM_DIRECT_FAST_FIRST_FRAME_TIMEOUT_MS = 1600
const NATIVE_STREAM_DIRECT_RETRY_READY_TIMEOUT_MS = 7000
const NATIVE_STREAM_DIRECT_RETRY_FIRST_FRAME_TIMEOUT_MS = 2400
const NATIVE_BRIDGE_MAX_CONSECUTIVE_ERRORS = 12
const NATIVE_BRIDGE_ERROR_RETRY_MS = 250
const NATIVE_BRIDGE_FETCH_REENABLE_COOLDOWN_MS = 15000
const NATIVE_BRIDGE_REQUIRE_EXPLICIT_CANVAS_FALLBACK_CONFIRM = true
const NATIVE_SENDER_CONTROL_PLANE_DRY_RUN = false
const NATIVE_SENDER_LOCAL_PREVIEW_ENABLED = false
const NATIVE_SENDER_STATUS_POLL_INTERVAL_MS = 2000
const NATIVE_SENDER_SIGNAL_POLL_INTERVAL_MS = 220
const NATIVE_SENDER_PREVIEW_FRAME_POLL_INTERVAL_MS = 180
const NATIVE_SENDER_CAPTURE_PREPARE_MAX_ATTEMPTS = 5
const NATIVE_SENDER_CAPTURE_PREPARE_RETRY_MS = 350
const NATIVE_SENDER_CAPTURE_AUTO_RECOVER_COOLDOWN_MS = 2500
const AGENT_WEBRTC_STATS_INTERVAL_MS = 2000
const AGENT_WEBRTC_STREAM_ZERO_FRAMES_GRACE_MS = 3500
const AGENT_WEBRTC_ZERO_FRAMES_GRACE_MS = 7000
const AGENT_WEBRTC_RECOVERY_COOLDOWN_MS = 8000
const AGENT_WEBRTC_MAX_RECOVERY_ATTEMPTS = 3
const AGENT_WEBRTC_OFFER_RETRY_BASE_DELAY_MS = 1200
const AGENT_WEBRTC_OFFER_RETRY_MAX_ATTEMPTS = 8
const AGENT_ADAPTIVE_DEGRADE_SWITCH_COOLDOWN_MS = 3500
const AGENT_ADAPTIVE_UPGRADE_SWITCH_COOLDOWN_MS = 12000
const AGENT_ADAPTIVE_UPGRADE_MIN_DWELL_MS = 18000
const AGENT_ADAPTIVE_UPGRADE_STABLE_SAMPLES = 5
const AGENT_ADAPTIVE_BAD_SAMPLE_STREAK_FOR_DEGRADE = 2
const AGENT_ADAPTIVE_SEVERE_BAD_SAMPLE_STREAK_FOR_DEGRADE = 2
const AGENT_ADAPTIVE_EWMA_ALPHA = 0.45
const AGENT_ADAPTIVE_MIN_SEND_FPS = 8
const AGENT_ADAPTIVE_SCENE_SWITCH_MIN_DWELL_MS = 4500
const AGENT_ADAPTIVE_SCENE_DYNAMIC_ENTER_STREAK = 1
const AGENT_ADAPTIVE_SCENE_STATIC_ENTER_STREAK = 4
const AGENT_ADAPTIVE_SCENE_DYNAMIC_MIN_KBPS = 260
const AGENT_ADAPTIVE_SCENE_DYNAMIC_MIN_UTIL = 0.10
const AGENT_ADAPTIVE_SCENE_STATIC_MAX_KBPS = 180
const AGENT_ADAPTIVE_SCENE_STATIC_MAX_UTIL = 0.06
const AGENT_ADAPTIVE_SCENE_DYNAMIC_TARGET_PROFILE_INDEX = 0
const AGENT_ADAPTIVE_EMULATOR_WARMUP_MS = 20000
const ICE_POLICY_RELAY_UDP_HIGH_RTT_MS = 220
const QUALITY_HINT_FPS_LOW_THRESHOLD = 10
const QUALITY_HINT_STALL_FPS_THRESHOLD = 16
const QUALITY_HINT_BITRATE_LOW_THRESHOLD_KBPS = 350
const QUALITY_HINT_RTT_HIGH_THRESHOLD_MS = 220
const AGENT_ADAPTIVE_PROFILES = [
  {
    id: "smooth",
    label: "流畅优先",
    maxWidth: 848,
    maxHeight: 480,
    maxFps: 24,
    maxBitrate: 3200000,
    scaleResolutionDownBy: 1.25,
  },
  {
    id: "balanced",
    label: "均衡",
    maxWidth: 1280,
    maxHeight: 720,
    maxFps: 30,
    maxBitrate: 5200000,
    scaleResolutionDownBy: 1.15,
  },
  {
    id: "clear",
    label: "清晰优先",
    maxWidth: CAPTURE_MAX_WIDTH,
    maxHeight: CAPTURE_MAX_HEIGHT,
    maxFps: CAPTURE_DIRECT_TRACK_MAX_FPS,
    maxBitrate: 12000000,
    scaleResolutionDownBy: 1,
  },
]
const AGENT_ADAPTIVE_DEFAULT_PROFILE_INDEX = 0
const EMULATOR_SESSION_MAX_WIDTH = 1280
const EMULATOR_SESSION_MAX_HEIGHT = 720
const EMULATOR_SESSION_MAX_FPS = 24
const EMULATOR_SESSION_MAX_BITRATE = 5200000
const EMULATOR_SESSION_PROFILE_INDEX = 0
const EMULATOR_SESSION_MIN_PROFILE_INDEX = 0
const EMULATOR_SESSION_MAX_PROFILE_INDEX = 0
const EMULATOR_SESSION_MAX_SCALE_DOWN_BY = 1.0
const ANDROID_PHONE_SESSION_PROFILE_INDEX = 2
const ANDROID_PHONE_SESSION_MAX_WIDTH = 1440
const ANDROID_PHONE_SESSION_MAX_HEIGHT = 936
const ANDROID_PHONE_SESSION_MAX_FPS = 24
const ANDROID_PHONE_SESSION_MAX_BITRATE = 14000000
const ANDROID_PHONE_SESSION_MAX_SCALE_DOWN_BY = 1.0
const ANDROID_PHONE_INTERACTIVE_MAX_WIDTH = 384
const ANDROID_PHONE_INTERACTIVE_MAX_HEIGHT = 250
const ANDROID_PHONE_INTERACTIVE_MAX_FPS = 30
// 作者: long；WebRTC/H.264 主链路不再受 legacy JPEG 的 800px 档限制，全屏查看先给足真实像素，再由输入态临时降档保证跟手。
const ANDROID_PHONE_FULLSCREEN_MAX_WIDTH = CAPTURE_MAX_WIDTH
const ANDROID_PHONE_FULLSCREEN_MAX_HEIGHT = CAPTURE_MAX_HEIGHT
const ANDROID_PHONE_FULLSCREEN_MAX_FPS = 24
const ANDROID_PHONE_FULLSCREEN_MAX_BITRATE = 18000000
const ANDROID_PHONE_PINCH_PREVIEW_MAX_WIDTH = 320
const ANDROID_PHONE_PINCH_PREVIEW_MAX_HEIGHT = 208
const ANDROID_PHONE_PINCH_PREVIEW_MAX_FPS = 8
const ANDROID_PHONE_PINCH_PREVIEW_MAX_BITRATE = 3600000
const ANDROID_PHONE_ZOOM_MOTION_MAX_WIDTH = 512
const ANDROID_PHONE_ZOOM_MOTION_MAX_HEIGHT = 333
const ANDROID_PHONE_ZOOM_MOTION_MAX_FPS = 12
const ANDROID_PHONE_ZOOM_MOTION_MAX_BITRATE = 8200000
const ANDROID_PHONE_ZOOM_DETAIL_MAX_WIDTH = 1280
const ANDROID_PHONE_ZOOM_DETAIL_MAX_HEIGHT = 832
const ANDROID_PHONE_ZOOM_DETAIL_MAX_FPS = 18
const ANDROID_PHONE_ZOOM_DETAIL_MAX_BITRATE = 14000000
const ANDROID_PHONE_ZOOM_STILL_MAX_WIDTH = 1600
const ANDROID_PHONE_ZOOM_STILL_MAX_HEIGHT = 1040
const ANDROID_PHONE_ZOOM_STILL_MAX_FPS = 12
const ANDROID_PHONE_ZOOM_STILL_MAX_BITRATE = 18000000
const ANDROID_PHONE_ZOOM_DETAIL_MIN_SCALE = 1.08
// 作者: long；H.264 native sender 已经把重负载从 Android JPEG 解码挪到视频解码器，停手后的局部区域可以升到真实高清帧补文字细节。
const ANDROID_PHONE_ZOOM_DETAIL_UPGRADE_DELAY_MS = 150
const ANDROID_PHONE_ZOOM_STILL_UPGRADE_DELAY_MS = 760
const ANDROID_PHONE_SOURCE_RECT_UNITS = 1000000
const ANDROID_PHONE_MIN_SOURCE_RECT_PPM = 286000
// 作者: long；最大缩放后鼠标移动会连续推动局部视角，裁剪源仍要跟随，但拖动中只做低频重配，抬手再强制落最终 source_rect，避免 JPEG 采集和 Android 全屏窗口提交叠加出系统回压。
const ANDROID_PHONE_ZOOM_REGION_SOURCE_RECT_UPDATE_MIN_MS = 520
const ANDROID_PHONE_ZOOM_REGION_SOURCE_RECT_UPDATE_THRESHOLD_PPM = 45000
// 作者: long；真机输入通常是一串短促 move/pinch 事件，恢复窗口过短会让采集分辨率在移动中来回跳档，画面看起来更抖。
const NATIVE_SENDER_INTERACTIVE_RESTORE_DELAY_MS = 1800
const NATIVE_SENDER_VIEWPORT_INTERACTION_RESTORE_DELAY_MS = 520
const NATIVE_SENDER_PINCH_RESTORE_DELAY_MS = 650
const NATIVE_SENDER_PAN_RESTORE_DELAY_MS = 650
// 作者: long；Android 真机现在默认验证 WebRTC/H.264 native sender 主链路，JPEG 帧流只在首帧失败或兼容性异常时兜底可见画面。
const ANDROID_PHONE_LEGACY_FRAME_STREAM_ONLY = false
// 作者: long；兜底帧流承担真机可视操作时，移动中用更小帧换刷新，停手后再用局部清晰帧补文字细节。
const CAPTURE_FRAME_INTERVAL_MS = 100
const ANDROID_PHONE_LEGACY_INTERACTIVE_INTERVAL_MS = 33
const ANDROID_PHONE_LEGACY_PINCH_PREVIEW_INTERVAL_MS = 125
const ANDROID_PHONE_LEGACY_FULLSCREEN_INTERVAL_MS = 42
const ANDROID_PHONE_LEGACY_FULLSCREEN_HD_INTERVAL_MS = 167
const ANDROID_PHONE_LEGACY_ZOOM_DETAIL_INTERVAL_MS = 84
const ANDROID_PHONE_LEGACY_ZOOM_STILL_INTERVAL_MS = 125
const ANDROID_PHONE_LEGACY_CLEAR_INTERVAL_MS = 62
const AUTO_CONNECT_DELAY_MS = 1200
const SETTINGS_AUTO_CONNECT_DELAY_MS = 500
const WS_RECONNECT_INITIAL_DELAY_MS = 1000
const WS_RECONNECT_MAX_DELAY_MS = 15000
const AUTO_SEND_DEDUP_MS = 1200
const MAX_INCOMING_FRAME_B64_LENGTH = 12 * 1024 * 1024
const MAX_INCOMING_FRAME_DIMENSION = 4096
const MAX_UI_LOG_LINES = 120
const DESKTOP_TRACE_VERBOSE = true
const INPUT_UI_RENDER_THROTTLE_MS = 120
const INPUT_MOVE_LOG_THROTTLE_MS = 800
// 作者: long；鼠标移动本身按接近 60Hz 执行，成功回执降频回传，避免 ACK 流量反过来抢占远控画面和后续 move 信令。
const INPUT_MOVE_RESULT_PUSH_THROTTLE_MS = 240
// 作者: long；兜底 JPEG 流每帧都会推给 Android，桌面端本地预览不需要同频 render，降频可把 CPU 留给采集编码和输入执行。
const FRAME_STREAM_UI_RENDER_THROTTLE_MS = 500
const LOCAL_PREVIEW_FPS_SAMPLE_MS = 1000
const SESSION_LINK_SNAPSHOT_LOG_INTERVAL_MS = 2000
const LIVE_E2E_PROOF_REPORT_MIN_INTERVAL_MS = 1500
const REMOTE_POINTER_MOVE_THROTTLE_MS = 16
// 作者: long；Android 最大缩放平移视角时，source_rect 重配、JPEG 帧和原生鼠标事件会叠加成系统合成压力，受控端同步降到约 25fps 执行鼠标 move。
const REMOTE_ZOOM_PAN_MOUSE_MOVE_THROTTLE_MS = 48
const REMOTE_ZOOM_PAN_MOUSE_MOVE_RECENT_MS = 1800
const REMOTE_WHEEL_DELTA_SCALE = 120
const REMOTE_WHEEL_MAX_DELTA = 4096
const REMOTE_INPUT_REQUIRED_CATEGORIES = ["click", "drag", "keyboard", "wheel"]
const SESSION_FILE_CHUNK_RAW_BYTES = 192 * 1024
const SESSION_FILE_MAX_BYTES = 64 * 1024 * 1024
// 作者: long；Android 电脑键盘面板和桌面内置控制端共用这组标准 DOM key code，白名单必须覆盖目标端原生执行器支持的键位。
const REMOTE_KEY_CODES = new Set([
  "Backquote", "Backslash", "Backspace", "BracketLeft", "BracketRight", "Comma", "Digit0",
  "Digit1", "Digit2", "Digit3", "Digit4", "Digit5", "Digit6", "Digit7", "Digit8", "Digit9",
  "Enter", "Equal", "Escape", "KeyA", "KeyB", "KeyC", "KeyD", "KeyE", "KeyF", "KeyG", "KeyH",
  "KeyI", "KeyJ", "KeyK", "KeyL", "KeyM", "KeyN", "KeyO", "KeyP", "KeyQ", "KeyR", "KeyS",
  "KeyT", "KeyU", "KeyV", "KeyW", "KeyX", "KeyY", "KeyZ", "Minus", "Period", "Quote",
  "Semicolon", "Slash", "Space", "Tab", "ArrowDown", "ArrowLeft", "ArrowRight", "ArrowUp",
  "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
  "Insert", "Delete", "Home", "End", "PageUp", "PageDown", "PrintScreen", "ScrollLock",
  "Pause", "CapsLock", "NumLock", "Numpad0", "Numpad1", "Numpad2", "Numpad3", "Numpad4",
  "Numpad5", "Numpad6", "Numpad7", "Numpad8", "Numpad9", "NumpadAdd", "NumpadSubtract",
  "NumpadMultiply", "NumpadDivide", "NumpadDecimal", "NumpadEnter", "NumpadEqual",
])
const REMOTE_MODIFIER_CODES = new Set([
  "ShiftLeft", "ShiftRight", "ControlLeft", "ControlRight", "AltLeft", "AltRight", "MetaLeft",
  "MetaRight",
])

const state = {
  ws: null,
  wsUrl: queryWsUrl || storedWsUrl || "",
  token: "",
  sessionId: "",
  sessionInfo: null,
  deviceId: initialDeviceId,
  role: initialRole,
  targetDeviceId: initialTargetDeviceId,
  logs: [],
  remoteFrameUrl: "",
  remoteFrameMeta: null,
  localFrameUrl: "",
  localFrameMeta: null,
  lastInput: "",
  lastAck: "",
  clipboardStatus: "剪贴板：等待会话",
  fileTransferStatus: "文件：等待会话",
  incomingFileTransfers: new Map(),
  debugToolsEnabled: false,
  debugSendClipboardText: "",
  debugSendFilePath: "",
  debugSessionToolsRunKey: "",
  lastRemoteInputResult: emptyRemoteInputResult(),
  remoteInputResultCount: 0,
  remoteInputResultAppliedCount: 0,
  remoteInputResultFailedCount: 0,
  remoteInputAppliedCategories: emptyRemoteInputCoverage(),
  streamTimer: null,
  streamLoopId: 0,
  streamStarting: false,
  streamSending: false,
  streamSendingLoopId: 0,
  streamSendPromise: null,
  streamLastSentCaptureTs: 0,
  frameSeq: 0,
  messageSeq: 0,
  lastSessionMetricsReportSessionId: "",
  lastSessionMetricsReportKey: "",
  lastLiveE2EProofReportAt: 0,
  lastLiveE2EProofReportKey: "",
  peerConnection: null,
  remoteMediaStream: null,
  localMediaStream: null,
  nativeBridgeStream: null,
  nativeBridgeVideo: null,
  nativeBridgeImage: null,
  nativeBridgeEndpoint: "",
  nativeBridgeMode: "",
  nativeBridgeCanvas: null,
  nativeBridgeFetchAbortController: null,
  nativeBridgeFetchDisabled: false,
  nativeBridgeFetchDisabledReason: "",
  nativeBridgeFetchDisabledAt: 0,
  nativeBridgeFetchSkipLoggedKey: "",
  nativeBridgeCanvasFallbackDecisionSessionId: "",
  nativeBridgeCanvasFallbackAllowed: false,
  nativeBridgeTimer: null,
  nativeBridgeLoopId: 0,
  agentStatsTimer: null,
  agentStatsLoopId: 0,
  controllerStatsTimer: null,
  controllerStatsLoopId: 0,
  nativeSenderStatusTimer: null,
  nativeSenderStatusLoopId: 0,
  nativeSenderSignalTimer: null,
  nativeSenderSignalLoopId: 0,
  nativeSenderPreviewTimer: null,
  nativeSenderPreviewLoopId: 0,
  nativeSenderPreviewCanvas: null,
  nativeSenderPreviewVideo: null,
  nativeSenderPreviewStream: null,
  nativeSenderPreviewMode: "",
  nativeSenderPreviewVideoModeDisabled: false,
  nativeSenderInteractiveRestoreTimer: null,
  nativeSenderInteractiveProfileActive: false,
  nativeSenderInteractiveSessionId: "",
  nativeSenderInteractiveProfileMode: "",
  nativeSenderInteractiveSourceRectKey: "",
  nativeSenderInteractiveSourceRectUpdatedAt: 0,
  nativeSenderFullscreenProfileActive: false,
  nativeSenderFullscreenSessionId: "",
  nativeSenderZoomDetailUpgradeTimer: null,
  nativeSenderZoomStillUpgradeTimer: null,
  nativeSenderZoomDetailSessionId: "",
  lastAndroidViewportInteraction: null,
  agentZeroFramesSince: 0,
  agentRecoveryAttempts: 0,
  agentLastRecoveryAt: 0,
  agentOfferRetryTimer: null,
  agentOfferRetryAttempts: 0,
  agentAdaptive: emptyAgentAdaptiveState(),
  agentStatsSnapshot: emptyAgentStatsSnapshot(),
  controllerStatsSnapshot: emptyControllerStatsSnapshot(),
  bridgeModeStats: emptyBridgeModeStats(),
  sessionStartedAt: 0,
  firstFrameAt: 0,
  webrtcState: "idle",
  pendingRemoteCandidates: [],
  captureStream: null,
  captureVideo: null,
  captureCanvas: null,
  captureError: "",
  captureLabel: "",
  platformCapabilities: emptyPlatformCapabilities(),
  nativeSenderCapabilities: emptyNativeSenderCapabilities(),
  nativeSenderStatus: emptyNativeSenderStatus(),
  nativeSenderError: "",
  nativeSenderCaptureRecoverInFlight: false,
  nativeSenderCaptureRecoverLastAt: 0,
  captureStatus: emptyCaptureStatus(),
  captureSources: [],
  windowsSelfTestRunning: false,
  windowsSelfTestReport: null,
  windowsSelfTestError: "",
  e2eProofLoading: false,
  e2eProofResetting: false,
  e2eProofSnapshot: null,
  e2eProofError: "",
  lastE2EProofSyncAt: 0,
  runtime: isTauri() ? "tauri" : "browser",
  shellPlatform: tauriRuntime ? "unknown" : inferPlatform(),
  protocolVersion: "1.0",
  defaultWsUrl: "ws://localhost:18081/ws",
  defaultCodec: "jpeg-frame-stream",
  bootstrapError: "",
  hostBridgeStatus: emptyHostBridgeStatus(),
  hostBridgeError: "",
  autoConnect: true,
  autoRegister: true,
  autoHeartbeat: true,
  autoBootstrapped: false,
  autoConnectTimer: null,
  settingsAutoConnectTimer: null,
  wsReconnectTimer: null,
  wsReconnectAttempt: 0,
  connectionKey: "",
  connectAttemptSeq: 0,
  lastRegisterAt: 0,
  lastRegisteredCapabilitiesKey: "",
  lastHeartbeatAt: 0,
  currentPage: "devices",
  relayDevices: [],
  devicesStatusMessage: "请输入中继地址后同步设备。",
  lastDevicesUrl: "",
  devicesLoaded: false,
  devicesLoading: false,
  lastDevicesSyncAt: 0,
  lastDevicesPushAt: 0,
  presenceReady: false,
  devicesNoticeDismissed: false,
  agentAutoPrepareAttempted: false,
  agentAutoPrepareInFlight: false,
  uiDebugView: storedDebugView,
  uiRuntimeMode: "react",
  localPreviewFps: -1,
  localPreviewFpsUpdatedAtMs: 0,
}

let uiRevision = 0
const uiSubscribers = new Set()
let inputUiRenderTimer = null
let lastInputUiRenderAtMs = 0
let lastInputMoveLogAtMs = 0
let lastInputMoveResultPushAtMs = 0
let lastFrameStreamUiRenderAtMs = 0
let inputMoveSuppressedCount = 0
let hostMouseMoveApplyInFlight = false
let hostMouseMoveApplyPromise = null
let hostMouseMoveDrainTimer = null
let hostMouseMovePendingMsg = null
let lastHostMouseMoveApplyAtMs = 0
let hostDiscreteInputQueueTail = Promise.resolve()
let hostDiscreteInputQueueDepth = 0
let localPreviewSamplerTimer = null
let localPreviewLastTotalFrames = -1
let localPreviewLastSampleAtMs = 0
let localPreviewFrameCallbackElement = null
let localPreviewFrameCallbackHandle = 0
let localPreviewFrameSampleStartMs = 0
let localPreviewFrameSampleCount = 0
let sessionLinkSnapshotTimer = null
const remoteViewportPointer = {
  active: false,
  pointerId: null,
  button: "left",
  lastPoint: null,
  lastMoveAt: 0,
  dragging: false,
}
const remoteViewportActiveModifiers = new Set()
const remoteViewportKeyModifiers = new Map()

function emitUiRevision() {
  uiRevision += 1
  for (const subscriber of uiSubscribers) {
    try {
      subscriber()
    } catch {
      // ignore subscriber failures to keep runtime alive
    }
  }
}

function subscribeUiRevision(callback) {
  uiSubscribers.add(callback)
  return () => {
    uiSubscribers.delete(callback)
  }
}

function getUiRevisionSnapshot() {
  return uiRevision
}

function scheduleInputUiRender() {
  const now = Date.now()
  const elapsed = now - lastInputUiRenderAtMs
  if (elapsed >= INPUT_UI_RENDER_THROTTLE_MS) {
    lastInputUiRenderAtMs = now
    render()
    return
  }
  if (inputUiRenderTimer) {
    return
  }
  const waitMs = Math.max(1, INPUT_UI_RENDER_THROTTLE_MS - elapsed)
  inputUiRenderTimer = window.setTimeout(() => {
    inputUiRenderTimer = null
    lastInputUiRenderAtMs = Date.now()
    render()
  }, waitMs)
}

function appendInputMoveLogThrottled() {
  const now = Date.now()
  if (now - lastInputMoveLogAtMs >= INPUT_MOVE_LOG_THROTTLE_MS) {
    const suffix = inputMoveSuppressedCount > 0 ? `（+${inputMoveSuppressedCount} 条移动）` : ""
    appendLog(`收到输入: 鼠标移动${suffix}`)
    lastInputMoveLogAtMs = now
    inputMoveSuppressedCount = 0
    return
  }
  inputMoveSuppressedCount += 1
}

function normalizeRole(value) {
  return `${value || ""}`.trim().toLowerCase() === "agent" ? "agent" : "controller"
}

function normalizeDeviceRole(value) {
  const normalized = `${value || ""}`.trim().toLowerCase()
  if (normalized === "agent" || normalized === "controller") {
    return normalized
  }
  return normalized || "unknown"
}

function persistLocalSettings() {
  safeStorageSet(localStore, "rd-ws-url", state.wsUrl)
  safeStorageSet(localStore, "rd-device-id", state.deviceId)
  safeStorageSet(localStore, "rd-role", state.role)
  safeStorageSet(localStore, "rd-target-device-id", state.targetDeviceId)
  safeStorageSet(localStore, "rd-ui-debug-view", state.uiDebugView ? "1" : "0")
}

async function updateRole(nextRole) {
  const normalizedRole = normalizeRole(nextRole)
  if (state.role === normalizedRole) {
    return
  }

  state.role = normalizedRole
  persistLocalSettings()
  if (normalizedRole !== "agent" && !isAgentSession()) {
    stopFrameStream(true)
    await stopCaptureStream(true)
  }

  state.token = ""
  state.presenceReady = false
  state.lastRegisterAt = 0
  state.lastRegisteredCapabilitiesKey = ""
  state.lastHeartbeatAt = 0
  state.agentAutoPrepareAttempted = false
  state.agentAutoPrepareInFlight = false

  render()
  if (connectionReady()) {
    sendRegister({ auto: true })
  }
}

async function copyText(value, successLabel) {
  const text = `${value || ""}`.trim()
  if (!text) {
    appendLog(`没有可复制的${successLabel}`)
    render()
    return false
  }

  try {
    await navigator.clipboard.writeText(text)
    appendLog(`已复制${successLabel}: ${text}`)
    render()
    return true
  } catch (error) {
    appendLog(`复制${successLabel}失败: ${error?.message || "unknown error"}`)
    render()
    return false
  }
}

function updateClipboardStatus(message) {
  state.clipboardStatus = message
  appendLog(message)
}

function updateFileTransferStatus(message) {
  state.fileTransferStatus = message
  appendLog(message)
}

function currentSessionToolSessionId(label = "工具") {
  const sessionId = `${state.sessionId || ""}`.trim()
  if (!sessionId) {
    appendLog(`${label}需要先建立远程会话`)
    return ""
  }
  return sessionId
}

function sessionToolMessageMatchesCurrentSession(msg, label = "工具") {
  const messageSessionId = `${msg?.session_id || msg?.payload?.session_id || ""}`.trim()
  if (!state.sessionId || !messageSessionId || messageSessionId !== state.sessionId) {
    appendLog(`忽略非当前会话${label}消息`)
    return false
  }
  return true
}

function randomTransferId(prefix) {
  const id = typeof crypto?.randomUUID === "function"
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `${prefix}-${id}`
}

function sanitizeRemoteFileName(name) {
  const cleaned = `${name || ""}`
    .trim()
    .replace(/[\\/:*?"<>|\n\r\t]/g, "_")
    .slice(0, 120)
  return cleaned || "remote-file.bin"
}

function formatBytes(size) {
  const value = Number(size) || 0
  if (value < 1024) {
    return `${value} B`
  }
  const kb = value / 1024
  if (kb < 1024) {
    return `${kb.toFixed(1)} KB`
  }
  return `${(kb / 1024).toFixed(1)} MB`
}

function uint8ArrayToBase64(bytes) {
  let binary = ""
  for (let index = 0; index < bytes.length; index += 1) {
    binary += String.fromCharCode(bytes[index])
  }
  return btoa(binary)
}

function base64ToUint8Array(base64) {
  const binary = atob(`${base64 || ""}`)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index)
  }
  return bytes
}

function normalizeSha256(value) {
  const normalized = `${value || ""}`.trim().toLowerCase()
  return /^[a-f0-9]{64}$/.test(normalized) ? normalized : ""
}

async function sha256HexOfBytes(bytes) {
  if (!window.crypto?.subtle?.digest) {
    throw new Error("当前桌面环境不支持 SHA-256 校验")
  }
  const digest = await window.crypto.subtle.digest("SHA-256", bytes)
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("")
}

async function readDesktopClipboardText() {
  if (isTauri()) {
    return `${await invoke("desktop_clipboard_read_text") || ""}`
  }
  if (!navigator.clipboard?.readText) {
    throw new Error("当前桌面环境不支持读取剪贴板")
  }
  return `${await navigator.clipboard.readText() || ""}`
}

async function writeDesktopClipboardText(text) {
  if (isTauri()) {
    await invoke("desktop_clipboard_write_text", { text })
    return
  }
  if (!navigator.clipboard?.writeText) {
    throw new Error("当前桌面环境不支持写入剪贴板")
  }
  await navigator.clipboard.writeText(text)
}

function concatUint8Chunks(chunks, totalSize) {
  const bytes = new Uint8Array(totalSize)
  let offset = 0
  for (const chunk of chunks) {
    bytes.set(chunk, offset)
    offset += chunk.byteLength
  }
  return bytes
}

function downloadIncomingFileInBrowser(transfer, chunks, totalSize) {
  const blob = new Blob(chunks, { type: transfer.mime || "application/octet-stream" })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement("a")
  anchor.href = url
  anchor.download = transfer.name
  anchor.style.display = "none"
  document.body.appendChild(anchor)
  anchor.click()
  window.setTimeout(() => {
    URL.revokeObjectURL(url)
    anchor.remove()
  }, 1000)
  return {
    name: transfer.name,
    path: "",
    bytes: totalSize,
  }
}

async function sendClipboardToRemote() {
  const sessionId = currentSessionToolSessionId("剪贴板")
  if (!sessionId) {
    updateClipboardStatus("剪贴板：请先建立会话")
    render()
    return false
  }
  try {
    const text = await readDesktopClipboardText()
    return sendClipboardTextToRemote(text, { emptyMessage: "剪贴板：本机剪贴板为空" })
  } catch (error) {
    updateClipboardStatus(`剪贴板：读取失败 ${error?.message || "unknown"}`)
    render()
    return false
  }
}

function sendClipboardTextToRemote(text, options = {}) {
  const sessionId = currentSessionToolSessionId("剪贴板")
  if (!sessionId) {
    updateClipboardStatus("剪贴板：请先建立会话")
    render()
    return false
  }
  const value = `${text || ""}`
  if (!value) {
    updateClipboardStatus(options.emptyMessage || "剪贴板：待发送文本为空")
    render()
    return false
  }
  const sent = sendEnvelope("clipboard.text", {
    clipboard_id: randomTransferId("clip"),
    text: value,
    source_platform: `${state.shellPlatform || "macos"}`,
    created_at: Date.now(),
  }, sessionId, { log: false })
  updateClipboardStatus(sent ? `剪贴板：已发送 ${value.length} 字符` : "剪贴板：发送失败")
  render()
  return sent
}

async function handleIncomingClipboardText(msg) {
  if (!sessionToolMessageMatchesCurrentSession(msg, "剪贴板")) {
    return
  }
  const clipboardId = `${msg.payload?.clipboard_id || ""}`.trim()
  const text = `${msg.payload?.text || ""}`
  if (!text) {
    updateClipboardStatus("剪贴板：收到空内容，已忽略")
    sendClipboardResultToRemote(clipboardId, false, 0, "clipboard text is empty")
    return
  }
  try {
    await writeDesktopClipboardText(text)
    updateClipboardStatus(`剪贴板：已接收 ${text.length} 字符并写入本机`)
    sendClipboardResultToRemote(clipboardId, true, text.length)
  } catch (error) {
    const detail = error?.message || "unknown"
    updateClipboardStatus(`剪贴板：接收成功但写入失败 ${detail}`)
    sendClipboardResultToRemote(clipboardId, false, text.length, detail)
  }
}

function sendClipboardResultToRemote(clipboardId, applied, chars = 0, errorDetail = "") {
  const sessionId = currentSessionToolSessionId("剪贴板")
  const cleanClipboardId = `${clipboardId || ""}`.trim()
  if (!sessionId || !cleanClipboardId) {
    return false
  }
  // 作者: long；共享剪贴板不能只证明消息被 relay 转发，接收端必须回传本机剪贴板写入结果，发送端才能区分“已送达”和“已应用”。
  return sendEnvelope("clipboard.result", {
    clipboard_id: cleanClipboardId,
    applied: Boolean(applied),
    chars: Math.max(0, Number(chars) || 0),
    error_detail: applied ? "" : (`${errorDetail || "clipboard apply failed"}`).slice(0, 512),
    created_at: Date.now(),
  }, sessionId, { log: false })
}

function handleIncomingClipboardResult(msg) {
  if (!sessionToolMessageMatchesCurrentSession(msg, "剪贴板")) {
    return
  }
  const payload = msg.payload || {}
  const applied = Boolean(payload.applied)
  const chars = Math.max(0, Number(payload.chars || 0) || 0)
  const detail = `${payload.error_detail || ""}`.trim()
  updateClipboardStatus(
    applied
      ? `剪贴板：对端已写入 ${chars} 字符`
      : `剪贴板：对端写入失败 ${detail || "unknown"}`,
  )
}

async function sendFileBytesToRemote({ name, mime, bytes }) {
  const sessionId = currentSessionToolSessionId("文件")
  if (!sessionId || !(bytes instanceof Uint8Array)) {
    updateFileTransferStatus("文件：请先建立会话")
    render()
    return false
  }
  if (bytes.length > SESSION_FILE_MAX_BYTES) {
    updateFileTransferStatus("文件：超过 64MB，未发送")
    render()
    return false
  }
  try {
    const totalChunks = Math.max(1, Math.ceil(bytes.length / SESSION_FILE_CHUNK_RAW_BYTES))
    const fileId = randomTransferId("file")
    const safeName = sanitizeRemoteFileName(name)
    const resolvedMime = `${mime || "application/octet-stream"}`
    const sha256 = await sha256HexOfBytes(bytes)
    let sent = sendEnvelope("file.transfer.start", {
      file_id: fileId,
      name: safeName,
      mime: resolvedMime,
      size: bytes.length,
      total_chunks: totalChunks,
      sha256,
    }, sessionId, { log: false })
    if (sent) {
      for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex += 1) {
        const start = chunkIndex * SESSION_FILE_CHUNK_RAW_BYTES
        const end = Math.min(start + SESSION_FILE_CHUNK_RAW_BYTES, bytes.length)
        sent = sendEnvelope("file.transfer.chunk", {
          file_id: fileId,
          chunk_index: chunkIndex,
          total_chunks: totalChunks,
          data_base64: uint8ArrayToBase64(bytes.slice(start, end)),
        }, sessionId, { log: false })
        if (!sent) {
          break
        }
      }
    }
    if (sent) {
      sent = sendEnvelope("file.transfer.complete", {
        file_id: fileId,
        name: safeName,
        mime: resolvedMime,
        size: bytes.length,
        total_chunks: totalChunks,
        sha256,
      }, sessionId, { log: false })
    }
    updateFileTransferStatus(sent ? `文件：已发送 ${safeName} (${formatBytes(bytes.length)})` : "文件：发送中断")
    render()
    return sent
  } catch (error) {
    updateFileTransferStatus(`文件：发送失败 ${error?.message || "unknown"}`)
    render()
    return false
  }
}

async function sendFileToRemote(file) {
  if (!file) {
    updateFileTransferStatus("文件：请先选择文件")
    render()
    return false
  }
  try {
    updateFileTransferStatus(`文件：正在读取 ${file.name}`)
    render()
    const bytes = new Uint8Array(await file.arrayBuffer())
    return sendFileBytesToRemote({
      name: file.name,
      mime: file.type || "application/octet-stream",
      bytes,
    })
  } catch (error) {
    updateFileTransferStatus(`文件：发送失败 ${error?.message || "unknown"}`)
    render()
    return false
  }
}

async function runDesktopDebugSessionTools() {
  if (!state.debugToolsEnabled || !isAgentSession()) {
    return
  }
  const sessionId = `${state.sessionId || ""}`.trim()
  if (!sessionId) {
    return
  }
  const clipboardText = `${state.debugSendClipboardText || ""}`
  const filePath = `${state.debugSendFilePath || ""}`.trim()
  const runKey = `${sessionId}|${clipboardText}|${filePath}`
  if (state.debugSessionToolsRunKey === runKey) {
    return
  }
  state.debugSessionToolsRunKey = runKey

  // 作者: long；真机端到端验证需要从 Mac 受控端主动发工具消息，调试入口只在显式环境变量开启时运行，避免普通会话误传本机文件或剪贴板。
  if (clipboardText) {
    sendClipboardTextToRemote(clipboardText)
  }

  if (!filePath) {
    return
  }
  if (!isTauri()) {
    updateFileTransferStatus("文件：调试发送需要 Tauri 桌面运行时")
    render()
    return
  }
  try {
    updateFileTransferStatus(`文件：调试读取 ${filePath}`)
    render()
    const payload = await invoke("desktop_debug_read_file", { path: filePath })
    const bytes = base64ToUint8Array(payload?.data_base64 || payload?.dataBase64 || "")
    await sendFileBytesToRemote({
      name: payload?.name || "remote-file.bin",
      mime: payload?.mime || "application/octet-stream",
      bytes,
    })
  } catch (error) {
    updateFileTransferStatus(`文件：调试发送失败 ${error?.message || "unknown"}`)
    render()
  }
}

function handleIncomingFileTransferStart(msg) {
  if (!sessionToolMessageMatchesCurrentSession(msg, "文件")) {
    return
  }
  const payload = msg.payload || {}
  const fileId = `${payload.file_id || ""}`.trim()
  const name = sanitizeRemoteFileName(payload.name)
  const totalChunks = Number(payload.total_chunks || 0)
  const size = Number(payload.size || 0)
  if (!fileId || !name || totalChunks <= 0 || totalChunks > 512 || size > SESSION_FILE_MAX_BYTES) {
    updateFileTransferStatus("文件：收到无效文件请求")
    sendFileTransferResultToRemote({
      fileId,
      applied: false,
      name,
      bytes: Math.max(0, size || 0),
      sha256: normalizeSha256(payload.sha256),
      errorDetail: "invalid file transfer start payload",
    })
    return
  }
  state.incomingFileTransfers.set(fileId, {
    fileId,
    name,
    mime: `${payload.mime || "application/octet-stream"}`,
    size,
    totalChunks,
    sha256: normalizeSha256(payload.sha256),
    chunks: new Map(),
  })
  updateFileTransferStatus(`文件：开始接收 ${name}，共 ${totalChunks} 块`)
}

function handleIncomingFileTransferChunk(msg) {
  if (!sessionToolMessageMatchesCurrentSession(msg, "文件")) {
    return
  }
  const payload = msg.payload || {}
  const fileId = `${payload.file_id || ""}`.trim()
  const transfer = state.incomingFileTransfers.get(fileId)
  if (!transfer) {
    updateFileTransferStatus("文件：收到未知分块，已忽略")
    sendFileTransferResultToRemote({
      fileId,
      applied: false,
      errorDetail: "file chunk arrived before transfer start",
    })
    return
  }
  const chunkIndex = Number(payload.chunk_index)
  const totalChunks = Number(payload.total_chunks)
  if (!Number.isInteger(chunkIndex) || chunkIndex < 0 || chunkIndex >= transfer.totalChunks || totalChunks !== transfer.totalChunks) {
    updateFileTransferStatus("文件：收到无效分块，已忽略")
    sendFileTransferResultToRemote({
      fileId,
      applied: false,
      name: transfer.name,
      bytes: transfer.size,
      sha256: transfer.sha256,
      errorDetail: "invalid file transfer chunk payload",
    })
    return
  }
  try {
    transfer.chunks.set(chunkIndex, base64ToUint8Array(payload.data_base64))
    updateFileTransferStatus(`文件：接收 ${transfer.name} ${transfer.chunks.size}/${transfer.totalChunks}`)
  } catch (error) {
    const detail = error?.message || "unknown"
    updateFileTransferStatus(`文件：分块解码失败 ${detail}`)
    sendFileTransferResultToRemote({
      fileId,
      applied: false,
      name: transfer.name,
      bytes: transfer.size,
      sha256: transfer.sha256,
      errorDetail: detail,
    })
  }
}

async function handleIncomingFileTransferComplete(msg) {
  if (!sessionToolMessageMatchesCurrentSession(msg, "文件")) {
    return
  }
  const payload = msg.payload || {}
  const fileId = `${payload.file_id || ""}`.trim()
  const transfer = state.incomingFileTransfers.get(fileId)
  if (!transfer) {
    updateFileTransferStatus("文件：完成消息没有对应文件")
    sendFileTransferResultToRemote({
      fileId,
      applied: false,
      errorDetail: "file complete arrived before transfer start",
    })
    return
  }
  if (transfer.chunks.size !== transfer.totalChunks) {
    updateFileTransferStatus(`文件：${transfer.name} 分块未收齐 ${transfer.chunks.size}/${transfer.totalChunks}`)
    sendFileTransferResultToRemote({
      fileId,
      applied: false,
      name: transfer.name,
      bytes: transfer.size,
      sha256: transfer.sha256,
      errorDetail: `missing chunks ${transfer.chunks.size}/${transfer.totalChunks}`,
    })
    return
  }
  const chunks = []
  let totalSize = 0
  for (let index = 0; index < transfer.totalChunks; index += 1) {
    const chunk = transfer.chunks.get(index)
    if (!chunk) {
      updateFileTransferStatus(`文件：${transfer.name} 缺少第 ${index + 1} 块`)
      sendFileTransferResultToRemote({
        fileId,
        applied: false,
        name: transfer.name,
        bytes: transfer.size,
        sha256: transfer.sha256,
        errorDetail: `missing chunk ${index + 1}`,
      })
      return
    }
    totalSize += chunk.byteLength
    if (totalSize > SESSION_FILE_MAX_BYTES) {
      state.incomingFileTransfers.delete(fileId)
      updateFileTransferStatus("文件：超过 64MB，已丢弃")
      sendFileTransferResultToRemote({
        fileId,
        applied: false,
        name: transfer.name,
        bytes: totalSize,
        sha256: transfer.sha256,
        errorDetail: "file exceeds 64MB",
      })
      return
    }
    chunks.push(chunk)
  }
  if (transfer.size >= 0 && totalSize !== transfer.size) {
    state.incomingFileTransfers.delete(fileId)
    updateFileTransferStatus(`文件：${transfer.name} 大小不匹配，已丢弃`)
    sendFileTransferResultToRemote({
      fileId,
      applied: false,
      name: transfer.name,
      bytes: totalSize,
      sha256: transfer.sha256,
      errorDetail: `size mismatch expected=${transfer.size} actual=${totalSize}`,
    })
    return
  }
  try {
    const allBytes = concatUint8Chunks(chunks, totalSize)
    const expectedSha256 = normalizeSha256(payload.sha256) || transfer.sha256
    if (expectedSha256) {
      const actualSha256 = await sha256HexOfBytes(allBytes)
      if (actualSha256 !== expectedSha256) {
        state.incomingFileTransfers.delete(fileId)
        updateFileTransferStatus(`文件：${transfer.name} 哈希不匹配，已丢弃`)
        appendLog(`文件哈希不匹配：expected=${expectedSha256} actual=${actualSha256}`)
        sendFileTransferResultToRemote({
          fileId,
          applied: false,
          name: transfer.name,
          bytes: totalSize,
          sha256: actualSha256,
          errorDetail: "sha256 mismatch",
        })
        return
      }
    }
    const saved = isTauri()
      ? await invoke("desktop_save_session_file", {
        name: transfer.name,
        dataBase64: uint8ArrayToBase64(allBytes),
      })
      : downloadIncomingFileInBrowser(transfer, chunks, totalSize)
    state.incomingFileTransfers.delete(fileId)
    const savedPath = `${saved?.path || ""}`.trim()
    const savedName = `${saved?.name || transfer.name}`.trim() || transfer.name
    updateFileTransferStatus(
      savedPath
        ? `文件：已保存 ${savedName} (${formatBytes(totalSize)})`
        : `文件：已接收 ${savedName} (${formatBytes(totalSize)})`,
    )
    if (savedPath) {
      appendLog(`文件保存路径：${savedPath}`)
    }
    sendFileTransferResultToRemote({
      fileId,
      applied: true,
      name: savedName,
      bytes: totalSize,
      sha256: expectedSha256 || transfer.sha256,
      location: savedPath,
    })
  } catch (error) {
    state.incomingFileTransfers.delete(fileId)
    const detail = error?.message || "unknown"
    updateFileTransferStatus(`文件：保存失败 ${detail}`)
    sendFileTransferResultToRemote({
      fileId,
      applied: false,
      name: transfer.name,
      bytes: totalSize,
      sha256: transfer.sha256,
      errorDetail: detail,
    })
  }
}

function sendFileTransferResultToRemote({
  fileId,
  applied,
  name = "",
  bytes = 0,
  sha256 = "",
  location = "",
  errorDetail = "",
} = {}) {
  const sessionId = currentSessionToolSessionId("文件")
  const cleanFileId = `${fileId || ""}`.trim()
  if (!sessionId || !cleanFileId) {
    return false
  }
  // 作者: long；文件传输的完成消息只表示发送端分块发完，result 才表示接收端已完成校验并保存到本机。
  return sendEnvelope("file.transfer.result", {
    file_id: cleanFileId,
    applied: Boolean(applied),
    name: sanitizeRemoteFileName(name),
    bytes: Math.max(0, Number(bytes) || 0),
    sha256: normalizeSha256(sha256),
    location: `${location || ""}`.slice(0, 512),
    error_detail: applied ? "" : (`${errorDetail || "file receive failed"}`).slice(0, 512),
    created_at: Date.now(),
  }, sessionId, { log: false })
}

function handleIncomingFileTransferResult(msg) {
  if (!sessionToolMessageMatchesCurrentSession(msg, "文件")) {
    return
  }
  const payload = msg.payload || {}
  const applied = Boolean(payload.applied)
  const name = sanitizeRemoteFileName(payload.name)
  const bytes = Math.max(0, Number(payload.bytes || 0) || 0)
  const location = `${payload.location || ""}`.trim()
  const detail = `${payload.error_detail || ""}`.trim()
  updateFileTransferStatus(
    applied
      ? `文件：对端已保存 ${name} (${formatBytes(bytes)})`
      : `文件：对端保存失败 ${detail || "unknown"}`,
  )
  if (applied && location) {
    appendLog(`文件：对端保存位置 ${location}`)
  }
}

function currentVerificationCode() {
  return state.token
    ? (state.token.replace(/[^a-zA-Z0-9]/g, "").slice(0, 10).toUpperCase() || state.token)
    : ""
}

function deriveDevicesUrl(wsUrl) {
  const normalized = typeof wsUrl === "string" ? wsUrl.trim() : ""
  if (!normalized) {
    return ""
  }

  try {
    const url = new URL(normalized)
    if (url.protocol === "ws:") {
      url.protocol = "http:"
    } else if (url.protocol === "wss:") {
      url.protocol = "https:"
    }

    const trimmedPath = url.pathname.replace(/\/+$/, "")
    if (!trimmedPath) {
      url.pathname = "/devices"
    } else {
      const parts = trimmedPath.split("/")
      const lastIndex = parts.length - 1
      const lastSegment = parts[lastIndex] || ""
      if (lastSegment === "ws") {
        parts[lastIndex] = "devices"
        url.pathname = parts.join("/")
      } else {
        url.pathname = `${trimmedPath}/devices`
      }
    }

    url.search = ""
    url.hash = ""
    return url.toString()
  } catch {
    return ""
  }
}

function deriveRelayHttpUrl(wsUrl, endpoint) {
  const normalized = typeof wsUrl === "string" ? wsUrl.trim() : ""
  const normalizedEndpoint = `/${`${endpoint || ""}`.replace(/^\/+/, "")}`
  if (!normalized || normalizedEndpoint === "/") {
    return ""
  }

  try {
    const url = new URL(normalized)
    if (url.protocol === "ws:") {
      url.protocol = "http:"
    } else if (url.protocol === "wss:") {
      url.protocol = "https:"
    }

    const trimmedPath = url.pathname.replace(/\/+$/, "")
    if (!trimmedPath || trimmedPath === "/") {
      url.pathname = normalizedEndpoint
    } else {
      const parts = trimmedPath.split("/")
      const lastIndex = parts.length - 1
      const lastSegment = parts[lastIndex] || ""
      if (lastSegment === "ws") {
        parts[lastIndex] = normalizedEndpoint.slice(1)
        url.pathname = parts.join("/")
      } else {
        url.pathname = `${trimmedPath}${normalizedEndpoint}`
      }
    }

    url.search = ""
    url.hash = ""
    return url.toString()
  } catch {
    return ""
  }
}

function deriveE2EProofUrl(wsUrl) {
  return deriveRelayHttpUrl(wsUrl, "/e2e-proof")
}

function inferDeviceKind(device = {}) {
  const source = `${device.device_name || ""} ${device.platform || ""} ${device.device_id || ""}`.toLowerCase()
  if (source.includes("android") || source.includes("phone") || source.includes("mobile") || source.includes("redmi") || source.includes("pixel") || source.includes("huawei") || source.includes("xiaomi")) {
    return "mobile"
  }
  if (source.includes("mac") || source.includes("desktop") || source.includes("windows") || source.includes("pc") || source.includes("laptop") || source.includes("book")) {
    return "desktop"
  }
  return device.role === "agent" ? "target" : "other"
}

function platformDisplayName(platform) {
  const normalized = `${platform || ""}`.toLowerCase()
  if (normalized.includes("android")) {
    return "安卓"
  }
  if (normalized.includes("mac")) {
    return "苹果电脑"
  }
  if (normalized.includes("win")) {
    return "视窗系统"
  }
  if (normalized.includes("linux")) {
    return "Linux 系统"
  }
  return platform || "未知平台"
}

function deviceKindLabel(kind) {
  switch (kind) {
    case "desktop":
      return "电脑"
    case "mobile":
      return "手机"
    case "target":
      return "受控端"
    default:
      return "其他"
  }
}

function roleDisplayName(role) {
  if (role === "agent") {
    return "受控端"
  }
  if (role === "controller") {
    return "控制端"
  }
  return role || "未知"
}

function statusDisplayName(status) {
  switch (status) {
    case "online":
      return "在线"
    case "busy":
      return "忙碌"
    case "offline":
      return "离线"
    default:
      return status || "未知"
  }
}

function statusTone(status) {
  switch (status) {
    case "online":
      return "success"
    case "busy":
      return "warn"
    case "offline":
      return "muted"
    default:
      return "muted"
  }
}

function formatRelativeTime(value) {
  if (!value) {
    return "未同步"
  }
  const timestamp = new Date(value).getTime()
  if (!Number.isFinite(timestamp)) {
    return "未同步"
  }
  const diff = Math.max(0, Date.now() - timestamp)
  if (diff < 60_000) {
    return "刚刚"
  }
  const minutes = Math.round(diff / 60_000)
  if (minutes < 60) {
    return `${minutes} 分钟前`
  }
  const hours = Math.round(minutes / 60)
  if (hours < 24) {
    return `${hours} 小时前`
  }
  const days = Math.round(hours / 24)
  return `${days} 天前`
}

function formatSessionStartTime(value) {
  const timestamp = Number(value)
  if (!Number.isFinite(timestamp) || timestamp <= 0) {
    return "-"
  }
  try {
    return new Date(timestamp).toLocaleString("zh-CN", { hour12: false })
  } catch {
    return "-"
  }
}

function formatSessionDuration(valueMs) {
  const durationMs = Number(valueMs)
  if (!Number.isFinite(durationMs) || durationMs < 0) {
    return "-"
  }
  const totalSeconds = Math.floor(durationMs / 1000)
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`
}

function formatProofTimestamp(value) {
  if (!value) {
    return "-"
  }
  const timestamp = new Date(value).getTime()
  if (!Number.isFinite(timestamp)) {
    return "-"
  }
  try {
    return new Date(timestamp).toLocaleTimeString("zh-CN", { hour12: false })
  } catch {
    return "-"
  }
}

const LEGACY_ANDROID_RUNTIME_DEVICE_ID_PATTERN = /^android-[0-9a-f]{10,13}$/i

function isLegacyAndroidRuntimeDevice(device) {
  const deviceId = `${device?.device_id || ""}`.trim()
  const platform = `${device?.platform || ""}`.trim().toLowerCase()
  const role = `${device?.role || ""}`.trim().toLowerCase()
  return platform.includes("android") && role === "controller" && LEGACY_ANDROID_RUNTIME_DEVICE_ID_PATTERN.test(deviceId)
}

function androidControllerGhostGroupKey(device) {
  const platform = `${device?.platform || ""}`.trim().toLowerCase()
  const role = `${device?.role || ""}`.trim().toLowerCase()
  if (!platform.includes("android") || role !== "controller") {
    return ""
  }
  const userId = `${device?.user_id || ""}`.trim().toLowerCase()
  const displayName = `${device?.display_name || device?.device_name || ""}`.trim().toLowerCase()
  return `${platform}|${role}|${userId}|${displayName}`
}

function lastSeenTimestamp(device) {
  const timestamp = new Date(device?.last_seen_at || "").getTime()
  return Number.isFinite(timestamp) ? timestamp : 0
}

function chooseAndroidControllerRepresentative(items) {
  return items.reduce((best, item) => {
    if (!best) {
      return item
    }
    const bestIsStable = !isLegacyAndroidRuntimeDevice(best.device)
    const itemIsStable = !isLegacyAndroidRuntimeDevice(item.device)
    if (bestIsStable !== itemIsStable) {
      return itemIsStable ? item : best
    }
    const bestLastSeen = lastSeenTimestamp(best.device)
    const itemLastSeen = lastSeenTimestamp(item.device)
    if (bestLastSeen !== itemLastSeen) {
      return itemLastSeen > bestLastSeen ? item : best
    }
    return `${item.device.device_id || ""}`.localeCompare(`${best.device.device_id || ""}`) > 0 ? item : best
  }, null)
}

function collapseLegacyAndroidControllerGhosts(devices) {
  const groups = new Map()
  const passthrough = []
  devices.forEach((device, index) => {
    const key = androidControllerGhostGroupKey(device)
    if (!key) {
      passthrough.push({ device, index })
      return
    }
    const group = groups.get(key) || { items: [], hasLegacyRuntimeId: false }
    group.items.push({ device, index })
    group.hasLegacyRuntimeId = group.hasLegacyRuntimeId || isLegacyAndroidRuntimeDevice(device)
    groups.set(key, group)
  })

  const selected = [...passthrough]
  for (const group of groups.values()) {
    if (group.items.length > 1 && group.hasLegacyRuntimeId) {
      // 作者: long；旧安卓端把启动时间戳写进 device_id，同一真机反复重连会留下多条在线残影，列表只展示稳定 ID 或最新旧 ID。
      const representative = chooseAndroidControllerRepresentative(group.items)
      if (representative) {
        selected.push(representative)
      }
      continue
    }
    selected.push(...group.items)
  }
  return selected
    .sort((left, right) => left.index - right.index)
    .map((item) => item.device)
}

function normalizeRelayDevice(device) {
  if (!device || typeof device !== "object") {
    return null
  }
  const deviceId = `${device.device_id || ""}`.trim()
  if (!deviceId) {
    return null
  }
  const deviceName = `${device.device_name || ""}`.trim()
  const platform = `${device.platform || ""}`.trim()
  const role = normalizeDeviceRole(device.role)
  const capabilities = device.capabilities && typeof device.capabilities === "object"
    ? device.capabilities
    : {}
  const canControl = typeof capabilities.can_control === "boolean"
    ? capabilities.can_control
    : role === "controller"
  const canBeControlled = typeof capabilities.can_be_controlled === "boolean"
    ? capabilities.can_be_controlled
    : role === "agent"
  const status = `${device.status || "unknown"}`.trim().toLowerCase() || "unknown"
  const kind = inferDeviceKind({ ...device, device_id: deviceId, device_name: deviceName, platform, role })
  return {
    device_id: deviceId,
    device_name: deviceName,
    user_id: `${device.user_id || ""}`.trim(),
    platform,
    platform_label: platformDisplayName(platform),
    role,
    role_label: roleDisplayName(role),
    capabilities: {
      can_control: canControl,
      can_be_controlled: canBeControlled,
    },
    status,
    status_label: statusDisplayName(status),
    status_tone: statusTone(status),
    last_seen_at: `${device.last_seen_at || ""}`.trim(),
    last_seen_label: formatRelativeTime(device.last_seen_at),
    display_name: deviceName || deviceId,
    is_self: deviceId === state.deviceId,
    is_target_candidate: canBeControlled && status !== "offline" && deviceId !== state.deviceId,
    kind,
    kind_label: deviceKindLabel(kind),
  }
}

function normalizeRelayDevicesPayload(payload) {
  const rawDevices = Array.isArray(payload)
    ? payload
    : Array.isArray(payload?.devices)
      ? payload.devices
      : []
  return rawDevices
    .map((device) => normalizeRelayDevice(device))
    .filter(Boolean)
    .sort((left, right) => {
      if (left.is_self !== right.is_self) {
        return left.is_self ? -1 : 1
      }
      if (left.is_target_candidate !== right.is_target_candidate) {
        return left.is_target_candidate ? -1 : 1
      }
      if (left.status === "busy" && right.status !== "busy") {
        return -1
      }
      if (right.status === "busy" && left.status !== "busy") {
        return 1
      }
      return left.display_name.localeCompare(right.display_name, "zh-CN")
    })
}

function buildOnlineRelayDevices() {
  const devices = Array.isArray(state.relayDevices) ? state.relayDevices : []
  return collapseLegacyAndroidControllerGhosts(devices.filter((device) => device.status !== "offline"))
}

function relayDevicesFingerprint(devices = []) {
  return devices
    .map((device) => [
      device.device_id || "",
      device.status || "",
      device.role || "",
      device.display_name || "",
      device.platform_label || "",
      device.kind || "",
      device.is_self ? "1" : "0",
      device.is_target_candidate ? "1" : "0",
    ].join("|"))
    .join("||")
}

function applyRelayDevicesSnapshot(rawPayload, options = {}) {
  const nextDevices = normalizeRelayDevicesPayload(rawPayload)
  const prevFingerprint = relayDevicesFingerprint(state.relayDevices)
  const nextFingerprint = relayDevicesFingerprint(nextDevices)
  const devicesChanged = prevFingerprint !== nextFingerprint
  state.relayDevices = nextDevices
  state.devicesLoaded = true
  state.lastDevicesSyncAt = Date.now()
  const nextStatusMessage = state.relayDevices.length
    ? `已同步 ${state.relayDevices.length} 台设备`
    : "暂无在线设备，请先完成注册和心跳。"
  const statusChanged = state.devicesStatusMessage !== nextStatusMessage
  state.devicesStatusMessage = nextStatusMessage

  if (options.log) {
    const source = `${options.source || "snapshot"}`.trim()
    appendLog(`设备列表已更新：${state.relayDevices.length} 台（来源：${source}）`)
  }
  if (!options.silent || devicesChanged || statusChanged) {
    render()
  }
  return {
    devicesChanged,
    statusChanged,
  }
}

async function refreshRelayDevices(options = {}) {
  const devicesUrl = deriveDevicesUrl(state.wsUrl)
  state.lastDevicesUrl = devicesUrl

  if (!devicesUrl) {
    state.relayDevices = []
    state.devicesLoaded = false
    state.devicesLoading = false
    state.devicesStatusMessage = "请先填写有效中继地址。"
    render()
    return []
  }

  if (!options.force && state.devicesLoading) {
    return state.relayDevices
  }

  state.devicesLoading = true
  if (!options.silent && !state.devicesLoaded) {
    state.devicesStatusMessage = "正在同步设备列表..."
    render()
  }
  try {
    const response = await fetch(devicesUrl, {
      method: "GET",
      headers: { Accept: "application/json" },
    })
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    const payload = await response.json()
    applyRelayDevicesSnapshot(payload, {
      source: "http_snapshot",
      silent: options.silent,
      log: false,
    })
    if (!options.silent) {
      appendLog(`已同步设备列表 ${state.relayDevices.length} 台 (${devicesUrl})`)
    }
  } catch (error) {
    state.relayDevices = []
    state.devicesLoaded = false
    state.devicesStatusMessage = `同步设备列表失败：${error?.message || "未知错误"}`
    if (!options.silent) {
      appendLog(state.devicesStatusMessage)
    }
    render()
  } finally {
    state.devicesLoading = false
    if (!options.silent) {
      render()
    }
  }

  return state.relayDevices
}

async function refreshE2EProofSnapshot(options = {}) {
  const proofUrl = deriveE2EProofUrl(state.wsUrl)
  if (!proofUrl) {
    state.e2eProofSnapshot = null
    state.e2eProofError = "Enter a valid relay URL before checking E2E proof."
    state.e2eProofLoading = false
    if (!options.silent) {
      appendLog(state.e2eProofError)
      render()
    }
    return null
  }
  if (!options.force && state.e2eProofLoading) {
    return state.e2eProofSnapshot
  }

  state.e2eProofLoading = true
  state.e2eProofError = ""
  if (!options.silent) {
    render()
  }
  try {
    const response = await fetch(proofUrl, {
      method: "GET",
      headers: { Accept: "application/json" },
    })
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    const snapshot = await response.json()
    state.e2eProofSnapshot = snapshot
    state.lastE2EProofSyncAt = Date.now()
    if (!options.silent) {
      appendLog(`E2E proof refreshed: ${snapshot?.target_routes_complete ?? 0}/${snapshot?.target_routes_total ?? 0}`)
    }
    return snapshot
  } catch (error) {
    state.e2eProofSnapshot = null
    state.e2eProofError = `E2E proof refresh failed: ${error?.message || "unknown error"}`
    if (!options.silent) {
      appendLog(state.e2eProofError)
    }
    return null
  } finally {
    state.e2eProofLoading = false
    if (!options.silent) {
      render()
    }
  }
}

async function resetE2EProofSnapshot(options = {}) {
  const proofUrl = deriveE2EProofUrl(state.wsUrl)
  if (!proofUrl) {
    state.e2eProofSnapshot = null
    state.e2eProofError = "Enter a valid relay URL before resetting E2E proof."
    state.e2eProofResetting = false
    if (!options.silent) {
      appendLog(state.e2eProofError)
      render()
    }
    return null
  }
  if (state.e2eProofResetting) {
    return state.e2eProofSnapshot
  }

  state.e2eProofResetting = true
  state.e2eProofError = ""
  if (!options.silent) {
    render()
  }
  try {
    const response = await fetch(proofUrl, {
      method: "DELETE",
      headers: { Accept: "application/json" },
    })
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    const snapshot = await response.json()
    state.e2eProofSnapshot = snapshot
    state.lastE2EProofSyncAt = Date.now()
    if (!options.silent) {
      appendLog("E2E proof state reset")
    }
    return snapshot
  } catch (error) {
    state.e2eProofError = `E2E proof reset failed: ${error?.message || "unknown error"}`
    if (!options.silent) {
      appendLog(state.e2eProofError)
    }
    return null
  } finally {
    state.e2eProofResetting = false
    if (!options.silent) {
      render()
    }
  }
}

async function selectRelayDevice(deviceId, options = {}) {
  const normalized = `${deviceId || ""}`.trim()
  if (!normalized) {
    return
  }
  const selected = state.relayDevices.find((device) => device.device_id === normalized)
  if (!selected) {
    appendLog(`未找到目标设备 ${normalized}`)
    render()
    return
  }
  if (!selected.is_target_candidate) {
    appendLog(`设备 ${selected.display_name || normalized} 当前不可用于协助`)
    render()
    return
  }

  state.targetDeviceId = normalized
  appendLog(`已选择目标设备 ${selected.display_name || normalized}`)
  if (options.switchPage !== false) {
    state.currentPage = "devices"
  }

  if (!connectionReady()) {
    void connect({ source: "device-list" })
    return
  }
  if (!state.token) {
    sendRegister({ auto: true })
    render()
    return
  }
  if (!state.presenceReady) {
    sendHeartbeat({ auto: true })
    render()
    return
  }
  if (!state.sessionId && selected.status !== "offline") {
    requestSession()
    return
  }

  render()
}

function getFirstControlValue(ids) {
  for (const id of ids) {
    const element = document.getElementById(id)
    if (element && typeof element.value === "string") {
      return element.value.trim()
    }
  }
  return ""
}

function emptyHostBridgeStatus() {
  return {
    input_count: 0,
    last_input_type: "",
    last_input_summary: "",
    last_session_id: "",
    last_trace_id: "",
    last_executor: "",
    last_status_code: "",
    last_status_detail: "",
    last_error_code: "",
    last_error_detail: "",
  }
}

function emptyRemoteInputResult() {
  return {
    input_type: "",
    input_category: "",
    input_trace_id: "",
    applied: false,
    executor: "",
    status_code: "",
    status_detail: "",
    error_code: "",
    error_detail: "",
    summary: "",
    input_count: 0,
    target_device_id: "",
  }
}

function emptyRemoteInputCoverage() {
  return {
    click: false,
    drag: false,
    keyboard: false,
    wheel: false,
  }
}

function emptyCaptureCapabilities() {
  return {
    backend: "",
    default_codec: "jpeg-frame-stream",
    support_level: "unknown",
    support_detail: "",
    requires_permission: false,
    permission_capability: "screen_recording",
    supports_permission_check: false,
    supports_permission_request: false,
    supports_source_listing: false,
    supports_frame_streaming: false,
    supports_pause_resume: false,
    supported_source_kinds: [],
  }
}

function emptyHostInputCapabilities() {
  return {
    backend: "",
    support_level: "unknown",
    support_detail: "",
    requires_permission: false,
    permission_capability: "accessibility",
    permission: {
      capability: "accessibility",
      status: "unknown",
      can_request: false,
      detail: "",
    },
    supports_pointer_input: false,
    supports_pointer_move: false,
    supported_pointer_buttons: [],
    supports_keyboard_input: false,
    supports_wheel_input: false,
  }
}

function emptyPlatformCapabilities() {
  return {
    platform: "unknown",
    capture: emptyCaptureCapabilities(),
    host_input: emptyHostInputCapabilities(),
  }
}

function emptyNativeSenderCapabilities() {
  return {
    supported: false,
    support_level: "unknown",
    support_detail: "",
    blocker_code: "",
    capture_backend: "",
    capture_support_level: "",
  }
}

function emptyNativeSenderStatus() {
  return {
    lifecycle: "idle",
    session_id: "",
    dry_run: false,
    signaling_state: "idle",
    started_at_ms: 0,
    stopped_at_ms: 0,
    last_signal_type: "",
    last_signal_direction: "",
    last_signal_trace_id: "",
    last_signal_payload_bytes: 0,
    signal_count: 0,
    inbound_signal_count: 0,
    outbound_signal_count: 0,
    local_offer_count: 0,
    local_answer_count: 0,
    local_candidate_count: 0,
    remote_offer_count: 0,
    remote_answer_count: 0,
    remote_candidate_count: 0,
    restart_ice_count: 0,
    local_restart_ice_count: 0,
    remote_restart_ice_count: 0,
    remote_answer_sdp_len: 0,
    remote_offer_sdp_len: 0,
    last_local_candidate_type: "-",
    last_local_candidate_protocol: "-",
    last_remote_candidate_type: "-",
    last_remote_candidate_protocol: "-",
    candidate_path: "-",
    candidate_tier: "-",
    media_probe_running: false,
    media_probe_frame_count: 0,
    media_probe_total_bytes: 0,
    media_probe_last_frame_ts_ms: 0,
    media_probe_last_width: 0,
    media_probe_last_height: 0,
    media_probe_fps: 0,
    media_probe_kbps: 0,
    webrtc_outbound_stats_available: false,
    webrtc_outbound_reports: 0,
    webrtc_outbound_bytes_sent: 0,
    webrtc_outbound_packets_sent: 0,
    webrtc_outbound_header_bytes_sent: 0,
    webrtc_outbound_kbps: 0,
    webrtc_outbound_fps: 0,
    webrtc_outbound_rtt_ms: -1,
    webrtc_outbound_updated_at_ms: 0,
    shadow_runtime_ready: false,
    shadow_track_bound: false,
    shadow_last_apply_action: "",
    last_error_code: "",
    last_error_detail: "",
    updated_at_ms: 0,
  }
}

function emptyCaptureStatus() {
  const capabilities = emptyCaptureCapabilities()
  return {
    lifecycle: "idle",
    backend: capabilities.backend,
    capabilities,
    active_source: null,
    config: {
      max_width: CAPTURE_MAX_WIDTH,
      max_height: CAPTURE_MAX_HEIGHT,
      max_fps: NATIVE_CAPTURE_FALLBACK_MAX_FPS,
      codec: NATIVE_CAPTURE_FALLBACK_CODEC,
    },
    permission: {
      capability: "screen_recording",
      status: "unknown",
      can_request: false,
      detail: "",
    },
    supports_frame_streaming: capabilities.supports_frame_streaming,
    supports_pause_resume: capabilities.supports_pause_resume,
    last_error_code: "",
    last_error_detail: "",
    last_transition_at: 0,
  }
}

function emptyWindowsSelfTestReport() {
  return {
    platform: "",
    ok: false,
    duration_ms: 0,
    capture_backend: "",
    host_input_backend: "",
    source_id: "",
    source_title: "",
    frame_width: 0,
    frame_height: 0,
    frame_mime_type: "",
    frame_bytes: 0,
    stream_endpoint: "",
    native_sender_support_level: "",
    native_sender_lifecycle: "",
    native_sender_signal_count: 0,
    native_sender_offer_count: 0,
    native_sender_probe_frame_count: 0,
    native_sender_probe_total_bytes: 0,
    native_sender_last_error_code: "",
    native_sender_last_error_detail: "",
    checks: [],
  }
}

function emptyAgentAdaptiveState() {
  return {
    sessionId: "",
    sessionStartedAt: 0,
    profileIndex: AGENT_ADAPTIVE_DEFAULT_PROFILE_INDEX,
    lastSwitchAt: 0,
    warmupLogged: false,
    warmupReleasedLogged: false,
    stableUpgradeSamples: 0,
    lastSampleAt: 0,
    lastFramesSent: 0,
    lastBytesSent: 0,
    lastAppliedSignature: "",
    lastAppliedReason: "",
    mediaPath: "unknown",
    applying: false,
    bridgeProducedFrames: 0,
    lastBridgeFrames: 0,
    ewmaSendFps: -1,
    ewmaSendKbps: -1,
    ewmaBridgeFps: -1,
    ewmaRttMs: -1,
    badSampleStreak: 0,
    severeBadSampleStreak: 0,
    sampleSeq: 0,
    sceneMode: "mixed",
    sceneDynamicSignalStreak: 0,
    sceneStaticSignalStreak: 0,
    sceneLastSwitchAt: 0,
    sceneSwitchCount: 0,
  }
}

function emptyAgentStatsSnapshot(sessionId = "") {
  return {
    sessionId: sessionId || "",
    updatedAt: 0,
    framesSent: 0,
    framesEncoded: 0,
    packetsSent: 0,
    bytesSent: 0,
    frameWidth: 0,
    frameHeight: 0,
    roundTripTimeMs: -1,
    remoteCandidateType: "-",
    candidatePath: "-",
    candidateTier: "-",
    qualityLimitationReason: "-",
    sendFps: -1,
    sendKbps: -1,
    bridgeFps: -1,
    adaptiveDecision: "hold",
    profileId: "",
    profileLabel: "",
    sceneMode: "mixed",
    sceneSwitches: 0,
    statsSource: "-",
  }
}

function resetAgentStatsSnapshot(sessionId = state.sessionId || "") {
  state.agentStatsSnapshot = emptyAgentStatsSnapshot(sessionId)
}

function emptyControllerStatsSnapshot(sessionId = "") {
  return {
    sessionId: sessionId || "",
    updatedAt: 0,
    framesDecoded: 0,
    bytesReceived: 0,
    renderFpsSampleSum: 0,
    renderFpsSampleCount: 0,
    recvKbpsSampleSum: 0,
    recvKbpsSampleCount: 0,
    rttMsSampleSum: 0,
    rttMsSampleCount: 0,
    renderFps: -1,
    recvKbps: -1,
    roundTripTimeMs: -1,
    candidatePath: "-",
    candidateTier: "-",
    qualityHint: "-",
  }
}

function resetControllerStatsSnapshot(sessionId = state.sessionId || "") {
  state.controllerStatsSnapshot = emptyControllerStatsSnapshot(sessionId)
}

function resetRemoteInputResultStats() {
  state.lastRemoteInputResult = emptyRemoteInputResult()
  state.remoteInputResultCount = 0
  state.remoteInputResultAppliedCount = 0
  state.remoteInputResultFailedCount = 0
  state.remoteInputAppliedCategories = emptyRemoteInputCoverage()
}

function resetLiveE2EProofReportState() {
  state.lastLiveE2EProofReportAt = 0
  state.lastLiveE2EProofReportKey = ""
}

function emptyBridgeModeStats(sessionId = "") {
  return {
    sessionId: sessionId || "",
    startedAt: Date.now(),
    endedAt: 0,
    emitted: false,
    total: 0,
    counts: {
      direct_track: 0,
      mjpeg_stream_direct: 0,
      mjpeg_stream_fetch_generator: 0,
      mjpeg_stream_fetch_canvas: 0,
      mjpeg_stream_image_canvas: 0,
      mjpeg_stream_canvas: 0,
      unknown: 0,
    },
    pipeline: {
      raw_attempts: 0,
      raw_success: 0,
      raw_failed: 0,
      jpeg_retry_attempted: 0,
      jpeg_retry_success: 0,
      jpeg_retry_failed: 0,
    },
    fetchSkips: {},
  }
}

function resetBridgeModeStats(sessionId = state.sessionId || "") {
  state.bridgeModeStats = emptyBridgeModeStats(sessionId)
}

function recordBridgeModeUsage(mode, options = {}) {
  const normalizedMode = `${mode || ""}`.trim() || "unknown"
  const sessionId = `${options.sessionId || state.sessionId || ""}`.trim()
  if (!state.bridgeModeStats || state.bridgeModeStats.sessionId !== sessionId) {
    resetBridgeModeStats(sessionId)
  }
  const stats = state.bridgeModeStats
  if (!Object.prototype.hasOwnProperty.call(stats.counts, normalizedMode)) {
    stats.counts[normalizedMode] = 0
  }
  stats.counts[normalizedMode] += 1
  stats.total += 1
  stats.endedAt = 0
  stats.emitted = false

  traceLog("native.bridge.mode.hit", {
    session_id: sessionId || "-",
    mode: normalizedMode,
    count: stats.counts[normalizedMode],
    total: stats.total,
  }, { console: false })
}

function summarizeBridgeModeStats(stats = state.bridgeModeStats) {
  if (!stats || !stats.counts || typeof stats.counts !== "object") {
    return ""
  }
  const parts = Object.entries(stats.counts)
    .filter(([, count]) => Number(count) > 0)
    .sort((left, right) => Number(right[1]) - Number(left[1]))
    .map(([mode, count]) => `${mode}:${count}`)
  return parts.join(", ")
}

function recordBridgePipelineStats(patch = {}, options = {}) {
  const sessionId = `${options.sessionId || state.sessionId || ""}`.trim()
  if (!state.bridgeModeStats || state.bridgeModeStats.sessionId !== sessionId) {
    resetBridgeModeStats(sessionId)
  }
  const stats = state.bridgeModeStats
  if (!stats.pipeline || typeof stats.pipeline !== "object") {
    stats.pipeline = {
      raw_attempts: 0,
      raw_success: 0,
      raw_failed: 0,
      jpeg_retry_attempted: 0,
      jpeg_retry_success: 0,
      jpeg_retry_failed: 0,
    }
  }
  for (const [key, value] of Object.entries(patch || {})) {
    const delta = Number(value)
    if (!Number.isFinite(delta) || delta === 0) {
      continue
    }
    if (!Object.prototype.hasOwnProperty.call(stats.pipeline, key)) {
      stats.pipeline[key] = 0
    }
    stats.pipeline[key] = Math.max(0, Number(stats.pipeline[key] || 0) + delta)
  }
  stats.endedAt = 0
  stats.emitted = false
}

function recordBridgeFetchSkipReason(reason, options = {}) {
  const sessionId = `${options.sessionId || state.sessionId || ""}`.trim()
  if (!state.bridgeModeStats || state.bridgeModeStats.sessionId !== sessionId) {
    resetBridgeModeStats(sessionId)
  }
  const stats = state.bridgeModeStats
  if (!stats.fetchSkips || typeof stats.fetchSkips !== "object") {
    stats.fetchSkips = {}
  }
  const normalizedReason = `${reason || ""}`.trim() || "unknown"
  if (!Object.prototype.hasOwnProperty.call(stats.fetchSkips, normalizedReason)) {
    stats.fetchSkips[normalizedReason] = 0
  }
  stats.fetchSkips[normalizedReason] += 1
  stats.endedAt = 0
  stats.emitted = false
}

function summarizeBridgePipelineStats(stats = state.bridgeModeStats) {
  const pipeline = stats?.pipeline
  if (!pipeline || typeof pipeline !== "object") {
    return "-"
  }
  const fields = [
    "raw_attempts",
    "raw_success",
    "raw_failed",
    "jpeg_retry_attempted",
    "jpeg_retry_success",
    "jpeg_retry_failed",
  ]
  return fields.map((key) => `${key}:${Number(pipeline[key] || 0)}`).join(", ")
}

function summarizeBridgeFetchSkips(stats = state.bridgeModeStats) {
  const fetchSkips = stats?.fetchSkips
  if (!fetchSkips || typeof fetchSkips !== "object") {
    return "-"
  }
  const parts = Object.entries(fetchSkips)
    .filter(([, count]) => Number(count) > 0)
    .sort((left, right) => Number(right[1]) - Number(left[1]))
    .map(([reason, count]) => `${reason}:${count}`)
  if (parts.length === 0) {
    return "-"
  }
  return parts.join(", ")
}

function ratioPercent(numerator, denominator) {
  const left = Number(numerator)
  const right = Number(denominator)
  if (!Number.isFinite(left) || !Number.isFinite(right) || right <= 0) {
    return -1
  }
  return Number(((left / right) * 100).toFixed(2))
}

function countBridgeCanvasHits(stats = state.bridgeModeStats) {
  const counts = stats?.counts || {}
  return Number(counts.mjpeg_stream_image_canvas || 0)
    + Number(counts.mjpeg_stream_canvas || 0)
    + Number(counts.mjpeg_stream_fetch_canvas || 0)
}

function summarizeBridgePipelineRatios(stats = state.bridgeModeStats) {
  const pipeline = stats?.pipeline || {}
  const rawAttempts = Number(pipeline.raw_attempts || 0)
  const rawSuccess = Number(pipeline.raw_success || 0)
  const jpegRetryAttempted = Number(pipeline.jpeg_retry_attempted || 0)
  const jpegRetrySuccess = Number(pipeline.jpeg_retry_success || 0)
  const total = Number(stats?.total || 0)
  const canvasHits = countBridgeCanvasHits(stats)
  const rawSuccessRate = ratioPercent(rawSuccess, rawAttempts)
  const jpegRetrySuccessRate = ratioPercent(jpegRetrySuccess, jpegRetryAttempted)
  const canvasShare = ratioPercent(canvasHits, total)
  return [
    `raw_success_rate=${rawSuccessRate >= 0 ? `${rawSuccessRate}%` : "-"}`,
    `jpeg_retry_success_rate=${jpegRetrySuccessRate >= 0 ? `${jpegRetrySuccessRate}%` : "-"}`,
    `canvas_share=${canvasShare >= 0 ? `${canvasShare}%` : "-"}`,
  ].join(", ")
}

function inferBridgeCapabilityTier(stats = state.bridgeModeStats) {
  if (!stats || typeof stats !== "object") {
    return "-"
  }
  const counts = stats.counts || {}
  const fetchSkips = stats.fetchSkips || {}
  const total = Number(stats.total || 0)
  if (total <= 0) {
    return "-"
  }
  const noCanvasHits = Number(counts.direct_track || 0)
    + Number(counts.mjpeg_stream_direct || 0)
    + Number(counts.mjpeg_stream_fetch_generator || 0)
  const canvasHits = countBridgeCanvasHits(stats)
  const unsupportedTrackGeneratorHits = Number(fetchSkips.unsupported_track_generator || 0)
  if (unsupportedTrackGeneratorHits > 0 && noCanvasHits <= 0 && canvasHits > 0) {
    return "capability_blocked"
  }
  if (noCanvasHits > 0) {
    return "no_canvas_ready"
  }
  if (canvasHits > 0) {
    return "canvas_only_unknown"
  }
  return "unknown"
}

function detectRuntimeKernel() {
  const userAgent = `${navigator.userAgent || ""}`
  const chromiumMatch = userAgent.match(/Chrom(?:e|ium)\/([\d.]+)/i)
  const webkitMatch = userAgent.match(/AppleWebKit\/([\d.]+)/i)
  const geckoMatch = userAgent.match(/Gecko\/([\d.]+)/i)
  const firefoxMatch = userAgent.match(/Firefox\/([\d.]+)/i)
  let engine = "unknown"
  if (chromiumMatch && chromiumMatch[1]) {
    engine = `chromium/${chromiumMatch[1]}`
  } else if (firefoxMatch && firefoxMatch[1]) {
    engine = `firefox/${firefoxMatch[1]}`
  } else if (webkitMatch && webkitMatch[1]) {
    engine = `webkit/${webkitMatch[1]}`
  } else if (geckoMatch && geckoMatch[1]) {
    engine = `gecko/${geckoMatch[1]}`
  }
  const runtime = `${state.runtime || (tauriRuntime ? "tauri" : "browser")}`.trim() || "unknown"
  const shell = `${state.shellPlatform || "unknown"}`.trim() || "unknown"
  return `${runtime}|${shell}|${engine}`
}

function buildRuntimeCapabilitySignature() {
  const captureCapabilities = currentPlatformCaptureCapabilities()
  const hostInputCapabilities = currentPlatformHostInputCapabilities()
  const nativeSenderCapabilities = state.nativeSenderCapabilities || emptyNativeSenderCapabilities()
  const supportsFetch = typeof fetch === "function"
  const supportsMediaStream = typeof MediaStream !== "undefined"
  const supportsVideoFrame = typeof VideoFrame !== "undefined"
  const trackGeneratorSupport = resolveTrackGeneratorSupport()
  const supportsTrackGenerator = trackGeneratorSupport.available
  const supportsDisplayMedia = Boolean(navigator.mediaDevices?.getDisplayMedia)
  return [
    `fetch:${supportsFetch ? 1 : 0}`,
    `media_stream:${supportsMediaStream ? 1 : 0}`,
    `video_frame:${supportsVideoFrame ? 1 : 0}`,
    `track_generator:${supportsTrackGenerator ? 1 : 0}`,
    `track_generator_impl:${trackGeneratorSupport.name || "-"}`,
    `display_media:${supportsDisplayMedia ? 1 : 0}`,
    `native_sender:${nativeSenderCapabilities.supported ? 1 : 0}`,
    `native_sender_level:${nativeSenderCapabilities.support_level || "-"}`,
    `native_sender_blocker:${nativeSenderCapabilities.blocker_code || "-"}`,
    `native_streaming:${captureCapabilities.supports_frame_streaming ? 1 : 0}`,
    `native_pause_resume:${captureCapabilities.supports_pause_resume ? 1 : 0}`,
    `capture_backend:${captureCapabilities.backend || "-"}`,
    `capture_support:${captureCapabilities.support_level || "-"}`,
    `host_pointer:${hostInputCapabilities.supports_pointer_input ? 1 : 0}`,
    `host_keyboard:${hostInputCapabilities.supports_keyboard_input ? 1 : 0}`,
    `host_wheel:${hostInputCapabilities.supports_wheel_input ? 1 : 0}`,
  ].join("|")
}

function runtimeSignaturePayload() {
  const trackGeneratorSupport = resolveTrackGeneratorSupport()
  const nativeSenderCapabilities = state.nativeSenderCapabilities || emptyNativeSenderCapabilities()
  return {
    runtime_signature_version: 1,
    runtime_kernel: detectRuntimeKernel(),
    runtime_capability_signature: buildRuntimeCapabilitySignature(),
    runtime_engine: `${state.runtime || (tauriRuntime ? "tauri" : "browser")}`,
    runtime_shell_platform: `${state.shellPlatform || "-"}`,
    runtime_browser_platform: `${navigator.platform || "-"}`,
    runtime_user_agent: `${navigator.userAgent || "-"}`,
    runtime_cap_fetch: typeof fetch === "function",
    runtime_cap_media_stream: typeof MediaStream !== "undefined",
    runtime_cap_video_frame: typeof VideoFrame !== "undefined",
    runtime_cap_track_generator: trackGeneratorSupport.available,
    runtime_cap_display_media: Boolean(navigator.mediaDevices?.getDisplayMedia),
    runtime_cap_native_sender: nativeSenderCapabilities.supported,
    runtime_native_sender_support_level: nativeSenderCapabilities.support_level || "-",
    runtime_native_sender_blocker: nativeSenderCapabilities.blocker_code || "-",
  }
}

function currentSessionSourceRole(sessionId = state.sessionId || "") {
  if (sessionId && state.sessionInfo?.agent_device_id === state.deviceId) {
    return "agent"
  }
  if (sessionId && state.sessionInfo?.controller_device_id === state.deviceId) {
    return "controller"
  }
  return normalizeRole(state.role) || "unknown"
}

function metricOrMinusOne(value, digits = 2) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric < 0) {
    return -1
  }
  return Number(numeric.toFixed(digits))
}

function metricAverageOrMinusOne(sum, count, digits = 2) {
  const numericSum = Number(sum)
  const numericCount = Number(count)
  if (!Number.isFinite(numericSum) || !Number.isFinite(numericCount) || numericCount <= 0) {
    return -1
  }
  return metricOrMinusOne(numericSum / numericCount, digits)
}

function buildSessionMetricsReportPayload(reason = "session_end", options = {}) {
  const sessionId = `${options.sessionId || state.sessionId || state.bridgeModeStats?.sessionId || ""}`.trim()
  if (!sessionId) {
    return null
  }
  const stats = options.stats || state.bridgeModeStats || emptyBridgeModeStats(sessionId)
  const agentStats = options.agentStats
    || (state.agentStatsSnapshot?.sessionId === sessionId ? state.agentStatsSnapshot : null)
  const controllerStats = options.controllerStats
    || (state.controllerStatsSnapshot?.sessionId === sessionId ? state.controllerStatsSnapshot : null)
  const endedAt = Number(options.endedAt || Date.now())
  const startedAt = Number(stats?.startedAt || endedAt)
  const durationMs = Math.max(0, endedAt - startedAt)
  const pipeline = stats?.pipeline || {}
  const rawSuccessRate = ratioPercent(pipeline.raw_success, pipeline.raw_attempts)
  const jpegRetrySuccessRate = ratioPercent(
    pipeline.jpeg_retry_success,
    pipeline.jpeg_retry_attempted,
  )
  const fetchSkipSummary = summarizeBridgeFetchSkips(stats)
  const fetchSkipTotal = Object.values(stats?.fetchSkips || {}).reduce(
    (sum, value) => sum + Number(value || 0),
    0,
  )
  const canvasHits = countBridgeCanvasHits(stats)
  const canvasShare = ratioPercent(canvasHits, stats?.total || 0)
  const capabilityTier = inferBridgeCapabilityTier(stats)
  const runtimeSignature = runtimeSignaturePayload()
  const nativeSenderStatus = state.nativeSenderStatus || emptyNativeSenderStatus()
  const remoteInputResult = state.lastRemoteInputResult || emptyRemoteInputResult()
  const remoteInputCoverage = state.remoteInputAppliedCategories || emptyRemoteInputCoverage()
  const remoteInputCoverageText = remoteInputCoverageSummary(remoteInputCoverage)
  const firstFrameMs = state.firstFrameAt > 0 && state.sessionStartedAt > 0
    ? Math.max(0, state.firstFrameAt - state.sessionStartedAt)
    : -1
  return {
    session_id: sessionId,
    report_version: 1,
    source_client: "desktop",
    source_platform: `${state.shellPlatform || "-"}`,
    source_role: currentSessionSourceRole(sessionId),
    reason: `${reason || "session_end"}`,
    duration_ms: durationMs,
    bridge_total: Number(stats?.total || 0),
    bridge_modes: summarizeBridgeModeStats(stats) || "-",
    bridge_pipeline: summarizeBridgePipelineStats(stats),
    bridge_pipeline_ratios: summarizeBridgePipelineRatios(stats),
    bridge_fetch_skips: fetchSkipSummary,
    bridge_fetch_skip_total: Number(fetchSkipTotal || 0),
    bridge_raw_attempts: Number(pipeline.raw_attempts || 0),
    bridge_raw_success: Number(pipeline.raw_success || 0),
    bridge_raw_failed: Number(pipeline.raw_failed || 0),
    bridge_jpeg_retry_attempted: Number(pipeline.jpeg_retry_attempted || 0),
    bridge_jpeg_retry_success: Number(pipeline.jpeg_retry_success || 0),
    bridge_jpeg_retry_failed: Number(pipeline.jpeg_retry_failed || 0),
    bridge_raw_success_rate_pct: rawSuccessRate,
    bridge_jpeg_retry_success_rate_pct: jpegRetrySuccessRate,
    bridge_canvas_hits: canvasHits,
    bridge_canvas_share_pct: canvasShare,
    bridge_capability_tier: capabilityTier,
    send_fps: metricOrMinusOne(agentStats?.sendFps),
    send_kbps: metricOrMinusOne(agentStats?.sendKbps, 0),
    capture_fps: metricOrMinusOne(agentStats?.bridgeFps),
    rtt_ms: metricOrMinusOne(agentStats?.roundTripTimeMs, 0),
    frame_width: Number(agentStats?.frameWidth || 0),
    frame_height: Number(agentStats?.frameHeight || 0),
    candidate_type: `${agentStats?.remoteCandidateType || "-"}`,
    candidate_path: `${agentStats?.candidatePath || "-"}`,
    candidate_tier: `${agentStats?.candidateTier || "-"}`,
    first_frame_ms: firstFrameMs,
    render_fps_avg: metricAverageOrMinusOne(
      controllerStats?.renderFpsSampleSum,
      controllerStats?.renderFpsSampleCount,
    ),
    rendered_frames: Number(controllerStats?.framesDecoded || 0),
    recv_kbps_avg: metricAverageOrMinusOne(
      controllerStats?.recvKbpsSampleSum,
      controllerStats?.recvKbpsSampleCount,
    ),
    rtt_ms_avg: metricAverageOrMinusOne(
      controllerStats?.rttMsSampleSum,
      controllerStats?.rttMsSampleCount,
      0,
    ),
    candidate_pair_last: `${controllerStats?.candidatePath || "-"}`,
    candidate_tier_last: `${controllerStats?.candidateTier || "-"}`,
    controller_quality_hint: `${controllerStats?.qualityHint || "-"}`,
    quality_limit: `${agentStats?.qualityLimitationReason || "-"}`,
    adaptive_profile: `${agentStats?.profileId || "-"}`,
    adaptive_decision: `${agentStats?.adaptiveDecision || "-"}`,
    adaptive_scene: `${agentStats?.sceneMode || "-"}`,
    adaptive_scene_switches: Number(agentStats?.sceneSwitches || 0),
    adaptive_stats_source: `${agentStats?.statsSource || "-"}`,
    remote_input_result_count: Number(state.remoteInputResultCount || 0),
    remote_input_result_applied_count: Number(state.remoteInputResultAppliedCount || 0),
    remote_input_result_failed_count: Number(state.remoteInputResultFailedCount || 0),
    remote_input_applied_click: Boolean(remoteInputCoverage.click),
    remote_input_applied_drag: Boolean(remoteInputCoverage.drag),
    remote_input_applied_keyboard: Boolean(remoteInputCoverage.keyboard),
    remote_input_applied_wheel: Boolean(remoteInputCoverage.wheel),
    remote_input_required_coverage_complete: remoteInputCoverageComplete(remoteInputCoverage),
    remote_input_applied_categories: remoteInputCoverageText || "-",
    remote_input_last_type: `${remoteInputResult.input_type || "-"}`,
    remote_input_last_category: `${remoteInputResult.input_category || "-"}`,
    remote_input_last_trace_id: `${remoteInputResult.input_trace_id || "-"}`,
    remote_input_last_applied: Boolean(remoteInputResult.applied),
    remote_input_last_executor: `${remoteInputResult.executor || "-"}`,
    remote_input_last_status_code: `${remoteInputResult.status_code || "-"}`,
    remote_input_last_status_detail: `${remoteInputResult.status_detail || "-"}`,
    remote_input_last_error_code: `${remoteInputResult.error_code || "-"}`,
    remote_input_last_error_detail: `${remoteInputResult.error_detail || "-"}`,
    remote_input_last_summary: `${remoteInputResult.summary || "-"}`,
    remote_input_last_count: Number(remoteInputResult.input_count || 0),
    remote_input_last_target_device_id: `${remoteInputResult.target_device_id || "-"}`,
    native_sender_lifecycle: `${nativeSenderStatus.lifecycle || "-"}`,
    native_sender_signaling_state: `${nativeSenderStatus.signaling_state || "-"}`,
    native_sender_dry_run: Boolean(nativeSenderStatus.dry_run),
    native_sender_signal_count: Number(nativeSenderStatus.signal_count || 0),
    native_sender_last_signal_type: `${nativeSenderStatus.last_signal_type || "-"}`,
    native_sender_last_signal_direction: `${nativeSenderStatus.last_signal_direction || "-"}`,
    native_sender_last_signal_trace_id: `${nativeSenderStatus.last_signal_trace_id || "-"}`,
    native_sender_last_signal_payload_bytes: Number(nativeSenderStatus.last_signal_payload_bytes || 0),
    native_sender_inbound_signal_count: Number(nativeSenderStatus.inbound_signal_count || 0),
    native_sender_outbound_signal_count: Number(nativeSenderStatus.outbound_signal_count || 0),
    native_sender_local_offer_count: Number(nativeSenderStatus.local_offer_count || 0),
    native_sender_local_answer_count: Number(nativeSenderStatus.local_answer_count || 0),
    native_sender_local_candidate_count: Number(nativeSenderStatus.local_candidate_count || 0),
    native_sender_remote_offer_count: Number(nativeSenderStatus.remote_offer_count || 0),
    native_sender_remote_answer_count: Number(nativeSenderStatus.remote_answer_count || 0),
    native_sender_remote_candidate_count: Number(nativeSenderStatus.remote_candidate_count || 0),
    native_sender_restart_ice_count: Number(nativeSenderStatus.restart_ice_count || 0),
    native_sender_local_restart_ice_count: Number(nativeSenderStatus.local_restart_ice_count || 0),
    native_sender_remote_restart_ice_count: Number(nativeSenderStatus.remote_restart_ice_count || 0),
    native_sender_remote_answer_sdp_len: Number(nativeSenderStatus.remote_answer_sdp_len || 0),
    native_sender_remote_offer_sdp_len: Number(nativeSenderStatus.remote_offer_sdp_len || 0),
    native_sender_last_remote_candidate_type: `${nativeSenderStatus.last_remote_candidate_type || "-"}`,
    native_sender_probe_running: Boolean(nativeSenderStatus.media_probe_running),
    native_sender_probe_fps: metricOrMinusOne(nativeSenderStatus.media_probe_fps),
    native_sender_probe_kbps: metricOrMinusOne(nativeSenderStatus.media_probe_kbps),
    native_sender_probe_frame_count: Number(nativeSenderStatus.media_probe_frame_count || 0),
    native_sender_probe_total_bytes: Number(nativeSenderStatus.media_probe_total_bytes || 0),
    native_sender_probe_frame_width: Number(nativeSenderStatus.media_probe_last_width || 0),
    native_sender_probe_frame_height: Number(nativeSenderStatus.media_probe_last_height || 0),
    native_sender_probe_last_frame_ts_ms: Number(nativeSenderStatus.media_probe_last_frame_ts_ms || 0),
    native_sender_webrtc_outbound_stats_available: Boolean(nativeSenderStatus.webrtc_outbound_stats_available),
    native_sender_webrtc_outbound_reports: Number(nativeSenderStatus.webrtc_outbound_reports || 0),
    native_sender_webrtc_outbound_bytes_sent: Number(nativeSenderStatus.webrtc_outbound_bytes_sent || 0),
    native_sender_webrtc_outbound_packets_sent: Number(nativeSenderStatus.webrtc_outbound_packets_sent || 0),
    native_sender_webrtc_outbound_header_bytes_sent: Number(nativeSenderStatus.webrtc_outbound_header_bytes_sent || 0),
    native_sender_webrtc_outbound_kbps: metricOrMinusOne(nativeSenderStatus.webrtc_outbound_kbps),
    native_sender_webrtc_outbound_fps: metricOrMinusOne(nativeSenderStatus.webrtc_outbound_fps),
    native_sender_webrtc_outbound_rtt_ms: metricOrMinusOne(nativeSenderStatus.webrtc_outbound_rtt_ms, 0),
    native_sender_webrtc_outbound_updated_at_ms: Number(nativeSenderStatus.webrtc_outbound_updated_at_ms || 0),
    native_sender_shadow_runtime_ready: Boolean(nativeSenderStatus.shadow_runtime_ready),
    native_sender_shadow_track_bound: Boolean(nativeSenderStatus.shadow_track_bound),
    native_sender_shadow_last_apply_action: `${nativeSenderStatus.shadow_last_apply_action || "-"}`,
    native_sender_last_error_code: `${nativeSenderStatus.last_error_code || "-"}`,
    ...runtimeSignature,
  }
}

function emitSessionMetricsReport(reason = "session_end", options = {}) {
  const payload = buildSessionMetricsReportPayload(reason, options)
  if (!payload || !payload.session_id) {
    return false
  }
  const sessionId = `${payload.session_id}`.trim()
  if (!sessionId) {
    return false
  }
  const reportKey = `${sessionId}|${payload.source_role || ""}|${payload.reason || reason || ""}`
  if (state.lastSessionMetricsReportKey === reportKey && options.allowDuplicate !== true) {
    return false
  }
  const sent = sendEnvelope("session.metrics.report", payload, sessionId, { log: false })
  if (!sent) {
    return false
  }
  state.lastSessionMetricsReportSessionId = sessionId
  state.lastSessionMetricsReportKey = reportKey
  traceLog("session.metrics.report.sent", {
    session_id: sessionId,
    reason: `${reason || "session_end"}`,
    source_role: payload.source_role || "-",
    source_client: payload.source_client || "-",
    payload_keys: Object.keys(payload).join(","),
  }, { console: false })
  return true
}

function scheduleLiveE2EProofReport(reason = "live_probe", options = {}) {
  const sessionId = `${options.sessionId || state.sessionId || ""}`.trim()
  if (!sessionId || !state.sessionInfo) {
    return false
  }
  const role = currentSessionSourceRole(sessionId)
  if (role !== "controller" && role !== "agent") {
    return false
  }

  const now = Date.now()
  const remoteInputCount = Number(state.remoteInputResultCount || 0)
  const hostInputCount = Number(state.hostBridgeStatus?.input_count || 0)
  const controllerFrames = Number(state.controllerStatsSnapshot?.sessionId === sessionId ? state.controllerStatsSnapshot.framesDecoded || 0 : 0)
  const agentFrames = Number(state.agentStatsSnapshot?.sessionId === sessionId ? state.agentStatsSnapshot.framesSent || 0 : 0)
  const firstFrameMs = state.firstFrameAt > 0 && state.sessionStartedAt > 0
    ? Math.max(0, state.firstFrameAt - state.sessionStartedAt)
    : -1
  const videoObserved = firstFrameMs >= 0 || controllerFrames > 0
  const agentSenderObserved = agentFrames > 0
  const proofKey = [
    sessionId,
    role,
    reason,
    videoObserved ? "video:1" : "video:0",
    agentSenderObserved ? "agent_sender:1" : "agent_sender:0",
    remoteInputCount,
    hostInputCount,
    state.lastRemoteInputResult?.input_trace_id || "",
    state.hostBridgeStatus?.last_trace_id || "",
    remoteInputCoverageSummary(),
  ].join("|")

  if (proofKey === state.lastLiveE2EProofReportKey && options.force !== true) {
    return false
  }
  if (now - Number(state.lastLiveE2EProofReportAt || 0) < LIVE_E2E_PROOF_REPORT_MIN_INTERVAL_MS && options.force !== true) {
    return false
  }

  const sent = emitSessionMetricsReport(reason, {
    sessionId,
    allowDuplicate: true,
    endedAt: now,
  })
  if (sent) {
    state.lastLiveE2EProofReportAt = now
    state.lastLiveE2EProofReportKey = proofKey
  }
  return sent
}

function emitBridgeModeSummary(reason = "session_end", options = {}) {
  const stats = state.bridgeModeStats
  if (!stats || stats.emitted) {
    return false
  }
  const agentStats = state.agentStatsSnapshot?.sessionId === stats.sessionId
    ? state.agentStatsSnapshot
    : null
  const summary = summarizeBridgeModeStats(stats)
  const capabilityTier = inferBridgeCapabilityTier(stats)
  const runtimeSignature = runtimeSignaturePayload()
  const durationMs = Math.max(
    0,
    (options.endedAt || Date.now()) - Number(stats.startedAt || Date.now()),
  )
  traceLog("native.bridge.mode.summary", {
    session_id: stats.sessionId || "-",
    reason,
    total: stats.total,
    duration_ms: durationMs,
    modes: summary || "-",
    pipeline: summarizeBridgePipelineStats(stats),
    pipeline_ratios: summarizeBridgePipelineRatios(stats),
    fetch_skips: summarizeBridgeFetchSkips(stats),
    capability_tier: capabilityTier,
    send_fps: agentStats && agentStats.sendFps >= 0 ? agentStats.sendFps.toFixed(2) : "-",
    send_kbps: agentStats && agentStats.sendKbps >= 0 ? Math.round(agentStats.sendKbps) : -1,
    capture_fps: agentStats && agentStats.bridgeFps >= 0 ? agentStats.bridgeFps.toFixed(2) : "-",
    rtt_ms: agentStats?.roundTripTimeMs ?? -1,
    frame_size: agentStats ? `${agentStats.frameWidth || 0}x${agentStats.frameHeight || 0}` : "-",
    candidate_type: agentStats?.remoteCandidateType || "-",
    candidate_path: agentStats?.candidatePath || "-",
    candidate_tier: agentStats?.candidateTier || "-",
    quality_limit: agentStats?.qualityLimitationReason || "-",
    profile: agentStats?.profileId || "-",
    decision: agentStats?.adaptiveDecision || "-",
    scene: agentStats?.sceneMode || "-",
    stats_source: agentStats?.statsSource || "-",
    runtime_kernel: runtimeSignature.runtime_kernel,
    runtime_cap_sig: runtimeSignature.runtime_capability_signature,
  })
  appendLog(
    `会话总报表(${reason}): modes=${summary || "none"} total=${stats.total} dur=${durationMs}ms `
    + `pipeline=${summarizeBridgePipelineStats(stats)} `
    + `pipeline_ratios=${summarizeBridgePipelineRatios(stats)} `
    + `fetch_skips=${summarizeBridgeFetchSkips(stats)} `
    + `capability=${capabilityTier} `
    + `send=${agentStats && agentStats.sendFps >= 0 ? agentStats.sendFps.toFixed(1) : "-"}fps `
    + `bitrate=${agentStats && agentStats.sendKbps >= 0 ? Math.round(agentStats.sendKbps) : -1}kbps `
    + `rtt=${agentStats?.roundTripTimeMs ?? -1}ms `
    + `capture=${agentStats && agentStats.bridgeFps >= 0 ? agentStats.bridgeFps.toFixed(1) : "-"}fps `
    + `size=${agentStats ? `${agentStats.frameWidth || 0}x${agentStats.frameHeight || 0}` : "-"} `
    + `cand=${agentStats?.remoteCandidateType || "-"} `
    + `path=${agentStats?.candidatePath || "-"} `
    + `tier=${agentStats?.candidateTier || "-"} `
    + `ql=${agentStats?.qualityLimitationReason || "-"} `
    + `profile=${agentStats?.profileId || "-"} `
    + `scene=${agentStats?.sceneMode || "-"} `
    + `source=${agentStats?.statsSource || "-"}`
  )
  emitSessionMetricsReport(reason, {
    ...options,
    sessionId: stats.sessionId,
    stats,
    agentStats,
    allowDuplicate: false,
  })
  stats.endedAt = options.endedAt || Date.now()
  stats.emitted = true
  return true
}

function clampAdaptiveProfileIndex(value) {
  if (!Number.isFinite(value)) {
    return AGENT_ADAPTIVE_DEFAULT_PROFILE_INDEX
  }
  return Math.min(
    AGENT_ADAPTIVE_PROFILES.length - 1,
    Math.max(0, Math.round(value)),
  )
}

function currentAgentAdaptiveProfile() {
  const index = clampAdaptiveProfileIndex(state.agentAdaptive?.profileIndex)
  return AGENT_ADAPTIVE_PROFILES[index] || AGENT_ADAPTIVE_PROFILES[AGENT_ADAPTIVE_DEFAULT_PROFILE_INDEX]
}

function currentSessionControllerProfile() {
  return `${state.sessionInfo?.webrtc?.controller_profile || "standard"}`.trim().toLowerCase() || "standard"
}

function currentSessionMediaCaps(options = {}) {
  const controllerProfile = currentSessionControllerProfile()
  if (controllerProfile === "emulator") {
    return {
      source: "emulator",
      maxWidth: EMULATOR_SESSION_MAX_WIDTH,
      maxHeight: EMULATOR_SESSION_MAX_HEIGHT,
      maxFps: EMULATOR_SESSION_MAX_FPS,
      maxBitrate: EMULATOR_SESSION_MAX_BITRATE,
      maxScaleResolutionDownBy: EMULATOR_SESSION_MAX_SCALE_DOWN_BY,
    }
  }
  if (controllerProfile === "android_phone") {
    if (options.androidPhoneZoomMotion) {
      return {
        source: "android_phone_zoom_motion",
        maxWidth: ANDROID_PHONE_ZOOM_MOTION_MAX_WIDTH,
        maxHeight: ANDROID_PHONE_ZOOM_MOTION_MAX_HEIGHT,
        maxFps: ANDROID_PHONE_ZOOM_MOTION_MAX_FPS,
        maxBitrate: ANDROID_PHONE_ZOOM_MOTION_MAX_BITRATE,
        maxScaleResolutionDownBy: ANDROID_PHONE_SESSION_MAX_SCALE_DOWN_BY,
      }
    }
    if (options.allowAndroidZoomStill) {
      return {
        source: "android_phone_zoom_still",
        maxWidth: ANDROID_PHONE_ZOOM_STILL_MAX_WIDTH,
        maxHeight: ANDROID_PHONE_ZOOM_STILL_MAX_HEIGHT,
        maxFps: ANDROID_PHONE_ZOOM_STILL_MAX_FPS,
        maxBitrate: ANDROID_PHONE_ZOOM_STILL_MAX_BITRATE,
        maxScaleResolutionDownBy: ANDROID_PHONE_SESSION_MAX_SCALE_DOWN_BY,
      }
    }
    if (options.allowAndroidZoomDetail) {
      return {
        source: "android_phone_zoom_detail",
        maxWidth: ANDROID_PHONE_ZOOM_DETAIL_MAX_WIDTH,
        maxHeight: ANDROID_PHONE_ZOOM_DETAIL_MAX_HEIGHT,
        maxFps: ANDROID_PHONE_ZOOM_DETAIL_MAX_FPS,
        maxBitrate: ANDROID_PHONE_ZOOM_DETAIL_MAX_BITRATE,
        maxScaleResolutionDownBy: ANDROID_PHONE_SESSION_MAX_SCALE_DOWN_BY,
      }
    }
    if (options.allowAndroidFullscreenDetail) {
      return {
        source: "android_phone_fullscreen_detail",
        maxWidth: ANDROID_PHONE_FULLSCREEN_MAX_WIDTH,
        maxHeight: ANDROID_PHONE_FULLSCREEN_MAX_HEIGHT,
        maxFps: ANDROID_PHONE_FULLSCREEN_MAX_FPS,
        maxBitrate: ANDROID_PHONE_FULLSCREEN_MAX_BITRATE,
        maxScaleResolutionDownBy: ANDROID_PHONE_SESSION_MAX_SCALE_DOWN_BY,
      }
    }
    return {
      source: "android_phone",
      maxWidth: ANDROID_PHONE_SESSION_MAX_WIDTH,
      maxHeight: ANDROID_PHONE_SESSION_MAX_HEIGHT,
      maxFps: ANDROID_PHONE_SESSION_MAX_FPS,
      maxBitrate: ANDROID_PHONE_SESSION_MAX_BITRATE,
      maxScaleResolutionDownBy: ANDROID_PHONE_SESSION_MAX_SCALE_DOWN_BY,
    }
  }
  return null
}

function buildEffectiveAdaptiveProfile(profile, options = {}) {
  if (!profile) {
    return profile
  }
  const caps = currentSessionMediaCaps(options)
  if (!caps) {
    return profile
  }
  return {
    ...profile,
    maxWidth: Math.max(160, Math.min(profile.maxWidth, caps.maxWidth)),
    maxHeight: Math.max(90, Math.min(profile.maxHeight, caps.maxHeight)),
    maxFps: Math.max(1, Math.min(profile.maxFps, caps.maxFps)),
    maxBitrate: Math.max(450000, Math.min(profile.maxBitrate, caps.maxBitrate)),
    scaleResolutionDownBy: Math.max(1, Math.min(profile.scaleResolutionDownBy || 1, caps.maxScaleResolutionDownBy || 1)),
    cappedBy: caps.source,
  }
}

function currentEffectiveAgentAdaptiveProfile() {
  return buildEffectiveAdaptiveProfile(currentAgentAdaptiveProfile())
}

function normalizeEvenDimension(value, minimum = 2) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric <= 0) {
    return minimum
  }
  const rounded = Math.max(minimum, Math.round(numeric))
  return rounded % 2 === 0 ? rounded : rounded - 1
}

function resolveBridgeCanvasOutputSize(sourceWidth, sourceHeight, profile = currentEffectiveAgentAdaptiveProfile()) {
  const width = Number(sourceWidth) || 0
  const height = Number(sourceHeight) || 0
  if (width <= 0 || height <= 0) {
    return { width: 0, height: 0, capped: false }
  }
  const maxWidth = Math.max(2, Number(profile?.maxWidth) || width)
  const maxHeight = Math.max(2, Number(profile?.maxHeight) || height)
  const scale = Math.min(1, maxWidth / width, maxHeight / height)
  const targetWidth = normalizeEvenDimension(width * scale)
  const targetHeight = normalizeEvenDimension(height * scale)
  return {
    width: targetWidth,
    height: targetHeight,
    capped: targetWidth < width || targetHeight < height,
  }
}

function resolveInitialAdaptiveProfileIndex() {
  if (currentSessionControllerProfile() === "emulator") {
    return EMULATOR_SESSION_PROFILE_INDEX
  }
  // long: Android 真机全屏和手势放大依赖足够的源分辨率，默认从清晰档起步；真机链路会把上限收在 1000p 以内，兼顾放大清晰度和软件编码帧率。
  if (currentSessionControllerProfile() === "android_phone") {
    return ANDROID_PHONE_SESSION_PROFILE_INDEX
  }
  return AGENT_ADAPTIVE_DEFAULT_PROFILE_INDEX
}

function minAdaptiveProfileIndexForCurrentSession() {
  if (currentSessionControllerProfile() === "emulator") {
    return EMULATOR_SESSION_MIN_PROFILE_INDEX
  }
  // long: Android 真机路线最低也守在清晰档的手机上限内，避免退回 720p 后缩放发糊，同时用帧率/码率指标暴露真实性能。
  if (currentSessionControllerProfile() === "android_phone") {
    return ANDROID_PHONE_SESSION_PROFILE_INDEX
  }
  return 0
}

function maxAdaptiveProfileIndexForCurrentSession() {
  if (currentSessionControllerProfile() === "emulator") {
    return EMULATOR_SESSION_MAX_PROFILE_INDEX
  }
  return AGENT_ADAPTIVE_PROFILES.length - 1
}

function dynamicTargetProfileIndexForCurrentSession() {
  if (currentSessionControllerProfile() === "android_phone") {
    return ANDROID_PHONE_SESSION_PROFILE_INDEX
  }
  return AGENT_ADAPTIVE_SCENE_DYNAMIC_TARGET_PROFILE_INDEX
}

function resetAgentAdaptiveState() {
  state.agentAdaptive = emptyAgentAdaptiveState()
}

function ensureAgentAdaptiveSessionState(sessionId) {
  const normalizedSessionId = `${sessionId || ""}`.trim()
  if (!normalizedSessionId) {
    return null
  }
  if (!state.agentAdaptive || state.agentAdaptive.sessionId !== normalizedSessionId) {
    resetAgentAdaptiveState()
    state.agentAdaptive.sessionId = normalizedSessionId
    state.agentAdaptive.sessionStartedAt = Date.now()
    state.agentAdaptive.profileIndex = resolveInitialAdaptiveProfileIndex()
  }
  state.agentAdaptive.lastSwitchAt = state.agentAdaptive.lastSwitchAt || Date.now()
  return state.agentAdaptive
}

function inferAgentMediaPath() {
  if (nativeSenderOwnershipEnabledForSession()) {
    return "native_sender"
  }
  if (state.nativeBridgeStream) {
    return "native_bridge"
  }
  if (state.captureStream) {
    return "direct_track"
  }
  return "unknown"
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  })[char])
}

function appendLog(line) {
  state.logs.unshift(`[${new Date().toLocaleTimeString()}] ${line}`)
  state.logs = state.logs.slice(0, MAX_UI_LOG_LINES)
}

function extractIceCandidateType(candidate) {
  const text = `${candidate || ""}`
  const marker = " typ "
  const index = text.indexOf(marker)
  if (index < 0) {
    return "unknown"
  }
  const start = index + marker.length
  if (start >= text.length) {
    return "unknown"
  }
  const end = text.indexOf(" ", start)
  return (end > start ? text.slice(start, end) : text.slice(start)).trim() || "unknown"
}

function countSdpCandidateLines(sdp) {
  const content = `${sdp || ""}`
  if (!content) {
    return 0
  }
  return content.split(/\r?\n/).filter((line) => /^a=candidate:/i.test(line)).length
}

function summarizeSignalPayload(type, payload = {}) {
  if (type === "webrtc.offer" || type === "webrtc.answer") {
    const sdp = typeof payload?.sdp === "string" ? payload.sdp : ""
    return {
      sdp_len: sdp.length,
      sdp_has_video: sdp.includes("m=video"),
      sdp_candidate_lines: countSdpCandidateLines(sdp),
    }
  }
  if (type === "webrtc.ice_candidate") {
    const candidate = typeof payload?.candidate === "string" ? payload.candidate : ""
    return {
      candidate_type: extractIceCandidateType(candidate),
      candidate_len: candidate.length,
      sdp_mid: `${payload?.sdp_mid || ""}`,
      sdp_mline_index: Number.isFinite(Number(payload?.sdp_mline_index)) ? Number(payload.sdp_mline_index) : -1,
    }
  }
  return {}
}

function summarizeEnvelope(message, options = {}) {
  const msg = message || {}
  const payload = msg.payload && typeof msg.payload === "object" ? msg.payload : {}
  return {
    direction: options.direction || "unknown",
    type: `${msg.type || "unknown"}`,
    msg_id: `${msg.msg_id || "-"}`,
    trace_id: `${msg.trace_id || "-"}`,
    session_id: `${msg.session_id || "-"}`,
    from_device: `${msg.from?.device_id || "-"}`,
    from_role: `${msg.from?.role || "-"}`,
    payload_keys: Object.keys(payload).join(",") || "-",
    ...summarizeSignalPayload(msg.type, payload),
    bytes: Number.isFinite(options.bytes) ? options.bytes : undefined,
  }
}

function traceLog(event, data = {}, options = {}) {
  if (!DESKTOP_TRACE_VERBOSE) {
    return
  }
  const entries = Object.entries(data)
    .filter(([, value]) => value !== undefined && value !== null && `${value}` !== "")
    .map(([key, value]) => `${key}=${value}`)
  const line = `${event}${entries.length ? ` ${entries.join(" ")}` : ""}`
  appendLog(`[trace] ${line}`)
  if (options.console !== false) {
    console.info("[RemoteDeskTrace]", event, data)
  }
  if (shouldMirrorTraceToNative(event)) {
    void mirrorTraceToNative(event, line)
  }
}

function shouldMirrorTraceToNative(event) {
  const name = `${event || ""}`.trim()
  return name.startsWith("native.bridge.")
    || name.startsWith("native.sender.")
    || name.startsWith("webrtc.agent.native_stream")
    || name.startsWith("webrtc.agent.offer")
    || name.startsWith("webrtc.agent.zero_frames")
    || name.startsWith("webrtc.local_offer")
}

async function mirrorTraceToNative(event, line) {
  if (!isTauri()) {
    return
  }
  try {
    await invoke("debug_log", {
      line: `${event}: ${line}`,
    })
  } catch {
    // ignore debug log transport failures
  }
}

function emitNativeDebugLog(line) {
  if (!isTauri()) {
    return
  }
  const text = `${line || ""}`.trim()
  if (!text) {
    return
  }
  void invoke("debug_log", { line: text }).catch(() => {})
}

function buildConnectionKey() {
  return `${state.wsUrl}|${state.deviceId}|${state.role}`
}

function isValidRelayWsUrl(value) {
  const candidate = `${value || ""}`.trim()
  if (!candidate) {
    return false
  }
  try {
    const url = new URL(candidate)
    return url.protocol === "ws:" || url.protocol === "wss:"
  } catch {
    return false
  }
}

function triggerAutoConnectFromSettings() {
  if (!state.autoConnect || !isValidRelayWsUrl(state.wsUrl)) {
    return
  }

  const nextConnectionKey = buildConnectionKey()
  const sameConnection = state.connectionKey === nextConnectionKey
  const readyState = state.ws?.readyState
  if (sameConnection && (readyState === WebSocket.OPEN || readyState === WebSocket.CONNECTING)) {
    return
  }

  void connect({ source: "settings-change" })
}

function scheduleAutoConnectFromSettings() {
  if (state.settingsAutoConnectTimer) {
    window.clearTimeout(state.settingsAutoConnectTimer)
    state.settingsAutoConnectTimer = null
  }
  if (!state.autoConnect || !isValidRelayWsUrl(state.wsUrl)) {
    return
  }
  state.settingsAutoConnectTimer = window.setTimeout(() => {
    state.settingsAutoConnectTimer = null
    triggerAutoConnectFromSettings()
  }, SETTINGS_AUTO_CONNECT_DELAY_MS)
}

function clearAutoConnectTimer() {
  if (state.autoConnectTimer) {
    window.clearTimeout(state.autoConnectTimer)
    state.autoConnectTimer = null
  }
}

function clearWsReconnectTimer(options = {}) {
  if (state.wsReconnectTimer) {
    window.clearTimeout(state.wsReconnectTimer)
    state.wsReconnectTimer = null
  }
  if (options.resetAttempt) {
    state.wsReconnectAttempt = 0
  }
}

function wsReconnectDelayForAttempt(attempt) {
  const shift = Math.min(Math.max(attempt - 1, 0), 4)
  return Math.min(WS_RECONNECT_INITIAL_DELAY_MS * (2 ** shift), WS_RECONNECT_MAX_DELAY_MS)
}

function scheduleWsReconnect(source = "closed") {
  if (!state.autoConnect || !isValidRelayWsUrl(state.wsUrl) || state.wsReconnectTimer || connectionReady()) {
    return
  }
  state.wsReconnectAttempt += 1
  const attempt = state.wsReconnectAttempt
  const delayMs = wsReconnectDelayForAttempt(attempt)
  appendLog(`WebSocket 已断开，${delayMs}ms 后自动重连（第 ${attempt} 次，来源=${source}）`)
  state.wsReconnectTimer = window.setTimeout(() => {
    state.wsReconnectTimer = null
    if (!state.autoConnect || connectionReady()) {
      return
    }
    void connect({ source: "reconnect" })
  }, delayMs)
  render()
}

function scheduleAutoConnect() {
  if (!state.autoConnect || state.autoBootstrapped) {
    return
  }
  if (!isValidRelayWsUrl(state.wsUrl)) {
    return
  }

  state.autoBootstrapped = true
  clearAutoConnectTimer()
  const nextConnectionKey = buildConnectionKey()
  const lastConnectionKey = safeStorageGet(sessionStore, "rd-auto-connect-key")
  const lastAutoConnectAt = Number(safeStorageGet(sessionStore, "rd-auto-connect-at") || "0")
  const now = Date.now()

  if (!isTauri() && lastConnectionKey === nextConnectionKey && now - lastAutoConnectAt < AUTO_CONNECT_DELAY_MS) {
    return
  }

  safeStorageSet(sessionStore, "rd-auto-connect-key", nextConnectionKey)
  safeStorageSet(sessionStore, "rd-auto-connect-at", String(now))
  state.autoConnectTimer = window.setTimeout(() => {
    state.autoConnectTimer = null
    void connect({ source: "auto" })
  }, AUTO_CONNECT_DELAY_MS)
}

function connectionReady() {
  return Boolean(state.ws && state.ws.readyState === WebSocket.OPEN)
}

function readyStateLabel() {
  if (!state.ws) {
    return "未连接"
  }
  if (state.ws.readyState === WebSocket.CONNECTING) {
    return "连接中"
  }
  if (state.ws.readyState === WebSocket.OPEN) {
    return "已连接"
  }
  if (state.ws.readyState === WebSocket.CLOSING) {
    return "关闭中"
  }
  return "已关闭"
}

function inferPlatform() {
  const platform = `${navigator.platform || ""}`.toLowerCase()
  if (platform.includes("mac")) {
    return "macos"
  }
  return "windows"
}

function formatRuntimeLabel(value) {
  switch (value) {
    case "tauri":
      return "桌面运行时"
    case "browser":
      return "浏览器"
    default:
      return value || "-"
  }
}

function formatPlatformLabel(value) {
  switch (value) {
    case "macos":
      return "苹果电脑"
    case "windows":
      return "视窗系统"
    case "linux":
      return "Linux 系统"
    case "unknown":
      return "未知"
    default:
      return value || "-"
  }
}

function formatCapabilityLevel(value) {
  switch (value) {
    case "implemented":
      return "已实现"
    case "partial":
      return "部分实现"
    case "unsupported":
      return "不支持"
    case "unknown":
      return "未知"
    default:
      return value || "-"
  }
}

function formatPermissionStatus(value) {
  switch (value) {
    case "granted":
      return "已授权"
    case "denied":
      return "已拒绝"
    case "prompt":
      return "待确认"
    case "restricted":
      return "受限"
    case "unknown":
      return "未知"
    default:
      return value || "-"
  }
}

function formatCodecLabel(value) {
  switch (value) {
    case "webrtc-h264":
      return "WebRTC H.264"
    case "jpeg-frame-stream":
      return "JPEG 帧流"
    case "png-frame-stream":
      return "PNG 帧流"
    default:
      return value || "-"
  }
}

function formatBackendLabel(value) {
  switch (value) {
    case "host.bridge":
      return "主机输入桥接"
    default:
      return value || "-"
  }
}

function formatTransportModeLabel(value) {
  switch (value) {
    case "webrtc":
      return "WebRTC"
    default:
      return value || "-"
  }
}

function formatCaptureLifecycleLabel(value) {
  switch (value) {
    case "idle":
      return "空闲"
    case "ready":
      return "就绪"
    case "running":
      return "运行中"
    case "paused":
      return "暂停"
    case "stopped":
      return "已停止"
    default:
      return value || "-"
  }
}

function applyBootstrapContext(context) {
  if (!context || typeof context !== "object") {
    return
  }

  state.runtime = typeof context.runtime === "string" && context.runtime ? context.runtime : state.runtime
  state.shellPlatform = typeof context.platform === "string" && context.platform ? context.platform : state.shellPlatform
  state.protocolVersion = typeof context.protocol_version === "string" && context.protocol_version ? context.protocol_version : state.protocolVersion
  state.defaultWsUrl = typeof context.default_ws_url === "string" && context.default_ws_url ? context.default_ws_url : state.defaultWsUrl
  state.defaultCodec = typeof context.default_codec === "string" && context.default_codec ? context.default_codec : state.defaultCodec
  state.autoConnect = context.auto_connect !== false
  state.autoRegister = context.auto_register !== false
  state.autoHeartbeat = context.auto_heartbeat !== false
  state.debugToolsEnabled = context.debug_tools_enabled === true
  state.debugSendClipboardText = `${context.debug_send_clipboard_text || ""}`
  state.debugSendFilePath = `${context.debug_send_file_path || ""}`

  if (!hasExplicitWsUrlOverride || !isValidRelayWsUrl(state.wsUrl)) {
    if (isValidRelayWsUrl(state.defaultWsUrl)) {
      state.wsUrl = state.defaultWsUrl
    } else if (!isValidRelayWsUrl(state.wsUrl)) {
      state.wsUrl = "ws://127.0.0.1:18081/ws"
    }
    persistLocalSettings()
  }
}

function currentCaptureSource() {
  return state.captureStatus?.active_source || null
}

function nativeCaptureSupportsActiveSource(source = currentCaptureSource()) {
  if (!source) {
    return false
  }
  if (!currentPlatformHostInputCapabilities().supports_pointer_input) {
    return true
  }
  return source.kind === "display"
}

function nativeCaptureUnsupportedSourceMessage(source = currentCaptureSource()) {
  if (!source || nativeCaptureSupportsActiveSource(source)) {
    return ""
  }
  return `当前桌面采集源 ${source.title || source.source_id || "未命名来源"} 不是显示器源，无法安全映射远端指针`
}

function preferredNativeCaptureSource() {
  const supportedSources = state.captureSources.filter((source) => nativeCaptureSupportsActiveSource(source))
  return supportedSources.find((source) => source?.is_primary) || supportedSources[0] || null
}

function normalizePlatformCapabilities(capabilities) {
  if (!capabilities || typeof capabilities !== "object") {
    return emptyPlatformCapabilities()
  }

  const base = emptyPlatformCapabilities()
  const hostInput = capabilities.host_input && typeof capabilities.host_input === "object"
    ? {
        ...base.host_input,
        ...capabilities.host_input,
        permission: capabilities.host_input.permission && typeof capabilities.host_input.permission === "object"
          ? { ...base.host_input.permission, ...capabilities.host_input.permission }
          : base.host_input.permission,
      }
    : base.host_input
  return {
    ...base,
    ...capabilities,
    capture: capabilities.capture && typeof capabilities.capture === "object"
      ? { ...base.capture, ...capabilities.capture }
      : base.capture,
    host_input: hostInput,
  }
}

function currentPlatformCaptureCapabilities() {
  const base = emptyCaptureCapabilities()
  const platformCapabilities = state.platformCapabilities?.capture
  const runtimeCapabilities = state.captureStatus?.capabilities
  return {
    ...base,
    ...(platformCapabilities && typeof platformCapabilities === "object" ? platformCapabilities : {}),
    ...(runtimeCapabilities && typeof runtimeCapabilities === "object" ? runtimeCapabilities : {}),
  }
}

function currentPlatformHostInputCapabilities() {
  return state.platformCapabilities?.host_input || emptyHostInputCapabilities()
}

function syncPlatformCapabilities(capabilities) {
  state.platformCapabilities = normalizePlatformCapabilities(capabilities)
  if (state.platformCapabilities.platform) {
    state.shellPlatform = state.platformCapabilities.platform
  }
  const captureCapabilities = currentPlatformCaptureCapabilities()
  if (captureCapabilities.default_codec) {
    state.defaultCodec = captureCapabilities.default_codec
  }
}

function normalizeNativeSenderCapabilities(capabilities) {
  if (!capabilities || typeof capabilities !== "object") {
    return emptyNativeSenderCapabilities()
  }
  return {
    ...emptyNativeSenderCapabilities(),
    ...capabilities,
    supported: Boolean(capabilities.supported),
  }
}

function normalizeNativeSenderStatus(status) {
  if (!status || typeof status !== "object") {
    return emptyNativeSenderStatus()
  }
  return {
    ...emptyNativeSenderStatus(),
    ...status,
    signal_count: Number.isFinite(Number(status.signal_count)) ? Number(status.signal_count) : 0,
  }
}

async function refreshNativeSenderState(options = {}) {
  if (!isTauri()) {
    return state.nativeSenderStatus
  }
  try {
    state.nativeSenderCapabilities = normalizeNativeSenderCapabilities(
      await invoke("native_sender_get_capabilities"),
    )
    state.nativeSenderStatus = normalizeNativeSenderStatus(await invoke("native_sender_status"))
    state.nativeSenderError = ""
  } catch (error) {
    state.nativeSenderError = error?.message || "读取原生 sender 状态失败"
    if (options.log !== false) {
      appendLog(`读取原生 sender 状态失败: ${state.nativeSenderError}`)
    }
  }
  return state.nativeSenderStatus
}

function stopNativeSenderStatusProbe() {
  if (state.nativeSenderStatusTimer) {
    window.clearTimeout(state.nativeSenderStatusTimer)
    state.nativeSenderStatusTimer = null
  }
  state.nativeSenderStatusLoopId += 1
}

function startNativeSenderStatusProbe(sessionId) {
  stopNativeSenderStatusProbe()
  const normalizedSessionId = `${sessionId || ""}`.trim()
  if (!isTauri() || !normalizedSessionId || !isAgentSession()) {
    return
  }
  const loopId = state.nativeSenderStatusLoopId + 1
  state.nativeSenderStatusLoopId = loopId
  let sampleCount = 0

  const tick = async () => {
    if (
      state.nativeSenderStatusLoopId !== loopId
      || !isAgentSession()
      || !state.sessionId
      || state.sessionId !== normalizedSessionId
    ) {
      return
    }
    const status = await refreshNativeSenderState({ log: false })
    sampleCount += 1
    if (nativeSenderOwnershipEnabledForSession(normalizedSessionId)) {
      const outboundStats = collectAgentOutboundVideoStatsFromNativeSender()
      if (outboundStats) {
        ensureAgentAdaptiveSessionState(normalizedSessionId)
        const adaptiveResult = await maybeAdjustAgentAdaptiveProfile(
          normalizedSessionId,
          null,
          outboundStats,
          Date.now(),
        )
        const currentProfile = adaptiveResult.profile || currentAgentAdaptiveProfile()
        state.agentStatsSnapshot = {
          sessionId: normalizedSessionId,
          updatedAt: Date.now(),
          framesSent: outboundStats.framesSent,
          framesEncoded: outboundStats.framesEncoded,
          packetsSent: outboundStats.packetsSent,
          bytesSent: outboundStats.bytesSent,
          frameWidth: outboundStats.frameWidth,
          frameHeight: outboundStats.frameHeight,
          roundTripTimeMs: outboundStats.roundTripTimeMs,
          remoteCandidateType: outboundStats.remoteCandidateType,
          candidatePath: outboundStats.candidatePath,
          candidateTier: outboundStats.candidateTier,
          qualityLimitationReason: outboundStats.qualityLimitationReason,
          sendFps: adaptiveResult.sendFps,
          sendKbps: adaptiveResult.sendKbps,
          bridgeFps: adaptiveResult.bridgeFps,
          adaptiveDecision: adaptiveResult.decision || "hold",
          profileId: currentProfile.id,
          profileLabel: currentProfile.label,
          sceneMode: adaptiveResult.sceneMode || state.agentAdaptive?.sceneMode || "mixed",
          sceneSwitches: Number(adaptiveResult.sceneSwitches || state.agentAdaptive?.sceneSwitchCount || 0),
          statsSource: `${outboundStats.statsSource || "native_sender_probe"}`,
        }
        if (!state.firstFrameAt && outboundStats.framesSent > 0) {
          state.firstFrameAt = Date.now()
        }
        if (sampleCount % 3 === 0) {
          traceLog("webrtc.agent.outbound_stats", {
            session_id: normalizedSessionId,
            stats_source: outboundStats.statsSource || "native_sender_probe",
            frames_sent: outboundStats.framesSent,
            bytes_sent: outboundStats.bytesSent,
            frame_size: `${outboundStats.frameWidth || 0}x${outboundStats.frameHeight || 0}`,
            send_fps: adaptiveResult.sendFps >= 0 ? adaptiveResult.sendFps.toFixed(2) : "-",
            send_kbps: adaptiveResult.sendKbps >= 0 ? Math.round(adaptiveResult.sendKbps) : -1,
            capture_fps: adaptiveResult.bridgeFps >= 0 ? adaptiveResult.bridgeFps.toFixed(2) : "-",
            profile: currentProfile.id,
            profile_label: currentProfile.label,
            adaptive_decision: adaptiveResult.decision || "hold",
            adaptive_scene: adaptiveResult.sceneMode || state.agentAdaptive?.sceneMode || "mixed",
            adaptive_scene_switches: Number(adaptiveResult.sceneSwitches || state.agentAdaptive?.sceneSwitchCount || 0),
          }, { console: false })
        }
      }
    }
    if (
      nativeSenderOwnershipEnabledForSession(normalizedSessionId)
      && nativeSenderCaptureSourceMissing(status)
      && !state.nativeSenderCaptureRecoverInFlight
    ) {
      const now = Date.now()
      if (now - Number(state.nativeSenderCaptureRecoverLastAt || 0) >= NATIVE_SENDER_CAPTURE_AUTO_RECOVER_COOLDOWN_MS) {
        state.nativeSenderCaptureRecoverInFlight = true
        state.nativeSenderCaptureRecoverLastAt = now
        try {
          const recovered = await ensureNativeCaptureSourceReadyForSession(normalizedSessionId, { log: false, maxAttempts: 2 })
          traceLog("native.sender.capture.auto_recover", {
            session_id: normalizedSessionId,
            recovered: recovered ? 1 : 0,
            error_detail: `${status?.last_error_detail || "-"}`,
          }, { console: false })
          if (recovered) {
            appendLog("检测到原生 sender 采集源丢失，已自动恢复采集源")
          }
        } finally {
          state.nativeSenderCaptureRecoverInFlight = false
        }
      }
    }
    if (sampleCount % 3 === 0) {
      traceLog("native.sender.status.sample", {
        session_id: normalizedSessionId,
        lifecycle: `${status?.lifecycle || "-"}`,
        signaling_state: `${status?.signaling_state || "-"}`,
        probe_running: status?.media_probe_running ? 1 : 0,
        probe_fps: Number.isFinite(Number(status?.media_probe_fps)) ? Number(status.media_probe_fps).toFixed(2) : "-",
        probe_kbps: Number.isFinite(Number(status?.media_probe_kbps)) ? Number(status.media_probe_kbps).toFixed(1) : "-",
        probe_frames: Number(status?.media_probe_frame_count || 0),
        outbound_available: status?.webrtc_outbound_stats_available ? 1 : 0,
        outbound_reports: Number(status?.webrtc_outbound_reports || 0),
        outbound_bytes_sent: Number(status?.webrtc_outbound_bytes_sent || 0),
        outbound_packets_sent: Number(status?.webrtc_outbound_packets_sent || 0),
        outbound_kbps: Number.isFinite(Number(status?.webrtc_outbound_kbps)) ? Number(status.webrtc_outbound_kbps).toFixed(1) : "-",
        outbound_fps: Number.isFinite(Number(status?.webrtc_outbound_fps)) ? Number(status.webrtc_outbound_fps).toFixed(2) : "-",
        outbound_rtt_ms: Number.isFinite(Number(status?.webrtc_outbound_rtt_ms)) ? Number(status.webrtc_outbound_rtt_ms).toFixed(1) : "-",
      }, { console: false })
    }
    render()
    state.nativeSenderStatusTimer = window.setTimeout(() => {
      void tick()
    }, NATIVE_SENDER_STATUS_POLL_INTERVAL_MS)
  }

  state.nativeSenderStatusTimer = window.setTimeout(() => {
    void tick()
  }, NATIVE_SENDER_STATUS_POLL_INTERVAL_MS)
}

function stopNativeSenderSignalProbe() {
  if (state.nativeSenderSignalTimer) {
    window.clearTimeout(state.nativeSenderSignalTimer)
    state.nativeSenderSignalTimer = null
  }
  state.nativeSenderSignalLoopId += 1
}

function stopNativeSenderPreviewProbe() {
  if (state.nativeSenderPreviewTimer) {
    window.clearTimeout(state.nativeSenderPreviewTimer)
    state.nativeSenderPreviewTimer = null
  }
  state.nativeSenderPreviewLoopId += 1
  if (state.nativeSenderPreviewStream) {
    try {
      state.nativeSenderPreviewStream.getTracks?.().forEach((track) => track.stop?.())
    } catch {
      // ignore preview stream stop failures
    }
    if (state.localMediaStream === state.nativeSenderPreviewStream) {
      state.localMediaStream = null
    }
    state.nativeSenderPreviewStream = null
  }
  if (state.nativeSenderPreviewVideo) {
    try {
      state.nativeSenderPreviewVideo.pause?.()
      state.nativeSenderPreviewVideo.srcObject = null
      state.nativeSenderPreviewVideo.src = ""
      state.nativeSenderPreviewVideo.load?.()
    } catch {
      // ignore preview video cleanup failures
    }
    state.nativeSenderPreviewVideo = null
  }
  state.nativeSenderPreviewMode = ""
}

async function sampleNativeSenderPreviewFrame(normalizedSessionId) {
  const frame = await invoke("capture_take_frame")
  const mimeType = `${frame?.mime_type || "image/jpeg"}`.trim().toLowerCase() || "image/jpeg"
  const frameId = `${frame?.frame_id || `native-preview-${Date.now()}`}`
  const captureTs = Number(frame?.capture_ts)
  const frameWidth = Number(frame?.frame_width || 0)
  const frameHeight = Number(frame?.frame_height || 0)
  const contentB64 = `${frame?.content_b64 || ""}`
  if (!contentB64) {
    throw new Error("preview frame payload missing")
  }

  if (mimeType === NATIVE_CAPTURE_RAW_BGRA_MIME) {
    const bytes = decodeBase64ToBytes(contentB64)
    const rendered = renderRawBgraPreviewDataUrl(
      bytes,
      frameWidth,
      frameHeight,
      state.nativeSenderPreviewCanvas,
    )
    state.nativeSenderPreviewCanvas = rendered.canvas
    state.localFrameUrl = rendered.dataUrl
  } else if (mimeType.startsWith("image/")) {
    state.localFrameUrl = `data:${mimeType};base64,${contentB64}`
  } else {
    throw new Error(`preview mime not supported: ${mimeType}`)
  }

  state.localFrameMeta = {
    frameId,
    captureTs: Number.isFinite(captureTs) && captureTs > 0 ? captureTs : Date.now(),
    width: frameWidth,
    height: frameHeight,
  }
  traceLog("native.sender.preview.frame_poll_sample", {
    session_id: normalizedSessionId,
    mime_type: mimeType,
    size: `${frameWidth}x${frameHeight}`,
  }, { console: false })
}

function startNativeSenderPreviewFramePolling(loopId, normalizedSessionId) {
  const tick = async () => {
    if (
      state.nativeSenderPreviewLoopId !== loopId
      || !isAgentSession()
      || state.sessionId !== normalizedSessionId
      || !nativeSenderOwnershipEnabledForSession(normalizedSessionId)
    ) {
      return
    }
    try {
      await sampleNativeSenderPreviewFrame(normalizedSessionId)
      if (state.nativeSenderPreviewMode !== "frame_polling") {
        state.nativeSenderPreviewMode = "frame_polling"
      }
      render()
    } catch (error) {
      traceLog("native.sender.preview.frame_poll_failed", {
        session_id: normalizedSessionId,
        reason: error?.message || "unknown",
      }, { console: false })
    }
    state.nativeSenderPreviewTimer = window.setTimeout(() => {
      void tick()
    }, NATIVE_SENDER_PREVIEW_FRAME_POLL_INTERVAL_MS)
  }

  state.nativeSenderPreviewTimer = window.setTimeout(() => {
    void tick()
  }, 60)
}

async function startNativeSenderPreviewProbe(sessionId) {
  stopNativeSenderPreviewProbe()
  const normalizedSessionId = `${sessionId || ""}`.trim()
  if (!isTauri() || !normalizedSessionId || !nativeSenderOwnershipEnabledForSession(normalizedSessionId)) {
    return
  }
  const loopId = state.nativeSenderPreviewLoopId + 1
  state.nativeSenderPreviewLoopId = loopId
  try {
    const endpoint = await invoke("capture_get_stream_endpoint")
    const streamUrl = typeof endpoint?.url === "string" ? endpoint.url.trim() : ""
    if (!streamUrl) {
      throw new Error("capture_get_stream_endpoint 返回空地址")
    }
    const fps = Math.max(1, Number(state.captureStatus?.config?.max_fps) || 24)
    traceLog("native.sender.preview.bootstrap", {
      session_id: normalizedSessionId,
      stream_url: streamUrl,
      fps_target: fps,
      mode: "start",
    }, { console: false })

    if (!state.nativeSenderPreviewVideoModeDisabled) {
      try {
        const previewVideo = document.createElement("video")
        previewVideo.autoplay = true
        previewVideo.muted = true
        previewVideo.playsInline = true
        previewVideo.crossOrigin = "anonymous"
        previewVideo.src = streamUrl
        await previewVideo.play().catch(() => {})
        try {
          await waitForStreamVideoReady(previewVideo, { timeoutMs: 3500 })
        } catch (error) {
          throw new Error(`preview_ready_failed:${error?.message || "unknown"}`)
        }
        try {
          await waitForStreamVideoFirstFrame(previewVideo, { timeoutMs: 2200 })
        } catch (error) {
          throw new Error(`preview_first_frame_failed:${error?.message || "unknown"}`)
        }

        const captureStreamFactory = typeof previewVideo.captureStream === "function"
          ? previewVideo.captureStream.bind(previewVideo)
          : (typeof previewVideo.webkitCaptureStream === "function" ? previewVideo.webkitCaptureStream.bind(previewVideo) : null)
        if (!captureStreamFactory) {
          throw new Error("preview_capture_stream_unavailable")
        }
        const previewStream = captureStreamFactory(fps)
        const previewTrack = previewStream.getVideoTracks?.()[0] || null
        if (!previewTrack) {
          throw new Error("preview_media_stream_missing_video_track")
        }
        if (
          state.nativeSenderPreviewLoopId !== loopId
          || state.sessionId !== normalizedSessionId
          || !nativeSenderOwnershipEnabledForSession(normalizedSessionId)
        ) {
          previewStream.getTracks?.().forEach((track) => track.stop?.())
          throw new Error("preview loop invalidated")
        }
        state.nativeSenderPreviewVideo = previewVideo
        state.nativeSenderPreviewStream = previewStream
        state.nativeSenderPreviewMode = "media_stream"
        state.localMediaStream = previewStream
        state.localFrameUrl = ""
        state.localFrameMeta = {
          frameId: `native-preview-stream-${Date.now()}`,
          captureTs: Date.now(),
          width: Number(previewVideo.videoWidth || 0),
          height: Number(previewVideo.videoHeight || 0),
        }
        previewTrack.onended = () => {
          traceLog("native.sender.preview.track_ended", {
            session_id: normalizedSessionId,
            track_id: previewTrack.id || "-",
          }, { console: false })
        }
        traceLog("native.sender.preview.started", {
          session_id: normalizedSessionId,
          mode: "media_stream",
          track_id: previewTrack.id || "-",
          frame_size: `${previewVideo.videoWidth || 0}x${previewVideo.videoHeight || 0}`,
        }, { console: false })
        render()
        return
      } catch (error) {
        const reason = error?.message || "unknown"
        if (
          reason.startsWith("preview_ready_failed:")
          || reason.startsWith("preview_first_frame_failed:")
          || reason.includes("preview_capture_stream_unavailable")
        ) {
          state.nativeSenderPreviewVideoModeDisabled = true
          traceLog("native.sender.preview.video_mode_disabled", {
            session_id: normalizedSessionId,
            reason,
          }, { console: false })
        }
        traceLog("native.sender.preview.stream_unavailable", {
          session_id: normalizedSessionId,
          reason,
        }, { console: false })
      }
    } else {
      traceLog("native.sender.preview.video_mode_skipped", {
        session_id: normalizedSessionId,
        reason: "preview_video_mode_disabled",
      }, { console: false })
    }

    if (
      state.nativeSenderPreviewLoopId !== loopId
      || state.sessionId !== normalizedSessionId
      || !nativeSenderOwnershipEnabledForSession(normalizedSessionId)
    ) {
      return
    }
    state.localMediaStream = null
    try {
      await sampleNativeSenderPreviewFrame(normalizedSessionId)
      state.nativeSenderPreviewMode = "frame_polling"
      traceLog("native.sender.preview.started", {
        session_id: normalizedSessionId,
        mode: "frame_polling",
        stream_url: streamUrl,
      }, { console: false })
      render()
      startNativeSenderPreviewFramePolling(loopId, normalizedSessionId)
      return
    } catch (frameError) {
      state.localFrameUrl = streamUrl
      state.localFrameMeta = {
        frameId: `native-preview-mjpeg-${Date.now()}`,
        captureTs: Date.now(),
        width: 0,
        height: 0,
      }
      state.nativeSenderPreviewMode = "mjpeg_stream"
      traceLog("native.sender.preview.started", {
        session_id: normalizedSessionId,
        mode: "mjpeg_stream",
        stream_url: streamUrl,
        frame_poll_reason: frameError?.message || "unknown",
      }, { console: false })
      render()
    }
  } catch (error) {
    traceLog("native.sender.preview.bootstrap_failed", {
      session_id: normalizedSessionId,
      reason: error?.message || "unknown",
    }, { console: false })
  }
}

function nativeSenderOwnershipEnabledForSession(sessionId = state.sessionId) {
  const normalizedSessionId = `${sessionId || ""}`.trim()
  if (!isTauri() || !normalizedSessionId || !isAgentSession()) {
    return false
  }
  const lifecycle = `${state.nativeSenderStatus?.lifecycle || ""}`.trim().toLowerCase()
  const ownerDryRun = Boolean(state.nativeSenderStatus?.dry_run)
  return Boolean(state.nativeSenderCapabilities?.supported && lifecycle === "running" && !ownerDryRun && state.sessionId === normalizedSessionId)
}

async function drainNativeSenderOutboundSignals(sessionId, options = {}) {
  const normalizedSessionId = `${sessionId || ""}`.trim()
  if (!isTauri() || !normalizedSessionId || !nativeSenderOwnershipEnabledForSession(normalizedSessionId)) {
    return 0
  }
  let sent = 0
  try {
    const signals = await invoke("native_sender_drain_outbound_signals", {
      request: {
        session_id: normalizedSessionId,
        limit: 96,
      },
    })
    const list = Array.isArray(signals) ? signals : []
    for (const signal of list) {
      const signalType = `${signal?.signal_type || ""}`.trim()
      if (!signalType) {
        continue
      }
      let payload = {}
      if (signalType === "webrtc.offer" || signalType === "webrtc.answer") {
        payload = {
          sdp_type: signalType === "webrtc.offer" ? "offer" : "answer",
          sdp: `${signal?.sdp || ""}`,
        }
      } else if (signalType === "webrtc.ice_candidate") {
        const rawMlineIndex = Number(signal?.sdp_mline_index)
        const sdpMlineIndex = Number.isFinite(rawMlineIndex) && rawMlineIndex >= 0 ? rawMlineIndex : 0
        payload = {
          candidate: `${signal?.candidate || ""}`,
          sdp_mid: `${signal?.sdp_mid || "0"}`,
          sdp_mline_index: sdpMlineIndex,
        }
      } else if (signalType === "webrtc.restart_ice") {
        payload = {}
      } else {
        continue
      }
      const ok = sendEnvelope(
        signalType,
        payload,
        normalizedSessionId,
        {
          log: false,
          skipNativeMirror: true,
          logLine: `发送 ${signalType} (native-owner)`,
        },
      )
      if (ok) {
        sent += 1
      }
    }
    if (sent > 0 && options.log !== false) {
      traceLog("native.sender.outbound.forwarded", {
        session_id: normalizedSessionId,
        count: sent,
      }, { console: false })
    }
  } catch (error) {
    state.nativeSenderError = error?.message || "native sender drain outbound signals failed"
    traceLog("native.sender.outbound.forward_failed", {
      session_id: normalizedSessionId,
      reason: state.nativeSenderError,
    })
  }
  return sent
}

function startNativeSenderSignalProbe(sessionId) {
  stopNativeSenderSignalProbe()
  const normalizedSessionId = `${sessionId || ""}`.trim()
  if (!isTauri() || !normalizedSessionId || !nativeSenderOwnershipEnabledForSession(normalizedSessionId)) {
    return
  }
  const loopId = state.nativeSenderSignalLoopId + 1
  state.nativeSenderSignalLoopId = loopId
  const tick = async () => {
    if (
      state.nativeSenderSignalLoopId !== loopId
      || !nativeSenderOwnershipEnabledForSession(normalizedSessionId)
      || state.sessionId !== normalizedSessionId
    ) {
      return
    }
    await drainNativeSenderOutboundSignals(normalizedSessionId, { log: true })
    state.nativeSenderSignalTimer = window.setTimeout(() => {
      void tick()
    }, NATIVE_SENDER_SIGNAL_POLL_INTERVAL_MS)
  }
  state.nativeSenderSignalTimer = window.setTimeout(() => {
    void tick()
  }, 30)
}

async function startNativeSenderControlPlane(sessionId, options = {}) {
  const normalizedSessionId = `${sessionId || ""}`.trim()
  if (!isTauri() || !normalizedSessionId) {
    return false
  }
  await refreshNativeSenderState({ log: false })
  if (!state.nativeSenderCapabilities.supported && !NATIVE_SENDER_CONTROL_PLANE_DRY_RUN) {
    return false
  }
  const dryRun = options.dryRun === true || !state.nativeSenderCapabilities.supported
  try {
    const status = await invoke("native_sender_start", {
      request: {
        session_id: normalizedSessionId,
        dry_run: dryRun,
        ice_servers: sessionIceServers(),
      },
    })
    state.nativeSenderStatus = normalizeNativeSenderStatus(status)
    state.nativeSenderError = ""
    appendLog(`原生 sender 控制面已启动: session=${normalizedSessionId} dry_run=${dryRun ? 1 : 0}`)
    traceLog("native.sender.start_ok", {
      session_id: normalizedSessionId,
      dry_run: dryRun ? 1 : 0,
      lifecycle: state.nativeSenderStatus.lifecycle || "-",
    })
    startNativeSenderStatusProbe(normalizedSessionId)
    startNativeSenderSignalProbe(normalizedSessionId)
    if (NATIVE_SENDER_LOCAL_PREVIEW_ENABLED) {
      void startNativeSenderPreviewProbe(normalizedSessionId)
    } else {
      stopNativeSenderPreviewProbe()
      state.localMediaStream = null
      state.localFrameUrl = ""
      state.localFrameMeta = null
      state.nativeSenderPreviewMode = "disabled"
      traceLog("native.sender.preview.disabled", {
        session_id: normalizedSessionId,
        reason: "debug_preview_disabled",
      }, { console: false })
    }
    if (!dryRun) {
      await drainNativeSenderOutboundSignals(normalizedSessionId, { log: true })
    }
    return true
  } catch (error) {
    state.nativeSenderError = error?.message || "native sender start failed"
    appendLog(`原生 sender 控制面启动失败: ${state.nativeSenderError}`)
    traceLog("native.sender.start_failed", {
      session_id: normalizedSessionId,
      reason: state.nativeSenderError,
    })
    return false
  }
}

async function pushNativeSenderSignal(
  sessionId,
  signalType,
  traceId = "",
  signalPayload = null,
  signalDirection = "inbound",
) {
  const normalizedSessionId = `${sessionId || ""}`.trim()
  const normalizedType = `${signalType || ""}`.trim()
  if (!isTauri() || !normalizedSessionId || !normalizedType) {
    return false
  }
  const lifecycle = `${state.nativeSenderStatus?.lifecycle || ""}`.trim()
  if (!lifecycle || lifecycle === "idle") {
    return false
  }
  const payload = signalPayload && typeof signalPayload === "object" ? signalPayload : {}
  const payloadSdp = typeof payload.sdp === "string" ? payload.sdp : ""
  const payloadCandidate = typeof payload.candidate === "string" ? payload.candidate : ""
  const payloadSdpMid = typeof payload.sdp_mid === "string"
    ? payload.sdp_mid
    : (typeof payload.sdpMid === "string" ? payload.sdpMid : "")
  const rawSdpMlineIndex = payload.sdp_mline_index ?? payload.sdpMlineIndex
  const payloadSdpMlineIndex = Number.isFinite(Number(rawSdpMlineIndex))
    ? Number(rawSdpMlineIndex)
    : -1
  const normalizedDirection = `${signalDirection || "inbound"}`.trim() || "inbound"

  try {
    const status = await invoke("native_sender_push_signal", {
      signal: {
        session_id: normalizedSessionId,
        signal_type: normalizedType,
        signal_direction: normalizedDirection,
        trace_id: `${traceId || ""}`,
        sdp: payloadSdp,
        candidate: payloadCandidate,
        sdp_mid: `${payloadSdpMid || ""}`,
        sdp_mline_index: payloadSdpMlineIndex,
      },
    })
    state.nativeSenderStatus = normalizeNativeSenderStatus(status)
    state.nativeSenderError = ""
    traceLog("native.sender.signal_pushed", {
      session_id: normalizedSessionId,
      signal_type: normalizedType,
      signal_count: state.nativeSenderStatus.signal_count || 0,
      lifecycle: state.nativeSenderStatus.lifecycle || "-",
      signaling_state: state.nativeSenderStatus.signaling_state || "-",
      signal_direction: normalizedDirection,
      payload_bytes: state.nativeSenderStatus.last_signal_payload_bytes || 0,
      trace_id: `${traceId || "-"}`,
    })
    return true
  } catch (error) {
    state.nativeSenderError = error?.message || "native sender signal push failed"
    traceLog("native.sender.signal_push_failed", {
      session_id: normalizedSessionId,
      signal_type: normalizedType,
      reason: state.nativeSenderError,
    })
    return false
  }
}

async function stopNativeSenderControlPlane(reason = "session_end") {
  stopNativeSenderStatusProbe()
  stopNativeSenderSignalProbe()
  stopNativeSenderPreviewProbe()
  state.nativeSenderPreviewCanvas = null
  if (!isTauri()) {
    return false
  }
  const lifecycle = `${state.nativeSenderStatus?.lifecycle || ""}`.trim()
  if (!lifecycle || lifecycle === "idle") {
    return false
  }
  try {
    const status = await invoke("native_sender_stop", { reason })
    state.nativeSenderStatus = normalizeNativeSenderStatus(status)
    state.nativeSenderError = ""
    traceLog("native.sender.stop_ok", {
      reason,
      lifecycle: state.nativeSenderStatus.lifecycle || "-",
    })
    return true
  } catch (error) {
    state.nativeSenderError = error?.message || "native sender stop failed"
    traceLog("native.sender.stop_failed", {
      reason,
      error: state.nativeSenderError,
    })
    return false
  }
}

function normalizeCaptureStatus(status) {
  if (!status || typeof status !== "object") {
    return emptyCaptureStatus()
  }

  const base = emptyCaptureStatus()
  const capabilities = status.capabilities && typeof status.capabilities === "object"
    ? { ...base.capabilities, ...status.capabilities }
    : base.capabilities

  return {
    ...base,
    ...status,
    capabilities,
    backend: typeof status.backend === "string" && status.backend ? status.backend : capabilities.backend,
    config: status.config && typeof status.config === "object"
      ? { ...base.config, ...status.config }
      : base.config,
    permission: status.permission && typeof status.permission === "object"
      ? { ...base.permission, ...status.permission }
      : base.permission,
    supports_frame_streaming: typeof status.supports_frame_streaming === "boolean"
      ? status.supports_frame_streaming
      : capabilities.supports_frame_streaming,
    supports_pause_resume: typeof status.supports_pause_resume === "boolean"
      ? status.supports_pause_resume
      : capabilities.supports_pause_resume,
  }
}

function syncCaptureStatus(status) {
  state.captureStatus = normalizeCaptureStatus(status)
  const source = currentCaptureSource()
  state.captureLabel = source ? `${source.title} (${source.width}x${source.height})` : ""
  if (state.captureStatus.config?.codec) {
    state.defaultCodec = state.captureStatus.config.codec
  }

  if (state.captureStatus.last_error_detail) {
    state.captureError = state.captureStatus.last_error_detail
    return
  }

  const permissionStatus = state.captureStatus.permission?.status || ""
  const permissionDetail = state.captureStatus.permission?.detail || ""
  if (permissionStatus && permissionStatus !== "granted" && permissionStatus !== "unknown" && permissionDetail) {
    state.captureError = permissionDetail
    return
  }

  const unsupportedSourceMessage = nativeCaptureUnsupportedSourceMessage(source)
  if (unsupportedSourceMessage) {
    state.captureError = unsupportedSourceMessage
    return
  }

  state.captureError = ""
}

function syncCaptureSources(sources) {
  state.captureSources = Array.isArray(sources) ? sources.filter(Boolean) : []
}

async function refreshCaptureState(options = {}) {
  if (!isTauri()) {
    return state.captureStatus
  }

  try {
    syncPlatformCapabilities(await invoke("platform_get_capabilities"))
    syncCaptureStatus(await invoke("capture_status"))
    if (currentPlatformCaptureCapabilities().supports_source_listing) {
      syncCaptureSources(await invoke("capture_list_sources"))
    } else {
      syncCaptureSources([])
    }
    syncRegisteredCapabilitiesIfChanged()
  } catch (error) {
    syncCaptureSources([])
    state.captureError = error?.message || "读取桌面采集状态失败"
    if (options.log !== false) {
      appendLog(`读取桌面采集状态失败: ${state.captureError}`)
    }
  }

  return state.captureStatus
}

function normalizeWindowsSelfTestReport(report) {
  if (!report || typeof report !== "object") {
    return emptyWindowsSelfTestReport()
  }
  const base = emptyWindowsSelfTestReport()
  return {
    ...base,
    ...report,
    ok: Boolean(report.ok),
    duration_ms: Number.isFinite(Number(report.duration_ms)) ? Number(report.duration_ms) : 0,
    frame_width: Number.isFinite(Number(report.frame_width)) ? Number(report.frame_width) : 0,
    frame_height: Number.isFinite(Number(report.frame_height)) ? Number(report.frame_height) : 0,
    frame_bytes: Number.isFinite(Number(report.frame_bytes)) ? Number(report.frame_bytes) : 0,
    native_sender_signal_count: Number.isFinite(Number(report.native_sender_signal_count)) ? Number(report.native_sender_signal_count) : 0,
    native_sender_offer_count: Number.isFinite(Number(report.native_sender_offer_count)) ? Number(report.native_sender_offer_count) : 0,
    native_sender_probe_frame_count: Number.isFinite(Number(report.native_sender_probe_frame_count)) ? Number(report.native_sender_probe_frame_count) : 0,
    native_sender_probe_total_bytes: Number.isFinite(Number(report.native_sender_probe_total_bytes)) ? Number(report.native_sender_probe_total_bytes) : 0,
    checks: Array.isArray(report.checks)
      ? report.checks.map((check) => ({
          name: `${check?.name || ""}`,
          ok: Boolean(check?.ok),
          status: `${check?.status || ""}`,
          detail: `${check?.detail || ""}`,
        }))
      : [],
  }
}

function windowsSelfTestFailedChecks(report = state.windowsSelfTestReport) {
  return (report?.checks || []).filter((check) => !check.ok)
}

function isWindowsSelfTestCheckSkipped(check) {
  return `${check?.status || ""}`.trim().toLowerCase() === "skipped"
}

function windowsSelfTestCheckTone(check) {
  if (isWindowsSelfTestCheckSkipped(check)) {
    return "self-test-skip"
  }
  return check?.ok ? "self-test-pass" : "self-test-fail"
}

function windowsSelfTestCheckLabel(check) {
  if (isWindowsSelfTestCheckSkipped(check)) {
    return "SKIP"
  }
  return check?.ok ? "PASS" : "FAIL"
}

async function runWindowsSelfTest() {
  if (!isTauri()) {
    state.windowsSelfTestError = "桌面自检需要在 Tauri 桌面壳层内运行"
    appendLog(state.windowsSelfTestError)
    render()
    return null
  }
  if (state.sessionId) {
    state.windowsSelfTestError = "请先结束当前会话，再运行桌面自检"
    appendLog(state.windowsSelfTestError)
    render()
    return null
  }

  state.windowsSelfTestRunning = true
  state.windowsSelfTestError = ""
  render()
  try {
    const report = normalizeWindowsSelfTestReport(await invoke("desktop_self_test"))
    state.windowsSelfTestReport = report
    const failedChecks = windowsSelfTestFailedChecks(report)
    if (report.ok) {
      appendLog(`桌面自检通过：${report.frame_width}x${report.frame_height} ${report.frame_mime_type || "-"}，${report.frame_bytes} bytes`)
    } else {
      appendLog(`桌面自检未通过：${failedChecks.map((check) => check.name).join(", ") || "unknown"}`)
    }
    traceLog("desktop.self_test.completed", {
      ok: report.ok ? 1 : 0,
      platform: report.platform || "-",
      capture_backend: report.capture_backend || "-",
      host_input_backend: report.host_input_backend || "-",
      source_id: report.source_id || "-",
      frame_size: `${report.frame_width || 0}x${report.frame_height || 0}`,
      frame_bytes: report.frame_bytes || 0,
      failed_checks: failedChecks.map((check) => check.name).join("|") || "-",
    })
    await refreshCaptureState({ log: false })
    return report
  } catch (error) {
    state.windowsSelfTestError = error?.message || "桌面自检失败"
    appendLog(`桌面自检失败：${state.windowsSelfTestError}`)
    traceLog("desktop.self_test.failed", {
      error: state.windowsSelfTestError,
    })
    return null
  } finally {
    state.windowsSelfTestRunning = false
    render()
  }
}

async function stopNativeCapture(options = {}) {
  if (!isTauri()) {
    return false
  }

  const hadActiveSource = Boolean(currentCaptureSource())
  try {
    syncCaptureStatus(await invoke("capture_stop"))
    state.localFrameUrl = ""
    state.localFrameMeta = null
    await refreshCaptureState({ log: false })
    if (options.log !== false && hadActiveSource) {
      appendLog("已停止桌面原生采集")
    }
    render()
    return true
  } catch (error) {
    state.captureError = error?.message || "停止桌面原生采集失败"
    if (options.log !== false) {
      appendLog(`停止桌面原生采集失败: ${state.captureError}`)
    }
    render()
    return false
  }
}

async function requestNativeCaptureSource(options = {}) {
  if (!isTauri()) {
    return false
  }

  await refreshCaptureState({ log: false })

  const captureCapabilities = currentPlatformCaptureCapabilities()
  if (!captureCapabilities.supports_source_listing) {
    state.captureError = captureCapabilities.support_detail || `当前桌面壳层尚未实现 ${state.shellPlatform} 采集源管理`
    if (options.log !== false) {
      appendLog(state.captureError)
    }
    render()
    return false
  }

  let permission = state.captureStatus.permission || emptyCaptureStatus().permission
  if (permission.status !== "granted") {
    if (permission.can_request) {
      try {
        await invoke("capture_request_permission")
      } catch (error) {
        state.captureError = error?.message || "请求桌面采集权限失败"
        if (options.log !== false) {
          appendLog(`请求桌面采集权限失败: ${state.captureError}`)
        }
        render()
        return false
      }
      await refreshCaptureState({ log: false })
      permission = state.captureStatus.permission || permission
    }

    if (permission.status !== "granted") {
      state.captureError = permission.detail || "桌面采集权限未就绪"
      if (options.log !== false) {
        appendLog(`桌面采集权限未就绪: ${state.captureError}`)
      }
      render()
      return false
    }
  }

  const preferredSource = preferredNativeCaptureSource()
  if (!preferredSource) {
    state.captureError = "当前未发现可用桌面采集源，尝试自动恢复中"
  }
  const unsupportedSourceMessage = preferredSource ? nativeCaptureUnsupportedSourceMessage(preferredSource) : ""
  if (unsupportedSourceMessage) {
    state.captureError = unsupportedSourceMessage
    if (options.log !== false) {
      appendLog(unsupportedSourceMessage)
    }
    render()
    return false
  }

  const activeSource = currentCaptureSource()
  const preferredSourceId = preferredSource?.source_id || ""
  const lifecycle = `${state.captureStatus?.lifecycle || ""}`
  const sourceSessionStable = ["starting", "running", "ready", "paused"].includes(lifecycle)
  if (
    activeSource
    && activeSource.source_id === preferredSourceId
    && (nativeCaptureSourceReady() || sourceSessionStable)
  ) {
    if (options.log !== false) {
      appendLog(`桌面采集源已就绪: ${state.captureLabel}`)
      render()
    }
    return true
  }

  try {
    syncCaptureStatus(await invoke("capture_start", {
      request: {
        sourceId: preferredSource?.source_id || "",
      },
    }))
    await refreshCaptureState({ log: false })
  } catch (error) {
    state.captureError = error?.message || "准备桌面采集源失败"
    if (options.log !== false) {
      appendLog(`准备桌面采集源失败: ${state.captureError}`)
    }
    render()
    return false
  }

  if (!currentCaptureSource()) {
    state.captureError = state.captureError || "未找到可用桌面采集源"
    if (options.log !== false) {
      appendLog(state.captureError)
    }
    render()
    return false
  }

  const readinessMessage = nativeCaptureReadinessMessage()
  if (readinessMessage) {
    state.captureError = readinessMessage
    if (options.log !== false) {
      appendLog(readinessMessage)
    }
    render()
    return false
  }

  if (options.log !== false) {
    appendLog(`已准备桌面采集源: ${state.captureLabel}`)
  }
  render()
  return true
}

async function applyNativeSenderCaptureConfig(sessionId, options = {}) {
  if (!isTauri()) {
    return
  }
  const profile = buildEffectiveAdaptiveProfile(options.profile || currentEffectiveAgentAdaptiveProfile(), {
    allowAndroidFullscreenDetail: Boolean(options.allowAndroidFullscreenDetail),
    allowAndroidZoomDetail: Boolean(options.allowAndroidZoomDetail),
    allowAndroidZoomStill: Boolean(options.allowAndroidZoomStill),
    androidPhoneZoomMotion: Boolean(options.androidPhoneZoomMotion),
  })
  const patch = {
    maxWidth: profile.maxWidth,
    maxHeight: profile.maxHeight,
    maxFps: Math.max(1, Math.min(profile.maxFps, CAPTURE_DIRECT_TRACK_MAX_FPS)),
    // 作者: long；WebRTC/H.264 native sender 是当前主链路，受控端直接取 raw BGRA 做 H.264 编码；legacy JPEG 只保留为首帧失败后的独立兜底流。
    codec: NATIVE_CAPTURE_RAW_BGRA_CODEC,
    ...androidPhoneCaptureSourceRectPatch(options),
  }
  try {
    syncCaptureStatus(await invoke("capture_update_config", { patch }))
    if (currentSessionControllerProfile() === "android_phone") {
      // 作者: long；切换交互/全屏/高清档后，下一帧必须按新采集尺寸发送，避免兜底流继续复用上一档 capture_ts。
      state.streamLastSentCaptureTs = 0
    }
    traceLog("native.capture.sender_config.applied", {
      session_id: `${sessionId || state.sessionId || "-"}`,
      max_width: patch.maxWidth,
      max_height: patch.maxHeight,
      max_fps: patch.maxFps,
      codec: patch.codec,
      source_rect: `${patch.sourceRectXPpm},${patch.sourceRectYPpm},${patch.sourceRectWidthPpm},${patch.sourceRectHeightPpm}`,
      reason: options.reason || "-",
    }, { console: false })
  } catch (error) {
    const detail = error?.message || "unknown"
    traceLog("native.capture.sender_config.failed", {
      session_id: `${sessionId || state.sessionId || "-"}`,
      reason: detail,
      codec: patch.codec,
    })
    if (options.log !== false) {
      appendLog(`原生 sender 采集配置下发失败: ${detail}`)
    }
  }
}

function buildAndroidPhoneInteractiveNativeProfile() {
  const clearProfile = buildEffectiveAdaptiveProfile(AGENT_ADAPTIVE_PROFILES[ANDROID_PHONE_SESSION_PROFILE_INDEX])
  return {
    ...clearProfile,
    id: "android_phone_interactive",
    label: "真机交互流畅",
    maxWidth: Math.min(clearProfile.maxWidth, ANDROID_PHONE_INTERACTIVE_MAX_WIDTH),
    maxHeight: Math.min(clearProfile.maxHeight, ANDROID_PHONE_INTERACTIVE_MAX_HEIGHT),
    maxFps: Math.min(clearProfile.maxFps, ANDROID_PHONE_INTERACTIVE_MAX_FPS),
  }
}

function buildAndroidPhoneFullscreenNativeProfile() {
  const clearProfile = buildEffectiveAdaptiveProfile(AGENT_ADAPTIVE_PROFILES[ANDROID_PHONE_SESSION_PROFILE_INDEX], {
    allowAndroidFullscreenDetail: true,
  })
  return {
    ...clearProfile,
    id: "android_phone_fullscreen",
    label: "真机全屏高清",
    maxWidth: Math.min(clearProfile.maxWidth, ANDROID_PHONE_FULLSCREEN_MAX_WIDTH),
    maxHeight: Math.min(clearProfile.maxHeight, ANDROID_PHONE_FULLSCREEN_MAX_HEIGHT),
    maxFps: Math.min(clearProfile.maxFps, ANDROID_PHONE_FULLSCREEN_MAX_FPS),
    maxBitrate: Math.min(clearProfile.maxBitrate, ANDROID_PHONE_FULLSCREEN_MAX_BITRATE),
    scaleResolutionDownBy: 1,
  }
}

function buildAndroidPhonePinchPreviewNativeProfile() {
  const clearProfile = AGENT_ADAPTIVE_PROFILES[ANDROID_PHONE_SESSION_PROFILE_INDEX]
  return {
    ...clearProfile,
    id: "android_phone_pinch_preview",
    label: "真机缩放轻量预览",
    maxWidth: ANDROID_PHONE_PINCH_PREVIEW_MAX_WIDTH,
    maxHeight: ANDROID_PHONE_PINCH_PREVIEW_MAX_HEIGHT,
    maxFps: ANDROID_PHONE_PINCH_PREVIEW_MAX_FPS,
    maxBitrate: ANDROID_PHONE_PINCH_PREVIEW_MAX_BITRATE,
    scaleResolutionDownBy: 1,
  }
}

function buildAndroidPhoneZoomMotionNativeProfile() {
  const clearProfile = AGENT_ADAPTIVE_PROFILES[ANDROID_PHONE_SESSION_PROFILE_INDEX]
  return {
    ...clearProfile,
    id: "android_phone_zoom_motion",
    label: "真机局部跟手",
    maxWidth: ANDROID_PHONE_ZOOM_MOTION_MAX_WIDTH,
    maxHeight: ANDROID_PHONE_ZOOM_MOTION_MAX_HEIGHT,
    maxFps: ANDROID_PHONE_ZOOM_MOTION_MAX_FPS,
    maxBitrate: ANDROID_PHONE_ZOOM_MOTION_MAX_BITRATE,
    scaleResolutionDownBy: 1,
  }
}

function buildAndroidPhoneZoomDetailNativeProfile() {
  const clearProfile = AGENT_ADAPTIVE_PROFILES[ANDROID_PHONE_SESSION_PROFILE_INDEX]
  return {
    ...clearProfile,
    id: "android_phone_zoom_detail",
    label: "真机局部高清",
    maxWidth: ANDROID_PHONE_ZOOM_DETAIL_MAX_WIDTH,
    maxHeight: ANDROID_PHONE_ZOOM_DETAIL_MAX_HEIGHT,
    maxFps: ANDROID_PHONE_ZOOM_DETAIL_MAX_FPS,
    maxBitrate: ANDROID_PHONE_ZOOM_DETAIL_MAX_BITRATE,
    scaleResolutionDownBy: 1,
  }
}

function buildAndroidPhoneZoomStillNativeProfile() {
  const clearProfile = AGENT_ADAPTIVE_PROFILES[ANDROID_PHONE_SESSION_PROFILE_INDEX]
  return {
    ...clearProfile,
    id: "android_phone_zoom_still",
    label: "真机局部静止高清",
    maxWidth: ANDROID_PHONE_ZOOM_STILL_MAX_WIDTH,
    maxHeight: ANDROID_PHONE_ZOOM_STILL_MAX_HEIGHT,
    maxFps: ANDROID_PHONE_ZOOM_STILL_MAX_FPS,
    maxBitrate: ANDROID_PHONE_ZOOM_STILL_MAX_BITRATE,
    scaleResolutionDownBy: 1,
  }
}

function clearNativeSenderInteractiveRestoreTimer() {
  if (state.nativeSenderInteractiveRestoreTimer) {
    window.clearTimeout(state.nativeSenderInteractiveRestoreTimer)
    state.nativeSenderInteractiveRestoreTimer = null
  }
}

function clearNativeSenderZoomDetailUpgradeTimer() {
  if (state.nativeSenderZoomDetailUpgradeTimer) {
    window.clearTimeout(state.nativeSenderZoomDetailUpgradeTimer)
    state.nativeSenderZoomDetailUpgradeTimer = null
  }
}

function clearNativeSenderZoomStillUpgradeTimer() {
  if (state.nativeSenderZoomStillUpgradeTimer) {
    window.clearTimeout(state.nativeSenderZoomStillUpgradeTimer)
    state.nativeSenderZoomStillUpgradeTimer = null
  }
}

function resetNativeSenderInteractiveProfileState() {
  clearNativeSenderInteractiveRestoreTimer()
  clearNativeSenderZoomDetailUpgradeTimer()
  clearNativeSenderZoomStillUpgradeTimer()
  state.nativeSenderInteractiveProfileActive = false
  state.nativeSenderInteractiveSessionId = ""
  state.nativeSenderInteractiveProfileMode = ""
  state.nativeSenderInteractiveSourceRectKey = ""
  state.nativeSenderInteractiveSourceRectUpdatedAt = 0
  state.nativeSenderFullscreenProfileActive = false
  state.nativeSenderFullscreenSessionId = ""
  state.nativeSenderZoomDetailSessionId = ""
  state.lastAndroidViewportInteraction = null
}

function shouldUseNativeSenderInteractiveProfileForInput(msg) {
  if (!isTauri() || !isAgentSession() || currentSessionControllerProfile() !== "android_phone") {
    return false
  }
  const legacyFrameStreamActive = Boolean(state.streamTimer)
  if (!nativeSenderOwnershipEnabledForSession(state.sessionId) && !legacyFrameStreamActive) {
    return false
  }
  return msg?.type === "input.mouse.move"
    || msg?.type === "input.mouse.button"
    || msg?.type === "input.wheel.scroll"
    || msg?.type === "session.viewport.interaction"
}

function viewportInteractionRestoreDelayMs(phase, interaction) {
  if (`${phase || ""}`.trim().toLowerCase() !== "end") {
    return NATIVE_SENDER_INTERACTIVE_RESTORE_DELAY_MS
  }
  const normalizedInteraction = `${interaction || ""}`.trim().toLowerCase()
  if (normalizedInteraction === "pinch") {
    return NATIVE_SENDER_PINCH_RESTORE_DELAY_MS
  }
  if (normalizedInteraction === "pan") {
    return NATIVE_SENDER_PAN_RESTORE_DELAY_MS
  }
  return NATIVE_SENDER_VIEWPORT_INTERACTION_RESTORE_DELAY_MS
}

function androidPhoneIdleNativeProfileForSession(sessionId = state.sessionId) {
  const activeSessionId = `${sessionId || ""}`.trim()
  if (
    state.nativeSenderFullscreenProfileActive
    && activeSessionId
    && state.nativeSenderFullscreenSessionId === activeSessionId
  ) {
    return {
      profile: buildAndroidPhoneFullscreenNativeProfile(),
      allowAndroidFullscreenDetail: true,
      reason: "android_phone_fullscreen_idle_restore",
    }
  }
  return {
    profile: AGENT_ADAPTIVE_PROFILES[ANDROID_PHONE_SESSION_PROFILE_INDEX],
    reason: "android_phone_interaction_idle_restore",
  }
}

function shouldUseAndroidPhoneZoomDetailAfterViewport(msg) {
  if (msg?.type !== "session.viewport.interaction") {
    return false
  }
  const payload = msg.payload && typeof msg.payload === "object" ? msg.payload : {}
  const phase = `${payload.phase || ""}`.trim().toLowerCase()
  const interaction = `${payload.interaction || ""}`.trim().toLowerCase()
  const scale = Number(payload.scale || 0)
  return phase === "end"
    && interaction === "pinch"
    && Number.isFinite(scale)
    && scale >= ANDROID_PHONE_ZOOM_DETAIL_MIN_SCALE
}

function shouldRestoreAndroidPhoneFullViewport(msg) {
  if (msg?.type !== "session.viewport.interaction") {
    return false
  }
  const payload = msg.payload && typeof msg.payload === "object" ? msg.payload : {}
  const phase = `${payload.phase || ""}`.trim().toLowerCase()
  const interaction = `${payload.interaction || ""}`.trim().toLowerCase()
  const scale = Number(payload.scale || 0)
  const viewportRegion = parseAndroidViewportRegion(payload)
  const isFullRegion = Boolean(viewportRegion)
    && viewportRegion.viewport_x <= 0.000001
    && viewportRegion.viewport_y <= 0.000001
    && viewportRegion.viewport_width >= 0.999999
    && viewportRegion.viewport_height >= 0.999999
  return phase === "end"
    && interaction === "pinch"
    && Number.isFinite(scale)
    && scale < ANDROID_PHONE_ZOOM_DETAIL_MIN_SCALE
    && isFullRegion
}

function shouldKeepAndroidPhoneZoomRegionForSession(activeSessionId) {
  const normalizedSessionId = `${activeSessionId || ""}`.trim()
  const interaction = state.lastAndroidViewportInteraction
  if (
    currentSessionControllerProfile() !== "android_phone"
    || !normalizedSessionId
    || !interaction
    || interaction.session_id !== normalizedSessionId
    || !interaction.viewport_region
  ) {
    return false
  }
  const scale = Number(interaction.scale || 0)
  if (!Number.isFinite(scale) || scale < ANDROID_PHONE_ZOOM_DETAIL_MIN_SCALE) {
    return false
  }
  const phase = `${interaction.phase || ""}`.trim().toLowerCase()
  const normalizedInteraction = `${interaction.interaction || ""}`.trim().toLowerCase()
  if (normalizedInteraction === "pinch" && phase !== "end") {
    // 作者: long；两指开合过程中频繁重配 source_rect 会打断 JPEG 采集节奏，先保持整屏低延迟帧，停手后再切局部高清。
    return false
  }
  // 作者: long；缩放后的局部高清是用户主动选择的阅读区域，不能靠固定秒数自动退回整屏；只有缩回 1x、退出全屏或会话结束才清掉。
  return state.nativeSenderZoomDetailSessionId === normalizedSessionId
}

function normalizedPayloadNumber(payload, key) {
  const value = Number(payload?.[key])
  if (!Number.isFinite(value) || value < 0 || value > 1) {
    return null
  }
  return value
}

function parseAndroidViewportRegion(payload) {
  const viewportX = normalizedPayloadNumber(payload, "viewport_x")
  const viewportY = normalizedPayloadNumber(payload, "viewport_y")
  const viewportWidth = normalizedPayloadNumber(payload, "viewport_width")
  const viewportHeight = normalizedPayloadNumber(payload, "viewport_height")
  const focusX = normalizedPayloadNumber(payload, "focus_x")
  const focusY = normalizedPayloadNumber(payload, "focus_y")
  const hasRegion = viewportX !== null || viewportY !== null || viewportWidth !== null || viewportHeight !== null
  if (!hasRegion) {
    return null
  }
  if (
    viewportX === null
    || viewportY === null
    || viewportWidth === null
    || viewportHeight === null
    || viewportWidth <= 0
    || viewportHeight <= 0
    || viewportX + viewportWidth > 1.000001
    || viewportY + viewportHeight > 1.000001
  ) {
    return null
  }
  return {
    viewport_x: viewportX,
    viewport_y: viewportY,
    viewport_width: viewportWidth,
    viewport_height: viewportHeight,
    focus_x: focusX,
    focus_y: focusY,
  }
}

function fullSourceRectPatch() {
  return {
    sourceRectXPpm: 0,
    sourceRectYPpm: 0,
    sourceRectWidthPpm: ANDROID_PHONE_SOURCE_RECT_UNITS,
    sourceRectHeightPpm: ANDROID_PHONE_SOURCE_RECT_UNITS,
  }
}

function sourceRectPatchFromRegion(region) {
  if (!region) {
    return fullSourceRectPatch()
  }
  const requestedX = Math.round(region.viewport_x * ANDROID_PHONE_SOURCE_RECT_UNITS)
  const requestedY = Math.round(region.viewport_y * ANDROID_PHONE_SOURCE_RECT_UNITS)
  const requestedWidth = Math.round(region.viewport_width * ANDROID_PHONE_SOURCE_RECT_UNITS)
  const requestedHeight = Math.round(region.viewport_height * ANDROID_PHONE_SOURCE_RECT_UNITS)
  const safeWidth = Math.max(
    ANDROID_PHONE_MIN_SOURCE_RECT_PPM,
    Math.min(ANDROID_PHONE_SOURCE_RECT_UNITS, requestedWidth),
  )
  const safeHeight = Math.max(
    ANDROID_PHONE_MIN_SOURCE_RECT_PPM,
    Math.min(ANDROID_PHONE_SOURCE_RECT_UNITS, requestedHeight),
  )
  const centerX = Math.max(
    0,
    Math.min(ANDROID_PHONE_SOURCE_RECT_UNITS, requestedX + requestedWidth / 2),
  )
  const centerY = Math.max(
    0,
    Math.min(ANDROID_PHONE_SOURCE_RECT_UNITS, requestedY + requestedHeight / 2),
  )
  // 作者: long；Android 最大缩放可能短时间内连续上报极小可视区域，桌面端二次保底裁剪尺寸，避免采集源被递归压小后拖垮 JPEG 解码或触发真机闪退。
  const x = Math.round(Math.max(0, Math.min(ANDROID_PHONE_SOURCE_RECT_UNITS - safeWidth, centerX - safeWidth / 2)))
  const y = Math.round(Math.max(0, Math.min(ANDROID_PHONE_SOURCE_RECT_UNITS - safeHeight, centerY - safeHeight / 2)))
  return {
    sourceRectXPpm: x,
    sourceRectYPpm: y,
    sourceRectWidthPpm: safeWidth,
    sourceRectHeightPpm: safeHeight,
  }
}

function sourceRectPatchKey(patch) {
  return [
    patch.sourceRectXPpm,
    patch.sourceRectYPpm,
    patch.sourceRectWidthPpm,
    patch.sourceRectHeightPpm,
  ].join(",")
}

function sourceRectPatchChangedEnough(previousKey, nextPatch) {
  if (!previousKey) {
    return true
  }
  const previous = previousKey.split(",").map((value) => Number(value))
  if (previous.length !== 4 || previous.some((value) => !Number.isFinite(value))) {
    return true
  }
  const next = [
    nextPatch.sourceRectXPpm,
    nextPatch.sourceRectYPpm,
    nextPatch.sourceRectWidthPpm,
    nextPatch.sourceRectHeightPpm,
  ]
  return next.some((value, index) => Math.abs(value - previous[index]) >= ANDROID_PHONE_ZOOM_REGION_SOURCE_RECT_UPDATE_THRESHOLD_PPM)
}

function androidPhoneCaptureSourceRectPatch(options = {}) {
  if (
    currentSessionControllerProfile() !== "android_phone" ||
    (!options.allowAndroidZoomDetail && !options.allowAndroidZoomStill)
  ) {
    return fullSourceRectPatch()
  }
  const activeSessionId = `${options.sessionId || state.sessionId || ""}`.trim()
  const interaction = state.lastAndroidViewportInteraction
  if (
    !activeSessionId
    || !interaction
    || interaction.session_id !== activeSessionId
    || !interaction.viewport_region
  ) {
    return fullSourceRectPatch()
  }
  // 作者: long；缩放后的高清档必须按手机当前可视区域裁剪源桌面，否则只是把整屏 JPEG 继续放大，文字不会出现新的真实细节。
  return sourceRectPatchFromRegion(interaction.viewport_region)
}

function currentAndroidFrameSourceRectMetadata() {
  const config = state.captureStatus?.config || {}
  const x = Number(config.source_rect_x_ppm ?? config.sourceRectXPpm ?? 0)
  const y = Number(config.source_rect_y_ppm ?? config.sourceRectYPpm ?? 0)
  const width = Number(config.source_rect_width_ppm ?? config.sourceRectWidthPpm ?? ANDROID_PHONE_SOURCE_RECT_UNITS)
  const height = Number(config.source_rect_height_ppm ?? config.sourceRectHeightPpm ?? ANDROID_PHONE_SOURCE_RECT_UNITS)
  if (
    currentSessionControllerProfile() !== "android_phone"
    || !Number.isFinite(x)
    || !Number.isFinite(y)
    || !Number.isFinite(width)
    || !Number.isFinite(height)
    || width <= 0
    || height <= 0
  ) {
    return null
  }
  return {
    source_rect_x: x / ANDROID_PHONE_SOURCE_RECT_UNITS,
    source_rect_y: y / ANDROID_PHONE_SOURCE_RECT_UNITS,
    source_rect_width: width / ANDROID_PHONE_SOURCE_RECT_UNITS,
    source_rect_height: height / ANDROID_PHONE_SOURCE_RECT_UNITS,
    full_frame_width: currentCaptureSource()?.width || 0,
    full_frame_height: currentCaptureSource()?.height || 0,
  }
}

function scheduleAndroidPhoneZoomStillUpgrade(activeSessionId, reason = "android_phone_pinch_zoom_still") {
  clearNativeSenderZoomStillUpgradeTimer()
  const interactionToken = Number(state.lastAndroidViewportInteraction?.updated_at || 0)
  state.nativeSenderZoomStillUpgradeTimer = window.setTimeout(() => {
    state.nativeSenderZoomStillUpgradeTimer = null
    if (
      state.sessionId !== activeSessionId
      || state.nativeSenderZoomDetailSessionId !== activeSessionId
      || Number(state.lastAndroidViewportInteraction?.updated_at || 0) !== interactionToken
    ) {
      return
    }
    // 作者: long；用户停住后才升到 960 局部帧读取文字；一旦期间有新的鼠标/手势输入，前面的 token 会变化并取消这次高清升档。
    void applyNativeSenderCaptureConfig(activeSessionId, {
      profile: buildAndroidPhoneZoomStillNativeProfile(),
      allowAndroidZoomDetail: true,
      allowAndroidZoomStill: true,
      reason,
      log: false,
    })
  }, ANDROID_PHONE_ZOOM_STILL_UPGRADE_DELAY_MS)
}

function scheduleAndroidPhoneZoomDetailUpgrade(activeSessionId, reason = "android_phone_pinch_zoom_detail") {
  clearNativeSenderZoomDetailUpgradeTimer()
  clearNativeSenderZoomStillUpgradeTimer()
  state.nativeSenderZoomDetailSessionId = activeSessionId
  state.nativeSenderZoomDetailUpgradeTimer = window.setTimeout(() => {
    state.nativeSenderZoomDetailUpgradeTimer = null
    if (
      state.sessionId !== activeSessionId
      || state.nativeSenderZoomDetailSessionId !== activeSessionId
    ) {
      return
    }
    // 作者: long；最大缩放后用户更容易继续移动视角，当前 legacy JPEG 链路先保持 512px 局部跟手档；800px detail 会和 Android 全屏窗口提交叠加，v5 真机已出现 BLASTSyncEngine 预警。
    void applyNativeSenderCaptureConfig(activeSessionId, {
      profile: buildAndroidPhoneZoomMotionNativeProfile(),
      allowAndroidZoomDetail: true,
      androidPhoneZoomMotion: true,
      reason,
      log: false,
    })
  }, ANDROID_PHONE_ZOOM_DETAIL_UPGRADE_DELAY_MS)
}

function handleAndroidPhoneFullscreenViewportInteraction(msg) {
  if (msg?.type !== "session.viewport.interaction") {
    return false
  }
  const payload = msg.payload && typeof msg.payload === "object" ? msg.payload : {}
  const interaction = `${payload.interaction || ""}`.trim().toLowerCase()
  if (interaction !== "fullscreen") {
    return false
  }
  const activeSessionId = `${msg?.session_id || state.sessionId || ""}`.trim()
  if (!activeSessionId) {
    return true
  }
  const phase = `${payload.phase || ""}`.trim().toLowerCase()
  clearNativeSenderInteractiveRestoreTimer()
  clearNativeSenderZoomDetailUpgradeTimer()
  clearNativeSenderZoomStillUpgradeTimer()
  if (phase === "start" || phase === "update") {
    state.nativeSenderFullscreenProfileActive = true
    state.nativeSenderFullscreenSessionId = activeSessionId
    // 作者: long；全屏查看文字时不能继续沿用普通会话的 800px 上限，否则手机横屏仍是在放大整页低清帧；这里单独放开到接近桌面源尺寸，输入时仍由交互档临时降载。
    void applyNativeSenderCaptureConfig(activeSessionId, {
      profile: buildAndroidPhoneFullscreenNativeProfile(),
      allowAndroidFullscreenDetail: true,
      reason: "android_phone_fullscreen",
      log: false,
    })
    return true
  }
  if (phase === "end") {
    state.nativeSenderFullscreenProfileActive = false
    state.nativeSenderFullscreenSessionId = ""
    state.nativeSenderZoomDetailSessionId = ""
    void applyNativeSenderCaptureConfig(activeSessionId, {
      profile: AGENT_ADAPTIVE_PROFILES[ANDROID_PHONE_SESSION_PROFILE_INDEX],
      reason: "android_phone_fullscreen_exit",
      log: false,
    })
    return true
  }
  return true
}

function scheduleNativeSenderInteractiveProfileForInput(msg, options = {}) {
  if (!shouldUseNativeSenderInteractiveProfileForInput(msg)) {
    return
  }
  if (handleAndroidPhoneFullscreenViewportInteraction(msg)) {
    return
  }
  const activeSessionId = `${msg?.session_id || state.sessionId || ""}`.trim()
  if (!activeSessionId) {
    return
  }
  const restoreDelayMs = Math.max(120, Number(options.restoreDelayMs) || NATIVE_SENDER_INTERACTIVE_RESTORE_DELAY_MS)
  const shouldRestoreToZoomDetail = shouldUseAndroidPhoneZoomDetailAfterViewport(msg)
  const shouldRestoreFullViewport = shouldRestoreAndroidPhoneFullViewport(msg)
  const shouldKeepZoomRegion = shouldRestoreToZoomDetail || shouldKeepAndroidPhoneZoomRegionForSession(activeSessionId)
  const payload = msg.payload && typeof msg.payload === "object" ? msg.payload : {}
  const phase = `${payload.phase || ""}`.trim().toLowerCase()
  const interaction = `${payload.interaction || ""}`.trim().toLowerCase()

  clearNativeSenderInteractiveRestoreTimer()
  clearNativeSenderZoomDetailUpgradeTimer()
  clearNativeSenderZoomStillUpgradeTimer()
  if (msg?.type === "session.viewport.interaction" && interaction === "pinch" && phase !== "end") {
    state.nativeSenderInteractiveProfileActive = true
    state.nativeSenderInteractiveSessionId = activeSessionId
    state.nativeSenderInteractiveProfileMode = "pinch_preview"
    state.nativeSenderInteractiveSourceRectKey = ""
    state.nativeSenderInteractiveSourceRectUpdatedAt = Date.now()
    // 作者: long；双指开合时 Android 主要显示本地矩阵动画，整屏 JPEG 多数会被门控丢弃；Mac 只保留极轻量预览，减少无用编码/信令对手势帧的干扰。
    void applyNativeSenderCaptureConfig(activeSessionId, {
      profile: buildAndroidPhonePinchPreviewNativeProfile(),
      reason: "android_phone_pinch_preview_light",
      log: false,
    })
    return
  }
  if (shouldRestoreFullViewport) {
    state.nativeSenderInteractiveProfileActive = false
    state.nativeSenderInteractiveSessionId = ""
    state.nativeSenderInteractiveProfileMode = ""
    state.nativeSenderInteractiveSourceRectKey = ""
    state.nativeSenderInteractiveSourceRectUpdatedAt = 0
    state.nativeSenderZoomDetailSessionId = ""
    const idleProfile = androidPhoneIdleNativeProfileForSession(activeSessionId)
    // 作者: long；Android 已经缩回完整桌面视口时，立即恢复整屏采集，避免前一个 pinch 的延迟高清任务把 source_rect 又抢回局部。
    void applyNativeSenderCaptureConfig(activeSessionId, {
      profile: idleProfile.profile,
      reason: "android_phone_zoom_full_viewport_restore",
      log: false,
    })
    return
  }
  if (!shouldRestoreToZoomDetail && !shouldKeepZoomRegion) {
    state.nativeSenderZoomDetailSessionId = ""
  }
  if (shouldRestoreToZoomDetail) {
    state.nativeSenderInteractiveProfileActive = false
    state.nativeSenderInteractiveSessionId = ""
    state.nativeSenderInteractiveProfileMode = ""
    state.nativeSenderInteractiveSourceRectKey = ""
    state.nativeSenderInteractiveSourceRectUpdatedAt = 0
    // 作者: long；pinch 松手后先用轻量 source_rect 预览替换本地放大的整屏旧帧，再延迟升局部高清，避免大 JPEG 抢在手势收尾时造成顿挫。
    void applyNativeSenderCaptureConfig(activeSessionId, {
      profile: buildAndroidPhoneZoomMotionNativeProfile(),
      allowAndroidZoomDetail: true,
      androidPhoneZoomMotion: true,
      reason: "android_phone_pinch_zoom_preview",
      log: false,
    })
    scheduleAndroidPhoneZoomDetailUpgrade(activeSessionId, "android_phone_pinch_zoom_detail")
    return
  }
  const desiredProfileMode = shouldKeepZoomRegion ? "zoom_region" : "interactive"
  const desiredSourceRectPatch = shouldKeepZoomRegion
    ? androidPhoneCaptureSourceRectPatch({ sessionId: activeSessionId, allowAndroidZoomDetail: true })
    : fullSourceRectPatch()
  const desiredSourceRectKey = sourceRectPatchKey(desiredSourceRectPatch)
  const sourceRectChangedEnough = shouldKeepZoomRegion
    && sourceRectPatchChangedEnough(state.nativeSenderInteractiveSourceRectKey, desiredSourceRectPatch)
  const shouldForceFinalPanSourceRectUpdate = shouldKeepZoomRegion
    && msg?.type === "session.viewport.interaction"
    && interaction === "pan"
    && phase === "end"
    && sourceRectChangedEnough
  const sourceRectUpdateDue = shouldKeepZoomRegion
    && state.nativeSenderInteractiveProfileActive
    && state.nativeSenderInteractiveSessionId === activeSessionId
    && state.nativeSenderInteractiveProfileMode === desiredProfileMode
    && (
      shouldForceFinalPanSourceRectUpdate
      || Date.now() - Number(state.nativeSenderInteractiveSourceRectUpdatedAt || 0) >= ANDROID_PHONE_ZOOM_REGION_SOURCE_RECT_UPDATE_MIN_MS
    )
    && sourceRectChangedEnough
  if (
    !state.nativeSenderInteractiveProfileActive
    || state.nativeSenderInteractiveSessionId !== activeSessionId
    || state.nativeSenderInteractiveProfileMode !== desiredProfileMode
    || sourceRectUpdateDue
  ) {
    state.nativeSenderInteractiveProfileActive = true
    state.nativeSenderInteractiveSessionId = activeSessionId
    state.nativeSenderInteractiveProfileMode = desiredProfileMode
    state.nativeSenderInteractiveSourceRectKey = desiredSourceRectKey
    state.nativeSenderInteractiveSourceRectUpdatedAt = Date.now()
    const interactionProfile = shouldKeepZoomRegion
      ? buildAndroidPhoneZoomMotionNativeProfile()
      : buildAndroidPhoneInteractiveNativeProfile()
    void applyNativeSenderCaptureConfig(activeSessionId, {
      // 作者: long；用户已经放大到局部后，鼠标移动仍要保留可读文字；可视区域明显变化时节流刷新裁剪源，避免双指移动后继续看上一块旧局部。
      profile: interactionProfile,
      allowAndroidZoomDetail: shouldKeepZoomRegion,
      androidPhoneZoomMotion: shouldKeepZoomRegion,
      reason: shouldKeepZoomRegion
        ? (sourceRectUpdateDue
          ? (shouldForceFinalPanSourceRectUpdate ? "android_phone_zoom_region_source_rect_final" : "android_phone_zoom_region_source_rect_update")
          : "android_phone_zoom_region_interaction")
        : "android_phone_interaction",
      log: false,
    })
  }

  state.nativeSenderInteractiveRestoreTimer = window.setTimeout(() => {
    state.nativeSenderInteractiveRestoreTimer = null
    if (!state.nativeSenderInteractiveProfileActive || state.nativeSenderInteractiveSessionId !== activeSessionId) {
      return
    }
    state.nativeSenderInteractiveProfileActive = false
    state.nativeSenderInteractiveSessionId = ""
    state.nativeSenderInteractiveProfileMode = ""
    state.nativeSenderInteractiveSourceRectKey = ""
    state.nativeSenderInteractiveSourceRectUpdatedAt = 0
    if (shouldRestoreToZoomDetail || shouldKeepZoomRegion) {
      // 作者: long；放大后继续平移或移动鼠标时，先保留低成本局部源预览，停稳后再升高清，避免画面在低清整屏和重局部帧之间来回抖。
      void applyNativeSenderCaptureConfig(activeSessionId, {
        profile: buildAndroidPhoneZoomMotionNativeProfile(),
        allowAndroidZoomDetail: true,
        androidPhoneZoomMotion: true,
        reason: shouldRestoreToZoomDetail ? "android_phone_pinch_zoom_preview" : "android_phone_zoom_region_preview",
        log: false,
      })
      scheduleAndroidPhoneZoomDetailUpgrade(
        activeSessionId,
        shouldRestoreToZoomDetail ? "android_phone_pinch_zoom_detail" : "android_phone_zoom_region_restore",
      )
      return
    }
    const idleProfile = androidPhoneIdleNativeProfileForSession(activeSessionId)
    void applyNativeSenderCaptureConfig(activeSessionId, {
      profile: idleProfile.profile,
      allowAndroidFullscreenDetail: Boolean(idleProfile.allowAndroidFullscreenDetail),
      reason: idleProfile.reason,
      log: false,
    })
  }, restoreDelayMs)
}

function nativeSenderCaptureSourceMissing(status = state.nativeSenderStatus) {
  const errorCode = `${status?.last_error_code || ""}`.trim().toLowerCase()
  const errorDetail = `${status?.last_error_detail || ""}`.trim().toLowerCase()
  if (errorCode !== "native_sender.probe.capture_failed") {
    return false
  }
  return errorDetail.includes("source_missing") || errorDetail.includes("no active capture source")
}

async function ensureNativeCaptureSourceReadyForSession(sessionId, options = {}) {
  if (!isTauri()) {
    return true
  }
  await applyNativeSenderCaptureConfig(sessionId, { log: options.log })
  const normalizedSessionId = `${sessionId || ""}`.trim() || `${state.sessionId || ""}`.trim() || "-"
  const maxAttempts = Math.max(1, Number(options.maxAttempts) || NATIVE_SENDER_CAPTURE_PREPARE_MAX_ATTEMPTS)
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    const ready = await requestNativeCaptureSource({ log: options.log !== false && attempt === 1 })
    const activeSource = currentCaptureSource()
    const lifecycle = `${state.captureStatus?.lifecycle || "-"}`.trim()
    const readied = Boolean(ready && activeSource && !nativeCaptureReadinessMessage())
    traceLog("native.capture.session_prepare.sample", {
      session_id: normalizedSessionId,
      attempt,
      ready: readied ? 1 : 0,
      lifecycle,
      source_id: activeSource?.source_id || "-",
      error_code: state.captureStatus?.last_error_code || "-",
      error_detail: state.captureError || state.captureStatus?.last_error_detail || "-",
    }, { console: false })
    if (readied) {
      if (options.log !== false && attempt > 1) {
        appendLog(`桌面采集源已就绪（重试 ${attempt}/${maxAttempts}）: ${state.captureLabel}`)
      }
      return true
    }
    if (attempt < maxAttempts) {
      await new Promise((resolve) => {
        window.setTimeout(resolve, NATIVE_SENDER_CAPTURE_PREPARE_RETRY_MS)
      })
    }
  }
  if (options.log !== false) {
    appendLog("桌面采集源准备超时，回退 JS 会话协商链路")
  }
  return false
}

async function refreshHostBridgeStatus(options = {}) {
  if (!isTauri()) {
    return state.hostBridgeStatus
  }

  try {
    const status = await invoke("host_bridge_status")
    applyHostBridgeSnapshot(status)
    state.hostBridgeError = ""
  } catch (error) {
    state.hostBridgeError = error?.message || "读取 host bridge 状态失败"
    recordHostBridgeFailure(null, {
      last_executor: "host.bridge",
      last_error_code: "host.bridge.status.read_failed",
      last_error_detail: state.hostBridgeError,
      last_status_code: "host.bridge.status.read_failed",
      last_status_detail: state.hostBridgeError,
    })
    if (options.log !== false) {
      appendLog(`读取 host bridge 状态失败: ${state.hostBridgeError}`)
    }
  }

  return state.hostBridgeStatus
}

async function hydrateDesktopBootstrap() {
  if (!isTauri()) {
    return
  }

  try {
    const context = await invoke("bootstrap_context")
    applyBootstrapContext(context)
    await refreshHostBridgeStatus({ log: false })
    await refreshNativeSenderState({ log: false })
    await refreshCaptureState({ log: false })
    state.bootstrapError = ""
    appendLog(`桌面壳层已加载: ${state.shellPlatform} / ${state.defaultCodec}`)
    scheduleAutoConnect()
    if (localCanPrepareRemoteControlHost()) {
      window.setTimeout(() => {
        if (state.autoConnect && !connectionReady()) {
          triggerAutoConnectFromSettings()
        }
      }, AUTO_CONNECT_DELAY_MS + 300)
    }
  } catch (error) {
    state.bootstrapError = error?.message || "加载桌面壳层上下文失败"
    appendLog(`加载桌面壳层上下文失败: ${state.bootstrapError}`)
  }

  render()
}

async function syncHostSession(context, options = {}) {
  state.hostBridgeStatus = emptyHostBridgeStatus()
  state.hostBridgeError = ""

  if (!isTauri()) {
    return state.hostBridgeStatus
  }

  try {
    const status = await invoke("host_sync_session", { context })
    applyHostBridgeSnapshot(status)
    state.hostBridgeError = ""
  } catch (error) {
    state.hostBridgeError = error?.message || "同步 host 会话失败"
    recordHostBridgeFailure(null, {
      last_executor: "host.bridge",
      last_error_code: "host.bridge.session.sync_failed",
      last_error_detail: state.hostBridgeError,
      last_status_code: "host.bridge.session.sync_failed",
      last_status_detail: state.hostBridgeError,
    })
    if (options.log !== false) {
      appendLog(`同步 host 会话失败: ${state.hostBridgeError}`)
    }
  }

  return state.hostBridgeStatus
}

async function bridgeInputToHost(msg) {
  if (!isTauri() || !isAgentSession()) {
    return false
  }

  let bridgeResult = null
  try {
    const result = await invoke("host_apply_input", {
      envelope: {
        messageType: msg.type,
        sessionId: msg.session_id,
        traceId: msg.trace_id || "",
        senderDeviceId: typeof msg.from?.device_id === "string" ? msg.from.device_id.trim() : "",
        senderRole: typeof msg.from?.role === "string" ? msg.from.role.trim() : "",
        payload: msg.payload || {},
      },
    })
    bridgeResult = result
    const errorCode = result?.error_code || ""
    const statusCode = result?.status_code || "-"
    const executor = result?.executor || "-"
    const statusDetail = result?.status_detail || ""
    const errorDetail = result?.error_detail || ""

    applyHostBridgeSnapshot({
      input_count: Number(result?.input_count) || state.hostBridgeStatus.input_count || 0,
      last_input_type: result?.message_type || msg.type,
      last_input_summary: result?.summary || formatInputMessage(msg),
      last_session_id: result?.session_id || msg.session_id || "",
      last_trace_id: result?.trace_id || msg.trace_id || "",
      last_executor: executor,
      last_status_code: statusCode,
      last_status_detail: statusDetail,
      last_error_code: errorCode,
      last_error_detail: errorDetail,
    })

    if (errorCode) {
      state.hostBridgeError = errorDetail || statusDetail || "host bridge 应用输入失败"
      appendLog(`host bridge 应用输入失败 [${executor}/${errorCode}]: ${state.hostBridgeError}`)
      return false
    }

    state.hostBridgeError = ""
    if (result?.applied) {
      if (msg.type !== "input.mouse.move") {
        appendLog(`host bridge 已应用输入 [${executor}/${statusCode}]: ${state.hostBridgeStatus.last_input_summary}`)
      }
      return true
    }

    appendLog(`host bridge 未实际应用输入 [${executor}/${statusCode}]: ${statusDetail || state.hostBridgeStatus.last_input_summary}`)
    return false
  } catch (error) {
    state.hostBridgeError = error?.message || "host bridge 应用输入失败"
    recordHostBridgeFailure(msg, {
      last_executor: "host.bridge",
      last_error_code: "host.bridge.invoke_failed",
      last_error_detail: state.hostBridgeError,
      last_status_code: "host.bridge.invoke_failed",
      last_status_detail: state.hostBridgeError,
    })
    appendLog(`host bridge 调用失败: ${state.hostBridgeError}`)
    return false
  } finally {
    sendInputResultPush(msg, bridgeResult)
    scheduleLiveE2EProofReport(bridgeResult?.applied ? "live_agent_input_applied" : "live_agent_input_result")
  }
}

function scheduleHostMouseMoveDrain(delayMs = 0) {
  if (hostMouseMoveDrainTimer || hostMouseMoveApplyInFlight || hostDiscreteInputQueueDepth > 0) {
    return
  }
  hostMouseMoveDrainTimer = window.setTimeout(() => {
    hostMouseMoveDrainTimer = null
    if (hostDiscreteInputQueueDepth > 0) {
      return
    }
    void drainHostMouseMoveQueue()
  }, Math.max(0, Number(delayMs) || 0))
}

function currentHostMouseMoveThrottleMs(msg = hostMouseMovePendingMsg) {
  if (!isTauri() || !isAgentSession() || currentSessionControllerProfile() !== "android_phone") {
    return REMOTE_POINTER_MOVE_THROTTLE_MS
  }
  if (msg?.type !== "input.mouse.move") {
    return REMOTE_POINTER_MOVE_THROTTLE_MS
  }
  const messageSessionId = `${msg.session_id || ""}`.trim()
  const interaction = state.lastAndroidViewportInteraction
  if (!messageSessionId || !interaction || interaction.session_id !== messageSessionId) {
    return REMOTE_POINTER_MOVE_THROTTLE_MS
  }
  const updatedAt = Number(interaction.updated_at || 0)
  if (!updatedAt || Date.now() - updatedAt > REMOTE_ZOOM_PAN_MOUSE_MOVE_RECENT_MS) {
    return REMOTE_POINTER_MOVE_THROTTLE_MS
  }
  const scale = Number(interaction.scale || 0)
  const viewportRegion = interaction.viewport_region
  const isZoomRegion = Boolean(viewportRegion)
    && (
      viewportRegion.viewport_width < 0.999999
      || viewportRegion.viewport_height < 0.999999
      || viewportRegion.viewport_x > 0.000001
      || viewportRegion.viewport_y > 0.000001
    )
  const normalizedInteraction = `${interaction.interaction || ""}`.trim().toLowerCase()
  const isZoomPan = normalizedInteraction === "pan"
    && (isZoomRegion || (Number.isFinite(scale) && scale >= ANDROID_PHONE_ZOOM_DETAIL_MIN_SCALE))
  if (!isZoomPan) {
    return REMOTE_POINTER_MOVE_THROTTLE_MS
  }
  return REMOTE_ZOOM_PAN_MOUSE_MOVE_THROTTLE_MS
}

function queueHostMouseMove(msg) {
  if (!isTauri() || !isAgentSession()) {
    return false
  }
  hostMouseMovePendingMsg = msg
  scheduleHostMouseMoveDrain(0)
  return true
}

async function applyQueuedHostMouseMove(msg) {
  hostMouseMoveApplyInFlight = true
  lastHostMouseMoveApplyAtMs = Date.now()
  // 作者: long；鼠标移动和按下/抬起共用同一个系统输入执行器，记录当前 promise，后续按钮/键盘事件才能等最后一个 move 真正落地后再执行。
  const applyPromise = bridgeInputToHost(msg)
  hostMouseMoveApplyPromise = applyPromise
  try {
    return await applyPromise
  } finally {
    if (hostMouseMoveApplyPromise === applyPromise) {
      hostMouseMoveApplyPromise = null
    }
    hostMouseMoveApplyInFlight = false
    if (hostMouseMovePendingMsg) {
      scheduleHostMouseMoveDrain(0)
    }
  }
}

async function drainHostMouseMoveQueue() {
  if (hostMouseMoveApplyInFlight || hostDiscreteInputQueueDepth > 0 || !hostMouseMovePendingMsg) {
    return false
  }
  const now = Date.now()
  const elapsedMs = now - lastHostMouseMoveApplyAtMs
  const throttleMs = currentHostMouseMoveThrottleMs(hostMouseMovePendingMsg)
  if (lastHostMouseMoveApplyAtMs > 0 && elapsedMs < throttleMs) {
    scheduleHostMouseMoveDrain(throttleMs - elapsedMs)
    return false
  }
  const msg = hostMouseMovePendingMsg
  hostMouseMovePendingMsg = null
  // 作者: long；Android 真机会在一次滑动里发出大量 move；节流窗口从本次原生输入开始计时，避免每次 CGEvent 耗时后再额外等待。
  return applyQueuedHostMouseMove(msg)
}

async function flushQueuedHostMouseMove() {
  if (hostMouseMoveDrainTimer) {
    window.clearTimeout(hostMouseMoveDrainTimer)
    hostMouseMoveDrainTimer = null
  }
  if (hostMouseMoveApplyInFlight && hostMouseMoveApplyPromise) {
    // 作者: long；按钮、键盘和滚轮是离散动作，必须排在最后一个鼠标 move 后面，否则拖拽结束或点击可能落在上一帧坐标。
    await hostMouseMoveApplyPromise
  }
  if (!hostMouseMovePendingMsg) {
    return false
  }
  const msg = hostMouseMovePendingMsg
  hostMouseMovePendingMsg = null
  // 作者: long；鼠标按下、抬起和滚轮前先补最后一个移动点，避免按钮事件落在用户手指上一帧的位置。
  return applyQueuedHostMouseMove(msg)
}

function enqueueHostDiscreteInput(msg) {
  if (!isTauri() || !isAgentSession()) {
    return false
  }
  hostDiscreteInputQueueDepth += 1
  // 作者: long；按钮、键盘和滚轮必须按 relay 到达顺序串行落地；队列空闲前暂停后台 move drain，避免 down/up 等离散动作被后续移动事件插队。
  const queuedTask = hostDiscreteInputQueueTail
    .catch((error) => {
      appendLog(`host bridge 输入队列恢复: ${error?.message || "unknown error"}`)
    })
    .then(async () => {
      try {
        await flushQueuedHostMouseMove()
        await bridgeInputToHost(msg)
      } finally {
        scheduleInputUiRender()
      }
    })
    .catch((error) => {
      appendLog(`host bridge 输入队列失败: ${error?.message || "unknown error"}`)
    })
    .finally(() => {
      hostDiscreteInputQueueDepth = Math.max(0, hostDiscreteInputQueueDepth - 1)
      if (hostDiscreteInputQueueDepth === 0 && hostMouseMovePendingMsg) {
        scheduleHostMouseMoveDrain(0)
      }
    })
  hostDiscreteInputQueueTail = queuedTask
  return true
}

function sendInputResultPush(msg, bridgeResult = null) {
  const messageSessionId = typeof msg?.session_id === "string" ? msg.session_id.trim() : ""
  if (!messageSessionId || !isAgentSession()) {
    return false
  }
  const status = bridgeResult && typeof bridgeResult === "object"
    ? bridgeResult
    : {
        applied: false,
        message_type: msg?.type || "",
        session_id: messageSessionId,
        trace_id: msg?.trace_id || "",
        summary: msg ? formatInputMessage(msg) : "",
        input_count: Number(state.hostBridgeStatus?.input_count || 0),
        executor: state.hostBridgeStatus?.last_executor || "host.bridge",
        status_code: state.hostBridgeStatus?.last_status_code || "host.bridge.invoke_failed",
        status_detail: state.hostBridgeStatus?.last_status_detail || state.hostBridgeError || "",
        error_code: state.hostBridgeStatus?.last_error_code || "host.bridge.invoke_failed",
        error_detail: state.hostBridgeStatus?.last_error_detail || state.hostBridgeError || "host bridge apply failed",
      }
  const inputCategory = normalizeRemoteInputCategory(
    status.input_category || msg?.payload?.input_category,
    status.message_type || msg?.type,
    status.summary || (msg ? formatInputMessage(msg) : ""),
  )
  if (msg?.type === "input.mouse.move" && Boolean(status.applied) && !status.error_code) {
    const now = Date.now()
    if (now - lastInputMoveResultPushAtMs < INPUT_MOVE_RESULT_PUSH_THROTTLE_MS) {
      return true
    }
    lastInputMoveResultPushAtMs = now
  }
  return sendEnvelope("input.result.push", {
    input_type: status.message_type || msg?.type || "",
    input_category: inputCategory || "",
    input_trace_id: status.trace_id || msg?.trace_id || "",
    applied: Boolean(status.applied),
    executor: status.executor || "",
    status_code: status.status_code || "",
    status_detail: status.status_detail || "",
    error_code: status.error_code || "",
    error_detail: status.error_detail || "",
    summary: status.summary || (msg ? formatInputMessage(msg) : ""),
    input_count: Number(status.input_count || 0),
  }, messageSessionId, {
    log: false,
    logLine: "发送 input.result.push",
  })
}

function buildHostSessionContext(sessionId = state.sessionId, sessionInfo = state.sessionInfo) {
  const normalizedSessionId = typeof sessionId === "string" ? sessionId.trim() : ""
  const controllerDeviceId = typeof sessionInfo?.controller_device_id === "string"
    ? sessionInfo.controller_device_id.trim()
    : ""
  const agentDeviceId = typeof sessionInfo?.agent_device_id === "string"
    ? sessionInfo.agent_device_id.trim()
    : ""
  const localDeviceId = typeof state.deviceId === "string" ? state.deviceId.trim() : ""

  if (!normalizedSessionId || !controllerDeviceId || !agentDeviceId || !localDeviceId) {
    return null
  }

  return {
    sessionId: normalizedSessionId,
    controllerDeviceId,
    agentDeviceId,
    localDeviceId,
  }
}

function currentSessionSide() {
  if (!state.sessionInfo) {
    return state.role
  }
  if (state.sessionInfo.controller_device_id === state.deviceId) {
    return "controller"
  }
  if (state.sessionInfo.agent_device_id === state.deviceId) {
    return "agent"
  }
  return state.role
}

function isControllerSession() {
  return Boolean(state.sessionId && state.sessionInfo?.controller_device_id === state.deviceId)
}

function isAgentSession() {
  return Boolean(state.sessionId && state.sessionInfo?.agent_device_id === state.deviceId)
}

function currentRemoteControlTargetDevice() {
  const targetDeviceId = isControllerSession()
    ? `${state.sessionInfo?.agent_device_id || ""}`.trim()
    : `${state.targetDeviceId || ""}`.trim()
  if (!targetDeviceId) {
    return null
  }
  return state.relayDevices.find((device) => device.device_id === targetDeviceId) || null
}

function remoteTargetSupportsPointerButton(button) {
  if (button !== "middle") {
    return true
  }
  const target = currentRemoteControlTargetDevice()
  return `${target?.platform || ""}`.trim().toLowerCase() !== "macos"
}

function idlePrefersControllerPerspective(selectedTarget = null) {
  if (state.sessionId) {
    return false
  }
  if (state.role === "controller") {
    return true
  }
  return Boolean(selectedTarget?.is_target_candidate)
}

function canSendInput() {
  return Boolean(connectionReady() && isControllerSession())
}

function canPublishFrames() {
  if (!connectionReady() || !isAgentSession()) {
    return false
  }
  return !isTauri()
    || Boolean(currentPlatformCaptureCapabilities().supports_frame_streaming)
    || Boolean(navigator.mediaDevices?.getDisplayMedia)
}

function canStreamCapturedFrames() {
  return !isTauri()
    || Boolean(currentPlatformCaptureCapabilities().supports_frame_streaming)
    || Boolean(navigator.mediaDevices?.getDisplayMedia)
}

function hasSelectedCaptureSource() {
  return isTauri()
    ? (Boolean(currentCaptureSource()) || hasActiveCaptureStream())
    : hasActiveCaptureStream()
}

function nativeCapturePermissionGranted() {
  return (state.captureStatus?.permission?.status || "") === "granted"
}

function nativeCaptureLifecycleReady() {
  return ["ready", "running", "paused"].includes(state.captureStatus?.lifecycle || "")
}

function nativeCaptureSourceReady() {
  return Boolean(
    currentCaptureSource()
    && nativeCaptureSupportsActiveSource()
    && nativeCapturePermissionGranted()
    && nativeCaptureLifecycleReady(),
  )
}

function localCanPrepareRemoteControlHost() {
  if (isAgentSession() || state.role === "agent" || hasSelectedCaptureSource()) {
    return true
  }
  if (!isTauri()) {
    return false
  }
  const captureCapabilities = currentPlatformCaptureCapabilities()
  const hostInputCapabilities = currentPlatformHostInputCapabilities()
  return Boolean(
    captureCapabilities.supports_source_listing
    && (
      hostInputCapabilities.supports_pointer_input
      || hostInputCapabilities.supports_keyboard_input
      || hostInputCapabilities.supports_wheel_input
    ),
  )
}

function nativeCaptureReadinessMessage() {
  if (!currentCaptureSource()) {
    return "请先准备桌面原生采集源"
  }
  const unsupportedSourceMessage = nativeCaptureUnsupportedSourceMessage()
  if (unsupportedSourceMessage) {
    return unsupportedSourceMessage
  }
  if (!nativeCapturePermissionGranted()) {
    return state.captureStatus?.permission?.detail || "桌面采集权限未就绪"
  }
  if (!nativeCaptureLifecycleReady()) {
    return state.captureStatus?.last_error_detail || `桌面原生采集尚未就绪 (${state.captureStatus?.lifecycle || "idle"})`
  }
  return ""
}

function canSelectCaptureSource() {
  if (!localCanPrepareRemoteControlHost()) {
    return false
  }
  if (!isTauri()) {
    return true
  }
  return Boolean(currentPlatformCaptureCapabilities().supports_source_listing || hasSelectedCaptureSource())
}

function localCaptureCanPublishFrames() {
  if (!isTauri()) {
    return Boolean(navigator.mediaDevices?.getDisplayMedia)
  }

  const capabilities = currentPlatformCaptureCapabilities()
  const permissionStatus = `${state.captureStatus?.permission?.status || ""}`.toLowerCase()
  const permissionReady = !capabilities.requires_permission || permissionStatus === "granted"
  return Boolean(capabilities.supports_frame_streaming && permissionReady)
}

function localHostCanApplyRemoteInput() {
  if (!isTauri()) {
    return false
  }

  const capabilities = currentPlatformHostInputCapabilities()
  const permissionStatus = `${capabilities.permission?.status || ""}`.toLowerCase()
  const permissionReady = !capabilities.requires_permission || permissionStatus === "granted"
  return Boolean(
    permissionReady
    && (
      capabilities.supports_pointer_input
      || capabilities.supports_keyboard_input
      || capabilities.supports_wheel_input
    ),
  )
}

function localCanBeControlled() {
  return localCaptureCanPublishFrames() && localHostCanApplyRemoteInput()
}

function currentDeviceCapabilities() {
  return {
    can_control: true,
    can_be_controlled: localCanBeControlled(),
  }
}

function localCanInitiateRemoteControl() {
  return Boolean(currentDeviceCapabilities().can_control)
}

function deviceCapabilitiesKey(capabilities = currentDeviceCapabilities()) {
  return [
    capabilities.can_control ? "control:1" : "control:0",
    capabilities.can_be_controlled ? "host:1" : "host:0",
  ].join("|")
}

function syncRegisteredCapabilitiesIfChanged() {
  if (!connectionReady() || !state.token) {
    return false
  }

  const capabilities = currentDeviceCapabilities()
  const nextKey = deviceCapabilitiesKey(capabilities)
  if (state.lastRegisteredCapabilitiesKey === nextKey) {
    return false
  }

  state.lastRegisterAt = 0
  traceLog("device.capabilities.changed", {
    can_control: capabilities.can_control ? 1 : 0,
    can_be_controlled: capabilities.can_be_controlled ? 1 : 0,
  }, { console: false })
  return sendRegister()
}

function nativeFrameStreamingStatusMessage() {
  if (hasActiveCaptureStream()) {
    return state.streamTimer
      ? `当前正在推送共享屏幕画面：${state.captureLabel || "共享屏幕"}`
      : "共享屏幕采集已就绪（直推视频轨道）"
  }

  const captureCapabilities = currentPlatformCaptureCapabilities()
  if (!captureCapabilities.supports_source_listing && !currentCaptureSource()) {
    return captureCapabilities.support_detail || "当前桌面壳层尚未实现原生采集能力"
  }
  const readinessMessage = nativeCaptureReadinessMessage()
  if (readinessMessage) {
    return readinessMessage
  }
  if (canStreamCapturedFrames()) {
    return state.streamTimer
      ? `当前正在推送桌面原生画面：${state.captureLabel || currentCaptureSource()?.title || "显示器"}`
      : "桌面原生帧采集已就绪"
  }
  return captureCapabilities.support_detail || "已准备桌面原生采集源，当前桌面壳层尚未实现帧输出"
}

function currentViewportFrame() {
  const videoElement = document.getElementById("remoteMediaVideo")
  if (videoElement instanceof HTMLVideoElement && videoElement.videoWidth > 0 && videoElement.videoHeight > 0) {
    return {
      url: "",
      meta: {
        frameId: "webrtc-track",
        captureTs: Date.now(),
        width: videoElement.videoWidth,
        height: videoElement.videoHeight,
      },
    }
  }
  if (isControllerSession()) {
    return { url: state.remoteFrameUrl, meta: state.remoteFrameMeta }
  }
  if (isAgentSession()) {
    return { url: state.localFrameUrl, meta: state.localFrameMeta }
  }
  return {
    url: state.remoteFrameUrl || state.localFrameUrl,
    meta: state.remoteFrameMeta || state.localFrameMeta,
  }
}

function clamp01(value) {
  if (!Number.isFinite(value)) {
    return 0
  }
  return Math.max(0, Math.min(1, value))
}

function formatNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(2) : "?"
}

function formatMetricValue(value, options = {}) {
  const number = Number(value)
  if (!Number.isFinite(number) || number < 0) {
    return "-"
  }
  const digits = Number.isFinite(Number(options.digits)) ? Number(options.digits) : 1
  return number.toFixed(digits)
}

function resolveLocalPreviewFpsMetric(agentStats = null) {
  if (state.localPreviewFps >= 0) {
    return {
      value: state.localPreviewFps,
      text: `${formatMetricValue(state.localPreviewFps)} fps`,
      source: "video_element",
    }
  }
  const senderFps = Number(agentStats?.sendFps)
  if (Number.isFinite(senderFps) && senderFps >= 0) {
    return {
      value: senderFps,
      text: `${formatMetricValue(senderFps)} fps`,
      source: "sender_stats",
    }
  }
  const probeFps = Number(state.nativeSenderStatus?.media_probe_fps)
  if (Number.isFinite(probeFps) && probeFps >= 0) {
    return {
      value: probeFps,
      text: `${formatMetricValue(probeFps)} fps`,
      source: "native_sender_probe",
    }
  }
  return {
    value: -1,
    text: "-",
    source: "unavailable",
  }
}

function qualityHintLabel(hint) {
  const normalized = `${hint || ""}`.trim().toLowerCase()
  if (!normalized || normalized === "-") {
    return "-"
  }
  if (normalized === "stable") {
    return "稳定"
  }
  if (normalized === "render_fps_low") {
    return "渲染FPS偏低"
  }
  if (normalized === "recv_bitrate_low") {
    return "接收码率偏低"
  }
  if (normalized === "send_fps_low") {
    return "发送FPS偏低"
  }
  if (normalized === "send_bitrate_low") {
    return "发送码率偏低"
  }
  if (normalized === "canvas_fallback_heavy") {
    return "Canvas回退占比高"
  }
  if (normalized === "capability_blocked") {
    return "环境能力受限"
  }
  if (normalized === "rtt_high") {
    return "RTT偏高"
  }
  if (normalized.startsWith("send_limited_")) {
    return `发送受限(${normalized.slice("send_limited_".length) || "-"})`
  }
  if (normalized.startsWith("path_")) {
    return `候选链路受限(${normalized.slice(5) || "-"})`
  }
  return normalized
}

function inferAgentQualityHint(stats) {
  const sendFps = Number(stats?.sendFps)
  const sendKbps = Number(stats?.sendKbps)
  const tier = `${stats?.candidateTier || "-"}`.toLowerCase()
  const qualityLimit = `${stats?.qualityLimitationReason || "-"}`.toLowerCase()
  if (tier === "relay_tcp" || tier === "p2p_tcp" || tier === "relay_udp_high_rtt") {
    return `path_${stats?.candidateTier || "-"}`
  }
  if (qualityLimit && qualityLimit !== "-" && qualityLimit !== "none") {
    return `send_limited_${qualityLimit}`
  }
  if (Number.isFinite(sendFps) && sendFps >= 0 && sendFps < QUALITY_HINT_FPS_LOW_THRESHOLD) {
    return "send_fps_low"
  }
  const likelyStall = Number.isFinite(sendFps) && sendFps >= 0 && sendFps < QUALITY_HINT_STALL_FPS_THRESHOLD
  if (likelyStall && Number.isFinite(sendKbps) && sendKbps >= 0 && sendKbps < QUALITY_HINT_BITRATE_LOW_THRESHOLD_KBPS) {
    return "send_bitrate_low"
  }
  return "stable"
}

function formatFrameMeta(meta) {
  if (!meta?.width || !meta?.height) {
    return state.webrtcState === "connected" ? "WebRTC 已连接" : `WebRTC ${state.webrtcState || "idle"}`
  }
  const timestamp = meta.captureTs ? ` @ ${new Date(meta.captureTs).toLocaleTimeString()}` : ""
  return `${meta.frameId ? `${meta.frameId} ` : ""}${meta.width}x${meta.height}${timestamp}`
}

function formatInputMessage(msg) {
  const payload = msg.payload || {}
  switch (msg.type) {
    case "input.mouse.move":
      return `鼠标移动 (${formatNumber(payload.x)}, ${formatNumber(payload.y)})`
    case "input.mouse.button":
      return `鼠标 ${payload.button || "left"} ${payload.action || "?"} (${formatNumber(payload.x)}, ${formatNumber(payload.y)})`
    case "input.keyboard.key":
      return `键盘 ${payload.key_code || "?"} ${payload.action || "?"}${Array.isArray(payload.modifiers) && payload.modifiers.length ? ` modifiers=${payload.modifiers.join("+")}` : ""}`
    case "input.wheel.scroll":
      return `滚轮 dx=${formatNumber(payload.delta_x)} dy=${formatNumber(payload.delta_y)}`
    default:
      return msg.type || "unknown"
  }
}

function normalizeRemoteInputCategory(value, inputType = "", summary = "") {
  const normalized = `${value || ""}`.trim().toLowerCase()
  switch (normalized) {
    case "tap":
    case "pointer":
    case "click":
      return "click"
    case "drag":
      return "drag"
    case "key":
    case "keyboard":
      return "keyboard"
    case "scroll":
    case "wheel":
      return "wheel"
    default:
      break
  }

  const normalizedType = `${inputType || ""}`.trim().toLowerCase()
  if (normalizedType === "input.keyboard.key") {
    return "keyboard"
  }
  if (normalizedType === "input.wheel.scroll") {
    return "wheel"
  }
  if (normalizedType === "input.mouse.button") {
    return "click"
  }
  const summaryText = `${summary || ""}`.toLowerCase()
  if (summaryText.includes("drag")) {
    return "drag"
  }
  if (summaryText.includes("wheel") || summaryText.includes("scroll")) {
    return "wheel"
  }
  if (summaryText.includes("keyboard") || summaryText.includes("key")) {
    return "keyboard"
  }
  return ""
}

function remoteInputCoverageSummary(coverage = state.remoteInputAppliedCategories) {
  return REMOTE_INPUT_REQUIRED_CATEGORIES.filter((category) => Boolean(coverage?.[category])).join(",")
}

function remoteInputCoverageComplete(coverage = state.remoteInputAppliedCategories) {
  return REMOTE_INPUT_REQUIRED_CATEGORIES.every((category) => Boolean(coverage?.[category]))
}

function recordRemoteInputAppliedCategory(category) {
  const normalized = normalizeRemoteInputCategory(category)
  if (!normalized) {
    return false
  }
  const coverage = state.remoteInputAppliedCategories || emptyRemoteInputCoverage()
  const changed = !coverage[normalized]
  coverage[normalized] = true
  state.remoteInputAppliedCategories = coverage
  return changed
}

function normalizeRemoteInputResult(payload = {}, from = {}) {
  const inputType = `${payload.input_type || ""}`
  const summary = `${payload.summary || ""}`
  const inputCategory = normalizeRemoteInputCategory(payload.input_category, inputType, summary)
  return {
    input_type: inputType,
    input_category: inputCategory,
    input_trace_id: `${payload.input_trace_id || ""}`,
    applied: Boolean(payload.applied),
    executor: `${payload.executor || ""}`,
    status_code: `${payload.status_code || ""}`,
    status_detail: `${payload.status_detail || ""}`,
    error_code: `${payload.error_code || ""}`,
    error_detail: `${payload.error_detail || ""}`,
    summary,
    input_count: Number(payload.input_count || 0),
    target_device_id: `${from?.device_id || ""}`,
  }
}

function formatRemoteInputResult(result = state.lastRemoteInputResult) {
  if (!result?.input_type) {
    return "-"
  }
  const status = result.applied ? "目标已执行" : "目标未执行"
  const code = result.error_code || result.status_code || "-"
  const executor = result.executor ? ` / ${formatBackendLabel(result.executor)}` : ""
  const category = result.input_category ? `/${result.input_category}` : ""
  const summary = result.summary ? `: ${result.summary}` : ""
  return `${status} ${result.input_type}${category} [${code}${executor}]${summary}`
}

function hostBridgeExecutionState() {
  const status = state.hostBridgeStatus || emptyHostBridgeStatus()
  const statusCode = status.last_status_code || ""
  const errorCode = status.last_error_code || ""
  const executor = status.last_executor || currentPlatformHostInputCapabilities().backend || "-"
  const inputCount = Number(status.input_count) || 0
  const summary = status.last_input_summary || state.lastInput || "尚未收到远端输入"
  const traceId = status.last_trace_id || "-"
  const sessionId = status.last_session_id || state.sessionId || "-"
  const detail = status.last_error_detail || status.last_status_detail || state.hostBridgeError || ""

  if (errorCode) {
    return {
      tone: "error",
      badge: "失败",
      headline: "输入执行失败",
      detail: detail || summary,
      executor,
      statusCode: errorCode,
      inputCount,
      summary,
      traceId,
      sessionId,
    }
  }

  if (statusCode) {
    const applied = statusCode === "applied" || statusCode.startsWith("applied.")
    return {
      tone: applied ? "success" : "warn",
      badge: applied ? "已执行" : "未执行",
      headline: applied
        ? "远端输入已在本机执行"
        : "远端输入已处理但未落地执行",
      detail: detail || summary,
      executor,
      statusCode,
      inputCount,
      summary,
      traceId,
      sessionId,
    }
  }

  return {
    tone: state.hostBridgeError ? "error" : "idle",
    badge: state.hostBridgeError ? "异常" : "待命",
    headline: state.hostBridgeError ? "读取输入执行状态失败" : "等待远端输入进入执行流程",
    detail: state.hostBridgeError
      || (isAgentSession()
        ? "会话开始后，可从控制端发送点击/键盘/滚轮，在这里查看执行反馈。"
        : "当前不是受控端会话，本机不会执行远端输入。"),
    executor,
    statusCode: "-",
    inputCount,
    summary,
    traceId,
    sessionId,
  }
}

function collectSessionLinkMetricsSnapshot() {
  const roleLabel = roleDisplayName(state.role)
  const selectedTarget = state.relayDevices.find((device) => device.device_id === state.targetDeviceId) || null
  const selectedTargetName = selectedTarget?.display_name || state.targetDeviceId || "-"
  const transportModeLabel = formatTransportModeLabel(state.sessionInfo?.transport?.mode || "-")
  const sessionCodecLabel = formatCodecLabel(state.defaultCodec || "-")
  const hostBridgeExecution = hostBridgeExecutionState()
  const remoteInputResult = state.lastRemoteInputResult || emptyRemoteInputResult()
  const remoteInputCoverage = state.remoteInputAppliedCategories || emptyRemoteInputCoverage()
  const agentStats = state.agentStatsSnapshot || emptyAgentStatsSnapshot()
  const controllerStats = state.controllerStatsSnapshot || emptyControllerStatsSnapshot()
  const senderStatsActive = isAgentSession() && agentStats.sessionId === state.sessionId
  const receiverStatsActive = isControllerSession() && controllerStats.sessionId === state.sessionId
  const liveRenderFps = receiverStatsActive ? controllerStats.renderFps : -1
  const liveRecvKbps = receiverStatsActive ? controllerStats.recvKbps : -1
  const liveSendFps = senderStatsActive ? agentStats.sendFps : -1
  const liveSendKbps = senderStatsActive ? agentStats.sendKbps : -1
  const liveCandidatePath = senderStatsActive
    ? agentStats.candidatePath
    : receiverStatsActive
      ? controllerStats.candidatePath
      : "-"
  const liveCandidateTier = senderStatsActive
    ? agentStats.candidateTier
    : receiverStatsActive
      ? controllerStats.candidateTier
      : "-"
  const liveQualityHint = senderStatsActive
    ? inferAgentQualityHint(agentStats)
    : receiverStatsActive
      ? controllerStats.qualityHint
      : "-"
  const firstFrameMs = state.firstFrameAt > 0 && state.sessionStartedAt > 0
    ? Math.max(0, state.firstFrameAt - state.sessionStartedAt)
    : -1
  const localPreviewFpsMetric = resolveLocalPreviewFpsMetric(agentStats)

  return {
    role: roleLabel,
    role_raw: state.role || "-",
    session_id: state.sessionId || "-",
    target_device: selectedTargetName,
    transport: transportModeLabel,
    codec: sessionCodecLabel,
    bridge_input_count: hostBridgeExecution.inputCount,
    bridge_executor: formatBackendLabel(hostBridgeExecution.executor),
    bridge_status_code: hostBridgeExecution.statusCode || "-",
    remote_input_result_count: Number(state.remoteInputResultCount || 0),
    remote_input_result_applied_count: Number(state.remoteInputResultAppliedCount || 0),
    remote_input_applied_categories: remoteInputCoverageSummary(remoteInputCoverage) || "-",
    remote_input_last_type: remoteInputResult.input_type || "-",
    remote_input_last_category: remoteInputResult.input_category || "-",
    remote_input_last_executor: remoteInputResult.executor || "-",
    remote_input_last_status_code: remoteInputResult.error_code || remoteInputResult.status_code || "-",
    first_frame_ms: firstFrameMs >= 0 ? firstFrameMs : "-",
    render_fps: liveRenderFps >= 0 ? `${formatMetricValue(liveRenderFps)} fps` : "-",
    recv_kbps: liveRecvKbps >= 0 ? `${formatMetricValue(liveRecvKbps)} kbps` : "-",
    send_fps_and_kbps: liveSendFps >= 0 || liveSendKbps >= 0
      ? `${formatMetricValue(liveSendFps)} fps / ${formatMetricValue(liveSendKbps)} kbps`
      : "-",
    local_preview_fps: localPreviewFpsMetric.text,
    local_preview_fps_source: localPreviewFpsMetric.source,
    local_preview_mode: state.nativeSenderPreviewMode || (state.localMediaStream ? "video_stream" : (state.localFrameUrl ? "image_stream" : "-")),
    candidate_path: liveCandidatePath || "-",
    candidate_tier: liveCandidateTier || "-",
    quality_hint: qualityHintLabel(liveQualityHint),
    quality_hint_raw: liveQualityHint || "-",
    webrtc_state: state.webrtcState || "-",
    session_side: currentSessionSide(),
  }
}

function logSessionLinkMetricsSnapshot(reason = "interval") {
  if (!isAgentSession() || !state.sessionId) {
    return
  }
  const snapshot = collectSessionLinkMetricsSnapshot()
  traceLog("session.link.snapshot", {
    reason,
    ...snapshot,
  }, { console: false })
  emitNativeDebugLog(`session.link.snapshot ${JSON.stringify({ reason, ...snapshot })}`)
}

function ensureSessionLinkSnapshotLoggerRunning() {
  if (sessionLinkSnapshotTimer) {
    return
  }
  sessionLinkSnapshotTimer = window.setInterval(() => {
    logSessionLinkMetricsSnapshot("interval")
  }, SESSION_LINK_SNAPSHOT_LOG_INTERVAL_MS)
}

function hostBridgeToneStyles(tone) {
  switch (tone) {
    case "success":
      return {
        border: "rgba(34, 197, 94, 0.35)",
        background: "rgba(34, 197, 94, 0.10)",
        badgeBackground: "rgba(34, 197, 94, 0.18)",
        badgeText: "#15803d",
      }
    case "warn":
      return {
        border: "rgba(251, 191, 36, 0.4)",
        background: "rgba(251, 191, 36, 0.10)",
        badgeBackground: "rgba(251, 191, 36, 0.16)",
        badgeText: "#b45309",
      }
    case "error":
      return {
        border: "rgba(248, 113, 113, 0.38)",
        background: "rgba(248, 113, 113, 0.10)",
        badgeBackground: "rgba(248, 113, 113, 0.16)",
        badgeText: "#b91c1c",
      }
    default:
      return {
        border: "rgba(148, 163, 184, 0.35)",
        background: "rgba(148, 163, 184, 0.10)",
        badgeBackground: "rgba(148, 163, 184, 0.14)",
        badgeText: "#64748b",
      }
  }
}

function normalizeHostBridgeStatus(status) {
  if (!status || typeof status !== "object") {
    return emptyHostBridgeStatus()
  }

  return {
    ...emptyHostBridgeStatus(),
    ...status,
  }
}

function applyHostBridgeSnapshot(snapshot = {}) {
  state.hostBridgeStatus = normalizeHostBridgeStatus({
    ...state.hostBridgeStatus,
    ...snapshot,
  })
  state.hostBridgeError = state.hostBridgeStatus.last_error_detail || ""
  return state.hostBridgeStatus
}

function recordHostBridgeFailure(msg, snapshot = {}) {
  const normalizedMessageType = typeof msg?.type === "string" ? msg.type : ""
  const normalizedSessionId = typeof msg?.session_id === "string" ? msg.session_id.trim() : ""
  const normalizedTraceId = typeof msg?.trace_id === "string" ? msg.trace_id.trim() : ""

  return applyHostBridgeSnapshot({
    input_count: Number(state.hostBridgeStatus?.input_count) || 0,
    last_input_type: normalizedMessageType,
    last_input_summary: snapshot.last_input_summary || (normalizedMessageType ? formatInputMessage(msg) : state.lastInput || "-"),
    last_session_id: snapshot.last_session_id || normalizedSessionId || state.sessionId || "",
    last_trace_id: snapshot.last_trace_id || normalizedTraceId,
    last_executor: snapshot.last_executor || "host.bridge",
    last_status_code: snapshot.last_status_code || snapshot.last_error_code || "error",
    last_status_detail: snapshot.last_status_detail || snapshot.last_error_detail || state.hostBridgeError || "",
    last_error_code: snapshot.last_error_code || "host.bridge.error",
    last_error_detail: snapshot.last_error_detail || snapshot.last_status_detail || state.hostBridgeError || "host bridge 应用输入失败",
  })
}

function syncControlsFromInputs() {
  state.wsUrl = `${state.wsUrl || ""}`.trim()
  persistLocalSettings()
}

function syncSelectedTargetPreview() {
  const selectedTarget = state.relayDevices.find((device) => device.device_id === state.targetDeviceId) || null
  const selectedTargetName = selectedTarget?.display_name || state.targetDeviceId || "未选择设备"
  const selectedTargetOffline = selectedTarget?.status === "offline"
  const selectedTargetMeta = selectedTarget
    ? `${selectedTarget.platform_label} · ${selectedTarget.role_label} · ${selectedTarget.status_label}`
    : (state.targetDeviceId ? "已手动填写目标设备 ID" : "未选择伙伴设备")
  const assistConnectLabel = state.sessionId
    ? "结束远控"
    : !localCanInitiateRemoteControl()
      ? "本机不可发起远控"
      : !state.targetDeviceId
        ? "请选择目标设备"
      : selectedTargetOffline
        ? "目标设备离线"
        : "连接伙伴设备"
  const assistConnectDisabled = Boolean(!state.sessionId && (!localCanInitiateRemoteControl() || !state.targetDeviceId || !state.presenceReady || selectedTargetOffline))

  const selectedTargetNameElement = document.getElementById("selectedTargetName")
  if (selectedTargetNameElement) {
    selectedTargetNameElement.textContent = selectedTargetName
  }

  const selectedTargetMetaElement = document.getElementById("selectedTargetMeta")
  if (selectedTargetMetaElement) {
    selectedTargetMetaElement.textContent = selectedTargetMeta
  }

  const assistConnectButton = document.getElementById("assistConnectBtn")
  if (assistConnectButton instanceof HTMLButtonElement) {
    assistConnectButton.disabled = assistConnectDisabled
    assistConnectButton.textContent = assistConnectLabel
    assistConnectButton.classList.toggle("danger-btn", Boolean(state.sessionId))
    assistConnectButton.classList.toggle("secondary-btn", !state.sessionId)
    assistConnectButton.onclick = state.sessionId ? endSession : requestSession
  }

  document.querySelectorAll("[data-device-pill-id]").forEach((element) => {
    element.classList.toggle("device-pill-active", element.getAttribute("data-device-pill-id") === state.targetDeviceId)
  })

  document.querySelectorAll("[data-device-row-id]").forEach((element) => {
    element.classList.toggle("device-row-active", element.getAttribute("data-device-row-id") === state.targetDeviceId)
  })
}

function sendEnvelope(type, payload = {}, sessionId = "", options = {}) {
  if (!connectionReady()) {
    if (options.log !== false) {
      appendLog("WebSocket 未连接")
      render()
    }
    return false
  }

  const now = Date.now()
  const messageSeq = state.messageSeq + 1
  state.messageSeq = messageSeq
  const normalizedSessionId = typeof sessionId === "string" ? sessionId.trim() : ""
  const fromRole = normalizedSessionId ? currentSessionSourceRole(normalizedSessionId) : state.role
  const message = {
    v: state.protocolVersion,
    msg_id: `${type}-${now}-${messageSeq}`,
    type,
    ts: now,
    trace_id: `trace-${now}-${messageSeq}`,
    from: {
      device_id: state.deviceId,
      role: fromRole,
    },
    payload,
  }

  if (normalizedSessionId) {
    message.session_id = normalizedSessionId
  }

  const payloadText = JSON.stringify(message)
  traceLog("ws.send.prepare", summarizeEnvelope(message, {
    direction: "out",
    bytes: payloadText.length,
  }), { console: false })

  try {
    state.ws.send(payloadText)
  } catch (error) {
    if (options.log !== false) {
      appendLog(`发送 ${type} 失败: ${error.message}`)
      render()
    }
    traceLog("ws.send.failed", {
      type,
      session_id: message.session_id || "-",
      reason: error?.message || "unknown",
    })
    return false
  }

  if (type.startsWith("webrtc.") && message.session_id && options.skipNativeMirror !== true) {
    void pushNativeSenderSignal(
      message.session_id,
      type,
      message.trace_id,
      payload,
      "outbound",
    )
  }

  if (isTauri() && type.startsWith("webrtc.")) {
    void invoke("debug_log", {
      line: `signal.send type=${type} session=${message.session_id || "-"} msg=${message.msg_id || "-"}`,
    }).catch(() => {})
  }

  traceLog("ws.send.ok", summarizeEnvelope(message, {
    direction: "out",
    bytes: payloadText.length,
  }))

  if (options.log !== false) {
    appendLog(options.logLine || `发送 ${type}`)
    render()
  }
  return true
}

function sessionIceServers() {
  const fromSession = Array.isArray(state.sessionInfo?.webrtc?.ice_servers)
    ? state.sessionInfo.webrtc.ice_servers
    : []
  if (!fromSession.length) {
    return [{ urls: ["stun:stun.l.google.com:19302"] }]
  }
  return fromSession
    .map((server) => {
      if (!server || typeof server !== "object") {
        return null
      }
      const urls = Array.isArray(server.urls)
        ? server.urls.filter((url) => typeof url === "string" && url.trim())
        : (typeof server.urls === "string" && server.urls.trim() ? [server.urls.trim()] : [])
      const orderedUrls = orderIceUrls(urls)
      if (!orderedUrls.length) {
        return null
      }
      const normalized = { urls: orderedUrls }
      if (typeof server.username === "string" && server.username.trim()) {
        normalized.username = server.username.trim()
      }
      if (typeof server.credential === "string" && server.credential.trim()) {
        normalized.credential = server.credential.trim()
      }
      return normalized
    })
    .filter(Boolean)
}

function orderIceUrls(urls) {
  if (!Array.isArray(urls) || !urls.length) {
    return []
  }
  return Array.from(new Set(
    urls
      .map((url) => `${url || ""}`.trim())
      .filter(Boolean),
  )).sort((left, right) => {
    const leftPriority = iceUrlPriority(left)
    const rightPriority = iceUrlPriority(right)
    if (leftPriority !== rightPriority) {
      return leftPriority - rightPriority
    }
    if (left.length !== right.length) {
      return left.length - right.length
    }
    return left.localeCompare(right)
  })
}

function iceUrlPriority(url) {
  const normalized = `${url || ""}`.trim().toLowerCase()
  if (!normalized) {
    return 9
  }
  if (normalized.startsWith("stun:")) {
    return 0
  }
  if (normalized.startsWith("turn:") && normalized.includes("transport=udp")) {
    return 1
  }
  if (normalized.startsWith("turn:") && normalized.includes("transport=tcp")) {
    return 3
  }
  if (normalized.startsWith("turn:")) {
    return 2
  }
  return 4
}

function currentSessionRelayUdpHighRttMs() {
  const configured = Number(state.sessionInfo?.webrtc?.ice_policy?.relay_udp_high_rtt_ms)
  if (Number.isFinite(configured) && configured >= 0) {
    return configured
  }
  return ICE_POLICY_RELAY_UDP_HIGH_RTT_MS
}

function currentSessionIceTransportPolicy() {
  const mode = `${state.sessionInfo?.webrtc?.ice_policy?.mode || ""}`.trim().toLowerCase()
  if (mode.startsWith("relay")) {
    return "relay"
  }
  return "all"
}

function classifyIceCandidateTier(candidatePath = "", roundTripTimeMs = -1, options = {}) {
  const segments = `${candidatePath || ""}`.split("/").map((value) => `${value || ""}`.trim().toLowerCase())
  const localType = segments[0] || ""
  const remoteType = segments[1] || ""
  const protocol = segments[2] || ""
  const hasRelay = localType === "relay" || remoteType === "relay"
  const rtt = Number(roundTripTimeMs)
  const relayUdpHighRttMs = Number.isFinite(Number(options.relayUdpHighRttMs))
    ? Number(options.relayUdpHighRttMs)
    : ICE_POLICY_RELAY_UDP_HIGH_RTT_MS
  const highRelayRtt = Number.isFinite(rtt) && rtt >= relayUdpHighRttMs
  if (protocol === "udp" && hasRelay) {
    return highRelayRtt ? "relay_udp_high_rtt" : "relay_udp"
  }
  if (protocol === "udp") {
    return "p2p_udp"
  }
  if (protocol === "tcp" && hasRelay) {
    return "relay_tcp"
  }
  if (protocol === "tcp") {
    return "p2p_tcp"
  }
  if (hasRelay) {
    return "relay_other"
  }
  if (localType || remoteType) {
    return "p2p_other"
  }
  return "-"
}

function findAgentVideoSender(pc) {
  if (!pc || typeof pc.getSenders !== "function") {
    return null
  }
  return pc.getSenders().find((sender) => sender?.track?.kind === "video") || null
}

async function applyNativeFallbackProfile(profile) {
  if (!isTauri() || !profile) {
    return
  }
  const effectiveProfile = buildEffectiveAdaptiveProfile(profile)
  if (!state.nativeBridgeStream && !nativeCaptureSourceReady()) {
    return
  }
  try {
    syncCaptureStatus(await invoke("capture_update_config", {
      patch: {
        maxWidth: effectiveProfile.maxWidth,
        maxHeight: effectiveProfile.maxHeight,
        maxFps: effectiveProfile.maxFps,
        codec: NATIVE_CAPTURE_FALLBACK_CODEC,
      },
    }))
  } catch (error) {
    traceLog("native.bridge.adaptive.config_failed", {
      session_id: state.sessionId || "-",
      reason: error?.message || "unknown",
      profile: profile.id,
    })
  }
}

async function tuneVideoSender(sender, options = {}) {
  if (!sender || sender.track?.kind !== "video" || typeof sender.getParameters !== "function" || typeof sender.setParameters !== "function") {
    return
  }

  const baseProfile = options.profile || currentAgentAdaptiveProfile()
  const profile = buildEffectiveAdaptiveProfile(baseProfile)
  if (!baseProfile || !profile) {
    return
  }
  if (!state.agentAdaptive) {
    resetAgentAdaptiveState()
  }
  const adaptiveState = state.agentAdaptive
  const mediaPath = inferAgentMediaPath()
  const sceneMode = options.sceneMode || adaptiveState.sceneMode || "mixed"
  const scenePolicy = options.scenePolicy || resolveAdaptiveScenePolicy(sceneMode)
  const signature = [
    profile.id,
    profile.maxBitrate,
    profile.maxFps,
    profile.scaleResolutionDownBy,
    mediaPath,
    scenePolicy.degradationPreference || "maintain-framerate",
  ].join(":")
  if (!options.force && adaptiveState.lastAppliedSignature === signature) {
    return
  }
  if (adaptiveState.applying) {
    return
  }
  adaptiveState.applying = true

  try {
    const parameters = sender.getParameters() || {}
    const encodings = Array.isArray(parameters.encodings) && parameters.encodings.length > 0
      ? parameters.encodings.map((encoding) => ({ ...encoding }))
      : [{}]

    for (const encoding of encodings) {
      encoding.maxBitrate = profile.maxBitrate
      encoding.maxFramerate = profile.maxFps
      if (profile.scaleResolutionDownBy > 1.01) {
        encoding.scaleResolutionDownBy = profile.scaleResolutionDownBy
      } else if ("scaleResolutionDownBy" in encoding) {
        delete encoding.scaleResolutionDownBy
      }
      if (!encoding.priority) {
        encoding.priority = "high"
      }
    }

    parameters.encodings = encodings
    parameters.degradationPreference = options.degradationPreference
      || scenePolicy.degradationPreference
      || "maintain-framerate"
    const timeoutMs = 2000
    await Promise.race([
      sender.setParameters(parameters),
      new Promise((_, reject) => {
        window.setTimeout(() => {
          reject(new Error(`setParameters timeout (${timeoutMs}ms)`))
        }, timeoutMs)
      }),
    ])
    if (mediaPath === "native_bridge") {
      await applyNativeFallbackProfile(profile)
    } else if (mediaPath === "native_sender") {
      await applyNativeSenderCaptureConfig(state.sessionId, { log: false })
    }
    adaptiveState.lastAppliedSignature = signature
    adaptiveState.lastAppliedReason = options.reason || ""
    adaptiveState.mediaPath = mediaPath
    traceLog("webrtc.agent.adaptive.applied", {
      session_id: state.sessionId || "-",
      profile: profile.id,
      profile_label: profile.label,
      reason: options.reason || "-",
      max_bitrate: profile.maxBitrate,
      max_fps: profile.maxFps,
      scale_down_by: profile.scaleResolutionDownBy,
      media_path: mediaPath,
      profile_capped_by: profile.cappedBy || "-",
      scene_mode: sceneMode,
      scene_policy: scenePolicy.label || adaptiveSceneLabel(sceneMode),
      degradation_preference: parameters.degradationPreference || "-",
    })
    if (options.log !== false) {
      appendLog(
        `受控端画质档位切换为 ${profile.label} (${profile.maxWidth}x${profile.maxHeight}@${profile.maxFps}fps, ${(profile.maxBitrate / 1000000).toFixed(1)}Mbps)`,
      )
      render()
    }
  } catch (error) {
    traceLog("webrtc.agent.adaptive.apply_failed", {
      session_id: state.sessionId || "-",
      profile: profile.id,
      reason: error?.message || "unknown",
    })
    if (options.log !== false) {
      appendLog(`设置视频发送参数失败: ${error?.message || "unknown error"}`)
      render()
    }
  } finally {
    adaptiveState.applying = false
  }
}

function nextAdaptiveEwma(previous, current) {
  const numeric = Number(current)
  if (!Number.isFinite(numeric) || numeric < 0) {
    return Number.isFinite(previous) ? previous : -1
  }
  if (!Number.isFinite(previous) || previous < 0) {
    return numeric
  }
  const alpha = Math.min(1, Math.max(0.05, AGENT_ADAPTIVE_EWMA_ALPHA))
  return previous + (numeric - previous) * alpha
}

function adaptiveUpgradeBitrateRatio(profileId, sceneMode = "mixed") {
  const profile = `${profileId || ""}`
  const scene = `${sceneMode || "mixed"}`
  if (scene === "static") {
    switch (profile) {
      case "balanced":
        return 0.06
      case "smooth":
        return 0.03
      default:
        return 0.05
    }
  }
  if (scene === "dynamic") {
    switch (profile) {
      case "balanced":
        return 0.52
      case "smooth":
        return 0.42
      default:
        return 0.58
    }
  }
  switch (profile) {
    case "balanced":
      return 0.62
    case "smooth":
      return 0.4
    default:
      return 0.55
  }
}

function adaptiveSceneLabel(mode) {
  switch (`${mode || "mixed"}`) {
    case "static":
      return "静态清晰"
    case "dynamic":
      return "动态流畅"
    default:
      return "混合"
  }
}

function resolveAdaptiveScenePolicy(sceneMode) {
  const mode = `${sceneMode || "mixed"}`
  if (mode === "static") {
    return {
      mode,
      label: adaptiveSceneLabel(mode),
      degradationPreference: "maintain-resolution",
      targetProfileIndex: AGENT_ADAPTIVE_PROFILES.length - 1,
      badStreakForDegrade: 3,
      severeBadStreakForDegrade: 2,
      fpsDropRatio: 0.64,
      severeFpsDropRatio: 0.5,
      bridgeLowRatio: 0.55,
      lowBitrateRatio: 0.2,
      upgradeStableSamples: 3,
      fpsHealthyRatio: 0.75,
      rttHealthyMaxMs: 180,
      bridgeHealthyRatio: 0.62,
      relayConstrainedRttMs: 145,
    }
  }
  if (mode === "dynamic") {
    return {
      mode,
      label: adaptiveSceneLabel(mode),
      degradationPreference: "maintain-framerate",
      targetProfileIndex: dynamicTargetProfileIndexForCurrentSession(),
      badStreakForDegrade: 2,
      severeBadStreakForDegrade: 1,
      fpsDropRatio: 0.82,
      severeFpsDropRatio: 0.66,
      bridgeLowRatio: 0.7,
      lowBitrateRatio: 0.3,
      upgradeStableSamples: 9,
      fpsHealthyRatio: 0.94,
      rttHealthyMaxMs: 135,
      bridgeHealthyRatio: 0.82,
      relayConstrainedRttMs: 95,
    }
  }
  return {
    mode: "mixed",
    label: adaptiveSceneLabel("mixed"),
    degradationPreference: "maintain-framerate",
    targetProfileIndex: AGENT_ADAPTIVE_DEFAULT_PROFILE_INDEX,
    badStreakForDegrade: AGENT_ADAPTIVE_BAD_SAMPLE_STREAK_FOR_DEGRADE,
    severeBadStreakForDegrade: AGENT_ADAPTIVE_SEVERE_BAD_SAMPLE_STREAK_FOR_DEGRADE,
    fpsDropRatio: 0.72,
    severeFpsDropRatio: 0.52,
    bridgeLowRatio: 0.62,
    lowBitrateRatio: 0.32,
    upgradeStableSamples: AGENT_ADAPTIVE_UPGRADE_STABLE_SAMPLES,
    fpsHealthyRatio: 0.92,
    rttHealthyMaxMs: 125,
    bridgeHealthyRatio: 0.78,
    relayConstrainedRttMs: 85,
  }
}

function updateAdaptiveSceneMode(adaptiveState, context) {
  const nowMs = Number(context.nowMs || Date.now())
  const currentMode = `${adaptiveState.sceneMode || "mixed"}`
  const observedSendFps = Number(context.observedSendFps)
  const observedSendKbps = Number(context.observedSendKbps)
  const observedRttMs = Number(context.observedRttMs)
  const qualityLimited = Boolean(context.isLimitedByQuality)
  const relayPath = Boolean(context.relayPath)
  const tcpPath = Boolean(context.tcpPath)
  const currentProfile = context.currentProfile || currentEffectiveAgentAdaptiveProfile()
  const maxBitrateKbps = Math.max(1, Number(currentProfile?.maxBitrate || 0) / 1000)
  const profileMaxFps = Math.max(1, Number(currentProfile?.maxFps || 24))
  const bitrateUtil = observedSendKbps >= 0 ? observedSendKbps / maxBitrateKbps : -1
  const fpsPressureDynamicSignal = observedSendFps >= 0
    && observedSendFps < profileMaxFps * 0.86
    && observedSendKbps >= 120
  const dynamicSignal = (
    (observedSendKbps >= AGENT_ADAPTIVE_SCENE_DYNAMIC_MIN_KBPS && observedSendFps >= Math.max(12, profileMaxFps * 0.7))
    || (bitrateUtil >= AGENT_ADAPTIVE_SCENE_DYNAMIC_MIN_UTIL && observedSendFps >= Math.max(10, profileMaxFps * 0.6))
    || fpsPressureDynamicSignal
    || (qualityLimited && observedRttMs >= 130)
  )
  const staticSignal = (
    !qualityLimited
    && observedSendFps >= Math.max(10, Number(currentProfile?.maxFps || 24) * 0.55)
    && observedSendKbps >= 0
    && observedSendKbps <= AGENT_ADAPTIVE_SCENE_STATIC_MAX_KBPS
    && bitrateUtil >= 0
    && bitrateUtil <= AGENT_ADAPTIVE_SCENE_STATIC_MAX_UTIL
    && (observedRttMs < 0 || observedRttMs < 180)
    && !tcpPath
    && !(relayPath && observedRttMs >= 140)
  )

  if (dynamicSignal && !staticSignal) {
    adaptiveState.sceneDynamicSignalStreak += 1
    adaptiveState.sceneStaticSignalStreak = Math.max(0, adaptiveState.sceneStaticSignalStreak - 1)
  } else if (staticSignal && !dynamicSignal) {
    adaptiveState.sceneStaticSignalStreak += 1
    adaptiveState.sceneDynamicSignalStreak = Math.max(0, adaptiveState.sceneDynamicSignalStreak - 1)
  } else {
    adaptiveState.sceneDynamicSignalStreak = Math.max(0, adaptiveState.sceneDynamicSignalStreak - 1)
    adaptiveState.sceneStaticSignalStreak = Math.max(0, adaptiveState.sceneStaticSignalStreak - 1)
  }

  let nextMode = currentMode
  if (adaptiveState.sceneDynamicSignalStreak >= AGENT_ADAPTIVE_SCENE_DYNAMIC_ENTER_STREAK) {
    nextMode = "dynamic"
  } else if (adaptiveState.sceneStaticSignalStreak >= AGENT_ADAPTIVE_SCENE_STATIC_ENTER_STREAK) {
    nextMode = "static"
  }
  const enoughDwell = nowMs - Number(adaptiveState.sceneLastSwitchAt || 0) >= AGENT_ADAPTIVE_SCENE_SWITCH_MIN_DWELL_MS
  let changed = false
  if (nextMode !== currentMode && (adaptiveState.sceneLastSwitchAt <= 0 || enoughDwell)) {
    adaptiveState.sceneMode = nextMode
    adaptiveState.sceneLastSwitchAt = nowMs
    adaptiveState.sceneSwitchCount = Number(adaptiveState.sceneSwitchCount || 0) + 1
    adaptiveState.sceneDynamicSignalStreak = 0
    adaptiveState.sceneStaticSignalStreak = 0
    changed = true
  }
  return {
    mode: `${adaptiveState.sceneMode || "mixed"}`,
    previousMode: currentMode,
    changed,
    dynamicSignal,
    staticSignal,
    bitrateUtil,
  }
}

async function maybeAdjustAgentAdaptiveProfile(sessionId, pc, stats, nowMs) {
  let adaptiveState = state.agentAdaptive
  if (!adaptiveState || !sessionId || !stats) {
    return {
      sendFps: -1,
      sendKbps: -1,
      bridgeFps: -1,
      profile: currentEffectiveAgentAdaptiveProfile(),
      decision: "skip",
      sceneMode: adaptiveState?.sceneMode || "mixed",
      sceneSwitches: Number(adaptiveState?.sceneSwitchCount || 0),
    }
  }

  if (adaptiveState.sessionId && adaptiveState.sessionId !== sessionId) {
    resetAgentAdaptiveState()
    adaptiveState = state.agentAdaptive
  }
  adaptiveState.sessionId = sessionId
  const nativeSenderOwnerMode = nativeSenderOwnershipEnabledForSession(sessionId)
  const minAllowedProfileIndex = Math.max(0, Math.min(
    AGENT_ADAPTIVE_PROFILES.length - 1,
    minAdaptiveProfileIndexForCurrentSession(),
  ))
  const maxAllowedProfileIndex = Math.max(0, Math.min(
    AGENT_ADAPTIVE_PROFILES.length - 1,
    maxAdaptiveProfileIndexForCurrentSession(),
  ))
  if (!Number.isFinite(adaptiveState.profileIndex)) {
    adaptiveState.profileIndex = maxAllowedProfileIndex
  } else if (adaptiveState.profileIndex > maxAllowedProfileIndex) {
    adaptiveState.profileIndex = maxAllowedProfileIndex
  } else if (adaptiveState.profileIndex < minAllowedProfileIndex) {
    adaptiveState.profileIndex = minAllowedProfileIndex
  }

  if (
    adaptiveState.lastFramesSent > 0
    && adaptiveState.lastBytesSent > 0
    && (stats.framesSent < adaptiveState.lastFramesSent || stats.bytesSent < adaptiveState.lastBytesSent)
  ) {
    adaptiveState.lastSampleAt = 0
  }

  const elapsedSec = adaptiveState.lastSampleAt > 0 && nowMs > adaptiveState.lastSampleAt
    ? (nowMs - adaptiveState.lastSampleAt) / 1000
    : 0
  let sendFps = -1
  let sendKbps = -1
  let bridgeFps = -1
  if (elapsedSec >= 0.4) {
    const frameDelta = Math.max(0, stats.framesSent - adaptiveState.lastFramesSent)
    const bytesDelta = Math.max(0, stats.bytesSent - adaptiveState.lastBytesSent)
    sendFps = frameDelta / elapsedSec
    sendKbps = (bytesDelta * 8) / elapsedSec / 1000
    if (nativeSenderOwnerMode) {
      bridgeFps = -1
    } else {
      const bridgeFrameDelta = Math.max(0, adaptiveState.bridgeProducedFrames - adaptiveState.lastBridgeFrames)
      bridgeFps = bridgeFrameDelta / elapsedSec
    }
  }

  adaptiveState.lastSampleAt = nowMs
  adaptiveState.lastFramesSent = stats.framesSent
  adaptiveState.lastBytesSent = stats.bytesSent
  adaptiveState.lastBridgeFrames = adaptiveState.bridgeProducedFrames
  adaptiveState.sessionStartedAt = Number(adaptiveState.sessionStartedAt || nowMs)

  const currentProfile = currentEffectiveAgentAdaptiveProfile()
  if (sendFps < 0 || sendKbps < 0) {
    return {
      sendFps,
      sendKbps,
      bridgeFps,
      profile: currentProfile,
      decision: "warmup",
      sceneMode: adaptiveState.sceneMode || "mixed",
      sceneSwitches: Number(adaptiveState.sceneSwitchCount || 0),
    }
  }

  adaptiveState.ewmaSendFps = nextAdaptiveEwma(adaptiveState.ewmaSendFps, sendFps)
  adaptiveState.ewmaSendKbps = nextAdaptiveEwma(adaptiveState.ewmaSendKbps, sendKbps)
  adaptiveState.ewmaBridgeFps = nextAdaptiveEwma(adaptiveState.ewmaBridgeFps, bridgeFps)
  adaptiveState.ewmaRttMs = nextAdaptiveEwma(adaptiveState.ewmaRttMs, stats.roundTripTimeMs)

  const observedSendFps = adaptiveState.ewmaSendFps >= 0 ? adaptiveState.ewmaSendFps : sendFps
  const observedSendKbps = adaptiveState.ewmaSendKbps >= 0 ? adaptiveState.ewmaSendKbps : sendKbps
  const observedBridgeFps = adaptiveState.ewmaBridgeFps >= 0 ? adaptiveState.ewmaBridgeFps : bridgeFps
  const observedRttMs = adaptiveState.ewmaRttMs >= 0 ? adaptiveState.ewmaRttMs : stats.roundTripTimeMs
  adaptiveState.sampleSeq = Number.isFinite(adaptiveState.sampleSeq) ? adaptiveState.sampleSeq + 1 : 1

  const qualityLimitReason = `${stats.qualityLimitationReason || "-"}`.toLowerCase()
  const isLimitedByQuality = qualityLimitReason === "bandwidth" || qualityLimitReason === "cpu" || qualityLimitReason === "other"
  const candidateTier = `${stats.candidateTier || classifyIceCandidateTier(
    stats.candidatePath,
    stats.roundTripTimeMs,
    { relayUdpHighRttMs: currentSessionRelayUdpHighRttMs() },
  )}`.toLowerCase()
  const relayPath = candidateTier.startsWith("relay_")
  const tcpPath = candidateTier.endsWith("_tcp")
  const sceneResolution = updateAdaptiveSceneMode(adaptiveState, {
    nowMs,
    observedSendFps,
    observedSendKbps,
    observedRttMs,
    isLimitedByQuality,
    relayPath,
    tcpPath,
    currentProfile,
  })
  const scenePolicy = resolveAdaptiveScenePolicy(sceneResolution.mode)
  if (sceneResolution.changed) {
    emitNativeDebugLog(
      `webrtc.agent.adaptive.scene_switch session=${sessionId || "-"} from=${adaptiveSceneLabel(sceneResolution.previousMode)} to=${adaptiveSceneLabel(scenePolicy.mode)} ewma_fps=${observedSendFps.toFixed(2)} ewma_kbps=${Math.round(observedSendKbps)} util=${sceneResolution.bitrateUtil >= 0 ? sceneResolution.bitrateUtil.toFixed(2) : "-"} ewma_rtt=${Math.round(observedRttMs)} dynamic_signal=${sceneResolution.dynamicSignal ? 1 : 0} static_signal=${sceneResolution.staticSignal ? 1 : 0}`,
    )
  }
  const sceneTargetProfileIndex = clampAdaptiveProfileIndex(Math.max(
    minAllowedProfileIndex,
    Math.min(maxAllowedProfileIndex, scenePolicy.targetProfileIndex),
  ))
  const warmupDurationMs = currentSessionControllerProfile() === "emulator"
    ? AGENT_ADAPTIVE_EMULATOR_WARMUP_MS
    : 0
  const warmupActive = warmupDurationMs > 0
    && nowMs - adaptiveState.sessionStartedAt < warmupDurationMs
  if (warmupActive && !adaptiveState.warmupLogged) {
    adaptiveState.warmupLogged = true
    emitNativeDebugLog(
      `webrtc.agent.adaptive.warmup_lock session=${sessionId || "-"} duration_ms=${warmupDurationMs} profile=${currentProfile.id} max_profile=${maxAllowedProfileIndex}`,
    )
  }
  if (!warmupActive && adaptiveState.warmupLogged && !adaptiveState.warmupReleasedLogged) {
    adaptiveState.warmupReleasedLogged = true
    emitNativeDebugLog(
      `webrtc.agent.adaptive.warmup_release session=${sessionId || "-"} elapsed_ms=${nowMs - adaptiveState.sessionStartedAt} profile=${currentProfile.id} scene=${scenePolicy.mode}`,
    )
  }
  const highRtt = observedRttMs >= 220
  const mildRtt = observedRttMs >= 160
  const fpsDrop = observedSendFps < Math.max(AGENT_ADAPTIVE_MIN_SEND_FPS, currentProfile.maxFps * scenePolicy.fpsDropRatio)
  const severeFpsDrop = observedSendFps < Math.max(AGENT_ADAPTIVE_MIN_SEND_FPS, currentProfile.maxFps * scenePolicy.severeFpsDropRatio)
  const bridgeLow = observedBridgeFps >= 0 && observedBridgeFps < currentProfile.maxFps * scenePolicy.bridgeLowRatio
  const lowBitrate = observedSendKbps >= 0 && observedSendKbps < (currentProfile.maxBitrate / 1000) * scenePolicy.lowBitrateRatio
  const poorQualitySignal = isLimitedByQuality || bridgeLow || lowBitrate
  const badSample = severeFpsDrop || (fpsDrop && (mildRtt || poorQualitySignal)) || (highRtt && (isLimitedByQuality || relayPath || tcpPath))
  const severeBadSample = severeFpsDrop && (highRtt || isLimitedByQuality || bridgeLow || lowBitrate)

  adaptiveState.badSampleStreak = badSample ? adaptiveState.badSampleStreak + 1 : 0
  adaptiveState.severeBadSampleStreak = severeBadSample ? adaptiveState.severeBadSampleStreak + 1 : 0

  if (adaptiveState.sampleSeq % 3 === 0) {
    emitNativeDebugLog(
      `webrtc.agent.adaptive.sample session=${sessionId || "-"} profile=${currentProfile.id} scene=${scenePolicy.mode} raw_fps=${sendFps.toFixed(2)} ewma_fps=${observedSendFps.toFixed(2)} raw_kbps=${Math.round(sendKbps)} ewma_kbps=${Math.round(observedSendKbps)} util=${sceneResolution.bitrateUtil >= 0 ? sceneResolution.bitrateUtil.toFixed(2) : "-"} raw_rtt=${stats.roundTripTimeMs} ewma_rtt=${Math.round(observedRttMs)} path_tier=${candidateTier} ql=${qualityLimitReason} bad=${adaptiveState.badSampleStreak} severe=${adaptiveState.severeBadSampleStreak} stable=${adaptiveState.stableUpgradeSamples}`,
    )
  }

  const degradeStreakRequired = scenePolicy.badStreakForDegrade
  const severeDegradeStreakRequired = scenePolicy.severeBadStreakForDegrade
  const degradeSwitchAllowed = nowMs - adaptiveState.lastSwitchAt >= AGENT_ADAPTIVE_DEGRADE_SWITCH_COOLDOWN_MS
  if (
    adaptiveState.profileIndex > sceneTargetProfileIndex
    && degradeSwitchAllowed
    && scenePolicy.mode === "dynamic"
  ) {
    const previousProfile = currentProfile
    adaptiveState.profileIndex = sceneTargetProfileIndex
    adaptiveState.lastSwitchAt = nowMs
    adaptiveState.stableUpgradeSamples = 0
    adaptiveState.badSampleStreak = 0
    adaptiveState.severeBadSampleStreak = 0
    const nextProfile = currentAgentAdaptiveProfile()
    const nextEffectiveProfile = buildEffectiveAdaptiveProfile(nextProfile)
    const sender = findAgentVideoSender(pc)
    if (sender) {
      await tuneVideoSender(sender, {
        profile: nextEffectiveProfile,
        sceneMode: scenePolicy.mode,
        scenePolicy,
        reason: `scene_shift:${scenePolicy.mode}:${Math.round(observedSendKbps)}kbps:${observedSendFps.toFixed(1)}fps`,
      })
    } else if (nativeSenderOwnerMode) {
      await applyNativeSenderCaptureConfig(sessionId, { log: false })
      emitNativeDebugLog(
        `webrtc.agent.adaptive.native_sender_apply session=${sessionId || "-"} reason=scene_shift profile=${nextEffectiveProfile.id} size=${nextEffectiveProfile.maxWidth}x${nextEffectiveProfile.maxHeight} fps=${nextEffectiveProfile.maxFps}`,
      )
    }
    emitNativeDebugLog(
      `webrtc.agent.adaptive.scene_shift session=${sessionId || "-"} from=${previousProfile.id} to=${nextEffectiveProfile.id} scene=${scenePolicy.mode} ewma_fps=${observedSendFps.toFixed(2)} ewma_kbps=${Math.round(observedSendKbps)} ewma_rtt=${Math.round(observedRttMs)}`,
    )
    return { sendFps, sendKbps, bridgeFps, profile: nextEffectiveProfile, decision: "scene_shift", sceneMode: scenePolicy.mode, sceneSwitches: adaptiveState.sceneSwitchCount }
  }
  if (
    adaptiveState.profileIndex > minAllowedProfileIndex
    && degradeSwitchAllowed
    && (
      adaptiveState.severeBadSampleStreak >= severeDegradeStreakRequired
      || adaptiveState.badSampleStreak >= degradeStreakRequired
    )
  ) {
    adaptiveState.profileIndex = clampAdaptiveProfileIndex(adaptiveState.profileIndex - 1)
    adaptiveState.lastSwitchAt = nowMs
    adaptiveState.stableUpgradeSamples = 0
    adaptiveState.badSampleStreak = 0
    adaptiveState.severeBadSampleStreak = 0
    const nextProfile = currentAgentAdaptiveProfile()
    const nextEffectiveProfile = buildEffectiveAdaptiveProfile(nextProfile)
    const sender = findAgentVideoSender(pc)
    if (sender) {
      await tuneVideoSender(sender, {
        profile: nextEffectiveProfile,
        sceneMode: scenePolicy.mode,
        scenePolicy,
        reason: `degrade:${qualityLimitReason}:${observedSendFps.toFixed(1)}fps:${observedBridgeFps.toFixed(1)}capturefps:${Math.round(observedRttMs)}ms:bad=${badSample ? 1 : 0}`,
      })
    } else if (nativeSenderOwnerMode) {
      await applyNativeSenderCaptureConfig(sessionId, { log: false })
      emitNativeDebugLog(
        `webrtc.agent.adaptive.native_sender_apply session=${sessionId || "-"} reason=degrade profile=${nextEffectiveProfile.id} size=${nextEffectiveProfile.maxWidth}x${nextEffectiveProfile.maxHeight} fps=${nextEffectiveProfile.maxFps}`,
      )
    }
    emitNativeDebugLog(
      `webrtc.agent.adaptive.degrade session=${sessionId || "-"} from=${currentProfile.id} to=${nextEffectiveProfile.id} scene=${scenePolicy.mode} ql=${qualityLimitReason} ewma_fps=${observedSendFps.toFixed(2)} ewma_kbps=${Math.round(observedSendKbps)} ewma_rtt=${Math.round(observedRttMs)} bad=${adaptiveState.badSampleStreak} severe=${adaptiveState.severeBadSampleStreak}`,
    )
    return { sendFps, sendKbps, bridgeFps, profile: nextEffectiveProfile, decision: "degrade", sceneMode: scenePolicy.mode, sceneSwitches: adaptiveState.sceneSwitchCount }
  }

  const qualityHealthy = qualityLimitReason === "-" || qualityLimitReason === "none"
  const fpsHealthy = observedSendFps >= currentProfile.maxFps * scenePolicy.fpsHealthyRatio
  const rttHealthy = observedRttMs < 0 || observedRttMs < scenePolicy.rttHealthyMaxMs
  const bitrateHealthy = observedSendKbps >= (currentProfile.maxBitrate / 1000) * adaptiveUpgradeBitrateRatio(currentProfile.id, scenePolicy.mode)
  const bridgeHealthy = observedBridgeFps < 0 || observedBridgeFps >= currentProfile.maxFps * scenePolicy.bridgeHealthyRatio
  const relayConstrained = (relayPath && observedRttMs >= scenePolicy.relayConstrainedRttMs) || tcpPath
  if (qualityHealthy && fpsHealthy && rttHealthy && bitrateHealthy && bridgeHealthy && !relayConstrained) {
    adaptiveState.stableUpgradeSamples += 1
  } else {
    adaptiveState.stableUpgradeSamples = Math.max(0, adaptiveState.stableUpgradeSamples - 1)
  }

  const upgradeSwitchAllowed =
    nowMs - adaptiveState.lastSwitchAt >= AGENT_ADAPTIVE_UPGRADE_SWITCH_COOLDOWN_MS
    && nowMs - adaptiveState.lastSwitchAt >= AGENT_ADAPTIVE_UPGRADE_MIN_DWELL_MS
    && !warmupActive
  if (
    adaptiveState.profileIndex < maxAllowedProfileIndex
    && upgradeSwitchAllowed
    && adaptiveState.stableUpgradeSamples >= scenePolicy.upgradeStableSamples
  ) {
    const stableSamples = adaptiveState.stableUpgradeSamples
    adaptiveState.profileIndex = clampAdaptiveProfileIndex(adaptiveState.profileIndex + 1)
    adaptiveState.lastSwitchAt = nowMs
    adaptiveState.stableUpgradeSamples = 0
    adaptiveState.badSampleStreak = 0
    adaptiveState.severeBadSampleStreak = 0
    const nextProfile = currentAgentAdaptiveProfile()
    const nextEffectiveProfile = buildEffectiveAdaptiveProfile(nextProfile)
    const sender = findAgentVideoSender(pc)
    if (sender) {
      await tuneVideoSender(sender, {
        profile: nextEffectiveProfile,
        sceneMode: scenePolicy.mode,
        scenePolicy,
        reason: `upgrade:${observedSendFps.toFixed(1)}fps:${observedBridgeFps.toFixed(1)}capturefps:${Math.round(observedRttMs)}ms:stable=${stableSamples}`,
      })
    } else if (nativeSenderOwnerMode) {
      await applyNativeSenderCaptureConfig(sessionId, { log: false })
      emitNativeDebugLog(
        `webrtc.agent.adaptive.native_sender_apply session=${sessionId || "-"} reason=upgrade profile=${nextEffectiveProfile.id} size=${nextEffectiveProfile.maxWidth}x${nextEffectiveProfile.maxHeight} fps=${nextEffectiveProfile.maxFps}`,
      )
    }
    emitNativeDebugLog(
      `webrtc.agent.adaptive.upgrade session=${sessionId || "-"} from=${currentProfile.id} to=${nextEffectiveProfile.id} scene=${scenePolicy.mode} ewma_fps=${observedSendFps.toFixed(2)} ewma_kbps=${Math.round(observedSendKbps)} ewma_rtt=${Math.round(observedRttMs)} stable=${stableSamples}`,
    )
    return { sendFps, sendKbps, bridgeFps, profile: nextEffectiveProfile, decision: "upgrade", sceneMode: scenePolicy.mode, sceneSwitches: adaptiveState.sceneSwitchCount }
  }

  return { sendFps, sendKbps, bridgeFps, profile: currentProfile, decision: "hold", sceneMode: scenePolicy.mode, sceneSwitches: adaptiveState.sceneSwitchCount }
}

function stopAgentWebRtcStatsProbe() {
  if (state.agentStatsTimer) {
    window.clearTimeout(state.agentStatsTimer)
    state.agentStatsTimer = null
  }
  state.agentStatsLoopId += 1
  state.agentZeroFramesSince = 0
}

function stopControllerWebRtcStatsProbe() {
  if (state.controllerStatsTimer) {
    window.clearTimeout(state.controllerStatsTimer)
    state.controllerStatsTimer = null
  }
  state.controllerStatsLoopId += 1
}

async function collectAgentOutboundVideoStats(pc) {
  if (!pc || typeof pc.getStats !== "function") {
    return null
  }
  const report = await pc.getStats()
  let outbound = null
  let selectedPair = null
  const reportById = new Map()

  report.forEach((item) => {
    reportById.set(item.id, item)
    if (item.type === "outbound-rtp" && !item.isRemote && (item.kind === "video" || item.mediaType === "video")) {
      outbound = item
    }
  })

  report.forEach((item) => {
    if (item.type !== "candidate-pair") {
      return
    }
    if (item.selected === true || item.nominated === true) {
      selectedPair = item
      return
    }
    if (!selectedPair && item.state === "succeeded" && item.currentRoundTripTime != null) {
      selectedPair = item
    }
  })

  if (!outbound) {
    const nativeStats = collectAgentOutboundVideoStatsFromNativeSender()
    if (nativeStats) {
      return nativeStats
    }
    return null
  }

  let remoteCandidateType = ""
  let localCandidateType = ""
  let localCandidateProtocol = ""
  if (selectedPair?.remoteCandidateId) {
    const remoteCandidate = reportById.get(selectedPair.remoteCandidateId)
    remoteCandidateType = `${remoteCandidate?.candidateType || ""}`
  }
  if (selectedPair?.localCandidateId) {
    const localCandidate = reportById.get(selectedPair.localCandidateId)
    localCandidateType = `${localCandidate?.candidateType || ""}`
    localCandidateProtocol = `${localCandidate?.protocol || ""}`
  }
  const candidatePath = [localCandidateType, remoteCandidateType, localCandidateProtocol]
    .map((value) => `${value || ""}`.trim())
    .filter(Boolean)
    .join("/") || "-"
  const roundTripTimeMs = Number.isFinite(Number(selectedPair?.currentRoundTripTime))
    ? Math.round(Number(selectedPair.currentRoundTripTime) * 1000)
    : -1
  const candidateTier = classifyIceCandidateTier(
    candidatePath,
    roundTripTimeMs,
    { relayUdpHighRttMs: currentSessionRelayUdpHighRttMs() },
  )

  return {
    framesSent: Number(outbound.framesSent ?? outbound.framesEncoded ?? 0),
    framesEncoded: Number(outbound.framesEncoded ?? 0),
    bytesSent: Number(outbound.bytesSent ?? 0),
    packetsSent: Number(outbound.packetsSent ?? 0),
    frameWidth: Number(outbound.frameWidth ?? 0),
    frameHeight: Number(outbound.frameHeight ?? 0),
    qualityLimitationReason: `${outbound.qualityLimitationReason || "-"}`,
    roundTripTimeMs,
    remoteCandidateType: remoteCandidateType || "-",
    candidatePath,
    candidateTier,
  }
}

function collectAgentOutboundVideoStatsFromNativeSender() {
  const status = state.nativeSenderStatus || emptyNativeSenderStatus()
  const lifecycle = `${status.lifecycle || ""}`.trim().toLowerCase()
  const probeFrames = Number(status.media_probe_frame_count || 0)
  const probeBytes = Number(status.media_probe_total_bytes || 0)
  const probeFps = Number(status.media_probe_fps || 0)
  const probeKbps = Number(status.media_probe_kbps || 0)
  const frameWidth = Number(status.media_probe_last_width || 0)
  const frameHeight = Number(status.media_probe_last_height || 0)
  const outboundStatsAvailable = Boolean(status.webrtc_outbound_stats_available)
  const outboundBytes = Number(status.webrtc_outbound_bytes_sent || 0)
  const outboundPackets = Number(status.webrtc_outbound_packets_sent || 0)
  const outboundKbps = Number(status.webrtc_outbound_kbps || 0)
  const outboundFps = Number(status.webrtc_outbound_fps || 0)
  const outboundRttMs = Number(status.webrtc_outbound_rtt_ms || -1)
  const hasOutboundCounters = outboundStatsAvailable && (outboundBytes > 0 || outboundPackets > 0)
  if (lifecycle !== "running" && probeFrames <= 0 && probeBytes <= 0) {
    return null
  }
  if (
    !hasOutboundCounters
    && probeFrames <= 0
    && probeBytes <= 0
    && (!Number.isFinite(probeFps) || probeFps <= 0 || !Number.isFinite(probeKbps) || probeKbps <= 0)
  ) {
    return null
  }
  const remoteCandidateType = `${status.last_remote_candidate_type || "-"}`.trim() || "-"
  const candidatePath = `${status.candidate_path || "-"}`.trim() || "-"
  const candidateTier = `${status.candidate_tier || "-"}`.trim() || "-"
  if (hasOutboundCounters) {
    const fpsBaseline = probeFrames > 0
      ? probeFrames
      : (Number.isFinite(outboundFps) && outboundFps > 0 ? Math.round(outboundFps) : 0)
    return {
      framesSent: Math.max(0, fpsBaseline),
      framesEncoded: Math.max(0, fpsBaseline),
      bytesSent: Math.max(0, outboundBytes),
      packetsSent: Math.max(0, outboundPackets),
      frameWidth: Math.max(0, frameWidth),
      frameHeight: Math.max(0, frameHeight),
      qualityLimitationReason: "-",
      roundTripTimeMs: Number.isFinite(outboundRttMs) && outboundRttMs >= 0 ? Math.round(outboundRttMs) : -1,
      remoteCandidateType,
      candidatePath,
      candidateTier,
      statsSource: "native_sender_outbound_rtp",
      outboundKbps: Number.isFinite(outboundKbps) ? outboundKbps : 0,
      outboundFps: Number.isFinite(outboundFps) ? outboundFps : 0,
    }
  }
  return {
    framesSent: Math.max(0, probeFrames),
    framesEncoded: Math.max(0, probeFrames),
    bytesSent: Math.max(0, probeBytes),
    packetsSent: 0,
    frameWidth: Math.max(0, frameWidth),
    frameHeight: Math.max(0, frameHeight),
    qualityLimitationReason: "-",
    roundTripTimeMs: -1,
    remoteCandidateType,
    candidatePath,
    candidateTier,
    statsSource: "native_sender_probe",
  }
}

async function collectControllerInboundVideoStats(pc) {
  if (!pc || typeof pc.getStats !== "function") {
    return null
  }
  const report = await pc.getStats()
  let inbound = null
  let selectedPair = null
  const reportById = new Map()

  report.forEach((item) => {
    reportById.set(item.id, item)
    if (
      item.type === "inbound-rtp"
      && !item.isRemote
      && (item.kind === "video" || item.mediaType === "video")
    ) {
      inbound = item
    }
  })

  report.forEach((item) => {
    if (item.type !== "candidate-pair") {
      return
    }
    if (item.selected === true || item.nominated === true) {
      selectedPair = item
      return
    }
    if (!selectedPair && item.state === "succeeded" && item.currentRoundTripTime != null) {
      selectedPair = item
    }
  })

  if (!inbound) {
    return null
  }

  let remoteCandidateType = ""
  let localCandidateType = ""
  let localCandidateProtocol = ""
  if (selectedPair?.remoteCandidateId) {
    const remoteCandidate = reportById.get(selectedPair.remoteCandidateId)
    remoteCandidateType = `${remoteCandidate?.candidateType || ""}`
  }
  if (selectedPair?.localCandidateId) {
    const localCandidate = reportById.get(selectedPair.localCandidateId)
    localCandidateType = `${localCandidate?.candidateType || ""}`
    localCandidateProtocol = `${localCandidate?.protocol || ""}`
  }

  const candidatePath = [localCandidateType, remoteCandidateType, localCandidateProtocol]
    .map((value) => `${value || ""}`.trim())
    .filter(Boolean)
    .join("/") || "-"
  const roundTripTimeMs = Number.isFinite(Number(selectedPair?.currentRoundTripTime))
    ? Math.round(Number(selectedPair.currentRoundTripTime) * 1000)
    : -1
  const candidateTier = classifyIceCandidateTier(
    candidatePath,
    roundTripTimeMs,
    { relayUdpHighRttMs: currentSessionRelayUdpHighRttMs() },
  )

  return {
    timestampMs: Date.now(),
    framesDecoded: Number(inbound.framesDecoded ?? 0),
    bytesReceived: Number(inbound.bytesReceived ?? 0),
    decodedFps: Number.isFinite(Number(inbound.framesPerSecond)) ? Number(inbound.framesPerSecond) : -1,
    roundTripTimeMs,
    candidatePath,
    candidateTier,
  }
}

function inferControllerQualityHint(stats) {
  const tier = `${stats?.candidateTier || "-"}`.toLowerCase()
  const rttMs = Number(stats?.roundTripTimeMs)
  const renderFps = Number(stats?.renderFps)
  const recvKbps = Number(stats?.recvKbps)

  if (tier === "relay_tcp" || tier === "p2p_tcp" || tier === "relay_udp_high_rtt") {
    return `path_${stats.candidateTier}`
  }
  if (Number.isFinite(rttMs) && rttMs >= QUALITY_HINT_RTT_HIGH_THRESHOLD_MS) {
    return "rtt_high"
  }
  if (Number.isFinite(renderFps) && renderFps >= 0 && renderFps < QUALITY_HINT_FPS_LOW_THRESHOLD) {
    return "render_fps_low"
  }
  const likelyStall = Number.isFinite(renderFps) && renderFps >= 0 && renderFps < QUALITY_HINT_STALL_FPS_THRESHOLD
  if (likelyStall && Number.isFinite(recvKbps) && recvKbps >= 0 && recvKbps < QUALITY_HINT_BITRATE_LOW_THRESHOLD_KBPS) {
    return "recv_bitrate_low"
  }
  return "stable"
}

function startControllerWebRtcStatsProbe(sessionId, pc) {
  stopControllerWebRtcStatsProbe()
  if (!sessionId || !pc || !isControllerSession()) {
    return
  }

  const loopId = state.controllerStatsLoopId + 1
  state.controllerStatsLoopId = loopId

  const tick = async () => {
    if (
      state.controllerStatsLoopId !== loopId
      || !state.sessionId
      || state.sessionId !== sessionId
      || !isControllerSession()
      || !state.peerConnection
      || state.peerConnection !== pc
    ) {
      return
    }
    try {
      const stats = await collectControllerInboundVideoStats(pc)
      if (stats) {
        const previous = state.controllerStatsSnapshot?.sessionId === sessionId
          ? state.controllerStatsSnapshot
          : emptyControllerStatsSnapshot(sessionId)
        const deltaMs = Number(stats.timestampMs - Number(previous.updatedAt || 0))
        const deltaFrames = Number(stats.framesDecoded - Number(previous.framesDecoded || 0))
        const deltaBytes = Number(stats.bytesReceived - Number(previous.bytesReceived || 0))
        const sampledRenderFps = Number.isFinite(stats.decodedFps) && stats.decodedFps >= 0
          ? stats.decodedFps
          : (deltaMs > 0 && deltaFrames >= 0 ? (deltaFrames * 1000) / deltaMs : -1)
        const sampledRecvKbps = deltaMs > 0 && deltaBytes >= 0
          ? (deltaBytes * 8) / deltaMs
          : -1
        const renderFpsSampleSum = Number(previous.renderFpsSampleSum || 0)
          + (Number.isFinite(sampledRenderFps) && sampledRenderFps >= 0 ? sampledRenderFps : 0)
        const renderFpsSampleCount = Number(previous.renderFpsSampleCount || 0)
          + (Number.isFinite(sampledRenderFps) && sampledRenderFps >= 0 ? 1 : 0)
        const recvKbpsSampleSum = Number(previous.recvKbpsSampleSum || 0)
          + (Number.isFinite(sampledRecvKbps) && sampledRecvKbps >= 0 ? sampledRecvKbps : 0)
        const recvKbpsSampleCount = Number(previous.recvKbpsSampleCount || 0)
          + (Number.isFinite(sampledRecvKbps) && sampledRecvKbps >= 0 ? 1 : 0)
        const rttMsSampleSum = Number(previous.rttMsSampleSum || 0)
          + (Number.isFinite(stats.roundTripTimeMs) && stats.roundTripTimeMs >= 0 ? stats.roundTripTimeMs : 0)
        const rttMsSampleCount = Number(previous.rttMsSampleCount || 0)
          + (Number.isFinite(stats.roundTripTimeMs) && stats.roundTripTimeMs >= 0 ? 1 : 0)
        const nextSnapshot = {
          sessionId,
          updatedAt: stats.timestampMs,
          framesDecoded: stats.framesDecoded,
          bytesReceived: stats.bytesReceived,
          renderFpsSampleSum,
          renderFpsSampleCount,
          recvKbpsSampleSum,
          recvKbpsSampleCount,
          rttMsSampleSum,
          rttMsSampleCount,
          renderFps: sampledRenderFps,
          recvKbps: sampledRecvKbps,
          roundTripTimeMs: stats.roundTripTimeMs,
          candidatePath: stats.candidatePath,
          candidateTier: stats.candidateTier,
          qualityHint: "-",
        }
        nextSnapshot.qualityHint = inferControllerQualityHint(nextSnapshot)
        state.controllerStatsSnapshot = nextSnapshot
        if (!state.firstFrameAt && stats.framesDecoded > 0) {
          state.firstFrameAt = Date.now()
        }
        if (stats.framesDecoded > 0) {
          scheduleLiveE2EProofReport("live_video_frame")
        }
      }
    } catch (error) {
      traceLog("webrtc.controller.inbound_stats.failed", {
        session_id: sessionId,
        reason: error?.message || "unknown",
      }, { console: false })
    }
    render()
    state.controllerStatsTimer = window.setTimeout(() => {
      void tick()
    }, AGENT_WEBRTC_STATS_INTERVAL_MS)
  }

  state.controllerStatsTimer = window.setTimeout(() => {
    void tick()
  }, AGENT_WEBRTC_STATS_INTERVAL_MS)
}

function startAgentWebRtcStatsProbe(sessionId, pc = null) {
  stopAgentWebRtcStatsProbe()
  if (!sessionId || !isAgentSession()) {
    return
  }

  const ownerMode = nativeSenderOwnershipEnabledForSession(sessionId)
  const loopId = state.agentStatsLoopId + 1
  state.agentStatsLoopId = loopId
  let sampleCount = 0

  const tick = async () => {
    if (state.agentStatsLoopId !== loopId || !state.sessionId || state.sessionId !== sessionId || !isAgentSession()) {
      return
    }
    if (!ownerMode) {
      if (!state.peerConnection || state.peerConnection !== pc) {
        return
      }
    }

    const now = Date.now()
    try {
      const stats = ownerMode
        ? collectAgentOutboundVideoStatsFromNativeSender()
        : await collectAgentOutboundVideoStats(pc)
      if (stats) {
        ensureAgentAdaptiveSessionState(sessionId)
        const adaptiveResult = await maybeAdjustAgentAdaptiveProfile(sessionId, pc, stats, now)
        const currentProfile = adaptiveResult.profile || currentAgentAdaptiveProfile()
        state.agentStatsSnapshot = {
          sessionId,
          updatedAt: now,
          framesSent: stats.framesSent,
          framesEncoded: stats.framesEncoded,
          packetsSent: stats.packetsSent,
          bytesSent: stats.bytesSent,
          frameWidth: stats.frameWidth,
          frameHeight: stats.frameHeight,
          roundTripTimeMs: stats.roundTripTimeMs,
          remoteCandidateType: stats.remoteCandidateType,
          candidatePath: stats.candidatePath,
          candidateTier: stats.candidateTier,
          qualityLimitationReason: stats.qualityLimitationReason,
          sendFps: adaptiveResult.sendFps,
          sendKbps: adaptiveResult.sendKbps,
          bridgeFps: adaptiveResult.bridgeFps,
          adaptiveDecision: adaptiveResult.decision || "hold",
          profileId: currentProfile.id,
          profileLabel: currentProfile.label,
          sceneMode: adaptiveResult.sceneMode || state.agentAdaptive?.sceneMode || "mixed",
          sceneSwitches: Number(adaptiveResult.sceneSwitches || state.agentAdaptive?.sceneSwitchCount || 0),
          statsSource: `${stats.statsSource || "webrtc_outbound"}`,
        }
        if (!state.firstFrameAt && stats.framesSent > 0) {
          state.firstFrameAt = now
        }
        if (stats.framesSent > 0) {
          scheduleLiveE2EProofReport("live_agent_sender")
        }
        sampleCount += 1
        if (sampleCount % 4 === 0) {
          traceLog("webrtc.agent.outbound_stats", {
            session_id: sessionId,
            frames_sent: stats.framesSent,
            frames_encoded: stats.framesEncoded,
            packets_sent: stats.packetsSent,
            bytes_sent: stats.bytesSent,
            frame_size: `${stats.frameWidth || 0}x${stats.frameHeight || 0}`,
            rtt_ms: stats.roundTripTimeMs,
            quality_limit: stats.qualityLimitationReason,
            remote_candidate_type: stats.remoteCandidateType,
            candidate_path: stats.candidatePath,
            candidate_tier: stats.candidateTier,
            send_fps: adaptiveResult.sendFps >= 0 ? adaptiveResult.sendFps.toFixed(2) : "-",
            send_kbps: adaptiveResult.sendKbps >= 0 ? Math.round(adaptiveResult.sendKbps) : -1,
            capture_fps: adaptiveResult.bridgeFps >= 0 ? adaptiveResult.bridgeFps.toFixed(2) : "-",
            profile: currentProfile.id,
            profile_label: currentProfile.label,
            profile_max_fps: currentProfile.maxFps,
            profile_max_bitrate: currentProfile.maxBitrate,
            adaptive_decision: adaptiveResult.decision || "hold",
            adaptive_scene: adaptiveResult.sceneMode || state.agentAdaptive?.sceneMode || "mixed",
            adaptive_scene_switches: Number(adaptiveResult.sceneSwitches || state.agentAdaptive?.sceneSwitchCount || 0),
            adaptive_stats_source: `${stats.statsSource || "webrtc_outbound"}`,
          }, { console: false })
        }

        if (!ownerMode) {
          const iceState = `${pc?.iceConnectionState || ""}`.toLowerCase()
          const iceConnected = iceState === "connected" || iceState === "completed"
          if (iceConnected && stats.framesSent <= 0) {
            if (!state.agentZeroFramesSince) {
              state.agentZeroFramesSince = now
            }
            const zeroFramesDuration = now - state.agentZeroFramesSince
            if (
              zeroFramesDuration >= AGENT_WEBRTC_STREAM_ZERO_FRAMES_GRACE_MS
              && isNativeStreamBridgeMode()
            ) {
              const switched = await recoverNativeStreamAndRenegotiate(sessionId, {
                reason: `zero_frames_${zeroFramesDuration}ms`,
              })
              if (switched) {
                return
              }
            }
            const recoveryCoolingDown = now - state.agentLastRecoveryAt < AGENT_WEBRTC_RECOVERY_COOLDOWN_MS
            if (
              zeroFramesDuration >= AGENT_WEBRTC_ZERO_FRAMES_GRACE_MS
              && !recoveryCoolingDown
              && state.agentRecoveryAttempts < AGENT_WEBRTC_MAX_RECOVERY_ATTEMPTS
            ) {
              state.agentRecoveryAttempts += 1
              state.agentLastRecoveryAt = now
              traceLog("webrtc.agent.zero_frames.recover", {
                session_id: sessionId,
                zero_frames_ms: zeroFramesDuration,
                attempt: state.agentRecoveryAttempts,
              })
              appendLog(`检测到受控端出站视频 0 帧，执行第 ${state.agentRecoveryAttempts} 次恢复协商`)
              void beginAgentWebRtcOffer(sessionId)
              return
            }
          } else if (stats.framesSent > 0) {
            state.agentZeroFramesSince = 0
          }
        }
      }
    } catch (error) {
      traceLog("webrtc.agent.outbound_stats.failed", {
        session_id: sessionId,
        reason: error?.message || "unknown",
      }, { console: false })
    }

    render()
    state.agentStatsTimer = window.setTimeout(() => {
      void tick()
    }, AGENT_WEBRTC_STATS_INTERVAL_MS)
  }

  state.agentStatsTimer = window.setTimeout(() => {
    void tick()
  }, AGENT_WEBRTC_STATS_INTERVAL_MS)
}

function clearAgentWebRtcOfferRetry() {
  if (state.agentOfferRetryTimer) {
    window.clearTimeout(state.agentOfferRetryTimer)
    state.agentOfferRetryTimer = null
  }
}

function isCapturePermissionBlocked(reason = "") {
  const permissionStatus = `${state.captureStatus?.permission?.status || ""}`.toLowerCase()
  if (permissionStatus && permissionStatus !== "granted" && permissionStatus !== "unknown") {
    return true
  }
  const normalized = `${reason || state.captureError || ""}`.toLowerCase()
  if (!normalized) {
    return false
  }
  return normalized.includes("screen recording permission")
    || normalized.includes("screen capture requires")
    || normalized.includes("capture permission")
    || normalized.includes("桌面采集权限")
    || normalized.includes("屏幕录制权限")
}

function scheduleAgentWebRtcOfferRetry(sessionId, reason) {
  if (!sessionId || !isAgentSession() || state.sessionId !== sessionId) {
    return
  }
  const normalizedReason = `${reason || ""}`.trim()
  if (normalizedReason) {
    state.captureError = normalizedReason
  }
  if (isCapturePermissionBlocked(normalizedReason)) {
    appendLog(`受控端媒体初始化失败：${state.captureError || "桌面采集权限未就绪"}。请先授予屏幕录制权限后重试`)
    state.webrtcState = "failed"
    render()
    return
  }
  if (state.agentOfferRetryAttempts >= AGENT_WEBRTC_OFFER_RETRY_MAX_ATTEMPTS) {
    appendLog("受控端媒体初始化多次失败，已停止自动重试，请检查桌面采集权限与显示器状态")
    state.webrtcState = "failed"
    render()
    return
  }
  state.agentOfferRetryAttempts += 1
  const attempt = state.agentOfferRetryAttempts
  const delayMs = Math.min(5000, AGENT_WEBRTC_OFFER_RETRY_BASE_DELAY_MS * attempt)
  traceLog("webrtc.agent.offer.retry_scheduled", {
    session_id: sessionId,
    attempt,
    delay_ms: delayMs,
    reason,
  })
  appendLog(`受控端媒体未就绪，${Math.round(delayMs / 1000)} 秒后重试（${attempt}/${AGENT_WEBRTC_OFFER_RETRY_MAX_ATTEMPTS}）`)
  clearAgentWebRtcOfferRetry()
  state.agentOfferRetryTimer = window.setTimeout(() => {
    state.agentOfferRetryTimer = null
    void beginAgentWebRtcOffer(sessionId)
  }, delayMs)
}

function closePeerConnection(silent = false) {
  stopAgentWebRtcStatsProbe()
  stopControllerWebRtcStatsProbe()
  clearAgentWebRtcOfferRetry()
  resetAgentAdaptiveState()
  resetControllerStatsSnapshot("")
  if (state.peerConnection) {
    try {
      state.peerConnection.onicecandidate = null
      state.peerConnection.ontrack = null
      state.peerConnection.onconnectionstatechange = null
      state.peerConnection.oniceconnectionstatechange = null
      state.peerConnection.close()
    } catch {
      // ignore close failures
    }
  }
  state.peerConnection = null
  state.pendingRemoteCandidates = []
  state.remoteMediaStream = null
  state.localMediaStream = null
  state.webrtcState = "idle"
  if (!silent) {
    appendLog("WebRTC 连接已关闭")
  }
}

function stopNativeWebRtcBridge(silent = true) {
  if (state.nativeBridgeTimer) {
    window.clearTimeout(state.nativeBridgeTimer)
    state.nativeBridgeTimer = null
  }
  if (state.nativeBridgeFetchAbortController) {
    try {
      state.nativeBridgeFetchAbortController.abort()
    } catch {
      // ignore abort failures
    }
    state.nativeBridgeFetchAbortController = null
  }
  state.nativeBridgeLoopId += 1
  if (state.nativeBridgeStream) {
    for (const track of state.nativeBridgeStream.getTracks()) {
      track.stop()
    }
  }
  state.nativeBridgeStream = null
  if (state.nativeBridgeVideo) {
    try {
      state.nativeBridgeVideo.pause()
    } catch {
      // ignore video pause failures
    }
    try {
      state.nativeBridgeVideo.src = ""
      state.nativeBridgeVideo.load?.()
    } catch {
      // ignore video reset failures
    }
  }
  state.nativeBridgeVideo = null
  if (state.nativeBridgeImage) {
    try {
      state.nativeBridgeImage.src = ""
    } catch {
      // ignore image reset failures
    }
  }
  state.nativeBridgeImage = null
  state.nativeBridgeEndpoint = ""
  state.nativeBridgeMode = ""
  state.nativeBridgeCanvas = null
  state.nativeBridgeFetchSkipLoggedKey = ""
  if (!silent) {
    appendLog("已停止桌面原生采集桥接轨道")
  }
}

async function decodeBinaryImage(bytes, mimeType = "image/jpeg") {
  const resolvedMimeType = `${mimeType || "image/jpeg"}`.trim().toLowerCase() || "image/jpeg"
  const blob = new Blob([bytes], { type: resolvedMimeType })
  if (typeof createImageBitmap === "function") {
    return createImageBitmap(blob)
  }
  return new Promise((resolve, reject) => {
    const objectUrl = URL.createObjectURL(blob)
    const image = new Image()
    image.onload = () => {
      URL.revokeObjectURL(objectUrl)
      resolve(image)
    }
    image.onerror = () => {
      URL.revokeObjectURL(objectUrl)
      reject(new Error("无法解码二进制屏幕帧"))
    }
    image.src = objectUrl
  })
}

function decodeBase64ToBytes(base64Content) {
  const payload = `${base64Content || ""}`
  if (!payload) {
    return new Uint8Array(0)
  }
  const binary = atob(payload)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index)
  }
  return bytes
}

function renderRawBgraPreviewDataUrl(bytes, width, height, canvas) {
  if (!(bytes instanceof Uint8Array) || bytes.length <= 0) {
    throw new Error("raw-bgra preview bytes missing")
  }
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    throw new Error("raw-bgra preview size invalid")
  }
  const expectedBytes = width * height * 4
  if (bytes.length !== expectedBytes) {
    throw new Error(`raw-bgra preview bytes mismatch: got=${bytes.length} expected=${expectedBytes}`)
  }

  const previewCanvas = canvas || document.createElement("canvas")
  previewCanvas.width = width
  previewCanvas.height = height
  const context = previewCanvas.getContext("2d", { alpha: false })
  if (!context) {
    throw new Error("raw-bgra preview canvas unavailable")
  }
  const rgba = new Uint8ClampedArray(expectedBytes)
  for (let index = 0; index < expectedBytes; index += 4) {
    rgba[index] = bytes[index + 2]
    rgba[index + 1] = bytes[index + 1]
    rgba[index + 2] = bytes[index]
    // Raw BGRA preview frames may carry non-opaque alpha from native buffers.
    // Force preview alpha to opaque to avoid black canvas artifacts.
    rgba[index + 3] = 255
  }
  context.putImageData(new ImageData(rgba, width, height), 0, 0)
  return {
    canvas: previewCanvas,
    dataUrl: previewCanvas.toDataURL("image/jpeg", 0.82),
  }
}

function supportsRawBgraVideoFrame() {
  if (cachedRawBgraVideoFrameSupport !== null) {
    return cachedRawBgraVideoFrameSupport
  }
  if (typeof VideoFrame !== "function") {
    cachedRawBgraVideoFrameSupport = false
    return false
  }
  try {
    const probe = new VideoFrame(new Uint8Array(4), {
      format: "BGRA",
      codedWidth: 1,
      codedHeight: 1,
      timestamp: 0,
    })
    probe.close?.()
    cachedRawBgraVideoFrameSupport = true
  } catch {
    cachedRawBgraVideoFrameSupport = false
  }
  return cachedRawBgraVideoFrameSupport
}

function shouldTryNativeRawBgraBridge() {
  return isTauri() && NATIVE_CAPTURE_RAW_BGRA_ENABLED && supportsRawBgraVideoFrame()
}

function shouldDisableFetchGeneratorBridge(reason) {
  const normalized = `${reason || ""}`.trim().toLowerCase()
  if (!normalized) {
    return false
  }
  if (normalized.includes("load failed")) {
    return true
  }
  return normalized.includes("不支持 mediastreamtrackgenerator")
    || normalized.includes("not support mediastreamtrackgenerator")
    || normalized.includes("mediastreamtrackgenerator/videoframe")
}

function isPermanentFetchGeneratorDisableReason(reason) {
  const normalized = `${reason || ""}`.trim().toLowerCase()
  if (!normalized) {
    return false
  }
  return normalized.includes("不支持 mediastreamtrackgenerator")
    || normalized.includes("not support mediastreamtrackgenerator")
    || normalized.includes("mediastreamtrackgenerator/videoframe")
    || normalized.includes("未发现可用 trackgenerator")
    || normalized.includes("trackgenerator")
}

function disableFetchGeneratorBridge(reason = "unknown") {
  state.nativeBridgeFetchDisabled = true
  state.nativeBridgeFetchDisabledReason = `${reason || "unknown"}`
  state.nativeBridgeFetchDisabledAt = Date.now()
  traceLog("native.bridge.stream.fetch_disabled", {
    session_id: state.sessionId || "-",
    reason: state.nativeBridgeFetchDisabledReason,
    permanent: isPermanentFetchGeneratorDisableReason(reason),
  }, { console: false })
}

function maybeReenableFetchGeneratorBridge(trigger = "runtime_recheck", options = {}) {
  if (!state.nativeBridgeFetchDisabled) {
    return false
  }
  const force = options.force === true
  const disabledReason = `${state.nativeBridgeFetchDisabledReason || ""}`.trim() || "unknown"
  const disabledAt = Number(state.nativeBridgeFetchDisabledAt || 0)
  const elapsedMs = disabledAt > 0 ? Math.max(0, Date.now() - disabledAt) : Number.POSITIVE_INFINITY
  const permanentDisabled = isPermanentFetchGeneratorDisableReason(disabledReason)
  if (!force && permanentDisabled && !supportsMultipartTrackGeneratorBridge()) {
    return false
  }
  if (!force && !permanentDisabled && elapsedMs < NATIVE_BRIDGE_FETCH_REENABLE_COOLDOWN_MS) {
    return false
  }
  state.nativeBridgeFetchDisabled = false
  state.nativeBridgeFetchDisabledReason = ""
  state.nativeBridgeFetchDisabledAt = 0
  state.nativeBridgeFetchSkipLoggedKey = ""
  traceLog("native.bridge.stream.fetch_reenabled", {
    session_id: state.sessionId || "-",
    trigger,
    force: force ? 1 : 0,
    previous_reason: disabledReason,
    elapsed_ms: Number.isFinite(elapsedMs) ? elapsedMs : -1,
  }, { console: false })
  return true
}

function resolveTrackGeneratorSupport() {
  const root = typeof globalThis !== "undefined"
    ? globalThis
    : (typeof window !== "undefined" ? window : {})
  const candidates = [
    ["MediaStreamTrackGenerator", typeof MediaStreamTrackGenerator === "function" ? MediaStreamTrackGenerator : null],
    ["VideoTrackGenerator", typeof root?.VideoTrackGenerator === "function" ? root.VideoTrackGenerator : null],
    ["webkitMediaStreamTrackGenerator", typeof root?.webkitMediaStreamTrackGenerator === "function" ? root.webkitMediaStreamTrackGenerator : null],
  ]
  for (const [name, ctor] of candidates) {
    if (typeof ctor === "function") {
      return { available: true, name, ctor }
    }
  }
  return { available: false, name: "", ctor: null }
}

function supportsMultipartTrackGeneratorBridge() {
  if (typeof fetch !== "function") {
    return false
  }
  if (typeof MediaStream !== "function") {
    return false
  }
  const trackGeneratorSupport = resolveTrackGeneratorSupport()
  if (!trackGeneratorSupport.available || typeof VideoFrame !== "function") {
    return false
  }
  return true
}

function logNativeBridgeFetchSkipped(reason, streamUrl) {
  const normalizedReason = `${reason || ""}`.trim() || "unknown"
  recordBridgeFetchSkipReason(normalizedReason, { sessionId: state.sessionId || "" })
  const sessionId = `${state.sessionId || "-"}`.trim()
  const logKey = `${sessionId}|${normalizedReason}`
  if (state.nativeBridgeFetchSkipLoggedKey === logKey) {
    return
  }
  state.nativeBridgeFetchSkipLoggedKey = logKey
  traceLog("native.bridge.stream.fetch_skipped", {
    session_id: sessionId,
    reason: normalizedReason,
    stream_url: streamUrl,
  }, { console: false })
}

function resetCanvasFallbackDecisionForSession(sessionId = "") {
  const normalizedSessionId = `${sessionId || ""}`.trim()
  if (
    !state.nativeBridgeCanvasFallbackDecisionSessionId
    || state.nativeBridgeCanvasFallbackDecisionSessionId !== normalizedSessionId
  ) {
    state.nativeBridgeCanvasFallbackDecisionSessionId = ""
    state.nativeBridgeCanvasFallbackAllowed = false
  }
}

function canvasFallbackDecisionForSession(sessionId = state.sessionId) {
  const normalizedSessionId = `${sessionId || ""}`.trim()
  if (!normalizedSessionId) {
    return null
  }
  if (state.nativeBridgeCanvasFallbackDecisionSessionId !== normalizedSessionId) {
    return null
  }
  return state.nativeBridgeCanvasFallbackAllowed === true
}

async function confirmNativeCanvasFallback(reason, streamUrl) {
  const normalizedSessionId = `${state.sessionId || ""}`.trim()
  if (!NATIVE_BRIDGE_REQUIRE_EXPLICIT_CANVAS_FALLBACK_CONFIRM) {
    return true
  }
  if (!normalizedSessionId) {
    return false
  }
  const existingDecision = canvasFallbackDecisionForSession(normalizedSessionId)
  if (existingDecision !== null) {
    return existingDecision
  }

  const detail = `${reason || "unknown"}`.trim() || "unknown"
  const promptText = [
    "原生无画布链路不可用。",
    `原因: ${detail}`,
    "",
    "是否允许回退到 Canvas 兼容模式（仅当前会话）？",
  ].join("\n")
  const allowFallback = typeof window.confirm === "function"
    ? window.confirm(promptText)
    : false
  state.nativeBridgeCanvasFallbackDecisionSessionId = normalizedSessionId
  state.nativeBridgeCanvasFallbackAllowed = allowFallback
  traceLog("native.bridge.canvas_fallback.confirmed", {
    session_id: normalizedSessionId,
    allowed: allowFallback ? 1 : 0,
    reason: detail,
    stream_url: streamUrl,
  })
  appendLog(allowFallback
    ? `已确认本会话允许 Canvas 回退：${detail}`
    : `已拒绝本会话 Canvas 回退：${detail}`)
  return allowFallback
}

async function updateNativeCaptureCodec(codec, reason = "runtime_switch") {
  if (!isTauri()) {
    return false
  }
  const nextCodec = `${codec || ""}`.trim()
  if (!nextCodec) {
    return false
  }
  const currentCodec = `${state.captureStatus?.config?.codec || ""}`.trim().toLowerCase()
  if (currentCodec === nextCodec.toLowerCase()) {
    return true
  }
  try {
    syncCaptureStatus(await invoke("capture_update_config", {
      patch: { codec: nextCodec },
    }))
    traceLog("native.capture.codec.updated", {
      session_id: state.sessionId || "-",
      codec: nextCodec,
      reason,
    }, { console: false })
    return true
  } catch (error) {
    traceLog("native.capture.codec.update_failed", {
      session_id: state.sessionId || "-",
      codec: nextCodec,
      reason,
      error: error?.message || "unknown",
    })
    return false
  }
}

async function createVideoFrameFromMultipartPayload(framePayload = {}) {
  const mimeType = `${framePayload?.mimeType || "image/jpeg"}`.trim().toLowerCase() || "image/jpeg"
  const captureTs = Number(framePayload?.captureTs)
  const timestampUs = Number.isFinite(captureTs) && captureTs > 0
    ? Math.round(captureTs * 1000)
    : Math.round(performance.now() * 1000)

  if (mimeType === NATIVE_CAPTURE_RAW_BGRA_MIME) {
    const width = Number(framePayload?.frameWidth || 0)
    const height = Number(framePayload?.frameHeight || 0)
    const bytes = framePayload?.bytes
    if (!(bytes instanceof Uint8Array) || bytes.length <= 0) {
      throw new Error("原生 raw-bgra 帧数据为空")
    }
    if (width <= 0 || height <= 0) {
      throw new Error("原生 raw-bgra 帧缺少有效宽高")
    }
    const expectedBytes = width * height * 4
    if (bytes.length !== expectedBytes) {
      throw new Error(`原生 raw-bgra 帧长度异常: got=${bytes.length} expected=${expectedBytes}`)
    }
    const videoFrame = new VideoFrame(bytes, {
      format: "BGRA",
      codedWidth: width,
      codedHeight: height,
      timestamp: timestampUs,
    })
    return {
      frame: videoFrame,
      width,
      height,
      source: "raw_bgra",
    }
  }

  let image = null
  try {
    image = await decodeBinaryImage(framePayload?.bytes, mimeType)
    const width = Number(image?.width || image?.videoWidth || 0)
    const height = Number(image?.height || image?.videoHeight || 0)
    if (width <= 0 || height <= 0) {
      throw new Error("连续流帧尺寸无效")
    }
    const videoFrame = new VideoFrame(image, {
      timestamp: timestampUs,
    })
    return {
      frame: videoFrame,
      width,
      height,
      source: "encoded_image",
    }
  } finally {
    closeDecodedImage(image)
  }
}

function createMultipartTrackGeneratorArtifacts() {
  const support = resolveTrackGeneratorSupport()
  if (!support.available || !support.ctor) {
    return null
  }

  if (support.name === "MediaStreamTrackGenerator" || support.name === "webkitMediaStreamTrackGenerator") {
    const generatorTrack = new support.ctor({ kind: "video" })
    const writer = generatorTrack?.writable?.getWriter?.()
    if (!writer) {
      throw new Error(`无法创建 ${support.name} writer`)
    }
    const stream = new MediaStream([generatorTrack])
    const videoTrack = stream.getVideoTracks?.()[0] || null
    if (!videoTrack) {
      throw new Error(`${support.name} 未生成视频轨道`)
    }
    return {
      mode: support.name,
      stream,
      videoTrack,
      writer,
      closeWriter: async () => writer.close(),
    }
  }

  if (support.name === "VideoTrackGenerator") {
    const generator = new support.ctor()
    const writer = generator?.writable?.getWriter?.()
    const track = generator?.track || generator
    if (!writer) {
      throw new Error("无法创建 VideoTrackGenerator writer")
    }
    if (!track || track.kind !== "video") {
      throw new Error("VideoTrackGenerator 未生成视频轨道")
    }
    const stream = new MediaStream([track])
    const videoTrack = stream.getVideoTracks?.()[0] || null
    if (!videoTrack) {
      throw new Error("VideoTrackGenerator 未生成可用视频轨道")
    }
    return {
      mode: support.name,
      stream,
      videoTrack,
      writer,
      closeWriter: async () => {
        await writer.close()
        try {
          generator.stop?.()
        } catch {
          // ignore generator stop failures
        }
      },
    }
  }

  return null
}

function closeDecodedImage(image) {
  if (!image || typeof image.close !== "function") {
    return
  }
  try {
    image.close()
  } catch {
    // ignore decoded image close failures
  }
}

function concatByteArrays(left, right) {
  if (!left || left.length === 0) {
    return right || new Uint8Array(0)
  }
  if (!right || right.length === 0) {
    return left
  }
  const merged = new Uint8Array(left.length + right.length)
  merged.set(left, 0)
  merged.set(right, left.length)
  return merged
}

function findByteSubarray(haystack, needle, start = 0) {
  if (!haystack || !needle || needle.length === 0 || haystack.length < needle.length) {
    return -1
  }
  const startIndex = Math.max(0, Number(start) || 0)
  const maxIndex = haystack.length - needle.length
  for (let i = startIndex; i <= maxIndex; i += 1) {
    let matched = true
    for (let j = 0; j < needle.length; j += 1) {
      if (haystack[i + j] !== needle[j]) {
        matched = false
        break
      }
    }
    if (matched) {
      return i
    }
  }
  return -1
}

function parseMultipartHeaders(rawHeaders) {
  const headers = {}
  for (const line of `${rawHeaders || ""}`.split("\r\n")) {
    const separator = line.indexOf(":")
    if (separator <= 0) {
      continue
    }
    const key = line.slice(0, separator).trim().toLowerCase()
    const value = line.slice(separator + 1).trim()
    if (key) {
      headers[key] = value
    }
  }
  return headers
}

function parseNextMjpegFrame(buffer, boundaryBytes, textDecoder) {
  const crlf = new Uint8Array([13, 10])
  const headerEndMarker = new Uint8Array([13, 10, 13, 10])
  const boundaryMarkerLength = boundaryBytes.length
  const boundaryIndex = findByteSubarray(buffer, boundaryBytes)
  if (boundaryIndex < 0) {
    const tailKeep = Math.max(0, boundaryMarkerLength - 1)
    const trimmed = buffer.length > tailKeep ? buffer.slice(buffer.length - tailKeep) : buffer
    return { status: "need_more", buffer: trimmed }
  }

  const candidate = boundaryIndex > 0 ? buffer.slice(boundaryIndex) : buffer
  if (candidate.length < boundaryMarkerLength + 2) {
    return { status: "need_more", buffer: candidate }
  }

  const lineEndIndex = findByteSubarray(candidate, crlf, boundaryMarkerLength)
  if (lineEndIndex < 0) {
    return { status: "need_more", buffer: candidate }
  }

  const headerStart = lineEndIndex + 2
  const headerEnd = findByteSubarray(candidate, headerEndMarker, headerStart)
  if (headerEnd < 0) {
    return { status: "need_more", buffer: candidate }
  }

  const rawHeaderBytes = candidate.slice(headerStart, headerEnd)
  const headerText = textDecoder.decode(rawHeaderBytes)
  const headers = parseMultipartHeaders(headerText)
  const contentLength = Number.parseInt(headers["content-length"] || "", 10)
  if (!Number.isFinite(contentLength) || contentLength <= 0) {
    return {
      status: "error",
      buffer: new Uint8Array(0),
      error: `连续流帧头缺少有效 Content-Length: ${headers["content-length"] || "-"}`,
    }
  }

  const bodyStart = headerEnd + 4
  const bodyEnd = bodyStart + contentLength
  if (candidate.length < bodyEnd) {
    return { status: "need_more", buffer: candidate }
  }
  const frameBytes = candidate.slice(bodyStart, bodyEnd)
  const frameWidth = Number.parseInt(headers["x-frame-width"] || "", 10)
  const frameHeight = Number.parseInt(headers["x-frame-height"] || "", 10)
  const captureTs = Number.parseInt(headers["x-capture-ts"] || "", 10)
  const frameFormat = `${headers["x-frame-format"] || ""}`.trim()
  let nextOffset = bodyEnd
  if (candidate.length >= nextOffset + 2 && candidate[nextOffset] === 13 && candidate[nextOffset + 1] === 10) {
    nextOffset += 2
  }
  const nextBuffer = candidate.slice(nextOffset)
  return {
    status: "frame",
    frame: {
      bytes: frameBytes,
      mimeType: headers["content-type"] || "image/jpeg",
      frameWidth: Number.isFinite(frameWidth) && frameWidth > 0 ? frameWidth : 0,
      frameHeight: Number.isFinite(frameHeight) && frameHeight > 0 ? frameHeight : 0,
      captureTs: Number.isFinite(captureTs) && captureTs > 0 ? captureTs : 0,
      frameFormat,
    },
    buffer: nextBuffer,
  }
}

async function ensureNativeFallbackCaptureConfig() {
  if (!isTauri()) {
    return
  }
  const profile = currentEffectiveAgentAdaptiveProfile()
  try {
    syncCaptureStatus(await invoke("capture_update_config", {
      patch: {
        maxWidth: profile.maxWidth,
        maxHeight: profile.maxHeight,
        maxFps: Math.max(1, Math.min(NATIVE_CAPTURE_FALLBACK_MAX_FPS, profile.maxFps)),
        codec: NATIVE_CAPTURE_FALLBACK_CODEC,
      },
    }))
  } catch (error) {
    appendLog(`更新原生采集配置失败: ${error?.message || "unknown error"}`)
  }
}

async function startNativeWebRtcBridgeViaMediaStream() {
  await ensureNativeFallbackCaptureConfig()
  const ready = await requestNativeCaptureSource({ log: false })
  if (!ready) {
    return null
  }
  maybeReenableFetchGeneratorBridge("bridge_start")
  if (isNativeStreamBridgeMode(state.nativeBridgeMode) && streamHasLiveVideoTrack(state.nativeBridgeStream)) {
    traceLog("native.bridge.stream.reused", {
      session_id: state.sessionId || "-",
      mode: state.nativeBridgeMode,
      endpoint: state.nativeBridgeEndpoint || "-",
    }, { console: false })
    return state.nativeBridgeStream
  }
  stopNativeWebRtcBridge(true)
  const endpoint = await invoke("capture_get_stream_endpoint")
  const streamUrl = typeof endpoint?.url === "string" ? endpoint.url.trim() : ""
  const streamBoundary = typeof endpoint?.boundary === "string" ? endpoint.boundary.trim() : ""
  if (!streamUrl) {
    throw new Error("capture_get_stream_endpoint 返回空地址")
  }
  const fps = Math.max(1, Number(state.captureStatus?.config?.max_fps) || NATIVE_CAPTURE_FALLBACK_MAX_FPS)
  let canvasFallbackReason = "native_no_canvas_path_unavailable"
  try {
    const stream = await startNativeWebRtcBridgeViaMjpegVideo(streamUrl, fps, {
      readyTimeoutMs: NATIVE_STREAM_DIRECT_FAST_READY_TIMEOUT_MS,
      firstFrameTimeoutMs: NATIVE_STREAM_DIRECT_FAST_FIRST_FRAME_TIMEOUT_MS,
      allowCanvasFallback: false,
    })
    if (stream) {
      return stream
    }
  } catch (error) {
    canvasFallbackReason = error?.message || canvasFallbackReason
    traceLog("native.bridge.stream.direct_unavailable", {
      session_id: state.sessionId || "-",
      reason: error?.message || "unknown",
      stream_url: streamUrl,
    }, { console: false })
  }

  let fetchPathShortCircuited = false
  if (!state.nativeBridgeFetchDisabled) {
    if (!supportsMultipartTrackGeneratorBridge()) {
      disableFetchGeneratorBridge("unsupported_track_generator")
      fetchPathShortCircuited = true
      logNativeBridgeFetchSkipped("unsupported_track_generator", streamUrl)
    }
  }

  if (!state.nativeBridgeFetchDisabled) {
    let usingRawBgra = false
    let fetchFailureReason = ""
    let jpegRetryReason = ""
    let jpegRetryAttempted = false
    let jpegRetrySucceeded = false
    if (shouldTryNativeRawBgraBridge()) {
      usingRawBgra = await updateNativeCaptureCodec(
        NATIVE_CAPTURE_RAW_BGRA_CODEC,
        "mjpeg_stream_fetch_generator",
      )
      if (usingRawBgra) {
        recordBridgePipelineStats(
          { raw_attempts: 1 },
          { sessionId: state.sessionId },
        )
      }
    }
    try {
      const stream = await startNativeWebRtcBridgeViaMultipartTrackGenerator(streamUrl, streamBoundary, fps)
      if (stream) {
        if (usingRawBgra) {
          recordBridgePipelineStats(
            { raw_success: 1 },
            { sessionId: state.sessionId },
          )
        }
        return stream
      }
    } catch (error) {
      fetchFailureReason = error?.message || "unknown"
      if (usingRawBgra) {
        recordBridgePipelineStats(
          { raw_failed: 1 },
          { sessionId: state.sessionId },
        )
      }
      if (usingRawBgra) {
        await updateNativeCaptureCodec(
          NATIVE_CAPTURE_FALLBACK_CODEC,
          "mjpeg_stream_fetch_generator_failed_revert",
        )
        if (!shouldDisableFetchGeneratorBridge(fetchFailureReason)) {
          jpegRetryAttempted = true
          recordBridgePipelineStats(
            { jpeg_retry_attempted: 1 },
            { sessionId: state.sessionId },
          )
          try {
            const jpegRetryStream = await startNativeWebRtcBridgeViaMultipartTrackGenerator(
              streamUrl,
              streamBoundary,
              fps,
            )
            if (jpegRetryStream) {
              jpegRetrySucceeded = true
              recordBridgePipelineStats(
                { jpeg_retry_success: 1 },
                { sessionId: state.sessionId },
              )
              traceLog("native.bridge.stream.fetch_retry_jpeg_ok", {
                session_id: state.sessionId || "-",
                reason: fetchFailureReason,
                stream_url: streamUrl,
              })
              appendLog("raw-bgra 连续流失败，已自动回退到 JPEG 无画布桥接")
              return jpegRetryStream
            }
          } catch (jpegError) {
            jpegRetryReason = jpegError?.message || "unknown"
            recordBridgePipelineStats(
              { jpeg_retry_failed: 1 },
              { sessionId: state.sessionId },
            )
          }
        }
      }
      if (shouldDisableFetchGeneratorBridge(fetchFailureReason) || shouldDisableFetchGeneratorBridge(jpegRetryReason)) {
        disableFetchGeneratorBridge(jpegRetryReason || fetchFailureReason || "fetch_generator_failed")
      }
      traceLog("native.bridge.stream.fetch_unavailable", {
        session_id: state.sessionId || "-",
        reason: fetchFailureReason || "unknown",
        jpeg_retry_reason: jpegRetryReason || "-",
        jpeg_retry_attempted: jpegRetryAttempted,
        jpeg_retry_succeeded: jpegRetrySucceeded,
        stream_url: streamUrl,
        fetch_disabled: state.nativeBridgeFetchDisabled,
        raw_bgra: usingRawBgra,
      })
      const finalReason = jpegRetryReason || fetchFailureReason || "unknown"
      canvasFallbackReason = finalReason
      appendLog(`原生连续流无画布桥接不可用，尝试二次直连: ${finalReason}`)
    }
  } else {
    if (!fetchPathShortCircuited) {
      logNativeBridgeFetchSkipped("previous_load_failed", streamUrl)
    }
  }

  try {
    const stream = await startNativeWebRtcBridgeViaMjpegVideo(streamUrl, fps, {
      readyTimeoutMs: NATIVE_STREAM_DIRECT_RETRY_READY_TIMEOUT_MS,
      firstFrameTimeoutMs: NATIVE_STREAM_DIRECT_RETRY_FIRST_FRAME_TIMEOUT_MS,
      allowCanvasFallback: false,
    })
    if (stream) {
      return stream
    }
  } catch (error) {
    canvasFallbackReason = error?.message || canvasFallbackReason
    traceLog("native.bridge.stream.direct_retry_unavailable", {
      session_id: state.sessionId || "-",
      reason: error?.message || "unknown",
      stream_url: streamUrl,
    }, { console: false })
    appendLog(`原生连续流二次直连不可用，尝试 image 桥接: ${error?.message || "unknown error"}`)
  }

  const canvasFallbackAllowed = await confirmNativeCanvasFallback(canvasFallbackReason, streamUrl)
  if (!canvasFallbackAllowed) {
    traceLog("native.bridge.canvas_fallback.blocked", {
      session_id: state.sessionId || "-",
      reason: canvasFallbackReason,
      stream_url: streamUrl,
    })
    throw new Error(`用户拒绝 Canvas 回退: ${canvasFallbackReason}`)
  }

  try {
    const stream = await startNativeWebRtcBridgeViaMjpegImageCanvas(streamUrl, fps)
    if (stream) {
      return stream
    }
  } catch (error) {
    traceLog("native.bridge.stream.image_unavailable", {
      session_id: state.sessionId || "-",
      reason: error?.message || "unknown",
      stream_url: streamUrl,
    })
    appendLog(`原生连续流 image 桥接不可用，尝试 video 桥接: ${error?.message || "unknown error"}`)
  }

  return startNativeWebRtcBridgeViaMjpegVideo(streamUrl, fps, {
    readyTimeoutMs: 9000,
    firstFrameTimeoutMs: 2500,
    allowCanvasFallback: true,
  })
}

async function startNativeWebRtcBridgeViaMjpegVideo(streamUrl, fps, options = {}) {
  const readyTimeoutMs = Math.max(800, Number(options?.readyTimeoutMs) || 9000)
  const firstFrameTimeoutMs = Math.max(600, Number(options?.firstFrameTimeoutMs) || 2500)
  const allowCanvasFallback = options?.allowCanvasFallback !== false

  const video = document.createElement("video")
  video.autoplay = true
  video.muted = true
  video.playsInline = true
  video.crossOrigin = "anonymous"
  video.src = streamUrl

  const resetVideo = () => {
    try {
      video.pause()
    } catch {
      // ignore video pause failures
    }
    try {
      video.src = ""
      video.load?.()
    } catch {
      // ignore video reset failures
    }
  }

  try {
    await video.play().catch(() => {})
    await waitForStreamVideoReady(video, { timeoutMs: readyTimeoutMs })
    await waitForStreamVideoFirstFrame(video, { timeoutMs: firstFrameTimeoutMs })
    const captureStreamFactory = typeof video.captureStream === "function"
      ? video.captureStream.bind(video)
      : (typeof video.webkitCaptureStream === "function" ? video.webkitCaptureStream.bind(video) : null)
    if (captureStreamFactory) {
      const stream = captureStreamFactory(fps)
      const videoTrack = stream.getVideoTracks?.()[0] || null
      if (videoTrack) {
        state.nativeBridgeVideo = video
        state.nativeBridgeStream = stream
        state.nativeBridgeEndpoint = streamUrl
        state.nativeBridgeMode = "mjpeg_stream_direct"
        videoTrack.onmute = () => traceLog("native.bridge.stream.track_muted", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })
        videoTrack.onunmute = () => traceLog("native.bridge.stream.track_unmuted", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })
        videoTrack.onended = () => traceLog("native.bridge.stream.track_ended", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })
        traceLog("native.bridge.stream.started", {
          session_id: state.sessionId || "-",
          stream_url: streamUrl,
          fps_target: fps,
          track_id: videoTrack.id || "-",
          mode: "direct",
        })
        recordBridgeModeUsage("mjpeg_stream_direct", { sessionId: state.sessionId })
        appendLog("已启动桌面原生连续流桥接轨道")
        return stream
      }
      traceLog("native.bridge.stream.no_track", {
        session_id: state.sessionId || "-",
        stream_url: streamUrl,
      }, { console: false })
    }

    if (!allowCanvasFallback) {
      throw new Error("当前环境不支持 video.captureStream")
    }
    return startNativeWebRtcBridgeViaMjpegCanvas(video, streamUrl, fps)
  } catch (error) {
    traceLog("native.bridge.stream.wait_pending", {
      session_id: state.sessionId || "-",
      reason: error?.message || "unknown",
      stream_url: streamUrl,
      allow_canvas_fallback: allowCanvasFallback,
    }, { console: false })
    resetVideo()
    throw error
  }
}

async function startNativeWebRtcBridgeViaMultipartTrackGenerator(streamUrl, boundary, fps) {
  if (typeof fetch !== "function") {
    throw new Error("当前环境不支持 fetch 连续流")
  }
  const trackGeneratorSupport = resolveTrackGeneratorSupport()
  if (!trackGeneratorSupport.available || typeof VideoFrame !== "function" || typeof MediaStream !== "function") {
    throw new Error("当前环境不支持 MediaStreamTrackGenerator/VideoFrame")
  }
  const resolvedBoundary = `${boundary || ""}`.trim() || "rdframe"
  const boundaryBytes = new TextEncoder().encode(`--${resolvedBoundary}`)
  const textDecoder = new TextDecoder()
  const abortController = new AbortController()

  const response = await fetch(streamUrl, {
    method: "GET",
    cache: "no-store",
    headers: {
      Accept: "multipart/x-mixed-replace",
    },
    signal: abortController.signal,
  })
  if (!response.ok) {
    throw new Error(`连续流请求失败: HTTP ${response.status}`)
  }
  const reader = response.body?.getReader?.()
  if (!reader) {
    throw new Error("连续流响应体不可读")
  }

  const artifacts = createMultipartTrackGeneratorArtifacts()
  if (!artifacts) {
    throw new Error("当前环境未发现可用 TrackGenerator 实现")
  }
  const {
    mode: generatorMode,
    stream,
    videoTrack,
    closeWriter: closeWriterArtifact,
  } = artifacts
  const writer = artifacts.writer

  const loopId = state.nativeBridgeLoopId + 1
  state.nativeBridgeLoopId = loopId
  state.nativeBridgeStream = stream
  state.nativeBridgeEndpoint = streamUrl
  state.nativeBridgeMode = "mjpeg_stream_fetch_generator"
  state.nativeBridgeFetchAbortController = abortController

  videoTrack.onmute = () => traceLog("native.bridge.stream_generator.track_muted", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })
  videoTrack.onunmute = () => traceLog("native.bridge.stream_generator.track_unmuted", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })
  videoTrack.onended = () => traceLog("native.bridge.stream_generator.track_ended", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })

  let firstFrameSeen = false
  let firstFrameSettled = false
  let frameCount = 0
  let consecutiveErrors = 0
  let bufferedBytes = new Uint8Array(0)
  const isBridgeActive = () => (
    state.nativeBridgeLoopId === loopId
    && Boolean(state.sessionId)
    && isAgentSession()
  )
  let firstFrameTimer = 0
  let resolveFirstFrame = null
  let rejectFirstFrame = null
  const firstFramePromise = new Promise((resolve, reject) => {
    resolveFirstFrame = resolve
    rejectFirstFrame = reject
    firstFrameTimer = window.setTimeout(() => {
      if (firstFrameSettled) {
        return
      }
      firstFrameSettled = true
      reject(new Error("原生连续流无画布桥接首帧超时"))
    }, 3500)
  })

  let writerClosed = false
  const closeWriter = async () => {
    if (writerClosed) {
      return
    }
    writerClosed = true
    try {
      await closeWriterArtifact()
    } catch {
      // ignore writer close failures
    }
  }

  const pump = async () => {
    while (isBridgeActive()) {
      const { value, done } = await reader.read()
      if (done) {
        throw new Error("原生连续流连接已结束")
      }
      if (!value?.length) {
        continue
      }
      bufferedBytes = concatByteArrays(bufferedBytes, value)
      while (isBridgeActive()) {
        const parsed = parseNextMjpegFrame(bufferedBytes, boundaryBytes, textDecoder)
        bufferedBytes = parsed.buffer
        if (parsed.status === "need_more") {
          break
        }
        if (parsed.status === "error") {
          throw new Error(parsed.error || "原生连续流帧解析失败")
        }

        let frame = null
        try {
          const decoded = await createVideoFrameFromMultipartPayload(parsed.frame)
          const frameWidth = decoded.width
          const frameHeight = decoded.height
          frame = decoded.frame
          await writer.write(frame)
          frameCount += 1
          consecutiveErrors = 0
          if (state.agentAdaptive) {
            state.agentAdaptive.bridgeProducedFrames += 1
          }
          state.localFrameMeta = {
            frameId: `native-stream-generator-${Date.now()}`,
            captureTs: Date.now(),
            width: frameWidth,
            height: frameHeight,
          }
          if (!firstFrameSeen) {
            firstFrameSeen = true
            traceLog("native.bridge.stream_generator.first_frame", {
              session_id: state.sessionId || "-",
              frame_size: `${frameWidth}x${frameHeight}`,
              stream_url: streamUrl,
              frame_source: decoded.source,
              frame_format: parsed.frame?.frameFormat || "-",
            })
            if (!firstFrameSettled && typeof resolveFirstFrame === "function") {
              firstFrameSettled = true
              window.clearTimeout(firstFrameTimer)
              resolveFirstFrame()
            }
          }
        } catch (error) {
          consecutiveErrors += 1
          traceLog("native.bridge.stream_generator.frame_failed", {
            session_id: state.sessionId || "-",
            reason: error?.message || "unknown",
            consecutive_errors: consecutiveErrors,
          }, { console: false })
          if (consecutiveErrors >= NATIVE_BRIDGE_MAX_CONSECUTIVE_ERRORS) {
            throw new Error(error?.message || "原生连续流无画布桥接解帧失败")
          }
        } finally {
          try {
            frame?.close?.()
          } catch {
            // ignore frame close failures
          }
        }
      }
    }
  }

  void pump()
    .catch((error) => {
      if (!firstFrameSettled && typeof rejectFirstFrame === "function") {
        firstFrameSettled = true
        window.clearTimeout(firstFrameTimer)
        rejectFirstFrame(error)
      }
      if (isBridgeActive()) {
        traceLog("native.bridge.stream_generator.stopped", {
          session_id: state.sessionId || "-",
          reason: error?.message || "unknown",
          frames: frameCount,
          first_frame_seen: firstFrameSeen,
        })
        appendLog(`原生连续流无画布桥接中断: ${error?.message || "unknown error"}`)
        state.webrtcState = "failed"
        render()
      }
    })
    .finally(() => {
      window.clearTimeout(firstFrameTimer)
      try {
        reader.cancel()
      } catch {
        // ignore reader cancel failures
      }
      void closeWriter()
      if (state.nativeBridgeFetchAbortController === abortController) {
        state.nativeBridgeFetchAbortController = null
      }
    })

  try {
    await firstFramePromise
  } catch (error) {
    if (state.nativeBridgeLoopId === loopId) {
      stopNativeWebRtcBridge(true)
    }
    throw error
  }

  traceLog("native.bridge.stream.started", {
    session_id: state.sessionId || "-",
    stream_url: streamUrl,
    fps_target: fps,
    track_id: videoTrack.id || "-",
    mode: "fetch_generator",
    generator_mode: generatorMode,
    boundary: resolvedBoundary,
  })
  recordBridgeModeUsage("mjpeg_stream_fetch_generator", { sessionId: state.sessionId })
  appendLog("已启动桌面原生连续流无画布桥接轨道")
  return stream
}

async function startNativeWebRtcBridgeViaMjpegImageCanvas(streamUrl, fps) {
  const image = new Image()
  image.decoding = "async"
  image.crossOrigin = "anonymous"
  image.src = streamUrl
  await waitForStreamImageReady(image, { timeoutMs: 5000 })

  const canvas = document.createElement("canvas")
  const context = canvas.getContext("2d", { alpha: false })
  if (!context) {
    throw new Error("创建连续流图片桥接画布失败")
  }
  const intervalMs = Math.max(33, Math.round(1000 / Math.max(1, fps)))
  const stream = canvas.captureStream(fps)
  const videoTrack = stream.getVideoTracks?.()[0] || null
  if (!videoTrack) {
    throw new Error("连续流图片桥接未生成视频轨道")
  }

  const loopId = state.nativeBridgeLoopId + 1
  state.nativeBridgeLoopId = loopId
  state.nativeBridgeImage = image
  state.nativeBridgeCanvas = canvas
  state.nativeBridgeStream = stream
  state.nativeBridgeEndpoint = streamUrl
  state.nativeBridgeMode = "mjpeg_stream_image_canvas"

  videoTrack.onmute = () => traceLog("native.bridge.stream_image.track_muted", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })
  videoTrack.onunmute = () => traceLog("native.bridge.stream_image.track_unmuted", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })
  videoTrack.onended = () => traceLog("native.bridge.stream_image.track_ended", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })

  let consecutiveErrors = 0
  let lastSizeSignature = ""
  const tick = () => {
    if (state.nativeBridgeLoopId !== loopId || !state.sessionId || !isAgentSession()) {
      return
    }
    try {
      const sourceWidth = Number(image.naturalWidth || 0)
      const sourceHeight = Number(image.naturalHeight || 0)
      if (sourceWidth > 0 && sourceHeight > 0) {
        const outputSize = resolveBridgeCanvasOutputSize(sourceWidth, sourceHeight)
        if (canvas.width !== outputSize.width || canvas.height !== outputSize.height) {
          canvas.width = outputSize.width
          canvas.height = outputSize.height
        }
        const sizeSignature = `${sourceWidth}x${sourceHeight}->${outputSize.width}x${outputSize.height}`
        if (sizeSignature !== lastSizeSignature) {
          lastSizeSignature = sizeSignature
          traceLog("native.bridge.stream_image.output_size", {
            session_id: state.sessionId || "-",
            source_size: `${sourceWidth}x${sourceHeight}`,
            output_size: `${outputSize.width}x${outputSize.height}`,
            capped: outputSize.capped,
            capped_by: currentSessionMediaCaps()?.source || "-",
          }, { console: false })
        }
        context.drawImage(image, 0, 0, outputSize.width, outputSize.height)
        if (state.agentAdaptive) {
          state.agentAdaptive.bridgeProducedFrames += 1
        }
        state.localFrameMeta = {
          frameId: `native-stream-image-${Date.now()}`,
          captureTs: Date.now(),
          width: outputSize.width,
          height: outputSize.height,
        }
      }
      consecutiveErrors = 0
    } catch (error) {
      consecutiveErrors += 1
      traceLog("native.bridge.stream_image.tick_failed", {
        session_id: state.sessionId || "-",
        reason: error?.message || "unknown",
        consecutive_errors: consecutiveErrors,
      }, { console: false })
      if (consecutiveErrors >= NATIVE_BRIDGE_MAX_CONSECUTIVE_ERRORS) {
        appendLog(`桌面连续流图片桥接失败: ${error?.message || "unknown error"}`)
        state.webrtcState = "failed"
        render()
        return
      }
    }
    state.nativeBridgeTimer = window.setTimeout(tick, intervalMs)
  }

  tick()
  traceLog("native.bridge.stream.started", {
    session_id: state.sessionId || "-",
    stream_url: streamUrl,
    fps_target: fps,
    track_id: videoTrack.id || "-",
    mode: "image_canvas",
  })
  recordBridgeModeUsage("mjpeg_stream_image_canvas", { sessionId: state.sessionId })
  appendLog("已启动桌面原生连续流图片桥接轨道")
  return stream
}

async function startNativeWebRtcBridgeViaMultipartFetch(streamUrl, boundary, fps) {
  if (typeof fetch !== "function") {
    throw new Error("当前环境不支持 fetch 连续流")
  }
  const resolvedBoundary = `${boundary || ""}`.trim() || "rdframe"
  const boundaryBytes = new TextEncoder().encode(`--${resolvedBoundary}`)
  const textDecoder = new TextDecoder()
  const abortController = new AbortController()

  const response = await fetch(streamUrl, {
    method: "GET",
    cache: "no-store",
    headers: {
      Accept: "multipart/x-mixed-replace",
    },
    signal: abortController.signal,
  })
  if (!response.ok) {
    throw new Error(`连续流请求失败: HTTP ${response.status}`)
  }
  const reader = response.body?.getReader?.()
  if (!reader) {
    throw new Error("连续流响应体不可读")
  }

  const canvas = document.createElement("canvas")
  const context = canvas.getContext("2d", { alpha: false })
  if (!context) {
    throw new Error("创建连续流 fetch 画布失败")
  }
  const stream = canvas.captureStream(fps)
  const videoTrack = stream.getVideoTracks?.()[0] || null
  if (!videoTrack) {
    throw new Error("连续流 fetch 画布未生成视频轨道")
  }

  const loopId = state.nativeBridgeLoopId + 1
  state.nativeBridgeLoopId = loopId
  state.nativeBridgeCanvas = canvas
  state.nativeBridgeStream = stream
  state.nativeBridgeEndpoint = streamUrl
  state.nativeBridgeMode = "mjpeg_stream_fetch_canvas"
  state.nativeBridgeFetchAbortController = abortController

  videoTrack.onmute = () => traceLog("native.bridge.stream_fetch.track_muted", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })
  videoTrack.onunmute = () => traceLog("native.bridge.stream_fetch.track_unmuted", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })
  videoTrack.onended = () => traceLog("native.bridge.stream_fetch.track_ended", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })

  let firstFrameSeen = false
  let firstFrameSettled = false
  let frameCount = 0
  let lastSizeSignature = ""
  let consecutiveErrors = 0
  let bufferedBytes = new Uint8Array(0)
  const isBridgeActive = () => (
    state.nativeBridgeLoopId === loopId
    && Boolean(state.sessionId)
    && isAgentSession()
  )
  let firstFrameTimer = 0
  let resolveFirstFrame = null
  let rejectFirstFrame = null
  const firstFramePromise = new Promise((resolve, reject) => {
    resolveFirstFrame = resolve
    rejectFirstFrame = reject
    firstFrameTimer = window.setTimeout(() => {
      if (firstFrameSettled) {
        return
      }
      firstFrameSettled = true
      reject(new Error("原生连续流 fetch 首帧超时"))
    }, 3500)
  })

  const pump = async () => {
    while (isBridgeActive()) {
      const { value, done } = await reader.read()
      if (done) {
        throw new Error("原生连续流 fetch 连接已结束")
      }
      if (!value?.length) {
        continue
      }
      bufferedBytes = concatByteArrays(bufferedBytes, value)
      while (isBridgeActive()) {
        const parsed = parseNextMjpegFrame(bufferedBytes, boundaryBytes, textDecoder)
        bufferedBytes = parsed.buffer
        if (parsed.status === "need_more") {
          break
        }
        if (parsed.status === "error") {
          throw new Error(parsed.error || "原生连续流 fetch 帧解析失败")
        }

        try {
          const image = await decodeBinaryImage(parsed.frame.bytes, parsed.frame.mimeType)
          const frameWidth = Number(image.width || image.videoWidth || 0)
          const frameHeight = Number(image.height || image.videoHeight || 0)
          if (frameWidth <= 0 || frameHeight <= 0) {
            throw new Error("原生连续流 fetch 帧尺寸无效")
          }
          const outputSize = resolveBridgeCanvasOutputSize(frameWidth, frameHeight)
          if (canvas.width !== outputSize.width || canvas.height !== outputSize.height) {
            canvas.width = outputSize.width
            canvas.height = outputSize.height
          }
          const sizeSignature = `${frameWidth}x${frameHeight}->${outputSize.width}x${outputSize.height}`
          if (sizeSignature !== lastSizeSignature) {
            lastSizeSignature = sizeSignature
            traceLog("native.bridge.stream_fetch.output_size", {
              session_id: state.sessionId || "-",
              source_size: `${frameWidth}x${frameHeight}`,
              output_size: `${outputSize.width}x${outputSize.height}`,
              capped: outputSize.capped,
              capped_by: currentSessionMediaCaps()?.source || "-",
            }, { console: false })
          }
          context.drawImage(image, 0, 0, outputSize.width, outputSize.height)
          if (typeof image.close === "function") {
            image.close()
          }
          frameCount += 1
          consecutiveErrors = 0
          if (state.agentAdaptive) {
            state.agentAdaptive.bridgeProducedFrames += 1
          }
          state.localFrameMeta = {
            frameId: `native-stream-fetch-${Date.now()}`,
            captureTs: Date.now(),
            width: outputSize.width,
            height: outputSize.height,
          }
          if (!firstFrameSeen) {
            firstFrameSeen = true
            traceLog("native.bridge.stream_fetch.first_frame", {
              session_id: state.sessionId || "-",
              frame_size: `${outputSize.width}x${outputSize.height}`,
              stream_url: streamUrl,
            })
            if (!firstFrameSettled && typeof resolveFirstFrame === "function") {
              firstFrameSettled = true
              window.clearTimeout(firstFrameTimer)
              resolveFirstFrame()
            }
          }
        } catch (error) {
          consecutiveErrors += 1
          traceLog("native.bridge.stream_fetch.frame_failed", {
            session_id: state.sessionId || "-",
            reason: error?.message || "unknown",
            consecutive_errors: consecutiveErrors,
          }, { console: false })
          if (consecutiveErrors >= NATIVE_BRIDGE_MAX_CONSECUTIVE_ERRORS) {
            throw new Error(error?.message || "原生连续流 fetch 解帧失败")
          }
        }
      }
    }
  }

  void pump()
    .catch((error) => {
      if (!firstFrameSettled && typeof rejectFirstFrame === "function") {
        firstFrameSettled = true
        window.clearTimeout(firstFrameTimer)
        rejectFirstFrame(error)
      }
      if (isBridgeActive()) {
        traceLog("native.bridge.stream_fetch.stopped", {
          session_id: state.sessionId || "-",
          reason: error?.message || "unknown",
          frames: frameCount,
          first_frame_seen: firstFrameSeen,
        })
        appendLog(`原生连续流 fetch 桥接中断: ${error?.message || "unknown error"}`)
        state.webrtcState = "failed"
        render()
      }
    })
    .finally(() => {
      window.clearTimeout(firstFrameTimer)
      try {
        reader.cancel()
      } catch {
        // ignore reader cancel failures
      }
      if (state.nativeBridgeFetchAbortController === abortController) {
        state.nativeBridgeFetchAbortController = null
      }
    })

  try {
    await firstFramePromise
  } catch (error) {
    if (state.nativeBridgeLoopId === loopId) {
      stopNativeWebRtcBridge(true)
    }
    throw error
  }

  traceLog("native.bridge.stream.started", {
    session_id: state.sessionId || "-",
    stream_url: streamUrl,
    fps_target: fps,
    track_id: videoTrack.id || "-",
    mode: "fetch_canvas",
    boundary: resolvedBoundary,
  })
  recordBridgeModeUsage("mjpeg_stream_fetch_canvas", { sessionId: state.sessionId })
  appendLog("已启动桌面原生连续流 fetch 画布桥接轨道")
  return stream
}

async function startNativeWebRtcBridgeViaMjpegCanvas(video, streamUrl, fps) {
  const canvas = document.createElement("canvas")
  const context = canvas.getContext("2d", { alpha: false })
  if (!context) {
    throw new Error("创建连续流桥接画布失败")
  }
  const intervalMs = Math.max(33, Math.round(1000 / Math.max(1, fps)))
  const stream = canvas.captureStream(fps)
  const videoTrack = stream.getVideoTracks?.()[0] || null
  if (!videoTrack) {
    throw new Error("连续流画布桥接未生成视频轨道")
  }

  const loopId = state.nativeBridgeLoopId + 1
  state.nativeBridgeLoopId = loopId
  state.nativeBridgeVideo = video
  state.nativeBridgeCanvas = canvas
  state.nativeBridgeStream = stream
  state.nativeBridgeEndpoint = streamUrl
  state.nativeBridgeMode = "mjpeg_stream_canvas"

  videoTrack.onmute = () => traceLog("native.bridge.stream_canvas.track_muted", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })
  videoTrack.onunmute = () => traceLog("native.bridge.stream_canvas.track_unmuted", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })
  videoTrack.onended = () => traceLog("native.bridge.stream_canvas.track_ended", { session_id: state.sessionId || "-", track_id: videoTrack.id || "-" })

  let consecutiveErrors = 0
  let lastSizeSignature = ""
  const tick = () => {
    if (state.nativeBridgeLoopId !== loopId || !state.sessionId || !isAgentSession()) {
      return
    }
    try {
      const sourceWidth = Number(video.videoWidth)
      const sourceHeight = Number(video.videoHeight)
      if (sourceWidth > 0 && sourceHeight > 0) {
        const outputSize = resolveBridgeCanvasOutputSize(sourceWidth, sourceHeight)
        if (canvas.width !== outputSize.width || canvas.height !== outputSize.height) {
          canvas.width = outputSize.width
          canvas.height = outputSize.height
        }
        const sizeSignature = `${sourceWidth}x${sourceHeight}->${outputSize.width}x${outputSize.height}`
        if (sizeSignature !== lastSizeSignature) {
          lastSizeSignature = sizeSignature
          traceLog("native.bridge.stream_canvas.output_size", {
            session_id: state.sessionId || "-",
            source_size: `${sourceWidth}x${sourceHeight}`,
            output_size: `${outputSize.width}x${outputSize.height}`,
            capped: outputSize.capped,
            capped_by: currentSessionMediaCaps()?.source || "-",
          }, { console: false })
        }
        context.drawImage(video, 0, 0, outputSize.width, outputSize.height)
        if (state.agentAdaptive) {
          state.agentAdaptive.bridgeProducedFrames += 1
        }
        state.localFrameMeta = {
          frameId: `native-stream-${Date.now()}`,
          captureTs: Date.now(),
          width: outputSize.width,
          height: outputSize.height,
        }
      }
      consecutiveErrors = 0
    } catch (error) {
      consecutiveErrors += 1
      traceLog("native.bridge.stream_canvas.tick_failed", {
        session_id: state.sessionId || "-",
        reason: error?.message || "unknown",
        consecutive_errors: consecutiveErrors,
      }, { console: false })
      if (consecutiveErrors >= NATIVE_BRIDGE_MAX_CONSECUTIVE_ERRORS) {
        appendLog(`桌面连续流画布桥接失败: ${error?.message || "unknown error"}`)
        state.webrtcState = "failed"
        render()
        return
      }
    }
    state.nativeBridgeTimer = window.setTimeout(tick, intervalMs)
  }

  tick()
  traceLog("native.bridge.stream.started", {
    session_id: state.sessionId || "-",
    stream_url: streamUrl,
    fps_target: fps,
    track_id: videoTrack.id || "-",
    mode: "canvas",
  })
  recordBridgeModeUsage("mjpeg_stream_canvas", { sessionId: state.sessionId })
  appendLog("已启动桌面原生连续流画布桥接轨道")
  return stream
}

async function startNativeWebRtcBridge() {
  try {
    const stream = await startNativeWebRtcBridgeViaMediaStream()
    if (stream) {
      return stream
    }
  } catch (error) {
    traceLog("native.bridge.stream.unavailable", {
      session_id: state.sessionId || "-",
      reason: error?.message || "unknown",
    })
    appendLog(`原生连续流桥接不可用: ${error?.message || "unknown error"}`)
  }
  return null
}

function syncViewportMediaElement() {
  const mediaElement = document.getElementById("remoteMediaVideo")
  if (!(mediaElement instanceof HTMLVideoElement)) {
    return
  }
  const sessionSide = currentSessionSide()
  const stream = sessionSide === "controller" ? state.remoteMediaStream : state.localMediaStream
  if (stream) {
    const streamChanged = mediaElement.srcObject !== stream
    if (streamChanged) {
      mediaElement.srcObject = stream
    }
    if (streamChanged || mediaElement.paused) {
      void mediaElement.play().catch(() => {})
    }
  } else if (mediaElement.srcObject) {
    mediaElement.srcObject = null
  }
}

function readVideoTotalFrames(mediaElement) {
  if (!(mediaElement instanceof HTMLVideoElement)) {
    return -1
  }
  if (typeof mediaElement.getVideoPlaybackQuality === "function") {
    const quality = mediaElement.getVideoPlaybackQuality()
    const frames = Number(quality?.totalVideoFrames)
    if (Number.isFinite(frames) && frames >= 0) {
      return frames
    }
  }
  const decodedFrames = Number(mediaElement.webkitDecodedFrameCount)
  if (Number.isFinite(decodedFrames) && decodedFrames >= 0) {
    return decodedFrames
  }
  return -1
}

function stopLocalPreviewFrameCallbackSampler() {
  if (
    localPreviewFrameCallbackElement
    && typeof localPreviewFrameCallbackElement.cancelVideoFrameCallback === "function"
    && localPreviewFrameCallbackHandle
  ) {
    try {
      localPreviewFrameCallbackElement.cancelVideoFrameCallback(localPreviewFrameCallbackHandle)
    } catch {
      // ignore cancel failures
    }
  }
  localPreviewFrameCallbackElement = null
  localPreviewFrameCallbackHandle = 0
  localPreviewFrameSampleStartMs = 0
  localPreviewFrameSampleCount = 0
}

function applyLocalPreviewFps(nextFps, sampleAtMs = Date.now()) {
  if (!Number.isFinite(nextFps) || nextFps < 0) {
    return
  }
  if (Math.abs(nextFps - state.localPreviewFps) >= 0.4 || state.localPreviewFps < 0) {
    state.localPreviewFps = nextFps
    state.localPreviewFpsUpdatedAtMs = sampleAtMs
    render()
  }
}

function ensureLocalPreviewFrameCallbackSampler(mediaElement) {
  if (!(mediaElement instanceof HTMLVideoElement)) {
    return false
  }
  if (typeof mediaElement.requestVideoFrameCallback !== "function") {
    return false
  }
  if (localPreviewFrameCallbackElement === mediaElement && localPreviewFrameCallbackHandle) {
    return true
  }

  stopLocalPreviewFrameCallbackSampler()
  localPreviewFrameCallbackElement = mediaElement

  const onFrame = (_now, _metadata) => {
    if (localPreviewFrameCallbackElement !== mediaElement) {
      return
    }
    const sampleNowMs = performance.now()
    if (localPreviewFrameSampleStartMs <= 0) {
      localPreviewFrameSampleStartMs = sampleNowMs
      localPreviewFrameSampleCount = 0
    }
    localPreviewFrameSampleCount += 1
    const elapsedMs = sampleNowMs - localPreviewFrameSampleStartMs
    if (elapsedMs >= LOCAL_PREVIEW_FPS_SAMPLE_MS) {
      const fps = localPreviewFrameSampleCount * 1000 / elapsedMs
      applyLocalPreviewFps(fps)
      localPreviewFrameSampleStartMs = sampleNowMs
      localPreviewFrameSampleCount = 0
    }
    try {
      localPreviewFrameCallbackHandle = mediaElement.requestVideoFrameCallback(onFrame)
    } catch {
      stopLocalPreviewFrameCallbackSampler()
    }
  }

  try {
    localPreviewFrameCallbackHandle = mediaElement.requestVideoFrameCallback(onFrame)
    return true
  } catch {
    stopLocalPreviewFrameCallbackSampler()
    return false
  }
}

function sampleLocalPreviewFps() {
  const localPreviewActive = currentSessionSide() === "agent" && Boolean(state.localMediaStream)
  const mediaElement = document.getElementById("remoteMediaVideo")
  if (!localPreviewActive || !(mediaElement instanceof HTMLVideoElement)) {
    stopLocalPreviewFrameCallbackSampler()
    localPreviewLastTotalFrames = -1
    localPreviewLastSampleAtMs = 0
    if (state.localPreviewFps >= 0) {
      state.localPreviewFps = -1
      state.localPreviewFpsUpdatedAtMs = Date.now()
      render()
    }
    return
  }

  if (ensureLocalPreviewFrameCallbackSampler(mediaElement)) {
    return
  }

  const totalFrames = readVideoTotalFrames(mediaElement)
  const nowMs = Date.now()
  if (totalFrames < 0) {
    return
  }
  if (localPreviewLastTotalFrames < 0 || localPreviewLastSampleAtMs <= 0) {
    localPreviewLastTotalFrames = totalFrames
    localPreviewLastSampleAtMs = nowMs
    return
  }
  const deltaFrames = totalFrames - localPreviewLastTotalFrames
  const deltaMs = nowMs - localPreviewLastSampleAtMs
  localPreviewLastTotalFrames = totalFrames
  localPreviewLastSampleAtMs = nowMs
  if (deltaMs <= 0 || deltaFrames < 0) {
    return
  }
  const nextFps = deltaFrames * 1000 / deltaMs
  applyLocalPreviewFps(nextFps, nowMs)
}

function ensureLocalPreviewFpsSamplerRunning() {
  if (localPreviewSamplerTimer) {
    return
  }
  localPreviewSamplerTimer = window.setInterval(() => {
    sampleLocalPreviewFps()
  }, LOCAL_PREVIEW_FPS_SAMPLE_MS)
}

function createPeerConnection(sessionId) {
  closePeerConnection(true)
  if (typeof RTCPeerConnection !== "function") {
    appendLog("当前运行环境不支持 RTCPeerConnection")
    state.webrtcState = "failed"
    render()
    return null
  }
  const transportPolicy = currentSessionIceTransportPolicy()
  const pc = new RTCPeerConnection({
    iceServers: sessionIceServers(),
    iceTransportPolicy: transportPolicy,
  })
  traceLog("webrtc.pc.created", {
    session_id: sessionId || "-",
    ice_transport_policy: transportPolicy,
    ice_servers: sessionIceServers().map((server) => (Array.isArray(server?.urls) ? server.urls.join("|") : `${server?.urls || ""}`)).join(","),
  })
  pc.onicecandidate = (event) => {
    if (!event.candidate || !state.sessionId || state.sessionId !== sessionId) {
      return
    }
    traceLog("webrtc.local_ice", {
      session_id: sessionId || "-",
      candidate_type: extractIceCandidateType(event.candidate.candidate),
      sdp_mid: `${event.candidate.sdpMid || ""}`,
      sdp_mline_index: Number.isInteger(event.candidate.sdpMLineIndex) ? event.candidate.sdpMLineIndex : -1,
    })
    sendEnvelope("webrtc.ice_candidate", {
      candidate: event.candidate.candidate,
      sdp_mid: event.candidate.sdpMid || "0",
      sdp_mline_index: Number.isInteger(event.candidate.sdpMLineIndex) ? event.candidate.sdpMLineIndex : 0,
    }, sessionId, { logLine: "发送 webrtc.ice_candidate" })
  }
  pc.onicegatheringstatechange = () => {
    traceLog("webrtc.ice_gathering_state", {
      session_id: sessionId || "-",
      state: pc.iceGatheringState || "unknown",
    })
  }
  pc.oniceconnectionstatechange = () => {
    const nextState = pc.iceConnectionState || "unknown"
    traceLog("webrtc.ice_connection_state", {
      session_id: sessionId || "-",
      state: nextState,
      pending_remote_ice: state.pendingRemoteCandidates.length,
    })
    appendLog(`WebRTC ICE 状态: ${nextState}`)
    render()
  }
  pc.ontrack = (event) => {
    const stream = event.streams?.[0]
    if (stream) {
      state.remoteMediaStream = stream
      state.webrtcState = "connected"
      traceLog("webrtc.remote_track", {
        session_id: sessionId || "-",
        track_kind: event.track?.kind || "unknown",
        track_id: event.track?.id || "-",
      })
      appendLog("收到远端 WebRTC 视频轨道")
      if (isControllerSession()) {
        startControllerWebRtcStatsProbe(sessionId, pc)
      }
      render()
    }
  }
  pc.onconnectionstatechange = () => {
    const stateLabel = pc.connectionState || "unknown"
    state.webrtcState = stateLabel
    traceLog("webrtc.connection_state", {
      session_id: sessionId || "-",
      state: stateLabel,
    })
    if (stateLabel === "failed" || stateLabel === "closed" || stateLabel === "disconnected") {
      appendLog(`WebRTC 连接状态: ${stateLabel}`)
    }
    render()
  }
  state.peerConnection = pc
  state.pendingRemoteCandidates = []
  return pc
}

async function flushPendingRemoteCandidates() {
  if (!state.peerConnection || !state.peerConnection.remoteDescription) {
    return
  }
  const candidates = state.pendingRemoteCandidates.splice(0)
  if (candidates.length) {
    traceLog("webrtc.remote_ice.flush", {
      session_id: state.sessionId || "-",
      pending: candidates.length,
    })
  }
  for (const candidate of candidates) {
    try {
      await state.peerConnection.addIceCandidate(candidate)
      traceLog("webrtc.remote_ice.applied", {
        session_id: state.sessionId || "-",
        candidate_type: extractIceCandidateType(candidate.candidate),
        sdp_mid: `${candidate.sdpMid || ""}`,
        sdp_mline_index: Number.isFinite(Number(candidate.sdpMLineIndex)) ? Number(candidate.sdpMLineIndex) : -1,
      }, { console: false })
    } catch (error) {
      appendLog(`应用远端 ICE candidate 失败: ${error?.message || "unknown error"}`)
      traceLog("webrtc.remote_ice.apply_failed", {
        session_id: state.sessionId || "-",
        reason: error?.message || "unknown",
      })
    }
  }
}

async function applyAgentVideoTrackPreferences(track, options = {}) {
  if (!track || track.kind !== "video") {
    return
  }
  const profile = buildEffectiveAdaptiveProfile(options.profile || currentAgentAdaptiveProfile())

  try {
    track.contentHint = "detail"
  } catch {
    // ignore unsupported contentHint assignments
  }

  if (typeof track.applyConstraints !== "function") {
    return
  }

  try {
    const constraints = {
      frameRate: { ideal: Math.min(CAPTURE_DIRECT_TRACK_IDEAL_FPS, profile.maxFps), max: profile.maxFps },
      width: { ideal: profile.maxWidth, max: profile.maxWidth },
      height: { ideal: profile.maxHeight, max: profile.maxHeight },
    }
    const timeoutMs = 1800
    await Promise.race([
      track.applyConstraints(constraints),
      new Promise((_, reject) => {
        window.setTimeout(() => {
          reject(new Error(`applyConstraints timeout (${timeoutMs}ms)`))
        }, timeoutMs)
      }),
    ])
  } catch (error) {
    appendLog(`应用本地视频轨道约束失败: ${error?.message || "unknown error"}`)
  }
}

async function requestAgentMediaStream() {
  if (hasActiveCaptureStream()) {
    recordBridgeModeUsage("direct_track", { sessionId: state.sessionId })
    appendLog("受控端媒体路径：复用已预备的共享屏幕直推轨道")
    return state.captureStream
  }

  const directReady = await requestCaptureStream({ log: false, forceDisplayMedia: true })
  if (directReady && state.captureStream) {
    recordBridgeModeUsage("direct_track", { sessionId: state.sessionId })
    appendLog("受控端媒体路径：直推共享屏幕视频轨道")
    return state.captureStream
  }

  if (isTauri()) {
    const reason = state.captureError || "unknown error"
    appendLog(`直推共享屏幕轨道不可用，准备回退原生桥接: ${reason}`)
    const fallbackStream = await startNativeWebRtcBridge()
    if (fallbackStream) {
      appendLog("受控端媒体路径：回退到原生桥接轨道")
      return fallbackStream
    }
  }

  return null
}

function isPeerConnectionClosed(pc) {
  if (!pc) {
    return true
  }
  const signalingState = `${pc.signalingState || ""}`.toLowerCase()
  if (signalingState === "closed") {
    return true
  }
  const connectionState = `${pc.connectionState || ""}`.toLowerCase()
  if (connectionState === "closed") {
    return true
  }
  return false
}

function isAgentOfferContextValid(sessionId, pc) {
  if (!sessionId || !isAgentSession() || state.sessionId !== sessionId) {
    return false
  }
  if (isPeerConnectionClosed(pc)) {
    return false
  }
  return true
}

async function disposeStaleMediaStream(mediaStream) {
  const stream = mediaStream || null
  if (!stream) {
    return
  }
  if (state.captureStream === stream || state.localMediaStream === stream) {
    try {
      await stopCaptureStream(true)
    } catch {
      // ignore stale stream cleanup failures
    }
    return
  }
  for (const track of stream.getTracks()) {
    try {
      track.stop()
    } catch {
      // ignore stale track stop failures
    }
  }
}

async function ensureAgentPeerReady(sessionId) {
  emitNativeDebugLog(`agent.peer.ready.begin session=${sessionId || "-"}`)
  const pc = createPeerConnection(sessionId)
  if (!pc) {
    emitNativeDebugLog(`agent.peer.ready.failed session=${sessionId || "-"} reason=pc_null`)
    return null
  }
  const mediaStream = await requestAgentMediaStream()
  if (!mediaStream) {
    emitNativeDebugLog(`agent.peer.ready.failed session=${sessionId || "-"} reason=media_stream_null`)
    appendLog("准备屏幕采集失败，无法建立 WebRTC 视频轨道")
    state.webrtcState = "failed"
    render()
    return null
  }
  if (!isAgentOfferContextValid(sessionId, pc)) {
    emitNativeDebugLog(`agent.peer.ready.aborted session=${sessionId || "-"} reason=stale_context_after_media`)
    await disposeStaleMediaStream(mediaStream)
    return null
  }
  emitNativeDebugLog(`agent.peer.ready.media session=${sessionId || "-"} mode=${state.nativeBridgeMode || "direct"} tracks=${mediaStream.getVideoTracks().length}`)
  state.localMediaStream = mediaStream
  const tracks = mediaStream.getVideoTracks()
  if (tracks.length === 0) {
    emitNativeDebugLog(`agent.peer.ready.failed session=${sessionId || "-"} reason=no_video_track`)
    appendLog("准备屏幕采集失败：缺少视频轨道")
    state.webrtcState = "failed"
    render()
    return null
  }
  ensureAgentAdaptiveSessionState(sessionId)
  const initialProfile = currentEffectiveAgentAdaptiveProfile()
  for (const track of tracks) {
    if (!isAgentOfferContextValid(sessionId, pc)) {
      emitNativeDebugLog(`agent.peer.ready.aborted session=${sessionId || "-"} reason=stale_context_before_add_track`)
      await disposeStaleMediaStream(mediaStream)
      return null
    }
    try {
      await applyAgentVideoTrackPreferences(track, { profile: initialProfile })
      const sender = pc.addTrack(track, mediaStream)
      emitNativeDebugLog(`agent.peer.ready.track_added session=${sessionId || "-"} track=${track.id || "-"} sender=${sender?.track?.id || "-"}`)
      await tuneVideoSender(sender, {
        profile: initialProfile,
        reason: "initial",
        force: true,
      })
    } catch (error) {
      if (!isAgentOfferContextValid(sessionId, pc)) {
        emitNativeDebugLog(`agent.peer.ready.aborted session=${sessionId || "-"} reason=stale_context_track_add_exception`)
        await disposeStaleMediaStream(mediaStream)
        return null
      }
      emitNativeDebugLog(`agent.peer.ready.track_add_failed session=${sessionId || "-"} reason=${error?.message || "unknown"}`)
      appendLog(`受控端添加视频轨道失败: ${error?.message || "unknown error"}`)
      await disposeStaleMediaStream(mediaStream)
      state.webrtcState = "failed"
      render()
      return null
    }
  }
  emitNativeDebugLog(`agent.peer.ready.ok session=${sessionId || "-"} tracks=${tracks.length}`)
  return pc
}

function ensureControllerPeerReady(sessionId) {
  const pc = state.peerConnection || createPeerConnection(sessionId)
  if (!pc) {
    return null
  }
  const transceivers = typeof pc.getTransceivers === "function" ? pc.getTransceivers() : []
  const hasVideoReceiver = transceivers.some((transceiver) => transceiver?.receiver?.track?.kind === "video")
  if (!hasVideoReceiver) {
    try {
      pc.addTransceiver("video", { direction: "recvonly" })
    } catch (error) {
      appendLog(`添加控制端接收轨道失败: ${error?.message || "unknown error"}`)
    }
  }
  return pc
}

async function beginAgentWebRtcOffer(sessionId) {
  if (!sessionId) {
    return
  }
  emitNativeDebugLog(`agent.offer.begin session=${sessionId}`)
  clearAgentWebRtcOfferRetry()
  if (!isAgentSession() || state.sessionId !== sessionId) {
    emitNativeDebugLog(`agent.offer.skipped session=${sessionId} reason=session_mismatch`)
    return
  }
  if (nativeSenderOwnershipEnabledForSession(sessionId)) {
    try {
      ensureAgentAdaptiveSessionState(sessionId)
      const status = await invoke("native_sender_create_offer", {
        request: {
          session_id: sessionId,
          ice_restart: false,
        },
      })
      state.nativeSenderStatus = normalizeNativeSenderStatus(status)
      await drainNativeSenderOutboundSignals(sessionId, { log: true })
      emitNativeDebugLog(`agent.offer.sent session=${sessionId} owner=native_sender`)
      state.agentOfferRetryAttempts = 0
      startAgentWebRtcStatsProbe(sessionId, null)
      state.webrtcState = "negotiating"
      render()
      return
    } catch (error) {
      emitNativeDebugLog(`agent.offer.failed session=${sessionId} owner=native_sender reason=${error?.message || "unknown"}`)
      appendLog(`Rust 原生 sender 创建 offer 失败: ${error?.message || "unknown error"}`)
      scheduleAgentWebRtcOfferRetry(sessionId, error?.message || "native_owner_create_offer_failed")
      state.webrtcState = "negotiating"
      render()
      return
    }
  }
  const pc = await ensureAgentPeerReady(sessionId)
  if (!pc) {
    emitNativeDebugLog(`agent.offer.media_not_ready session=${sessionId}`)
    scheduleAgentWebRtcOfferRetry(sessionId, state.captureError || "media_not_ready")
    return
  }
  if (!isAgentOfferContextValid(sessionId, pc)) {
    emitNativeDebugLog(`agent.offer.skipped session=${sessionId} reason=stale_context_after_peer_ready`)
    return
  }
  try {
    emitNativeDebugLog(`agent.offer.create_offer session=${sessionId}`)
    const offer = await pc.createOffer({
      offerToReceiveVideo: false,
      offerToReceiveAudio: false,
    })
    emitNativeDebugLog(`agent.offer.set_local_description session=${sessionId} sdp_len=${`${offer?.sdp || ""}`.length}`)
    await pc.setLocalDescription(offer)
    startAgentWebRtcStatsProbe(sessionId, pc)
    traceLog("webrtc.local_offer.ready", {
      session_id: sessionId || "-",
      side: "agent",
      sdp_len: `${offer?.sdp || ""}`.length,
      sdp_candidate_lines: countSdpCandidateLines(offer?.sdp || ""),
    })
    sendEnvelope("webrtc.offer", {
      sdp_type: "offer",
      sdp: offer.sdp || "",
    }, sessionId, { logLine: "发送 webrtc.offer" })
    emitNativeDebugLog(`agent.offer.sent session=${sessionId}`)
    state.agentOfferRetryAttempts = 0
    state.webrtcState = "negotiating"
    render()
  } catch (error) {
    emitNativeDebugLog(`agent.offer.failed session=${sessionId} reason=${error?.message || "unknown"}`)
    appendLog(`创建 WebRTC offer 失败: ${error?.message || "unknown error"}`)
    scheduleAgentWebRtcOfferRetry(sessionId, error?.message || "create_offer_failed")
    state.webrtcState = "negotiating"
    render()
  }
}

async function recoverNativeStreamAndRenegotiate(sessionId, options = {}) {
  if (!sessionId || !isAgentSession() || state.sessionId !== sessionId) {
    return false
  }
  if (!isTauri() || !isNativeStreamBridgeMode()) {
    return false
  }

  const reason = options.reason || "stream_no_frames"
  const now = Date.now()
  if (
    now - state.agentLastRecoveryAt < AGENT_WEBRTC_RECOVERY_COOLDOWN_MS
    || state.agentRecoveryAttempts >= AGENT_WEBRTC_MAX_RECOVERY_ATTEMPTS
  ) {
    traceLog("webrtc.agent.native_stream.retry_skipped", {
      session_id: sessionId,
      reason,
      recovery_attempts: state.agentRecoveryAttempts,
    }, { console: false })
    return false
  }
  state.agentRecoveryAttempts += 1
  state.agentLastRecoveryAt = now
  traceLog("webrtc.agent.native_stream.force_retry", {
    session_id: sessionId,
    from_mode: state.nativeBridgeMode || "-",
    reason,
    attempt: state.agentRecoveryAttempts,
  })
  appendLog(`检测到连续流桥接 0 帧，执行第 ${state.agentRecoveryAttempts} 次纯视频重协商（${reason}）`)
  stopNativeWebRtcBridge(true)
  state.localMediaStream = null
  void beginAgentWebRtcOffer(sessionId)
  return true
}

async function beginControllerWebRtcOffer(sessionId, options = {}) {
  if (!sessionId) {
    return
  }
  const trigger = typeof options.trigger === "string" && options.trigger.trim()
    ? options.trigger.trim()
    : "controller_offer"
  const pc = ensureControllerPeerReady(sessionId)
  if (!pc) {
    return
  }
  try {
    const offer = await pc.createOffer({
      offerToReceiveVideo: true,
      offerToReceiveAudio: false,
      iceRestart: trigger === "restart_ice",
    })
    await pc.setLocalDescription(offer)
    traceLog("webrtc.local_offer.ready", {
      session_id: sessionId || "-",
      side: "controller",
      trigger,
      sdp_len: `${offer?.sdp || ""}`.length,
      sdp_candidate_lines: countSdpCandidateLines(offer?.sdp || ""),
    })
    sendEnvelope("webrtc.offer", {
      sdp_type: "offer",
      sdp: offer.sdp || "",
    }, sessionId, { logLine: `发送 webrtc.offer (${trigger})` })
    startControllerWebRtcStatsProbe(sessionId, pc)
    state.webrtcState = "negotiating"
    render()
  } catch (error) {
    appendLog(`控制端创建 WebRTC offer 失败: ${error?.message || "unknown error"}`)
    state.webrtcState = "failed"
    render()
  }
}

async function handleRemoteWebRtcOffer(sessionId, sdp) {
  const pc = isAgentSession()
    ? await ensureAgentPeerReady(sessionId)
    : (state.peerConnection || createPeerConnection(sessionId))
  if (!pc) {
    if (isAgentSession()) {
      scheduleAgentWebRtcOfferRetry(sessionId, state.captureError || "remote_offer_media_not_ready")
    }
    return
  }
  try {
    traceLog("webrtc.remote_offer.recv", {
      session_id: sessionId || "-",
      sdp_len: `${sdp || ""}`.length,
      sdp_has_video: `${sdp || ""}`.includes("m=video"),
      sdp_candidate_lines: countSdpCandidateLines(sdp || ""),
    })
    await pc.setRemoteDescription({
      type: "offer",
      sdp,
    })
    await flushPendingRemoteCandidates()
    const answer = await pc.createAnswer()
    await pc.setLocalDescription(answer)
    if (isAgentSession()) {
      startAgentWebRtcStatsProbe(sessionId, pc)
    } else {
      startControllerWebRtcStatsProbe(sessionId, pc)
    }
    traceLog("webrtc.local_answer.ready", {
      session_id: sessionId || "-",
      sdp_len: `${answer?.sdp || ""}`.length,
      sdp_candidate_lines: countSdpCandidateLines(answer?.sdp || ""),
    })
    sendEnvelope("webrtc.answer", {
      sdp_type: "answer",
      sdp: answer.sdp || "",
    }, sessionId, { logLine: "发送 webrtc.answer" })
    state.webrtcState = "negotiating"
    render()
  } catch (error) {
    appendLog(`处理 webrtc.offer 失败: ${error?.message || "unknown error"}`)
    if (isAgentSession()) {
      scheduleAgentWebRtcOfferRetry(sessionId, error?.message || "handle_remote_offer_failed")
      state.webrtcState = "negotiating"
    } else {
      state.webrtcState = "failed"
    }
    render()
  }
}

async function handleRemoteWebRtcAnswer(sessionId, sdp) {
  const pc = state.peerConnection
  if (!pc) {
    appendLog("收到 webrtc.answer 时本地 PeerConnection 未就绪")
    return
  }
  try {
    traceLog("webrtc.remote_answer.recv", {
      session_id: sessionId || "-",
      sdp_len: `${sdp || ""}`.length,
      sdp_has_video: `${sdp || ""}`.includes("m=video"),
      sdp_candidate_lines: countSdpCandidateLines(sdp || ""),
    })
    await pc.setRemoteDescription({
      type: "answer",
      sdp,
    })
    await flushPendingRemoteCandidates()
    if (isAgentSession()) {
      startAgentWebRtcStatsProbe(sessionId, pc)
    } else {
      startControllerWebRtcStatsProbe(sessionId, pc)
    }
    state.webrtcState = "negotiating"
    render()
  } catch (error) {
    appendLog(`处理 webrtc.answer 失败: ${error?.message || "unknown error"}`)
    state.webrtcState = "failed"
    render()
  }
}

async function handleRemoteIceCandidate(payload) {
  const candidateText = typeof payload?.candidate === "string" ? payload.candidate : ""
  if (!candidateText) {
    return
  }
  const candidate = {
    candidate: candidateText,
    sdpMid: typeof payload?.sdp_mid === "string" && payload.sdp_mid.trim() ? payload.sdp_mid : "0",
    sdpMLineIndex: Number.isFinite(Number(payload?.sdp_mline_index)) && Number(payload.sdp_mline_index) >= 0 ? Number(payload.sdp_mline_index) : 0,
  }
  traceLog("webrtc.remote_ice.recv", {
    session_id: state.sessionId || "-",
    candidate_type: extractIceCandidateType(candidateText),
    candidate_len: candidateText.length,
    sdp_mid: `${candidate.sdpMid || ""}`,
    sdp_mline_index: Number.isFinite(Number(candidate.sdpMLineIndex)) ? Number(candidate.sdpMLineIndex) : -1,
  }, { console: false })
  if (!state.peerConnection) {
    state.pendingRemoteCandidates.push(candidate)
    traceLog("webrtc.remote_ice.queued", {
      session_id: state.sessionId || "-",
      pending: state.pendingRemoteCandidates.length,
      reason: "peer_not_ready",
    }, { console: false })
    return
  }
  if (!state.peerConnection.remoteDescription) {
    state.pendingRemoteCandidates.push(candidate)
    traceLog("webrtc.remote_ice.queued", {
      session_id: state.sessionId || "-",
      pending: state.pendingRemoteCandidates.length,
      reason: "remote_description_missing",
    }, { console: false })
    return
  }
  try {
    await state.peerConnection.addIceCandidate(candidate)
    traceLog("webrtc.remote_ice.applied", {
      session_id: state.sessionId || "-",
      candidate_type: extractIceCandidateType(candidateText),
    }, { console: false })
  } catch (error) {
    appendLog(`处理 webrtc.ice_candidate 失败: ${error?.message || "unknown error"}`)
    traceLog("webrtc.remote_ice.apply_failed", {
      session_id: state.sessionId || "-",
      reason: error?.message || "unknown",
    })
  }
}

function buildFrameSendContext(options = {}) {
  return {
    socket: state.ws,
    sessionId: typeof options.sessionId === "string" ? options.sessionId : state.sessionId,
    loopId: Number.isInteger(options.loopId) ? options.loopId : null,
  }
}

function frameSendContextCurrent(context) {
  if (!context?.socket || state.ws !== context.socket || !connectionReady()) {
    return false
  }
  if (!context.sessionId || !isAgentSession() || state.sessionId !== context.sessionId) {
    return false
  }
  if (Number.isInteger(context.loopId) && state.streamLoopId !== context.loopId) {
    return false
  }
  return true
}

function stopFrameStream(silent = false) {
  if (state.streamTimer) {
    window.clearTimeout(state.streamTimer)
    state.streamTimer = null
  }
  state.streamLoopId += 1
  state.streamStarting = false
  state.streamLastSentCaptureTs = 0
  if (!state.streamSending) {
    state.streamSending = false
    state.streamSendingLoopId = 0
    state.streamSendPromise = null
  }
  if (!silent) {
    appendLog("停止连续推帧")
    render()
  }
}

function hasActiveCaptureStream() {
  const videoTrack = state.captureStream?.getVideoTracks?.()[0]
  return Boolean(videoTrack && videoTrack.readyState === "live" && state.captureVideo)
}

function streamHasLiveVideoTrack(stream) {
  const videoTrack = stream?.getVideoTracks?.()?.[0]
  return Boolean(videoTrack && videoTrack.readyState === "live")
}

function isNativeStreamBridgeMode(mode = state.nativeBridgeMode) {
  return mode === "mjpeg_stream_direct"
    || mode === "mjpeg_stream_fetch_generator"
    || mode === "mjpeg_stream_canvas"
    || mode === "mjpeg_stream_fetch_canvas"
    || mode === "mjpeg_stream_image_canvas"
}

function fitCaptureSize(width, height) {
  if (!width || !height) {
    return { width: 0, height: 0 }
  }
  const scale = Math.min(CAPTURE_MAX_WIDTH / width, CAPTURE_MAX_HEIGHT / height, 1)
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  }
}

function legacyFrameStreamIntervalMs() {
  if (currentSessionControllerProfile() !== "android_phone") {
    return CAPTURE_FRAME_INTERVAL_MS
  }
  const config = state.captureStatus?.config || {}
  const maxWidth = Number(config.max_width || config.maxWidth || 0)
  const maxHeight = Number(config.max_height || config.maxHeight || 0)
  const maxFps = Math.max(1, Number(config.max_fps || config.maxFps || 0) || 10)
  const sourceRectWidthPpm = Number(config.source_rect_width_ppm ?? config.sourceRectWidthPpm ?? ANDROID_PHONE_SOURCE_RECT_UNITS)
  const sourceRectHeightPpm = Number(config.source_rect_height_ppm ?? config.sourceRectHeightPpm ?? ANDROID_PHONE_SOURCE_RECT_UNITS)
  const isFullSourceRect = sourceRectWidthPpm >= ANDROID_PHONE_SOURCE_RECT_UNITS - 1
    && sourceRectHeightPpm >= ANDROID_PHONE_SOURCE_RECT_UNITS - 1
  const fpsIntervalMs = Math.ceil(1000 / maxFps)
  let targetIntervalMs = ANDROID_PHONE_LEGACY_CLEAR_INTERVAL_MS
  if (maxWidth <= ANDROID_PHONE_PINCH_PREVIEW_MAX_WIDTH && maxHeight <= ANDROID_PHONE_PINCH_PREVIEW_MAX_HEIGHT) {
    targetIntervalMs = ANDROID_PHONE_LEGACY_PINCH_PREVIEW_INTERVAL_MS
  } else if (maxWidth <= ANDROID_PHONE_INTERACTIVE_MAX_WIDTH && maxHeight <= ANDROID_PHONE_INTERACTIVE_MAX_HEIGHT) {
    targetIntervalMs = ANDROID_PHONE_LEGACY_INTERACTIVE_INTERVAL_MS
  } else if (isFullSourceRect && maxWidth === ANDROID_PHONE_FULLSCREEN_MAX_WIDTH && maxHeight === ANDROID_PHONE_FULLSCREEN_MAX_HEIGHT) {
    targetIntervalMs = ANDROID_PHONE_LEGACY_FULLSCREEN_HD_INTERVAL_MS
  } else if (isFullSourceRect && maxWidth <= ANDROID_PHONE_SESSION_MAX_WIDTH && maxHeight <= ANDROID_PHONE_SESSION_MAX_HEIGHT) {
    targetIntervalMs = ANDROID_PHONE_LEGACY_FULLSCREEN_INTERVAL_MS
  } else if (maxWidth >= ANDROID_PHONE_ZOOM_STILL_MAX_WIDTH && maxHeight >= ANDROID_PHONE_ZOOM_STILL_MAX_HEIGHT) {
    targetIntervalMs = ANDROID_PHONE_LEGACY_ZOOM_STILL_INTERVAL_MS
  } else if (maxWidth <= ANDROID_PHONE_ZOOM_DETAIL_MAX_WIDTH && maxHeight <= ANDROID_PHONE_ZOOM_DETAIL_MAX_HEIGHT) {
    targetIntervalMs = ANDROID_PHONE_LEGACY_ZOOM_DETAIL_INTERVAL_MS
  }
  // 作者: long；JPEG 兜底仍是临时视频链路：移动/缩放中用小图高频刷新，停手后用大图低频保清晰，避免 1000p 解码和 Base64 信令把真机拖到个位数 FPS。
  return Math.max(ANDROID_PHONE_LEGACY_INTERACTIVE_INTERVAL_MS, Math.max(fpsIntervalMs, targetIntervalMs))
}

async function waitForVideoReady(video) {
  if (video.readyState >= 2 && video.videoWidth > 0 && video.videoHeight > 0) {
    return
  }
  await new Promise((resolve, reject) => {
    const timeoutId = window.setTimeout(() => {
      cleanup()
      reject(new Error("共享画面初始化超时"))
    }, 5000)

    function cleanup() {
      window.clearTimeout(timeoutId)
      video.onloadedmetadata = null
      video.onerror = null
    }

    video.onloadedmetadata = () => {
      cleanup()
      resolve()
    }
    video.onerror = () => {
      cleanup()
      reject(new Error("无法读取共享画面"))
    }
  })
}

async function waitForStreamVideoReady(video, options = {}) {
  const timeoutMs = Math.max(1000, Number(options.timeoutMs) || 8000)
  if (video.readyState >= 2 && video.videoWidth > 0 && video.videoHeight > 0) {
    return
  }
  await new Promise((resolve, reject) => {
    const startedAt = performance.now()
    let intervalId = 0
    const timeoutId = window.setTimeout(() => {
      cleanup()
      reject(new Error("原生连续流画面初始化超时"))
    }, timeoutMs)

    function isReady() {
      return video.readyState >= 2 && video.videoWidth > 0 && video.videoHeight > 0
    }

    function cleanup() {
      window.clearTimeout(timeoutId)
      if (intervalId) {
        window.clearInterval(intervalId)
      }
      video.onloadedmetadata = null
      video.onloadeddata = null
      video.oncanplay = null
      video.onerror = null
    }

    function resolveIfReady() {
      if (!isReady()) {
        return
      }
      cleanup()
      resolve()
    }

    video.onloadedmetadata = resolveIfReady
    video.onloadeddata = resolveIfReady
    video.oncanplay = resolveIfReady
    video.onerror = () => {
      cleanup()
      reject(new Error("无法读取原生连续流画面"))
    }
    intervalId = window.setInterval(() => {
      if (isReady()) {
        resolveIfReady()
        return
      }
      if (performance.now() - startedAt >= timeoutMs) {
        cleanup()
        reject(new Error("原生连续流画面初始化超时"))
      }
    }, 120)
  })
}

async function waitForStreamImageReady(image, options = {}) {
  const timeoutMs = Math.max(1000, Number(options.timeoutMs) || 8000)
  if (image.naturalWidth > 0 && image.naturalHeight > 0) {
    return
  }
  await new Promise((resolve, reject) => {
    let intervalId = 0
    const timeoutId = window.setTimeout(() => {
      cleanup()
      reject(new Error("原生连续流图片初始化超时"))
    }, timeoutMs)

    function isReady() {
      return image.naturalWidth > 0 && image.naturalHeight > 0
    }

    function cleanup() {
      window.clearTimeout(timeoutId)
      if (intervalId) {
        window.clearInterval(intervalId)
      }
      image.onload = null
      image.onerror = null
    }

    function resolveIfReady() {
      if (!isReady()) {
        return
      }
      cleanup()
      resolve()
    }

    image.onload = resolveIfReady
    image.onerror = () => {
      cleanup()
      reject(new Error("无法读取原生连续流图片"))
    }
    intervalId = window.setInterval(() => {
      resolveIfReady()
    }, 120)
  })
}

function readVideoDecodedFrameCount(video) {
  if (!video) {
    return -1
  }
  const webkitCount = Number(video.webkitDecodedFrameCount)
  if (Number.isFinite(webkitCount) && webkitCount >= 0) {
    return webkitCount
  }
  if (typeof video.getVideoPlaybackQuality === "function") {
    const quality = video.getVideoPlaybackQuality()
    const totalFrames = Number(quality?.totalVideoFrames)
    if (Number.isFinite(totalFrames) && totalFrames >= 0) {
      return totalFrames
    }
  }
  return -1
}

async function waitForStreamVideoFirstFrame(video, options = {}) {
  const timeoutMs = Math.max(1000, Number(options.timeoutMs) || 2500)
  if (!video) {
    throw new Error("连续流视频元素无效")
  }

  if (typeof video.requestVideoFrameCallback === "function") {
    await new Promise((resolve, reject) => {
      let callbackId = 0
      let settled = false
      const timeoutId = window.setTimeout(() => {
        cleanup()
        reject(new Error("原生连续流首帧超时"))
      }, timeoutMs)

      const onError = () => {
        cleanup()
        reject(new Error("原生连续流读取首帧失败"))
      }

      const onFrame = () => {
        cleanup()
        resolve()
      }

      const cleanup = () => {
        if (settled) {
          return
        }
        settled = true
        window.clearTimeout(timeoutId)
        video.onerror = null
        if (callbackId && typeof video.cancelVideoFrameCallback === "function") {
          try {
            video.cancelVideoFrameCallback(callbackId)
          } catch {
            // ignore callback cancel failures
          }
        }
      }

      video.onerror = onError
      callbackId = video.requestVideoFrameCallback(onFrame)
    })
    return
  }

  await new Promise((resolve, reject) => {
    const startTime = Number(video.currentTime) || 0
    const startDecodedFrames = readVideoDecodedFrameCount(video)
    const timeoutId = window.setTimeout(() => {
      cleanup()
      reject(new Error("原生连续流首帧超时"))
    }, timeoutMs)
    const intervalId = window.setInterval(() => {
      const currentTime = Number(video.currentTime) || 0
      const decodedFrames = readVideoDecodedFrameCount(video)
      const hasTimeProgress = currentTime > startTime + 0.01
      const hasDecodedProgress = startDecodedFrames >= 0
        && decodedFrames >= 0
        && decodedFrames > startDecodedFrames
      if (hasTimeProgress || hasDecodedProgress) {
        cleanup()
        resolve()
      }
    }, 100)

    const cleanup = () => {
      window.clearTimeout(timeoutId)
      window.clearInterval(intervalId)
    }
  })
}

async function stopCaptureStream(silent = false) {
  stopNativeWebRtcBridge(true)
  const stream = state.captureStream
  const hadDisplayStream = Boolean(stream)
  const hadNativeSource = isTauri() ? Boolean(currentCaptureSource()) : false
  if (stream) {
    for (const track of stream.getTracks()) {
      track.onended = null
      track.stop()
    }
  }
  if (state.captureVideo) {
    state.captureVideo.pause()
    state.captureVideo.srcObject = null
  }
  state.captureStream = null
  state.captureVideo = null
  state.captureCanvas = null
  state.captureLabel = ""
  state.localFrameUrl = ""
  state.localFrameMeta = null
  state.localMediaStream = null

  if (isTauri()) {
    await stopNativeCapture({ log: false })
    if (!silent && (hadDisplayStream || hadNativeSource)) {
      appendLog("已停止屏幕采集")
      render()
    }
    return true
  }

  if (!silent) {
    appendLog("已停止共享屏幕")
    render()
  }
  return true
}

async function handleCaptureEnded(message) {
  stopFrameStream(true)
  await stopCaptureStream(true)
  state.captureError = message
  appendLog(message)
  render()
}

async function requestCaptureStream(options = {}) {
  const forceDisplayMedia = options.forceDisplayMedia === true
  const shouldLog = options.log !== false
  if (isTauri() && !forceDisplayMedia) {
    return requestNativeCaptureSource(options)
  }

  if (!navigator.mediaDevices?.getDisplayMedia) {
    state.captureError = "当前浏览器不支持屏幕采集"
    if (shouldLog) {
      appendLog(state.captureError)
      render()
    }
    return false
  }

  await stopCaptureStream(true)

  try {
    const stream = await navigator.mediaDevices.getDisplayMedia({
      video: {
        frameRate: { ideal: CAPTURE_DIRECT_TRACK_IDEAL_FPS, max: CAPTURE_DIRECT_TRACK_MAX_FPS },
        width: { ideal: CAPTURE_MAX_WIDTH },
        height: { ideal: CAPTURE_MAX_HEIGHT },
      },
      audio: false,
    })
    const videoTrack = stream.getVideoTracks()[0]
    if (!videoTrack) {
      throw new Error("未获取到视频轨道")
    }

    const video = document.createElement("video")
    video.autoplay = true
    video.muted = true
    video.playsInline = true
    video.srcObject = stream
    await video.play()
    await waitForVideoReady(video)

    state.captureStream = stream
    state.captureVideo = video
    state.captureCanvas = document.createElement("canvas")
    state.captureError = ""
    state.captureLabel = videoTrack.label || "共享屏幕"

    videoTrack.onended = () => {
      if (state.captureStream === stream) {
        void handleCaptureEnded("共享屏幕已结束")
      }
    }

    if (shouldLog) {
      appendLog(`已选择共享屏幕: ${state.captureLabel}`)
      render()
    }
    return true
  } catch (error) {
    await stopCaptureStream(true)
    state.captureError = error.message || "选择共享屏幕失败"
    if (shouldLog) {
      appendLog(`启动采屏失败: ${state.captureError}`)
      render()
    }
    return false
  }
}

function buildFramePayload() {
  if (!hasActiveCaptureStream()) {
    state.captureError = "请先选择共享屏幕"
    return null
  }

  const video = state.captureVideo
  const canvas = state.captureCanvas || document.createElement("canvas")
  const ctx = canvas.getContext("2d", { alpha: false })
  if (!video || !ctx) {
    state.captureError = "采屏上下文初始化失败"
    return null
  }

  const sourceWidth = video.videoWidth
  const sourceHeight = video.videoHeight
  if (!sourceWidth || !sourceHeight) {
    state.captureError = "共享画面尚未准备好"
    return null
  }

  const targetSize = fitCaptureSize(sourceWidth, sourceHeight)
  if (!targetSize.width || !targetSize.height) {
    state.captureError = "共享画面尺寸无效"
    return null
  }

  canvas.width = targetSize.width
  canvas.height = targetSize.height
  ctx.drawImage(video, 0, 0, targetSize.width, targetSize.height)

  const captureTs = Date.now()
  const frameNumber = state.frameSeq + 1
  const dataUrl = canvas.toDataURL("image/png")
  const frameId = `frame-${captureTs}-${frameNumber}`

  state.captureCanvas = canvas
  state.captureError = ""
  state.frameSeq = frameNumber
  state.localFrameUrl = dataUrl
  state.localFrameMeta = {
    frameId,
    captureTs,
    width: targetSize.width,
    height: targetSize.height,
  }

  return {
    frame_id: frameId,
    mime_type: "image/png",
    content_b64: dataUrl.split(",")[1],
    capture_ts: captureTs,
    frame_width: targetSize.width,
    frame_height: targetSize.height,
  }
}

async function sendFrame(options = {}) {
  const context = buildFrameSendContext(options)
  if (!frameSendContextCurrent(context)) {
    return false
  }

  let framePayload = null
  if (isTauri()) {
    try {
      const frame = await invoke("capture_take_frame")
      const mimeType = `${frame?.mime_type || ""}`.trim().toLowerCase()
      const contentB64 = `${frame?.content_b64 || ""}`
      if (!contentB64) {
        throw new Error("capture frame payload missing")
      }
      if (mimeType === NATIVE_CAPTURE_RAW_BGRA_MIME) {
        const rendered = renderRawBgraPreviewDataUrl(
          decodeBase64ToBytes(contentB64),
          Number(frame?.frame_width || 0),
          Number(frame?.frame_height || 0),
          state.nativeSenderPreviewCanvas,
        )
        state.nativeSenderPreviewCanvas = rendered.canvas
        framePayload = {
          frame_id: `${frame?.frame_id || `frame-${Date.now()}`}`,
          mime_type: "image/png",
          content_b64: rendered.dataUrl.split(",")[1],
          capture_ts: Number(frame?.capture_ts || Date.now()),
          frame_width: Number(frame?.frame_width || 0),
          frame_height: Number(frame?.frame_height || 0),
        }
      } else if (mimeType.startsWith("image/")) {
        framePayload = {
          frame_id: `${frame?.frame_id || `frame-${Date.now()}`}`,
          mime_type: mimeType,
          content_b64: contentB64,
          capture_ts: Number(frame?.capture_ts || Date.now()),
          frame_width: Number(frame?.frame_width || 0),
          frame_height: Number(frame?.frame_height || 0),
        }
      } else {
        throw new Error(`legacy frame mime not supported: ${mimeType || "-"}`)
      }
    } catch (error) {
      state.captureError = error?.message || "截取兜底帧失败"
      if (options.log !== false) {
        appendLog(`兜底帧发送失败: ${state.captureError}`)
      }
      render()
      return false
    }
  } else {
    framePayload = buildFramePayload()
  }
  if (!framePayload || !frameSendContextCurrent(context)) {
    return false
  }
  const sourceRectMeta = currentAndroidFrameSourceRectMetadata()
  if (sourceRectMeta) {
    framePayload = {
      ...framePayload,
      ...sourceRectMeta,
    }
  }
  const captureTs = Number(framePayload.capture_ts || 0)
  if (options.skipDuplicateCaptureTs && captureTs > 0 && state.streamLastSentCaptureTs === captureTs) {
    return true
  }
  const sent = sendEnvelope("screen.frame.push", framePayload, context.sessionId, { log: false })
  if (sent && captureTs > 0) {
    state.streamLastSentCaptureTs = captureTs
  }
  if (sent && options.log !== false) {
    appendLog(`已发送兜底画面 ${framePayload.frame_width || 0}x${framePayload.frame_height || 0}`)
  }
  const now = Date.now()
  if (now - lastFrameStreamUiRenderAtMs >= FRAME_STREAM_UI_RENDER_THROTTLE_MS) {
    lastFrameStreamUiRenderAtMs = now
    render()
  }
  return sent
}

async function startFrameStream(options = {}) {
  if (state.streamTimer || state.streamStarting) {
    return true
  }
  if (state.streamSending && state.streamSendPromise) {
    try {
      await state.streamSendPromise
    } catch {
      // sendFrame 已自行记录失败原因
    }
    if (state.streamTimer || state.streamStarting) {
      return true
    }
  }
  if (!canPublishFrames()) {
    appendLog("当前不是受控端会话，不能连续推帧")
    render()
    return false
  }

  state.streamStarting = true
  const loopId = state.streamLoopId + 1
  state.streamLoopId = loopId

    const sendNextFrame = async () => {
      state.streamSending = true
      state.streamSendingLoopId = loopId
      const sendPromise = sendFrame({
        log: false,
        requestCapture: false,
        loopId,
        sessionId: state.sessionId,
        skipDuplicateCaptureTs: true,
      })
    state.streamSendPromise = sendPromise
    try {
      return await sendPromise
    } finally {
      if (state.streamSendingLoopId === loopId && state.streamSendPromise === sendPromise) {
        state.streamSending = false
        state.streamSendingLoopId = 0
        state.streamSendPromise = null
      }
    }
  }

  try {
    if (isTauri()) {
      if (!nativeCaptureSourceReady()) {
        if (options.requestCapture === false) {
          if (options.log !== false) {
            appendLog(nativeCaptureReadinessMessage())
            render()
          }
          return false
        }
        const ready = await requestCaptureStream()
        if (!ready || state.streamLoopId !== loopId) {
          return false
        }
      }
    } else if (!hasActiveCaptureStream()) {
      if (options.requestCapture === false) {
        if (options.log !== false) {
          appendLog("请先选择共享屏幕，再开始推帧")
          render()
        }
        return false
      }
      const ready = await requestCaptureStream()
      if (!ready || state.streamLoopId !== loopId) {
        return false
      }
    }

    const sent = await sendNextFrame()
    if (!sent || state.streamLoopId !== loopId) {
      return false
    }

    const tick = async () => {
      if (state.streamLoopId !== loopId) {
        return
      }
      if (!canPublishFrames() || (isTauri() ? !hasSelectedCaptureSource() : !hasActiveCaptureStream())) {
        stopFrameStream(true)
        render()
        return
      }

      const startedAt = performance.now()
      const nextSent = await sendNextFrame()
      if (!nextSent || state.streamLoopId !== loopId) {
        stopFrameStream(true)
        render()
        return
      }

      if (state.streamLoopId !== loopId) {
        return
      }
      const targetIntervalMs = legacyFrameStreamIntervalMs()
      const elapsedMs = Math.max(0, performance.now() - startedAt)
      // 作者: long；JPEG 兜底流必须维持“最新一帧”节拍：发送慢时下一帧立即追上，发送快时才补足间隔，避免缩放后每帧都额外多等 42-50ms。
      const nextDelayMs = Math.max(0, Math.round(targetIntervalMs - elapsedMs))
      state.streamTimer = window.setTimeout(() => {
        void tick()
      }, nextDelayMs)
    }

    state.streamStarting = false
    state.streamTimer = window.setTimeout(() => {
      void tick()
    }, legacyFrameStreamIntervalMs())
    appendLog(isTauri() ? "开始连续推送桌面原生屏幕帧" : "开始连续推送真实屏幕帧")
    render()
    return true
  } finally {
    state.streamStarting = false
  }
}

async function selectCaptureSource() {
  if (!localCanPrepareRemoteControlHost()) {
    appendLog(isTauri() ? "当前桌面壳层尚未具备受控端采集/输入能力" : "当前浏览器环境尚未具备共享屏幕能力")
    render()
    return
  }
  if (isTauri() && navigator.mediaDevices?.getDisplayMedia) {
    const directReady = await requestCaptureStream({ forceDisplayMedia: true })
    if (directReady) {
      appendLog("已预备共享屏幕直推轨道（会话将优先复用）")
      render()
      return
    }
    appendLog(`共享屏幕直推轨道预备失败，回退原生采集源：${state.captureError || "unknown error"}`)
  }
  await requestCaptureStream()
}

async function ensureAgentAutoReadyForRemoteControl() {
  if (!isTauri() || !localCanPrepareRemoteControlHost()) {
    return
  }
  if (navigator.mediaDevices?.getDisplayMedia) {
    return
  }
  if (!connectionReady() || !state.presenceReady || Boolean(state.sessionId)) {
    return
  }
  if (state.agentAutoPrepareInFlight || state.agentAutoPrepareAttempted) {
    return
  }
  if (nativeCaptureSourceReady()) {
    return
  }

  state.agentAutoPrepareAttempted = true
  state.agentAutoPrepareInFlight = true
  try {
    const prepared = await requestCaptureStream({ log: false })
    if (!prepared) {
      state.agentAutoPrepareAttempted = false
    }
  } catch {
    state.agentAutoPrepareAttempted = false
  } finally {
    state.agentAutoPrepareInFlight = false
  }
}

async function connect(options = {}) {
  syncControlsFromInputs()
  state.deviceId = `${state.deviceId || ""}`.trim() || createDefaultDeviceId()
  persistLocalSettings()
  const connectAttempt = state.connectAttemptSeq + 1
  state.connectAttemptSeq = connectAttempt
  traceLog("ws.connect.begin", {
    attempt: connectAttempt,
    ws_url: state.wsUrl,
    source: options.source || "manual",
    role: state.role,
    device_id: state.deviceId,
  })
  clearAutoConnectTimer()
  clearWsReconnectTimer({ resetAttempt: options.source !== "reconnect" })
  if (state.settingsAutoConnectTimer) {
    window.clearTimeout(state.settingsAutoConnectTimer)
    state.settingsAutoConnectTimer = null
  }

  const nextConnectionKey = buildConnectionKey()
  const previousSocket = state.ws
  const currentSocketActive = Boolean(previousSocket && (previousSocket.readyState === WebSocket.OPEN || previousSocket.readyState === WebSocket.CONNECTING))
  if (options.source === "auto" && currentSocketActive && state.connectionKey === nextConnectionKey) {
    return
  }

  state.connectionKey = nextConnectionKey
  stopFrameStream(true)
  await stopCaptureStream(true)
  closePeerConnection(true)
  void stopNativeSenderControlPlane("connect_reset")
  resetNativeSenderInteractiveProfileState()
  if (connectAttempt !== state.connectAttemptSeq) {
    return
  }

  if (state.sessionId) {
    emitBridgeModeSummary("connect_reset", { endedAt: Date.now() })
  }

  state.token = ""
  state.sessionId = ""
  resetCanvasFallbackDecisionForSession("")
  state.sessionInfo = null
  resetBridgeModeStats("")
  resetAgentStatsSnapshot("")
  state.remoteFrameUrl = ""
  state.remoteFrameMeta = null
  state.localFrameUrl = ""
  state.localFrameMeta = null
  state.lastInput = ""
  state.lastAck = ""
  resetRemoteInputResultStats()
  state.frameSeq = 0
  state.messageSeq = 0
  state.captureError = ""
  state.captureLabel = ""
  state.lastRegisterAt = 0
  state.lastRegisteredCapabilitiesKey = ""
  state.lastHeartbeatAt = 0
  state.presenceReady = false
  state.relayDevices = []
  state.devicesLoaded = false
  state.lastDevicesSyncAt = 0
  state.devicesStatusMessage = "连接成功后会自动同步全部设备。"
  state.agentAutoPrepareAttempted = false
  state.agentAutoPrepareInFlight = false
  void syncHostSession(null, { log: false }).finally(() => render())

  if (previousSocket) {
    previousSocket.onopen = null
    previousSocket.onmessage = null
    previousSocket.onclose = null
    previousSocket.onerror = null
  }
  state.ws = null

  if (currentSocketActive && previousSocket) {
    previousSocket.close(1000, "reconnect")
  }

  let socket
  try {
    socket = new WebSocket(state.wsUrl)
  } catch (error) {
    state.connectionKey = ""
    appendLog(`连接失败: ${error?.message || "WebSocket URL 无效"}`)
    render()
    return
  }

  if (connectAttempt !== state.connectAttemptSeq) {
    socket.close(1000, "superseded")
    return
  }

  state.ws = socket
  appendLog(`连接 ${state.wsUrl}`)
  traceLog("ws.connecting", {
    attempt: connectAttempt,
    ws_url: state.wsUrl,
  })
  render()

  socket.onopen = () => {
    if (state.ws !== socket) {
      return
    }
    if (state.wsReconnectAttempt > 0) {
      appendLog(`WebSocket 自动重连成功（第 ${state.wsReconnectAttempt} 次尝试）`)
    }
    state.wsReconnectAttempt = 0
    appendLog("WebSocket 已连接")
    traceLog("ws.open", {
      ws_url: state.wsUrl,
      connection_key: state.connectionKey,
    })
    void refreshRelayDevices({ force: true })
    render()
    if (state.autoRegister) {
      window.setTimeout(() => {
        if (state.ws === socket && connectionReady()) {
          sendRegister({ auto: true })
        }
      }, 50)
    }
  }

  socket.onmessage = (event) => {
    if (state.ws !== socket) {
      return
    }
    traceLog("ws.recv.raw", {
      bytes: `${event?.data || ""}`.length,
    }, { console: false })
    let msg
    try {
      msg = JSON.parse(event.data)
    } catch (error) {
      appendLog(`收到非法消息: ${error.message}`)
      traceLog("ws.recv.decode_failed", {
        reason: error?.message || "unknown",
      })
      render()
      return
    }
    traceLog("ws.recv.ok", summarizeEnvelope(msg, {
      direction: "in",
      bytes: `${event?.data || ""}`.length,
    }), { console: false })
    void handleMessage(msg)
  }

  socket.onclose = async (event) => {
    if (state.ws !== socket) {
      return
    }
    state.ws = null
    stopFrameStream(true)
    await stopCaptureStream(true)
    closePeerConnection(true)
    void stopNativeSenderControlPlane("ws_closed")
    resetNativeSenderInteractiveProfileState()
    state.connectionKey = ""
    void syncHostSession(null, { log: false }).finally(() => render())
    appendLog("WebSocket 已关闭")
    traceLog("ws.closed", {
      code: event?.code ?? "-",
      reason: event?.reason || "-",
      was_clean: event?.wasClean ?? false,
    })
    render()
    scheduleWsReconnect("close")
  }

  socket.onerror = (event) => {
    if (state.ws !== socket) {
      return
    }
    appendLog("WebSocket 出现错误")
    traceLog("ws.error", {
      ready_state: socket.readyState,
      event_type: event?.type || "error",
    })
    render()
  }
}

async function handleMessage(msg) {
  const payload = msg.payload || {}
  let skipFinalRender = false
  traceLog("signal.inbound", summarizeEnvelope(msg, {
    direction: "in",
  }), { console: false })

  switch (msg.type) {
    case "device.register.rsp":
      state.token = payload.token || state.token
      state.lastRegisterAt = Date.now()
      state.presenceReady = false
      appendLog(`收到 ${msg.type}，token 已更新`)
      if (state.autoHeartbeat) {
        window.setTimeout(() => {
          if (connectionReady()) {
            sendHeartbeat({ auto: true })
          }
        }, 50)
      }
      break
    case "presence.heartbeat.rsp":
      state.presenceReady = true
      appendLog(`心跳成功，状态=${payload.device_status || "unknown"}`)
      void ensureAgentAutoReadyForRemoteControl()
      break
    case "device.presence.push": {
      const pushReason = `${payload.reason || "unknown"}`.trim() || "unknown"
      const changedDeviceID = `${payload.changed_device_id || ""}`.trim() || "-"
      const hasDevices = Array.isArray(payload.devices)
      state.lastDevicesPushAt = Date.now()
      traceLog("device.presence.push", {
        reason: pushReason,
        changed_device_id: changedDeviceID,
        devices_count: hasDevices ? payload.devices.length : 0,
      }, { console: false })
      if (hasDevices) {
        applyRelayDevicesSnapshot(payload, {
          source: `ws_push:${pushReason}`,
          silent: true,
          log: false,
        })
      } else {
        appendLog("收到设备在线推送但缺少 devices 快照，回退 HTTP 同步")
        void refreshRelayDevices({ force: true, silent: true })
      }
      break
    }
    case "session.request.result.push":
      state.sessionId = payload.session_id || state.sessionId
      appendLog(`会话请求结果 ${payload.result || "unknown"}${payload.session_id ? `，session=${payload.session_id}` : ""}`)
      break
    case "session.start.push": {
      const previousSessionId = state.sessionId
      const nextSessionId = payload.session_id || msg.session_id || state.sessionId
      if (previousSessionId && previousSessionId !== nextSessionId) {
        emitBridgeModeSummary("session_replaced", { endedAt: Date.now() })
      }
      state.sessionId = nextSessionId
      resetNativeSenderInteractiveProfileState()
      resetCanvasFallbackDecisionForSession(state.sessionId)
      state.lastSessionMetricsReportSessionId = ""
      state.lastSessionMetricsReportKey = ""
      resetLiveE2EProofReportState()
      state.sessionInfo = payload
      state.sessionStartedAt = Date.now()
      state.firstFrameAt = 0
      resetBridgeModeStats(state.sessionId)
      resetAgentStatsSnapshot(state.sessionId)
      resetControllerStatsSnapshot(state.sessionId)
      maybeReenableFetchGeneratorBridge("session_start", { force: true })
      state.agentRecoveryAttempts = 0
      state.agentLastRecoveryAt = 0
      state.agentZeroFramesSince = 0
      state.agentOfferRetryAttempts = 0
      clearAgentWebRtcOfferRetry()
      traceLog("session.start", {
        session_id: state.sessionId || "-",
        controller_device_id: payload.controller_device_id || "-",
        agent_device_id: payload.agent_device_id || "-",
        transport_mode: payload.transport?.mode || "-",
      })
      state.lastAck = ""
      state.lastInput = ""
      resetRemoteInputResultStats()
      state.remoteFrameUrl = ""
      state.remoteFrameMeta = null
      const hostSessionContext = buildHostSessionContext(state.sessionId, payload)
      await syncHostSession(hostSessionContext, { log: false })
      if (payload.agent_device_id === state.deviceId) {
        appendLog(`进入受控端会话 ${state.sessionId}`)
        logSessionLinkMetricsSnapshot("session_start")
        // long: 原生 sender 启动前必须先把控制端 profile 落到采集配置，否则常驻采集会沿用上一轮低清晰度参数。
        ensureAgentAdaptiveSessionState(state.sessionId)
        const nativeCaptureReady = await ensureNativeCaptureSourceReadyForSession(state.sessionId, { log: true })
        traceLog("session.start.native_capture_ready", {
          session_id: state.sessionId || "-",
          ready: nativeCaptureReady ? 1 : 0,
          capture_lifecycle: state.captureStatus?.lifecycle || "-",
          capture_source: currentCaptureSource()?.source_id || "-",
        })
        if (nativeCaptureReady) {
          await applyNativeSenderCaptureConfig(state.sessionId, { log: true })
        }
        if (nativeCaptureReady && currentSessionControllerProfile() === "android_phone" && ANDROID_PHONE_LEGACY_FRAME_STREAM_ONLY) {
          appendLog("Android 真机当前采用 JPEG 帧流兜底，暂不启动 H.264 native sender")
          await stopNativeSenderControlPlane("android_phone_legacy_frame_stream_only")
          void startFrameStream({ log: false, requestCapture: false })
          void runDesktopDebugSessionTools()
          return
        }
        const nativeStarted = nativeCaptureReady
          ? await startNativeSenderControlPlane(state.sessionId, { dryRun: false })
          : false
        if (!nativeCaptureReady || !nativeStarted || !nativeSenderOwnershipEnabledForSession(state.sessionId)) {
          if (!nativeCaptureReady) {
            appendLog("原生 sender 启动前采集源未就绪，回退 JS 会话协商链路")
          }
          if (currentSessionControllerProfile() === "android_phone") {
            // 作者: long；native sender 是真机主链路，但启动阶段如果 Rust/WebRTC owner 没起来，仍要保留 legacy 可见画面，避免验证时直接黑屏失去输入 proof。
            appendLog("Android 真机 native sender 未接管，启用 JPEG 帧流兜底")
            void startFrameStream({ log: false, requestCapture: false })
          }
          void beginAgentWebRtcOffer(state.sessionId)
        } else {
          appendLog("受控端会话采用 Rust 原生 WebRTC sender 主导信令")
          if (currentSessionControllerProfile() === "android_phone") {
            // 作者: long；H.264 owner 正常启动后不再主动开启 JPEG 兜底流，否则兜底会把共享 capture config 切回 JPEG，主链路又退化成“JPEG 解码后再 H.264 编码”。
            stopFrameStream(true)
          }
        }
        void runDesktopDebugSessionTools()
        return
      }
      stopFrameStream(true)
      await stopCaptureStream(true)
      createPeerConnection(state.sessionId)
      appendLog(`进入控制端会话 ${state.sessionId}`)
      break
    }
    case "session.end.push":
      {
        logSessionLinkMetricsSnapshot("session_end")
        const endingSessionId = state.sessionId
        const endedAt = Date.now()
        const emitted = emitBridgeModeSummary("session_end", { endedAt })
        if (!emitted && endingSessionId) {
          emitSessionMetricsReport("session_end", {
            sessionId: endingSessionId,
            endedAt,
            allowDuplicate: false,
          })
        }
      }
      releaseRemoteViewportPointer()
      releaseRemoteViewportKeys()
      resetNativeSenderInteractiveProfileState()
      state.sessionId = ""
      resetCanvasFallbackDecisionForSession("")
      state.sessionInfo = null
      resetBridgeModeStats("")
      resetAgentStatsSnapshot("")
      resetControllerStatsSnapshot("")
      resetLiveE2EProofReportState()
      state.sessionStartedAt = 0
      state.firstFrameAt = 0
      state.agentRecoveryAttempts = 0
      state.agentLastRecoveryAt = 0
      state.agentZeroFramesSince = 0
      state.agentOfferRetryAttempts = 0
      clearAgentWebRtcOfferRetry()
      state.remoteFrameUrl = ""
      state.remoteFrameMeta = null
      state.localFrameUrl = ""
      state.localFrameMeta = null
      state.lastAck = ""
      state.lastInput = ""
      state.incomingFileTransfers.clear()
      state.clipboardStatus = "剪贴板：等待会话"
      state.fileTransferStatus = "文件：等待会话"
      state.debugSessionToolsRunKey = ""
      resetRemoteInputResultStats()
      stopFrameStream(true)
      await stopCaptureStream(true)
      closePeerConnection(true)
      void stopNativeSenderControlPlane("session_end")
      state.localMediaStream = null
      void syncHostSession(null, { log: false }).finally(() => render())
      appendLog(`会话结束: ${payload.reason || "unknown"}`)
      break
    case "clipboard.text":
      await handleIncomingClipboardText(msg)
      break
    case "clipboard.result":
      handleIncomingClipboardResult(msg)
      break
    case "file.transfer.start":
      handleIncomingFileTransferStart(msg)
      break
    case "file.transfer.chunk":
      handleIncomingFileTransferChunk(msg)
      break
    case "file.transfer.complete":
      await handleIncomingFileTransferComplete(msg)
      break
    case "file.transfer.result":
      handleIncomingFileTransferResult(msg)
      break
    case "session.viewport.interaction": {
      const interactionSessionId = `${msg.session_id || payload.session_id || ""}`.trim()
      if (!interactionSessionId || interactionSessionId !== state.sessionId) {
        appendLog("忽略非当前会话视图交互提示")
        break
      }
      const phase = `${payload.phase || "update"}`.trim().toLowerCase()
      const interaction = `${payload.interaction || "-"}`.trim().toLowerCase()
      const viewportRegion = parseAndroidViewportRegion(payload)
      state.lastAndroidViewportInteraction = {
        session_id: interactionSessionId,
        phase,
        interaction,
        scale: Number(payload.scale || 0) || 0,
        viewport_region: viewportRegion,
        updated_at: Date.now(),
      }
      traceLog("session.viewport.interaction.received", {
        session_id: interactionSessionId,
        phase,
        interaction,
        scale: Number(payload.scale || 0) || 0,
        viewport_x: viewportRegion?.viewport_x ?? -1,
        viewport_y: viewportRegion?.viewport_y ?? -1,
        viewport_width: viewportRegion?.viewport_width ?? -1,
        viewport_height: viewportRegion?.viewport_height ?? -1,
        focus_x: viewportRegion?.focus_x ?? -1,
        focus_y: viewportRegion?.focus_y ?? -1,
      }, { console: false })
      scheduleNativeSenderInteractiveProfileForInput({
        ...msg,
        type: "session.viewport.interaction",
        session_id: interactionSessionId,
      }, {
        restoreDelayMs: viewportInteractionRestoreDelayMs(phase, interaction),
      })
      break
    }
    case "webrtc.offer": {
      const signalSessionId = typeof msg.session_id === "string" ? msg.session_id.trim() : ""
      if (!signalSessionId || signalSessionId !== state.sessionId) {
        appendLog("忽略非当前会话 webrtc.offer")
        traceLog("webrtc.offer.ignored", {
          session_id: signalSessionId || "-",
          current_session_id: state.sessionId || "-",
          reason: "session_mismatch",
        }, { console: false })
        break
      }
      const sdp = typeof payload.sdp === "string" ? payload.sdp : ""
      if (!sdp) {
        appendLog("忽略缺少 SDP 的 webrtc.offer")
        break
      }
      appendLog("收到 webrtc.offer")
      void pushNativeSenderSignal(signalSessionId, msg.type, msg.trace_id, payload, "inbound")
      if (isAgentSession() && nativeSenderOwnershipEnabledForSession(signalSessionId)) {
        traceLog("webrtc.offer.native_owner", {
          session_id: signalSessionId,
          owner: "native_sender",
        }, { console: false })
        await drainNativeSenderOutboundSignals(signalSessionId, { log: true })
      } else {
        await handleRemoteWebRtcOffer(signalSessionId, sdp)
      }
      break
    }
    case "webrtc.answer": {
      const signalSessionId = typeof msg.session_id === "string" ? msg.session_id.trim() : ""
      if (!signalSessionId || signalSessionId !== state.sessionId) {
        appendLog("忽略非当前会话 webrtc.answer")
        traceLog("webrtc.answer.ignored", {
          session_id: signalSessionId || "-",
          current_session_id: state.sessionId || "-",
          reason: "session_mismatch",
        }, { console: false })
        break
      }
      const sdp = typeof payload.sdp === "string" ? payload.sdp : ""
      if (!sdp) {
        appendLog("忽略缺少 SDP 的 webrtc.answer")
        break
      }
      appendLog("收到 webrtc.answer")
      void pushNativeSenderSignal(signalSessionId, msg.type, msg.trace_id, payload, "inbound")
      if (isAgentSession() && nativeSenderOwnershipEnabledForSession(signalSessionId)) {
        traceLog("webrtc.answer.native_owner", {
          session_id: signalSessionId,
          owner: "native_sender",
        }, { console: false })
      } else {
        await handleRemoteWebRtcAnswer(signalSessionId, sdp)
      }
      break
    }
    case "webrtc.ice_candidate": {
      const signalSessionId = typeof msg.session_id === "string" ? msg.session_id.trim() : ""
      if (!signalSessionId || signalSessionId !== state.sessionId) {
        appendLog("忽略非当前会话 webrtc.ice_candidate")
        traceLog("webrtc.ice_candidate.ignored", {
          session_id: signalSessionId || "-",
          current_session_id: state.sessionId || "-",
          reason: "session_mismatch",
        }, { console: false })
        break
      }
      void pushNativeSenderSignal(signalSessionId, msg.type, msg.trace_id, payload, "inbound")
      if (isAgentSession() && nativeSenderOwnershipEnabledForSession(signalSessionId)) {
        traceLog("webrtc.ice_candidate.native_owner", {
          session_id: signalSessionId,
          owner: "native_sender",
        }, { console: false })
      } else {
        await handleRemoteIceCandidate(payload)
      }
      break
    }
    case "webrtc.restart_ice": {
      const signalSessionId = typeof msg.session_id === "string" ? msg.session_id.trim() : ""
      if (!signalSessionId || signalSessionId !== state.sessionId) {
        appendLog("忽略非当前会话 webrtc.restart_ice")
        break
      }
      void pushNativeSenderSignal(signalSessionId, msg.type, msg.trace_id, payload, "inbound")
      appendLog("收到 webrtc.restart_ice，重新发送 offer")
      if (isAgentSession()) {
        if (nativeSenderOwnershipEnabledForSession(signalSessionId)) {
          void invoke("native_sender_create_offer", {
            request: {
              session_id: signalSessionId,
              ice_restart: true,
            },
          }).then((status) => {
            state.nativeSenderStatus = normalizeNativeSenderStatus(status)
            return drainNativeSenderOutboundSignals(signalSessionId, { log: true })
          }).catch((error) => {
            appendLog(`Rust 原生 sender restart_ice 失败: ${error?.message || "unknown error"}`)
          })
        } else {
          void beginAgentWebRtcOffer(signalSessionId)
        }
      } else if (isControllerSession()) {
        void beginControllerWebRtcOffer(signalSessionId, { trigger: "restart_ice" })
      }
      break
    }
    case "input.ack":
      state.lastAck = `${payload.echo_type || "input"} 已确认`
      appendLog(`收到 ${state.lastAck}`)
      break
    case "input.result.push": {
      const resultSessionId = typeof msg.session_id === "string" ? msg.session_id.trim() : ""
      if (!resultSessionId || resultSessionId !== state.sessionId) {
        appendLog("忽略非当前会话输入执行结果")
        break
      }
      state.lastRemoteInputResult = normalizeRemoteInputResult(payload, msg.from)
      state.remoteInputResultCount = Number(state.remoteInputResultCount || 0) + 1
      if (state.lastRemoteInputResult.applied) {
        state.remoteInputResultAppliedCount = Number(state.remoteInputResultAppliedCount || 0) + 1
        recordRemoteInputAppliedCategory(state.lastRemoteInputResult.input_category)
      } else {
        state.remoteInputResultFailedCount = Number(state.remoteInputResultFailedCount || 0) + 1
      }
      const resultLabel = formatRemoteInputResult(state.lastRemoteInputResult)
      state.lastAck = resultLabel
      scheduleLiveE2EProofReport(state.lastRemoteInputResult.applied ? "live_controller_input_applied" : "live_controller_input_result", { force: true })
      appendLog(`收到目标端输入执行结果: ${resultLabel}`)
      break
    }
    case "input.mouse.move":
    case "input.mouse.button":
    case "input.keyboard.key":
    case "input.wheel.scroll": {
      skipFinalRender = true
      const messageSessionId = typeof msg.session_id === "string" ? msg.session_id.trim() : ""
      const senderDeviceId = typeof msg.from?.device_id === "string" ? msg.from.device_id.trim() : ""
      const senderRole = typeof msg.from?.role === "string" ? msg.from.role.trim() : ""
      if (!messageSessionId) {
        state.hostBridgeError = "input message missing session_id"
        recordHostBridgeFailure(msg, {
          last_executor: "host.bridge",
          last_error_code: "input.session_id.required",
          last_error_detail: state.hostBridgeError,
          last_status_code: "input.session_id.required",
          last_status_detail: state.hostBridgeError,
        })
        appendLog(`忽略缺少会话标识的输入 ${msg.type}`)
        break
      }
      if (state.sessionId && messageSessionId !== state.sessionId) {
        state.hostBridgeError = `input session ${messageSessionId} does not match current session ${state.sessionId}`
        recordHostBridgeFailure(msg, {
          last_executor: "host.bridge",
          last_session_id: messageSessionId,
          last_error_code: "input.session.mismatch",
          last_error_detail: state.hostBridgeError,
          last_status_code: "input.session.mismatch",
          last_status_detail: state.hostBridgeError,
        })
        appendLog(`忽略非当前会话输入 ${msg.type}`)
        break
      }
      if (!senderDeviceId) {
        state.hostBridgeError = "input message missing sender_device_id"
        recordHostBridgeFailure(msg, {
          last_executor: "host.bridge",
          last_error_code: "input.sender_device.required",
          last_error_detail: state.hostBridgeError,
          last_status_code: "input.sender_device.required",
          last_status_detail: state.hostBridgeError,
        })
        appendLog(`忽略缺少发送方设备标识的输入 ${msg.type}`)
        break
      }
      if (state.sessionInfo?.controller_device_id && senderDeviceId !== state.sessionInfo.controller_device_id) {
        state.hostBridgeError = `input sender ${senderDeviceId} does not match controller ${state.sessionInfo.controller_device_id}`
        recordHostBridgeFailure(msg, {
          last_executor: "host.bridge",
          last_error_code: "input.sender_device.mismatch",
          last_error_detail: state.hostBridgeError,
          last_status_code: "input.sender_device.mismatch",
          last_status_detail: state.hostBridgeError,
        })
        appendLog(`忽略非控制端设备输入 ${msg.type}`)
        break
      }
      state.lastInput = formatInputMessage(msg)
      if (msg.type === "input.mouse.move") {
        appendInputMoveLogThrottled()
      } else {
        appendLog(`收到输入: ${state.lastInput}`)
      }
      scheduleNativeSenderInteractiveProfileForInput({
        ...msg,
        session_id: messageSessionId,
      })
      if (isAgentSession()) {
        const hostInputMessage = {
          ...msg,
          session_id: messageSessionId,
          from: {
            ...msg.from,
            device_id: senderDeviceId,
            role: senderRole,
          },
        }
        if (msg.type === "input.mouse.move") {
          queueHostMouseMove(hostInputMessage)
          scheduleInputUiRender()
        } else {
          enqueueHostDiscreteInput(hostInputMessage)
        }
      } else {
        scheduleInputUiRender()
      }
      break
    }
    case "error.rsp":
      appendLog(`错误 ${payload.name || "UNKNOWN"}: ${payload.message || "unknown error"}`)
      break
    default:
      appendLog(`收到 ${msg.type}`)
      break
  }

  if (!skipFinalRender) {
    render()
  }
}

function sendRegister(options = {}) {
  syncControlsFromInputs()
  if (options.auto && Date.now() - state.lastRegisterAt < AUTO_SEND_DEDUP_MS) {
    return false
  }
  const isAgent = state.role === "agent"
  const capabilities = currentDeviceCapabilities()
  const sent = sendEnvelope("device.register.req", {
    device_id: state.deviceId,
    user_id: `desktop-user-${state.deviceId}`,
    platform: state.shellPlatform,
    client_version: "0.1.1",
    device_name: isAgent ? "Desktop Agent" : "Desktop Controller",
    capabilities,
  })
  if (sent) {
    state.lastRegisterAt = Date.now()
    state.lastRegisteredCapabilitiesKey = deviceCapabilitiesKey(capabilities)
  }
  return sent
}

function sendHeartbeat(options = {}) {
  syncControlsFromInputs()
  if (options.auto && Date.now() - state.lastHeartbeatAt < AUTO_SEND_DEDUP_MS) {
    return false
  }
  const sent = sendEnvelope("presence.heartbeat.req", {
    token: state.token || "",
    status: state.sessionId ? "busy" : "online",
    active_session_id: state.sessionId || null,
  })
  if (sent) {
    state.lastHeartbeatAt = Date.now()
  }
  return sent
}

function requestSession() {
  syncControlsFromInputs()
  if (!localCanInitiateRemoteControl()) {
    appendLog("本机未上报控制能力，不能主动发起会话")
    render()
    return
  }
  if (!state.targetDeviceId) {
    appendLog("请先在设备列表中选择目标设备")
    state.currentPage = "devices"
    render()
    return
  }

  const selectedTarget = state.relayDevices.find((device) => device.device_id === state.targetDeviceId) || null
  if (selectedTarget && !selectedTarget.is_target_candidate) {
    appendLog(`目标设备 ${selectedTarget.display_name || state.targetDeviceId} 当前不可连接`)
    render()
    return
  }

  if (!state.presenceReady) {
    appendLog("请先发送心跳并同步在线状态，再发起会话")
    render()
    return
  }
  sendEnvelope("session.request.req", {
    target_device_id: state.targetDeviceId,
    request_id: `req-${Date.now()}`,
    auth_mode: "consent_required",
  })
}

function buildSessionWindowUrl(device) {
  const targetDeviceId = `${device?.device_id || state.targetDeviceId || ""}`.trim()
  const deviceName = `${device?.display_name || querySessionDeviceName || targetDeviceId || "远程设备"}`.trim()
  const sessionUrl = new URL(window.location.href)
  sessionUrl.searchParams.set("session_window", "1")
  sessionUrl.searchParams.set("target_device_id", targetDeviceId)
  sessionUrl.searchParams.set("session_device_name", deviceName)
  sessionUrl.searchParams.set("ws_url", state.wsUrl || "")
  sessionUrl.searchParams.set("role", state.role || "controller")
  sessionUrl.searchParams.set("device_id", state.deviceId || "")
  return sessionUrl
}

async function openRemoteSessionWindow(device) {
  const targetDeviceId = `${device?.device_id || ""}`.trim()
  if (!targetDeviceId) {
    appendLog("缺少目标设备，无法打开远程窗口")
    render()
    return
  }
  if (device?.is_self) {
    appendLog("本机设备不可远程控制")
    render()
    return
  }
  if (!device?.is_target_candidate) {
    appendLog(`目标设备 ${device?.display_name || targetDeviceId} 当前不可远程`)
    render()
    return
  }

  state.targetDeviceId = targetDeviceId
  persistLocalSettings()
  const sessionUrl = buildSessionWindowUrl(device)
  const label = `session_${targetDeviceId.replace(/[^a-zA-Z0-9_/-]/g, "_")}`.slice(0, 64)

  try {
    if (tauriRuntime) {
      const existing = await WebviewWindow.getByLabel(label)
      if (existing) {
        await existing.setFocus()
        return
      }
      // 作者: long；远控会话独立成窗口，主窗口只承担设备发现和设置，避免调试/画面状态污染设备列表。
      const sessionWindow = new WebviewWindow(label, {
        url: `${sessionUrl.pathname}${sessionUrl.search}${sessionUrl.hash}`,
        title: `${device.display_name || targetDeviceId} - RemoteDesk`,
        width: 1180,
        height: 760,
        minWidth: 920,
        minHeight: 620,
        resizable: true,
        center: true,
      })
      sessionWindow.once("tauri://error", (event) => {
        appendLog(`打开远程窗口失败：${event?.payload || "unknown"}`)
        render()
      })
      return
    }
    window.open(sessionUrl.toString(), label, "width=1180,height=760,noopener,noreferrer")
  } catch (error) {
    appendLog(`打开远程窗口失败：${error?.message || "unknown error"}`)
    render()
  }
}

function endSession() {
  syncControlsFromInputs()
  if (!state.sessionId) {
    appendLog("当前没有 session，不能结束")
    render()
    return
  }
  releaseRemoteViewportPointer()
  releaseRemoteViewportKeys()
  sendEnvelope("session.end.req", {
    session_id: state.sessionId,
    reason: "user_end",
  }, state.sessionId)
}

function sendRemotePointerMove(point, options = {}) {
  if (!canSendInput()) {
    if (options.log !== false) {
      appendLog("当前不是控制端会话，不能发送输入")
    }
    render()
    return false
  }

  return sendEnvelope("input.mouse.move", {
    x: clamp01(point.x),
    y: clamp01(point.y),
    input_category: options.inputCategory || "",
  }, state.sessionId, {
    log: options.log !== false,
    logLine: options.logLine || `发送 input.mouse.move x=${clamp01(point.x).toFixed(2)} y=${clamp01(point.y).toFixed(2)}`,
  })
}

function sendRemotePointerButton(point, button, action, options = {}) {
  if (!canSendInput()) {
    if (options.log !== false) {
      appendLog("当前不是控制端会话，不能发送输入")
    }
    render()
    return false
  }

  return sendEnvelope("input.mouse.button", {
    button,
    action,
    x: clamp01(point.x),
    y: clamp01(point.y),
    input_category: options.inputCategory || "click",
  }, state.sessionId, {
    log: options.log !== false,
    logLine: options.logLine || `发送 ${button} ${action}`,
  })
}

function sendPointerSequence(x, y, button = "left") {
  const point = {
    x: clamp01(x),
    y: clamp01(y),
  }

  sendRemotePointerMove(point)
  sendRemotePointerButton(point, button, "down", { logLine: `发送 ${button} down` })
  sendRemotePointerButton(point, button, "up", { logLine: `发送 ${button} up` })
}

function sendDragSample() {
  if (!canSendInput()) {
    appendLog("Cannot send drag sample outside a controller session")
    render()
    return false
  }
  const start = { x: 0.36, y: 0.36 }
  const mid = { x: 0.52, y: 0.52 }
  const end = { x: 0.66, y: 0.62 }
  sendRemotePointerMove(start, { inputCategory: "drag", logLine: "send proof drag move start" })
  sendRemotePointerButton(start, "left", "down", { inputCategory: "drag", logLine: "send proof drag down" })
  sendRemotePointerMove(mid, { inputCategory: "drag", log: false })
  sendRemotePointerMove(end, { inputCategory: "drag", log: false })
  sendRemotePointerButton(end, "left", "up", { inputCategory: "drag", logLine: "send proof drag up" })
  return true
}

function sendE2EProofInputSequence() {
  if (!canSendInput()) {
    appendLog("Cannot send E2E proof sequence outside a controller session")
    render()
    return false
  }
  appendLog("Sending E2E proof input sequence: click / drag / keyboard / wheel")
  sendPointerSequence(0.5, 0.5)
  sendDragSample()
  sendKeyboardSample()
  sendScrollSample()
  scheduleLiveE2EProofReport("live_controller_e2e_proof_sequence", { force: true })
  return true
}

function sendKeyboardSample() {
  if (!canSendInput()) {
    appendLog("当前不是控制端会话，不能发送键盘事件")
    render()
    return
  }
  sendEnvelope("input.keyboard.key", {
    key_code: "KeyA",
    action: "down",
    modifiers: [],
    input_category: "keyboard",
  }, state.sessionId, { logLine: "发送 KeyA down" })
  sendEnvelope("input.keyboard.key", {
    key_code: "KeyA",
    action: "up",
    modifiers: [],
    input_category: "keyboard",
  }, state.sessionId, { logLine: "发送 KeyA up" })
}

function sendRemoteKeyboardKey(keyCode, action, modifiers = [], options = {}) {
  if (!canSendInput()) {
    if (options.log !== false) {
      appendLog("当前不是控制端会话，不能发送键盘事件")
    }
    render()
    return false
  }
  const normalizedKey = `${keyCode || ""}`.trim()
  const normalizedAction = `${action || ""}`.trim()
  if (!REMOTE_KEY_CODES.has(normalizedKey) || !["down", "up"].includes(normalizedAction)) {
    appendLog(`远程键盘事件无效：${normalizedKey || "-"} ${normalizedAction || "-"}`)
    render()
    return false
  }
  const normalizedModifiers = [...new Set((Array.isArray(modifiers) ? modifiers : [])
    .map((modifier) => `${modifier || ""}`.trim())
    .filter((modifier) => REMOTE_MODIFIER_CODES.has(modifier)))]
  return sendEnvelope("input.keyboard.key", {
    key_code: normalizedKey,
    action: normalizedAction,
    modifiers: normalizedModifiers,
    input_category: options.inputCategory || "keyboard",
  }, state.sessionId, {
    log: options.log !== false,
    logLine: options.logLine || `发送 ${normalizedKey} ${normalizedAction}${normalizedModifiers.length ? ` ${normalizedModifiers.join("+")}` : ""}`,
  })
}

function sendRemoteCtrlAltDel() {
  // 作者: long；会话工具栏的组合键必须走同一条远程输入链路，目标端原生执行器会在 Delete down/up 两侧压住并释放 Ctrl/Alt。
  const modifiers = ["ControlLeft", "AltLeft"]
  const downSent = sendRemoteKeyboardKey("Delete", "down", modifiers, {
    logLine: "发送 Ctrl+Alt+Del down",
  })
  const upSent = sendRemoteKeyboardKey("Delete", "up", modifiers, {
    logLine: "发送 Ctrl+Alt+Del up",
  })
  if (downSent || upSent) {
    scheduleLiveE2EProofReport("live_controller_ctrl_alt_del", { force: true })
  }
  return downSent && upSent
}

function sendScrollSample() {
  if (!canSendInput()) {
    appendLog("当前不是控制端会话，不能发送滚轮事件")
    render()
    return
  }
  sendEnvelope("input.wheel.scroll", {
    delta_x: 0,
    delta_y: -120,
    input_category: "wheel",
  }, state.sessionId, { logLine: "发送 input.wheel.scroll dy=-120" })
}

function normalizeRemotePointerButton(button) {
  switch (button) {
    case 0:
      return "left"
    case 1:
      return "middle"
    case 2:
      return "right"
    default:
      return ""
  }
}

function resetRemoteViewportPointer() {
  remoteViewportPointer.active = false
  remoteViewportPointer.pointerId = null
  remoteViewportPointer.button = "left"
  remoteViewportPointer.lastPoint = null
  remoteViewportPointer.lastMoveAt = 0
  remoteViewportPointer.dragging = false
}

function handleViewportPointerDown(event) {
  event.preventDefault()
  event.currentTarget?.focus?.({ preventScroll: true })
  if (!canSendInput()) {
    appendLog("当前不是控制端会话，点击画面不会发送输入")
    render()
    return
  }

  const button = normalizeRemotePointerButton(event.button)
  if (!button) {
    return
  }
  if (!remoteTargetSupportsPointerButton(button)) {
    appendLog("当前 macOS 目标暂不支持远端中键")
    render()
    return
  }
  const point = mapViewportPointer(event)
  if (!point) {
    const { meta } = currentViewportFrame()
    appendLog(meta?.width && meta?.height ? "请点击预览图区域" : "远端画面尚未准备好")
    render()
    return
  }

  remoteViewportPointer.active = true
  remoteViewportPointer.pointerId = event.pointerId
  remoteViewportPointer.button = button
  remoteViewportPointer.lastPoint = point
  remoteViewportPointer.lastMoveAt = Date.now()
  event.currentTarget?.setPointerCapture?.(event.pointerId)
  sendRemotePointerMove(point)
  sendRemotePointerButton(point, button, "down", { logLine: `发送 ${button} down` })
}

function handleViewportPointerMove(event) {
  if (!remoteViewportPointer.active || remoteViewportPointer.pointerId !== event.pointerId) {
    return
  }
  event.preventDefault()
  const point = mapViewportPointer(event)
  if (!point) {
    return
  }

  remoteViewportPointer.lastPoint = point
  const now = Date.now()
  if (now - remoteViewportPointer.lastMoveAt < REMOTE_POINTER_MOVE_THROTTLE_MS) {
    return
  }
  remoteViewportPointer.lastMoveAt = now
  remoteViewportPointer.dragging = true
  sendRemotePointerMove(point, { inputCategory: "drag", log: false })
}

function finishViewportPointer(event, cancelled = false) {
  if (!remoteViewportPointer.active || remoteViewportPointer.pointerId !== event.pointerId) {
    return
  }
  event.preventDefault()
  const point = mapViewportPointer(event) || remoteViewportPointer.lastPoint
  const button = remoteViewportPointer.button
  const inputCategory = remoteViewportPointer.dragging ? "drag" : "click"
  resetRemoteViewportPointer()
  event.currentTarget?.releasePointerCapture?.(event.pointerId)
  if (point) {
    sendRemotePointerMove(point, { inputCategory: inputCategory === "drag" ? "drag" : "", log: false })
    sendRemotePointerButton(point, button, "up", {
      inputCategory,
      logLine: cancelled ? `发送 ${button} cancel up` : `发送 ${button} up`,
    })
  }
}

function releaseRemoteViewportPointer() {
  if (!remoteViewportPointer.active) {
    return
  }
  const point = remoteViewportPointer.lastPoint
  const button = remoteViewportPointer.button
  const inputCategory = remoteViewportPointer.dragging ? "drag" : "click"
  resetRemoteViewportPointer()
  if (!canSendInput() || !point) {
    return
  }
  sendRemotePointerMove(point, { inputCategory: inputCategory === "drag" ? "drag" : "", log: false })
  sendRemotePointerButton(point, button, "up", { logLine: `发送 ${button} blur up` })
}

function normalizeRemoteWheelDelta(value) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric === 0) {
    return 0
  }
  const notches = Math.max(1, Math.round(Math.abs(numeric) / 100))
  const scaled = Math.sign(numeric) * REMOTE_WHEEL_DELTA_SCALE * notches
  return Math.max(-REMOTE_WHEEL_MAX_DELTA, Math.min(REMOTE_WHEEL_MAX_DELTA, scaled))
}

function handleViewportWheel(event) {
  if (!canSendInput()) {
    return
  }
  event.preventDefault()
  const deltaX = normalizeRemoteWheelDelta(event.deltaX)
  const deltaY = -normalizeRemoteWheelDelta(event.deltaY)
  if (deltaX === 0 && deltaY === 0) {
    return
  }
  sendEnvelope("input.wheel.scroll", {
    delta_x: deltaX,
    delta_y: deltaY,
    input_category: "wheel",
  }, state.sessionId, { logLine: `发送 input.wheel.scroll dx=${deltaX} dy=${deltaY}` })
}

function normalizeRemoteModifierCode(code) {
  const normalized = `${code || ""}`.trim()
  if (REMOTE_MODIFIER_CODES.has(normalized)) {
    return normalized
  }
  return ""
}

function syncRemoteModifierState(event, pressed) {
  const modifier = normalizeRemoteModifierCode(event.code)
  if (modifier) {
    if (pressed) {
      remoteViewportActiveModifiers.add(modifier)
    } else {
      remoteViewportActiveModifiers.delete(modifier)
    }
    return modifier
  }
  return ""
}

function collectRemoteKeyboardModifiers(event) {
  const modifiers = new Set(remoteViewportActiveModifiers)
  const hasShift = [...modifiers].some((modifier) => modifier.startsWith("Shift"))
  const hasControl = [...modifiers].some((modifier) => modifier.startsWith("Control"))
  const hasAlt = [...modifiers].some((modifier) => modifier.startsWith("Alt"))
  const hasMeta = [...modifiers].some((modifier) => modifier.startsWith("Meta"))
  if (event.shiftKey && !hasShift) {
    modifiers.add("ShiftLeft")
  }
  if (event.ctrlKey && !hasControl) {
    modifiers.add("ControlLeft")
  }
  if (event.altKey && !hasAlt) {
    modifiers.add("AltLeft")
  }
  if (event.metaKey && !hasMeta) {
    modifiers.add("MetaLeft")
  }
  return [...modifiers]
}

function handleViewportKey(event, action) {
  if (!canSendInput()) {
    return
  }
  const modifier = syncRemoteModifierState(event, action === "down")
  if (modifier) {
    event.preventDefault()
    return
  }

  const keyCode = `${event.code || ""}`.trim()
  if (!REMOTE_KEY_CODES.has(keyCode)) {
    return
  }
  if (action === "down" && event.repeat) {
    event.preventDefault()
    return
  }

  const modifiers = action === "down"
    ? collectRemoteKeyboardModifiers(event)
    : (remoteViewportKeyModifiers.get(keyCode) || collectRemoteKeyboardModifiers(event))
  event.preventDefault()
  sendEnvelope("input.keyboard.key", {
    key_code: keyCode,
    action,
    modifiers,
    input_category: "keyboard",
  }, state.sessionId, {
    logLine: `发送 ${keyCode} ${action}${modifiers.length ? ` ${modifiers.join("+")}` : ""}`,
  })

  if (action === "down") {
    remoteViewportKeyModifiers.set(keyCode, modifiers)
  } else {
    remoteViewportKeyModifiers.delete(keyCode)
  }
}

function releaseRemoteViewportKeys() {
  if (!canSendInput()) {
    remoteViewportKeyModifiers.clear()
    remoteViewportActiveModifiers.clear()
    return
  }
  for (const [keyCode, modifiers] of remoteViewportKeyModifiers.entries()) {
    sendEnvelope("input.keyboard.key", {
      key_code: keyCode,
      action: "up",
      modifiers,
      input_category: "keyboard",
    }, state.sessionId, { log: false })
  }
  remoteViewportKeyModifiers.clear()
  remoteViewportActiveModifiers.clear()
}

function toggleStream() {
  if (state.streamTimer) {
    stopFrameStream()
    return
  }
  void startFrameStream()
}

function mapViewportPointer(event) {
  const mediaElement = document.getElementById("remoteMediaVideo")
  const frameWidth = mediaElement instanceof HTMLVideoElement && mediaElement.videoWidth > 0
    ? mediaElement.videoWidth
    : currentViewportFrame().meta?.width
  const frameHeight = mediaElement instanceof HTMLVideoElement && mediaElement.videoHeight > 0
    ? mediaElement.videoHeight
    : currentViewportFrame().meta?.height
  if (!frameWidth || !frameHeight) {
    return null
  }

  const rect = event.currentTarget.getBoundingClientRect()
  if (!rect.width || !rect.height) {
    return null
  }

  const scale = Math.min(rect.width / frameWidth, rect.height / frameHeight)
  if (!scale || !Number.isFinite(scale)) {
    return null
  }

  const displayedWidth = frameWidth * scale
  const displayedHeight = frameHeight * scale
  const left = rect.left + (rect.width - displayedWidth) / 2
  const top = rect.top + (rect.height - displayedHeight) / 2
  const right = left + displayedWidth
  const bottom = top + displayedHeight

  if (event.clientX < left || event.clientX > right || event.clientY < top || event.clientY > bottom) {
    return null
  }

  return {
    x: clamp01((event.clientX - left) / displayedWidth),
    y: clamp01((event.clientY - top) / displayedHeight),
  }
}

function handleViewportPointer(event, button = "left") {
  event.preventDefault()
  if (!canSendInput()) {
    appendLog("当前不是控制端会话，点击画面不会发送输入")
    render()
    return
  }

  const point = mapViewportPointer(event)
  if (!point) {
    const { meta } = currentViewportFrame()
    appendLog(meta?.width && meta?.height ? "请点击预览图区域" : "远端画面尚未准备好")
    render()
    return
  }

  sendPointerSequence(point.x, point.y, button)
}

function render() {
  if (state.uiRuntimeMode === "react") {
    emitUiRevision()
    return
  }
  const app = document.getElementById("app")
  if (!app) {
    return
  }
  const previousPageShell = app.querySelector(".page-shell")
  const previousScrollTop = previousPageShell ? previousPageShell.scrollTop : 0
  const previousPageKey = state.currentPage
  const sessionSide = currentSessionSide()
  const { url: activeFrameUrl, meta: activeFrameMeta } = currentViewportFrame()
  const nativeCaptureMode = isTauri()
  const captureCapabilities = currentPlatformCaptureCapabilities()
  const hostInputCapabilities = currentPlatformHostInputCapabilities()
  const captureSource = currentCaptureSource()
  const captureFramePushEnabled = !nativeCaptureMode || canStreamCapturedFrames()
  const captureSupportLevel = captureCapabilities.support_level || "unknown"
  const hostInputSupportLevel = hostInputCapabilities.support_level || "unknown"
  const runtimeLabel = formatRuntimeLabel(state.runtime)
  const platformLabel = formatPlatformLabel(state.shellPlatform)
  const captureSupportLabel = formatCapabilityLevel(captureSupportLevel)
  const hostInputSupportLabel = formatCapabilityLevel(hostInputSupportLevel)
  const sessionCodecLabel = formatCodecLabel(state.defaultCodec || "-")
  const transportModeLabel = formatTransportModeLabel(state.sessionInfo?.transport?.mode || "-")
  const hostBridgeExecution = hostBridgeExecutionState()
  const hostBridgeStyles = hostBridgeToneStyles(hostBridgeExecution.tone)
  const connectionLabel = readyStateLabel()
  const tokenReady = Boolean(state.token)
  const heartbeatReady = Boolean(state.presenceReady)
  const sessionActive = Boolean(state.sessionId)
  const targetReady = Boolean(state.targetDeviceId)
  const roleLabel = roleDisplayName(state.role)
  const selectedTarget = state.relayDevices.find((device) => device.device_id === state.targetDeviceId) || null
  const idleControllerPerspective = idlePrefersControllerPerspective(selectedTarget)
  const onlineDevices = buildOnlineRelayDevices()
  const candidateAgents = state.relayDevices.filter((device) => device.is_target_candidate && device.status !== "offline")
  const viewportTitle = isControllerSession()
    ? "远端画面"
    : isAgentSession()
      ? "本地共享画面"
      : idleControllerPerspective
        ? "远端画面"
        : state.role === "agent"
        ? "本地预览"
        : "远端画面"
  const viewportHint = canSendInput()
    ? "在画面内可点击、拖动、滚轮和键盘输入。"
    : isAgentSession()
      ? (nativeCaptureMode
          ? (captureSource ? nativeFrameStreamingStatusMessage() : (state.captureError || "会话开始后将自动准备并推送桌面采集轨道。"))
          : (state.captureLabel ? `当前共享：${state.captureLabel}` : "会话开始后可选择真实屏幕共享。"))
      : idleControllerPerspective
        ? "请先完成连接、注册、心跳，再发起远控。"
        : state.role === "agent"
        ? (nativeCaptureMode
            ? (captureSource ? `采集源已准备：${state.captureLabel}` : "请先准备桌面采集源。")
            : (state.captureLabel ? `已选择共享屏幕：${state.captureLabel}` : "请先选择共享屏幕。"))
        : "请先完成连接、注册、心跳，再发起远控。"
  const agentPerspective = sessionActive ? sessionSide === "agent" : !idleControllerPerspective && state.role === "agent"
  const showViewportCard = !agentPerspective || NATIVE_SENDER_LOCAL_PREVIEW_ENABLED

  let gateTitle = "请先连接中继"
  let gateHint = "进入远控前需要先建立 WebSocket 连接。"
  let primaryActionId = "connectBtn"
  let primaryActionLabel = state.ws?.readyState === WebSocket.CONNECTING ? "连接中..." : "连接中继"
  let primaryActionDisabled = state.ws?.readyState === WebSocket.CONNECTING

  if (sessionActive) {
    gateTitle = isControllerSession() ? "远控进行中" : "正在被远控"
    gateHint = "会话已建立，可在右侧查看画面与状态。"
    primaryActionId = "endBtn"
    primaryActionLabel = "结束会话"
    primaryActionDisabled = false
  } else if (!connectionReady()) {
    gateTitle = "未连接"
    gateHint = "请先填写中继地址并连接。"
  } else if (!tokenReady) {
    gateTitle = "已连接 · 待注册"
    gateHint = "请先在中继服务注册本机身份。"
    primaryActionId = "registerBtn"
    primaryActionLabel = "注册设备"
    primaryActionDisabled = false
  } else if (!heartbeatReady) {
    gateTitle = "已注册 · 待心跳"
    gateHint = "发送心跳后会显示在线状态。"
    primaryActionId = "heartbeatBtn"
    primaryActionLabel = "发送心跳"
    primaryActionDisabled = false
  } else if (localCanInitiateRemoteControl()) {
    gateTitle = targetReady ? "可发起远控" : "请选择目标设备"
    gateHint = targetReady
      ? `目标就绪：${selectedTarget?.display_name || state.targetDeviceId}`
      : "请在设备列表选择在线受控端，或手动填写目标 ID。"
    primaryActionId = "sessionBtn"
    primaryActionLabel = "开始远控"
    primaryActionDisabled = !targetReady
  } else {
    gateTitle = "等待控制端"
    gateHint = nativeCaptureMode
      ? `采集：${captureSupportLabel} · 输入：${hostInputSupportLabel}`
      : "受控端待命中，可先选择共享屏幕。"
    primaryActionId = canSelectCaptureSource() ? "captureBtn" : "heartbeatBtn"
    primaryActionLabel = canSelectCaptureSource()
      ? (nativeCaptureMode ? (hasSelectedCaptureSource() ? "重新准备采集源" : "准备采集源") : (state.captureStream ? "重新选择共享屏幕" : "选择共享屏幕"))
      : "发送心跳"
    primaryActionDisabled = false
  }

  const selectedTargetMeta = selectedTarget
    ? `${selectedTarget.platform_label} · ${selectedTarget.role_label} · ${selectedTarget.status_label}`
    : (state.targetDeviceId ? "已手动填写目标设备 ID" : "未选择目标设备")
  const selectedTargetOffline = selectedTarget?.status === "offline"

  const deviceStatusClass = (tone) => {
    switch (tone) {
      case "success":
        return "tone-success"
      case "warn":
        return "tone-warn"
      default:
        return "tone-muted"
    }
  }

  const captureActionLabel = nativeCaptureMode
    ? (hasSelectedCaptureSource() ? "重新准备采集源" : "准备采集源")
    : (state.captureStream ? "重新选择共享屏幕" : "选择共享屏幕")
  const assistConnectLabel = sessionActive
    ? "结束远控"
    : !localCanInitiateRemoteControl()
      ? "本机不可发起远控"
      : !state.targetDeviceId
        ? "请选择目标"
      : selectedTargetOffline
        ? "目标离线"
        : "连接目标"
  const assistConnectDisabled = Boolean(!sessionActive && (!localCanInitiateRemoteControl() || !state.targetDeviceId || !state.presenceReady || selectedTargetOffline))
  const pageTitle = state.currentPage === "devices" ? "在线设备" : "中继设置"
  const pageSubtitle = state.currentPage === "devices"
    ? "浏览在线设备并选择目标发起远控。"
    : "配置中继地址与本机身份。"
  const firstFrameMs = state.firstFrameAt > 0 && state.sessionStartedAt > 0
    ? Math.max(0, state.firstFrameAt - state.sessionStartedAt)
    : -1
  const sessionStartText = sessionActive && state.sessionStartedAt > 0
    ? formatSessionStartTime(state.sessionStartedAt)
    : "-"
  const sessionDurationMs = sessionActive && state.sessionStartedAt > 0
    ? Math.max(0, Date.now() - state.sessionStartedAt)
    : -1
  const sessionDurationText = sessionDurationMs >= 0 ? formatSessionDuration(sessionDurationMs) : "-"
  const agentStats = state.agentStatsSnapshot || emptyAgentStatsSnapshot()
  const controllerStats = state.controllerStatsSnapshot || emptyControllerStatsSnapshot()
  const senderStatsActive = isAgentSession() && agentStats.sessionId === state.sessionId
  const receiverStatsActive = isControllerSession() && controllerStats.sessionId === state.sessionId
  const viewportStream = sessionSide === "controller" ? state.remoteMediaStream : state.localMediaStream
  const hasViewportStream = Boolean(viewportStream)
  const liveRenderFps = receiverStatsActive ? controllerStats.renderFps : -1
  const liveRecvKbps = receiverStatsActive ? controllerStats.recvKbps : -1
  const liveSendFps = senderStatsActive ? agentStats.sendFps : -1
  const liveSendKbps = senderStatsActive ? agentStats.sendKbps : -1
  const liveCandidatePath = senderStatsActive
    ? agentStats.candidatePath
    : receiverStatsActive
      ? controllerStats.candidatePath
      : "-"
  const liveCandidateTier = senderStatsActive
    ? agentStats.candidateTier
    : receiverStatsActive
      ? controllerStats.candidateTier
      : "-"
  const liveQualityHint = senderStatsActive
    ? inferAgentQualityHint(agentStats)
    : receiverStatsActive
      ? controllerStats.qualityHint
      : "-"
  const liveRenderFpsText = liveRenderFps >= 0 ? `${formatMetricValue(liveRenderFps)} fps` : "-"
  const liveRecvKbpsText = liveRecvKbps >= 0 ? `${formatMetricValue(liveRecvKbps)} kbps` : "-"
  const liveSendStatsText = liveSendFps >= 0 || liveSendKbps >= 0
    ? `${formatMetricValue(liveSendFps)} fps / ${formatMetricValue(liveSendKbps)} kbps`
    : "-"
  const liveAdaptiveModeText = senderStatsActive
    ? `${adaptiveSceneLabel(agentStats.sceneMode || "mixed")} · ${agentStats.profileLabel || agentStats.profileId || "-"}`
    : "-"
  const metricsRoleHint = senderStatsActive
    ? "受控端上行"
    : receiverStatsActive
      ? "控制端下行"
      : "空闲"

  const settingsPage = `
    <section class="stack-lg">
      <section class="card hero-card">
        <div class="hero-header">
          <div>
            <div class="eyebrow">桌面控制台</div>
            <h2 class="hero-title">中继设置</h2>
            <p class="hero-text">仅配置中继连接参数。</p>
          </div>
        </div>

        <div class="device-status-strip">
          <span class="chip chip-soft">${escapeHtml(gateTitle)}</span>
          <span class="chip ${connectionReady() ? "chip-success" : "chip-muted"}">${escapeHtml(connectionLabel)}</span>
          <span class="chip chip-soft">${escapeHtml(runtimeLabel)} / ${escapeHtml(platformLabel)}</span>
        </div>

        <div class="form-grid">
          <label class="field">
            <span>中继地址</span>
            <input id="assistWsUrl" value="${escapeHtml(state.wsUrl)}" placeholder="ws://localhost:18081/ws" />
          </label>
        </div>

        <div class="action-row">
          <button id="connectBtn" ${state.ws?.readyState === WebSocket.CONNECTING ? "disabled" : ""}>${escapeHtml(state.ws?.readyState === WebSocket.CONNECTING ? "连接中..." : "连接")}</button>
          <button id="registerBtn" class="secondary-btn" ${!connectionReady() ? "disabled" : ""}>注册</button>
          <button id="heartbeatBtn" class="secondary-btn" ${!connectionReady() ? "disabled" : ""}>心跳</button>
          <button id="endBtn" class="danger-btn" ${!sessionActive ? "disabled" : ""}>结束会话</button>
        </div>
      </section>
    </section>
  `

  const allDevicesPage = `
    <section class="stack-lg">
      <section class="card grouped-card">
        <div class="section-head compact-list-head">
          <div>
            <h3>在线设备列表</h3>
          </div>
          <span class="chip chip-soft">${escapeHtml(String(onlineDevices.length))} 台</span>
        </div>
        <div class="device-list-grid">
          ${onlineDevices.length ? onlineDevices.map((device) => `
            <article class="device-row-card ${device.device_id === state.targetDeviceId ? "device-row-active" : ""}" data-device-row-id="${escapeHtml(device.device_id)}">
              <div class="device-row-main">
                <div class="device-icon-badge">${escapeHtml(device.kind === "mobile" ? "手" : "电")}</div>
                <div class="stack-xs">
                  <div class="device-row-title">
                    <strong>${escapeHtml(device.display_name)}</strong>
                    ${device.is_self ? '<span class="inline-tag">本机</span>' : ""}
                  </div>
                  <div class="device-row-meta">${escapeHtml(device.kind === "mobile" ? "手机" : device.kind === "desktop" ? "电脑" : "其他")} · ${escapeHtml(device.platform_label)} · ${escapeHtml(device.device_id)}</div>
                </div>
              </div>
              <div class="device-row-side">
                <span class="chip chip-compact ${escapeHtml(device.is_self && isAgentSession() && state.sessionId ? "tone-warn" : deviceStatusClass(device.status_tone))}">${escapeHtml(device.is_self && isAgentSession() && state.sessionId ? "被控" : device.status_label)}</span>
                ${device.is_self
                  ? (isAgentSession() && state.sessionId
                      ? '<button class="danger-btn device-row-action" data-end-control="1">断开控制</button>'
                      : '<button class="secondary-btn device-row-action" disabled>本机</button>')
                  : `<button class="secondary-btn device-row-action" data-select-device-id="${escapeHtml(device.device_id)}" ${device.is_target_candidate ? "" : "disabled"}>${device.is_target_candidate ? "设为目标" : "不可用"}</button>`}
              </div>
            </article>
          `).join("") : `<div class="empty-state">暂无在线设备，请保持中继连接并等待设备心跳。</div>`}
        </div>
      </section>

      <section class="card grouped-card">
        <div class="section-head compact-head">
          <div>
            <h3>会话与链路</h3>
          </div>
        </div>
        <div class="kv-two-col">
          <div class="kv-item"><span>角色</span><strong>${escapeHtml(roleLabel)}</strong></div>
          <div class="kv-item"><span>会话 ID</span><strong>${escapeHtml(state.sessionId || "未开始")}</strong></div>
          <div class="kv-item"><span>会话开始</span><strong>${escapeHtml(sessionStartText)}</strong></div>
          <div class="kv-item"><span>会话时长</span><strong>${escapeHtml(sessionDurationText)}</strong></div>
          <div class="kv-item"><span>传输</span><strong>${escapeHtml(transportModeLabel)}</strong></div>
          <div class="kv-item"><span>编码</span><strong>${escapeHtml(sessionCodecLabel)}</strong></div>
          <div class="kv-item"><span>采集源</span><strong>${escapeHtml(state.captureLabel || "未准备")}</strong></div>
          <div class="kv-item"><span>最后回执</span><strong>${escapeHtml(state.lastAck || "-")}</strong></div>
          <div class="kv-item"><span>目标执行结果</span><strong>${escapeHtml(formatRemoteInputResult())}</strong></div>
          <div class="kv-item"><span>桥接执行器</span><strong>${escapeHtml(formatBackendLabel(hostBridgeExecution.executor))}</strong></div>
          <div class="kv-item"><span>桥接状态码</span><strong>${escapeHtml(hostBridgeExecution.statusCode)}</strong></div>
          <div class="kv-item"><span>桥接输入次数</span><strong>${escapeHtml(String(hostBridgeExecution.inputCount))}</strong></div>
          <div class="kv-item"><span>桥接摘要</span><strong>${escapeHtml(hostBridgeExecution.summary)}</strong></div>
          <div class="kv-item"><span>首帧耗时</span><strong>${escapeHtml(firstFrameMs >= 0 ? `${firstFrameMs} ms` : "-")}</strong></div>
          <div class="kv-item"><span>渲染 FPS</span><strong>${escapeHtml(liveRenderFpsText)}</strong></div>
          <div class="kv-item"><span>接收码率</span><strong>${escapeHtml(liveRecvKbpsText)}</strong></div>
          <div class="kv-item"><span>发送 FPS / 码率</span><strong>${escapeHtml(liveSendStatsText)}</strong></div>
          <div class="kv-item"><span>调度场景 / 档位</span><strong>${escapeHtml(liveAdaptiveModeText)}</strong></div>
          <div class="kv-item"><span>候选路径 / 分级</span><strong>${escapeHtml(`${liveCandidatePath || "-"} / ${liveCandidateTier || "-"}`)}</strong></div>
          <div class="kv-item"><span>质量判定</span><strong>${escapeHtml(qualityHintLabel(liveQualityHint))}</strong></div>
        </div>
      </section>

      ${showViewportCard ? `<section class="card viewport-card">
          <div class="section-head compact-head">
            <div>
              <h3>${escapeHtml(viewportTitle)}</h3>
              <p>${escapeHtml(viewportHint)}</p>
            </div>
            <span class="chip chip-soft">${escapeHtml(sessionSide === "controller" ? "控制端" : sessionSide === "agent" ? "受控端" : "空闲")}</span>
          </div>
          <div id="remoteViewport" class="product-viewport ${canSendInput() ? "clickable" : ""}" tabindex="${canSendInput() ? "0" : "-1"}">
            ${hasViewportStream
              ? `<video id="remoteMediaVideo" class="viewport-image ${state.webrtcState === "connected" ? "is-active" : ""}" autoplay playsinline muted></video>`
              : ""}
            ${activeFrameUrl
              ? `<img src="${escapeHtml(activeFrameUrl)}" alt="${escapeHtml(sessionSide === "agent" ? "本地共享画面" : "远端画面")}" class="viewport-image" />`
              : !hasViewportStream ? `<div class="viewport-placeholder">${escapeHtml(viewportHint)} · WebRTC 状态：${escapeHtml(state.webrtcState || "idle")}</div>` : ""}
          </div>
          <div class="viewport-meta">画面：${escapeHtml(formatFrameMeta(activeFrameMeta))}</div>
      </section>` : ""}
    </section>
  `

  app.innerHTML = `
    <div class="desktop-shell">
      <aside class="sidebar-shell">
        <div class="sidebar-brand">
          <div class="brand-title">远控桌面端</div>
          <div class="brand-subtitle">稳定版</div>
        </div>

        <div class="sidebar-group">
          <button id="navAllDevices" class="nav-item ${state.currentPage === "devices" ? "nav-item-active" : ""}">
            <span>设备</span>
          </button>
          <button id="navStartAssist" class="nav-item ${state.currentPage === "settings" ? "nav-item-active" : ""}">
            <span>设置</span>
          </button>
        </div>

      </aside>

      <section class="main-shell">
        <header class="topbar-shell">
          <div class="topbar-leading">
            <div>
              <h1>${escapeHtml(pageTitle)}</h1>
              <p>${escapeHtml(pageSubtitle)}</p>
            </div>
          </div>
        </header>

        <div class="page-shell">
          ${state.currentPage === "devices" ? allDevicesPage : settingsPage}

        </div>
      </section>
    </div>
  `

  const bindClick = (id, handler) => {
    const element = document.getElementById(id)
    if (element) {
      element.onclick = handler
    }
  }

  const bindInput = (id, handler, eventName = "input") => {
    const element = document.getElementById(id)
    if (element) {
      element[eventName === "change" ? "onchange" : "oninput"] = handler
    }
  }

  bindClick("navStartAssist", () => {
    state.currentPage = "settings"
    render()
  })
  bindClick("navAllDevices", () => {
    state.currentPage = "devices"
    render()
    if (connectionReady()) {
      void refreshRelayDevices({ force: true })
    }
  })
  bindInput("assistWsUrl", (event) => {
    state.wsUrl = event.target.value.trim()
    persistLocalSettings()
    scheduleAutoConnectFromSettings()
  })
  bindInput("assistWsUrl", (event) => {
    state.wsUrl = event.target.value.trim()
    persistLocalSettings()
    triggerAutoConnectFromSettings()
  }, "change")
  bindClick("connectBtn", connect)
  bindClick("registerBtn", sendRegister)
  bindClick("heartbeatBtn", sendHeartbeat)
  bindClick("endBtn", endSession)

  document.querySelectorAll("[data-select-device-id]").forEach((element) => {
    element.onclick = () => {
      void selectRelayDevice(element.getAttribute("data-select-device-id"))
    }
  })
  document.querySelectorAll("[data-end-control]").forEach((element) => {
    element.onclick = () => {
      endSession()
    }
  })

  const remoteViewport = document.getElementById("remoteViewport")
  if (remoteViewport) {
    remoteViewport.onpointerdown = handleViewportPointerDown
    remoteViewport.onpointermove = handleViewportPointerMove
    remoteViewport.onpointerup = (event) => finishViewportPointer(event)
    remoteViewport.onpointercancel = (event) => finishViewportPointer(event, true)
    remoteViewport.onwheel = handleViewportWheel
    remoteViewport.onkeydown = (event) => handleViewportKey(event, "down")
    remoteViewport.onkeyup = (event) => handleViewportKey(event, "up")
    remoteViewport.onblur = () => {
      releaseRemoteViewportPointer()
      releaseRemoteViewportKeys()
    }
    remoteViewport.oncontextmenu = (event) => event.preventDefault()
  }
  const nextPageShell = app.querySelector(".page-shell")
  if (nextPageShell && previousScrollTop > 0 && state.currentPage === previousPageKey) {
    nextPageShell.scrollTop = previousScrollTop
    window.requestAnimationFrame(() => {
      const shell = app.querySelector(".page-shell")
      if (shell && state.currentPage === previousPageKey) {
        shell.scrollTop = previousScrollTop
      }
    })
  }
  syncViewportMediaElement()
}

function useUiRuntimeRevision() {
  useSyncExternalStore(
    subscribeUiRevision,
    getUiRevisionSnapshot,
    getUiRevisionSnapshot,
  )
}

function platformBadgeLabel(platform) {
  switch (`${platform || ""}`.toLowerCase()) {
    case "windows":
      return "W"
    case "android":
      return "A"
    case "macos":
      return "M"
    case "linux":
      return "L"
    default:
      return "D"
  }
}

function statusDotClass(status) {
  switch (`${status || ""}`.toLowerCase()) {
    case "online":
      return "online"
    case "busy":
      return "busy"
    default:
      return "offline"
  }
}

function runSessionPrimaryAction() {
  if (!connectionReady()) {
    connect()
    return
  }
  if (!state.token) {
    sendRegister()
    return
  }
  if (!state.presenceReady) {
    sendHeartbeat()
    return
  }
  requestSession()
}

function DesktopReactApp() {
  useUiRuntimeRevision()
  const [, setSessionClockTick] = useState(0)

  useEffect(() => {
    syncViewportMediaElement()
  }, [state.currentPage, state.sessionId, state.webrtcState, state.remoteMediaStream, state.localMediaStream])

  useEffect(() => {
    if (!state.sessionId || state.sessionStartedAt <= 0) {
      return undefined
    }
    const timer = window.setInterval(() => {
      setSessionClockTick((value) => value + 1)
    }, 1000)
    return () => {
      window.clearInterval(timer)
    }
  }, [state.sessionId, state.sessionStartedAt])

  const sessionSide = currentSessionSide()
  const selectedTarget = state.relayDevices.find((device) => device.device_id === state.targetDeviceId) || null
  const onlineDevices = useMemo(() => buildOnlineRelayDevices(), [state.relayDevices, state.sessionId, state.targetDeviceId])
  const roleLabel = roleDisplayName(state.role)
  const sessionActive = Boolean(state.sessionId)
  const selectedTargetOffline = selectedTarget?.status === "offline"
  const selectedTargetName = selectedTarget?.display_name || state.targetDeviceId || "未选择目标设备"
  const controllerDeviceId = `${state.sessionInfo?.controller_device_id || ""}`.trim()
  const controllerDevice = controllerDeviceId
    ? (state.relayDevices.find((device) => device.device_id === controllerDeviceId) || null)
    : null
  const controllerDeviceName = controllerDevice?.display_name || controllerDeviceId || "未识别控制端"
  const idleControllerPerspective = idlePrefersControllerPerspective(selectedTarget)
  const assistConnectDisabled = Boolean(!sessionActive && (!localCanInitiateRemoteControl() || !state.targetDeviceId || !state.presenceReady || selectedTargetOffline))
  const assistConnectLabel = sessionActive
    ? "结束远控"
    : !localCanInitiateRemoteControl()
      ? "本机不可发起远控"
      : !state.targetDeviceId
        ? "请选择目标"
      : selectedTargetOffline
        ? "目标离线"
        : "开始远控"
  const transportModeLabel = formatTransportModeLabel(state.sessionInfo?.transport?.mode || "-")
  const sessionCodecLabel = formatCodecLabel(state.defaultCodec || "-")
  const hostBridgeExecution = hostBridgeExecutionState()
  const agentStats = state.agentStatsSnapshot || emptyAgentStatsSnapshot()
  const controllerStats = state.controllerStatsSnapshot || emptyControllerStatsSnapshot()
  const senderStatsActive = isAgentSession() && agentStats.sessionId === state.sessionId
  const receiverStatsActive = isControllerSession() && controllerStats.sessionId === state.sessionId
  const liveRenderFps = receiverStatsActive ? controllerStats.renderFps : -1
  const liveRecvKbps = receiverStatsActive ? controllerStats.recvKbps : -1
  const liveSendFps = senderStatsActive ? agentStats.sendFps : -1
  const liveSendKbps = senderStatsActive ? agentStats.sendKbps : -1
  const liveCandidatePath = senderStatsActive
    ? agentStats.candidatePath
    : receiverStatsActive
      ? controllerStats.candidatePath
      : "-"
  const liveCandidateTier = senderStatsActive
    ? agentStats.candidateTier
    : receiverStatsActive
      ? controllerStats.candidateTier
      : "-"
  const liveQualityHint = senderStatsActive
    ? inferAgentQualityHint(agentStats)
    : receiverStatsActive
      ? controllerStats.qualityHint
      : "-"
  const firstFrameMs = state.firstFrameAt > 0 && state.sessionStartedAt > 0
    ? Math.max(0, state.firstFrameAt - state.sessionStartedAt)
    : -1
  const sessionStartText = sessionActive && state.sessionStartedAt > 0
    ? formatSessionStartTime(state.sessionStartedAt)
    : "-"
  const sessionDurationMs = sessionActive && state.sessionStartedAt > 0
    ? Math.max(0, Date.now() - state.sessionStartedAt)
    : -1
  const sessionDurationText = sessionDurationMs >= 0 ? formatSessionDuration(sessionDurationMs) : "-"
  const viewportStream = sessionSide === "controller" ? state.remoteMediaStream : state.localMediaStream
  const hasViewportStream = Boolean(viewportStream)
  const { url: activeFrameUrl, meta: activeFrameMeta } = currentViewportFrame()
  const viewportTitle = isControllerSession()
    ? "远端画面"
    : isAgentSession()
      ? "本地共享画面"
      : idleControllerPerspective
        ? "远端画面"
        : state.role === "agent"
        ? "本地预览"
        : "远端画面"
  const viewportHint = canSendInput()
    ? "在画面内点击可发送远端鼠标，右键发送远端右键。"
    : isAgentSession()
      ? "正在共享本地画面。"
      : idleControllerPerspective
        ? "请先连接并建立会话。"
      : "请先连接并建立会话。"
  const agentPerspective = sessionActive ? sessionSide === "agent" : !idleControllerPerspective && state.role === "agent"
  const showViewportCard = !agentPerspective || NATIVE_SENDER_LOCAL_PREVIEW_ENABLED
  const pageTitle = state.currentPage === "devices" ? "在线设备" : "中继设置"
  const pageSubtitle = state.currentPage === "devices" ? "浏览在线设备并发起远控" : "配置中继地址并连接"
  const localPreviewFpsMetric = resolveLocalPreviewFpsMetric(agentStats)
  const localPreviewFpsText = localPreviewFpsMetric.text
  const showLocalPreviewMetric = NATIVE_SENDER_LOCAL_PREVIEW_ENABLED
  const peerDeviceLabel = agentPerspective ? "控制端设备" : "目标设备"
  const peerDeviceValue = agentPerspective ? controllerDeviceName : selectedTargetName
  const showCandidateMetric = senderStatsActive || receiverStatsActive
  const windowsSelfTestReport = state.windowsSelfTestReport || null
  const windowsSelfTestChecks = Array.isArray(windowsSelfTestReport?.checks) ? windowsSelfTestReport.checks : []
  const windowsSelfTestFailures = windowsSelfTestFailedChecks(windowsSelfTestReport)
  const windowsSelfTestAvailable = isTauri() && ["windows", "macos"].includes(state.shellPlatform)
  const windowsSelfTestDisabled = state.windowsSelfTestRunning || sessionActive || !windowsSelfTestAvailable
  const windowsSelfTestTone = state.windowsSelfTestRunning
    ? "chip-muted"
    : windowsSelfTestReport
      ? (windowsSelfTestReport.ok ? "chip-success" : "tone-warn")
      : "chip-muted"
  const windowsSelfTestLabel = state.windowsSelfTestRunning
    ? "运行中"
    : windowsSelfTestReport
      ? (windowsSelfTestReport.ok ? "通过" : "需处理")
      : "未运行"
  const windowsSelfTestSummary = state.windowsSelfTestRunning
    ? "正在检查桌面平台能力、权限、采集首帧、MJPEG 端点、输入会话守卫和原生 sender。"
    : state.windowsSelfTestError
      ? state.windowsSelfTestError
      : windowsSelfTestReport
        ? (windowsSelfTestReport.ok
            ? `采集 ${windowsSelfTestReport.frame_width || 0}x${windowsSelfTestReport.frame_height || 0}，sender ${windowsSelfTestReport.native_sender_probe_frame_count || 0} 帧，耗时 ${windowsSelfTestReport.duration_ms || 0} ms`
            : `失败项：${windowsSelfTestFailures.map((check) => check.name).join(", ") || "unknown"}`)
        : windowsSelfTestAvailable
          ? "在当前桌面壳层中运行一次无会话自检。"
          : "仅 Windows/macOS Tauri 桌面壳层可运行。"

  const e2eProofSnapshot = state.e2eProofSnapshot || null
  const e2eProofRoutes = Array.isArray(e2eProofSnapshot?.routes) ? e2eProofSnapshot.routes : []
  const e2eProofCompleteText = e2eProofSnapshot
    ? `${e2eProofSnapshot.target_routes_complete || 0}/${e2eProofSnapshot.target_routes_total || 0}`
    : "-"
  const e2eProofTone = state.e2eProofLoading
    ? "chip-muted"
    : state.e2eProofResetting
      ? "chip-muted"
    : e2eProofSnapshot?.complete
      ? "chip-success"
      : state.e2eProofError
        ? "tone-warn"
        : "chip-muted"
  const e2eProofLabel = state.e2eProofLoading
    ? "loading"
    : state.e2eProofResetting
      ? "resetting"
    : e2eProofSnapshot?.complete
      ? "complete"
      : state.e2eProofError
        ? "error"
        : "not checked"

  const visibleDevices = state.relayDevices.length ? state.relayDevices : onlineDevices
  const onlineCount = visibleDevices.filter((device) => device.status !== "offline").length
  const sessionDeviceName = querySessionDeviceName || selectedTargetName || "远程设备"
  const sessionPrimaryLabel = sessionActive
    ? "结束会话"
    : !connectionReady()
      ? "连接中继"
      : !state.token
        ? "注册本机"
        : !state.presenceReady
          ? "发送心跳"
          : "开始远控"
  const sessionPrimaryDisabled = !sessionActive && state.targetDeviceId.trim() === "" && connectionReady() && state.token && state.presenceReady

  if (sessionWindowMode) {
    return (
      <div className="session-window-shell">
        <header className="session-titlebar">
          <div className="session-title">
            <span className="brand-mark-mini">RD</span>
            <strong>{sessionDeviceName} - 远程会话</strong>
          </div>
          <div className="session-status-strip">
            <span className={`status-pill ${connectionReady() ? "status-pill-good" : ""}`}>{readyStateLabel()}</span>
            <span className="status-pill">{state.sessionId || "未开始"}</span>
          </div>
        </header>

        <section className="session-toolbar">
          <button
            className={sessionActive ? "danger-btn" : "session-primary-btn"}
            disabled={sessionPrimaryDisabled}
            onClick={() => {
              if (sessionActive) {
                endSession()
              } else {
                runSessionPrimaryAction()
              }
            }}
          >
            {sessionPrimaryLabel}
          </button>
          <button
            className="session-tool-btn"
            disabled={!sessionActive}
            onClick={() => sendRemoteCtrlAltDel()}
          >
            Ctrl+Alt+Del
          </button>
          <button
            className="session-tool-btn"
            disabled={!sessionActive}
            onClick={() => void sendClipboardToRemote()}
          >
            剪贴板
          </button>
          <button
            className="session-tool-btn"
            disabled={!sessionActive}
            onClick={() => document.getElementById("sessionFileInput")?.click()}
          >
            文件
          </button>
          <button className="session-tool-btn" disabled={!sessionActive}>截图</button>
          <button className="session-tool-btn" disabled={!sessionActive}>视图</button>
          <input
            id="sessionFileInput"
            type="file"
            hidden
            onChange={(event) => {
              const file = event.target.files?.[0]
              if (file) {
                void sendFileToRemote(file)
              } else {
                updateFileTransferStatus("文件：已取消选择")
              }
              event.target.value = ""
            }}
          />
          <div className="session-toolbar-spacer" />
          <span className="metric-inline">{transportModeLabel}</span>
          {showCandidateMetric ? <span className="metric-inline">{`${liveCandidatePath || "-"} / ${liveCandidateTier || "-"}`}</span> : null}
          <span className="metric-inline">{firstFrameMs >= 0 ? `${firstFrameMs} ms 首帧` : "等待首帧"}</span>
          <span className="metric-inline">{state.clipboardStatus}</span>
          <span className="metric-inline">{state.fileTransferStatus}</span>
        </section>

        <section className="session-canvas">
          <div
            id="remoteViewport"
            className={`session-viewport ${canSendInput() ? "clickable" : ""}`}
            tabIndex={canSendInput() ? 0 : -1}
            onPointerDown={(event) => handleViewportPointerDown(event)}
            onPointerMove={(event) => handleViewportPointerMove(event)}
            onPointerUp={(event) => finishViewportPointer(event)}
            onPointerCancel={(event) => finishViewportPointer(event, true)}
            onWheel={(event) => handleViewportWheel(event)}
            onKeyDown={(event) => handleViewportKey(event, "down")}
            onKeyUp={(event) => handleViewportKey(event, "up")}
            onBlur={() => {
              releaseRemoteViewportPointer()
              releaseRemoteViewportKeys()
            }}
            onContextMenu={(event) => event.preventDefault()}
          >
            {hasViewportStream ? <video id="remoteMediaVideo" className={`viewport-image ${state.webrtcState === "connected" ? "is-active" : ""}`} autoPlay playsInline muted /> : null}
            {activeFrameUrl
              ? <img src={activeFrameUrl} alt="远端画面" className="viewport-image" />
              : (!hasViewportStream ? <div className="session-placeholder">{viewportHint}</div> : null)}
          </div>
          <aside className="floating-session-panel">
            <button type="button" disabled={!sessionActive}>1x</button>
            <button type="button" disabled={!sessionActive}>键盘</button>
            <button type="button" disabled={!sessionActive}>更多</button>
          </aside>
        </section>

        <footer className="session-footer">
          <span>画面：{formatFrameMeta(activeFrameMeta)}</span>
          <span>输入：{formatBackendLabel(hostBridgeExecution.executor)}</span>
          <span>质量：{qualityHintLabel(liveQualityHint)}</span>
          <span>会话：{sessionDurationText}</span>
        </footer>
      </div>
    )
  }

  return (
    <div className="desktop-shell product-desktop-shell">
      <aside className="sidebar-shell">
        <div className="sidebar-brand">
          <div className="brand-row">
            <span className="brand-mark-mini">RD</span>
            <div>
              <div className="brand-title">RemoteDesk</div>
              <div className="brand-subtitle">桌面端</div>
            </div>
          </div>
        </div>
        <div className="sidebar-group">
          <button
            id="navAllDevices"
            className={`nav-item ${state.currentPage === "devices" ? "nav-item-active" : ""}`}
            onClick={() => {
              state.currentPage = "devices"
              render()
              if (connectionReady()) {
                void refreshRelayDevices({ force: true })
              }
            }}
          >
            <span className="nav-dot-icon">▣</span>
            <span>设备</span>
          </button>
          <button
            id="navStartAssist"
            className={`nav-item ${state.currentPage === "settings" ? "nav-item-active" : ""}`}
            onClick={() => {
              state.currentPage = "settings"
              render()
            }}
          >
            <span className="nav-dot-icon">⚙</span>
            <span>设置</span>
          </button>
        </div>
        <div className="sidebar-status">
          <span className={`status-dot ${connectionReady() ? "online" : "offline"}`} />
          <span>{connectionReady() ? "中继在线" : "中继未连接"}</span>
        </div>
      </aside>

      <section className="main-shell">
        <header className="topbar-shell">
          <div className="topbar-leading">
            <div>
              <h1>{pageTitle}</h1>
              <p>{pageSubtitle}</p>
            </div>
          </div>
          <div className="topbar-actions">
            <button className="secondary-btn" onClick={() => void refreshRelayDevices({ force: true })}>刷新</button>
            <button className="primary-btn" onClick={() => { state.currentPage = "settings"; render() }}>设置连接</button>
          </div>
        </header>

        <div className="page-shell">
          {state.currentPage === "settings" ? (
            <section className="settings-page-grid">
              <section className="settings-panel">
                <div className="section-head compact-head">
                  <div>
                    <h3>中继服务</h3>
                    <p>设备发现与会话信令走这里，远控画面仍使用 WebRTC。</p>
                  </div>
                  <span className={`chip ${connectionReady() ? "chip-success" : "chip-muted"}`}>{readyStateLabel()}</span>
                </div>
                <div className="form-grid settings-form-grid">
                  <label className="field">
                    <span>中继地址</span>
                    <input
                      id="assistWsUrl"
                      value={state.wsUrl}
                      placeholder="ws://localhost:18081/ws"
                      onInput={(event) => {
                        state.wsUrl = `${event.target.value || ""}`.trim()
                        persistLocalSettings()
                        scheduleAutoConnectFromSettings()
                        render()
                      }}
                      onBlur={(event) => {
                        state.wsUrl = `${event.target.value || ""}`.trim()
                        persistLocalSettings()
                        triggerAutoConnectFromSettings()
                        render()
                      }}
                    />
                  </label>
                  <label className="field">
                    <span>本机角色</span>
                    <select
                      value={state.role}
                      onChange={(event) => {
                        void updateRole(event.target.value)
                      }}
                    >
                      <option value="controller">控制端</option>
                      <option value="agent">受控端</option>
                    </select>
                  </label>
                </div>
                <div className="action-row">
                  <button className="primary-btn" id="connectBtn" disabled={state.ws?.readyState === WebSocket.CONNECTING} onClick={() => connect()}>
                    {state.ws?.readyState === WebSocket.CONNECTING ? "连接中..." : "连接"}
                  </button>
                  <button id="registerBtn" className="secondary-btn" disabled={!connectionReady()} onClick={() => sendRegister()}>
                    注册
                  </button>
                  <button id="heartbeatBtn" className="secondary-btn" disabled={!connectionReady()} onClick={() => sendHeartbeat()}>
                    心跳
                  </button>
                  <button id="endBtn" className="danger-btn" disabled={!sessionActive} onClick={() => endSession()}>
                    结束会话
                  </button>
                </div>
              </section>

              <section className="settings-panel">
                <div className="section-head compact-head">
                  <div>
                    <h3>远控偏好</h3>
                    <p>这些设置先落 UI 结构，后续可接入真实画质策略。</p>
                  </div>
                </div>
                <div className="form-grid settings-form-grid">
                  <label className="field">
                    <span>默认画质</span>
                    <select defaultValue="smooth">
                      <option value="smooth">流畅优先 · 720p / 24fps</option>
                      <option value="balanced">均衡</option>
                      <option value="clear">清晰优先</option>
                    </select>
                  </label>
                  <label className="toggle-row">
                    <span><strong>请求前确认</strong><small>被控端需要显式接受远控请求</small></span>
                    <input type="checkbox" defaultChecked />
                  </label>
                </div>
              </section>

              <section className="settings-panel">
                <div className="section-head compact-head">
                  <div>
                    <h3>访问安全</h3>
                    <p>把授权、剪贴板和输入权限从主设备页中拆出来。</p>
                  </div>
                </div>
                <div className="security-row">
                  <span className="security-mark">✓</span>
                  <div>
                    <strong>一次性访问码</strong>
                    <small>{state.token ? "已注册，可用于本轮会话" : "注册后生成"}</small>
                  </div>
                  <button className="secondary-btn" disabled={!connectionReady()} onClick={() => sendRegister()}>刷新</button>
                </div>
                <label className="toggle-row">
                  <span><strong>允许剪贴板同步</strong><small>仅在远程会话窗口中生效</small></span>
                  <input type="checkbox" defaultChecked />
                </label>
              </section>

              <section className="settings-panel">
                <div className="section-head compact-head">
                  <div>
                    <h3>开发者</h3>
                    <p>调试信息默认不展示，开启后才显示自检、Proof 和日志。</p>
                  </div>
                </div>
                <label className="toggle-row">
                  <span><strong>调试模式</strong><small>显示诊断入口与最近日志</small></span>
                  <input
                    type="checkbox"
                    checked={state.uiDebugView}
                    onChange={(event) => {
                      state.uiDebugView = event.target.checked
                      persistLocalSettings()
                      render()
                    }}
                  />
                </label>
              </section>

              {state.uiDebugView ? (
                <section className="settings-panel debug-panel-wide">
                  <div className="section-head compact-head">
                    <div>
                      <h3>调试面板</h3>
                      <p>这些内容只在设置页调试模式里出现，不再污染设备列表。</p>
                    </div>
                    <span className={`chip ${e2eProofTone}`}>{e2eProofLabel}</span>
                  </div>

                  <div className="debug-grid">
                    <section className="debug-card">
                      <div className="section-head compact-head">
                        <div>
                          <h4>桌面自检</h4>
                          <p>{windowsSelfTestSummary}</p>
                        </div>
                        <span className={`chip ${windowsSelfTestTone}`}>{windowsSelfTestLabel}</span>
                      </div>
                      <div className="action-row">
                        <button
                          className="secondary-btn"
                          disabled={windowsSelfTestDisabled}
                          onClick={() => {
                            void runWindowsSelfTest()
                          }}
                        >
                          {state.windowsSelfTestRunning ? "检测中..." : "运行自检"}
                        </button>
                      </div>
                    </section>

                    <section className="debug-card">
                      <div className="section-head compact-head">
                        <div>
                          <h4>E2E Proof</h4>
                          <p>{state.e2eProofError || `目标路由 ${e2eProofCompleteText}，最近刷新 ${formatProofTimestamp(state.lastE2EProofSyncAt)}`}</p>
                        </div>
                      </div>
                      <div className="action-row">
                        <button
                          className="secondary-btn"
                          disabled={state.e2eProofLoading || state.e2eProofResetting || !deriveE2EProofUrl(state.wsUrl)}
                          onClick={() => {
                            void refreshE2EProofSnapshot({ force: true })
                          }}
                        >
                          {state.e2eProofLoading ? "刷新中..." : "刷新 Proof"}
                        </button>
                        <button
                          className="secondary-btn"
                          disabled={state.e2eProofLoading || state.e2eProofResetting || !deriveE2EProofUrl(state.wsUrl)}
                          onClick={() => {
                            void resetE2EProofSnapshot()
                          }}
                        >
                          {state.e2eProofResetting ? "重置中..." : "重置 Proof"}
                        </button>
                        {isControllerSession() ? (
                          <button className="secondary-btn" disabled={!canSendInput()} onClick={() => sendE2EProofInputSequence()}>
                            Proof 输入序列
                          </button>
                        ) : null}
                      </div>
                    </section>
                  </div>

                  {windowsSelfTestReport ? (
                    <div className="kv-two-col debug-kv-grid">
                      <div className="kv-item"><span>平台</span><strong>{windowsSelfTestReport.platform || "-"}</strong></div>
                      <div className="kv-item"><span>耗时</span><strong>{windowsSelfTestReport.duration_ms || 0} ms</strong></div>
                      <div className="kv-item"><span>采集后端</span><strong>{windowsSelfTestReport.capture_backend || "-"}</strong></div>
                      <div className="kv-item"><span>输入后端</span><strong>{windowsSelfTestReport.host_input_backend || "-"}</strong></div>
                      <div className="kv-item"><span>首帧</span><strong>{windowsSelfTestReport.frame_width > 0 ? `${windowsSelfTestReport.frame_width}x${windowsSelfTestReport.frame_height}` : "-"}</strong></div>
                      <div className="kv-item"><span>Sender 帧</span><strong>{windowsSelfTestReport.native_sender_probe_frame_count || 0}</strong></div>
                    </div>
                  ) : null}

                  {e2eProofRoutes.length ? (
                    <div className="proof-route-grid">
                      {e2eProofRoutes.map((route) => {
                        const latest = route.latest || null
                        const missing = Array.isArray(route.missing) && route.missing.length ? route.missing.join(", ") : "-"
                        const coverage = Array.isArray(latest?.remote_input_coverage) && latest.remote_input_coverage.length
                          ? latest.remote_input_coverage.join(",")
                          : "-"
                        const routeTone = route.complete ? "chip-success" : latest ? "tone-warn" : "chip-muted"
                        return (
                          <div key={route.route_key || route.route} className="proof-route-card">
                            <div className="proof-route-head">
                              <strong>{route.route || route.route_key}</strong>
                              <span className={`chip chip-compact ${routeTone}`}>{route.status || "not_observed"}</span>
                            </div>
                            <div className="proof-route-meta">missing: {missing}<br />coverage: {coverage}<br />latest: {latest?.proof_status || "-"}</div>
                          </div>
                        )
                      })}
                    </div>
                  ) : null}

                  <pre className="log-panel">{state.logs.length ? state.logs.slice(0, 80).join("\n") : "暂无日志"}</pre>
                </section>
              ) : null}
            </section>
          ) : (
            <section className="devices-page">
              <section className="device-list-panel">
                <div className="section-head compact-head">
                  <div>
                    <h3>设备</h3>
                    <p>{visibleDevices.length} 台设备 · 在线 {onlineCount} 台</p>
                  </div>
                  <div className="device-list-actions">
                    <label className="search-field">
                      <span>⌕</span>
                      <input placeholder="搜索设备、平台或设备 ID" readOnly />
                    </label>
                    <button className="secondary-btn" onClick={() => void refreshRelayDevices({ force: true })}>刷新</button>
                  </div>
                </div>

                <div className="device-table" role="table" aria-label="设备列表">
                  <div className="device-table-row device-table-head" role="row">
                    <span>设备</span>
                    <span>平台</span>
                    <span>状态</span>
                    <span>延迟</span>
                    <span>最近活跃</span>
                    <span>操作</span>
                  </div>
                  {visibleDevices.length ? visibleDevices.map((device) => {
                    const selected = device.device_id === state.targetDeviceId
                    const isSelf = device.is_self
                    const canRemote = device.is_target_candidate
                    return (
                      <article key={device.device_id} className={`device-table-row ${selected ? "device-row-active" : ""}`} role="row">
                        <div className="device-name-cell">
                          <span className={`platform-badge ${device.platform || "unknown"}`}>{platformBadgeLabel(device.platform)}</span>
                          <span><strong>{device.display_name}</strong><small>{device.device_id}</small></span>
                        </div>
                        <span>{device.platform_label}</span>
                        <span className="status-text"><i className={`status-dot ${statusDotClass(device.status)}`} />{device.status_label}</span>
                        <span>{device.status === "offline" ? "-" : "-"}</span>
                        <span>{device.last_seen_label || "-"}</span>
                        <span className="row-actions">
                          <button
                            className="secondary-btn compact-action"
                            disabled={!canRemote}
                            onClick={() => {
                              void openRemoteSessionWindow(device)
                            }}
                          >
                            {isSelf ? "本机" : "远程"}
                          </button>
                        </span>
                      </article>
                    )
                  }) : <div className="empty-state">暂无在线设备，请保持中继连接并等待设备心跳。</div>}
                </div>
              </section>

              <section className="device-side-panel">
                <h3>当前状态</h3>
                <div className="kv-two-col single-col">
                  <div className="kv-item"><span>角色</span><strong>{roleLabel}</strong></div>
                  <div className="kv-item"><span>会话</span><strong>{state.sessionId || "未开始"}</strong></div>
                  <div className="kv-item"><span>目标</span><strong>{selectedTargetName}</strong></div>
                  <div className="kv-item"><span>传输</span><strong>{transportModeLabel}</strong></div>
                </div>
                <p className="muted-copy">远程画面和输入工具会在独立会话窗口中打开，主页面只保留设备浏览和选择。</p>
              </section>
            </section>
          )}
        </div>
      </section>
    </div>
  )
}

function mountReactUi() {
  const app = document.getElementById("app")
  if (!app) {
    return
  }
  const root = createRoot(app)
  root.render(<DesktopReactApp />)
}

mountReactUi()
ensureLocalPreviewFpsSamplerRunning()
ensureSessionLinkSnapshotLoggerRunning()
render()
if (!isTauri() && state.autoConnect && isValidRelayWsUrl(state.wsUrl)) {
  scheduleAutoConnect()
}
void hydrateDesktopBootstrap()

window.addEventListener("blur", () => {
  releaseRemoteViewportPointer()
  releaseRemoteViewportKeys()
})

window.addEventListener("beforeunload", () => {
  releaseRemoteViewportPointer()
  releaseRemoteViewportKeys()
  if (sessionLinkSnapshotTimer) {
    window.clearInterval(sessionLinkSnapshotTimer)
    sessionLinkSnapshotTimer = null
  }
  stopLocalPreviewFrameCallbackSampler()
  if (localPreviewSamplerTimer) {
    window.clearInterval(localPreviewSamplerTimer)
    localPreviewSamplerTimer = null
  }
  if (inputUiRenderTimer) {
    window.clearTimeout(inputUiRenderTimer)
    inputUiRenderTimer = null
  }
})
