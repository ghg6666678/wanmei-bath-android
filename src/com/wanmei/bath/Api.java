package com.wanmei.bath;

import android.os.Build;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 完美校园「智慧用水」协议实现。
 *
 * 逆向来源：xqh5.17wanxiao.com/water3to1 前端 JS
 *   POST https://vwater.59wanmei.com/usewater/api/use/water/h5/WXInvokFront/
 *   签名：AES-ECB-PKCS7( uuid + "-" + timestamp_ms , key = "wanmeixiaoyuan11" )
 *         放在请求头 encrypt，配合 uuid / timestamp
 */
public class Api {

    static final String BASE = "https://vwater.59wanmei.com/usewater";
    static final String H5_ORIGIN = "https://xqh5.17wanxiao.com";
    static final String UA = "Mozilla/5.0 (Linux; Android " + androidVer() + "; " + deviceModel() + ")"
            + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.179 Mobile Safari/537.36 wanxiao/5.8.36";
    static final String SIGN_KEY = "wanmeixiaoyuan11";
    static final int LIMIT_BATH = 1;

    /**
     * 真实机型。去掉非 ASCII 字符，空格换成下划线——User-Agent 必须是 ASCII，
     * 且空格会截断该字段。取不到时退回 "Android"。
     */
    static String deviceModel() {
        String m = Build.MODEL == null ? "" : Build.MODEL.trim();
        StringBuilder sb = new StringBuilder(m.length());
        for (int i = 0; i < m.length(); i++) {
            char c = m.charAt(i);
            if (c >= 0x20 && c <= 0x7E) sb.append(c == ' ' ? '_' : c);
        }
        String out = sb.toString();
        return out.isEmpty() ? "Android" : out;
    }

    /** 真实系统版本，例如 "16"、"14"。 */
    static String androidVer() {
        String v = Build.VERSION.RELEASE;
        if (v == null) return "14";
        v = v.trim();
        return v.isEmpty() ? "14" : v;
    }

    private String token = "";
    private String outid = "";
    private String customerid = "";
    private String customerCode = "";
    private String name = "";
    private String school = "";

    private String uuid;
    private String page;
    private long uuidAt;
    private final Map<String, String> cookies = new HashMap<>();

    private JSONArray lastRooms = new JSONArray();
    private JSONArray lastOrders = new JSONArray();
    private JSONObject cachedLimit;

    public static class Expired extends Exception {
        public Expired(String m) { super(m); }
    }

    public static class Fail extends Exception {
        public Fail(String m) { super(m); }
    }

    // ---------------- 配置 ----------------

    public synchronized void setConfig(JSONObject cfg) {
        token = cfg.optString("token", "");
        outid = cfg.optString("outid", "");
        customerid = cfg.optString("customerid", "");
        customerCode = cfg.optString("customerCode", "");
        name = cfg.optString("name", "");
        school = cfg.optString("school", "");
        resetSession();
    }

    public synchronized void resetSession() {
        uuid = null;
        page = null;
        uuidAt = 0;
        cachedLimit = null;
    }

    public String getName() { return name; }
    public String getSchool() { return school; }
    public String getOutid() { return outid; }
    public String getToken() { return token; }
    public boolean hasToken() { return token != null && token.length() >= 30; }

    // ---------------- 底层 HTTP ----------------

