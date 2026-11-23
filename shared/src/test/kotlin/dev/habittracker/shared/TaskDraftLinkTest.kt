package dev.habittracker.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TaskDraftLinkTest {
    @Test fun `browser and shared HTTPS link give the same editable draft`() {
        val fields = "title=%D0%94%D0%B5%D0%BB%D0%BE+%26+%D0%B4%D0%BE%D0%BC&date=2026-11-20&time=18%3A00&description=One%0ATwo"
        val expected = TaskContent("Дело & дом", "One\nTwo", "2026-11-20", "18:00", "Asia/Yekaterinburg")
        assertEquals(expected, taskDraftFromLink("$TASK_DRAFT_PAGE#$fields", expected.zoneId))
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
            "habittracker://import/note?title=One&text=x&attachmentId=other",
            "habittracker://import/note?title=Bad%00title&text=x"
        )) assertFailsWith<IllegalArgumentException>(link) { noteDraftFromLink(link) }
    }
}
