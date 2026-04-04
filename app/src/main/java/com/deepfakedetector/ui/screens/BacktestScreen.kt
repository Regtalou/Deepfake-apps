package com.deepfakedetector.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.deepfakedetector.data.BacktestReport
import com.deepfakedetector.ui.theme.DeepfakeColors
import com.deepfakedetector.viewmodel.BacktestUiPhase
import com.deepfakedetector.viewmodel.BacktestViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BacktestScreen(
    onBack: () -> Unit,
    viewModel: BacktestViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val history by viewModel.history.collectAsState()

    var fakeUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var realUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val fakePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> fakeUris = uris }

    val realPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> realUris = uris }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backtest Engine", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (uiState.phase != BacktestUiPhase.IDLE) {
                        IconButton(onClick = { viewModel.reset() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Réinitialiser")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Intro ────────────────────────────────────────────────────────
            item { BacktestIntroCard() }

            when (uiState.phase) {

                // ── Setup ────────────────────────────────────────────────────
                BacktestUiPhase.IDLE -> {
                    item {
                        BacktestSetupCard(
                            fakeCount = fakeUris.size,
                            realCount = realUris.size,
                            onPickFake = { fakePickerLauncher.launch("video/*") },
                            onPickReal = { realPickerLauncher.launch("video/*") },
                            onStart    = {
                                if (fakeUris.isNotEmpty() && realUris.isNotEmpty()) {
                                    viewModel.runBacktest(fakeUris, realUris)
                                }
                            }
                        )
                    }
                }

                // ── Progress ─────────────────────────────────────────────────
                BacktestUiPhase.RUNNING -> {
                    item {
                        BacktestProgressCard(
                            progress   = uiState.progress,
                            statusText = uiState.statusText
                        )
                    }
                }

                // ── Résultats ────────────────────────────────────────────────
                BacktestUiPhase.DONE -> {
                    uiState.report?.let { report ->
                        item { MetricsOverviewCard(report, uiState.calibration?.aucRoc) }
                        item { ConfusionMatrixCard(report) }
                        uiState.calibration?.let { cal ->
                            item { ThresholdCard(cal.optimalThreshold, cal.aucRoc) }
                        }
                        item { ReportTextCard(uiState.reportText) }
                    }
                }

                // ── Erreur ───────────────────────────────────────────────────
                BacktestUiPhase.ERROR -> {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors   = CardDefaults.cardColors(
                                containerColor = DeepfakeColors.DangerContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    tint = DeepfakeColors.DangerRed
                                )
                                Text(
                                    uiState.errorMsg ?: "Erreur inconnue",
                                    color = DeepfakeColors.DangerRed
                                )
                            }
                        }
                    }
                }
            }

            // ── Historique des runs ──────────────────────────────────────────
            if (history.isNotEmpty()) {
                item {
                    Text(
                        "Historique des backtests",
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(history.take(5)) { run ->
                    HistoryRunItem(run.accuracy, run.f1Score, run.auc, run.totalVideos, run.runAt)
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ─── Composants ───────────────────────────────────────────────────────────────

@Composable
private fun BacktestIntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(DeepfakeColors.Surface2, DeepfakeColors.ElectricContainer)
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text("⚗️", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Backtest Engine",
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "Évaluez la précision du détecteur sur votre propre jeu de données. Fournissez des vidéos labellisées (fake & réel) pour mesurer accuracy, précision, rappel et F1-score.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BacktestSetupCard(
    fakeCount: Int,
    realCount: Int,
    onPickFake: () -> Unit,
    onPickReal: () -> Unit,
    onStart: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Configuration du dataset", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            // Vidéos fake
            OutlinedButton(
                onClick  = onPickFake,
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.outlinedButtonColors(
                    contentColor = DeepfakeColors.DangerRed
                )
            ) {
                Icon(Icons.Default.VideoFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (fakeCount == 0) "Sélectionner vidéos FAKE"
                    else "$fakeCount vidéo(s) fake sélectionnée(s)"
                )
            }

            // Vidéos réelles
            OutlinedButton(
                onClick  = onPickReal,
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.outlinedButtonColors(
                    contentColor = DeepfakeColors.SafeGreen
                )
            ) {
                Icon(Icons.Default.VideoFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (realCount == 0) "Sélectionner vidéos RÉELLES"
                    else "$realCount vidéo(s) réelle(s) sélectionnée(s)"
                )
            }

            if (fakeCount > 0 && realCount > 0) {
                Surface(
                    color = DeepfakeColors.ElectricContainer,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "📊 Dataset : $fakeCount fakes + $realCount réels = ${fakeCount + realCount} vidéos au total",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = DeepfakeColors.Electric
                    )
                }
            }

            Button(
                onClick  = onStart,
                enabled  = fakeCount > 0 && realCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Lancer le backtest")
            }
        }
    }
}

@Composable
private fun BacktestProgressCard(progress: Float, statusText: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(72.dp),
                color    = DeepfakeColors.Electric,
                strokeWidth = 6.dp
            )
            Text(
                "${(progress * 100).toInt()}%",
                style      = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color      = DeepfakeColors.Electric
            )
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun MetricsOverviewCard(report: BacktestReport, auc: Float?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Métriques globales",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricBox("Accuracy",  report.accuracy,  DeepfakeColors.Electric)
                MetricBox("Précision", report.precision, DeepfakeColors.SafeGreen)
                MetricBox("Rappel",    report.recall,    DeepfakeColors.WarnAmber)
                MetricBox("F1-Score",  report.f1Score,   DeepfakeColors.DangerRed)
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text("AUC-ROC", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (auc != null) "%.3f".format(auc) else "—",
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color      = DeepfakeColors.Electric
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Vidéos testées", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${report.totalVideos}",
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricBox(label: String, value: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${(value * 100).toInt()}%",
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color      = color
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConfusionMatrixCard(report: BacktestReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Matrice de confusion",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // Header
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1.2f))
                Text(
                    "Prédit FAKE",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = DeepfakeColors.DangerRed
                )
                Text(
                    "Prédit RÉEL",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = DeepfakeColors.SafeGreen
                )
            }

            // Ligne FAKE
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Réel FAKE",
                    modifier = Modifier.weight(1.2f),
                    style = MaterialTheme.typography.labelMedium,
                    color = DeepfakeColors.DangerRed
                )
                ConfusionCell(report.truePositives, DeepfakeColors.SafeGreen, "TP", Modifier.weight(1f))
                ConfusionCell(report.falseNegatives, DeepfakeColors.DangerRed.copy(alpha = 0.5f), "FN", Modifier.weight(1f))
            }

            // Ligne RÉEL
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Réel RÉEL",
                    modifier = Modifier.weight(1.2f),
                    style = MaterialTheme.typography.labelMedium,
                    color = DeepfakeColors.SafeGreen
                )
                ConfusionCell(report.falsePositives, DeepfakeColors.WarnAmber.copy(alpha = 0.5f), "FP", Modifier.weight(1f))
                ConfusionCell(report.trueNegatives, DeepfakeColors.SafeGreen, "TN", Modifier.weight(1f))
            }

            // Légende
            Text(
                "TP = vrai positif (fake détecté) · TN = vrai négatif · FP = fausse alarme · FN = deepfake manqué",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ConfusionCell(value: Int, color: Color, label: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$value",
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color      = color
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ThresholdCard(optimalThreshold: Float, auc: Float) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = DeepfakeColors.ElectricContainer)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text("🎯", style = MaterialTheme.typography.titleLarge)
            Column {
                Text(
                    "Seuil optimal calculé",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "%.1f%%".format(optimalThreshold * 100),
                    style      = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color      = DeepfakeColors.Electric
                )
                Text(
                    "AUC-ROC : ${"%.3f".format(auc)} — Seuil qui maximise le F1-Score",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReportTextCard(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        onClick  = { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text("Rapport complet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            AnimatedVisibility(visible = expanded) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text,
                        modifier  = Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),
                        style     = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 11.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRunItem(
    accuracy: Float,
    f1Score: Float,
    auc: Float,
    totalVideos: Int,
    runAt: Long
) {
    val dateStr = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(runAt))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Column {
                Text(dateStr, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$totalVideos vidéos", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SmallMetric("Acc", accuracy)
                SmallMetric("F1", f1Score)
                SmallMetric("AUC", auc)
            }
        }
    }
}

@Composable
private fun SmallMetric(label: String, value: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${(value * 100).toInt()}%",
            style      = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.primary
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
