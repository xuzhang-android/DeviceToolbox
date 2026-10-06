package com.xuzhang.devicetoolbox.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.xuzhang.devicetoolbox.MainActivity;
import com.xuzhang.devicetoolbox.R;
import com.xuzhang.devicetoolbox.core.Library;
import com.xuzhang.devicetoolbox.core.Target;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 机型页：机型库 + 自定义生成器。
 *
 * 五百多台机用 ListView + 适配器渲染，只创建可见的行 —— 用 LinearLayout 全量创建会明显卡顿。
 *
 * 机型名的中文化格式放在本类（cnName），首页「当前设备」也用同一套，
 * 免得同一个机型在两页显示成两个名字。
 */
public final class ModelPage extends Page {

    /**
     * 品牌中文名，按「常见程度」排序 —— 筛选条的芯片顺序就是这里的顺序。
     *
     * 库里的 brand 列大小写很乱（Xiaomi / xiaomi、REDMI / Redmi / redmi …），
     * 所以键一律用小写规范名，取值是给用户看的中文名；像 OPPO / vivo / iQOO / POCO /
     * Nothing / TCL / LG / HTC 这些，中文语境里本来就用拉丁写法，保持一致。
     * 表外的长尾杂牌（电视盒子 / 白牌平板那类）不进筛选条，但仍可搜索。
     */
    private static final String[][] BRAND_CN = {
            {"google", "谷歌"}, {"samsung", "三星"}, {"xiaomi", "小米"}, {"redmi", "红米"},
            {"oneplus", "一加"}, {"oppo", "OPPO"}, {"vivo", "vivo"}, {"honor", "荣耀"},
            {"huawei", "华为"}, {"realme", "真我"}, {"iqoo", "iQOO"}, {"meizu", "魅族"},
            {"zte", "中兴"}, {"nubia", "努比亚"}, {"lenovo", "联想"}, {"motorola", "摩托罗拉"},
            {"sony", "索尼"}, {"asus", "华硕"}, {"nothing", "Nothing"}, {"poco", "POCO"},
            {"redmagic", "红魔"}, {"blackshark", "黑鲨"}, {"tcl", "TCL"}, {"lg", "LG"},
            {"htc", "HTC"}, {"nokia", "诺基亚"}, {"apple", "苹果"}, {"coolpad", "酷派"},
            {"gionee", "金立"}, {"sharp", "夏普"}, {"philips", "飞利浦"}, {"panasonic", "松下"},
            {"hisense", "海信"}, {"acer", "宏碁"}, {"razer", "雷蛇"}, {"tencent", "腾讯"},
            {"infinix", "Infinix"}, {"tecno", "TECNO"}, {"blackview", "Blackview"},
            {"fairphone", "Fairphone"}, {"micromax", "Micromax"}, {"digma", "DIGMA"},
            {"ulefone", "Ulefone"}, {"umidigi", "UMIDIGI"}, {"doogee", "DOOGEE"},
    };

    /** 品牌的规范键：同一个品牌的不同写法归一，避免筛选条出现「小米 / Xiaomi」两枚芯片。 */
    private static String brandKey(String brand) {
        String b = brand == null ? "" : brand.trim().toLowerCase(Locale.ROOT);
        if (b.equals("oplus")) return "oppo";
        if (b.equals("lge")) return "lg";
        return b;
    }

    /** 品牌中文名；表里没有就返回 null（表示这个牌子没有通用中文名）。 */
    private static String brandCn(String brand) {
        String key = brandKey(brand);
        for (String[] row : BRAND_CN) if (row[0].equals(key)) return row[1];
        return null;
    }

