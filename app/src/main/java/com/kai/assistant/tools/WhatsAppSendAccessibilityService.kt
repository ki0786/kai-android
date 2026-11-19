package com.kai.assistant.tools

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Taps WhatsApp's Send button once, and only after KAI opened a chat for a send
 * the user just requested. It does not run for calls or any other action.
 */
@Suppress("DEPRECATION")
class WhatsAppSendAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        tryTap(event.packageName?.toString())
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        val pending = WhatsAppSendRequest.peek(SystemClock.elapsedRealtime()) ?: return
        tryTap(pending.packageName)
    }

    private fun tryTap(eventPackage: String?) {
        val pending = WhatsAppSendRequest.peek(SystemClock.elapsedRealtime()) ?: return
        if (eventPackage != pending.packageName) return
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != pending.packageName) return
            if (!composerHasMessage(root, pending.message)) return
            val send = findSend(root) ?: return
            val clicked = clickSend(send)
            if (send !== root) send.recycle()
            if (clicked) WhatsAppSendRequest.clear()
        } finally {
            root.recycle()
        }
    }

    private fun composerHasMessage(node: AccessibilityNodeInfo, message: String): Boolean {
        if (node.isEditable && textMatchesMessage(node.text?.toString(), message)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = composerHasMessage(child, message)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun findSend(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isSendControl(node.contentDescription?.toString(), node.viewIdResourceName)) {
            return node
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findSend(child)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun clickSend(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val parent = node.parent ?: return false
        return try {
            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            parent.recycle()
        }
    }
}
