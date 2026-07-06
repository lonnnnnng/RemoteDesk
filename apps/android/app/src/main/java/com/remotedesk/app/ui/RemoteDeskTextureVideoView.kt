package com.remotedesk.app.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

class RemoteDeskTextureVideoView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener, VideoSink {
  private var renderer: EglRenderer? = null
  private var drawer: GlRectDrawer? = null
  private var eglSurfaceAttached = false

  init {
    surfaceTextureListener = this
    isOpaque = true
  }

  fun init(sharedContext: EglBase.Context?) {
    if (renderer != null || sharedContext == null) {
      return
    }
    val nextDrawer = GlRectDrawer()
    drawer = nextDrawer
    renderer = EglRenderer("RemoteDeskTextureRenderer").apply {
      // 作者: long；全屏真机上 SurfaceViewRenderer 会触发独立 Surface 合成回压，TextureView 复用同一 View 合成树做 A/B，先验证是否能恢复帧率。
      init(sharedContext, EglBase.CONFIG_PLAIN, nextDrawer)
      setMirror(false)
    }
    attachEglSurfaceIfReady()
  }

  fun release() {
    renderer?.release()
    renderer = null
    drawer?.release()
    drawer = null
    eglSurfaceAttached = false
  }

  fun clearImage() {
    renderer?.clearImage()
  }

  override fun onFrame(frame: VideoFrame) {
    renderer?.onFrame(frame)
  }

  override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
    attachEglSurfaceIfReady()
  }

  override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
    renderer?.setLayoutAspectRatio(if (height > 0) width.toFloat() / height.toFloat() else 0f)
  }

  override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
    renderer?.releaseEglSurface {
      eglSurfaceAttached = false
    }
    return true
  }

  override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

  private fun attachEglSurfaceIfReady() {
    val surface = surfaceTexture ?: return
    val currentRenderer = renderer ?: return
    if (eglSurfaceAttached) {
      return
    }
    currentRenderer.createEglSurface(surface)
    eglSurfaceAttached = true
    if (height > 0) {
      currentRenderer.setLayoutAspectRatio(width.toFloat() / height.toFloat())
    }
  }
}
