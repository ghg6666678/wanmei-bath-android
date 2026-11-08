package com.wanmei.bath;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 完美校园登录（短信验证码）。
 *
 * 流程：
 *   1. 本地生成 RSA-1024，POST exchangeSecretkey.action（公钥用 PKCS#1 DER）
 *   2. 用私钥解密响应，得到 session 与 appKey
 *   3. 业务参数 3DES-CBC 加密（key=appKey 前 24 字节，IV="66666666"）后 base64，
 *      包成 {"session":...,"data":...}，请求头 campusSign = sha256(紧凑 JSON)
 *   4. 登录成功后，这个 session 就是智慧用水 H5 认的 token
 */
public class Login {

    static final String UA = "Dalvik/2.1.0 (Linux; U; Android 5.1.1; HUAWEI MLA-AL10 Build/HUAWEIMLA-AL10)";
    static final String[] HOSTS = {"https://app.17wanxiao.com:443", "https://server.17wanxiao.com"};
    static final byte[] IV = {'6', '6', '6', '6', '6', '6', '6', '6'};
    static final int WX_VERSION = 10552101;

    public static class Err extends Exception {
        public Err(String m) { super(m); }
    }

    public String host = HOSTS[0];
    public String deviceId = UUID.randomUUID().toString();
    public String sessionId = "";
    public String appKey = "";

    private KeyPair keyPair;

    // ---------------- 加密原语 ----------------

    private static byte[] des3(byte[] data, byte[] key, boolean encrypt) throws Exception {
        SecretKeySpec ks = new SecretKeySpec(key, "DESede");
        Cipher c = Cipher.getInstance("DESede/CBC/PKCS5Padding");
        c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, ks, new IvParameterSpec(IV));
        return c.doFinal(data);
    }

    private byte[] key24() throws Exception {
        byte[] k = appKey.getBytes("UTF-8");
        if (k.length < 24) throw new Err("appKey 长度异常");
        byte[] out = new byte[24];
        System.arraycopy(k, 0, out, 0, 24);
        return out;
    }

    private static String sha256Hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ---------------- HTTP ----------------

    private String post(String url, String body, String sign) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Content-Type", "application/text;charset=UTF-8");
        if (sign != null) c.setRequestProperty("campusSign", sign);

        OutputStream os = c.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
        }
        c.disconnect();
        String text = new String(bo.toByteArray(), "UTF-8");
        if (code != 200) throw new Err("服务端返回 HTTP " + code);
        return text;
    }

    /** 3DES 加密参数 + 签名后 POST，返回原始 JSON */
    private JSONObject signedPost(String path, String paramsJson) throws Exception {
        byte[] enc = des3(paramsJson.getBytes("UTF-8"), key24(), true);
        String data = Base64.encodeToString(enc, Base64.NO_WRAP);
        String compact = "{\"session\":\"" + sessionId + "\",\"data\":\"" + data + "\"}";
        String sign = sha256Hex(compact);
        String text = post(host + path, compact, sign);
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            throw new Err("返回不是 JSON: " + trim(text));
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    // ---------------- 会话 ----------------

    /** 生成密钥对并与服务端交换，得到 session + appKey */
    public void exchangeKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        keyPair = g.generateKeyPair();

        // 服务端要的是 X.509 SubjectPublicKeyInfo —— Java 的 getEncoded() 正是这个格式
        // （PyCryptodome 的 export_key(format='DER') 也是 X.509，实测 162 字节；
        //   用手工拼的 PKCS#1 只有 140 字节，服务端会返回 200 + 空响应）
        String pubB64 = Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.NO_WRAP);
        String body = "{\"key\":\"" + pubB64 + "\"}";
        String text = post(host + "/campus/cam_iface46/exchangeSecretkey.action", body, null).trim();
        if (text.isEmpty()) throw new Err("交换密钥失败：空响应");

        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
        byte[] plain = c.doFinal(Base64.decode(text, Base64.DEFAULT));
        JSONObject o = new JSONObject(new String(plain, "UTF-8"));
        if (!o.has("session") || !o.has("key")) throw new Err("交换密钥失败：缺少字段");
        sessionId = o.getString("session");
        appKey = o.getString("key");
    }

    // ---------------- 业务 ----------------

    public JSONObject sendCode(String phone) throws Exception {
        String p = "{"
                + "\"action\":\"registAndLogin\","
                + "\"confirm\":false,"
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"mobile\":\"" + phone + "\","
                + "\"requestMethod\":\"cam_iface46/gainMatrixCaptcha543.action\","
                + "\"type\":\"sms\""
                + "}";
        JSONObject r = signedPost("/campus/cam_iface46/gainMatrixCaptcha543.action", p);
        checkCode(r, "验证码发送失败");
        return r;
    }

    public JSONObject checkPhone(String phone) throws Exception {
        String p = "{"
                + "\"action\":\"registAndLogin\","
                + "\"confirm\":false,"
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"mobile\":\"" + phone + "\","
                + "\"requestMethod\":\"cam_iface46/gainMatrixCaptcha543.action\","
                + "\"type\":\"sms\""
                + "}";
        return signedPost("/campus/cam_iface46/checkBeforeSendRegisterCode.action", p);
    }

    /** 用短信验证码登录，成功后 sessionId 即为可用的用水 token */
    public JSONObject loginSms(String phone, String code) throws Exception {
        String p = "{"
                + "\"appCode\":\"M002\","
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"mobile\":\"" + phone + "\","
                + "\"netWork\":\"wifi\","
                + "\"qudao\":\"tencent\","
                + "\"requestMethod\":\"cam_iface46/registerUsersByTelAndLoginNew.action\","
                + "\"shebeixinghao\":\"LGE-AN10\","
                + "\"sms\":\"" + code + "\","
                + "\"systemType\":\"android\","
                + "\"telephoneInfo\":\"12\","
                + "\"telephoneModel\":\"LGE-AN10\","
                + "\"wanxiaoVersion\":" + WX_VERSION
                + "}";
        JSONObject r = signedPost("/campus/cam_iface46/registerUsersByTelAndLoginNew.action", p);
        checkCode(r, "登录失败");

        String data = r.optString("data", "");
        if (data.isEmpty()) throw new Err("登录返回缺少 data");
        byte[] dec = des3(Base64.decode(data, Base64.DEFAULT), key24(), false);
        String json = new String(dec, "UTF-8").replace("\r", "");
        JSONObject obj = new JSONObject(json);
        JSONObject user = obj.optJSONObject("user");
        if (user == null) throw new Err("登录返回结构异常");
        if (user.optString("token", "").isEmpty()) throw new Err("登录返回缺少 token");
        return user;
    }

    private static void checkCode(JSONObject r, String fallback) throws Err {
        String code = r.optString("code_", "");
        if (!"0".equals(code)) {
            String m = r.optString("message_", fallback);
            throw new Err(m.isEmpty() ? fallback : m);
        }
    }
}
