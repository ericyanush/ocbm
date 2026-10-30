package com.carlink.navigation

import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Process-local liveness for Templates Host sessions, shared with MainActivity's binder launcher. */
object ClusterBindingState {
    private val activeSessions = Collections.newSetFromMap(ConcurrentHashMap<Any, Boolean>())

    val sessionAlive: Boolean get() = activeSessions.isNotEmpty()

    fun sessionStarted(session: Any) { activeSessions.add(session) }

    fun sessionEnded(session: Any) { activeSessions.remove(session) }

    /** Clear stale ownership before explicitly rebuilding the host task. Late old-session teardown is harmless. */
    fun reset() { activeSessions.clear() }
}
