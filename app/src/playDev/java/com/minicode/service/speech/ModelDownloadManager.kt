package com.minicode.service.speech

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ModelDownloadManager"

private val MODEL_URLS = listOf(
    "https://matlogica.com/minicode/models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
)

private const val MODEL_DIR_NAME = "parakeet-tdt-0.6b-v3-int8"
private const val TAR_INNER_DIR = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val modelDir = File(context.filesDir, "sherpa-models/$MODEL_DIR_NAME")

    private val modelFiles = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

    private fun hasModelFiles(dir: File): Boolean =
        modelFiles.all { File(dir, it).let { f -> f.exists() && f.canRead() && f.length() > 0 } }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val state: StateFlow<DownloadState> = _state

    init {
        if (hasModelFiles(modelDir)) {
            _state.value = DownloadState.Ready
        }
    }

    fun isModelReady(): Boolean = hasModelFiles(modelDir)

    suspend fun downloadAndExtract() {
        if (isModelReady()) {
            _state.value = DownloadState.Ready
            return
        }
        withContext(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, "parakeet-model.tar.bz2")
                downloadFile(tempFile)
                extractTarBz2(tempFile)
                tempFile.delete()

                if (isModelReady()) {
                    _state.value = DownloadState.Ready
                } else {
                    _state.value = DownloadState.Error("Extraction completed but model files not found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download/extract failed", e)
                _state.value = DownloadState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun downloadFile(dest: File) {
        var lastError: Exception? = null
        for (modelUrl in MODEL_URLS) {
            try {
                Log.d(TAG, "Trying: $modelUrl")
                downloadFromUrl(dest, modelUrl)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download from $modelUrl: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: Exception("All download URLs failed")
    }

    private fun downloadFromUrl(dest: File, modelUrl: String) {
        _state.value = DownloadState.Downloading(0f)
        val url = URL(modelUrl)
        var conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000

        // Handle redirects (GitHub releases redirect)
        var redirects = 0
        while (conn.responseCode in 301..302 || conn.responseCode == 307 || conn.responseCode == 308) {
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URL(location).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000

            // Resume support
            if (dest.exists() && dest.length() > 0) {
                conn.setRequestProperty("Range", "bytes=${dest.length()}-")
            }
            if (++redirects > 10) throw Exception("Too many redirects")
        }

        val totalBytes = conn.contentLengthLong
        var downloaded = 0L

        val output = if (conn.responseCode == 206) {
            // Partial content — resume
            downloaded = dest.length()
            dest.outputStream().apply { channel.position(downloaded) }
        } else {
            dest.outputStream()
        }

        conn.inputStream.buffered(65536).use { input ->
            output.use { out ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                    downloaded += n
                    if (totalBytes > 0) {
                        _state.value = DownloadState.Downloading(downloaded.toFloat() / totalBytes)
                    }
                }
            }
        }
        conn.disconnect()
        Log.d(TAG, "Download complete: ${dest.length()} bytes")
    }

    private fun extractTarBz2(archive: File) {
        _state.value = DownloadState.Extracting
        val targetDir = modelDir
        targetDir.mkdirs()
        Log.d(TAG, "Extracting to ${targetDir.absolutePath}")

        archive.inputStream().buffered(65536).use { fileIn ->
            BZip2CompressorInputStream(BufferedInputStream(fileIn, 65536)).use { bz2In ->
                TarArchiveInputStream(bz2In).use { tarIn ->
                    var entry = tarIn.nextEntry
                    while (entry != null) {
                        val name = entry.name.removePrefix("$TAR_INNER_DIR/")
                        if (name.isNotEmpty() && !entry.isDirectory) {
                            val outFile = File(targetDir, name)
                            if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                                entry = tarIn.nextEntry
                                continue
                            }
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                tarIn.copyTo(out, 65536)
                            }
                            Log.d(TAG, "Extracted: ${outFile.name} (${outFile.length()} bytes)")
                        }
                        entry = tarIn.nextEntry
                    }
                }
            }
        }
        Log.d(TAG, "Extraction complete to ${targetDir.absolutePath}")
    }

    fun deleteModel() {
        modelDir.deleteRecursively()
        _state.value = DownloadState.NotDownloaded
    }
}
