// 属性区私有副本 —— 只改「本进程」看到的系统属性
//
// 原理：bionic 的 __system_property_find / __system_property_read_callback 读的是
// /dev/__properties__/* 映射进来的那块内存，不走函数调用，所以 hook 不了 —— 只能改内存。
// 把「装着目标属性的那块映射」在本进程内换成一份私有副本（写时复制），往里写值：
//   · 本进程后续读到的就是新值（Java 的 SystemProperties.get 与 native 的
//     __system_property_find 走同一块内存，因此两边必然一致）；
//   · 别的进程、以及 root 的 getprop 读的是原来的共享页，完全不受影响。
//
// 只克隆「用得到的那几块」：bionic 会把 /dev/__properties__/ 下上百个上下文映射进来
// （本机 app 进程 157 块），全克隆既费内存又多出上百个可疑 VMA。做法是先用
// __system_property_find 问到 prop_info 的地址，再回查 maps 找出它落在哪一块。
//
// 两条克隆路径：
//   A. open(path) + mmap(MAP_PRIVATE|MAP_FIXED, fd, offset) —— 首选，语义最干净；
//      应用进程的 SELinux 域常常不允许 open /dev/__properties__/*，此时退化到 B。
//   B. 先把内容抄到临时缓冲，再 mmap(ANON|MAP_PRIVATE|MAP_FIXED) 覆盖同一地址，写回内容。
//      地址不变，bionic 里已有的指针依然有效。
// 无论哪条路，写完都要 seal（mprotect PROT_READ）—— 属性区本该是只读的，
// 留着 rw 会在 /proc/self/maps 里显形。
#pragma once

#include <sys/types.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <sys/prctl.h>

// bionic 的 prop_info：serial(4) + value[92]。长值属性是另一套布局，本模块不处理。
struct prop_info {
    uint32_t serial;
    char value[92];
};

extern "C" const prop_info *__system_property_find(const char *name);

// bionic 官方读接口（API 26+）：长值也能读到，故用它判断「原值是不是长值」。
// 注意：回调第 4 个参数是 serial，不是长度 —— 长度得自己 strlen（踩过）。
typedef void (*pa_read_cb_t)(void *, const char *, const char *, uint32_t);
extern "C" void __system_property_read_callback(const prop_info *pi, pa_read_cb_t cb, void *cookie);
// bionic 的老接口：走 serial 里的长度字段，是对「协议写对没有」最敏感的读取路径
extern "C" int __system_property_get(const char *name, char *value);

#define PA_MAX_MAPS 512
#define PA_MAX_CLONES 64
#define PA_MAX_KEYS 256
#define PA_PATH_MAX 192
#define PA_VALUE_MAX 92
// bionic prop_info::kLongFlag = 1 << 16；serial 的最高字节存「值长度」（SERIAL_VALUE_LEN = serial >> 24）。
// 这两个都不能猜：写错长度字节，legacy 的 __system_property_get 会读到空串，
// 而 read_callback 那条路照样读得到新值 —— 于是同一个进程里两个 API 对不上（踩过）。
#define PA_LONG_FLAG (1u << 16)
#define PA_SERIAL_DIRTY(s) ((s) & 1u)
#define PA_SERIAL_LEN(s) ((s) >> 24)
#define PA_SERIAL_MAKE(len, s) ((((uint32_t)(len)) << 24) | (((s) + 1u) & 0x00ffffffu))

struct PaMapInfo {
    void *start;
    size_t len;
    unsigned long off;
    char path[PA_PATH_MAX];
    int cloned;
    int writable;               // 本进程本来就能写（root/ksu 域常见），不用克隆
    unsigned char *copy;        // 「不留痕」路子里的匿名副本（原映射保持不动）
    int via_repoint;            // 1 = 走的是重指 ContextNode 这条路
    int repoint_slots;          // 这块映射改写了几个 pa_ 槽位
};

// ── 「没生效的键」名单 ──
#define PA_NOTE_MAX 24
struct PaNote {
    char key[64];
    int code;
};

struct PaStats {
    int maps_total;             // 属性区映射总数
    int keys_asked;
    int keys_found;             // 本机确实存在的键
    int keys_missing;           // 本机没有的键（native 写不进去，Java 侧仍会改）
    int areas_needed;           // 实际克隆的块数
    int cloned_fd;              // fd COW 路成功
    int cloned_anon;            // 匿名覆盖路成功
    int cloned_repoint;         // 「不留痕」重指路成功（原映射没动）
    int repoint_slots;          // 一共改写了几个 ContextNode::pa_ 槽位
    int clone_failed;
    int open_denied;            // open 被拒（多半是 SELinux）
    int last_errno;
    int sealed;
    int already_writable;       // 所在块本来就可写，无需克隆
    int written;                // 写成功的键数
    int write_missing;          // 键不存在
    int write_long;             // 原值是长值属性，跳过
    int write_toolong;          // 新值超过 91 字节
    int write_notcloned;        // 所在块既没克隆也不可写（不许写，写了会漏到共享页）
    int write_noverify;         // 写完回读对不上 —— 真·静默失效，必须能看见
    int sentinel_refused;       // 值等于删除哨兵 ⇒ 拒绝当值写入（硬断言，见 pa_write_impl）
    int deleted;                // __DELETE__：真删掉的键数
    int delete_missing;         // __DELETE__：该键本来就不存在（没什么可删）
    int delete_failed;          // __DELETE__：认不出布局 / 删完回读没过（已回滚）
    int delete_broke;           // 删除后，原本写成功的键读不到了 —— trie 被弄坏（必须为 0）
    // ── 「没生效的键」名单 ──
    // 加它的原因很实在：这个项目里**两次**都被同一个问题卡住 —— 日志只有聚合计数
    // （「写入 122 / 缺失 51」），要找到底是**哪几个键**没生效，只能写探针脚本二分，
    // 一次一分钟地折腾设备。把没生效的键名记下来，debug 时一眼就能看到。
    // 只记**非成功**的（上限 24 条），成功的不记，避免刷屏。
    // 编码：0=缺失 -1=长值 -2=过长 -3=未克隆 -4=回读失败 -5=值是删除哨兵（拒绝写入、按删除处理）
    //       100=删除时本就不存在 99/97/-103/-104=删除失败(已回滚)
    int notes_n;
    PaNote notes[PA_NOTE_MAX];
    int ctx_layout;             // ContextNode 布局：1=硬编码成立 2=扫出来的 -1=认不出（会退化到 r--p）
};

