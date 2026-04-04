package com.deepfakedetector.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.deepfakedetector.data.*
import com.deepfakedetector.ui.theme.DeepfakeColors
import com.deepfakedetector.viewmodel.MultiModalPhase
import com.deepfakedetector.viewmodel.MultiModalViewModel

// ─── Écran d'entrée ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiModalScreen(
    onBack: () -> Unit,
    onResultReady: (MultiModalResult) -> Unit,
    viewModel: MultiModalViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var imageUri  by remember { mutableStateOf<Uri?>(null) }
    var text      by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { imageUri = it } }

    // Observer résultat
    LaunchedEffect(uiState.result) {
        uiState.result?.let { onResultReady(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analyser un post", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Intro ────────────────────────────────────────────────────
            MultiModalIntroCard()

            when (uiState.phase) {
                MultiModalPhase.INPUT -> {
                    // ── Sélection image ──────────────────────────────────
                    ImagePickerCard(
                        imageUri  = imageUri,
                        onPick    = { imagePicker.launch("image/*") },
                        onClear   = { imageUri = null }
                    )

                    // ── Saisie texte ─────────────────────────────────────
                    TextInputCard(
                        text     = text,
                        onChange = { text = it }
                    )

                    // ── Bouton analyser ──────────────────────────────────
                    Button(
                        onClick  = {
                            imageUri?.let { uri ->
                                viewModel.analyze(uri, text)
                            }
                        },
                        enabled  = imageUri != null,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape    = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Analytics, null, Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Analyser le post",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (imageUri == null) {
                        Text(
                            "Sélectionnez d'abord une image pour activer l'analyse",
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                MultiModalPhase.ANALYZING -> {
                    MultiModalProgressCard(
                        progress   = uiState.progress,
                        statusText = uiState.statusText
                    )
                }

                MultiModalPhase.DONE -> {
                    // Géré par LaunchedEffect → navigation
                }

                MultiModalPhase.ERROR -> {
                    ErrorCard(uiState.errorMsg ?: "Erreur inconnue") {
                        viewModel.reset()
                    }
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

// ─── Écran résultat ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiModalResultScreen(
    result: MultiModalResult,
    onBack: () -> Unit
) {
    val verdictColor = when (result.verdictLevel) {
        VerdictLevel.FAKE, VerdictLevel.LIKELY_FAKE -> DeepfakeColors.DangerRed
        VerdictLevel.REAL, VerdictLevel.LIKELY_REAL -> DeepfakeColors.SafeGreen
        VerdictLevel.UNCERTAIN                      -> DeepfakeColors.WarnAmber
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Résultat — Post", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Verdict
            ManipulationIndexCard(result, verdictColor)

            // Scores détaillés par module
            TripleScoreCard(result)

            // Explication
            MultiModalExplanationCard(result.explanation)

            // Key findings
            if (result.keyFindings.isNotEmpty()) {
                FindingsCard(result.keyFindings)
            }

            // Warnings
            if (result.warnings.isNotEmpty()) {
                MultiModalWarningsCard(result.warnings)
            }

            // Conseils
            FactCheckAdviceCard(result.verdictLevel)

            Spacer(Modifier.height(80.dp))
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// COMPOSANTS — SAISIE
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun MultiModalIntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(DeepfakeColors.ElectricContainer, DeepfakeColors.Surface2))
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text("🔍", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Détection multi-modale",
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "Analysez simultanément une image et son texte associé pour détecter la manipulation " +
                    "globale : image IA, texte généré, incohérences entre les deux.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Badges modules
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModuleBadge("🖼️ Image", DeepfakeColors.Electric)
                    ModuleBadge("📝 Texte", DeepfakeColors.WarnAmber)
                    ModuleBadge("🔗 Cohérence", DeepfakeColors.SafeGreen)
                }
            }
        }
    }
}

@Composable
private fun ModuleBadge(label: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(20.dp)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun ImagePickerCard(imageUri: Uri?, onPick: () -> Unit, onClear: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "1. Image du post",
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (imageUri == null) {
                OutlinedButton(
                    onClick  = onPick,
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(28.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("Sélectionner une image", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "Importez une capture d'écran de post, une photo ou une image suspecte",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DeepfakeColors.SafeContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Image, null, tint = DeepfakeColors.SafeGreen)
                        }
                        Column {
                            Text(
                                "Image sélectionnée ✓",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = DeepfakeColors.SafeGreen
                            )
                            Text(
                                imageUri.lastPathSegment ?: "image",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun TextInputCard(text: String, onChange: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    "2. Texte associé",
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${text.length} car.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value         = text,
                onValueChange = onChange,
                modifier      = Modifier.fillMaxWidth(),
                placeholder   = {
                    Text(
                        "Collez ici la légende, le titre ou la description du post…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                minLines = 4,
                maxLines = 10,
                shape    = RoundedCornerShape(10.dp)
            )
            Text(
                "Laissez vide si le post n'a pas de texte — seule l'image sera analysée",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MultiModalProgressCard(progress: Float, statusText: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                progress     = { progress },
                modifier     = Modifier.size(72.dp),
                color        = DeepfakeColors.Electric,
                trackColor   = DeepfakeColors.Electric.copy(alpha = 0.15f),
                strokeWidth  = 6.dp
            )
            Text(
                "${(progress * 100).toInt()}%",
                style      = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color      = DeepfakeColors.Electric
            )
            Text(statusText, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)

            // Étapes visuelles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ProgressStep("🖼️", "Image",    progress >= 0.40f)
                ProgressStep("📝", "Texte",     progress >= 0.60f)
                ProgressStep("🔗", "Cohérence", progress >= 0.85f)
            }
        }
    }
}

@Composable
private fun ProgressStep(emoji: String, label: String, done: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (done) DeepfakeColors.SafeGreen.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(if (done) "✓" else emoji, fontSize = 18.sp)
        }
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = if (done) DeepfakeColors.SafeGreen else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(containerColor = DeepfakeColors.DangerContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, color = DeepfakeColors.DangerRed, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Réessayer")
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// COMPOSANTS — RÉSULTAT
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun ManipulationIndexCard(result: MultiModalResult, color: Color) {
    var animated by remember { mutableStateOf(false) }
    val animatedScore by animateFloatAsState(
        targetValue   = if (animated) result.manipulationIndex else 0f,
        animationSpec = tween(1400, easing = FastOutSlowInEasing),
        label         = "manipulation"
    )
    LaunchedEffect(Unit) { animated = true }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.18f), color.copy(alpha = 0.04f))
                ))
                .padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Badge MULTI-MODAL
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ManageSearch, null, Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("ANALYSE MULTI-MODALE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Score circulaire grand format
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress     = { animatedScore },
                        modifier     = Modifier.size(120.dp),
                        color        = color,
                        trackColor   = color.copy(alpha = 0.15f),
                        strokeWidth  = 10.dp
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${(animatedScore * 100).toInt()}%",
                            style      = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color      = color
                        )
                        Text(
                            "manipulation",
                            style = MaterialTheme.typography.labelSmall,
                            color = color.copy(alpha = 0.8f)
                        )
                    }
                }

                Text(
                    result.verdictText,
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color      = color,
                    textAlign  = TextAlign.Center
                )

                // Badge confiance
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        "Confiance : ${result.confidencePercent}% — ${result.reliabilityLevel.label}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style    = MaterialTheme.typography.labelMedium,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TripleScoreCard(result: MultiModalResult) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text("Scores par module", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Surface(
                    color = DeepfakeColors.ElectricContainer,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        "Cohérence = poids x2",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style    = MaterialTheme.typography.labelSmall,
                        color    = DeepfakeColors.Electric
                    )
                }
            }

            HorizontalDivider()

            result.scores.image?.let { s ->
                ScoreModuleRow(
                    emoji       = "🖼️",
                    name        = "Image",
                    weight      = "30%",
                    description = "Artefacts IA · bruit · textures · EXIF",
                    score       = s,
                    color       = DeepfakeColors.Electric
                )
            }
            result.scores.text?.let { s ->
                ScoreModuleRow(
                    emoji       = "📝",
                    name        = "Texte",
                    weight      = "20%",
                    description = "Patterns LLM · ton alarmiste · exagération",
                    score       = s,
                    color       = DeepfakeColors.WarnAmber
                )
            }
            result.scores.coherence?.let { s ->
                ScoreModuleRow(
                    emoji       = "🔗",
                    name        = "Cohérence",
                    weight      = "50%",
                    description = "Correspondance image/texte · mismatch ton/intensité",
                    score       = s,
                    color       = DeepfakeColors.SafeGreen
                )
            }

            // Récap visuel type post réseaux sociaux
            HorizontalDivider()
            SocialPostSummary(result)
        }
    }
}

