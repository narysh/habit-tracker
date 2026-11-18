package dev.habittracker.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.habittracker.shared.ChatGptLinkPreview
import dev.habittracker.shared.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ChatGptLinkDialog(vm: AppViewModel, account: Session, close: () -> Unit) {
    var code by remember(account) { mutableStateOf("") }
    var preview by remember(account) { mutableStateOf<ChatGptLinkPreview?>(null) }
    var approved by remember(account) { mutableStateOf(false) }
    var loading by remember(account) { mutableStateOf(false) }
    var error by remember(account) { mutableStateOf<String?>(null) }
    val coroutine = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!loading) close() },
        title = { Text("Подключить ChatGPT") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val permissions = preview
                when {
                    approved -> Text("Вернись на страницу подключения и нажми «Завершить подключение».")
                    permissions != null -> {
                        Text("Разрешить ChatGPT работать с задачами аккаунта ${account.email}?")
                        if ("tracker:read" in permissions.scopes) Text("• Просматривать список задач")
                        if ("tracker:write" in permissions.scopes) Text("• Создавать задачи и отмечать их выполнение")
                    }
                    else -> {
                        Text("Открой Habit Tracker в разделе плагинов ChatGPT, нажми «Подключить» и вставь код со страницы подключения. Используй только код для запроса, который начал сам.")
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.trim().take(16); error = null },
                            label = { Text("Код подключения") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                            enabled = !loading,
                            singleLine = true
                        )
                    }
                }
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && (approved || code.length == 16),
                onClick = {
                    if (approved) close()
                    else {
                        loading = true
                        error = null
                        coroutine.launch {
                            try {
                                if (preview == null) preview = vm.previewChatGptLink(code, account)
                                else {
                                    vm.approveChatGptLink(code, account)
                                    approved = true
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "Не удалось подтвердить подключение"
                            } finally {
                                loading = false
                            }
                        }
                    }
                }
            ) { Text(if (approved) "Готово" else if (preview == null) "Проверить код" else "Разрешить доступ") }
        },
        dismissButton = {
            if (!approved) TextButton(onClick = close, enabled = !loading) { Text("Отмена") }
        }
    )
}
