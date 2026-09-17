#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>

#define TAG "PowerReaderJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MAX_LINE_LENGTH 128

/**
 * 底层文件描述符缓存结构体，用于保持内核节点长期打开，消除高频 I/O 开销。
 */
typedef struct {
    FILE *voltage_fp;
    FILE *current_fp;
    FILE *capacity_fp;
    FILE *status_fp;
    FILE *temp_fp;
    int initialized;
    int init_attempted;
} FileCache;

static FileCache g_cache = {NULL, NULL, NULL, NULL, NULL, 0, 0};

/**
 * 候选路径列表，覆盖主流高通、联发科及各 OEM 厂商的电源节点命名。
 */
static const char *VOLTAGE_CANDIDATES[] = {
    "/sys/class/power_supply/battery/voltage_now",
    "/sys/class/power_supply/bms/voltage_now",
    "/sys/class/power_supply/battery_gauge/voltage_now",
    "/sys/class/power_supply/qcom-battery/voltage_now",
    NULL
};

static const char *CURRENT_CANDIDATES[] = {
    "/sys/class/power_supply/battery/current_now",
    "/sys/class/power_supply/bms/current_now",
    "/sys/class/power_supply/battery_gauge/current_now",
    "/sys/class/power_supply/qcom-battery/current_now",
    NULL
};

static const char *CAPACITY_CANDIDATES[] = {
    "/sys/class/power_supply/battery/capacity",
    "/sys/class/power_supply/bms/capacity",
    NULL
};

static const char *STATUS_CANDIDATES[] = {
    "/sys/class/power_supply/battery/status",
    "/sys/class/power_supply/bms/status",
    NULL
};

static const char *TEMP_CANDIDATES[] = {
    "/sys/class/power_supply/battery/temp",
    "/sys/class/power_supply/bms/temp",
    "/sys/class/power_supply/battery_gauge/temp",
    "/sys/class/power_supply/qcom-battery/temp",
    NULL
};

/**
 * 从给定的候选路径列表中尝试以只读方式打开首个有效节点。
 *
 * @param candidates 候选文件路径字符串数组（以 NULL 结尾）
 * @return 成功打开的文件指针 FILE*，若均无法打开则返回 NULL
 */
static FILE* open_first_available(const char *candidates[]) {
    for (int i = 0; candidates[i] != NULL; ++i) {
        FILE *fp = fopen(candidates[i], "r");
        if (fp != NULL) {
            LOGI("open_first_available: 成功打开节点 %s", candidates[i]);
            return fp;
        }
    }
    return NULL;
}

/**
 * 从已打开的文件描述符中极速读取一行长整型数值（自动 rewind + fgets）。
 *
 * @param fp 目标文件指针
 * @return 解析得到的 long 整数，失败时返回 0
 */
static long read_long(FILE *fp) {
    if (!fp) return 0;
    char buffer[MAX_LINE_LENGTH];
    rewind(fp);
    fflush(fp);

    if (!fgets(buffer, MAX_LINE_LENGTH, fp)) {
        return 0;
    }

    return atol(buffer);
}

/**
 * 从已打开的文件描述符中极速读取一行整型数值。
 *
 * @param fp 目标文件指针
 * @return 解析得到的 int 整数，失败时返回 0
 */
static int read_int(FILE *fp) {
    if (!fp) return 0;
    char buffer[MAX_LINE_LENGTH];
    rewind(fp);
    fflush(fp);

    if (!fgets(buffer, MAX_LINE_LENGTH, fp)) {
        return 0;
    }

    return atoi(buffer);
}

/**
 * 初始化底层节点文件描述符缓存。
 * 具备一次性探测保护：若此前已执行过初始化尝试，则直接返回上次结果，杜绝在 SELinux 限制环境下每次读取重复 fopen。
 *
 * @return 初始化结果，1 表示成功打开关键节点，0 表示打开失败
 */
