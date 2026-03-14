package com.minicode.viewmodel

import androidx.lifecycle.ViewModel
import com.minicode.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    private val _terminalFontSize = MutableStateFlow(repository.terminalFontSize)
    val terminalFontSize: StateFlow<Float> = _terminalFontSize.asStateFlow()

    private val _editorFontSize = MutableStateFlow(repository.editorFontSize)
    val editorFontSize: StateFlow<Float> = _editorFontSize.asStateFlow()

    private val _autoReconnect = MutableStateFlow(repository.autoReconnect)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    private val _wordWrap = MutableStateFlow(repository.wordWrap)
    val wordWrap: StateFlow<Boolean> = _wordWrap.asStateFlow()

    private val _showLineNumbers = MutableStateFlow(repository.showLineNumbers)
    val showLineNumbers: StateFlow<Boolean> = _showLineNumbers.asStateFlow()

    private val _extendedScrollRows = MutableStateFlow(repository.extendedScrollRows)
    val extendedScrollRows: StateFlow<Int> = _extendedScrollRows.asStateFlow()

    private val _showHiddenFiles = MutableStateFlow(repository.showHiddenFiles)
    val showHiddenFiles: StateFlow<Boolean> = _showHiddenFiles.asStateFlow()

    private val _speechEngine = MutableStateFlow(repository.speechEngine)
    val speechEngine: StateFlow<String> = _speechEngine.asStateFlow()

    private val _checkForUpdates = MutableStateFlow(repository.checkForUpdates)
    val checkForUpdates: StateFlow<Boolean> = _checkForUpdates.asStateFlow()

    fun setTerminalFontSize(size: Float) {
        val clamped = size.coerceIn(8f, 24f)
        _terminalFontSize.value = clamped
        repository.terminalFontSize = clamped
    }

    fun setEditorFontSize(size: Float) {
        val clamped = size.coerceIn(8f, 24f)
        _editorFontSize.value = clamped
        repository.editorFontSize = clamped
    }

    fun setAutoReconnect(enabled: Boolean) {
        _autoReconnect.value = enabled
        repository.autoReconnect = enabled
    }

    fun setWordWrap(enabled: Boolean) {
        _wordWrap.value = enabled
        repository.wordWrap = enabled
    }

    fun setShowLineNumbers(enabled: Boolean) {
        _showLineNumbers.value = enabled
        repository.showLineNumbers = enabled
    }

    fun setExtendedScrollRows(rows: Int) {
        val clamped = rows.coerceIn(100, 2000)
        _extendedScrollRows.value = clamped
        repository.extendedScrollRows = clamped
    }

    fun setShowHiddenFiles(show: Boolean) {
        _showHiddenFiles.value = show
        repository.showHiddenFiles = show
    }

    fun setSpeechEngine(engine: String) {
        _speechEngine.value = engine
        repository.speechEngine = engine
    }

    fun setCheckForUpdates(enabled: Boolean) {
        _checkForUpdates.value = enabled
        repository.checkForUpdates = enabled
    }
}
