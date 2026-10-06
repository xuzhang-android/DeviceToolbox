package com.xuzhang.devicetoolbox.core;

import android.os.Build;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/** 读取本机当前状态：属性表、root、resetprop、LSPosed 等。 */
public final class Device {

    private static Map<String, String> propCache;

    // ---------------------------------------------------------------- 属性

    /** 全部 getprop（root 优先，失败回落普通身份）。 */
    public static synchronized Map<String, String> props() {
        if (propCache != null) return propCache;
        Map<String, String> map = new LinkedHashMap<>();
        Sh.Result r = Sh.root("getprop");
        if (!r.ok() || r.out.trim().isEmpty()) r = Sh.user("getprop");
        for (String line : r.out.split("\n")) {
            // 形如: [ro.product.model]: [Pixel 8]
            int a = line.indexOf('[');
            int b = line.indexOf("]: [");
            int c = line.lastIndexOf(']');
            if (a < 0 || b < 0 || c <= b) continue;
            map.put(line.substring(a + 1, b), line.substring(b + 4, c));
        }
        propCache = map;
        return map;
    }

    public static void invalidate() { propCache = null; }

    public static String prop(String key) {
        String v = props().get(key);
        return v == null ? "" : v;
    }

    // ---------------------------------------------------------------- Build

    /** 读 android.os.Build 的静态字段（Java 层看到的机型，用于三路对比）。 */
    public static String build(String field) {
        try {
            Field f = Build.class.getField(field);
            Object v = f.get(null);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            try {
                Field f = Build.VERSION.class.getField(field);
                Object v = f.get(null);
                return v == null ? "" : String.valueOf(v);
            } catch (Throwable t2) {
                return "";
            }
        }
    }

    // ---------------------------------------------------------------- 环境

    /** 当前是否已获得 root（su 可用）。 */
    public static boolean hasRoot() {
        Sh.Result r = Sh.root("id", 8000);
        return r.ok() && r.out.contains("uid=0");
    }

    /** 是否有可用的 resetprop（Magisk / KernelSU）。 */
    public static String resetpropPath() {
        String script = Scripts.RESETPROP_PROBE;
        Sh.Result r = Sh.root(script, 8000);
        String p = r.out.trim();
        return p.isEmpty() ? "" : p;
    }

    public static boolean hasResetprop() { return !resetpropPath().isEmpty(); }

    /** KernelSU / Magisk 的版本串。 */
    public static String rootImpl() {
        Sh.Result r = Sh.root("[ -d /data/adb/ksu ] && echo KernelSU; "
                + "[ -d /data/adb/magisk ] && echo Magisk; "
                + "[ -d /data/adb/apatch ] && echo APatch; true", 8000);
        String s = r.out.trim().replace("\n", " + ");
        return s.isEmpty() ? "未知" : s;
    }

    /** LSPosed / Xposed 框架是否在跑（看是否有框架的进程或目录）。 */
    public static boolean hasLsposed() {
        Sh.Result r = Sh.root("[ -d /data/adb/lspd ] || [ -d /data/adb/modules/zygisk_lsposed ] "
                + "|| [ -d /data/adb/modules/riru_lsposed ]; echo $?", 8000);
        if ("0".equals(r.out.trim())) return true;
        Sh.Result p = Sh.root("ps -A -o NAME 2>/dev/null | grep -c lspd", 8000);
        try { return Integer.parseInt(p.out.trim()) > 0; } catch (Throwable ignored) { return false; }
    }

    // ------------------------------------------------------- 当前机型摘要

    public static String currentRelease() {
        String v = prop("ro.build.version.release");
        return v.isEmpty() ? build("RELEASE") : v;
    }

    public static String currentSdk() {
        String v = prop("ro.build.version.sdk");
        return v.isEmpty() ? build("SDK_INT") : v;
    }

    public static String currentPatch() {
        String v = prop("ro.build.version.security_patch");
        return v.isEmpty() ? build("SECURITY_PATCH") : v;
    }

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
