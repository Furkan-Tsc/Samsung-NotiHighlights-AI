package com.s23u.notihighlights.tr;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import android.util.AtomicFile;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Universal One UI 8.5 LSPosed entry point scoped to android, com.android.systemui,
 * and com.android.settings.
 * Enables Samsung Notification Highlights (Priority + Summary) across One UI 8.5 devices
 * with full Turkish support via policy auto-installation and a Binder LLM bridge.
 */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "NotiHighlightsTR";
    private static final String MODULE_PACKAGE = "com.s23u.notihighlights.tr";
    private static final String ASSET_SCPM_POLICY = "assets/notification_ai_scpm_policies.json";
    private static final int MIN_AI_VERSION = 20261;
    private static final int MIN_LLM_MINOR_VERSION = 70;
    public static final String ACTION_SYNC_CONFIG = "com.s23u.notihighlights.tr.ACTION_SYNC_CONFIG";
    public static final String ACTION_TRIGGER_TEST_SUMMARY =
            "com.s23u.notihighlights.tr.ACTION_TRIGGER_TEST_SUMMARY";
    public static final String EXTRA_TEST_MARKER = "noti_highlights_tr_test";

    private static final Uri PROVIDER_URI = Uri.parse("content://com.s23u.notihighlights.tr.provider");

    private static final Pattern TR_DATETIME_PATTERN = Pattern.compile(
            "(?:(?<![\\p{L}\\p{N}])(?:([01]?\\d|2[0-3])[:.][0-5]\\d|"
                    + "\\d{1,2}[./-]\\d{1,2}(?:[./-]\\d{2,4})?|"
                    + "bugün|yarın|akşam|sabah|öğle|gece|"
                    + "saat\\s+\\d{1,2}(?:[:.]\\d{2})?|"
                    + "pazartesi|salı|çarşamba|perşembe|cuma|cumartesi|pazar|"
                    + "ocak|şubat|mart|nisan|mayıs|haziran|temmuz|ağustos|eylül|ekim|kasım|aralık|"
                    + "\\d+\\s*(?:dk|dakika|saat|gün)\\s*(?:içinde|sonra|kaldı|içerisinde))"
                    + "(?![\\p{L}\\p{N}]))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private volatile Context sysContext;
    private volatile Object nmsInstance;
    private volatile Object summaryMgr;
    private volatile Class<?> notiSummaryManagerClass;
    private volatile Class<?> notiSummaryInputClass;

    private volatile boolean cfgEnabled = true;
    private volatile boolean cfgAllowScreenOn = true;
    private volatile boolean cfgAllowPowerSave = true;
    private volatile boolean cfgAllowLowBattery = false;
    private volatile long cfgWaitPrivateMs = PrefsStore.DEFAULT_WAIT_PRIVATE_SEC * 1000L;
    private volatile long cfgWaitGroupMs = PrefsStore.DEFAULT_WAIT_GROUP_SEC * 1000L;
    private volatile long cfgAlarmDelayMs = PrefsStore.DEFAULT_ALARM_DELAY_SEC * 1000L;
    private volatile int cfgMinWords = PrefsStore.DEFAULT_MIN_WORDS;
    private volatile long testForceUntilMs = 0L;
    private volatile boolean configLoadedFromProvider = false;
    private volatile boolean receiverRegistered = false;

    private final ExecutorService summaryExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> inFlightKeys = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean maxHeightHookErrorLogged = new AtomicBoolean(false);
    private static final AtomicBoolean updateLimitsHookErrorLogged = new AtomicBoolean(false);
    private static final AtomicBoolean singleLineHookErrorLogged = new AtomicBoolean(false);
    private static final AtomicBoolean settingsHooked = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null) {
            return;
        }
        if ("android".equals(lpparam.packageName)) {
            logInfo("Loaded into system_server (" + lpparam.processName + ")");
            ClassLoader cl = lpparam.classLoader;

            hookNmRuneUniversalSupport(cl);
            hookNotificationManagerServiceStart(cl);
            hookScpmPolicyAutoInstall(cl);
            hookPriorityNotificationTurkish(cl);
            hookSummaryTriggersAndThresholds(cl);
            hookNotiSummarizerDetect(cl);
        } else if ("com.android.systemui".equals(lpparam.packageName)) {
            if (XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                    lpparam.classLoader) == null) {
                return;
            }
            logInfo("Loaded into com.android.systemui");
            hookSystemUiSummaryHeight(lpparam.classLoader);
        } else if ("com.android.settings".equals(lpparam.packageName)) {
            if (XposedHelpers.findClassIfExists(
                    "com.samsung.android.feature.SemFloatingFeature",
                    lpparam.classLoader) == null) {
                return;
            }
            if (settingsHooked.compareAndSet(false, true)) {
                logInfo("Loaded into com.android.settings");
                hookSecSettingsAiFeatures(lpparam.classLoader);
            }
        }
    }

    private static int computeDesiredSummaryMaxHeight(View view) {
        DisplayMetrics dm = view.getResources().getDisplayMetrics();
        float fontScale = Math.max(1.0f, dm.scaledDensity / dm.density);
        return (int) (240f * dm.density * fontScale);
    }

    private static boolean isInsideSummarizedMessagingView(
            View view, Class<?> messagingLayoutCls, Class<?> conversationLayoutCls) {
        ViewParent p = view.getParent();
        for (int depth = 0; p != null && depth < 6; p = p.getParent(), depth++) {
            boolean isMessaging = messagingLayoutCls != null && messagingLayoutCls.isInstance(p);
            boolean isConversation = conversationLayoutCls != null && conversationLayoutCls.isInstance(p);
            if (isMessaging || isConversation) {
                boolean collapsed = XposedHelpers.getBooleanField(p, "mIsCollapsed");
                CharSequence sum = (CharSequence) XposedHelpers.getObjectField(p, "mSummarizedContent");
                return collapsed && sum != null && sum.length() > 0;
            }
        }
        return false;
    }

    private void hookSystemUiSummaryHeight(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                    cl,
                    "updateLimits",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                View row = (View) param.thisObject;
                                int desired = computeDesiredSummaryMaxHeight(row);
                                int maxExpanded = XposedHelpers.getIntField(row, "mMaxExpandedHeight");
                                if (maxExpanded > 0) {
                                    desired = Math.min(desired, maxExpanded);
                                }
                                int cur = XposedHelpers.getIntField(row, "mMaxSmallHeightWithSummarization");
                                if (cur < desired) {
                                    XposedHelpers.setIntField(row, "mMaxSmallHeightWithSummarization", desired);
                                }
                            } catch (Throwable t) {
                                if (updateLimitsHookErrorLogged.compareAndSet(false, true)) {
                                    logError("ExpandableNotificationRow.updateLimits hook error", t);
                                }
                            }
                        }
                    });
            logInfo("Hooked ExpandableNotificationRow.updateLimits in SystemUI");
        } catch (Throwable t) {
            logError("hook ExpandableNotificationRow.updateLimits failed", t);
        }

        try {
            final Class<?> messagingLayoutCls = XposedHelpers.findClassIfExists(
                    "com.android.internal.widget.MessagingLayout", cl);
            final Class<?> conversationLayoutCls = XposedHelpers.findClassIfExists(
                    "com.android.internal.widget.ConversationLayout", cl);
            final ThreadLocal<java.util.IdentityHashMap<Object, Integer>> savedOrigMaxTl =
                    ThreadLocal.withInitial(java.util.IdentityHashMap::new);
            XposedHelpers.findAndHookMethod(
                    "com.android.internal.widget.NotificationMaxHeightFrameLayout",
                    cl,
                    "onMeasure",
                    int.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                View frame = (View) param.thisObject;
                                if (!isInsideSummarizedMessagingView(
                                        frame, messagingLayoutCls, conversationLayoutCls)) {
                                    return;
                                }
                                int origMax = XposedHelpers.getIntField(frame, "mNotificationMaxHeight");
                                int desired = computeDesiredSummaryMaxHeight(frame);
                                if (desired > origMax) {
                                    XposedHelpers.setIntField(frame, "mNotificationMaxHeight", desired);
                                    savedOrigMaxTl.get().put(frame, Integer.valueOf(origMax));
                                }
                            } catch (Throwable t) {
                                if (maxHeightHookErrorLogged.compareAndSet(false, true)) {
                                    logError("NotificationMaxHeightFrameLayout.onMeasure before failed", t);
                                }
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Integer orig = savedOrigMaxTl.get().remove(param.thisObject);
                            if (orig == null) {
                                return;
                            }
                            try {
                                XposedHelpers.setIntField(
                                        param.thisObject, "mNotificationMaxHeight", orig.intValue());
                            } catch (Throwable t) {
                                if (maxHeightHookErrorLogged.compareAndSet(false, true)) {
                                    logError("NotificationMaxHeightFrameLayout.onMeasure after failed", t);
                                }
                            }
                        }
                    });
            logInfo("Hooked NotificationMaxHeightFrameLayout.onMeasure in SystemUI");
        } catch (Throwable t) {
            logError("hook NotificationMaxHeightFrameLayout.onMeasure failed", t);
        }

        try {
            Class<?> binderCls = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.row.ui.viewbinder.SingleLineViewBinder", cl);
            Class<?> vmCls = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.row.ui.viewmodel.SingleLineViewModel", cl);
            Class<?> hybridCls = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.row.HybridNotificationView", cl);
            final Class<?> hybridConvCls = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.row.HybridConversationNotificationView", cl);

            XposedHelpers.findAndHookMethod(
                    binderCls,
                    "bind",
                    vmCls,
                    hybridCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args == null || param.args.length < 2
                                        || param.args[0] == null || param.args[1] == null) {
                                    return;
                                }
                                if (!hybridConvCls.isInstance(param.args[1])) {
                                    return;
                                }
                                Object convData = XposedHelpers.getObjectField(param.args[0], "conversationData");
                                if (convData == null) {
                                    return;
                                }
                                CharSequence sum = (CharSequence) XposedHelpers.getObjectField(convData, "summarization");
                                if (sum != null && sum.length() > 0) {
                                    TextView tv = (TextView) XposedHelpers.getObjectField(param.args[1], "mTextView");
                                    if (tv != null) {
                                        tv.setMaxLines(6);
                                    }
                                }
                            } catch (Throwable t) {
                                if (singleLineHookErrorLogged.compareAndSet(false, true)) {
                                    logError("SingleLineViewBinder.bind hook error", t);
                                }
                            }
                        }
                    });
            logInfo("Hooked SingleLineViewBinder.bind in SystemUI");
        } catch (Throwable t) {
            logError("hook SingleLineViewBinder.bind failed", t);
        }
    }

    private static boolean hasStackFrame(String className) {
        for (StackTraceElement el : Thread.currentThread().getStackTrace()) {
            if (className.equals(el.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private static int parseSafeInt(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int parseLlmMinorVersion(Object raw) {
        if (raw == null) {
            return 0;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            String[] parts = s.split("\\.");
            if (parts.length > 1 && !parts[1].isEmpty()) {
                return Integer.parseInt(parts[1].trim());
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private void hookSemFloatingFeatureString(ClassLoader cl, XC_MethodHook callback) {
        Class<?> ffCls = XposedHelpers.findClassIfExists("com.samsung.android.feature.SemFloatingFeature", cl);
        if (ffCls == null) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(ffCls, "getString", String.class, String.class, callback);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(ffCls, "getString", String.class, callback);
        } catch (Throwable ignored) {
        }
    }

    private void hookSecSettingsAiFeatures(ClassLoader cl) {
        try {
            hookSemFloatingFeatureString(cl, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    String key = (String) param.args[0];
                    if ("SEC_FLOATING_FEATURE_COMMON_CONFIG_AI_VERSION".equals(key)) {
                        if (parseSafeInt(param.getResult(), 0) < MIN_AI_VERSION) {
                            param.setResult(String.valueOf(MIN_AI_VERSION));
                        }
                    } else if ("SEC_FLOATING_FEATURE_GENAI_CONFIG_LLM_VERSION".equals(key)) {
                        if (parseLlmMinorVersion(param.getResult()) < MIN_LLM_MINOR_VERSION) {
                            param.setResult("2." + MIN_LLM_MINOR_VERSION);
                        }
                    }
                }
            });
            logInfo("Hooked SemFloatingFeature AI gates in com.android.settings");
        } catch (Throwable t) {
            logError("hookSecSettingsAiFeatures failed", t);
        }
    }

    private void hookNmRuneUniversalSupport(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.os.SystemProperties",
                    cl,
                    "get",
                    String.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length < 1) {
                                return;
                            }
                            if (!"ro.product.vendor.device".equals(param.args[0])) {
                                return;
                            }
                            if (hasStackFrame("com.android.server.notification.NmRune")) {
                                param.setResult("m3q");
                                logInfo("NmRune spoofed ro.product.vendor.device -> m3q");
                            }
                        }
                    });
        } catch (Throwable t) {
            logError("hookNmRuneUniversalSupport SystemProperties failed", t);
        }

        try {
            hookSemFloatingFeatureString(cl, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1) {
                        return;
                    }
                    if (!"SEC_FLOATING_FEATURE_COMMON_CONFIG_AI_VERSION".equals(param.args[0])) {
                        return;
                    }
                    if (!hasStackFrame("com.android.server.notification.NmRune")) {
                        return;
                    }
                    if (parseSafeInt(param.getResult(), 0) < MIN_AI_VERSION) {
                        param.setResult(String.valueOf(MIN_AI_VERSION));
                        logInfo("NmRune spoofed COMMON_CONFIG_AI_VERSION -> " + MIN_AI_VERSION);
                    }
                }
            });
        } catch (Throwable t) {
            logError("hookNmRuneUniversalSupport SemFloatingFeature failed", t);
        }
    }

    private void hookNotificationManagerServiceStart(final ClassLoader cl) {
        try {
            notiSummaryManagerClass = XposedHelpers.findClassIfExists(
                    "com.android.server.notification.sec.summarize.NotiSummaryManager", cl);
            notiSummaryInputClass = XposedHelpers.findClassIfExists(
                    "com.android.server.notification.sec.summarize.NotiSummaryInput", cl);
            XposedHelpers.findAndHookMethod(
                    "com.android.server.notification.NotificationManagerService",
                    cl,
                    "onStart",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                nmsInstance = param.thisObject;
                                sysContext = (Context) XposedHelpers.callMethod(param.thisObject, "getContext");
                                summaryMgr = XposedHelpers.getObjectField(param.thisObject, "mNotiSummaryManager");
                                Object prioMgr = XposedHelpers.getObjectField(
                                        param.thisObject, "mPriorityNotiScenarioManager");

                                Class<?> nmRune = XposedHelpers.findClassIfExists(
                                        "com.android.server.notification.NmRune", cl);
                                boolean prioSupport = nmRune != null && XposedHelpers.getStaticBooleanField(
                                        nmRune, "NM_SUPPORT_AI_NOTIFICATION_PRIORITY");
                                boolean sumSupport = nmRune != null && XposedHelpers.getStaticBooleanField(
                                        nmRune, "NM_SUPPORT_AI_NOTIFICATION_SUMMARY");

                                logInfo("NMS.onStart complete: NM_SUPPORT_AI_NOTIFICATION_PRIORITY=" + prioSupport
                                        + ", NM_SUPPORT_AI_NOTIFICATION_SUMMARY=" + sumSupport
                                        + ", summaryMgr=" + (summaryMgr != null)
                                        + ", prioMgr=" + (prioMgr != null));

                                applyTimings();
                                registerControlReceiver();
                            } catch (Throwable t) {
                                logError("NMS.onStart hook body failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            logError("hookNotificationManagerServiceStart failed", t);
        }
    }

    private void hookScpmPolicyAutoInstall(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.notification.NotificationManagerService",
                    cl,
                    "loadAiNotificationPoliciesFile",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context ctx = sysContext;
                            try {
                                Object c = XposedHelpers.callMethod(param.thisObject, "getContext");
                                if (c instanceof Context) {
                                    ctx = (Context) c;
                                }
                            } catch (Throwable ignored) {
                            }
                            ensureTurkishScpmPolicyInstalled(ctx);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyTimings();
                        }
                    });
            logInfo("Hooked NMS.loadAiNotificationPoliciesFile for SCPM policy auto-install");
        } catch (Throwable t) {
            logError("hookScpmPolicyAutoInstall failed", t);
        }
    }

    private synchronized boolean ensureTurkishScpmPolicyInstalled(Context ctx) {
        File policyFile = new File(
                new File(Environment.getDataDirectory(), "system"),
                "notification_ai_scpm_policies.json");
        try {
            String bundledJson = readBundledScpmPolicy(ctx);
            if (bundledJson == null || bundledJson.trim().isEmpty()) {
                return false;
            }
            JSONObject bundledObj = new JSONObject(bundledJson);

            JSONObject existingObj = null;
            if (policyFile.isFile() && policyFile.length() > 0) {
                try (FileInputStream fis = new FileInputStream(policyFile)) {
                    String existingText = readStreamUtf8(fis);
                    if (existingText != null && !existingText.trim().isEmpty()) {
                        existingObj = new JSONObject(existingText);
                    }
                } catch (Throwable ignored) {
                    existingObj = null;
                }
            }

            long bundledVer = bundledObj.optLong("policy_version", 2026112101L);
            if (existingObj != null
                    && existingObj.has("turkish")
                    && existingObj.optLong("policy_version", 0L) >= bundledVer) {
                return false;
            }

            JSONObject out = existingObj != null ? existingObj : new JSONObject(bundledJson);
            if (bundledObj.has("turkish")) {
                out.put("turkish", bundledObj.getJSONObject("turkish"));
            }
            if (!out.has("english") && bundledObj.has("english")) {
                out.put("english", bundledObj.getJSONObject("english"));
            }
            if (!out.has("summary_policies") && bundledObj.has("summary_policies")) {
                out.put("summary_policies", bundledObj.getJSONArray("summary_policies"));
            }
            if (!out.has("except_scenarios") && bundledObj.has("except_scenarios")) {
                out.put("except_scenarios", bundledObj.getJSONArray("except_scenarios"));
            }

            LinkedHashSet<String> allowSet = new LinkedHashSet<>();
            addJsonArrayStrings(out.optJSONArray("summary_allow_list"), allowSet);
            addJsonArrayStrings(bundledObj.optJSONArray("summary_allow_list"), allowSet);
            allowSet.add(MODULE_PACKAGE);
            JSONArray mergedAllow = new JSONArray();
            for (String pkg : allowSet) {
                mergedAllow.put(pkg);
            }
            out.put("summary_allow_list", mergedAllow);

            String mergedExclude = mergeCommaKeywords(
                    out.optString("summary_exclude_keywords", ""),
                    bundledObj.optString("summary_exclude_keywords", ""));
            if (!mergedExclude.isEmpty()) {
                out.put("summary_exclude_keywords", mergedExclude);
            }

            long existingVer = existingObj != null ? existingObj.optLong("policy_version", 0L) : 0L;
            long targetVer = existingVer >= bundledVer ? (existingVer + 1L) : bundledVer;
            out.put("policy_version", targetVer);

            AtomicFile atomic = new AtomicFile(policyFile);
            FileOutputStream fos = null;
            try {
                fos = atomic.startWrite();
                fos.write(out.toString(2).getBytes(StandardCharsets.UTF_8));
                atomic.finishWrite(fos);
                fos = null;
            } finally {
                if (fos != null) {
                    atomic.failWrite(fos);
                }
            }

            policyFile.setReadable(false, false);
            policyFile.setReadable(true, true);
            policyFile.setWritable(false, false);
            policyFile.setWritable(true, true);

            logInfo("Auto-installed/merged Turkish SCPM policy (version=" + targetVer + ")");
            return true;
        } catch (Throwable t) {
            logError("ensureTurkishScpmPolicyInstalled failed", t);
            return false;
        }
    }

    private static void addJsonArrayStrings(JSONArray arr, LinkedHashSet<String> out) {
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            String item = arr.optString(i, "").trim();
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
    }

    private static String mergeCommaKeywords(String existingCsv, String bundledCsv) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        addCsvTokens(existingCsv, tokens);
        addCsvTokens(bundledCsv, tokens);
        return String.join(", ", tokens);
    }

    private static void addCsvTokens(String csv, LinkedHashSet<String> out) {
        if (csv == null || csv.isEmpty()) {
            return;
        }
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
    }

    private String readBundledScpmPolicy(Context ctx) {
        try (InputStream is = HookEntry.class.getClassLoader().getResourceAsStream(ASSET_SCPM_POLICY)) {
            if (is != null) {
                String text = readStreamUtf8(is);
                if (text != null && !text.trim().isEmpty()) {
                    return text;
                }
            }
        } catch (Throwable ignored) {
        }

        if (ctx != null) {
            try {
                String apkPath = ctx.getPackageManager().getApplicationInfo(MODULE_PACKAGE, 0).sourceDir;
                if (apkPath != null && !apkPath.isEmpty()) {
                    try (ZipFile zf = new ZipFile(apkPath)) {
                        ZipEntry entry = zf.getEntry(ASSET_SCPM_POLICY);
                        if (entry != null) {
                            try (InputStream is = zf.getInputStream(entry)) {
                                String text = readStreamUtf8(is);
                                if (text != null && !text.trim().isEmpty()) {
                                    return text;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            long token = Binder.clearCallingIdentity();
            try {
                Bundle res = ctx.getContentResolver().call(
                        PROVIDER_URI, NotiBridgeProvider.METHOD_GET_SCPM_POLICY, null, null);
                if (res != null && res.getBoolean(NotiBridgeProvider.RES_OK, false)) {
                    String text = res.getString(NotiBridgeProvider.RES_POLICY_JSON, null);
                    if (text != null && !text.trim().isEmpty()) {
                        return text;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        return null;
    }

    private static String readStreamUtf8(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private void hookPriorityNotificationTurkish(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.notification.sec.ainotification.prioritynotification.PriorityNotiScenarioManager",
                    cl,
                    "getCurrentLanguage",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args.length > 0
                                    && param.args[0] instanceof String
                                    && "tr".equalsIgnoreCase((String) param.args[0])) {
                                param.setResult("turkish");
                            }
                        }
                    });
        } catch (Throwable t) {
            logError("hook PriorityNotiScenarioManager.getCurrentLanguage failed", t);
        }

        try {
            final Class<?> extractorCls = XposedHelpers.findClass(
                    "com.android.server.notification.sec.ainotification.prioritynotification.extractor.PriorityNotiScenarioExtractor",
                    cl);
            XposedHelpers.findAndHookMethod(
                    extractorCls,
                    "pattern",
                    String[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String[] arr = (param.args != null && param.args.length > 0)
                                    ? (String[]) param.args[0]
                                    : null;
                            if (arr == null || arr.length == 0) {
                                return;
                            }
                            ArrayList<String> valid = new ArrayList<>();
                            for (String item : arr) {
                                if (item != null) {
                                    String trimmed = item.trim();
                                    if (!trimmed.isEmpty()) {
                                        valid.add(trimmed);
                                    }
                                }
                            }
                            if (valid.isEmpty()) {
                                Object emptyPattern = XposedHelpers.getStaticObjectField(extractorCls, "EMPTY_PATTERN");
                                if (emptyPattern != null) {
                                    param.setResult(emptyPattern);
                                }
                                return;
                            }
                            try {
                                Pattern compiled = Pattern.compile(
                                        String.join("|", valid),
                                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                                param.setResult(compiled);
                            } catch (Throwable ex) {
                                logError("Unicode pattern compile fallback", ex);
                            }
                        }
                    });
        } catch (Throwable t) {
            logError("hook PriorityNotiScenarioExtractor.pattern failed", t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.notification.sec.textmining.NotificationTextMining",
                    cl,
                    "execute",
                    String.class,
                    String.class,
                    Object[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject == null
                                    || !param.thisObject.getClass().getName().endsWith("NotificationDateTimeFilter")) {
                                return;
                            }
                            Object resObj = param.getResult();
                            if (resObj instanceof Long && ((Long) resObj) >= 0L) {
                                return;
                            }
                            if (param.args != null && param.args.length > 1 && param.args[1] instanceof String) {
                                String msg = (String) param.args[1];
                                if (!msg.isEmpty() && TR_DATETIME_PATTERN.matcher(msg).find()) {
                                    param.setResult(1L);
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            logError("hook NotificationTextMining.execute failed", t);
        }
    }

    private void hookSummaryTriggersAndThresholds(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.notification.sec.summarize.NotiSummaryManager",
                    cl,
                    "checkDeviceStateForSummary",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!cfgEnabled) {
                                return;
                            }
                            if (!cfgAllowScreenOn && !cfgAllowLowBattery && !cfgAllowPowerSave) {
                                return;
                            }
                            Object mgr = param.thisObject;
                            Object screenRx = XposedHelpers.getObjectField(mgr, "mScreenStateReceiver");
                            Object batteryRx = XposedHelpers.getObjectField(mgr, "mBatteryStateReceiver");
                            Object limit = XposedHelpers.getObjectField(mgr, "mLimit");
                            Object scheduler = XposedHelpers.getObjectField(mgr, "mAlarmScheduler");

                            boolean isScreenOff = screenRx != null && XposedHelpers.getBooleanField(screenRx, "isScreenOff");
                            int batLevel = batteryRx != null ? XposedHelpers.getIntField(batteryRx, "mLevel") : 100;
                            boolean isPowerSave = batteryRx != null && XposedHelpers.getBooleanField(batteryRx, "mIsPowerSave");
                            boolean isLimited = limit != null && Boolean.TRUE.equals(XposedHelpers.callMethod(limit, "isLimited"));
                            boolean isRequested = scheduler != null && XposedHelpers.getBooleanField(scheduler, "isRequested");

                            boolean ok = (cfgAllowScreenOn || isScreenOff)
                                    && (cfgAllowLowBattery || batLevel > 30)
                                    && (cfgAllowPowerSave || !isPowerSave)
                                    && !isLimited
                                    && !isRequested;
                            if (!ok) {
                                logInfo("checkDeviceStateForSummary=false (" + param.args[0]
                                        + "): screenOff=" + isScreenOff
                                        + ", bat=" + batLevel
                                        + ", powerSave=" + isPowerSave
                                        + ", limited=" + isLimited
                                        + ", requested=" + isRequested);
                            }
                            param.setResult(ok);
                        }
                    });
        } catch (Throwable t) {
            logError("hook NotiSummaryManager.checkDeviceStateForSummary failed", t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.notification.sec.summarize.NotiSummaryManager$BatteryStateReceiver",
                    cl,
                    "onReceive",
                    Context.class,
                    Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!cfgEnabled || (!cfgAllowPowerSave && !cfgAllowLowBattery)) {
                                return;
                            }
                            if (param.args == null || param.args.length < 2) {
                                return;
                            }
                            Intent intent = (Intent) param.args[1];
                            if (intent == null) {
                                return;
                            }
                            String action = intent.getAction();
                            Object outerMgr = summaryMgr;
                            if (outerMgr == null) {
                                try {
                                    outerMgr = XposedHelpers.getObjectField(param.thisObject, "this$0");
                                } catch (Throwable ignored) {
                                }
                            }
                            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                                int scale = intent.getIntExtra("scale", 100);
                                int rawLevel = intent.getIntExtra("level", 100);
                                int pct = scale > 0 ? (int) ((rawLevel * 100.0f) / scale) : 100;
                                XposedHelpers.setIntField(param.thisObject, "mLevel", pct);
                            }
                            if ("android.os.action.POWER_SAVE_MODE_CHANGED".equals(action)
                                    || Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                                if (outerMgr != null) {
                                    PowerManager pm = (PowerManager) XposedHelpers.getObjectField(
                                            outerMgr, "mPowerManager");
                                    if (pm != null) {
                                        XposedHelpers.setBooleanField(
                                                param.thisObject, "mIsPowerSave", pm.isPowerSaveMode());
                                    }
                                }
                            }
                            boolean isPowerSave = XposedHelpers.getBooleanField(param.thisObject, "mIsPowerSave");
                            int curLevel = XposedHelpers.getIntField(param.thisObject, "mLevel");
                            if (((!cfgAllowPowerSave && isPowerSave) || (!cfgAllowLowBattery && curLevel <= 30))
                                    && outerMgr != null) {
                                XposedHelpers.callMethod(outerMgr, "cancelAll");
                            }
                            param.setResult(null);
                        }
                    });
        } catch (Throwable t) {
            logError("hook BatteryStateReceiver.onReceive failed", t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.notification.sec.summarize.NotiSummaryManager$ScreenStateReceiver",
                    cl,
                    "onReceive",
                    Context.class,
                    Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!cfgEnabled || !cfgAllowScreenOn || param.args == null || param.args.length < 2) {
                                return;
                            }
                            Intent intent = (Intent) param.args[1];
                            if (intent != null && Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                                XposedHelpers.setBooleanField(param.thisObject, "isScreenOff", false);
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            logError("hook ScreenStateReceiver.onReceive failed", t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.notification.sec.summarize.NotiSummaryManager",
                    cl,
                    "isFresh",
                    Notification.class,
                    long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (System.currentTimeMillis() >= testForceUntilMs || param.args == null || param.args.length < 1) {
                                return;
                            }
                            Notification n = (Notification) param.args[0];
                            if (n != null && n.extras != null && n.extras.getBoolean(EXTRA_TEST_MARKER, false)) {
                                param.setResult(Boolean.FALSE);
                            }
                        }
                    });
        } catch (Throwable t) {
            logError("hook NotiSummaryManager.isFresh failed", t);
        }

        try {
            Class<?> historyMgrCls = XposedHelpers.findClass(
                    "com.android.server.notification.NotificationHistoryManager", cl);
            XposedHelpers.findAndHookMethod(
                    "com.android.server.notification.sec.summarize.NotiSummaryInput",
                    cl,
                    "from",
                    Context.class,
                    historyMgrCls,
                    StatusBarNotification.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!cfgEnabled) {
                                return;
                            }
                            Object input = param.getResult();
                            if (input == null) {
                                return;
                            }
                            int count = XposedHelpers.getIntField(input, "count");
                            if (count >= cfgMinWords && count < 40) {
                                XposedHelpers.setIntField(input, "count", 40);
                            }
                        }
                    });
        } catch (Throwable t) {
            logError("hook NotiSummaryInput.from failed", t);
        }
    }

    private static final class ResolvedSummaryCallback {
        final Object mgr;
        final String notiKey;
        final BiConsumer<Boolean, String> finalCb;

        ResolvedSummaryCallback(Object mgr, String notiKey, BiConsumer<Boolean, String> finalCb) {
            this.mgr = mgr;
            this.notiKey = notiKey;
            this.finalCb = finalCb;
        }
    }

    @SuppressWarnings("unchecked")
    private ResolvedSummaryCallback resolveSummaryCallback(
            Object cbObj, Class<?> summaryMgrCls, Class<?> summaryInputCls, Object fallbackMgr) {
        if (cbObj == null) {
            return null;
        }
        boolean foundWakeLock = false;
        Object foundMgr = null;
        boolean foundSummaryInput = false;
        BiConsumer<Boolean, String> foundFinalCb = null;
        String foundKey = null;

        for (Class<?> cur = cbObj.getClass(); cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Field f : cur.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Object val;
                try {
                    f.setAccessible(true);
                    val = f.get(cbObj);
                } catch (Throwable t) {
                    continue;
                }
                if (val == null) {
                    continue;
                }
                if (val instanceof PowerManager.WakeLock) {
                    foundWakeLock = true;
                } else if (summaryMgrCls != null && summaryMgrCls.isInstance(val)) {
                    foundMgr = val;
                } else if (summaryInputCls != null && summaryInputCls.isInstance(val)) {
                    foundSummaryInput = true;
                } else if (val instanceof BiConsumer && val != cbObj) {
                    if (foundFinalCb == null) {
                        foundFinalCb = (BiConsumer<Boolean, String>) val;
                    }
                } else if (val instanceof String) {
                    String s = (String) val;
                    if (foundKey == null || (s.contains("|") && !foundKey.contains("|"))) {
                        foundKey = s;
                    }
                }
            }
        }

        if (foundWakeLock || foundFinalCb == null) {
            return null;
        }
        if (!foundSummaryInput && (foundKey == null || !foundKey.contains("|"))) {
            return null;
        }
        Object resolvedMgr = foundMgr != null ? foundMgr : fallbackMgr;
        if (resolvedMgr == null) {
            return null;
        }
        return new ResolvedSummaryCallback(resolvedMgr, foundKey, foundFinalCb);
    }

    private void hookNotiSummarizerDetect(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.notification.sec.summarize.NotiSummarizer",
                    cl,
                    "detect",
                    String.class,
                    BiConsumer.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!cfgEnabled || param.args == null || param.args.length < 2) {
                                return;
                            }
                            final String inputJson = (String) param.args[0];
                            ResolvedSummaryCallback resolved = resolveSummaryCallback(
                                    param.args[1], notiSummaryManagerClass, notiSummaryInputClass, summaryMgr);
                            if (resolved == null) {
                                return;
                            }

                            final Object mgr = resolved.mgr;
                            final String notiKey = resolved.notiKey;
                            final BiConsumer<Boolean, String> finalCb = resolved.finalCb;

                            // Bypass Samsung NeuralTranslator / OfflineLanguageModel completely.
                            param.setResult(null);

                            final String trackKey = notiKey != null ? notiKey : String.valueOf(System.nanoTime());
                            if (!inFlightKeys.add(trackKey)) {
                                logInfo("Skip duplicate in-flight summarize for " + trackKey);
                                return;
                            }

                            Object limiter = XposedHelpers.getObjectField(mgr, "mLimit");
                            if (limiter != null && Boolean.TRUE.equals(XposedHelpers.callMethod(limiter, "isLimited"))) {
                                inFlightKeys.remove(trackKey);
                                finalCb.accept(Boolean.FALSE, "limit reached");
                                return;
                            }
                            if (limiter != null) {
                                int cnt = XposedHelpers.getIntField(limiter, "mCount");
                                XposedHelpers.setIntField(limiter, "mCount", cnt + 1);
                            }

                            PowerManager pm = (PowerManager) XposedHelpers.getObjectField(mgr, "mPowerManager");
                            final PowerManager.WakeLock wl = pm != null
                                    ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NotiHighlightsTR:WakeLock")
                                    : null;
                            if (wl != null) {
                                wl.setReferenceCounted(false);
                                wl.acquire(35_000L);
                            }

                            final Handler mgrHandler = (Handler) XposedHelpers.getObjectField(mgr, "mHandler");
                            logInfo("Intercepted NotiSummarizer.detect -> delegating via NotiBridgeProvider for " + trackKey);

                            summaryExecutor.execute(new Runnable() {
                                @Override
                                public void run() {
                                    boolean ok = false;
                                    String payload = "Internal bridge error";
                                    try {
                                        if (!configLoadedFromProvider) {
                                            refreshConfigSync();
                                        }
                                        if (!cfgEnabled) {
                                            ok = false;
                                            payload = "NotiHighlights TR disabled";
                                        } else if (sysContext == null) {
                                            ok = false;
                                            payload = "sysContext null";
                                        } else {
                                            Bundle req = new Bundle();
                                            req.putString(NotiBridgeProvider.ARG_INPUT_JSON,
                                                    inputJson != null ? inputJson : "");
                                            req.putString(NotiBridgeProvider.ARG_NOTI_KEY, trackKey);

                                            Bundle res;
                                            long token = Binder.clearCallingIdentity();
                                            try {
                                                res = sysContext.getContentResolver().call(
                                                        PROVIDER_URI,
                                                        NotiBridgeProvider.METHOD_SUMMARIZE,
                                                        null,
                                                        req);
                                            } finally {
                                                Binder.restoreCallingIdentity(token);
                                            }

                                            if (res != null && res.getBoolean(NotiBridgeProvider.RES_OK, false)) {
                                                String summary = res.getString(NotiBridgeProvider.RES_SUMMARY, "");
                                                if (!summary.isEmpty()) {
                                                    ok = true;
                                                    payload = summary;
                                                    long ms = res.getLong(NotiBridgeProvider.RES_LATENCY_MS, 0L);
                                                    logInfo("Summary OK (" + ms + "ms) for " + trackKey);
                                                } else {
                                                    payload = "Empty summary returned";
                                                }
                                            } else {
                                                payload = res != null
                                                        ? res.getString(NotiBridgeProvider.RES_ERROR, "Unknown provider error")
                                                        : "NotiBridgeProvider returned null";
                                                logInfo("Summary rejected/failed for " + trackKey + ": " + payload);
                                            }
                                        }
                                    } catch (Throwable t) {
                                        ok = false;
                                        payload = "Bridge exception: " + t.getMessage();
                                        logError("summarizeExecutor failed for " + trackKey, t);
                                    }

                                    final boolean deliverOk = ok;
                                    final String deliverPayload = payload;
                                    Runnable deliverTask = new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                finalCb.accept(deliverOk, deliverPayload);
                                            } catch (Throwable t) {
                                                logError("finalCb.accept threw for " + trackKey, t);
                                            } finally {
                                                inFlightKeys.remove(trackKey);
                                                safeReleaseWakeLock(wl);
                                            }
                                        }
                                    };
                                    if (mgrHandler != null) {
                                        mgrHandler.post(deliverTask);
                                    } else {
                                        deliverTask.run();
                                    }
                                }
                            });
                        }
                    });
        } catch (Throwable t) {
            logError("hook NotiSummarizer.detect failed", t);
        }
    }

    private void registerControlReceiver() {
        if (receiverRegistered || sysContext == null) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_UNLOCKED);
            filter.addAction(ACTION_SYNC_CONFIG);
            filter.addAction(ACTION_TRIGGER_TEST_SUMMARY);

            BroadcastReceiver rx = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    String action = intent.getAction();
                    if (Intent.ACTION_USER_UNLOCKED.equals(action) || ACTION_SYNC_CONFIG.equals(action)) {
                        logInfo("Received " + action + " -> refreshing config");
                        summaryExecutor.execute(new Runnable() {
                            @Override
                            public void run() {
                                refreshConfigSync();
                                try {
                                    if (ensureTurkishScpmPolicyInstalled(sysContext) && nmsInstance != null) {
                                        XposedHelpers.callMethod(nmsInstance, "loadAiNotificationPoliciesFile");
                                    }
                                } catch (Throwable t) {
                                    logError("reload SCPM policy after config sync failed", t);
                                }
                            }
                        });
                    } else if (ACTION_TRIGGER_TEST_SUMMARY.equals(action)) {
                        logInfo("Received ACTION_TRIGGER_TEST_SUMMARY -> scheduling immediate summary task");
                        testForceUntilMs = System.currentTimeMillis() + 30_000L;
                        summaryExecutor.execute(new Runnable() {
                            @Override
                            public void run() {
                                refreshConfigSync();
                                triggerSummaryTaskNow();
                            }
                        });
                    }
                }
            };
            sysContext.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
            logInfo("Control BroadcastReceiver registered");
        } catch (Throwable t) {
            logError("registerControlReceiver failed", t);
        }
    }

    private void triggerSummaryTaskNow() {
        Object mgr = summaryMgr;
        if (mgr == null) {
            logInfo("triggerSummaryTaskNow: mNotiSummaryManager is null");
            return;
        }
        try {
            Handler h = (Handler) XposedHelpers.getObjectField(mgr, "mHandler");
            Runnable task = (Runnable) XposedHelpers.getObjectField(mgr, "mSummaryTask");
            if (h != null && task != null) {
                h.post(task);
            } else {
                logInfo("triggerSummaryTaskNow: mHandler or mSummaryTask null");
            }
        } catch (Throwable t) {
            logError("triggerSummaryTaskNow failed", t);
        }
    }

    private void refreshConfigSync() {
        Context ctx = sysContext;
        if (ctx == null) {
            return;
        }
        long token = Binder.clearCallingIdentity();
        try {
            Bundle cfg = ctx.getContentResolver().call(
                    PROVIDER_URI, NotiBridgeProvider.METHOD_GET_CONFIG, null, null);
            if (cfg != null) {
                cfgEnabled = cfg.getBoolean(PrefsStore.KEY_ENABLED, true);
                cfgAllowScreenOn = cfg.getBoolean(PrefsStore.KEY_ALLOW_SCREEN_ON, true);
                cfgAllowPowerSave = cfg.getBoolean(PrefsStore.KEY_ALLOW_POWER_SAVE, true);
                cfgAllowLowBattery = cfg.getBoolean(PrefsStore.KEY_ALLOW_LOW_BATTERY, false);
                cfgWaitPrivateMs = Math.max(1, cfg.getInt(
                        PrefsStore.KEY_WAIT_PRIVATE_SEC, PrefsStore.DEFAULT_WAIT_PRIVATE_SEC)) * 1000L;
                cfgWaitGroupMs = Math.max(1, cfg.getInt(
                        PrefsStore.KEY_WAIT_GROUP_SEC, PrefsStore.DEFAULT_WAIT_GROUP_SEC)) * 1000L;
                cfgAlarmDelayMs = Math.max(1, cfg.getInt(
                        PrefsStore.KEY_ALARM_DELAY_SEC, PrefsStore.DEFAULT_ALARM_DELAY_SEC)) * 1000L;
                cfgMinWords = Math.max(1, cfg.getInt(
                        PrefsStore.KEY_MIN_WORDS, PrefsStore.DEFAULT_MIN_WORDS));
                configLoadedFromProvider = true;
                applyTimings();
                logInfo("Config synced: enabled=" + cfgEnabled
                        + ", screenOn=" + cfgAllowScreenOn
                        + ", powerSave=" + cfgAllowPowerSave
                        + ", waitPrivateMs=" + cfgWaitPrivateMs
                        + ", waitGroupMs=" + cfgWaitGroupMs
                        + ", minWords=" + cfgMinWords);
            }
        } catch (Throwable t) {
            logInfo("refreshConfigSync deferred/failed: " + t.getMessage());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void applyTimings() {
        try {
            if (notiSummaryManagerClass != null) {
                XposedHelpers.setStaticLongField(notiSummaryManagerClass, "WAIT_MS_PRIVATE", cfgWaitPrivateMs);
                XposedHelpers.setStaticLongField(notiSummaryManagerClass, "WAIT_MS_GROUP", cfgWaitGroupMs);
            }
            Object mgr = summaryMgr;
            if (mgr != null) {
                Object scheduler = XposedHelpers.getObjectField(mgr, "mAlarmScheduler");
                if (scheduler != null) {
                    XposedHelpers.setLongField(scheduler, "ALARM_FIRST_DELAY", cfgAlarmDelayMs);
                    XposedHelpers.setLongField(
                            scheduler, "ALARM_INTERVAL", Math.max(cfgAlarmDelayMs, cfgWaitPrivateMs + 1_500L));
                }
            }
        } catch (Throwable t) {
            logError("applyTimings failed", t);
        }
    }

    private static void safeReleaseWakeLock(PowerManager.WakeLock wl) {
        try {
            if (wl != null && wl.isHeld()) {
                wl.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void logInfo(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    private static void logError(String msg, Throwable t) {
        XposedBridge.log(TAG + ": " + msg);
        XposedBridge.log(t);
    }
}
