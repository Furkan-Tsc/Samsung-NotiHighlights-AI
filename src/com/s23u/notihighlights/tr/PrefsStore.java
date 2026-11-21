package com.s23u.notihighlights.tr;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Private preferences storage for LLM provider settings, behavioral flags, and recent logs.
 */
public final class PrefsStore {
    private static final String PREF_NAME = "notihighlights_tr_prefs";

    public static final String KEY_PROVIDER_TYPE = "provider_type";
    public static final String KEY_API_DIALECT = "api_dialect";
    public static final String KEY_API_URL = "api_url";
    public static final String KEY_API_MODEL = "api_model";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_SYSTEM_PROMPT = "system_prompt";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_ALLOW_SCREEN_ON = "allow_screen_on";
    public static final String KEY_ALLOW_POWER_SAVE = "allow_power_save";
    public static final String KEY_ALLOW_LOW_BATTERY = "allow_low_battery";
    public static final String KEY_WAIT_PRIVATE_SEC = "wait_private_sec";
    public static final String KEY_WAIT_GROUP_SEC = "wait_group_sec";
    public static final String KEY_ALARM_DELAY_SEC = "alarm_delay_sec";
    public static final String KEY_MIN_WORDS = "min_words";
    public static final String KEY_RECENT_LOGS = "recent_logs";
    public static final String KEY_APP_LANG = "app_lang";

    public static final String LANG_TR = "tr";
    public static final String LANG_EN = "en";
    public static final String DEFAULT_APP_LANG = LANG_TR;

    public static final String DIALECT_GEMINI = "gemini";
    public static final String DIALECT_OPENAI = "openai";

    public static final String DEFAULT_PROVIDER_TYPE = "gemini";
    public static final String DEFAULT_API_DIALECT = DIALECT_GEMINI;
    public static final String DEFAULT_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent";
    public static final String DEFAULT_API_MODEL = "gemini-2.5-flash";
    public static final String DEFAULT_SYSTEM_PROMPT_TR =
            "Sen Samsung One UI Bildirim Özetleyici asistanısın. Sana JSON formatında gelen mesajlaşma bildirimlerini analiz et ve kullanıcının bir bakışta anlayacağı şekilde doğal, akıcı ve kısa TÜRKÇE bir özet (1-2 kısa cümle, en fazla 180 karakter) yaz. Madde işareti, tırnak, markdown veya 'Özet:' gibi ön ekler KULLANMA. Sadece doğrudan özeti yaz.";
    public static final String DEFAULT_SYSTEM_PROMPT_EN =
            "You are the Samsung One UI Notification Summarizer assistant. Analyze the incoming JSON messaging notifications and write a natural, fluent, concise ENGLISH summary (1-2 short sentences, max 180 characters) that the user can understand at a glance. Do NOT use bullet points, quotes, markdown, or prefixes like 'Summary:'. Output only the summary directly.";
    public static final String DEFAULT_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT_TR;

    public static final int DEFAULT_WAIT_PRIVATE_SEC = 15;
    public static final int DEFAULT_WAIT_GROUP_SEC = 30;
    public static final int DEFAULT_ALARM_DELAY_SEC = 5;
    public static final int DEFAULT_MIN_WORDS = 15;