struct PaReadCtx {
    char val[512];
    uint32_t len;
    uint32_t serial;
};

static PaMapInfo g_pa_maps[PA_MAX_MAPS];
static int g_pa_maps_n = 0;
static PaMapInfo *g_pa_clones[PA_MAX_CLONES];
static int g_pa_clones_n = 0;

static void pa_read_cb(void *cookie, const char *, const char *value, uint32_t serial) {
    PaReadCtx *c = (PaReadCtx *)cookie;
    c->serial = serial;
    size_t len = value ? strlen(value) : 0;
    c->len = (uint32_t)len;
    size_t n = len < sizeof(c->val) - 1 ? len : sizeof(c->val) - 1;
    if (value) memcpy(c->val, value, n);
    c->val[n] = 0;
}

// 用官方回调读一个键（含长值）。false = 本机没有这个键。
static bool pa_read_full(const char *name, PaReadCtx *ctx) {
    ctx->val[0] = 0;
    ctx->len = 0;
    ctx->serial = 0;
    const prop_info *pi = __system_property_find(name);
    if (!pi) return false;
    __system_property_read_callback(pi, pa_read_cb, ctx);
    return true;
}

// 读值（走 bionic，与本进程其它代码看到的完全同源）。
static bool pa_read(const char *name, char *out, size_t cap) {
    if (!out || cap == 0) return false;
    PaReadCtx c;
    if (!pa_read_full(name, &c)) { out[0] = 0; return false; }
    size_t n = strlen(c.val);
    if (n >= cap) n = cap - 1;
    memcpy(out, c.val, n);
    out[n] = 0;
    return true;
}

// 读完 maps 之后把缓冲擦掉。maps 文本里含我们自己的 .so 路径（Zygisk 从
// /data/adb/modules/... 把模块映射进来），留在可写内存里会被「扫自身内存找模块字样」
// 的检测命中 —— 这正是常见实现的一个已知缺口（读 maps 用堆 String，从不擦）。
static void pa_wipe(void *p, size_t n) {
    volatile unsigned char *v = (volatile unsigned char *)p;
    while (n--) *v++ = 0;
}

// 扫描 /proc/self/maps 里的属性区映射。append=1 时保留已有条目，只追加新出现的
// （bionic 会按需惰性映射新的属性上下文，扫过一次的名单会过期）。
static int pa_scan_maps(int append) {
    if (!append) g_pa_maps_n = 0;
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) return g_pa_maps_n;
    char line[1024];
    while (fgets(line, sizeof(line), f)) {
        if (!strstr(line, "/dev/__properties__/")) continue;
        unsigned long s = 0, e = 0, off = 0;
        char perms[8] = {0};
        if (sscanf(line, "%lx-%lx %7s %lx", &s, &e, perms, &off) != 4) continue;
        if (!strchr(perms, 'r')) continue;
        const char *path = strstr(line, "/dev/__properties__/");
        size_t plen = strcspn(path, "\n");
        if (plen >= PA_PATH_MAX) continue;

        int seen = 0;
        for (int i = 0; i < g_pa_maps_n; i++) {
            if ((unsigned long)g_pa_maps[i].start == s) { seen = 1; break; }
        }
        if (seen) continue;
        if (g_pa_maps_n >= PA_MAX_MAPS) break;

        PaMapInfo *m = &g_pa_maps[g_pa_maps_n];
        m->start = (void *)s;
        m->len = (size_t)(e - s);
        m->off = off;
        memcpy(m->path, path, plen);
        m->path[plen] = 0;
        m->cloned = 0;
        m->writable = strchr(perms, 'w') ? 1 : 0;
        g_pa_maps_n++;
    }
    pa_wipe(line, sizeof(line));
    fclose(f);
    return g_pa_maps_n;
}

static int pa_map_index_of(const void *p) {
    uintptr_t a = (uintptr_t)p;
    for (int i = 0; i < g_pa_maps_n; i++) {
        uintptr_t s = (uintptr_t)g_pa_maps[i].start;
        if (a >= s && a < s + g_pa_maps[i].len) return i;
    }
    return -1;
}

// ─────────────────────── 「不留痕」克隆：改指针，不动原映射 ───────────────────────
// 上面 fd-COW 那条路会把属性区在 /proc/self/maps 里从 `r--s` 变成 `r--p`（映射权限变了），
// 这是公开实现专门避开的显形点。更隐蔽的做法是：
//   ① 在别处 mmap 一块匿名内存当副本，把属性区整块抄过去；
//   ② 把 bionic 内部 ContextNode 数组里指向原区的 pa_ 指针改成指向副本；
//   ③ 原映射原封不动 —— maps 里 157 块属性区全是正常的 `r--s`，只多一块普通匿名内存。
// ContextNode 布局：stride 0x28，pa_ 在 +0x10（本机 Android 16 实测）。
// 数组本身在 maps 里叫 `[anon:System property context nodes]`，不用解机器码就能定位。
#define PA_CTX_STRIDE 0x28
#define PA_CTX_PA_OFF 0x10
#define PA_MAX_CTX 8

struct PaCtxRegion {
    unsigned char *base;
    size_t len;
};

static PaCtxRegion g_ctx[PA_MAX_CTX];
static int g_ctx_n = 0;

