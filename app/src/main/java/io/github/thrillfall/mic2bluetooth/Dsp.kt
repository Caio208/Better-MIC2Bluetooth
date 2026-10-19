package io.github.thrillfall.mic2bluetooth

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Signal path: gain -> optional volume normalization -> transparent soft-knee safety limiter.
 *
 * Nothing is filtered or removed: no EQ, no noise reduction, no high-pass.
 *
 * Normalization: tracks the recent loudness (RMS) as a reference. When the peak level suddenly
 * exceeds "reference * factor", gain is pulled down within <1 ms so the loud sound comes out at
 * about the level the previous sound had. When the loud sound ends, gain recovers (~0.35 s) and
 * the reference decays back. If the new loudness persists, the reference follows it within a few
 * seconds, so a real change of scene isn't squashed forever.
 * The result is applied before the system volume, so the system volume still scales everything.
 */
class Dsp(sampleRate: Int) {
    private val fs = sampleRate.toDouble()
    private val envRel = exp(-1.0 / (0.030 * fs)).toFloat()
    private val kRefNormal = (1.0 - exp(-1.0 / (0.8 * fs))).toFloat()
    private val kRefClamped = (1.0 - exp(-1.0 / (2.5 * fs))).toFloat()
    private val gAtk = (1.0 - exp(-1.0 / (0.0008 * fs))).toFloat()
    private val gRel = (1.0 - exp(-1.0 / (0.35 * fs))).toFloat()

    private var env = 0f
    private var ms = 0f
    private var gr = 1f

    fun reset() {
        env = 0f
        ms = 0f
        gr = 1f
    }

    /** @param norm 0 = off, 1 = soft, 2 = strong */
    fun process(buf: ShortArray, n: Int, gain: Float, norm: Int) {
        val factor = when (norm) {
            1 -> 8f   // +18 dB over the recent RMS
            2 -> 4f   // +12 dB over the recent RMS
            else -> 0f
        }
        for (i in 0 until n) {
            var x = buf[i] / 32768f * gain
            if (factor > 0f) {
                val a = abs(x)
                env = if (a > env) a else env * envRel
                val ceiling = max(sqrt(ms) * factor, CEIL_FLOOR)
                val clamped = env > ceiling
                val c = min(x * x, ceiling * ceiling)
                ms += (c - ms) * (if (clamped) kRefClamped else kRefNormal)
                val desired = if (clamped) max(ceiling / env, MIN_GAIN) else 1f
                gr += (desired - gr) * (if (desired < gr) gAtk else gRel)
                x *= gr
            }
            val ax = abs(x)
            if (ax > KNEE) {
                x = sign(x) * (KNEE + (1f - KNEE) * tanh((ax - KNEE) / (1f - KNEE)))
            }
            buf[i] = (x * 32768f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    companion object {
        private const val CEIL_FLOOR = 0.0178f // -35 dBFS: quiet scenes are never touched
        private const val MIN_GAIN = 0.0316f   // at most -30 dB of reduction
        private const val KNEE = 0.8f          // limiter is transparent below this level
    }
}
