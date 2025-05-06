# LSPosed Framework

[![Build](https://img.shields.io/github/actions/workflow/status/JingMatrix/LSPosed/core.yml?branch=master&event=push&logo=github&label=Build)](https://github.com/JingMatrix/LSPosed/actions/workflows/core.yml?query=event%3Apush+branch%3Amaster+is%3Acompleted) [![Crowdin](https://img.shields.io/badge/Localization-Crowdin-blueviolet?logo=Crowdin)](https://crowdin.com/project/lsposed_jingmatrix) [![Download](https://img.shields.io/github/v/release/JingMatrix/LSPosed?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/JingMatrix/LSPosed/releases/latest) [![Total](https://shields.io/github/downloads/JingMatrix/LSPosed/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/JingMatrix/LSPosed/releases)

## Introduction 

A Zygisk module trying to provide an ART hooking framework which delivers consistent APIs with the OG Xposed, leveraging LSPlant hooking framework.

> Xposed is a framework for modules that can change the behavior of the system and apps without touching any APKs. That's great because it means that modules can work for different versions and even ROMs without any changes (as long as the original code was not changed too much). It's also easy to undo. As all changes are done in the memory, you just need to deactivate the module and reboot to get your original system back. There are many other advantages, but here is just one more: multiple modules can do changes to the same part of the system or app. With modified APKs, you have to choose one. No way to combine them, unless the author builds multiple APKs with different combinations.

## Supported Versions

Android 8.1 ~ 15

## Install

1. Root Environment Setup
   Choose one of the following root methods:
   - Magisk
     1. Install Magisk v26+
     2. Enable Zygisk *or* install [ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext) modele
   - KernelSU/APatch
     1. Install respective framework
     2. Install [ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext) module

2. Install LSPosed Module
   - [Download](#download) & install LSPosed via:  
     - Magisk App
     - KernelSU Manager
     - APatch Manager
   - Reboot

3. Use LSPosed 
   - Launch LSPosed Manager via notification

4. Have fun :)

**Tips: The ZygiskNext module does not guarantee continuous availability; please find an available version on your own.**

## Download

- For stable releases, please go to [GitHub Releases page](https://github.com/JingMatrix/LSPosed/releases)
- For canary build, please check [GitHub Actions](https://github.com/JingMatrix/LSPosed/actions/workflows/core.yml?query=branch%3Amaster)


Note: debug builds are only available in GitHub Actions.

## Documentations

- If you encounter problems while using LSPosed, please follow the [TROUBLESHOOT guide](TROUBLESHOOT.md).
- To understand the mechanism of LSPosed, please read the [DEVELOPMENT introduction](DEVELOPMENT.md).
- Xposed modules submit to [LSPosed Module Repository](https://github.com/Xposed-Modules-Repo) will be shown in the LSPosed manager.
- UI translations are managed in [the Crowdin project](https://crowdin.com/project/lsposed_jingmatrix).


## Credits 

- [Magisk](https://github.com/topjohnwu/Magisk/): makes all these possible
- [XposedBridge](https://github.com/rovo89/XposedBridge): the OG Xposed framework APIs
- [LSPlant](https://github.com/JingMatrix/LSPlant): the core ART hooking framework
- [Dobby](https://github.com/JingMatrix/Dobby): inline hooker for `LSPlant` and `native_api` implement
- [EdXposed](https://github.com/ElderDrivers/EdXposed): fork source
- [xz-embedded](https://github.com/tukaani-project/xz-embedded): decompress `.gnu_debugdata` header section of stripped `libart.so`
- ~~[Riru](https://github.com/RikkaApps/Riru): provides a way to inject code into zygote process~~
- ~[SandHook](https://github.com/ganyao114/SandHook/): ART hooking framework for SandHook variant~
- ~[YAHFA](https://github.com/rk700/YAHFA): previous ART hooking framework~
- ~[dexmaker](https://github.com/linkedin/dexmaker) and [dalvikdx](https://github.com/JakeWharton/dalvik-dx): to dynamically generate YAHFA hooker classes~

## License

LSPosed is licensed under the **GNU General Public License v3 (GPL-3)** (http://www.gnu.org/copyleft/gpl.html).
