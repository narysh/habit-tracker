package dev.habittracker.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.habittracker.shared.ChatGptConnection
import dev.habittracker.shared.ChatGptLinkPreview
import dev.habittracker.shared.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ChatGptLinkDialog(vm: AppViewModel, account: Session, close: () -> Unit) {
    var code by remember(account) { mutableStateOf("") }
    var preview by remember(account) { mutableStateOf<ChatGptLinkPreview?>(null) }
    var approved by remember(account) { mutableStateOf(false) }
    var connection by remember(account) { mutableStateOf<ChatGptConnection?>(null) }
    var confirmingDisconnect by remember(account) { mutableStateOf(false) }
    var loading by remember(account) { mutableStateOf(true) }
    var error by remember(account) { mutableStateOf<String?>(null) }
    val coroutine = rememberCoroutineScope()
    suspend fun runRequest(block: suspend () -> Unit) {
        loading = true
        error = null
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Не удалось связаться с сервером Habit Tracker"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(account) { runRequest { connection = vm.chatGptConnection(account) } }
    val status = connection
    val validCode = code.length == 16 && code.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
    val actionLabel = when {
        approved || status?.available == false -> "Готово"
        status == null -> "Повторить"
        confirmingDisconnect -> "Отключить доступ"
        status.connected -> "Отключить ChatGPT"
        preview != null -> "Разрешить доступ"
        else -> "Проверить код"
    }

    AlertDialog(
        onDismissRequest = { if (!loading) close() },
        title = { Text("Подключение ChatGPT") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val permissions = preview
                when {
                    approved -> Text("Вернись на страницу подключения и нажми «Завершить подключение».")
                    status == null -> if (loading) Text("Проверяем подключение…") else Unit
                    !status.available -> Text("Подключение ChatGPT ещё не доступно на этом сервере.")
                    confirmingDisconnect -> Text("Отозвать доступ ChatGPT к задачам аккаунта ${account.email}? Вход в Habit Tracker сохранится.")
                    status.connected -> Text("ChatGPT подключён к аккаунту ${account.email}.")
                    permissions != null -> {
                        Text("Разрешить ChatGPT работать с задачами аккаунта ${account.email}?")
                        if ("tracker:read" in permissions.scopes) Text("• Просматривать список задач")
                        if ("tracker:write" in permissions.scopes) Text("• Создавать задачи и отмечать их выполнение")
                    }
                    else -> {
                        Text("Открой Habit Tracker в разделе плагинов ChatGPT, нажми «Подключить» и вставь код со страницы подключения. Используй только код для запроса, который начал сам.")
                        OutlinedTextField(
                            value = code,
                            onValueChange = { if (it.trim().length <= 16) code = it.trim(); error = null },
                            label = { Text("Код подключения") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                            enabled = !loading,
                            singleLine = true
                        )
                    }
                }
                Text(
                    "В мобильном ChatGPT выбери «Поделиться → Habit Tracker»: отправленный текст сразу сохранится как задача, заметка или привычка. Для привычки укажи расписание строкой «Дни: вторник, четверг» или «Цель: 3 дня в неделю».",
                    style = MaterialTheme.typography.bodySmall
                )
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && (approved || status?.available != true || status.connected || validCode),
                onClick = {
                    when {
                        approved || status?.available == false -> close()
                        confirmingDisconnect -> coroutine.launch {
                            runRequest {
                                vm.disconnectChatGpt(account)
                                connection = ChatGptConnection(available = true, connected = false)
                                confirmingDisconnect = false
                            }
                        }
                        status?.connected == true -> confirmingDisconnect = true
                        else -> coroutine.launch {
                            runRequest {
                                when {
                                    status == null -> connection = vm.chatGptConnection(account)
                                    preview == null -> preview = vm.previewChatGptLink(code, account)
                                    else -> {
                                        vm.approveChatGptLink(code, account)
                                        approved = true
                                    }
                                }
                            }
                        }
                    }
                }
            ) { Text(actionLabel) }
        },
        dismissButton = {
            if (!approved) TextButton(
                onClick = { if (confirmingDisconnect) { confirmingDisconnect = false; error = null } else close() },
                enabled = !loading
            ) { Text(if (confirmingDisconnect || status?.connected != true) "Отмена" else "Закрыть") }
        }
    )
}
