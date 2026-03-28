package org.matrix.vector.daemon.data

import org.lsposed.lspd.models.Module

data class ProcessScope(val processName: String, val uid: Int)

/**
 * An immutable snapshot of the Daemon's state. Any updates will generate a new copy of this class
 * and atomically swap the reference.
 */
data class DaemonState(
    val managerUid: Int = -1,
    val isCacheReady: Boolean = false,
    val modules: Map<String, Module> = emptyMap(),
    val scopes: Map<ProcessScope, List<Module>> = emptyMap()
)
