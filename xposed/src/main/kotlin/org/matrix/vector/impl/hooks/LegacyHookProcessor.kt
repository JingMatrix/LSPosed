package org.matrix.vector.impl.hooks

import java.lang.reflect.Executable

fun interface OriginalInvoker {
    fun invoke(): Any?
}

interface LegacyHookProcessor {
    /** Executes legacy hooks wrapped around the original method invocation. */
    fun process(
        executable: Executable,
        thisObject: Any?,
        args: Array<Any?>,
        legacyHooks: Array<Any?>,
        invokeOriginal: OriginalInvoker,
    ): Any?
}

/** Registry to hold the injected LegacyHookProcessor. */
object LegacySupport {
    var processor: LegacyHookProcessor? = null
}

/**
 * Adapter for backward compatibility with [XposedBridge.LegacyApiSupport]. Contains state mutations
 * strictly for legacy module support.
 */
class VectorLegacyCallback<T : Executable>(
    val method: T,
    var thisObject: Any?,
    var args: Array<Any?>,
) {
    var result: Any? = null
        private set

    var throwable: Throwable? = null
        private set

    var isSkipped = false
        private set

    fun setResult(res: Any?) {
        result = res
        throwable = null
        isSkipped = true
    }

    fun setThrowable(t: Throwable?) {
        result = null
        throwable = t
        isSkipped = true
    }
}
