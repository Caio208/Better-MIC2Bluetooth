package io.github.thrillfall.mic2bluetooth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

/**
 * Layered translucent waves that swell with the live sound level.
 * Quiet = calm ripple, loud = tall fast waves. Stops drawing when idle.
 */
class WaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** One color per layer (cycled). */
    var colors: IntArray = intArrayOf(Color.WHITE)
        set(v) {
            field = if (v.isEmpty()) intArrayOf(Color.WHITE) else v
            invalidate()
        }

    /** True while the amplifier is running. */
    var active: Boolean = false
        set(v) {
            field = v
            last = SystemClock.uptimeMillis()
            invalidate()
        }

    private class Layer(val freq: Float, val speed: Float, val scale: Float, val alpha: Int)

    private val layers = arrayOf(
        Layer(1.0f, 0.8f, 1.00f, 90),
        Layer(1.7f, -1.2f, 0.78f, 70),
        Layer(2.4f, 1.7f, 0.58f, 55),
        Layer(3.3f, -2.3f, 0.40f, 42)
    )
    private val phases = FloatArray(layers.size)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val twoPi = (2.0 * PI).toFloat()
    private var amp = 0f
    private var last = SystemClock.uptimeMillis()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = SystemClock.uptimeMillis()
        val dt = (now - last).coerceIn(0L, 50L) / 1000f
        last = now

        // Level (RMS) -> 0..1 on a dB scale, fast attack / slow release
        val rms = LoopbackService.level
        val db = if (rms > 1e-5f) 20f * log10(rms) else -100f
        val target = if (active) ((db + 50f) / 50f).coerceIn(0f, 1f) else 0f
        amp += (target - amp) * (if (target > amp) 0.35f else 0.07f)

        if (!active && amp < 0.005f) return // fully faded out: draw nothing, stop animating

        val base = h * 0.68f
        val maxAmp = h * 0.30f
        val idle = if (active) h * 0.018f else 0f
        for (i in layers.indices) {
            val l = layers[i]
            phases[i] += l.speed * dt * (1f + amp * 2.5f)
            val a = idle + amp * maxAmp * l.scale
            path.reset()
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w + 8f) {
                path.lineTo(x, base - a * sin((x / w) * twoPi * l.freq + phases[i]))
                x += 8f
            }
            path.lineTo(w, h)
            path.close()
            paint.color = ColorUtils.setAlphaComponent(colors[i % colors.size], l.alpha)
            canvas.drawPath(path, paint)
        }
        postInvalidateOnAnimation()
    }
}
