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
}
