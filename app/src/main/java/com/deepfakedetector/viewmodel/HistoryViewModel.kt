package com.deepfakedetector.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepfakedetector.data.AnalysisResult
import com.deepfakedetector.repository.AnalysisRepository
import com.deepfakedetector.repository.HistoryStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: AnalysisRepository
) : ViewModel() {

    val results: StateFlow<List<AnalysisResult>> =
        repository.allResults
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _stats = MutableStateFlow<HistoryStats?>(null)
    val stats: StateFlow<HistoryStats?> = _stats.asStateFlow()

    init {
        viewModelScope.launch {
            _stats.value = repository.getStats()
        }
    }

    fun deleteResult(id: String) {
        viewModelScope.launch {
            repository.deleteResult(id)
            _stats.value = repository.getStats()
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            _stats.value = repository.getStats()
        }
    }
}
