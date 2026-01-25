# Vector Zygisk Module

## Overview

This sub-project contains the source code for the Zygisk module component of the Vector framework. This module acts as the initial entry point into newly forked application processes and the `system_server` on Android.

Its primary responsibility is to bootstrap the core Vector framework by injecting the necessary components into the target process's memory at the earliest possible stage of its lifecycle. It is designed to be a lightweight, robust, and efficient "glue" layer that bridges the Zygisk execution environment with our main `vector-native` core library.

## Core Responsibilities

1.  **Process Injection**: Hooks into the Zygote process creation lifecycle via the Zygisk API (`preAppSpecialize`, `postAppSpecialize`, etc.).
2.  **Target Filtering**: Implements logic to decide which processes should be instrumented. It actively skips injection into isolated processes, application zygotes, and other non-target system components to ensure stability and minimize footprint.
3.  **IPC Communication**: Establishes a secure Binder IPC connection with the daemon manager service to fetch the core framework DEX and configuration data (e.g., the obfuscation map).
4.  **Framework Bootstrapping**: Uses the functionality provided by the [native](../native) library to:
    *   Load the framework's Java code (DEX) into the target process using an `InMemoryDexClassLoader`.
    *   Initialize the ART hooking environment.
    *   Hand off execution control to the framework's Java entry point.
5.  **IPC Interception**: Installs a low-level JNI hook to intercept specific Binder transactions. This allows the framework to patch into the system's communication flow and handle custom IPCs efficiently.

## Architecture & Design

The module is architected to have minimal internal logic, instead delegating all heavy lifting to the `native` library.
This promotes code reuse and a clean separation of concerns.

### Key Components

*   **`VectorModule` (`module.cpp`)**: The main class that implements the `zygisk::ModuleBase` interface. It serves as the central orchestrator, containing the state machine and logic for the injection process. It inherits from `vector::native::Context` to gain the core injection capabilities.

*   **`IPCBridge` (`ipc_bridge.h`, `ipc_bridge.cpp`)**: A dedicated singleton responsible for all Binder IPC.
    *   It handles the two-step protocol for connecting to the manager service (rendezvous via a system service, then a request for a dedicated binder).
    *   It contains the sophisticated JNI table override hook on `CallBooleanMethodV` to intercept `Binder.execTransact` calls. This is the core of the framework's live-patching capability.
    *   It includes a performance optimization to prevent repeated IPC attempts from a caller that has recently failed a transaction.

*   **Dependency on `native`**: This module is a client of the `native` library. It does not implement its own DEX loading, symbol finding, or ART hooking logic. It simply includes the relevant headers (e.g., `<core/context.h>`, `<elf/symbol_cache.h>`) and links against the pre-built static library `libvector.a`.
