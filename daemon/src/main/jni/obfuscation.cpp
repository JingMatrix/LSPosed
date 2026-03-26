#include "obfuscation.h"

#include <android/sharedmem.h>
#include <android/sharedmem_jni.h>
#include <fcntl.h>
#include <jni.h>
#include <slicer/dex_utf8.h>
#include <slicer/reader.h>
#include <slicer/writer.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <map>
#include <mutex>
#include <random>
#include <string>
#include <string_view>
#include <utils/jni_helper.hpp>

namespace {

std::once_flag init_flag;

std::map<std::string, std::string> signatures = {
    {"Lde/robv/android/xposed/", ""},    {"Landroid/app/AndroidApp", ""},
    {"Landroid/content/res/XRes", ""},   {"Landroid/content/res/XModule", ""},
    {"Lorg/matrix/vector/core/", ""},    {"Lorg/matrix/vector/nativebridge/", ""},
    {"Lorg/matrix/vector/service/", ""},
};

jclass class_file_descriptor = nullptr;
jmethodID method_file_descriptor_ctor = nullptr;

jclass class_shared_memory = nullptr;
jmethodID method_shared_memory_ctor = nullptr;

}  // anonymous namespace

// Converts Dex signatures to Java format.
// Trailing slashes are translated to dots, which correctly aligns with
// Java's string matching expectations for package prefixes.
static std::string to_java(const std::string &signature) {
    std::string java(signature, 1);
    std::replace(java.begin(), java.end(), '/', '.');
    return java;
}

static void ensureInitialized(JNIEnv *env) {
    // Thread-safe one-time initialization
    std::call_once(init_flag, [&]() {
        LOGD("ObfuscationManager.init");

        if (auto file_descriptor = lsplant::JNI_FindClass(env, "java/io/FileDescriptor")) {
            class_file_descriptor =
                static_cast<jclass>(lsplant::JNI_NewGlobalRef(env, file_descriptor));
        } else
            return;

        method_file_descriptor_ctor =
            lsplant::JNI_GetMethodID(env, class_file_descriptor, "<init>", "(I)V");

        if (auto shared_memory = lsplant::JNI_FindClass(env, "android/os/SharedMemory")) {
            class_shared_memory =
                static_cast<jclass>(lsplant::JNI_NewGlobalRef(env, shared_memory));
        } else
            return;

        method_shared_memory_ctor = lsplant::JNI_GetMethodID(env, class_shared_memory, "<init>",
                                                             "(Ljava/io/FileDescriptor;)V");

        auto regen = [](std::string_view original_signature) {
            static constexpr auto chrs = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

            thread_local static std::mt19937 rg{std::random_device{}()};
            thread_local static std::uniform_int_distribution<std::string::size_type> pick(
                0, strlen(chrs) - 1);
            thread_local static std::uniform_int_distribution<std::string::size_type> choose_slash(
                0, 10);

            std::string out;
            size_t length = original_signature.size();
            out.reserve(length);
            out += "L";

            for (size_t i = 1; i < length - 1; i++) {
                if (choose_slash(rg) > 8 &&  // 20% chance for a slash
                    out.back() != '/' &&     // Avoid consecutive slashes
                    i != 1 &&                // No slash immediately after 'L'
                    i != length - 2) {       // No slash right before the end
                    out += "/";
                } else {
                    out += chrs[pick(rg)];
                }
            }

            // Respect the original termination character type to prevent
            if (original_signature.back() == '/') {
                out += "/";
            } else {
                out += chrs[pick(rg)];
            }

            if (out.length() != original_signature.length()) {
                LOGE("Length mismatch! Org: %zu vs New: %zu. '%s' -> '%s'",
                     original_signature.length(), out.length(),
                     std::string(original_signature).c_str(), out.c_str());
            }

            return out;
        };

        for (auto &i : signatures) {
            i.second = regen(i.first);
            LOGD("%s => %s", i.first.c_str(), i.second.c_str());
        }

        LOGD("ObfuscationManager init successfully");
    });
}

