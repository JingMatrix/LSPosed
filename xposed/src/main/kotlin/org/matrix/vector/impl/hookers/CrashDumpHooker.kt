package org.matrix.vector.impl.hookers

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * Intercepts uncaught exceptions in the framework to provide diagnostic logging before the process
 * completely terminates.
 */
object CrashDumpHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        try {
            val throwable = chain.args.firstOrNull() as? Throwable
            if (throwable != null) {
                Log.e("Vector", "Crash unexpectedly", throwable)
            }
        } catch (ignored: Throwable) {}
        return chain.proceed()
    }
}
