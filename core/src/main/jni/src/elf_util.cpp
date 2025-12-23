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
 * Copyright (C) 2019 Swift Gan
 * Copyright (C) 2021 LSPosed Contributors
 */
#include "elf_util.h"

#include <fcntl.h>
#include <malloc.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cassert>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>

#include "linux/xz.h"
#include "logging.h"

using namespace SandHook;

template <typename T>
inline constexpr auto offsetOf(ElfW(Ehdr) * head, ElfW(Off) off) {
    return reinterpret_cast<std::conditional_t<std::is_pointer_v<T>, T, T *>>(
        reinterpret_cast<uintptr_t>(head) + off);
}

ElfImg::ElfImg(std::string_view base_name) : elf(base_name) {
    if (!findModuleBase()) {
        base = nullptr;
        return;
    }

    // load elf
    int fd = open(elf.data(), O_RDONLY);
    if (fd < 0) {
        LOGE("failed to open {}", elf);
        return;
    }

    size = lseek(fd, 0, SEEK_END);
    if (size <= 0) {
        LOGE("lseek() failed for {}", elf);
    }

    header = reinterpret_cast<decltype(header)>(mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0));

    close(fd);
    parse(header);
    if (isStripped()) {
        if (xzdecompress()) {
            header_debugdata = reinterpret_cast<ElfW(Ehdr) *>(elf_debugdata.data());
            parse(header_debugdata);
        }
    }
}

void ElfImg::parse(ElfW(Ehdr) * hdr) {
    section_header = offsetOf<decltype(section_header)>(hdr, hdr->e_shoff);

    auto shoff = reinterpret_cast<uintptr_t>(section_header);
    char *section_str = offsetOf<char *>(hdr, section_header[hdr->e_shstrndx].sh_offset);

    for (int i = 0; i < hdr->e_shnum; i++, shoff += hdr->e_shentsize) {
        auto *section_h = (ElfW(Shdr) *)shoff;
        char *sname = section_h->sh_name + section_str;
        auto entsize = section_h->sh_entsize;
        switch (section_h->sh_type) {
        case SHT_DYNSYM: {
            if (bias == -4396) {
                dynsym = section_h;
                dynsym_offset = section_h->sh_offset;
                dynsym_start = offsetOf<decltype(dynsym_start)>(hdr, dynsym_offset);
                LOGD("dynsym header {:#x} size {}", section_h->sh_offset, section_h->sh_size);
            }
            break;
        }
        case SHT_SYMTAB: {
            if (strcmp(sname, ".symtab") == 0) {
                symtab = section_h;
                symtab_offset = section_h->sh_offset;
                symtab_size = section_h->sh_size;
                symtab_count = symtab_size / entsize;
                symtab_start = offsetOf<decltype(symtab_start)>(hdr, symtab_offset);
                LOGD("symtab header {:#x} size {} found in {}", section_h->sh_offset,
                     section_h->sh_size, debugdata_offset != 0 ? "gnu_debugdata" : "orgin elf");
            }
            break;
        }
        case SHT_STRTAB: {
            if (bias == -4396) {
                strtab = section_h;
                symstr_offset = section_h->sh_offset;
                strtab_start = offsetOf<decltype(strtab_start)>(hdr, symstr_offset);
                LOGD("strtab header {:#x} size {}", section_h->sh_offset, section_h->sh_size);
            }
            if (strcmp(sname, ".strtab") == 0) {
                symstr_offset_for_symtab = section_h->sh_offset;
            }
            break;
        }
        case SHT_PROGBITS: {
            if (strcmp(sname, ".gnu_debugdata") == 0) {
                debugdata_offset = section_h->sh_offset;
                debugdata_size = section_h->sh_size;
                LOGD("gnu_debugdata header {:#x} size {}", section_h->sh_offset,
                     section_h->sh_size);
            }
            if (strtab == nullptr || dynsym == nullptr) break;
            if (bias == -4396) {
                bias = (off_t)section_h->sh_addr - (off_t)section_h->sh_offset;
            }
            break;
        }
        case SHT_HASH: {
            auto *d_un = offsetOf<ElfW(Word)>(hdr, section_h->sh_offset);
            nbucket_ = d_un[0];
            bucket_ = d_un + 2;
            chain_ = bucket_ + nbucket_;
            break;
        }
        case SHT_GNU_HASH: {
            auto *d_buf = reinterpret_cast<ElfW(Word) *>(((size_t)hdr) + section_h->sh_offset);
            gnu_nbucket_ = d_buf[0];
            gnu_symndx_ = d_buf[1];
            gnu_bloom_size_ = d_buf[2];
            gnu_shift2_ = d_buf[3];
            gnu_bloom_filter_ = reinterpret_cast<decltype(gnu_bloom_filter_)>(d_buf + 4);
            gnu_bucket_ =
                reinterpret_cast<decltype(gnu_bucket_)>(gnu_bloom_filter_ + gnu_bloom_size_);
            gnu_chain_ = gnu_bucket_ + gnu_nbucket_ - gnu_symndx_;
            break;
        }
        }
    }
}

