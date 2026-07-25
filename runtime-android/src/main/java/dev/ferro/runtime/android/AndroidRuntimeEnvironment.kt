package dev.ferro.runtime.android

import android.content.Context
import dev.ferro.core.UuidIdGenerator
import dev.ferro.platform.android.AndroidJsonlAgentEventStore

internal class AndroidRuntimeEnvironment(context: Context) {
    private val applicationContext = context.applicationContext
    private val ids = UuidIdGenerator()
    private val eventStore = AndroidJsonlAgentEventStore(applicationContext, ids)

    val sessionFactory: RuntimeSessionFactory =
        AndroidRuntimeSessionFactory(applicationContext, eventStore, ids)
    val recoveryRepository: RuntimeRecoveryRepository = AndroidRuntimeRecoveryRepository(
        FileActiveRuntimeStore(applicationContext),
        eventStore,
    )
}
