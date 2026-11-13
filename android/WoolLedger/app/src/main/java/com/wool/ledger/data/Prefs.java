package com.wool.ledger.data;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量配置：大模型接口、预算、账期、深色模式。 */
public class Prefs {

    private static final String FILE = "wool.prefs";
    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String apiBase(Context c) {
        return sp(c).getString("api_base", "https://api.deepseek.com/v1");
    }

    public static void setApiBase(Context c, String v) {
        sp(c).edit().putString("api_base", v.trim()).apply();
    }

    public static String apiKey(Context c) {
        return sp(c).getString("api_key", "");
    }

    public static void setApiKey(Context c, String v) {
        sp(c).edit().putString("api_key", v.trim()).apply();
    }

    public static String apiModel(Context c) {
        return sp(c).getString("api_model", "deepseek-chat");
    }

    public static void setApiModel(Context c, String v) {
        sp(c).edit().putString("api_model", v.trim()).apply();
    }

    public static double budget(Context c) {
        return Double.parseDouble(sp(c).getString("budget", "1500"));
    }

    public static void setBudget(Context c, double v) {
        sp(c).edit().putString("budget", String.valueOf(v)).apply();
    }

    public static boolean dark(Context c) {
        return sp(c).getBoolean("dark", false);
    }

    public static void setDark(Context c, boolean v) {
        sp(c).edit().putBoolean("dark", v).apply();
    }

    public static String defCategory(Context c) {
        return sp(c).getString("def_cat", "food");
    }

    public static void setDefCategory(Context c, String v) {
        sp(c).edit().putString("def_cat", v).apply();
    }

    public static String defSlot(Context c) {
        return sp(c).getString("def_slot", "午");
    }

    public static void setDefSlot(Context c, String v) {
        sp(c).edit().putString("def_slot", v).apply();
    }

    public static boolean autoHome(Context c) {
        return sp(c).getBoolean("auto_home", true);
    }

    public static void setAutoHome(Context c, boolean v) {
        sp(c).edit().putBoolean("auto_home", v).apply();
    }

    /** 按当前设置换主题（深色 / 浅色），Activity 里 onCreate 调一次 */
    public static void applyTheme(android.app.Activity a) {
        a.setTheme(dark(a) ? com.wool.ledger.R.style.Theme_Wool_Dark : com.wool.ledger.R.style.Theme_Wool);
        try {
            android.view.Window w = a.getWindow();
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    a.getColor(dark(a) ? com.wool.ledger.R.color.paper_dark
                            : com.wool.ledger.R.color.paper)));
            int flags = w.getDecorView().getSystemUiVisibility();
            if (dark(a)) {
                flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                w.setStatusBarColor(a.getColor(com.wool.ledger.R.color.paper_dark));
                w.setNavigationBarColor(a.getColor(com.wool.ledger.R.color.paper_dark));
            } else {
                flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                w.setStatusBarColor(a.getColor(com.wool.ledger.R.color.paper));
                w.setNavigationBarColor(a.getColor(com.wool.ledger.R.color.paper));
            }
            w.getDecorView().setSystemUiVisibility(flags);
        } catch (Exception ignored) {
        }
    }

    public static String reminder(Context c) {
        return sp(c).getString("reminder", "关闭");
    }

    public static void setReminder(Context c, String v) {
        sp(c).edit().putString("reminder", v).apply();
    }

    public static int cycleStart(Context c) {
        return Integer.parseInt(sp(c).getString("cycle_start", "1"));
    }

    public static void setCycleStart(Context c, int v) {
        sp(c).edit().putString("cycle_start", String.valueOf(v)).apply();
    }

    /** 首次启动是否已灌演示数据 */
    public static final int VOICE_NONE = 0;
    public static final int VOICE_DOWNLOADING = 1;
    public static final int VOICE_READY = 2;

    public static boolean voiceWifiOnly(Context c) {
        return sp(c).getBoolean("voice_wifi_only", true);
    }

    public static void setVoiceWifiOnly(Context c, boolean v) {
        sp(c).edit().putBoolean("voice_wifi_only", v).apply();
    }

    public static int voiceModelState(Context c) {
        return sp(c).getInt("voice_model_state", VOICE_NONE);
    }

    public static void setVoiceModelState(Context c, int v) {
        // 文件状态一变，之前那次"真能加载"的验证结论就作废
        sp(c).edit().putInt("voice_model_state", v)
                .putBoolean("voice_verified", false).apply();
    }

    /** 是否真的成功加载过一次离线 ASR 模型（不只是文件在） */
    public static boolean voiceVerified(Context c) {
        return sp(c).getBoolean("voice_verified", false);
    }

    public static void setVoiceVerified(Context c, boolean v) {
        sp(c).edit().putBoolean("voice_verified", v).apply();
    }

    public static long voiceDownloadId(Context c) {
        return sp(c).getLong("voice_download_id", -1L);
    }

    public static void setVoiceDownloadId(Context c, long id) {
        sp(c).edit().putLong("voice_download_id", id).apply();
    }

    public static boolean voiceHintShown(Context c) {
        return sp(c).getBoolean("voice_hint_shown", false);
    }

    public static void setVoiceHintShown(Context c, boolean v) {
        sp(c).edit().putBoolean("voice_hint_shown", v).apply();
    }

    public static boolean seeded(Context c) {
        return sp(c).getBoolean("seeded", false);
    }

    public static void markSeeded(Context c) {
        sp(c).edit().putBoolean("seeded", true).apply();
    }
}
