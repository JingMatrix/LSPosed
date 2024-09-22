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
#include <sys/mman.h>

#include "elf_util.h"
#include "lsplt.hpp"
#include "symbol_cache.h"
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-value"
#pragma once

#include <context.h>

#include <cassert>

#include "../src/native_api.h"
#include "config.h"
#include "config_bridge.h"
#include "logging.h"
#include "utils/jni_helper.hpp"

namespace lspd {

[[gnu::always_inline]]
inline bool RegisterNativeMethodsInternal(JNIEnv *env, std::string_view class_name,
                                          const JNINativeMethod *methods, jint method_count) {
    auto clazz = Context::GetInstance()->FindClassFromCurrentLoader(env, class_name.data());
    if (clazz.get() == nullptr) {
        LOGF("Couldn't find class: {}", class_name.data());
        return false;
    }
    return JNI_RegisterNatives(env, clazz, methods, method_count);
}

#if defined(__cplusplus)
#define _NATIVEHELPER_JNI_MACRO_CAST(to) reinterpret_cast<to>
#else
#define _NATIVEHELPER_JNI_MACRO_CAST(to) (to)
#endif

#ifndef LSP_NATIVE_METHOD
#define LSP_NATIVE_METHOD(className, functionName, signature)                                      \
    {#functionName, signature,                                                                     \
     _NATIVEHELPER_JNI_MACRO_CAST(void *)(                                                         \
         Java_org_lsposed_lspd_nativebridge_##className##_##functionName)}
#endif

#define JNI_START [[maybe_unused]] JNIEnv *env, [[maybe_unused]] jclass clazz

#ifndef LSP_DEF_NATIVE_METHOD
#define LSP_DEF_NATIVE_METHOD(ret, className, functionName, ...)                                   \
    extern "C" ret Java_org_lsposed_lspd_nativebridge_##className##_##functionName(JNI_START,      \
                                                                                   ##__VA_ARGS__)
#endif

#define REGISTER_LSP_NATIVE_METHODS(class_name)                                                    \
    RegisterNativeMethodsInternal(env, GetNativeBridgeSignature() + #class_name, gMethods,         \
                                  arraysize(gMethods))

static dev_t dev = 0;
static ino_t inode = 0;
static std::vector<std::pair<const char *, void **>> plt_hook_saved = {};

inline int HookPLT(void *art_symbol, void *callback, void **backup, bool save = true) {
    auto symbol = reinterpret_cast<const char *>(art_symbol);

    if (GetArt()->isStripped()) {
        if (dev == 0 || inode == 0) {
            auto libart_path = GetArt()->name();
            for (auto map : lsplt::MapInfo::Scan()) {
                if (map.path == libart_path) {
                    inode = map.inode;
                    dev = map.dev;
                    break;
                }
            }
        }

        auto result =
            lsplt::RegisterHook(dev, inode, symbol, callback, backup) && lsplt::CommitHook();
        if (result && *backup != nullptr) {
            if (save) plt_hook_saved.emplace_back(symbol, backup);
            return 0;
        }
    }

    if (auto addr = GetArt()->getSymbAddress(symbol); addr) {
        HookInline(addr, callback, backup);
    } else if (*backup == nullptr && isDebug) {
        LOGW("Failed to {} Art symbol {}", save ? "hook" : "unhook", symbol);
    }
    return *backup == nullptr;
}

inline int UnhookPLT(void *original) {
    Dl_info info;

    if (!dladdr(original, &info) || info.dli_sname != nullptr) return 1;
    if (!GetArt()->isStripped()) return UnhookInline(original);

    auto hook_iter =
        std::find_if(plt_hook_saved.begin(), plt_hook_saved.end(),
                     [info](auto record) { return strcmp(record.first, info.dli_sname) == 0; });
    void *stub = nullptr;
    if (hook_iter != plt_hook_saved.end() &&
        HookPLT(original, *(hook_iter->second), &stub, false)) {
        plt_hook_saved.erase(hook_iter);
        return 0;
    }
    return 1;
}

inline std::string GetNativeBridgeSignature() {
    const auto &obfs_map = ConfigBridge::GetInstance()->obfuscation_map();
    static auto signature = obfs_map.at("org.lsposed.lspd.nativebridge.");
    return signature;
}

}  // namespace lspd

#pragma clang diagnostic pop
