package dev.habittracker.probe

import java.net.URI
import java.nio.file.Path

/** Isolated, single-user integration probe. Never used as production account authentication. */
data class Config(
    val publicUrl: String,
    val clientId: String,
    val clientSecret: String,
    val pairingSecret: String,
    val redirects: Set<String>,
    val dataFile: Path,
    val port: Int = 8080,
) {
    init {
        val uri = URI(publicUrl)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host == "127.0.0.1"))
        require(uri.rawPath.isNullOrEmpty() && uri.query == null && uri.fragment == null && uri.userInfo == null)
        require(clientId.isNotBlank() && clientSecret.length >= 32 && pairingSecret.length >= 32)
        require(redirects.isNotEmpty())
        redirects.forEach {
            val redirect = URI(it)
            require(redirect.userInfo == null && redirect.fragment == null)
            // Native OAuth client callback (RFC 8252). Exact URI allowlisting still applies.
            require(redirect.scheme == "https" || (redirect.scheme == "http" &&
                redirect.host == "127.0.0.1" && redirect.port in 1..65535 && redirect.path == "/callback"))
        }
    }
    val resource get() = "$publicUrl/mcp"
    companion object {
        fun fromEnvironment(): Config {
            fun env(key: String) = requireNotNull(System.getenv(key)) { "Set $key (see README)" }
            return Config(
                publicUrl = env("PUBLIC_URL").trimEnd('/'),
                clientId = env("OAUTH_CLIENT_ID"),
                clientSecret = env("OAUTH_CLIENT_SECRET"),
                pairingSecret = env("PAIRING_SECRET"),
                redirects = env("OAUTH_REDIRECT_URIS").split(',').map { it.trim() }.toSet(),
                dataFile = Path.of(System.getenv("PROBE_DATA_FILE") ?: ".local/probe-tasks.json"),
                port = System.getenv("PORT")?.toInt() ?: 8080,
            )
        }
    }
}
