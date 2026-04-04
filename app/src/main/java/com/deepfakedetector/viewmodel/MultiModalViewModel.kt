package com.deepfakedetector.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepfakedetector.analysis.MultiModalAnalyzer
import com.deepfakedetector.data.MultiModalInput
import com.deepfakedetector.data.MultiModalResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MultiModalPhase { INPUT, ANALYZING, DONE, ERROR }

data class MultiModalUiState(
    val phase:      MultiModalPhase = MultiModalPhase.INPUT,
    val progress:   Float           = 0f,
    val statusText: String          = "Prêt",
    val result:     MultiModalResult? = null,
    val errorMsg:   String?         = null
)

@HiltViewModel
class MultiModalViewModel @Inject constructor(
    private val analyzer: MultiModalAnalyzer
) : ViewModel() {

    private val _uiState = MutableStateFlow(MultiModalUiState())
    val uiState: StateFlow<MultiModalUiState> = _uiState.asStateFlow()

    fun analyze(imageUri: Uri, text: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(phase = MultiModalPhase.ANALYZING, progress = 0f) }
            try {
                val input = MultiModalInput(imageUri = imageUri, text = text)
                val result = analyzer.analyze(input) { progress, status ->
                    _uiState.update { it.copy(progress = progress, statusText = status) }
                }
                _uiState.update { it.copy(
                    phase   = MultiModalPhase.DONE,
                    result  = result,
                    progress = 1f
                )}
            } catch (e: CancellationException) {
                _uiState.value = MultiModalUiState()
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    phase    = MultiModalPhase.ERROR,
                    errorMsg = e.localizedMessage ?: "Erreur inconnue"
                )}
            }
        }
    }

    fun reset() { _uiState.value = MultiModalUiState() }
}
