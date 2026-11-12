package dev.habittracker.probe

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

@Serializable
data class ProbeTask(
    val id: String,
    val title: String,
    val version: Int = 1,
    val deleted: Boolean = false,
    val createdAt: String,
)

@Serializable
private data class Snapshot(
    val tasks: List<ProbeTask> = emptyList(),
    val operations: Map<String, String> = emptyMap(),
)

/** Atomic snapshot, only for disposable integration data. The main backend will use PostgreSQL. */
class TaskStore(private val path: Path) {
    private var snapshot = if (Files.exists(path)) Json.decodeFromString<Snapshot>(Files.readString(path)) else Snapshot()

    @Synchronized fun list(): List<ProbeTask> = snapshot.tasks.filterNot { it.deleted }

    @Synchronized fun create(title: String, operationId: String): ProbeTask {
        require(title.isNotBlank() && title.length <= 300) { "title must contain 1..300 characters" }
        UUID.fromString(operationId)
        snapshot.operations[operationId]?.let { id ->
            val task = snapshot.tasks.single { it.id == id }
            require(task.title == title.trim()) { "operation_id already used with different arguments" }
            return task
        }
        require(snapshot.tasks.size < 1000) { "Probe limit: 1000 tasks" }
        val task = ProbeTask(UUID.randomUUID().toString(), title.trim(), createdAt = Instant.now().toString())
        save(snapshot.copy(tasks = snapshot.tasks + task, operations = snapshot.operations + (operationId to task.id)))
        return task
    }

    @Synchronized fun delete(id: String, expectedVersion: Int): ProbeTask {
        val task = snapshot.tasks.singleOrNull { it.id == id } ?: error("Task not found")
        require(task.version == expectedVersion) { "Version conflict; call get_tasks again" }
        require(!task.deleted) { "Task already deleted" }
        val deleted = task.copy(deleted = true, version = task.version + 1)
        save(snapshot.copy(tasks = snapshot.tasks.map { if (it.id == id) deleted else it }))
        return deleted
    }

    private fun save(next: Snapshot) {
        val target = path.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "probe-", ".tmp")
        try {
            Files.writeString(temp, Json.encodeToString(next))
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            snapshot = next
        } finally { Files.deleteIfExists(temp) }
    }
}
