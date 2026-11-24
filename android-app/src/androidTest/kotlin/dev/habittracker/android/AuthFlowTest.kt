package dev.habittracker.android

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
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
            lateinit var vm: AppViewModel
            it.onActivity { activity -> vm=ViewModelProvider(activity)[AppViewModel::class.java] }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Нет аккаунта? Зарегистрироваться").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Адрес сервера").performTextClearance()
            compose.onNodeWithText("Адрес сервера").performTextInput(TestServer.url)
            compose.onNodeWithTag("login-toggle-mode").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Создай свой аккаунт").fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithTag("login-email").performScrollTo().performTextInput("not-an-email")
            compose.onNodeWithTag("login-password").performScrollTo().performTextInput(password)
            compose.onNodeWithTag("login-submit").performScrollTo().performClick()
            compose.waitUntil(10_000) { vm.busy.value }
            compose.waitUntil(20_000) { !vm.busy.value }
            assertNotNull("Registration did not expose an error: ${vm.message.value}",vm.authError.value)
            assertEquals("Некорректный email",vm.authError.value)
            compose.onNodeWithTag("auth-error").performScrollTo().assertTextEquals("Некорректный email")

            compose.onNodeWithTag("login-email").performScrollTo().performTextClearance()
            compose.onNodeWithTag("login-email").performTextInput(email)
            compose.onNodeWithTag("login-password").performScrollTo().performTextClearance()
            compose.onNodeWithTag("login-password").performTextInput("short")
            compose.onNodeWithTag("login-submit").performScrollTo().performClick()
            compose.waitUntil(10_000) { vm.busy.value }
            compose.waitUntil(20_000) { !vm.busy.value }
            assertEquals("Пароль: от 10 до 128 символов",vm.authError.value)
            compose.onNodeWithTag("auth-error").performScrollTo().assertTextEquals("Пароль: от 10 до 128 символов")

            compose.onNodeWithTag("login-password").performScrollTo().performTextClearance()
            compose.onNodeWithTag("login-password").performTextInput(password)
            compose.onNodeWithTag("login-submit").performScrollTo().performClick()
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
