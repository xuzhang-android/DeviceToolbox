package com.xuzhang.devicetoolbox.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xuzhang.devicetoolbox.MainActivity;
import com.xuzhang.devicetoolbox.core.Library;
import com.xuzhang.devicetoolbox.core.Props;
import com.xuzhang.devicetoolbox.core.Share;
import com.xuzhang.devicetoolbox.core.Store;
import com.xuzhang.devicetoolbox.core.Target;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 方案页：把一套伪装存下来随时套用，并支持带校验的分享码。 */
public final class SchemePage extends Page {

    private EditText nameField;
    private LinearLayout listCard;
    private TextView countLabel;

    public SchemePage(MainActivity act) { super(act); }

    @Override
    protected void build() {
        sectionFirst("保存当前方案");
        LinearLayout save = addCard();
        nameField = field(save, "方案名", "", "例如：我的 Pixel 9");
        TextView b = filled("保存当前目标机型与档位");
        b.setOnClickListener(v -> saveScheme());
        addWide(save, b);
        hint(save, "方案只保存「目标机型 + 档位」，不含系统当前状态；套用时再决定要不要真的写入。");

        section("分享");
        LinearLayout sh = addCard();
        TextView exp = tonal("导出分享码");
        TextView imp = tonal("从剪贴板导入");
        exp.setOnClickListener(v -> exportCode());
        imp.setOnClickListener(v -> importCode());
        sh.addView(buttonRow(exp, imp));
        Ui.pressable(exp);
        Ui.pressable(imp);
        hint(sh, "分享码形如 DTB1.xxxx.yyyy，自带校验位；被截断或复制不全时会直接报错，不会写坏配置。");

        section("已保存的方案");
        countLabel = Ui.label(act, "", p.onSurfaceVariant);
        Ui.pad(countLabel, Ui.S1, 0, 0, Ui.S2);
        body.addView(countLabel);

        listCard = Ui.col(act);
        listCard.setBackground(Ui.bg(p.surfaceContainerLow, Ui.R_CARD));
        Ui.elevate(listCard, 1.5f, p);
        body.addView(listCard, Ui.wrap());
    }

    @Override
    public void onShow() { render(); }

    // ------------------------------------------------------------ 列表

    private void render() {
        listCard.removeAllViews();
        List<Store.Scheme> list = store.schemes();
        countLabel.setText(list.isEmpty() ? "还没有保存过方案" : "共 " + list.size() + " 个");

        if (list.isEmpty()) {
            TextView empty = Ui.paragraph(act, "选好机型后回到这里保存，之后可以一键套用。", p.onSurfaceVariant);
            Ui.pad(empty, Ui.S5, Ui.S4, Ui.S5, Ui.S5);
            listCard.addView(empty);
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        boolean first = true;
        for (int i = 0; i < list.size(); i++) {
            final Store.Scheme s = list.get(i);
            final int index = i;
            final Target t = s.asTarget();
            t.normalize();

            if (!first) {
                View hair = new View(act);
                hair.setBackgroundColor(p.outlineVariant);
                listCard.addView(hair, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.7f))));
                Ui.margins(hair, Ui.S4, 0, 0, 0);
            }
            first = false;

            LinearLayout box = Ui.col(act);
            Ui.pad(box, Ui.S5, Ui.S4, Ui.S5, Ui.S4);

            LinearLayout head = Ui.row(act);
            head.addView(Ui.text(act, s.name, 16, p.onSurface, Ui.W_BOLD), Ui.lpw(1f));
            TextView lvl = Ui.text(act, Props.LEVEL_NAME[Math.max(0, Math.min(2, s.level))],
                    12, p.onPrimaryContainer, Ui.W_BOLD);
            lvl.setBackground(Ui.pill(p.primaryContainer));
            lvl.setPadding(Ui.dp(Ui.S3), Ui.dp(4), Ui.dp(Ui.S3), Ui.dp(4));
            head.addView(lvl);
            box.addView(head);

            TextView sub = Ui.text(act, Library.displayName(t) + " · Android " + t.release
                    + (s.time > 0 ? "\n" + fmt.format(new Date(s.time)) : ""), 12, p.onSurfaceVariant, Ui.W_REGULAR);
            sub.setLineSpacing(Ui.dp(3), 1f);
            box.addView(sub);
            Ui.margins(sub, 0, Ui.S2, 0, 0);

            TextView apply = outlined("套用为当前目标");
            apply.setOnClickListener(v -> {
                store.setTarget(t.copy());
                store.setLevel(s.level);
                act.rebuildPages();
                render();
                Task.toast(act, "已套用：" + s.name);
                act.goTab(1);
            });
            TextView del = textBtn("删除");
            del.setOnClickListener(v -> confirm("删除方案", "确定删除「" + s.name + "」？", "删除", () -> {
                store.removeScheme(index);
                render();
            }));
            LinearLayout btns = Ui.row(act);
            btns.addView(apply, Ui.lpw(1f, Ui.dp(46)));
            View gap = new View(act);
            btns.addView(gap, Ui.lp(Ui.dp(Ui.S3), 1));
            btns.addView(del, Ui.lp(Ui.dp(88), Ui.dp(46)));
            box.addView(btns);
            Ui.margins(btns, 0, Ui.S4, 0, 0);
            Ui.pressable(apply);

            listCard.addView(box, Ui.wrap());
        }
    }

    // ------------------------------------------------------------ 操作

    private void saveScheme() {
        Target t = store.target();
        if (t == null) {
            info("没有目标机型", "先去机型库挑一台，或者用生成器造一个。");
            return;
        }
        String name = nameField.getText().toString().trim();
        if (name.isEmpty()) name = Library.displayName(t);
        Store.Scheme s = new Store.Scheme();
        s.name = name;
        s.target = t.normalize().serialize();
        s.level = store.level();
        s.time = System.currentTimeMillis();
        store.addScheme(s);
        nameField.setText("");
        render();
        Task.toast(act, "已保存：" + name);
    }

    private void exportCode() {
        Target t = store.target();
        if (t == null) {
            info("没有目标机型", "先选一台机器再导出。");
            return;
        }
        final String code = Share.encode(t, store.level());
        copy(code);
        info("分享码已复制", "长度 " + code.length() + " 字符，包含目标机型与档位。\n\n"
                + (code.length() > 140 ? code.substring(0, 140) + "…" : code));
    }

    private void importCode() {
        String text = paste();
        if (text == null || text.trim().isEmpty()) {
            info("剪贴板是空的", "先把分享码复制进来。");
            return;
        }
        try {
            Object[] r = Share.decode(text);
            final Target t = (Target) r[0];
            final int level = (Integer) r[1];
            t.normalize();
            confirm("导入分享码",
                    "机型：" + Library.displayName(t) + "\nAndroid " + t.release + " · 补丁 " + t.patch
                            + "\n档位：" + Props.LEVEL_NAME[Math.max(0, Math.min(2, level))]
                            + "\n\n导入后只作为当前目标，不会自动写入系统。",
                    "导入", () -> {
                        store.setTarget(t);
                        store.setLevel(level);
                        act.rebuildPages();
                        render();
                        Task.toast(act, "已导入：" + Library.displayName(t));
                    });
        } catch (IllegalArgumentException e) {
            info("导入失败", e.getMessage());
        }
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("改机型工具箱", s));
    }

    private String paste() {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) return null;
        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(act);
        return cs == null ? null : cs.toString();
    }
}
