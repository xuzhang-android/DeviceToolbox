package com.xuzhang.devicetoolbox.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** 把耗时的 root 调用挪到后台线程，结果回主线程。 */
public final class Task {

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private Task() { }

    public static void bg(final Runnable work, final Runnable done) {
        new Thread(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                android.util.Log.w("toolbox", "后台任务异常", t);
            } finally {
                if (done != null) UI.post(done);
            }
        }, "toolbox-bg").start();
    }

    public static void ui(Runnable r) { UI.post(r); }

    public static void toast(Context c, String s) {
        UI.post(() -> Toast.makeText(c, s, s != null && s.length() > 40 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show());
    }
}
