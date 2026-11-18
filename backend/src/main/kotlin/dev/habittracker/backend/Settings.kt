package dev.habittracker.backend

import java.net.URI
import java.nio.file.Path

data class Settings(
    val publicUrl: String = "http://127.0.0.1:8081",
    val jdbcUrl: String = "jdbc:postgresql://127.0.0.1:5432/habit_tracker",
    val dbUser: String = "habit_tracker",
    val dbPassword: String,
    val clientId: String = "habit-tracker",
    val clientSecret: String,
    val redirects: Set<String> = setOf("http://127.0.0.1:45838/callback"),
    val files: Path = Path.of(".local/backend-files"),
    val port: Int = 8081,
    val publicPlugin: Boolean = false,
    val openAiDomainChallenge: String? = null,
) {
    val resource get() = "$publicUrl/mcp"
    init {
        val uri = URI(publicUrl)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host == "127.0.0.1"))
        require(uri.path.isNullOrEmpty() && uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(clientSecret.length >= 32 && dbPassword.isNotBlank())
        if (publicPlugin) {
            require(uri.scheme == "https" && uri.host != null && redirects.isNotEmpty())
            require(redirects.all { URI(it).let { callback -> callback.scheme == "https" && callback.host == "chatgpt.com" && callback.query == null } })
        }
        require(openAiDomainChallenge == null || (openAiDomainChallenge.length in 1..2048 && openAiDomainChallenge.none(Char::isWhitespace)))
        redirects.forEach {
            val callback = URI(it)
            require(callback.fragment == null && callback.userInfo == null)
            require(callback.scheme == "https" || (callback.scheme == "http" && callback.host == "127.0.0.1" && callback.port in 1..65535 && callback.path == "/callback"))
        }
    }
    companion object {
        fun environment() = Settings(
            publicUrl = (System.getenv("PUBLIC_URL") ?: "http://127.0.0.1:8081").trimEnd('/'),
            jdbcUrl = System.getenv("JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:5432/habit_tracker",
            dbUser = System.getenv("DB_USER") ?: "habit_tracker",
            dbPassword = requireNotNull(System.getenv("DB_PASSWORD")) { "DB_PASSWORD is required" },
            clientId = System.getenv("MCP_CLIENT_ID") ?: "habit-tracker",
            clientSecret = requireNotNull(System.getenv("MCP_CLIENT_SECRET")) { "MCP_CLIENT_SECRET is required" },
            redirects = (System.getenv("MCP_REDIRECT_URIS") ?: "http://127.0.0.1:45838/callback").split(',').map(String::trim).toSet(),
            files = Path.of(System.getenv("FILES_DIR") ?: ".local/backend-files"),
            port = System.getenv("PORT")?.toInt() ?: 8081,
            publicPlugin = System.getenv("PUBLIC_CHATGPT_PLUGIN")?.toBooleanStrict() ?: false,
            openAiDomainChallenge = System.getenv("OPENAI_APPS_CHALLENGE")?.takeIf(String::isNotEmpty),
        )
    }
}
