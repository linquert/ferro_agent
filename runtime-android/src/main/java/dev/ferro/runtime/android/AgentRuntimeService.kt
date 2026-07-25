package dev.ferro.runtime.android

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AgentRuntimeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notifications: RuntimeNotificationController
    private lateinit var companionOverlay: CompanionOverlayController
    lateinit var runtime: AgentRuntimeController
        private set
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        notifications = RuntimeNotificationController(this)
        val environment = AndroidRuntimeEnvironment(this)
        runtime = AgentRuntimeController(
            serviceScope,
            environment.sessionFactory,
            environment.recoveryRepository,
        )
        companionOverlay = CompanionOverlayController(this, runtime)
        runtime.restore()
        serviceScope.launch(Dispatchers.Main.immediate) {
            runtime.view.collect { view ->
                companionOverlay.update(view)
                if (foreground) notifications.update(view)
                if (foreground && view.snapshot.phase == AgentRuntimePhase.IDLE) {
                    leaveForeground()
                    stopSelf()
                }
                if (view.snapshot.phase == AgentRuntimePhase.FAILED) {
                    leaveForeground()
                    stopSelf()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        when (intent?.action) {
            ACTION_PAUSE -> runtime.pauseActiveTurn()
            ACTION_RESUME -> runtime.resumeActiveTurn()
            ACTION_STOP -> runtime.interruptActiveTurn()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        companionOverlay.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun enterForeground() {
        foreground = true
        val notification = notifications.build(runtime.view.value)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(
                RuntimeNotificationController.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(RuntimeNotificationController.NOTIFICATION_ID, notification)
        }
    }

    private fun leaveForeground() {
        if (!foreground) return
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    inner class LocalBinder : Binder() {
        fun runtime(): AgentRuntimeController = runtime
    }

    companion object {
        const val ACTION_START = "dev.ferro.runtime.android.action.START"
        const val ACTION_PAUSE = "dev.ferro.runtime.android.action.PAUSE"
        const val ACTION_RESUME = "dev.ferro.runtime.android.action.RESUME"
        const val ACTION_STOP = "dev.ferro.runtime.android.action.STOP"
    }
}
