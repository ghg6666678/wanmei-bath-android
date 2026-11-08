package com.wanmei.bath;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends android.app.Activity {

    static final Api api = new Api();

    private WebView web;
    private JSONObject cfg = new JSONObject();
    private Login login;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        applySystemBars();

        cfg = Store.load(this);
        api.setConfig(cfg);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        root.setBackgroundColor(isDark() ? 0xFF0F1418 : 0xFFF4F6F8);

        web = new WebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        root.addView(web);
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setTextZoom(100);
        // 深色模式交给前端 CSS 的 prefers-color-scheme，不用 force-dark
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleScheme(url);
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");

        web.loadUrl("file:///android_asset/index.html");
    }

    private boolean isDark() {
        int m = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applySystemBars() {
        boolean dark = isDark();
        getWindow().setStatusBarColor(dark ? 0xFF0F1418 : 0xFFF4F6F8);
        getWindow().setNavigationBarColor(dark ? 0xFF0F1418 : 0xFFF4F6F8);
        View dv = getWindow().getDecorView();
        int f = dv.getSystemUiVisibility();
        if (dark) f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            if (dark) f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        dv.setSystemUiVisibility(f);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applySystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到前台都让页面刷一次（登录态可能已过期），前端自己做了 30 秒防抖
        if (web != null) {
            web.evaluateJavascript("(window.__onShow ? window.__onShow() : 0)", null);
        }
    }

    private boolean handleScheme(String url) {
        if (url == null) return true;
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
            return false;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) { }
        return true;
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.__onBack ? window.__onBack() : false)", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    // ================= JS 桥 =================

    private void post(String id, String json) {
        final String js = "window.__apiDone(" + JSONObject.quote(id) + "," + JSONObject.quote(json) + ")";
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    private static String err(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", msg == null ? "未知错误" : msg);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"内部错误\"}";
        }
    }

    public class Bridge {

        @JavascriptInterface
        public void apiAsync(String id, String path, String method, String body) {
            new Thread(() -> post(id, handle(path, method, body))).start();
        }

        /** 发送短信验证码 */
        @JavascriptInterface
        public void sendCode(String id, String phone) {
            new Thread(() -> {
                try {
                    if (phone == null || phone.trim().length() < 11) throw new Login.Err("手机号格式不对");
                    Login lg = getLogin();
                    lg.sendCode(phone.trim());
                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("message", "验证码已发送");
                    post(id, o.toString());
                } catch (Exception e) {
                    post(id, err(msg(e)));
                }
            }).start();
        }

        /** 短信登录；成功后 session 即可用于用水接口 */
        @JavascriptInterface
        public void doLogin(String id, String phone, String code) {
            new Thread(() -> {
                try {
                    if (code == null || code.trim().length() < 4) throw new Login.Err("验证码不完整");
                    Login lg = getLogin();
                    JSONObject user = lg.loginSms(phone.trim(), code.trim());

                    JSONObject c = new JSONObject();
                    c.put("session", lg.sessionId);
                    c.put("token", lg.sessionId);
                    c.put("phone", phone.trim());
                    c.put("name", user.optString("name", ""));
                    c.put("customerCode", user.optString("customId", ""));
                    c.put("customerid", user.optString("ecardCustomerid", ""));
                    c.put("outid", firstNonEmpty(user, "authStuNo", "outid", "account", "userName"));
                    c.put("school", firstNonEmpty(user, "customName_", "schoolName", "customerName", "campusName"));
                    Store.save(MainActivity.this, c);
                    cfg = c;
                    api.setConfig(c);
                    login = null;

                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("message", "登录成功");
                    o.put("config", publicConfig());
                    post(id, o.toString());
                } catch (Exception e) {
                    post(id, err(msg(e)));
                }
            }).start();
        }

        @JavascriptInterface
        public void logout(String id) {
            Store.clear(MainActivity.this);
            cfg = new JSONObject();
            api.setConfig(cfg);
            login = null;
            JSONObject o = new JSONObject();
            try {
                o.put("ok", true);
                o.put("message", "已退出登录");
            } catch (Exception ignored) { }
            post(id, o.toString());
        }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> handleScheme(url));
        }

        @JavascriptInterface
        public String deviceInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("model", Build.MODEL);
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("hasToken", api.hasToken());
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        /** 旧版「置顶」接口，保留以兼容已存数据 */
        @JavascriptInterface
        public String getPinRoom() {
            return getSharedPreferences("bath", MODE_PRIVATE).getString("pinRoom", "");
        }

        @JavascriptInterface
        public void setPinRoom(String code) {
            getSharedPreferences("bath", MODE_PRIVATE).edit()
                    .putString("pinRoom", code == null ? "" : code).apply();
        }

        /** 星标宿舍楼（楼栋名，不含 A/B 区），存原生避免 file:// 下 localStorage 失效 */
        @JavascriptInterface
        public String getStarDorm() {
            return getSharedPreferences("bath", MODE_PRIVATE).getString("starDorm", "");
        }

        @JavascriptInterface
        public void setStarDorm(String name) {
            getSharedPreferences("bath", MODE_PRIVATE).edit()
                    .putString("starDorm", name == null ? "" : name).apply();
        }
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
        }
        return "";
    }

    private synchronized Login getLogin() throws Exception {
        if (login == null || login.sessionId == null || login.sessionId.isEmpty()) {
            Login l = new Login();
            l.exchangeKey();
            login = l;
        }
        return login;
    }

    // ================= 接口分发 =================

    private JSONObject publicConfig() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", cfg.optString("name", ""));
            o.put("outid", cfg.optString("outid", ""));
            o.put("school", cfg.optString("school", ""));
            o.put("phone", cfg.optString("phone", ""));
            o.put("hasToken", api.hasToken());
        } catch (Exception ignored) { }
        return o;
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        return m;
    }

    private String handle(String path, String method, String bodyRaw) {
        if (!api.hasToken()) {
            return err("还没有登录，请先用手机号登录");
        }
        try {
            return dispatch(path, method, bodyRaw).toString();
        } catch (Api.Expired e) {
            return err("登录已失效，请重新登录");
        } catch (Exception e) {
            return err(msg(e));
        }
    }

    private JSONObject dispatch(String path, String method, String bodyRaw) throws Exception {
        JSONObject body = new JSONObject();
        if (bodyRaw != null && !bodyRaw.trim().isEmpty()) {
            try {
                body = new JSONObject(bodyRaw);
            } catch (Exception ignored) { }
        }

        if (path.startsWith("/api/state")) {
            JSONObject b = api.bathrooms();
            // bathrooms 里也带一个 oddfare，但实测恒为 "0.0"（占位值），不能用来显示余额，
            // 必须单独查 zfbgetcustoddfare 才是准确值
            double bal = api.balance();
            JSONObject lim = api.getSettings(false);

            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("config", publicConfig());
            o.put("balance", bal);
            o.put("orders", b.getJSONArray("orders"));
            o.put("bathrooms", b.getJSONArray("list"));
            o.put("serverTime", b.optString("serverTime", ""));
            o.put("limits", lim);
            o.put("settings", lim);
            return o;
        }

        if (path.startsWith("/api/config")) {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("config", publicConfig());
            return o;
        }

        if (path.startsWith("/api/settings")) {
            if ("POST".equalsIgnoreCase(method)) {
                double fare = body.optDouble("fare", -1);
                int tmin = body.optInt("time", -1);
                if (fare <= 0 || tmin <= 0) throw new Api.Fail("缺少参数");
                if (tmin < 1 || tmin > 120) throw new Api.Fail("限时需在 1–120 分钟");
                // 统一换算成「分」做整数判断，避免 0.05 这类值的浮点误差
                long fen = Math.round(fare * 100);
                // 5000 元（500000 分）是官方的「不限额」
                if (fen < 100 || (fen > 2000 && fen != 500000))
                    throw new Api.Fail("限额需在 1.00–20.00 元，或选择「不限」");
                if (fen != 500000 && fen % 5 != 0)
                    throw new Api.Fail("金额需为 0.05 元的整数倍（如 2.35）");
                boolean ok = api.saveSettings((int) fen, tmin);
                JSONObject o = new JSONObject();
                o.put("ok", ok);
                o.put("message", ok ? "已保存" : "保存失败");
                return o;
            }
            JSONObject lim = api.getSettings(true);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("settings", lim);
            o.put("limits", lim);
            return o;
        }

        if (path.startsWith("/api/records")) {
            String month = null;
            int qi = path.indexOf("month=");
            if (qi >= 0) {
                month = path.substring(qi + 6);
                int amp = month.indexOf('&');
                if (amp >= 0) month = month.substring(0, amp);
            }
            JSONObject d = api.records(month, 1);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("data", d.opt("data"));
            return o;
        }

        if (path.startsWith("/api/book")) {
            String code = body.optString("bathroomcode", "");
            String dt = body.optString("datetime", "");
            String vt = body.optString("verificationtype", "2");
            if (code.isEmpty()) throw new Api.Fail("缺少浴室编号");

            JSONObject b = api.bathrooms();
            if (dt.isEmpty()) dt = nowFromServer(b.optString("serverTime", ""));

            JSONArray rooms = b.getJSONArray("list");
            JSONObject room = null;
            for (int i = 0; i < rooms.length(); i++) {
                JSONObject x = rooms.optJSONObject(i);
                if (x != null && code.equals(x.optString("bathroomcode"))) { room = x; break; }
            }
            if (room == null) throw new Api.Fail("找不到该浴室");

            String close = room.optString("businessclosetime", "23:59:59");
            String hm = dt.length() >= 19 ? dt.substring(11, 19) : "";
            if (!hm.isEmpty() && hm.compareTo(close) > 0) {
                throw new Api.Fail("超出营业时间（" + close + " 关门）");
            }

            JSONObject lim = api.getSettings(false);
            int maxFare = lim.optInt("max_fare_limit", room.optInt("max_fare_limit", 200));
            int maxTime = lim.optInt("max_time_limit", room.optInt("max_time_limit", 30));
            if (maxFare <= 0) maxFare = 200;
            if (maxTime <= 0) maxTime = 30;

            JSONObject r = api.book(code, dt, vt, maxFare, maxTime);
            boolean ok = "0".equals(r.optString("result"));
            JSONObject data = r.optJSONObject("data");
            String vcode = data == null ? "" : data.optString("verificationcode", "");

            JSONObject o = new JSONObject();
            o.put("ok", ok);
            o.put("message", ok ? "取码成功" : r.optString("message", "预约失败"));
            o.put("data", data == null ? new JSONObject() : data);
            o.put("verificationcode", vcode);
            return o;
        }

        if (path.startsWith("/api/cancel")) {
            String orderno = body.optString("orderno", "");
            if (orderno.isEmpty()) throw new Api.Fail("缺少 orderno");
            boolean ok = api.cancel(orderno);
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            o.put("message", ok ? "已取消预约" : "取消失败");
            return o;
        }

        if (path.startsWith("/api/time")) {
            JSONObject b = api.bathrooms();
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("serverTime", b.optString("serverTime", ""));
            o.put("localTime", new SimpleDateFormat("HHmmss", Locale.CHINA).format(new Date()));
            return o;
        }

        throw new Api.Fail("未知接口: " + path);
    }

    /** 用服务端下发的 HHMMSS 生成本次取码时间（秒置 0，避开本地时钟偏差） */
    private static String nowFromServer(String hhmmss) {
        Date now = new Date();
        String ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(now);
        String hh, mm;
        if (hhmmss != null && hhmmss.length() >= 6) {
            hh = hhmmss.substring(0, 2);
            mm = hhmmss.substring(2, 4);
        } else {
            String t = new SimpleDateFormat("HH:mm", Locale.CHINA).format(now);
            hh = t.substring(0, 2);
            mm = t.substring(3, 5);
        }
        return ymd + " " + hh + ":" + mm + ":00";
    }
}
