package com.wanmei.bath;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** 本地配置：登录得到的 session 与用户信息。 */
public class Store {

    private static final String PREFS = "bath";
    private static final String KEY = "config";

    public static JSONObject load(Context ctx) {
        String s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        if (s == null || s.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void save(Context ctx, JSONObject cfg) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, cfg.toString()).apply();
    }

    public static void clear(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }
}
