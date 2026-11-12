package dev.habittracker.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.UUID

val WireJson = Json { encodeDefaults = true; ignoreUnknownKeys = true; classDiscriminator = "kind" }

@Serializable sealed interface Content { val title: String }
@Serializable @SerialName("task") data class TaskContent(
    override val title: String,
    val description: String = "",
    val dueDate: String? = null,
    val dueTime: String? = null,
    val zoneId: String = "Europe/Moscow",
    val reminderMinutes: Int? = null,
    val completed: Boolean = false,
) : Content
@Serializable @SerialName("note") data class NoteContent(
    override val title: String,
    val text: String = "",
    val attachmentIds: List<String> = emptyList(),
) : Content
@Serializable enum class HabitMode { WEEKDAYS, WEEKLY_TARGET }
@Serializable @SerialName("habit") data class HabitContent(
    override val title: String,
    val description: String = "",
    val mode: HabitMode = HabitMode.WEEKDAYS,
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val targetPerWeek: Int = 3,
    val startsOn: String,
    val zoneId: String = "Europe/Moscow",
    val reminderTime: String? = null,
) : Content
@Serializable @SerialName("habit_mark") data class HabitMarkContent(
    val habitId: String,
    val date: String,
    val completed: Boolean = true,
    override val title: String = "",
) : Content

@Serializable data class Item(
    val id: String,
    val version: Long,
    val content: Content,
    val deleted: Boolean = false,
    val updatedAt: String,
    val sequence: Long = 0,
)
@Serializable data class Mutation(
    val operationId: String,
    val itemId: String,
    val baseVersion: Long,
    val content: Content,
    val deleted: Boolean = false,
)
@Serializable enum class MutationStatus { APPLIED, CONFLICT, REJECTED }
@Serializable data class MutationResult(val operationId: String, val status: MutationStatus, val item: Item? = null, val message: String? = null)
@Serializable data class SyncRequest(val mutations: List<Mutation> = emptyList(), val cursor: Long = 0)
@Serializable data class SyncResponse(val results: List<MutationResult>, val changes: List<Item>, val nextCursor: Long, val hasMore: Boolean)
@Serializable data class Credentials(val email: String, val password: String)
@Serializable data class Session(val token: String, val userId: String, val email: String, val expiresAt: String)
@Serializable data class ApiError(val error: String)
@Serializable data class Attachment(val id: String, val noteId: String, val name: String, val mediaType: String, val size: Long)

fun Content.validate() {
    if (this !is HabitMarkContent) require(title.isNotBlank() && title.length <= 300) { "Название: от 1 до 300 символов" }
    when (this) {
        is TaskContent -> {
            require(description.length <= 100_000)
            ZoneId.of(zoneId)
            dueDate?.let(LocalDate::parse); dueTime?.let(LocalTime::parse)
            require(dueTime == null || dueDate != null) { "Для времени нужен день" }
            require(reminderMinutes == null || (dueDate != null && dueTime != null && reminderMinutes in 0..10080)) { "Напоминание требует даты и времени" }
        }
        is NoteContent -> {
            require(text.length <= 1_000_000); require(attachmentIds.size <= 30)
            attachmentIds.forEach(UUID::fromString)
            require(attachmentIds.distinct().size == attachmentIds.size)
        }
        is HabitContent -> {
            LocalDate.parse(startsOn); ZoneId.of(zoneId); reminderTime?.let(LocalTime::parse)
            require(description.length <= 100_000)
            require(targetPerWeek in 1..7)
            require(weekdays.all { it in 1..7 })
            if (mode == HabitMode.WEEKDAYS) require(weekdays.isNotEmpty()) { "Выбери дни недели" }
        }
        is HabitMarkContent -> { UUID.fromString(habitId); LocalDate.parse(date); require(title.isEmpty()) }
    }
}
fun markId(habitId: String, date: LocalDate): String = UUID.nameUUIDFromBytes("habit:$habitId:$date".toByteArray(Charsets.UTF_8)).toString()
fun Content.typeName(): String = when (this) { is TaskContent -> "task"; is NoteContent -> "note"; is HabitContent -> "habit"; is HabitMarkContent -> "habit_mark" }

fun HabitContent.isDue(date: LocalDate): Boolean = date >= LocalDate.parse(startsOn) &&
    (mode == HabitMode.WEEKLY_TARGET || date.dayOfWeek.value in weekdays)
fun HabitContent.weekProgress(date: LocalDate, marks: Set<LocalDate>): Int {
    val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return marks.count { it >= monday && it <= monday.plusDays(6) && it >= LocalDate.parse(startsOn) }
}
/** Weekday series counts scheduled days. Weekly-target series counts completed calendar weeks. */
fun HabitContent.streak(today: LocalDate, marks: Set<LocalDate>): Int {
    val start = LocalDate.parse(startsOn)
    if (start > today) return 0
    var count = 0
    if (mode == HabitMode.WEEKLY_TARGET) {
        var monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (weekProgress(today, marks) < targetPerWeek) monday = monday.minusWeeks(1)
        while (monday.plusDays(6) >= start) {
            if (weekProgress(monday, marks) < targetPerWeek) break
            count++; monday = monday.minusWeeks(1)
        }
    } else {
        var date = today
        if (isDue(date) && date !in marks) date = date.minusDays(1)
        while (date >= start) {
            if (isDue(date)) { if (date !in marks) break; count++ }
            date = date.minusDays(1)
        }
    }
    return count
}
