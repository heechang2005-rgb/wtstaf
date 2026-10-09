package com.gyarulens.app

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.SystemClock
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * MediaProjection 화면을 계속 받아 두고, 요청이 오면 가장 최근 프레임을 Bitmap으로 넘겨준다.
 * 프레임 관련 작업은 전부 handler 스레드에서 처리한다.
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    private val handler: Handler,
    private val onFatal: (String) -> Unit
) {

    /** screen = 실제 화면 크기, cap = 캡처 이미지 크기, scale = cap / screen */
    class Config(val screenW: Int, val screenH: Int, val capW: Int, val capH: Int, val scale: Float)

    @Volatile
    var config: Config = Config(0, 0, 1, 1, 1f)
        private set

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var latest: Image? = null
    private var latestTime = 0L
    private var released = false

    fun start(screenW: Int, screenH: Int, dpi: Int) {
        val cfg = makeConfig(screenW, screenH)
        config = cfg
        handler.post {
            if (released) return@post
            try {
                val r = newReader(cfg)
                reader = r
                display = projection.createVirtualDisplay(
                    "GyaruLens",
                    cfg.capW, cfg.capH, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    r.surface, null, handler
                )
            } catch (e: Exception) {
                onFatal("화면 캡처를 시작하지 못했어요: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** 화면이 회전하는 등 크기가 바뀌었을 때 */
    fun resize(screenW: Int, screenH: Int, dpi: Int) {
        val cfg = makeConfig(screenW, screenH)
        config = cfg
        handler.post {
            if (released) return@post
            val vd = display ?: return@post
            try {
                latest?.close()
                latest = null
                val old = reader
                val r = newReader(cfg)
                reader = r
                vd.resize(cfg.capW, cfg.capH, dpi)
                vd.surface = r.surface
                old?.close()
            } catch (e: Exception) {
                onFatal("화면 크기 변경을 따라가지 못했어요: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 프레임 하나를 Bitmap으로 받아 callback(handler 스레드)으로 넘긴다. 실패하면 null.
     * @param notBefore 이 시각(uptimeMillis) 이후에 들어온 프레임을 원함
     * @param deadline 이 시각까지 원하는 프레임이 안 오면 가진 것 중 최신 걸 준다
     */
    fun grab(notBefore: Long, deadline: Long, callback: (Bitmap?) -> Unit) {
        handler.post { tryGrab(notBefore, deadline, callback) }
    }

    fun release() {
        handler.post {
            released = true
            try {
                display?.release()
            } catch (e: Exception) {
            }
            display = null
            latest?.close()
            latest = null
            try {
                reader?.close()
            } catch (e: Exception) {
            }
            reader = null
        }
    }

    private fun tryGrab(notBefore: Long, deadline: Long, callback: (Bitmap?) -> Unit) {
        if (released) {
            callback(null)
            return
        }
        val now = SystemClock.uptimeMillis()
        val img = latest
        if (img != null && (latestTime >= notBefore || now >= deadline)) {
            val bmp = try {
                toBitmap(img)
            } catch (e: Exception) {
                null
            }
            callback(bmp)
            return
        }
        if (now >= deadline) {
            callback(null)
            return
        }
        handler.postDelayed({ tryGrab(notBefore, deadline, callback) }, 25)
    }

    private fun makeConfig(screenW: Int, screenH: Int): Config {
        val w = max(1, screenW)
        val h = max(1, screenH)
        val longSide = max(w, h)
        val scale = if (longSide > MAX_LONG_SIDE) MAX_LONG_SIDE.toFloat() / longSide else 1f
        return Config(w, h, max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()), scale)
    }

    private fun newReader(cfg: Config): ImageReader {
        val r = ImageReader.newInstance(cfg.capW, cfg.capH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir -> onImage(ir) }, handler)
        return r
    }

    private fun onImage(ir: ImageReader) {
        if (released || ir !== reader) {
            // 이전 크기의 리더에서 남은 프레임은 버린다
            try {
                ir.acquireLatestImage()?.close()
            } catch (e: Exception) {
            }
            return
        }
        val img = try {
            ir.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        latest?.close()
        latest = img
        latestTime = SystemClock.uptimeMillis()
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = img.width
        val h = img.height
        val rowPixels = rowStride / pixelStride

        val padded = Bitmap.createBitmap(rowPixels, h, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        if (buffer.remaining() >= padded.byteCount) {
            padded.copyPixelsFromBuffer(buffer)
        } else {
            // 일부 기기는 마지막 줄 여백이 없어서 버퍼가 조금 짧다
            val bytes = ByteArray(padded.byteCount)
            buffer.get(bytes, 0, buffer.remaining())
            padded.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        }
        if (rowPixels == w) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
        padded.recycle()
        return cropped
    }

    companion object {
        /** 너무 큰 화면은 줄여서 캡처 (OCR 속도·메모리) */
        private const val MAX_LONG_SIDE = 2400
    }
}
