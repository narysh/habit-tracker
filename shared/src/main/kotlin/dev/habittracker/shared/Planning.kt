package dev.habittracker.shared

import kotlinx.serialization.Serializable
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class QuickDraft(val content: TaskContent,val warnings: List<String>)
/** Conservative parser: never silently guesses an unknown date expression. */
fun parseQuickTask(input: String,today: LocalDate,zoneId: String): QuickDraft {
    require(input.isNotBlank());ZoneId.of(zoneId)
    var title=input.trim();var date: LocalDate?=null;var time: LocalTime?=null;val warnings=mutableListOf<String>()
    fun remove(range: IntRange){title=title.removeRange(range).replace(Regex("\\s+")," ").trim()}
    val timeMatch=Regex("(?i)(?:\\s|^)(?:в\\s+)?(\\d{1,2}):(\\d{2})(?=\\s|$)").find(title)
    if(timeMatch!=null){time=LocalTime.of(timeMatch.groupValues[1].toInt(),timeMatch.groupValues[2].toInt());remove(timeMatch.range)}
    val relative=Regex("(?i)(?<![\\p{L}\\p{N}])(послезавтра|завтра|сегодня)(?![\\p{L}\\p{N}])").find(title)
    if(relative!=null){date=today.plusDays(when(relative.value.lowercase(Locale.ROOT)){"завтра"->1;"послезавтра"->2;else->0});remove(relative.range)}
    if(date==null){
        val days=listOf("понедельник","вторник","среду","четверг","пятницу","субботу","воскресенье")
        val weekday=Regex("(?i)(?:^|\\s)в\\s+(${days.joinToString("|")})(?=\\s|$)").find(title)
        if(weekday!=null){val day=DayOfWeek.of(days.indexOf(weekday.groupValues[1].lowercase(Locale.ROOT))+1);date=today.with(TemporalAdjusters.nextOrSame(day));remove(weekday.range);warnings+="День недели распознан как ближайшая дата; проверь её."}
    }
    if(date==null){
        val explicit=Regex("(?<![\\d.])(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?(?![\\d.])").find(title)
        if(explicit!=null){val year=explicit.groupValues[3].ifBlank {today.year.toString()}.toInt();date=LocalDate.of(year,explicit.groupValues[2].toInt(),explicit.groupValues[1].toInt());remove(explicit.range);if(explicit.groupValues[3].isBlank())warnings+="Год не указан: выбран ${today.year}."}
    }
    if(time!=null && date==null){date=today;warnings+="Указано только время: выбран сегодняшний день."}
    require(title.isNotBlank()){ "Добавь название задачи" }
    if(date!=null && date!!.atTime(time ?: LocalTime.MAX).atZone(ZoneId.of(zoneId)).toInstant()<today.atStartOfDay(ZoneId.of(zoneId)).toInstant())warnings+="Дата уже прошла."
    if(Regex("(?i)(вечером|утром|на следующей|через|после обеда)").containsMatchIn(title))warnings+="Часть фразы не распознана: срок можно уточнить в редакторе."
    val task=TaskContent(title,dueDate=date?.toString(),dueTime=time?.toString(),zoneId=zoneId)
    task.validate();return QuickDraft(task,warnings)
}

@Serializable data class HabitPeriod(val id: String,val title: String,val completedDays: Int,val targetDays: Int,val previousCompletedDays: Int,val previousTargetDays: Int,val completedDates: List<String>,val percentage: Int,val previousPercentage: Int)
fun habitPeriod(item: Item,all: List<Item>,start: LocalDate,end: LocalDate): HabitPeriod {
    val habit=item.content as HabitContent;require(!end.isBefore(start) && java.time.temporal.ChronoUnit.DAYS.between(start,end)<367)
    val marks=all.filterNot {it.deleted}.mapNotNull {(it.content as? HabitMarkContent)?.takeIf {it.habitId==item.id && it.completed}?.date?.let(LocalDate::parse)}.toSet()
    fun count(from: LocalDate,to: LocalDate): Pair<Int,Int> {
        var expected=0;var done=0;var cursor=from
        while(cursor<=to){
            val weekEnd=cursor.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).coerceAtMost(to)
            val eligible=(0..java.time.temporal.ChronoUnit.DAYS.between(cursor,weekEnd).toInt()).map {cursor.plusDays(it.toLong())}.filter {habit.isDue(it)}
            val target=if(habit.mode==HabitMode.WEEKLY_TARGET) minOf(habit.targetPerWeek,eligible.size) else eligible.size
            expected+=target;done+=minOf(target,eligible.count {it in marks});cursor=weekEnd.plusDays(1)
        }
        return done to expected
    }
    val length=java.time.temporal.ChronoUnit.DAYS.between(start,end)+1
    val now=count(start,end);val previous=count(start.minusDays(length),start.minusDays(1))
    fun percent(pair: Pair<Int,Int>)=if(pair.second==0)0 else (100*pair.first/pair.second).coerceAtMost(100)
    return HabitPeriod(item.id,habit.title,now.first,now.second,previous.first,previous.second,marks.filter {it in start..end}.sorted().map {it.toString()},percent(now),percent(previous))
}
@Serializable data class WeekReview(val weekStart: String,val completedTasks: Int,val completedWithoutDate: Int,val remainingTasks: List<Item>,val tasksByDay: Map<String,Int>,val habits: List<HabitPeriod>)
fun weekReview(all: List<Item>,week: LocalDate,zone: ZoneId): WeekReview {
    require(week.dayOfWeek==DayOfWeek.MONDAY);val visible=all.filterNot {it.deleted};val end=week.plusDays(6)
    val tasks=visible.filter {it.content is TaskContent}
    val completed=tasks.count {val task=it.content as TaskContent;task.completed && task.completedAt?.let {Instant.parse(it).atZone(zone).toLocalDate() in week..end}==true}
    val remaining=tasks.filter {!(it.content as TaskContent).completed}
    return WeekReview(week.toString(),completed,tasks.count {(it.content as TaskContent).completed && it.content.completedAt==null},remaining,remaining.mapNotNull {(it.content as TaskContent).dueDate?.takeIf {date->LocalDate.parse(date) in week..end}}.groupingBy {it}.eachCount(),visible.filter {it.content is HabitContent}.map {habitPeriod(it,visible,week,end)})
}