@Composable
private fun ScoreModuleRow(
    emoji: String, name: String, weight: String, description: String,
    score: ModuleScore, color: Color
) {
    var showDetails by remember { mutableStateOf(false) }
    val scoreColor = when {
        score.score > 0.65f -> DeepfakeColors.DangerRed
        score.score > 0.45f -> DeepfakeColors.WarnAmber
        else                -> DeepfakeColors.SafeGreen
    }
    val scoreLabel = when {
        score.score > 0.65f -> "suspect ⚠️"
        score.score > 0.45f -> "douteux"
        else                -> "ok ✓"
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { showDetails = !showDetails },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Text(emoji, fontSize = 18.sp) }

            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp)) {
                        Text(weight, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall, color = color)
                    }
                }
                Text(description, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${score.scorePercent}%", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = scoreColor)
                Text(scoreLabel, style = MaterialTheme.typography.labelSmall, color = scoreColor)
            }
        }

        AnimatedVisibility(visible = showDetails) {
            Column(Modifier.padding(start = 52.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                score.details.take(5).forEach { detail ->
                    Text("• $detail", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// Récap visuel style réseaux sociaux — comme demandé dans la spec
@Composable
private fun SocialPostSummary(result: MultiModalResult) {
    val imageScore = result.scores.image?.score ?: 0f
    val textScore  = result.scores.text?.score  ?: 0f
    val cohScore   = result.scores.coherence?.score ?: 0f

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Résumé", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        SummaryLine("🖼️ Image",    imageScore)
        SummaryLine("✍️ Texte",    textScore)
        SummaryLine("🔗 Cohérence", cohScore)

        Spacer(Modifier.height(4.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
            Text(
                "🧠 ${result.scores.coherence?.details?.lastOrNull() ?: result.explanation}",
                modifier = Modifier.padding(10.dp),
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, score: Float) {
    val (statusText, statusColor) = when {
        score > 0.65f -> "suspect ⚠️"   to DeepfakeColors.DangerRed
        score > 0.45f -> "douteux"       to DeepfakeColors.WarnAmber
        else          -> "ok ✓"          to DeepfakeColors.SafeGreen
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress   = { score },
                modifier   = Modifier.width(80.dp).height(5.dp).clip(RoundedCornerShape(50)),
                color      = statusColor,
                trackColor = statusColor.copy(alpha = 0.15f)
            )
            Text("${(score * 100).toInt()}%", style = MaterialTheme.typography.labelSmall,
                color = statusColor, fontWeight = FontWeight.Bold)
            Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
        }
    }
}

@Composable
private fun MultiModalExplanationCard(explanation: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Psychology, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Analyse globale", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(explanation, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FindingsCard(findings: List<String>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FindInPage, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Éléments détectés", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            findings.forEach { finding ->
                Text(finding, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MultiModalWarningsCard(warnings: List<String>) {
    Surface(
        color    = DeepfakeColors.DangerContainer,
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⚠️ Alertes", style = MaterialTheme.typography.labelLarge,
                color = DeepfakeColors.DangerRed, fontWeight = FontWeight.Bold)
            warnings.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = DeepfakeColors.DangerRed)
            }
        }
    }
}

@Composable
private fun FactCheckAdviceCard(verdict: VerdictLevel) {
    val tips = when (verdict) {
        VerdictLevel.FAKE, VerdictLevel.LIKELY_FAKE -> listOf(
            "Cherchez l'image originale avec Google Images ou TinEye",
            "Vérifiez sur des sites de fact-checking (AFP Factuel, Snopes, Checknews)",
            "Ne partagez pas ce contenu sans vérification préalable",
            "Signalez le contenu sur la plateforme concernée si vous confirmez la manipulation"
        )
        VerdictLevel.UNCERTAIN -> listOf(
            "En cas de doute, ne partagez pas",
            "Cherchez des sources secondaires qui confirment l'information",
            "Consultez des fact-checkers reconnus"
        )
        else -> listOf(
            "Le contenu semble authentique mais restez vigilant",
            "Vérifiez toujours la source originale avant de partager"
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = DeepfakeColors.ElectricContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("💡", style = MaterialTheme.typography.titleSmall)
                Text("Que faire ?", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = DeepfakeColors.Electric)
            }
            tips.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