// ── ContextNode 数组布局：运行期校验 + 兜底扫描 ──
// 硬编码的 stride=0x28 / pa_ 在 +0x10 是本机（Android 16 的 bionic）实测值，但换 ROM /
// 换内核 / 换 libc 版本就可能变。**变了的后果很隐蔽**：pa_repoint 一个槽位都改不到 →
// 重指路判 0 → 回滚 → 退到 fd-COW 兜底路 → 属性区在 maps 里从 `r--s` 变成 `r--p`（显形）。
// 所以动手前先**验证**，不过才扫候选。
static int g_ctx_stride = PA_CTX_STRIDE;
static int g_ctx_pa_off = PA_CTX_PA_OFF;
static int g_ctx_probed = 0;      // 0 未探测 / 1 硬编码值成立 / 2 扫出来的 / -1 都不成立

// 给一个候选布局打分。判据与公开实现的判据一致：
//   ① 每个非零 pa_ 必须**等于**某个 /dev/__properties__ 映射的起点（bionic 就是这么填的）
//   ② 非零槽位 ≥ 3
//   ③ 至少来自 2 个不同属性区
// 三条全过才返回命中数，否则 -1。这比「猜偏移」稳得多 —— 假阳性能被 ①②③ 一起挡掉。
// 对**任意一段内存**按给定布局累加统计（不做阈值判断，由调用方汇总后判断）。
// 为什么必须「先累加、后判断」：正常进程里有 **两个** ContextNode 数组（活跃 + 非活跃），
// 非活跃那个只有 2 个非零槽位。若按单区判断「非零 ≥ 3」，它必然不达标 ——
// 于是正确的布局会被判失败（实测踩到）。必须跨区汇总再判断。
static void pa_region_accum(unsigned char *base, size_t len, int stride, int pa_off,
                            int *nonzero, int *real, uintptr_t *seen, int *nseen) {
    if (!base || stride <= (int)sizeof(void *) || pa_off < 0
        || pa_off + (int)sizeof(void *) > stride) return;
    unsigned char *p = base;
    unsigned char *end = base + len;
    for (; p + stride <= end; p += stride) {
        uintptr_t v = 0;
        memcpy(&v, p + pa_off, sizeof(v));
        if (!v) continue;
        (*nonzero)++;
        for (int j = 0; j < g_pa_maps_n; j++) {
            if ((uintptr_t)g_pa_maps[j].start != v) continue;
            (*real)++;
            bool dup = false;
            for (int k = 0; k < *nseen; k++) if (seen[k] == v) { dup = true; break; }
            if (!dup && *nseen < PA_MAX_MAPS) seen[(*nseen)++] = v;
            break;
        }
    }
}

static int pa_ctx_layout_score(int stride, int pa_off) {
    if (g_ctx_n == 0 || g_pa_maps_n == 0) return -1;
    int nonzero = 0, real = 0, nseen = 0;
    uintptr_t seen[PA_MAX_MAPS];
    for (int i = 0; i < g_ctx_n; i++)
        pa_region_accum(g_ctx[i].base, g_ctx[i].len, stride, pa_off, &nonzero, &real, seen, &nseen);
    if (nonzero < 3) return -1;
    if (real != nonzero) return -1;    // 有任何一条 pa_ 不是属性区起点 → 这个布局不对
    if (nseen < 2) return -1;          // 必须来自 ≥2 个不同属性区
    return real;
}

static void pa_ctx_resolve_layout(void) {
    if (g_ctx_n == 0) return;
    // ★ 只认一次。原因：重指之后 pa_ 已指向我们的副本、不再指向真实属性区，
    // 而校验判据正是「pa_ 必须等于某个属性区起点」—— 再跑一次会把**正确的布局判为失败**，
    // 甚至从候选里挑出一个错误的。第一次调用发生在任何重指之前，那次结果才是有效的。
    if (g_ctx_probed != 0) return;
    if (pa_ctx_layout_score(g_ctx_stride, g_ctx_pa_off) > 0) { g_ctx_probed = 1; return; }
    static const int strides[] = {0x20, 0x28, 0x30, 0x38, 0x40, 0x48, 0x50, 0x18, 0x60, 0x68};
    static const int offs[] = {0x08, 0x10, 0x18, 0x20, 0x00};
    int best = -1, bs = g_ctx_stride, bo = g_ctx_pa_off;
    for (int si = 0; si < (int)(sizeof(strides) / sizeof(strides[0])); si++) {
        for (int oi = 0; oi < (int)(sizeof(offs) / sizeof(offs[0])); oi++) {
            int sc = pa_ctx_layout_score(strides[si], offs[oi]);
            if (sc > best) { best = sc; bs = strides[si]; bo = offs[oi]; }
        }
    }
    if (best > 0) { g_ctx_stride = bs; g_ctx_pa_off = bo; g_ctx_probed = 2; }
    else g_ctx_probed = -1;
}

static void pa_scan_ctx_nodes(void) {
    g_ctx_n = 0;
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) return;
    char line[1024];
    while (fgets(line, sizeof(line), f) && g_ctx_n < PA_MAX_CTX) {
        if (!strstr(line, "[anon:System property context nodes]")) continue;
        unsigned long s = 0, e = 0;
        if (sscanf(line, "%lx-%lx", &s, &e) != 2) continue;
        g_ctx[g_ctx_n].base = (unsigned char *)s;
        g_ctx[g_ctx_n].len = (size_t)(e - s);
        g_ctx_n++;
    }
    pa_wipe(line, sizeof(line));
    fclose(f);
    // 说明：曾实现过一个「按内存形状搜 ContextNode 数组」的兜底（P4，给内核不提供
    // `[anon:System property context nodes]` 名字的老设备用），但实测**会 SIGBUS 把目标
    // 应用打崩**（扫任意匿名区时踩到不可安全访问的映射）。一个能把宿主进程干掉的兜底，
    // 比它想修的「退化到 r--p」严重得多，所以**移除**。老内核上维持原行为：
    // 找不到数组 → 重指路不可用 → `ctx_layout = -1`（在日志里可见），由调用方决定。
    pa_ctx_resolve_layout();
}

