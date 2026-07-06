package com.remotedesk.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class RemoteDeskFrameView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : View(context, attrs) {
  private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
  private val destinationRect = RectF()
  private var frameBitmap: Bitmap? = null
  private var highQualityScaling = true

  init {
    setWillNotDraw(false)
  }

  fun setFrameBitmap(bitmap: Bitmap) {
    frameBitmap = bitmap
    postInvalidateOnAnimation()
  }

  fun clearFrameBitmap() {
    frameBitmap = null
    postInvalidateOnAnimation()
  }

  fun setHighQualityScaling(enabled: Boolean) {
    if (highQualityScaling == enabled) {
      return
    }
    highQualityScaling = enabled
    framePaint.isFilterBitmap = enabled
    framePaint.isDither = enabled
    postInvalidateOnAnimation()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val bitmap = frameBitmap?.takeIf { !it.isRecycled } ?: return
    val contentWidth = width - paddingLeft - paddingRight
    val contentHeight = height - paddingTop - paddingBottom
    if (contentWidth <= 0 || contentHeight <= 0 || bitmap.width <= 0 || bitmap.height <= 0) {
      return
    }

    val scale = min(
      contentWidth.toFloat() / bitmap.width.toFloat(),
      contentHeight.toFloat() / bitmap.height.toFloat(),
    )
    if (scale <= 0f) {
      return
    }

    val displayedWidth = bitmap.width.toFloat() * scale
    val displayedHeight = bitmap.height.toFloat() * scale
    val left = paddingLeft + (contentWidth - displayedWidth) / 2f
    val top = paddingTop + (contentHeight - displayedHeight) / 2f
    destinationRect.set(left, top, left + displayedWidth, top + displayedHeight)
    // 作者: long；真机 legacy JPEG 是兜底主画面，复用同一个 View 绘制位图；全屏整屏移动时可关闭滤波减轻合成压力，局部高清和小窗再打开滤波保证文字边缘。
    canvas.drawBitmap(bitmap, null, destinationRect, framePaint)
  }
}
