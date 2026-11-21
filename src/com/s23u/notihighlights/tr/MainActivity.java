package com.s23u.notihighlights.tr;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQ_POST_NOTI = 1001;
    private static final String CH_SUMMARY = "noti_tr_msg";
    private static final String CH_PRIORITY = "noti_tr_priority";

    private static final class Preset {
        final String labelTr;
        final String labelEn;
        final String type;
        final String dialect;
        final String url;
        final String model;

        Preset(String labelTr, String labelEn, String type, String dialect, String url, String model) {
            this.labelTr = labelTr;
            this.labelEn = labelEn;
            this.type = type;
            this.dialect = dialect;
            this.url = url;
            this.model = model;
        }

        String getLabel(boolean en) {
            return en ? labelEn : labelTr;
        }
    }

    private static final Preset[] PRESETS = {
        new Preset(
                "Google Gemini",
                "Google Gemini",
                "gemini",
                PrefsStore.DIALECT_GEMINI,
                "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                "gemini-3.8-flash"),
        new Preset(
                "Groq (Ücretsiz)",
                "Groq (Free)",
                "groq",
                PrefsStore.DIALECT_OPENAI,
                "https://api.groq.com/openai/v1/chat/completions",
                "qwen/qwen3.8-27b"),
        new Preset(
                "OpenAI",
                "OpenAI",
                "openai",
                PrefsStore.DIALECT_OPENAI,
                "https://api.openai.com/v1/chat/completions",
                "gpt-4o-mini"),
        new Preset(
                "OpenRouter",
                "OpenRouter",
                "openrouter",
                PrefsStore.DIALECT_OPENAI,
                "https://openrouter.ai/api/v1/chat/completions",
                "google/gemini-2.0-flash-001"),
        new Preset(
                "Özel / Yerel (Ollama, LM Studio)",
                "Custom / Local (Ollama, LM Studio)",
                "custom",
                PrefsStore.DIALECT_OPENAI,
                "http://127.0.0.1:11434/v1/chat/completions",
                "gemma3:4b"),
    };

    private Spinner spProvider;
    private CheckBox cbShowKey;
    private EditText etApiKey;
    private EditText etModel;
    private EditText etUrl;
    private EditText etPrompt;
    private Switch swEnabled;
    private Switch swScreenOn;
    private Switch swPowerSave;
    private Switch swLowBattery;
    private EditText etWaitPrivate;
    private EditText etWaitGroup;
    private EditText etMinWords;
    private TextView tvStatusBadge;
    private TextView tvApiResult;
    private TextView tvLogs;

    private boolean isEn = false;
    private int lastSelectedProviderPos = -1;
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isEn = PrefsStore.isEnglish(this);
        buildUi();
        loadFromPrefs();
        handleAutoTestIntent(getIntent());
    }

    private String t(String tr, String en) {
        return isEn ? en : tr;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleAutoTestIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatusAndLogs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdownNow();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0D1117);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(28));
        scroll.addView(root);

        // 1. Header Card
        LinearLayout headerCard = makeCard(0xFF161B22);
        TextView title = makeText(getString(R.string.app_name), 22, true, 0xFFF0F6FC);
        TextView sub = makeText(
                t(
                        "One UI 8.5 Bildirim Öne Çıkanları (Evrensel)",
                        "One UI 8.5 Notification Highlights (Universal)"),
                13,
                false,
                0xFF8B949E);
        tvStatusBadge = makeText("", 13, true, 0xFF58A6FF);
        tvStatusBadge.setPadding(0, dp(8), 0, 0);
        headerCard.addView(title);
        headerCard.addView(sub);
        headerCard.addView(tvStatusBadge);
        root.addView(headerCard);

        // 2. Language Card
        LinearLayout langCard = makeCard(0xFF161B22);
        langCard.addView(makeSectionTitle("Dil / Language"));
        LinearLayout langRow = new LinearLayout(this);
        langRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnTr = makeLangButton("Türkçe", !isEn, 0, dp(4));
        btnTr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchLanguage(PrefsStore.LANG_TR);
            }
        });
        Button btnEn = makeLangButton("English", isEn, dp(4), 0);
        btnEn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchLanguage(PrefsStore.LANG_EN);
            }
        });
        langRow.addView(btnTr);
        langRow.addView(btnEn);
        langCard.addView(langRow);

        TextView langHint = makeText(
                t(
                        "Uygulama arayüzü ve varsayılan özet istemi seçilen dile göre güncellenir.",
                        "App interface and default summary prompt update to the selected language."),
                12,
                false,
                0xFF8B949E);
        langHint.setPadding(0, dp(8), 0, 0);
        langCard.addView(langHint);
        root.addView(langCard);

        // 3. LLM Provider Card
        LinearLayout llmCard = makeCard(0xFF161B22);
        llmCard.addView(makeSectionTitle(t("LLM Sağlayıcı Ayarları", "LLM Provider Settings")));

        llmCard.addView(makeLabel(t("Sağlayıcı Ön Ayarı", "Provider Preset")));
        spProvider = new Spinner(this);
        String[] labels = new String[PRESETS.length];
        for (int i = 0; i < PRESETS.length; i++) {
            labels[i] = PRESETS[i].getLabel(isEn);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(0xFFF0F6FC);
                }
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                v.setBackgroundColor(0xFF161B22);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(0xFFF0F6FC);
                    int p = dp(12);
                    v.setPadding(p, p, p, p);
                }
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        GradientDrawable spBg = new GradientDrawable();
        spBg.setColor(0xFF0D1117);
        spBg.setCornerRadius(dp(10));
        spBg.setStroke(dp(1), 0xFF30363D);
        spProvider.setBackground(spBg);
        spProvider.setPadding(dp(8), dp(6), dp(8), dp(6));
        spProvider.setAdapter(adapter);
        spProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == lastSelectedProviderPos) {
                    return;
                }
                lastSelectedProviderPos = pos;
                Preset p = PRESETS[pos];
                etUrl.setText(p.url);
                etModel.setText(p.model);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        llmCard.addView(spProvider);

        llmCard.addView(makeLabel(t("API Anahtarı", "API Key")));
        etApiKey = makeInput(t("API anahtarınızı yapıştırın", "Paste your API key"), true);
        llmCard.addView(etApiKey);

        cbShowKey = new CheckBox(this);
        cbShowKey.setText(t("Anahtarı Göster", "Show Key"));
        cbShowKey.setTextColor(0xFFC9D1D9);
        cbShowKey.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean checked) {
                int sel = etApiKey.getSelectionEnd();
                etApiKey.setInputType(checked
                        ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                        : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
                if (sel >= 0) {
                    etApiKey.setSelection(Math.min(sel, etApiKey.getText().length()));
                }
            }
        });
        llmCard.addView(cbShowKey);

        llmCard.addView(makeLabel(t("Model Adı", "Model Name")));
        etModel = makeInput("gemini-2.5-flash", false);
        llmCard.addView(etModel);

        llmCard.addView(makeLabel("Endpoint URL"));
        etUrl = makeInput("https://...", false);
        llmCard.addView(etUrl);

        llmCard.addView(makeLabel(t("Özet İstemi (System Prompt)", "Summary System Prompt")));
        etPrompt = makeInput("", false);
        etPrompt.setSingleLine(false);
        etPrompt.setMinLines(3);
        etPrompt.setGravity(Gravity.TOP | Gravity.START);
        llmCard.addView(etPrompt);
        TextView promptHint = makeText(
                t(
                        "İpucu: Farklı bir dilde özet almak için bu istemi istediğiniz dilde yazabilirsiniz.",
                        "Tip: Write this prompt in any language to receive summaries in that language."),
                12,
                false,
                0xFF8B949E);
        promptHint.setPadding(0, dp(4), 0, 0);
        llmCard.addView(promptHint);
        root.addView(llmCard);

        // 4. Behavior Card
        LinearLayout behCard = makeCard(0xFF161B22);
        behCard.addView(makeSectionTitle(t("Özetleme Davranışı", "Summarization Behavior")));

        swEnabled = makeSwitch(t("Bildirim Özetleme Aktif", "Notification Summarization Enabled"));
        swScreenOn = makeSwitch(t("Ekran Açıkken de Özetle (Hızlı / Test)", "Summarize While Screen On (Fast / Test)"));
        swPowerSave = makeSwitch(t("Güç Tasarrufu Modunda da Özetle", "Allow in Power Save Mode"));
        swLowBattery = makeSwitch(t("Düşük Pil (%30 altı) İzin Ver", "Allow on Low Battery (<30%)"));
        behCard.addView(swEnabled);
        behCard.addView(swScreenOn);
        behCard.addView(swPowerSave);
        behCard.addView(swLowBattery);

        LinearLayout numRow = new LinearLayout(this);
        numRow.setOrientation(LinearLayout.HORIZONTAL);
        numRow.setPadding(0, dp(8), 0, 0);

        etWaitPrivate = makeNumericColumn(numRow, t("Özel Bekleme (sn)", "Private Wait (sec)"));
        etWaitGroup = makeNumericColumn(numRow, t("Grup Bekleme (sn)", "Group Wait (sec)"));
        etMinWords = makeNumericColumn(numRow, t("Min. Kelime", "Min. Words"));
        behCard.addView(numRow);
        root.addView(behCard);

        // 5. Actions & Test Card
        LinearLayout actCard = makeCard(0xFF161B22);
        actCard.addView(makeSectionTitle(t("Kontrol ve Testler", "Controls & Tests")));

        Button btnSave = makeButton(t("Kaydet ve Sisteme Uygula", "Save & Sync to System"), 0xFF0381FE);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndSync(true);
            }
        });
        actCard.addView(btnSave);

        Button btnTestApi = makeButton(t("API Bağlantısını Test Et", "Test API Connection"), 0xFF1F6FEB);
        btnTestApi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndSync(false);
                runApiSelfTest();
            }
        });
        actCard.addView(btnTestApi);

        tvApiResult = makeText("", 13, false, 0xFFD2A8FF);
        tvApiResult.setPadding(dp(4), dp(6), dp(4), dp(6));
        actCard.addView(tvApiResult);

        Button btnTestSummaryNoti = makeButton(
                t("Özetleme Test Bildirimi Gönder", "Send Summary Test Notification"), 0xFF238636);
        btnTestSummaryNoti.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndSync(false);
                sendSummaryTestNotification(true);
            }
        });
        actCard.addView(btnTestSummaryNoti);

        Button btnTestPriorityNoti = makeButton(
                t("Öncelikli Test Bildirimi Gönder", "Send Priority Test Notification"), 0xFF8957E5);
        btnTestPriorityNoti.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendPriorityTestNotification();
            }
        });
        actCard.addView(btnTestPriorityNoti);

        Button btnSamsungSettings = makeButton(
                t("Samsung Bildirim Öne Çıkanları Ayarlarını Aç", "Open Samsung Notification Highlights Settings"),
                0xFF30363D);
        btnSamsungSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSamsungNotificationHighlightsSettings();
            }
        });
        actCard.addView(btnSamsungSettings);
        root.addView(actCard);

        // 6. Log Card
        LinearLayout logCard = makeCard(0xFF161B22);
        logCard.addView(makeSectionTitle(t("Son İşlem Günlüğü", "Recent Activity Logs")));
        Button btnRefreshLog = makeButton(t("Günlüğü Yenile", "Refresh Logs"), 0xFF21262D);
        btnRefreshLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshStatusAndLogs();
            }
        });
        logCard.addView(btnRefreshLog);

        tvLogs = makeText("", 11, false, 0xFF8B949E);
        tvLogs.setTypeface(Typeface.MONOSPACE);
        tvLogs.setPadding(0, dp(8), 0, 0);
        logCard.addView(tvLogs);
        root.addView(logCard);

        setContentView(scroll);
    }

    private void switchLanguage(String newLang) {
        String currentLang = isEn ? PrefsStore.LANG_EN : PrefsStore.LANG_TR;
        if (currentLang.equals(newLang)) {
            return;
        }
        int curProviderPos = Math.max(0, Math.min(spProvider.getSelectedItemPosition(), PRESETS.length - 1));
        String curApiKey = etApiKey.getText().toString();
        boolean curShowKey = cbShowKey != null && cbShowKey.isChecked();
        String curModel = etModel.getText().toString();
        String curUrl = etUrl.getText().toString();
        String curPrompt = etPrompt.getText().toString();
        boolean curEnabled = swEnabled.isChecked();
        boolean curScreenOn = swScreenOn.isChecked();
        boolean curPowerSave = swPowerSave.isChecked();
        boolean curLowBattery = swLowBattery.isChecked();
        String curWaitPrivate = etWaitPrivate.getText().toString();
        String curWaitGroup = etWaitGroup.getText().toString();
        String curMinWords = etMinWords.getText().toString();
        String curApiResult = tvApiResult.getText().toString();

        PrefsStore.setAppLang(this, newLang);
        isEn = PrefsStore.LANG_EN.equals(newLang);

        if (PrefsStore.isBuiltInDefaultPrompt(curPrompt)) {
            curPrompt = PrefsStore.getDefaultSystemPrompt(newLang);
            PrefsStore.prefs(this).edit().putString(PrefsStore.KEY_SYSTEM_PROMPT, curPrompt).apply();
        }

        buildUi();

        lastSelectedProviderPos = curProviderPos;
        spProvider.setSelection(curProviderPos, false);
        etApiKey.setText(curApiKey);
        if (cbShowKey != null) {
            cbShowKey.setChecked(curShowKey);
        }
        etModel.setText(curModel);
        etUrl.setText(curUrl);
        etPrompt.setText(curPrompt);
        swEnabled.setChecked(curEnabled);
        swScreenOn.setChecked(curScreenOn);
        swPowerSave.setChecked(curPowerSave);
        swLowBattery.setChecked(curLowBattery);
        etWaitPrivate.setText(curWaitPrivate);
        etWaitGroup.setText(curWaitGroup);
        etMinWords.setText(curMinWords);
        tvApiResult.setText(curApiResult);
        refreshStatusAndLogs();
    }

    private void loadFromPrefs() {
        String type = PrefsStore.getProviderType(this);
        int idx = 0;
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i].type.equals(type)) {
                idx = i;
                break;
            }
        }
        lastSelectedProviderPos = idx;
        spProvider.setSelection(idx, false);
        etApiKey.setText(PrefsStore.getApiKey(this));
        etModel.setText(PrefsStore.getApiModel(this));
        etUrl.setText(PrefsStore.getApiUrl(this));
        etPrompt.setText(PrefsStore.getSystemPrompt(this));

        swEnabled.setChecked(PrefsStore.isEnabled(this));
        swScreenOn.setChecked(PrefsStore.isAllowScreenOn(this));
        swPowerSave.setChecked(PrefsStore.isAllowPowerSave(this));
        swLowBattery.setChecked(PrefsStore.isAllowLowBattery(this));
        etWaitPrivate.setText(String.valueOf(PrefsStore.getWaitPrivateSec(this)));
        etWaitGroup.setText(String.valueOf(PrefsStore.getWaitGroupSec(this)));
        etMinWords.setText(String.valueOf(PrefsStore.getMinWords(this)));

        refreshStatusAndLogs();
    }

    private void saveAndSync(boolean showToast) {
        int idx = Math.max(0, Math.min(spProvider.getSelectedItemPosition(), PRESETS.length - 1));
        Preset p = PRESETS[idx];

        PrefsStore.prefs(this).edit()
                .putString(PrefsStore.KEY_APP_LANG, isEn ? PrefsStore.LANG_EN : PrefsStore.LANG_TR)
                .putString(PrefsStore.KEY_PROVIDER_TYPE, p.type)
                .putString(PrefsStore.KEY_API_DIALECT, p.dialect)
                .putString(PrefsStore.KEY_API_KEY, etApiKey.getText().toString().trim())
                .putString(PrefsStore.KEY_API_MODEL, etModel.getText().toString().trim())
                .putString(PrefsStore.KEY_API_URL, etUrl.getText().toString().trim())
                .putString(PrefsStore.KEY_SYSTEM_PROMPT, etPrompt.getText().toString().trim())
                .putBoolean(PrefsStore.KEY_ENABLED, swEnabled.isChecked())
                .putBoolean(PrefsStore.KEY_ALLOW_SCREEN_ON, swScreenOn.isChecked())
                .putBoolean(PrefsStore.KEY_ALLOW_POWER_SAVE, swPowerSave.isChecked())
                .putBoolean(PrefsStore.KEY_ALLOW_LOW_BATTERY, swLowBattery.isChecked())
                .putInt(PrefsStore.KEY_WAIT_PRIVATE_SEC, parsePosInt(etWaitPrivate, PrefsStore.DEFAULT_WAIT_PRIVATE_SEC))
                .putInt(PrefsStore.KEY_WAIT_GROUP_SEC, parsePosInt(etWaitGroup, PrefsStore.DEFAULT_WAIT_GROUP_SEC))
                .putInt(PrefsStore.KEY_MIN_WORDS, parsePosInt(etMinWords, PrefsStore.DEFAULT_MIN_WORDS))
                .apply();

        sendBroadcast(new Intent(HookEntry.ACTION_SYNC_CONFIG));
        refreshStatusAndLogs();
        if (showToast) {
            Toast.makeText(
                    this,
                    t("Ayarlar kaydedildi ve sisteme gönderildi.", "Settings saved and synced to system."),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void runApiSelfTest() {
        tvApiResult.setText(t("API test ediliyor...", "Testing API..."));
        final String sampleJson = isEn
                ? ("{\"type\":\"group\",\"conversation\":"
                        + "\"Alice: Let's meet at Kadikoy pier tonight at 19:30, should I bring snacks?\\n"
                        + "Emily: Bring cold drinks please, I'll bring chips and nuts.\\n"
                        + "Mark: I might be slightly late and join you around 20:00.\\n"
                        + "Alice: Sounds great, share your location and we'll wait by the pier gate.\"}")
                : ("{\"type\":\"group\",\"conversation\":"
                        + "\"Ahmet: Akşam saat 19:30'da Kadıköy sahilde buluşuyoruz, gelirken atıştırmalık alayım mı?\\n"
                        + "Elif: Bence soğuk içecek al, cips ve kuruyemişi ben getireceğim.\\n"
                        + "Murat: Ben biraz gecikebilirim, saat 20:00 gibi yanınızda olurum.\\n"
                        + "Ahmet: Tamam süper, konum atınca sahil girişinde bekleriz.\"}");

        bgExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final LlmClient.Result r = LlmClient.summarize(MainActivity.this, sampleJson, "self_test");
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (r.ok) {
                            tvApiResult.setText(t("Başarılı (", "Success (") + r.latencyMs + " ms):\n" + r.summary);
                        } else {
                            tvApiResult.setText(t("Hata (", "Error (") + r.latencyMs + " ms):\n" + r.error);
                        }
                        refreshStatusAndLogs();
                    }
                });
            }
        });
    }

    private void sendSummaryTestNotification(boolean autoTrigger) {
        if (!ensureNotificationPermission()) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        ensureChannel(nm, CH_SUMMARY, t("Türkçe Özetleme Testi", "Summary Test Notification"));

        long now = System.currentTimeMillis();
        Person me = new Person.Builder().setName(t("Ben", "Me")).build();
        Person p1 = new Person.Builder().setName(t("Ahmet Yılmaz", "Alice Stone")).build();
        Person p2 = new Person.Builder().setName(t("Elif Kaya", "Emily Clark")).build();

        Notification.MessagingStyle style = new Notification.MessagingStyle(me)
                .setGroupConversation(autoTrigger)
                .addMessage(
                        t(
                                "Arkadaşlar bugün akşam saat 19:30 gibi Kadıköy iskele önünde buluşalım diyorum, hava çok güzel olacak.",
                                "Hey everyone, let's meet at Kadikoy pier around 19:30 tonight, the weather looks amazing."),
                        now - 4000,
                        p1)
                .addMessage(
                        t(
                                "Bana uyar, gelirken kahve ve biraz atıştırmalık da alırım. Murat biraz gecikecekmiş ama yetişecek.",
                                "Works for me, I will pick up coffee and some snacks on the way. Mark will be slightly late but still coming."),
                        now - 3000,
                        p2)
                .addMessage(
                        t(
                                "Harika olur, ben de evden çıkmadan önce konum paylaşırım. Gerekirse yarım saat ilerideki kafeye geçeriz.",
                                "Awesome, I will share my live location before leaving home. If it gets crowded we can move to the cafe nearby."),
                        now - 2000,
                        p1)
                .addMessage(
                        t(
                                "Tamam görüşürüz, ben metroya binince tekrar yazarım.",
                                "Sounds good, see you soon. I will text again once I get on the subway."),
                        now - 1000,
                        p2);
        if (autoTrigger) {
            style.setConversationTitle(t("Akşam Buluşması", "Evening Meetup"));
        }

        Bundle extras = new Bundle();
        extras.putBoolean(HookEntry.EXTRA_TEST_MARKER, true);

        Notification noti = new Notification.Builder(this, CH_SUMMARY)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setStyle(style)
                .addExtras(extras)
                .setAutoCancel(true)
                .build();

        nm.notify(9001, noti);
        PrefsStore.appendLog(
                this,
                t(
                        autoTrigger
                                ? "Test özetleme bildirimi gönderildi (tetikleniyor...)"
                                : "Otomatik alarm testi için özetleme bildirimi gönderildi",
                        autoTrigger
                                ? "Summary test notification sent (triggering...)"
                                : "Summary test notification sent for auto-alarm test"));
        refreshStatusAndLogs();

        if (autoTrigger) {
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sendBroadcast(new Intent(HookEntry.ACTION_TRIGGER_TEST_SUMMARY));
                }
            }, 1200L);
        }
        Toast.makeText(
                this,
                t("Özetleme test bildirimi gönderildi.", "Summary test notification sent."),
                Toast.LENGTH_SHORT).show();
    }

    private void sendPriorityTestNotification() {
        if (!ensureNotificationPermission()) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        ensureChannel(nm, CH_PRIORITY, t("Türkçe Öncelikli Bildirim Testi", "Priority Test Notification"));

        Notification noti = new Notification.Builder(this, CH_PRIORITY)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(t("Kargo Takip", "Package Tracking"))
                .setContentText(t(
                        "Siparişiniz bugün 14:30 itibarıyla dağıtıma çıktı, teslimat bekleniyor.",
                        "Your package is out for delivery today at 14:30 and will arrive soon."))
                .setAutoCancel(true)
                .build();

        nm.notify(9002, noti);
        PrefsStore.appendLog(
                this,
                t(
                        "Öncelikli bildirim testi gönderildi (DELIVERY)",
                        "Priority test notification sent (DELIVERY)"));
        refreshStatusAndLogs();
        Toast.makeText(
                this,
                t(
                        "Öncelikli (Kargo) test bildirimi gönderildi.",
                        "Priority (Delivery) test notification sent."),
                Toast.LENGTH_SHORT).show();
    }

    private void openSamsungNotificationHighlightsSettings() {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.android.settings", "com.android.settings.SubSettings");
            intent.putExtra(":settings:show_fragment",
                    "com.samsung.android.settings.notification.SecNotificationAssistFragment");
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent("android.settings.NOTIFICATION_SETTINGS"));
            } catch (Exception ex) {
                Toast.makeText(
                        this,
                        t("Ayarlar açılamadı: ", "Could not open settings: ") + ex.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void handleAutoTestIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.getBooleanExtra("auto_test_priority", false)) {
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sendPriorityTestNotification();
                }
            }, 300L);
        }
        if (intent.getBooleanExtra("auto_test_summary", false)) {
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sendSummaryTestNotification(true);
                }
            }, 600L);
        }
        if (intent.getBooleanExtra("auto_test_natural_summary", false)) {
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sendSummaryTestNotification(false);
                }
            }, 600L);
        }
    }

    private boolean ensureNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTI);
        Toast.makeText(
                this,
                t(
                        "Lütfen bildirim iznini onaylayın ve tekrar deneyin.",
                        "Please grant notification permission and try again."),
                Toast.LENGTH_SHORT).show();
        return false;
    }

    private static void ensureChannel(NotificationManager nm, String id, String name) {
        if (nm.getNotificationChannel(id) == null) {
            NotificationChannel ch = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
    }

    private void refreshStatusAndLogs() {
        boolean hasKey = !PrefsStore.getApiKey(this).isEmpty();
        String activeText = PrefsStore.isEnabled(this) ? t("Aktif", "Active") : t("Pasif", "Inactive");
        String keyText = hasKey ? t("API Anahtarı Kayıtlı", "API Key Saved") : t("API Anahtarı Eksik", "API Key Missing");
        tvStatusBadge.setText(t("Durum: ", "Status: ") + activeText + "  |  Model: " + PrefsStore.getApiModel(this) + "  |  " + keyText);
        tvStatusBadge.setTextColor(hasKey ? 0xFF3FB950 : 0xFFF85149);
        tvLogs.setText(PrefsStore.getRecentLogs(this));
    }

    private static int parsePosInt(EditText et, int fallback) {
        try {
            int val = Integer.parseInt(et.getText().toString().trim());
            return Math.max(1, val);
        } catch (Exception e) {
            return fallback;
        }
    }

    private LinearLayout makeCard(int bgColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0xFF30363D);
        card.setBackground(bg);
        int p = dp(16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView makeText(String txt, float sp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    private TextView makeSectionTitle(String txt) {
        TextView tv = makeText(txt, 16, true, 0xFFF0F6FC);
        tv.setPadding(0, 0, 0, dp(8));
        return tv;
    }

    private TextView makeLabel(String txt) {
        TextView tv = makeText(txt, 12, true, 0xFF8B949E);
        tv.setPadding(0, dp(8), 0, dp(4));
        return tv;
    }

    private EditText makeInput(String hint, boolean password) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(0xFF484F58);
        et.setTextColor(0xFFF0F6FC);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setSingleLine(true);
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0D1117);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0xFF30363D);
        et.setBackground(bg);
        int p = dp(10);
        et.setPadding(p, p, p, p);
        return et;
    }

    private Switch makeSwitch(String label) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(0xFFC9D1D9);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sw.setPadding(0, dp(6), 0, dp(6));
        return sw;
    }

    private EditText makeNumericColumn(LinearLayout parent, String label) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = dp(6);
        col.setLayoutParams(colLp);

        col.addView(makeLabel(label));
        EditText et = makeInput("", false);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        col.addView(et);
        parent.addView(col);
        return et;
    }

    private Button makeLangButton(String label, boolean active, int leftMargin, int rightMargin) {
        Button btn = makeStyledButton(
                label,
                active ? 0xFF0381FE : 0xFF0D1117,
                active ? 0 : 0xFF30363D,
                active ? 0xFFFFFFFF : 0xFF8B949E,
                active);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = leftMargin;
        lp.rightMargin = rightMargin;
        btn.setLayoutParams(lp);
        return btn;
    }

    private Button makeButton(String label, int color) {
        Button btn = makeStyledButton(label, color, 0, 0xFFFFFFFF, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        btn.setLayoutParams(lp);
        return btn;
    }

    private Button makeStyledButton(String label, int bgColor, int strokeColor, int textColor, boolean bold) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(12));
        if (strokeColor != 0) {
            bg.setStroke(dp(1), strokeColor);
        }
        btn.setBackground(bg);
        return btn;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
