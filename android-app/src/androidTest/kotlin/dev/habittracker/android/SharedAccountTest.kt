package dev.habittracker.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.habittracker.shared.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Optional end-to-end fixture: host script places credentials into the debug app's private files. */
@RunWith(AndroidJUnit4::class)
class SharedAccountTest {
    @Test fun readsTaskHabitAndNoteCreatedThroughMcp()=runBlocking(Dispatchers.IO) {
        val app=ApplicationProvider.getApplicationContext<HabitApplication>()
        val fixture=File(app.filesDir,"mcp-fixture.json")
        assumeTrue("Run scripts/Test-SharedAccount.ps1 to supply the local fixture",fixture.exists())
        val credentials=WireJson.decodeFromString<Credentials>(fixture.readText())
        fixture.delete()
        val session=Api(TestServer.url).post<Credentials,Session>("/api/v1/auth/login",credentials)
        app.preferences.save(session,TestServer.url)
        val repo=Repository(app,session,false)
        repo.synchronize()
        val contents=repo.dao.items().map {WireJson.decodeFromString<Item>(it.localJson)}.filterNot {it.deleted}.map {it.content}
        assertTrue(contents.any {it is TaskContent && it.title=="Задача из MCP"})
        assertTrue(contents.any {it is HabitContent && it.title=="Чтение из MCP"})
        assertTrue(contents.any {it is NoteContent && it.title=="Заметка из MCP"})
        repo.database.close()
    }
}
