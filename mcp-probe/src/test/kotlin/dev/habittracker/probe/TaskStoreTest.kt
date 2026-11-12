package dev.habittracker.probe

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class TaskStoreTest {
    @Test fun `retries persist without duplicate tasks`() {
        val path = testConfig().dataFile; val store = TaskStore(path); val operation = UUID.randomUUID().toString()
        val task = store.create("  Проверить MCP  ", operation)
        assertEquals(task, store.create("Проверить MCP", operation))
        val restored = TaskStore(path)
        assertEquals(listOf(task), restored.list())
        assertEquals(task, restored.create("Проверить MCP", operation))
        assertFails { restored.create("Другой текст", operation) }
    }
    @Test fun `deletion checks version and persists tombstone`() {
        val path = testConfig().dataFile; val store = TaskStore(path)
        val task = store.create("Delete test", UUID.randomUUID().toString())
        assertFails { store.delete(task.id, 5) }; assertEquals(1, store.list().size)
        val deleted = store.delete(task.id, 1)
        assertTrue(deleted.deleted); assertEquals(2, deleted.version)
        assertTrue(TaskStore(path).list().isEmpty())
        assertFails { store.delete(task.id, 1) }
    }
    @Test fun `invalid creation does not change store`() {
        val store = TaskStore(testConfig().dataFile)
        for (title in listOf("", " ", "a".repeat(301))) assertFails { store.create(title, UUID.randomUUID().toString()) }
        assertFails { store.create("valid title", "not-a-uuid") }
        assertTrue(store.list().isEmpty())
    }
}
