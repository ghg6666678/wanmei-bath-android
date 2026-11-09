package com.wanmei.bath;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 检查更新。
 *
 * <p>GitHub 在国内经常连不上（api.github.com 直连超时、第三方代理镜像时好时坏），
 * 所以这里不把「能访问 GitHub」当作前提，而是做成三层保障：
 *
 * <ol>
 *   <li><b>网络层</b>：4 个通道并行探测（GitHub 官方 API、代理镜像、jsDelivr CDN、raw），
 *       各自独立超时；官方 API 的结果最权威，清单文件次之，取版本号更高的那个。</li>
 *   <li><b>本地层</b>：APK 内置 latest.json，记着「已知最新版本 + 下载地址」，
 *       即使一条网络通道都不通，用户也不会只看到一个空白弹窗。</li>
 *   <li><b>缓存层</b>：上次检测成功的结果落在 SharedPreferences（7 天内有效），
 *       网络抽风时至少能报出「上次看到的版本」。</li>
 * </ol>
 *
 * <p>任何一层失败都不会把异常抛到界面，统一返回带 error / stale 标记的 JSON，
 * 前端据此显示「检测失败，但仍有手动下载入口」。
 */
public class Update {

    static final String REPO = "ghg6666678/wanmei-bath-android";

    /** 单个通道的超时（毫秒）：够慢的 3G 也能拿到结果，又不至于让界面干等。 */
    private static final int TIMEOUT = 4500;
    /** 整体等待上限：4 个通道并行跑，最多等这么久就拿现有结果。 */
    private static final long TOTAL_WAIT = 6000L;
    /** 缓存有效期：7 天。 */
    private static final long CACHE_TTL = 7L * 24 * 3600 * 1000;

    private static final int KIND_API = 0;    // GitHub Release 接口
    private static final int KIND_MANIFEST = 1; // latest.json 清单

    /** 下载镜像模板：把 GitHub 直链套进去即可，避免用户手动拼。 */
    private static final String[] MIRRORS = {
            "https://ghfast.top/%s",
            "https://ghproxy.cn/%s",
    };

    private static final class Source {
        final int kind;
        final String label;
        final String url;
        JSONObject latest;
        String error;

        Source(int kind, String label, String url) {
            this.kind = kind;
            this.label = label;
            this.url = url;
        }
    }