    private PrefsStore() {
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static String getProviderType(Context ctx) {
        return prefs(ctx).getString(KEY_PROVIDER_TYPE, DEFAULT_PROVIDER_TYPE);
    }

    public static String getApiDialect(Context ctx) {
        return prefs(ctx).getString(KEY_API_DIALECT, DEFAULT_API_DIALECT);
    }

    public static String getApiUrl(Context ctx) {
        return prefs(ctx).getString(KEY_API_URL, DEFAULT_API_URL);
    }

    public static String getApiModel(Context ctx) {
        return prefs(ctx).getString(KEY_API_MODEL, DEFAULT_API_MODEL);
    }

    public static String getApiKey(Context ctx) {
        return prefs(ctx).getString(KEY_API_KEY, "").trim();
    }

    public static String getAppLang(Context ctx) {
        String lang = prefs(ctx).getString(KEY_APP_LANG, DEFAULT_APP_LANG);
        return LANG_EN.equals(lang) ? LANG_EN : LANG_TR;
    }

    public static boolean isEnglish(Context ctx) {
        return LANG_EN.equals(getAppLang(ctx));
    }

    public static void setAppLang(Context ctx, String lang) {
        String normalized = LANG_EN.equals(lang) ? LANG_EN : LANG_TR;
        prefs(ctx).edit().putString(KEY_APP_LANG, normalized).apply();
    }

    public static String getDefaultSystemPrompt(String lang) {
        return LANG_EN.equals(lang) ? DEFAULT_SYSTEM_PROMPT_EN : DEFAULT_SYSTEM_PROMPT_TR;
    }

    public static boolean isBuiltInDefaultPrompt(String prompt) {
        if (prompt == null) {
            return true;
        }
        String trimmed = prompt.trim();
        if (trimmed.isEmpty()
                || DEFAULT_SYSTEM_PROMPT_TR.equals(trimmed)
                || DEFAULT_SYSTEM_PROMPT_EN.equals(trimmed)) {
            return true;
        }
        return trimmed.startsWith("Sen Samsung One UI Bildirim Özetleyici asistanısın.")
                || trimmed.startsWith("You are the Samsung One UI Notification Summarizer assistant.");
    }

    public static String getSystemPrompt(Context ctx) {
        String def = getDefaultSystemPrompt(getAppLang(ctx));
        String prompt = prefs(ctx).getString(KEY_SYSTEM_PROMPT, def);
        return (prompt == null || prompt.trim().isEmpty()) ? def : prompt.trim();
    }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static boolean isAllowScreenOn(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ALLOW_SCREEN_ON, true);
    }

    public static boolean isAllowPowerSave(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ALLOW_POWER_SAVE, true);
    }

    public static boolean isAllowLowBattery(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ALLOW_LOW_BATTERY, false);
    }

    public static int getWaitPrivateSec(Context ctx) {
        return Math.max(1, prefs(ctx).getInt(KEY_WAIT_PRIVATE_SEC, DEFAULT_WAIT_PRIVATE_SEC));
    }

    public static int getWaitGroupSec(Context ctx) {
        return Math.max(1, prefs(ctx).getInt(KEY_WAIT_GROUP_SEC, DEFAULT_WAIT_GROUP_SEC));
    }

    public static int getAlarmDelaySec(Context ctx) {
        return Math.max(1, prefs(ctx).getInt(KEY_ALARM_DELAY_SEC, DEFAULT_ALARM_DELAY_SEC));
    }

    public static int getMinWords(Context ctx) {
        return Math.max(1, prefs(ctx).getInt(KEY_MIN_WORDS, DEFAULT_MIN_WORDS));
    }

    public static Bundle toNonSecretConfigBundle(Context ctx) {
        Bundle b = new Bundle();
        b.putBoolean(KEY_ENABLED, isEnabled(ctx));
        b.putBoolean(KEY_ALLOW_SCREEN_ON, isAllowScreenOn(ctx));
        b.putBoolean(KEY_ALLOW_POWER_SAVE, isAllowPowerSave(ctx));
        b.putBoolean(KEY_ALLOW_LOW_BATTERY, isAllowLowBattery(ctx));
        b.putInt(KEY_WAIT_PRIVATE_SEC, getWaitPrivateSec(ctx));
        b.putInt(KEY_WAIT_GROUP_SEC, getWaitGroupSec(ctx));
        b.putInt(KEY_ALARM_DELAY_SEC, getAlarmDelaySec(ctx));
        b.putInt(KEY_MIN_WORDS, getMinWords(ctx));
        return b;
    }

    public static synchronized void appendLog(Context ctx, String entry) {
        if (ctx == null || entry == null) {
            return;
        }
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        String line = "[" + ts + "] " + entry.replace('\n', ' ').trim();
        SharedPreferences sp = prefs(ctx);
        String existing = sp.getString(KEY_RECENT_LOGS, "");
        String[] oldLines = existing.isEmpty() ? new String[0] : existing.split("\n");
        StringBuilder sb = new StringBuilder(line);
        int maxKeep = 19;
        for (int i = 0; i < oldLines.length && i < maxKeep; i++) {
            String item = oldLines[i].trim();
            if (!item.isEmpty()) {
                sb.append('\n').append(item);
            }
        }
        sp.edit().putString(KEY_RECENT_LOGS, sb.toString()).apply();
    }

    public static String getRecentLogs(Context ctx) {
        String logs = prefs(ctx).getString(KEY_RECENT_LOGS, "");
        if (logs.isEmpty()) {
            return isEnglish(ctx) ? "(No activity logs yet)" : "(Henüz işlem kaydı yok)";
        }
        return logs;
    }
}
