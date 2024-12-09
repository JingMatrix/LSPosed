# Development introduction to LSPosed 

As a Zygisk module, LSPosed utilizes the `postAppSpecialize` [API](https://github.com/topjohnwu/Magisk/blob/master/native/src/core/zygisk/api.hpp) to inject into target processes (Android applications), and provides [Xposed Framework API](https://api.xposed.info/reference/packages.html) for modules to hook Java methods.
We strongly advise developers to follow the [Development tutorial](https://github.com/rovo89/XposedBridge/wiki/Development-tutorial) by [rovo89](https://github.com/rovo89) to understand the purpose of Xposed.
Moreover, LSPosed also provides [Native Hook API](https://github.com/LSPosed/LSPosed/wiki/Native-Hook) to facilite the routine of hooking functions in loaded native libraries of target processes.


## Introduction to Zygisk
