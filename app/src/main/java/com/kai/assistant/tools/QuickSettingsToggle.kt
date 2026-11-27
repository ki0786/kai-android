package com.kai.assistant.tools

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Taps a quick-settings tile and closes the shade. The current app stays
 * underneath. This is the path that still changes Bluetooth and Wi-Fi on a
 * phone where the public radio APIs refuse an ordinary app.
 */
@Suppress("DEPRECATION")
internal object QuickSettingsToggle {
    fun toggle(names: List<String>, wantOn: Boolean): Boolean {
        val service = ScreenReaderAccessibilityService.instance ?: return false
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        var changed = false
        main.post {
            val opened = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            if (!opened) {
                done.countDown()
                return@post
            }
            main.postDelayed({
                try {
                    changed = clickTile(service, names, wantOn)
                } finally {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    done.countDown()
                }
            }, SHADE_SETTLE_MS)
        }
        val finished = done.await(WAIT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            main.post {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }
        }
        return changed
    }

    private fun clickTile(service: AccessibilityService, names: List<String>, wantOn: Boolean): Boolean {
        val windows = service.windows ?: return false
        var already = false
        for (window in windows) {
            val root = window.root ?: continue
            val node = findTile(root, names, wantOn)
            if (node == null) {
                root.recycle()
                continue
            }
            val action = quickSettingAction(nodeLabel(node), names, wantOn)
            if (action == QuickSettingAction.Already) {
                already = true
                if (node !== root) node.recycle()
                root.recycle()
                continue
            }
            val clicked = click(node)
            if (node !== root) node.recycle()
            root.recycle()
            if (clicked) return true
        }
        return already
    }

    private fun findTile(
        node: AccessibilityNodeInfo,
        names: List<String>,
        wantOn: Boolean,
    ): AccessibilityNodeInfo? {
        val action = quickSettingAction(nodeLabel(node), names, wantOn)
        if (action != QuickSettingAction.Ignore) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findTile(child, names, wantOn)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
            depth++
        }
        return false
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        return listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.viewIdResourceName,
        ).joinToString(" ")
    }

    private const val SHADE_SETTLE_MS = 450L
    private const val WAIT_MS = 2_000L
}
