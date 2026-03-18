package com.minicode.service.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SherpaRecognizer"
private const val SAMPLE_RATE = 16000
private const val CHUNK_INTERVAL_MS = 2000L

@Singleton
class SherpaRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager,
    private val postProcessor: VoicePostProcessor,
) {
    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var recording = false
    private val audioBuffer = mutableListOf<FloatArray>()
    private val recognizerLock = Object()

    var onRmsChanged: ((Float) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null

    fun isAvailable(): Boolean = modelDownloadManager.isModelReady()

    private fun ensureRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }

        val modelDir = modelDownloadManager.modelDir.absolutePath
        Log.d(TAG, "ensureRecognizer: modelDir=$modelDir")

        // Copy tokens.txt to cache dir — native sherpa-onnx has issues reading
        // from certain Android storage paths
        val tokensFile = File(context.cacheDir, "sherpa-tokens.txt")
        if (!tokensFile.exists() || tokensFile.length() == 0L) {
            File("$modelDir/tokens.txt").copyTo(tokensFile, overwrite = true)
        }
        val tokensPath = tokensFile.absolutePath
        Log.d(TAG, "tokensPath=$tokensPath (${tokensFile.length()} bytes)")

        val modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "$modelDir/encoder.int8.onnx",
                decoder = "$modelDir/decoder.int8.onnx",
                joiner = "$modelDir/joiner.int8.onnx",
            ),
            tokens = tokensPath,
            modelType = "nemo_transducer",
            numThreads = 4,
            debug = true,
        )

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )

        Log.d(TAG, "Creating OfflineRecognizer...")
        val r = OfflineRecognizer(null, config)
        recognizer = r
        Log.d(TAG, "OfflineRecognizer created successfully")
        return r
    }

    private fun collectSamples(): FloatArray? {
        synchronized(audioBuffer) {
            val totalSize = audioBuffer.sumOf { it.size }
            if (totalSize == 0) return null
            val samples = FloatArray(totalSize)
            var offset = 0
            for (chunk in audioBuffer) {
                chunk.copyInto(samples, offset)
                offset += chunk.size
            }
            return samples
        }
    }

    private fun recognize(samples: FloatArray): String? {
        synchronized(recognizerLock) {
            return try {
                val rec = ensureRecognizer()
                val stream = rec.createStream()
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                val result = rec.getResult(stream).text.trim()
                if (result.isNotEmpty()) postProcessor.postProcess(result) else null
            } catch (e: Exception) {
                Log.e(TAG, "Recognition failed", e)
                null
            }
        }
    }

    fun startRecording(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        if (!isAvailable()) return false

        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            ),
            SAMPLE_RATE * 4
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufSize,
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        synchronized(audioBuffer) { audioBuffer.clear() }
        recording = true
        audioRecord?.startRecording()

        // Audio capture thread
        Thread {
            val buf = FloatArray(SAMPLE_RATE / 10) // 100ms chunks
            while (recording) {
                val read = audioRecord?.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING) ?: -1
                if (read > 0) {
                    val chunk = buf.copyOf(read)
                    synchronized(audioBuffer) {
                        audioBuffer.add(chunk)
                    }
                    var sum = 0f
                    for (i in 0 until read) {
                        sum += chunk[i] * chunk[i]
                    }
                    val rms = Math.sqrt((sum / read).toDouble()).toFloat()
                    val rmsdB = 20f * Math.log10(maxOf(rms, 1e-7f).toDouble()).toFloat()
                    onRmsChanged?.invoke(rmsdB)
                }
            }
        }.apply {
            name = "SherpaAudioCapture"
            isDaemon = true
            start()
        }

        // Periodic chunked recognition thread
        Thread {
            Thread.sleep(CHUNK_INTERVAL_MS)
            while (recording) {
                val samples = collectSamples()
                if (samples != null && samples.size > SAMPLE_RATE / 2) {
                    Log.d(TAG, "Chunk recognize: ${samples.size} samples (${samples.size / SAMPLE_RATE.toFloat()}s)")
                    val result = recognize(samples)
                    if (result != null && recording) {
                        Log.d(TAG, "Chunk result: '$result'")
                        onPartialResult?.invoke(result)
                    }
                }
                Thread.sleep(CHUNK_INTERVAL_MS)
            }
        }.apply {
            name = "SherpaChunkRecognize"
            isDaemon = true
            start()
        }

        Log.d(TAG, "Recording started (streaming mode)")
        return true
    }

    suspend fun stopRecordingAndRecognize(): String? = withContext(Dispatchers.Default) {
        recording = false
        // Brief sleep to let the chunk thread exit its loop
        Thread.sleep(100)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val samples = collectSamples()
        synchronized(audioBuffer) { audioBuffer.clear() }

        if (samples == null || samples.isEmpty()) return@withContext null

        Log.d(TAG, "Final recognize: ${samples.size} samples (${samples.size / SAMPLE_RATE.toFloat()}s)")
        val result = recognize(samples)
        Log.d(TAG, "Final result: '$result'")
        result
    }

    fun cancelRecording() {
        recording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        synchronized(audioBuffer) { audioBuffer.clear() }
    }

    fun release() {
        cancelRecording()
        synchronized(recognizerLock) {
            recognizer?.release()
            recognizer = null
        }
    }
}
