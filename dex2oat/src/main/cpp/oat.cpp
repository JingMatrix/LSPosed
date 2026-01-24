#include "oat.h"

namespace art {

// Helpers based on the header file, renamed intentionally to avoid confusing PLT hook targets.
uint32_t OatHeader::getKeyValueStoreSize() const {
    return *(uint32_t*)((uintptr_t)this + OatHeader::Get_key_value_store_size_Offset());
}

const uint8_t* OatHeader::getKeyValueStore() const {
    return (const uint8_t*)((uintptr_t)this + OatHeader::Get_key_value_store_Offset());
}

void OatHeader::setKeyValueStoreSize(uint32_t new_size) {
    *reinterpret_cast<uint32_t*>((uintptr_t)this + Get_key_value_store_size_Offset()) = new_size;
}

}  // namespace art
