package com.minicode.service.speech

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub ModelDownloadManager for FOSS builds — no model download needed.
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.NotAvailable)
    val state: StateFlow<DownloadState> = _state

    fun isModelReady(): Boolean = false
    suspend fun downloadAndExtract() {}
    fun deleteModel() {}
}
