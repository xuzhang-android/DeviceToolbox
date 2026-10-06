package com.xuzhang.devicetoolbox;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xuzhang.devicetoolbox.core.Engine;
import com.xuzhang.devicetoolbox.core.Library;
import com.xuzhang.devicetoolbox.core.Store;
import com.xuzhang.devicetoolbox.ui.HomePage;
import com.xuzhang.devicetoolbox.ui.ModelPage;
import com.xuzhang.devicetoolbox.ui.Page;
import com.xuzhang.devicetoolbox.ui.Palette;
import com.xuzhang.devicetoolbox.ui.SchemePage;
import com.xuzhang.devicetoolbox.ui.SpoofPage;
import com.xuzhang.devicetoolbox.ui.ToolsPage;
import com.xuzhang.devicetoolbox.ui.Ui;

/**
 * 改机型工具箱 —— 主界面。
 *
 * 结构按 Material 3：顶部应用栏承载屏幕语境，底部导航栏承载 3–5 个目的地，
 * 中间是内容区。导航项用固定尺寸的图标容器 + 药丸选中指示器，
 * 图标与文字因此永远居中对齐，不受文字基线影响。
 */
public class MainActivity extends Activity {

    private static final String[] TAB_LABEL = {"首页", "伪装", "机型", "方案", "工具"};
    private static final String[] TAB_ICON = {
            "ic_nav_home", "ic_nav_spoof", "ic_nav_model", "ic_nav_scheme", "ic_nav_tools"};

    private Palette p;
    private Store store;

    private LinearLayout root;
    private FrameLayout content;
    private final Page[] pages = new Page[5];
    private final ImageView[] navIcon = new ImageView[5];
    private final TextView[] navLabel = new TextView[5];
    private final View[] navPill = new View[5];

    private int tab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        Library.init(this);
        store = new Store(this);
        // 启动时就抓一份真值基线（此时还没伪装过），后台做，别拖慢启动
        com.xuzhang.devicetoolbox.ui.Task.bg(() -> Engine.warmBaseline(this), null);
        p = Palette.of(store.dark());
        buildUi();
    }

    public Palette palette() { return p; }

    public Store store() { return store; }

    /** 目标机型 / 档位变化后，让所有页面下次显示时重建。 */
    public void rebuildPages() {
        for (int i = 0; i < pages.length; i++) pages[i] = null;
    }

    public void goTab(int i) { select(i); }

    // ---------------------------------------------------------------- 骨架

    private void buildUi() {
        root = Ui.col(this);
        root.setBackgroundColor(p.surface);

        root.addView(buildTopBar(), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildNavBar(), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        applySystemBars();
        select(tab);
    }

    /** 顶部应用栏：只有标题和一个主题切换，不加分隔线。 */
    private View buildTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(p.surface);
        Ui.pad(bar, Ui.S5, Ui.S4, Ui.S3, Ui.S2);

        LinearLayout box = Ui.col(this);
        box.addView(Ui.display(this, "改机型工具箱", p.onSurface));
        bar.addView(box, Ui.lpw(1f));

        ImageView theme = Ui.iconButton(this, iconRes(p.dark ? "ic_sun" : "ic_moon"), p.onSurfaceVariant);
        theme.setOnClickListener(v -> {
            store.setDark(!p.dark);
            p = Palette.of(store.dark());
            rebuildPages();
            buildUi();
        });
        bar.addView(theme, Ui.lp(Ui.dp(48), Ui.dp(48)));
        return bar;
    }

    /**
     * 底部导航栏。每一项 = 固定 64×32 的图标容器（内含药丸指示器）+ 下方标签，
     * 两者都水平居中，所以图标与文字永远在同一中轴线上。
     */
    private View buildNavBar() {
        LinearLayout wrap = Ui.col(this);
        wrap.setBackgroundColor(p.surfaceContainerLow);

        View hairline = new View(this);
        hairline.setBackgroundColor(p.outlineVariant);
        wrap.addView(hairline, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.7f))));

        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.TOP);
        Ui.pad(bar, Ui.S1, Ui.S2, Ui.S1, Ui.S2);
        for (int i = 0; i < 5; i++) bar.addView(buildNavItem(i), Ui.lpw(1f));
        wrap.addView(bar, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(60)));

        // 系统导航栏安全区
        int navH = Ui.navBarHeight(this);
        if (navH > 0) {
            View safe = new View(this);
            wrap.addView(safe, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, navH));
        }
        return wrap;
    }

    private View buildNavItem(final int i) {
        LinearLayout item = Ui.col(this);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setClickable(true);
        item.setFocusable(true);

        FrameLayout holder = new FrameLayout(this);
        View pill = new View(this);
        pill.setBackground(Ui.pill(p.primaryContainer));
        pill.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(Ui.dp(60), Ui.dp(30));
        plp.gravity = Gravity.CENTER;
        holder.addView(pill, plp);

        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes(TAB_ICON[i]));
        ic.setColorFilter(p.onSurfaceVariant);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(23), Ui.dp(23));
        ilp.gravity = Gravity.CENTER;
        holder.addView(ic, ilp);

        item.addView(holder, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(30)));

        TextView label = Ui.text(this, TAB_LABEL[i], 11.5f, p.onSurfaceVariant, Ui.W_REGULAR);
        label.setGravity(Gravity.CENTER);
        Ui.margins(label, 0, Ui.S1, 0, 0);
        item.addView(label, Ui.wrap());

        item.setOnClickListener(v -> select(i));

        navPill[i] = pill;
        navIcon[i] = ic;
        navLabel[i] = label;
        return item;
    }

    private int iconRes(String name) {
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    private void applySystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(p.surface);
        w.setNavigationBarColor(p.surfaceContainerLow);
        if (Build.VERSION.SDK_INT >= 23) {
            View d = w.getDecorView();
            int flags = d.getSystemUiVisibility();
            if (p.dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            d.setSystemUiVisibility(flags);
        }
    }

    // ---------------------------------------------------------------- 切页

    private Page pageOf(int i) {
        if (pages[i] == null) {
            switch (i) {
                case 0: pages[i] = new HomePage(this); break;
                case 1: pages[i] = new SpoofPage(this); break;
                case 2: pages[i] = new ModelPage(this); break;
                case 3: pages[i] = new SchemePage(this); break;
                default: pages[i] = new ToolsPage(this); break;
            }
        }
        return pages[i];
    }

    private void select(int i) {
        tab = i;
        content.removeAllViews();
        Page pg = pageOf(i);
        content.addView(pg.view(), Ui.match());
        for (int k = 0; k < 5; k++) {
            boolean on = k == i;
            navPill[k].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            navIcon[k].setColorFilter(on ? p.onPrimaryContainer : p.onSurfaceVariant);
            navLabel[k].setTextColor(on ? p.onSurface : p.onSurfaceVariant);
            navLabel[k].setTypeface(null, on ? Ui.W_BOLD : Ui.W_REGULAR);
        }
        pg.onShow();
    }
}
