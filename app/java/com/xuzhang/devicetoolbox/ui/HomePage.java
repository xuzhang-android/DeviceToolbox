package com.xuzhang.devicetoolbox.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.xuzhang.devicetoolbox.MainActivity;
import com.xuzhang.devicetoolbox.core.AppSpoof;
import com.xuzhang.devicetoolbox.core.Device;
import com.xuzhang.devicetoolbox.core.Engine;
import com.xuzhang.devicetoolbox.core.Library;
import com.xuzhang.devicetoolbox.core.Props;
import com.xuzhang.devicetoolbox.core.Sh;
import com.xuzhang.devicetoolbox.core.Target;
import com.xuzhang.devicetoolbox.core.Zygisk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 首页：先给结论（这是什么设备、环境是否就绪），再给动作。
 *
 * 刻意不做成「一摞同尺寸卡片」：身份信息用一张主卡承载，
 * 环境状态收成一行芯片，动作只有一组主次按钮。
 *
 * 顺序按「先决条件在前」排：当前设备 → Zygisk（必须安装，没有它应用隐藏不生效）→
 * 目标伪装（机型 + 应用隐藏 / 全局隐藏 / 还原原始）。Zygisk 只留状态行与四个动作，不放解释长文。
 *
 * 「应用隐藏」的选择面板是公共静态入口 {@link #pickApps(Page)}：任何页面都能就地调起（伪装页在用）。
 */
public final class HomePage extends Page {

    private TextView heroModel, heroMeta, chipRoot, chipReset, chipLsp, chipModule;
    private TextView targetName, targetMeta, levelLine;
    private TextView applyBtn, restoreBtn;
    private TextView zyStatus;

    public HomePage(MainActivity act) { super(act); }

    @Override
    protected void build() {
        // ---------------- 1. 主卡：当前设备 + 环境芯片 ----------------
        LinearLayout hero = addCard();
        Ui.margins(hero, 0, 0, 0, 0);

        hero.addView(Ui.label(act, "当前设备", p.onSurfaceVariant));

        heroModel = Ui.display(act, "读取中…", p.onSurface);
        hero.addView(heroModel);
        Ui.margins(heroModel, 0, Ui.S2, 0, 0);

        heroMeta = Ui.paragraph(act, "", p.onSurfaceVariant);
        hero.addView(heroMeta);
        Ui.margins(heroMeta, 0, Ui.S2, 0, 0);

        View d = Ui.divider(act, p);
        hero.addView(d, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.7f))));
        Ui.margins(d, 0, Ui.S5, 0, 0);

        // 状态芯片排成 2×2 —— 四个挤一行时最后一个会被压成竖条
        LinearLayout rowA = Ui.row(act);
        LinearLayout rowB = Ui.row(act);
        chipRoot = chip(rowA, true);
        chipReset = chip(rowA, false);
        chipLsp = chip(rowB, true);
        chipModule = chip(rowB, false);
        hero.addView(rowA, Ui.wrap());
        Ui.margins(rowA, 0, Ui.S4, 0, 0);
        hero.addView(rowB, Ui.wrap());
        Ui.margins(rowB, 0, Ui.S2, 0, 0);

        // ---------------- 2. Zygisk（放在目标伪装前面：它是应用隐藏的先决条件）----------------
        section("Zygisk 伪装（必须安装）");
        LinearLayout zy = addCard();
        zyStatus = Ui.body(act, "Zygisk：检测中…", p.onSurfaceVariant);
        zy.addView(zyStatus);

        TextView zyTest = outlined("检测");
        TextView zyInstall = tonal("安装");
        TextView zyRemove = outlined("卸载");
        TextView zyAgain = outlined("更新重装");
        zyTest.setOnClickListener(v -> detectZygisk());
        zyInstall.setOnClickListener(v -> ToolsPage.installZygisk(this));
        zyRemove.setOnClickListener(v -> ToolsPage.uninstallZygisk(this));
        zyAgain.setOnClickListener(v -> ToolsPage.reinstallZygisk(this));
        zy.addView(buttonRow(zyTest, zyInstall));
        zy.addView(buttonRow(zyRemove, zyAgain));
        Ui.pressable(zyTest);
        Ui.pressable(zyInstall);
        Ui.pressable(zyRemove);
        Ui.pressable(zyAgain);

        // ---------------- 3. 目标伪装 ----------------
        section("目标伪装");
        LinearLayout tc = addCard();

        targetName = Ui.headline(act, "未选择机型", p.onSurface);
        tc.addView(targetName);

        targetMeta = Ui.paragraph(act, "", p.onSurfaceVariant);
        tc.addView(targetMeta);
        Ui.margins(targetMeta, 0, Ui.S2, 0, 0);

        levelLine = Ui.body(act, "", p.onSurfaceVariant);
        tc.addView(levelLine);
        Ui.margins(levelLine, 0, Ui.S3, 0, 0);

        // 选机型放在摘要紧下面：目标没定，后面的隐藏都没有意义。
        TextView pick = outlined("去机型库挑选或生成");
        pick.setOnClickListener(v -> act.goTab(2));
        addWide(tc, pick);

        // 主路径 = 应用隐藏（Zygisk 逐应用注入，与公开实现里的主流做法一致）：只改被勾选的应用进程，
        // 在那个进程里 Java 与 native 一起改、天然一致，**不需要重启**。
        applyBtn = filled("应用隐藏");
        restoreBtn = outlined("全局隐藏");
        applyBtn.setOnClickListener(v -> doPerApp());
        restoreBtn.setOnClickListener(v -> doApply());
        tc.addView(buttonRow(applyBtn, restoreBtn));
        Ui.pressable(applyBtn);
        Ui.pressable(restoreBtn);

        // 还原是有依托的真按钮（原来是裸文字，用户会以为它不可点）。
        TextView undo = outlined("还原原始");
        undo.setOnClickListener(v -> doRestore());
        tc.addView(undo, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(Ui.H_CONTROL)));
        Ui.margins(undo, 0, Ui.S3, 0, 0);
        Ui.pressable(undo);

        TextView random = textBtn("随机换一个机型");
        random.setOnClickListener(v -> doRandom());
        tc.addView(random, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(Ui.H_CONTROL)));
        Ui.margins(random, 0, Ui.S3, 0, 0);
        Ui.pressable(random);
    }

    /** 状态芯片：等宽两列，单行显示。 */
    private TextView chip(LinearLayout parent, boolean left) {
        TextView t = Ui.text(act, "● 检测中", 12, p.onSurfaceVariant, Ui.W_REGULAR);
        t.setBackground(Ui.pill(p.surfaceContainerHigh));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(Ui.S3), 0, Ui.dp(Ui.S3), 0);
        LinearLayout.LayoutParams lp = Ui.lpw(1f, Ui.dp(36));
        if (left) lp.rightMargin = Ui.dp(Ui.S2);
        parent.addView(t, lp);
        return t;
    }

    private void setChip(TextView t, String label, boolean ok) {
        t.setText("● " + label);
        t.setTextColor(ok ? p.success : p.error);
        t.setBackground(Ui.pill(p.surfaceContainerHigh));
    }

    @Override
    public void onShow() {
        Task.bg(() -> {
            Sh.Result probe = Sh.root("id", 12000);
            final String diag = "code=" + probe.code + (probe.timeout ? " 超时" : "")
                    + " · out=" + probe.out.trim() + " · err=" + probe.err.trim();

            boolean root = Device.hasRoot();
            String rp = root ? Device.resetpropPath() : "";
            String impl = root ? Device.rootImpl() : "";
            boolean lsp = Device.hasLsposed();
            final boolean zy = ToolsPage.zygiskInstalled();

            // 本机机型码 → 机型库里的俗名（只写俗名，如「一加 Ace 6」）；库里查不到才回退机型码
            final String host = hostName(Device.prop("ro.product.model"));

            final String meta = "Android " + Device.currentRelease() + " · SDK " + Device.currentSdk()
                    + " · 补丁 " + Device.currentPatch();
            final String sub = "代号 " + Device.prop("ro.product.device")
                    + " · 主板 " + Device.prop("ro.product.board");

            Task.ui(() -> {
                heroModel.setText(host);
                heroMeta.setText(meta + "\n" + sub);
                heroMeta.setOnClickListener(v -> info("su 自检原始返回", diag));

                setChip(chipRoot, root ? "Root · " + impl : "Root 未获取", root);
                setChip(chipReset, rp.isEmpty() ? "resetprop 缺失" : "resetprop 就绪", !rp.isEmpty());
                setChip(chipLsp, lsp ? "LSPosed 在运行" : "无 LSPosed", lsp);
                applyZygisk(zy);

                Target t = store.target();
                if (t == null) {
                    targetName.setText("未选择机型");
                    targetName.setTextColor(p.onSurfaceVariant);
                    targetMeta.setText("去「机型」页挑一台，或者用生成器造一个。");
                    levelLine.setText("");
                } else {
                    t.normalize();
                    targetName.setText(Library.displayName(t));
                    targetName.setTextColor(p.onSurface);
                    targetMeta.setText("Android " + t.release + " · SDK " + t.sdk()
                            + " · 补丁 " + t.patch + (t.soc.isEmpty() ? "" : "\n" + t.soc));
                    levelLine.setText("已隐藏");
                }
            });
        }, null);
    }

    /** 当前设备的显示名：只给俗名（「一加 Ace 6」），库里查不到俗名才回退机型码。 */
    private static String hostName(String model) {
        if (model == null || model.isEmpty()) return "";
        Target known = Library.byModel(model);
        if (known == null) return model;
        String name = ModelPage.cnName(known);
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(model) || name.contains(model)) {
            return model;
        }
        return name;
    }

    // ------------------------------------------------------------ Zygisk 状态

    private void applyZygisk(boolean on) {
        setChip(chipModule, on ? "Zygisk 已装" : "Zygisk 未装", on);
        zyStatus.setText(on ? "Zygisk：已装" : "Zygisk：未装 · 必须安装，否则应用隐藏不生效");
        zyStatus.setTextColor(on ? p.onSurfaceVariant : p.error);
    }

    /** 「检测」：读回 Zygisk 模块现状。 */
    private void detectZygisk() {
        zyStatus.setTextColor(p.onSurfaceVariant);
        zyStatus.setText("Zygisk：检测中…");
        Task.bg(() -> {
            final boolean on = ToolsPage.zygiskInstalled();
            Task.ui(() -> applyZygisk(on));
        }, null);
    }

    // ------------------------------------------------------------ 操作

    /** 两条隐藏路径都先要有一个目标机型：没有就当场说明，不往下走。 */
    private static boolean hasTarget(Page host) {
        if (host.store.target() != null) return true;
        host.info("还没选机型", "先去「机型」页挑一台，或者用生成器造一个。");
        return false;
    }

    private void doApply() {
        if (!hasTarget(this)) return;
        final Target t = store.target();
        final int level = store.level();
        t.normalize();
        String err = Engine.validate(t, level);
        if (err != null) { info("还不能开始", err); return; }
        confirm("确认全局隐藏（会被检测，不推荐）",
                "目标：" + Library.displayName(t) + "\n范围：深度档（" + Props.count(level) + " 条属性）\n\n"
                        + "⚠ 这条路改的是**整机内存属性区**，而 zygote 在开机时就把 android.os.Build 的"
                        + "静态字段读死了 —— 运行期改追不上它，所以任何检测 App 都能同时看到"
                        + "「真指纹」和「假指纹」，必然露馅。\n\n"
                        + "**对检测类应用（如 Momo）建议只用「应用隐藏」，不要叠加全局隐藏** —— "
                        + "全局隐藏改不动已经在运行的进程里缓存的值，同一个应用里两套值并列，"
                        + "本身就是可疑迹象。\n\n"
                        + "**要隐蔽请用「应用隐藏」**（首页那个按钮）："
                        + "只注入你勾选的应用，在那个进程里 Java 与 native 一起改，"
                        + "不需要重启，且没勾的应用完全不受影响 —— 这也是公开实现里常见的做法。\n\n"
                        + "改之前会自动存快照，出问题可以用「还原原始」回退。",
                "仍要全局隐藏", () -> Task.bg(() -> {
                    final Engine.Op op = Engine.apply(act, t, level, true);
                    // ★ 全局换了机型之后，把逐应用隐藏的目标也改成同一个。
                    //   否则被隐藏的应用（Momo 这种）会同时拿到「旧目标的 Java 层」与
                    //   「新目标的 native 层」，两个指纹互相打架，一眼就是可疑迹象。
                    final List<String> hidden = AppSpoof.enabled(act);
                    Engine.Op hide = null;
                    if (op.ok && !hidden.isEmpty()) {
                        for (String pkg : hidden) AppSpoof.setTargetFor(act, pkg, t);
                        hide = ToolsPage.syncAppHide(act);
                    }
                    final Engine.Op hideOp = hide;
                    final int hideN = hidden.size();
                    Task.ui(() -> {
                        StringBuilder sb = new StringBuilder(op.message);
                        if (hideOp != null) {
                            sb.append("\n\n已把 ").append(hideN).append(" 个已隐藏应用的目标同步为「")
                                    .append(Library.displayName(t)).append("」");
                            sb.append(hideOp.ok ? "（配置已写入）" : "，但写入失败：" + hideOp.message);
                            sb.append("。\n已经开着的目标应用要重新打开一次才会读到新值。");
                        }
                        info(op.ok ? "已完成（但会被检测）" : "未完成", sb.toString());
                        act.rebuildPages();
                        onShow();
                    });
                }, null));
    }

    /** 主路径：应用隐藏 —— 直接开选择面板（面板自己会先检查有没有选机型）。 */
    private void doPerApp() {
        pickApps(this);
    }

    /**
     * 还原原始：属性写回真值基线，同时停掉两个还在持续伪装的机制 ——
     * 逐应用隐藏的配置、开机自动生效的模块。否则「还原」只是当下有效。
     */
    private void doRestore() {
        confirm("还原原始机型",
                "把本工具改过的属性全部写回伪装之前的值，并停掉逐应用隐藏与开机自动生效模块"
                        + "（之后重启也不会再被伪装）。\n\n"
                        + "正在运行、且被隐藏过的应用，要重启（或杀掉）一次才会读回真值。",
                "还原", () -> Task.bg(() -> {
                    final Engine.Op op = Engine.restoreLast(act);
                    final Engine.Op hide = ToolsPage.clearAppHide(act);
                    final Engine.Op boot = ToolsPage.removeBootModule(act);
                    Task.ui(() -> {
                        StringBuilder sb = new StringBuilder(op.message);
                        sb.append("\n\n逐应用隐藏：").append(hide != null && hide.ok
                                ? "已取消并清空配置" : "取消失败（" + (hide == null ? "未知" : hide.message) + "）");
                        if (boot != null) {
                            sb.append("\n\n开机自动生效的模块：").append(boot.ok ? "已卸载" : "卸载失败（" + boot.message + "）");
                        }
                        info(op.ok ? "已还原" : "还原失败", sb.toString());
                        act.rebuildPages();
                        onShow();
                    });
                }, null));
    }

    private void doRandom() {
        java.util.List<Target> all = Library.all();
        if (all.isEmpty()) return;
        Target pick = all.get((int) (Math.random() * all.size()));
        store.setTarget(pick);
        act.rebuildPages();
        onShow();
        Task.toast(act, "已选：" + pick.title());
    }

    // ------------------------------------------------------------ 应用隐藏：选择面板 + 立即隐藏

    /** 面板里的一个应用。 */
    private static final class AppEntry {
        String label, pkg;
        Drawable icon;
        boolean checked;
        /** 它实际生效的目标机型（没单独设过就回落到全局目标）；管理卡片用。 */
        Target target;
        /** 目标机型能不能写「构建身份族」：完整伪装 / 只改型号信息。 */
        boolean full;
        /** true = 这是「已隐藏的应用」的卡片项（排在全量应用行之前），不是勾选行。 */
        boolean card;
        /** true = 空态说明那一行（卡片区没有应用时占位）。 */
        boolean hint;
    }

    /**
     * 就地弹出「应用隐藏」选择面板：枚举应用 + 搜索 + 勾选 + 落盘 + Zygisk.sync，任何页面可调。
     */
    public static void pickApps(final Page host) {
        if (!hasTarget(host)) return;
        Task.bg(() -> {
            final PackageManager pm = host.act.getPackageManager();
            final List<String> hidden = AppSpoof.enabled(host.act);
            final List<AppEntry> entries = new ArrayList<>();
            try {
                Set<String> seen = new HashSet<>();
                Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                    if (ri.activityInfo == null || ri.activityInfo.applicationInfo == null) continue;
                    ApplicationInfo ai = ri.activityInfo.applicationInfo;
                    if (seen.add(ai.packageName)) collect(host, entries, pm, ai, hidden);
                }
            } catch (Throwable ignored) { }
            if (entries.isEmpty()) {
                try {
                    for (ApplicationInfo ai : pm.getInstalledApplications(0)) collect(host, entries, pm, ai, hidden);
                } catch (Throwable ignored) { }
            }
            Collections.sort(entries, (a, b) -> a.label.compareToIgnoreCase(b.label));

            Task.ui(() -> {
                if (entries.isEmpty()) {
                    host.info("没有可选的应用",
                            "系统没有把应用列表给到本应用。\n\n"
                                    + "可在「设置 → 应用 → 改机型工具箱 → 权限」里确认「读取应用列表」已允许。");
                    return;
                }
                showPicker(host, entries);
            });
        }, null);
    }

    private static void collect(Page host, List<AppEntry> out, PackageManager pm,
                                ApplicationInfo ai, List<String> hidden) {
        if (ai == null || ai.packageName == null) return;
        if (ai.packageName.equals(host.act.getPackageName())) return;
        AppEntry e = new AppEntry();
        e.pkg = ai.packageName;
        e.label = String.valueOf(pm.getApplicationLabel(ai));
        try { e.icon = pm.getApplicationIcon(ai); } catch (Throwable ignored) { }
        e.checked = hidden.contains(ai.packageName);
        out.add(e);
    }

    private static void showPicker(final Page host, final List<AppEntry> entries) {
        final MainActivity act = host.act;
        final Palette p = host.p;
        // 内边距交给弹窗卡片（Ui.Sheet），这里只排内容
        final LinearLayout box = Ui.col(act);

        final EditText search = new EditText(act);
        search.setHint("搜索应用名或包名");
        search.setTextSize(15);
        search.setTextColor(p.onSurface);
        search.setHintTextColor(p.onSurfaceVariant);
        search.setBackground(Ui.bg(p.surfaceContainerHigh, Ui.R_INPUT));
        Ui.pad(search, Ui.S4, 0, Ui.S4, 0);
        search.setSingleLine(true);
        box.addView(search, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(Ui.H_CONTROL)));

        // 明确的取消入口：一次清掉所有已勾选的应用（在勾选列表上方）
        final TextView clearAll = host.textBtn("取消全部隐藏");
        clearAll.setTextColor(p.error);
        box.addView(clearAll, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(44)));
        Ui.margins(clearAll, 0, Ui.S2, 0, 0);
        Ui.pressable(clearAll);

        // ★ 列表数据 =「已隐藏的应用」的卡片（展开时，排在最前）+ 过滤后的全量应用行。
        //   两者共用**同一个 ListView** —— 弹窗里只有这一个滚动容器（外层不再套 ScrollView）。
        final List<AppEntry> shown = new ArrayList<>();
        final String[] query = {""};
        final HiddenBlock[] blockRef = new HiddenBlock[1];
        final BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public int getViewTypeCount() { return 2; }
            public int getItemViewType(int i) { return shown.get(i).card ? 1 : 0; }

            public View getView(int pos, View reuse, ViewGroup parent) {
                final AppEntry e = shown.get(pos);
                if (e.card) return hiddenCardView(host, blockRef[0], e);   // 卡片：整块自绘，不复用

                LinearLayout row;
                ImageView icon;
                TextView name, pkgView;
                CheckBox cb;
                if (reuse instanceof LinearLayout) {
                    row = (LinearLayout) reuse;
                    Object[] held = (Object[]) row.getTag();
                    icon = (ImageView) held[0];
                    name = (TextView) held[1];
                    pkgView = (TextView) held[2];
                    cb = (CheckBox) held[3];
                } else {
                    row = Ui.row(act);
                    Ui.pad(row, Ui.S2, Ui.S2, Ui.S2, Ui.S2);
                    icon = new ImageView(act);
                    row.addView(icon, Ui.lp(Ui.dp(38), Ui.dp(38)));
                    Ui.margins(icon, 0, 0, Ui.S3, 0);
                    LinearLayout info = Ui.col(act);
                    name = Ui.text(act, "", 15, p.onSurface, Ui.W_BOLD);
                    info.addView(name);
                    pkgView = Ui.text(act, "", 11, p.onSurfaceVariant, Ui.W_REGULAR);
                    info.addView(pkgView);
                    row.addView(info, Ui.lpw(1f));
                    cb = new CheckBox(act);
                    cb.setClickable(false);
                    cb.setFocusable(false);
                    row.addView(cb);
                    row.setTag(new Object[]{icon, name, pkgView, cb});
                }
                icon.setImageDrawable(e.icon);
                name.setText(e.label);
                pkgView.setText(e.pkg);
                cb.setChecked(e.checked);
                cb.setButtonTintList(android.content.res.ColorStateList.valueOf(p.primary));
                row.setOnClickListener(v -> {
                    e.checked = !e.checked;      // 只改勾选，点「立即隐藏」才落盘 + 生效
                    cb.setChecked(e.checked);
                });
                return row;
            }
        };

        // 唯一的滚动容器（先建对象：下面 onChange 里刚展开时要把它滚回顶部）
        final ListView lv = new ListView(act);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setAdapter(adapter);

        // 「已隐藏的应用（N）」折叠区块：标题行固定在搜索框上方；卡片进列表（见上）
        final HiddenBlock block = new HiddenBlock(host, box, entries);
        // 展开/收起、动作完成、搜索词变化 —— 都由这里重建列表数据
        final boolean[] hadCards = {false};
        block.onChange = () -> {
            shown.clear();
            shown.addAll(block.cards());
            for (AppEntry e : entries) {
                if (query[0].isEmpty()
                        || e.label.toLowerCase(Locale.getDefault()).contains(query[0])
                        || e.pkg.toLowerCase(Locale.getDefault()).contains(query[0])) {
                    shown.add(e);
                }
            }
            adapter.notifyDataSetChanged();
            // 刚展开（无卡片 → 有卡片）时滚回顶部：ListView 会保留原来的像素偏移，
            // 否则卡片第一行（图标 / 名称 / 包名）被顶到视口之上，看着像"卡片缺了一块"。
            boolean now = !block.cards().isEmpty();
            if (now && !hadCards[0]) lv.setSelectionFromTop(0, 0);
            hadCards[0] = now;
        };
        blockRef[0] = block;
        block.onChange.run();

        // 唯一的滚动容器：给确定的像素高度（不做 wrap_content，也不套外层滚动）。
        // 高度按两条约束取：① 固定部件（标题/区块行/搜索/取消全部隐藏/两颗按钮）≈365dp
        // + 列表 ≤ 一屏可用高（本机 ≈700dp）⇒ 列表 ≤ 约 330dp；
        // ② 列表视口必须容得下一整张明细卡（图标行+机型+状态+三颗竖排按钮 ≈279dp）。
        // 取 min(300dp, 屏高 40%) ⇒ 本机 297dp，两条都满足，底部按钮也永远在屏幕里。
        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        int listH = Math.min(Ui.dp(300), Math.max(Ui.dp(190), Math.round(screenH * 0.40f)));
        box.addView(lv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, listH));

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            public void afterTextChanged(Editable e) {
                query[0] = e.toString().trim().toLowerCase(Locale.getDefault());
                block.onChange.run();
            }
        });

        // 弹窗皮走公共层；noOuterScroll：外层不套滚动，让 ListView 成为唯一的滚动容器
        final AlertDialog dlg = Ui.sheet(act, p)
                .noOuterScroll()
                .title("应用隐藏 · " + entries.size() + " 个应用")
                .view(box)
                .negative("取消", null)
                .positive("立即隐藏", () -> applyHidden(host, entries))
                .noIme()
                .show();
        clearAll.setOnClickListener(v -> {
            dlg.dismiss();
            clearHidden(host);
        });
    }

    /** 取消全部逐应用隐藏：复用 ToolsPage.clearAppHide（清空勾选 + 写空配置），并告知结果。 */
    private static void clearHidden(final Page host) {
        Task.bg(() -> {
            final Engine.Op op = ToolsPage.clearAppHide(host.act);
            Task.ui(() -> {
                host.info(op.ok ? "已取消全部隐藏" : "取消失败",
                        op.ok ? "逐应用隐藏已清空。\n\n正在运行、且被隐藏过的应用，"
                                + "要重启（或杀掉）一次才会读回真值。"
                                : op.message);
                if (op.ok) host.onShow();
            });
        }, null);
    }

    /**
     * 面板里点「立即隐藏」：勾选的落盘并生效，取消勾选的从列表里移除。
     *
     * ★ 列表与 zygisk.conf 必须一起成立（缺陷①）：这里**一次算出完整列表**交给
     * {@link Zygisk#applyList}，由它「先落列表 → 按列表写配置 → 写失败就回滚」。
     * 旧写法是循环里逐个 {@code setEnabled}（N 次写）+ 后台 sync，sync 一失败就留下
     * 「列表说在用、配置里没有它」——正是「Momo 重启后读到的全是真机值」的成因。
     */
    private static void applyHidden(final Page host, final List<AppEntry> entries) {
        final Target t = host.store.target();
        final List<String> pkgs = new ArrayList<>();
        for (AppEntry e : entries) {
            if (!e.checked) continue;
            pkgs.add(e.pkg);
            if (t != null) AppSpoof.setTargetFor(host.act, e.pkg, t.normalize());
        }
        syncHidden(host, pkgs);
    }

    /** 把勾选结果写进列表与模块配置（同一个 core 入口）并如实报告有没有真的生效。 */
    private static void syncHidden(final Page host, final List<String> pkgs) {
        Task.bg(() -> {
            final Engine.Op op = Zygisk.applyList(host.act, pkgs);
            final boolean zy = ToolsPage.zygiskInstalled();
            Task.ui(() -> {
                if (!op.ok) {
                    host.info("生效失败", op.message);
                } else if (op.message.contains("机型库里没有指纹数据")
                        || op.message.contains("没有真指纹数据")) {
                    // ★ 非阻断说明（2026-10-06 设计定稿）：目标机型没有真指纹时**不拦操作** ——
                    //   机型字段照写，系统构建身份整族保持真机原样。这条必须让人看见
                    //   （只弹 toast 的话用户不知道「为什么系统指纹还是真机的」），
                    //   所以用普通信息框如实说明，标题里也没有「失败」字样。
                    host.info("已隐藏（附一条说明）", op.message);
                } else if (!zy) {
                    host.info("配置已写入，但还不会生效",
                            "Zygisk 模块还没装 —— 先在首页装好 Zygisk 并重启一次，隐藏才会生效。");
                } else if (pkgs.isEmpty()) {
                    Task.toast(host.act, "已清空隐藏列表");
                } else {
                    Task.toast(host.act, "已隐藏 " + pkgs.size() + " 个应用，重启目标应用后生效");
                }
                if (op.ok) host.onShow();
            });
        }, null);
    }

    // ------------------------------------------------------------ 面板里的「已隐藏的应用」折叠区块

    /**
     * 「已隐藏的应用（N）」折叠区块的**标题行**（固定在面板上方，搜索框之上）。
     *
     * 卡片本身不在这里放：它们作为列表项插进面板那个唯一的 ListView 最前面 ——
     * 弹窗里只允许一层滚动，否则外层 ScrollView 会把列表的竖向手势吃掉（真机实测）。
     * 展开/收起只改数据（{@link #cards()}），由面板 {@code onChange} 重建列表。
     */
    private static final class HiddenBlock {

        private final Page host;
        private final List<AppEntry> all;        // 面板的全量列表数据：动作后要同步勾选态
        private final TextView head;
        private List<AppEntry> cards = new ArrayList<>();
        private boolean open;
        private int count;
        /** 展开/收起、动作完成、搜索词变化时由面板重建列表数据。 */
        Runnable onChange;

        HiddenBlock(final Page host, final LinearLayout panel, final List<AppEntry> all) {
            this.host = host;
            this.all = all;
            final MainActivity act = host.act;
            final Palette p = host.p;
            count = AppSpoof.enabled(act).size();

            head = Ui.text(act, "", 14, p.primary, Ui.W_BOLD);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setSingleLine(true);
            head.setBackground(Ui.pill(p.surfaceContainerHigh));
            Ui.pad(head, Ui.S4, 0, Ui.S4, 0);
            head.setOnClickListener(v -> toggle());
            LinearLayout.LayoutParams lp = Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(44));
            lp.bottomMargin = Ui.dp(Ui.S2);
            panel.addView(head, 0, lp);

            renderHead();
        }

        /** 现在要插在列表最前面的卡片（收起时为空）。 */
        List<AppEntry> cards() {
            return open ? cards : new ArrayList<AppEntry>();
        }

        private void renderHead() {
            head.setText((open ? "▾ " : "▸ ") + "已隐藏的应用（" + count + "）");
            // 空态压成次要色（一眼看出"还没隐藏过"），但**仍然可点** —— 点开会给一行说明，
            // 不再是"点了没反应"（真机反馈过这一点）。
            head.setTextColor(count == 0 ? host.p.onSurfaceVariant : host.p.primary);
        }

        private void toggle() {
            open = !open;
            renderHead();
            if (open) loadCards();
            else if (onChange != null) onChange.run();
        }

        /** 动作做完后：重读数量与卡片，并把全量列表的勾选态一起刷新（两边必须一致）。 */
        void reload() {
            List<String> on = AppSpoof.enabled(host.act);
            count = on.size();
            for (AppEntry e : all) e.checked = on.contains(e.pkg);
            renderHead();
            if (open) loadCards();
            else if (onChange != null) onChange.run();
        }

        /** 读卡片数据：图标 / 名称 / 包名 + 它实际生效的目标机型 + 完整伪装还是只改型号信息。 */
        private void loadCards() {
            final MainActivity act = host.act;
            Task.bg(() -> {
                final PackageManager pm = act.getPackageManager();
                final Target global = host.store.target();
                final List<AppEntry> list = new ArrayList<>();
                for (String pkg : AppSpoof.enabled(act)) {
                    AppEntry e = new AppEntry();
                    e.card = true;
                    e.pkg = pkg;
                    e.label = pkg;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        e.label = String.valueOf(pm.getApplicationLabel(ai));
                        e.icon = pm.getApplicationIcon(ai);
                    } catch (Throwable ignored) { }
                    // 读它**实际生效**的目标：单独设过就用它自己的，没有就回落到全局目标
                    Target t = AppSpoof.targetFor(act, pkg);
                    if (t == null) t = global;
                    if (t != null) t.normalize();
                    e.target = t;
                    // 判据与真正写属性的那条路一致（Props.appLevel 用的就是 familyOk）：
                    // true = 型号族 + 构建身份族一起写；false = 只写型号族，系统指纹保持真机原样。
                    e.full = t != null && t.familyOk();
                    list.add(e);
                }
                if (list.isEmpty()) {
                    AppEntry hint = new AppEntry();
                    hint.card = true;
                    hint.hint = true;
                    list.add(hint);
                }
                Task.ui(() -> {
                    cards = list;
                    if (onChange != null) onChange.run();
                });
            }, null);
        }
    }

    /** 一张应用卡（列表项）：图标 + 名称 + 包名 / 伪装成哪台 / 状态 / 三个竖排整宽动作。 */
    private static LinearLayout hiddenCardView(final Page host, final HiddenBlock block, final AppEntry e) {
        final MainActivity act = host.act;
        final Palette p = host.p;
        if (e.hint) {
            LinearLayout wrap = Ui.col(act);
            Ui.pad(wrap, 0, Ui.S2, 0, Ui.S2);
            wrap.addView(Ui.paragraph(act,
                    "还没有隐藏任何应用：勾选下面的应用，点「立即隐藏」即可。", p.onSurfaceVariant));
            return wrap;
        }

        // 卡片自己排版（不用 Ui.tinted 的 16/16 内边距）：整张卡要能塞进列表视口，
        // 否则最下面那颗「移除」会被 ListView 边界裁掉（真机审计报过）。
        final LinearLayout card = Ui.col(act);
        card.setBackground(Ui.bg(p.surfaceContainerHigh, Ui.R_INPUT));
        Ui.pad(card, Ui.S4, Ui.S3, Ui.S4, Ui.S3);

        LinearLayout top = Ui.row(act);
        ImageView iv = new ImageView(act);
        if (e.icon != null) iv.setImageDrawable(e.icon);
        top.addView(iv, Ui.lp(Ui.dp(34), Ui.dp(34)));
        Ui.margins(iv, 0, 0, Ui.S3, 0);
        LinearLayout info = Ui.col(act);
        info.addView(Ui.text(act, e.label, 14, p.onSurface, Ui.W_BOLD));
        info.addView(Ui.text(act, e.pkg, 11, p.onSurfaceVariant, Ui.W_REGULAR));
        top.addView(info, Ui.lpw(1f));
        card.addView(top, Ui.wrap());

        TextView model = Ui.text(act, "伪装成："
                + (e.target == null ? "（没有可用机型）" : ModelPage.cnName(e.target)),
                13, p.onSurface, Ui.W_REGULAR);
        Ui.pad(model, 0, Ui.S2, 0, 0);
        card.addView(model);

        TextView state = Ui.text(act, e.full
                        ? "完整伪装 · 型号与构建身份一起写"
                        : "只改型号信息 · 系统指纹保持真机原样",
                11, e.full ? p.success : p.warning, Ui.W_REGULAR);
        Ui.pad(state, 0, Ui.S1, 0, 0);
        card.addView(state);

        // 三个动作竖排、整宽：文字完整不截断（并排时会被挤成「更…」「关…」）
        TextView chg = host.outlined("更换机型");
        TextView off = host.outlined("关闭伪装");
        TextView rm = host.outlined("移除");
        rm.setTextColor(p.error);
        card.addView(chg, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(Ui.H_CONTROL)));
        Ui.margins(chg, 0, Ui.S2, 0, 0);
        card.addView(off, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(Ui.H_CONTROL)));
        Ui.margins(off, 0, Ui.S1, 0, 0);
        card.addView(rm, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(Ui.H_CONTROL)));
        Ui.margins(rm, 0, Ui.S1, 0, 0);
        Ui.pressable(chg);
        Ui.pressable(off);
        Ui.pressable(rm);

        chg.setOnClickListener(v -> pickTargetFor(host, e.pkg, e.label, block));
        off.setOnClickListener(v -> offApp(host, e.pkg, e.label, block));
        rm.setOnClickListener(v -> removeApp(host, e.pkg, e.label, block));
        // 卡片自己不设点击（动作只走上面三颗按钮，免得和"勾选"混淆）
        return card;
    }

    /** 关闭单个应用的伪装：把它从勾选列表里去掉，再走同一个原子入口重写配置。 */
    private static void offApp(final Page host, final String pkg, final String label, final HiddenBlock block) {
        host.confirm("关闭「" + label + "」的伪装",
                "把它从隐藏列表里停用，并按新列表重写模块配置。\n\n"
                        + "已经开着的它要重启（或杀掉）一次，才会读回真机值。",
                "关闭伪装", () -> {
                    final List<String> keep = new ArrayList<>(AppSpoof.enabled(host.act));
                    keep.remove(pkg);
                    Task.bg(() -> {
                        final Engine.Op op = Zygisk.applyList(host.act, keep);
                        Task.ui(() -> {
                            if (op.ok) {
                                host.info("已关闭", "「" + label + "」的伪装已停用。\n\n"
                                        + "重启这个应用后读回真机值。");
                                host.onShow();
                                if (block != null) block.reload();
                            } else {
                                host.info("关闭失败", op.message);
                            }
                        });
                    }, null);
                });
    }

    /** 从隐藏列表移除：连它单独设过的目标机型一起清掉（AppSpoof.remove），再原子重写配置。 */
    private static void removeApp(final Page host, final String pkg, final String label, final HiddenBlock block) {
        host.confirm("移除「" + label + "」",
                "从隐藏列表里删掉它，并清掉为它单独设过的机型"
                        + "（以后重新勾选就用当时的机型）。\n\n"
                        + "已经开着的它要重启（或杀掉）一次，才会读回真机值。",
                "移除", () -> {
                    final List<String> keep = new ArrayList<>(AppSpoof.enabled(host.act));
                    keep.remove(pkg);
                    Task.bg(() -> {
                        AppSpoof.remove(host.act, pkg);      // 停用 + 清掉它的目标值
                        final Engine.Op op = Zygisk.applyList(host.act, keep);
                        Task.ui(() -> {
                            if (op.ok) {
                                host.info("已移除", "「" + label + "」已从隐藏列表移除，"
                                        + "它单独设过的机型也一并清掉了。\n\n重启这个应用后读回真机值。");
                                host.onShow();
                                if (block != null) block.reload();
                            } else {
                                host.info("移除失败", op.message);
                            }
                        });
                    }, null);
                });
    }

    /** 给单个应用换机型：选中即写进它的目标，再走同一个原子入口重写配置。 */
    private static void pickTargetFor(final Page host, final String pkg, final String label, final HiddenBlock block) {
        final MainActivity act = host.act;
        final Palette p = host.p;

        // 内边距交给弹窗卡片（Ui.Sheet），这里只排内容
        final LinearLayout box = Ui.col(act);

        final EditText search = new EditText(act);
        search.setHint("搜索俗名 / 机型码 / 品牌");
        search.setTextSize(15);
        search.setTextColor(p.onSurface);
        search.setHintTextColor(p.onSurfaceVariant);
        search.setBackground(Ui.bg(p.surfaceContainerHigh, Ui.R_INPUT));
        Ui.pad(search, Ui.S4, 0, Ui.S4, 0);
        search.setSingleLine(true);
        box.addView(search, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(Ui.H_CONTROL)));

        // 弹窗皮走公共层；noOuterScroll：外层不套滚动，ListView 是唯一滚动容器
        // （列表在 show 之后挂上：行的点按要能关掉这个弹窗）
        final AlertDialog dlg = Ui.sheet(act, p)
                .noOuterScroll()
                .title("给「" + label + "」挑机型")
                .view(box)
                .negative("取消", null)
                .noIme()
                .show();

        final List<Target> all = Library.all();
        final List<Target> shown = new ArrayList<>(all);
        final BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }

            public View getView(int pos, View reuse, ViewGroup parent) {
                LinearLayout row;
                TextView name, sub;
                if (reuse instanceof LinearLayout) {
                    row = (LinearLayout) reuse;
                    Object[] held = (Object[]) row.getTag();
                    name = (TextView) held[0];
                    sub = (TextView) held[1];
                } else {
                    row = Ui.row(act);
                    Ui.pad(row, Ui.S4, Ui.S3, Ui.S4, Ui.S3);
                    LinearLayout info = Ui.col(act);
                    name = Ui.text(act, "", 14, p.onSurface, Ui.W_BOLD);
                    info.addView(name);
                    sub = Ui.text(act, "", 11, p.onSurfaceVariant, Ui.W_REGULAR);
                    info.addView(sub);
                    row.addView(info, Ui.lpw(1f));
                    row.setTag(new Object[]{name, sub});
                }
                final Target t = shown.get(pos);
                name.setText(ModelPage.cnName(t));
                sub.setText(t.model + " · Android " + t.release
                        + (t.soc == null || t.soc.isEmpty() ? "" : " · " + t.soc)
                        + (t.familyOk() ? "" : " · 只改型号信息"));
                row.setOnClickListener(v -> {
                    dlg.dismiss();
                    setAppTarget(host, pkg, label, t, block);
                });
                return row;
            }
        };

        ListView lv = new ListView(act);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setAdapter(adapter);
        // 唯一的滚动容器：确定像素高度（固定部件约 250dp + 列表，整体放得进一屏）
        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        int listH = Math.min(Ui.dp(420), Math.round(screenH * 0.45f));
        box.addView(lv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, listH));

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            public void afterTextChanged(Editable e) {
                String q = e.toString().trim().toLowerCase(Locale.getDefault());
                shown.clear();
                for (Target t : all) {
                    if (q.isEmpty() || hay(t).contains(q)) shown.add(t);
                }
                adapter.notifyDataSetChanged();
            }
        });
        dlg.show();
    }

    private static String hay(Target t) {
        return (t.market + " " + ModelPage.cnName(t) + " " + t.brand + " " + t.manufacturer + " "
                + t.model + " " + t.device + " " + t.soc + " " + t.release)
                .toLowerCase(Locale.getDefault());
    }

    /** 把某台机型写给这个应用：先写它的目标 → 再原子重写配置，成败都如实告知。 */
    private static void setAppTarget(final Page host, final String pkg, final String label, final Target t,
                                     final HiddenBlock block) {
        Task.bg(() -> {
            AppSpoof.setTargetFor(host.act, pkg, t.copy());
            final List<String> keep = new ArrayList<>(AppSpoof.enabled(host.act));
            if (!keep.contains(pkg)) keep.add(pkg);
            final Engine.Op op = Zygisk.applyList(host.act, keep);
            Task.ui(() -> {
                if (op.ok) {
                    host.info("已更换机型", "「" + label + "」现在伪装成「" + ModelPage.cnName(t) + "」。\n\n"
                            + (t.familyOk() ? "这台机型库里有真值：完整伪装。"
                                            : "这台机型库里没有真指纹：只改型号信息，系统指纹保持真机原样。")
                            + "\n\n重启这个应用后生效。");
                    host.onShow();
                    if (block != null) block.reload();
                } else {
                    host.info("写入失败", op.message);
                }
            });
        }, null);
    }
}
