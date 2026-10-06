// 改机型工具箱 —— Zygisk 原生模块
//
// 做两件 Xposed 做不到的事：
//   ① 在应用进程里、它的代码跑起来之前，用 JNI 直接改 android.os.Build 的静态字段；
//   ② 把本进程的属性区换成私有副本，再往里写值 —— 这样 native 的
//      __system_property_find/read 在这个进程里读到的也是伪装值。
//      属性区是 bionic 直接读的内存，不走函数调用，所以只能改内存，hook 不了。
//
// 进程内 Java 与 native 两条路一起改，才不会出现「同一个进程里两个来源对不上」。
//
// 配置不落在这个进程里：模块跑在目标应用进程（无 root），读不到 /data/adb，
// 所以用 connectCompanion() 拿一个通往 root 侧 companion 的 socket，从那里取。
//
// 本文件同时给「改机型工具箱」App 提供一个 JNI 自检入口（同一个 .so，
// App 里用 System.loadLibrary 加载后调 Native.selftest），
// 用来在真机上验证属性区私有副本这条路真的成立。
#include <sys/types.h>
#include "zygisk.hpp"
#include "prop_area.hpp"
#include <jni.h>
#include <android/log.h>
#include <sys/socket.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <linux/futex.h>
#include <sys/syscall.h>
#include <pthread.h>

// ───────────────────── C++ ABI 垫片（只为摆脱 libc++_shared.so）─────────────────────
// 用 -nostdlib++ 编译时，函数内静态对象（zygisk.hpp 里的 module_abi）需要
// __cxa_guard_acquire / __cxa_guard_release，而 bionic 的 libc **不导出**这两个
// （它们在 libc++abi 里）。不补的话 dlopen 直接失败：
//   cannot locate symbol "__cxa_guard_acquire"
// 为了几个静态初始化守卫去拖 1.4MB 的 libc++_shared.so 不值，这里按 Itanium ABI
// 用 futex 实现一份最小的（0=未初始化 / 1=正在初始化 / 2=已完成），并标成 hidden，
// 不污染进程里其它库的符号解析。
extern "C" __attribute__((visibility("hidden"))) int __cxa_guard_acquire(uint64_t *g) {
    uint32_t *w = reinterpret_cast<uint32_t *>(g);
    for (;;) {
        uint32_t v = __atomic_load_n(w, __ATOMIC_ACQUIRE);
        if (v == 2) return 0;
        if (v == 0) {
            uint32_t expect = 0;
            if (__atomic_compare_exchange_n(w, &expect, 1u, false,
                                            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE))
                return 1;
            continue;
        }
        syscall(SYS_futex, w, FUTEX_WAIT_PRIVATE, 1, nullptr, nullptr, 0);
    }
}

extern "C" __attribute__((visibility("hidden"))) void __cxa_guard_release(uint64_t *g) {
    uint32_t *w = reinterpret_cast<uint32_t *>(g);
    __atomic_store_n(w, 2u, __ATOMIC_RELEASE);
    syscall(SYS_futex, w, FUTEX_WAKE_PRIVATE, INT32_MAX, nullptr, nullptr, 0);
}

extern "C" __attribute__((visibility("hidden"))) void __cxa_guard_abort(uint64_t *g) {
    uint32_t *w = reinterpret_cast<uint32_t *>(g);
    __atomic_store_n(w, 0u, __ATOMIC_RELEASE);
    syscall(SYS_futex, w, FUTEX_WAKE_PRIVATE, INT32_MAX, nullptr, nullptr, 0);
}

#define LOG_TAG "DeviceToolbox"

// 默认**一个字都不打** logcat：debug 关着时连错误都不落盘（公开实现的做法）。
// 想排障就在 /data/adb/devicetoolbox/zygisk.conf 里加一行  #debug=1
// —— companion 会把它作为 __debug__ 假键送进来，这里据此决定要不要说话。
static bool g_debug = false;
#define LOGI(...) do { if (g_debug) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); } while (0)
#define LOGE(...) do { if (g_debug) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); } while (0)

// ─────────────────────────── 配置（来自 companion）───────────────────────────
// 上限是必须的：跨生态清理会往配置里加一批删除行（App 侧封顶 72 条），
// 逐应用表本身已占 ~173 条 —— 超了这里会**静默截断**，所以从 192 提到 256。
#define MAX_PROPS 256
#define MAX_KEY 96
#define MAX_VAL 192
// 删除**独立通道**的条数上限（与 App 侧 Props.MAX_DELETES=72 对齐，留余量）。
// 2026-10-06 修缺陷①：删除不再靠「值 == 哨兵」的约定混在值域里 —— 值域里出现哨兵
// 曾让模块的 Java 侧把 "__DELETE__" 当值写进 Build.FINGERPRINT（Momo 实测抓到）。
#define MAX_DELS 96

