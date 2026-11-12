package dev.habittracker.backend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.nio.file.Path

/** Explicit local developer launcher. Production uses the normal MainKt and external PostgreSQL. */
fun main() {
    Files.writeString(Path.of(".local/backend.pid"),ProcessHandle.current().pid().toString())
    val root=Path.of(".local/postgres").toAbsolutePath()
    val password=requireNotNull(System.getenv("DB_PASSWORD")) { "Use scripts/Start-Backend.ps1" }
    require(Regex("[A-Za-z0-9_-]{32,}").matches(password))
    Files.createDirectories(root)
    val postgres=EmbeddedPostgres.builder().setDataDirectory(root.toFile()).setCleanDataDirectory(false).setPort(5433).setConnectConfig("password",password)
        .setServerConfig("listen_addresses","127.0.0.1").setServerConfig("fsync","on")
        .setServerConfig("synchronous_commit","on").setServerConfig("full_page_writes","on").setServerConfig("max_connections","40").start()
    // Bootstrap the private development database, then require password auth over loopback.
    val admin=org.postgresql.ds.PGSimpleDataSource().apply { setURL(postgres.getJdbcUrl("postgres","postgres")); this.password=password }
    admin.connection.use { c -> c.createStatement().use { s ->
        s.execute("ALTER ROLE postgres PASSWORD '$password'")
        val hba=root.resolve("pg_hba.conf")
        Files.writeString(hba,Files.readString(hba).replace(Regex("(?m)(\\s)trust(\\s*(?:#.*)?)$"),"$1scram-sha-256$2"))
        s.execute("SELECT pg_reload_conf()")
    } }
    val settings=Settings.environment().copy(jdbcUrl=postgres.getJdbcUrl("postgres","postgres"),dbUser="postgres",dbPassword=password)
    val store=Store(settings.jdbcUrl,settings.dbUser,settings.dbPassword)
    Runtime.getRuntime().addShutdownHook(Thread { store.close(); postgres.close() })
    embeddedServer(Netty,host="0.0.0.0",port=settings.port) { backendModule(settings,store) }.start(wait=true)
}
