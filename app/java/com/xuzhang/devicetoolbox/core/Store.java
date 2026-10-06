package com.xuzhang.devicetoolbox.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 本地持久化：当前目标机型、档位、主题、方案列表、收藏。 */
public final class Store {

    private static final String NAME = "toolbox";
    private static final String K_TARGET = "target";
    private static final String K_DARK = "dark";
    private static final String K_SCHEMES = "schemes";
    private static final String K_FAV = "favorites";

    private final SharedPreferences sp;

    public Store(Context c) {
        sp = c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 基本设置

    public Target target() {
        String s = sp.getString(K_TARGET, null);
        if (s == null || s.trim().isEmpty()) return null;
        Target t = Target.parse(s);
        // 旧版本会把「按 SDK 写死的补丁日期」存进来；这里做一次精确迁移，
        // 让它按新规则（近期）重新派生 —— 否则已存过的目标永远顶着 2 年前的补丁日期。
        Target.healInventedDates(t);
        // ★ 再按机型码从库里补一份**真指纹**：内部版本号只能从真指纹第 6 段取（见 Target.fingerprint()），
        //   旧版把日期族清掉之后指纹就再也回不来了（真指纹一旦丢失就无法再推导出来）；补上之后
        //   buildId / incremental 会随真指纹一起对齐成真值。
        Library.fillRealFingerprint(t);
        return t;
    }

    public void setTarget(Target t) {
        // 用 commit()（同步落盘）而不是 apply()（异步排队写）：本 App 的配置流程是
        // 「改完立刻重启自己生效」，排队写会随进程一起消失。
        boolean ok = sp.edit().putString(K_TARGET, t == null ? "" : t.serialize()).commit();
        if (!ok) android.util.Log.w("toolbox", "全局目标写入失败");
    }

    /**
     * 伪装深度**固定为「深度」档**，不再让用户选。
     *
     * 为什么去掉选择：三档的差别只是「改多少条属性」，而真正决定隐藏效果的是
     * 「有没有改到该改的地方」—— 少改一处就是一个不一致点，就是被检测的入口。
     * 既然没有「改得少反而更安全」的场景（改少了只会更容易露馅），
     * 那就没有理由把选择权交给用户：一律用覆盖最全的深度档。
     */
    public int level() { return Props.DEEP; }

    /** 保留接口以兼容旧的分享码/方案导入；档位已固定，调用被忽略。 */
    public void setLevel(int l) { /* 档位固定为深度，不再可改 */ }

    public boolean dark() { return sp.getBoolean(K_DARK, true); }

    public void setDark(boolean d) { sp.edit().putBoolean(K_DARK, d).commit(); }

    // ------------------------------------------------------------ 方案

    public static final class Scheme {
        public String name = "";
        public String target = "";
        public int level = Props.DEEP;      // 档位固定为深度档（见 level()），新建方案即深度
        public long time = 0;

        public Target asTarget() { return Target.parse(target); }

        public String encode() {
            return Target.esc(name) + "\u0001" + level + "\u0001" + time + "\u0001" + Target.esc(target);
        }

        public static Scheme decode(String s) {
            String[] f = s.split("\u0001", 4);
            Scheme sc = new Scheme();
            if (f.length < 4) return sc;
            sc.name = Target.unesc(f[0]);
            // 老版本存下的方案里可能是 0/1：读出来就归一，列表显示的档位才与实际执行一致。
            try { sc.level = Props.normalizedLevel(Integer.parseInt(f[1])); } catch (Throwable ignored) { }
            try { sc.time = Long.parseLong(f[2]); } catch (Throwable ignored) { }
            sc.target = Target.unesc(f[3]);
            return sc;
        }
    }

    public List<Scheme> schemes() {
        List<Scheme> out = new ArrayList<>();
        String raw = sp.getString(K_SCHEMES, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String row : raw.split("\u0002")) {
            if (row.trim().isEmpty()) continue;
            out.add(Scheme.decode(row));
        }
        return out;
    }

    public void saveSchemes(List<Scheme> list) {
        StringBuilder sb = new StringBuilder();
        for (Scheme s : list) {
            if (sb.length() > 0) sb.append('\u0002');
            sb.append(s.encode());
        }
        sp.edit().putString(K_SCHEMES, sb.toString()).commit();
    }

    public void addScheme(Scheme s) {
        List<Scheme> list = schemes();
        // 同名覆盖
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(s.name)) { list.set(i, s); saveSchemes(list); return; }
        }
        list.add(0, s);
        saveSchemes(list);
    }

    public void removeScheme(int index) {
        List<Scheme> list = schemes();
        if (index >= 0 && index < list.size()) { list.remove(index); saveSchemes(list); }
    }

    // ------------------------------------------------------------ 收藏

    public Set<String> favorites() {
        Set<String> s = sp.getStringSet(K_FAV, null);
        return s == null ? new LinkedHashSet<String>() : new LinkedHashSet<>(s);
    }

    public boolean isFavorite(Target t) { return favorites().contains(key(t)); }

    public void toggleFavorite(Target t) {
        Set<String> s = favorites();
        String k = key(t);
        if (!s.remove(k)) s.add(k);
        sp.edit().putStringSet(K_FAV, s).commit();
    }

    private static String key(Target t) {
        return (t.brand + "/" + t.model + "/" + t.release).toLowerCase();
    }
}
