/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 - 2022 LSPosed Contributors
 */

#include <dlfcn.h>
#include "lsplt.hpp"
#include <sys/mman.h>
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-value"
#pragma once

#include <context.h>
#include "utils/jni_helper.hpp"
#include "logging.h"
#include "config.h"
#include <cassert>
#include "config_bridge.h"

namespace lspd {

[[gnu::always_inline]]
inline bool RegisterNativeMethodsInternal(JNIEnv *env,
                                          std::string_view class_name,
                                          const JNINativeMethod *methods,
                                          jint method_count) {

    auto clazz = Context::GetInstance()->FindClassFromCurrentLoader(env, class_name.data());
    if (clazz.get() == nullptr) {
        LOGF("Couldn't find class: {}", class_name.data());
        return false;
    }
    return JNI_RegisterNatives(env, clazz, methods, method_count);
}

#if defined(__cplusplus)
#define _NATIVEHELPER_JNI_MACRO_CAST(to) \
    reinterpret_cast<to>
#else
#define _NATIVEHELPER_JNI_MACRO_CAST(to) \
    (to)
#endif

#ifndef LSP_NATIVE_METHOD
#define LSP_NATIVE_METHOD(className, functionName, signature)                \
  { #functionName,                                                       \
    signature,                                                           \
    _NATIVEHELPER_JNI_MACRO_CAST(void*) (Java_org_lsposed_lspd_nativebridge_## className ## _ ## functionName) \
  }
#endif

#define JNI_START [[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz

#ifndef LSP_DEF_NATIVE_METHOD
#define LSP_DEF_NATIVE_METHOD(ret, className, functionName, ...)                \
  extern "C" ret Java_org_lsposed_lspd_nativebridge_## className ## _ ## functionName (JNI_START, ##  __VA_ARGS__)
#endif

#define REGISTER_LSP_NATIVE_METHODS(class_name) \
  RegisterNativeMethodsInternal(env, GetNativeBridgeSignature() + #class_name, gMethods, arraysize(gMethods))

static std::vector<std::tuple<dev_t, ino_t, const char *, void **>> plt_hook_list = {};
static auto scan_maps = lsplt::MapInfo::Scan();

inline int plt_hook(const char *lib, const char *symbol, void *callback, void **backup) {
    dev_t dev = 0;
    ino_t inode = 0;
    for (auto map : scan_maps) {
        if (map.path == lib) {
            inode = map.inode;
            dev = map.dev;
            break;
        }
    }

    auto result = lsplt::RegisterHook(dev, inode, symbol, callback, backup) && lsplt::CommitHook();
    if (result) {
        plt_hook_list.emplace_back(dev, inode, symbol, backup);
        return 0;
    }
    return 1;
}

inline int HookFunction(void *original, void *replace, void **backup) {
    Dl_info info;
    if (dladdr(original, &info)) {
        if constexpr (isDebug) {
            LOGD("Hooking {} ({}) from {} ({})",
                 info.dli_sname ? info.dli_sname : "(unknown symbol)", info.dli_saddr,
                 info.dli_fname ? info.dli_fname : "(unknown file)", info.dli_fbase);
        }
        if (info.dli_sname != NULL && info.dli_fname != NULL)
            return plt_hook(info.dli_fname, info.dli_sname, replace, backup);
    }
    return 1;
}

inline int UnhookFunction(void *original) {
    Dl_info info;
    if (dladdr(original, &info)) {
        if constexpr (isDebug) {
            LOGD("Unhooking {} ({}) from {} ({})",
                 info.dli_sname ? info.dli_sname : "(unknown symbol)", info.dli_saddr,
                 info.dli_fname ? info.dli_fname : "(unknown file)", info.dli_fbase);
        }

        for (const auto [dev, inode, symbol, backup] : plt_hook_list) {
            if (info.dli_sname == symbol) {
                auto result = lsplt::RegisterHook(dev, inode, symbol, backup, nullptr)
                    && lsplt::CommitHook();
                return 1 - result;
            }
        }
    }
    return 1;
}

inline std::string GetNativeBridgeSignature() {
    const auto &obfs_map = ConfigBridge::GetInstance()->obfuscation_map();
    static auto signature = obfs_map.at("org.lsposed.lspd.nativebridge.");
    return signature;
}

} // namespace lspd

#pragma clang diagnostic pop
