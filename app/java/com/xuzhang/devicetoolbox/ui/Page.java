package com.xuzhang.devicetoolbox.ui;

import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xuzhang.devicetoolbox.MainActivity;
import com.xuzhang.devicetoolbox.core.Store;

/**
 * 页面基类：把设计令牌包成各页能直接用的积木。
 *
 * 标题不带「小标签」（kicker）—— 标题自己扛得住；标题上方的留白永远大于下方。
 */
public abstract class Page {

    protected final MainActivity act;
    protected final Palette p;
    protected final Store store;
    protected final LinearLayout body;

    private View root;

    protected Page(MainActivity act) {
        this.act = act;
        this.p = act.palette();
        this.store = act.store();
        this.body = Ui.col(act);
        Ui.pad(body, Ui.S5, Ui.S4, Ui.S5, Ui.S10);
    }

    /** 需要 ListView 之类自己滚动的页面覆写为 false。 */
    protected boolean scrollable() { return true; }

    public final View view() {
        if (root == null) {
            build();
            if (scrollable()) {
                ScrollView sc = new ScrollView(act);
                sc.setFillViewport(true);
                sc.setClipToPadding(false);
                sc.setVerticalScrollBarEnabled(false);
                sc.addView(body, Ui.wrap());
                root = sc;
            } else {
                root = body;
            }
        }
        return root;
    }

    protected abstract void build();

    public void onShow() { }

    // ------------------------------------------------------------ 区块

    /** 区块标题：上方留白是下方的两倍还多。 */
    protected void section(String title) {
        TextView t = Ui.headline(act, title, p.onSurface);
        body.addView(t);
    }

    /** 区块标题（首个区块，不需要上方大留白）。 */
    protected void sectionFirst(String title) {
        TextView t = Ui.headline(act, title, p.onSurface);
        body.addView(t);
        Ui.margins(t, Ui.S1, Ui.S8, 0, 0);
        Ui.margins(t, Ui.S1, 0, 0, 0);
    }

    protected LinearLayout addCard() {
        LinearLayout c = Ui.card(act, p);
        body.addView(c, Ui.wrap());
        Ui.margins(c, 0, Ui.S4, 0, 0);
        return c;
    }

    protected void hint(LinearLayout card, String text) {
        TextView t = Ui.paragraph(act, text, p.onSurfaceVariant);
        Ui.pad(t, Ui.S1, Ui.S3, Ui.S1, 0);
        card.addView(t);
    }

    protected View divider() {
        View v = Ui.divider(act, p);
        cardRowSpacer(v);
        return v;
    }

    private void cardRowSpacer(View v) {
        v.setLayoutParams(Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.7f))));
        Ui.margins(v, 0, Ui.S3, 0, 0);
    }

    // ------------------------------------------------------------ 按钮

    protected TextView filled(String label) { return Ui.button(act, label, Ui.Btn.FILLED, p); }

    protected TextView tonal(String label) { return Ui.button(act, label, Ui.Btn.TONAL, p); }

    protected TextView outlined(String label) { return Ui.button(act, label, Ui.Btn.OUTLINED, p); }

    protected TextView textBtn(String label) { return Ui.button(act, label, Ui.Btn.TEXT, p); }

    /** 并排两个按钮：等高、等宽、同层留白。 */
    protected LinearLayout buttonRow(android.view.View left, android.view.View right) {
        LinearLayout row = Ui.row(act);
        Ui.pad(row, 0, Ui.S5, 0, 0);
        row.addView(left, Ui.lpw(1f, Ui.dp(Ui.H_CONTROL)));
        if (right != null) {
            View gap = new View(act);
            row.addView(gap, Ui.lp(Ui.dp(Ui.S3), 1));
            row.addView(right, Ui.lpw(1f, Ui.dp(Ui.H_CONTROL)));
        }
        return row;
    }

    /** 整宽按钮。 */
    protected void addWide(LinearLayout parent, TextView btn) {
        parent.addView(btn, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(Ui.H_CONTROL)));
        Ui.margins(btn, 0, Ui.S4, 0, 0);
        Ui.pressable(btn);
    }

    // ------------------------------------------------------------ 输入

    protected EditText field(LinearLayout parent, String labelText, String value, String hintText) {
        if (labelText != null && !labelText.isEmpty()) {
            TextView l = Ui.label(act, labelText, p.onSurfaceVariant);
            Ui.pad(l, Ui.S1, Ui.S5, 0, 0);
            parent.addView(l);
        }
        EditText e = new EditText(act);
        e.setText(value == null ? "" : value);
        e.setHint(hintText == null ? "" : hintText);
        e.setTextSize(15);
        e.setTextColor(p.onSurface);
        e.setHintTextColor(p.onSurfaceVariant);
        e.setBackground(Ui.bg(p.surfaceContainerHigh, Ui.R_INPUT));
        Ui.pad(e, Ui.S4, 0, Ui.S4, 0);
        e.setSingleLine(true);
        parent.addView(e, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(Ui.H_CONTROL)));
        Ui.margins(e, 0, Ui.S2, 0, 0);
        return e;
    }

    // ------------------------------------------------------------ 弹窗

    /**
     * 提示框：标题 + 排版过的正文 + 一个整宽主按钮。
     *
     * 外观全部走 {@link Ui.Sheet}（圆角卡片 / 标题层级 / 正文拆行 / 主次按钮），
     * 所以全 App 的弹窗是同一套皮，深浅色主题都跟着 Palette 走。
     */
    protected void info(String title, String message) {
        Ui.sheet(act, p).title(title).body(message).positive("知道了", null).show();
    }

    /** 确认框：主按钮实心、取消描边；危险动作（还原/移除/卸载…）主按钮自动用错误色。 */
    protected void confirm(String title, String message, String okLabel, Runnable onOk) {
        confirm(title, message, okLabel, onOk, Ui.isDangerLabel(okLabel));
    }

    /** 确认框（显式指定是否危险）：{@code danger=true} 时主按钮用错误色。 */
    protected void confirm(String title, String message, String okLabel, Runnable onOk, boolean danger) {
        Ui.sheet(act, p)
                .title(title)
                .body(message)
                .danger(danger)
                .negative("取消", null)
                .positive(okLabel, onOk)
                .show();
    }
}
