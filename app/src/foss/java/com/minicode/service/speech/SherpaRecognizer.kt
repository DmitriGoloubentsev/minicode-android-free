package com.minicode.service.speech

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub SherpaRecognizer for FOSS builds — Parakeet offline recognition not available.
 */
@Singleton
class SherpaRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val postProcessor: VoicePostProcessor,
) {
    var onRmsChanged: ((Float) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null

    fun isAvailable(): Boolean = false
    fun startRecording(): Boolean = false
    suspend fun stopRecordingAndRecognize(): String? = null
    fun cancelRecording() {}
    fun release() {}
}
