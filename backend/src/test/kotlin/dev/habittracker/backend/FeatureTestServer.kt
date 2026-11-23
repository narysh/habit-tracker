package dev.habittracker.backend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.nio.file.Path

/** Disposable synthetic-data server, loopback only; never reads the user's backend.env or database. */
fun main(){
    val pg=EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses","127.0.0.1").start()
    val files=Files.createTempDirectory("habit-feature-files")
    val baseline = Settings(publicUrl="http://127.0.0.1:18082",jdbcUrl=pg.getJdbcUrl("postgres","postgres"),dbUser="postgres",dbPassword="synthetic-test-only",clientSecret="feature-test-only-".repeat(3),files=files,port=18082)
    val settings = if (System.getenv("FEATURE_TEST_PLUGIN") == "true") baseline.copy(
        publicUrl = "https://127.0.0.1:18443",
        clientId = "habit-tracker-chatgpt",
        redirects = setOf("https://chatgpt.com/connector_platform_oauth_redirect"),
        publicPlugin = true,
        openAiDomainChallenge = "synthetic-domain-challenge"
    ) else baseline
    val store=Store(settings.jdbcUrl,settings.dbUser,"")
    Files.writeString(Path.of(".local/feature-test-server.pid"),ProcessHandle.current().pid().toString())
    Runtime.getRuntime().addShutdownHook(Thread {store.close();pg.close()})
    embeddedServer(Netty,host="127.0.0.1",port=18082){backendModule(settings,store,SocialLogin(store,emptyMap()),LoginThrottle(maxAttemptsPerMinute=500))}.start(wait=true)
}
