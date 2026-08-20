package com.orangechuice.lightstick.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 Cooley–Tukey FFT.
 *
 * Hand-written rather than pulled from JTransforms — as [plan.md] 3.2 suggested —
 * for the same reason the colour picker is hand-written: at one fixed power-of-two
 * size this is forty lines, it keeps the build offline-clean, and it is a pure
 * function that unit-tests without a device.
 *
 * Twiddle factors are precomputed once, so [transform] allocates nothing. It runs
 * on every audio frame (~43 times a second) and must not produce garbage.
 */
class Fft(val size: Int) {

    init {
        require(size > 0 && (size and (size - 1)) == 0) { "size must be a power of two: $size" }
    }

    private val cosTable = FloatArray(size / 2) { cos(-2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(-2.0 * PI * it / size).toFloat() }

    /** Transforms [re]/[im] in place. Both must be [size] long. */
    fun transform(re: FloatArray, im: FloatArray) {
        require(re.size == size && im.size == size) { "buffers must be $size long" }

        // Decimation in time: reorder into bit-reversed index order first.
        var j = 0
        for (i in 0 until size - 1) {
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var k = size shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var span = 2
        while (span <= size) {
            val half = span shr 1
            val step = size / span
            var base = 0
            while (base < size) {
                var twiddle = 0
                for (offset in 0 until half) {
                    val lo = base + offset
                    val hi = lo + half
                    val c = cosTable[twiddle]
                    val s = sinTable[twiddle]
                    val tRe = c * re[hi] - s * im[hi]
                    val tIm = c * im[hi] + s * re[hi]
                    re[hi] = re[lo] - tRe
                    im[hi] = im[lo] - tIm
                    re[lo] += tRe
                    im[lo] += tIm
                    twiddle += step
                }
                base += span
            }
            span = span shl 1
        }
    }
}
