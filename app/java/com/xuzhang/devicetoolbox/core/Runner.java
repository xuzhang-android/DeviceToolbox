package com.xuzhang.devicetoolbox.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * 把脚本文本落到应用私有目录，再一次性交给 root 执行。
 *
 * 为什么不直接把脚本塞进 su -c：脚本里带引号和换行，层层转义极易出错；
 * 写文件的方式只有一次参数传递，稳得多。root 可以读应用私有目录。
 */
public final class Runner {

    private Runner() { }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "run");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File write(Context c, String name, String text) {
        File f = new File(dir(c), name);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")) {
            w.write(text);
        } catch (Exception ignored) { }
        return f;
    }

    /** 以 root 身份执行脚本，默认 25 秒超时。 */
    public static Sh.Result root(Context c, String name, String script) {
        return root(c, name, script, 25000);
    }

    public static Sh.Result root(Context c, String name, String script, long timeoutMs) {
        File f = write(c, name, script);
        return Sh.root("sh " + Sh.q(f.getAbsolutePath()), timeoutMs);
    }

    /** 只写文件、不执行，返回绝对路径（用于再拷到别处）。 */
    public static String writeOnly(Context c, String name, String text) {
        return write(c, name, text).getAbsolutePath();
    }
}
