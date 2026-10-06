package com.xuzhang.devicetoolbox.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * UI 工具集 —— 令牌化的间距、字号、圆角与组件工厂。
 *
 * 三条硬约束（来自平台规范，不随页面走）：
 *   1. 触控目标不小于 48dp，相邻至少 8dp；
 *   2. 同一层级的按钮只有一个高度，只有颜色 / 描边区分主次；
 *   3. 不用实心 1px 灰边，改带透明度的发丝描边；不用硬阴影，深度靠表面色阶。
 */
public final class Ui {

    private static float density = 0f;
    private static Typeface FONT_REG, FONT_MED, FONT_BOLD;

    public static void init(Context c) {
        if (density == 0f) density = c.getResources().getDisplayMetrics().density;
        loadFonts(c);
    }

    /**
     * 全局字体：Plus Jakarta Sans（OFL）。
     *
     * 中文不在这个字体里，系统会自动回落到设备自带的中文字体 —— 所以西文与数字用上
     * 更精致的字形，中文保持系统字，不用为了中文字体把包撑大十几兆。
     * 重量取 450 / 600 而不是默认的 400 / 700：粗体不那么糊，观感更柔和。
     */
    private static void loadFonts(Context c) {
        if (FONT_REG != null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                FONT_REG = new Typeface.Builder(c.getAssets(), "fonts/PlusJakartaSans.ttf")
                        .setFontVariationSettings("'wght' 450").build();
                FONT_MED = new Typeface.Builder(c.getAssets(), "fonts/PlusJakartaSans.ttf")
                        .setFontVariationSettings("'wght' 550").build();
                FONT_BOLD = new Typeface.Builder(c.getAssets(), "fonts/PlusJakartaSans.ttf")
                        .setFontVariationSettings("'wght' 680").build();
            } else {
                FONT_REG = Typeface.createFromAsset(c.getAssets(), "fonts/PlusJakartaSans.ttf");
                FONT_MED = Typeface.create(FONT_REG, Typeface.BOLD);
                FONT_BOLD = FONT_MED;
            }
        } catch (Throwable t) {
            FONT_REG = FONT_MED = FONT_BOLD = null;
        }
    }

    private static Typeface face(int weight) {
        if (weight == W_BOLD) return FONT_BOLD != null ? FONT_BOLD : Typeface.DEFAULT_BOLD;
        if (weight == W_MEDIUM) return FONT_MED != null ? FONT_MED : Typeface.DEFAULT;
        return FONT_REG != null ? FONT_REG : Typeface.DEFAULT;
    }

    public static int dp(float v) { return Math.round(v * (density == 0f ? 2.75f : density)); }

    // ---------------------------------------------------------------- 令牌

    public static final int S1 = 4, S2 = 8, S3 = 12, S4 = 16, S5 = 20, S6 = 24, S8 = 32, S10 = 40;

    public static final float R_INPUT = 14, R_CARD = 20, R_SHEET = 28, R_CHIP = 999, R_BTN = 999;

    /** 统一控件高度：按钮 / 输入框 / 次级操作全部一致。 */
    public static final float H_CONTROL = 52;

    public static final float H_NAV = 76;

    public static final int W_REGULAR = Typeface.NORMAL;
    public static final int W_MEDIUM = 0x1000;   // 自定义：中等重量
    public static final int W_BOLD = Typeface.BOLD;

    // ---------------------------------------------------------------- 背景

    public static GradientDrawable bg(int fill, float radiusDp) { return bg(fill, radiusDp, 0, 0); }

    public static GradientDrawable bg(int fill, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return d;
    }

    public static GradientDrawable pill(int fill) { return bg(fill, R_CHIP); }

    /** 深度靠表面色阶；只有浅色主题额外给一层极轻的环境阴影。 */
    public static void elevate(View v, float dpHeight, Palette p) {
        if (p.dark) { v.setElevation(0f); return; }
        v.setElevation(dp(dpHeight));
    }

    // ---------------------------------------------------------------- 文字

    public static TextView text(Context c, CharSequence s, float size, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(face(weight));
        return t;
    }

    public static TextView display(Context c, CharSequence s, int color) {
        TextView t = text(c, s, 33, color, W_BOLD);
        t.setLetterSpacing(-0.02f);
        return t;
    }

    public static TextView headline(Context c, CharSequence s, int color) {
        TextView t = text(c, s, 22, color, W_BOLD);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    public static TextView title(Context c, CharSequence s, int color) {
        return text(c, s, 17, color, W_BOLD);
    }

    public static TextView body(Context c, CharSequence s, int color) {
        return text(c, s, 14, color, W_REGULAR);
    }

    public static TextView label(Context c, CharSequence s, int color) {
        TextView t = text(c, s, 12, color, W_REGULAR);
        t.setLetterSpacing(0.02f);
        return t;
    }

    public static TextView paragraph(Context c, CharSequence s, int color) {
        TextView t = text(c, s, 13, color, W_REGULAR);
        t.setLineSpacing(dp(4), 1f);
        return t;
    }

    // ---------------------------------------------------------------- 布局

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    public static LinearLayout.LayoutParams lpw(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.weight = weight;
        return p;
    }

    public static LinearLayout.LayoutParams lpw(float weight, int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, h);
        p.weight = weight;
        return p;
    }

    public static void pad(View v, float l, float t, float r, float b) {
        v.setPadding(dp(l), dp(t), dp(r), dp(b));
    }

    /**
     * 加入父容器并设置外边距 —— 一步到位。
     *
     * 不要写成「先 Ui.margins(v, ...) 再 parent.addView(v)」：addView 之前 v 还没有
     * LayoutParams，margins 是空操作，间距会静默丢失（这个坑踩过两次，共 25 处）。
     */
    public static <T extends View> T add(LinearLayout parent, T v,
                                         float l, float t, float r, float b) {
        parent.addView(v, wrap());
        margins(v, l, t, r, b);
        return v;
    }

    public static <T extends View> T add(LinearLayout parent, T v, LinearLayout.LayoutParams lp,
                                         float l, float t, float r, float b) {
        parent.addView(v, lp);
        margins(v, l, t, r, b);
        return v;
    }

    public static void margins(View v, float l, float t, float r, float b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) p).setMargins(dp(l), dp(t), dp(r), dp(b));
        }
    }

    public static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : dp(24);
    }

    public static int navBarHeight(Context c) {
        int id = c.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : dp(24);
    }

    public static View divider(Context c, Palette p) {
        View v = new View(c);
        v.setBackgroundColor(p.outlineVariant);
        return v;
    }

    // ---------------------------------------------------------------- 组件

    public enum Btn { FILLED, TONAL, OUTLINED, TEXT }

    /** 按钮：高度一律 H_CONTROL，只有配色与描边区分主次。 */
    public static TextView button(Context c, CharSequence label, Btn kind, Palette p) {
        TextView t = text(c, label, 15, p.onSurface, W_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        switch (kind) {
            case FILLED:
                t.setTextColor(p.onPrimary);
                t.setBackground(pill(p.primary));
                break;
            case TONAL:
                t.setTextColor(p.onPrimaryContainer);
                t.setBackground(pill(p.primaryContainer));
                break;
            case OUTLINED:
                t.setTextColor(p.primary);
                t.setBackground(bg(0x00000000, R_BTN, p.outline, 1));
                break;
            default:
                t.setTextColor(p.primary);
                t.setBackground(bg(0x00000000, R_BTN));
                break;
        }
        t.setPadding(dp(20), 0, dp(20), 0);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** 图标按钮：固定 48dp 触控区，图形居中。 */
    public static ImageView iconButton(Context c, int resId, int tint) {
        ImageView v = new ImageView(c);
        v.setImageResource(resId);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        pad(v, 12, 12, 12, 12);
        v.setBackground(bg(0x00000000, R_CHIP));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    public static ImageView icon(Context c, int resId, float sizeDp, int tint) {
        ImageView v = new ImageView(c);
        v.setImageResource(resId);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** 单层卡片。 */
    public static LinearLayout card(Context c, Palette p) {
        LinearLayout box = col(c);
        box.setBackground(bg(p.surfaceContainerLow, R_CARD));
        elevate(box, 1.5f, p);
        pad(box, S5, S5, S5, S5);
        return box;
    }

    /** 卡片内的分区：比卡片低一层，无描边，不是嵌套卡片。 */
    public static LinearLayout tinted(Context c, Palette p) {
        LinearLayout box = col(c);
        box.setBackground(bg(p.surfaceContainerHigh, R_INPUT));
        pad(box, S4, S4, S4, S4);
        return box;
    }

    /** 按下时的轻微缩放，给触感一点重量。 */
    public static void pressable(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(170)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- 弹窗

    /**
     * 全 App 统一的弹窗：圆角卡片 + 标题 / 小字说明 / 排版过的正文 / 底部整宽按钮。
     *
     * 不用 AlertDialog 自带的那套外观 —— 它标题与正文没有层级、正文是一面左对齐文字墙、
     * 两个按钮都是没有主次的纯文本。这里自己搭，颜色全部取自 Palette，深浅两套一致。
     *
     * 用法：{@code Ui.sheet(act, p).title("…").body("…").negative("取消", null)
     * .positive("确定", run).show()}（正文里的 {@code **强调**} 会渲染成粗体）。
     */
    public static final class Sheet {

        private final Context c;
        private final Palette p;
        private final LinearLayout card;
        private final LinearLayout column;
        private final ScrollView scroll;
        private String negLabel, posLabel;
        private Runnable negRun, posRun;
        private boolean danger, noIme;
        /** 默认给内容套一层滚动；列表类弹窗要关掉它（见 noOuterScroll）。 */
        private boolean outerScroll = true;

        private Sheet(Context c, Palette p) {
            this.c = c;
            this.p = p;
            card = col(c);
            // 与 App 里的卡片同一个表面色：这样弹窗里的输入框（containerHigh）在两套主题下都有对比，
            // 深色下换成更亮的容器色反而会让输入框糊在卡片里。再加一道发丝描边把边界交代清楚。
            card.setBackground(bg(p.surfaceContainerLow, R_SHEET, p.outline, 1));
            elevate(card, 6f, p);
            pad(card, S5, S5, S5, S4);
            column = col(c);
            // 内容容器：默认再套一层滚动（正文很长时也不会把按钮顶出屏幕），
            // 具体挂到哪一层由 show() 决定（见 noOuterScroll）。
            scroll = new ScrollView(c);
            scroll.setVerticalScrollBarEnabled(false);
        }

        /**
         * **内容自己管高度，外层不套滚动容器。**
         *
         * 专给「里面带 ListView 的弹窗」用。外层 ScrollView 只要竖向位移越过 touch slop 就会
         * 抢走手势（哪怕它自己没得滚），里面的 ListView 就永远滚不动 —— 真机上实测过：
         * 面板整体只滚了 25px（外层自己的全部余量），而列表纹丝不动。
         * （在 ListView 上挂 OnTouchListener 指望它 requestDisallowInterceptTouchEvent 是没用的：
         *   列表行的 DOWN 会被行内可点子视图吃掉，那个监听器根本不会触发。）
         *
         * 代价（调用方负责）：**内容必须能放进一屏**。列表给确定的像素高度、别用 wrap_content，
         * 标题/搜索框/按钮都固定，这样才能既不套滚动、又不会把按钮顶出屏幕。
         */
        public Sheet noOuterScroll() {
            outerScroll = false;
            return this;
        }

        /** 标题：加粗，明显大于正文。 */
        public Sheet title(CharSequence s) {
            if (s == null || s.length() == 0) return this;
            TextView t = text(c, s, 18, p.onSurface, W_BOLD);
            t.setLetterSpacing(-0.01f);
            column.addView(t);
            return this;
        }

        /** 标题下的一行灰色小字说明。 */
        public Sheet note(CharSequence s) {
            if (s == null || s.length() == 0) return this;
            add(column, text(c, s, 12, p.onSurfaceVariant, W_REGULAR), 0, S2, 0, 0);
            return this;
        }

        /** 正文：长段落拆成短句一行，「标签：值」排成 灰色标签 + 强调值。 */
        public Sheet body(String s) {
            if (s == null || s.length() == 0) return this;
            add(column, Ui.body(c, s, p), 0, S4, 0, 0);
            return this;
        }

        /** 自定义内容（列表、输入框…）：排在正文之后。 */
        public Sheet view(View v) {
            add(column, v, 0, S4, 0, 0);
            return this;
        }

        public Sheet negative(String label, Runnable run) {
            negLabel = label;
            negRun = run;
            return this;
        }

        public Sheet positive(String label, Runnable run) {
            posLabel = label;
            posRun = run;
            return this;
        }

        /** 危险操作：主按钮用错误色（还原 / 移除 / 卸载 / 删除…）。 */
        public Sheet danger(boolean d) {
            danger = d;
            return this;
        }

        /** 弹窗里有输入框：弹出时先把键盘收着。 */
        public Sheet noIme() {
            noIme = true;
            return this;
        }

        public AlertDialog show() {
            // 内容容器挂到哪一层：默认套滚动；noOuterScroll 时直接放卡片里（列表类弹窗）
            if (outerScroll) {
                scroll.addView(column, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
                card.addView(scroll, wrap());
            } else {
                card.addView(column, wrap());
            }

            TextView neg = null, pos = null;
            if (negLabel != null) {
                neg = button(c, negLabel, Btn.OUTLINED, p);
                card.addView(neg, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(H_CONTROL)));
                margins(neg, 0, S5, 0, 0);
                pressable(neg);
            }
            if (posLabel != null) {
                pos = button(c, posLabel, Btn.FILLED, p);
                if (danger) {
                    pos.setTextColor(p.onError);
                    pos.setBackground(pill(p.error));
                }
                card.addView(pos, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(H_CONTROL)));
                margins(pos, 0, neg == null ? S5 : S2, 0, 0);
                pressable(pos);
            }

            final AlertDialog dlg = new AlertDialog.Builder(c).setView(card).create();
            if (noIme) {
                dlg.getWindow().setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            }
            if (neg != null) {
                neg.setOnClickListener(v -> {
                    dlg.dismiss();
                    if (negRun != null) negRun.run();
                });
            }
            if (pos != null) {
                pos.setOnClickListener(v -> {
                    dlg.dismiss();
                    if (posRun != null) posRun.run();
                });
            }
            // 圆角卡片自己画背景：窗口背景必须透明，否则会出现方形底板
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
            // 宽度自适应 + 最大宽度：不要顶到屏幕两边
            int w = Math.min(dp(420), c.getResources().getDisplayMetrics().widthPixels - dp(48));
            // 内容超过屏幕大半就让它自己滚：按钮永远留在屏幕里
            // （noOuterScroll 模式下没有外层滚动可调，尺寸由调用方保证放进一屏）
            if (outerScroll) {
                int inner = Math.max(1, w - dp(S5 * 2));
                column.measure(View.MeasureSpec.makeMeasureSpec(inner, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                int maxBody = Math.round(c.getResources().getDisplayMetrics().heightPixels * 0.62f);
                if (column.getMeasuredHeight() > maxBody) {
                    scroll.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT, maxBody));
                }
            }
            dlg.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlg.show();
            return dlg;
        }
    }

    /** 全 App 统一的弹窗（见 {@link Sheet}）。 */
    public static Sheet sheet(Context c, Palette p) { return new Sheet(c, p); }

    /**
     * 约定的危险动作词：调用方不改签名也能拿到红按钮。
     *
     * 口径（与验收方对齐）：**移除 / 还原 / 卸载 / 删除 / 清空 / 恢复 / 重置** 算危险红；
     * 「关闭伪装」「取消全部隐藏」**不算** —— 它们只停用、不清机型记录，红底视觉过重。
     * 「回滚」「重建」「重装」「确定」也不在此列（半危险或语义太泛，保持普通主按钮）。
     */
    public static boolean isDangerLabel(String label) {
        if (label == null) return false;
        String[] words = {"移除", "还原", "卸载", "删除", "清空", "恢复", "重置"};
        for (String w : words) if (label.contains(w)) return true;
        return false;
    }

    /** 正文排版：段落 → 短句一行 → 「标签：值」两段式；行距拉开，不再是文字墙。 */
    public static LinearLayout body(Context c, String text, Palette p) {
        LinearLayout box = col(c);
        boolean firstPara = true;
        for (String rawPara : text.split("\n\\s*\n")) {
            String para = rawPara.trim();
            if (para.isEmpty()) continue;
            LinearLayout block = col(c);
            add(box, block, 0, firstPara ? 0 : S4, 0, 0);
            firstPara = false;

            boolean oneLine = para.indexOf('\n') < 0;
            List<String> lines = new ArrayList<>();
            for (String rawLine : para.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;
                if (oneLine) {
                    // 整段没有换行的长文：按句号/分号切开，一句一行
                    for (String x : line.split("(?<=[。；])")) {
                        String t = x.trim();
                        if (!t.isEmpty()) lines.add(t);
                    }
                } else {
                    lines.add(line);
                }
            }
            boolean bullets = oneLine && lines.size() > 1;
            boolean firstLine = true;
            for (String line : lines) {
                add(block, lineView(c, line, p, bullets), 0, firstLine ? 0 : S2, 0, 0);
                firstLine = false;
            }
        }
        return box;
    }

    /** 一行正文：能对上的「标签：值」排成 灰色标签 + 强调值，其余按正文（⚠ 开头的用警示色）。 */
    private static View lineView(Context c, String line, Palette p, boolean bullet) {
        int colon = kvColon(line);
        if (colon > 0) {
            LinearLayout row = row(c);
            TextView k = text(c, line.substring(0, colon).trim(), 12.5f, p.onSurfaceVariant, W_REGULAR);
            row.addView(k);
            margins(k, 0, 0, S3, 0);
            TextView v = text(c, emphasis(line.substring(colon + 1).trim()), 14, p.onSurface, W_BOLD);
            row.addView(v, lpw(1f));
            return row;
        }
        boolean warn = line.startsWith("⚠");
        TextView t = text(c, emphasis(bullet ? "· " + line : line), 13.5f,
                warn ? p.warning : p.onSurface, W_REGULAR);
        t.setLineSpacing(dp(5), 1.45f);
        return t;
    }

    /** 正文里「标签：值」的分隔位置；对不上返回 -1（当普通句子）。 */
    private static int kvColon(String line) {
        if (line.length() > 52) return -1;
        int i = line.indexOf('：');
        if (i <= 0 || i > 8) return -1;
        String label = line.substring(0, i);
        String value = line.substring(i + 1).trim();
        if (value.isEmpty()) return -1;
        for (char ch : new char[]{'。', '，', '；', '！', '？'}) {
            if (label.indexOf(ch) >= 0) return -1;
        }
        return i;
    }

    /** 把 {@code **强调**} 渲染成粗体（去掉字面的星号，别让用户看见 markdown 痕迹）。 */
    public static CharSequence emphasis(String s) {
        if (s == null || s.indexOf("**") < 0) return s;
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        while (i < s.length()) {
            int a = s.indexOf("**", i);
            if (a < 0) {
                out.append(s, i, s.length());
                break;
            }
            int b = s.indexOf("**", a + 2);
            if (b < 0) {
                out.append(s, i, s.length());
                break;
            }
            out.append(s, i, a);
            int start = out.length();
            out.append(s, a + 2, b);
            out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = b + 2;
        }
        return out;
    }
}
