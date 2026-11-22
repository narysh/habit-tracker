package dev.habittracker.backend

import java.net.URI
import java.net.URLDecoder
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
    val trustReverseProxy: Boolean = false,
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
        fun environment() = fromEnvironment(System.getenv())

        internal fun fromEnvironment(env: Map<String, String>): Settings {
            val explicitJdbc = env["JDBC_URL"]
            val hosted = if (explicitJdbc == null) env["DATABASE_URL"]?.let(::hostedPostgres) else null
            return Settings(
                publicUrl = (env["PUBLIC_URL"] ?: env["RENDER_EXTERNAL_URL"] ?: "http://127.0.0.1:8081").trimEnd('/'),
                jdbcUrl = explicitJdbc ?: hosted?.jdbcUrl ?: "jdbc:postgresql://127.0.0.1:5432/habit_tracker",
                dbUser = env["DB_USER"] ?: hosted?.user ?: "habit_tracker",
                dbPassword = requireNotNull(env["DB_PASSWORD"] ?: hosted?.password) { "DB_PASSWORD or DATABASE_URL is required" },
                clientId = env["MCP_CLIENT_ID"] ?: "habit-tracker",
                clientSecret = requireNotNull(env["MCP_CLIENT_SECRET"]) { "MCP_CLIENT_SECRET is required" },
                redirects = (env["MCP_REDIRECT_URIS"] ?: "http://127.0.0.1:45838/callback").split(',').map(String::trim).toSet(),
                files = Path.of(env["FILES_DIR"] ?: ".local/backend-files"),
                port = env["PORT"]?.toInt() ?: 8081,
                publicPlugin = env["PUBLIC_CHATGPT_PLUGIN"]?.toBooleanStrict() ?: false,
                openAiDomainChallenge = env["OPENAI_APPS_CHALLENGE"]?.takeIf(String::isNotEmpty),
                trustReverseProxy = env["TRUST_REVERSE_PROXY"]?.toBooleanStrict() ?: false,
            )
        }

        private fun hostedPostgres(value: String): HostedPostgres {
            val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Invalid DATABASE_URL") }
            require(uri.scheme in setOf("postgresql", "postgres") && uri.host != null && uri.rawFragment == null && uri.port in -1..65535) { "Invalid DATABASE_URL" }
            val credentials = uri.rawUserInfo.orEmpty().split(':', limit = 2)
            require(credentials.size == 2 && credentials.all(String::isNotEmpty)) { "Database URL requires a user and password" }
            require(uri.rawPath.length > 1 && uri.rawPath.lastIndexOf('/') == 0) { "Database URL requires a database name" }
            val host = uri.host + if (uri.port == -1) "" else ":${uri.port}"
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            fun decode(field: String) = URLDecoder.decode(field.replace("+", "%2B"), Charsets.UTF_8)
            return HostedPostgres("jdbc:postgresql://$host${uri.rawPath}$query", decode(credentials[0]), decode(credentials[1]))
        }
    }
}

private class HostedPostgres(val jdbcUrl: String, val user: String, val password: String)
