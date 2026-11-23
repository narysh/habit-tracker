package dev.habittracker.backend

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AttachmentStorageTest {
    private val config = S3StorageConfig(
        endpoint = "https://account.r2.cloudflarestorage.com",
        bucket = "habit-attachments",
        accessKey = "synthetic-access-key",
        secretKey = "synthetic-secret-key",
    )
    private val key = "7b1b4e66-823c-4c85-9238-d606d1366dc9"

    @Test fun `S3 adapter writes reads deletes and reports bucket health through client boundary`() {
        val client = FakeS3ObjectClient()
        val storage = S3AttachmentStorage(config, client)
        val bytes = byteArrayOf(0, 1, 2, -1)

        storage.put(key, bytes)
        assertContentEquals(bytes, storage.get(key))
        assertEquals(listOf("put:habit-attachments:$key", "get:habit-attachments:$key"), client.calls.take(2))
        assertTrue(storage.check())
        assertTrue(storage.delete(key))
        assertNull(storage.get(key))
        assertTrue(client.calls.contains("delete:habit-attachments:$key"))
    }

    @Test fun `S3 adapter returns null for missing objects and false when storage is unhealthy or delete fails`() {
        val client = FakeS3ObjectClient().apply {
            bucketHealthy = false
            deleteFails = true
        }
        val storage = S3AttachmentStorage(config, client)

        assertNull(storage.get(key))
        assertFalse(storage.check())
        assertFalse(storage.delete(key))
    }

    @Test fun `storage config rejects invalid endpoints and incomplete credentials`() {
        assertFailsWith<IllegalArgumentException> {
            config.copy(endpoint = "https://user:password@example.test")
        }
        assertFailsWith<IllegalArgumentException> {
            config.copy(bucket = "bad_bucket")
        }

        val base = mapOf("MCP_CLIENT_SECRET" to "synthetic-secret-".repeat(3))
        val partial = base + mapOf(
            "DB_PASSWORD" to "test-db-password",
            "S3_ENDPOINT" to config.endpoint,
            "S3_BUCKET" to config.bucket,
            "S3_ACCESS_KEY" to config.accessKey,
        )
        val error = assertFailsWith<IllegalArgumentException> { Settings.fromEnvironment(partial) }
        assertEquals("S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY, and S3_SECRET_KEY must be configured together", error.message)
    }

    @Test fun `environment selects S3 only with a complete config and defaults its region to auto`() {
        val base = mapOf(
            "MCP_CLIENT_SECRET" to "synthetic-secret-".repeat(3),
            "DB_PASSWORD" to "test-db-password",
        )
        val localRoot = java.nio.file.Files.createTempDirectory("habit-storage-settings-")
        try {
            val local = Settings.fromEnvironment(base + ("FILES_DIR" to localRoot.toString()))
            assertNull(local.s3Storage)
            val localStorage = local.createAttachmentStorage()
            assertTrue(localStorage is LocalFilesystemAttachmentStorage)
            localStorage.close()
        } finally {
            java.nio.file.Files.deleteIfExists(localRoot)
        }

        val remote = Settings.fromEnvironment(base + mapOf(
            "S3_ENDPOINT" to config.endpoint,
            "S3_BUCKET" to config.bucket,
            "S3_ACCESS_KEY" to config.accessKey,
            "S3_SECRET_KEY" to config.secretKey,
        ))
        assertEquals("auto", remote.s3Storage?.region)
        val remoteStorage = remote.createAttachmentStorage()
        assertTrue(remoteStorage is S3AttachmentStorage)
        remoteStorage.close()
    }

    @Test fun `trusted client ip header is explicit and restricted to known proxy headers`() {
        val base = mapOf("MCP_CLIENT_SECRET" to "synthetic-secret-".repeat(3), "DB_PASSWORD" to "test-db-password")
        assertNull(Settings.fromEnvironment(base).trustedClientIpHeader)
        assertEquals("CF-Connecting-IP", Settings.fromEnvironment(base + ("TRUSTED_CLIENT_IP_HEADER" to "CF-Connecting-IP")).trustedClientIpHeader)
        assertFailsWith<IllegalArgumentException> {
            Settings.fromEnvironment(base + ("TRUSTED_CLIENT_IP_HEADER" to "X-Real-IP, X-Forwarded-For"))
        }
    }

    @Test fun `hosted mode requires HTTPS object storage public origin and stable connection key material`() {
        val hosted = mapOf(
            "MCP_CLIENT_SECRET" to "synthetic-secret-".repeat(3),
            "DB_PASSWORD" to "test-db-password",
            "HOSTED_MODE" to "true",
            "PUBLIC_URL" to "https://habit-tracker.example.test",
            "ANDROID_PUBLIC_URL" to "https://habit-tracker.example.test",
            "S3_ENDPOINT" to config.endpoint,
            "S3_BUCKET" to config.bucket,
            "S3_ACCESS_KEY" to config.accessKey,
            "S3_SECRET_KEY" to config.secretKey,
            "CONNECTION_PRIVATE_KEY_BASE64" to "c3ludGhldGljLXByaXZhdGU=",
            "CONNECTION_PUBLIC_KEY_BASE64" to "c3ludGhldGljLXB1YmxpYw==",
        )
        assertTrue(Settings.fromEnvironment(hosted).hostedMode)
        assertFailsWith<IllegalArgumentException> {
            Settings.fromEnvironment(hosted - "CONNECTION_PUBLIC_KEY_BASE64")
        }
        assertFailsWith<IllegalArgumentException> {
            Settings.fromEnvironment(hosted + ("S3_ENDPOINT" to "http://storage.example.test"))
        }
    }

    private class FakeS3ObjectClient : S3ObjectClient {
        val calls = mutableListOf<String>()
        private val objects = mutableMapOf<Pair<String, String>, ByteArray>()
        var bucketHealthy = true
        var deleteFails = false

        override fun put(bucket: String, key: String, bytes: ByteArray) {
            calls += "put:$bucket:$key"
            objects[bucket to key] = bytes.copyOf()
        }

        override fun get(bucket: String, key: String): ByteArray? {
            calls += "get:$bucket:$key"
            return objects[bucket to key]?.copyOf()
        }

        override fun delete(bucket: String, key: String) {
            calls += "delete:$bucket:$key"
            if (deleteFails) error("synthetic delete failure")
            objects.remove(bucket to key)
        }

        override fun checkBucket(bucket: String): Boolean {
            calls += "check:$bucket"
            return bucketHealthy
        }
    }
}
