package com.deepfakedetector.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.deepfakedetector.data.*
import com.deepfakedetector.ui.theme.DeepfakeColors
import com.deepfakedetector.viewmodel.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onResultClick: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val results by viewModel.results.collectAsState()
    val stats   by viewModel.stats.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedFilter   by remember { mutableStateOf(HistoryFilter.ALL) }

    val filteredResults = remember(results, selectedFilter) {
        when (selectedFilter) {
            HistoryFilter.ALL     -> results
            HistoryFilter.FAKE    -> results.filter { it.verdictLevel in listOf(VerdictLevel.LIKELY_FAKE, VerdictLevel.FAKE) }
            HistoryFilter.REAL    -> results.filter { it.verdictLevel in listOf(VerdictLevel.LIKELY_REAL, VerdictLevel.REAL) }
            HistoryFilter.UNSURE  -> results.filter { it.verdictLevel == VerdictLevel.UNCERTAIN }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historique", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (results.isNotEmpty()) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "Effacer l'historique",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->

        if (results.isEmpty()) {
            // ── État vide ────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("📂", style = MaterialTheme.typography.displayMedium)
                    Text(
                        "Aucune analyse effectuée",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Vos analyses apparaîtront ici",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // ── Stats globales ───────────────────────────────────────────
                item {
                    stats?.let { s ->
                        HistoryStatsCard(
                            total      = s.total,
                            fakeCount  = s.fakeCounts,
                            realCount  = s.realCounts,
                            avgScore   = s.avgScore30d
                        )
                    }
                }

                // ── Filtres ──────────────────────────────────────────────────
                item {
                    FilterChipRow(
                        selected  = selectedFilter,
                        onSelect  = { selectedFilter = it },
                        counts    = mapOf(
                            HistoryFilter.ALL   to results.size,
                            HistoryFilter.FAKE  to results.count { it.verdictLevel in listOf(VerdictLevel.LIKELY_FAKE, VerdictLevel.FAKE) },
                            HistoryFilter.REAL  to results.count { it.verdictLevel in listOf(VerdictLevel.LIKELY_REAL, VerdictLevel.REAL) },
                            HistoryFilter.UNSURE to results.count { it.verdictLevel == VerdictLevel.UNCERTAIN }
                        )
                    )
                }

                // ── Liste ────────────────────────────────────────────────────
                items(
                    items = filteredResults,
                    key   = { it.videoId }
                ) { result ->
                    HistoryItemCard(
                        result  = result,
                        onClick = { onResultClick(result.videoId) },
                        onDelete = { viewModel.deleteResult(result.videoId) }
                    )
                }

                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    // ── Dialog suppression ───────────────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title  = { Text("Effacer l'historique") },
            text   = { Text("Toutes les ${results.size} analyses seront supprimées. Cette action est irréversible.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showDeleteDialog = false
                    }
                ) {
                    Text("Effacer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}

// ─── Carte stats ──────────────────────────────────────────────────────────────

@Composable
private fun HistoryStatsCard(
    total: Int,
    fakeCount: Int,
    realCount: Int,
    avgScore: Float
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            DeepfakeColors.ElectricContainer,
                            DeepfakeColors.Surface1
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Résumé (30 derniers jours)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatBox(label = "Total", value = "$total", icon = "📊")
                    StatBox(label = "Fakes", value = "$fakeCount", icon = "⚠️")
                    StatBox(label = "Réels", value = "$realCount", icon = "✅")
                    StatBox(
                        label = "Score moy.",
                        value = "${(avgScore * 100).toInt()}%",
                        icon  = "📈"
                    )
                }
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, icon: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, style = MaterialTheme.typography.titleMedium)
        Text(
            value,
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── Filtres ──────────────────────────────────────────────────────────────────

enum class HistoryFilter(val label: String) {
    ALL("Tout"), FAKE("Fakes"), REAL("Réels"), UNSURE("Incertains")
}

@Composable
private fun FilterChipRow(
    selected: HistoryFilter,
    onSelect: (HistoryFilter) -> Unit,
    counts: Map<HistoryFilter, Int>
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        HistoryFilter.values().forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick  = { onSelect(filter) },
                label    = { Text("${filter.label} (${counts[filter] ?: 0})") }
            )
        }
    }
}

// ─── Carte item ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryItemCard(
    result: AnalysisResult,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val dateStr  = remember(result.timestamp) {
        SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
            .format(Date(result.timestamp))
    }
    val verdictColor = when (result.verdictLevel) {
        VerdictLevel.FAKE, VerdictLevel.LIKELY_FAKE -> DeepfakeColors.DangerRed
        VerdictLevel.REAL, VerdictLevel.LIKELY_REAL -> DeepfakeColors.SafeGreen
        else -> DeepfakeColors.WarnAmber
    }
    val verdictEmoji = when (result.verdictLevel) {
        VerdictLevel.FAKE, VerdictLevel.LIKELY_FAKE -> "⚠️"
        VerdictLevel.REAL, VerdictLevel.LIKELY_REAL -> "✅"
        else -> "❓"
    }

    Card(
        onClick   = onClick,
        shape     = RoundedCornerShape(14.dp),
        modifier  = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Score circle
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(verdictColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(verdictEmoji, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${(result.overallScore * 100).toInt()}%",
                        style      = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color      = verdictColor
                    )
                }
            }

            // Infos
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.verdictLevel.displayName(),
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color      = verdictColor
                )
                Text(
                    result.explanation,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    ModeChip(result.analysisMode)
                    Text(
                        dateStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Menu kebab
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded        = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text    = { Text("Voir le résultat") },
                        onClick = { showMenu = false; onClick() },
                        leadingIcon = { Icon(Icons.Default.Visibility, null) }
                    )
                    DropdownMenuItem(
                        text    = { Text("Supprimer", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete, null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeChip(mode: AnalysisMode) {
    val (label, color) = when (mode) {
        AnalysisMode.INSTANT  -> "Instant"  to DeepfakeColors.Electric
        AnalysisMode.FAST     -> "Rapide"   to DeepfakeColors.WarnAmber
        AnalysisMode.COMPLETE -> "Complet"  to DeepfakeColors.SafeGreen
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private fun VerdictLevel.displayName() = when (this) {
    VerdictLevel.FAKE         -> "DEEPFAKE CONFIRMÉ"
    VerdictLevel.LIKELY_FAKE  -> "Probablement Fake"
    VerdictLevel.UNCERTAIN    -> "Incertain"
    VerdictLevel.LIKELY_REAL  -> "Probablement Réel"
    VerdictLevel.REAL         -> "Authentique"
}
