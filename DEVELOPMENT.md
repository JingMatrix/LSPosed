# Development introduction to LSPosed 

As a Zygisk module, LSPosed utilizes the `postAppSpecialize` [API](https://github.com/topjohnwu/Magisk/blob/master/native/src/core/zygisk/api.hpp) to inject into target processes (Android applications), and provides [Xposed Framework API](https://api.xposed.info/reference/packages.html) for modules to hook their Java methods.
We strongly advise developers to follow the [Development tutorial](https://github.com/rovo89/XposedBridge/wiki/Development-tutorial) by [rovo89](https://github.com/rovo89) to understand the purpose of Xposed.
Moreover, LSPosed also provides [Native Hook API](https://github.com/LSPosed/LSPosed/wiki/Native-Hook) to facilite the routine of hooking functions in loaded native libraries of target processes.


## Introduction to Zygisk

The name Zygisk comes from Zygote and Magisk, referring to [a set of APIs](https://github.com/topjohnwu/Magisk/blob/f56ea52932a8d927b1d96fd2d7c7e634c8b6c710/native/jni/zygisk/api.hpp) exposed to module developers and thus allowing them to inject custom codes into any Android processes.
Zygisk was first introduced in [Magisk v24.1](https://github.com/topjohnwu/Magisk/releases/tag/v24.1), and it is currently the most widely used interface of Zygote injection.
[Riru](https://github.com/RikkaApps/Riru) provides an alternative interface of Zygote injection, which is achieved now though it was the inspiration for Zygisk.
JingMatrix/LSPosed dropped its support of Riru in [v1.10.1](https://github.com/JingMatrix/LSPosed/releases/tag/v1.10.1), so we will focus on understanding Zygisk only.