static jobject stringMapToJavaHashMap(JNIEnv *env, const std::map<std::string, std::string> &map) {
    jclass mapClass = env->FindClass("java/util/HashMap");
    if (mapClass == nullptr) return nullptr;

    jmethodID init = env->GetMethodID(mapClass, "<init>", "()V");
    jobject hashMap = env->NewObject(mapClass, init);
    jmethodID put = env->GetMethodID(mapClass, "put",
                                     "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    for (const auto &[key, value] : map) {
        jstring keyJava = env->NewStringUTF(key.c_str());
        jstring valueJava = env->NewStringUTF(value.c_str());

        env->CallObjectMethod(hashMap, put, keyJava, valueJava);

        env->DeleteLocalRef(keyJava);
        env->DeleteLocalRef(valueJava);
    }

    jobject hashMapGlobal = env->NewGlobalRef(hashMap);
    env->DeleteLocalRef(hashMap);
    env->DeleteLocalRef(mapClass);

    return hashMapGlobal;
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_matrix_vector_daemon_utils_ObfuscationManager_getSignatures(
    JNIEnv *env, [[maybe_unused]] jclass clazz) {
    ensureInitialized(env);

    static jobject signatures_jni = nullptr;
    static std::once_flag jni_map_flag;

    // Thread-safe, one-time JNI HashMap translation
    std::call_once(jni_map_flag, [&]() {
        std::map<std::string, std::string> signatures_java;
        for (const auto &i : signatures) {
            signatures_java[to_java(i.first)] = to_java(i.second);
        }
        signatures_jni = stringMapToJavaHashMap(env, signatures_java);
    });

    return signatures_jni;
}

static int obfuscateDexBuffer(const void *dex_data, size_t size) {
    // LOGD("obfuscateDexBuffer: dex_data=%p, size=%zu", dex_data, size);
    dex::Reader reader{reinterpret_cast<const dex::u1 *>(dex_data), size};
    reader.CreateFullIr();
    auto ir = reader.GetIr();

    LOGD("Mutating strings in-place");
    // Mutate strings in-place.
    for (auto &i : ir->strings) {
        const char *s = i->c_str();
        for (const auto &signature : signatures) {
            char *p = const_cast<char *>(strstr(s, signature.first.c_str()));
            if (p) memcpy(p, signature.second.c_str(), signature.first.length());
        }
    }

    dex::Writer writer(ir);
    size_t new_size;
    DexAllocator allocator;

    // CreateImage calls allocator.Allocate()
    auto *image = writer.CreateImage(&allocator, &new_size);
    LOGD("writer.CreateImage returned: %p", image);

    return allocator.GetFd();
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_matrix_vector_daemon_utils_ObfuscationManager_obfuscateDex(JNIEnv *env,
                                                                    [[maybe_unused]] jclass clazz,
                                                                    jobject memory) {
    ensureInitialized(env);

    int fd = ASharedMemory_dupFromJava(env, memory);
    if (fd < 0) return nullptr;

    auto size = ASharedMemory_getSize(fd);
    LOGD("obfuscateDex: fd=%d, size=%zu", fd, size);

    // CRITICAL: We MUST use MAP_SHARED here, not MAP_PRIVATE.
    // 1. Android's SharedMemory is backed by purely virtual IPC buffers (ashmem/memfd).
    //    If we use MAP_PRIVATE, the kernel attempts to create a Copy-On-Write snapshot.
    //    Because the Java side just populated this virtual buffer and immediately passed
    //    it to JNI, mapping it MAP_PRIVATE often results in mapping unpopulated zero-pages,
    //    which causes Slicer to read a corrupted/empty header and abort.
    // 2. Using MAP_SHARED gives us direct pointers to the exact physical memory pages
    //    populated by Java.
    // 3. ZERO-COPY ARCHITECTURE: Because Slicer's IR holds direct pointers to this mapped
    //    memory, mutating strings in-place (via const_cast) instantly updates the IR
    //    without allocating new memory. Since the Java caller discards the original
    //    SharedMemory buffer anyway, this in-place mutation is completely safe and highly
    //    efficient.
    void *mem = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (mem == MAP_FAILED) {
        LOGE("Failed to map input dex");
        close(fd);
        return nullptr;
    }

    bool needs_obfuscation = false;
    for (const auto &sig : signatures) {
        if (memmem(mem, size, sig.first.c_str(), sig.first.length()) != nullptr) {
            needs_obfuscation = true;
            break;
        }
    }

    if (!needs_obfuscation) {
        LOGD("No target signatures found in fd=%d, skipping slicer.", fd);
        munmap(mem, size);

        // Wrap the duplicated FD into Java objects and return instantly
        auto java_fd =
            lsplant::JNI_NewObject(env, class_file_descriptor, method_file_descriptor_ctor, fd);
        auto java_sm =
            lsplant::JNI_NewObject(env, class_shared_memory, method_shared_memory_ctor, java_fd);
        return java_sm.release();
    }

    // Process the DEX and obtain a new file descriptor for the output
    int new_fd = obfuscateDexBuffer(mem, size);

    // Safely unmap and close the input buffer mapping
    munmap(mem, size);
    close(fd);

    if (new_fd < 0) {
        LOGE("Obfuscation failed to create new dex buffer");
        return nullptr;
    }

    // Construct new SharedMemory object around the new_fd
    auto java_fd =
        lsplant::JNI_NewObject(env, class_file_descriptor, method_file_descriptor_ctor, new_fd);
    auto java_sm =
        lsplant::JNI_NewObject(env, class_shared_memory, method_shared_memory_ctor, java_fd);

    return java_sm.release();
}
