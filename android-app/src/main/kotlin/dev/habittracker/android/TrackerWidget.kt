package dev.habittracker.android

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import dev.habittracker.shared.*
import kotlinx.coroutines.*
import java.time.*

class TrackerWidget : AppWidgetProvider() {
    override fun onAppWidgetOptionsChanged(context: Context,manager: AppWidgetManager,id: Int,options: android.os.Bundle){onUpdate(context,manager,intArrayOf(id))}
    override fun onUpdate(context: Context,manager: AppWidgetManager,ids: IntArray) {
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { updateAll(context) } finally {pending.finish()} }
    }
    companion object {
        suspend fun updateAll(context: Context) {
            val manager=AppWidgetManager.getInstance(context)
            val widgetIds=manager.getAppWidgetIds(ComponentName(context,TrackerWidget::class.java))
            if(widgetIds.isEmpty()) return
            val app=context.applicationContext as HabitApplication
            val repo=app.repository();val user=app.preferences.current()?.userId
            val all=repo?.dao?.items()?.map {WireJson.decodeFromString<Item>(it.localJson)}?.filterNot {it.deleted}.orEmpty()
            val today=LocalDate.now()
            val tasks=all.filter {val t=it.content as? TaskContent;t!=null && !t.completed && (t.dueDate==null || LocalDate.parse(t.dueDate)<=today)}.sortedBy {(it.content as TaskContent).dueDate ?: "9999"}
            val dueHabits=all.filter {item->val h=item.content as? HabitContent
                if(h==null)false else {val date=LocalDate.now(ZoneId.of(h.zoneId));val marks=all.mapNotNull {row->(row.content as? HabitMarkContent)?.takeIf {it.habitId==item.id && it.completed}?.date?.let(LocalDate::parse)}.toSet()
                    h.isDue(date) && date !in marks && (h.mode!=HabitMode.WEEKLY_TARGET || h.weekProgress(date,marks)<h.targetPerWeek)}
            }
            for(widgetId in widgetIds) {
                val height=manager.getAppWidgetOptions(widgetId).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,300)
                val slots=((height-95)/42).coerceIn(1,5)
                val habitSlots=if(dueHabits.isEmpty())0 else minOf(2,maxOf(1,slots/2))
                val rows=(tasks.take((slots-habitSlots).coerceAtLeast(0))+dueHabits.take(habitSlots)).let {initial->(initial+tasks.filterNot {it in initial}+dueHabits.filterNot {it in initial}).distinctBy {it.id}.take(slots)}
                val views=RemoteViews(context.packageName,R.layout.tracker_widget)
                fun open(quick: Boolean)=PendingIntent.getActivity(context,if(quick) widgetId+1 else widgetId,Intent(context,MainActivity::class.java).putExtra("quick",quick).setData(Uri.parse("habittracker://widget/$widgetId/$quick")),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setTextViewText(R.id.widget_title,"Сегодня · ${today.dayOfMonth}.${today.monthValue}")
                views.setTextViewText(R.id.widget_empty,if(user==null) "Войди в приложение" else if(rows.isEmpty()) "На сегодня всё выполнено" else "Нажми ✓, чтобы отметить")
                views.setOnClickPendingIntent(R.id.widget_title,open(false));views.setOnClickPendingIntent(R.id.widget_add,open(true))
                views.removeAllViews(R.id.widget_rows)
                rows.forEach { item->
                    val row=RemoteViews(context.packageName,R.layout.tracker_widget_row)
                    row.setTextViewText(R.id.widget_row_text,(if(item.content is HabitContent) "↻ " else "")+item.content.title)
                    row.setOnClickPendingIntent(R.id.widget_row_text,open(false))
                    val intent=Intent(context,WidgetActionReceiver::class.java).setData(Uri.parse("habittracker://widget-done/$widgetId/${item.id}")).putExtra("id",item.id).putExtra("user",user)
                    row.setOnClickPendingIntent(R.id.widget_row_done,PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    views.addView(R.id.widget_rows,row)
                }
                val refresh=Intent(context,WidgetActionReceiver::class.java).setData(Uri.parse("habittracker://widget-refresh/$widgetId")).putExtra("user",user)
                views.setOnClickPendingIntent(R.id.widget_refresh,PendingIntent.getBroadcast(context,0,refresh,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                manager.updateAppWidget(widgetId,views)
            }
        }
    }
}
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {try {
            val app=context.applicationContext as HabitApplication
            if(app.preferences.current()?.userId!=intent.getStringExtra("user")) return@launch
            val repo=app.repository() ?: return@launch
            val id=intent.getStringExtra("id")
            if(id!=null){val item=repo.dao.item(id)?.let {WireJson.decodeFromString<Item>(it.localJson)}
                if(item!=null && !item.deleted) when(val content=item.content){
                    is TaskContent->if(!content.completed)repo.save(content.copy(completed=true),id)
                    is HabitContent->{val date=LocalDate.now(ZoneId.of(content.zoneId));if(content.isDue(date))repo.save(HabitMarkContent(id,date.toString()),markId(id,date))}
                    else->Unit
                }
            } else repo.scheduleSync()
            TrackerWidget.updateAll(context)
        } catch(_: Exception) { /* The app retains local operations and displays sync errors. */ } finally {pending.finish()} }
    }
}
