package com.deepfakedetector.ui.navigation

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.navigation.*
import androidx.navigation.compose.*
import com.deepfakedetector.data.AnalysisResult
import com.deepfakedetector.data.ImageAnalysisResult
import com.deepfakedetector.data.MultiModalResult
import com.deepfakedetector.ui.screens.*

// ─── Routes ───────────────────────────────────────────────────────────────────

sealed class Screen(val route: String) {
    object MultiModal       : Screen("multimodal")
    object MultiModalResult : Screen("multimodal_result/{resultId}") {
        fun createRoute(resultId: String) = "multimodal_result/$resultId"
    }
    object Main        : Screen("main")
    object Result      : Screen("result/{resultId}") {
        fun createRoute(resultId: String) = "result/$resultId"
    }
    object ImageResult : Screen("image_result/{imageId}") {
        fun createRoute(imageId: String) = "image_result/$imageId"
    }
    object History     : Screen("history")
    object Education   : Screen("education")
    object Backtest    : Screen("backtest")
    object Detail      : Screen("detail/{resultId}") {
        fun createRoute(resultId: String) = "detail/$resultId"
    }
}

// ─── NavGraph ─────────────────────────────────────────────────────────────────

@Composable
fun AppNavGraph(
    startDestination: String = Screen.Main.route,
    initialVideoUri: Uri? = null
) {
    val navController      = rememberNavController()
    val imageResults       = remember { mutableMapOf<String, ImageAnalysisResult>() }
    val videoResults       = remember { mutableMapOf<String, AnalysisResult>() }
    val multiModalResults  = remember { mutableMapOf<String, MultiModalResult>() }

    NavHost(
        navController    = navController,
        startDestination = startDestination,
        enterTransition  = { slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)) },
        exitTransition   = { slideOutHorizontally(tween(300)) { -it } + fadeOut(tween(300)) },
        popEnterTransition  = { slideInHorizontally(tween(300)) { -it } + fadeIn(tween(300)) },
        popExitTransition   = { slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300)) }
    ) {

        // ── Main ─────────────────────────────────────────────────────────────
        composable(Screen.Main.route) {
            MainScreen(
                initialUri       = initialVideoUri,
                onResultReady    = { result ->
                    videoResults[result.videoId] = result
                    navController.navigate(Screen.Result.createRoute(result.videoId)) {
                        popUpTo(Screen.Main.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onImageResultReady = { imageResult ->
                    imageResults[imageResult.imageId] = imageResult
                    navController.navigate(Screen.ImageResult.createRoute(imageResult.imageId)) {
                        popUpTo(Screen.Main.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onHistoryClick   = { navController.navigate(Screen.History.route) },
                onEducationClick = { navController.navigate(Screen.Education.route) },
                onBacktestClick  = { navController.navigate(Screen.Backtest.route) },
                onMultiModalClick = { navController.navigate(Screen.MultiModal.route) }
            )
        }

        // ── Result (vidéo) ───────────────────────────────────────────────────
        composable(
            route = Screen.Result.route,
            arguments = listOf(navArgument("resultId") { type = NavType.StringType })
        ) { backStackEntry ->
            val resultId = backStackEntry.arguments?.getString("resultId") ?: return@composable
            val result   = videoResults[resultId] ?: return@composable
            ResultScreen(
                result    = result,
                onBack    = { navController.popBackStack() },
                onHistory = {
                    navController.navigate(Screen.History.route) {
                        popUpTo(Screen.Main.route)
                    }
                },
                onNewScan = { navController.popBackStack() }
            )
        }

        // ── Result (image) ───────────────────────────────────────────────────
        composable(
            route = Screen.ImageResult.route,
            arguments = listOf(navArgument("imageId") { type = NavType.StringType })
        ) { backStackEntry ->
            val imageId = backStackEntry.arguments?.getString("imageId") ?: return@composable
            val imageResult = imageResults[imageId] ?: return@composable
            ImageResultScreen(
                result = imageResult,
                onBack = { navController.popBackStack() }
            )
        }

        // ── History ──────────────────────────────────────────────────────────
        composable(Screen.History.route) {
            HistoryScreen(
                onBack        = { navController.popBackStack() },
                onResultClick = { resultId ->
                    navController.navigate(Screen.Detail.createRoute(resultId))
                }
            )
        }

        // ── Detail (depuis historique) ────────────────────────────────────────
        composable(
            route = Screen.Detail.route,
            arguments = listOf(navArgument("resultId") { type = NavType.StringType })
        ) { backStackEntry ->
            val resultId = backStackEntry.arguments?.getString("resultId") ?: return@composable
            val result   = videoResults[resultId] ?: return@composable
            ResultScreen(
                result    = result,
                onBack    = { navController.popBackStack() },
                onHistory = { navController.popBackStack() }
            )
        }

        // ── Education ────────────────────────────────────────────────────────
        composable(Screen.Education.route) {
            EducationScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // ── Backtest ─────────────────────────────────────────────────────────
        composable(Screen.Backtest.route) {
            BacktestScreen(onBack = { navController.popBackStack() })
        }

        // ── Multi-modal ──────────────────────────────────────────────────────
        composable(Screen.MultiModal.route) {
            MultiModalScreen(
                onBack         = { navController.popBackStack() },
                onResultReady  = { result ->
                    multiModalResults[result.id] = result
                    navController.navigate(Screen.MultiModalResult.createRoute(result.id)) {
                        popUpTo(Screen.MultiModal.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(
            route     = Screen.MultiModalResult.route,
            arguments = listOf(navArgument("resultId") { type = NavType.StringType })
        ) { backStackEntry ->
            val resultId = backStackEntry.arguments?.getString("resultId") ?: return@composable
            val result   = multiModalResults[resultId] ?: return@composable
            MultiModalResultScreen(
                result = result,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
