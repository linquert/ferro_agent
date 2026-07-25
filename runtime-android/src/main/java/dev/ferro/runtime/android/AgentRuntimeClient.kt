package dev.ferro.runtime.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AgentRuntimeClient(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val mutableRuntime = MutableStateFlow<AgentRuntimeController?>(null)
    val runtime: StateFlow<AgentRuntimeController?> = mutableRuntime.asStateFlow()
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            mutableRuntime.value = (binder as? AgentRuntimeService.LocalBinder)?.runtime()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mutableRuntime.value = null
        }

        override fun onBindingDied(name: ComponentName?) {
            mutableRuntime.value = null
            bound = false
        }
    }

    fun bind() {
        if (bound) return
        bound = applicationContext.bindService(
            Intent(applicationContext, AgentRuntimeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun ensureForegroundStarted() {
        ContextCompat.startForegroundService(
            applicationContext,
            Intent(applicationContext, AgentRuntimeService::class.java)
                .setAction(AgentRuntimeService.ACTION_START),
        )
    }

    override fun close() {
        if (bound) {
            applicationContext.unbindService(connection)
            bound = false
        }
        mutableRuntime.value = null
    }
}
