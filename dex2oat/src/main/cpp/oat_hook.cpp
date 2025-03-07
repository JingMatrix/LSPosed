#include <dlfcn.h>

#include <lsplt.hpp>
#include <map>
#include <string>
#include <string_view>
#include <vector>

#include "logging.h"

const std::string_view parameter_to_remove = " --inline-max-code-units=0";

#define DCL_HOOK_FUNC(ret, func, ...)                                                              \
    ret (*old_##func)(__VA_ARGS__);                                                                \
    ret new_##func(__VA_ARGS__)

bool store_updated = false;

void UpdateKeyValueStore(std::map<std::string, std::string>* key_value, uint8_t* store) {
    LOGD("updating KeyValueStore");
    char* data_ptr = reinterpret_cast<char*>(store);
    if (key_value != nullptr) {
        auto it = key_value->begin();
        auto end = key_value->end();
        for (; it != end; ++it) {
            strlcpy(data_ptr, it->first.c_str(), it->first.length() + 1);
            data_ptr += it->first.length() + 1;
            strlcpy(data_ptr, it->second.c_str(), it->second.length() + 1);
            data_ptr += it->second.length() + 1;
        }
    }
    LOGD("KeyValueStore updated");
    store_updated = true;
}

DCL_HOOK_FUNC(uint32_t, _ZNK3art9OatHeader20GetKeyValueStoreSizeEv, void* header) {
    uint32_t size = old__ZNK3art9OatHeader20GetKeyValueStoreSizeEv(header);
    if (store_updated) {
        LOGD("OatHeader::GetKeyValueStoreSize() called on object at %p\n", header);
        size = size - parameter_to_remove.size();
    }
    return size;
}

DCL_HOOK_FUNC(uint8_t*, _ZNK3art9OatHeader16GetKeyValueStoreEv, void* header) {
    LOGD("OatHeader::GetKeyValueStore() called on object at %p\n", header);
    uint8_t* key_value_store_ = old__ZNK3art9OatHeader16GetKeyValueStoreEv(header);
    uint32_t key_value_store_size_ = old__ZNK3art9OatHeader20GetKeyValueStoreSizeEv(header);
    const char* ptr = reinterpret_cast<const char*>(key_value_store_);
    const char* end = ptr + key_value_store_size_;
    std::map<std::string, std::string> new_store = {};

    LOGD("scanning [%p-%p] for oat headers", ptr, end);
    while (ptr < end) {
        // Scan for a closing zero.
        const char* str_end = reinterpret_cast<const char*>(memchr(ptr, 0, end - ptr));
        if (str_end == nullptr) [[unlikely]] {
            LOGE("failed to find str_end");
            return key_value_store_;
        }
        std::string_view key = std::string_view(ptr, str_end - ptr);
        const char* value_start = str_end + 1;
        const char* value_end =
            reinterpret_cast<const char*>(memchr(value_start, 0, end - value_start));
        if (value_end == nullptr) [[unlikely]] {
            LOGE("failed to find value_end");
            return key_value_store_;
        }
        std::string_view value = std::string_view(value_start, value_end - value_start);
        LOGV("header %s:%s", key.data(), value.data());
        if (key == "dex2oat-cmdline") {
            value = value.substr(0, value.size() - parameter_to_remove.size());
        }
        new_store.insert(std::make_pair(std::string(key), std::string(value)));
        // Different from key. Advance over the value.
        ptr = value_end + 1;
    }
    UpdateKeyValueStore(&new_store, key_value_store_);

    return key_value_store_;
}

#undef DCL_HOOK_FUNC

void register_hook(dev_t dev, ino_t inode, const char* symbol, void* new_func, void** old_func) {
    LOGD("RegisterHook: %s, %p, %p", symbol, new_func, old_func);
    if (!lsplt::RegisterHook(dev, inode, symbol, new_func, old_func)) {
        LOGE("Failed to register plt_hook \"%s\"\n", symbol);
        return;
    }
}

#define PLT_HOOK_REGISTER_SYM(DEV, INODE, SYM, NAME)                                               \
    register_hook(DEV, INODE, SYM, reinterpret_cast<void*>(new_##NAME),                            \
                  reinterpret_cast<void**>(&old_##NAME))

#define PLT_HOOK_REGISTER(DEV, INODE, NAME) PLT_HOOK_REGISTER_SYM(DEV, INODE, #NAME, NAME)

__attribute__((constructor)) static void initialize() {
    dev_t dev = 0;
    ino_t inode = 0;
    for (auto& info : lsplt::MapInfo::Scan()) {
        if (info.path.starts_with("/apex/com.android.art/bin/dex2oat")) {
            dev = info.dev;
            inode = info.inode;
            break;
        }
    }

    PLT_HOOK_REGISTER(dev, inode, _ZNK3art9OatHeader20GetKeyValueStoreSizeEv);
    PLT_HOOK_REGISTER(dev, inode, _ZNK3art9OatHeader16GetKeyValueStoreEv);
    if (lsplt::CommitHook()) {
        LOGD("lsplt hooks done");
    };
}