bool ElfImg::xzdecompress() {
    struct xz_buf str_xz_buf;
    struct xz_dec *str_xz_dec;
    enum xz_ret ret = XZ_OK;
    bool bError = true;

#define BUFSIZE 1024 * 1024

    xz_crc32_init();
#ifdef XZ_USE_CRC64
    xz_crc64_init();
#endif
    str_xz_dec = xz_dec_init(XZ_DYNALLOC, 1 << 26);
    if (str_xz_dec == NULL) {
        LOGE("xz_dec_init memory allocation failed");
        return false;
    }

    uint8_t *sBuffOut = (uint8_t *)malloc(BUFSIZE);
    if (sBuffOut == NULL) {
        LOGE("allocation for debugdata_header failed");
        return false;
    }

    int iSzOut = BUFSIZE;

    str_xz_buf.in = ((uint8_t *)header) + debugdata_offset;
    str_xz_buf.in_pos = 0;
    str_xz_buf.in_size = debugdata_size;
    str_xz_buf.out = sBuffOut;
    str_xz_buf.out_pos = 0;
    str_xz_buf.out_size = BUFSIZE;

    uint8_t iSkip = 0;

    while (true) {
        ret = xz_dec_run(str_xz_dec, &str_xz_buf);

        if (str_xz_buf.out_pos == BUFSIZE) {
            str_xz_buf.out_pos = 0;
            iSkip++;
        } else {
            iSzOut -= (BUFSIZE - str_xz_buf.out_pos);
        }

        if (ret == XZ_OK) {
            iSzOut += BUFSIZE;
            sBuffOut = (uint8_t *)realloc(sBuffOut, iSzOut);
            str_xz_buf.out = sBuffOut + (iSkip * BUFSIZE);
            continue;
        }

#ifdef XZ_DEC_ANY_CHECK
        if (ret == XZ_UNSUPPORTED_CHECK) {
            LOGW("Unsupported check; not verifying file integrity");
            continue;
        }
#endif
        break;
    }  // end while true

    switch (ret) {
    case XZ_STREAM_END:
        bError = false;
        break;

    case XZ_MEM_ERROR:
        LOGE("Memory allocation failed");
        break;

    case XZ_MEMLIMIT_ERROR:
        LOGE("Memory usage limit reached");
        break;

    case XZ_FORMAT_ERROR:
        LOGE("Not a .xz file");
        break;

    case XZ_OPTIONS_ERROR:
        LOGE("Unsupported options in the .xz headers");
        break;

    case XZ_DATA_ERROR:
        LOGE("Compressed data is corrupt");
        break;

    case XZ_BUF_ERROR:
        LOGE("xz_dec_run failed with XZ_BUF_ERROR");
        break;

    default:
        LOGE("xz_dec_run return a wrong value!");
        break;
    }
    xz_dec_end(str_xz_dec);
    if (bError) {
        return false;
    }
    if (sBuffOut[0] != 0x7F && sBuffOut[1] != 0x45 && sBuffOut[2] != 0x4C && sBuffOut[3] != 0x46) {
        LOGE("not ELF header in gnu_debugdata");
        return false;
    }
    elf_debugdata = std::string((char *)sBuffOut, iSzOut);
    free(sBuffOut);
    return true;
}