    private static class Resp {
        int code;
        String body = "";
        Map<String, String> headers = new HashMap<>();
        String header(String k) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(k)) return e.getValue();
            }
            return null;
        }
    }

    private Resp call(String path, String formBody, Map<String, String> extra, boolean follow) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(follow);
        c.setRequestMethod(formBody == null ? "GET" : "POST");
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Origin", H5_ORIGIN);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (page != null) c.setRequestProperty("Referer", page);
        if (!cookies.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : cookies.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            c.setRequestProperty("Cookie", sb.toString());
        }
        if (formBody != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        }
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
        }

        if (formBody != null) {
            OutputStream os = c.getOutputStream();
            os.write(formBody.getBytes("UTF-8"));
            os.flush();
            os.close();
        }

        Resp r = new Resp();
        r.code = c.getResponseCode();

        // 收集 cookie
        Map<String, java.util.List<String>> hf = c.getHeaderFields();
        for (Map.Entry<String, java.util.List<String>> e : hf.entrySet()) {
            if (e.getKey() == null) continue;
            StringBuilder v = new StringBuilder();
            for (String s : e.getValue()) {
                if (v.length() > 0) v.append(", ");
                v.append(s);
            }
            r.headers.put(e.getKey(), v.toString());
            if (e.getKey().equalsIgnoreCase("Set-Cookie")) {
                for (String s : e.getValue()) {
                    int semi = s.indexOf(';');
                    String kv = semi > 0 ? s.substring(0, semi) : s;
                    int eq = kv.indexOf('=');
                    if (eq > 0) cookies.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
        }

        InputStream is = (r.code >= 400) ? c.getErrorStream() : c.getInputStream();
        if (is != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            r.body = new String(bo.toByteArray(), "UTF-8");
        }
        c.disconnect();
        return r;
    }

    // ---------------- 签名 ----------------

    public static String sign(String uuid, String ts) throws Exception {
        SecretKeySpec key = new SecretKeySpec(SIGN_KEY.getBytes("UTF-8"), "AES");
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        byte[] out = c.doFinal((uuid + "-" + ts).getBytes("UTF-8"));
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- 会话 ----------------

    public synchronized void entrance() throws Exception {
        if (!hasToken()) throw new Expired("还没有登录，请先用手机号登录");

        String q = "source=wx&tVer=20260918&versionnum=1050836101"
                + "&customerId=" + enc(customerCode)
                + "&systemType=Android&UAinfo=wanxiao&versioncode=10589101"
                + "&token=" + enc(token);
        Resp r = call("/api/use-water/entrance?" + q, null, null, false);

        String loc = r.header("Location");
        if (loc == null || loc.isEmpty()) {
            throw new Expired("登录已失效，请重新登录（「我的」→ 更新登录）");
        }
        Map<String, String> p = parseQuery(loc);
        String u = p.get("uuid");
        if (u == null || u.isEmpty()) throw new Expired("登录态已失效（uuid 缺失）");

        if (p.containsKey("outid") && p.get("outid").length() > 0) outid = p.get("outid");
        if (p.containsKey("customerid") && p.get("customerid").length() > 0) customerid = p.get("customerid");
        if (p.containsKey("customerCode") && p.get("customerCode").length() > 0) customerCode = p.get("customerCode");

        uuid = u;
        page = loc;
        uuidAt = System.currentTimeMillis();
    }

    private static Map<String, String> parseQuery(String url) {
        Map<String, String> m = new HashMap<>();
        int i = url.indexOf('?');
        if (i < 0) return m;
        String q = url.substring(i + 1);
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            try {
                String k = URLDecoder.decode(kv.substring(0, eq), "UTF-8");
                String v = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                m.put(k, v);
            } catch (Exception ignored) { }
        }
        return m;
    }

    private void ensure() throws Exception {
        if (uuid == null || System.currentTimeMillis() - uuidAt > 240000L) entrance();
    }

    // ---------------- 业务调用 ----------------

    public synchronized JSONObject invoke(String cmd, JSONObject extra, boolean retry) throws Exception {
        ensure();

        JSONObject payload = new JSONObject();
        payload.put("cmd", cmd);
        payload.put("ver", "2.0");
        payload.put("source", "wx");
        if (extra != null) {
            Iterator<String> it = extra.keys();
            while (it.hasNext()) {
                String k = it.next();
                payload.put(k, extra.get(k));
            }
        }

        String ts = String.valueOf(System.currentTimeMillis());
        String encStr = sign(uuid, ts);

        String body = "param=" + enc(payload.toString()) + "&source=wx";
        Map<String, String> h = new HashMap<>();
        h.put("uuid", uuid);
        h.put("timestamp", ts);
        h.put("encrypt", encStr);

        Resp r = call("/api/use/water/h5/WXInvokFront/", body, h, false);

        if (r.code != 200 || r.body == null || r.body.isEmpty()) {
            if (retry) {
                resetSession();
                return invoke(cmd, extra, false);
            }
            throw new Fail("接口无响应 (HTTP " + r.code + ")");
        }

        JSONObject outer;
        try {
            outer = new JSONObject(r.body);
        } catch (Exception e) {
            throw new Fail("返回格式异常：" + trim(r.body));
        }

        Object inner = outer.opt("body");
        JSONObject j = null;
        if (inner instanceof JSONObject) {
            j = (JSONObject) inner;
        } else if (inner instanceof String) {
            try {
                j = new JSONObject((String) inner);
            } catch (Exception ignored) { }
        }
        if (j == null) {
            String msg = outer.optString("message_", outer.optString("msg", trim(r.body)));
            throw new Fail(msg);
        }
        // 业务层级的登录失效
        if (j.has("result") && !"0".equals(j.optString("result"))) {
            String msg = j.optString("message", "");
            if (msg.contains("登录") || msg.contains("用户信息")) throw new Expired(msg);
        }
        return j;
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static String ts14() {
        return new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA).format(new Date());
    }

    // ---------------- 具体功能 ----------------

    public synchronized JSONObject bathrooms() throws Exception {
        JSONObject extra = new JSONObject();
        extra.put("outid", outid);
        JSONObject d = invoke("zfbgetbathroomlist", extra, true);
        JSONObject data = d.optJSONObject("data");
        JSONObject out = new JSONObject();
        if (data == null) {
            out.put("orders", new JSONArray());
            out.put("list", new JSONArray());
            out.put("serverTime", "");
            return out;
        }
        JSONArray orders = data.optJSONArray("orderbathroomlist");
        JSONArray list = data.optJSONArray("bathroomlist");
        if (orders == null) orders = new JSONArray();
        if (list == null) list = new JSONArray();
        out.put("orders", orders);
        out.put("list", list);
        out.put("oddfare", data.opt("oddfare"));
        out.put("serverTime", data.optString("nowdate", ""));
        lastRooms = list;
        lastOrders = orders;
        return out;
    }

    public synchronized double balance() throws Exception {
        JSONObject extra = new JSONObject();
        extra.put("outid", outid);
        extra.put("timestamp", ts14());
        JSONObject d = invoke("zfbgetcustoddfare", extra, true);
        try {
            return Double.parseDouble(d.optString("oddfare", "0"));
        } catch (Exception e) {
            return 0d;
        }
    }

    public synchronized JSONObject getSettings(boolean force) throws Exception {
        if (cachedLimit != null && !force) return cachedLimit;
        JSONObject extra = new JSONObject();
        extra.put("outid", outid);
        extra.put("limittype", LIMIT_BATH);
        extra.put("timestamp", ts14());
        JSONObject d = invoke("zfbquerylimit", extra, true);
        JSONObject data = d.optJSONObject("data");
        JSONObject out = new JSONObject();
        if (data != null) {
            out.put("max_fare_limit", data.opt("max_fare_limit"));
            out.put("max_time_limit", data.opt("max_time_limit"));
        }
        cachedLimit = out;
        return out;
    }

    /** fareFen 单位为分：浴室按 0.05 元扣费，合法值都是 5 的倍数。 */
    public synchronized boolean saveSettings(int fareFen, int timeMin) throws Exception {
        JSONObject extra = new JSONObject();
        extra.put("outid", outid);
        extra.put("timestamp", ts14());
        extra.put("limittype", LIMIT_BATH);
        extra.put("max_fare_limit", fareFen);
        extra.put("max_time_limit", timeMin);
        JSONObject d = invoke("zfbsavelimit", extra, true);
        cachedLimit = null;
        return "0".equals(d.optString("result"));
    }

    public synchronized JSONObject book(String bathroomCode, String datetime,
                                        String verificationType, int maxFare, int maxTime) throws Exception {
        JSONObject extra = new JSONObject();
        extra.put("orderno", "");
        extra.put("bathroomcode", bathroomCode);
        extra.put("outid", outid);
        extra.put("orderdatetime", datetime);
        extra.put("verificationtype", verificationType);
        extra.put("timetype", "0");
        extra.put("ecard_customerid", customerid);
        extra.put("verificationtelephone", outid);
        extra.put("max_fare_limit", maxFare);
        extra.put("max_time_limit", maxTime);
        extra.put("timestamp", ts14());
        extra.put("ordertype", 0);
        return invoke("zfbskorderbathroom", extra, true);
    }

    public synchronized boolean cancel(String orderNo) throws Exception {
        JSONObject extra = new JSONObject();
        extra.put("outid", outid);
        extra.put("closetype", 0);
        extra.put("orderno", orderNo);
        extra.put("timestamp", ts14());
        JSONObject d = invoke("zfbrevokeorderbathroom", extra, true);
        return "0".equals(d.optString("result"));
    }

    public synchronized JSONObject records(String month, int page) throws Exception {
        JSONObject extra = new JSONObject();
        extra.put("outid", outid);
        extra.put("page_index", page);
        extra.put("page_rows", 20);
        extra.put("timestamp", ts14());
        if (month != null && !month.isEmpty()) extra.put("month", month);
        return invoke("getwaterconsume", extra, true);
    }

    public JSONArray cachedRooms() { return lastRooms; }
    public JSONArray cachedOrders() { return lastOrders; }
}