static int init_file_cache() {
    if (g_cache.init_attempted) {
        return g_cache.initialized;
    }
    g_cache.init_attempted = 1;

    g_cache.voltage_fp = open_first_available(VOLTAGE_CANDIDATES);
    g_cache.current_fp = open_first_available(CURRENT_CANDIDATES);
    g_cache.capacity_fp = open_first_available(CAPACITY_CANDIDATES);
    g_cache.status_fp = open_first_available(STATUS_CANDIDATES);
    g_cache.temp_fp = open_first_available(TEMP_CANDIDATES);

    // 只要电流或电压至少有一个节点打开成功，即视为具备硬件直读能力
    if (g_cache.current_fp != NULL || g_cache.voltage_fp != NULL) {
        g_cache.initialized = 1;
        LOGI("init_file_cache: 底层硬件节点缓存初始化成功");
        return 1;
    }

    LOGE("init_file_cache: 无法直接打开电流或电压节点，可能受 SELinux 限制（已置位避免重试）");
    return 0;
}

extern "C" {

/**
 * 初始化 Linux 底层硬件电源节点的文件缓存。
 *
 * @param env JNI 运行环境指针
 * @param clazz Java 静态方法调用类对象
 * @return 1 表示成功，0 表示失败
 */
JNIEXPORT jint JNICALL
Java_com_battery_analysis_util_SysfsBatterySampler_nativeInit(
    JNIEnv *env,
    jclass clazz
) {
    return init_file_cache();
}

/**
 * 从底层节点直接读取当前瞬时电压（微伏 uV 或毫伏 mV）。
 *
 * @param env JNI 运行环境指针
 * @param clazz Java 静态方法调用类对象
 * @return 瞬时电压原始读数
 */
JNIEXPORT jlong JNICALL
Java_com_battery_analysis_util_SysfsBatterySampler_nativeGetVoltage(
    JNIEnv *env,
    jclass clazz
) {
    if (!g_cache.initialized && !init_file_cache()) {
        return 0;
    }
    return read_long(g_cache.voltage_fp);
}

/**
 * 从底层节点直接读取当前瞬时放电电流（微安 uA 或毫安 mA）。
 *
 * @param env JNI 运行环境指针
 * @param clazz Java 静态方法调用类对象
 * @return 瞬时电流原始读数（放电通常为负或绝对值）
 */
JNIEXPORT jlong JNICALL
Java_com_battery_analysis_util_SysfsBatterySampler_nativeGetCurrent(
    JNIEnv *env,
    jclass clazz
) {
    if (!g_cache.initialized && !init_file_cache()) {
        return 0;
    }
    return read_long(g_cache.current_fp);
}

/**
 * 从底层节点直接读取当前电池百分比。
 *
 * @param env JNI 运行环境指针
 * @param clazz Java 静态方法调用类对象
 * @return 电池剩余百分比 (0-100)
 */
JNIEXPORT jint JNICALL
Java_com_battery_analysis_util_SysfsBatterySampler_nativeGetCapacity(
    JNIEnv *env,
    jclass clazz
) {
    if (!g_cache.initialized && !init_file_cache()) {
        return 0;
    }
    return read_int(g_cache.capacity_fp);
}

/**
 * 从底层节点直接读取电池状态首字符（例如 'D' 表示 Discharging，'C' 表示 Charging）。
 *
 * @param env JNI 运行环境指针
 * @param clazz Java 静态方法调用类对象
 * @return 状态首字母 ASCII 码，若不可用返回 0
 */
JNIEXPORT jint JNICALL
Java_com_battery_analysis_util_SysfsBatterySampler_nativeGetStatus(
    JNIEnv *env,
    jclass clazz
) {
    if (!g_cache.initialized && !init_file_cache()) {
        return 0;
    }
    if (!g_cache.status_fp) return 0;

    FILE *fp = g_cache.status_fp;
    rewind(fp);
    fflush(fp);

    int ch = fgetc(fp);
    return (ch != EOF) ? ch : 0;
}

/**
 * 从底层节点直接读取当前瞬时电池温度。
 *
 * @param env JNI 运行环境指针
 * @param clazz Java 静态方法调用类对象
 * @return 电池温度原始数值（通常为 0.1 摄氏度，如 370 表示 37.0℃）
 */
JNIEXPORT jint JNICALL
Java_com_battery_analysis_util_SysfsBatterySampler_nativeGetTemp(
    JNIEnv *env,
    jclass clazz
) {
    if (!g_cache.initialized && !init_file_cache()) {
        return 0;
    }
    return read_int(g_cache.temp_fp);
}

} // extern "C"
