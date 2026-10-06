package com.xuzhang.devicetoolbox.core;

import android.util.Base64;

import java.util.zip.CRC32;

/**
 * 方案分享码。
 *
 * 格式：DTB1.<base64url(内容)>.<crc32 前 6 位十六进制>
 * 带校验位，粘贴时被截断或串行能立刻发现，而不是把错误配置写进系统。
 */
public final class Share {

    private static final String PREFIX = "DTB1";

    public static String encode(Target t, int level) {
        String payload = "level=" + level + "\n" + t.normalize().serialize();
        byte[] raw = payload.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        String b64 = Base64.encodeToString(raw, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return PREFIX + "." + b64 + "." + crc(raw);
    }

    /** 解码失败抛 IllegalArgumentException，消息可直接展示给用户。 */
    public static Object[] decode(String code) {
        if (code == null) throw new IllegalArgumentException("分享码为空");
        String s = code.trim().replaceAll("\\s+", "");
        // 容忍整段文本里夹带分享码
        int at = s.indexOf(PREFIX + ".");
        if (at > 0) s = s.substring(at);
        String[] part = s.split("\\.");
        if (part.length < 3) throw new IllegalArgumentException("格式不对：应有 3 段，用 . 分隔");
        if (!PREFIX.equals(part[0])) throw new IllegalArgumentException("不是本工具的分享码（应以 " + PREFIX + " 开头）");
        byte[] raw;
        try {
            raw = Base64.decode(part[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Throwable t) {
            throw new IllegalArgumentException("内容段不是合法的 Base64");
        }
        if (!crc(raw).equalsIgnoreCase(part[2])) {
            throw new IllegalArgumentException("校验不通过：分享码可能被截断或复制不完整");
        }
        String payload = new String(raw, java.nio.charset.Charset.forName("UTF-8"));
        int level = Props.STD;
        StringBuilder body = new StringBuilder();
        for (String line : payload.split("\n")) {
            if (line.startsWith("level=")) {
                try { level = Integer.parseInt(line.substring(6).trim()); } catch (Throwable ignored) { }
            } else {
                body.append(line).append('\n');
            }
        }
        Target t = Target.parse(body.toString());
        return new Object[]{t, level};
    }

    private static String crc(byte[] raw) {
        CRC32 c = new CRC32();
        c.update(raw);
        return String.format("%06x", c.getValue() & 0xffffff);
    }
}
