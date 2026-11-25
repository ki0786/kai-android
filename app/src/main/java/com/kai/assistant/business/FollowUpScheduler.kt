package com.kai.assistant.business

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Wakes once when the next quiet-customer follow-up is due for the owner. */
object FollowUpScheduler {
    private const val REQUEST = 7311

    fun scheduleNext(context: Context) {
        val app = context.applicationContext
        val due = FollowUpPolicy.nextDueAt(EnquiryStore.get(app).all(), System.currentTimeMillis())
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pending(app)
        if (due == null) {
            alarms.cancel(pending)
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
            } else {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
            }
        }.onFailure {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
        }
    }

    fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST,
            Intent(context, FollowUpReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

class FollowUpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val store = EnquiryStore.get(app)
        val now = System.currentTimeMillis()
        FollowUpPolicy.toRemind(store.all(), now).forEach { enquiry ->
            EnquiryStatusNotifier.followUpDue(app, enquiry)
            store.update(enquiry.chatKey) {
                it.copy(followUpNotifiedForMs = FollowUpPolicy.quietSince(it))
            }
        }
        FollowUpScheduler.scheduleNext(app)
    }
}
