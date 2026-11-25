package com.kai.assistant.business

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kai.assistant.api.KaiApiClient
import com.kai.assistant.books.BooksStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads only WhatsApp Business (`com.whatsapp.w4b`) notifications and replies through
 * each notification's own RemoteInput action. Personal WhatsApp and every other app
 * are ignored before any content is read.
 */
class WhatsAppBusinessNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, Debounced>()
    private val replyActions = ConcurrentHashMap<String, NotificationReply.Action>()

    private data class Debounced(
        val facts: NotificationFacts,
        val runnable: Runnable,
    )

    private val engine: EnquiryEngine by lazy {
        val app = applicationContext
        EnquiryEngine(
            infoStore = { BusinessInfoStore.get(app).current() },
            repository = EnquiryStore.get(app),
            brain = BackendEnquiryBrain(KaiApiClient.forDevice(app, callTimeoutSeconds = 25L)),
            enabledAtMs = { AutoReplySettings.get(app).enabledAtMs() },
        )
    }

    private val manager: ManagerEngine by lazy {
        val app = applicationContext
        ManagerEngine(
            tags = PartyTagStore.get(app),
            books = { BooksStore.get(app).books.value },
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        EnquiryStatusNotifier.ensureChannels(this)
        val on = AutoReplySettings.get(this).isEnabled()
        EnquiryStatusNotifier.updateListeningIndicator(this, on)
    }

    override fun onDestroy() {
        scope.cancel()
        pending.values.forEach { main.removeCallbacks(it.runnable) }
        pending.clear()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName != WHATSAPP_BUSINESS_PACKAGE) return

        val notification = sbn.notification ?: return
        if (!shouldRead(sbn, notification)) return
        val facts = toFacts(sbn, notification) ?: return
        val key = ChatFilter.chatKey(facts)
        val tagged = PartyTagStore.get(this).find(key, ChatFilter.customerName(facts)) != null
        val route = routeWhatsAppBusiness(
            hasPartyTag = tagged,
            autoReplyOn = AutoReplySettings.get(this).isEnabled(),
            businessReady = BusinessInfoStore.get(this).current().readyForAutoReply,
        )
        if (route == IncomingRoute.Ignore) return

        val reply = NotificationReply.find(notification)
        if (reply != null) {
            replyActions[key] = reply
        }
        pending.remove(key)?.let { main.removeCallbacks(it.runnable) }
        val runnable = Runnable {
            pending.remove(key)
            scope.launch {
                when (route) {
                    IncomingRoute.Manager -> handleManager(facts)
                    IncomingRoute.Enquiry -> handle(facts, key)
                    IncomingRoute.Ignore -> Unit
                }
            }
        }
        pending[key] = Debounced(facts, runnable)
        main.postDelayed(runnable, DEBOUNCE_MS)
    }

    /**
     * Message text is read only for a tagged labour or supplier chat, or while customer
     * auto-reply is on. Other WhatsApp Business chats are left alone.
     */
    private fun shouldRead(sbn: StatusBarNotification, notification: Notification): Boolean {
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val ids = buildList {
            sbn.tag?.let { add(it) }
            sbn.key?.let { add(it) }
            notification.shortcutId?.let { add(it) }
            extras?.getString(Notification.EXTRA_CONVERSATION_TITLE)?.let { add(it) }
        }
        val preview = NotificationFacts(
            packageName = sbn.packageName,
            title = title,
            text = null,
            postedAtMs = sbn.postTime,
            ids = ids,
        )
        val tagged = PartyTagStore.get(this).find(ChatFilter.chatKey(preview), ChatFilter.customerName(preview)) != null
        val enquiryOn = AutoReplySettings.get(this).isEnabled() &&
            BusinessInfoStore.get(this).current().readyForAutoReply
        return tagged || enquiryOn
    }

    private fun handleManager(facts: NotificationFacts) {
        when (val action = manager.handle(facts)) {
            ManagerAction.Skip -> Unit
            is ManagerAction.Alert -> alert(action.title, action.body, action.chatKey)
            is ManagerAction.NotifyPayable -> {
                BooksStore.get(this).apply(action.record)
                alert(action.supplierName, action.notice, action.chatKey)
            }
            is ManagerAction.SendOrders -> {
                action.orders.forEach { order -> sendOrder(order) }
                if (action.skipped.isNotBlank()) {
                    alert(action.labourName, "Not ordered:\n${action.skipped}", action.chatKey)
                }
            }
        }
    }

    /**
     * Sends only when the chat is still tagged Supplier and the number is the one on that tag.
     * A number written inside the labour message is never used.
     */
    private fun sendOrder(order: OutboundOrder) {
        if (!orderTextIsSafe(order.text)) {
            alert(order.supplierName, "That order mentioned a price or a payment, so it was not sent.", order.supplierChatKey)
            return
        }
        val tag = PartyTagStore.get(this).find(order.supplierChatKey, order.supplierName)
        val digits = tag?.takeIf { it.role == PartyRole.SUPPLIER }?.let { whatsAppDigits(it.chatKey) }
        if (tag == null || digits == null || digits != order.digits) {
            alert(
                order.supplierName,
                "I did not message anyone. ${order.supplierName} is not a tagged Supplier with a WhatsApp number.",
                order.supplierChatKey,
            )
            return
        }
        val replied = replyActions[order.supplierChatKey]?.let { NotificationReply.send(this, it, order.text) } == true
        val sent = replied || BusinessWhatsAppSender.send(this, digits, order.text)
        if (!sent) {
            alert(
                order.supplierName,
                "Could not send the order to ${order.supplierName} on WhatsApp Business. " +
                    "Turn on KAI under Accessibility if Send was not tapped. Nothing was recorded as sent.",
                order.supplierChatKey,
            )
            return
        }
        BooksStore.get(this).apply(order.record)
    }

    private fun alert(title: String, body: String, chatKey: String) {
        EnquiryStatusNotifier.alertOwner(
            this,
            customerName = title,
            customerText = body,
            reason = "",
            chatKey = chatKey,
        )
    }

    private suspend fun handle(facts: NotificationFacts, chatKey: String) {
        val action = engine.handle(facts)
        when (action) {
            EngineAction.Skip -> Unit
            is EngineAction.AlertOnly -> {
                EnquiryStatusNotifier.alertOwner(
                    this,
                    customerName = action.customerName,
                    customerText = action.customerText,
                    reason = action.reason,
                    chatKey = action.chatKey,
                )
                FollowUpScheduler.scheduleNext(this)
            }
            is EngineAction.Reply -> {
                val replyAction = replyActions[chatKey] ?: replyActions[action.chatKey]
                val sent = replyAction?.let { NotificationReply.send(this, it, action.text) } == true
                if (!sent) {
                    EnquiryStatusNotifier.alertOwner(
                        this,
                        customerName = action.customerName,
                        customerText = action.text,
                        reason = "Could not send reply — open WhatsApp Business",
                        chatKey = action.chatKey,
                    )
                } else if (action.alertOwner) {
                    EnquiryStatusNotifier.alertOwner(
                        this,
                        customerName = action.customerName,
                        customerText = EnquiryStore.get(this).get(action.chatKey)?.lastCustomerText.orEmpty(),
                        reason = action.alertReason.ifBlank { "Needs you" },
                        chatKey = action.chatKey,
                    )
                }
                FollowUpScheduler.scheduleNext(this)
            }
        }
    }

    private fun toFacts(sbn: StatusBarNotification, notification: Notification): NotificationFacts? {
        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val style = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val messages = style?.messages.orEmpty().map { message ->
            NotifiedMessage(
                text = message.text?.toString().orEmpty(),
                timestampMs = message.timestamp,
                sender = message.person?.name?.toString() ?: message.sender?.toString(),
            )
        }
        val ids = buildList {
            sbn.tag?.let { add(it) }
            sbn.key?.let { add(it) }
            notification.shortcutId?.let { add(it) }
            extras.getString("android.shortcutId")?.let { add(it) }
            extras.getString(Notification.EXTRA_CONVERSATION_TITLE)?.let { add(it) }
        }
        return NotificationFacts(
            packageName = sbn.packageName,
            title = title,
            text = text,
            postedAtMs = sbn.postTime,
            isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            category = notification.category,
            conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            isGroupConversation = extras.getBoolean("android.isGroupConversation", false) ||
                (style?.isGroupConversation == true),
            ids = ids,
            hasReplyAction = NotificationReply.find(notification) != null,
            messages = messages,
            selfName = style?.user?.name?.toString(),
        )
    }

    companion object {
        private const val DEBOUNCE_MS = 2_500L
    }
}
