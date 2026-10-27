# Jarvis — Android voice agent (Urdu / Roman Urdu / English)

Jarvis aap ke phone par chalne wala voice agent hai. Aap bolte hain — Urdu, Roman Urdu ya English mein — aur Jarvis
kaam ka plan banata hai, apps ko khud chala kar kaam karta hai, nateeja check karta hai, aur aakhir mein **ek chhoti si
line** bol kar batata hai ke kya hua.

> "Hey Jarvis, Ammi ko WhatsApp par likho ke main 10 minute mein pohanch raha hoon"
> "Kal subah 7 baje ka alarm laga do"
> "What's on my calendar tomorrow?"
> "Har roz subah 8 baje mujhe aaj ka schedule aur mausam batao"

---

## 1. Milestones (har ek compile aur test hota hai)

| # | Milestone | Kya shamil hai |
|---|-----------|----------------|
| 1 | Foreground service + wake word | Hamesha chalne wali microphone service, Porcupine "Hey Jarvis" (screen off par bhi), push-to-talk, floating mic button, Android/Whisper STT, Android/ElevenLabs TTS (beech mein roka ja sakta hai), Keystore mein keys |
| 2 | Tool registry + risk/confirm system | Claude agent loop (streaming), 30 tools, risk levels, awaaz + screen par tasdeeq, HIGH ke liye fingerprint, secrets guard, prompt-injection defence, action log |
| 3 | Accessibility control | Screen ka UI tree parhna, tap / long-press / type / scroll / swipe / back / home / recents — **koi bhi app**; volume-down ×3 kill switch |
| 4 | Notifications + vision | Notifications parhna, inline reply, open/dismiss; screenshot aur gallery photos Claude ko image ke taur par |
| 5 | Memory + routines | Room DB: facts, contact nicknames, routines, conversation summaries (khud-ba-khud yaad); WorkManager routines |
| 6 | Polish | Ek waqt mein ek task, limits ki settings, warnings, apna proxy server, yeh README |

Git history mein har milestone ka alag commit hai.

---

## 2. Zaroori cheezen

