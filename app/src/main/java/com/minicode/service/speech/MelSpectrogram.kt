package com.minicode.service.speech

import kotlin.math.*

/**
 * Computes log-mel spectrogram features compatible with NeMo ASR models.
 * Parameters match NeMo's AudioToMelSpectrogramPreprocessor defaults.
 */
class MelSpectrogram(
    private val sampleRate: Int = 16000,
    private val nFft: Int = 512,
    private val hopLength: Int = 160,   // 10ms at 16kHz
    private val winLength: Int = 400,   // 25ms at 16kHz
    private val nMels: Int = 128,
    private val fMin: Float = 0f,
    private val fMax: Float = 8000f,
) {
    private val window = FloatArray(winLength) { i ->
        0.5f * (1f - cos(2.0 * PI * i / (winLength - 1)).toFloat())
    }
    private val melBank = createMelFilterbank()

    data class Features(val data: FloatArray, val numFrames: Int, val numMels: Int)

    /**
     * Compute log-mel spectrogram with per-feature normalization.
     * Returns features in [nMels, numFrames] layout (NeMo encoder input format).
     */
    fun compute(samples: FloatArray): Features {
        val numFrames = maxOf(1, (samples.size - winLength) / hopLength + 1)
        val melSpec = Array(numFrames) { FloatArray(nMels) }
        val fftReal = FloatArray(nFft)
        val fftImag = FloatArray(nFft)

        for (t in 0 until numFrames) {
            val offset = t * hopLength
            fftReal.fill(0f)
            fftImag.fill(0f)
            for (j in 0 until winLength) {
                val idx = offset + j
                fftReal[j] = if (idx < samples.size) samples[idx] * window[j] else 0f
            }

            fft(fftReal, fftImag)

            for (m in 0 until nMels) {
                var sum = 0f
                val filter = melBank[m]
                for (k in filter.indices) {
                    if (filter[k] > 0f) {
                        sum += filter[k] * (fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k])
                    }
                }
                melSpec[t][m] = ln(maxOf(sum, 1e-10f))
            }
        }

        // Per-feature normalization (NeMo default: normalize="per_feature")
        for (m in 0 until nMels) {
            var mean = 0f
            for (t in 0 until numFrames) mean += melSpec[t][m]
            mean /= numFrames

            var variance = 0f
            for (t in 0 until numFrames) {
                val d = melSpec[t][m] - mean
                variance += d * d
            }
            val std = sqrt(variance / numFrames).coerceAtLeast(1e-5f)

            for (t in 0 until numFrames) {
                melSpec[t][m] = (melSpec[t][m] - mean) / std
            }
        }

        // Transpose to [nMels, numFrames] for NeMo encoder
        val result = FloatArray(nMels * numFrames)
        for (m in 0 until nMels) {
            for (t in 0 until numFrames) {
                result[m * numFrames + t] = melSpec[t][m]
            }
        }
        return Features(result, numFrames, nMels)
    }

    private fun createMelFilterbank(): Array<FloatArray> {
        val nBins = nFft / 2 + 1
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        val melPoints = FloatArray(nMels + 2) { i ->
            melToHz(melMin + (melMax - melMin) * i / (nMels + 1))
        }
        val bins = IntArray(nMels + 2) { i ->
            ((melPoints[i] * nFft / sampleRate) + 0.5f).toInt().coerceIn(0, nBins - 1)
        }

        return Array(nMels) { m ->
            FloatArray(nBins).also { filter ->
                val left = bins[m]
                val center = bins[m + 1]
                val right = bins[m + 2]
                for (k in left until center) {
                    if (center > left) filter[k] = (k - left).toFloat() / (center - left)
                }
                for (k in center..right) {
                    if (right > center) filter[k] = (right - k).toFloat() / (right - center)
                }
            }
        }
    }

    /** In-place radix-2 Cooley-Tukey FFT */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
            var m = n / 2
            while (m >= 1 && j >= m) { j -= m; m /= 2 }
            j += m
        }
        var step = 1
        while (step < n) {
            val angle = -PI / step
            for (group in 0 until n step step * 2) {
                for (pair in 0 until step) {
                    val w = angle * pair
                    val cosW = cos(w).toFloat()
                    val sinW = sin(w).toFloat()
                    val i1 = group + pair
                    val i2 = i1 + step
                    val tR = cosW * real[i2] - sinW * imag[i2]
                    val tI = sinW * real[i2] + cosW * imag[i2]
                    real[i2] = real[i1] - tR
                    imag[i2] = imag[i1] - tI
                    real[i1] += tR
                    imag[i1] += tI
                }
            }
            step *= 2
        }
    }

    private fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
    private fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)
}
