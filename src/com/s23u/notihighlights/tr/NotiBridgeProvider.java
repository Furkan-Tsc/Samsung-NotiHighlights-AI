package com.s23u.notihighlights.tr;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Binder bridge allowing system_server (UID 1000) to fetch non-secret behavioral config,
 * bundled Turkish SCPM policy, and delegate HTTPS LLM summarization.
 */
public final class NotiBridgeProvider extends ContentProvider {
    public static final String METHOD_GET_CONFIG = "get_config";
    public static final String METHOD_GET_SCPM_POLICY = "get_scpm_policy";
    public static final String METHOD_SUMMARIZE = "summarize";
    public static final String METHOD_PING = "ping";

    public static final String ARG_INPUT_JSON = "input_json";
    public static final String ARG_NOTI_KEY = "noti_key";
    public static final String RES_OK = "ok";
    public static final String RES_SUMMARY = "summary";
    public static final String RES_POLICY_JSON = "policy_json";
    public static final String RES_ERROR = "error";
    public static final String RES_LATENCY_MS = "latency_ms";
    public static final String RES_HAS_KEY = "has_key";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID && callingUid != Process.myUid()) {
            throw new SecurityException("NotiBridgeProvider: unauthorized uid=" + callingUid);
        }

        Context ctx = getContext();
        if (ctx == null) {
            Bundle err = new Bundle();
            err.putBoolean(RES_OK, false);
            err.putString(RES_ERROR, "Provider context null");
            return err;
        }

        if (METHOD_GET_CONFIG.equals(method)) {
            return PrefsStore.toNonSecretConfigBundle(ctx);
        }

        if (METHOD_GET_SCPM_POLICY.equals(method)) {
            Bundle b = new Bundle();
            try (InputStream is = ctx.getAssets().open("notification_ai_scpm_policies.json");
                    ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                b.putBoolean(RES_OK, true);
                b.putString(RES_POLICY_JSON, new String(baos.toByteArray(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                b.putBoolean(RES_OK, false);
                b.putString(RES_ERROR, e.getMessage());
            }
            return b;
        }

        if (METHOD_PING.equals(method)) {
            Bundle b = new Bundle();
            b.putBoolean(RES_OK, true);
            b.putBoolean(RES_HAS_KEY, !PrefsStore.getApiKey(ctx).isEmpty());
            return b;
        }

        if (METHOD_SUMMARIZE.equals(method)) {
            String inputJson = extras != null ? extras.getString(ARG_INPUT_JSON, "") : "";
            String notiKey = extras != null ? extras.getString(ARG_NOTI_KEY, "") : "";
            LlmClient.Result r = LlmClient.summarize(ctx, inputJson, notiKey);
            Bundle out = new Bundle();
            out.putBoolean(RES_OK, r.ok);
            out.putString(RES_SUMMARY, r.summary);
            out.putString(RES_ERROR, r.error);
            out.putLong(RES_LATENCY_MS, r.latencyMs);
            return out;
        }

        Bundle unknown = new Bundle();
        unknown.putBoolean(RES_OK, false);
        unknown.putString(RES_ERROR, "Unknown method: " + method);
        return unknown;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