    /**
     * 执行检查。
     *
     * @param ctx     用于读 assets/latest.json 与读写缓存
     * @param current 当前安装版本，形如 {"version":"1.0.3","versionCode":4}
     */
    public JSONObject check(Context ctx, JSONObject current) {
        List<Source> found = new ArrayList<>();
        List<String> tried = new ArrayList<>();

        // ---- 本地层：先拿到「已知信息」，作为网络全挂时的底牌 ----
        JSONObject known = readManifest(ctx);
        String knownSource = "本地清单";
        if (known != null) {
            Source s = new Source(KIND_MANIFEST, "本地清单", "assets/latest.json");
            s.latest = known;
            found.add(s);
            tried.add("本地清单");
        }

        // ---- 网络层：并行探测 ----
        List<Source> probes = new ArrayList<>();
        probes.add(new Source(KIND_API, "GitHub 官方接口",
                "https://api.github.com/repos/" + REPO + "/releases/latest"));
        probes.add(new Source(KIND_API, "GitHub 代理镜像",
                "https://ghproxy.cn/https://api.github.com/repos/" + REPO + "/releases/latest"));
        // jsDelivr 有多个 CDN 节点：主节点（Fastly）对分支文件缓存长达 12 小时，
        // 刚发版后仍会返回旧版本；备用节点与 raw 都是即时生效的，所以一起探测，取版本号最高的。
        probes.add(new Source(KIND_MANIFEST, "jsDelivr CDN",
                "https://cdn.jsdelivr.net/gh/" + REPO + "@main/latest.json"));
        probes.add(new Source(KIND_MANIFEST, "jsDelivr 备用节点",
                "https://gcore.jsdelivr.net/gh/" + REPO + "@main/latest.json"));
        probes.add(new Source(KIND_MANIFEST, "jsDelivr 国内节点",
                "https://jsd.onmicrosoft.cn/gh/" + REPO + "@main/latest.json"));
        probes.add(new Source(KIND_MANIFEST, "raw 直连",
                "https://raw.githubusercontent.com/" + REPO + "/main/latest.json"));

        ExecutorService pool = Executors.newFixedThreadPool(probes.size());
        for (final Source s : probes) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        String body = httpGet(s.url, TIMEOUT);
                        JSONObject j = new JSONObject(body);
                        s.latest = s.kind == KIND_API ? fromRelease(j) : fromManifest(j);
                    } catch (Exception e) {
                        s.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    }
                }
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(TOTAL_WAIT, TimeUnit.MILLISECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }

        // ---- 汇总：官方 API 优先；版本号更高的优先（避免展示比已知更旧的版本） ----
        Source best = null;
        for (Source s : probes) {
            if (s.latest == null) continue;
            tried.add(s.label + (s.error == null ? " 成功" : " 失败"));
            if (best == null) {
                best = s;
                continue;
            }
            int c = verCmp(s.latest.optString("version", ""), best.latest.optString("version", ""));
            if (c > 0 || (c == 0 && s.kind == KIND_API && best.kind != KIND_API)) best = s;
        }
        if (best != null) {
            best.latest = withDownload(best.latest);
            saveCache(ctx, best.latest);
            return result(true, current, best.latest, best.label, false, "", tried);
        }

        // ---- 网络层没拿到：缓存层 ----
        JSONObject cached = readCache(ctx);
        if (cached != null) {
            return result(true, current, withDownload(cached), "上次检测结果（缓存）", true,
                    "连不上 GitHub", tried);
        }

        // ---- 只剩本地层 ----
        if (known != null) {
            return result(true, current, withDownload(known), knownSource, true,
                    "连不上 GitHub", tried);
        }

        // ---- 连本地清单都没有（正常不会发生） ----
        return result(false, current, null, "", false,
                "无法连接 GitHub，且本地没有可用清单。请手动到项目主页下载新版", tried);
    }

    // ================= 结果组装 =================

    private static JSONObject result(boolean ok, JSONObject current, JSONObject latest,
                                     String source, boolean stale, String error, List<String> tried) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", ok);
            o.put("current", current == null ? new JSONObject() : current);
            o.put("latest", latest == null ? new JSONObject() : latest);
            o.put("source", source == null ? "" : source);
            o.put("stale", stale);
            o.put("error", error == null ? "" : error);
            JSONArray t = new JSONArray();
            if (tried != null) for (String s : tried) t.put(s);
            o.put("tried", t);
        } catch (Exception ignored) { }
        return o;
    }

    /** 给版本信息补上下载地址与镜像地址。 */
    private static JSONObject withDownload(JSONObject latest) {
        if (latest == null) return new JSONObject();
        try {
            String ver = latest.optString("version", "");
            String apk = latest.optString("apkUrl", "");
            if (apk.isEmpty() && !ver.isEmpty()) {
                String name = latest.optString("apkName", "");
                if (name.isEmpty()) name = "zhishui-" + ver + ".apk";
                apk = "https://github.com/" + REPO + "/releases/download/v" + ver + "/" + name;
                latest.put("apkName", name);
                latest.put("apkUrl", apk);
            }
            String rel = latest.optString("releaseUrl", "");
            if (rel.isEmpty()) {
                rel = ver.isEmpty()
                        ? "https://github.com/" + REPO + "/releases/latest"
                        : "https://github.com/" + REPO + "/releases/tag/v" + ver;
                latest.put("releaseUrl", rel);
            }
            if (!latest.has("mirrors")) {
                JSONArray ms = new JSONArray();
                if (!apk.isEmpty()) for (String m : MIRRORS) ms.put(m.replace("%s", apk));
                latest.put("mirrors", ms);
            }
        } catch (Exception ignored) { }
        return latest;
    }

    // ================= 数据来源 =================

    /** 解析 GitHub Release 接口返回。 */
    private static JSONObject fromRelease(JSONObject j) throws Exception {
        String tag = j.optString("tag_name", "").trim();
        if (tag.isEmpty()) tag = j.optString("name", "").trim();
        String ver = tag.startsWith("v") ? tag.substring(1) : tag;
        if (ver.isEmpty()) throw new IllegalStateException("接口没有版本号");

        JSONObject o = new JSONObject();
        o.put("version", ver);
        o.put("publishedAt", j.optString("published_at", j.optString("created_at", "")));
        o.put("notes", summarize(j.optString("body", "")));

        JSONArray assets = j.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "");
                if (name.toLowerCase().endsWith(".apk")) {
                    o.put("apkName", name);
                    o.put("apkUrl", a.optString("browser_download_url", ""));
                    o.put("apkSize", a.optLong("size", 0));
                    break;
                }
            }
        }
        return o;
    }

    /** 解析 latest.json 清单。 */
    private static JSONObject fromManifest(JSONObject j) throws Exception {
        String ver = j.optString("version", "").trim();
        if (ver.isEmpty()) throw new IllegalStateException("清单没有版本号");
        JSONObject o = new JSONObject();
        o.put("version", ver);
        o.put("versionCode", j.optInt("versionCode", 0));
        o.put("publishedAt", j.optString("publishedAt", ""));
        o.put("notes", j.optString("notes", ""));
        o.put("apkName", j.optString("apkName", ""));
        o.put("apkUrl", j.optString("apkUrl", ""));
        return o;
    }

    /** 读取 APK 内置的 latest.json。 */
    private static JSONObject readManifest(Context ctx) {
        try {
            InputStream is = ctx.getAssets().open("latest.json");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            JSONObject j = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
            return fromManifest(j);
        } catch (Exception e) {
            return null;
        }
    }

    // ================= 缓存 =================

    private static void saveCache(Context ctx, JSONObject latest) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("bath_update", Context.MODE_PRIVATE);
            sp.edit().putString("last", latest.toString())
                    .putLong("at", System.currentTimeMillis()).apply();
        } catch (Exception ignored) { }
    }

    private static JSONObject readCache(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("bath_update", Context.MODE_PRIVATE);
            long at = sp.getLong("at", 0);
            if (at == 0 || System.currentTimeMillis() - at > CACHE_TTL) return null;
            String s = sp.getString("last", "");
            if (s == null || s.isEmpty()) return null;
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    // ================= 工具 =================

    /**
     * 解析 GitHub Release 的正文，取「本次更新」那一节做摘要。
     *
     * <p>发布说明的结构一般是：一级标题 → 一段简介 → {@code ## x.y.z 更新：xxx} → 若干条改动。
     * 界面里只想看到「这次改了什么」，所以优先定位带「更新 / 新增 / 修复」字样的二级标题，
     * 取它下面的前几条；找不到就退到第一个二级标题，再退到正文开头。
     */
    static String summarize(String body) {
        if (body == null) return "";
        String[] ls = body.split("\n");

        int start = -1;
        for (int i = 0; i < ls.length; i++) {
            String t = ls[i].trim();
            if (t.startsWith("## ") && (t.contains("更新") || t.contains("新增") || t.contains("修复"))) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            for (int i = 0; i < ls.length; i++) {
                if (ls[i].trim().startsWith("## ")) { start = i; break; }
            }
        }
        if (start < 0) {
            // 没有小节标题：跳过一级标题和空行，取正文开头
            for (int i = 0; i < ls.length; i++) {
                String t = ls[i].trim();
                if (t.isEmpty() || t.startsWith("# ")) continue;
                start = i - 1;
                break;
            }
            if (start < 0) return "";
        }

        StringBuilder sb = new StringBuilder();
        int lines = 0;
        for (int i = start; i < ls.length; i++) {
            String t = ls[i].trim();
            if (t.isEmpty()) continue;
            if (i > start && t.startsWith("#")) break;      // 撞到下一个小节就收手
            if (t.startsWith("## ")) t = t.substring(3).trim();
            if (t.startsWith("- ") || t.startsWith("* ")) t = t.substring(2);
            t = t.replace("**", "");
            if (t.length() > 70) t = t.substring(0, 70) + "…";
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
            if (++lines >= 4) break;
        }
        return sb.toString();
    }

    /**
     * 比较两个版本号：1.0.10 &gt; 1.0.9，1.0.3-dev &lt; 1.0.3。
     * 只比数字段，数字全等时带后缀的（预发布）算更旧。
     */
    static int verCmp(String a, String b) {
        int[] pa = nums(a), pb = nums(b);
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? pa[i] : 0;
            int y = i < pb.length ? pb[i] : 0;
            if (x != y) return x > y ? 1 : -1;
        }
        boolean sa = suffix(a), sb = suffix(b);
        if (sa == sb) return 0;
        return sa ? -1 : 1;   // 带后缀的更旧
    }

    private static int[] nums(String v) {
        if (v == null) return new int[0];
        String s = v.trim();
        if (s.startsWith("v")) s = s.substring(1);
        String[] parts = s.split("[.\\-]");
        int[] out = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            int val = 0;
            boolean any = false;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') break;
                val = val * 10 + (c - '0');
                any = true;
            }
            if (!any) break;
            out[n++] = val;
        }
        int[] r = new int[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    private static boolean suffix(String v) {
        if (v == null) return false;
        String s = v.trim();
        int i = 0;
        boolean dot = false;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'v' || c == '.') { if (c == '.') dot = true; continue; }
            if (c < '0' || c > '9') return true;
            dot = false;
        }
        return dot;   // 以小数点结尾也算带后缀
    }

    /** 简单 GET；失败抛异常，由调用方归类。 */
    private static String httpGet(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "BathHelper/1.0 (Android)");
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (is != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                is.close();
            }
            if (code >= 400) throw new IllegalStateException("HTTP " + code);
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            try { c.disconnect(); } catch (Exception ignored) { }
        }
    }
}
