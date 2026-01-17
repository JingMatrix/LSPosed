#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

// POSIX/Linux headers
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "logging.h"

#if defined(__LP64__)
#define LP_SELECT(lp32, lp64) lp64
#else
#define LP_SELECT(lp32, lp64) lp32
#endif

constexpr int id_vec(bool is64, bool is_debug) { return ((is64 << 1) | is_debug); }

// An anonymous namespace is used to limit the scope of helper functions and constants
// to this file, which is the C++ equivalent of the `static` keyword for free functions.
namespace {

// The abstract socket name for communication.
const char kSockName[] = "5291374ceda0aef7c5d86cd2a4f6a3ac\0";

ssize_t xrecvmsg(int sockfd, struct msghdr* msg, int flags) {
    ssize_t rec = recvmsg(sockfd, msg, flags);
    if (rec < 0) {
        PLOGE("recvmsg");
    }
    return rec;
}

void* recv_fds(int sockfd, char* cmsgbuf, size_t bufsz, int cnt) {
    struct iovec iov = {.iov_base = &cnt, .iov_len = sizeof(cnt)};
    struct msghdr msg = {
        .msg_iov = &iov, .msg_iovlen = 1, .msg_control = cmsgbuf, .msg_controllen = bufsz};

    xrecvmsg(sockfd, &msg, MSG_WAITALL);
    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);

    if (msg.msg_controllen != bufsz || cmsg == nullptr ||
        cmsg->cmsg_len != CMSG_LEN(sizeof(int) * cnt) || cmsg->cmsg_level != SOL_SOCKET ||
        cmsg->cmsg_type != SCM_RIGHTS) {
        return nullptr;
    }

    return CMSG_DATA(cmsg);
}

int recv_fd(int sockfd) {
    char cmsgbuf[CMSG_SPACE(sizeof(int))];

    void* data = recv_fds(sockfd, cmsgbuf, sizeof(cmsgbuf), 1);
    if (data == nullptr) return -1;

    int result;
    memcpy(&result, data, sizeof(int));
    return result;
}

int read_int(int fd) {
    int val;
    if (read(fd, &val, sizeof(val)) != sizeof(val)) return -1;
    return val;
}

void write_int(int fd, int val) {
    if (fd < 0) return;
    write(fd, &val, sizeof(val));
}

}  // namespace

int main(int argc, char** argv) {
    LOGD("dex2oat wrapper ppid=%d", getppid());

    struct sockaddr_un sock{};
    sock.sun_family = AF_UNIX;
    strlcpy(sock.sun_path + 1, kSockName, sizeof(sock.sun_path) - 1);

    int sock_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    size_t len = sizeof(sa_family_t) + strlen(sock.sun_path + 1) + 1;
    if (connect(sock_fd, reinterpret_cast<struct sockaddr*>(&sock), len)) {
        PLOGE("failed to connect to %s", sock.sun_path + 1);
        return 1;
    }
    write_int(sock_fd, id_vec(LP_SELECT(0, 1), strstr(argv[0], "dex2oatd") != nullptr));
    int stock_fd = recv_fd(sock_fd);
    read_int(sock_fd);
    close(sock_fd);

    sock_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (connect(sock_fd, reinterpret_cast<struct sockaddr*>(&sock), len)) {
        PLOGE("failed to connect to %s", sock.sun_path + 1);
        return 1;
    }
    write_int(sock_fd, LP_SELECT(4, 5));
    int hooker_fd = recv_fd(sock_fd);
    read_int(sock_fd);
    close(sock_fd);

    if (hooker_fd == -1) {
        PLOGE("failed to read liboat_hook.so");
    }
    LOGD("sock: %s %d", sock.sun_path + 1, stock_fd);

    // Use std::vector for safer and more flexible argument list management.
    std::vector<const char*> new_argv;
    for (int i = 0; i < argc; ++i) {
        new_argv.push_back(argv[i]);
    }
    new_argv.push_back("--inline-max-code-units=0");
    new_argv.push_back(nullptr);  // execve requires a null-terminated array.

    if (getenv("LD_LIBRARY_PATH") == nullptr) {
        const char* libenv = LP_SELECT(
            "LD_LIBRARY_PATH=/apex/com.android.art/lib:/apex/com.android.os.statsd/lib",
            "LD_LIBRARY_PATH=/apex/com.android.art/lib64:/apex/com.android.os.statsd/lib64");
        // The putenv function is a legacy C API that requires a char*.
        // A const_cast is necessary here.
        putenv(const_cast<char*>(libenv));
    }

    // Use std::string for safe and easy string construction.
    std::string ld_preload_env =
        "LD_PRELOAD=/proc/" + std::to_string(getpid()) + "/fd/" + std::to_string(hooker_fd);
    // putenv(const_cast<char*>(ld_preload_env.c_str()));
    LOGD("[dry_run] Set env %s", ld_preload_env.c_str());

    // fexecve requires a non-const char* array. We must use const_cast to match the
    // required signature, as is common when interfacing modern C++ with older C APIs.
    fexecve(stock_fd, const_cast<char* const*>(new_argv.data()), environ);

    // This part is only reached if fexecve fails.
    PLOGE("fexecve failed");
    return 2;
}
