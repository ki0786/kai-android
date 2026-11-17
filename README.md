# KAI (Android)

Voice-first Android assistant — Kotlin · Jetpack Compose MVP shell.

This repo is the Android client. Cloud AI, auth, and the multi-provider gateway live in the sibling backend (`kai-backend`). Provider API keys stay server-side only.

## Requirements

- Android Studio Ladybug (2024.2+) or newer
- JDK 17+
- Android SDK 35; emulator or device with **API 26+**

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

You do **not** need the backend running to build or explore the UI shell.

## Run on an emulator

1. **Tools → Device Manager** → create/start a virtual device (API 26+).
2. Select the app run configuration **app**.
3. Click **Run** (▶).

Or from a machine with the Android SDK:

```bash
./gradlew :app:installDebug
adb shell am start -n com.kai.assistant/.MainActivity
```

## What’s in this scaffold

- Home with animated mic/orb + CTAs for voice and text chat
- Voice session: push-to-talk, activity indicator, interrupt stub, visible mic status
- Text chat with in-memory session messages + API stub calls
- Settings: language, voice gender/rate placeholders, memory toggle, light/dark, permissions entry
- Local tool stubs (`com.kai.assistant.tools`) — flashlight, battery, launch app, alarms/timers, settings intents; volume/brightness/etc. marked TODO
- Privacy: mic chip always visible; no background recording

## Out of scope (next milestones)

Full STT/TTS, wake word, real cloud auth, and the task planner.

## License

Private / TBD by owner.
