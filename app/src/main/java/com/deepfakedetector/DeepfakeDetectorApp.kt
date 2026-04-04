package com.deepfakedetector

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DeepfakeDetectorApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    // Work Manager avec Hilt
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Canal : analyse terminée
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ANALYSIS_DONE,
                    "Analyse terminée",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notification quand une analyse deepfake est terminée"
                }
            )

            // Canal : alertes vidéos virales suspectes
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_VIRAL_ALERT,
                    "Alertes virales",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerte quand une vidéo suspecte devient virale"
                }
            )
        }
    }

    companion object {
        const val CHANNEL_ANALYSIS_DONE = "analysis_done"
        const val CHANNEL_VIRAL_ALERT   = "viral_alert"
    }
}
