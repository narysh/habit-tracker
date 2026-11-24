package dev.habittracker.android

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.habittracker.shared.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IntegrationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val app get()=ApplicationProvider.getApplicationContext<HabitApplication>()
    private fun account(): Session {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName,Manifest.permission.POST_NOTIFICATIONS)
        val session=Api(TestServer.url).post<Credentials,Session>("/api/v1/auth/register",Credentials("${UUID.randomUUID()}@device.test","test-password-123"))
        app.preferences.save(session,TestServer.url)
        return session
    }
    @Test fun automaticDiscoveryCannotMoveManualSessionOrRestoreSignedOutSession() {
        val session=Session("synthetic-token",UUID.randomUUID().toString(),"synthetic@device.test",Instant.now().plusSeconds(3600).toString())
        val manual="https://self-hosted.example"
        val verified="https://verified.example"
        app.preferences.save(session,manual,automatic=false)
        try {
            try { app.preferences.automatic(true,verified); fail("Automatic discovery must not take a session from another server") }
            catch(_: IllegalArgumentException) { }
            app.preferences.followDiscoveredServer(session,manual,verified)
            assertEquals(manual,app.preferences.url())
            assertNull(app.preferences.tokenFor(session.userId,verified))

            app.preferences.automatic(true,manual)
            val key=app.preferences.storageKey()
            app.preferences.followDiscoveredServer(session,manual,verified)
            assertEquals(session.token,app.preferences.tokenFor(session.userId,verified))
            assertEquals(key,app.preferences.storageKey())

            app.preferences.clear()
            app.preferences.followDiscoveredServer(session,verified,manual)
            assertNull(app.preferences.current())
        } finally {
            app.preferences.clear()
            app.preferences.automatic(false)
        }
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
        val api=Api(TestServer.url) { session.token }
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
        val note=WireJson.decodeFromString<Item>(repo.dao.item(noteId)!!.localJson).content as NoteContent
        val cachedCopy=File(checkNotNull(file.path))
        assertTrue(cachedCopy.exists())
        repo.save(note.copy(attachmentIds=emptyList()),noteId)
        repo.synchronize()
        assertNull(repo.dao.file(file.id))
        assertFalse(cachedCopy.exists())
        assertTrue(api.get<List<Attachment>>("/api/v1/notes/$noteId/attachments").isEmpty())
        val bob=account()
        try { repo.synchronize(); fail("Old repository must not send Alice's data with Bob's token") } catch(e: NetworkFailure) { assertEquals(401,e.status) }
        assertTrue(Api(TestServer.url) { bob.token }.get<List<Item>>("/api/v1/items").isEmpty())
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
    @Test fun todaySeparatesUndatedTasksFromDeadlineTasks() {
        account()
        val repo=app.repository()!!
        val today=LocalDate.now(ZoneId.systemDefault())
        runBlocking(Dispatchers.IO) {
            repo.save(TaskContent("Просроченная задача",dueDate=today.minusDays(1).toString()),notify=false)
            repo.save(TaskContent("Задача на сегодня",dueDate=today.toString()),notify=false)
            repo.save(TaskContent("Задача без срока"),notify=false)
            repo.save(TaskContent("Будущая задача",dueDate=today.plusDays(1).toString()),notify=false)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithText("На сегодня и просрочено").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Просроченная задача").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Задача на сегодня").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Без срока").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Задача без срока").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Будущая задача").assertDoesNotExist()
        }
        repo.database.close()
    }
    @Test fun accountDeletionRequiresConfirmationAndRemovesAccountData() {
        val session=runBlocking(Dispatchers.IO) {
            val created=account()
            val repository=app.repository()!!
            repository.save(TaskContent("Запись перед удалением"),UUID.randomUUID().toString())
            repository.synchronize()
            created
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15000) { compose.onAllNodesWithText("Сегодня").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Настройки").performClick()
            compose.onNodeWithText("Удалить аккаунт и все данные").performScrollTo().performClick()
            compose.onNodeWithText("Удалить аккаунт безвозвратно?").assertIsDisplayed()
            compose.onNodeWithText("Отмена").performClick()
            assertEquals(session.userId,app.preferences.current()?.userId)
            compose.onNodeWithContentDescription("Настройки").performClick()
            compose.onNodeWithText("Удалить аккаунт и все данные").performScrollTo().performClick()
            compose.onNodeWithText("Удалить аккаунт безвозвратно?").assertIsDisplayed()
            compose.onNodeWithText("Удалить аккаунт и данные").performClick()
            compose.waitUntil(15000) { app.preferences.current()==null }
        }
        runBlocking(Dispatchers.IO) {
            assertEquals(401,try { Api(TestServer.url){session.token}.get<List<Item>>("/api/v1/items"); 200 } catch(e: NetworkFailure) { e.status })
            assertNull(app.repository())
            assertFalse(File(app.getDatabasePath("tracker-${session.userId}-${TestServer.url.hashCode().toUInt().toString(16)}.db").path).exists())
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15000) { compose.onAllNodesWithText("Войди, чтобы синхронизировать данные").fetchSemanticsNodes().isNotEmpty() }
        }
    }
    @Test fun reconnectKeepsOfflineQueueFilesAndRejectsAnotherAccount()=runBlocking(Dispatchers.IO) {
        val session=account()
        val original=Repository(app,session,false)
        val id=UUID.randomUUID().toString()
        original.save(NoteContent("До смены адреса"),id)
        val source=File(app.cacheDir,"reconnect.txt").apply { writeText("preserved-file") }
        original.attach(id,Uri.fromFile(source))
        val operation=original.dao.pending(id)!!.operationJson
        val key=app.preferences.storageKey()
        val alias=TestServer.url + "/"
        app.preferences.reconnect(session,alias)
        val reconnected=Repository(app,session,false)
        assertEquals(operation,reconnected.dao.pending(id)!!.operationJson)
        assertEquals("preserved-file",File(reconnected.dao.files().single().path!!).readText())
        app.preferences.clear()
        app.preferences.save(session,alias)
        assertEquals(key,app.preferences.storageKey())
        val other=Api(alias).post<Credentials,Session>("/api/v1/auth/register",Credentials("${UUID.randomUUID()}@device.test","another-password-123"))
        try { app.preferences.reconnect(other,alias); fail("Another account must not inherit the local database") } catch(_: IllegalArgumentException) { }
        assertEquals(session.userId,app.preferences.current()!!.userId)
        reconnected.synchronize()
        assertNull(reconnected.dao.pending(id))
        assertTrue(reconnected.dao.files().single().uploaded)
        original.database.close(); reconnected.database.close()
    }
}
