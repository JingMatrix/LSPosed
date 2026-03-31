#include "logcat.h"

#include <android/log.h>
#include <jni.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <functional>
#include <string>
#include <thread>

using namespace std::string_view_literals;
using namespace std::chrono_literals;

constexpr size_t kMaxLogSize = 4 * 1024 * 1024;
constexpr long kLogBufferSize = 128 * 1024;

namespace {
constexpr std::array<char, ANDROID_LOG_SILENT + 1> kLogChar = {
    /*ANDROID_LOG_UNKNOWN*/ '?',
    /*ANDROID_LOG_DEFAULT*/ '?',
    /*ANDROID_LOG_VERBOSE*/ 'V',
    /*ANDROID_LOG_DEBUG*/ 'D',
    /*ANDROID_LOG_INFO*/ 'I',
    /*ANDROID_LOG_WARN*/ 'W',
    /*ANDROID_LOG_ERROR*/ 'E',
    /*ANDROID_LOG_FATAL*/ 'F',
    /*ANDROID_LOG_SILENT*/ 'S',
};

long ParseUint(const char *s) {
    if (s[0] == '\0') return -1;

    while (isspace(*s)) {
        s++;
    }

    if (s[0] == '-') {
        return -1;
    }

    int base = (s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) ? 16 : 10;
    char *end;
    auto result = strtoull(s, &end, base);
    if (end == s) {
        return -1;
    }
    if (*end != '\0') {
        const char *suffixes = "bkmgtpe";
        const char *suffix;
        if ((suffix = strchr(suffixes, tolower(*end))) == nullptr ||
            __builtin_mul_overflow(result, 1ULL << (10 * (suffix - suffixes)), &result)) {
            return -1;
        }
    }
    if (std::numeric_limits<size_t>::max() < result) {
        return -1;
    }
    return static_cast<size_t>(result);
}

inline long GetByteProp(std::string_view prop, long def = -1) {
    std::array<char, PROP_VALUE_MAX> buf{};
    if (__system_property_get(prop.data(), buf.data()) < 0) return def;
    return ParseUint(buf.data());
}

inline std::string GetStrProp(std::string_view prop, std::string def = {}) {
    std::array<char, PROP_VALUE_MAX> buf{};
    if (__system_property_get(prop.data(), buf.data()) < 0) return def;
    return {buf.data()};
}

inline bool SetIntProp(std::string_view prop, int val) {
    auto buf = std::to_string(val);
    return __system_property_set(prop.data(), buf.data()) >= 0;
}

inline bool SetStrProp(std::string_view prop, std::string_view val) {
    return __system_property_set(prop.data(), val.data()) >= 0;
}

}  // namespace

class UniqueFile : public std::unique_ptr<FILE, std::function<void(FILE *)>> {
    inline static deleter_type deleter = [](auto f) { f &&f != stdout &&fclose(f); };

public:
    explicit UniqueFile(FILE *f) : std::unique_ptr<FILE, std::function<void(FILE *)>>(f, deleter) {}

    UniqueFile(int fd, const char *mode) : UniqueFile(fd > 0 ? fdopen(fd, mode) : stdout) {};

    UniqueFile() : UniqueFile(stdout) {};
};

class Logcat {
public:
    explicit Logcat(JNIEnv *env, jobject thiz, jmethodID method)
        : env_(env), thiz_(thiz), refresh_fd_method_(method) {}

    [[noreturn]] void Run();

private:
    inline void RefreshFd(bool is_verbose);

    inline void Log(std::string_view str);

    void OnCrash(int err);

    void ProcessBuffer(struct log_msg *buf);

    static size_t PrintLogLine(const AndroidLogEntry &entry, FILE *out);

    JNIEnv *env_;
    jobject thiz_;
    jmethodID refresh_fd_method_;

    UniqueFile modules_file_{};
    size_t modules_file_part_ = 0;
    size_t modules_print_count_ = 0;

    UniqueFile verbose_file_{};
    size_t verbose_file_part_ = 0;
    size_t verbose_print_count_ = 0;

    pid_t my_pid_ = getpid();

    bool verbose_ = true;
};

size_t Logcat::PrintLogLine(const AndroidLogEntry &entry, FILE *out) {
    if (!out) return 0;
    constexpr static size_t kMaxTimeBuff = 64;
    struct tm tm{};
    std::array<char, kMaxTimeBuff> time_buff{};

    auto now = entry.tv_sec;
    auto nsec = entry.tv_nsec;
    auto message_len = entry.messageLen;
    const auto *message = entry.message;
    if (now < 0) {
        nsec = NS_PER_SEC - nsec;
    }
    if (message_len >= 1 && message[message_len - 1] == '\n') {
        --message_len;
    }
    localtime_r(&now, &tm);
    strftime(time_buff.data(), time_buff.size(), "%Y-%m-%dT%H:%M:%S", &tm);
    int len =
        fprintf(out, "[ %s.%03ld %8d:%6d:%6d %c/%-15.*s ] %.*s\n", time_buff.data(),
                nsec / MS_PER_NSEC, entry.uid, entry.pid, entry.tid, kLogChar[entry.priority],
                static_cast<int>(entry.tagLen), entry.tag, static_cast<int>(message_len), message);
    fflush(out);
    // trigger overflow when failed to generate a new fd
    if (len <= 0) len = kMaxLogSize;
    return static_cast<size_t>(len);
}