ElfW(Addr) ElfImg::ElfLookup(std::string_view name, uint32_t hash) const {
    if (nbucket_ == 0) return 0;

    char *strings = (char *)strtab_start;

    for (auto n = bucket_[hash % nbucket_]; n != 0; n = chain_[n]) {
        auto *sym = dynsym_start + n;
        if (name == strings + sym->st_name) {
            return sym->st_value;
        }
    }
    return 0;
}

ElfW(Addr) ElfImg::GnuLookup(std::string_view name, uint32_t hash) const {
    static constexpr auto bloom_mask_bits = sizeof(ElfW(Addr)) * 8;

    if (gnu_nbucket_ == 0 || gnu_bloom_size_ == 0) return 0;

    auto bloom_word = gnu_bloom_filter_[(hash / bloom_mask_bits) % gnu_bloom_size_];
    uintptr_t mask = 0 | (uintptr_t)1 << (hash % bloom_mask_bits) |
                     (uintptr_t)1 << ((hash >> gnu_shift2_) % bloom_mask_bits);
    if ((mask & bloom_word) == mask) {
        auto sym_index = gnu_bucket_[hash % gnu_nbucket_];
        if (sym_index >= gnu_symndx_) {
            char *strings = (char *)strtab_start;
            do {
                auto *sym = dynsym_start + sym_index;
                if (((gnu_chain_[sym_index] ^ hash) >> 1) == 0 && name == strings + sym->st_name) {
                    return sym->st_value;
                }
            } while ((gnu_chain_[sym_index++] & 1) == 0);
        }
    }
    return 0;
}

void ElfImg::MayInitLinearMap() const {
    if (symtabs_.empty()) {
        if (symtab_start != nullptr && symstr_offset_for_symtab != 0) {
            auto hdr = header_debugdata != nullptr ? header_debugdata : header;
            for (ElfW(Off) i = 0; i < symtab_count; i++) {
                unsigned int st_type = ELF_ST_TYPE(symtab_start[i].st_info);
                const char *st_name =
                    offsetOf<const char *>(hdr, symstr_offset_for_symtab + symtab_start[i].st_name);
                if ((st_type == STT_FUNC || st_type == STT_OBJECT) && symtab_start[i].st_size) {
                    symtabs_.emplace(st_name, &symtab_start[i]);
                }
            }
        }
    }
}

ElfW(Addr) ElfImg::LinearLookup(std::string_view name) const {
    MayInitLinearMap();
    if (auto i = symtabs_.find(name); i != symtabs_.end()) {
        return i->second->st_value;
    } else {
        return 0;
    }
}

std::vector<ElfW(Addr)> ElfImg::LinearRangeLookup(std::string_view name) const {
    MayInitLinearMap();
    std::vector<ElfW(Addr)> res;
    for (auto [i, end] = symtabs_.equal_range(name); i != end; ++i) {
        auto offset = i->second->st_value;
        res.emplace_back(offset);
        LOGD("found {} {:#x} in {} in symtab by linear range lookup", name, offset, elf);
    }
    return res;
}

ElfW(Addr) ElfImg::PrefixLookupFirst(std::string_view prefix) const {
    MayInitLinearMap();
    if (auto i = symtabs_.lower_bound(prefix);
        i != symtabs_.end() && i->first.starts_with(prefix)) {
        LOGD("found prefix {} of {} {:#x} in {} in symtab by linear lookup", prefix, i->first,
             i->second->st_value, elf);
        return i->second->st_value;
    } else {
        return 0;
    }
}

ElfImg::~ElfImg() {
    // open elf file local
    if (buffer) {
        free(buffer);
        buffer = nullptr;
    }
    // use mmap
    if (header) {
        munmap(header, size);
    }
}

ElfW(Addr) ElfImg::getSymbOffset(std::string_view name, uint32_t gnu_hash,
                                 uint32_t elf_hash) const {
    if (auto offset = GnuLookup(name, gnu_hash); offset > 0) {
        LOGD("found {} {:#x} in {} in dynsym by gnuhash", name, offset, elf);
        return offset;
    } else if (offset = ElfLookup(name, elf_hash); offset > 0) {
        LOGD("found {} {:#x} in {} in dynsym by elfhash", name, offset, elf);
        return offset;
    } else if (offset = LinearLookup(name); offset > 0) {
        LOGD("found {} {:#x} in {} in symtab by linear lookup", name, offset, elf);
        return offset;
    } else {
        return 0;
    }
}

