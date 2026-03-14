package com.minicode.service.speech

sealed class DownloadState {
    data object NotDownloaded : DownloadState()
    data object NotAvailable : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data object Extracting : DownloadState()
    data object Ready : DownloadState()
    data class Error(val message: String) : DownloadState()
}
