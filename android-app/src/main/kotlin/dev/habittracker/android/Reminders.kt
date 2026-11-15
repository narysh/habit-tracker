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
    private const val channel="reminders"
    private const val summaryId="daily-summary"
    fun settings(context: Context): ReminderSettings=runCatching {WireJson.decodeFromString<ReminderSettings>(context.getSharedPreferences("reminder-settings",Context.MODE_PRIVATE).getString("value",null) ?: return ReminderSettings())}.getOrDefault(ReminderSettings())
    fun saveSettings(context: Context,value: ReminderSettings){value.validate();check(context.getSharedPreferences("reminder-settings",Context.MODE_PRIVATE).edit().putString("value",WireJson.encodeToString(value)).commit())}
    fun createChannel(context: Context){context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,"Задачи и привычки",NotificationManager.IMPORTANCE_DEFAULT))}
    private fun pending(context: Context,id: String): PendingIntent=PendingIntent.getBroadcast(context,0,Intent(context,ReminderReceiver::class.java).setData(Uri.parse("habittracker://reminder/$id")).putExtra("id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    suspend fun reschedule(context: Context,repository: Repository){
        if(repository.app.preferences.current()?.userId!=repository.session.userId || repository.app.preferences.url()!=repository.serverUrl)return
        createChannel(context)
        val options=settings(context);val alarm=context.getSystemService(AlarmManager::class.java);val prefs=context.getSharedPreferences("reminders",Context.MODE_PRIVATE)
        prefs.getStringSet("scheduled",emptySet()).orEmpty().forEach {alarm.cancel(pending(context,it))}
        val all=repository.dao.items().map {WireJson.decodeFromString<Item>(it.localJson)}.filterNot {it.deleted}
        val ids=mutableSetOf<String>();val now=Instant.now()
        if(!options.summaryOnly) for(item in all){
            var zone=ZoneId.systemDefault()
            val next=when(val content=item.content){
                is TaskContent->{zone=ZoneId.of(content.zoneId);if(!content.completed && content.reminderMinutes!=null && content.dueDate!=null && content.dueTime!=null)LocalDate.parse(content.dueDate).atTime(LocalTime.parse(content.dueTime)).atZone(zone).toInstant().minusSeconds(requireNotNull(content.reminderMinutes).toLong()*60)else null}
                is HabitContent->{zone=ZoneId.of(content.zoneId);val time=content.reminderTime?.let(LocalTime::parse);val today=LocalDate.now(zone)
                    val marks=all.mapNotNull {(it.content as? HabitMarkContent)?.takeIf {it.habitId==item.id && it.completed}?.date?.let(LocalDate::parse)}.toSet()
                    if(time==null)null else (0L..8).map {today.plusDays(it)}.firstOrNull {date->content.isDue(date) && date !in marks && (content.mode!=HabitMode.WEEKLY_TARGET || content.weekProgress(date,marks)<content.targetPerWeek) && date.atTime(time).atZone(zone).toInstant()>now}?.atTime(time)?.atZone(zone)?.toInstant()
                }
                else->null
            }
            val snooze=prefs.getLong("snooze:${item.id}",0).takeIf {it>now.toEpochMilli()}?.let(Instant::ofEpochMilli)
            if((item.content as? TaskContent)?.completed==true)continue
            val candidate=(snooze ?: next)?.let {nextAllowedNotification(it,zone,options)}
            if(candidate!=null && candidate>now){alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,candidate.toEpochMilli(),pending(context,item.id));ids+=item.id}
        }
        if(options.summaryEnabled){
            val zone=ZoneId.systemDefault();val time=LocalTime.parse(options.summaryTime)
            var instant=nextAllowedNotification(LocalDate.now(zone).atTime(time).atZone(zone).toInstant(),zone,options)
            if(instant<=now)instant=nextAllowedNotification(LocalDate.now(zone).plusDays(1).atTime(time).atZone(zone).toInstant(),zone,options)
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,instant.toEpochMilli(),pending(context,summaryId));ids+=summaryId
        }
        prefs.edit().putStringSet("scheduled",ids).apply()
    }
    fun clear(context: Context){val prefs=context.getSharedPreferences("reminders",Context.MODE_PRIVATE);val alarm=context.getSystemService(AlarmManager::class.java);prefs.getStringSet("scheduled",emptySet()).orEmpty().forEach {alarm.cancel(pending(context,it))};prefs.edit().clear().apply();NotificationManagerCompat.from(context).cancelAll()}
    private fun allowed(context: Context)=Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
    private fun post(context: Context,id: Int,notification: Notification){try{NotificationManagerCompat.from(context).notify(id,notification)}catch(_:SecurityException){/* Permission may be revoked after the earlier check. */}}
    fun show(context: Context,item: Item,userId: String){
        if(!allowed(context))return
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String)=PendingIntent.getBroadcast(context,0,Intent(context,ReminderActionReceiver::class.java).setData(Uri.parse("habittracker://notification/$userId/${item.id}/$name")).putExtra("id",item.id).putExtra("user",userId).putExtra("action",name),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(context,channel).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(item.content.title).setContentText(if(item.content is HabitContent)"Время для привычки" else "Напоминание о задаче").setContentIntent(open).setAutoCancel(true).setGroup("habit-reminders")
            .addAction(0,"Выполнено",action("done")).addAction(0,"Через час",action("hour")).addAction(0,"Завтра",action("tomorrow")).build()
        post(context,item.id.hashCode(),notification)
    }
    fun summary(context: Context,all: List<Item>){
        if(!allowed(context))return
        val today=LocalDate.now();val visible=all.filterNot {it.deleted}
        val tasks=visible.count {val t=it.content as? TaskContent;t!=null && !t.completed && (t.dueDate==null || LocalDate.parse(t.dueDate)<=today)}
        val habits=visible.count {item->val h=item.content as? HabitContent
            if(h==null)false else {val date=LocalDate.now(ZoneId.of(h.zoneId));val marks=visible.mapNotNull {row->(row.content as? HabitMarkContent)?.takeIf {it.habitId==item.id && it.completed}?.date?.let(LocalDate::parse)}.toSet()
                h.isDue(date) && date !in marks && (h.mode!=HabitMode.WEEKLY_TARGET || h.weekProgress(date,marks)<h.targetPerWeek)}
        }
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(context,summaryId.hashCode(),NotificationCompat.Builder(context,channel).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Твой день в Habit Tracker").setContentText("Осталось задач: $tasks · привычек: $habits").setContentIntent(open).setGroup("habit-reminders").setGroupSummary(true).setAutoCancel(true).build())
    }
    fun isSummary(id: String)=id==summaryId
}
class ReminderReceiver: BroadcastReceiver(){override fun onReceive(context: Context,intent: Intent){val pending=goAsync();CoroutineScope(Dispatchers.IO).launch{try{
    val app=context.applicationContext as HabitApplication;val repo=app.repository() ?: return@launch;val id=intent.getStringExtra("id") ?: return@launch
    val all=repo.dao.items().map {WireJson.decodeFromString<Item>(it.localJson)}
    if(Reminders.isSummary(id))Reminders.summary(context,all)else{
        val item=all.firstOrNull {it.id==id && !it.deleted}
        if(item!=null && (item.content as? TaskContent)?.completed!=true){
            val habit=item.content as? HabitContent;val today=habit?.let {LocalDate.now(ZoneId.of(it.zoneId))}
            val already=all.any {val mark=it.content as? HabitMarkContent;!it.deleted && mark?.habitId==id && mark.date==today?.toString() && mark.completed}
            if(habit==null || (habit.isDue(today!!) && !already))Reminders.show(context,item,repo.session.userId)
        }
    };Reminders.reschedule(context,repo)
}finally{pending.finish()}}}}
class ReminderActionReceiver: BroadcastReceiver(){override fun onReceive(context: Context,intent: Intent){val pending=goAsync();CoroutineScope(Dispatchers.IO).launch{try{
    val app=context.applicationContext as HabitApplication;if(app.preferences.current()?.userId!=intent.getStringExtra("user"))return@launch
    val repo=app.repository() ?: return@launch;val id=intent.getStringExtra("id") ?: return@launch
    val item=repo.dao.item(id)?.let {WireJson.decodeFromString<Item>(it.localJson)}?.takeIf {!it.deleted} ?: return@launch
    val content=item.content;val zone=ZoneId.of(when(content){is TaskContent->content.zoneId;is HabitContent->content.zoneId;else->return@launch})
    val prefs=context.getSharedPreferences("reminders",Context.MODE_PRIVATE)
    if(intent.getStringExtra("action")=="done"){
        prefs.edit().remove("snooze:$id").apply()
        when(content){is TaskContent->repo.save(content.copy(completed=true),id);is HabitContent->{val date=LocalDate.now(zone);if(content.isDue(date))repo.save(HabitMarkContent(id,date.toString()),markId(id,date))};else->Unit}
    }else{
        val now=ZonedDateTime.now(zone);val target=if(intent.getStringExtra("action")=="tomorrow")now.plusDays(1).toInstant()else now.plusHours(1).toInstant()
        prefs.edit().putLong("snooze:$id",target.toEpochMilli()).apply();Reminders.reschedule(context,repo)
    }
    NotificationManagerCompat.from(context).cancel(id.hashCode());TrackerWidget.updateAll(context)
}finally{pending.finish()}}}}
class BootReceiver: BroadcastReceiver(){override fun onReceive(context: Context,intent: Intent){val pending=goAsync();CoroutineScope(Dispatchers.IO).launch{try{(context.applicationContext as HabitApplication).repository()?.let {Reminders.reschedule(context,it)};TrackerWidget.updateAll(context)}finally{pending.finish()}}}}
