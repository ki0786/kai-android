package com.kai.assistant.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.kai.assistant.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/**
 * The backend address in use: the last one found on this Wi‑Fi, else the build's
 * `KAI_API_BASE_URL`. Shared by every [KaiApiClient] and shown in Settings.
 */
class BackendEndpoint private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _url = MutableStateFlow(prefs.getString(KEY_URL, null) ?: BuildConfig.KAI_API_BASE_URL)
    val url: StateFlow<String> = _url.asStateFlow()

    val discovered: Boolean get() = prefs.contains(KEY_URL)

    fun current(): String = _url.value

    fun save(url: String) {
        prefs.edit().putString(KEY_URL, url).apply()
        _url.value = url
    }

    fun reset() {
        prefs.edit().remove(KEY_URL).apply()
        _url.value = BuildConfig.KAI_API_BASE_URL
    }

    companion object {
        private const val PREFS = "kai_backend"
        private const val KEY_URL = "base_url"

        @Volatile private var instance: BackendEndpoint? = null

        fun get(context: Context): BackendEndpoint =
            instance ?: synchronized(this) {
                instance ?: BackendEndpoint(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Re-finds kai-backend on the current Wi‑Fi subnet. One scan runs at a time, and a
 * failed scan isn't repeated for [RETRY_AFTER_MS] so offline turns stay fast.
 */
class BackendLocator(
    private val context: Context,
    private val endpoint: BackendEndpoint = BackendEndpoint.get(context),
) {
    suspend fun rediscover(failedUrl: String, force: Boolean = false): String? = lock.withLock {
        val now = System.currentTimeMillis()
        val current = endpoint.current()
        if (!force && current != failedUrl && now - lastFoundAt < RETRY_AFTER_MS) return current
        if (!force && now - lastMissAt < RETRY_AFTER_MS) return null
        val local = wifiAddress() ?: return null.also { lastMissAt = now }
        val port = BackendDiscovery.portOf(failedUrl)
        val hosts = BackendDiscovery.candidates(local, BackendDiscovery.hostOf(failedUrl))
        val host = BackendDiscovery.firstResponding(hosts, probe = { isKaiBackend(it, port) })
        if (host == null) {
            lastMissAt = System.currentTimeMillis()
            return null
        }
        val url = BackendDiscovery.urlFor(host, port)
        endpoint.save(url)
        lastFoundAt = System.currentTimeMillis()
        lastMissAt = 0L
        url
    }

    private fun isKaiBackend(host: String, port: Int): Boolean {
        val request = Request.Builder().url("${BackendDiscovery.urlFor(host, port)}/health").get().build()
        return runCatching {
            probeClient.newCall(request).execute().use { response ->
                response.isSuccessful && BackendDiscovery.isKaiHealth(response.body?.string().orEmpty())
            }
        }.getOrDefault(false)
    }

    /** The phone's IPv4 address on Wi‑Fi or Ethernet. Mobile data is never scanned. */
    private fun wifiAddress(): BackendDiscovery.LocalAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!local) return null
        val link = cm.getLinkProperties(network)?.linkAddresses ?: return null
        val v4 = link.firstOrNull { it.address is Inet4Address } ?: return null
        val ip = v4.address.hostAddress ?: return null
        return BackendDiscovery.LocalAddress(ip, v4.prefixLength)
    }

    companion object {
        private const val RETRY_AFTER_MS = 30_000L
        private val lock = Mutex()
        @Volatile private var lastMissAt = 0L
        @Volatile private var lastFoundAt = 0L

        private val probeClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(700, TimeUnit.MILLISECONDS)
                .readTimeout(1_200, TimeUnit.MILLISECONDS)
                .callTimeout(1_500, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}
