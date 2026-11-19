# KAI (Android)

Voice-first Android assistant — Kotlin · Jetpack Compose.

This repo is the Android client. Cloud AI, auth, and the multi-provider gateway live in the sibling backend (`kai-backend`). Provider API keys stay server-side only.

## Requirements

- Android Studio Ladybug (2024.2+) or newer
- JDK 17+
- Android SDK 35; emulator or device with **API 26+**
- Google / device speech recognition + TTS voices installed for push-to-talk

## Open the project

1. Clone this repository.
2. Open the folder in Android Studio (**File → Open**).
3. Let Gradle sync (Android Studio downloads the wrapper distribution if needed).
4. Copy `local.properties.example` → `local.properties` if Studio did not already create `local.properties`.

```bash
cp local.properties.example local.properties
```

Android Studio normally writes `sdk.dir` for you. Keep that line.

## Set the backend URL

Add or edit in `local.properties` (gitignored):

```properties
KAI_API_BASE_URL=http://10.0.2.2:8000
```

| Target | Typical URL |
|--------|-------------|
| Emulator → host machine | `http://10.0.2.2:8000` |
| Physical device on LAN | `http://192.168.x.x:8000` |
| Deployed backend | `https://your-api.example.com` |

The value is injected into `BuildConfig.KAI_API_BASE_URL` and used by `KaiApiClient`. Default if unset: `http://10.0.2.2:8000`.

You do **not** need the backend for local phone commands (flashlight, battery, volume, settings intents, etc.). Cloud chat replies need `kai-backend` running with at least one provider key.

## Permissions

| Permission | When used |
|------------|-----------|
| `RECORD_AUDIO` | Only while you **hold** the push-to-talk button (runtime prompt on first use) |
| `INTERNET` | Backend chat + on-device STT that uses network recognizers |
| `ACCESS_NETWORK_STATE` | “Am I online?” local tool |
| `FLASHLIGHT` | Torch via `CameraManager` (hardware permitting) |
| `READ_CONTACTS` | Match “call <name>”, WhatsApp messages, and WhatsApp calls on this device. Numbers are not uploaded. |
| `CALL_PHONE` | Place a phone call with `ACTION_CALL`, and start a WhatsApp voice or video call, after you allow it. The number is not uploaded. |
| `POST_NOTIFICATIONS` | Tell you when a timer or reminder is due. The text stays on this phone. |
| Screen capture | “Take a screenshot” asks once per session, then saves the picture on the phone. |

Mic status is always visible in the UI. There is **no** background / always-on recording and no wake word in this milestone.

Sensitive local actions (launch app, alarm/timer, maps navigation, camera) ask for confirmation in a dialog before running. A call to one matching contact starts immediately with `ACTION_CALL` after you allow phone calls — there is no confirm dialog and no extra tap in the dialer. If the contact is missing or several people match, KAI says so on the phone and does not send the utterance to the backend. “Send hi to Mohammed Kaif on WhatsApp” stays on the phone, opens that chat with the text filled in, and a KAI accessibility service taps WhatsApp’s Send button once. Turn that service on the first time it asks. If it is off, KAI says so and still opens the chat. The service does not run for calls. “Call Mohammed Kaif on WhatsApp” and “video call Mohammed Kaif on WhatsApp” start that contact’s WhatsApp voice or video call. A missing WhatsApp install is reported on the device.

## Run on an emulator

1. **Tools → Device Manager** → create/start a virtual device (API 26+). Prefer an image **with Google Play** so SpeechRecognizer / TTS work.
2. Grant microphone access when prompted (emulator mic may need host mic enabled).
3. Select the app run configuration **app** → **Run** (▶).

Or from a machine with the Android SDK:

```bash
./gradlew :app:installDebug
adb shell am start -n com.kai.assistant/.MainActivity
```

### Emulator voice tips

- Enable the host microphone for the AVD (**… → Microphone** / extended controls).
- Install a voice language pack if STT returns network errors (`Settings → System → Languages` on the emulator).
- Flashlight often does nothing on emulators without a camera flash — use a physical device to verify torch.
- For backend chat from the emulator, run `kai-backend` on the host at port `8000` and keep `KAI_API_BASE_URL=http://10.0.2.2:8000`.

### Sample local phrases

- “flashlight on” / “flashlight off”
- “battery” / “what time is it” / “am I online”
- “open wifi settings” / “open bluetooth settings”
- “volume up” / “set volume to 40”
- “set a timer for 60 seconds” (confirms first)
- “open Chrome” / “navigate to nearest coffee” (confirms first)
- “call Kaif” / “phone Mom” (places the call immediately — not sent to the server)
- “send hi to Mohammed Kaif on WhatsApp” / “WhatsApp Mohammed Kaif hi” (opens the chat and taps Send — not sent to the server)
- “call Mohammed Kaif on WhatsApp” / “video call Mohammed Kaif on WhatsApp” (WhatsApp call — not sent to the server)
- “take a screenshot” (saves a picture on the phone; Android asks for screen capture the first time in a session)
- “set a timer for 5 minutes” (starts a countdown and notifies when it ends — not sent to the server)
- “set an alarm for 6:30 a.m.” (system clock alarm — not sent to the server)
- “remind me to call the supplier at 7” (notification stored on this phone; KAI speaks it when it fires)
- “what’s on my screen” (reads visible text and controls on the phone; says so if accessibility is off)

Anything that does not match a local command is sent to `POST /v1/chat` with the in-memory session transcript. Offline / HTTP 503 (no provider keys) returns a friendly message; local tools still work.

## What’s in this slice

- Push-to-talk STT (`SpeechRecognizer`) + TTS with interrupt, speech rate, male/female voice preference
- Visible mic states: off / listening / processing / speaking
- Session context shared between voice and text chat
- Local-first intent router for phone actions via official APIs/intents
- `KaiApiClient` aligned with backend `{ messages, session_id }` → `{ content, provider, … }`

## Out of scope (later)

Wake word, Hindi/Kannada quality polish, multi-step task planner, memory CRUD UI beyond the existing toggle.

## License

Private / TBD by owner.
