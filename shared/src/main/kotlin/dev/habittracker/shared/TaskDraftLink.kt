package dev.habittracker.shared

import java.net.URI
import java.net.URLDecoder
import java.time.LocalDate
import java.time.ZoneId

const val TASK_DRAFT_PAGE = "https://narysh.github.io/habit-tracker/task/"

private fun draftFields(link: String, directPath: String, allowed: Set<String>): Pair<Map<String, String>, Boolean> {
    require(link.length <= 8192) { "Ссылка на черновик слишком длинная" }
    val uri = URI.create(link)
    val isWeb = link.startsWith("$TASK_DRAFT_PAGE#")
    val encodedFields = when {
        uri.scheme == "habittracker" && uri.rawAuthority == "import" && uri.rawPath == directPath && uri.rawFragment == null -> uri.rawQuery
        isWeb -> uri.rawFragment
        else -> null
    }
    requireNotNull(encodedFields) { "Неизвестная ссылка Habit Tracker" }
    val fields = linkedMapOf<String, String>()
    for (part in encodedFields.split('&')) {
        val key = part.substringBefore('=')
        require(key in allowed && key !in fields) { "Неизвестное или повторяющееся поле ссылки" }
        fields[key] = URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8.name())
    }
    return fields to isWeb
}

/** A link supplies only editable task fields. Account, record ID and actions are never taken from it. */
fun taskDraftFromLink(link: String, zoneId: String): TaskContent {
    val isWebLink = link.startsWith("$TASK_DRAFT_PAGE#")
    val allowed = setOf("title", "description", "date", "time") + if (isWebLink) setOf("kind") else emptySet()
    val (fields, isWeb) = draftFields(link, "/task", allowed)
    if (isWeb) require(fields["kind"] == null || fields["kind"] == "task") { "В ссылке указан не черновик задачи" }
    val title = fields["title"].orEmpty().trim()
    val description = fields["description"].orEmpty()
    require(title.none(Char::isISOControl) && description.none { it.isISOControl() && it != '\n' && it != '\t' } && description.length <= 1000) {
        "В ссылке слишком длинное описание или недопустимые символы"
    }
    val date = fields["date"]?.takeIf(String::isNotEmpty)
    val time = fields["time"]?.takeIf(String::isNotEmpty)
    require(date == null || Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(date)) { "Нужна дата в формате ГГГГ-ММ-ДД" }
    require(time == null || Regex("[0-9]{2}:[0-9]{2}").matches(time)) { "Нужно время в формате ЧЧ:ММ" }
    return TaskContent(title, description, date, time, zoneId).also { it.validate() }
}

/** Notes opened from ChatGPT are editable previews; links never save records directly. */
fun noteDraftFromLink(link: String): NoteContent {
    val isWeb = link.startsWith("$TASK_DRAFT_PAGE#")
    val allowed = if (isWeb) setOf("kind", "title", "text") else setOf("title", "text")
    val (fields, fromWeb) = draftFields(link, "/note", allowed)
    if (fromWeb) require(fields["kind"] == "note") { "В ссылке указан не черновик заметки" }
    val note = NoteContent(fields["title"].orEmpty().trim(), fields["text"].orEmpty())
    require(note.title.none(Char::isISOControl) && note.text.none { it.isISOControl() && it != '\n' && it != '\t' }) {
        "В ссылке есть недопустимые символы"
    }
    note.validate()
    return note
}

/** A habit link supplies only its schedule. The app chooses today's local start date and zone. */
fun habitDraftFromLink(link: String, zoneId: String, today: LocalDate = LocalDate.now(ZoneId.of(zoneId))): HabitContent {
    val isWebLink = link.startsWith("$TASK_DRAFT_PAGE#")
    val allowed = setOf("title", "description", "days", "target") + if (isWebLink) setOf("kind") else emptySet()
    val (fields, isWeb) = draftFields(link, "/habit", allowed)
    if (isWeb) require(fields["kind"] == "habit") { "В ссылке указан не черновик привычки" }
    val title = fields["title"].orEmpty().trim()
    val description = fields["description"].orEmpty()
    require(title.none(Char::isISOControl) && description.none { it.isISOControl() && it != '\n' && it != '\t' }) {
        "В ссылке есть недопустимые символы"
    }
    require(("days" in fields) xor ("target" in fields)) { "Укажи дни недели или цель за неделю" }
    val habit = if ("days" in fields) {
        val days = fields.getValue("days").split(',').map { it.toIntOrNull() ?: 0 }
        require(days.isNotEmpty() && days.distinct().size == days.size && days.all { it in 1..7 }) {
            "Дни недели должны быть числами от 1 до 7 без повторов"
        }
        HabitContent(title, description, HabitMode.WEEKDAYS, days.toSet(), startsOn = today.toString(), zoneId = zoneId)
    } else {
        val target = requireNotNull(fields.getValue("target").toIntOrNull()) { "Цель должна быть числом от 1 до 7" }
        require(target in 1..7) { "Цель должна быть от 1 до 7 дней в неделю" }
        HabitContent(title, description, HabitMode.WEEKLY_TARGET, emptySet(), target, today.toString(), zoneId)
    }
    habit.validate()
    return habit
}

fun contentDraftFromLink(
    link: String,
    zoneId: String,
    today: LocalDate = LocalDate.now(ZoneId.of(zoneId)),
): Content {
    val uri = URI.create(link)
    val isHabit = (uri.scheme == "habittracker" && uri.rawAuthority == "import" && uri.rawPath == "/habit") ||
        (link.startsWith("$TASK_DRAFT_PAGE#") && uri.rawFragment.orEmpty().split('&').any {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8.name()) == "kind" &&
                URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8.name()) == "habit"
        })
    if (isHabit) {
        return habitDraftFromLink(link, zoneId, today)
    }
    val isNote = (uri.scheme == "habittracker" && uri.rawAuthority == "import" && uri.rawPath == "/note") ||
        (link.startsWith("$TASK_DRAFT_PAGE#") && uri.rawFragment.orEmpty().split('&').any { it.substringBefore('=') == "kind" && it.substringAfter('=', "") == "note" })
    return if (isNote) noteDraftFromLink(link) else taskDraftFromLink(link, zoneId)
}
