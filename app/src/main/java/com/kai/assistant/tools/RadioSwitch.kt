package com.kai.assistant.tools

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.provider.Settings

/**
 * Turns Wi-Fi and Bluetooth on or off and reads the result back.
 * The public calls are attempted first. On a phone that rejects them, the
 * quick-settings tile is tapped and the shade is closed.
 */
internal class RadioSwitch(private val context: Context) {
    fun wifi(enabled: Boolean): ToolResult {
        if (wifiIs(enabled) == true) return ToolResult.Ok(radioSpoken("Wi-Fi", enabled))
        directWifi(enabled)
        if (waitUntil { wifiIs(enabled) == true }) {
            return ToolResult.Ok(radioSpoken("Wi-Fi", enabled))
        }
        val tile = QuickSettingsToggle.toggle(listOf("wi-fi", "wifi", "wlan"), enabled)
        if (tile && (waitUntil { wifiIs(enabled) == true } || wifiIs(enabled) == null)) {
            return ToolResult.Ok(radioSpoken("Wi-Fi", enabled))
        }
        val now = wifiIs(enabled)
        if (now == true) return ToolResult.Ok(radioSpoken("Wi-Fi", enabled))
        return ToolResult.Error(radioFailed("Wi-Fi", enabled))
    }

    fun bluetooth(enabled: Boolean): ToolResult {
        if (bluetoothIs(enabled) == true) return spokenBluetooth(enabled)
        directBluetooth(enabled)
        if (waitUntil { bluetoothIs(enabled) == true }) return spokenBluetooth(enabled)
        // Public enable/disable is a no-op for an app targeting 35. The tile is
        // the path that still changes the adapter. The shade is closed either way.
        QuickSettingsToggle.toggle(listOf("bluetooth", "blue tooth"), enabled)
        if (waitUntil(timeoutMs = 2_000) { bluetoothIs(enabled) == true }) return spokenBluetooth(enabled)
        return ToolResult.Error(radioFailed("Bluetooth", enabled))
    }

    private fun spokenBluetooth(enabled: Boolean): ToolResult {
        val speech = radioSpeechForReadback("Bluetooth", enabled, bluetoothIs(enabled))
        return if (speech != null) ToolResult.Ok(speech) else ToolResult.Error(radioFailed("Bluetooth", enabled))
    }

    @Suppress("DEPRECATION")
    private fun directWifi(enabled: Boolean) {
        val manager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
        runCatching { manager.setWifiEnabled(enabled) }
        runCatching {
            val field = manager.javaClass.getDeclaredField("mService")
            field.isAccessible = true
            val service = field.get(manager) ?: return
            val method = service.javaClass.methods.firstOrNull { candidate ->
                candidate.name == "setWifiEnabled" && candidate.parameterTypes.size == 2
            } ?: return
            method.invoke(service, context.packageName, enabled)
        }
    }

    @Suppress("DEPRECATION")
    private fun directBluetooth(enabled: Boolean) {
        val adapter = adapter() ?: return
        runCatching {
            if (enabled) adapter.enable() else adapter.disable()
        }
        runCatching {
            val method = adapter.javaClass.methods.firstOrNull { candidate ->
                candidate.name == "getService" && candidate.parameterTypes.isEmpty()
            } ?: return
            method.isAccessible = true
            val service = method.invoke(adapter) ?: return
            val name = if (enabled) "enable" else "disable"
            val call = service.javaClass.methods.firstOrNull { candidate ->
                candidate.name == name &&
                    (candidate.parameterTypes.isEmpty() || candidate.parameterTypes.size == 1)
            } ?: return
            if (call.parameterTypes.isEmpty()) call.invoke(service)
            else call.invoke(service, context.packageName)
        }
    }

    private fun wifiIs(enabled: Boolean): Boolean? {
        val manager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val on = runCatching { manager.isWifiEnabled }.getOrNull() ?: return null
        return on == enabled
    }

    @Suppress("DEPRECATION")
    private fun adapter(): BluetoothAdapter? {
        val manager = context.getSystemService(BluetoothManager::class.java)
        return manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    @Suppress("DEPRECATION")
    private fun bluetoothIs(enabled: Boolean): Boolean? {
        val fromAdapter = adapter()?.let { runCatching { it.isEnabled }.getOrNull() }
        val on = fromAdapter ?: runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BLUETOOTH_ON, 0) == 1
        }.getOrNull() ?: return null
        return on == enabled
    }

    private fun waitUntil(timeoutMs: Long = 900, check: () -> Boolean): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (check()) return true
            Thread.sleep(80)
        }
        return check()
    }
}
