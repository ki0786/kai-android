package com.kai.assistant.api

import android.os.Build
import com.kai.assistant.BuildConfig

/**
 * Resolves the kai-backend base URL for the current device.
 *
 * Emulator default: [EMULATOR_LOOPBACK] (host machine via Android emulator alias).
 * Physical device: never silently uses 10.0.2.2 — requires Settings or a
 * non-emulator BuildConfig override from local.properties.
 */
object BackendUrlResolver {
    const val EMULATOR_LOOPBACK = "http://10.0.2.2:8000"
    const val PHYSICAL_PLACEHOLDER = "http://YOUR_PC_LAN_IP:8000"

    fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT
        val model = Build.MODEL
        val product = Build.PRODUCT
        val hardware = Build.HARDWARE
        val manufacturer = Build.MANUFACTURER
        return fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            model.contains("google_sdk", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            manufacturer.contains("Genymotion", ignoreCase = true) ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
            product == "google_sdk" ||
            product.contains("sdk", ignoreCase = true) ||
            product.contains("emulator", ignoreCase = true) ||
            hardware.contains("goldfish", ignoreCase = true) ||
            hardware.contains("ranchu", ignoreCase = true)
    }

    /**
     * @param savedOverride persisted Settings value (may be blank)
     */
    fun resolve(savedOverride: String?): String {
        val saved = savedOverride?.trim()?.trimEnd('/').orEmpty()
        if (saved.isNotEmpty()) return saved

        val buildUrl = BuildConfig.KAI_API_BASE_URL.trim().trimEnd('/')
        val buildIsEmulatorOnly =
            buildUrl.isEmpty() || buildUrl == EMULATOR_LOOPBACK

        return when {
            isEmulator() -> buildUrl.ifEmpty { EMULATOR_LOOPBACK }
            // Physical device: never fall back to 10.0.2.2
            !buildIsEmulatorOnly -> buildUrl
            else -> ""
        }
    }

    fun isConfigured(savedOverride: String?): Boolean =
        resolve(savedOverride).isNotEmpty()

    fun offlineHint(currentUrl: String): String {
        val usingEmulatorAlias = currentUrl.contains("10.0.2.2")
        return buildString {
            append("Couldn't reach the backend")
            if (currentUrl.isNotBlank()) append(" at $currentUrl")
            append(". ")
            if (!isEmulator()) {
                append(
                    "On a physical phone, open Settings → Backend URL and set your PC's " +
                        "LAN IP on the same Wi‑Fi (e.g. http://192.168.1.10:8000). " +
                        "Do not use 10.0.2.2 — that only works on the Android emulator.",
                )
            } else if (usingEmulatorAlias) {
                append(
                    "Start kai-backend on the host (port 8000). " +
                        "If this still fails, check Settings → Backend URL.",
                )
            } else {
                append("Check Settings → Backend URL, or use $EMULATOR_LOOPBACK on the emulator.")
            }
            append(" Local phone commands still work.")
        }
    }
}
