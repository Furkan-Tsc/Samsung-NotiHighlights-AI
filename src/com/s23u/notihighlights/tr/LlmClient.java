package com.s23u.notihighlights.tr;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pure HttpURLConnection LLM client supporting Gemini generateContent and OpenAI chat/completions.
 */
public final class LlmClient {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int MAX_SUMMARY_CHARS = 360;

    public static final class Result {
        public final boolean ok;
        public final String summary;
        public final String error;
        public final long latencyMs;

        public Result(boolean ok, String summary, String error, long latencyMs) {
            this.ok = ok;
            this.summary = summary;
            this.error = error;
            this.latencyMs = latencyMs;
        }
    }

    private LlmClient() {
    }

    public static Result summarize(Context ctx, String inputJson, String notiKey) {
        long start = System.currentTimeMillis();
        String dialect = PrefsStore.getApiDialect(ctx);
        String apiUrl = PrefsStore.getApiUrl(ctx).trim();
        String apiModel = PrefsStore.getApiModel(ctx).trim();
        String apiKey = PrefsStore.getApiKey(ctx);
        String systemPrompt = PrefsStore.getSystemPrompt(ctx);
        boolean en = PrefsStore.isEnglish(ctx);

        if (apiUrl.isEmpty()) {
            return fail(ctx, en ? "Endpoint URL is empty" : "Endpoint URL boş", start);
        }
        if (apiKey.isEmpty() && !isLocalOrPrivateUrl(apiUrl)) {
            return fail(
                    ctx,
                    en
                            ? "API key is missing (add it in the NotiHighlights AI app)"
                            : "API anahtarı girilmedi (NotiHighlights AI uygulamasından ekleyin)",
                    start);
        }
        if (inputJson == null || inputJson.trim().isEmpty()) {
            return fail(ctx, en ? "Empty notification content" : "Boş bildirim içeriği", start);
        }

        try {
            String rawText;
            if (PrefsStore.DIALECT_GEMINI.equalsIgnoreCase(dialect)) {
                rawText = callGemini(apiUrl, apiModel, apiKey, systemPrompt, inputJson);
            } else {
                rawText = callOpenAiCompatible(apiUrl, apiModel, apiKey, systemPrompt, inputJson);
            }

            String cleaned = sanitizeSummary(rawText);
            if (cleaned.isEmpty()) {
                return fail(ctx, en ? "LLM returned an empty response" : "LLM boş yanıt döndürdü", start);
            }
            long elapsed = System.currentTimeMillis() - start;
            PrefsStore.appendLog(ctx, "OK (" + elapsed + "ms) [" + shortKey(notiKey) + "] -> " + cleaned);
            return new Result(true, cleaned, "", elapsed);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return fail(ctx, msg, start);
        }
    }

    private static Result fail(Context ctx, String reason, long startMs) {
        long elapsed = System.currentTimeMillis() - startMs;
        String prefix = PrefsStore.isEnglish(ctx) ? "ERROR (" : "HATA (";
        PrefsStore.appendLog(ctx, prefix + elapsed + "ms): " + reason);
        return new Result(false, "", reason, elapsed);
    }

    private static String callGemini(
            String apiUrl, String model, String apiKey, String systemPrompt, String inputJson)
            throws IOException, JSONException {
        String cleanModel = model.startsWith("models/") ? model.substring("models/".length()) : model;
        if (cleanModel.isEmpty()) {
            cleanModel = PrefsStore.DEFAULT_API_MODEL;
        }
        String resolvedUrl = apiUrl.replace("{model}", cleanModel);

        String bodyWithThinking = buildGeminiBody(systemPrompt, inputJson, true);
        try {
            String resp = postJson(resolvedUrl, "x-goog-api-key", apiKey, bodyWithThinking);
            return parseGeminiResponse(resp);
        } catch (HttpStatusException e) {
            if (e.code == 400 && e.body != null && e.body.toLowerCase(Locale.ROOT).contains("thinkingconfig")) {
                String bodyNoThinking = buildGeminiBody(systemPrompt, inputJson, false);
                String resp = postJson(resolvedUrl, "x-goog-api-key", apiKey, bodyNoThinking);
                return parseGeminiResponse(resp);
            }
            throw e;
        }
    }

