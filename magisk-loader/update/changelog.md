# LSPosed v1.11.0 🎐

This release brings major improvements for **Android 16 Beta** readiness, resolves specific quirks on Android 10 and OnePlus devices, and significantly reinforces overall system stability.

### 📱 Compatibility & Core
*   **Android 16 Beta Support:** Fixed compatibility issues with Android 16 QPR Beta 3 (specifically `UserManager` changes) and recent ART updates affecting the `dex2oat` wrapper.
*   **Android 10 Fixes:** Resolved `dex2oat` crashes caused by 32-bit/64-bit architecture mismatches.
*   **OnePlus Compatibility:** Restored `Application#attach` hooking capabilities, overcoming aggressive method inlining found in recent OOS updates.
*   **Dex2Oat Overhaul:** Refactored the wrapper to utilize the APEX linker directly, eliminating missing symbol errors and boosting reliability.

### 🛠️ Stability & Fixes
*   **Database Integrity:** Resolved critical crashes and potential corruption during database initialization and migration.
*   **Frida Compatibility:** Fixed `SIGSEGV` crashes when running alongside Frida by making memory mapping parsing more robust.
*   **SELinux:** Corrected file contexts for the modern Xposed API 100 (`openRemoteFile`) and ensured they persist across reboots.
*   **Injection Reliability:** Implemented retry logic for System Server injection to minimize start-up failures.

### ⚡ Internal Changes
*   **Kotlin Refactor:** The `DexParser` has been rewritten in Kotlin for improved performance and maintainability.
*   **WebUI Removal:** Removed the WebUI integration as it is no longer required.

---

## 🔮 Development Plan

The current LSPosed fork is undergoing a complete refactor into a new project: **Vector**. 

We are in the process of rewriting the Java layer into Kotlin and adding extensive documentation for the native layer. 

The name **Vector** was chosen to manifest its close mathematical relationship with **Matrix**, while symbolizing the framework's role as a precise injection vector for modules.
