package com.wanmei.bath;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 本机诊断日志（1.2.0）。
 *
 * <p><b>为什么必须落盘</b>：只留内存的证据会死在「重启一下试试」这一步，而重启恰恰是
 * 多数人遇到问题的第一反应；偶发问题还常常要跨几次启动才凑得齐线索。闪退更是如此——
 * 进程都没了，内存里的东西一个字也留不下。
 *
 * <p><b>存在哪</b>：{@code files/diag.log}，应用私有目录（非 root 读不到，卸载即删）。
 * 一行一条记录，字段用制表符分隔：时间戳 / 类型 / 位置 / 原文 / 改写后。制表符和换行在
 * 写入前会被替换成空格——一条记录必须占一行，否则解析会串。
 *
 * <p><b>存什么</b>：只有错误与关键节点。不含手机号、姓名、验证码、cookie；原文里可能出现
 * 域名和异常类名，那正是排查所需。
 *
 * <p><b>上限</b>：200 条 / 128 KB，超出丢最旧的。写满后整体重写一次文件（而不是无限追加），
 * 免得用户几个月不开就攒出一个大文件。
 */
final class Diag {

    private static final int MAX_LINES = 200;
    private static final long MAX_BYTES = 128 * 1024;
    private static final String FILE_NAME = "diag.log";
    /** 单条原文上限：栈信息会很长，但再长就对排查没帮助了。 */
    private static final int RAW_MAX = 1200;

    private static final Object LOCK = new Object();
    private static final java.util.ArrayList<String> LINES = new java.util.ArrayList<>();
    private static File file;

    private Diag() { }

    /** 读回上次留下的记录。重复调用无副作用。 */
    static void init(Context ctx) {
        File dir;
        try {
            if (file != null) return;
            dir = ctx.getFilesDir();
        } catch (Throwable t) {
            return;                     /* 拿不到私有目录（极少见）：退化成只用内存 */
        }
        initIn(dir);
    }

    /** 指定目录读回记录。init() 与测试共用同一条路，免得两套实现各错一半。 */
    static void initIn(File dir) {
        synchronized (LOCK) {
            try {
                if (file != null) return;
                file = new File(dir, FILE_NAME);
                LINES.clear();
                if (!file.exists() || file.length() == 0) return;
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(file), "UTF-8"));
                try {
                    String ln;
                    while ((ln = r.readLine()) != null) {
                        if (ln.trim().isEmpty()) continue;
                        LINES.add(ln);
                    }
                } finally {
                    r.close();
                }
                if (LINES.size() > MAX_LINES) {
                    LINES.subList(0, LINES.size() - MAX_LINES).clear();
                }
            } catch (Throwable ignored) { }
        }
    }

    /** 记一条。kind/where/raw/shown 都可为 null。 */
    static void add(Context ctx, String kind, String where, String raw, String shown) {
        init(ctx);
        addLine(kind, where, raw, shown);
    }

    /** 核心写入（不碰 Context，便于测试）。 */
    static void addLine(String kind, String where, String raw, String shown) {
        String line = System.currentTimeMillis() + "\t" + clean(kind) + "\t" + clean(where)
                + "\t" + clean(trim(raw, RAW_MAX)) + "\t" + clean(shown);
        synchronized (LOCK) {
            try {
                if (file == null) return;
                boolean overflow = LINES.size() >= MAX_LINES;
                LINES.add(line);
                if (overflow) LINES.subList(0, LINES.size() - MAX_LINES).clear();
                if (overflow || size() > MAX_BYTES) rewrite();
                else append(line);
            } catch (Throwable ignored) { }
        }
    }

    /** 给前端读：JSON 数组，前端直接当自己那份缓冲的初始值。 */
    static String dumpJson(Context ctx) {
        init(ctx);
        JSONArray arr = new JSONArray();
        synchronized (LOCK) {
            for (String ln : LINES) {
                String[] p = ln.split("\t", -1);
                if (p.length < 5) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("t", Long.parseLong(p[0]));
                    o.put("kind", p[1]);
                    o.put("where", p[2]);
                    o.put("raw", p[3]);
                    o.put("shown", p[4]);
                    arr.put(o);
                } catch (Exception ignored) { }
            }
        }
        return arr.toString();
    }

    static void clear(Context ctx) {
        synchronized (LOCK) {
            LINES.clear();
            try {
                init(ctx);
                if (file != null && file.exists()) {
                    // 只截空，不删文件：手机上删文件容易留下「目录项还在」的怪状态
                    new FileOutputStream(file).close();
                }
            } catch (Throwable ignored) { }
        }
    }

    /**
     * 装一个兜底：**未捕获异常（真闪退）也留证据**。
     *
     * <p>装完照旧交回系统默认处理器——我们只负责记一笔，不改变「崩溃就退出」这件事。
     * 写入是同步的：进程马上要死了，异步写大概率来不及。
     */
    static void installCrashHandler(final Context ctx) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    add(ctx, "crash", "thread:" + t.getName(), trim(sw.toString(), RAW_MAX),
                            "应用异常退出");
                } catch (Throwable ignored) { }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    // ---------------- 文件 ----------------

    private static long size() {
        return file == null ? 0 : file.length();
    }

    private static void append(String line) throws Exception {
        FileOutputStream out = new FileOutputStream(file, true);
        try {
            OutputStreamWriter w = new OutputStreamWriter(out, "UTF-8");
            w.write(line);
            w.write("\n");
            w.flush();
        } finally {
            out.close();
        }
    }

    /** 整体重写（写临时文件再改名，写一半断电也不会把旧记录毁掉）。 */
    private static void rewrite() throws Exception {
        File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp, false);
        try {
            OutputStreamWriter w = new OutputStreamWriter(out, "UTF-8");
            for (String ln : LINES) {
                w.write(ln);
                w.write("\n");
            }
            w.flush();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(file)) {
            // 改名失败（少见）：退回直接覆盖，至少内容是对的
            FileOutputStream o2 = new FileOutputStream(file, false);
            try {
                OutputStreamWriter w = new OutputStreamWriter(o2, "UTF-8");
                for (String ln : LINES) {
                    w.write(ln);
                    w.write("\n");
                }
                w.flush();
            } finally {
                o2.close();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    // ---------------- 文本清洗 ----------------

    /** 一条记录必须占一行：制表符/换行会把字段切错。 */
    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c == '\t' || c == '\n' || c == '\r' ? ' ' : c);
        }
        return sb.toString().trim();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
