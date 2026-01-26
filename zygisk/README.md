# Vector Zygisk Module & Framework Loader

## Overview

This sub-project constitutes the injection engine of the Vector framework. It acts as the bridge between the Android Zygote process and the high-level Xposed API.

The project is a hybrid system consisting of two distinct layers:
1.  **Native Layer (C++)**: A Zygisk module that hooks process creation, filters targets, and bootstraps the environment.
2.  **Loader Layer (Kotlin)**: The initial Java-world payload that initializes the Xposed bridge, establishes high-level IPC, and manages the "Parasitic" execution environment for the Manager.

Its primary responsibility is to inject the Vector framework into the target process's memory at the earliest possible stage of its lifecycle, ensuring a robust and stealthy environment.

---

## Part 1: The Native Zygisk Layer

The native layer (`libzygisk.so`) is the entry point. It hooks into the Zygote process creation lifecycle via the Zygisk API (e.g., `preAppSpecialize`, `postAppSpecialize`). It is architected to have minimal internal logic, delegating heavy lifting (like ART hooking and ELF parsing) to the core [native](../native) library.

### Core Responsibilities
*   **Target Filtering**: Implements logic to skip isolated processes, application zygotes, and non-target system components to minimize footprint.
*   **IPC Communication**: Establishes a secure Binder IPC connection with the daemon manager service via a "Rendezvous" system service to fetch the framework DEX and configuration data (e.g., obfuscation maps).
*   **DEX Loading**: Uses `InMemoryDexClassLoader` to load the framework's bytecode directly from memory, avoiding disk I/O signatures.
*   **JNI Interception**: Installs a low-level JNI hook on `CallBooleanMethodV`. This intercepts `Binder.execTransact` calls, allowing the framework to patch into the system's IPC flow without registering standard Android Services.

### Key Components (C++)
*   **`VectorModule` (`module.cpp`)**: The central orchestrator implementing `zygisk::ModuleBase`. It manages the injection state machine and inherits from `vector::native::Context` to gain core injection capabilities.
*   **`IPCBridge` (`ipc_bridge.cpp`)**: A singleton handling raw Binder transactions. It manages the two-step connection protocol (Rendezvous -> Dedicated Binder) and contains the JNI table override logic.

---

## Part 2: The Kotlin Framework Loader

Once the native layer successfully loads the DEX, control is handed off to the Kotlin layer via JNI. This layer handles high-level Android framework manipulation, Xposed initialization, and identity spoofing.

### Core Responsibilities
*   **Bootstrapping**: `Main.forkCommon` acts as the Java entry point. It differentiates between the `system_server` and standard applications.
*   **Parasitic Injection**: Implements the logic to run the full LSPosed Manager application inside a host process (currently `com.android.shell`). This allows the Manager to run with elevated privileges without being installed as a system app.
*   **Manual Bridge Service**: Provides the Java-side handling for the intercepted Binder transactions.

### Key Components (Kotlin)
*   **`Main`**: The singleton entry point. It initializes the Xposed bridge (`Startup`) and decides whether to load the standard Xposed environment or the Parasitic Manager.
*   **`BridgeService`**: The peer to the C++ `IPCBridge`. It decodes custom `_LSP` transactions, manages the distribution of the system service binder, and handles communication between the injected framework and the root daemon.
*   **`ParasiticManagerHooker`**: The complex logic for identity transplantation.
    *   **App Swap**: Swaps the host's `ApplicationInfo` with the Manager's info during `handleBindApplication`.
    *   **State Persistence**: Since the Android System is unaware the host process is running Manager activities, this component manually captures and restores `Bundle` states to prevent data loss during lifecycle events.
    *   **Resource Spoofing**: Hooks `WebView` and `ContentProvider` installation to satisfy package name validations.

---

## Injection & Execution Flow

The full lifecycle of a Vector-instrumented process follows this sequence:

1.  **Zygote Fork**: Zygisk triggers the `preAppSpecialize` callback in C++.
2.  **Native Decision**: `VectorModule` checks the UID/Process Name. If valid, it initializes the `IPCBridge`.
3.  **DEX Fetch**: The C++ layer connects to the root daemon, fetches the Framework DEX file descriptor and the Obfuscation Map.
4.  **Memory Loading**: `postAppSpecialize` triggers the creation of an `InMemoryDexClassLoader`.
5.  **JNI Hand-off**: The native module calls the static Kotlin method `org.lsposed.lspd.core.Main.forkCommon`.
6.  **Identity Check (Kotlin)**:
    *   **If Manager Package**: `ParasiticManagerHooker.start()` is called. The process is "hijacked" to run the Manager UI.
    *   **If Standard App**: `Startup.bootstrapXposed()` is called. Third-party modules are loaded.
7.  **Live Interception**: Throughout the process life, the C++ JNI hook redirects specific `Binder.execTransact` calls to `BridgeService.execTransact` in Kotlin.

---

## Maintenance & Technical Notes

### The IPC Protocol
The communication between the native loader and the Kotlin framework relies on specific conventions:
*   **Transaction Code**: The custom code `_LSP` (bitwise constructed) must remain synchronized between `ipc_bridge.cpp` (Native) and `BridgeService.kt` (Kotlin).
*   **The "Out-Parameter" List**: In `ParasiticManagerHooker.start()`, you will see an empty list `mutableListOf<IBinder>()`.
It is used as an "out-parameter" for the Binder call, allowing the root daemon to push the Manager Service Binder back to the loader.

### System Server Hooks
The `ParasiticManagerSystemHooker` runs *only* in the `system_server`. It uses `XposedHooker` to intercept `ActivityTaskSupervisor.resolveActivity`. It detects Intents tagged with `LAUNCH_MANAGER` and forcefully redirects them to the parasitic process (e.g., `Shell`), modifying the `ActivityInfo` on the fly to ensure the Manager launches correctly.
