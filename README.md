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

You do **not** need the backend for local phone commands (flashlight, battery, volume, settings intents, YouTube and other apps, etc.). General questions are answered on the phone when the on-device model is ready. The backend is the fallback when that model is missing, still loading, or errors — and when you turn **Answer on this phone** off. Provider API keys stay on `kai-backend` only.

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

Anything that does not match a local command tries the on-device model first. If that model cannot answer, the turn goes to `POST /v1/chat` and the reply starts with “This reply used the backend.” Phone-command turns are left out of that request. Offline / HTTP 503 (no provider keys) returns a friendly message; local tools still work.

## On-device answers

Settings → **Answer on this phone** (on by default).

| | |
|---|---|
| Engine | Google AI Edge MediaPipe LLM Inference (`tasks-genai`) |
| Model | Qwen2.5 0.5B Instruct, int8, MediaPipe `.task` |
| Download | 547 MB (`546,660,344` bytes), first use only, into app storage |
| Status | Home, chat, voice, and Settings show downloading / loading / ready / failed |

Gemma 3 1B int4 is a similar size, but the LiteRT Gemma repo on Hugging Face is gated and would need a token in the app. KAI does not embed API keys, so it downloads the public Qwen2.5 0.5B MediaPipe build instead. CPU inference, so it runs on a mid-range vivo without a desktop-class GPU.

First question while the file is still downloading uses the backend and says so. The next question after status **ready** stays on the phone.

### Try it on a vivo

1. Phone and computer on the same Wi‑Fi. In `local.properties` set `KAI_API_BASE_URL=http://<computer-lan-ip>:8000` (the backend is only the fallback).
2. Enable USB debugging on the vivo and install:

```bash
./gradlew :app:installDebug
adb shell am start -n com.kai.assistant/.MainActivity
```

3. Open **Settings**. On-device answers should already be on. Tap **Download model**, or just ask a question — that starts the download. Stay on Wi‑Fi; the file is about 547 MB. Free a bit more than that on the phone.
4. Wait until the status says **ready** (loading the model into memory can take a little while after the download).
5. In text or voice, ask something general: “What is the capital of France?” The reply should **not** say it used the backend.
6. Say “open YouTube” or “flashlight on”. Those stay on the phone (YouTube asks you to confirm) and are not sent to the backend.
7. Turn **Answer on this phone** off and ask again. That reply comes from the backend URL in `local.properties`. Turn it back on to return to the on-device model.

## What’s in this slice

- Push-to-talk STT (`SpeechRecognizer`) + TTS with interrupt, speech rate, male/female voice preference
- Visible mic states: off / listening / processing / speaking
- Session context shared between voice and text chat
- Local-first intent router for phone actions via official APIs/intents
- `KaiApiClient` aligned with backend `{ messages, session_id }` → `{ content, provider, … }`
- On-device answers (MediaPipe LLM Inference) with the backend as fallback only

## Out of scope (later)

Wake word, Hindi/Kannada quality polish, multi-step task planner, memory CRUD UI beyond the existing toggle.

## License

Private / TBD by owner.