    /** 中文与拉丁/数字紧挨着时补一个空格：「一加Ace 6」→「一加 Ace 6」。 */
    private static String spaced(String s) {
        if (s == null || s.length() < 2) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0) {
                char prev = s.charAt(i - 1);
                boolean cn = prev >= 0x4E00 && prev <= 0x9FFF;
                boolean lat = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
                if (cn && lat) sb.append(' ');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 机型展示名（中文化）：俗名开头的英文品牌词换成中文，没有品牌词就补上。首页也用它。 */
    public static String cnName(Target t) {
        if (t == null) return "";
        String name = Library.displayName(t);
        String cn = brandCn(t.brand);
        if (cn == null || name == null || name.isEmpty()) return spaced(name);
        if (name.startsWith(cn)) return spaced(name);
        String lower = name.toLowerCase(Locale.ROOT);
        String[] aliases = {brandKey(t.brand), t.manufacturer == null ? "" : t.manufacturer.toLowerCase(Locale.ROOT)};
        for (String a : aliases) {
            if (a.isEmpty()) continue;
            if (lower.startsWith(a)) {
                String rest = name.substring(a.length()).trim();
                return spaced(rest.isEmpty() ? cn : cn + " " + rest);
            }
        }
        return spaced(cn + " " + name);
    }

    private static int brandColor(String brand) {
        switch (brand == null ? "" : brand.toLowerCase(Locale.ROOT)) {
            case "google": return 0xFF4285F4;
            case "samsung": return 0xFF1B4FD8;
            case "xiaomi": case "redmi": case "poco": return 0xFFFF6A00;
            case "oneplus": return 0xFFEB0028;
            case "oppo": return 0xFF16A34A;
            case "realme": return 0xFFEAB308;
            case "vivo": case "iqoo": return 0xFF415FFF;
            case "honor": return 0xFF0A5CFF;
            case "huawei": return 0xFFE60012;
            case "sony": return 0xFF8A8A8A;
            case "asus": return 0xFF00539B;
            case "nothing": return 0xFFE8452C;
            case "motorola": return 0xFF00A0DF;
            case "nubia": return 0xFFD92626;
            case "meizu": return 0xFF00A9E0;
            case "zte": return 0xFF0072CE;
            case "lge": case "lg": return 0xFFA50034;
            case "lenovo": return 0xFFE2231A;
            case "htc": return 0xFF69B540;
            default: return 0xFF7A8AA3;
        }
    }

    private static final int VER_ALL = 0, VER_10 = 1, VER_12 = 2;
    private static final String[] VER_LABEL = {"全部版本", "Android 10+", "Android 12+"};

    private EditText search;
    private LinearLayout chipRow, verRow;
    private TextView countLabel;
    private ListView list;
    private Adapter adapter;
    /** 与芯片一一对应的品牌规范键（第 0 枚是「全部品牌」，值为 null）。 */
    private final List<String> chipKeys = new ArrayList<>();

    private String keyword = "";
    private String brandFilter = null;
    private int verFilter = VER_ALL;

    public ModelPage(MainActivity act) { super(act); }

    @Override
    protected boolean scrollable() { return false; }

    @Override
    protected void build() {
        LinearLayout head = Ui.col(act);
        body.addView(head, Ui.wrap());

        LinearLayout top = Ui.card(act, p);
        head.addView(top, Ui.wrap());

        search = field(top, null, "", "搜通俗名 / 机型码 / 芯片，例如 三星、Pixel、8 Gen 3");
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                keyword = s.toString().trim().toLowerCase(Locale.ROOT);
                refresh();
            }
        });
        TextView gen = Ui.button(act, "＋ 自定机型生成器", Ui.Btn.TONAL, p);
        gen.setOnClickListener(v -> showGenerator());
        top.addView(gen, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(Ui.H_CONTROL)));
        Ui.margins(gen, 0, Ui.S4, 0, 0);
        Ui.pressable(gen);

        // 版本筛选（放进横向滚动容器，否则最后几个会被挤成竖排）
        verRow = Ui.row(act);
        android.widget.HorizontalScrollView verSc = new android.widget.HorizontalScrollView(act);
        verSc.setHorizontalScrollBarEnabled(false);
        verSc.setClipToPadding(false);
        verSc.addView(verRow);
        head.addView(verSc, Ui.wrap());
        Ui.margins(verSc, 0, Ui.S5, 0, 0);
        for (int i = 0; i < VER_LABEL.length; i++) {
            final int v = i;
            TextView c = chip(VER_LABEL[i], i == verFilter);
            c.setOnClickListener(x -> { verFilter = v; paintVer(); refresh(); });
            verRow.addView(c, (LinearLayout.LayoutParams) c.getTag());
        }

        // 品牌筛选（同样横滚）
        chipRow = Ui.row(act);
        android.widget.HorizontalScrollView brandSc = new android.widget.HorizontalScrollView(act);
        brandSc.setHorizontalScrollBarEnabled(false);
        brandSc.setClipToPadding(false);
        brandSc.addView(chipRow);
        head.addView(brandSc, Ui.wrap());
        Ui.margins(brandSc, 0, Ui.S3, 0, 0);

        countLabel = Ui.label(act, "", p.onSurfaceVariant);
        head.addView(countLabel);
        Ui.pad(countLabel, Ui.S1, Ui.S4, 0, Ui.S2);

        list = new ListView(act);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(false);
        list.setBackgroundColor(p.surfaceContainerLow);
        adapter = new Adapter();
        list.setAdapter(adapter);
        body.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        buildChips();
        paintVer();
    }

    @Override
    public void onShow() { refresh(); }

    private TextView chip(String label, boolean on) {
        TextView t = Ui.text(act, label, 13, on ? p.onPrimaryContainer : p.onSurfaceVariant,
                on ? Ui.W_BOLD : Ui.W_REGULAR);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.pill(on ? p.primaryContainer : p.surfaceContainerHigh));
        t.setPadding(Ui.dp(Ui.S4), 0, Ui.dp(Ui.S4), 0);
        t.setClickable(true);
        LinearLayout.LayoutParams lp = Ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(38));
        lp.rightMargin = Ui.dp(Ui.S2);
        t.setTag(lp);
        return t;
    }

    private void paintVer() {
        for (int i = 0; i < verRow.getChildCount(); i++) {
            TextView t = (TextView) verRow.getChildAt(i);
            boolean on = i == verFilter;
            t.setTextColor(on ? p.onPrimaryContainer : p.onSurfaceVariant);
            t.setTypeface(null, on ? Ui.W_BOLD : Ui.W_REGULAR);
            t.setBackground(Ui.pill(on ? p.primaryContainer : p.surfaceContainerHigh));
        }
    }

    private void buildChips() {
        chipRow.removeAllViews();
        chipKeys.clear();

        // 库里实际存在的品牌（按规范键去重）
        Map<String, Boolean> present = new LinkedHashMap<>();
        for (Target t : Library.all()) present.put(brandKey(t.brand), Boolean.TRUE);

        TextView all = chip("全部品牌", true);
        all.setOnClickListener(v -> { brandFilter = null; paintBrand(); refresh(); });
        chipRow.addView(all, (LinearLayout.LayoutParams) all.getTag());
        chipKeys.add(null);

        for (String[] row : BRAND_CN) {
            if (!present.containsKey(row[0])) continue;
            final String key = row[0];
            TextView c = chip(row[1], false);
            c.setOnClickListener(v -> { brandFilter = key; paintBrand(); refresh(); });
            chipRow.addView(c, (LinearLayout.LayoutParams) c.getTag());
            chipKeys.add(key);
        }
        paintBrand();
    }

    private void paintBrand() {
        for (int i = 0; i < chipRow.getChildCount(); i++) {
            TextView c = (TextView) chipRow.getChildAt(i);
            String key = i < chipKeys.size() ? chipKeys.get(i) : null;
            boolean on = key == null ? brandFilter == null : key.equals(brandFilter);
            c.setTextColor(on ? p.onPrimaryContainer : p.onSurfaceVariant);
            c.setTypeface(null, on ? Ui.W_BOLD : Ui.W_REGULAR);
            c.setBackground(Ui.pill(on ? p.primaryContainer : p.surfaceContainerHigh));
        }
    }

    // ------------------------------------------------------------ 过滤

    private List<Target> filtered() {
        List<Target> favs = new ArrayList<>();
        List<Target> rest = new ArrayList<>();
        for (Target t : Library.all()) {
            if (brandFilter != null && !brandFilter.equals(brandKey(t.brand))) continue;
            if (verFilter != VER_ALL) {
                int maj = major(t.release);
                if (verFilter == VER_12 && maj < 12) continue;
                if (verFilter == VER_10 && maj < 10) continue;
            }
            if (!keyword.isEmpty()) {
                String cn = brandCn(t.brand);
                String hay = (t.market + " " + t.brand + " " + t.manufacturer + " " + t.model + " "
                        + t.device + " " + t.soc + " " + t.release + " " + cnName(t)
                        + (cn == null ? "" : " " + cn)).toLowerCase(Locale.ROOT);
                if (!hay.contains(keyword)) continue;
            }
            if (store.isFavorite(t)) favs.add(t); else rest.add(t);
        }
        favs.addAll(rest);
        return favs;
    }

    private static int major(String release) {
        try { return Integer.parseInt(String.valueOf(release).split("\\.")[0]); }
        catch (Throwable t) { return 0; }
    }

    private void refresh() {
        if (adapter == null) return;
        List<Target> list2 = filtered();
        adapter.set(list2);
        int favs = 0;
        for (Target t : list2) if (store.isFavorite(t)) favs++;
        countLabel.setText("共 " + list2.size() + " 台"
                + (favs == 0 ? "" : " · 收藏 " + favs)
                + " · 点一下即选中");
    }

    // ------------------------------------------------------------ 适配器

    private final class Adapter extends BaseAdapter {
        private final List<Target> items = new ArrayList<>();

        void set(List<Target> l) {
            items.clear();
            items.addAll(l);
            notifyDataSetChanged();
        }

        public int getCount() { return items.size(); }
        public Object getItem(int i) { return items.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int pos, View reuse, ViewGroup parent) {
            LinearLayout row;
            Holder h;
            if (reuse instanceof LinearLayout && reuse.getTag() instanceof Holder) {
                row = (LinearLayout) reuse;
                h = (Holder) reuse.getTag();
            } else {
                row = Ui.row(act);
                Ui.pad(row, Ui.S4, Ui.S3, Ui.S2, Ui.S3);

                h = new Holder();
                h.dot = new View(act);
                row.addView(h.dot, Ui.lp(Ui.dp(8), Ui.dp(8)));
                Ui.margins(h.dot, 0, 0, Ui.S3, 0);

                LinearLayout info = Ui.col(act);
                h.name = Ui.text(act, "", 15.5f, p.onSurface, Ui.W_BOLD);
                info.addView(h.name);
                h.sub = Ui.text(act, "", 12, p.onSurfaceVariant, Ui.W_REGULAR);
                info.addView(h.sub);
                Ui.margins(h.sub, 0, 2, 0, 0);
                row.addView(info, Ui.lpw(1f));

                h.star = Ui.icon(act, R.drawable.ic_star_outline, 20, p.outline);
                h.star.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
                row.addView(h.star, Ui.lp(Ui.dp(48), Ui.dp(48)));

                row.setTag(h);
            }

            final Target t = items.get(pos);
            boolean fav = store.isFavorite(t);
            h.dot.setBackground(Ui.pill(brandColor(t.brand)));
            h.name.setText(cnName(t));
            h.sub.setText(t.model + " · Android " + t.release
                    + (t.soc == null || t.soc.isEmpty() ? "" : " · " + t.soc));
            h.star.setImageResource(fav ? R.drawable.ic_star : R.drawable.ic_star_outline);
            h.star.setColorFilter(fav ? p.warning : p.outline);
            h.star.setOnClickListener(v -> {
                store.toggleFavorite(t);
                refresh();
            });
            row.setOnClickListener(v -> {
                store.setTarget(t.copy());
                act.rebuildPages();
                Task.toast(act, "已选：" + cnName(t));
                act.goTab(1);
            });
            return row;
        }
    }

    private static final class Holder {
        View dot;
        TextView name, sub;
        ImageView star;
    }

    // ------------------------------------------------------------ 生成器

    private void showGenerator() {
        // 内边距交给弹窗卡片（Ui.Sheet）；内容超高时它自己会滚，这里不用再套一层
        LinearLayout box = Ui.col(act);

        final EditText b = field(box, "品牌（英文码）", "google", "google / xiaomi / samsung");
        final EditText m = field(box, "型号名", "Pixel 9 Pro", "Pixel 9 Pro");
        final EditText s = field(box, "芯片", "Tensor G4", "Tensor G4 / 骁龙 8 Gen 3");
        final EditText r = field(box, "Android 版本", "15", "15 / 14 / 13");

        Ui.sheet(act, p)
                .title("自定机型生成器")
                .view(box)
                .negative("取消", null)
                .positive("生成并使用", () -> {
                    Target t = Library.generate(
                            b.getText().toString().trim(),
                            m.getText().toString().trim(),
                            s.getText().toString().trim(),
                            r.getText().toString().trim());
                    store.setTarget(t);
                    act.rebuildPages();
                    refresh();
                    info("已生成", cnName(t)
                            + "\n\nAndroid " + t.release + " · SDK " + t.sdk()
                            + "\n代号 " + t.device
                            + "\n补丁 " + t.patch
                            + "\n构建号 " + t.buildId
                            + "\n\n指纹：\n" + t.fingerprint());
                })
                .show();
    }
}
