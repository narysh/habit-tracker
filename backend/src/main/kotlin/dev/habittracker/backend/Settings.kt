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
    val trustedClientIpHeader: String? = null,
    val s3Storage: S3StorageConfig? = null,
    val hostedMode: Boolean = false,
    val maintenanceIntervalSeconds: Long = if (hostedMode) 3_600 else 30,
    val androidPublicUrl: String? = null,
    val connectionKeyMaterial: ConnectionKeyMaterial? = null,
) {
    val resource get() = "$publicUrl/mcp"
    /** Derived from the existing stable server secret so local and hosted setups need no new setting. */
    val socialStateEncryptionKey: ByteArray get() = dev.habittracker.backend.socialStateEncryptionKey(clientSecret)
    fun createAttachmentStorage(): AttachmentStorage =
        s3Storage?.let { S3AttachmentStorage(it) } ?: LocalFilesystemAttachmentStorage(files)

    override fun toString(): String = "Settings(publicUrl=$publicUrl, jdbcUrl=$jdbcUrl, dbUser=$dbUser, dbPassword=[redacted], " +
        "clientId=$clientId, clientSecret=[redacted], redirects=$redirects, files=$files, port=$port, " +
        "publicPlugin=$publicPlugin, openAiDomainChallenge=[redacted], trustedClientIpHeader=$trustedClientIpHeader, " +
        "s3Storage=$s3Storage, hostedMode=$hostedMode, maintenanceIntervalSeconds=$maintenanceIntervalSeconds, " +
        "androidPublicUrl=$androidPublicUrl, connectionKeyMaterial=$connectionKeyMaterial)"

    init {
        val uri = URI(publicUrl)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host == "127.0.0.1"))
        require(uri.path.isNullOrEmpty() && uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(clientSecret.length >= 32 && dbPassword.isNotBlank())
        require(maintenanceIntervalSeconds in (if (hostedMode) 300L..86_400L else 10L..86_400L)) {
            "MAINTENANCE_INTERVAL_SECONDS must be at least 300 in hosted mode and no more than 86400"
        }
        if (publicPlugin) {
            require(uri.scheme == "https" && uri.host != null && redirects.isNotEmpty())
            require(redirects.all { URI(it).let { callback -> callback.scheme == "https" && callback.host == "chatgpt.com" && callback.query == null } })
        }
        require(openAiDomainChallenge == null || (openAiDomainChallenge.length in 1..2048 && openAiDomainChallenge.none(Char::isWhitespace)))
        androidPublicUrl?.let { value ->
            val androidUri = URI(value)
            require(androidUri.scheme == "https" && !androidUri.host.isNullOrBlank() && androidUri.userInfo == null &&
                androidUri.query == null && androidUri.fragment == null && androidUri.path.isNullOrEmpty()) {
                "ANDROID_PUBLIC_URL must be an HTTPS origin without a path or credentials"
            }
        }
        if (hostedMode) {
            require(s3Storage != null) { "Hosted mode requires private S3-compatible attachment storage" }
            require(connectionKeyMaterial != null) { "Hosted mode requires a stable connection-signing key pair" }
            require(androidPublicUrl != null) { "Hosted mode requires ANDROID_PUBLIC_URL" }
            require(URI(s3Storage.endpoint).scheme == "https") { "Hosted S3_ENDPOINT must use HTTPS" }
        }
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
            val hostedMode = env["HOSTED_MODE"]?.toBooleanStrict() ?: false
            val s3Keys = listOf("S3_ENDPOINT", "S3_BUCKET", "S3_ACCESS_KEY", "S3_SECRET_KEY")
            val configuredS3 = s3Keys.map { key -> key to env[key]?.takeIf(String::isNotBlank) }.toMap()
            val anyS3Value = configuredS3.values.any { it != null } || env["S3_REGION"]?.isNotBlank() == true
            val s3 = if (!anyS3Value) null else {
                require(configuredS3.values.all { it != null }) {
                    "S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY, and S3_SECRET_KEY must be configured together"
                }
                S3StorageConfig(
                    endpoint = requireNotNull(configuredS3.getValue("S3_ENDPOINT")),
                    bucket = requireNotNull(configuredS3.getValue("S3_BUCKET")),
                    accessKey = requireNotNull(configuredS3.getValue("S3_ACCESS_KEY")),
                    secretKey = requireNotNull(configuredS3.getValue("S3_SECRET_KEY")),
                    region = env["S3_REGION"]?.takeIf(String::isNotBlank) ?: "auto",
                )
            }
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
                trustedClientIpHeader = env["TRUSTED_CLIENT_IP_HEADER"]?.takeIf(String::isNotBlank)?.also {
                    require(it in setOf("X-Real-IP", "CF-Connecting-IP", "X-Envoy-External-Address")) {
                        "TRUSTED_CLIENT_IP_HEADER must be X-Real-IP, CF-Connecting-IP, or X-Envoy-External-Address"
                    }
                },
                s3Storage = s3,
                hostedMode = hostedMode,
                maintenanceIntervalSeconds = env["MAINTENANCE_INTERVAL_SECONDS"]?.let {
                    it.toLongOrNull() ?: throw IllegalArgumentException("MAINTENANCE_INTERVAL_SECONDS must be an integer")
                } ?: if (hostedMode) 3_600 else 30,
                androidPublicUrl = (env["ANDROID_PUBLIC_URL"] ?: env["RENDER_EXTERNAL_URL"])?.trimEnd('/')?.takeIf(String::isNotEmpty),
                connectionKeyMaterial = connectionKeyMaterial(env),
            )
        }

        private fun connectionKeyMaterial(env: Map<String, String>): ConnectionKeyMaterial? {
            val privateKey = env["CONNECTION_PRIVATE_KEY_BASE64"]?.takeIf(String::isNotBlank)
            val publicKey = env["CONNECTION_PUBLIC_KEY_BASE64"]?.takeIf(String::isNotBlank)
            require((privateKey == null) == (publicKey == null)) {
                "CONNECTION_PRIVATE_KEY_BASE64 and CONNECTION_PUBLIC_KEY_BASE64 must be configured together"
            }
            return if (privateKey == null) null else ConnectionKeyMaterial(privateKey, requireNotNull(publicKey))
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
