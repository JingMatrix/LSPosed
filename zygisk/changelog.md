🎉 To celebrate the release of Android 16, we are excited to announce a new stable version of LSPosed!

To better understand LSPosed, we recommend reading our [troubleshooting guide](https://github.com/JingMatrix/LSPosed/issues/123).

### ✨ What's New
*   Fully support Android 16.
*   Hide traces introduced by the `dex2oat` hook.
*   The LSPosed manager can now be opened via the Action button.
*   New options have been added to the `Select` menu for scopes.
*   Allow users to toggle off detectable logging of LSPosed.

### 🐛 Bug Fixes
*   The `LSPlt` hook has been abandoned for efficiency considerations.
*   Resolved an issue where modules targeting `systemui` (e.g., `ClassicPowerMenu`) were not working.
*   Removed Telemetry monitoring.

### 🔄 Other Changes
*   The dependency on `topjohnwu/libcxx` has been removed in favor of the official C++ implementation. This will result in a larger release archive for LSPosed.

### 🚀 High-Priority Plans
*   Creating comprehensive development documentation for LSPosed.
*   Resolving open issues with assignees.

**Full Changelog**: [v1.10.1...v1.10.2](https://github.com/JingMatrix/LSPosed/compare/v1.10.1...v1.10.2)

<details>
<summary>❤️ A personal note</summary>

For the past few months, I have been focused on finishing my PhD thesis manuscript, which has limited my active development on LSPosed. I sincerely appreciate the community's passion and support during this time. It has been a pleasure to witness our community grow and thrive around this open-source fork. I am deeply indebted to your trust and respect, which has indeed helped me navigate the unavoidable challenges and depressions faced by a PhD candidate.

Maintaining this project is a joyful responsibility. However, life is a grand museum of passions, and I am constantly called by my devotion to research and teaching at the university. For users eagerly awaiting new features, I want to reassure you that the LSPosed codebase is quite stable and sufficient for its functionality. Moreover, I sincerely encourage developers to join the project. For all users, please consider participating in the [GitHub Discussions](https://github.com/JingMatrix/LSPosed/discussions) to share your experiences and various tips. Nothing is more valuable to an open-source project than an active community.

</details>
