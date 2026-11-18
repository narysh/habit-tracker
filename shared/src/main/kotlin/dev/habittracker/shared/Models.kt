package dev.habittracker.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.UUID

val WireJson = Json { encodeDefaults = true; ignoreUnknownKeys = true; classDiscriminator = "kind" }
@Serializable data class SocialProviderInfo(val id: String,val title: String,val enabled: Boolean,val linked: Boolean = false)
@Serializable data class SocialStart(val provider: String,val challenge: String,val link: Boolean = false)
@Serializable data class SocialAttempt(val id: String,val authorizationUrl: String)
@Serializable data class SocialPoll(val id: String,val verifier: String)
@Serializable data class SocialResult(val status: String,val session: Session? = null,val message: String? = null)
@Serializable data class ChatGptLinkRequest(val code: String)
@Serializable data class ChatGptLinkPreview(val scopes: Set<String>, val expiresAt: String)
@Serializable data class ChatGptConnection(val available: Boolean, val connected: Boolean)

@Serializable data class Organization(val projectId: String?=null,val goalId: String?=null,val tags: Set<String> = emptySet(),val linkedIds: Set<String> = emptySet())
@Serializable sealed interface Content { val title: String; val organization: Organization get()=Organization() }
@Serializable @SerialName("task") data class TaskContent(
    override val title: String,
    val description: String = "",
    val dueDate: String? = null,
    val dueTime: String? = null,
    val zoneId: String = "Europe/Moscow",
    val reminderMinutes: Int? = null,
    val completed: Boolean = false,
    val completedAt: String? = null,
    override val organization: Organization = Organization(),
    val completionDateUnknown: Boolean = false,
) : Content
@Serializable @SerialName("note") data class NoteContent(
    override val title: String,
    val text: String = "",
    val attachmentIds: List<String> = emptyList(),
    override val organization: Organization = Organization(),
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
    override val organization: Organization = Organization(),
) : Content

@Serializable @SerialName("project") data class ProjectContent(override val title: String,val description: String="",val isGoal: Boolean=false) : Content
@Serializable data class PlanMove(val itemId: String,val expectedVersion: Long,val dueDate: String,val reason: String="")
@Serializable @SerialName("week_plan") data class WeekPlanContent(override val title: String,val weekStart: String,val summary: String,val moves: List<PlanMove>,val applied: Boolean=false) : Content
@Serializable data class ApplyPlanRequest(val expectedVersion: Long)
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
    val org=organization
    org.projectId?.let(UUID::fromString);org.goalId?.let(UUID::fromString)
    require(org.tags.size<=30 && org.tags.all { it.isNotBlank() && it.length<=50 && it==it.trim() }) { "Теги: до 30 значений по 50 символов" }
    require(org.linkedIds.size<=100);org.linkedIds.forEach(UUID::fromString)
    when (this) {
        is TaskContent -> {
            require(description.length <= 100_000)
            ZoneId.of(zoneId)
            dueDate?.let(LocalDate::parse); dueTime?.let(LocalTime::parse)
            require(dueTime == null || dueDate != null) { "Для времени нужен день" }
            require(reminderMinutes == null || (dueDate != null && dueTime != null && reminderMinutes in 0..10080)) { "Напоминание требует даты и времени" }
            completedAt?.let { require(completed);Instant.parse(it) }
            require(!completionDateUnknown || (completed && completedAt==null))
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
        is ProjectContent -> require(description.length<=100_000)
        is WeekPlanContent -> {
            val monday=LocalDate.parse(weekStart);require(monday.dayOfWeek==DayOfWeek.MONDAY)
            require(summary.length<=100_000 && moves.size<=100 && moves.map {it.itemId}.distinct().size==moves.size)
            moves.forEach { UUID.fromString(it.itemId);require(it.expectedVersion>0 && it.reason.length<=2000);require(LocalDate.parse(it.dueDate) in monday..monday.plusDays(6)) }
        }
    }
}
fun markId(habitId: String, date: LocalDate): String = UUID.nameUUIDFromBytes("habit:$habitId:$date".toByteArray(Charsets.UTF_8)).toString()
fun Content.typeName(): String = when (this) { is TaskContent -> "task"; is NoteContent -> "note"; is HabitContent -> "habit"; is HabitMarkContent -> "habit_mark"; is ProjectContent->"project";is WeekPlanContent->"week_plan" }
fun Content.withOrganization(value: Organization): Content=when(this){is TaskContent->copy(organization=value);is NoteContent->copy(organization=value);is HabitContent->copy(organization=value);else->this}
fun Content.searchableText(): String = listOf(title,when(this){is TaskContent->description;is NoteContent->text;is HabitContent->description;is ProjectContent->description;is WeekPlanContent->summary;else->""},organization.tags.joinToString(" ")).joinToString(" ")

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
