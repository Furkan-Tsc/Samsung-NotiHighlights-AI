# NotiHighlights AI
An LSPosed / Xposed module for **Samsung One UI 8.5 (Android 16)** devices that unlocks **Notification Highlights** (Notification Summaries & Priority Notifications) and powers chat summaries with your own LLM provider in **any language** — with full built-in **Turkish** priority & UI support.
> 🇹🇷 [Türkçe açıklama için aşağı kaydırın](#türkçe)
---
## Features
- **Unlocks Samsung Notification Highlights on One UI 8.5** without modifying `/system` partitions (runtime `NmRune` & `SemFloatingFeature` hooks).
- **Multilingual AI Summaries (Any Language):** Bypasses Samsung's offline language restrictions and bridges notification summaries to your preferred LLM:
  - Google Gemini
  - Groq (Free tier supported)
  - OpenAI
  - OpenRouter
  - Custom / Local OpenAI-compatible servers (**Ollama**, **LM Studio**, etc.)
  - Write the **System Prompt** in any language to receive summaries in that language.
- **Priority Notifications Support:** Program automatically deploys and maintains the Turkish keyword policy (`/data/system/notification_ai_scpm_policies.json`) with Unicode case-insensitive matching and Turkish date/time extraction. If you want to Priority Notification support on your language edit `notification_ai_scpm_policies.json` file and rebuild app.
- **Unclamped Collapsed Summary Cards (~180+ chars):** Fixes the 2-line (`72dp`) collapsed height cap in One UI 8.5 (`NotificationMaxHeightFrameLayout`, `ExpandableNotificationRow`, `SingleLineViewBinder`) so full summaries are readable without tapping to expand.
- **Customizable Triggers & Built-in Test Tools:**
  - Built-in **Turkish / English** UI switcher
  - Configurable private/group wait timers & minimum word threshold
  - Optional summarization while screen is on, in Power Saving mode, or on low battery
  - One-tap API self-test, summary test notification, priority test notification, and activity logs
## Requirements
- Samsung Galaxy device running **One UI 8.5 (Android 16)** *(tested on Galaxy S23 Ultra `SM-S918B`)*
- **Root / Soft Root** (KernelSU / Magisk) + **LSPosed / Vector**
- An API key from Gemini / Groq / OpenAI / OpenRouter, or a local Ollama/LM Studio server
## Installation
1. Download **`NotiHighlightsAI.apk`** from [Releases](../../releases) and install it.
2. Open **LSPosed / Vector Manager**, enable **NotiHighlights AI**, and make sure these scopes are checked:
   - `System Framework (android)`
   - `System UI (com.android.systemui)`
   - `Settings (com.android.settings)`
3. **Reboot** your device.
4. Open **NotiHighlights AI**, choose your LLM provider, paste your API key and model name, then tap **Save & Sync to System**.
5. Open **Samsung Notification Highlights Settings** (button inside the app) and enable **Summarize notifications** / **Priority notifications**.
## Privacy & Security
- Your API key is stored locally in the app's private `SharedPreferences`.
- The internal Binder bridge (`NotiBridgeProvider`) only accepts calls from `system_server` (UID 1000) and the app itself.
- **Note:** Summarized messaging notification text is sent to the LLM endpoint you configure. If you prefer 100% offline processing, point the endpoint to a local Ollama / LM Studio instance.
## Building from Source (Windows PowerShell)
Requires **JDK 17** and **Android SDK Platform 36 + Build-Tools 36.0.0** (no Gradle / Android Studio needed):
```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```
Output APK: `build\NotiHighlightsAI.apk`
---
## Türkçe
**NotiHighlights AI**, **Samsung One UI 8.5 (Android 16)** çalıştıran rootlu Galaxy cihazlarda **Bildirim Öne Çıkanları** (*Bildirimleri Özetle* ve *Öncelikli Bildirimler*) özelliğini açan ve bildirim özetlerini seçtiğiniz LLM sağlayıcısı üzerinden **istediğiniz dilde** (varsayılan olarak Türkçe ve İngilizce) çalıştıran bir LSPosed modülüdür.
### Özellikler
- `/system` bölümüne dokunmadan One UI 8.5 Bildirim Öne Çıkanlarını etkinleştirir.
- **Çok Dilli AI Özetleme:** Samsung'un çevrimdışı dil kısıtlamasını atlayarak Google Gemini, Groq, OpenAI, OpenRouter veya yerel Ollama / LM Studio üzerinden özet üretir. **System Prompt** kısmına istediğiniz dili yazarak o dilde özet alabilirsiniz.
- **Tam Türkçe Öncelikli Bildirim Desteği:** Türkçe kargo, fatura, randevu, seyahat, güvenlik vb. anahtar kelime politikasını (`/data/system/notification_ai_scpm_policies.json`) ilk açılışta otomatik olarak kurar ve korur.
- **Genişletilmiş Daraltılmış Özet Kartı:** Daraltılmış bildirim kartındaki 2 satırlık (`72dp`) kesilmeyi kaldırır; 180 karaktere kadar özetler kartı açmaya gerek kalmadan okunabilir.
- **Esnek Ayarlar ve Test Araçları:** Türkçe/İngilizce arayüz, ekran açıkken / güç tasarrufu modunda özetleme, özel/grup bekleme süreleri, minimum kelime eşiği ve yerleşik API / bildirim test butonları.
### Kurulum
1. [Releases](../../releases) bölümünden **`NotiHighlightsAI.apk`** dosyasını indirip kurun.
2. **LSPosed / Vector** içinden modülü aktif edin ve kapsamların işaretli olduğunu doğrulayın:
   - `Sistem Çerçevesi (android)`
   - `Sistem Arayüzü (com.android.systemui)`
   - `Ayarlar (com.android.settings)`
3. Telefonu **yeniden başlatın**.
4. **NotiHighlights AI** uygulamasını açıp sağlayıcınızı, API anahtarınızı ve model adınızı girin; **Kaydet ve Sisteme Uygula** butonuna basın.
5. Uygulama içinden **Samsung Bildirim Öne Çıkanları Ayarlarını Aç** diyerek özetleme ve öncelikli bildirimleri aktif edin.
### Lisans & Sorumluluk Reddi
Bu proje eğitim ve kişisel kullanım amaçlıdır; Samsung Electronics ile herhangi bir resmi bağı yoktur.
