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

**Preferred (runtime):** open the app → **Settings → Backend URL**, enter e.g. `http://192.168.1.10:8000`, tap **Save**, then **Test**.

| Target | Typical URL |
|--------|-------------|
| Emulator → host machine | `http://10.0.2.2:8000` (automatic default on emulator) |
| Physical device on LAN | `http://192.168.x.x:8000` (set in Settings — **never** `10.0.2.2`) |
| Deployed backend | `https://your-api.example.com` |

Optional build-time override in `local.properties` (gitignored):

```properties
KAI_API_BASE_URL=http://10.0.2.2:8000
```

Runtime resolution: Settings override → else BuildConfig if set → else emulator default `http://10.0.2.2:8000` **only on emulator**. On a physical phone with no Settings value, the URL stays empty and cloud chat tells you to configure Settings.

Bind `kai-backend` to `0.0.0.0:8000` (not only `127.0.0.1`) so the phone can reach it on the LAN.

You do **not** need the backend for local phone commands (flashlight, battery, volume, settings intents, etc.). Cloud chat replies need `kai-backend` running with at least one provider key.

## Permissions

| Permission | When used |
|------------|-----------|
| `RECORD_AUDIO` | Only while you **hold** the push-to-talk button (runtime prompt on first use) |
| `INTERNET` | Backend chat + on-device STT that uses network recognizers |
| `ACCESS_NETWORK_STATE` | “Am I online?” local tool |
| `FLASHLIGHT` | Torch via `CameraManager` (hardware permitting) |

Mic status is always visible in the UI. There is **no** background / always-on recording and no wake word in this milestone.

Sensitive local actions (launch app, alarm/timer, maps navigation, camera) ask for confirmation in a dialog before running.

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
- “open YouTube and play believer” (opens YouTube, then play/search)

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
