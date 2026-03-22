package org.matrix.vector.impl.hookers

import io.github.libxposed.api.XposedInterface
import org.matrix.vector.impl.VectorLifecycleManager
import org.matrix.vector.impl.core.VectorDeopter
import org.matrix.vector.impl.di.VectorBootstrap
import org.matrix.vector.impl.hooks.VectorHookBuilder

/**
 * Intercepts the system server initialization process to deoptimize targets and attach bootstrap
 * hookers dynamically.
 */
object HandleSystemServerProcessHooker : XposedInterface.Hooker {

    interface Callback {
        fun onSystemServerLoaded(classLoader: ClassLoader)
    }

    @Volatile var callback: Callback? = null

    @Volatile
    var systemServerCL: ClassLoader? = null
        private set

    override fun intercept(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val classLoader = Thread.currentThread().contextClassLoader
        if (classLoader != null) {
            initSystemServer(classLoader)
        }
        return result
    }

    /** Performs system server initialization. */
    fun initSystemServer(classLoader: ClassLoader) {
        if (systemServerCL != null) return // Ensure this only runs once
        systemServerCL = classLoader

        // Deoptimize heavily inlined system server paths
        VectorDeopter.deoptSystemServerMethods(classLoader)

        // Dynamically locate and hook the bootstrap service initializer
        val sysServerClass = Class.forName("com.android.server.SystemServer", false, classLoader)
        val startMethod = sysServerClass.getDeclaredMethod("startBootstrapServices")

        VectorHookBuilder(startMethod).intercept(StartBootstrapServicesHooker)
        callback?.onSystemServerLoaded(classLoader)
    }
}

/** Dispatches the core system server bootstrap event to the module engines. */
object StartBootstrapServicesHooker : XposedInterface.Hooker {

    override fun intercept(chain: XposedInterface.Chain): Any? {
        HandleSystemServerProcessHooker.systemServerCL?.let { dispatchSystemServerLoaded(it) }
        return chain.proceed()
    }

    /** Dispatches module loading events. */
    fun dispatchSystemServerLoaded(classLoader: ClassLoader) {
        // 1. Dispatch to modern framework modules
        VectorLifecycleManager.dispatchSystemServerStarting(classLoader)

        // 2. Dispatch to legacy framework modules
        VectorBootstrap.withLegacy { delegate -> delegate.onSystemServerLoaded(classLoader) }
    }
}
