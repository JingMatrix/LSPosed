#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "logging.h"

static uint32_t (*OatHeader_GetKeyValueStoreSize)(void*);
static uint8_t* (*OatHeader_GetKeyValueStore)(void*);
static bool store_updated = false;
const char* parameter_to_remove = " --inline-max-code-units=0";

typedef struct {
    char* key;
    char* value;
} KeyValuePair;

void UpdateKeyValueStore(KeyValuePair* key_value, uint8_t* store) {
    char* data_ptr = (char*)store;
    while (key_value != NULL && key_value->key != NULL) {
        strcpy(data_ptr, key_value->key);
        data_ptr += strlen(key_value->key) + 1;
        strcpy(data_ptr, key_value->value);
        data_ptr += strlen(key_value->value) + 1;
        key_value++;
    }
    LOGD("KeyValueStore updated");
    store_updated = true;
}

__attribute__((visibility("default"))) uint8_t* _ZNK3art9OatHeader16GetKeyValueStoreEv(
    void* header) {
    LOGD("OatHeader::GetKeyValueStore() called on object at %p\n", header);
    uint8_t* key_value_store_ = OatHeader_GetKeyValueStore(header);
    uint32_t key_value_store_size_ = OatHeader_GetKeyValueStoreSize(header);
    const char* ptr = (const char*)(key_value_store_);
    const char* end = ptr + key_value_store_size_;
    LOGD("store: %p, size: %d", key_value_store_, key_value_store_size_);
    KeyValuePair* new_store = NULL;
    int new_store_count = 0;

    while (ptr < end) {
        // Scan for a closing zero.
        const char* key_end = (const char*)(memchr(ptr, '\0', end - ptr));
        if (!key_end) {
            LOGE("no key_end scanned");
            return key_value_store_;
        }
        char* key = strndup(ptr, key_end - ptr);
        const char* value_start = key_end + 1;
        const char* value_end = (const char*)(memchr(value_start, '\0', end - value_start));
        if (!value_end) {
            LOGE("no value_end scanned");
            return key_value_store_;
        }
        char* value = strndup(value_start, value_end - value_start);

        // Clean the value if the key is "dex2oat-cmdline"
        if (strcmp(key, "dex2oat-cmdline") == 0) {
            size_t len = strlen(value) - strlen(parameter_to_remove);
            char* new_value = (char*)malloc(len + 1);
            strncpy(new_value, value, len);
            new_value[len] = '\0';
            free(value);
            value = new_value;
        }

        new_store_count++;
        new_store = (KeyValuePair*)realloc(new_store, sizeof(KeyValuePair) * new_store_count);
        new_store[new_store_count - 1].key = key;
        new_store[new_store_count - 1].value = value;

        // Advance over the value.
        ptr = value_end + 1;
    }
    UpdateKeyValueStore(new_store, key_value_store_);

    // Free allocated memory
    for (int i = 0; i < new_store_count; i++) {
        free(new_store[i].key);
        free(new_store[i].value);
    }
    free(new_store);

    return key_value_store_;
}

__attribute__((visibility("default"))) uint32_t
_ZNK3art9OatHeader20GetKeyValueStoreSizeEv(void* header) {
    uint32_t size = OatHeader_GetKeyValueStoreSize(header);
    if (store_updated) {
        LOGD("OatHeader::GetKeyValueStoreSize() called on object at %p\n", header);
        size -= strlen(parameter_to_remove);
    }
    return size;
}

__attribute__((constructor)) static void initialize() {
    if (!OatHeader_GetKeyValueStore) {
        const char* symbol = "_ZNK3art9OatHeader16GetKeyValueStoreEv";
        OatHeader_GetKeyValueStore = dlsym(RTLD_NEXT, symbol);
        if (!OatHeader_GetKeyValueStore) {
            LOGE("Error resolving symbol %s: %s", symbol, dlerror());
        }
    }

    if (!OatHeader_GetKeyValueStoreSize) {
        const char* symbol = "_ZNK3art9OatHeader20GetKeyValueStoreSizeEv";
        OatHeader_GetKeyValueStoreSize = dlsym(RTLD_NEXT, symbol);
        if (!OatHeader_GetKeyValueStoreSize) {
            LOGE("Error resolving symbol %s: %s", symbol, dlerror());
        }
    }
}
