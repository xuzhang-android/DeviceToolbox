package com.xuzhang.devicetoolbox.core;

import java.util.Collection;
import java.util.Map;

/**
 * 生成各类 shell 脚本文本。
 *
 * 所有会改系统状态的动作都走这里产出的脚本：Java 侧只负责生成文本，
 * 由 {@link Runner} 写到应用私有目录后一次性交给 root 执行 —— 这样避免成百上千次 su 调用。
 */
public final class Scripts {

    /** 模块 / 快照的落点。 */
    public static final String MODULE_ID = "devicetoolbox";
    public static final String DIR = "/data/adb/devicetoolbox";
    public static final String SNAP_DIR = DIR + "/snapshots";
    public static final String MODULE_DIR = "/data/adb/modules/" + MODULE_ID;

    /** 探测 resetprop，并定义 setp()。所有脚本都以此为开头。 */
    public static final String RESETPROP_PROBE =
            "RP=\"\"\n"
            + "for p in /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop "
            + "/data/adb/apd/bin/resetprop /data/adb/modules/apatch/bin/resetprop "
            + "/system/bin/resetprop /system/xbin/resetprop; do\n"
            + "  [ -x \"$p\" ] && RP=\"$p\" && break\n"
            + "done\n"
            + "[ -z \"$RP\" ] && RP=\"$(command -v resetprop 2>/dev/null)\"\n"
            + "echo \"$RP\"\n";

    /** 脚本头：探测 + setp 函数。 */
    public static String header() {
        return "RP=\"\"\n"
                + "for p in /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop "
                + "/data/adb/apd/bin/resetprop /data/adb/modules/apatch/bin/resetprop "
                + "/system/bin/resetprop /system/xbin/resetprop; do\n"
                + "  [ -x \"$p\" ] && RP=\"$p\" && break\n"
                + "done\n"
                + "[ -z \"$RP\" ] && RP=\"$(command -v resetprop 2>/dev/null)\"\n"
                + "setp() {\n"
                + "  if [ -n \"$RP\" ]; then \"$RP\" -n \"$1\" \"$2\"; else setprop \"$1\" \"$2\"; fi\n"
                + "}\n"
                + "delp() {\n"
                + "  if [ -n \"$RP\" ]; then \"$RP\" -d \"$1\"; else setprop \"$1\" \"\"; fi\n"
                + "}\n"
                + "[ -n \"$RP\" ] || { echo 'NO_RESETPROP'; exit 3; }\n";
    }

    // ------------------------------------------------------------ 应用伪装

    /** 生成应用脚本：把 target 的属性按 level 写入。 */
    public static String apply(Target t, int level) {
        StringBuilder sb = new StringBuilder(header());
        sb.append("echo \"APPLY level=").append(level).append(" target=")
          .append(esc(t.title())).append("\"\n");
        int n = 0;
        for (Map.Entry<String, String> e : Props.build(t, level).entrySet()) {
            sb.append("setp ").append(Sh.q(e.getKey())).append(' ').append(Sh.q(e.getValue())).append('\n');
            n++;
        }
        sb.append("echo \"DONE ").append(n).append("\"\n");
        return sb.toString();
    }

    // ------------------------------------------------------------ 快照

    /**
     * 生成快照脚本：先落一份完整 getprop 存档，再把「即将被改动的键」的当前值写成 restore.sh。
     * 还原时只回写这些键，不去动系统其它属性。
     */
    public static String snapshot(String dir, Map<String, String> current, Collection<String> keys) {
        StringBuilder sb = new StringBuilder();
        sb.append("mkdir -p ").append(Sh.q(dir)).append('\n');
        sb.append("getprop > ").append(Sh.q(dir + "/props-full.txt")).append('\n');
        sb.append("echo ").append(Sh.q("" + System.currentTimeMillis())).append(" > ")
          .append(Sh.q(dir + "/time.txt")).append('\n');
        return sb.toString();
    }

    /**
     * 生成还原脚本内容（由 Java 侧直接写成文件，再拷进快照目录）。
     *
     * 注意：getprop 不输出空值，所以「原机没有这个属性」和「值是空」在快照里都表现为缺失。
     * 这类键要**删掉**而不是写空 —— 写空会留下一个空壳属性，仍然能被检测到。
     */
    public static String restoreText(Map<String, String> current, Collection<String> keys) {
        StringBuilder sb = new StringBuilder("#!/system/bin/sh\n");
        sb.append(header());
        int n = 0, del = 0;
        for (String k : keys) {
            String v = current.get(k);
            if (v == null) {
                sb.append("delp ").append(Sh.q(k)).append('\n');
                del++;
            } else {
                sb.append("setp ").append(Sh.q(k)).append(' ').append(Sh.q(v)).append('\n');
            }
            n++;
        }
        sb.append("echo \"RESTORED ").append(n).append(" (deleted ").append(del).append(")\"\n");
        return sb.toString();
    }

