package com.xuzhang.devicetoolbox.ui;

import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xuzhang.devicetoolbox.MainActivity;
import com.xuzhang.devicetoolbox.core.Device;
import com.xuzhang.devicetoolbox.core.Engine;
import com.xuzhang.devicetoolbox.core.Runner;
import com.xuzhang.devicetoolbox.core.Scripts;
import com.xuzhang.devicetoolbox.core.Sh;
import com.xuzhang.devicetoolbox.core.Target;
import com.xuzhang.devicetoolbox.core.Zygisk;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具页。顺序按「用得多的在上面」排：
 * 自检 → 快照 → 导出 → 危险区 → 真值基线 → 设备信息总览（只读，放最后）。
 *
 * 应用隐藏（勾选目标应用 + 让它生效）与 Zygisk 模块管理都在首页；
 * 这里保留两边共用的静态入口（zygiskInstalled / syncAppHide / installZygisk / uninstallZygisk）。
 */
public final class ToolsPage extends Page {

    private LinearLayout checkBox, snapBox, overviewCard;
    private TextView baselineInfo, snapTitle;
    private LinearLayout snapCard;
    /** 快照区块是否展开：默认收起。 */
    private boolean snapOpen;

    public ToolsPage(MainActivity act) { super(act); }

    @Override
    protected void build() {
        // ---------------- 1. 三路一致性 ----------------
        sectionFirst("三路一致性自检");
        LinearLayout c = addCard();
        c.addView(Ui.body(act, "系统属性 / Java Build / 目标值 并排对比", p.onSurface));
        hint(c, "「系统属性」是 getprop 看到的，「Java Build」是应用通过 android.os.Build 读到的。"
                + "两者不一致，说明有的检测点还没覆盖到。");
        TextView run = tonal("运行自检");
        run.setOnClickListener(v -> runCheck());
        addWide(c, run);
        checkBox = Ui.col(act);
        c.addView(checkBox);
        Ui.margins(checkBox, 0, Ui.S4, 0, 0);

        // ---------------- 2. 快照（可折叠，默认收起）----------------
        // 标题自己当开关：与 section() 同样式加到 body 上，点它展开 / 收起整块。
        snapTitle = Ui.headline(act, "", p.onSurface);
        body.addView(snapTitle);
        snapTitle.setOnClickListener(v -> toggleSnaps());
        renderSnapTitle();

        snapCard = addCard();
        snapCard.setVisibility(View.GONE);   // 默认折叠

        TextView snap = tonal("立即存一份快照");
        TextView load = tonal("刷新列表");
        snap.setOnClickListener(v -> snapshotNow());
        load.setOnClickListener(v -> loadSnaps());
        snapCard.addView(buttonRow(snap, load));
        Ui.pressable(snap);
        Ui.pressable(load);
        hint(snapCard, "快照保存在 " + Scripts.SNAP_DIR + "，记录被改动的键在改动前的值；回滚只写回这些键。"
                + "每一份都可以单独删除。");
        snapBox = Ui.col(act);
        snapCard.addView(snapBox);
        Ui.margins(snapBox, 0, Ui.S4, 0, 0);

        // ---------------- 3. 导出 ----------------
        section("导出");
        LinearLayout ex = addCard();
        TextView sh = outlined("导出独立脚本到 /sdcard/ModelSpoof");
        sh.setOnClickListener(v -> exportScripts());
        addWide(ex, sh);
        hint(ex, "导出后即使卸载本应用，也能用 su -c 'sh /sdcard/ModelSpoof/apply.sh' 手动应用或还原。");

        // ---------------- 4. 危险区 ----------------
        section("危险区");
        LinearLayout dg = addCard();
        TextView fr = Ui.button(act, "恢复出厂 · 还原机型并卸载模块", Ui.Btn.OUTLINED, p);
        fr.setTextColor(p.error);
        fr.setBackground(Ui.bg(0x00000000, Ui.R_BTN, p.error, 1));
        fr.setOnClickListener(v -> factoryReset());
        addWide(dg, fr);
        hint(dg, "会回滚到最近一次快照、卸载开机自动生效的模块，并删除 /data/adb/devicetoolbox 目录。操作前会再确认一次。");

        // ---------------- 5. 真值基线 ----------------
        section("真值基线");
        LinearLayout bl = addCard();
        bl.addView(Ui.body(act, "还原时的「原厂值」从哪来", p.onSurface));
        hint(bl, "只读的 build.prop 文件并不总是可靠 —— 有些机型（如 OPPO / 一加）的 "
                + "ro.product.model 真值来自厂商 init，文件里写的是基础镜像值。\n\n"
                + "所以基线取「本工具第一次运行时的状态」—— 那时还没伪装过，读到的一定是真值。"
                + "基线只写一次，之后所有还原都从它取。\n\n"
                + "如果你现在确认设备处于原厂状态，可以用下面的按钮重建。");
        baselineInfo = Ui.paragraph(act, "", p.onSurfaceVariant);
        bl.addView(baselineInfo);
        Ui.margins(baselineInfo, 0, Ui.S3, 0, 0);
        TextView rb = outlined("用当前状态重建真值基线");
        rb.setOnClickListener(v -> confirm("重建真值基线",
                "把设备「现在」的属性当作原厂值记下来，之后还原都以它为基准。\n\n"
                        + "只在设备确实处于原厂状态时使用 —— 如果现在正伪装着，会把伪装值当成原厂值。",
                "重建", () -> Task.bg(() -> {
                    Engine.Op op = Engine.resetBaseline(act);
                    Task.ui(() -> {
                        info(op.ok ? "已重建" : "失败", op.message);
                        loadBaselineInfo();
                    });
                }, null)));
        addWide(bl, rb);

        // ---------------- 6. 设备信息总览（只读，放最后）----------------
        section("设备信息总览");
        overviewCard = addCard();
        TextView rf = textBtn("刷新");
        rf.setOnClickListener(v -> loadOverview());
        overviewCard.addView(rf, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(44)));
        Ui.margins(rf, 0, Ui.S3, 0, 0);
    }

    @Override
    public void onShow() {
        loadOverview();
        loadBaselineInfo();
        if (snapOpen) loadSnaps();
    }

    private void loadBaselineInfo() {
        Task.bg(() -> {
            final java.util.Map<String, String> b = Engine.baseline(act);
            final String model = b.get("ro.product.model");
            final String brand = b.get("ro.product.brand");
            final String fp = b.get("ro.build.fingerprint");
            Task.ui(() -> {
                if (baselineInfo == null) return;
                if (b.isEmpty()) {
                    baselineInfo.setText("还没有基线。");
                } else {
                    baselineInfo.setText("基线记录 " + b.size() + " 项\n原厂机型："
                            + (brand == null ? "?" : brand) + " " + (model == null ? "?" : model)
                            + "\n" + (fp == null ? "" : fp));
                }
            });
        }, null);
    }

    // ------------------------------------------------------------ 自检

    private void runCheck() {
        final Target t = store.target();
        final int lv = store.level();
        checkBox.removeAllViews();
        checkBox.addView(Ui.paragraph(act, "检测中…", p.onSurfaceVariant));
        Task.bg(() -> {
            final List<Engine.Row> rows = Engine.selfCheck(t == null ? new Target().normalize() : t.normalize(), lv);
            Task.ui(() -> {
                checkBox.removeAllViews();
                if (t == null) {
                    checkBox.addView(Ui.paragraph(act,
                            "还没选目标机型，下面只对比系统属性与 Java Build。", p.onSurfaceVariant));
                }
                int bad = 0, good = 0;
                for (Engine.Row r : rows) {
                    LinearLayout box = Ui.col(act);
                    Ui.pad(box, 0, Ui.S3, 0, Ui.S2);
                    box.addView(Ui.text(act, shortKey(r.key), 11, p.onSurfaceVariant, Ui.W_REGULAR));

                    boolean same = r.prop.equals(r.build);
                    boolean matchTarget = r.expect == null || r.expect.isEmpty() || r.prop.equals(r.expect);
                    box.addView(line("系统属性", r.prop, same ? p.onSurface : p.warning));
                    box.addView(line("Java Build", r.build, same ? p.onSurface : p.warning));
                    if (r.expect != null && !r.expect.isEmpty()) {
                        box.addView(line("目标值", r.expect, matchTarget ? p.success : p.primary));
                    }
                    if (same && matchTarget) good++; else bad++;
                    checkBox.addView(box);
                }
                TextView sum = Ui.text(act, bad == 0 ? "全部一致（" + good + " 项）" : bad + " 项需要留意",
                        13, bad == 0 ? p.success : p.warning, Ui.W_BOLD);
                checkBox.addView(sum);
                Ui.pad(sum, 0, Ui.S4, 0, 0);
            });
        }, null);
    }

    private View line(String label, String value, int color) {
        LinearLayout row = Ui.row(act);
        TextView l = Ui.text(act, label, 11, p.onSurfaceVariant, Ui.W_REGULAR);
        row.addView(l, Ui.lp(Ui.dp(74), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView v = Ui.text(act, value == null || value.isEmpty() ? "（空）" : value, 11, color, Ui.W_REGULAR);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(v, Ui.lpw(1f));
        return row;
    }

    private static String shortKey(String k) {
        return k.replace("ro.", "").replace("build.", "");
    }

    // ------------------------------------------------------------ 总览

    private void loadOverview() {
        Task.bg(() -> {
            final Map<String, String> ov = Engine.overview();
            Task.ui(() -> {
                if (overviewCard == null) return;
                while (overviewCard.getChildCount() > 1) overviewCard.removeViewAt(0);
                int at = 0;
                for (Map.Entry<String, String> e : ov.entrySet()) {
                    String v = e.getValue() == null || e.getValue().isEmpty() ? "（空）" : e.getValue();
                    if (v.length() > 26) {
                        TextView k = Ui.label(act, e.getKey(), p.onSurfaceVariant);
                        overviewCard.addView(k, at++);
                        Ui.pad(k, Ui.S1, Ui.S3, 0, 0);
                        TextView t = Ui.text(act, v, 12, p.onSurface, Ui.W_REGULAR);
                        t.setLineSpacing(Ui.dp(3), 1f);
                        overviewCard.addView(t, at++);
                    } else {
                        LinearLayout row = Ui.row(act);
                        row.addView(Ui.body(act, e.getKey(), p.onSurfaceVariant), Ui.lpw(1f));
                        row.addView(Ui.text(act, v, 14, p.onSurface, Ui.W_REGULAR));
                        overviewCard.addView(row, at++);
                        Ui.pad(row, Ui.S1, Ui.S3, Ui.S1, 0);
                    }
                }
            });
        }, null);
    }

    // ------------------------------------------------------------ 快照

    private void toggleSnaps() {
        snapOpen = !snapOpen;
        renderSnapTitle();
        snapCard.setVisibility(snapOpen ? View.VISIBLE : View.GONE);
        if (snapOpen) loadSnaps();
    }

    private void renderSnapTitle() {
        snapTitle.setText((snapOpen ? "▾ " : "▸ ") + "快照与回滚");
    }

    private void snapshotNow() {
        final Target t = store.target() == null ? new Target().normalize() : store.target().normalize();
        final int lv = store.level();
        Task.bg(() -> {
            Engine.Op op = Engine.snapshotNow(act, t, lv);
            Task.ui(() -> {
                info(op.ok ? "快照已保存" : "快照失败",
                        op.ok ? "共记录 " + op.count + " 个键的当前值。" : op.message);
                loadSnaps();
            });
        }, null);
    }

    private void loadSnaps() {
        Task.bg(() -> {
            final List<Engine.Snap> list = Engine.snapshots();
            Task.ui(() -> {
                if (snapBox == null) return;
                snapBox.removeAllViews();
                if (list.isEmpty()) {
                    snapBox.addView(Ui.paragraph(act, "还没有快照。", p.onSurfaceVariant));
                    return;
                }
                SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
                for (final Engine.Snap s : list) {
                    LinearLayout box = Ui.tinted(act, p);
                    snapBox.addView(box, Ui.wrap());
                    Ui.margins(box, 0, Ui.S2, 0, 0);
                    box.addView(Ui.text(act, s.title.isEmpty() ? "（未知机型）" : s.title,
                            14, p.onSurface, Ui.W_BOLD));
                    box.addView(Ui.text(act, s.label() + " · "
                            + (s.time > 0 ? fmt.format(new Date(s.time)) : s.dir),
                            11, p.onSurfaceVariant, Ui.W_REGULAR));

                    TextView rb = outlined("回滚到这一份");
                    rb.setOnClickListener(v -> confirm("回滚",
                            "把 " + s.label() + " 记录的那些属性写回快照时的值？", "回滚",
                            () -> Task.bg(() -> {
                                Engine.Op op = Engine.rollback(act, s);
                                Task.ui(() -> {
                                    info(op.ok ? "已回滚" : "回滚失败", op.message);
                                    Device.invalidate();
                                    act.rebuildPages();
                                    onShow();
                                });
                            }, null)));
                    TextView del = textBtn("删除");
                    del.setTextColor(p.error);
                    del.setOnClickListener(v -> deleteSnap(s));

                    LinearLayout acts = buttonRow(rb, del);
                    box.addView(acts, Ui.wrap());
                    Ui.margins(acts, 0, Ui.S3, 0, 0);
                    Ui.pressable(rb);
                    Ui.pressable(del);
                }
            });
        }, null);
    }

    /** 删除一份快照（连同它在 /data/adb 下的目录）。 */
    private void deleteSnap(final Engine.Snap s) {
        // 目录名来自 ls 的输出：不含路径分隔符才允许动手，免得删到快照目录以外。
        if (s.dir.isEmpty() || s.dir.indexOf('/') >= 0 || s.dir.contains("..")) {
            info("删除失败", "快照目录名不可用：" + s.dir);
            return;
        }
        confirm("删除快照", "删除「" + s.label() + "」这一份？删除后不能再回滚到它。", "删除",
                () -> Task.bg(() -> {
                    Sh.Result r = Runner.root(act, "snap-del.sh",
                            "rm -rf " + Sh.q(Scripts.SNAP_DIR + "/" + s.dir) + "\necho DEL_OK\n", 20000);
                    final boolean ok = r.ok() && r.out.contains("DEL_OK");
                    final String detail = r.text().trim();
                    Task.ui(() -> {
                        if (ok) Task.toast(act, "已删除快照");
                        else info("删除失败", detail);
                        loadSnaps();
                    });
                }, null));
    }

    // ------------------------------------------------------------ 导出

    private void exportScripts() {
        final Target t = store.target();
        if (t == null) {
            info("没有目标机型", "先选一台机器再导出。");
            return;
        }
        final int lv = store.level();
        t.normalize();
        Task.bg(() -> {
            String apply = Runner.writeOnly(act, "standalone-apply.sh", Scripts.standaloneApply(t, lv));
            String script = "mkdir -p /sdcard/ModelSpoof\n"
                    + "cp " + Sh.q(apply) + " /sdcard/ModelSpoof/apply.sh\n"
                    + "chmod 755 /sdcard/ModelSpoof/apply.sh\n"
                    + "ls -l /sdcard/ModelSpoof\n";
            Sh.Result r = Runner.root(act, "export.sh", script, 25000);
            Task.ui(() -> info(r.ok() ? "已导出" : "导出失败",
                    r.ok() ? "脚本在 /sdcard/ModelSpoof/\n\n" + r.out.trim() : r.text().trim()));
        }, null);
    }

    // ------------------------------------------------------------ 出厂

    private void factoryReset() {
        confirm("恢复出厂",
                "将执行：\n1. 回滚到最近一次快照\n2. 卸载开机自动生效的模块\n3. 删除 /data/adb/devicetoolbox\n\n"
                        + "此操作会改动系统属性，请确认你了解后果。",
                "确认恢复", () -> confirm("再确认一次",
                        "确定要恢复出厂吗？这是本工具里影响最大的操作。", "确定",
                        () -> Task.bg(() -> {
                            Engine.Op op = Engine.factoryReset(act);
                            Task.ui(() -> {
                                info(op.ok ? "已完成" : "失败", op.message);
                                Device.invalidate();
                                act.rebuildPages();
                                onShow();
                            });
                        }, null)));
    }

    // ------------------------------------------------------------ Zygisk（首页调用）

    /** Zygisk 模块是否已安装 —— 首页的状态芯片与状态行共用这一个判断。 */
    public static boolean zygiskInstalled() {
        return Zygisk.installed();
    }

    /** 「应用隐藏」的立即生效动作：把勾选的应用与目标机型写进模块配置（core 侧同一个 Zygisk.sync）。 */
    public static Engine.Op syncAppHide(MainActivity act) {
        return Zygisk.sync(act);
    }

    /**
     * 停掉全部逐应用隐藏：清空勾选，并写出一份空配置 —— **两件事必须一起成立**。
     * 「还原原始」用它 —— 否则被隐藏的应用还会继续读到假机型（要重启该应用才读回真值）。
     *
     * 这里走 {@link Zygisk#applyList}：先落列表、再按列表写配置，配置写失败就把列表**回滚**。
     * 旧写法是「先清列表、再 sync」，sync 一失败就留下「列表已空、配置里还勾着一堆应用」——
     * 正是缺陷①那类不一致的一半（应用仍会被注入假机型，而界面显示已经取消了）。
     */
    public static Engine.Op clearAppHide(MainActivity act) {
        return Zygisk.applyList(act, java.util.Collections.<String>emptyList());
    }

    /** 卸载「开机自动生效」的模块；没装返回 null。 */
    public static Engine.Op removeBootModule(MainActivity act) {
        if (!Engine.moduleInstalled()) return null;
        return Engine.uninstallModule(act);
    }

    /** 安装 Zygisk 模块（首页「安装」）。 */
    public static void installZygisk(final Page host) {
        confirmInstall(host, false);
    }

    /** 更新重装：同一套安装逻辑再跑一遍，覆盖安装（首页「更新重装」）。 */
    public static void reinstallZygisk(final Page host) {
        confirmInstall(host, true);
    }

    private static void confirmInstall(final Page host, final boolean again) {
        host.confirm(again ? "更新重装 Zygisk 模块" : "安装 Zygisk 模块",
                "会把模块（module.prop + zygisk/arm64-v8a.so）装到 " + Zygisk.MODULE_DIR + "，"
                        + "把当前勾选的 " + Zygisk.appCount(host.act) + " 个应用的隐藏配置写进 "
                        + Zygisk.CONF + "，并把开机自动生效的模块一并装好。\n\n"
                        + "装完要重启一次，Zygisk 才会加载它。",
                again ? "重装" : "安装", () -> Task.bg(() -> {
                    final Engine.Op op = Zygisk.install(host.act);
                    final Target t = host.act.store().target();
                    final Engine.Op boot = op.ok && t != null
                            ? Engine.installModule(host.act, t.normalize(), host.act.store().level())
                            : null;
                    Task.ui(() -> {
                        StringBuilder sb = new StringBuilder(op.message);
                        if (boot != null && boot.ok) {
                            sb.append("\n\n开机自动生效的模块：已装好，重启后不用再手动点。");
                        } else if (boot != null) {
                            sb.append("\n\n开机自动生效的模块没装上：").append(boot.message);
                        } else if (op.ok) {
                            sb.append("\n\n还没选机型，开机自动生效的模块没装 —— 选好机型后再点一次「更新重装」。");
                        }
                        host.info(op.ok ? "已安装" : "安装失败", sb.toString());
                        if (op.ok) host.onShow();
                    });
                }, null));
    }

    /** 卸载 Zygisk 模块（首页「卸载」）。 */
    public static void uninstallZygisk(final Page host) {
        host.confirm("卸载 Zygisk 模块",
                "删除 " + Zygisk.MODULE_DIR + "，重启后 Zygisk 不再加载它。",
                "卸载", () -> Task.bg(() -> {
                    final Engine.Op op = Zygisk.uninstall(host.act);
                    Task.ui(() -> {
                        host.info(op.ok ? "已卸载" : "卸载失败", op.message);
                        if (op.ok) host.onShow();
                    });
                }, null));
    }
}
