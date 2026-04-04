package com.deepfakedetector.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.deepfakedetector.data.*
import com.deepfakedetector.viewmodel.AnalysisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onResultReady: (AnalysisResult) -> Unit,
    onImageResultReady: (ImageAnalysisResult) -> Unit,
    onHistoryClick: () -> Unit,
    onBacktestClick: () -> Unit,
    onEducationClick: () -> Unit = {},
    onMultiModalClick: () -> Unit = {},
    initialUri: Uri? = null,
    viewModel: AnalysisViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedMode by viewModel.selectedMode.collectAsState()
    val progress by viewModel.analysisProgress.collectAsState()

    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }

    // Launcher contenu galerie (vidéo OU image)
    val contentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.analyzeContent(it) }
    }

    // Launcher image spécifique (appareil photo ou galerie images)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.analyzeImage(it) }
    }

    // Launcher vidéo spécifique
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.analyzeVideo(it, it.lastPathSegment ?: "Vidéo") }
    }

    // Uri initiale passée par ShareReceiverActivity
    LaunchedEffect(initialUri) {
        initialUri?.let { viewModel.analyzeContent(it) }
    }

    // Observer résultat vidéo
    LaunchedEffect(uiState.result) {
        uiState.result?.let { onResultReady(it) }
    }

    // Observer résultat image
    LaunchedEffect(uiState.imageResult) {
        uiState.imageResult?.let { onImageResultReady(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("DeepfakeDetector", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onEducationClick) {
                        Icon(Icons.Default.School, "Éducation")
                    }
                    IconButton(onClick = onHistoryClick) {
                        Icon(Icons.Default.History, "Historique")
                    }
                    IconButton(onClick = onBacktestClick) {
                        Icon(Icons.Default.Science, "Backtest")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            when (uiState.status) {
                AnalysisStatus.IDLE -> {
                    IdleContent(
                        selectedMode = selectedMode,
                        onModeSelected = viewModel::selectMode,
                        onScanFromGallery = { contentPickerLauncher.launch("*/*") },
                        onScanVideo      = { videoPickerLauncher.launch("video/*") },
                        onScanImage      = { imagePickerLauncher.launch("image/*") },
                        onScanFromUrl    = { showUrlDialog = true },
                        onHistoryClick   = onHistoryClick,
                        onMultiModalClick = onMultiModalClick
                    )
                }

                AnalysisStatus.COMPLETED -> {
                    // Géré par LaunchedEffect → navigation
                }

                AnalysisStatus.ERROR -> {
                    ErrorContent(
                        error = uiState.error ?: "Erreur inconnue",
                        onRetry = { viewModel.clearResult() }
                    )
                }

                else -> {
                    AnalysisProgressContent(
                        status = uiState.status,
                        stepLabel = uiState.currentStep,
                        stepProgress = uiState.stepProgress,
                        progressSteps = progress,
                        onCancel = viewModel::cancelAnalysis
                    )
                }
            }
        }
    }

    // Dialog URL
    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Analyser depuis une URL") },
            text = {
                Column {
                    Text("Collez un lien TikTok, YouTube ou X :")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = { Text("https://tiktok.com/...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (urlInput.isNotBlank()) {
                        // TODO: téléchargement + analyse URL
                        showUrlDialog = false
                    }
                }) { Text("Analyser") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUrlDialog = false
                    urlInput = ""
                }) { Text("Annuler") }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// IDLE CONTENT — Écran principal
// ─────────────────────────────────────────────────────────────

@Composable
fun IdleContent(
    selectedMode: AnalysisMode,
    onModeSelected: (AnalysisMode) -> Unit,
    onScanFromGallery: () -> Unit,
    onScanVideo: () -> Unit,
    onScanImage: () -> Unit,
    onScanFromUrl: () -> Unit,
    onHistoryClick: () -> Unit,
    onMultiModalClick: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        // Logo
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(
                    brush = Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.RemoveRedEye,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
        }

        Text(
            text = "Détectez les Deepfakes",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Analyse multi-couches — Vidéos & Images IA",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        // Mode selector (vidéo uniquement)
        ModeSelector(
            selectedMode = selectedMode,
            onModeSelected = onModeSelected
        )

        // BOUTON PRINCIPAL — Scanner un contenu (auto-détection)
        Button(
            onClick = onScanFromGallery,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .scale(pulseScale),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
        ) {
            Icon(Icons.Default.Search, null, Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "Scanner un contenu",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        // Boutons spécifiques vidéo / image
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onScanVideo,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.VideoLibrary, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Vidéo")
            }
            OutlinedButton(
                onClick = onScanImage,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Image, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Image")
            }
            OutlinedButton(
                onClick = onScanFromUrl,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Link, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("URL")
            }
        }

        // Partage direct
        InfoCard(
            icon = Icons.Default.Share,
            title = "Partage direct Android",
            description = "Partagez depuis n'importe quelle app pour analyser instantanément (vidéo ou image)"
        )

        // Bouton post complet (multi-modal)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(16.dp),
            onClick  = onMultiModalClick
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f)
                            )
                        )
                    )
                    .padding(16.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text("🔍", style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Analyser un post complet",
                            style      = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Image + texte → détection de désinformation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Stats rapides
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard("6", "Modules\nvidéo", Modifier.weight(1f))
            StatCard("4", "Modules\nimage", Modifier.weight(1f))
            StatCard("100%", "Analyse\nlocale", Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun ModeSelector(
    selectedMode: AnalysisMode,
    onModeSelected: (AnalysisMode) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Mode d'analyse",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnalysisMode.entries.forEach { mode ->
                    val isSelected = mode == selectedMode
                    FilterChip(
                        selected = isSelected,
                        onClick = { onModeSelected(mode) },
                        label = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(mode.label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                Text(mode.durationHint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun InfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium)
                Text(description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text(label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// PROGRESS CONTENT
// ─────────────────────────────────────────────────────────────

@Composable
fun AnalysisProgressContent(
    status: AnalysisStatus,
    stepLabel: String,
    stepProgress: Float,
    progressSteps: List<com.deepfakedetector.data.AnalysisProgress>,
    onCancel: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing)
        ),
        label = "rotation"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Scan animation
        Box(
            modifier = Modifier
                .size(120.dp)
                .rotate(rotation)
                .border(
                    3.dp,
                    Brush.sweepGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0f)
                        )
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Security,
                null,
                modifier = Modifier
                    .size(56.dp)
                    .rotate(-rotation),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            "Analyse en cours…",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            stepLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // Progress bar
        LinearProgressIndicator(
            progress = { stepProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            "${(stepProgress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(Modifier.height(24.dp))

        // Steps complétés
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            progressSteps.takeLast(5).forEach { step ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (step.progressFraction >= 1f) Icons.Default.CheckCircle
                        else Icons.Default.RadioButtonUnchecked,
                        null,
                        tint = if (step.progressFraction >= 1f)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        step.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step.progressFraction >= 1f)
                            MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        OutlinedButton(onClick = onCancel) {
            Icon(Icons.Default.Close, null)
            Spacer(Modifier.width(8.dp))
            Text("Annuler")
        }
    }
}

@Composable
fun ErrorContent(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Error,
            null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Erreur d'analyse",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Default.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text("Réessayer")
        }
    }
}
