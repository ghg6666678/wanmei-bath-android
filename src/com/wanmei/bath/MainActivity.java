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

        /** 用系统浏览器打开（下载页、镜像、项目主页），不占用 App 自己的 WebView */
        @JavascriptInterface
        public void openExternal(String url) {
            if (url == null || url.trim().isEmpty()) return;
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ignored) { }
            });
        }

        /** 复制文本（校验码、下载链接） */
        @JavascriptInterface
        public void copyText(String text) {
            if (text == null) return;
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("bath", text));
            } catch (Exception ignored) { }
        }

        /**
         * 直接把新版本 APK 下载到手机（系统下载器）。
         * 不指定保存目录，落在系统共享下载缓存里，由通知引导安装，不需要存储权限。
         */
        @JavascriptInterface
        public void downloadApk(String id, String url, String fileName) {
            new Thread(() -> {
                try {
                    if (url == null || url.trim().isEmpty()) throw new IllegalStateException("下载地址为空");
                    android.app.DownloadManager dm =
                            (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm == null) throw new IllegalStateException("系统没有下载服务");
                    String name = (fileName == null || fileName.trim().isEmpty())
                            ? "zhishui.apk" : fileName.trim();
                    android.app.DownloadManager.Request req =
                            new android.app.DownloadManager.Request(Uri.parse(url.trim()));
                    req.setTitle("智水 " + name);
                    req.setDescription("正在下载新版本 APK");
                    req.setMimeType("application/vnd.android.package-archive");
                    req.setNotificationVisibility(
                            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setAllowedNetworkTypes(
                            android.app.DownloadManager.Request.NETWORK_MOBILE
                                    | android.app.DownloadManager.Request.NETWORK_WIFI);
                    long did = dm.enqueue(req);
                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("message", "已加入下载队列，完成后点通知安装");
                    o.put("data", String.valueOf(did));
                    post(id, o.toString());
                } catch (Exception e) {
                    post(id, err(msg(e)));
                }
            }).start();
        }

        /** 当前安装的版本号，取自安装包本身，不是前端写死的 */
        @JavascriptInterface
        public String appVersion() {
            return appVersionObj().toString();
        }

        /**
         * 检查更新。GitHub 不通时由 Update 内部回落（内置清单 + 缓存），
         * 这里只负责把结果回传，前端统一按同一份 JSON 结构渲染。
         */
        @JavascriptInterface
        public void checkUpdateAsync(String id) {
            new Thread(() -> {
                try {
                    JSONObject r = new Update().check(MainActivity.this, appVersionObj());
                    post(id, r.toString());
                } catch (Exception e) {
                    post(id, err(msg(e)));
                }
            }).start();
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

        /** 首次启动标记：看过一次引导后就不再弹。存原生，file:// 下 localStorage 不可靠。 */
        @JavascriptInterface
        public String getFirstRunFlag() {
            return getSharedPreferences("bath", MODE_PRIVATE).getString("firstRunShown", "");
        }

        @JavascriptInterface
        public void setFirstRunFlag(String v) {
            getSharedPreferences("bath", MODE_PRIVATE).edit()
                    .putString("firstRunShown", v == null ? "" : v).apply();
        }

        /* ---------- 取码通知：锁屏上直接看验证码 ---------- */

        /** 把「浴室名 + 验证码」推到常驻通知。
            锁屏不点开 App 就能看到；只显示码，不显示倒计时（1.1.3.11 起），
            到点由 timeoutAfter 静默自动消失，不需要额外的定时任务。 */
        @JavascriptInterface
        public void showOrderCode(String name, String code, long expireAtMs) {
            notifyOrderCode(name, code, expireAtMs);
        }

        @JavascriptInterface
        public void clearOrderCode() {
            cancelOrderCode();
        }

        /** 通知是否真的能在锁屏露出来。前端据此决定要不要给「去设置」的提示。
            返回 {ok, enabled, channel, silent, importance, locked}。 */
        @JavascriptInterface
        public String notifyStatus() {
            return codeNotifyStatus();
        }

        /** 跳到本应用的通知总设置页，让用户改允许通知 / 锁屏显示通知。 */
        @JavascriptInterface
        public void openNotifySettings() {
            gotoCodeNotifySettings();
        }

        /** 用户点过「知道了」就不再提示锁屏设置。存原生，file:// 下 localStorage 不可靠。 */
        @JavascriptInterface
        public String getNotifyHintDismissed() {
            return getSharedPreferences("bath", MODE_PRIVATE).getString("notifyHint", "");
        }

        @JavascriptInterface
        public void setNotifyHintDismissed(String v) {
            getSharedPreferences("bath", MODE_PRIVATE).edit()
                    .putString("notifyHint", v == null ? "" : v).apply();
        }
    }

    // ---------------- 验证码显示通知 ----------------

    /* 通道 id 带 v2：通道一旦创建，重要级别就改不了了（系统不允许程序覆盖用户
       改过的设置）。第一版用了 IMPORTANCE_LOW，在「子类别」里被归成「静默通知」，
       结果锁屏不显示。所以必须换一个新 id 重建，旧的在 ensureCodeChannel 里删掉。 */
    private static final String CH_CODE = "bath_code_v2";
    private static final String CH_CODE_OLD = "bath_code";
    private static final int NID_CODE = 1001;
    private static final int REQ_NOTIFY = 1001;

    /* Android 16 QPR1 起，「请求提升」靠的是 extras 里的这个键，不是 flag。
       AndroidX 的 NotificationCompat.Builder.setRequestPromotedOngoing(true)
       内部就是往 extras 写它（源码注释说明字段名抄自 android.app.Notification）。
       官方文档把它列为实时更新的硬性条件之一。 */
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing";
    private static final String EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText";

    /* FLAG_PROMOTED_ONGOING（API 36）= 262144 (0x40000)，是【系统】给合格通知
       打上的结果标记，应用不能自己设：AOSP NotificationManagerService 明确有
       「notification.flags &= ~FLAG_PROMOTED_ONGOING; // Apps cannot set this flag」。
       这里仍保留一次兼容写入（少数 ROM 可能直接读应用设的位），写错也无害。
       真正决定是否提升的，是下面的 EXTRA_REQUEST_PROMOTED_ONGOING + 通知特征。 */
    private static final int FLAG_PROMOTED_ONGOING = 262144;

    /* 用户还没授权时先把要发的内容存着，授权回来再补发，
       免得「第一次装完正好有预约」这个场景白发一次 */
    private String pendName;
    private String pendCode;
    private long pendExpireMs;
    private boolean notifyAsked;

    private void ensureCodeChannel() {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        /* 清掉第一版那个「静默」通道，否则设置里会留一个永远不显示在锁屏的旧类别 */
        try {
            if (nm.getNotificationChannel(CH_CODE_OLD) != null) {
                nm.deleteNotificationChannel(CH_CODE_OLD);
            }
        } catch (Exception ignored) { }
        if (nm.getNotificationChannel(CH_CODE) != null) return;
        /* 用 IMPORTANCE_DEFAULT 而不是 LOW：LOW 会被系统归进「静默通知」，
           静默通知不点亮锁屏、也不在锁屏上展开——那这个功能就白做了。
           DEFAULT 允许系统发出提示音并显示在锁屏上；同一条通知刷新时有
           onlyAlertOnce 兜着，不会反复响。 */
        android.app.NotificationChannel ch = new android.app.NotificationChannel(
                CH_CODE, getString(R.string.notify_channel_name),
                android.app.NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription(getString(R.string.notify_channel_desc));
        ch.setShowBadge(false);
        /* 锁屏可见：即使系统设置里选了「隐藏敏感通知内容」，这一条也要露出来
           —— 用户要的正是「点亮屏幕就能看到」，藏起来就没意义了 */
        ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private void notifyOrderCode(String name, String code, long expireAtMs) {
        try {
            String c = code == null ? "" : code.trim();
            if (c.isEmpty()) { cancelOrderCode(); return; }
            long left = expireAtMs - System.currentTimeMillis();
            // 已经过期（或没算出有效时间）就不发，避免出现一个 0:00 的常驻通知
            if (left <= 0) { cancelOrderCode(); return; }

            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendName = name;
                pendCode = c;
                pendExpireMs = expireAtMs;
                requestNotifyPermission();
                return;
            }

            ensureCodeChannel();
            String room = (name == null || name.trim().isEmpty()) ? "已预约" : name.trim();

            /* 验证码放标题、浴室名退到副行：同一栋楼不会变，每次要看的是那六个数字，
               锁屏上标题字号最大、最粗，才是该给它占的位置。
               系统自带的「取单码」实时卡片就是这个思路：一个图标 + 一个大号数字，
               旁边没有多余信息。第三方应用改不了系统卡片的字号（平台会剥掉
               Notification 里的文字尺寸 span），所以能做的是把「最显眼的字段」
               全给验证码：芯片文案 shortCriticalText + 通知标题 contentTitle。

               【不要再设 chronometer】之前 setUsesChronometer(true) +
               setChronometerCountDown(true) + setWhen(失效时刻) 会在普通通知头部
               和实时卡片里各渲染一串「HH:MM:SS」倒计时。失效时刻往往是当天结束，
               这串数字对用户没有任何意义。showWhen 也要显式关掉：Android 16 的
               芯片文案在没有 shortCriticalText 时会退回「距 when 还剩多久」，
               关掉它才算把倒计时来源彻底断干净。
               setTimeoutAfter 只是到点自动收掉通知，不产生任何可见文字，保留。 */
            android.app.Notification.Builder b =
                    new android.app.Notification.Builder(this, CH_CODE)
                            .setSmallIcon(R.drawable.ic_stat_bath)
                            .setContentTitle(c)
                            /* 1.1.3.12：删掉 setSubText("验证码")。
                               厂商会把 subText 单独排成第三行，于是「验证码」这个
                               标签和它要说明的六位数反而隔了两行。通道名已经是
                               「验证码显示」，芯片和标题又都放码，不必再重复一次。 */
                            .setContentText(room + " · 当日有效")
                            .setOngoing(true)          // 常驻，划不掉
                            .setOnlyAlertOnce(true)    // 刷新时不重复响铃
                            .setShowWhen(false)        // 不显示时间/剩余时间
                            .setTimeoutAfter(left)     // 到点自动消失（不显示倒计时）
                            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                            .setCategory(android.app.Notification.CATEGORY_STATUS)
                            .setContentIntent(pendingOpenApp());

            /* 给通知加上「可被提升」的特征（Android 16 实时通知 / 上岛）。
               是否真的被提升由系统决定，不在这里判断——见 applyLiveUpdateFlavor 注释。 */
            applyLiveUpdateFlavor(b, c, room);

            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NID_CODE, b.build());
        } catch (Exception ignored) {
            // 通知失败不能影响取码主流程
        }
    }

    private void cancelOrderCode() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NID_CODE);
        } catch (Exception ignored) { }
    }

    /** 通知能不能真的露在锁屏上。前端据此决定要不要给「去设置」的提示。
        注意：厂商的「静默通知」开关没有公开 API，这里只能用好坏程度的近似
        ——importance <= LOW 视为静默；DEFAULT 及以上则认为是正常通知。 */
    private String codeNotifyStatus() {
        JSONObject o = new JSONObject();
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            boolean perm = Build.VERSION.SDK_INT < 33
                    || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean enabled = nm != null && nm.areNotificationsEnabled() && perm;
            boolean has = false;
            int imp = -1;
            boolean silent = true;
            if (nm != null) {
                android.app.NotificationChannel ch = nm.getNotificationChannel(CH_CODE);
                if (ch != null) {
                    has = true;
                    imp = ch.getImportance();
                    silent = imp <= android.app.NotificationManager.IMPORTANCE_LOW;
                }
            }
            o.put("enabled", enabled);
            o.put("perm", perm);
            o.put("channel", has);
            o.put("importance", imp);
            o.put("silent", silent);
            /* 注意语义：canPostPromotedNotifications() 问的是「用户有没有允许本应用发提升通知」，
               不是「系统支不支持实时活动」，更不是「这条通知已经被提升」。
               真实结果看 diag.flagged / diag.chars，前端已不再把这个值说成「支持」。
               1.1.3.12 起前端只用 liveWhy 判断「接口是否存在」（no_api / err / no_service
               视为不支持），据此切换提示文案，不拿开关状态冒充设备能力。 */
            String why = promotedSupport();
            o.put("live", "yes".equals(why));   // 兼容旧字段名，含义=用户开关允许
            o.put("promotedAllowed", "yes".equals(why));
            o.put("liveWhy", why);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("diag", promotedDiag());
            o.put("ok", enabled && has && !silent);
        } catch (Exception e) {
            try { o.put("ok", false); } catch (Exception ignored) { }
        }
        return o.toString();
    }

    /** 跳到本应用的「通知」总设置页（母类）。1.1.3.12 起这是唯一的设置入口：
        「流体云显示实时活动」/「实时动态」开关就在「允许通知」正下方。
        故意【不】直达子类别：母类一旦关掉「允许通知」或「锁屏显示通知」，
        子类别里的开关会被系统隐藏或置灰，用户进去也改不了——反而卡在死路上。
        所以统一停在母类，让用户先开父级，再自己往下点「验证码显示」。 */
    private void gotoCodeNotifySettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            // 少数 ROM 没有这个页面，退回应用详情页
            try {
                Intent i2 = new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i2.setData(Uri.parse("package:" + getPackageName()));
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i2);
            } catch (Exception ignored) { }
        }
    }

    /* 1.1.3.12 删除了「直达实时活动开关页」的桥（原先的 openPromotedSettings /
       gotoPromotedSettings）。那个页面在 ColorOS / 澎湃 OS 上基本 resolve 不到，
       最后还是退回 ACTION_APP_NOTIFICATION_SETTINGS；而「流体云显示实时活动」
       （澎湃 OS 4 叫「实时动态」）本来就长在「允许通知」正下方。两个按钮跳同一个
       页面属于重复功能，现在只保留 openNotifySettings 一个入口。 */

    /* ---------- Android 16 原生实时通知（Live Updates）----------
       这是「上岛」的第三条路：不是厂商私有 SDK，也不需要白名单。
       我们按标准发一条「持续进行中」的通知，系统决定把它渲染成什么形态
       —— 兼容这套标准的 ROM（ColorOS 16 流体云、HyperOS 超级岛等）会把
       它提升成岛/云/窗；不兼容就退化成普通常驻通知，不会出错。

       因为本地只编译到 SDK 34，下面这些 API 全走反射，任何一步失败都
       安静退回普通通知（安全失败）。 */

    /** 「我能不能被提升」只用于向用户解释，【不再】用它决定要不要补这些特征。
        它的默认值就是 true，而之前却因为在部分 ROM 上取不到（API 未开放 /
        反射失败）就把整条路挡死——典型的把探测当开关。
        返回 yes / no / no_api / err:xxx */
    private String promotedSupport() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return "no_service";
            java.lang.reflect.Method m = android.app.NotificationManager.class
                    .getMethod("canPostPromotedNotifications");
            Object r = m.invoke(nm);
            if (!(r instanceof Boolean)) return "bad_ret";
            return ((Boolean) r) ? "yes" : "no";
        } catch (NoSuchMethodException e) {
            return "no_api";
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    /** 兼容写入：绝大多数 ROM 会按规范清掉应用自带的这个位，属于「无害但别指望」。
        【不要】把它当成请求提升的手段，见 applyLiveUpdateFlavor。 */
    private void requestPromoted(android.app.Notification.Builder b) {
        try {
            b.getClass().getMethod("setFlag", int.class, boolean.class)
                    .invoke(b, FLAG_PROMOTED_ONGOING, true);
        } catch (Throwable ignored) { }
    }

    /** 给通知补上「可被提升」所需的特征。
        依据官方《创建实时更新通知》(Android 16 QPR1) + 能实际上岛的
        InstallerX-Revived / ModernNotificationBuilder.kt：
          必须声明 POST_PROMOTED_NOTIFICATIONS（清单里已加）
          必须【请求提升】：extras 里写 android.requestPromotedOngoing=true
          必须 ongoing、有 contentTitle、无自定义视图、非分组摘要
          样式须为 Standard / BigText / Call / Progress / Metric
          通道 importance 不能是 IMPORTANCE_MIN（这里是 DEFAULT）
          【不能】setColorized(true)——官方硬性要求，AOSP 旧分支那套
          「isColorizedRequested 才算可提升」的判定已经不适用于 QPR1。

        1.1.3.11 起刻意【不用】ProgressStyle：它是「进度类」实时卡片，
        必然画一条进度条。我们的验证码没有进度可表示，画出来的条毫无意义。
        改用 BigTextStyle（同样在可提升样式白名单内），系统会走「文本类」
        卡片——图标 + 芯片/标题上的验证码，正是系统「取单码提醒」那种排版。
        AOSP Notification.hasPromotableStyle() 对 null / BigTextStyle 都返回 true，
        去掉进度样式不会让通知失去被提升的资格。
        @param shortText 状态栏芯片/岛上显示的那几个字——这里直接放验证码 */
    private void applyLiveUpdateFlavor(android.app.Notification.Builder b,
                                       String shortText, String room) {
        /* 关键：请求提升。AndroidX 的 setRequestPromotedOngoing(true) 就是
           b.getExtras().putBoolean("android.requestPromotedOngoing", true)，
           没有别的动作。之前设 FLAG_PROMOTED_ONGOING 是错的：那个位是系统
           给通知打的「结果」，应用设了会被系统清掉，等于从来没请求过。 */
        b.getExtras().putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
        // 若平台自身提供了这个方法，也调一次（幂等）；没有就只靠 extras
        try {
            b.getClass().getMethod("setRequestPromotedOngoing", boolean.class)
                    .invoke(b, true);
        } catch (Throwable ignored) { }
        requestPromoted(b);   // 兼容少数直接读 flag 的 ROM

        /* 芯片上的极短文案（官方建议 ≤7 字符），反射 + extras 双写兜底 */
        try {
            b.getClass().getMethod("setShortCriticalText", String.class)
                    .invoke(b, shortText);
        } catch (Throwable ignored) { }
        b.getExtras().putString(EXTRA_SHORT_CRITICAL_TEXT, shortText);

        /* 文本类卡片：不设任何进度段，就一条标题（验证码）+ 一行地点。
           setBigContentTitle 再写一次验证码，保证展开后的大视图里最显眼的
           仍然是那串数字，而不是「浴室名」。不要再往 bigText 里塞说明文字，
           系统「取单码」卡片之所以清爽，就是因为它只有码。 */
        b.setStyle(new android.app.Notification.BigTextStyle()
                .setBigContentTitle(shortText)
                .bigText(room));
    }

    /** 读回自己那条通知，看系统最终有没有把它提升，以及为什么没提升。
        canPostPromotedNotifications() 只能说明「用户开关是否允许」，
        hasPromotableCharacteristics() 只能说明「通知本身是否合格」，
        只有 FLAG_PROMOTED_ONGOING 是系统真正提升后的结果标记。
        全部随 notifyStatus 一起给前端显示，不再靠探测结果猜。 */
    private JSONObject promotedDiag() {
        JSONObject o = new JSONObject();
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            boolean found = false, flagged = false, chars = false;
            int flags = 0;
            String req = "", shortText = "", style = "";
            boolean colorized = false;
            /* 1.1.3.11 起用来确认「倒计时来源已彻底关掉」：
               android.showWhen / android.showChronometer 都是 Notification 的公开
               extras 键，读回来就能核对，不用靠肉眼猜。 */
            boolean showWhen = true, chron = false;
            if (nm != null) {
                android.service.notification.StatusBarNotification[] act =
                        nm.getActiveNotifications();
                if (act != null) {
                    for (android.service.notification.StatusBarNotification s : act) {
                        if (s == null || s.getId() != NID_CODE) continue;
                        android.app.Notification n = s.getNotification();
                        if (n == null) continue;
                        found = true;
                        flags = n.flags;
                        flagged = (flags & FLAG_PROMOTED_ONGOING) != 0;
                        if (n.extras != null) {
                            req = n.extras.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, false)
                                    ? "yes" : "no";
                            shortText = n.extras.getString(EXTRA_SHORT_CRITICAL_TEXT, "");
                            colorized = n.extras.getBoolean("android.colorized", false);
                            showWhen = n.extras.getBoolean("android.showWhen", true);
                            chron = n.extras.getBoolean("android.showChronometer", false);
                        }
                        try {
                            Object r = android.app.Notification.class
                                    .getMethod("hasPromotableCharacteristics").invoke(n);
                            chars = (r instanceof Boolean) && ((Boolean) r);
                        } catch (Throwable ignored) { }
                        try {
                            Object r = android.app.Notification.class
                                    .getMethod("getNotificationStyle").invoke(n);
                            if (r instanceof Class) style = ((Class<?>) r).getSimpleName();
                        } catch (Throwable ignored) { }
                        break;
                    }
                }
            }
            o.put("found", found);            // 通知是否还在通知栏
            o.put("req", req);                // 有没有按官方要求「请求提升」
            o.put("short", shortText);        // 芯片文案
            o.put("style", style);            // 实际使用的样式
            o.put("colorized", colorized);    // 应为 false（官方禁止 true）
            o.put("showWhen", showWhen);      // 应为 false：不显示时间
            o.put("chron", chron);            // 应为 false：没有 HH:MM:SS 倒计时
            o.put("chars", chars);            // 系统认为「具备可提升特征」
            o.put("flagged", flagged);        // 系统已打上 FLAG_PROMOTED_ONGOING
            o.put("flags", flags);
            o.put("canPost", promotedSupport());  // 用户开关：yes/no/no_api/...
        } catch (Throwable t) {
            try { o.put("err", t.getClass().getSimpleName()); } catch (Exception ignored) { }
        }
        return o;
    }

    private android.app.PendingIntent pendingOpenApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
        return android.app.PendingIntent.getActivity(this, 0, i, flags);
    }

    private void requestNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33 || notifyAsked) return;
        notifyAsked = true;
        try {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_NOTIFY) return;
        boolean ok = results != null && results.length > 0
                && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (ok && pendCode != null && !pendCode.isEmpty()) {
            notifyOrderCode(pendName, pendCode, pendExpireMs);
        }
    }

    /** 安装包自身的版本信息，取不到时给兜底值，不让前端拿到空对象。 */
    private JSONObject appVersionObj() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", "1.0.0");
            o.put("versionCode", 0);
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            String vn = pi.versionName;
            if (vn != null && !vn.isEmpty()) o.put("version", vn);
            int vc = Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
            o.put("versionCode", vc);
        } catch (Exception ignored) { }
        return o;
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
            /* 缺一卡通客户号或学号 = 官方那边还没绑校园卡，要提示用户去绑 */
            o.put("needCard",
                    cfg.optString("customerid", "").isEmpty() || cfg.optString("outid", "").isEmpty());
        } catch (Exception ignored) { }
        return o;
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        return m;
    }

    private String handle(String path, String method, String bodyRaw) {
        /* /api/config 不要求登录：首次使用还没登录时，前端要靠它判断
           「这个号还没绑校园卡」，好给出绑定指引。 */
        if (!api.hasToken() && !path.startsWith("/api/config")) {
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
            // 服务器时钟：来自最近一次响应的 Date 头（HTTP 强制头，无需额外接口）
            JSONObject sc = new JSONObject();
            sc.put("has", api.hasServerClock());
            sc.put("nowMs", api.serverNowMs());
            sc.put("skewMs", api.serverSkewMs());
            sc.put("from", api.serverClockFrom());
            o.put("srvClock", sc);
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
            // 分页：前端按「记录页」的触底加载传 page，默认第 1 页
            int page = 1;
            int pi = path.indexOf("page=");
            if (pi >= 0) {
                String pv = path.substring(pi + 5);
                int amp = pv.indexOf('&');
                if (amp >= 0) pv = pv.substring(0, amp);
                try {
                    page = Math.max(1, Integer.parseInt(pv.trim()));
                } catch (Exception ignored) { }
            }
            JSONObject d = api.records(month, page);
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
            if (dt.isEmpty()) dt = nowFromServer(b.optString("serverTime", ""), api);

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
            o.put("message", ok ? "预约成功" : r.optString("message", "预约失败"));
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

    /** 用服务端下发的 HHMMSS 生成本次预约时间（秒置 0，避开本地时钟偏差）。
        服务端没下发 nowdate 时，退回到 Date 头测到的服务器时刻；
        两者都没有才用本机时钟。 */
    private static String nowFromServer(String hhmmss, Api api) {
        Date now = new Date();
        long srvMs = api == null ? 0L : api.serverNowMs();
        if (srvMs > 0) now = new Date(srvMs);
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
