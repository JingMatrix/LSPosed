package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Executable
import org.lsposed.lspd.util.Utils.Log

/** Represents a registered hook configuration, stored natively by [HookBridge]. */
data class VectorHookRecord(
    val hooker: XposedInterface.Hooker,
    val priority: Int,
    val exceptionMode: ExceptionMode,
)

/**
 * Core interceptor chain engine. Manages recursive hook execution and enforces [ExceptionMode]
 * protections.
 */
class VectorChain(
    private val executable: Executable,
    private val thisObj: Any?,
    private val args: Array<Any?>,
    private val hooks: Array<VectorHookRecord>,
    private val index: Int,
    private val terminal: (thisObj: Any?, args: Array<Any?>) -> Any?,
) : Chain {

    // Tracks if this specific chain node has forwarded execution downstream
    internal var proceedCalled: Boolean = false
        private set

    // Stores the actual result/exception from the rest of the chain/original method
    internal var downstreamResult: Any? = null
    internal var downstreamThrowable: Throwable? = null

    override fun getExecutable(): Executable = executable

    override fun getThisObject(): Any? = thisObj

    override fun getArgs(): List<Any?> = args.toList()

    override fun getArg(index: Int): Any? = args[index]

    override fun proceed(): Any? = proceedWith(thisObj ?: Any(), args)

    override fun proceed(args: Array<Any?>): Any? = proceedWith(thisObj ?: Any(), args)

    override fun proceedWith(thisObject: Any): Any? = proceedWith(thisObject, args)

    override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? {
        proceedCalled = true

        // Reached the end of the modern hooks; trigger the original executable (and legacy hooks)
        if (index >= hooks.size) {
            return executeDownstream { terminal(thisObject, args) }
        }

        val record = hooks[index]
        val nextChain = VectorChain(executable, thisObject, args, hooks, index + 1, terminal)

        return try {
            executeDownstream { record.hooker.intercept(nextChain) }
        } catch (t: Throwable) {
            handleInterceptorException(t, record, nextChain, thisObject, args)
        }
    }

    /**
     * Executes the block and caches the downstream state so parent chains can recover it if the
     * current interceptor crashes during post-processing.
     */
    private inline fun executeDownstream(block: () -> Any?): Any? {
        return try {
            val result = block()
            downstreamResult = result
            result
        } catch (t: Throwable) {
            downstreamThrowable = t
            throw t
        }
    }

    /** Handles exceptions thrown by module interceptors. */
    private fun handleInterceptorException(
        t: Throwable,
        record: VectorHookRecord,
        nextChain: VectorChain,
        currentThis: Any,
        currentArgs: Array<Any?>,
    ): Any? {
        if (record.exceptionMode == ExceptionMode.PASSTHROUGH) {
            throw t
        }

        // DEFAULT or PROTECTIVE mode: log the crash and attempt to rescue the execution.
        Log.e("VectorChain", "Hooker threw exception: ${record.hooker.javaClass.name}", t)

        if (!nextChain.proceedCalled) {
            // Crash occurred BEFORE proceed(). Skip this hooker entirely and drive the chain
            // manually.
            return nextChain.proceedWith(currentThis, currentArgs)
        } else {
            // Crash occurred AFTER proceed(). Swallow the module's crash and return the real
            // downstream state.
            nextChain.downstreamThrowable?.let { throw it }
            return nextChain.downstreamResult
        }
    }
}
