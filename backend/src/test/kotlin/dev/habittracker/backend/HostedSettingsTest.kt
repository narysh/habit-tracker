package dev.habittracker.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HostedSettingsTest {
    private val base = mapOf("MCP_CLIENT_SECRET" to "synthetic-secret-".repeat(3))

    @Test fun `Render supplies database credentials and public OAuth origin`() {
        val settings = Settings.fromEnvironment(base + mapOf(
            "DATABASE_URL" to "postgresql://habit_user:p%40ss%3Aword+suffix@dpg-synthetic:5432/habit?sslmode=require",
            "RENDER_EXTERNAL_URL" to "https://habit-pilot.onrender.com/",
            "PUBLIC_CHATGPT_PLUGIN" to "true",
            "MCP_REDIRECT_URIS" to "https://chatgpt.com/connector_platform_oauth_redirect",
            "PORT" to "10000"
        ))
        assertEquals("jdbc:postgresql://dpg-synthetic:5432/habit?sslmode=require", settings.jdbcUrl)
        assertEquals("habit_user", settings.dbUser)
        assertEquals("p@ss:word+suffix", settings.dbPassword)
        assertEquals("https://habit-pilot.onrender.com/mcp", settings.resource)
        assertEquals(10000, settings.port)
        assertTrue(settings.publicPlugin)
    }

    @Test fun `invalid database URI does not leak credentials and explicit JDBC still wins`() {
        val invalid = "postgresql://habit_user:super-secret-invalid%@dpg-synthetic/habit"
        val error = assertFailsWith<IllegalArgumentException> {
            Settings.fromEnvironment(base + ("DATABASE_URL" to invalid))
        }
        assertEquals("Invalid DATABASE_URL", error.message)
        val settings = Settings.fromEnvironment(base + mapOf(
            "DATABASE_URL" to invalid, "JDBC_URL" to "jdbc:postgresql://localhost/habit", "DB_PASSWORD" to "local-secret"
        ))
        assertEquals("jdbc:postgresql://localhost/habit", settings.jdbcUrl)
        assertEquals("local-secret", settings.dbPassword)
    }
}
