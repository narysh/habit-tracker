package dev.habittracker.shared

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

data class SharedHabitImport(
    val habit: HabitContent,
    val autoSave: Boolean,
    val reviewWarning: String?,
)

/** Parses only an explicit habit directive and never guesses its schedule. */
fun parseSharedHabit(
    title: String,
    details: List<String>,
    zoneId: String,
    today: LocalDate = LocalDate.now(ZoneId.of(zoneId)),
): SharedHabitImport {
    val metadata = details.map(String::trim).filter {
        it.startsWith("Дни:", ignoreCase = true) || it.startsWith("Цель:", ignoreCase = true)
    }
    val daysLine = metadata.singleOrNull()?.takeIf { it.startsWith("Дни:", ignoreCase = true) }
    val targetLine = metadata.singleOrNull()?.takeIf { it.startsWith("Цель:", ignoreCase = true) }
    val russianDays = mapOf(
        "понедельник" to 1, "вторник" to 2, "среда" to 3, "четверг" to 4,
        "пятница" to 5, "суббота" to 6, "воскресенье" to 7,
    )
    val days = daysLine?.substringAfter(':')?.trim()?.split(Regex("\\s*,\\s*|\\s+и\\s+"))
        ?.takeIf { it.isNotEmpty() && it.all { day -> day.lowercase(Locale.ROOT) in russianDays } }
        ?.map { russianDays.getValue(it.lowercase(Locale.ROOT)) }?.toSet()
    val targetMatch = targetLine?.let {
        Regex("(?i)^Цель:\\s*([1-7])\\s+(день|дня|дней)\\s+в\\s+неделю$").matchEntire(it)
    }
    val target = targetMatch?.let { match ->
        val count = match.groupValues[1].toInt()
        val noun = match.groupValues[2].lowercase(Locale.ROOT)
        count.takeIf { noun == when (count) { 1 -> "день"; in 2..4 -> "дня"; else -> "дней" } }
    }
    val scheduleValid = title.isNotBlank() && metadata.size == 1 &&
        ((days?.isNotEmpty() == true && targetLine == null) || target != null)
    val description = details.filterNot {
        it.trim().startsWith("Дни:", ignoreCase = true) || it.trim().startsWith("Цель:", ignoreCase = true)
    }.joinToString("\n").trim()
    val habit = if (target != null) {
        HabitContent(title, description, HabitMode.WEEKLY_TARGET, targetPerWeek = target, startsOn = today.toString(), zoneId = zoneId)
    } else {
        HabitContent(title, description, HabitMode.WEEKDAYS, weekdays = days ?: emptySet(), startsOn = today.toString(), zoneId = zoneId)
    }
    return SharedHabitImport(
        habit = habit,
        autoSave = scheduleValid,
        reviewWarning = if (scheduleValid) null else
            "Расписание не распознано. Укажи дни недели или цель в днях за неделю и проверь привычку перед сохранением.",
    )
}