// 把「指向原区的 pa_ 槽位」改指到副本；返回改了几个槽位。
static int pa_repoint(const void *from, void *to) {
    if (g_ctx_probed == -1) return 0;    // 布局都没认出来，别瞎改
    int n = 0;
    for (int i = 0; i < g_ctx_n; i++) {
        unsigned char *p = g_ctx[i].base;
        unsigned char *end = p + g_ctx[i].len;
        for (; p + g_ctx_stride <= end; p += g_ctx_stride) {
            void **slot = (void **)(p + g_ctx_pa_off);
            if (*slot == from) { *slot = to; n++; }
        }
    }
    return n;
}

// ─────────────────── 给私有副本起一个「正常」的 VMA 名 ───────────────────
// 为什么必须做：不加名字的话，/proc/self/maps 里会凭空多出一条**无名匿名映射**。
// 设备上正常的匿名映射几乎都有名字（[anon:libc_malloc]、[anon:linker_alloc]、
// [anon:.bss] …），一条无名、大小又恰好等于某个属性区上下文的映射，非常扎眼。
// 公开实现的做法是命名成 linker_alloc —— linker 本来就大量产出同名的
// `r--p [anon:linker_alloc]`，混在里面看不出来（原厂系统里同样到处都是）。
//
// 两个坑（公开实现踩过，照抄）：
//   1. 内核**不拷贝**名字字符串，只存指针 —— 缓冲区必须长期有效。放栈上会在函数
//      返回后变成乱码，maps 里显示成 `[anon:<fault>]`，比不命名更扎眼。故堆上分配、永不释放。
//   2. PR_SET_VMA_ANON_NAME 不是所有内核都支持，且可能「假成功」。先拿一块一次性
//      映射探一次，明确不支持就整体放弃命名（宁可不名，也别留 [anon:<fault>]）。
#ifndef PR_SET_VMA
#define PR_SET_VMA 0x53564d41
#endif
#ifndef PR_SET_VMA_ANON_NAME
#define PR_SET_VMA_ANON_NAME 0
#endif

#define PA_VMA_NAME "linker_alloc"

static int g_vma_name_ok = -1;   // -1 未探测 / 0 不支持 / 1 支持

// 永不释放的名字缓冲：内核持有这个指针，free 掉就会显示成乱码
static const char *pa_vma_name(void) {
    static char *s = nullptr;
    if (!s) {
        s = (char *)malloc(sizeof(PA_VMA_NAME));
        if (s) memcpy(s, PA_VMA_NAME, sizeof(PA_VMA_NAME));
    }
    return s;
}

// 读 /proc/self/maps，取「包含 addr 的那条 VMA」的方括号名字。
// 为什么需要它：`prctl(PR_SET_VMA_ANON_NAME)` 在部分内核上会**假成功**——返回值是 0，
// 但名字并没有落到 VMA 上（maps 里显示成 `[anon:<fault>]` 或干脆无名），
// 那比不命名更扎眼。只有回读 maps 才能确认（公开实现也是这么干的）。
static bool pa_vma_name_at(const void *addr, char *out, size_t cap) {
    if (out && cap) out[0] = 0;
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) return false;
    char line[1024];
    uintptr_t a = (uintptr_t)addr;
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        unsigned long s = 0, e = 0;
        if (sscanf(line, "%lx-%lx", &s, &e) != 2) continue;
        if (a < s || a >= e) continue;
        const char *b = strchr(line, '[');
        const char *c = b ? strchr(b, ']') : nullptr;
        if (b && c && out && cap) {
            size_t n = (size_t)(c - b - 1);
            if (n >= cap) n = cap - 1;
            memcpy(out, b + 1, n);
            out[n] = 0;
        }
        found = true;
        break;
    }
    pa_wipe(line, sizeof(line));
    fclose(f);
    return found;
}

