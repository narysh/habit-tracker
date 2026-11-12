package dev.habittracker.android

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.habittracker.shared.*
import kotlinx.coroutines.*
import java.time.*

object Reminders {
    private const val channel = "reminders"
    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,"Задачи и привычки",NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun pending(context: Context,id: String): PendingIntent {
        val intent=Intent(context,ReminderReceiver::class.java).apply { data=Uri.parse("habittracker://reminder/$id"); putExtra("id",id) }
        return PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    suspend fun reschedule(context: Context,repository: Repository) {
        if(repository.app.preferences.current()?.userId!=repository.session.userId || repository.app.preferences.url()!=repository.serverUrl) return
        createChannel(context)
        val alarm=context.getSystemService(AlarmManager::class.java)
        val preferences=context.getSharedPreferences("reminders",Context.MODE_PRIVATE)
        for(id in preferences.getStringSet("scheduled",emptySet()).orEmpty()) alarm.cancel(pending(context,id))
        val all=repository.dao.items().map { WireJson.decodeFromString<Item>(it.localJson) }.filterNot { it.deleted }
        val ids=mutableSetOf<String>()
        for(item in all) {
            val next: Instant?=when(val content=item.content) {
                is TaskContent -> if(!content.completed && content.reminderMinutes != null && content.dueDate != null && content.dueTime != null) {
                    LocalDate.parse(content.dueDate).atTime(LocalTime.parse(content.dueTime)).atZone(ZoneId.of(content.zoneId)).toInstant().minusSeconds(requireNotNull(content.reminderMinutes).toLong()*60)
                } else null
                is HabitContent -> {
                    val time=content.reminderTime?.let(LocalTime::parse)
                    if(time==null) null else {
                        val zone=ZoneId.of(content.zoneId); val today=LocalDate.now(zone)
                        val marks=all.mapNotNull { (it.content as? HabitMarkContent)?.takeIf { mark -> mark.habitId==item.id && mark.completed }?.date?.let(LocalDate::parse) }.toSet()
                        (0L..8).map { today.plusDays(it) }.firstOrNull { date ->
                            content.isDue(date) && date !in marks && (content.mode!=HabitMode.WEEKLY_TARGET || content.weekProgress(date,marks)<content.targetPerWeek) && date.atTime(time).atZone(zone).toInstant()>Instant.now()
                        }?.atTime(time)?.atZone(zone)?.toInstant()
                    }
                }
                else -> null
            }
            if(next!=null && next>Instant.now()) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.toEpochMilli(),pending(context,item.id)); ids+=item.id
            }
        }
        preferences.edit().putStringSet("scheduled",ids).apply()
    }
    fun clear(context: Context) {
        val alarm=context.getSystemService(AlarmManager::class.java)
        val prefs=context.getSharedPreferences("reminders",Context.MODE_PRIVATE)
        prefs.getStringSet("scheduled",emptySet()).orEmpty().forEach { alarm.cancel(pending(context,it)) }
        prefs.edit().remove("scheduled").apply()
    }
    fun show(context: Context,item: Item) {
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(context,channel).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(item.content.title).setContentText(if(item.content is HabitContent) "Время для привычки" else "Напоминание о задаче").setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(context).notify(item.id.hashCode(),notification)
    }
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val result=goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app=context.applicationContext as HabitApplication
                val repo=app.repository() ?: return@launch
                val id=intent.getStringExtra("id") ?: return@launch
                val item=repo.dao.item(id)?.let { WireJson.decodeFromString<Item>(it.localJson) }
                if(item!=null && !item.deleted && (item.content as? TaskContent)?.completed!=true) Reminders.show(context,item)
                Reminders.reschedule(context,repo)
            } finally { result.finish() }
        }
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val result=goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { (context.applicationContext as HabitApplication).repository()?.let { Reminders.reschedule(context,it) } } finally { result.finish() } }
    }
}