    /** 读回校验：把指定键的当前值按 key=value 打印出来。 */
    public static String readBack(Collection<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            sb.append("printf '%s=%s\\n' ").append(Sh.q(k)).append(" \"$(getprop ").append(Sh.q(k)).append(")\"\n");
        }
        return sb.toString();
    }

    /** 一键还原到出厂（清掉我们所有改动 + 卸载模块 + 删目录）。 */
    public static String factoryReset(String restoreScriptPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("[ -f ").append(Sh.q(restoreScriptPath)).append(" ] && sh ")
          .append(Sh.q(restoreScriptPath)).append('\n');
        sb.append("rm -rf ").append(Sh.q(MODULE_DIR)).append('\n');
        sb.append("rm -rf ").append(Sh.q(DIR)).append('\n');
        sb.append("echo FACTORY_RESET_DONE\n");
        return sb.toString();
    }

    // ------------------------------------------------------------ 真值基线

    /** 存放真值基线的文件（只写一次，之后所有还原都从它取）。 */
    public static final String BASELINE = DIR + "/baseline.tsv";

    /**
     * 导出「原厂真值」。
     *
     * 关键点：**只读的 build.prop 文件不受 resetprop 影响**，所以它们才是真值。
     * getprop 读的是内存属性区，一旦伪装过就再也回不到真值了 ——
     * 之前「还原成我隐藏的那个机型」就是这么来的。
     *
     * 输出顺序即优先级：文件里的值在前，getprop 兜底在后，Java 侧按「先到先得」取值。
     */
    public static String dumpBaseline() {
        StringBuilder sb = new StringBuilder();
        // ★ 顺序很关键：**prop 文件在前、getprop 在后**（2026-10-06「全面检查」查出的真实事故）。
        //   全局伪装（resetprop 之类）只改**内存**属性区，改不动只读镜像里的 build.prop；
        //   而基线一旦抓到被内存伪装过的值，就会被当成「原厂真值」永久留存 ——
        //   本机实测基线里混进了 `ro.soc.manufacturer=vivo`、`ro.soc.model=SM8650`、
        //   `ro.build.flavor=OnePlus-PLQ110-user` 这些**这台机器上根本不存在的值**
        //   （来自更早的全局伪装实验，重启把内存改动清掉了，文件却一直没变）。
        //   文件优先能从根上挡掉这一整类。
        //   例外：厂商 init 会在运行时**覆盖**产品标识（本机 `ro.product.model` 文件里是
        //   基础镜像值 `ossi`，运行时是 `PLQ110`）—— 那几个键必须用 getprop，
        //   见 Engine.parseBaseline 里的窄白名单。
        sb.append("echo '---FILES---'\n");
        sb.append("for f in /system/build.prop /vendor/build.prop /odm/etc/build.prop ")
          .append("/vendor/odm/etc/build.prop /product/build.prop /system_ext/build.prop ")
          .append("/system/etc/prop.default /vendor/default.prop /default.prop; do\n")
          .append("  [ -f \"$f\" ] && grep -hE '^[A-Za-z_][A-Za-z0-9_.]*=' \"$f\" 2>/dev/null\n")
          .append("done\n");
        sb.append("echo '---GETPROP---'\n");
        // 这个标记必须与 Engine.parseBaseline 找的字符串**一字不差**：
        // 上一版 dump 发的是 `---FILES---`、解析器找的是 `---GETPROP---`，两边对不上，
        // 标记形同虚设 —— 当时靠「getprop 放最前 + 先到先得」歪打正着，也就此埋下了污染通道。
        sb.append("getprop | sed -n 's/^\\[\\(.*\\)\\]: \\[\\(.*\\)\\]$/\\1=\\2/p'\n");
        return sb.toString();
    }

    /** 把还原脚本的 setp/delp 改成从真值基线取值的版本。 */
    public static String restoreFromBaseline(java.util.Map<String, String> baseline,
                                             java.util.Collection<String> keys) {
        StringBuilder sb = new StringBuilder("#!/system/bin/sh\n");
        sb.append(header());
        int del = 0;
        for (String k : keys) {
            String v = baseline.get(k);
            if (v == null || v.isEmpty()) {
                sb.append("delp ").append(Sh.q(k)).append('\n');
                del++;
            } else {
                sb.append("setp ").append(Sh.q(k)).append(' ').append(Sh.q(v)).append('\n');
            }
        }
        sb.append("echo \"RESTORED_FROM_BASELINE (deleted ").append(del).append(")\"\n");
        return sb.toString();
    }

    // ------------------------------------------------------------ 隐藏 root 痕迹

    /**
     * 暴露「这台机器被解锁过 / 可调试」的属性。
     * 检测 SDK 主要就看这几项，所以把它们写回「原厂锁定、校验通过」的状态。
     */
    public static final String[][] HIDE_ROOT_PROPS = {
            {"ro.debuggable", "0"},
            {"ro.secure", "1"},
            {"ro.build.type", "user"},
            {"ro.build.tags", "release-keys"},
            {"ro.build.selinux", "1"},
            {"ro.boot.verifiedbootstate", "green"},
            {"ro.boot.flash.locked", "1"},
            {"ro.boot.veritymode", "enforcing"},
            {"ro.boot.vbmeta.device_state", "locked"},
            {"ro.boot.warranty_bit", "0"},
            {"ro.warranty_bit", "0"},
            {"ro.secureboot.lockstate", "locked"},
            {"ro.boot.mode", "normal"},
            {"ro.oem_unlock_supported", "0"},
            {"sys.oem_unlock_allowed", "0"},
            {"ro.crypto.state", "encrypted"},
            {"vendor.boot.verifiedbootstate", "green"},
            {"vendor.boot.vbmeta.device_state", "locked"},
            {"ro.is_ever_orange", "0"},
            {"ro.boot.realmebootstate", "green"},
    };

    public static java.util.List<String> hideRootKeys() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String[] kv : HIDE_ROOT_PROPS) keys.add(kv[0]);
        return keys;
    }

    /** 生成隐藏 root 的脚本。 */
    public static String hideRoot() {
        StringBuilder sb = new StringBuilder(header());
        sb.append("echo 'HIDE_ROOT'\n");
        for (String[] kv : HIDE_ROOT_PROPS) {
            sb.append("setp ").append(Sh.q(kv[0])).append(' ').append(Sh.q(kv[1])).append('\n');
        }
        sb.append("echo \"DONE ").append(HIDE_ROOT_PROPS.length).append("\"\n");
        return sb.toString();
    }

    /**
     * 快照的「期望值」清单：每行 key<TAB>值。
     * 还原之后用它读回校验 —— 还原失败必须能发现，不能默默失败。
     */
    public static String valuesText(java.util.Map<String, String> current, java.util.Collection<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            String v = current.get(k);
            sb.append(k).append('\t').append(v == null ? "" : Target.esc(v)).append('\n');
        }
        return sb.toString();
    }

    /** 读回校验脚本：把 values 里的键逐个打印成 key=值。 */

    // ------------------------------------------------------------ 开机模块

    /** Magisk / KernelSU 模块描述。 */
    public static String moduleProp(Target t, int level) {
        return "id=" + MODULE_ID + "\n"
                + "name=改机型工具箱 · 持久化\n"
                + "version=1.0\n"
                + "versionCode=1\n"
                + "author=xuzhang\n"
                + "description=开机自动应用机型伪装（" + Props.LEVEL_NAME[level] + "档 · "
                + t.title() + "）\n";
    }

    /** 模块的 post-fs-data.sh：开机早期就把属性写好。 */
    public static String postFsData(Target t, int level) {
        StringBuilder sb = new StringBuilder("#!/system/bin/sh\n");
        sb.append("# 由「改机型工具箱」生成\n");
        sb.append("MODDIR=${0%/*}\n");
        sb.append(header());
        sb.append("echo \"apply at $(date)\" >> ").append(Sh.q(DIR + "/boot.log")).append('\n');
        int n = 0;
        for (Map.Entry<String, String> e : Props.build(t, level).entrySet()) {
            sb.append("setp ").append(Sh.q(e.getKey())).append(' ').append(Sh.q(e.getValue())).append('\n');
            n++;
        }
        sb.append("echo \"boot applied ").append(n).append(" props\" >> ").append(Sh.q(DIR + "/boot.log")).append('\n');
        return sb.toString();
    }

    /** 独立的 apply.sh（导出到 /sdcard 用，不依赖本 App）。 */
    public static String standaloneApply(Target t, int level) {
        return "#!/system/bin/sh\n"
                + "# 改机型工具箱 —— 独立应用脚本（" + Props.LEVEL_NAME[level] + "档 / " + t.title() + "）\n"
                + "# 用法: su -c 'sh apply.sh'\n"
                + header() + apply(t, level);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }
}
