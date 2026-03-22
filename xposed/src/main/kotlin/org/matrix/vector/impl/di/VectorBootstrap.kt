package org.matrix.vector.impl.di

import android.content.pm.ApplicationInfo
import java.lang.reflect.Executable

/** Data class representing package load information for the legacy bridge. */
data class LegacyPackageInfo(
    val packageName: String,
    val processName: String,
    val classLoader: ClassLoader,
    val appInfo: ApplicationInfo,
    val isFirstApplication: Boolean,
)

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

/** Functional interface for executing the original method within a legacy hook bypass. */
fun interface OriginalInvoker {
    fun invoke(): Any?
}

/**
 * The explicit contract that the `core` (legacy) module must fulfill. The modern framework will
 * call these methods at the appropriate lifecycle moments.
 */
interface LegacyFrameworkDelegate {
    /** Instructs the legacy bridge to load legacy modules. */
    fun loadModules(activityThread: Any)

    /** Dispatches a package load event to legacy XC_LoadPackage callbacks. */
    fun onPackageLoaded(info: LegacyPackageInfo)

    /** Dispatches the system server load event to legacy callbacks. */
    fun onSystemServerLoaded(classLoader: ClassLoader)

    /** Processes legacy hooks wrapped around the original method invocation. */
    fun processLegacyHook(
        executable: Executable,
        thisObject: Any?,
        args: Array<Any?>,
        legacyHooks: Array<Any?>,
        invokeOriginal: OriginalInvoker,
    ): Any?

    /** Checks if resource hooking is disabled by the legacy configuration. */
    val isResourceHookingDisabled: Boolean

    fun setPackageNameForResDir(packageName: String, resDir: String?)

    /** Checks if a legacy module is active for the given package name. */
    fun hasLegacyModule(packageName: String): Boolean
}

/** The central registry for framework bootstrapping. */
object VectorBootstrap {
    @Volatile
    var delegate: LegacyFrameworkDelegate? = null
        private set

    fun init(frameworkDelegate: LegacyFrameworkDelegate) {
        check(delegate == null) { "VectorBootstrap is already initialized!" }
        delegate = frameworkDelegate
    }

    /** Helper to safely execute operations requiring the legacy delegate. */
    inline fun withLegacy(block: (LegacyFrameworkDelegate) -> Unit) {
        delegate?.let(block)
    }
}
