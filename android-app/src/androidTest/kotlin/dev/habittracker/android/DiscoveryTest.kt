package dev.habittracker.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.habittracker.shared.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.util.UUID

/** Internet acceptance test; requires Start-Internet and the matching signed server. */
@RunWith(AndroidJUnit4::class)
class DiscoveryTest {
    @Test fun discoversSignedPublicServerAndKeepsOfflineChanges()=runBlocking(Dispatchers.IO) {
        val app=ApplicationProvider.getApplicationContext<HabitApplication>()
        val session=Api("http://10.0.2.2:8081").post<Credentials,Session>("/api/v1/auth/register",Credentials("${UUID.randomUUID()}@discovery.test","test-password-123"))
        app.preferences.automatic(false);app.preferences.save(session,"http://10.0.2.2:8081")
        val local=Repository(app,session,false);val id=UUID.randomUUID().toString()
        local.save(TaskContent("Автоподключение сохраняет очередь"),id)
        val key=app.preferences.storageKey()
        val url=ServerDiscovery(app).resolve(true)
        assertTrue(url.startsWith("https://"))
        app.preferences.save(session,url,key)
        val remote=Repository(app,session,false)
        assertNotNull(remote.dao.pending(id))
        remote.synchronize()
        assertNull(remote.dao.pending(id))
        assertEquals("Автоподключение сохраняет очередь",Api(url){session.token}.get<Item>("/api/v1/items/$id").content.title)
        local.database.close();remote.database.close()
    }
}
