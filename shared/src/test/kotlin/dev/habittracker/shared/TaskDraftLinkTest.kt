package dev.habittracker.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.LocalDate

class TaskDraftLinkTest {
    @Test fun `browser and shared HTTPS link give the same editable draft`() {
        val fields = "title=%D0%94%D0%B5%D0%BB%D0%BE+%26+%D0%B4%D0%BE%D0%BC&date=2026-11-20&time=18%3A00&description=One%0ATwo"
        val expected = TaskContent("Дело & дом", "One\nTwo", "2026-11-20", "18:00", "Asia/Yekaterinburg")
        assertEquals(expected, taskDraftFromLink("$TASK_DRAFT_PAGE#$fields", expected.zoneId))
        assertEquals(expected, taskDraftFromLink("$TASK_DRAFT_PAGE#kind=task&$fields", expected.zoneId))
        val direct = taskDraftFromLink("habittracker://import/task?$fields", expected.zoneId)
        assertEquals(expected, direct)
        assertFalse(direct.completed)
    }

    @Test fun `link cannot select an account or task or override duplicate fields`() {
        for (fields in listOf("title=One&id=abc", "title=One&account=abc", "title=One&completed=true", "title=One&title=Two", "title=One&time=18%3A00", "title=", "title=Bad%00text")) {
            assertFailsWith<IllegalArgumentException>(fields) { taskDraftFromLink("habittracker://import/task?$fields", "UTC") }
        }
        assertFailsWith<IllegalArgumentException> { taskDraftFromLink("https://elsewhere.example/task/#title=One", "UTC") }
    }

    @Test fun `note links open the same editable note from browser or app`() {
        val expected = NoteContent("Идея & план", "Первая строка\nВторая строка")
        val browser = "$TASK_DRAFT_PAGE#kind=note&title=%D0%98%D0%B4%D0%B5%D1%8F+%26+%D0%BF%D0%BB%D0%B0%D0%BD&text=%D0%9F%D0%B5%D1%80%D0%B2%D0%B0%D1%8F+%D1%81%D1%82%D1%80%D0%BE%D0%BA%D0%B0%0A%D0%92%D1%82%D0%BE%D1%80%D0%B0%D1%8F+%D1%81%D1%82%D1%80%D0%BE%D0%BA%D0%B0"
        val direct = "habittracker://import/note?title=%D0%98%D0%B4%D0%B5%D1%8F+%26+%D0%BF%D0%BB%D0%B0%D0%BD&text=%D0%9F%D0%B5%D1%80%D0%B2%D0%B0%D1%8F+%D1%81%D1%82%D1%80%D0%BE%D0%BA%D0%B0%0A%D0%92%D1%82%D0%BE%D1%80%D0%B0%D1%8F+%D1%81%D1%82%D1%80%D0%BE%D0%BA%D0%B0"
        assertEquals(expected, noteDraftFromLink(browser))
        assertEquals(expected, noteDraftFromLink(direct))
        assertEquals(expected, contentDraftFromLink(browser, "UTC"))
    }

    @Test fun `note links reject actions account IDs duplicate fields and wrong type`() {
        for (link in listOf(
            "$TASK_DRAFT_PAGE#kind=note&title=One&text=x&account=other",
            "$TASK_DRAFT_PAGE#kind=note&title=One&title=Two&text=x",
            "$TASK_DRAFT_PAGE#kind=task&title=One&text=x",
            "$TASK_DRAFT_PAGE#kind=habit&title=One&date=2026-11-20",
            "habittracker://import/note?title=One&text=x&attachmentId=other",
            "habittracker://import/note?title=Bad%00title&text=x"
        )) assertFailsWith<IllegalArgumentException>(link) { noteDraftFromLink(link) }
    }

    @Test fun `habit links create weekday or weekly target schedules for today's local date`() {
        val today = LocalDate.parse("2026-11-23")
        val weekdays = habitDraftFromLink(
            "habittracker://import/habit?title=%D0%91%D0%B5%D0%B3&description=%D0%9A%D0%BE%D1%80%D0%BE%D1%82%D0%BA%D0%BE&days=2%2C4",
            "Europe/Moscow",
            today,
        )
        assertEquals("Бег", weekdays.title)
        assertEquals("Коротко", weekdays.description)
        assertEquals(HabitMode.WEEKDAYS, weekdays.mode)
        assertEquals(setOf(2, 4), weekdays.weekdays)
        assertEquals(today.toString(), weekdays.startsOn)
        val routed = contentDraftFromLink(
            "habittracker://import/habit?title=%D0%91%D0%B5%D0%B3&description=%D0%9A%D0%BE%D1%80%D0%BE%D1%82%D0%BA%D0%BE&days=2%2C4",
            "Europe/Moscow",
        ) as HabitContent
        assertEquals(setOf(2, 4), routed.weekdays)

        val target = habitDraftFromLink("habittracker://import/habit?title=Stretch&target=3", "UTC", today)
        assertEquals(HabitMode.WEEKLY_TARGET, target.mode)
        assertEquals(3, target.targetPerWeek)
        assertTrue(target.weekdays.isEmpty())
        assertEquals(today.toString(), target.startsOn)
    }

    @Test fun `verified HTTPS habit links route to the same app draft`() {
        val today = LocalDate.parse("2026-11-23")
        val weekdaysLink = "$TASK_DRAFT_PAGE#kind=habit&title=%D0%91%D0%B5%D0%B3&description=%D0%9A%D0%BE%D1%80%D0%BE%D1%82%D0%BA%D0%BE&days=2%2C4"
        val weekdays = contentDraftFromLink(weekdaysLink, "Europe/Moscow", today) as HabitContent
        assertEquals("Бег", weekdays.title)
        assertEquals("Коротко", weekdays.description)
        assertEquals(setOf(2, 4), weekdays.weekdays)
        assertEquals(today.toString(), weekdays.startsOn)

        val targetLink = "$TASK_DRAFT_PAGE#kind=habit&title=Stretch&target=3"
        val target = contentDraftFromLink(targetLink, "UTC", today) as HabitContent
        assertEquals(HabitMode.WEEKLY_TARGET, target.mode)
        assertEquals(3, target.targetPerWeek)
        assertEquals(today.toString(), target.startsOn)
    }

    @Test fun `habit links reject missing or conflicting schedules and unknown fields`() {
        for (query in listOf(
            "title=Run",
            "title=Run&days=2&target=3",
            "title=Run&days=2%2C2",
            "title=Run&days=8",
            "title=Run&target=0",
            "title=Run&target=8",
            "title=Run&days=2&id=other",
            "title=Run&days=2&days=3",
            "title=",
        )) assertFailsWith<IllegalArgumentException>(query) {
            habitDraftFromLink("habittracker://import/habit?$query", "UTC")
        }
        assertFailsWith<IllegalArgumentException> {
            habitDraftFromLink("https://elsewhere.example/habit/#title=Run&days=2", "UTC")
        }
        for (fragment in listOf(
            "kind=task&title=Run&days=2",
            "kind=habit&title=Run&days=2&target=3",
            "kind=habit&title=Run&days=2&kind=habit",
        )) assertFailsWith<IllegalArgumentException>(fragment) {
            contentDraftFromLink("$TASK_DRAFT_PAGE#$fragment", "UTC")
        }
    }
}
