package dev.habittracker.android

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.habittracker.shared.Credentials
import dev.habittracker.shared.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AuthFlowTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<HabitApplication>()

    @Test fun registrationValidatesCredentialsAndSignsIn() {
        app.deleteLocalAccountData()
        app.preferences.automatic(false)
        val email = "${UUID.randomUUID()}@auth.android.test"
        val password = "valid-password-123"

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Нет аккаунта? Зарегистрироваться").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Адрес сервера").performTextClearance()
            compose.onNodeWithText("Адрес сервера").performTextInput(TestServer.url)
            compose.onNodeWithText("Нет аккаунта? Зарегистрироваться").performClick()

            compose.onNodeWithText("Email").performTextInput("not-an-email")
            compose.onNodeWithText("Пароль — от 10 символов").performTextInput(password)
            compose.onNodeWithText("Создать аккаунт").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Некорректный email").fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithText("Email").performTextClearance()
            compose.onNodeWithText("Email").performTextInput(email)
            compose.onNodeWithText("Пароль — от 10 символов").performTextClearance()
            compose.onNodeWithText("Пароль — от 10 символов").performTextInput("short")
            compose.onNodeWithText("Создать аккаунт").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Пароль: от 10 до 128 символов").fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithText("Пароль — от 10 символов").performTextClearance()
            compose.onNodeWithText("Пароль — от 10 символов").performTextInput(password)
            compose.onNodeWithText("Создать аккаунт").performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("Сегодня").fetchSemanticsNodes().isNotEmpty() && app.preferences.current() != null
            }

            val session: Session = app.preferences.current()!!
            assertEquals(email, session.email)
            assertNotNull(session.token)
            assertEquals(email, Api(TestServer.url).post<Credentials, Session>(
                "/api/v1/auth/login",
                Credentials(email, password),
            ).email)
        }
    }
}
