package org.matrix.vector.impl.core

import android.os.Build
import android.os.Process
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import java.io.File
import org.matrix.vector.impl.VectorContext
import org.matrix.vector.impl.VectorLifecycleManager
import org.matrix.vector.impl.util.VectorModuleClassLoader
import org.matrix.vector.lspd.models.Module
import org.matrix.vector.lspd.util.Utils.Log
import org.matrix.vector.nativebridge.NativeAPI

/**
 * Responsible for loading modern (API 101) modules into the target process. Handles ClassLoader
 * isolation and injects the framework context into the module instances.
 */
object VectorModuleManager {

    private const val TAG = "VectorModuleManager"

    /**
     * Loads a module APK, instantiates its entry classes, and binds them to the Vector framework.
     */
    fun loadModule(module: Module, isSystemServer: Boolean, processName: String): Boolean {
        try {
            Log.d(TAG, "Loading module ${module.packageName}")

            // 1. Construct the native library search path
            val librarySearchPath = buildString {
                val abis =
                    if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS
                    else Build.SUPPORTED_32_BIT_ABIS
                for (abi in abis) {
                    append(module.apkPath).append("!/lib/").append(abi).append(File.pathSeparator)
                }
            }

            // 2. Create the isolated ClassLoader for the module
            val initLoader = XposedModule::class.java.classLoader
            val moduleClassLoader =
                VectorModuleClassLoader.loadApk(
                    module.apkPath,
                    module.file.preLoadedDexes,
                    librarySearchPath,
                    initLoader,
                )

            // Security/Integrity Check: Ensure the module isn't bundling its own API classes
            if (
                moduleClassLoader.loadClass(XposedModule::class.java.name).classLoader !==
                    initLoader
            ) {
                Log.e(TAG, "  Cannot load module: ${module.packageName}")
                Log.e(
                    TAG,
                    "  The Xposed API classes are compiled into the module's APK (implementation instead of compileOnly).",
                )
                return false
            }

            // 3. Create the Context that will be injected into the module
            val vectorContext =
                VectorContext(
                    packageName = module.packageName,
                    applicationInfo = module.applicationInfo,
                    service = VectorServiceClient, // Our IPC client
                )

            // 4. Instantiate the module entry classes
            for (className in module.file.moduleClassNames) {
                runCatching {
                        val moduleClass = moduleClassLoader.loadClass(className)
                        Log.d(TAG, "  Loading class $moduleClass")

                        if (!XposedModule::class.java.isAssignableFrom(moduleClass)) {
                            Log.e(TAG, "    Class does not extend XposedModule, skipping.")
                            return@runCatching
                        }

                        // Look for the constructor required by API 101
                        val constructor =
                            moduleClass.getConstructor(
                                XposedInterface::class.java,
                                ModuleLoadedParam::class.java,
                            )

                        // Inject the Context and the loaded parameters!
                        val moduleInstance =
                            constructor.newInstance(
                                vectorContext,
                                object : ModuleLoadedParam {
                                    override fun isSystemServer(): Boolean = isSystemServer

                                    override fun getProcessName(): String = processName
                                },
                            ) as XposedModule

                        // Register the active module to receive future lifecycle events
                        VectorLifecycleManager.activeModules.add(moduleInstance)

                        // Trigger the initial onModuleLoaded callback
                        moduleInstance.onModuleLoaded(
                            object : ModuleLoadedParam {
                                override fun isSystemServer(): Boolean = isSystemServer

                                override fun getProcessName(): String = processName
                            }
                        )
                    }
                    .onFailure { e -> Log.e(TAG, "    Failed to instantiate class $className", e) }
            }

            // 5. Register any native JNI entrypoints declared by the module
            module.file.moduleLibraryNames.forEach { libraryName ->
                NativeAPI.recordNativeEntrypoint(libraryName)
            }

            Log.d(TAG, "Loaded module ${module.packageName} successfully.")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Fatal error loading module ${module.packageName}", e)
            return false
        }
    }
}