void Logcat::RefreshFd(bool is_verbose) {
    constexpr auto start = "----part %zu start----\n";
    constexpr auto end = "-----part %zu end----\n";
    if (is_verbose) {
        verbose_print_count_ = 0;
        fprintf(verbose_file_.get(), end, verbose_file_part_);
        fflush(verbose_file_.get());
        verbose_file_ = UniqueFile(env_->CallIntMethod(thiz_, refresh_fd_method_, JNI_TRUE), "a");
        verbose_file_part_++;
        fprintf(verbose_file_.get(), start, verbose_file_part_);
        fflush(verbose_file_.get());
    } else {
        modules_print_count_ = 0;
        fprintf(modules_file_.get(), end, modules_file_part_);
        fflush(modules_file_.get());
        modules_file_ = UniqueFile(env_->CallIntMethod(thiz_, refresh_fd_method_, JNI_FALSE), "a");
        modules_file_part_++;
        fprintf(modules_file_.get(), start, modules_file_part_);
        fflush(modules_file_.get());
    }
}

inline void Logcat::Log(std::string_view str) {
    if (verbose_) {
        fprintf(verbose_file_.get(), "%.*s", static_cast<int>(str.size()), str.data());
        fflush(verbose_file_.get());
    }
    fprintf(modules_file_.get(), "%.*s", static_cast<int>(str.size()), str.data());
    fflush(modules_file_.get());
}

void Logcat::OnCrash(int err) {
    using namespace std::string_literals;
    constexpr size_t max_restart_logd_wait = 1U << 10;
    static size_t kLogdCrashCount = 0;
    static size_t kLogdRestartWait = 1 << 3;
    if (++kLogdCrashCount >= kLogdRestartWait) {
        Log("\nLogd crashed too many times, trying manually start...\n");
        __system_property_set("ctl.restart", "logd");
        if (kLogdRestartWait < max_restart_logd_wait) {
            kLogdRestartWait <<= 1;
        } else {
            kLogdCrashCount = 0;
        }
    } else {
        Log("\nLogd maybe crashed (err="s + strerror(err) + "), retrying in 1s...\n");
    }

    std::this_thread::sleep_for(1s);
}

void Logcat::ProcessBuffer(struct log_msg *buf) {
    AndroidLogEntry entry;
    if (android_log_processLogBuffer(&buf->entry, &entry) < 0) return;

    entry.tagLen--;
    std::string_view tag(entry.tag, entry.tagLen);
    bool shortcut = false;

    if (tag == "VectorLegacyBridge"sv || tag == "XSharedPreferences"sv || tag == "VectorContext"sv)
        [[unlikely]] {
        modules_print_count_ += PrintLogLine(entry, modules_file_.get());
        shortcut = true;
    }

    constexpr std::array<std::string_view, 8> exact_tags = {
        "APatchD"sv, "Dobby"sv,  "KernelSU"sv, "LSPlant"sv,
        "LSPlt"sv,   "Magisk"sv, "SELinux"sv,  "TEESimulator"sv};
    constexpr std::array<std::string_view, 4> prefix_tags = {"dex2oat"sv, "Vector"sv, "LSPosed"sv,
                                                             "zygisk"sv};

    bool match_exact =
        std::any_of(exact_tags.begin(), exact_tags.end(), [&](auto t) { return tag == t; });
    bool match_prefix = std::any_of(prefix_tags.begin(), prefix_tags.end(),
                                    [&](auto t) { return tag.starts_with(t); });

    if (verbose_ && (shortcut || buf->id() == log_id::LOG_ID_CRASH || match_exact || match_prefix))
        [[unlikely]] {
        verbose_print_count_ += PrintLogLine(entry, verbose_file_.get());
    }

    if (entry.pid == my_pid_ && tag == "VectorLogcat"sv) [[unlikely]] {
        std::string_view msg(entry.message, entry.messageLen);
        if (msg == "!!start_verbose!!"sv) {
            verbose_ = true;
            verbose_print_count_ += PrintLogLine(entry, verbose_file_.get());
        } else if (msg == "!!stop_verbose!!"sv) {
            verbose_ = false;
        } else if (msg == "!!refresh_modules!!"sv) {
            RefreshFd(false);
        } else if (msg == "!!refresh_verbose!!"sv) {
            RefreshFd(true);
        }
    }
}

void Logcat::Run() {
    constexpr size_t tail_after_crash = 10U;
    size_t tail = 0;
    RefreshFd(true);
    RefreshFd(false);

    while (true) {
        std::unique_ptr<logger_list, decltype(&android_logger_list_free)> logger_list{
            android_logger_list_alloc(0, tail, 0), &android_logger_list_free};
        tail = tail_after_crash;

        for (log_id id : {LOG_ID_MAIN, LOG_ID_CRASH}) {
            auto *logger = android_logger_open(logger_list.get(), id);
            if (logger == nullptr) continue;
            if (auto size = android_logger_get_log_size(logger);
                size >= 0 && static_cast<size_t>(size) < kLogBufferSize) {
                android_logger_set_log_size(logger, kLogBufferSize);
            }
        }

        struct log_msg msg{};

        while (true) {
            if (android_logger_list_read(logger_list.get(), &msg) <= 0) [[unlikely]]
                break;

            ProcessBuffer(&msg);

            if (verbose_print_count_ >= kMaxLogSize) [[unlikely]]
                RefreshFd(true);
            if (modules_print_count_ >= kMaxLogSize) [[unlikely]]
                RefreshFd(false);
        }
        OnCrash(errno);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_matrix_vector_daemon_env_LogcatMonitor_runLogcat(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jmethodID method = env->GetMethodID(clazz, "refreshFd", "(Z)I");
    Logcat logcat(env, thiz, method);
    logcat.Run();
}
