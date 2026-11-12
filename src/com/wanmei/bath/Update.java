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
 * <p>GitHub 在国内经常连不上（api.github.com 直连超时、raw 时通时不通），
 * 所以这里不把「能访问 GitHub」当作前提，而是做成三层保障：
 *
 * <ol>
 *   <li><b>网络层</b>：并行探测 GitHub 官方 API、jsDelivr 三个节点与 raw，
 *       各自独立超时；官方 API 的结果最权威，清单文件次之，取版本号更高的那个。
 *       <b>不使用任何免费代理域名</b>——原因见 CDNS 上方的注释。</li>
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
    /** 完整 Markdown 原文的上限，防止异常大的正文把内存和流量撑爆。 */
    private static final int NOTE_MD_MAX = 20000;
    /** 更新日志摘要的字符上限：够放下要点，再长就让弹层滚动。 */
    private static final int NOTE_MAX = 3000;
    /** 统一的 UA，探测与拉取保持一致。 */
    private static final String UA = "BathHelper/1.0 (Android)";
    /** 单个下载地址的探测超时：够判断「通不通」，又不至于让用户干等。 */
    private static final int PROBE_TIMEOUT = 2500;
    /** 所有下载地址并行探测，最多等这么久。 */
    private static final long PROBE_WAIT = 3200L;

    private static final int KIND_API = 0;    // GitHub Release 接口
    private static final int KIND_MANIFEST = 1; // latest.json 清单

    /**
     * 下载加速：只走 jsDelivr 这类有企业背书的 CDN，**绝不内置免费代理域名**。
     *
     * <p>2026-11 实测教训：ghproxy.cn 在移动网络下会把下载跳转到色情站点。免费代理域名
     * 随时可能被抢注或劫持，把用户导向哪里完全不受我们控制。这类域名一律不进代码——
     * 宁可少一条通道，也不能让「检查更新」变成跳板。
     *
     * <p>路径依赖：APK 需要同时提交到仓库的 release/ 目录，<b>并且要在打 tag 之前提交</b>。
     * 地址用 {@code @v<version>} 而不是 {@code @main}——jsDelivr 对整个分支的快照有
     * 长达 12 小时的解析缓存，刚发版时 {@code @main} 可能仍指向旧提交（表现为 404 或
     * 返回上一版的文件），而每个版本的 tag 都是全新引用，没有缓存，立刻可用。
     */
    private static final String[] CDNS = {
            "https://cdn.jsdelivr.net/gh/%s@v%s/release/%s",
            "https://gcore.jsdelivr.net/gh/%s@v%s/release/%s",
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
        // 曾经这里有一条 ghproxy.cn 的镜像通道，2026-11 因该域名跳转色情站点已删除。
        // 免费代理一律不再进入代码，见 CDNS 上的注释。
        // jsDelivr 有两个可直接服务内容的节点（都在 Cloudflare 上）。主节点对分支文件
        // 缓存长达 12 小时，刚发版后仍会返回旧版本；gcore 与 raw 是即时生效的，一起探测取最高。
        //
        // 这里曾经还有一个 jsd.onmicrosoft.cn（标称「国内节点」）。2026-11 实测它对
        // raw.githubusercontent.com 做 301 跳转——本身不存内容，只是把请求转给 raw，
        // 而 raw 在国内比 github 还难访问。已经移除，别再相信任何「国内加速」的域名，
        // 先看它的响应头是 200 还是 30x。
        probes.add(new Source(KIND_MANIFEST, "jsDelivr CDN",
                "https://cdn.jsdelivr.net/gh/" + REPO + "@main/latest.json"));
        probes.add(new Source(KIND_MANIFEST, "jsDelivr 备用节点",
                "https://gcore.jsdelivr.net/gh/" + REPO + "@main/latest.json"));
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
            // 只有确实有新版时才去试下载地址——没更新就没必要多等这两秒
            if (verCmp(best.latest.optString("version", ""),
                       current.optString("version", "")) > 0) {
                attachDownloads(best.latest);
            }
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
                String name = latest.optString("apkName", "");
                // 只有「仓库里有这个 APK」时才有 CDN 地址可拼；Release 资产 CDN 取不到。
                if (!ver.isEmpty() && !name.isEmpty()) {
                    for (String t : CDNS) ms.put(String.format(t, REPO, ver, name));
                }
                latest.put("mirrors", ms);
            }
        } catch (Exception ignored) { }
        return latest;
    }

    // ================= 下载地址实测 =================

    /**
     * 逐个试一下下载地址，只留下真能取到文件的那几个。
     *
     * <p>为什么要做：网络环境千差万别，同一个地址在 A 能下、在 B 打不开。以前只在弹层里
     * 列一排地址让用户自己试，点了才发现下不动——现在先替他把这一步做掉。
     *
     * <p>用的是 {@code Range: bytes=0-0} 的 GET：只要 1 个字节，拿到响应头就断开，
     * 流量可以忽略。某些 CDN 不支持 HEAD，所以不用 HEAD。
     *
     * <p>结果写进 {@code downloads} 数组，每项含 url / ok / ms。只在确认有新版时才做，
     * 没更新就不必多等这两秒。
     */
    private static void attachDownloads(JSONObject latest) {
        if (latest == null) return;
        List<String> urls = new ArrayList<>();
        String apk = latest.optString("apkUrl", "");
        if (!apk.isEmpty()) urls.add(apk);
        JSONArray ms = latest.optJSONArray("mirrors");
        if (ms != null) {
            for (int i = 0; i < ms.length(); i++) {
                String u = ms.optString(i, "");
                if (!u.isEmpty() && !urls.contains(u)) urls.add(u);
            }
        }
        if (urls.isEmpty()) return;

        final String[] arr = urls.toArray(new String[0]);
        final boolean[] okFlags = new boolean[arr.length];
        final long[] cost = new long[arr.length];

        ExecutorService pool = Executors.newFixedThreadPool(arr.length);
        for (int i = 0; i < arr.length; i++) {
            final int idx = i;
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    long t0 = System.currentTimeMillis();
                    okFlags[idx] = probeUrl(arr[idx]);
                    cost[idx] = System.currentTimeMillis() - t0;
                }
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(PROBE_WAIT, TimeUnit.MILLISECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length; i++) {
            try {
                JSONObject d = new JSONObject();
                d.put("url", arr[i]);
                d.put("ok", okFlags[i]);
                d.put("ms", cost[i]);
                /* 三种来源在界面上要能分开说：GitHub 直链 / 官网直下 / 第三方 CDN。
                   官网那条是本项目自己的站点（WorkBuddy 托管），国内通常最快，
                   但它依赖分享码，链接换站点就会失效——所以只认域名，不认整条地址。 */
                String kind;
                if (!apk.isEmpty() && i == 0) {
                    kind = "github";
                } else if (arr[i].contains(".workbuddy.host")) {
                    kind = "site";
                } else {
                    kind = "cdn";
                }
                d.put("kind", kind);
                out.put(d);
            } catch (Exception ignored) { }
        }
        try {
            latest.put("downloads", out);
        } catch (Exception ignored) { }
    }

    /** 取 1 个字节验证地址可用性；拿到 200/206 就算通。 */
    private static boolean probeUrl(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("Range", "bytes=0-0");
            c.setRequestProperty("User-Agent", UA);
            c.setConnectTimeout(PROBE_TIMEOUT);
            c.setReadTimeout(PROBE_TIMEOUT);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            return code == 200 || code == 206;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Exception ignored) { }
            }
        }
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
        String body = j.optString("body", "");
        o.put("notes", summarize(body));
        /* 完整 Markdown 原文也带上，前端直接渲染成 HTML。
           notes 是给旧客户端准备的纯文本摘要，两份并存谁都不吃亏。 */
        o.put("notesMd", body.length() > NOTE_MD_MAX ? body.substring(0, NOTE_MD_MAX) : body);

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
     * 把 Release 正文抽成弹层里能显示的纯文本。
     *
     * <p>规则：
     * <ul>
     *   <li>跳过代码块（``` 之间）和一级标题；</li>
     *   <li>二级/三级标题保留为小标题，前面空一行；</li>
     *   <li>列表符号换成「·」；</li>
     *   <li>表格转成「· 左列 → 右列」，表头与分隔行跳过——纯文本里一排竖线没法看；</li>
     *   <li>去掉 **加粗** 与 `行内代码` 的记号。</li>
     * </ul>
     *
     * <p><b>上限看字符数，不看行数。</b>之前是「最多 4 行、每行 70 字、撞到下一个小标题就停」，
     * 结果 1.1.1 的说明只显示出两行——标题加一句概述就没了，用户根本看不到修了什么。
     * 行数限制对「短行多」的说明特别不友好，所以改成按字符封顶，剩下的交给弹层滚动。
     */
    /** 表格分隔行，如 |---|:--:|。用字符集合判断，避免多层转义把正则写坏。 */
    private static boolean isTableSep(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '|' && c != '-' && c != ':' && c != ' ') return false;
        }
        return true;
    }

    /** 去掉行首的 ## / ###，剩下的当小标题文字。 */
    private static String stripHashes(String t) {
        int i = 0;
        while (i < t.length() && t.charAt(i) == '#') i++;
        return t.substring(i).trim();
    }

    static String summarize(String body) {
        if (body == null) return "";
        String[] ls = body.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean inCode = false;
        String pendingHead = null;   // 小标题先存着，等它下面真有内容再输出

        for (int i = 0; i < ls.length; i++) {
            String t = ls[i].trim();
            if (t.startsWith("```")) { inCode = !inCode; continue; }
            if (inCode || t.isEmpty()) continue;
            if (t.startsWith("# ")) continue;                 // 一级标题不要，正文里有版本号

            if (t.startsWith("|")) {
                String next = (i + 1 < ls.length) ? ls[i + 1].trim() : "";
                if (isTableSep(t)) continue;                 // 分隔行 |---|
                if (isTableSep(next)) continue;              // 表头（下一行是分隔行）
                String[] cells = t.replaceAll("^\\|", "").replaceAll("\\|$", "").split("\\|");
                StringBuilder row = new StringBuilder("· ");
                boolean first = true;
                for (String c : cells) {
                    c = c.trim();
                    if (c.isEmpty()) continue;
                    if (!first) row.append(" → ");
                    row.append(c);
                    first = false;
                }
                t = row.toString();
            } else if (t.startsWith("## ") || t.startsWith("### ")) {
                pendingHead = stripHashes(t);
                continue;
            } else if (t.startsWith("- ") || t.startsWith("* ")) {
                t = "· " + t.substring(2);
            } else if (t.startsWith("> ")) {
                t = t.substring(2);
            }

            if (pendingHead != null) {
                if (sb.length() > 0) { sb.append('\n'); sb.append('\n'); }
                sb.append(pendingHead);
                pendingHead = null;
            }
            t = t.replace("**", "").replace("`", "");
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
            if (sb.length() >= NOTE_MAX) {
                int cut = NOTE_MAX;
                if (cut > 0 && Character.isHighSurrogate(sb.charAt(cut - 1))) cut--;
                sb.setLength(cut);
                sb.append("…（已截断，完整说明见下方链接）");
                break;
            }
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
            c.setRequestProperty("User-Agent", UA);
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
