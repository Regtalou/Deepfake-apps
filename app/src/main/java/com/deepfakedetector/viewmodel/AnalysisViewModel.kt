package com.deepfakedetector.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepfakedetector.analysis.*
import com.deepfakedetector.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

/**
 * AnalysisViewModel — Orchestration du pipeline de détection
 *
 * Gère :
 * - Pipeline d'analyse multi-modules avec progression temps réel
 * - Modes d'analyse (INSTANT / FAST / COMPLETE)
 * - Historique des résultats
 * - État UI réactif via StateFlow
 */
@HiltViewModel
class AnalysisViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoAnalyzer: VideoAnalyzer,
    private val audioAnalyzer: AudioAnalyzer,
    private val faceAnalyzer: FaceAnalyzer,
    private val metadataAnalyzer: MetadataAnalyzer,
    private val scoreFusionEngine: ScoreFusionEngine,
    private val imageAnalyzer: ImageAnalyzer
) : ViewModel() {

    // ─────────────────────────────────────────────────────────
    // STATE
    // ─────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    private val _analysisProgress = MutableStateFlow<List<AnalysisProgress>>(emptyList())
    val analysisProgress: StateFlow<List<AnalysisProgress>> = _analysisProgress.asStateFlow()

    private val _history = MutableStateFlow<List<AnalysisResult>>(emptyList())
    val history: StateFlow<List<AnalysisResult>> = _history.asStateFlow()

    private val _selectedMode = MutableStateFlow(AnalysisMode.FAST)
    val selectedMode: StateFlow<AnalysisMode> = _selectedMode.asStateFlow()

    private var currentAnalysisJob: Job? = null

    // ─────────────────────────────────────────────────────────
    // ACTIONS
    // ─────────────────────────────────────────────────────────

    fun selectMode(mode: AnalysisMode) {
        _selectedMode.value = mode
    }

    fun analyzeVideo(uri: Uri, videoName: String = "Vidéo") {
        currentAnalysisJob?.cancel()
        currentAnalysisJob = viewModelScope.launch {
            runAnalysisPipeline(uri, videoName, _selectedMode.value)
        }
    }

    /**
     * Détecte automatiquement si le contenu est une image ou une vidéo,
     * puis lance le pipeline adapté.
     */
    fun analyzeContent(uri: Uri) {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        if (mimeType.startsWith("image/")) {
            analyzeImage(uri)
        } else {
            analyzeVideo(uri, uri.lastPathSegment ?: "Vidéo")
        }
    }

    fun analyzeImage(uri: Uri) {
        currentAnalysisJob?.cancel()
        currentAnalysisJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                status = AnalysisStatus.LOADING_VIDEO,
                currentStep = "Chargement de l'image…",
                stepProgress = 0.05f,
                result = null,
                imageResult = null,
                error = null
            )
            try {
                _uiState.value = _uiState.value.copy(
                    status = AnalysisStatus.ANALYZING_PIXELS,
                    currentStep = "Analyse pixel, FFT, artefacts…",
                    stepProgress = 0.30f
                )
                val result = withContext(Dispatchers.Default) {
                    imageAnalyzer.analyze(uri)
                }
                _uiState.value = _uiState.value.copy(
                    status      = AnalysisStatus.COMPLETED,
                    currentStep = "Analyse terminée ✓",
                    stepProgress = 1.0f,
                    imageResult = result
                )
            } catch (e: CancellationException) {
                _uiState.value = AnalysisUiState()
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    status = AnalysisStatus.ERROR,
                    error  = "Erreur lors de l'analyse image : ${e.localizedMessage}"
                )
            }
        }
    }

    fun cancelAnalysis() {
        currentAnalysisJob?.cancel()
        _uiState.value = AnalysisUiState()
    }

    fun clearResult() {
        _uiState.value = _uiState.value.copy(
            status = AnalysisStatus.IDLE,
            result = null,
            imageResult = null,
            error = null,
            stepProgress = 0f
        )
        _analysisProgress.value = emptyList()
    }

    // ─────────────────────────────────────────────────────────
    // PIPELINE PRINCIPAL
    // ─────────────────────────────────────────────────────────

    private suspend fun runAnalysisPipeline(
        uri: Uri,
        videoName: String,
        mode: AnalysisMode
    ) {
        val startTime = System.currentTimeMillis()
        val videoId = UUID.randomUUID().toString()

        val progressSteps = mutableListOf<AnalysisProgress>()

        fun updateStep(step: AnalysisStatus, label: String, progress: Float) {
            val p = AnalysisProgress(step, label, progress)
            progressSteps.add(p)
            _analysisProgress.value = progressSteps.toList()
            _uiState.value = _uiState.value.copy(
                status = step,
                currentStep = label,
                stepProgress = progress
            )
        }

        try {
            // ── STEP 1 : Chargement ────────────────────────────
            updateStep(AnalysisStatus.LOADING_VIDEO, "Chargement de la vidéo…", 0.02f)
            _uiState.value = _uiState.value.copy(status = AnalysisStatus.LOADING_VIDEO)
            yield()

            // ── STEP 2 : Métadonnées ──────────────────────────
            updateStep(AnalysisStatus.ANALYZING_METADATA, "Analyse des métadonnées…", 0.08f)
            val (metadataScore, videoMetadata) = withContext(Dispatchers.IO) {
                metadataAnalyzer.analyze(uri)
            }
            updateStep(AnalysisStatus.ANALYZING_METADATA, "Métadonnées analysées ✓", 0.18f)
            yield()

            // ── STEP 3 : Extraction frames ────────────────────
            updateStep(AnalysisStatus.EXTRACTING_FRAMES, "Extraction des frames…", 0.20f)

            // ── STEP 4 : Analyse pixel + temporelle ───────────
            updateStep(AnalysisStatus.ANALYZING_PIXELS, "Analyse pixel et temporelle…", 0.28f)
            val (pixelScore, temporalScore) = withContext(Dispatchers.Default) {
                videoAnalyzer.analyze(uri, mode) { progress ->
                    val mapped = 0.28f + progress * 0.20f
                    _uiState.value = _uiState.value.copy(stepProgress = mapped)
                }
            }
            updateStep(AnalysisStatus.ANALYZING_PIXELS, "Pixel + Temporel analysés ✓", 0.50f)
            yield()

            // ── STEP 5 : Audio ────────────────────────────────
            var audioScore: ModuleScore? = null
            if (mode != AnalysisMode.INSTANT) {
                updateStep(AnalysisStatus.ANALYZING_AUDIO, "Analyse audio…", 0.52f)
                audioScore = withContext(Dispatchers.Default) {
                    audioAnalyzer.analyze(uri, mode) { progress ->
                        val mapped = 0.52f + progress * 0.12f
                        _uiState.value = _uiState.value.copy(stepProgress = mapped)
                    }
                }
                updateStep(AnalysisStatus.ANALYZING_AUDIO, "Audio analysé ✓", 0.65f)
                yield()
            }

            // ── STEP 6 : Visage + Physiologie ────────────────
            var faceScore: ModuleScore? = null
            var physioScore: ModuleScore? = null

            if (mode == AnalysisMode.FAST || mode == AnalysisMode.COMPLETE) {
                updateStep(AnalysisStatus.ANALYZING_FACE, "Analyse visage et physiologie…", 0.67f)
                val facePair = withContext(Dispatchers.Default) {
                    faceAnalyzer.analyze(uri, mode) { progress ->
                        val mapped = 0.67f + progress * 0.18f
                        _uiState.value = _uiState.value.copy(stepProgress = mapped)
                    }
                }
                faceScore = facePair?.first
                physioScore = facePair?.second
                updateStep(AnalysisStatus.ANALYZING_FACE, "Visage analysé ✓", 0.86f)
                yield()
            }

            // ── STEP 7 : Fusion ───────────────────────────────
            updateStep(AnalysisStatus.FUSING_SCORES, "Calcul du score global…", 0.90f)

            val moduleScores = ModuleScores(
                metadata = metadataScore,
                pixel = pixelScore,
                temporal = temporalScore,
                audio = audioScore,
                face = faceScore,
                physiological = physioScore
            )

            val totalTime = System.currentTimeMillis() - startTime

            val result = withContext(Dispatchers.Default) {
                scoreFusionEngine.fuse(
                    scores = moduleScores,
                    mode = mode,
                    videoMetadata = videoMetadata,
                    videoId = videoId,
                    videoPath = uri.toString(),
                    videoName = videoName,
                    totalProcessingTimeMs = totalTime
                )
            }

            // ── STEP 8 : Terminé ──────────────────────────────
            updateStep(AnalysisStatus.COMPLETED, "Analyse terminée ✓", 1.0f)

            _uiState.value = _uiState.value.copy(
                status = AnalysisStatus.COMPLETED,
                result = result,
                stepProgress = 1.0f
            )

            // Ajouter à l'historique
            _history.value = listOf(result) + _history.value

        } catch (e: CancellationException) {
            _uiState.value = AnalysisUiState()
            throw e
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                status = AnalysisStatus.ERROR,
                error = "Erreur lors de l'analyse : ${e.localizedMessage}"
            )
        }
    }

    // ─────────────────────────────────────────────────────────
    // COMMUNAUTÉ
    // ─────────────────────────────────────────────────────────

    fun submitCommunityVote(videoId: String, isFake: Boolean) {
        viewModelScope.launch {
            // TODO: Envoyer le vote au backend
        }
    }

    fun reportVideo(videoId: String) {
        viewModelScope.launch {
            // TODO: Signaler la vidéo
        }
    }
}
