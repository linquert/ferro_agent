package dev.ferro.runtime.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

internal class RuntimeNotificationController(private val service: Service) {
    private val manager = service.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Agent runtime",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Current Ferro task and controls"
                },
            )
        }
    }

    fun build(view: AgentRuntimeView): Notification {
        val state = RuntimeNotificationPolicy.from(view)
        val launchIntent = service.packageManager.getLaunchIntentForPackage(service.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                service,
                REQUEST_OPEN,
                it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Ferro")
            .setContentText(state.text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(state.ongoing)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        state.actions.forEach { action ->
            val (label, icon, intentAction, requestCode) = when (action) {
                RuntimeNotificationAction.PAUSE -> ActionSpec(
                    "Pause",
                    android.R.drawable.ic_media_pause,
                    AgentRuntimeService.ACTION_PAUSE,
                    REQUEST_PAUSE,
                )
                RuntimeNotificationAction.RESUME -> ActionSpec(
                    "Resume",
                    android.R.drawable.ic_media_play,
                    AgentRuntimeService.ACTION_RESUME,
                    REQUEST_RESUME,
                )
                RuntimeNotificationAction.STOP -> ActionSpec(
                    "Stop",
                    android.R.drawable.ic_menu_close_clear_cancel,
                    AgentRuntimeService.ACTION_STOP,
                    REQUEST_STOP,
                )
            }
            builder.addAction(
                icon,
                label,
                PendingIntent.getService(
                    service,
                    requestCode,
                    Intent(service, AgentRuntimeService::class.java).setAction(intentAction),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    fun update(view: AgentRuntimeView) {
        manager.notify(NOTIFICATION_ID, build(view))
    }

    private data class ActionSpec(
        val label: String,
        val icon: Int,
        val intentAction: String,
        val requestCode: Int,
    )

    companion object {
        const val NOTIFICATION_ID = 41
        private const val CHANNEL_ID = "ferro_agent_runtime"
        private const val REQUEST_OPEN = 410
        private const val REQUEST_PAUSE = 411
        private const val REQUEST_RESUME = 412
        private const val REQUEST_STOP = 413
    }
}
