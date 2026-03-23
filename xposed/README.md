# Xposed API implementation of the Vector framework

This module implements the [libxposed](https://github.com/libxposed/api) API for the Vector framework. It serves as the primary bridge between the native ART hooking engine (`lsplant`) and module developers, providing a type-safe, OkHttp-style interceptor chain architecture.

## Architectural Overview

The `xposed` module is designed with strict boundaries to ensure stability during the Android boot process and application lifecycles. It is written entirely in Kotlin and operates independently of the legacy Xposed API (`de.robv.android.xposed`). 
It defines a Dependency Injection (DI) contract (`LegacyFrameworkDelegate`) which the `core` module must implement and inject during startup.

## Core Components

### 1. The Hooking Engine

*   **`VectorHookBuilder`**: Implements the `HookBuilder` API. It validates the target `Executable`, bundles the module's `Hooker`, `priority`, and `ExceptionMode` into a `VectorHookRecord`, and registers it natively via JNI.
*   **`VectorNativeHooker`**: The JNI trampoline target. When a hooked method is executed, the C++ layer invokes `callback(Array<Any?>)` on this class. It fetches the active hooks (both modern and legacy) from the native registry as global `jobject` references, constructs the root `VectorChain`, and initiates execution.
*   **`VectorChain`**: Implements the recursive `proceed()` state machine.
    *   **Exception Handling**: It implements the logic for `ExceptionMode`. In `PROTECTIVE` mode, if an interceptor throws an exception *before* calling `proceed()`, the chain skips the interceptor. If it throws *after* calling `proceed()`, the chain catches the exception and restores the cached downstream result/throwable to protect the host process.

### 2. The Invocation System

The `Invoker` system allows modules to execute methods while bypassing standard JVM access checks, with granular control over hook execution.

*   **`Type.Origin`**: Dispatches directly to JNI (`HookBridge.invokeOriginalMethod`), bypassing all active hooks.
*   **`Type.Chain`**: Constructs a localized `VectorChain` containing only hooks with a priority less than or equal to the requested `maxPriority`, allowing modules to execute partial hook chains.
*   **`VectorCtorInvoker`**: Handles constructor invocation. It separates memory allocation (`HookBridge.allocateObject`) from initialization (`invokeOriginalMethod` / `invokeSpecialMethod`) to support safe `newInstanceSpecial` logic.

### 3. Dependency Injection Contract

To maintain the separation of concerns, the `xposed` module communicates with the legacy Xposed ecosystem via `VectorBootstrap` and `LegacyFrameworkDelegate`.

When `xposed` intercepts an Android lifecycle event (e.g., `LoadedApk.createClassLoader`), it dispatches the event internally via `VectorLifecycleManager` and then delegates the raw parameters to `LegacyFrameworkDelegate` so the `legacy` module can construct and dispatch the legacy `XC_LoadPackage` callbacks.

### 4. In-Memory Module ClassLoading

Modules are loaded directly from memory to prevent disk I/O bottlenecks and enhance security.
*   It utilizes `SharedMemory` buffers passed over IPC, mapping them to `ByteBuffer`s.
*   It extends `ByteBufferDexClassLoader` to evaluate the module's DEX files.
*   `VectorURLStreamHandler` intercepts resource requests (like `assets/`) to read directly from the original APK path without extracting the APK locally.
