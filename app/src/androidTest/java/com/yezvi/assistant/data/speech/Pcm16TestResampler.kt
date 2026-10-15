package com.yezvi.assistant.data.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Test WAV preparation only; AudioRecord already supplies 16 kHz and never uses this filter. */
internal object Pcm16TestResampler {
    fun to16k(input: ShortArray, sourceRate: Int): ShortArray {
        require(sourceRate >= 16000 && sourceRate % 16000 == 0)
        if (sourceRate == 16000) return input.copyOf()
        val ratio = sourceRate / 16000
        val half = 64
        val cutoff = 7200.0 / sourceRate
        val kernel = DoubleArray(129) { tap ->
            val n = tap - half
            val sinc = if (n == 0) 2 * cutoff else sin(2 * PI * cutoff * n) / (PI * n)
            sinc * (0.54 - 0.46 * cos(2 * PI * tap / 128))
        }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        // Centered FIR, zero-padded boundaries, no group-delay shift or trimming.
        return ShortArray((input.size + ratio - 1) / ratio) { output ->
            val center = output * ratio
            var filtered = 0.0
            for (tap in kernel.indices) {
                val index = center + tap - half
                if (index in input.indices) filtered += input[index] * kernel[tap]
            }
            filtered.roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
