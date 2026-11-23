package dev.habittracker.shared

import org.junit.jupiter.api.Test
import kotlin.test.*
import java.time.LocalDate

class HabitShareParserTest {
    private val today = LocalDate.parse("2026-11-23")

    @Test fun parsesExplicitWeekdaysAndPreservesDescription() {
        val result = parseSharedHabit("Бег", listOf("Описание привычки", "Дни: вторник, четверг"), "Europe/Moscow", today)

        assertTrue(result.autoSave, result.toString())
        assertNull(result.reviewWarning)
        assertEquals(HabitMode.WEEKDAYS, result.habit.mode)
        assertEquals(setOf(2, 4), result.habit.weekdays)
        assertEquals("Описание привычки", result.habit.description)
        assertEquals(today.toString(), result.habit.startsOn)
    }

    @Test fun parsesWeeklyTargetWithRussianNounForms() {
        for ((count, noun) in listOf(1 to "день", 2 to "дня", 4 to "дня", 5 to "дней", 7 to "дней")) {
            val result = parseSharedHabit("Растяжка", listOf("Цель: $count $noun в неделю"), "Europe/Moscow", today)
            assertTrue(result.autoSave, "$count $noun")
            assertEquals(count, result.habit.targetPerWeek)
            assertEquals(HabitMode.WEEKLY_TARGET, result.habit.mode)
        }
    }

    @Test fun refusesUnknownMalformedOrAmbiguousSchedules() {
        val unknown = parseSharedHabit("Бег", listOf("Дни: когда будет время"), "Europe/Moscow", today)
        val mismatchedNoun = parseSharedHabit("Бег", listOf("Цель: 3 дней в неделю"), "Europe/Moscow", today)
        val ambiguous = parseSharedHabit("Бег", listOf("Дни: вторник", "Цель: 2 дня в неделю"), "Europe/Moscow", today)

        listOf(unknown, mismatchedNoun, ambiguous).forEach {
            assertFalse(it.autoSave)
            assertNotNull(it.reviewWarning)
            assertTrue(it.habit.weekdays.isEmpty())
        }
    }

    @Test fun acceptsCaseAndConjunctionButDoesNotGuessAWeekday() {
        val result = parseSharedHabit("Бег", listOf("ДНИ: ВТОРНИК и ЧЕТВЕРГ"), "Europe/Moscow", today)
        assertTrue(result.autoSave)
        assertEquals(setOf(2, 4), result.habit.weekdays)
    }
}
