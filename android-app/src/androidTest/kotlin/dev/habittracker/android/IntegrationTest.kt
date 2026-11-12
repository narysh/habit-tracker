package dev.habittracker.android

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.habittracker.shared.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IntegrationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val app get()=ApplicationProvider.getApplicationContext<HabitApplication>()
    private fun account(): Session {
        val session=Api("http://10.0.2.2:8081").post<Credentials,Session>("/api/v1/auth/register",Credentials("${UUID.randomUUID()}@device.test","test-password-123"))
        app.preferences.save(session,"http://10.0.2.2:8081")
        return session
    }
    @Test fun offlinePersistenceSyncConflictFilesAndAccountIsolation()=runBlocking(Dispatchers.IO) {
        val session=account(); var repo=Repository(app,session,false)
        val id=UUID.randomUUID().toString()
        repo.save(TaskContent("Локальная задача"),id)
        assertNotNull(repo.dao.pending(id))
        repo.database.close(); repo=Repository(app,session,false)
        assertEquals("Локальная задача",WireJson.decodeFromString<Item>(repo.dao.item(id)!!.localJson).content.title)
        repo.synchronize()
        val remote=WireJson.decodeFromString<Item>(repo.dao.item(id)!!.serverJson!!)
        assertEquals(1L,remote.version)
        repo.save(TaskContent("Правка телефона"),id)
        val api=Api("http://10.0.2.2:8081") { session.token }
        api.post<Mutation,MutationResult>("/api/v1/mutations",Mutation(UUID.randomUUID().toString(),id,remote.version,TaskContent("Правка AI")))
        repo.synchronize()
        val conflict=repo.dao.conflict(id)!!
        assertEquals("Правка телефона",WireJson.decodeFromString<Item>(conflict.localJson).content.title)
        assertEquals("Правка AI",WireJson.decodeFromString<Item>(conflict.remoteJson!!).content.title)
        repo.resolve(conflict,true); repo.synchronize()
        assertEquals("Правка телефона",api.get<Item>("/api/v1/items/$id").content.title)
        val cancelledId=UUID.randomUUID().toString()
        repo.save(TaskContent("Удалена до сети"),cancelledId)
        repo.save(TaskContent("Удалена до сети"),cancelledId,true)
        repo.synchronize()
        assertTrue(api.get<Item>("/api/v1/items/$cancelledId").deleted)
        val noteId=UUID.randomUUID().toString()
        repo.save(NoteContent("Файл","Содержимое"),noteId)
        val source=File(app.cacheDir,"attachment-test.txt").apply { writeText("attachment-roundtrip") }
        repo.attach(noteId,Uri.fromFile(source)); repo.synchronize()
        val file=repo.dao.files().single { it.noteId==noteId }
        assertTrue(file.uploaded)
        assertEquals("attachment-roundtrip",api.request("/api/v1/attachments/${file.id}").toString(Charsets.UTF_8))
        val bob=account()
        try { repo.synchronize(); fail("Old repository must not send Alice's data with Bob's token") } catch(e: NetworkFailure) { assertEquals(401,e.status) }
        assertTrue(Api("http://10.0.2.2:8081") { bob.token }.get<List<Item>>("/api/v1/items").isEmpty())
        repo.database.close()
    }
    @Test fun composeCanCreateTaskAndSurviveActivityRecreation() {
        account()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(15000) { compose.onAllNodesWithText("Сегодня").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Добавить").performClick()
            compose.onNodeWithText("Задачу").performClick()
            compose.onNodeWithText("Название").performTextInput("Задача из Compose")
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Задача из Compose").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Удалить задачу").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Удалить задачу").performClick()
            compose.onNodeWithText("Отмена").performClick()
            compose.onNodeWithText("Задача из Compose").assertIsDisplayed()
            scenario.recreate()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Задача из Compose").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Задача из Compose").assertIsDisplayed()
        }
    }
}
