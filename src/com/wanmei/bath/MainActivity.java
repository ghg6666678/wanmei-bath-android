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
        // 以当前真实状态为基线，避免首次启动被误判成「权限刚被打开」
        lastNotifyPerm = hasNotifyPermission();

        applySystemBars();

        cfg = Store.load(this);
        api.setConfig(cfg);
        migrateNotifyPermissionDecision();

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
        /* 权限可能在系统设置页里被改动。
           AOSP 的不对称：撤销 POST_NOTIFICATIONS 会 killUid 杀进程（所以回到 App
           是重建，页面从头 loadAll，天然会重发通知）；但【授予不杀进程】，App 还
           停在原来的进程里，只有 onResume 这一次机会感知到变化。
           以前 onResume 只调 __onShow()，而前端有 30 秒防抖，从设置页打个来回
           通常不到 30 秒 → 刷新被挡 → 通知不重发，看起来就是「要重启才生效」。 */
        boolean now = hasNotifyPermission();
        boolean justGranted = !lastNotifyPerm && now;
        lastNotifyPerm = now;
        if (now) markNotifyGrantedEver();

        /* 用户在系统设置里手动打开权限：没有 onRequestPermissionsResult 回调，
           pendCode 只能靠这里补发。授权对话框那条路仍走原来的回调，两者互不干扰。
           即便这里有弹层挡住前端刷新，通知本身也已经补上了。 */
        if (justGranted && pendCode != null && !pendCode.isEmpty()) {
            notifyOrderCode(pendName, pendCode, pendExpireMs);
        }

        if (web != null) {
            // justGranted 时传 true 让前端绕过 30 秒防抖，立刻刷新状态与通知
            String call = justGranted
                    ? "(window.__onShow ? window.__onShow(true) : 0)"
                    : "(window.__onShow ? window.__onShow() : 0)";
            web.evaluateJavascript(call, null);
        }
    }

    private android.content.SharedPreferences notifyPrefs() {
        return getSharedPreferences("bath", MODE_PRIVATE);
    }

    private boolean hasNotifyPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void markNotifyGrantedEver() {
        if (!notifyPrefs().getBoolean(PREF_NOTIFY_GRANTED_EVER, false)) {
            notifyPrefs().edit().putBoolean(PREF_NOTIFY_GRANTED_EVER, true).apply();
        }
    }

    private boolean notifyPermissionDecided() {
        return notifyPrefs().getBoolean(PREF_NOTIFY_ASKED, false)
                || notifyPrefs().getBoolean(PREF_NOTIFY_GRANTED_EVER, false);
    }

    /** 升级迁移：1.1.3.15 之前「问过没问过」只在内存里，老用户升级后两个
        持久化标记都是空。若什么都不做，他们第一次关掉权限再回来仍会被弹一次。
        有登录态说明这不是全新安装，通知权限流程早就走过（1.1.3.4 起就有），
        直接记为「已决定」，不再自动打扰。全新安装仍保留首次问一次的机会。
        另外只要当前有权限就记下「曾授予过」，覆盖「从未走过我们对话框、
        但权限一直是开的」这种情况。 */
    private void migrateNotifyPermissionDecision() {
        if (hasNotifyPermission()) markNotifyGrantedEver();
        if (notifyPermissionDecided()) return;
        boolean usedBefore = !cfg.optString("token", "").isEmpty()
                || !cfg.optString("phone", "").isEmpty();
        if (usedBefore) {
            notifyPrefs().edit().putBoolean(PREF_NOTIFY_ASKED, true).apply();
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

        /** 余额提醒阈值（1.1.3.18）：'off' 或元数（'0.5'~'10'）。空串=还没设置过，
            前端回落默认 5。仍存原生——file:// 下 localStorage 不可靠（同 starDorm）。 */
        @JavascriptInterface
        public String getBalThresh() {
            return getSharedPreferences("bath", MODE_PRIVATE).getString("balThresh", "");
        }

        @JavascriptInterface
        public void setBalThresh(String v) {
            getSharedPreferences("bath", MODE_PRIVATE).edit()
                    .putString("balThresh", v == null ? "" : v).apply();
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
       平台常量名是 Notification.EXTRA_REQUEST_PROMOTED_ONGOING，
       Notification.Builder.setRequestPromotedOngoing(boolean) 的方法体就是
       getExtras().putBoolean(它, true)；AndroidX 的同名方法做的是同一件事。
       本地编译用的是 platform-36 rev2（Android 16 初版），这个 QPR1 常量
       还没进 public SDK，所以这里保留一份字面量并注明出处——它是数据键，
       不是反射调用，写错也只是被系统忽略。
       注意：这是唯一剩下的字符串常量，等 SDK 升到 QPR1 对应版本后可换成平台常量。 */
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing";

    /* 用户还没授权时先把要发的内容存着，授权回来再补发，
       免得「第一次装完正好有预约」这个场景白发一次 */
    private String pendName;
    private String pendCode;
    private long pendExpireMs;

    /* 通知权限的「用户决定」必须跨进程存活。1.1.3.15 前这里只有一个内存里的
       boolean notifyAsked，而 AOSP 在撤销 POST_NOTIFICATIONS 时会
       killUidForPermissionChange() 杀进程（见 12.7）：变量归零 → 用户刚在系统里
       关掉通知，回到 App 立刻又被弹一次权限请求，正好违背 1.1.3.13 定的规矩。

       两个持久化标记都存进原生 SharedPreferences（file:// 下 localStorage 不可靠）：
         notifyPermAsked      —— 本应用已经向系统弹过一次权限对话框
         notifyPermGrantedEver—— 权限曾经被授予过（说明用户后来是主动关掉的）
       只要任一为真就不再自动弹。想开的人走「我的」页静态入口或系统设置。 */
    private static final String PREF_NOTIFY_ASKED = "notifyPermAsked";
    private static final String PREF_NOTIFY_GRANTED_EVER = "notifyPermGrantedEver";

    /* 上次 onResume 看到的权限状态，用于识别「在系统设置里手动打开」这个变化 */
    private boolean lastNotifyPerm;

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

            if (!hasNotifyPermission()) {
                pendName = name;
                pendCode = c;
                pendExpireMs = expireAtMs;
                requestNotifyPermission();   // 内部已按「用户决定」判过，不会反复弹
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
               是否真的被提升由系统决定，不在这里判断——见 applyLiveUpdateFlavor 注释。

               【必须单独隔离】增强里调的是 API 36 方法；万一某个 ROM 的实现
               缺方法会抛 NoSuchMethodError（是 Error 不是 Exception），外层
               catch (Exception) 根本抓不住，会把整条普通通知一起吞掉。
               这里用 catch (Throwable) 兜住，再无条件执行 notify()。
               1.1.3.14 前这两步同处一个 try，是真实存在的健壮性缺口。 */
            try {
                applyLiveUpdateFlavor(b, c, room);
            } catch (Throwable ignored) { }

            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NID_CODE, b.build());
            /* 发成功就清掉挂起内容。否则用户取消预约后 pendCode 仍在，
               日后在系统设置里开关一次权限，onResume 会把这个已取消的码复活。 */
            pendName = null;
            pendCode = null;
        } catch (Throwable ignored) {
            // 通知失败不能影响取码主流程
        }
    }

    private void cancelOrderCode() {
        /* 取消预约 / 码为空 / 已过期时，挂起的补发内容一并作废 */
        pendName = null;
        pendCode = null;
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
            boolean perm = hasNotifyPermission();
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
            o.put("lock", lockScreenInfo());
            o.put("ok", enabled && has && !silent);
        } catch (Exception e) {
            try { o.put("ok", false); } catch (Exception ignored) { }
        }
        return o.toString();
    }

    /** 锁屏到底会不会把通知内容露出来（1.1.3.21）。
        读两个 Settings.Secure 开关：
          lock_screen_show_notifications          —— 锁屏是否显示通知
          lock_screen_allow_private_notifications —— 锁屏是否显示「敏感内容」
        这两个键在 AOSP 上可读，但部分 ROM 会限制或干脆不写，所以用 -1 表示
        「读不到」；前端拿到 canRead=false 时改说「如果你开了…」的说明，不硬猜。

        还要说明一个我们自己造成的因素：本应用通知的 visibility 是 PUBLIC
        （为了确保验证码不被系统打码），按 Android 语义 PUBLIC = 在所有锁屏上
        完整显示，系统「隐藏敏感内容」对它不起作用。所以 channelVis 也一并带出去，
        前端才能在「锁屏显示通知开着」时给出准确的警告，而不是含糊其辞。 */
    private JSONObject lockScreenInfo() {
        JSONObject o = new JSONObject();
        try {
            int show = android.provider.Settings.Secure.getInt(
                    getContentResolver(), "lock_screen_show_notifications", -1);
            int priv = android.provider.Settings.Secure.getInt(
                    getContentResolver(), "lock_screen_allow_private_notifications", -1);
            boolean secure = false;
            try {
                android.app.KeyguardManager km =
                        (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                secure = km != null && km.isDeviceSecure();
            } catch (Throwable ignored) { }
            int vis = -1000;   /* Notification.VISIBILITY_NO_OVERRIDE */
            try {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    android.app.NotificationChannel ch = nm.getNotificationChannel(CH_CODE);
                    if (ch != null) vis = ch.getLockscreenVisibility();
                }
            } catch (Throwable ignored) { }
            o.put("canRead", show >= 0 || priv >= 0);
            o.put("show", show == 1);
            o.put("private", priv == 1);
            o.put("secure", secure);
            o.put("channelVis", vis);
            o.put("public", vis == android.app.Notification.VISIBILITY_PUBLIC);
        } catch (Throwable e) {
            try { o.put("canRead", false); } catch (Throwable ignored) { }
        }
        return o;
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
       按标准发一条「持续进行中」的通知，系统决定把它渲染成什么形态
       —— 兼容这套标准的 ROM（ColorOS 16 流体云、HyperOS 超级岛等）会把
       它提升成岛/云/窗；不兼容就退化成普通常驻通知，不会出错。

       1.1.3.14 起 compileSdk 升到 36（platform-36 rev2），照 InstallerX-Revived
       的 Modern/Legacy 思路按 SDK 分流：API 36 的调用全部收进 LiveUpdate36
       这个独立类，低版本系统永远不会加载它；其余全是编译期可见的公开 API，
       不再有任何反射。QPR1 才公开的「请求提升」常量仍是唯一的数据键字面量。

       【不涉及 Shizuku】InstallerX 里的 Shizuku 属于安装器引擎的授权方式，
       和通知发送没有任何关系；它的小米岛适配走的是 MIUI 私有 extras，我们
       也不照搬。这里只用 AOSP 标准 Live Updates，清单权限一个都不加。 */

    /** API 36 专用分支。做成独立类，是为了低版本系统根本不会去解析这些
        方法引用（等价于 InstallerX 的 @RequiresApi(BAKLAVA) 独立 Builder）。 */
    private static final class LiveUpdate36 {
        static void apply(android.app.Notification.Builder b, String code, String room) {
            /* 请求提升。QPR1 的 Notification.Builder.setRequestPromotedOngoing(true)
               方法体就是写这个 extra；platform-36 rev2 的 public SDK 还没收录该
               常量，所以这里用上面那份带出处注释的字面量。不是反射。
               先写它：后面任何一步出意外，请求提升的数据也已经在 extras 里了。 */
            b.getExtras().putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
            /* 芯片文案：平台方法，内部写 android.shortCriticalText。
               @FlaggedApi，个别 ROM 上 flag 没开可能取不到，单独兜住，
               别让它挡住后面的样式设置。 */
            try {
                b.setShortCriticalText(code);
            } catch (Throwable ignored) { }
            /* 兼容写入：系统按规范会清掉应用自设的结果标记（Apps cannot set
               this flag），只照顾极少数直接读该位的 ROM；不再是请求提升的手段。 */
            try {
                b.setFlag(android.app.Notification.FLAG_PROMOTED_ONGOING, true);
            } catch (Throwable ignored) { }
            /* 文本类卡片：标题是验证码，下面一行是浴室名 + 当日有效 */
            b.setStyle(new android.app.Notification.BigTextStyle()
                    .setBigContentTitle(code)
                    .bigText(room + " · 当日有效"));
        }
        static boolean canPost(android.app.NotificationManager nm) {
            return nm.canPostPromotedNotifications();
        }
        static boolean chars(android.app.Notification n) {
            return n.hasPromotableCharacteristics();
        }
        static String shortText(android.app.Notification n) {
            return n.getShortCriticalText();
        }
    }

    /** 「我能不能被提升」只用于向用户解释，【不再】用它决定要不要补这些特征。
        它的默认值就是 true，而之前却因为在部分 ROM 上取不到（API 未开放 /
        反射失败）就把整条路挡死——典型的把探测当开关。
        返回 yes / no / no_api / err:xxx */
    private String promotedSupport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return "no_api";
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return "no_service";
            return LiveUpdate36.canPost(nm) ? "yes" : "no";
        } catch (NoSuchMethodError e) {
            return "no_api";
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    /** 给通知补上「可被提升」所需的特征，按 SDK 分流（照 InstallerX-Revived
        的 Modern / Legacy 双 Builder 结构，只是我们用平台 API 而非 AndroidX）。
          必须声明 POST_PROMOTED_NOTIFICATIONS（清单里已加）
          必须【请求提升】：extras 里写 android.requestPromotedOngoing=true
          必须 ongoing、有 contentTitle、无自定义视图、非分组摘要
          样式须为 Standard / BigText / Call / Progress / Metric
          通道 importance 不能是 IMPORTANCE_MIN（这里是 DEFAULT）
          【不能】setColorized(true)——QPR1 判定里明确要求 !isColorizedRequested

        刻意【不用】ProgressStyle：它是「进度类」实时卡片，必然画一条进度条，
        而我们的验证码没有进度可表示。改走 BigTextStyle 的「文本类」卡片——
        图标 + 芯片/标题上的验证码，正是系统「取单码提醒」那种排版。
        @param code 状态栏芯片/岛上显示的验证码 */
    private void applyLiveUpdateFlavor(android.app.Notification.Builder b,
                                       String code, String room) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            LiveUpdate36.apply(b, code, room);
        } else {
            /* 低版本：不带任何 API 36 调用，但保留同样的展开排版。
               BigTextStyle 从 API 16 就有，两边观感一致。 */
            b.setStyle(new android.app.Notification.BigTextStyle()
                    .setBigContentTitle(code)
                    .bigText(room + " · 当日有效"));
        }
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
            /* 这几个键在 SDK 34 起就是 Notification 的公开常量，直接引用，
               不再写字面量、也不再反射。用来确认倒计时来源确实关掉了。 */
            boolean showWhen = true, chron = false;
            boolean modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA;
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
                        // 编译期内联的 int 常量，低版本运行也不会触发类解析
                        flagged = (flags & android.app.Notification.FLAG_PROMOTED_ONGOING) != 0;
                        if (n.extras != null) {
                            req = n.extras.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, false)
                                    ? "yes" : "no";
                            colorized = n.extras.getBoolean(
                                    android.app.Notification.EXTRA_COLORIZED, false);
                            showWhen = n.extras.getBoolean(
                                    android.app.Notification.EXTRA_SHOW_WHEN, true);
                            chron = n.extras.getBoolean(
                                    android.app.Notification.EXTRA_SHOW_CHRONOMETER, false);
                            // 样式类名存在 EXTRA_TEMPLATE，无需反射 getNotificationStyle()
                            String tpl = n.extras.getString(
                                    android.app.Notification.EXTRA_TEMPLATE, "");
                            if (tpl != null && !tpl.isEmpty()) {
                                int i = Math.max(tpl.lastIndexOf('.'), tpl.lastIndexOf('$'));
                                style = tpl.substring(i + 1);
                            }
                        }
                        if (modern) {
                            try {
                                chars = LiveUpdate36.chars(n);
                                String sct = LiveUpdate36.shortText(n);
                                if (sct != null) shortText = sct;
                            } catch (Throwable ignored) { }
                        }
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
        /* 只在「从未问过、且权限从未被授予过」时弹一次。
           进程被系统 kill 重建后，内存标志会归零，所以判据必须落在原生持久化里。
           用户明确拒绝过、或授予后又从系统设置关掉，都不再自动打扰——
           这是 1.1.3.13「尊重用户关掉通知的决定」在原生侧的落实。
           markNotifyAsked 必须在 requestPermissions 之前写：对话框弹出期间若被
           系统杀进程，重建后也不会重复弹。 */
        if (Build.VERSION.SDK_INT < 33 || notifyPermissionDecided()) return;
        notifyPrefs().edit().putBoolean(PREF_NOTIFY_ASKED, true).apply();
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
        lastNotifyPerm = ok;
        if (ok) markNotifyGrantedEver();
        if (ok && pendCode != null && !pendCode.isEmpty()) {
            notifyOrderCode(pendName, pendCode, pendExpireMs);
        }
        /* 对话框授权成功也强制刷一次：把首页提示框和「我的」页状态从
           「未开启」更新成「已生效」，不用等用户手动下拉刷新。 */
        if (ok && web != null) {
            web.evaluateJavascript("(window.__onShow ? window.__onShow(true) : 0)", null);
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
