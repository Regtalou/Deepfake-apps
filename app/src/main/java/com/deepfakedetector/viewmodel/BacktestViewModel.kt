package com.deepfakedetector.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepfakedetector.analysis.BacktestEngine
import com.deepfakedetector.data.*
import com.deepfakedetector.db.BacktestDao
import com.deepfakedetector.db.BacktestRunEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class BacktestUiPhase { IDLE, RUNNING, DONE, ERROR }

data class BacktestUiState(
    val phase:       BacktestUiPhase = BacktestUiPhase.IDLE,
    val progress:    Float           = 0f,
    val statusText:  String          = "Prêt",
    val report:      BacktestReport? = null,
    val calibration: BacktestEngine.CalibrationResult? = null,
    val reportText:  String          = "",
    val errorMsg:    String?         = null
)

@HiltViewModel
class BacktestViewModel @Inject constructor(
    private val backtestEngine: BacktestEngine,
    private val backtestDao: BacktestDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(BacktestUiState())
    val uiState: StateFlow<BacktestUiState> = _uiState.asStateFlow()

    val history: StateFlow<List<BacktestRunEntity>> =
        backtestDao.getAllRuns()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun runBacktest(fakeUris: List<Uri>, realUris: List<Uri>) {
        viewModelScope.launch {
            _uiState.update { it.copy(phase = BacktestUiPhase.RUNNING, progress = 0f) }
            try {
                // Construire la liste BacktestVideo depuis les URIs
                val videos = buildList {
                    fakeUris.forEachIndexed { i, uri ->
                        add(BacktestVideo(id = "fake_$i", uri = uri, groundTruth = true))
                    }
                    realUris.forEachIndexed { i, uri ->
                        add(BacktestVideo(id = "real_$i", uri = uri, groundTruth = false))
                    }
                }

                val total = videos.size
                val results = mutableListOf<BacktestResult>()

                // Collecter la progression
                backtestEngine.runBacktest(videos, AnalysisMode.FAST)
                    .collect { progress ->
                        _uiState.update { it.copy(
                            progress   = progress.currentIndex.toFloat() / total.coerceAtLeast(1),
                            statusText = "Analyse : ${progress.currentVideoName} (${progress.currentIndex}/$total)"
                        )}
                        results.clear()
                        results.addAll(progress.completedResults)
                    }

                // Calibration + rapport
                _uiState.update { it.copy(progress = 0.95f, statusText = "Calibration du seuil optimal…") }
                val calibration   = backtestEngine.calibrateThreshold(results)
                val errorAnalysis = backtestEngine.analyzeErrors(results)
                val report        = backtestEngine.generateReport(results)
                val reportText    = backtestEngine.formatReport(report, calibration, errorAnalysis)

                // Persistance
                backtestDao.insert(
                    BacktestRunEntity(
                        id               = UUID.randomUUID().toString(),
                        accuracy         = report.accuracy,
                        precision        = report.precision,
                        recall           = report.recall,
                        f1Score          = report.f1Score,
                        auc              = calibration.aucRoc,
                        optimalThreshold = calibration.optimalThreshold,
                        truePositives    = report.truePositives,
                        trueNegatives    = report.trueNegatives,
                        falsePositives   = report.falsePositives,
                        falseNegatives   = report.falseNegatives,
                        totalVideos      = report.totalVideos,
                        runAt            = System.currentTimeMillis(),
                        reportText       = reportText
                    )
                )

                _uiState.update { it.copy(
                    phase       = BacktestUiPhase.DONE,
                    progress    = 1f,
                    statusText  = "Terminé",
                    report      = report,
                    calibration = calibration,
                    reportText  = reportText
                )}

            } catch (e: Exception) {
                _uiState.update { it.copy(
                    phase    = BacktestUiPhase.ERROR,
                    errorMsg = e.localizedMessage ?: "Erreur inconnue"
                )}
            }
        }
    }

    fun reset() { _uiState.value = BacktestUiState() }
}
