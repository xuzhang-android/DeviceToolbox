package com.xuzhang.devicetoolbox.ui;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xuzhang.devicetoolbox.MainActivity;
import com.xuzhang.devicetoolbox.core.Library;
import com.xuzhang.devicetoolbox.core.Target;

/**
 * 伪装页：选目标机型、改机型字段（可折叠）、就地开「应用隐藏」面板。
 *
 * 全 App 的动作入口只留首页一处：「全局隐藏」「还原原始」都在首页「目标伪装」卡里，
 * 本页不再重复实现（此前两页各有一份，行为已经漂移 —— 本页的「还原原始」少做了"停掉应用隐藏"）。
 * 「应用隐藏」是用户明确要求在伪装页也能用的，所以保留，直接复用 {@link HomePage#pickApps(Page)}。
 */
public final class SpoofPage extends Page {

    private TextView targetName, targetMeta, fpText;
    private EditText fModel, fBrand, fManu, fDevice, fBoard, fSoc, fRelease, fBuildId, fPatch;

    public SpoofPage(MainActivity act) { super(act); }

    @Override
    protected void build() {
        // ---------------- 目标 ----------------
        sectionFirst("目标机型");
        LinearLayout tg = addCard();
        targetName = Ui.headline(act, "", p.onSurface);
        tg.addView(targetName);
        targetMeta = Ui.paragraph(act, "", p.onSurfaceVariant);
        tg.addView(targetMeta);
        Ui.margins(targetMeta, 0, Ui.S2, 0, 0);
        TextView change = outlined("去机型库挑选 / 生成");
        change.setOnClickListener(v -> act.goTab(2));
        addWide(tg, change);

        // ---------------- 机型字段（可折叠，默认收起）----------------
        final TextView fieldArrow = Ui.text(act, "▸", 16, p.onSurfaceVariant, Ui.W_BOLD);
        LinearLayout fieldHead = Ui.row(act);
        fieldHead.setClickable(true);
        fieldHead.setFocusable(true);
        fieldHead.addView(Ui.headline(act, "机型字段", p.onSurface), Ui.lpw(1f));
        fieldHead.addView(fieldArrow);
        Ui.pad(fieldHead, 0, Ui.S3, 0, Ui.S3);
        body.addView(fieldHead, Ui.wrap());

        LinearLayout fs = addCard();
        fs.setVisibility(View.GONE);
        fieldHead.setOnClickListener(v -> {
            boolean open = fs.getVisibility() != View.VISIBLE;
            fs.setVisibility(open ? View.VISIBLE : View.GONE);
            fieldArrow.setText(open ? "▾" : "▸");
        });

        fModel = field(fs, "型号", "", "Pixel 9 Pro XL");
        fBrand = field(fs, "品牌（小写）", "", "google");
        fManu = field(fs, "制造商", "", "Google");
        fDevice = field(fs, "设备代号", "", "komodo");
        fBoard = field(fs, "主板", "", "tensor");
        fSoc = field(fs, "芯片（仅展示）", "", "Tensor G4");
        fRelease = field(fs, "系统版本", "", "15");
        fBuildId = field(fs, "构建号（留空自动生成）", "", "AP4A.250105.002");
        fPatch = field(fs, "安全补丁（留空自动推导）", "", "2025-01-05");

        TextView derive = tonal("按当前字段重新推导指纹与构建号");
        derive.setOnClickListener(v -> rederive());
        addWide(fs, derive);

        TextView fpLabel = Ui.label(act, "最终指纹", p.onSurfaceVariant);
        fs.addView(fpLabel);
        Ui.margins(fpLabel, Ui.S1, Ui.S6, 0, 0);
        fpText = Ui.paragraph(act, "", p.onSurface);
        fpText.setTextSize(12);
        fs.addView(fpText);
        Ui.margins(fpText, 0, Ui.S2, 0, 0);

        // ---------------- 操作：只留「应用隐藏」（就地开面板）----------------
        section("操作");
        LinearLayout ops = addCard();
        // 应用隐藏：就地开面板选应用（HomePage.pickApps 自带「还没选机型」检查，结束后会刷新本页）
        TextView hide = outlined("应用隐藏");
        hide.setOnClickListener(v -> HomePage.pickApps(this));
        addWide(ops, hide);
        hint(ops, "「全局隐藏」「还原原始」在首页「目标伪装」卡里操作 —— 同一动作全 App 只保留一处实现，"
                + "免得两边行为越走越远（此前本页的「还原原始」就少做了「停掉应用隐藏」这一步）。");
    }

    @Override
    public void onShow() {
        loadTarget();
    }

    // ------------------------------------------------------------ 数据

    private void loadTarget() {
        Target t = store.target();
        if (t == null) {
            targetName.setText("未选择机型");
            targetName.setTextColor(p.onSurfaceVariant);
            targetMeta.setText("去机型库挑一台，或者用生成器造一个。");
            for (EditText e : new EditText[]{fModel, fBrand, fManu, fDevice, fBoard, fSoc, fRelease, fBuildId, fPatch})
                e.setText("");
            fpText.setText("");
            return;
        }
        t.normalize();
        store.setTarget(t);
        targetName.setText(Library.displayName(t));
        targetName.setTextColor(p.onSurface);
        targetMeta.setText("Android " + t.release + " · SDK " + t.sdk() + " · 补丁 " + t.patch);
        fModel.setText(t.model); fBrand.setText(t.brand); fManu.setText(t.manufacturer);
        fDevice.setText(t.device); fBoard.setText(t.board); fSoc.setText(t.soc);
        fRelease.setText(t.release); fBuildId.setText(t.buildId); fPatch.setText(t.patch);
        fpText.setText(t.fingerprint());
    }

    private void rederive() {
        Target t = readFields();
        t.buildId = ""; t.incremental = ""; t.patch = ""; t.date = ""; t.dateUtc = ""; t.fingerprint = "";
        t.normalize();
        store.setTarget(t);
        loadTarget();
        Task.toast(act, "已重新推导");
    }

    private Target readFields() {
        Target t = store.target();
        if (t == null) t = new Target();
        t.model = fModel.getText().toString().trim();
        t.brand = fBrand.getText().toString().trim().toLowerCase();
        t.manufacturer = fManu.getText().toString().trim();
        t.device = fDevice.getText().toString().trim();
        t.board = fBoard.getText().toString().trim();
        t.soc = fSoc.getText().toString().trim();
        t.release = fRelease.getText().toString().trim();
        t.buildId = fBuildId.getText().toString().trim();
        t.patch = fPatch.getText().toString().trim();
        return t.normalize();
    }

}
