package com.kai.assistant.tools

import android.accessibilityservice.AccessibilityService
import android.os.Build
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
        fun finish(clicked: Boolean) {
            changed = clicked
            main.postDelayed({
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                done.countDown()
            }, AFTER_CLICK_MS)
        }
        fun attempt(left: Int) {
            val clicked = runCatching { clickTile(service, names, wantOn) }.getOrDefault(false)
            if (clicked || left <= 1) {
                finish(clicked)
                return
            }
            main.postDelayed({ attempt(left - 1) }, RETRY_MS)
        }
        main.post {
            val opened = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            if (!opened) {
                done.countDown()
                return@post
            }
            main.postDelayed({ attempt(ATTEMPTS) }, SHADE_SETTLE_MS)
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
        val roots = ArrayList<AccessibilityNodeInfo>()
        service.windows?.forEach { window -> window.root?.let { roots.add(it) } }
        val active = service.rootInActiveWindow
        if (active != null && roots.none { it === active }) roots.add(active)
        var already = false
        var clicked = false
        for (root in roots) {
            if (clicked) {
                root.recycle()
                continue
            }
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
            clicked = click(node)
            if (node !== root) node.recycle()
            root.recycle()
        }
        return clicked || already
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
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString()
        } else {
            null
        }
        return listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            state,
            node.viewIdResourceName,
        ).joinToString(" ")
    }

    private const val SHADE_SETTLE_MS = 350L
    private const val RETRY_MS = 250L
    private const val AFTER_CLICK_MS = 400L
    private const val ATTEMPTS = 6
    private const val WAIT_MS = 4_000L
}
