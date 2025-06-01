#include <jni.h>
#include <unistd.h>

class Logcat {
public:
    explicit Logcat(JNIEnv*, jobject, jmethodID) {}
    void Run() {}
};

extern "C" JNIEXPORT void JNICALL
Java_com_example_LoggerService_startLogging(JNIEnv* env, jobject thiz) {
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_LoggerService_stopLogging(JNIEnv* env, jobject thiz) {
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_LoggerService_refreshFd(JNIEnv* env, jobject thiz, jboolean is_verbose) {
    return 0;
}
