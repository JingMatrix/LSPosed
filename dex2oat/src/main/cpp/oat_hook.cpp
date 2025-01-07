#include <dlfcn.h>

#include <map>
#include <string>
#include <string_view>

#include "logging.h"

static uint32_t (*OatHeader_GetKeyValueStoreSize)(void*);
static uint8_t* (*OatHeader_GetKeyValueStore)(void*);
static bool store_updated = false;
const std::string_view parameter_to_remove = " --inline-max-code-units=0";

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

extern "C" [[gnu::visibility("default")]]
uint8_t* _ZNK3art9OatHeader16GetKeyValueStoreEv(void* header) {
    LOGD("OatHeader::GetKeyValueStore() called on object at %p\n", header);
    uint8_t* key_value_store_ = OatHeader_GetKeyValueStore(header);
    uint32_t key_value_store_size_ = OatHeader_GetKeyValueStoreSize(header);
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

extern "C" [[gnu::visibility("default")]]
uint32_t _ZNK3art9OatHeader20GetKeyValueStoreSizeEv(void* header) {
    uint32_t size = OatHeader_GetKeyValueStoreSize(header);
    if (store_updated) {
        LOGD("OatHeader::GetKeyValueStoreSize() called on object at %p\n", header);
        size = size - parameter_to_remove.size();
    }
    return size;
}

__attribute__((constructor)) static void initialize() {
    if (!OatHeader_GetKeyValueStore) {
        OatHeader_GetKeyValueStore = reinterpret_cast<decltype(OatHeader_GetKeyValueStore)>(
            dlsym(RTLD_NEXT, "_ZNK3art9OatHeader16GetKeyValueStoreEv"));
        if (!OatHeader_GetKeyValueStore) {
            PLOGE("resolving symbol");
        }
    }

    if (!OatHeader_GetKeyValueStoreSize) {
        OatHeader_GetKeyValueStoreSize = reinterpret_cast<decltype(OatHeader_GetKeyValueStoreSize)>(
            dlsym(RTLD_NEXT, "_ZNK3art9OatHeader20GetKeyValueStoreSizeEv"));
        if (!OatHeader_GetKeyValueStoreSize) {
            PLOGE("resolving symbol");
        }
    }
}
