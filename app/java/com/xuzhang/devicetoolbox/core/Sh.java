package com.xuzhang.devicetoolbox.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** 执行 shell 命令（root / 普通）。带超时看门狗，避免 su 卡死拖住界面。 */
public final class Sh {

    public static final class Result {
        public final int code;
        public final String out;
        public final String err;
        public final boolean timeout;

        Result(int code, String out, String err, boolean timeout) {
            this.code = code; this.out = out; this.err = err; this.timeout = timeout;
        }

        public boolean ok() { return code == 0 && !timeout; }

        /** out 非空则返回 out，否则返回 err，方便直接展示。 */
        public String text() {
            if (out != null && out.trim().length() > 0) return out;
            return err == null ? "" : err;
        }
    }

    private static final long DEFAULT_TIMEOUT_MS = 20000;

    /** 以 root 身份执行一段脚本。 */
    public static Result root(String script) { return root(script, DEFAULT_TIMEOUT_MS); }

    public static Result root(String script, long timeoutMs) {
        return exec(new String[]{"su", "-c", script}, timeoutMs);
    }

    /** 普通身份执行。 */
    public static Result user(String script) { return user(script, DEFAULT_TIMEOUT_MS); }

    public static Result user(String script, long timeoutMs) {
        return exec(new String[]{"sh", "-c", script}, timeoutMs);
    }

    private static Result exec(String[] cmd, long timeoutMs) {
        Process proc = null;
        try {
            proc = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            final Process fp = proc;
            final ByteArrayOutputStream ob = new ByteArrayOutputStream();
            final ByteArrayOutputStream eb = new ByteArrayOutputStream();
            Thread to = drain(fp.getInputStream(), ob);
            Thread te = drain(fp.getErrorStream(), eb);

            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroy();
                try { proc.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                proc.destroyForcibly();
                to.join(500); te.join(500);
                return new Result(-1, ob.toString("UTF-8"), "命令超时（" + (timeoutMs / 1000) + " 秒）", true);
            }
            to.join(1000); te.join(1000);
            return new Result(proc.exitValue(), ob.toString("UTF-8"), eb.toString("UTF-8"), false);
        } catch (Exception e) {
            if (proc != null) proc.destroyForcibly();
            return new Result(-1, "", e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        }
    }

    private static Thread drain(final InputStream in, final ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) > 0) sink.write(buf, 0, n);
            } catch (Exception ignored) {
            } finally {
                try { in.close(); } catch (Exception ignored) { }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 单引号包裹，内部的单引号按 shell 规则转义。 */
    public static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
