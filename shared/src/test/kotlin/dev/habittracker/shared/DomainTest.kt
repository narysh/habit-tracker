package dev.habittracker.shared

import java.time.LocalDate
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test
import kotlin.test.*

class DomainTest {
    @Test fun `weekdays streak skips unscheduled days and allows current day to remain open`() {
        val habit = HabitContent("Бег",mode=HabitMode.WEEKDAYS,weekdays=setOf(2,4),startsOn="2026-11-01")
        val marks = setOf(LocalDate.parse("2026-11-03"),LocalDate.parse("2026-11-05"),LocalDate.parse("2026-11-10"))
        assertEquals(3,habit.streak(LocalDate.parse("2026-11-12"),marks))
        assertEquals(0,habit.streak(LocalDate.parse("2026-11-13"),marks))
        assertFalse(habit.isDue(LocalDate.parse("2026-11-11")))
    }
    @Test fun `weekly goals count distinct dates and completed weeks`() {
        val habit = HabitContent("Чтение",mode=HabitMode.WEEKLY_TARGET,targetPerWeek=2,startsOn="2026-10-26")
        val marks = setOf("2026-10-26","2026-10-30","2026-11-02","2026-11-03","2026-11-09").map(LocalDate::parse).toSet()
        assertEquals(1,habit.weekProgress(LocalDate.parse("2026-11-12"),marks))
        assertEquals(2,habit.streak(LocalDate.parse("2026-11-12"),marks))
    }
    @Test fun `canonical daily mark id is stable and scoped by habit`() {
        val habit = UUID.randomUUID().toString(); val date = LocalDate.parse("2026-11-12")
        assertEquals(markId(habit,date),markId(habit,date))
        assertNotEquals(markId(habit,date),markId(UUID.randomUUID().toString(),date))
    }
    @Test fun `date-only task and habit days use their saved time zones`() {
        val instant = Instant.parse("2026-11-23T22:30:00Z")
        val task = TaskContent("Срок", zoneId="Europe/Moscow")
        val habit = HabitContent("Бег", startsOn="2026-11-01", zoneId="America/Los_Angeles")
        assertEquals(LocalDate.parse("2026-11-24"), task.todayAt(instant))
        assertEquals(LocalDate.parse("2026-11-23"), habit.todayAt(instant))
    }
    @Test fun `sync retries transient server and throttling responses only`() {
        listOf(408, 425, 429, 500, 502, 599).forEach { assertTrue(isTransientHttpStatus(it)) }
        listOf(400, 401, 403, 404, 409, 422, 600).forEach { assertFalse(isTransientHttpStatus(it)) }
    }
    @Test fun `validation rejects ambiguous reminders bad schedules and malformed attachments`() {
        assertFails { TaskContent("Task",dueTime="12:00").validate() }
        assertFails { HabitContent("Habit",weekdays=emptySet(),startsOn="2026-11-12").validate() }
        assertFails { HabitContent("Habit",mode=HabitMode.WEEKLY_TARGET,targetPerWeek=8,startsOn="2026-11-12").validate() }
        assertFails { NoteContent("Note",attachmentIds=listOf("invalid")).validate() }
    }
    @Test fun `all content types survive wire serialization`() {
        val contents = listOf<Content>(TaskContent("Задача"),NoteContent("Заметка","текст"),HabitContent("Бег",startsOn="2026-11-12"),HabitMarkContent(UUID.randomUUID().toString(),"2026-11-12"))
        contents.forEach { assertEquals(it,WireJson.decodeFromString<Content>(WireJson.encodeToString(it))) }
    }
}
