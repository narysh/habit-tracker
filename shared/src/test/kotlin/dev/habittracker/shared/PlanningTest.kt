package dev.habittracker.shared

import org.junit.jupiter.api.Test
import java.time.*
import java.util.UUID
import kotlin.test.*

class PlanningTest {
    private val today=LocalDate.parse("2026-11-15")
    @Test fun `quick entry previews Russian dates times and ambiguous phrases`() {
        val draft=parseQuickTask("Сдать отчёт завтра в 18:00",today,"Europe/Moscow")
        assertEquals("Сдать отчёт",draft.content.title);assertEquals("2026-11-16",draft.content.dueDate);assertEquals("18:00",draft.content.dueTime)
        assertEquals("2026-11-17",parseQuickTask("Позвонить послезавтра",today,"Europe/Moscow").content.dueDate)
        val vague=parseQuickTask("Прочитать главу вечером",today,"Europe/Moscow")
        assertNull(vague.content.dueDate);assertTrue(vague.warnings.isNotEmpty());assertTrue(vague.content.title.contains("вечером"))
    }
    @Test fun `quick entry rejects invalid dates and does not silently roll them`() {
        assertFails {parseQuickTask("Отчёт 30.02.2027",today,"Europe/Moscow")}
        assertFails {parseQuickTask("Отчёт завтра в 25:00",today,"Europe/Moscow")}
        assertEquals("2026-11-20",parseQuickTask("Отчёт в пятницу",today,"Europe/Moscow").content.dueDate)
        assertTrue(parseQuickTask("Отчёт 20.11",today,"Europe/Moscow").warnings.isNotEmpty())
    }
    @Test fun `old content remains readable with empty organization and unknown completion date`() {
        val old=WireJson.decodeFromString<Content>("""{"kind":"task","title":"Старая задача","completed":true}""") as TaskContent
        assertNull(old.completedAt);assertEquals(Organization(),old.organization)
        val tagged=old.copy(organization=Organization(tags=setOf("Учёба")))
        assertEquals(tagged,WireJson.decodeFromString<Content>(WireJson.encodeToString<Content>(tagged)))
    }
    @Test fun `quiet hours handle overnight boundaries and timezone transitions`() {
        val zone=ZoneId.of("Europe/Moscow");val settings=ReminderSettings(quietEnabled=true)
        val late=LocalDateTime.parse("2026-11-15T23:00").atZone(zone).toInstant()
        assertEquals(LocalDateTime.parse("2026-11-16T08:00").atZone(zone).toInstant(),nextAllowedNotification(late,zone,settings))
        val day=LocalDateTime.parse("2026-11-15T15:00").atZone(zone).toInstant();assertEquals(day,nextAllowedNotification(day,zone,settings))
        val berlin=ZoneId.of("Europe/Berlin");val dst=LocalDateTime.parse("2026-03-28T23:00").atZone(berlin).toInstant()
        assertEquals(LocalDateTime.parse("2026-03-29T08:00").atZone(berlin).toInstant(),nextAllowedNotification(dst,berlin,settings))
        assertFails {ReminderSettings(quietEnabled=true,quietStart="08:00",quietEnd="08:00").validate()}
    }
    @Test fun `habit statistics compare distinct scheduled days and previous period`() {
        val id=UUID.randomUUID().toString();val habit=HabitContent("Бег",weekdays=setOf(2,4),startsOn="2026-10-01")
        fun mark(date: String)=Item(markId(id,LocalDate.parse(date)),1,HabitMarkContent(id,date),updatedAt="2026-11-15T00:00:00Z")
        val item=Item(id,1,habit,updatedAt="2026-11-15T00:00:00Z")
        val stats=habitPeriod(item,listOf(item,mark("2026-11-10"),mark("2026-11-12"),mark("2026-11-03")),LocalDate.parse("2026-11-09"),today)
        assertEquals(100,stats.percentage);assertEquals(50,stats.previousPercentage);assertEquals(2,stats.completedDates.size)
    }
    @Test fun `review distinguishes unknown history and respects requested timezone`() {
        fun item(task: TaskContent)=Item(UUID.randomUUID().toString(),1,task,updatedAt="2026-11-15T00:00:00Z")
        val all=listOf(item(TaskContent("Без даты",completed=true)),item(TaskContent("Выполнена",completed=true,completedAt="2026-11-08T22:00:00Z")),item(TaskContent("Осталась",dueDate="2026-11-11")))
        val review=weekReview(all,LocalDate.parse("2026-11-09"),ZoneId.of("Europe/Moscow"))
        assertEquals(1,review.completedTasks);assertEquals(1,review.completedWithoutDate);assertEquals(1,review.tasksByDay["2026-11-11"])
    }
    @Test fun `plan validation rejects duplicate tasks dates outside week and malformed links`() {
        val id=UUID.randomUUID().toString();val move=PlanMove(id,1,"2026-11-10")
        assertFails {WeekPlanContent("План","2026-11-09","",listOf(move,move)).validate()}
        assertFails {WeekPlanContent("План","2026-11-09","",listOf(move.copy(dueDate="2026-11-16"))).validate()}
        assertFails {TaskContent("Задача",organization=Organization(projectId="invalid")).validate()}
    }
}
