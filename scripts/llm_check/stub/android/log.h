// Remplace <android/log.h> pour compiler llama_jni.cpp sur PC (scripts/llm_check).
#pragma once
#include <cstdarg>
#include <cstdio>

enum { ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };

inline int __android_log_print(int, const char *tag, const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    std::fprintf(stderr, "[%s] ", tag);
    std::vfprintf(stderr, fmt, args);
    std::fputc('\n', stderr);
    va_end(args);
    return 0;
}

inline int __android_log_write(int, const char *tag, const char *text) {
    std::fprintf(stderr, "[%s] %s", tag, text);
    return 0;
}
