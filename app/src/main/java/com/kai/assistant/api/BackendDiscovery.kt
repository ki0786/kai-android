package com.kai.assistant.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds kai-backend on the phone's own Wi‑Fi subnet when the saved address stops
 * answering, for example after the PC gets a new DHCP lease. Pure logic: the
 * caller supplies the phone's address and the health probe.
 */
object BackendDiscovery {
    const val DEFAULT_PORT = 8000
    const val MAX_HOSTS = 254
    const val CONCURRENCY = 48
    const val SCAN_TIMEOUT_MS = 6_000L

    data class LocalAddress(val ip: String, val prefixLength: Int)

    /**
     * Hosts to probe, best guesses first: the saved host's last number on the current
     * subnet (PCs often keep it across networks), then addresses nearest the phone.
     * Only private IPv4 ranges, and never more than the phone's own /24.
     */
    fun candidates(local: LocalAddress, savedHost: String?): List<String> {
        val phone = parseIpv4(local.ip) ?: return emptyList()
        if (!isPrivate(phone)) return emptyList()
        val prefix = local.prefixLength.coerceIn(24, 30)
        val hostBits = 32 - prefix
        val network = phone and (-1 shl hostBits)
        val size = 1 shl hostBits
        val hosts = (1 until size - 1).map { network or it }.filter { it != phone }

        val savedLast = savedHost?.let { parseIpv4(it) }?.and(0xFF)
        val phoneLast = phone and 0xFF
        val ordered = hosts.sortedWith(
            compareBy<Int>(
                { if (savedLast != null && (it and 0xFF) == savedLast) 0 else 1 },
                { kotlin.math.abs((it and 0xFF) - phoneLast) },
                { it and 0xFF },
            ),
        )
        return ordered.take(MAX_HOSTS).map(::formatIpv4)
    }

    /** `{"status":"ok","service":"KAI Backend",...}`. Other servers on port 8000 don't count. */
    fun isKaiHealth(body: String): Boolean {
        val status = Regex(""""status"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
        val service = Regex(""""service"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
        return status.equals("ok", ignoreCase = true) && service?.contains("kai", ignoreCase = true) == true
    }

    /**
     * Probes [hosts] in parallel and returns the first that answers, or null once all
     * fail or [timeoutMs] passes. Remaining probes are cancelled as soon as one wins.
     */
    suspend fun firstResponding(
        hosts: List<String>,
        probe: suspend (String) -> Boolean,
        concurrency: Int = CONCURRENCY,
        timeoutMs: Long = SCAN_TIMEOUT_MS,
    ): String? {
        if (hosts.isEmpty()) return null
        val winner = CompletableDeferred<String?>()
        return withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val gate = Semaphore(concurrency.coerceAtLeast(1))
                val jobs = hosts.map { host ->
                    launch(Dispatchers.IO) {
                        gate.withPermit {
                            if (winner.isCompleted) return@withPermit
                            if (runCatching { probe(host) }.getOrDefault(false)) winner.complete(host)
                        }
                    }
                }
                launch {
                    jobs.forEach { it.join() }
                    winner.complete(null)
                }
                val found = winner.await()
                jobs.forEach { it.cancel() }
                found
            }
        }
    }

    fun hostOf(url: String): String? =
        Regex("""^https?://([^/:]+)""", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.get(1)

    fun portOf(url: String): Int =
        Regex("""^https?://[^/:]+:(\d+)""", RegexOption.IGNORE_CASE).find(url.trim())
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: DEFAULT_PORT

    fun urlFor(host: String, port: Int = DEFAULT_PORT): String = "http://$host:$port"

    internal fun parseIpv4(value: String): Int? {
        val parts = value.trim().split(".")
        if (parts.size != 4) return null
        var result = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255 || part.length > 3) return null
            result = (result shl 8) or octet
        }
        return result
    }

    internal fun formatIpv4(value: Int): String =
        listOf(24, 16, 8, 0).joinToString(".") { ((value ushr it) and 0xFF).toString() }

    private fun isPrivate(ip: Int): Boolean {
        val a = (ip ushr 24) and 0xFF
        val b = (ip ushr 16) and 0xFF
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
}