static bool pa_vma_name_supported(void) {
    if (g_vma_name_ok >= 0) return g_vma_name_ok == 1;
    g_vma_name_ok = 0;
    const char *nm = pa_vma_name();
    if (!nm) return false;

    // 探针必须是**独立**的 VMA：单映射一页时内核会把它并进相邻的匿名 VMA，
    // 那样按地址回读 maps 查到的是别人那条、名字对不上 → 误判「内核不支持」→
    // 于是放弃命名，克隆块退化成无名匿名 r--p（实测踩到，比不校验还糟）。
    // 两侧各留一页 PROT_NONE 当护栏，强制它独立成条。
    const size_t pg = 0x1000;
    void *blk = mmap(nullptr, pg * 3, PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (blk == MAP_FAILED) return false;
    mprotect((char *)blk, pg, PROT_NONE);
    mprotect((char *)blk + pg * 2, pg, PROT_NONE);
    void *probe = (char *)blk + pg;

    long r = prctl(PR_SET_VMA, PR_SET_VMA_ANON_NAME,
                   (unsigned long)probe, (unsigned long)pg, nm);
    // 探针必须**回读 maps 核对**，不能只看 prctl 的返回值（假成功会留下 [anon:<fault>]）
    char got[64] = {0};
    bool named = (r == 0) && pa_vma_name_at(probe, got, sizeof(got)) && strstr(got, PA_VMA_NAME);
    munmap(blk, pg * 3);
    g_vma_name_ok = named ? 1 : 0;
    return g_vma_name_ok == 1;
}

// 必须在 mprotect(PROT_READ) **之前**调用 —— 部分内核要求 VMA 仍可写才肯改名。
static void pa_name_vma(void *addr, size_t len) {
    if (!addr || !len) return;
    if (!pa_vma_name_supported()) return;
    const char *nm = pa_vma_name();
    if (!nm) return;
    prctl(PR_SET_VMA, PR_SET_VMA_ANON_NAME, (unsigned long)addr, (unsigned long)len, nm);
}

// 匿名副本 + 重指。成功返回 1，并把改动的槽位数写进 slots_out。
static int pa_clone_index_repoint(int i, int *slots_out, int *err_out) {
    PaMapInfo *m = &g_pa_maps[i];
    if (m->cloned) return 1;
    unsigned char *copy = (unsigned char *)mmap(nullptr, m->len, PROT_READ | PROT_WRITE,
                                                MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (copy == MAP_FAILED) { *err_out = errno; return 0; }
    memcpy(copy, m->start, m->len);
    int n = pa_repoint(m->start, copy);
    if (n == 0) {
        // 没有任何指针指向它 —— 抄了也没人看，撤掉，免得白占一块可疑内存
        munmap(copy, m->len);
        *err_out = 0;
        return 0;
    }
    pa_name_vma(copy, m->len);      // 起个正常名字，别留无名匿名块（必须在 seal 之前）
    m->copy = copy;                 // 写完值之后 seal 这块
    m->cloned = 1;
    m->via_repoint = 1;
    m->repoint_slots = n;
    if (g_pa_clones_n < PA_MAX_CLONES) g_pa_clones[g_pa_clones_n++] = m;
    *slots_out = n;
    return 1;
}

// 把第 i 块映射换成本进程私有副本（优先「不留痕」那条路）。
static int pa_clone_index(int i, bool *used_fd, int *err_out) {
    PaMapInfo *m = &g_pa_maps[i];
    if (m->cloned) return 1;

    // ── 首选：匿名副本 + 重指 ContextNode（原映射保持 r--s 不动）──
    if (g_ctx_n > 0) {
        int slots = 0;
        if (pa_clone_index_repoint(i, &slots, err_out)) {
            *used_fd = false;
            return 1;
        }
    }

    // ── 兜底：fd 的私有映射 ──
    int fd = open(m->path, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        void *p = mmap(m->start, m->len, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_FIXED, fd, (off_t)m->off);
        close(fd);
        if (p != MAP_FAILED) {
            m->cloned = 1;
            *used_fd = true;
            if (g_pa_clones_n < PA_MAX_CLONES) g_pa_clones[g_pa_clones_n++] = m;
            return 1;
        }
        *err_out = errno;
    } else {
        *err_out = errno;               // open 被拒多半是 SELinux，走 B 路
    }

    // ── B 路：匿名副本（MAP_FIXED 会顶掉原映射，所以先抄内容）──
    unsigned char *tmp = (unsigned char *)malloc(m->len);
    if (!tmp) { *err_out = ENOMEM; return 0; }
    memcpy(tmp, m->start, m->len);
    void *p = mmap(m->start, m->len, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (p == MAP_FAILED) {
        *err_out = errno;
        free(tmp);
        return 0;                       // MAP_FIXED 失败时原映射没动过，内容没丢
    }
    memcpy(m->start, tmp, m->len);
    free(tmp);
    pa_name_vma(m->start, m->len);      // B 路顶掉原映射后已是匿名映射，同样需要起名
    m->cloned = 1;
    *used_fd = false;
    if (g_pa_clones_n < PA_MAX_CLONES) g_pa_clones[g_pa_clones_n++] = m;
    return 1;
}

static int pa_write_impl(const char *name, const char *value);
static int pa_seal(void);

// 主入口：为这批键克隆它们所在的属性区。seal_now=false 时保持可写（随后写值再 seal）。
static PaStats pa_clone_for_keys(const char *const *keys, int nkeys, int seal_now) {
    PaStats st;
    memset(&st, 0, sizeof(st));
    g_pa_clones_n = 0;
    if (nkeys > PA_MAX_KEYS) nkeys = PA_MAX_KEYS;
    st.keys_asked = nkeys;

    // 第一阶段：先把所有键都问一遍 —— 这一步可能触发 bionic 惰性映射新的属性上下文，
    // 所以必须在扫 maps 之前做完，否则新映射进来的块不在名单里（踩过）。
    static const prop_info *pi[PA_MAX_KEYS];
    for (int k = 0; k < nkeys; k++) {
        pi[k] = keys[k] ? __system_property_find(keys[k]) : nullptr;
        if (pi[k]) st.keys_found++; else st.keys_missing++;
    }

    // 第二阶段：扫 maps（此时所有用得到的块都已经映射出来了）
    st.maps_total = pa_scan_maps(0);
    if (st.maps_total == 0) return st;
    pa_scan_ctx_nodes();      // bionic 的 ContextNode 数组（重指用），内含布局校验
    st.ctx_layout = g_ctx_probed;

    // 第三阶段：逐块克隆
    for (int k = 0; k < nkeys; k++) {
        if (!pi[k]) continue;
        int idx = pa_map_index_of((const void *)pi[k]);
        if (idx < 0) { st.clone_failed++; continue; }
        if (g_pa_maps[idx].writable) { st.already_writable++; continue; }
        if (g_pa_maps[idx].cloned) continue;

        bool used_fd = false;
        int err = 0;
        if (pa_clone_index(idx, &used_fd, &err)) {
            st.areas_needed++;
            if (g_pa_maps[idx].via_repoint) {
                st.cloned_repoint++;
                st.repoint_slots += g_pa_maps[idx].repoint_slots;
            } else if (used_fd) {
                st.cloned_fd++;
            } else {
                st.cloned_anon++;
            }
        } else {
            st.clone_failed++;
            st.last_errno = err;
            if (err == EACCES || err == EPERM) st.open_denied++;
        }
    }

    if (seal_now) st.sealed = pa_seal();
    return st;
}

// 写完值之后封成只读。属性区本来就该是只读的，留着 rw 会被 maps 检测看到。
static int pa_seal(void) {
    int ok = 0;
    for (int i = 0; i < g_pa_clones_n; i++) {
        PaMapInfo *m = g_pa_clones[i];
        // 重指路封的是副本；原映射是别人的（共享库页），碰它会出事
        unsigned char *base = m->via_repoint ? m->copy : (unsigned char *)m->start;
        if (!base) continue;
        if (mprotect(base, m->len, PROT_READ) != 0) continue;
        // 回读校验：部分内核在 mprotect(PROT_READ) 之后会把匿名 VMA 的名字丢掉，
        // 于是这块变成**无名匿名 r--p** —— 那正是最扎眼的那种指纹（一行 grep 就能命中）。
        // 丢了就退回可写、重新命名：宁可保 `rw-p [anon:linker_alloc]`（stock 里也有），
        // 也不能留无名块（公开实现同款处理）。
        if (g_vma_name_ok == 1) {
            char got[64] = {0};
            // 注意：内核在 maps 里把匿名名字显示成 [anon:linker_alloc]（带 "anon:" 前缀），
            // 所以这里必须用「包含」判断，不能 strcmp（实测踩到，会误判成命名失败）。
            if (!(pa_vma_name_at(base, got, sizeof(got)) && strstr(got, PA_VMA_NAME))) {
                if (mprotect(base, m->len, PROT_READ | PROT_WRITE) == 0) pa_name_vma(base, m->len);
            }
        }
        ok++;
    }
    return ok;
}

// 按 bionic 的协议写值：置 dirty 位 → 写 → 清 dirty 并递增序列号。
// 结果码：1 成功 / 0 属性不存在 / -1 原值是长值属性（跳过）/ -2 值太长 / -3 这块没克隆（写了会漏到共享页）
// 指针落在某块「已克隆的副本」里吗（重指之后 prop_info 会指向副本）。
static bool pa_in_copy(const void *p) {
    uintptr_t a = (uintptr_t)p;
    for (int i = 0; i < g_pa_clones_n; i++) {
        PaMapInfo *m = g_pa_clones[i];
        if (!m->via_repoint || !m->copy) continue;
        uintptr_t s = (uintptr_t)m->copy;
        if (a >= s && a < s + m->len) return true;
    }
    return false;
}

// ─────────────────── __DELETE__：在本进程私有副本里「真正删掉」一个键 ───────────────────
// 常见实现**都只有哨兵、没有这个实现**：它们的 __DELETE__ 只在
// companion_resetprop（默认关）下生效，实际是把键名交给 Magisk 的 `resetprop -d` ——
// 那是**全局**删除（所有进程、连 root 的 getprop 都看得见），破坏它们自己承诺的
// per-process 隔离。我们这条是**进程内**的：
//
//   bionic 查属性走属性区内的一棵 trie。每个键对应树上一个终止节点，节点的 `prop`
//   字段是「指向 prop_info 的偏移」。把那个字段置 0，find_property 走到这里拿到的就是
//   nullptr ⇒ __system_property_find 返回 NULL、__system_property_foreach（getprop 用它
//   枚举）也会跳过它。**整棵树都在我们的克隆副本里，原区一个字节都不动。**
//
// 偏移基准**不猜**：区内所有偏移（prop/left/right/children）共用一个 base，用
// 「非零的 left/right/children 必须正好落在某个已扫出的节点上」这个自洽约束把它解出来；
// 自洽度 <75% 就放弃。**宁可不删，也绝不能把宿主进程的 trie 弄坏。**
// 真机实测（Android 16 / bionic / 内核 6.6.118）：base = 0x80，108/108 自洽；见
// test/delete_test.cpp（含负向控制：删 ro.build.display.id 后 .show/.ota/.full_id 照旧可读）。
#define PA_BT_MAX 1024
#define PA_BT_NAME_MAX 47
#define PA_DELETE_SENTINEL "__DELETE__"

struct PaBtNode {
    uint32_t off;
    uint32_t namelen, prop, left, right, children;
};

static bool pa_bt_name_ok(const unsigned char *p, uint32_t n) {
    if (n == 0 || n > PA_BT_NAME_MAX) return false;
    for (uint32_t i = 0; i < n; i++) {
        unsigned char c = p[i];
        if (c < 0x20 || c > 0x7e) return false;   // 名字片段都是可打印 ASCII
    }
    return true;
}

// p 落在某块**已克隆**的属性区里吗？是则返回副本基址。
// 没克隆过的绝对不能碰 —— 那是所有进程共享的页，改了会漏到全设备（违背本模块的承诺）。
static unsigned char *pa_cloned_area_of(const void *p, size_t *len_out) {
    uintptr_t a = (uintptr_t)p;
    for (int i = 0; i < g_pa_maps_n; i++) {
        PaMapInfo *m = &g_pa_maps[i];
        if (!m->cloned) continue;
        unsigned char *b = m->copy ? m->copy : (unsigned char *)m->start;
        if (a >= (uintptr_t)b && a < (uintptr_t)b + m->len) {
            *len_out = m->len;
            return b;
        }
    }
    return nullptr;
}

// ── 按属性区缓存：扫描 + 解基准只做一次 ──
// 每删一个键都重扫 256KB、再用 128×N² 解基准的话，跨生态清理（最多 72 条）会阻塞
// 目标 App 启动近一秒。属性区在一批删除里不会变，且节点按扫描顺序天然递增 ⇒ 缓存 + 二分。
static PaBtNode g_bt[PA_BT_MAX];
static int g_bt_n = 0;
static unsigned char *g_bt_base = nullptr;
static uint32_t g_bt_bestB = 0;
static int g_bt_ok = 0;                       // 0 未扫 / 1 已扫且基准自洽 / -1 认不出

// bt[] 的 off 是递增的（按扫描顺序填），直接二分
static bool pa_bt_has_off(uint32_t want) {
    int lo = 0, hi = g_bt_n - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (g_bt[mid].off == want) return true;
        if (g_bt[mid].off < want) lo = mid + 1; else hi = mid - 1;
    }
    return false;
}

static bool pa_bt_build(unsigned char *base, size_t alen) {
    if (g_bt_base == base && g_bt_ok != 0) return g_bt_ok > 0;
    g_bt_base = base;
    g_bt_ok = -1;
    g_bt_n = 0;
    for (size_t o = 4; o + 20 <= alen; o += 4) {
        uint32_t nl;
        memcpy(&nl, base + o, 4);
        if (nl < 1 || nl > PA_BT_NAME_MAX) continue;
        if (o + 20 + nl > alen) continue;
        if (!pa_bt_name_ok(base + o + 20, nl)) continue;
        if (g_bt_n >= PA_BT_MAX) return false;      // 节点太多 → 认不出，放弃
        PaBtNode *n = &g_bt[g_bt_n++];
        n->off = (uint32_t)o;
        n->namelen = nl;
        memcpy(&n->prop, base + o + 4, 4);
        memcpy(&n->left, base + o + 8, 4);
        memcpy(&n->right, base + o + 12, 4);
        memcpy(&n->children, base + o + 16, 4);
    }
    if (g_bt_n == 0) return false;

    // 解偏移基准：非零的 left/right/children 必须正好落在某个已知节点上
    uint32_t bestB = 0;
    int best_hit = -1, best_tot = 0;
    for (uint32_t B = 0; B <= 0x200; B += 4) {
        int hit = 0, tot = 0;
        for (int i = 0; i < g_bt_n; i++) {
            uint32_t v[3] = {g_bt[i].left, g_bt[i].right, g_bt[i].children};
            for (int k = 0; k < 3; k++) {
                if (!v[k]) continue;
                tot++;
                if (pa_bt_has_off(B + v[k])) hit++;
            }
        }
        if (hit > best_hit) { best_hit = hit; bestB = B; best_tot = tot; }
    }
    if (best_tot == 0 || best_hit * 4 < best_tot * 3) return false;   // 自洽度 <75%
    g_bt_bestB = bestB;
    g_bt_ok = 1;
    return true;
}

// 结果码：1 删除成功（已回读确认 find()==NULL）/ 0 该键本来就不存在
//        -3 布局认不出（放弃，不冒险）/ -4 删完回读仍能找到（已回滚）
static int pa_delete_impl(const char *name) {
    if (!name) return -3;
    const prop_info *pi = __system_property_find(name);
    if (!pi) return 0;
    size_t alen = 0;
    unsigned char *base = pa_cloned_area_of((const void *)pi, &alen);
    if (!base) return -3;
    if (!pa_bt_build(base, alen)) return -3;
    uint32_t pi_off = (uint32_t)((const unsigned char *)pi - base);
    int nn = g_bt_n;
    PaBtNode *bt = g_bt;
    uint32_t bestB = g_bt_bestB;

    size_t keylen = strlen(name);
    uint32_t *field = nullptr;
    for (int i = 0; i < nn; i++) {
        if (!bt[i].prop) continue;
        if (bt[i].prop + bestB != pi_off) continue;      // 必须正好指向我们的 prop_info
        uint32_t nl = bt[i].namelen;
        if (nl == 0 || nl > keylen) continue;
        // 名字片段必须是键名的后缀 —— 第二重确认删的是这个键本身，不是同前缀的兄弟
        if (memcmp(name + keylen - nl, base + bt[i].off + 20, nl) != 0) continue;
        if (field) return -3;                            // 多个命中 → 认不出，放弃
        field = (uint32_t *)(base + bt[i].off + 4);
    }
    if (!field) return -3;

    uint32_t saved = *field;
    __atomic_store_n(field, 0u, __ATOMIC_RELEASE);
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
    if (__system_property_find(name) != nullptr) {       // 回读没过 → 立刻回滚
        __atomic_store_n(field, saved, __ATOMIC_RELEASE);
        __atomic_thread_fence(__ATOMIC_SEQ_CST);
        return -4;
    }
    return 1;
}

static int pa_write_impl(const char *name, const char *value) {
    if (!name || !value) return -3;
    // ★ 硬断言（2026-10-06 修缺陷①）：删除哨兵**只能**走删除通道，永远不许当「值」写。
    //   历史事故：配置是扁平的 `pkg<TAB>key<TAB>value`，哨兵一路活到写入函数；native 这条
    //   虽然在本文件里被 strcmp 拦住，**模块的 Java 侧（spoof.cpp 的 set_static_str）没有拦**，
    //   于是目标进程里 `Build.FINGERPRINT` 变成了字面量 "__DELETE__"（Momo 实测抓到）。
    //   现在：返回 -5 = 拒绝写入（调用方计数 + 记名单），删除一律由 pa_delete_impl 负责。
    if (strcmp(value, PA_DELETE_SENTINEL) == 0) return -5;
    const prop_info *pi = __system_property_find(name);
    if (!pi) return 0;
    if (pa_in_copy((const void *)pi)) {
        // 已在私有副本里，直接写
    } else {
        int idx = pa_map_index_of((const void *)pi);
        if (idx < 0) {
            pa_scan_maps(1);                     // 名单可能过期，补扫一次
            idx = pa_map_index_of((const void *)pi);
            if (idx < 0) return -3;
        }
        if (!g_pa_maps[idx].cloned && !g_pa_maps[idx].writable) return -3;
    }

    size_t n = strlen(value);
    if (n > PA_VALUE_MAX - 1) return -2;
    // 原值是长值属性时 prop_info 不是 value[92] 布局，原地写会写出一个读不对的东西。
    PaReadCtx cur;
    if (pa_read_full(name, &cur) && cur.len > PA_VALUE_MAX - 1) return -1;

    prop_info *w = const_cast<prop_info *>(pi);
    uint32_t s = __atomic_load_n(&w->serial, __ATOMIC_RELAXED);
    if (s & PA_LONG_FLAG) return -1;

    // 严格按 bionic SystemProperties::Update 的协议：
    //   ① 置 dirty 位（读者见到 dirty 会去 dirty backup 区取旧值）
    //   ② 写值
    //   ③ 发布：长度放最高字节，计数位 +1 并清 dirty
    // 我们在进程启动早期单线程执行，读者看不到 ①② 之间的窗口，
    // 所以不复制 dirty backup 区（省掉一次 92 字节拷贝与对区头的依赖）。
    uint32_t dirty = s | 1u;
    __atomic_store_n(&w->serial, dirty, __ATOMIC_RELAXED);
    __atomic_thread_fence(__ATOMIC_RELEASE);
    memcpy(w->value, value, n);
    w->value[n] = '\0';
    __atomic_thread_fence(__ATOMIC_RELEASE);
    // 注意：要用「置过 dirty 的那个值」去 +1，这样最低位才会被清掉；
    // 拿原始 s 去 +1 会留下 dirty 位，读者会去读 dirty backup 区（读到空串）。
    __atomic_store_n(&w->serial, PA_SERIAL_MAKE(n, dirty), __ATOMIC_RELEASE);

    // ★ 回读校验（公开实现同款做法）。
    // 这是**唯一**能发现「写了但没生效」这类静默失效的手段：协议写错、被 bionic 的
    // 惰性映射挡住、或者这块其实没克隆成功 —— 三种情况的表象都是「函数返回成功、
    // 目标进程里那个键却还是真值」。不回读就只能等检测方告诉我们。
    // 用 __system_property_get（走 serial 里的长度字节，是对协议最敏感的那条读取路径）。
    char back[PA_VALUE_MAX];
    back[0] = 0;
    __system_property_get(name, back);
    if (strcmp(back, value) != 0) return -4;
    return 1;
}

// 单个键的写（外部用）。
static int pa_write(const char *name, const char *value) {
    return pa_write_impl(name, value);
}

// 记一条「没生效的键」（只记非成功的，上限 PA_NOTE_MAX）
static void pa_note(PaStats *st, const char *key, int code) {
    if (!st || !key || st->notes_n >= PA_NOTE_MAX) return;
    PaNote *nt = &st->notes[st->notes_n++];
    size_t l = strlen(key);
    if (l >= sizeof(nt->key)) l = sizeof(nt->key) - 1;
    memcpy(nt->key, key, l);
    nt->key[l] = 0;
    nt->code = code;
}

// 删一个键：统计 + 记名单（独立删除通道与老式带内哨兵共用这一条）。
static void pa_del_one(PaStats *st, const char *key) {
    int r = pa_delete_impl(key);
    if (r == 1) st->deleted++;
    else if (r == 0) { st->delete_missing++; pa_note(st, key, 100); }
    else { st->delete_failed++; pa_note(st, key, 100 + r); }
}

// 写一批键：先为这批键克隆属性区，逐个写，**写完再处理删除**，最后 seal。
//
// ★ 删除**有独立通道**（dels/ndel，配置里的 `DEL<TAB>pkg<TAB>key` 行）：值域里永远
//   不该出现哨兵 —— 这正是公开实现的结构（写与删两条通道，配置解析层就把
//   哨兵转成纯键名清单）。历史事故就是两条通道混在一条扁平 `key<TAB>value` 里，
//   哨兵被 Java 侧当成值写进了 `Build.FINGERPRINT`。
//
// 兼容：调用方若仍按老式「带内哨兵」传进来（老 App / 老配置），这里
//   ① 绝不当值写入（记 sentinel_refused + 名单）② 仍按删除处理 —— 老行为不丢，泄漏路径断掉。
// 统计都在返回值里。
static PaStats pa_spoof_props(const char *const *keys, const char *const *values, int n,
                              const char *const *dels, int ndel) {
    PaStats st = pa_clone_for_keys(keys, n, 0);

    // 写成功的键记下来，删除做完之后拿它们做**负向控制**：
    // 若删除把 trie 弄坏了，这些原本能读到的键会突然读不到 —— 这个计数就是证据。
    static const char *ok_keys[PA_MAX_KEYS];
    int ok_n = 0;

    for (int i = 0; i < n; i++) {
        if (!keys[i] || !values[i]) continue;
        if (strcmp(values[i], PA_DELETE_SENTINEL) == 0) {
            st.sentinel_refused++;                  // 硬断言：哨兵不是值
            pa_note(&st, keys[i], -5);              // 记名单（删除阶段会再处理这个键）
            continue;
        }
        int r = pa_write_impl(keys[i], values[i]);
        switch (r) {
            case 1:
                st.written++;
                if (ok_n < PA_MAX_KEYS) ok_keys[ok_n++] = keys[i];
                break;
            case 0: st.write_missing++; pa_note(&st, keys[i], 0); break;
            case -1: st.write_long++; pa_note(&st, keys[i], -1); break;
            case -2: st.write_toolong++; pa_note(&st, keys[i], -2); break;
            case -4: st.write_noverify++; pa_note(&st, keys[i], -4); break;
            case -5: st.sentinel_refused++; pa_note(&st, keys[i], -5); break;
            default: st.write_notcloned++; pa_note(&st, keys[i], r); break;
        }
    }

    // 删除：放在所有写之后 —— 同一个键不会既写又删，但同一块属性区会同时承载两者，
    // 先写后删的顺序让上面的 ok_keys 能覆盖到「删除是否波及写过的键」。
    for (int i = 0; i < ndel; i++) {                 // ① 独立通道（正常路径）
        if (!dels || !dels[i]) continue;
        pa_del_one(&st, dels[i]);
    }
    for (int i = 0; i < n; i++) {                    // ② 兜底：老式带内哨兵
        if (!keys[i] || !values[i]) continue;
        if (strcmp(values[i], PA_DELETE_SENTINEL) != 0) continue;
        pa_del_one(&st, keys[i]);
    }

    for (int i = 0; i < ok_n; i++) {
        char b[PA_VALUE_MAX];
        b[0] = 0;
        if (!pa_read(ok_keys[i], b, sizeof(b))) st.delete_broke++;
    }

    st.sealed = pa_seal();
    return st;
}