    private static String buildGeminiBody(String systemPrompt, String inputJson, boolean disableThinking)
            throws JSONException {
        JSONObject root = new JSONObject();

        JSONObject sysPart = new JSONObject().put("text", systemPrompt);
        root.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(sysPart)));

        JSONObject userPart = new JSONObject().put("text", inputJson);
        JSONObject userMsg = new JSONObject().put("role", "user").put("parts", new JSONArray().put(userPart));
        root.put("contents", new JSONArray().put(userMsg));

        JSONObject genCfg = new JSONObject()
                .put("temperature", 0.2)
                .put("maxOutputTokens", 180);
        if (disableThinking) {
            genCfg.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
        }
        root.put("generationConfig", genCfg);
        return root.toString();
    }

    private static String parseGeminiResponse(String respJson) throws JSONException, IOException {
        JSONObject root = new JSONObject(respJson);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IOException("Gemini yanıtında candidate yok: " + truncate(respJson, 180));
        }
        JSONObject first = candidates.getJSONObject(0);
        JSONObject content = first.optJSONObject("content");
        if (content == null) {
            String finish = first.optString("finishReason", "UNKNOWN");
            throw new IOException("Gemini içerik döndürmedi (finishReason=" + finish + ")");
        }
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null || parts.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (part.optBoolean("thought", false)) {
                continue;
            }
            String text = part.optString("text", "");
            if (!text.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private static String callOpenAiCompatible(
            String apiUrl, String model, String apiKey, String systemPrompt, String inputJson)
            throws IOException, JSONException {
        JSONObject root = new JSONObject();
        if (!model.isEmpty()) {
            root.put("model", model);
        }
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", systemPrompt))
                .put(new JSONObject().put("role", "user").put("content", inputJson));
        root.put("messages", messages);
        root.put("temperature", 0.2);
        root.put("max_tokens", 180);

        String authHeader = apiKey.isEmpty() ? null : "Bearer " + apiKey;
        String resp = postJson(apiUrl, authHeader == null ? null : "Authorization", authHeader, root.toString());
        JSONObject json = new JSONObject(resp);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IOException("API choices boş: " + truncate(resp, 180));
        }
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        return msg != null ? msg.optString("content", "") : "";
    }

    private static String postJson(String urlStr, String headerName, String headerVal, String jsonBody)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            if (headerName != null && headerVal != null && !headerVal.isEmpty()) {
                conn.setRequestProperty(headerName, headerVal);
            }

            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int status = conn.getResponseCode();
            InputStream stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readStream(stream);
            if (status < 200 || status >= 300) {
                throw new HttpStatusException(status, body);
            }
            return body;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[2048];
            int n;
            while ((n = br.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private static boolean isLocalOrPrivateUrl(String urlStr) {
        try {
            String host = new URL(urlStr).getHost().toLowerCase(Locale.ROOT);
            if ("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host)) {
                return true;
            }
            if (host.startsWith("10.") || host.startsWith("192.168.")) {
                return true;
            }
            if (host.startsWith("172.")) {
                String[] parts = host.split("\\.");
                if (parts.length >= 2) {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String sanitizeSummary(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("(?is)<think>.*?</think>", " ");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.startsWith("**") && s.endsWith("**") && s.length() > 4) {
            s = s.substring(2, s.length() - 2).trim();
        }
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            if (s.length() > 2) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        if (s.regionMatches(true, 0, "Özet:", 0, 5)) {
            s = s.substring(5).trim();
        } else if (s.regionMatches(true, 0, "Summary:", 0, 8)) {
            s = s.substring(8).trim();
        }
        if (s.length() > MAX_SUMMARY_CHARS) {
            s = s.substring(0, MAX_SUMMARY_CHARS - 3).trim() + "...";
        }
        return s;
    }

    private static String shortKey(String key) {
        if (key == null || key.isEmpty()) {
            return "test";
        }
        String[] parts = key.split("\\|");
        return parts.length > 1 ? parts[1] : truncate(key, 24);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    private static final class HttpStatusException extends IOException {
        final int code;
        final String body;

        HttpStatusException(int code, String body) {
            super("HTTP " + code + ": " + truncate(body, 160));
            this.code = code;
            this.body = body;
        }
    }
}
