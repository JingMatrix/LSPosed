# Vector Daemon

The Vector `daemon` is a highly privileged, standalone executable that runs as `root`.
It acts as the central coordinator and backend for the entire Vector framework.

Unlike the injected framework code, the daemon does not hook methods directly. Instead, it manages state, provides IPC endpoints to hooked apps and modules, handles AOT compilation evasion, and interacts safely with Android system services.

## Architecture Overview

The daemon relies on a dual-IPC architecture and extensive use of Android Binder mechanisms to orchestrate the framework lifecycle without triggering SELinux denials or breaking system stability.

1. **Bootstrapping & Bridge (`core/`)**: The daemon starts early in the boot process. It forces its primary Binder (`VectorService`) into `system_server` by hijacking transactions on the Android `activity` service.
2. **Privileged IPC Provider (`ipc/`)**: Android's sandbox prevents target processes from reading the framework APK, accessing SQLite databases, or resolving hidden ART symbols. The daemon exploits its root/system-level permissions to act as an asset server. It provides three critical components to hooked processes over Binder IPC:
    * **Framework Loader DEX**: Dispatched via `SharedMemory` to avoid disk-based detection and bypass SELinux `exec` restrictions.
    * **Obfuscation Maps**: Dictionaries provided over IPC when API protection is enabled, allowing the injected code to correctly resolve the randomized class names at runtime.
    * **Dynamic Module Scopes**: Fast, lock-free lookups of which modules should be loaded into a specific UID/ProcessName.
3. **State Management (`data/`)**: To ensure IPC calls resolve in microseconds without race conditions, the daemon uses an **Immutable State Container** (`DaemonState`). Module topology and scopes are built into a frozen snapshot in the background, which is atomically swapped into memory. High-volume module preference updates are isolated in a separate `PreferenceStore` to prevent state pollution.
4. **Native Environment (`env/` & JNI)**: Background threads (C++ and Kotlin Coroutines) handle low-level system subversion, including `dex2oat` compilation hijacking and logcat monitoring.

## Directory Layout

```text
src/main/
├── kotlin/org/matrix/vector/daemon/
│   ├── core/       # Entry point (Main), looper setup, and OS broadcast receivers
│   ├── ipc/        # AIDL implementations (Manager, Module, App, SystemServer endpoints)
│   ├── data/       # SQLite DB, Immutable State (DaemonState, ConfigCache), PreferenceStore, File & ZIP parsing
│   ├── system/     # System binder wrappers, UID observers, Notification UI
│   ├── env/        # Socket servers and monitors communicating with JNI (dex2oat, logcat)
│   └── utils/      # OEM-specific workarounds, FakeContext, JNI bindings
└── jni/            # Native C++ layer (dex2oat wrapper, logcat watcher, slicer obfuscation)
```

## Core Technical Mechanisms

### 1. IPC Routing (The Two Doors)
* **Door 1 (`SystemServerService`)**: A native-to-native entry point used exclusively for the **System-Level Initialization** of `system_server`. By proxying the hardware `serial` service (via `IServiceCallback`), the daemon provides a rendezvous point accessible to the system before the Activity Manager is even initialized. It handles raw UID/PID/Heartbeat packets to authorize the base system framework hook.
* **Door 2 (`VectorService`)**: The **Application-Level Entrance** used by user-space apps. Since user apps are forbidden by SELinux from accessing hardware services like `serial`, they use the "Activity Bridge" to reach the daemon. This door utilizes an action-based protocol allowing the daemon to perform **Scope Filtering**—matching the calling process against the current `DaemonState` before granting access to the framework.

### 2. AOT Compilation Hijacking (`dex2oat`)
To prevent Android's ART from inlining hooked methods (which makes them unhookable), Vector hijacks the Ahead-of-Time (AOT) compiler.
* **Mechanism**: The daemon (`Dex2OatServer`) mounts a C++ wrapper binary (`bin/dex2oatXX`) over the system's actual `dex2oat` binaries in the `/apex` mount namespace.
* **FD Passing**: When the wrapper executes, to read the original compiler or the `liboat_hook.so`, it opens a UNIX domain socket to the daemon. The daemon (running as root) opens the files and passes the File Descriptors (FDs) back to the wrapper via `SCM_RIGHTS`.
* **Execution**: The wrapper uses `memfd_create` and `sendfile` to load the hook, bypassing execute restrictions, and uses `LD_PRELOAD` to inject the hook into the real `dex2oat` process while appending `--inline-max-code-units=0`.

### 3. API Protection & DEX Obfuscation
To prevent unauthorized apps from detecting the framework or invoking the Xposed API, the daemon randomizes framework and loader class names on each boot. JNI maps the input `SharedMemory` via `MAP_SHARED` to gain direct, zero-copy access to the physical pages populated by Java. Using the [DexBuilder](https://github.com/JingMatrix/DexBuilder) library, the daemon mutates the DEX string pool in-place; this is highly efficient as the library's Intermediate Representation points directly to the mapped buffer, avoiding unnecessary heap allocations during the randomization process.

Once mutation is complete, the finalized DEX is written into a new `SharedMemory` region and the original plaintext handle is closed. Because signatures are now randomized, the daemon provides **Obfuscation Maps** via Door 1 and Door 2. These dictionaries allow the injected code to correctly "re-link" and resolve the framework's internal classes at runtime despite their randomized names.

### 4. Lifecycle & State Tracking
The daemon must precisely know which apps are installed and which processes are running.
* **Broadcasts**: `VectorService` registers a hidden `IIntentReceiver` to listen for `ACTION_PACKAGE_ADDED`, `REMOVED`, and `ACTION_LOCKED_BOOT_COMPLETED`.
* **UID Observers**: `IUidObserver` tracks `onUidActive` and `onUidGone`. When a process becomes active, the daemon uses a forged `ContentProvider` call (`send_binder`) to proactively push the `IXposedService` binder into the target process, bypassing standard `bindService` limitations.

## Development & Maintenance Guidelines

When modifying the daemon, strictly adhere to the following principles:

1. **Never Block IPC Threads**: AIDL `onTransact` methods are called synchronously by the Android framework and target apps. Blocking these threads (e.g., by executing raw SQL queries or heavy I/O directly) will cause Application Not Responding (ANR) crashes system-wide. Always read from the lock-free, immutable `DaemonState` snapshot exposed by `ConfigCache.state`.
2. **Resource Determinism**: The daemon runs indefinitely. Leaking a single `Cursor`, `ParcelFileDescriptor`, or `SharedMemory` instance will eventually exhaust system limits and crash the OS. Always use Kotlin's `.use { }` blocks or explicit C++ RAII wrappers for native resources.
3. **Isolate OEM Quirks**: Android OS behavior varies wildly between manufacturers (e.g., Lenovo hiding cloned apps in user IDs 900-909, MIUI killing background dual-apps). Place all OEM-specific logic in `utils/Workarounds.kt` to prevent core logic pollution.
4. **Context Forgery (`FakeContext`)**: The daemon does not have a real Android `Context`. To interact with system APIs that require one (like building Notifications or querying packages), use `FakeContext`. Be aware that standard `Context` methods may crash if not explicitly mocked.