struct PropKV {
    char k[MAX_KEY];
    char v[MAX_VAL];
};

static PropKV g_kv[MAX_PROPS];
static int g_n = 0;
static const char *g_keys[MAX_PROPS];
static const char *g_vals[MAX_PROPS];

// 删除清单（独立通道）。值为哨兵的键**永远不进 g_kv**，所以 kv_get() 不可能返回哨兵。
static char g_del[MAX_DELS][MAX_KEY];
static int g_del_n = 0;
static const char *g_delkeys[MAX_DELS];

static const char *kv_get(const char *key) {
    for (int i = 0; i < g_n; i++) {
        if (strcmp(g_kv[i].k, key) == 0) return g_kv[i].v;
    }
    return nullptr;
}

// 包名：优先 app_data_dir 的末段（比 nice_name 可靠，多进程/隔离进程不会错），
// 回落到 nice_name 并去掉 ":进程名" 后缀。
static void extract_pkg(JNIEnv *env, zygisk::AppSpecializeArgs *args, char *out, size_t cap) {
    out[0] = 0;
    if (args->app_data_dir) {
        const char *d = env->GetStringUTFChars(args->app_data_dir, nullptr);
        if (d) {
            const char *slash = strrchr(d, '/');
            const char *base = slash ? slash + 1 : d;
            if (*base) { strncpy(out, base, cap - 1); out[cap - 1] = 0; }
            env->ReleaseStringUTFChars(args->app_data_dir, d);
        }
    }
    if (!out[0] && args->nice_name) {
        const char *n = env->GetStringUTFChars(args->nice_name, nullptr);
        if (n) {
            size_t len = strcspn(n, ":");
            if (len >= cap) len = cap - 1;
            memcpy(out, n, len);
            out[len] = 0;
            env->ReleaseStringUTFChars(args->nice_name, n);
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
}

static bool read_exact(int fd, void *buf, size_t n) {
    unsigned char *p = (unsigned char *)buf;
    size_t got = 0;
    while (got < n) {
        ssize_t r = read(fd, p + got, n - got);
        if (r <= 0) return false;
        got += (size_t)r;
    }
    return true;
}

static bool read_u32(int fd, uint32_t *out) { return read_exact(fd, out, sizeof(*out)); }

// 向 companion 要这个包名的属性表。协议：u32 长度 + 包名 → u32 条数 + (n × (u32 klen + key + u32 vlen + value))
static bool fetch_config(int fd, const char *pkg) {
    g_n = 0;
    uint32_t len = (uint32_t)strlen(pkg);
    if (write(fd, &len, sizeof(len)) != (ssize_t)sizeof(len)) return false;
    if (len && write(fd, pkg, len) != (ssize_t)len) return false;

    uint32_t count = 0;
    if (!read_u32(fd, &count)) return false;
    if (count == 0 || count > MAX_PROPS) return false;

    for (uint32_t i = 0; i < count; i++) {
        uint32_t kl = 0, vl = 0;
        if (!read_u32(fd, &kl) || kl == 0 || kl >= MAX_KEY) return false;
        if (!read_exact(fd, g_kv[g_n].k, kl)) return false;
        g_kv[g_n].k[kl] = 0;
        if (!read_u32(fd, &vl) || vl >= MAX_VAL) return false;
        if (vl && !read_exact(fd, g_kv[g_n].v, vl)) return false;
        g_kv[g_n].v[vl] = 0;
        if (strcmp(g_kv[g_n].k, "__debug__") == 0) {
            g_debug = (g_kv[g_n].v[0] == '1');
            continue;                              // 假键，不写进属性
        }
        g_n++;
    }
    for (int i = 0; i < g_n; i++) { g_keys[i] = g_kv[i].k; g_vals[i] = g_kv[i].v; }
    return g_n > 0;
}

// 向 companion 要这个包名的**删除清单**（独立通道）。协议与 fetch_config 同形，
// 只是请求包名多一个前缀字节 '\x01'（包名不可能以控制字符开头，无歧义）：
//   请求：u32 长度 + '\x01'+包名 → 回复：u32 条数 + (n × (u32 klen + key))
// 为什么要单独一趟请求、而不是塞在 fetch_config 的回复里：**请求/回复成对**才扛得住版本错配 ——
// 万一对面是旧 companion，它只会把 "\x01pkg" 当成一个匹配不上的包名、回 0 条，这里照样能跑。
// 拿不到（对面旧 / 出错）就当作没有删除项，绝不影响写入那一趟。
static bool fetch_dels(int fd, const char *pkg) {
    g_del_n = 0;
    char req[256];
    size_t plen = strlen(pkg);
    if (plen + 1 >= sizeof(req)) return false;
    req[0] = '\x01';
    memcpy(req + 1, pkg, plen);
    uint32_t len = (uint32_t)(plen + 1);
    if (write(fd, &len, sizeof(len)) != (ssize_t)sizeof(len)) return false;
    if (write(fd, req, len) != (ssize_t)len) return false;

    uint32_t count = 0;
    if (!read_u32(fd, &count)) return false;
    if (count > MAX_DELS) count = MAX_DELS;
    for (uint32_t i = 0; i < count; i++) {
        uint32_t kl = 0;
        if (!read_u32(fd, &kl) || kl == 0 || kl >= MAX_KEY) return false;
        if (!read_exact(fd, g_del[i], kl)) return false;
        g_del[i][kl] = 0;
        g_delkeys[i] = g_del[i];
    }
    g_del_n = (int)count;
    return true;
}

// ─────────────────────────── JNI：改 Build 静态字段 ───────────────────────────
static void set_static_str(JNIEnv *env, jclass c, const char *field, const char *v) {
    if (!v || !*v) return;
    // ★ 硬断言（2026-10-06 修缺陷①）：删除哨兵**绝不是值**。
    //   这里是那次事故的真正泄漏点：配置里 `ro.build.fingerprint` 的值是哨兵，
    //   而这个函数只挡空串 ⇒ `Build.FINGERPRINT` 被塞成字面量 "__DELETE__"，
    //   Momo 的 Fingerprint 行原样显示了出来。
    //   正常路径下哨兵根本不会进 g_kv（见 companion_serve 的删除通道），这里再兜一道：
    //   拒绝写入 + 记日志；该键的删除由 pa_spoof_props 的删除阶段负责，Java 侧保持真值。
    if (strcmp(v, PA_DELETE_SENTINEL) == 0) {
        LOGE("Build.%s: 值是删除哨兵 %s —— 拒绝写入（这是缺陷①的泄漏路径，已拦住）",
             field, PA_DELETE_SENTINEL);
        return;
    }
    jfieldID fid = env->GetStaticFieldID(c, field, "Ljava/lang/String;");
    if (!fid || env->ExceptionCheck()) { env->ExceptionClear(); return; }
    jstring js = env->NewStringUTF(v);
    if (js) {
        env->SetStaticObjectField(c, fid, js);
        env->DeleteLocalRef(js);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
}

static void apply_build_fields(JNIEnv *env) {
    jclass build = env->FindClass("android/os/Build");
    if (build) {
        struct { const char *field, *key; } map[] = {
            {"MANUFACTURER", "ro.product.manufacturer"}, {"BRAND", "ro.product.brand"},
            {"MODEL", "ro.product.model"},               {"DEVICE", "ro.product.device"},
            {"PRODUCT", "ro.product.name"},              {"BOARD", "ro.product.board"},
            {"HARDWARE", "ro.hardware"},                 {"FINGERPRINT", "ro.build.fingerprint"},
            {"ID", "ro.build.id"},                       {"DISPLAY", "ro.build.display.id"},
            {"TAGS", "ro.build.tags"},                   {"TYPE", "ro.build.type"},
            {"HOST", "ro.build.host"},                   {"USER", "ro.build.user"},
            // 下面四个是按公开实现普遍覆盖的那份清单补的。它们**只在我们真的写了对应属性时才生效**
            // （kv_get 返回空 → set_static_str 直接 return），所以补上是零风险的：
            // 一旦机型模板里带了 SoC/基带/bootloader 的真值，Java 侧就自动跟上，
            // 不会有「属性是假的、Build.SOC_MODEL 还是真机 SoC」这种一行就能对拍出来的矛盾。
            {"SOC_MODEL", "ro.soc.model"},               {"SOC_MANUFACTURER", "ro.soc.manufacturer"},
            {"BOOTLOADER", "ro.bootloader"},             {"RADIO", "gsm.version.baseband"},
        };
        for (auto &m : map) set_static_str(env, build, m.field, kv_get(m.key));

        // Build.TIME 是 long（毫秒），来源是 ro.build.date.utc（秒）。
        // 不同步它就会出现「SystemProperties.getLong(ro.build.date.utc)*1000 = 假值，
        // 而 Build.TIME 还是真值」的矛盾 —— 一行比较即可反证伪装（公开实现也单独处理它）。
        jfieldID tf = env->GetStaticFieldID(build, "TIME", "J");
        const char *du = kv_get("ro.build.date.utc");
        if (tf && du && *du) env->SetStaticLongField(build, tf, (jlong)(atoll(du) * 1000LL));
        if (env->ExceptionCheck()) env->ExceptionClear();

        env->DeleteLocalRef(build);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    jclass ver = env->FindClass("android/os/Build$VERSION");
    if (ver) {
        struct { const char *field, *key; } vmap[] = {
            {"RELEASE", "ro.build.version.release"},
            {"INCREMENTAL", "ro.build.version.incremental"},
            {"SECURITY_PATCH", "ro.build.version.security_patch"},
            // 下面三个是「同步了属性却忘了 Java」的典型缺口：不改的话
            // Build.VERSION.RELEASE=14（假）而 RELEASE_OR_CODENAME=16（真），
            // Build.VERSION.SDK_INT=34（假）而 SDK="36"（真）—— 一行 equals 就矛盾。
            // 注意：只有对应属性真的写进了配置，kv_get 才返回非空、字段才会被改，否则是空操作。
            {"RELEASE_OR_CODENAME", "ro.build.version.release_or_codename"},
            {"SDK", "ro.build.version.sdk"},
            {"CODENAME", "ro.build.version.codename"},
            {"BASE_OS", "ro.build.version.base_os"},
            {"PREVIEW_SDK_FINGERPRINT", "ro.build.version.preview_sdk_fingerprint"},
        };
        for (auto &m : vmap) set_static_str(env, ver, m.field, kv_get(m.key));
        jfieldID sid = env->GetStaticFieldID(ver, "SDK_INT", "I");
        const char *sdk = kv_get("ro.build.version.sdk");
        if (sid && sdk && *sdk && !env->ExceptionCheck()) env->SetStaticIntField(ver, sid, atoi(sdk));
        if (env->ExceptionCheck()) env->ExceptionClear();

        // 其余 int 型 VERSION 字段（按公开实现普遍覆盖的那份清单）。同样是「属性没写就是空操作」：
        // 尤其 DEVICE_INITIAL_SDK_INT（ro.product.first_api_level）—— 拿新机型冒充老机型时，
        // 这个字段最容易露（机型说 2019 年、first_api_level 却是 36）。
        struct { const char *field, *key; } vimap[] = {
            {"PREVIEW_SDK_INT", "ro.build.version.preview_sdk"},
            {"MIN_SUPPORTED_TARGET_SDK_INT", "ro.build.version.min_supported_target_sdk"},
            {"DEVICE_INITIAL_SDK_INT", "ro.product.first_api_level"},
        };
        for (auto &m : vimap) {
            const char *v = kv_get(m.key);
            if (!v || !*v) continue;
            jfieldID fid = env->GetStaticFieldID(ver, m.field, "I");
            if (fid && !env->ExceptionCheck()) env->SetStaticIntField(ver, fid, atoi(v));
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        env->DeleteLocalRef(ver);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// ─────────────────────────── Zygisk 模块 ───────────────────────────
class SpoofModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        if (!args || !args->nice_name) {
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        char pkg[256] = {0};
        extract_pkg(env, args, pkg, sizeof(pkg));
        jint uid = args->uid;                      // zygisk.hpp 里是引用，不是指针
        int user = uid > 0 ? (int)(uid / 100000) : 0;   // Android app uid = userId*100000 + appId

        int fd = api->connectCompanion();
        bool ok = false;
        if (fd >= 0) {
            // 先按「包名@用户」找，找不到再按纯包名找（公开实现同款回落）
            char with_user[288];
            snprintf(with_user, sizeof(with_user), "%s@%d", pkg, user);
            const char *hit = with_user;
            ok = fetch_config(fd, with_user);
            if (!ok) { hit = pkg; ok = fetch_config(fd, pkg); }
            // 删除清单走**独立请求**（同一条连接的另一趟往返）。拿不到就当没有删除项 ——
            // 旧 companion / 出错都不影响写入那一趟。
            if (ok && !fetch_dels(fd, hit)) g_del_n = 0;
            close(fd);
        }
        if (!ok) {
            LOGI("%s: 无配置，跳过", pkg);
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        // ① Java 侧：Build / Build.VERSION 的静态字段
        apply_build_fields(env);

        // ② native 侧：属性区私有副本 + 写值 + seal
        // 上限告警（**无条件**，不走 debug 开关）：配置条数顶到 MAX_PROPS 说明
        // companion 侧可能已经**静默截断**，那等于悄悄丢掉一批覆盖 ——
        // 这个项目最忌讳的就是「静默失效」，所以哪怕只出现一行也要说。
        if (g_n >= MAX_PROPS) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                                "%s: 配置 %d 条已达上限 %d —— 可能有条目被截断，"
                                "请减少该应用的键数或调大 MAX_PROPS",
                                pkg, g_n, MAX_PROPS);
        }
        PaStats st = pa_spoof_props(g_keys, g_vals, g_n, g_delkeys, g_del_n);
        LOGI("%s: %d 键 + %d 条删除 → 写入 %d 成功 / 缺失 %d / 长值 %d / 过长 %d / 未克隆 %d / 回读失败 %d；"
             "克隆 %d 块（重指 %d / fd %d / anon %d），布局 %d（1=硬编码 2=扫出 -1=认不出），"
             "A路被拒 %d 次（errno %d），seal %d；"
             "删除 %d 成功 / 本就不存在 %d / 失败 %d / 波及已写键 %d；"
             "**值域出现哨兵被拒 %d 条**（硬断言，正常应为 0）",
             pkg, g_n, g_del_n, st.written, st.write_missing, st.write_long, st.write_toolong,
             st.write_notcloned, st.write_noverify, st.areas_needed, st.cloned_repoint,
             st.cloned_fd, st.cloned_anon, st.ctx_layout,
             st.open_denied, st.last_errno, st.sealed,
             st.deleted, st.delete_missing, st.delete_failed, st.delete_broke,
             st.sentinel_refused);

        // ③ **没生效的键名逐条列出**（debug 才有）。
        // 加这个是因为这个项目两次被「只有聚合计数、不知道是哪几个键」卡住 ——
        // 只能写探针脚本二分，一次一分钟地折腾设备。现在一行就能看到。
        // 编码：0=缺失 -1=长值 -2=过长 -3=未克隆 -4=回读失败 -5=值是删除哨兵（拒绝写入、按删除处理）
        //       100=删除时本就不存在 99/97/-103/-104=删除失败(已回滚)
        if (st.notes_n > 0) {
            char line[1024];
            int off = 0;
            for (int i = 0; i < st.notes_n && off < (int)sizeof(line) - 80; i++) {
                off += snprintf(line + off, sizeof(line) - off, "%s%s(%d)",
                                i ? " " : "", st.notes[i].key, st.notes[i].code);
            }
            LOGI("%s: 未生效 %d 条（最多列 %d）: %s%s",
                 pkg, st.notes_n, PA_NOTE_MAX, line,
                 st.notes_n >= PA_NOTE_MAX ? " …（已达上限，可能还有更多）" : "");
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    zygisk::Api *api = nullptr;
    JNIEnv *env = nullptr;
};

REGISTER_ZYGISK_MODULE(SpoofModule)

// ─────────────────────────── companion（root 侧）───────────────────────────
// 读 /data/adb/devicetoolbox/zygisk.conf，按包名回属性表。
// 文件格式（App 写入）：每行  pkg<TAB>key<TAB>value
static void companion_entry(int fd);
REGISTER_ZYGISK_COMPANION(companion_entry)
static void companion_serve(int fd);
static void companion_entry(int fd) {
    // zygiskd 给**每个连接**开一个线程，而这个 companion 进程是**常驻**的
    // （真机实测：pid 5025 跨三次冷启动目标应用都不变）—— 而下面用的
    // line[4096] / kbuf / vbuf 全是 static，并发连接会互相踩。
    // 整段串行化：连接本身很短，代价可忽略。
    static pthread_mutex_t g_conf_mu = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&g_conf_mu);
    companion_serve(fd);
    pthread_mutex_unlock(&g_conf_mu);
}
static void companion_serve(int fd) {
    for (;;) {
        uint32_t len = 0;
        if (!read_u32(fd, &len)) break;
        if (len == 0 || len > 255) break;
        char pkg[256];
        if (!read_exact(fd, pkg, len)) break;
        pkg[len] = 0;

        // 请求分两种：普通请求 = 包名（回属性表）；删除请求 = '\x01'+包名（回删除清单）。
        // 见 fetch_dels 的说明：请求/回复成对，旧 companion 遇到删除请求只会回 0 条。
        bool del_only = (pkg[0] == '\x01');
        const char *want = del_only ? (pkg + 1) : pkg;

        // 每行两种形态（见 App 侧 core/Zygisk.java）：
        //   pkg<TAB>key<TAB>value      写入
        //   DEL<TAB>pkg<TAB>key        删除（独立通道 —— 值域里绝不会出现哨兵）
        static char line[4096];
        uint32_t count = 0, dcount = 0;
        int legacy = 0;                       // 老式「带内哨兵」行数（兼容兜底）
        static char kbuf[MAX_PROPS][MAX_KEY];
        static char vbuf[MAX_PROPS][MAX_VAL];
        static char dbuf[MAX_DELS][MAX_KEY];

        FILE *f = fopen("/data/adb/devicetoolbox/zygisk.conf", "re");
        if (f) {
            while (fgets(line, sizeof(line), f)) {
                // 开关：文件里任意一行 #debug=1 就打开日志（默认全程静默）
                if (strncmp(line, "#debug=1", 8) == 0 || strncmp(line, "# debug=1", 10) == 0) {
                    if (count < MAX_PROPS) {
                        memcpy(kbuf[count], "__debug__", 9);
                        kbuf[count][9] = 0;
                        memcpy(vbuf[count], "1", 2);
                        count++;
                    }
                    continue;
                }
                if (strncmp(line, "DEL\t", 4) == 0) {
                    char *d1 = line + 4;
                    char *d2 = strchr(d1, '\t');
                    if (!d2) continue;
                    *d2 = 0;
                    if (strcmp(d1, want) != 0) continue;
                    const char *k = d2 + 1;
                    size_t kl = strcspn(k, "\r\n");
                    if (kl == 0 || kl >= MAX_KEY || dcount >= MAX_DELS) continue;
                    memcpy(dbuf[dcount], k, kl);
                    dbuf[dcount][kl] = 0;
                    dcount++;
                    continue;
                }
                char *t1 = strchr(line, '\t');
                if (!t1) continue;
                *t1 = 0;
                char *t2 = strchr(t1 + 1, '\t');
                if (!t2) continue;
                *t2 = 0;
                if (strcmp(line, want) != 0) continue;
                const char *k = t1 + 1;
                char *v = t2 + 1;
                size_t vl = strcspn(v, "\r\n");
                size_t kl = strlen(k);
                if (kl == 0 || kl >= MAX_KEY || vl >= MAX_VAL) continue;
                // ★ 兼容老配置：值是哨兵 ⇒ 当**删除**处理，绝不当值送出去。
                //   这一条就是缺陷①的兜底 —— 老 App 写的带内哨兵不会再进 Java Build 字段。
                //   注意 v 还带着行尾换行，必须按 vl 比（踩过：strcmp 直接比会漏掉全部带内哨兵）。
                if (vl == strlen(PA_DELETE_SENTINEL) && memcmp(v, PA_DELETE_SENTINEL, vl) == 0) {
                    legacy++;
                    if (dcount < MAX_DELS) {
                        memcpy(dbuf[dcount], k, kl);
                        dbuf[dcount][kl] = 0;
                        dcount++;
                    }
                    continue;
                }
                if (count >= MAX_PROPS) continue;
                memcpy(kbuf[count], k, kl);
                kbuf[count][kl] = 0;
                memcpy(vbuf[count], v, vl);
                vbuf[count][vl] = 0;
                count++;
            }
            fclose(f);
        }
        if (legacy > 0) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                                "%s: 配置里有 %d 行老式带内哨兵（值=%s）—— 已按删除处理，"
                                "没有当值写入（请更新 App 以使用 DEL 行）", want, legacy, PA_DELETE_SENTINEL);
        }

        if (del_only) {                       // 删除清单回复
            if (write(fd, &dcount, sizeof(dcount)) != (ssize_t)sizeof(dcount)) break;
            bool bad = false;
            for (uint32_t i = 0; i < dcount && !bad; i++) {
                uint32_t kl = (uint32_t)strlen(dbuf[i]);
                if (write(fd, &kl, sizeof(kl)) != (ssize_t)sizeof(kl)) { bad = true; break; }
                if (kl && write(fd, dbuf[i], kl) != (ssize_t)kl) bad = true;
            }
            if (bad) break;
            continue;
        }

        if (write(fd, &count, sizeof(count)) != (ssize_t)sizeof(count)) break;
        for (uint32_t i = 0; i < count; i++) {
            uint32_t kl = (uint32_t)strlen(kbuf[i]);
            uint32_t vl = (uint32_t)strlen(vbuf[i]);
            if (write(fd, &kl, sizeof(kl)) != (ssize_t)sizeof(kl)) return;
            if (kl && write(fd, kbuf[i], kl) != (ssize_t)kl) return;
            if (write(fd, &vl, sizeof(vl)) != (ssize_t)sizeof(vl)) return;
            if (vl && write(fd, vbuf[i], vl) != (ssize_t)vl) return;
        }
    }
}

// ─────────────────────────── App 侧自检入口 ───────────────────────────
// com.xuzhang.devicetoolbox.core.Native.selftest(String key, String value)
// 在调用方进程里：克隆属性区 → 写入 → 读回 → 与全局 getprop 对照 → 复原 → seal。
// 只改本进程内存，跑完复原，对设备没有持久影响。
static const char *SELFTEST_KEYS[] = {
    "ro.product.model", "ro.product.brand", "ro.product.manufacturer",
    "ro.product.name", "ro.product.device", "ro.product.board",
    "ro.build.fingerprint", "ro.build.description", "ro.build.id",
    "ro.build.display.id", "ro.build.flavor", "ro.build.version.release",
};
#define SELFTEST_N ((int)(sizeof(SELFTEST_KEYS) / sizeof(SELFTEST_KEYS[0])))

// 读一个 Java 静态 String 字段（用于对照「Build 还是旧值」）。
static void java_static_str(JNIEnv *env, const char *cls, const char *field, char *out, size_t cap) {
    out[0] = 0;
    jclass c = env->FindClass(cls);
    if (!c) { env->ExceptionClear(); return; }
    jfieldID fid = env->GetStaticFieldID(c, field, "Ljava/lang/String;");
    if (fid && !env->ExceptionCheck()) {
        jstring js = (jstring)env->GetStaticObjectField(c, fid);
        if (js && !env->ExceptionCheck()) {
            const char *p = env->GetStringUTFChars(js, nullptr);
            if (p) { strncpy(out, p, cap - 1); out[cap - 1] = 0; env->ReleaseStringUTFChars(js, p); }
            env->DeleteLocalRef(js);
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(c);
}

static void cat(char *dst, size_t cap, const char *s) {
    size_t used = strlen(dst);
    if (used + 1 >= cap) return;
    strncat(dst, s, cap - used - 1);
}

static void catf(char *dst, size_t cap, const char *fmt, ...) {
    char tmp[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);
    cat(dst, cap, tmp);
}

// 跑一次真实的 getprop（新地址空间），看全局属性有没有被影响。
static void global_getprop(const char *key, char *out, size_t cap) {
    out[0] = 0;
    char cmd[256];
    snprintf(cmd, sizeof(cmd), "getprop %s 2>/dev/null", key);
    FILE *p = popen(cmd, "r");
    if (!p) return;
    size_t n = fread(out, 1, cap - 1, p);
    pclose(p);
    out[n] = 0;
    while (n > 0 && (out[n - 1] == '\n' || out[n - 1] == '\r')) out[--n] = 0;
}

// com.xuzhang.devicetoolbox.core.Native.readProp(String key)
// 只读：返回「本进程」native 看到的这个属性值（走 bionic，与进程内其它代码同源）。
// 模块生效时，这里读到的应该是伪装后的值；而 root 的 getprop 仍是真机值。
extern "C" JNIEXPORT jstring JNICALL
Java_com_xuzhang_devicetoolbox_core_Native_readProp(JNIEnv *env, jclass, jstring jkey) {
    if (!jkey) return env->NewStringUTF("");
    const char *p = env->GetStringUTFChars(jkey, nullptr);
    char value[512] = {0};
    if (p) {
        PaReadCtx c;
        if (pa_read_full(p, &c)) {
            strncpy(value, c.val, sizeof(value) - 1);
        }
        env->ReleaseStringUTFChars(jkey, p);
    }
    return env->NewStringUTF(value);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xuzhang_devicetoolbox_core_Native_selftest(JNIEnv *env, jclass, jstring jkey, jstring jval) {
    const char *key = "ro.product.model";
    const char *fake = "TOOLBOX-SELFTEST-01";
    char keybuf[128] = {0}, fakebuf[128] = {0};
    if (jkey) {
        const char *p = env->GetStringUTFChars(jkey, nullptr);
        if (p) { strncpy(keybuf, p, sizeof(keybuf) - 1); env->ReleaseStringUTFChars(jkey, p); }
        if (keybuf[0]) key = keybuf;
    }
    if (jval) {
        const char *p = env->GetStringUTFChars(jval, nullptr);
        if (p) { strncpy(fakebuf, p, sizeof(fakebuf) - 1); env->ReleaseStringUTFChars(jval, p); }
        if (fakebuf[0]) fake = fakebuf;
    }

    static char rep[8192];
    rep[0] = 0;
    cat(rep, sizeof(rep), "═══ 原生伪装自检（本进程）═══\n");

    char before[256] = {0};
    bool had = pa_read(key, before, sizeof(before));
    catf(rep, sizeof(rep), "目标属性 %s\n 克隆前本进程读到: %s\n\n", key, had ? before : "(本机没有)");
    if (!had) return env->NewStringUTF(rep);

    char global_before[256];
    global_getprop(key, global_before, sizeof(global_before));

    PaStats st = pa_clone_for_keys(SELFTEST_KEYS, SELFTEST_N, 0);
    catf(rep, sizeof(rep), "① 属性区映射 %d 块；问到 %d 个键：存在 %d / 缺失 %d\n",
         st.maps_total, st.keys_asked, st.keys_found, st.keys_missing);
    catf(rep, sizeof(rep), "② 克隆 %d 块：重指(原映射不动·不留痕) %d · fd COW %d · 匿名覆盖 %d · 失败 %d\n",
         st.areas_needed, st.cloned_repoint, st.cloned_fd, st.cloned_anon, st.clone_failed);
    if (st.cloned_repoint > 0)
        catf(rep, sizeof(rep), "   改写了 ContextNode 里的 %d 个 pa_ 指针；属性区在 /proc/self/maps 里仍是 r--s，没有 rw/r--p 异常\n",
             st.repoint_slots);
    if (st.open_denied > 0)
        catf(rep, sizeof(rep), "   open /dev/__properties__ 被拒 %d 次（errno=%d）→ 自动走匿名副本\n",
             st.open_denied, st.last_errno);
    if (st.already_writable > 0)
        catf(rep, sizeof(rep), "   %d 个键所在属性区本进程本来就可写，无需克隆\n", st.already_writable);

    int r = pa_write(key, fake);
    char now[256] = {0};
    pa_read(key, now, sizeof(now));
    char legacy[256] = {0};
    __system_property_get(key, legacy);
    char direct[256] = {0};
    const prop_info *pi = __system_property_find(key);
    if (pi) { strncpy(direct, pi->value, sizeof(direct) - 1); direct[sizeof(direct) - 1] = 0; }
    char jmodel[256] = {0};
    java_static_str(env, "android/os/Build", "MODEL", jmodel, sizeof(jmodel));
    catf(rep, sizeof(rep), "\n③ 写入测试值 %s → 返回 %d\n", fake, r);
    catf(rep, sizeof(rep), "   __system_property_get  : %s\n", legacy);
    catf(rep, sizeof(rep), "   __system_property_find : %s\n", direct);
    catf(rep, sizeof(rep), "   read_callback          : %s\n", now);
    catf(rep, sizeof(rep), "   三条 native 路径:%s\n",
         (r == 1 && strcmp(now, fake) == 0 && strcmp(legacy, fake) == 0 && strcmp(direct, fake) == 0)
             ? " 一致 ✓" : " 不一致 ✗（协议没写对）");
    catf(rep, sizeof(rep), "   Java Build.MODEL       : %s  ← 静态字段，属性改了它也不会变\n", jmodel);

    int wrote = 0;
    for (int i = 0; i < SELFTEST_N; i++) if (pa_write(SELFTEST_KEYS[i], "SELFTEST-VALUE") == 1) wrote++;
    catf(rep, sizeof(rep), "   同一批 %d 个键写入成功 %d 个\n", SELFTEST_N, wrote);

    char global_now[256];
    global_getprop(key, global_now, sizeof(global_now));
    catf(rep, sizeof(rep), "\n④ 跨进程隔离（新起一个 getprop 进程读同一属性）\n   全局值: %s  %s\n",
         global_now, strcmp(global_now, global_before) == 0 ? "✓ 别的进程完全不受影响" : "✗ 泄漏到全局了");

    int rw = pa_write(key, before);
    int sealed = pa_seal();
    char back[256] = {0};
    pa_read(key, back, sizeof(back));
    catf(rep, sizeof(rep), "\n⑤ 复原并 seal\n   复原写入返回 %d · seal %d 块 · 读回: %s  %s\n",
         rw, sealed, back, strcmp(back, before) == 0 ? "✓ 已复原" : "✗ 复原失败");

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "selftest 报告 %zu 字节", strlen(rep));
    cat(rep, sizeof(rep),
        "\n说明：这一路只改「本进程」看到的属性。应用真正被伪装时，"
        "模块还会在进程启动早期把 android.os.Build 的静态字段一起改掉，"
        "Java 与 native 两条路同源，才不会自相矛盾。\n");
    return env->NewStringUTF(rep);
}
