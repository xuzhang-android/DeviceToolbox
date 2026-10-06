package com.xuzhang.devicetoolbox.ui;

/**
 * 色彩令牌 —— 按 Material 3 的「角色」组织，而不是直接写十六进制。
 *
 * 深色不是浅色的反转：深色走 OLED 深底 + 色调抬升（surface 越靠上层越亮），
 * 浅色走「柔结构」路线：近白底 + 极浅描边 + 轻环境阴影。
 * 正文与次要文字都按 4.5:1 对比度选过。
 */
public final class Palette {

    public final boolean dark;

    // 表面（由低到高：页面底 → 卡片 → 浮起元素）
    public final int surface, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest;
    // 前景
    public final int onSurface, onSurfaceVariant;
    // 描边（发丝级，带透明度，不用实心灰）
    public final int outline, outlineVariant;
    // 主色
    public final int primary, onPrimary, primaryContainer, onPrimaryContainer;
    // 语义色
    public final int success, warning, error, onError;

    private Palette(boolean dark,
                    int surface, int scl, int sc, int sch, int sce,
                    int onSurface, int onSurfaceVariant,
                    int outline, int outlineVariant,
                    int primary, int onPrimary, int primaryContainer, int onPrimaryContainer,
                    int success, int warning, int error, int onError) {
        this.dark = dark;
        this.surface = surface; this.surfaceContainerLow = scl; this.surfaceContainer = sc;
        this.surfaceContainerHigh = sch; this.surfaceContainerHighest = sce;
        this.onSurface = onSurface; this.onSurfaceVariant = onSurfaceVariant;
        this.outline = outline; this.outlineVariant = outlineVariant;
        this.primary = primary; this.onPrimary = onPrimary;
        this.primaryContainer = primaryContainer; this.onPrimaryContainer = onPrimaryContainer;
        this.success = success; this.warning = warning; this.error = error; this.onError = onError;
    }

    /** 深色：OLED 深底 + 色调抬升。主色取自图标里的天蓝，提亮以保证深底可读。 */
    public static final Palette DARK = new Palette(true,
            0xFF0A0C10,   // surface
            0xFF101317,   // containerLow  —— 卡片
            0xFF151920,   // container
            0xFF1B2028,   // containerHigh —— 输入框 / 次级按钮
            0xFF222833,   // containerHighest
            0xFFE8ECF4,   // onSurface
            0xFF9BA5B4,   // onSurfaceVariant  (对比 7.2:1)
            0x33FFFFFF,   // outline（发丝白）
            0x1AFFFFFF,   // outlineVariant
            0xFF7CC2F5,   // primary
            0xFF06283F,   // onPrimary
            0xFF123049,   // primaryContainer
            0xFFC3E4FF,   // onPrimaryContainer
            0xFF6EDCA8,   // success
            0xFFFFC46B,   // warning
            0xFFFF8A9B,   // error
            0xFF3A0A12);

    /** 浅色：近白底 + 极浅描边 + 轻阴影。 */
    public static final Palette LIGHT = new Palette(false,
            0xFFF4F7FB,   // surface（页面底，略带蓝的灰白）
            0xFFFFFFFF,   // containerLow —— 卡片纯白
            0xFFFFFFFF,
            0xFFEDF2F9,   // containerHigh —— 输入框
            0xFFE4EBF4,
            0xFF0F1620,   // onSurface
            0xFF5A6675,   // onSurfaceVariant  (对比 5.4:1)
            0xFFDCE4EF,   // outline
            0xFFEAF0F7,   // outlineVariant
            0xFF1F6FB2,   // primary
            0xFFFFFFFF,   // onPrimary
            0xFFDCEBFB,   // primaryContainer
            0xFF0B3A5E,   // onPrimaryContainer
            0xFF1B7F4B,   // success
            0xFF8A5A00,   // warning
            0xFFB3261E,   // error
            0xFFFFFFFF);

    public static Palette of(boolean dark) { return dark ? DARK : LIGHT; }
}
