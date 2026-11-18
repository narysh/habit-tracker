package dev.habittracker.shared

import java.net.URI
import java.net.URLDecoder

const val TASK_DRAFT_PAGE = "https://narysh.github.io/habit-tracker/task/"

/** A link supplies only editable task fields. Account, record ID and actions are never taken from it. */
fun taskDraftFromLink(link: String, zoneId: String): TaskContent {
    require(link.length <= 8192) { "Ссылка на задачу слишком длинная" }
    val uri = URI.create(link)
    val encodedFields = when {
        uri.scheme == "habittracker" && uri.rawAuthority == "import" && uri.rawPath == "/task" && uri.rawFragment == null -> uri.rawQuery
        link.startsWith("$TASK_DRAFT_PAGE#") -> uri.rawFragment
        else -> null
    }
    requireNotNull(encodedFields) { "Неизвестная ссылка Habit Tracker" }
    val fields = mutableMapOf<String, String>()
    for (part in encodedFields.split('&')) {
        val key = part.substringBefore('=')
        require(key in setOf("title", "description", "date", "time") && key !in fields) { "Неизвестное или повторяющееся поле ссылки" }
        fields[key] = URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8.name())
    }
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