constexpr inline bool contains(std::string_view a, std::string_view b) {
    return a.find(b) != std::string_view::npos;
}

// A clean and simple struct to hold parsed map entry data.
struct MapEntry {
    uintptr_t start_addr;
    char perms[5] = {0};  // Assured null-termination
    std::string pathname;
};

bool ElfImg::findModuleBase() {
    // Open the maps file using standard C file I/O.
    FILE *maps = fopen("/proc/self/maps", "r");
    if (!maps) {
        LOGE("failed to open /proc/self/maps");
        return false;
    }

    char line_buffer[512];  // A reasonable fixed-size buffer for map lines.
    std::vector<MapEntry> filtered_list;

    // Step 1: Filter all entries containing `elf` in its path.
    while (fgets(line_buffer, sizeof(line_buffer), maps)) {
        // Use an intermediate variable of a known, large type to avoid format warnings.
        // `unsigned long long` and `%llx` are standard and portable.
        unsigned long long temp_start;
        char path_buffer[256] = {0};
        char p[5] = {0};

        // Use the portable `%llx` specifier.
        int items_parsed =
            sscanf(line_buffer, "%llx-%*x %4s %*x %*s %*d %255s", &temp_start, p, path_buffer);

        // The filter condition: must parse the path, and it must contain the elf name.
        if (items_parsed == 3 && strstr(path_buffer, elf.c_str()) != nullptr) {
            MapEntry entry;
            // Safely assign the parsed value to the uintptr_t.
            entry.start_addr = static_cast<uintptr_t>(temp_start);
            strncpy(entry.perms, p, 4);
            entry.pathname = path_buffer;
            filtered_list.push_back(std::move(entry));
        }
    }
    fclose(maps);

    if (filtered_list.empty()) {
        LOGE("Could not find any mappings for {}", elf.data());
        return false;
    }

    // Also part of Step 1: Print the filtered list for debugging.
    LOGD("Found {} filtered map entries for {}:", filtered_list.size(), elf.data());
    for (const auto &entry : filtered_list) {
        LOGD("  {:#x} {} {}", entry.start_addr, entry.perms, entry.pathname);
    }

    const MapEntry *found_block = nullptr;

    // Step 2: In the filtered list, search for the first `r--p` whose next entry is `r-xp`.
    for (size_t i = 0; i < filtered_list.size() - 1; ++i) {
        if (strcmp(filtered_list[i].perms, "r--p") == 0 &&
            strcmp(filtered_list[i + 1].perms, "r-xp") == 0) {
            found_block = &filtered_list[i];
            LOGD("Found `r--p` -> `r-xp` pattern. Choosing base from `r--p` block at {:#x}",
                 found_block->start_addr);
            break;  // Pattern found, exit loop.
        }
    }

    // Step 2 (Fallback): If the pattern was not found, find the first `r-xp` entry.
    if (!found_block) {
        LOGD("`r--p` -> `r-xp` pattern not found. Falling back to first `r-xp` entry.");
        for (const auto &entry : filtered_list) {
            if (strcmp(entry.perms, "r-xp") == 0) {
                found_block = &entry;
                LOGD("Found first `r-xp` block at {:#x}", found_block->start_addr);
                break;  // Fallback found, exit loop.
            }
        }
    }

    // Step 3 (Fallback): If the pattern was not found, find the first `r--p` entry.
    if (!found_block) {
        LOGD("`r-xp` pattern not found. Falling back to first `r--p` entry.");
        for (const auto &entry : filtered_list) {
            if (strcmp(entry.perms, "r--p") == 0) {
                found_block = &entry;
                LOGD("Found first `r--p` block at {:#x}", found_block->start_addr);
                break;  // Fallback found, exit loop.
            }
        }
    }

    if (!found_block) {
        LOGE("Fatal: Could not determine a base address for {}", elf.data());
        return false;
    }

    // Step 3: Use the starting address of the found block as the base address.
    base = reinterpret_cast<void *>(found_block->start_addr);
    elf = found_block->pathname;  // Update elf path to the canonical one.

    LOGD("get module base {}: {:#x}", elf, found_block->start_addr);
    LOGD("update path: {}", elf);

    return true;
}
