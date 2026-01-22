#include <dlfcn.h>

#include <cinttypes>
#include <cstdint>
#include <lsplt.hpp>
#include <string>
#include <string_view>

#include "logging.h"
#include "oat.h"

const std::string_view param_to_remove = " --inline-max-code-units=0";

#define DCL_HOOK_FUNC(ret, func, ...)                                                              \
    ret (*old_##func)(__VA_ARGS__);                                                                \
    ret new_##func(__VA_ARGS__)

bool store_resized = false;

static void safe_memmove(uint8_t* dst, const uint8_t* src, size_t n) {
    if (dst < src) {
        for (size_t i = 0; i < n; i++) dst[i] = src[i];
    } else if (dst > src) {
        for (size_t i = n; i > 0; i--) dst[i - 1] = src[i - 1];
    }
}

bool ModifyStoreInPlace(uint8_t* store, uint32_t store_size) {
    if (store == nullptr || store_size == 0) {
        return false;
    }

    // Define the search space
    uint8_t* const store_begin = store;
    uint8_t* const store_end = store + store_size;

    // 1. Search for the parameter in the memory buffer
    auto it = std::search(store_begin, store_end, param_to_remove.begin(), param_to_remove.end());

    // Check if the parameter was found
    if (it == store_end) {
        LOGD("Parameter '%.*s' not found.", (int)param_to_remove.size(), param_to_remove.data());
        return false;
    }

    uint8_t* location_of_param = it;
    LOGD("Parameter found at offset %td.", location_of_param - store_begin);

    // 2. Check if there is padding immediately after the string
    uint8_t* const byte_after_param = location_of_param + param_to_remove.size();
    bool has_padding = false;

    // Boundary check: ensure the byte after the parameter is within the buffer
    if (byte_after_param + 1 < store_end) {
        if (*(byte_after_param + 1) == '\0') {
            has_padding = true;
        }
    }

    // 3. Perform the conditional action
    if (has_padding) {
        // CASE A: Padding exists. Overwrite the parameter with zeros.
        LOGD("Padding found. Overwriting parameter with zeros.");
        memset(location_of_param, 0, param_to_remove.size());
        return false;  // Size did not change
    } else {
        // CASE B: No padding exists (or parameter is at the very end).
        // Remove the parameter by shifting the rest of the memory forward.
        LOGD("No padding found. Removing parameter and shifting memory.");

        // Calculate what to move
        uint8_t* source = byte_after_param;
        uint8_t* destination = location_of_param;
        size_t bytes_to_move = store_end - source;

        // memmove is required because the source and destination buffers overlap
        if (bytes_to_move > 0) {
            safe_memmove(destination, source, bytes_to_move);
        }

        // 4. Update the total size of the store
        store_size -= param_to_remove.size();
        LOGD("Store size changed. New size: %u", store_size);

        return true;  // Size changed
    }
}

DCL_HOOK_FUNC(uint32_t, _ZNK3art9OatHeader20GetKeyValueStoreSizeEv, void* header) {
    uint32_t size = old__ZNK3art9OatHeader20GetKeyValueStoreSizeEv(header);
    if (store_resized) {
        LOGD("OatHeader::GetKeyValueStoreSize() called on object at %p\n", header);
        size = size - param_to_remove.size();
    }
    return size;
}

DCL_HOOK_FUNC(uint8_t*, _ZNK3art9OatHeader16GetKeyValueStoreEv, void* header) {
    LOGD("OatHeader::GetKeyValueStore() called on object at %p\n", header);
    uint8_t* key_value_store_ = old__ZNK3art9OatHeader16GetKeyValueStoreEv(header);
    uint32_t key_value_store_size_ = old__ZNK3art9OatHeader20GetKeyValueStoreSizeEv(header);
    LOGD("KeyValueStore via hook: [addr: %p, size: %u]", key_value_store_, key_value_store_size_);
    store_resized = ModifyStoreInPlace(key_value_store_, key_value_store_size_);

    return key_value_store_;
}

DCL_HOOK_FUNC(void, _ZNK3art9OatHeader15ComputeChecksumEPj, void* header, uint32_t* checksum) {
    art::OatHeader* oat_header = reinterpret_cast<art::OatHeader*>(header);
    const uint8_t* key_value_store_ = oat_header->GetKeyValueStore();
    uint32_t key_value_store_size_ = oat_header->GetKeyValueStoreSize();
    LOGD("KeyValueStore via offset: [addr: %p, size: %u]", key_value_store_, key_value_store_size_);
    store_resized =
        ModifyStoreInPlace(const_cast<uint8_t*>(key_value_store_), key_value_store_size_);
    if (store_resized) {
        oat_header->SetKeyValueStoreSize(key_value_store_size_ - param_to_remove.size());
    }
    old__ZNK3art9OatHeader15ComputeChecksumEPj(header, checksum);
    LOGD("ComputeChecksum called:  %" PRIu32, *checksum);
}

#undef DCL_HOOK_FUNC

void register_hook(dev_t dev, ino_t inode, const char* symbol, void* new_func, void** old_func) {
    LOGD("RegisterHook: %s, %p, %p", symbol, new_func, old_func);
    if (!lsplt::RegisterHook(dev, inode, symbol, new_func, old_func)) {
        LOGE("Failed to register plt_hook \"%s\"\n", symbol);
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
    if (!lsplt::CommitHook()) {
        PLT_HOOK_REGISTER(dev, inode, _ZNK3art9OatHeader15ComputeChecksumEPj);
        lsplt::CommitHook();
    }
}