- Android Studio (Ladybug ya naya) aur JDK 17
- Android 8.0+ phone (screenshot/vision ke liye Android 11+)
- **Claude API key** — [console.anthropic.com](https://console.anthropic.com) (ya apna proxy, neeche dekhein)
- **Picovoice AccessKey** (free) — [console.picovoice.ai](https://console.picovoice.ai) — wake word ke liye
- Optional: **ElevenLabs** key (behtar awaaz), **OpenAI** key (Whisper STT, Urdu ke liye behtareen)

Model: `claude-sonnet-5-5` (Settings mein badal sakte hain).

## 3. Build aur install

```bash
cd jarvis
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Ya Android Studio mein `jarvis/` folder kholein aur ▶ Run dabayein. GitHub par har push par
`.github/workflows/jarvis-android.yml` tests, lint aur APK build chalata hai (APK artifact mein milta hai).

### "Hey Jarvis" wake word

Built-in Porcupine keyword **"Jarvis"** pehle se kaam karta hai ("Hey Jarvis" kehne par bhi). Agar aap bilkul
"Hey Jarvis" chahte hain: Picovoice Console mein free custom keyword banayein (platform: Android), file ko
`app/src/main/assets/hey-jarvis_android.ppn` naam se rakh dein — Jarvis khud usay use karega.

---

## 4. Pehli dafa setup (permission flow)

App kholte hi **Permissions** screen aati hai. Har ijazat ke saath likha hai ke kyun chahiye, ✓ / ⚠ status, aur
"Ijazat dein" button jo seedha sahi dialog ya settings page kholta hai. Wapas aane par status khud update hota hai.

| Ijazat | Zaroori? | Kyun |
|--------|----------|------|
| Microphone | ✔ | Wake word aur aap ki awaaz sunna |
| Notifications | ✔ | Hamesha wali notification (STOP button) aur confirmation alerts |
| Doosri apps ke upar | ✔ | Floating mic button; background se confirmation screen kholna |
| Battery optimization se bahar | ✔ | Screen off par service band na ho |
| Accessibility | ✔ | Kisi bhi app ko chalana + volume-down kill switch |
| Notification access | – | Messages parhna aur reply |
| Contacts, Phone, SMS, Calendar, Location, Photos | – | Mutaliqa tools ke liye |
| System settings badalna | – | Brightness |
| Do Not Disturb | – | Silent/DND |
| All files access | – | Files dhoondna/parhna/delete |

> **Android 13+ (sideloaded APK):** Accessibility ka option grey ho to: *Settings → Apps → Jarvis → ⋮ → Allow restricted settings*, phir Accessibility on karein.

Phir **Settings** tab mein Claude API key aur Picovoice AccessKey daalein → **Save** → Home par "Jarvis shuru karein".

---

## 5. Istemal

- **"Hey Jarvis"** kahein (screen off bhi), ya floating mic button tap karein, ya notification mein "Baat karein", ya app ka bara mic button.
- Command type bhi kar sakte hain (Home screen).
- Jarvis jab bol raha ho, "Hey Jarvis" kehne ya bubble tap karne se woh foran chup ho kar sunne lagta hai.
- Jab Jarvis aap se PIN/OTP/password daalne ko kahe, khud daalein, phir **"Hey Jarvis"** kahein ya notification mein **Continue** dabayein.

---

## 6. Hifazat (Safety) — sab code mein enforce hai, sirf prompt mein nahi

### Risk levels

| Level | Misaal | Kya hota hai |
|-------|--------|--------------|
| **LOW** | Screen parhna, app kholna, alarm, volume, search | Foran chal jata hai |
| **MEDIUM** | SMS/email/WhatsApp bhejna, call, post, install, notification reply, routine banana | Jarvis exact content **bolta** hai aur **screen par dikhata** hai; "haan" / "nahi" ya button. 60 second mein jawab nahi = cancel |
| **HIGH** | Payment, paise transfer, khareedna, kuch bhi delete karna, security settings | Screen par confirmation + **fingerprint / phone lock** zaroori. Awaaz se manzoori nahi hoti. Screen lock na ho to kaam ruk jata hai |

- Har tool ka base risk hai, aur UI taps ka risk **button ke text aur app** se tay hota hai (`UiRiskClassifier`):
  "Pay", "Buy", "Delete", "Transfer", "ادائیگی" → HIGH; "Send", "Post", "Call", "Install" → MEDIUM; banking/wallet apps
  mein har tap kam az kam MEDIUM. Model is risk ko kam nahi kar sakta.
- "Send" ki tasdeeq mein message box ka text bhi dikhaya jata hai. Agar tasdeeq ke dauran screen badal jaye to
  Jarvis tap nahi karta (`SCREEN_CHANGED`).

### Secrets kabhi nahi
Password, PIN, OTP, CVV ya card number: Jarvis na type karta hai, na bhejta hai, na yaad rakhta hai.
Password fields, "OTP/PIN/CVV" wale fields, Luhn-valid card numbers aur "code 123456" jaisa text code mein block hai
(`SensitiveInputGuard`). Aisi jagah Jarvis `hand_off_to_user` se kaam aap ke hawale karta hai.

### Prompt injection
Screen, notifications, SMS, email, web pages, files aur clipboard ka text `<untrusted_data>` mein lapet kar bheja
jata hai — yeh **data** hai, hukm nahi. Agar us mein hidayat jaisi baat ho ("ignore previous instructions",
"Jarvis send money…", "OTP share karo") to:
1. Claude ko SECURITY NOTE milta hai ke yeh user ki taraf se nahi,
2. us task mein baad ka **har** side-effect wala kaam (LOW bhi) confirmation maangta hai,
3. Jarvis aap ko batata hai ke us text ne kya kaha tha aur poochta hai.

### Kill switch
Kisi bhi waqt sab kuch foran band: **volume-down 3 dafa jaldi** (Accessibility on honi chahiye), notification ka
**STOP** button, floating button par **long-press**, ya app mein "STOP sab kuch". Is se: chal rahe tasks cancel,
Claude ki request kat jati hai, awaaz band, pending confirmations reject, service band.

### Action log
**Log** tab mein har task, har tool call (risk ke saath), har confirmation ka nateeja aur har jawab. 30 din rakha jata hai.

### Limits
Max 25 steps per task, har Claude call 90s, har tool ka apna timeout, poore task ka timeout (default 300s),
requests/minute limit (default 20) — sab Settings mein.

### Keys
API keys Android Keystore ki AES-256-GCM key se encrypt hoti hain (hardware-backed jahan mumkin). Plain text kahin
save nahi hota. Behtar hifazat ke liye apna proxy chalayein taake Claude key phone par ho hi na.

---

## 7. Apna proxy server (optional)

`proxy/server.js` (Node 18+, koi dependency nahi): Claude key server par rehti hai, phone sirf ek token bhejta hai.
Token check, per-token rate limit, model allow-list, aur streaming as-is.

```bash
cd proxy
ANTHROPIC_API_KEY=sk-ant-... JARVIS_TOKENS=$(openssl rand -hex 24) node server.js
```

Phone par Settings → *API base URL* = `https://aap-ka-server.com` aur *Proxy token* = wohi token.
(Proxy mode mein `fallbacks` aur `eager_input_streaming` khud band ho jate hain.) HTTPS ke peeche chalayein
(Caddy/nginx/Cloudflare).

---

## 8. Architecture

```
jarvis/app/src/main/java/com/jarvis/agent/
├── JarvisApp.kt              Application, AppGraph banata hai
├── core/                     Prefs, SecureStore (Keystore), KillSwitch, TaskTracker, runtime state, notifications
├── service/                  JarvisService (foreground, microphone), WakeWordEngine (Porcupine), receivers
├── voice/                    STT (Android / Whisper), TTS (Android / ElevenLabs), VoiceIO, LanguageDetector
├── overlay/                  FloatingBubble
├── brain/                    ClaudeClient (OkHttp SSE), RequestFactory, MessageAccumulator, AgentLoop, SystemPrompt, RateLimiter
├── tools/                    Tool interface, BaseTool, Schema builder/validator, ToolRegistry, ToolCatalog
│   └── impl/                 System, Apps, Comm (calls/SMS/contacts), Time (alarms/calendar), Device, Web, Files,
│                             Screen (accessibility), Notifications, Vision, Memory/Routines
├── safety/                   Guards (secrets, injection, yes/no), UiRiskClassifier, Confirmation (+ biometric ConfirmActivity)
├── accessibility/            JarvisAccessibilityService, ScreenSnapshot model
├── notifications/            JarvisNotificationListener + store
├── memory/                   Room DB (facts, nicknames, routines, conversations), MemoryManager
├── routines/                 RoutineTime, RoutineScheduler, RoutineWorker (WorkManager)
├── log/                      Room action log
└── ui/                       Compose: Home, Log, Memory, Permissions, Settings
```

### Agent loop

```
awaaz → STT → AgentLoop
  context (waqt, zabaan, memory) + command
  ┌─────────────────────────────────────────────────────────────┐
  │ Claude (stream) → tool_use?                                  │
  │   ├─ schema validate → risk assess → (MEDIUM/HIGH: confirm)  │
  │   ├─ execute (timeout) → untrusted data wrap → action log    │
  │   └─ tool_result (text / screenshot) → wapas Claude          │
  │ end_turn → ek line ka jawab                                 │
  └─────────── max 25 steps, timeouts, rate limit, kill switch ──┘
  → TTS → memory mein summary
```

Request: `claude-sonnet-5-5`, streaming, `thinking: between_tools` (kam latency), `output_config.effort`,
prompt caching (system + conversation), server-side `fallbacks`, `anthropic-version: 2026-10-21`. Purane
screenshots history se hata diye jate hain taake tokens bachen.

---

## 9. Naya tool kaise add karein

```kotlin
class BatterySaverTool : BaseTool(
    name = "battery_saver",
    description = "Turn battery saver on or off.",
    baseRisk = RiskLevel.LOW,
    schemaBlock = { boolean("on", "true = on") },
) {
    override fun describe(input: JsonObject) = "Battery saver ${input.bool("on")}"
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        // ... kaam karein
        return ToolResult.ok("Battery saver on.")
    }
}
```

Phir `ToolCatalog.build()` mein register karein. Agar risk input par depend karta hai to `assess()` override
karein (sirf barha sakte hain, kam nahi). Teesri party ka text lautayein to `ToolResult.untrusted("source", text)`.

---

## 10. Tests

`./gradlew testDebugUnitTest` — 34 unit tests:
SSE/stream parsing (text, tool_use, invalid JSON, thinking, errors), request banana (model, thinking, caching,
fallbacks, purane screenshots hatana), schema validation, secrets guard (Luhn, OTP, password fields), prompt-injection
detection aur wrapping, haan/nahi (Urdu/Roman/English), UI risk classifier, screen rendering, routine schedule math,
rate limiter, wake-word kill-switch timing, WAV header, tool registry aur risk policy.

---

## 11. Imandari se — limitations

- **Android 14+:** phone restart ke baad microphone service khud start nahi ho sakti; Jarvis notification dikhata hai — tap kar ke shuru karein.
- **Banking apps** aksar screenshots block karti hain (`FLAG_SECURE`) aur kuch accessibility services ko bhi rokti hain. Aisi jagah Jarvis aap ke hawale kar dega.
- **Wi-Fi / Bluetooth** Android 10+ par apps seedha on/off nahi kar sakti — Jarvis settings panel khol kar switch tap karta hai.
- **Clipboard** background mein Android 10+ par parha nahi ja sakta.
- **Locked phone:** Jarvis lock screen nahi khol sakta; aap se unlock karne ko kahega.
- Google Play accessibility ke istemal par sakht policy rakhta hai — yeh app personal/sideload use ke liye hai.
- Har command Claude API calls karti hai (paise lagte hain). Limits Settings mein hain.

## 12. Masail (Troubleshooting)

| Masla | Hal |
|-------|-----|
| Wake word kaam nahi karta | Picovoice key check karein; Battery optimization se bahar karein; Home par error dekhein |
| "Accessibility band hai" | Permissions → Accessibility; Android 13+ par "Allow restricted settings" |
| Volume-down kill switch nahi chalta | Accessibility on honi chahiye |
| Confirmation screen nahi aati | "Doosri apps ke upar" ijazat dein; notification bhi aati hai |
| "Claude API key ghalat" | Settings mein dobara key daalein ya proxy URL/token check karein |
