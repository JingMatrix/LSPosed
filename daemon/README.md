# Vector Daemon

The Vector `daemon` is a highly privileged, standalone executable that runs as `root`.
It acts as the central coordinator and backend for the entire Vector framework.

Unlike the injected framework code, the daemon does not hook methods directly. Instead, it manages state, provides IPC endpoints to hooked apps and modules, handles AOT compilation evasion, and interacts safely with Android system services.

## Architecture Overview

The daemon relies on a dual-IPC architecture and extensive use of Android Binder mechanisms to orchestrate the framework lifecycle without triggering SELinux denials or breaking system stability.

1. _Bootstrapping & Bridge (`core/`)_: The daemon starts early in the boot process. It forces its primary Binder (`VectorService`) into `system_server` by hijacking transactions on the Android `activity` service.
2. _Privileged IPC Provider (`ipc/`)_: Android's sandbox prevents target processes from reading the framework APK, accessing SQLite databases, or resolving hidden ART symbols. The daemon exploits its root/system-level permissions to act as an asset server. It provides three critical components to hooked processes over Binder IPC:
    * _Framework Loader DEX_: Dispatched via `SharedMemory` to avoid disk-based detection and bypass SELinux `exec` restrictions.
    * _Obfuscation Maps_: Dictionaries provided over IPC when API protection is enabled, allowing the injected code to correctly resolve the randomized class names at runtime.
    * _Dynamic Module Scopes_: Fast, lock-free lookups of which modules should be loaded into a specific UID/ProcessName.
3. _State Management (`data/`)_: To ensure IPC calls resolve in microseconds without race conditions, the daemon uses an _Immutable State Container_ (`DaemonState`). Module topology and scopes are built into a frozen snapshot in the background, which is atomically swapped into memory. High-volume module preference updates are isolated in a separate `PreferenceStore` to prevent state pollution.
4. _Native Environment (`env/` & JNI)_: Background threads (C++ and Kotlin Coroutines) handle low-level system subversion, including `dex2oat` compilation hijacking and logcat monitoring.

## Directory Layout

```text
src/main/
├── kotlin/org/matrix/vector/daemon/
│   ├── *.kt        # Entry point (Daemon, Cli), looper setup, and OS broadcast receivers
│   ├── ipc/        # AIDL implementations (Manager, Module, App, SystemServer endpoints)
│   ├── data/       # SQLite DB, Immutable State (DaemonState, ConfigCache), PreferenceStore, File & ZIP parsing
│   ├── system/     # System binder wrappers, UID observers, Notification UI
│   ├── env/        # Socket servers and monitors communicating with JNI (dex2oat, logcat)
│   └── utils/      # OEM-specific workarounds, FakeContext, JNI bindings
└── jni/            # Native C++ layer (dex2oat wrapper, logcat watcher, slicer obfuscation)
```

## Core Technical Mechanisms

### 1. IPC Routing (The Two Doors)
* _Door 1 (`SystemServerService`)_: A native-to-native entry point used exclusively for the _System-Level Initialization_ of `system_server`. By proxying the hardware `serial` service (via `IServiceCallback`), the daemon provides a rendezvous point accessible to the system before the Activity Manager is even initialized. It handles raw UID/PID/Heartbeat packets to authorize the base system framework hook.
* _Door 2 (`VectorService`)_: The _Application-Level Entrance_ used by user-space apps. Since user apps are forbidden by SELinux from accessing hardware services like `serial`, they use the "Activity Bridge" to reach the daemon. This door utilizes an action-based protocol allowing the daemon to perform _Scope Filtering_—matching the calling process against the current `DaemonState` before granting access to the framework.

### 2. AOT Compilation Hijacking (`dex2oat`)
To prevent Android's ART from inlining hooked methods (which makes them unhookable), Vector hijacks the Ahead-of-Time (AOT) compiler.
* _Mechanism_: The daemon (`Dex2OatServer`) mounts a C++ wrapper binary (`bin/dex2oatXX`) over the system's actual `dex2oat` binaries in the `/apex` mount namespace.
* _FD Passing_: When the wrapper executes, to read the original compiler or the `liboat_hook.so`, it opens a UNIX domain socket to the daemon. The daemon (running as root) opens the files and passes the File Descriptors (FDs) back to the wrapper via `SCM_RIGHTS`.
* _Execution_: The wrapper uses `memfd_create` and `sendfile` to load the hook, bypassing execute restrictions, and uses `LD_PRELOAD` to inject the hook into the real `dex2oat` process while appending `--inline-max-code-units=0`.

### 3. API Protection & DEX Obfuscation
To prevent unauthorized apps from detecting the framework or invoking the Xposed API, the daemon randomizes framework and loader class names on each boot. JNI maps the input `SharedMemory` via `MAP_SHARED` to gain direct, zero-copy access to the physical pages populated by Java. Using the [DexBuilder](https://github.com/JingMatrix/DexBuilder) library, the daemon mutates the DEX string pool in-place; this is highly efficient as the library's Intermediate Representation points directly to the mapped buffer, avoiding unnecessary heap allocations during the randomization process.

Once mutation is complete, the finalized DEX is written into a new `SharedMemory` region and the original plaintext handle is closed. Because signatures are now randomized, the daemon provides _Obfuscation Maps_ via Door 1 and Door 2. These dictionaries allow the injected code to correctly "re-link" and resolve the framework's internal classes at runtime despite their randomized names.

### 4. Lifecycle & Process Injection
Vector uses a proactive _Push Model_ to distribute the `IXposedService` binder. Upon detecting a process start via `IUidObserver`, the daemon utilizes `getContentProviderExternal` to obtain a direct line to the module's internal provider. It then executes a synchronous `IContentProvider.call()`, passing the control binder within a `Bundle`. This ensures the framework reference is injected into the target process’s memory before its `Application.onCreate()` executes, bypassing the detection and latency associated with standard `bindService` calls.

_Remote Preferences & Files_ are supported by a combination of the injected Binder and custom SELinux types. The daemon stores preferences and shared files in directories labeled `xposed_data`. Because the policy allows global access to this type, the injected binder simply provides the path or File Descriptor, and the target app can perform direct I/O, bypassing standard per-app sandbox restrictions.

## Development & Maintenance Guidelines

When modifying the daemon, strictly adhere to the following principles:

1. _Never Block IPC Threads_: AIDL `onTransact` methods are called synchronously by the Android framework and target apps. Blocking these threads (e.g., by executing raw SQL queries or heavy I/O directly) will cause Application Not Responding (ANR) crashes system-wide. Always read from the lock-free, immutable `DaemonState` snapshot exposed by `ConfigCache.state`.
2. _Resource Determinism_: The daemon runs indefinitely. Leaking a single `Cursor`, `ParcelFileDescriptor`, or `SharedMemory` instance will eventually exhaust system limits and crash the OS. Always use Kotlin's `.use { }` blocks or explicit C++ RAII wrappers for native resources.
3. _Isolate OEM Quirks_: Android OS behavior varies wildly between manufacturers (e.g., Lenovo hiding cloned apps in user IDs 900-909, MIUI killing background dual-apps). Place all OEM-specific logic in `utils/Workarounds.kt` to prevent core logic pollution.
4. _Context Forgery (`FakeContext`)_: The daemon does not have a real Android `Context`. To interact with system APIs that require one (like building Notifications or querying packages), use `FakeContext`. Be aware that standard `Context` methods may crash if not explicitly mocked.
