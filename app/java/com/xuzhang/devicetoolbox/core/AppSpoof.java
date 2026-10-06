package com.xuzhang.devicetoolbox.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * App 级伪装配置 —— 存在本应用自己的 SharedPreferences 里。
 *
 * 这份偏好只是**本应用自己的**数据源（勾选列表 + 每个应用的目标机型），
 * 伪装模块不读它：模块要的那份配置由 {@link Zygisk} 生成后经 root 写到
 * `/data/adb/devicetoolbox/zygisk.conf`，模块的 companion 进程从那里读。
 *
 * 因此偏好文件**刻意不做成全局可读**。（旧实现里为了让 LSPosed 的
 * XSharedPreferences 读到它，曾把它设成 world-readable，Xposed 路退役后
 * 那段 hack 在 2026-10-06 一并删掉了。）
 */
public final class AppSpoof {

    public static final String PREFS = "appspoof";
    private static final String K_ENABLED = "enabled";
    private static final String K_TARGET = "target.";

    private AppSpoof() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 已开启 App 级伪装的包名，按加入顺序。 */
    public static List<String> enabled(Context c) {
        List<String> out = new ArrayList<>();
        String raw = sp(c).getString(K_ENABLED, "");
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static boolean isEnabled(Context c, String pkg) {
        return enabled(c).contains(pkg);
    }

    public static void setEnabled(Context c, String pkg, boolean on) {
        List<String> list = enabled(c);
        if (on) {
            if (!list.contains(pkg)) list.add(pkg);
        } else {
            list.remove(pkg);
        }
        setEnabledList(c, list);
    }

    /**
     * 一次性写整份勾选列表（原子：**一个 commit**，而不是循环里 N 次写）。
     *
     * 为什么必须这样：勾选列表与 `/data/adb/devicetoolbox/zygisk.conf` 是同一件事的两半，
     * 分多次写就会留下中间态（写到一半被杀进程、或中途某项写失败）。配置侧由
     * {@link Zygisk#applyList} 配套：先落这份列表，再按列表写配置，配置写失败就把列表回滚。
     *
     * 用 `commit()`（同步落盘）而不是 `apply()`（异步排队）：列表一旦生效，配置马上要按它写；
     * 排队写会随进程一起消失，留下「列表空了、配置里还勾着」这种最难查的不一致。
     */
    public static void setEnabledList(Context c, List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        if (pkgs != null) {
            for (String s : pkgs) {
                if (s == null) continue;
                String v = s.trim();
                if (!v.isEmpty()) sb.append(v).append('\n');
            }
        }
        sp(c).edit().putString(K_ENABLED, sb.toString()).commit();
    }

    /** 该包名的目标机型；没有单独设过就返回 null（模块会回落到全局目标）。 */
    public static Target targetFor(Context c, String pkg) {
        String raw = sp(c).getString(K_TARGET + pkg, "");
        if (raw == null || raw.trim().isEmpty()) return null;
        Target t = Target.parse(raw);
        // 与 Store.target() 同样做一次旧日期迁移 —— 逐应用存的目标也带着旧版「发明」的
        // 补丁日期，不迁移的话这条路的伪装会一直顶着 2 年前的补丁（检测方会点出来）。
        Target.healInventedDates(t);
        // ★ 真指纹也照补：内部版本号只能从真指纹第 6 段取（见 Target.fingerprint()）。
        //   库里那一行没有真指纹 ⇒ 目标就写不出可信的构建身份，那族键整族不写（不编造）。
        Library.fillRealFingerprint(t);
        return t;
    }

    public static void setTargetFor(Context c, String pkg, Target t) {
        sp(c).edit().putString(K_TARGET + pkg, t == null ? "" : t.serialize()).apply();
    }

    public static void remove(Context c, String pkg) {
        setEnabled(c, pkg, false);
        sp(c).edit().remove(K_TARGET + pkg).apply();
    }
}
